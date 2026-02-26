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

echo "=== Generating TypeScript IDL code ==="
python "${SCRIPT_DIR}/generate_idl.py" --lang typescript

echo "=== Installing dependencies ==="
cd "${SCRIPT_DIR}/typescript"
npm install

echo "=== Building Fory JS runtime ==="
cd "${SCRIPT_DIR}/../../javascript"
npm install
npm run build

echo "=== Running TypeScript IDL tests ==="
cd "${SCRIPT_DIR}/typescript"
npx jest --ci --reporters=default
