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

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Defaults
DATA=""
SERIALIZER=""
DURATION="3"
OUTPUT_JSON="results/benchmark_results.json"
NO_REPORT=false

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Build and run Swift benchmarks"
    echo ""
    echo "Options:"
    echo "  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>"
    echo "                               Filter benchmark by data type"
    echo "  --serializer <fory|protobuf|msgpack>"
    echo "                               Filter benchmark by serializer"
    echo "  --duration <seconds>         Minimum time to run each benchmark (default: 3)"
    echo "  --no-report                  Skip Python report generation"
    echo "  --help                       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0"
    echo "  $0 --data struct"
    echo "  $0 --serializer protobuf"
    echo "  $0 --data sample --serializer msgpack --duration 5"
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
        --no-report)
            NO_REPORT=true
            shift
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

mkdir -p results

echo -e "${GREEN}=== Fory Swift Benchmark ===${NC}"
echo ""

echo -e "${YELLOW}[1/3] Building benchmark...${NC}"
swift build -c release
echo -e "${GREEN}Build complete!${NC}"
echo ""

echo -e "${YELLOW}[2/3] Running benchmark...${NC}"
ARGS=("--duration" "$DURATION" "--output" "$OUTPUT_JSON")
if [[ -n "$DATA" ]]; then
    ARGS+=("--data" "$DATA")
fi
if [[ -n "$SERIALIZER" ]]; then
    ARGS+=("--serializer" "$SERIALIZER")
fi

swift run -c release swift-benchmark "${ARGS[@]}"
echo -e "${GREEN}Benchmark complete!${NC}"

if [[ "$NO_REPORT" == true ]]; then
    echo ""
    echo -e "${YELLOW}Skipping report generation (--no-report).${NC}"
    echo -e "JSON output: ${OUTPUT_JSON}"
    exit 0
fi

echo ""
echo -e "${YELLOW}[3/3] Generating report...${NC}"
if ! command -v python3 >/dev/null 2>&1; then
    echo -e "${RED}python3 is required for report generation${NC}"
    exit 1
fi

if ! python3 -c "import matplotlib" >/dev/null 2>&1; then
    echo -e "${YELLOW}Installing Python report dependencies...${NC}"
    pip3 install matplotlib numpy
fi

python3 benchmark_report.py --json-file "$OUTPUT_JSON" --output-dir results
echo -e "${GREEN}Report generated: ${SCRIPT_DIR}/results/REPORT.md${NC}"
