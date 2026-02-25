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

"""TypeScript/JavaScript code generator.

Generates pure TypeScript type definitions from FDL IDL files.
Supports messages, enums, unions, and all primitive types.
"""

from pathlib import Path
from typing import List, Optional, Tuple

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.ir.ast import (
    Message,
    Enum,
    Union,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
)
from fory_compiler.ir.types import PrimitiveKind


class TypeScriptGenerator(BaseGenerator):
    """Generates TypeScript type definitions and interfaces from IDL."""

    language_name = "typescript"
    file_extension = ".ts"

    # Mapping from FDL primitive types to TypeScript types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "boolean",
        PrimitiveKind.INT8: "number",
        PrimitiveKind.INT16: "number",
        PrimitiveKind.INT32: "number",
        PrimitiveKind.VARINT32: "number",
        PrimitiveKind.INT64: "bigint | number",
        PrimitiveKind.VARINT64: "bigint | number",
        PrimitiveKind.TAGGED_INT64: "bigint | number",
        PrimitiveKind.UINT8: "number",
        PrimitiveKind.UINT16: "number",
        PrimitiveKind.UINT32: "number",
        PrimitiveKind.VAR_UINT32: "number",
        PrimitiveKind.UINT64: "bigint | number",
        PrimitiveKind.VAR_UINT64: "bigint | number",
        PrimitiveKind.TAGGED_UINT64: "bigint | number",
        PrimitiveKind.FLOAT16: "number",
        PrimitiveKind.BFLOAT16: "number",
        PrimitiveKind.FLOAT32: "number",
        PrimitiveKind.FLOAT64: "number",
        PrimitiveKind.STRING: "string",
        PrimitiveKind.BYTES: "Uint8Array",
        PrimitiveKind.DATE: "Date",
        PrimitiveKind.TIMESTAMP: "Date",
        PrimitiveKind.DURATION: "number",
        PrimitiveKind.DECIMAL: "number",
        PrimitiveKind.ANY: "any",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.indent_str = "  "  # TypeScript uses 2 spaces

    def is_imported_type(self, type_def: object) -> bool:
        """Return True if a type definition comes from an imported IDL file."""
        schema_file = self.schema.source_file

        # If there's no source file set, all types are local (not imported)
        if not schema_file:
            return False

        location = getattr(type_def, "location", None)
        if location is None or not location.file:
            return False

        # If the type's location matches the schema's source file, it's local
        if schema_file == location.file:
            return False

        # Otherwise, try to resolve paths and compare
        try:
            return Path(location.file).resolve() != Path(schema_file).resolve()
        except Exception:
            # If Path resolution fails, compare as strings
            return location.file != schema_file

    def split_imported_types(
        self, items: List[object]
    ) -> Tuple[List[object], List[object]]:
        imported: List[object] = []
        local: List[object] = []
        for item in items:
            if self.is_imported_type(item):
                imported.append(item)
            else:
                local.append(item)
        return imported, local  # Return (imported, local) tuple

    def get_module_name(self) -> str:
        """Get the TypeScript module name from package."""
        if self.package:
            # Convert package name to camelCase file name
            parts = self.package.split(".")
            return self.to_camel_case(parts[-1])
        return "generated"

    def generate_type(self, field_type: FieldType, nullable: bool = False) -> str:
        """Generate TypeScript type string for a field type."""
        type_str = ""

        if isinstance(field_type, PrimitiveType):
            type_str = self.PRIMITIVE_MAP.get(field_type.kind, "any")
        elif isinstance(field_type, NamedType):
            # Check if this NamedType matches a primitive type name
            primitive_name = field_type.name.lower()
            # Map common shorthand names to primitive kinds
            shorthand_map = {
                "float": PrimitiveKind.FLOAT32,
                "double": PrimitiveKind.FLOAT64,
            }
            if primitive_name in shorthand_map:
                type_str = self.PRIMITIVE_MAP.get(shorthand_map[primitive_name], "any")
            else:
                # Check if it matches any primitive kind directly
                for primitive_kind, ts_type in self.PRIMITIVE_MAP.items():
                    if primitive_kind.value == primitive_name:
                        type_str = ts_type
                        break
                if not type_str:
                    # If not a primitive, treat as a message/enum type
                    type_str = self.to_pascal_case(field_type.name)
        elif isinstance(field_type, ListType):
            element_type = self.generate_type(field_type.element_type)
            type_str = f"{element_type}[]"
        elif isinstance(field_type, MapType):
            key_type = self.generate_type(field_type.key_type)
            value_type = self.generate_type(field_type.value_type)
            type_str = f"Record<{key_type}, {value_type}>"
        else:
            type_str = "any"

        if nullable:
            type_str += " | undefined"

        return type_str

    def generate(self) -> List[GeneratedFile]:
        """Generate TypeScript files for the schema."""
        files = []
        files.append(self.generate_module())
        return files

    def generate_module(self) -> GeneratedFile:
        """Generate a TypeScript module with all types."""
        lines = []

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        # Add package comment if present
        if self.package:
            lines.append(f"// Package: {self.package}")
            lines.append("")

        # Generate enums (top-level only)
        _, local_enums = self.split_imported_types(self.schema.enums)
        if local_enums:
            lines.append("// Enums")
            lines.append("")
            for enum in local_enums:
                lines.extend(self.generate_enum(enum))
                lines.append("")

        # Generate unions (top-level only)
        _, local_unions = self.split_imported_types(self.schema.unions)
        if local_unions:
            lines.append("// Unions")
            lines.append("")
            for union in local_unions:
                lines.extend(self.generate_union(union))
                lines.append("")

        # Generate messages (including nested types)
        _, local_messages = self.split_imported_types(self.schema.messages)
        if local_messages:
            lines.append("// Messages")
            lines.append("")
            for message in local_messages:
                lines.extend(self.generate_message(message, indent=0))
                lines.append("")

        # Generate registration function
        lines.extend(self.generate_registration())
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_module_name()}{self.file_extension}",
            content="\n".join(lines),
        )

    def generate_enum(self, enum: Enum, indent: int = 0) -> List[str]:
        """Generate a TypeScript enum."""
        lines = []
        ind = self.indent_str * indent
        comment = self.format_type_id_comment(enum, f"{ind}//")
        if comment:
            lines.append(comment)

        lines.append(f"{ind}export enum {enum.name} {{")
        for value in enum.values:
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"{ind}{self.indent_str}{stripped_name} = {value.value},")
        lines.append(f"{ind}}}")

        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a TypeScript interface for a message."""
        lines = []
        ind = self.indent_str * indent
        lineage = (parent_stack or []) + [message]

        comment = self.format_type_id_comment(message, f"{ind}//")
        if comment:
            lines.append(comment)

        # Generate the main interface first
        lines.append(f"{ind}export interface {message.name} {{")

        # Generate fields
        for field in message.fields:
            field_type = self.generate_type(field.field_type, nullable=field.optional)
            lines.append(
                f"{ind}{self.indent_str}{self.to_camel_case(field.name)}: {field_type};"
            )

        lines.append(f"{ind}}}")

        # Generate nested enums after parent interface
        for nested_enum in message.nested_enums:
            lines.append("")
            lines.extend(self.generate_enum(nested_enum, indent=indent))

        # Generate nested unions after parent interface
        for nested_union in message.nested_unions:
            lines.append("")
            lines.extend(self.generate_union(nested_union, indent=indent))

        # Generate nested messages after parent interface
        for nested_msg in message.nested_messages:
            lines.append("")
            lines.extend(
                self.generate_message(nested_msg, indent=indent, parent_stack=lineage)
            )

        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a TypeScript discriminated union."""
        lines = []
        ind = self.indent_str * indent
        union_name = union.name

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)

        # Generate case enum
        case_enum_name = f"{union_name}Case"
        lines.append(f"{ind}export enum {case_enum_name} {{")
        for field in union.fields:
            field_name_upper = self.to_upper_snake_case(field.name)
            lines.append(f"{ind}{self.indent_str}{field_name_upper} = {field.number},")
        lines.append(f"{ind}}}")
        lines.append("")

        # Generate union type as discriminated union
        union_cases = []
        for field in union.fields:
            field_type_str = self.generate_type(field.field_type)
            case_value = self.to_upper_snake_case(field.name)
            union_cases.append(
                f"{ind}{self.indent_str}| ( {{ case: {case_enum_name}.{case_value}; value: {field_type_str} }} )"
            )

        lines.append(f"{ind}export type {union_name} =")
        lines.extend(union_cases)
        lines.append(f"{ind}{self.indent_str};")

        return lines

    def generate_registration(self) -> List[str]:
        """Generate a registration function."""
        lines = []
        registration_name = (
            f"register{self.to_pascal_case(self.get_module_name())}Types"
        )

        lines.append("// Registration helper")
        lines.append(f"export function {registration_name}(fory: any): void {{")

        # Register enums
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            if self.should_register_by_id(enum):
                type_id = enum.type_id
                lines.append(f"  fory.register({enum.name}, {type_id});")

        # Register messages
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self._generate_message_registration(message, lines)

        # Register unions
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            if self.should_register_by_id(union):
                type_id = union.type_id
                lines.append(f"  fory.registerUnion({union.name}, {type_id});")

        lines.append("}")

        return lines

    def _generate_message_registration(self, message: Message, lines: List[str]):
        """Generate registration for a message and its nested types."""
        # Register nested enums with simple names
        for nested_enum in message.nested_enums:
            if self.should_register_by_id(nested_enum):
                type_id = nested_enum.type_id
                lines.append(f"  fory.register({nested_enum.name}, {type_id});")

        # Register nested unions with simple names
        for nested_union in message.nested_unions:
            if self.should_register_by_id(nested_union):
                type_id = nested_union.type_id
                lines.append(f"  fory.registerUnion({nested_union.name}, {type_id});")

        # Register nested messages recursively
        for nested_msg in message.nested_messages:
            self._generate_message_registration(nested_msg, lines)

        # Register the message itself with simple name
        if self.should_register_by_id(message):
            type_id = message.type_id
            lines.append(f"  fory.register({message.name}, {type_id});")
