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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

OUTPUT_DIR="$SCRIPT_DIR/results"
BUILD_DIR="$SCRIPT_DIR/build"
RUNNER="$BUILD_DIR/benchmark_runner"
GENERATE_REPORT=true
DATA=""
SERIALIZER=""
OPERATION=""
SAMPLES=5
DURATION=1.5
WARMUP=1.0

usage() {
  echo "Usage: $0 [options]"
  echo ""
  echo "Options:"
  echo "  --data <type>        Filter by data type."
  echo "  --serializer <name>  Filter by serializer: fory, protobuf."
  echo "  --operation <name>   Filter by operation: serialize, deserialize."
  echo "  --samples <n>        Number of measured samples (default: 5)."
  echo "  --duration <sec>     Seconds per sample (default: 1.5)."
  echo "  --warmup <sec>       Warmup seconds per case (default: 1.0)."
  echo "  --no-report          Skip report generation."
  echo "  -h, --help           Show this help."
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
    --samples)
      SAMPLES="$2"
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
    --no-report)
      GENERATE_REPORT=false
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

mkdir -p "$OUTPUT_DIR" "$BUILD_DIR"

echo "============================================"
echo "Preparing Dart benchmark package..."
echo "============================================"
dart pub get
./tool/generate_proto.sh
dart run build_runner build --delete-conflicting-outputs

echo ""
echo "============================================"
echo "Compiling benchmark runner..."
echo "============================================"
dart compile exe bin/benchmark_runner.dart -o "$RUNNER"

echo ""
echo "============================================"
echo "Running benchmarks..."
echo "============================================"

CMD=("$RUNNER"
  --samples "$SAMPLES"
  --duration "$DURATION"
  --warmup "$WARMUP"
  --json-output "$OUTPUT_DIR/benchmark_results.json"
  --size-output "$OUTPUT_DIR/serialized_sizes.txt")

if [[ -n "$DATA" ]]; then
  CMD+=(--data "$DATA")
fi
if [[ -n "$SERIALIZER" ]]; then
  CMD+=(--serializer "$SERIALIZER")
fi
if [[ -n "$OPERATION" ]]; then
  CMD+=(--operation "$OPERATION")
fi

"${CMD[@]}" | tee "$OUTPUT_DIR/benchmark_results.txt"

if $GENERATE_REPORT; then
  echo ""
  echo "============================================"
  echo "Generating report..."
  echo "============================================"
  python3 "$SCRIPT_DIR/benchmark_report.py" \
    --json-file "$OUTPUT_DIR/benchmark_results.json" \
    --output-dir "$OUTPUT_DIR"
fi

echo ""
echo "============================================"
echo "Benchmark complete!"
echo "============================================"
echo "Results saved to: $OUTPUT_DIR"

