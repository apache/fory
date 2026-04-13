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

OUTPUT_DIR="$SCRIPT_DIR/profile_output"
BUILD_DIR="$SCRIPT_DIR/build"
RUNNER="$BUILD_DIR/benchmark_runner"
DATA="sample"
SERIALIZER="fory"
OPERATION="serialize"
DURATION=20
WARMUP=5

usage() {
  echo "Usage: $0 [options]"
  echo ""
  echo "Options:"
  echo "  --data <type>        Data type filter (default: sample)."
  echo "  --serializer <name>  Serializer filter (default: fory)."
  echo "  --operation <name>   serialize or deserialize (default: serialize)."
  echo "  --duration <sec>     Profiling duration in seconds (default: 20)."
  echo "  --warmup <sec>       Warmup duration in seconds (default: 5)."
  echo "  --output-dir <dir>   Output directory."
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

if [[ ! -x "$RUNNER" ]]; then
  ./run.sh --data "$DATA" --serializer "$SERIALIZER" --operation "$OPERATION" --samples 1 --duration 1 --warmup 0 --no-report >/dev/null
fi

CMD=("$RUNNER"
  --data "$DATA"
  --serializer "$SERIALIZER"
  --operation "$OPERATION"
  --samples 1
  --duration "$DURATION"
  --warmup "$WARMUP")

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OS_TYPE="$(uname -s)"

if command -v sample >/dev/null 2>&1 && [[ "$OS_TYPE" == "Darwin" ]]; then
  "${CMD[@]}" >/dev/null &
  PID=$!
  sleep 1
  sample "$PID" "$DURATION" -file "$OUTPUT_DIR/sample_${TIMESTAMP}.txt" >/dev/null 2>&1 || true
  wait "$PID" || true
  echo "Saved sample profile to $OUTPUT_DIR/sample_${TIMESTAMP}.txt"
elif command -v perf >/dev/null 2>&1; then
  perf record -g -o "$OUTPUT_DIR/perf_${TIMESTAMP}.data" "${CMD[@]}"
  echo "Saved perf profile to $OUTPUT_DIR/perf_${TIMESTAMP}.data"
else
  echo "No supported profiler found (sample or perf)."
  exit 1
fi

