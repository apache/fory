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

python "${SCRIPT_DIR}/generate_idl.py" --lang kotlin

cd "${ROOT_DIR}/java"
ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 --batch-mode --no-transfer-progress -pl fory-core -am -DskipTests install

cd "${ROOT_DIR}/kotlin"
ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 --batch-mode --no-transfer-progress -pl fory-kotlin,fory-kotlin-ksp -am -DskipTests install

cd "${ROOT_DIR}/integration_tests/idl_tests/kotlin"
ENABLE_FORY_DEBUG_OUTPUT=1 mvn --batch-mode --no-transfer-progress clean package

cd "${ROOT_DIR}/integration_tests/idl_tests"
IDL_PEER_LANG=kotlin IDL_JAVA_TEST_PATTERN=IdlRoundTripTest#testAddressBookRoundTripCompatible,IdlRoundTripTest#testAddressBookRoundTripSchemaConsistent,IdlRoundTripTest#testTreeRoundTripCompatible,IdlRoundTripTest#testTreeRoundTripSchemaConsistent,IdlRoundTripTest#testGraphRoundTripCompatible,IdlRoundTripTest#testGraphRoundTripSchemaConsistent,IdlRoundTripTest#testExampleRoundTripCompatible,IdlRoundTripTest#testExampleRoundTripSchemaConsistent,IdlRoundTripTest#testJvmTypesRoundTripCompatible,IdlRoundTripTest#testJvmTypesRoundTripSchemaConsistent ./run_java_tests.sh
