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
DATA_TYPE=""
SERIALIZER=""
COUNT=5
BENCHTIME="1s"
GENERATE_REPORT=true

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --data)
            DATA_TYPE="$2"
            shift 2
            ;;
        --serializer)
            SERIALIZER="$2"
            shift 2
            ;;
        --count)
            COUNT="$2"
            shift 2
            ;;
        --benchtime)
            BENCHTIME="$2"
            shift 2
            ;;
        --no-report)
            GENERATE_REPORT=false
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --data <type>       Filter by data type: struct, sample, mediacontent, structlist, samplelist, mediacontentlist"
            echo "  --serializer <name> Filter by serializer: fory, protobuf, msgpack"
            echo "  --count <n>         Number of benchmark runs (default: 5)"
            echo "  --benchtime <dur>   Time for each benchmark (default: 1s)"
            echo "  --no-report         Skip report generation"
            echo "  -h, --help          Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                           # Run all benchmarks"
            echo "  $0 --data struct             # Only Struct benchmarks"
            echo "  $0 --serializer fory         # Only Fory benchmarks"
            echo "  $0 --data sample --serializer protobuf"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Build benchmark filter
FILTER=""
if [[ -n "$DATA_TYPE" ]]; then
    case "$DATA_TYPE" in
        struct)
            FILTER="Struct"
            ;;
        structlist|struct-list)
            FILTER="StructList"
            ;;
        sample)
            FILTER="Sample"
            ;;
        samplelist|sample-list)
            FILTER="SampleList"
            ;;
        mediacontent|media)
            FILTER="MediaContent"
            ;;
        mediacontentlist|medialist|media-list)
            FILTER="MediaContentList"
            ;;
        *)
            echo "Unknown data type: $DATA_TYPE"
            exit 1
            ;;
    esac
fi

if [[ -n "$SERIALIZER" ]]; then
    case "$SERIALIZER" in
        fory)
            SERIALIZER_FILTER="Fory"
            ;;
        protobuf|pb)
            SERIALIZER_FILTER="Protobuf"
            ;;
        msgpack|mp)
            SERIALIZER_FILTER="Msgpack"
            ;;
        *)
            echo "Unknown serializer: $SERIALIZER"
            exit 1
            ;;
    esac
    if [[ -n "$FILTER" ]]; then
        FILTER="${SERIALIZER_FILTER}_${FILTER}"
    else
        FILTER="${SERIALIZER_FILTER}"
    fi
fi

# Generate protobuf code if needed
echo "============================================"
echo "Generating protobuf code..."
echo "============================================"

PROTO_DIR="$SCRIPT_DIR/../proto"
PROTO_OUT_DIR="$SCRIPT_DIR/proto"
mkdir -p "$PROTO_OUT_DIR"

# Check if protoc is installed
if ! command -v protoc &> /dev/null; then
    echo "Error: protoc is not installed. Please install Protocol Buffers compiler."
    echo "  macOS: brew install protobuf"
    echo "  Ubuntu: apt-get install protobuf-compiler"
    exit 1
fi

# Add Go bin to PATH (where protoc-gen-go is installed)
export PATH="$PATH:$(go env GOPATH)/bin"

# Check if protoc-gen-go is installed
if ! command -v protoc-gen-go &> /dev/null; then
    echo "Installing protoc-gen-go..."
    go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
fi

# Generate Go code from proto
protoc --proto_path="$PROTO_DIR" \
    --go_out="$PROTO_OUT_DIR" \
    --go_opt=paths=source_relative \
    --go_opt=Mbench.proto=github.com/apache/fory/benchmarks/go/proto \
    "$PROTO_DIR/bench.proto"

echo "Protobuf code generated in $PROTO_OUT_DIR"

# Download dependencies
echo ""
echo "============================================"
echo "Downloading dependencies..."
echo "============================================"
go mod tidy

# Run benchmarks
echo ""
echo "============================================"
echo "Running benchmarks..."
echo "============================================"

BENCH_ARGS="-bench=."
if [[ -n "$FILTER" ]]; then
    BENCH_ARGS="-bench=$FILTER"
fi

# Run benchmarks with human-readable output (shown live) and save to file
# Also capture JSON format for report generation
echo "Running: go test $BENCH_ARGS -benchmem -count=$COUNT -benchtime=$BENCHTIME"
echo ""
go test $BENCH_ARGS -benchmem -count=$COUNT -benchtime=$BENCHTIME 2>&1 | tee "$OUTPUT_DIR/benchmark_results.txt"

# Also generate JSON format for programmatic analysis
echo ""
echo "Generating JSON output..."
go test $BENCH_ARGS -benchmem -count=1 -benchtime=$BENCHTIME -json > "$OUTPUT_DIR/benchmark_results.json" 2>&1 || true

# Print serialized sizes
echo ""
go test -run TestPrintSerializedSizes -v 2>&1 | \
    awk '
        /Serialized Sizes \(bytes\):/ { capture = 1 }
        capture { print }
        capture && /^=+$/ {
            separator_count++
            if (separator_count == 2) {
                exit
            }
        }
    ' | \
    tee "$OUTPUT_DIR/serialized_sizes.txt"

# Generate report
if $GENERATE_REPORT; then
    echo ""
    echo "============================================"
    echo "Generating report..."
    echo "============================================"

    # Check if Python is available
    if command -v python3 &> /dev/null; then
        python3 "$SCRIPT_DIR/benchmark_report.py" "$OUTPUT_DIR" || echo "Warning: Report generation failed. Install matplotlib and numpy for reports."
    elif command -v python &> /dev/null; then
        python "$SCRIPT_DIR/benchmark_report.py" "$OUTPUT_DIR" || echo "Warning: Report generation failed. Install matplotlib and numpy for reports."
    else
        echo "Warning: Python not found. Skipping report generation."
    fi
fi

echo ""
echo "============================================"
echo "Benchmark complete!"
echo "============================================"
echo "Results saved to: $OUTPUT_DIR/"
echo "  - benchmark_results.txt (human-readable)"
echo "  - benchmark_results.json (JSON format)"
if $GENERATE_REPORT; then
    echo "  - benchmark_report.md (report)"
    echo "  - benchmark_*.png (plots)"
fi
