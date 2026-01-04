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
from typing import List, Optional, Union
from enum import Enum as PyEnum


class PrimitiveKind(PyEnum):
    """Primitive type kinds."""

    BOOL = "bool"
    INT8 = "int8"
    INT16 = "int16"
    INT32 = "int32"
    INT64 = "int64"
    FLOAT32 = "float32"
    FLOAT64 = "float64"
    STRING = "string"
    BYTES = "bytes"
    DATE = "date"
    TIMESTAMP = "timestamp"


# Type aliases for primitive type names
PRIMITIVE_TYPES = {
    "bool": PrimitiveKind.BOOL,
    "int8": PrimitiveKind.INT8,
    "int16": PrimitiveKind.INT16,
    "int32": PrimitiveKind.INT32,
    "int64": PrimitiveKind.INT64,
    "float32": PrimitiveKind.FLOAT32,
    "float64": PrimitiveKind.FLOAT64,
    "string": PrimitiveKind.STRING,
    "bytes": PrimitiveKind.BYTES,
    "date": PrimitiveKind.DATE,
    "timestamp": PrimitiveKind.TIMESTAMP,
}


@dataclass
class PrimitiveType:
    """A primitive type like int32, string, etc."""

    kind: PrimitiveKind

    def __repr__(self) -> str:
        return f"PrimitiveType({self.kind.value})"


@dataclass
class NamedType:
    """A reference to a user-defined type (message or enum)."""

    name: str

    def __repr__(self) -> str:
        return f"NamedType({self.name})"


@dataclass
class ListType:
    """A list/repeated type."""

    element_type: "FieldType"

    def __repr__(self) -> str:
        return f"ListType({self.element_type})"


@dataclass
class MapType:
    """A map type with key and value types."""

    key_type: "FieldType"
    value_type: "FieldType"

    def __repr__(self) -> str:
        return f"MapType({self.key_type}, {self.value_type})"


# Union of all field types
FieldType = Union[PrimitiveType, NamedType, ListType, MapType]


@dataclass
class Field:
    """A field in a message."""

    name: str
    field_type: FieldType
    number: int
    optional: bool = False
    ref: bool = False
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0

    def __repr__(self) -> str:
        modifiers = []
        if self.optional:
            modifiers.append("optional")
        if self.ref:
            modifiers.append("ref")
        mod_str = " ".join(modifiers) + " " if modifiers else ""
        opts_str = f" [{self.options}]" if self.options else ""
        return f"Field({mod_str}{self.field_type} {self.name} = {self.number}{opts_str})"


@dataclass
class Import:
    """An import statement."""

    path: str
    line: int = 0
    column: int = 0

    def __repr__(self) -> str:
        return f'Import("{self.path}")'


@dataclass
class EnumValue:
    """A value in an enum."""

    name: str
    value: int
    line: int = 0
    column: int = 0

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
    options: dict = field(default_factory=dict)
    line: int = 0
    column: int = 0

    def __repr__(self) -> str:
        id_str = f" [id={self.type_id}]" if self.type_id is not None else ""
        nested_str = ""
        if self.nested_messages or self.nested_enums:
            nested_str = f", nested={len(self.nested_messages)}msg+{len(self.nested_enums)}enum"
        opts_str = f", options={len(self.options)}" if self.options else ""
        return f"Message({self.name}{id_str}, fields={self.fields}{nested_str}{opts_str})"

    def get_nested_type(self, name: str) -> Optional[Union["Message", "Enum"]]:
        """Look up a nested type by name."""
        for msg in self.nested_messages:
            if msg.name == name:
                return msg
        for enum in self.nested_enums:
            if enum.name == name:
                return enum
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

    def __repr__(self) -> str:
        id_str = f" [id={self.type_id}]" if self.type_id is not None else ""
        opts_str = f", options={len(self.options)}" if self.options else ""
        return f"Enum({self.name}{id_str}, values={self.values}{opts_str})"


