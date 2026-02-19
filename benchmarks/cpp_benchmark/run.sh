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

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
JOBS=16
DATA=""
SERIALIZER=""
DEBUG_BUILD=false
DURATION=""

# Parse arguments
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Build and run C++ benchmarks"
    echo ""
    echo "Options:"
    echo "  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>"
    echo "                               Filter benchmark by data type"
    echo "  --serializer <fory|protobuf|msgpack>"
    echo "                               Filter benchmark by serializer"
    echo "  --duration <seconds>         Minimum time to run each benchmark (e.g., 10, 30)"
    echo "  --debug                      Build with debug symbols and low optimization for profiling"
    echo "  --help                       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                          # Run all benchmarks"
    echo "  $0 --data struct            # Run only Struct benchmarks"
    echo "  $0 --serializer fory        # Run only Fory benchmarks"
    echo "  $0 --serializer msgpack     # Run only Msgpack benchmarks"
    echo "  $0 --data struct --serializer fory"
    echo "  $0 --duration 10            # Run each benchmark for at least 10 seconds"
    echo "  $0 --debug                  # Build for profiling (visible function names in flamegraph)"
    echo ""
    echo "For profiling/flamegraph, use: ./profile.sh"
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
        --debug)
            DEBUG_BUILD=true
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

# Build benchmark filter
FILTER=""
DATA_PATTERN=""
SERIALIZER_PATTERN=""

if [[ -n "$DATA" ]]; then
    DATA_LOWER="$(echo "$DATA" | tr '[:upper:]' '[:lower:]')"
    case "$DATA_LOWER" in
        struct)
            DATA_PATTERN="Struct"
            ;;
        sample)
            DATA_PATTERN="Sample"
            ;;
        mediacontent)
            DATA_PATTERN="MediaContent"
            ;;
        structlist)
            DATA_PATTERN="StructList"
            ;;
        samplelist)
            DATA_PATTERN="SampleList"
            ;;
        mediacontentlist)
            DATA_PATTERN="MediaContentList"
            ;;
        *)
            echo -e "${RED}Unknown data type: $DATA${NC}"
            echo "Expected one of: struct, sample, mediacontent, structlist, samplelist, mediacontentlist"
            exit 1
            ;;
    esac
fi

if [[ -n "$SERIALIZER" ]]; then
    SERIALIZER_LOWER="$(echo "$SERIALIZER" | tr '[:upper:]' '[:lower:]')"
    case "$SERIALIZER_LOWER" in
        fory)
            SERIALIZER_PATTERN="Fory"
            ;;
        protobuf)
            SERIALIZER_PATTERN="Protobuf"
            ;;
        msgpack)
            SERIALIZER_PATTERN="Msgpack"
            ;;
        *)
            echo -e "${RED}Unknown serializer: $SERIALIZER${NC}"
            echo "Expected one of: fory, protobuf, msgpack"
            exit 1
            ;;
    esac
fi

if [[ -n "$SERIALIZER_PATTERN" && -n "$DATA_PATTERN" ]]; then
    FILTER="^BM_${SERIALIZER_PATTERN}_${DATA_PATTERN}_(Serialize|Deserialize)$"
elif [[ -n "$SERIALIZER_PATTERN" ]]; then
    FILTER="^BM_${SERIALIZER_PATTERN}_"
elif [[ -n "$DATA_PATTERN" ]]; then
    FILTER="^BM_.*_${DATA_PATTERN}_(Serialize|Deserialize)$"
fi

echo -e "${GREEN}=== Fory C++ Benchmark ===${NC}"
echo ""

# Step 1: Build
echo -e "${YELLOW}[1/3] Building benchmark...${NC}"
mkdir -p build
cd build
if [[ "$DEBUG_BUILD" == true ]]; then
    echo -e "${YELLOW}Building with debug symbols for profiling...${NC}"
    # Detect OS for platform-specific flags
    OS_TYPE="$(uname -s)"
    if [[ "$OS_TYPE" == "Darwin" ]]; then
        # macOS: Use -gfull for complete debug info, -fno-omit-frame-pointer for stack traces
        cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo \
              -DCMAKE_CXX_FLAGS="-O1 -gfull -fno-inline -fno-omit-frame-pointer -fno-optimize-sibling-calls" ..
    else
        # Linux
        cmake -DCMAKE_BUILD_TYPE=RelWithDebInfo \
              -DCMAKE_CXX_FLAGS="-O1 -g -fno-inline -fno-omit-frame-pointer" ..
    fi
else
    cmake -DCMAKE_BUILD_TYPE=Release ..
fi
cmake --build . -j"$JOBS"
echo -e "${GREEN}Build complete!${NC}"
echo ""

# Step 2: Run benchmark
echo -e "${YELLOW}[2/3] Running benchmark...${NC}"
BENCH_ARGS="--benchmark_format=json --benchmark_out=benchmark_results.json"
if [[ -n "$DURATION" ]]; then
    BENCH_ARGS="$BENCH_ARGS --benchmark_min_time=${DURATION}s"
    echo -e "Duration: ${DURATION}s per benchmark"
fi
if [[ -n "$FILTER" ]]; then
    BENCH_ARGS="$BENCH_ARGS --benchmark_filter=$FILTER"
    echo -e "Filter: ${FILTER}"
fi
./fory_benchmark $BENCH_ARGS
echo -e "${GREEN}Benchmark complete!${NC}"
echo ""

# Step 3: Generate report
echo -e "${YELLOW}[3/3] Generating report...${NC}"
cd "$SCRIPT_DIR"

# Check for Python dependencies
if ! python3 -c "import matplotlib" 2>/dev/null; then
    echo -e "${YELLOW}Installing required Python packages...${NC}"
    pip3 install matplotlib numpy psutil
fi

python3 benchmark_report.py --json-file build/benchmark_results.json --output-dir report
echo ""

echo -e "${GREEN}=== All done! ===${NC}"
echo -e "Report generated at: ${SCRIPT_DIR}/report/REPORT.md"
echo -e "Plots saved in: ${SCRIPT_DIR}/report/"
echo ""
echo -e "For profiling/flamegraph, run: ${YELLOW}./profile.sh --help${NC}"
