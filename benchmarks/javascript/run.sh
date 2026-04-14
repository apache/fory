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
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
JS_ROOT="${REPO_ROOT}/javascript"
OUTPUT_JSON="${SCRIPT_DIR}/benchmark_results.json"
DOC_OUTPUT_DIR="${REPO_ROOT}/docs/benchmarks/javascript"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

DATA=""
SERIALIZER=""
DURATION=""

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Build and run JavaScript benchmarks"
    echo ""
    echo "Options:"
    echo "  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>"
    echo "                               Filter benchmark by data type"
    echo "  --serializer <fory|protobuf|json>"
    echo "                               Filter benchmark by serializer"
    echo "  --duration <seconds>         Minimum time to run each benchmark"
    echo "  --help                       Show this help message"
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
        --help|-h)
            usage
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            usage
            ;;
    esac
done

echo -e "${GREEN}=== Fory JavaScript Benchmark ===${NC}"
echo ""

echo -e "${YELLOW}[1/3] Building JavaScript packages...${NC}"
cd "${JS_ROOT}"
if [[ ! -d node_modules ]]; then
    npm install
fi
npm run build
echo -e "${GREEN}Build complete!${NC}"
echo ""

echo -e "${YELLOW}[2/3] Running benchmark...${NC}"
cd "${SCRIPT_DIR}"
BENCH_ARGS=(--output "${OUTPUT_JSON}")
if [[ -n "${DATA}" ]]; then
    BENCH_ARGS+=(--data "${DATA}")
fi
if [[ -n "${SERIALIZER}" ]]; then
    BENCH_ARGS+=(--serializer "${SERIALIZER}")
fi
if [[ -n "${DURATION}" ]]; then
    BENCH_ARGS+=(--duration "${DURATION}")
fi
node benchmark.js "${BENCH_ARGS[@]}"
echo -e "${GREEN}Benchmark complete!${NC}"
echo ""

echo -e "${YELLOW}[3/3] Generating report...${NC}"
if ! python3 -c "import matplotlib, numpy, psutil" 2>/dev/null; then
    pip3 install matplotlib numpy psutil
fi
python3 benchmark_report.py --json-file "${OUTPUT_JSON}" --output-dir "${DOC_OUTPUT_DIR}"
echo ""

echo -e "${GREEN}=== All done! ===${NC}"
echo -e "Report generated at: ${DOC_OUTPUT_DIR}/README.md"
echo -e "Plots saved in: ${DOC_OUTPUT_DIR}"
