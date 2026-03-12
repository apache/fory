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

PYTHON_BIN="${PYTHON_BIN:-python3}"
OUTPUT_DIR="$SCRIPT_DIR/results"
REPORT_DIR="$OUTPUT_DIR/report"
PROTO_DIR="$SCRIPT_DIR/proto"
DOCS_DIR="$SCRIPT_DIR/../../docs/benchmarks/python"

DATA=""
SERIALIZER=""
OPERATION="all"
WARMUP=3
ITERATIONS=15
REPEAT=5
NUMBER=1000
COPY_DOCS=true

usage() {
  cat <<'EOF'
Usage: ./run.sh [options]

Run Python comprehensive benchmarks for struct/sample/mediacontent and list variants.

Options:
  --data <type>          Filter by data type: struct,sample,mediacontent,structlist,samplelist,mediacontentlist
  --serializer <name>    Filter by serializer: fory,pickle,protobuf
  --operation <op>       all|serialize|deserialize (default: all)
  --warmup <n>           Warmup iterations (default: 3)
  --iterations <n>       Measurement iterations (default: 15)
  --repeat <n>           Repeat count per iteration (default: 5)
  --number <n>           Inner loop call count (default: 1000)
  --no-copy-docs         Skip copying report/plots into docs/benchmarks/python
  -h, --help             Show this help message

Examples:
  ./run.sh
  ./run.sh --data struct --serializer fory
  ./run.sh --operation serialize --iterations 30 --repeat 8
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --data)
      DATA="$2"
      shift 2
      ;;
    --serializer)
      SERIALIZER="$2"
      shift 2
      ;;
    --operation)
      OPERATION="$2"
      shift 2
      ;;
    --warmup)
      WARMUP="$2"
      shift 2
      ;;
    --iterations)
      ITERATIONS="$2"
      shift 2
      ;;
    --repeat)
      REPEAT="$2"
      shift 2
      ;;
    --number)
      NUMBER="$2"
      shift 2
      ;;
    --no-copy-docs)
      COPY_DOCS=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "Error: $PYTHON_BIN is not available"
  exit 1
fi

if ! command -v protoc >/dev/null 2>&1; then
  echo "Error: protoc is required. Install protobuf compiler first."
  echo "  macOS: brew install protobuf"
  exit 1
fi

echo "============================================"
echo "Python Comprehensive Benchmark"
echo "============================================"

echo "Checking runtime dependencies..."
if ! "$PYTHON_BIN" -c "import pyfory" >/dev/null 2>&1; then
  echo "Error: pyfory is not installed in current Python environment."
  echo "Install it with: cd python && pip install -e ."
  exit 1
fi

if ! "$PYTHON_BIN" -c "import google.protobuf" >/dev/null 2>&1; then
  echo "Installing benchmark dependency: protobuf"
  "$PYTHON_BIN" -m pip install protobuf
fi

if ! "$PYTHON_BIN" -c "import matplotlib, numpy" >/dev/null 2>&1; then
  echo "Installing report dependencies: matplotlib numpy psutil"
  "$PYTHON_BIN" -m pip install matplotlib numpy psutil
fi

mkdir -p "$PROTO_DIR" "$OUTPUT_DIR" "$REPORT_DIR"

if [[ ! -f "$PROTO_DIR/__init__.py" ]]; then
  touch "$PROTO_DIR/__init__.py"
fi

echo "Generating Python protobuf bindings..."
protoc \
  --proto_path="$SCRIPT_DIR/../proto" \
  --python_out="$PROTO_DIR" \
  "$SCRIPT_DIR/../proto/bench.proto"

BENCH_JSON="$OUTPUT_DIR/benchmark_results.json"
BENCH_CMD=(
  "$PYTHON_BIN" "$SCRIPT_DIR/benchmark.py"
  --proto-dir "$PROTO_DIR"
  --output-json "$BENCH_JSON"
  --operation "$OPERATION"
  --warmup "$WARMUP"
  --iterations "$ITERATIONS"
  --repeat "$REPEAT"
  --number "$NUMBER"
)

if [[ -n "$DATA" ]]; then
  BENCH_CMD+=(--data "$DATA")
fi
if [[ -n "$SERIALIZER" ]]; then
  BENCH_CMD+=(--serializer "$SERIALIZER")
fi

echo ""
echo "Running benchmark..."
"${BENCH_CMD[@]}"

echo ""
echo "Generating report..."
"$PYTHON_BIN" "$SCRIPT_DIR/benchmark_report.py" \
  --json-file "$BENCH_JSON" \
  --output-dir "$REPORT_DIR"

if [[ "$COPY_DOCS" == true ]]; then
  mkdir -p "$DOCS_DIR"
  cp "$REPORT_DIR/README.md" "$DOCS_DIR/README.md"
  cp "$REPORT_DIR"/*.png "$DOCS_DIR/" 2>/dev/null || true
  echo "Copied report and plots to: $DOCS_DIR"
fi

echo ""
echo "============================================"
echo "Benchmark complete!"
echo "============================================"
echo "Benchmark JSON: $BENCH_JSON"
echo "Report: $REPORT_DIR/README.md"
if [[ "$COPY_DOCS" == true ]]; then
  echo "Docs sync: $DOCS_DIR"
fi
