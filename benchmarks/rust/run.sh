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

OUTPUT_DIR="$SCRIPT_DIR/results"
LOG_FILE="$OUTPUT_DIR/cargo_bench.log"
SIZE_FILE="$OUTPUT_DIR/serialized_sizes.txt"
GENERATE_REPORT=true
DATA_FILTER=""
SERIALIZER_FILTER=""
CUSTOM_FILTER=""

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Build and run Rust shared benchmark cases from benchmarks/proto/bench.proto"
    echo ""
    echo "Options:"
    echo "  --data <type>       Filter by data type: struct, sample, mediacontent,"
    echo "                      structlist, samplelist, mediacontentlist, all"
    echo "  --serializer <name> Filter by serializer: fory, protobuf"
    echo "  --filter <regex>    Custom criterion filter regex (overrides --data/--serializer)"
    echo "  --no-report         Skip report generation"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0"
    echo "  $0 --data struct"
    echo "  $0 --serializer protobuf"
    echo "  $0 --data sample,mediacontent --serializer protobuf"
    echo "  $0 --filter 'struct|sample'"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --data)
            DATA_FILTER="$2"
            shift 2
            ;;
        --serializer)
            SERIALIZER_FILTER="$2"
            shift 2
            ;;
        --filter)
            CUSTOM_FILTER="$2"
            shift 2
            ;;
        --no-report)
            GENERATE_REPORT=false
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1"
            usage
            ;;
    esac
done

normalize_data_filter() {
    local input="$1"
    if [[ -z "$input" || "$input" == "all" ]]; then
        echo ""
        return
    fi

    local result=()
    IFS=',' read -ra parts <<< "$input"
    for part in "${parts[@]}"; do
        case "$part" in
            struct) result+=("struct") ;;
            sample) result+=("sample") ;;
            mediacontent|media-content|media_content) result+=("mediacontent") ;;
            structlist|struct-list|struct_list) result+=("structlist") ;;
            samplelist|sample-list|sample_list) result+=("samplelist") ;;
            mediacontentlist|media-content-list|media_content_list)
                result+=("mediacontentlist")
                ;;
            *)
                echo "Unknown data type: $part"
                exit 1
                ;;
        esac
    done

    local joined
    joined="$(IFS='|'; echo "${result[*]}")"
    echo "$joined"
}

build_filter() {
    if [[ -n "$CUSTOM_FILTER" ]]; then
        echo "$CUSTOM_FILTER"
        return
    fi

    local data_regex
    data_regex="$(normalize_data_filter "$DATA_FILTER")"

    if [[ -n "$SERIALIZER_FILTER" ]]; then
        case "$SERIALIZER_FILTER" in
            fory|protobuf) ;;
            *)
                echo "Unknown serializer: $SERIALIZER_FILTER"
                exit 1
                ;;
        esac
    fi

    if [[ -n "$data_regex" && -n "$SERIALIZER_FILTER" ]]; then
        echo "(${data_regex})/${SERIALIZER_FILTER}_"
    elif [[ -n "$data_regex" ]]; then
        echo "(${data_regex})"
    elif [[ -n "$SERIALIZER_FILTER" ]]; then
        echo "${SERIALIZER_FILTER}_"
    else
        echo ""
    fi
}

mkdir -p "$OUTPUT_DIR"
FILTER_REGEX="$(build_filter)"

BENCH_CMD=(cargo bench --bench serialization_bench)
if [[ -n "$FILTER_REGEX" ]]; then
    BENCH_CMD+=(-- "$FILTER_REGEX")
fi

echo "============================================"
echo "Fory Rust Benchmark"
echo "============================================"
if [[ -n "$FILTER_REGEX" ]]; then
    echo "Filter: $FILTER_REGEX"
else
    echo "Filter: (all benchmarks)"
fi
echo "Log: $LOG_FILE"
echo "Serialized sizes: $SIZE_FILE"
echo ""

echo "============================================"
echo "Running benchmarks..."
echo "============================================"
echo "Running: ${BENCH_CMD[*]}"
echo ""
"${BENCH_CMD[@]}" 2>&1 | tee "$LOG_FILE"

echo ""
echo "============================================"
echo "Collecting serialized sizes..."
echo "============================================"
cargo run --release --bin fory_profiler -- --print-all-serialized-sizes | tee "$SIZE_FILE"

if $GENERATE_REPORT; then
    echo ""
    echo "============================================"
    echo "Generating report..."
    echo "============================================"
    if command -v python3 >/dev/null 2>&1; then
        python3 "$SCRIPT_DIR/benchmark_report.py" \
            --log-file "$LOG_FILE" \
            --size-file "$SIZE_FILE" \
            --output-dir "$OUTPUT_DIR" || \
            echo "Warning: report generation failed. Install matplotlib, numpy, and psutil."
    elif command -v python >/dev/null 2>&1; then
        python "$SCRIPT_DIR/benchmark_report.py" \
            --log-file "$LOG_FILE" \
            --size-file "$SIZE_FILE" \
            --output-dir "$OUTPUT_DIR" || \
            echo "Warning: report generation failed. Install matplotlib, numpy, and psutil."
    else
        echo "Warning: Python not found. Skipping report generation."
    fi
fi

echo ""
echo "============================================"
echo "Benchmark complete!"
echo "============================================"
echo "Results saved to: $OUTPUT_DIR/"
echo "  - cargo_bench.log"
echo "  - serialized_sizes.txt"
if $GENERATE_REPORT; then
    echo "  - README.md and plots"
fi
