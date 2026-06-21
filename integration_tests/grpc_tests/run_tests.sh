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
TEST_CLASSES="${1:-PythonAsyncGrpcTest,PythonSyncGrpcTest,RustGrpcTest,GoGrpcTest,KotlinGrpcTest,DartGrpcTest}"

has_test_class() {
  [[ ",${TEST_CLASSES}," == *",$1,"* ]]
}

if has_test_class "PythonAsyncGrpcTest" || has_test_class "PythonSyncGrpcTest"; then
  python -m pip install "grpcio>=1.62.2,<1.71"
  python -m pip install -v -e "${ROOT_DIR}/python"
fi

python "${SCRIPT_DIR}/generate_grpc.py"

if has_test_class "GoGrpcTest"; then
  cd "${SCRIPT_DIR}/go"
  go build -o grpc-interop .
fi
if has_test_class "RustGrpcTest"; then
  cargo build --manifest-path "${SCRIPT_DIR}/rust/Cargo.toml" --workspace --quiet
fi
if has_test_class "KotlinGrpcTest"; then
  cd "${SCRIPT_DIR}/kotlin"
  mvn --no-transfer-progress -DskipTests package
fi
if has_test_class "DartGrpcTest"; then
  cd "${SCRIPT_DIR}/dart"
  dart pub get
  dart run build_runner build
  dart analyze bin lib/generated/*/*_grpc.dart
  dart format --output=none --set-exit-if-changed bin lib/generated/*/*_grpc.dart
fi
cd "${ROOT_DIR}/integration_tests/grpc_tests/java"
mvn -T16 --no-transfer-progress \
  -Dtest="${TEST_CLASSES}" \
  test
