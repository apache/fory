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

set -e
export ENABLE_FORY_DEBUG_OUTPUT=0
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Output directory for results
OUTPUT_DIR="$SCRIPT_DIR/results"
mkdir -p "$OUTPUT_DIR"

# Default values
DATA_FILTER=""
SERIALIZER_FILTER=""
CUSTOM_FILTER=""
GENERATE_REPORT=true

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Build and run Rust benchmarks (criterion)"
    echo ""
    echo "Options:"
    echo "  --data <type>       Filter by data type: simple_struct, simple_list, simple_map,"
    echo "                      person, company, ecommerce_data, system_data, all"
    echo "  --serializer <name> Filter by serializer: fory, json, protobuf"
    echo "  --filter <regex>    Custom criterion filter regex (overrides --data/--serializer)"
    echo "  --no-report         Skip report generation"
    echo "  -h, --help          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                               # Default: simple_struct/ecommerce_data/system_data"
    echo "  $0 --data simple_struct          # Only simple_struct benchmarks"
    echo "  $0 --data ecommerce_data,system_data"
    echo "  $0 --serializer fory             # Only Fory benchmarks (all data types)"
    echo "  $0 --data simple_struct --serializer json"
    echo "  $0 --filter 'simple_struct|person'"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
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
    if [[ -z "$input" ]]; then
        echo ""
        return
    fi
    if [[ "$input" == "all" ]]; then
        echo ""
        return
    fi
    local result=()
    IFS=',' read -ra parts <<< "$input"
    for part in "${parts[@]}"; do
        case "$part" in
            simple_struct|struct) result+=("simple_struct") ;;
            simple_list|list) result+=("simple_list") ;;
            simple_map|map) result+=("simple_map") ;;
            person) result+=("person") ;;
            company) result+=("company") ;;
            ecommerce_data|ecommerce|ecommerce-data) result+=("ecommerce_data") ;;
            system_data|system|system-data) result+=("system_data") ;;
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
    if [[ -n "$DATA_FILTER" ]]; then
        data_regex="$(normalize_data_filter "$DATA_FILTER")"
    else
        data_regex="simple_struct|ecommerce_data|system_data"
    fi

    if [[ -n "$SERIALIZER_FILTER" ]]; then
        case "$SERIALIZER_FILTER" in
            fory|json|protobuf)
                ;;
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

FILTER_REGEX="$(build_filter)"

LOG_FILE="$OUTPUT_DIR/cargo_bench.log"
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
echo ""

echo "============================================"
echo "Running benchmarks..."
echo "============================================"
echo "Running: ${BENCH_CMD[*]}"
echo ""
"${BENCH_CMD[@]}" 2>&1 | tee "$LOG_FILE"

if $GENERATE_REPORT; then
    echo ""
    echo "============================================"
    echo "Generating report..."
    echo "============================================"
    if command -v python3 &> /dev/null; then
        python3 "$SCRIPT_DIR/benchmark_report.py" --log-file "$LOG_FILE" --output-dir "$OUTPUT_DIR" || \
            echo "Warning: Report generation failed. Install matplotlib and numpy for reports."
    elif command -v python &> /dev/null; then
        python "$SCRIPT_DIR/benchmark_report.py" --log-file "$LOG_FILE" --output-dir "$OUTPUT_DIR" || \
            echo "Warning: Report generation failed. Install matplotlib and numpy for reports."
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
if $GENERATE_REPORT; then
    echo "  - REPORT.md and plots (if dependencies are available)"
fi
