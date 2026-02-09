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

"""AST node definitions for FlatBuffers schemas."""

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Union


@dataclass(frozen=True)
class FbsTypeName:
    """A named type reference."""

    name: str
    line: int = 0
    column: int = 0


@dataclass(frozen=True)
class FbsVectorType:
    """A vector type reference."""

    element_type: "FbsTypeRef"
    line: int = 0
    column: int = 0


FbsTypeRef = Union[FbsTypeName, FbsVectorType]


@dataclass
class FbsField:
    """A field declaration in a table or struct."""

    name: str
    field_type: FbsTypeRef
    default: Optional[object] = None
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsEnumValue:
    """A value in a FlatBuffers enum."""

    name: str
    value: int
    line: int = 0
    column: int = 0


@dataclass
class FbsEnum:
    """An enum declaration."""

    name: str
    base_type: Optional[str]
    values: List[FbsEnumValue] = field(default_factory=list)
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsUnion:
    """A FlatBuffers union declaration."""

    name: str
    types: List[str] = field(default_factory=list)
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsTable:
    """A FlatBuffers table declaration."""

    name: str
    fields: List[FbsField] = field(default_factory=list)
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsStruct:
    """A FlatBuffers struct declaration."""

    name: str
    fields: List[FbsField] = field(default_factory=list)
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsRpcMethod:
    """An RPC method declaration."""

    name: str
    request_type: str
    response_type: str
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsService:
    """A FlatBuffers service declaration."""

    name: str
    methods: List[FbsRpcMethod] = field(default_factory=list)
    attributes: Dict[str, object] = field(default_factory=dict)
    line: int = 0
    column: int = 0


@dataclass
class FbsSchema:
    """The root node representing a FlatBuffers schema."""

    namespace: Optional[str]
    includes: List[str] = field(default_factory=list)
    attributes: List[str] = field(default_factory=list)
    enums: List[FbsEnum] = field(default_factory=list)
    unions: List[FbsUnion] = field(default_factory=list)
    tables: List[FbsTable] = field(default_factory=list)
    structs: List[FbsStruct] = field(default_factory=list)
    services: List[FbsService] = field(default_factory=list)
    root_type: Optional[str] = None
    source_file: Optional[str] = None
