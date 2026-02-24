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

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DATA=""
SERIALIZER=""
DURATION="3"
WARMUP="1"

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
        --help|-h)
            usage
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            ;;
    esac
done

mkdir -p build report
RESULT_JSON="build/benchmark_results.json"

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

echo -e "${GREEN}=== Fory C# Benchmark ===${NC}"
echo ""

echo -e "${YELLOW}[1/3] Restoring dependencies...${NC}"
dotnet restore ./Fory.CSharpBenchmark.csproj >/dev/null

echo -e "${YELLOW}[2/3] Running benchmark...${NC}"
dotnet run -c Release --project ./Fory.CSharpBenchmark.csproj -- "${RUN_ARGS[@]}"

echo -e "${YELLOW}[3/3] Generating report...${NC}"
python3 benchmark_report.py --json-file "$RESULT_JSON" --output-dir report

echo ""
echo -e "${GREEN}=== All done! ===${NC}"
echo "Report generated at: $SCRIPT_DIR/report/REPORT.md"
