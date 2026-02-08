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

"""AST node definitions for FDL."""

from dataclasses import dataclass, field
from typing import List, Optional, Union as TypingUnion

from fory_compiler.ir.types import PrimitiveKind


@dataclass(frozen=True)
class SourceLocation:
    """Track original source location for error messages."""

    file: str
    line: int
    column: int
    source_format: str


@dataclass
class PrimitiveType:
    """A primitive type like int32, string, etc."""

    kind: PrimitiveKind
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        return f"PrimitiveType({self.kind.value})"


@dataclass
class NamedType:
    """A reference to a user-defined type (message or enum)."""

    name: str
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        return f"NamedType({self.name})"


@dataclass
class ListType:
    """A list type (list/repeated)."""

    element_type: "FieldType"
    element_optional: bool = False
    element_ref: bool = False
    element_ref_options: dict = field(default_factory=dict)
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        suffix = ""
        if self.element_optional:
            suffix = ", element_optional=True"
        if self.element_ref:
            suffix += ", element_ref=True"
            if self.element_ref_options:
                suffix += f", element_ref_options={self.element_ref_options}"
        return f"ListType({self.element_type}{suffix})"


@dataclass
class MapType:
    """A map type with key and value types."""

    key_type: "FieldType"
    value_type: "FieldType"
    value_ref: bool = False
    value_ref_options: dict = field(default_factory=dict)
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        suffix = ""
        if self.value_ref:
            suffix = ", value_ref=True"
            if self.value_ref_options:
                suffix += f", value_ref_options={self.value_ref_options}"
        return f"MapType({self.key_type}, {self.value_type}{suffix})"


# Union of all field types
FieldType = TypingUnion[PrimitiveType, NamedType, ListType, MapType]


@dataclass
class Field:
    """A field in a message."""

    name: str
    field_type: FieldType
    number: int
    tag_id: Optional[int] = None
    optional: bool = False
    ref: bool = False
    ref_options: dict = field(default_factory=dict)
    element_optional: bool = False
    element_ref: bool = False
    element_ref_options: dict = field(default_factory=dict)
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        modifiers = []
        if self.optional:
            modifiers.append("optional")
        if self.ref:
            modifiers.append("ref")
        if self.element_optional:
            modifiers.append("element_optional")
        if self.element_ref:
            modifiers.append("element_ref")
        mod_str = " ".join(modifiers) + " " if modifiers else ""
        opts_str = f" [{self.options}]" if self.options else ""
        return (
            f"Field({mod_str}{self.field_type} {self.name} = {self.number}{opts_str})"
        )


@dataclass
class Import:
    """An import statement."""

    path: str
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        return f'Import("{self.path}")'


@dataclass
class EnumValue:
    """A value in an enum."""

    name: str
    value: int
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        return f"EnumValue({self.name} = {self.value})"


@dataclass
class Message:
    """A message definition."""

    name: str
    type_id: Optional[int]
    fields: List[Field] = field(default_factory=list)
    nested_messages: List["Message"] = field(default_factory=list)
    nested_enums: List["Enum"] = field(default_factory=list)
    nested_unions: List["Union"] = field(default_factory=list)
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None
    id_generated: bool = False
    id_source: Optional[str] = None

    def __repr__(self) -> str:
        id_str = f" [id={self.type_id}]" if self.type_id is not None else ""
        nested_str = ""
        if self.nested_messages or self.nested_enums or self.nested_unions:
            nested_str = f", nested={len(self.nested_messages)}msg+{len(self.nested_enums)}enum+{len(self.nested_unions)}union"
        opts_str = f", options={len(self.options)}" if self.options else ""
        return (
            f"Message({self.name}{id_str}, fields={self.fields}{nested_str}{opts_str})"
        )

    def get_nested_type(
        self, name: str
    ) -> Optional[TypingUnion["Message", "Enum", "Union"]]:
        """Look up a nested type by name."""
        for msg in self.nested_messages:
            if msg.name == name:
                return msg
        for enum in self.nested_enums:
            if enum.name == name:
                return enum
        for union in self.nested_unions:
            if union.name == name:
                return union
        return None


@dataclass
class Enum:
    """An enum definition."""

    name: str
    type_id: Optional[int]
    values: List[EnumValue] = field(default_factory=list)
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None
    id_generated: bool = False
    id_source: Optional[str] = None

    def __repr__(self) -> str:
        id_str = f" [id={self.type_id}]" if self.type_id is not None else ""
        opts_str = f", options={len(self.options)}" if self.options else ""
        return f"Enum({self.name}{id_str}, values={self.values}{opts_str})"


