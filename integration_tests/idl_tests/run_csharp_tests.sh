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

python "${SCRIPT_DIR}/generate_idl.py" --lang csharp

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fory-csharp-idl-XXXXXX")"
cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

export DATA_FILE="${TMP_DIR}/addressbook.bin"
export DATA_FILE_AUTO_ID="${TMP_DIR}/auto_id.bin"
export DATA_FILE_PRIMITIVES="${TMP_DIR}/primitives.bin"
export DATA_FILE_COLLECTION="${TMP_DIR}/collection.bin"
export DATA_FILE_COLLECTION_UNION="${TMP_DIR}/collection_union.bin"
export DATA_FILE_COLLECTION_ARRAY="${TMP_DIR}/collection_array.bin"
export DATA_FILE_COLLECTION_ARRAY_UNION="${TMP_DIR}/collection_array_union.bin"
export DATA_FILE_OPTIONAL_TYPES="${TMP_DIR}/optional_types.bin"
export DATA_FILE_ANY="${TMP_DIR}/any.bin"
export DATA_FILE_ANY_PROTO="${TMP_DIR}/any_proto.bin"
export DATA_FILE_TREE="${TMP_DIR}/tree.bin"
export DATA_FILE_GRAPH="${TMP_DIR}/graph.bin"
export DATA_FILE_FLATBUFFERS_MONSTER="${TMP_DIR}/flatbuffers_monster.bin"
export DATA_FILE_FLATBUFFERS_TEST2="${TMP_DIR}/flatbuffers_test2.bin"
export DATA_FILE_ROOT="${TMP_DIR}/root.bin"

cd "${SCRIPT_DIR}/csharp/IdlTests"
ENABLE_FORY_DEBUG_OUTPUT=1 dotnet test -c Release
