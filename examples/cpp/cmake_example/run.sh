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

pip install cmake

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

echo "=== Fory C++ CMake Example Build Script ==="
echo ""

# Clean previous build if requested
if [[ "$1" == "clean" ]]; then
    echo "Cleaning build directory..."
    rm -rf "${BUILD_DIR}"
    echo "Done."
    exit 0
fi

# Create build directory
mkdir -p "${BUILD_DIR}"
cd "${BUILD_DIR}"

# Configure
echo "Configuring with CMake..."
cmake .. -DCMAKE_BUILD_TYPE=Release

# Build
echo ""
echo "Building..."
cmake --build . --parallel

echo ""
echo "=== Build successful! ==="
echo ""

# Run examples
echo "=== Running serialization_example ==="
echo ""
./serialization_example

echo ""
echo "=== Running row_format_example ==="
echo ""
./row_format_example

echo ""
echo "=== All examples completed! ==="