@dataclass
class Union:
    """A union definition."""

    name: str
    type_id: Optional[int]
    fields: List[Field] = field(default_factory=list)
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None
    id_generated: bool = False
    id_source: Optional[str] = None

    def __repr__(self) -> str:
        id_str = f" [id={self.type_id}]" if self.type_id is not None else ""
        opts_str = f", options={len(self.options)}" if self.options else ""
        return f"Union({self.name}{id_str}, fields={self.fields}{opts_str})"


@dataclass
class RpcMethod:
    """An RPC method inside a service."""

    name: str
    request_type: NamedType
    response_type: NamedType
    client_streaming: bool = False
    server_streaming: bool = False
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        opts_str = f" [{self.options}]" if self.options else ""
        req_stream = "stream " if self.client_streaming else ""
        res_stream = "stream " if self.server_streaming else ""
        return (
            f"RpcMethod({self.name} "
            f"({req_stream}{self.request_type}) returns ({res_stream}{self.response_type})"
            f"{opts_str})"
        )


@dataclass
class Service:
    """A service definition."""

    name: str
    methods: List[RpcMethod] = field(default_factory=list)
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0
    location: Optional[SourceLocation] = None

    def __repr__(self) -> str:
        opts_str = f", options={len(self.options)}" if self.options else ""
        return f"Service({self.name}, methods={len(self.methods)}{opts_str})"


@dataclass
class Schema:
    """The root AST node representing a complete FDL file."""

    package: Optional[str]
    package_alias: Optional[str] = None
    imports: List[Import] = field(default_factory=list)
    enums: List[Enum] = field(default_factory=list)
    messages: List[Message] = field(default_factory=list)
    unions: List[Union] = field(default_factory=list)
    services: List[Service] = field(default_factory=list)
    options: dict = field(
        default_factory=dict
    )  # File-level options (java_package, go_package, etc.)
    source_file: Optional[str] = None
    source_format: Optional[str] = None

    def __repr__(self) -> str:
        opts = f", options={len(self.options)}" if self.options else ""
        alias = f", package_alias={self.package_alias}" if self.package_alias else ""
        return (
            f"Schema(package={self.package}{alias}, imports={len(self.imports)}, "
            f"enums={len(self.enums)}, messages={len(self.messages)}, unions={len(self.unions)}, "
            f"services={len(self.services)}{opts})"
        )

    def get_option(self, name: str, default: Optional[str] = None) -> Optional[str]:
        """Get a file-level option value."""
        return self.options.get(name, default)

    def get_type(self, name: str) -> Optional[TypingUnion[Message, Enum, "Union"]]:
        """Look up a type by name, supporting qualified names like Parent.Child."""
        # Handle qualified names (e.g., SearchResponse.Result)
        if "." in name:
            parts = name.split(".")
            # Find the top-level type
            current = self._get_top_level_type(parts[0])
            if current is None:
                return None
            # Navigate through nested types
            for part in parts[1:]:
                if isinstance(current, Message):
                    current = current.get_nested_type(part)
                    if current is None:
                        return None
                else:
                    # Enums don't have nested types
                    return None
            return current
        else:
            return self._get_top_level_type(name)

    def _get_top_level_type(
        self, name: str
    ) -> Optional[TypingUnion[Message, Enum, "Union"]]:
        """Look up a top-level type by simple name."""
        for enum in self.enums:
            if enum.name == name:
                return enum
        for union in self.unions:
            if union.name == name:
                return union
        for message in self.messages:
            if message.name == name:
                return message
        return None

    def get_all_types(self) -> List[TypingUnion[Message, Enum, "Union"]]:
        """Get all types including nested types (flattened)."""
        result: List[TypingUnion[Message, Enum, "Union"]] = []
        result.extend(self.enums)
        result.extend(self.unions)
        for message in self.messages:
            self._collect_types(message, result)
        return result

    def _collect_types(
        self, message: Message, result: List[TypingUnion[Message, Enum, "Union"]]
    ):
        """Recursively collect all types from a message and its nested types."""
        result.append(message)
        for nested_enum in message.nested_enums:
            result.append(nested_enum)
        for nested_union in message.nested_unions:
            result.append(nested_union)
        for nested_msg in message.nested_messages:
            self._collect_types(nested_msg, result)
