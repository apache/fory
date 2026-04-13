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
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROTO_DIR="$ROOT_DIR/../proto"
OUTPUT_DIR="$ROOT_DIR/lib/src/generated"

mkdir -p "$OUTPUT_DIR"

if ! command -v protoc >/dev/null 2>&1; then
  echo "Error: protoc is required to generate Dart benchmark protobuf code."
  exit 1
fi

export PATH="$PATH:$HOME/.pub-cache/bin"

if ! command -v protoc-gen-dart >/dev/null 2>&1; then
  echo "Installing protoc_plugin globally..."
  dart pub global activate protoc_plugin
fi

protoc \
  --proto_path="$PROTO_DIR" \
  --dart_out="$OUTPUT_DIR" \
  "$PROTO_DIR/bench.proto"

echo "Generated Dart protobuf code in $OUTPUT_DIR"

