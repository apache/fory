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

"Shared utilities for gRPC service stub generators."

from enum import Enum
from typing import Dict
from fory_compiler.ir.ast import RpcMethod


class StreamingMode(Enum):
    UNARY = 1
    CLIENT_STREAMING = 2
    SERVER_STREAMING = 3
    BIDIRECTIONAL = 4


def streaming_mode(method: RpcMethod) -> StreamingMode:
    """Defines the type of rpc streaming patterns."""
    if not method.client_streaming and not method.server_streaming:
        return StreamingMode.UNARY
    elif method.client_streaming and not method.server_streaming:
        return StreamingMode.CLIENT_STREAMING
    elif not method.client_streaming and method.server_streaming:
        return StreamingMode.SERVER_STREAMING
    else:
        return StreamingMode.BIDIRECTIONAL


class ImportTracker:
    """Accumulates cross-package Go imports for generated service stubs."""

    def __init__(self):
        self._imports: Dict[str, str] = {}

    def add(self, alias: str, import_path: str) -> None:
        self._imports[alias] = import_path
