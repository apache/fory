#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set -euo pipefail
export ENABLE_FORY_DEBUG_OUTPUT=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
DOCS_DIR="$SCRIPT_DIR/../../docs/benchmarks/object-serialization/xlang/csharp"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DATA=""
SERIALIZER=""
DURATION="3"
WARMUP="1"
OUTPUT_DIR=""
COPY_DOCS=true
EXTERNAL_EQUIVALENCE=false
ALLOCATION_ITERATIONS=""
BASELINE_WORKTREE=""

usage() {
    cat <<USAGE
Usage: $0 [OPTIONS]

Build and run C# benchmarks.

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                               Filter benchmark by data type
  --serializer <fory|protobuf|msgpack>
                               Filter benchmark by serializer
  --duration <seconds>         Measure duration per benchmark (default: 3)
  --warmup <seconds>           Warmup duration per benchmark (default: 1)
  --output-dir <dir>           Base directory for benchmark outputs
  --no-copy-docs               Skip copying report/plots into docs/benchmarks/object-serialization/xlang/csharp
  --external-equivalence       Run ordinary/external Fory equivalence cases
  --allocation-iterations <n>  Allocation operations per equivalence case (default: 100000)
  --baseline-worktree <dir>    Dedicated apache/main worktree for equivalence runs
  --help                       Show this help
USAGE
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --data)
            DATA="$2"
            shift 2
            ;;
        --serializer)
            SERIALIZER="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --warmup)
            WARMUP="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --no-copy-docs)
            COPY_DOCS=false
            shift
            ;;
        --external-equivalence)
            EXTERNAL_EQUIVALENCE=true
            shift
            ;;
        --allocation-iterations)
            ALLOCATION_ITERATIONS="$2"
            shift 2
            ;;
        --baseline-worktree)
            BASELINE_WORKTREE="$2"
            shift 2
            ;;
        --help|-h)
            usage
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            ;;
    esac
done

if [[ "$EXTERNAL_EQUIVALENCE" == true && -n "$SERIALIZER" ]]; then
    echo -e "${RED}--external-equivalence does not accept --serializer.${NC}"
    exit 1
fi

if [[ "$EXTERNAL_EQUIVALENCE" == false && -n "$ALLOCATION_ITERATIONS" ]]; then
    echo -e "${RED}--allocation-iterations requires --external-equivalence.${NC}"
    exit 1
fi

if [[ "$EXTERNAL_EQUIVALENCE" == false && -n "$BASELINE_WORKTREE" ]]; then
    echo -e "${RED}--baseline-worktree requires --external-equivalence.${NC}"
    exit 1
fi

if [[ "$EXTERNAL_EQUIVALENCE" == true && "${FORY_BENCH_SCHEMA_MISMATCH:-0}" == "1" ]]; then
    echo -e "${RED}--external-equivalence does not support schema-mismatch mode.${NC}"
    exit 1
fi

if [[ "$EXTERNAL_EQUIVALENCE" == true && -n "$DATA" ]]; then
    case "$DATA" in
        class-root|struct-root|holder-field|list-field|list-root|map-field|map-root)
            ;;
        *)
            echo -e "${RED}Unknown external-equivalence data lane: $DATA${NC}"
            exit 1
            ;;
    esac
fi

if [[ "$EXTERNAL_EQUIVALENCE" == false && "${FORY_BENCH_SCHEMA_MISMATCH:-0}" == "1" && "$SERIALIZER" != "fory" ]]; then
    echo -e "${RED}FORY_BENCH_SCHEMA_MISMATCH=1 supports only Fory benchmarks; rerun with --serializer fory.${NC}"
    exit 1
fi

if [[ -n "$OUTPUT_DIR" ]]; then
    BUILD_DIR="$OUTPUT_DIR/build"
    REPORT_DIR="$OUTPUT_DIR/report"
else
    BUILD_DIR="build"
    REPORT_DIR="report"
fi

echo -e "${GREEN}=== Fory C# Benchmark ===${NC}"
echo ""

