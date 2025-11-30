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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
DATA=""
SERIALIZER=""
FILTER=""
DURATION=5
OUTPUT_DIR="profile_output"

# Parse arguments
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Generate flamegraph/profile for C++ benchmarks"
    echo ""
    echo "Options:"
    echo "  --filter <pattern>           Custom benchmark filter (regex pattern)"
    echo "  --data <struct|sample>       Filter benchmark by data type"
    echo "  --serializer <fory|protobuf> Filter benchmark by serializer"
    echo "  --duration <seconds>         Profiling duration (default: 5)"
    echo "  --output-dir <dir>           Output directory (default: profile_output)"
    echo "  --help                       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                                    # Profile all benchmarks"
    echo "  $0 --data struct --serializer fory   # Profile Fory Struct benchmarks"
    echo "  $0 --serializer protobuf --duration 10"
    echo "  $0 --filter BM_Fory_Struct_Serialize # Profile specific benchmark"
    echo ""
    echo "Supported profiling tools (in order of preference):"
    echo "  - samply (recommended): cargo install samply"
    echo "  - perf (Linux)"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --filter)
            FILTER="$2"
            shift 2
            ;;
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
        --output-dir)
            OUTPUT_DIR="$2"
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

# Build benchmark filter (only if --filter not provided)
if [[ -z "$FILTER" ]]; then
    if [[ -n "$DATA" ]]; then
        DATA_CAP="$(echo "${DATA:0:1}" | tr '[:lower:]' '[:upper:]')${DATA:1}"
        FILTER="${DATA_CAP}"
    fi
    if [[ -n "$SERIALIZER" ]]; then
        SER_CAP="$(echo "${SERIALIZER:0:1}" | tr '[:lower:]' '[:upper:]')${SERIALIZER:1}"
        if [[ -n "$FILTER" ]]; then
            FILTER="${SER_CAP}_${FILTER}"
        else
            FILTER="${SER_CAP}"
        fi
    fi
fi

# Check if benchmark exists
if [[ ! -f "build/fory_benchmark" ]]; then
    echo -e "${RED}Benchmark not found. Run ./run.sh first to build.${NC}"
    exit 1
fi

# Find FlameGraph tools
FLAMEGRAPH_DIR=""
if [[ -d "$HOME/FlameGraph" ]]; then
    FLAMEGRAPH_DIR="$HOME/FlameGraph"
elif [[ -d "/usr/share/FlameGraph" ]]; then
    FLAMEGRAPH_DIR="/usr/share/FlameGraph"
elif command -v flamegraph.pl &> /dev/null; then
    FLAMEGRAPH_DIR="PATH"
else
    # Auto-install FlameGraph tools
    echo -e "${YELLOW}FlameGraph tools not found. Installing to ~/FlameGraph...${NC}"
    git clone --depth 1 https://github.com/brendangregg/FlameGraph.git "$HOME/FlameGraph"
    FLAMEGRAPH_DIR="$HOME/FlameGraph"
    echo -e "${GREEN}FlameGraph installed successfully.${NC}"
fi

# Create output directory
mkdir -p "$OUTPUT_DIR"
cd build

# Build benchmark command
BENCH_CMD="./fory_benchmark --benchmark_min_time=${DURATION}s"
if [[ -n "$FILTER" ]]; then
    BENCH_CMD="$BENCH_CMD --benchmark_filter=$FILTER"
fi

echo -e "${GREEN}=== Fory C++ Benchmark Profiler ===${NC}"
echo -e "Filter: ${FILTER:-all}"
echo -e "Duration: ${DURATION}s"
echo -e "Output: ${OUTPUT_DIR}"
echo ""

TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Try different profiling tools
if command -v samply &> /dev/null; then
    echo -e "${YELLOW}Profiling with samply...${NC}"
    echo -e "Running: samply record $BENCH_CMD"
    samply record $BENCH_CMD
    echo -e "${GREEN}Done! Samply should have opened in your browser.${NC}"

elif command -v perf &> /dev/null; then
    echo -e "${YELLOW}Profiling with perf...${NC}"
    PERF_DATA="../${OUTPUT_DIR}/perf_${TIMESTAMP}.data"
    FLAMEGRAPH_SVG="../${OUTPUT_DIR}/flamegraph_${TIMESTAMP}.svg"

    echo -e "Running: perf record -g --call-graph dwarf -o $PERF_DATA $BENCH_CMD"
    perf record -g --call-graph dwarf -o "$PERF_DATA" $BENCH_CMD

    echo -e "${GREEN}Profile saved to: $(realpath ${PERF_DATA})${NC}"

    # Generate flamegraph SVG
    echo -e "${YELLOW}Generating flamegraph SVG...${NC}"
    if [[ "$FLAMEGRAPH_DIR" == "PATH" ]]; then
        perf script -i "$PERF_DATA" | stackcollapse-perf.pl | flamegraph.pl > "$FLAMEGRAPH_SVG"
    else
        perf script -i "$PERF_DATA" | "$FLAMEGRAPH_DIR/stackcollapse-perf.pl" | "$FLAMEGRAPH_DIR/flamegraph.pl" > "$FLAMEGRAPH_SVG"
    fi
    echo -e "${GREEN}Flamegraph saved to: $(realpath ${FLAMEGRAPH_SVG})${NC}"

else
    echo -e "${RED}No profiling tool found. Please install one of:${NC}"
    echo "  - samply: cargo install samply (recommended, cross-platform)"
    echo "  - perf (Linux): apt install linux-tools-generic"
    exit 1
fi

echo ""
echo -e "${GREEN}=== Profiling complete! ===${NC}"
