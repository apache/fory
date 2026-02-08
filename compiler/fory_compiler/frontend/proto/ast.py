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

"""Proto-specific AST nodes."""

from dataclasses import dataclass, field
from typing import Dict, List, Optional


@dataclass
class ProtoType:
    """Represents a field type in proto."""

    name: str
    is_map: bool = False
    map_key_type: Optional[str] = None
    map_value_type: Optional[str] = None
    line: int = 0
    column: int = 0


@dataclass
class ProtoField:
    """Represents a message field."""

    name: str
    field_type: ProtoType
    number: int
    label: Optional[str] = None  # optional/repeated
    options: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class ProtoOneof:
    """Represents a oneof block."""

    name: str
    fields: List[ProtoField] = field(default_factory=list)
    line: int = 0
    column: int = 0


@dataclass
class ProtoEnumValue:
    """Represents an enum value."""

    name: str
    value: int
    line: int = 0
    column: int = 0


@dataclass
class ProtoEnum:
    """Represents an enum declaration."""

    name: str
    values: List[ProtoEnumValue] = field(default_factory=list)
    options: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class ProtoMessage:
    """Represents a message declaration."""

    name: str
    fields: List[ProtoField] = field(default_factory=list)
    nested_messages: List["ProtoMessage"] = field(default_factory=list)
    nested_enums: List[ProtoEnum] = field(default_factory=list)
    oneofs: List[ProtoOneof] = field(default_factory=list)
    options: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class ProtoRpcMethod:
    """An RPC method declaration."""

    name: str
    request_type: str
    response_type: str
    client_streaming: bool = False
    server_streaming: bool = False
    options: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class ProtoService:
    """A ProtoBuffers service declaration."""

    name: str
    methods: List["ProtoRpcMethod"] = field(default_factory=list)
    options: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class ProtoSchema:
    """Represents a proto file."""

    syntax: str
    package: Optional[str]
    imports: List[str] = field(default_factory=list)
    enums: List[ProtoEnum] = field(default_factory=list)
    services: List[ProtoService] = field(default_factory=list)
    messages: List[ProtoMessage] = field(default_factory=list)
    options: Dict[str, object] = field(default_factory=dict)
    source_file: Optional[str] = None