@dataclass
class Schema:
    """The root AST node representing a complete FDL file."""

    package: Optional[str]
    imports: List[Import] = field(default_factory=list)
    enums: List[Enum] = field(default_factory=list)
    messages: List[Message] = field(default_factory=list)
    options: dict = field(default_factory=dict)  # File-level options (java_package, go_package, etc.)

    def __repr__(self) -> str:
        opts = f", options={len(self.options)}" if self.options else ""
        return f"Schema(package={self.package}, imports={len(self.imports)}, enums={len(self.enums)}, messages={len(self.messages)}{opts})"

    def get_option(self, name: str, default: Optional[str] = None) -> Optional[str]:
        """Get a file-level option value."""
        return self.options.get(name, default)

    def get_type(self, name: str) -> Optional[Union[Message, Enum]]:
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

    def _get_top_level_type(self, name: str) -> Optional[Union[Message, Enum]]:
        """Look up a top-level type by simple name."""
        for enum in self.enums:
            if enum.name == name:
                return enum
        for message in self.messages:
            if message.name == name:
                return message
        return None

    def get_all_types(self) -> List[Union[Message, Enum]]:
        """Get all types including nested types (flattened)."""
        result: List[Union[Message, Enum]] = []
        result.extend(self.enums)
        for message in self.messages:
            self._collect_types(message, result)
        return result

    def _collect_types(self, message: Message, result: List[Union[Message, Enum]]):
        """Recursively collect all types from a message and its nested types."""
        result.append(message)
        for nested_enum in message.nested_enums:
            result.append(nested_enum)
        for nested_msg in message.nested_messages:
            self._collect_types(nested_msg, result)

    def validate(self) -> List[str]:
        """Validate the schema and return a list of errors."""
        errors = []

        # Check for duplicate type names at top level
        names = set()
        for enum in self.enums:
            if enum.name in names:
                errors.append(f"Duplicate type name: {enum.name}")
            names.add(enum.name)
        for message in self.messages:
            if message.name in names:
                errors.append(f"Duplicate type name: {message.name}")
            names.add(message.name)

        # Check for duplicate type IDs (including nested types)
        type_ids = {}
        all_types = self.get_all_types()
        for t in all_types:
            if t.type_id is not None:
                if t.type_id in type_ids:
                    errors.append(
                        f"Duplicate type ID @{t.type_id}: "
                        f"{t.name} and {type_ids[t.type_id]}"
                    )
                type_ids[t.type_id] = t.name

        # Validate messages recursively (including nested)
        def validate_message(message: Message, parent_path: str = ""):
            full_name = f"{parent_path}.{message.name}" if parent_path else message.name

            # Check for duplicate nested type names
            nested_names = set()
            for nested_enum in message.nested_enums:
                if nested_enum.name in nested_names:
                    errors.append(f"Duplicate nested type name in {full_name}: {nested_enum.name}")
                nested_names.add(nested_enum.name)
            for nested_msg in message.nested_messages:
                if nested_msg.name in nested_names:
                    errors.append(f"Duplicate nested type name in {full_name}: {nested_msg.name}")
                nested_names.add(nested_msg.name)

            # Check for duplicate field numbers and names
            field_numbers = {}
            field_names = set()
            for f in message.fields:
                if f.number in field_numbers:
                    errors.append(
                        f"Duplicate field number {f.number} in {full_name}: "
                        f"{f.name} and {field_numbers[f.number]}"
                    )
                field_numbers[f.number] = f.name
                if f.name in field_names:
                    errors.append(
                        f"Duplicate field name in {full_name}: {f.name}"
                    )
                field_names.add(f.name)

            # Validate nested enums
            for nested_enum in message.nested_enums:
                validate_enum(nested_enum, full_name)

            # Recursively validate nested messages
            for nested_msg in message.nested_messages:
                validate_message(nested_msg, full_name)

        def validate_enum(enum: Enum, parent_path: str = ""):
            full_name = f"{parent_path}.{enum.name}" if parent_path else enum.name
            value_numbers = {}
            value_names = set()
            for v in enum.values:
                if v.value in value_numbers:
                    errors.append(
                        f"Duplicate enum value {v.value} in {full_name}: "
                        f"{v.name} and {value_numbers[v.value]}"
                    )
                value_numbers[v.value] = v.name
                if v.name in value_names:
                    errors.append(
                        f"Duplicate enum value name in {full_name}: {v.name}"
                    )
                value_names.add(v.name)

        # Validate all top-level enums
        for enum in self.enums:
            validate_enum(enum)

        # Validate all top-level messages (and their nested types)
        for message in self.messages:
            validate_message(message)

        # Check that referenced types exist (supports qualified names and nested type lookup)
        def check_type_ref(field_type: FieldType, context: str, enclosing_message: Optional[Message] = None):
            if isinstance(field_type, NamedType):
                type_name = field_type.name
                found = False

                # First, try to find as a nested type in the enclosing message
                if enclosing_message is not None and "." not in type_name:
                    if enclosing_message.get_nested_type(type_name) is not None:
                        found = True

                # Then, try to find as a top-level or qualified type
                if not found and self.get_type(type_name) is not None:
                    found = True

                if not found:
                    errors.append(f"Unknown type '{type_name}' in {context}")
            elif isinstance(field_type, ListType):
                check_type_ref(field_type.element_type, context, enclosing_message)
            elif isinstance(field_type, MapType):
                check_type_ref(field_type.key_type, context, enclosing_message)
                check_type_ref(field_type.value_type, context, enclosing_message)

        def check_message_refs(message: Message, parent_path: str = ""):
            full_name = f"{parent_path}.{message.name}" if parent_path else message.name
            for f in message.fields:
                check_type_ref(f.field_type, f"{full_name}.{f.name}", message)
            for nested_msg in message.nested_messages:
                check_message_refs(nested_msg, full_name)

        for message in self.messages:
            check_message_refs(message)

        return errors
