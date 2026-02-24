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
    echo "  - sample (macOS built-in)"
    echo "  - dtrace (macOS, may require sudo)"
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

# Detect OS
OS_TYPE="$(uname -s)"

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

elif [[ "$OS_TYPE" == "Darwin" ]]; then
    # macOS-specific profiling using sample command
    echo -e "${YELLOW}Profiling on macOS using sample command...${NC}"
    SAMPLE_OUTPUT="../${OUTPUT_DIR}/sample_${TIMESTAMP}.txt"
    COLLAPSED_OUTPUT="../${OUTPUT_DIR}/collapsed_${TIMESTAMP}.txt"
    FLAMEGRAPH_SVG="../${OUTPUT_DIR}/flamegraph_${TIMESTAMP}.svg"

    # Start benchmark in background
    echo -e "Starting benchmark: $BENCH_CMD"
    $BENCH_CMD &
    BENCH_PID=$!

    # Wait a moment for the process to start
    sleep 0.5

    # Check if process is running
    if ! kill -0 $BENCH_PID 2>/dev/null; then
        echo -e "${RED}Benchmark process failed to start${NC}"
        exit 1
    fi

    echo -e "Sampling process $BENCH_PID for ${DURATION} seconds..."
    # Use sample command to profile (built-in on macOS)
    sample $BENCH_PID $DURATION -file "$SAMPLE_OUTPUT" 2>/dev/null || true

    # Wait for benchmark to complete
    wait $BENCH_PID 2>/dev/null || true

    if [[ -f "$SAMPLE_OUTPUT" ]]; then
        echo -e "${GREEN}Sample output saved to: $(realpath ${SAMPLE_OUTPUT})${NC}"

        # Convert sample output to collapsed format for flamegraph
        echo -e "${YELLOW}Converting to flamegraph format...${NC}"

        # Parse macOS sample output to collapsed stack format
        # The sample output has a tree format with indentation and branch indicators
        python3 - "$SAMPLE_OUTPUT" "$COLLAPSED_OUTPUT" << 'PYTHON_SCRIPT'
import sys
import re

def parse_sample_output(input_file, output_file):
    """
    Parse macOS sample output tree format into collapsed stacks.

    Sample format is a tree like:
        3718 start  (in dyld) + 6076  [0x...]
          3718 main  (in fory_benchmark) + 136  [0x...]
            3718 benchmark::Run()  (in fory_benchmark) + 48  [0x...]
              + 3611 BM_Serialize()  (in fory_benchmark) + 316  [0x...]
              + ! 3301 fory::serialize()  (in fory_benchmark) + 468  [0x...]
    """
    stacks = {}

    with open(input_file, 'r') as f:
        lines = f.readlines()

    # Find the "Call graph:" section
    in_call_graph = False
    stack_lines = []

    for line in lines:
        if 'Call graph:' in line:
            in_call_graph = True
            continue
        if in_call_graph:
            # Stop at next section
            if line.strip() and not line.startswith(' ') and not any(c in line for c in ['+', '|', '!']):
                if 'Thread_' not in line and 'Total number' in line:
                    break
            stack_lines.append(line)

    # Parse the tree structure
    # Each frame has format: [indent/branch chars] COUNT FUNC_NAME (in MODULE) + OFFSET [ADDR] [FILE:LINE]
    frame_pattern = re.compile(
        r'^([\s+!:|]*)'           # Branch indicators and indentation
        r'(\d+)\s+'               # Sample count
        r'(.+?)'                  # Function name
        r'\s+\(in\s+([^)]+)\)'    # Module name
        r'(?:\s+\+\s+[\d,]+)?'    # Optional offset
        r'(?:\s+\[0x[0-9a-fA-F,]+\])?' # Optional address
        r'(?:\s+[\w./]+:\d+)?'    # Optional file:line
    )

    # Track stack at each depth level
    current_stack = []  # [(depth, func_name, count), ...]

    for line in stack_lines:
        match = frame_pattern.match(line)
        if not match:
            continue

        prefix = match.group(1)
        count = int(match.group(2))
        func_name = match.group(3).strip()
        module = match.group(4).strip()

        # Skip unknown frames
        if func_name == '???' or func_name.startswith('0x'):
            continue

        # Calculate depth based on prefix length (roughly 2 chars per level)
        # Count actual indentation ignoring branch chars
        depth = len(prefix.replace('+', ' ').replace('|', ' ').replace('!', ' ').replace(':', ' '))
        depth = depth // 2

        # Clean up function name for display
        func_name = func_name.replace(';', ':')

        # Pop stack until we're at the right depth
        while current_stack and current_stack[-1][0] >= depth:
            current_stack.pop()

        # Push current frame
        current_stack.append((depth, func_name, count))

        # Build stack string (bottom to top for flamegraph)
        stack_funcs = [f[1] for f in current_stack]
        if stack_funcs:
            stack_key = ';'.join(stack_funcs)
            # Use the count at this leaf node
            stacks[stack_key] = count

    # Write collapsed format
    with open(output_file, 'w') as f:
        for stack, count in sorted(stacks.items(), key=lambda x: -x[1]):
            if stack and count > 0:
                f.write(f"{stack} {count}\n")

    print(f"Extracted {len(stacks)} unique stack traces")
    if stacks:
        top_stacks = sorted(stacks.items(), key=lambda x: -x[1])[:5]
        print("Top 5 hottest stacks:")
        for stack, count in top_stacks:
            # Show just the last few functions
            funcs = stack.split(';')
            short = ';'.join(funcs[-3:]) if len(funcs) > 3 else stack
            print(f"  {count}: ...{short}")

    return len(stacks)

if __name__ == '__main__':
    parse_sample_output(sys.argv[1], sys.argv[2])
PYTHON_SCRIPT

        # Generate flamegraph
        if [[ -s "$COLLAPSED_OUTPUT" ]]; then
            if [[ "$FLAMEGRAPH_DIR" == "PATH" ]]; then
                flamegraph.pl "$COLLAPSED_OUTPUT" > "$FLAMEGRAPH_SVG"
            else
                "$FLAMEGRAPH_DIR/flamegraph.pl" "$COLLAPSED_OUTPUT" > "$FLAMEGRAPH_SVG"
            fi
            echo -e "${GREEN}Flamegraph saved to: $(realpath ${FLAMEGRAPH_SVG})${NC}"

            # Try to open in browser
            if command -v open &> /dev/null; then
                echo -e "${YELLOW}Opening flamegraph in browser...${NC}"
                open "$FLAMEGRAPH_SVG"
            fi
        else
            echo -e "${YELLOW}Could not generate flamegraph from sample output.${NC}"
            echo -e "You can view the raw sample output at: $(realpath ${SAMPLE_OUTPUT})"
            echo -e ""
            echo -e "${YELLOW}Tip: For better profiling results, consider using samply:${NC}"
            echo -e "  cargo install samply"
            echo -e "  ./profile.sh --data struct --serializer fory"
        fi
    else
        echo -e "${RED}Sample command failed to produce output${NC}"
        exit 1
    fi

else
    echo -e "${RED}No profiling tool found. Please install one of:${NC}"
    echo "  - samply: cargo install samply (recommended, cross-platform)"
    if [[ "$OS_TYPE" == "Darwin" ]]; then
        echo "  - sample: Built-in on macOS (should be available)"
    else
        echo "  - perf (Linux): apt install linux-tools-generic"
    fi
    exit 1
fi

echo ""
echo -e "${GREEN}=== Profiling complete! ===${NC}"
