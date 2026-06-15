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
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TEST_CLASSES="${1:-PythonGrpcInteropTest,RustGrpcInteropTest,GoGrpcInteropTest,KotlinGrpcInteropTest}"

python -m pip install "grpcio>=1.62.2,<1.71"
python -m pip install -v -e "${ROOT_DIR}/python"

python "${SCRIPT_DIR}/generate_grpc.py"

cd "${SCRIPT_DIR}/go"
go build -o grpc-interop .
cargo build --manifest-path "${SCRIPT_DIR}/rust/Cargo.toml" --workspace --quiet
cd "${SCRIPT_DIR}/kotlin"
mvn --no-transfer-progress -DskipTests package
cd "${ROOT_DIR}/integration_tests/grpc_tests/java"
mvn -T16 --no-transfer-progress \
  -Dtest="${TEST_CLASSES}" \
  test