if [[ "$EXTERNAL_EQUIVALENCE" == true ]]; then
    mkdir -p "$BUILD_DIR"
    RESULT_JSON="$BUILD_DIR/external_equivalence_results.json"
    SIDE_DIR="$BUILD_DIR/external_equivalence_sides"
    mkdir -p "$SIDE_DIR"
    ALLOCATION_ITERATIONS="${ALLOCATION_ITERATIONS:-100000}"
    if [[ ! "$ALLOCATION_ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
        echo -e "${RED}--allocation-iterations must be a positive integer.${NC}"
        exit 1
    fi

    if [[ -n "$DATA" ]]; then
        EXTERNAL_LANES=("$DATA")
    else
        EXTERNAL_LANES=(
            class-root
            struct-root
            holder-field
            list-field
            list-root
            map-field
            map-root
        )
    fi

    REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
    if [[ -z "$BASELINE_WORKTREE" ]]; then
        BASELINE_WORKTREE="$(dirname "$REPO_ROOT")/fory-benchmark-baseline"
    fi

    echo -e "${YELLOW}[1/5] Preparing fresh apache/main baseline...${NC}"
    git -C "$REPO_ROOT" fetch apache main
    APACHE_MAIN_COMMIT="$(
        git -C "$REPO_ROOT" rev-parse --verify FETCH_HEAD^{commit}
    )"

    if [[ -e "$BASELINE_WORKTREE" ]]; then
        BASELINE_REPO_ROOT="$(
            git -C "$BASELINE_WORKTREE" rev-parse --show-toplevel 2>/dev/null
        )" || {
            echo -e "${RED}Baseline path is not a git worktree: $BASELINE_WORKTREE${NC}"
            exit 1
        }
        CURRENT_COMMON_DIR="$(
            git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir
        )"
        BASELINE_COMMON_DIR="$(
            git -C "$BASELINE_WORKTREE" \
                rev-parse --path-format=absolute --git-common-dir
        )"
        if [[ "$BASELINE_COMMON_DIR" != "$CURRENT_COMMON_DIR" ]]; then
            echo -e "${RED}Baseline path is not a worktree of the current repository.${NC}"
            exit 1
        fi
        if [[ "$BASELINE_REPO_ROOT" == "$REPO_ROOT" ]]; then
            echo -e "${RED}Baseline worktree must differ from the current worktree.${NC}"
            exit 1
        fi
        if ! git -C "$BASELINE_WORKTREE" diff --quiet -- ||
            ! git -C "$BASELINE_WORKTREE" diff --cached --quiet --; then
            echo -e "${RED}Baseline worktree has tracked changes: $BASELINE_WORKTREE${NC}"
            exit 1
        fi
        if ! git -C "$BASELINE_WORKTREE" switch \
            --detach --no-overwrite-ignore "$APACHE_MAIN_COMMIT"; then
            echo -e "${RED}Could not switch the baseline worktree safely; its files were not reset or cleaned.${NC}"
            exit 1
        fi
    else
        git -C "$REPO_ROOT" worktree add \
            --detach "$BASELINE_WORKTREE" "$APACHE_MAIN_COMMIT"
    fi

    BASELINE_COMMIT="$(git -C "$BASELINE_WORKTREE" rev-parse HEAD)"
    if [[ "$BASELINE_COMMIT" != "$APACHE_MAIN_COMMIT" ]]; then
        echo -e "${RED}Baseline worktree is not at the fetched apache/main commit.${NC}"
        exit 1
    fi

    CURRENT_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal)" ]]; then
        CURRENT_DIRTY=true
    else
        CURRENT_DIRTY=false
    fi

    CURRENT_PROJECT="$SCRIPT_DIR/Fory.ExternalTypeBenchmark.csproj"
    BASELINE_PROJECT="$BASELINE_WORKTREE/benchmarks/csharp/Fory.ExternalTypeBenchmark.csproj"

    echo -e "${YELLOW}[2/5] Restoring baseline and current dependencies...${NC}"
    dotnet restore "$BASELINE_PROJECT" >/dev/null
    dotnet restore "$CURRENT_PROJECT" >/dev/null

    echo -e "${YELLOW}[3/5] Building baseline and current benchmarks...${NC}"
    dotnet build -c Release --no-restore "$BASELINE_PROJECT"
    dotnet build -c Release --no-restore "$CURRENT_PROJECT"

    echo -e "${YELLOW}[4/5] Running isolated adjacent triples...${NC}"
    MERGE_ARGS=(
        --output "$RESULT_JSON"
        --apache-main-commit "$APACHE_MAIN_COMMIT"
        --current-commit "$CURRENT_COMMIT"
        --current-dirty "$CURRENT_DIRTY"
    )
    for LANE in "${EXTERNAL_LANES[@]}"; do
        MERGE_ARGS+=(--lane "$LANE")
        WORKER_ARGS=(
            --data "$LANE"
            --duration "$DURATION"
            --warmup "$WARMUP"
            --allocation-iterations "$ALLOCATION_ITERATIONS"
        )

        for SAMPLE_INDEX in 1 2; do
            if [[ "$SAMPLE_INDEX" == 1 ]]; then
                SAMPLE_ORDER=(apache-main ordinary external)
            else
                SAMPLE_ORDER=(external ordinary apache-main)
            fi

            SIDE_INPUTS=()
            for ROLE in "${SAMPLE_ORDER[@]}"; do
                if [[ "$ROLE" == apache-main ]]; then
                    PROJECT="$BASELINE_PROJECT"
                    IMPLEMENTATION=ordinary
                else
                    PROJECT="$CURRENT_PROJECT"
                    IMPLEMENTATION="$ROLE"
                fi

                SIDE_JSON="$SIDE_DIR/${LANE}-sample-${SAMPLE_INDEX}-${ROLE}.json"
                SIDE_INPUTS+=("$SIDE_JSON")
                echo "Running $LANE sample $SAMPLE_INDEX ${SAMPLE_ORDER[*]}: $ROLE worker..."
                dotnet run -c Release --no-build \
                    --project "$PROJECT" -- \
                    "${WORKER_ARGS[@]}" \
                    --external-implementation "$IMPLEMENTATION" \
                    --output "$SIDE_JSON"
            done

            SAMPLE_ORDER_NAME="$(
                IFS=-
                echo "${SAMPLE_ORDER[*]}"
            )"
            MERGE_ARGS+=(
                --sample
                "$LANE"
                "$SAMPLE_INDEX"
                "$SAMPLE_ORDER_NAME"
                "${SIDE_INPUTS[0]}"
                "${SIDE_INPUTS[1]}"
                "${SIDE_INPUTS[2]}"
            )
        done
    done

    echo -e "${YELLOW}[5/5] Validating and merging results...${NC}"
    python3 external_equivalence_report.py "${MERGE_ARGS[@]}"
    echo ""
    echo -e "${GREEN}=== All done! ===${NC}"
    echo "Results written to: $RESULT_JSON"
    exit 0
fi

mkdir -p "$BUILD_DIR" "$REPORT_DIR"
RESULT_JSON="$BUILD_DIR/benchmark_results.json"
RUN_ARGS=(
    --output "$RESULT_JSON"
    --duration "$DURATION"
    --warmup "$WARMUP"
)

if [[ -n "$DATA" ]]; then
    RUN_ARGS+=(--data "$DATA")
fi

if [[ -n "$SERIALIZER" ]]; then
    RUN_ARGS+=(--serializer "$SERIALIZER")
fi

echo -e "${YELLOW}[1/3] Restoring dependencies...${NC}"
dotnet restore ./Fory.CSharpBenchmark.csproj >/dev/null

echo -e "${YELLOW}[2/3] Running benchmark...${NC}"
dotnet run -c Release --project ./Fory.CSharpBenchmark.csproj -- "${RUN_ARGS[@]}"

echo -e "${YELLOW}[3/3] Generating report...${NC}"
# Check for Python dependencies needed for plotting.
if ! python3 -c "import matplotlib" 2>/dev/null; then
    echo -e "${YELLOW}Installing required Python packages...${NC}"
    pip3 install matplotlib numpy psutil
fi

python3 benchmark_report.py --json-file "$RESULT_JSON" --output-dir "$REPORT_DIR"
if [[ "$COPY_DOCS" == true ]]; then
    mkdir -p "$DOCS_DIR"
    cp "$REPORT_DIR/README.md" "$DOCS_DIR/README.md"
    cp "$REPORT_DIR/throughput.png" "$DOCS_DIR/throughput.png"
    echo -e "${GREEN}Copied report and throughput plot to: ${DOCS_DIR}${NC}"
fi

echo ""
echo -e "${GREEN}=== All done! ===${NC}"
if [[ "$REPORT_DIR" = /* ]]; then
    REPORT_PATH="$REPORT_DIR/README.md"
    REPORT_PLOTS_DIR="$REPORT_DIR"
else
    REPORT_PATH="$SCRIPT_DIR/$REPORT_DIR/README.md"
    REPORT_PLOTS_DIR="$SCRIPT_DIR/$REPORT_DIR"
fi
echo "Report generated at: $REPORT_PATH"
echo "Plots saved in: $REPORT_PLOTS_DIR/"
if [[ "$COPY_DOCS" == true ]]; then
    echo "Docs sync: $DOCS_DIR"
fi
