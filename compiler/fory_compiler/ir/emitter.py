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

"""Emit Fory IDL as FDL source."""

from typing import Dict, List, Optional

from fory_compiler.ir.ast import (
    Schema,
    Message,
    Enum,
    Union,
    EnumValue,
    Field,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
)
from fory_compiler.ir.types import PrimitiveKind


class FDLEmitter:
    """Emit Fory IDL as FDL text for debugging."""

    def __init__(self, schema: Schema):
        self.schema = schema
        self.indent = "    "

    def emit(self) -> str:
        """Generate FDL source from the schema."""
        lines: List[str] = []

        if self.schema.package:
            lines.append(f"package {self.schema.package};")
            lines.append("")

        for name, value in self.schema.options.items():
            lines.append(self._emit_file_option(name, value))
        if self.schema.options:
            lines.append("")

        for imp in self.schema.imports:
            lines.append(f'import "{imp.path}";')
        if self.schema.imports:
            lines.append("")

        for enum in self.schema.enums:
            lines.extend(self._emit_enum(enum))
            lines.append("")

        for union in self.schema.unions:
            lines.extend(self._emit_union(union))
            lines.append("")

        for message in self.schema.messages:
            lines.extend(self._emit_message(message))
            lines.append("")

        return "\n".join(lines).rstrip() + "\n"

    def _emit_file_option(self, name: str, value) -> str:
        return f"option {name} = {self._format_value(value)};"

    def _emit_enum(self, enum: Enum, level: int = 0) -> List[str]:
        indent = self.indent * level
        lines: List[str] = []

        header_opts = self._collect_type_options(enum.type_id, enum.options)
        lines.append(
            f"{indent}enum {enum.name}{self._emit_inline_options(header_opts)} {{"
        )

        for value in enum.values:
            lines.append(f"{indent}{self.indent}{self._emit_enum_value(value)}")

        lines.append(f"{indent}}}")
        return lines

    def _emit_enum_value(self, value: EnumValue) -> str:
        return f"{value.name} = {value.value};"

    def _emit_message(self, msg: Message, level: int = 0) -> List[str]:
        indent = self.indent * level
        lines: List[str] = []

        header_opts = self._collect_type_options(msg.type_id, msg.options)
        lines.append(
            f"{indent}message {msg.name}{self._emit_inline_options(header_opts)} {{"
        )

        for enum in msg.nested_enums:
            lines.extend(self._emit_enum(enum, level + 1))

        for union in msg.nested_unions:
            lines.extend(self._emit_union(union, level + 1))

        for nested in msg.nested_messages:
            lines.extend(self._emit_message(nested, level + 1))

        for field in msg.fields:
            lines.append(f"{indent}{self.indent}{self._emit_field(field)}")

        lines.append(f"{indent}}}")
        return lines

    def _emit_union(self, union: Union, level: int = 0) -> List[str]:
        indent = self.indent * level
        lines: List[str] = []

        header_opts = self._collect_type_options(union.type_id, union.options)
        lines.append(
            f"{indent}union {union.name}{self._emit_inline_options(header_opts)} {{"
        )

        for field in union.fields:
            lines.append(f"{indent}{self.indent}{self._emit_union_field(field)}")

        lines.append(f"{indent}}}")
        return lines

    def _emit_union_field(self, field: Field) -> str:
        return f"{self._emit_type(field.field_type)} {field.name} = {field.number};"

    def _emit_field(self, field: Field) -> str:
        parts: List[str] = []
        if field.optional:
            parts.append("optional")
        if field.ref:
            parts.append(self._emit_ref_modifier(field.ref_options))
        parts.append(self._emit_type(field.field_type))
        parts.append(field.name)
        parts.append("=")
        parts.append(str(field.number))

        options = dict(field.options)
        if options:
            parts.append(self._emit_inline_options(options))
        return " ".join(parts) + ";"

    def _emit_type(self, field_type: FieldType) -> str:
        if isinstance(field_type, PrimitiveType):
            return self._emit_primitive_name(field_type.kind)
        if isinstance(field_type, NamedType):
            return field_type.name
        if isinstance(field_type, ListType):
            parts: List[str] = []
            if field_type.element_optional:
                parts.append("optional")
            if field_type.element_ref:
                parts.append(self._emit_ref_modifier(field_type.element_ref_options))
            parts.append(self._emit_type(field_type.element_type))
            return f"list<{' '.join(parts)}>"
        if isinstance(field_type, MapType):
            key = self._emit_type(field_type.key_type)
            value = self._emit_type(field_type.value_type)
            if field_type.value_ref:
                value = (
                    f"{self._emit_ref_modifier(field_type.value_ref_options)} {value}"
                )
            return f"map<{key}, {value}>"
        return "unknown"

    def _emit_ref_modifier(self, options: Dict[str, object]) -> str:
        if not options:
            return "ref"
        name_map = {
            "weak_ref": "weak",
            "thread_safe_pointer": "thread_safe",
        }
        parts = [
            f"{name_map.get(k, k)}={self._format_value(v)}" for k, v in options.items()
        ]
        return f"ref({', '.join(parts)})"

    def _emit_primitive_name(self, kind: PrimitiveKind) -> str:
        if kind == PrimitiveKind.VARINT32:
            return "int32"
        if kind == PrimitiveKind.VARINT64:
            return "int64"
        if kind == PrimitiveKind.INT32:
            return "fixed_int32"
        if kind == PrimitiveKind.INT64:
            return "fixed_int64"
        if kind == PrimitiveKind.VAR_UINT32:
            return "uint32"
        if kind == PrimitiveKind.VAR_UINT64:
            return "uint64"
        if kind == PrimitiveKind.UINT32:
            return "fixed_uint32"
        if kind == PrimitiveKind.UINT64:
            return "fixed_uint64"
        return kind.value

    def _emit_inline_options(self, options: Dict[str, object]) -> str:
        if not options:
            return ""
        parts = [f"{k}={self._format_value(v)}" for k, v in options.items()]
        return f" [{', '.join(parts)}]"

    def _format_value(self, value) -> str:
        if isinstance(value, bool):
            return "true" if value else "false"
        if isinstance(value, str):
            return f'"{value}"'
        return str(value)

    def _collect_type_options(
        self, type_id: Optional[int], options: Dict[str, object]
    ) -> Dict[str, object]:
        result = dict(options)
        if type_id is not None:
            result.setdefault("id", type_id)
        return result
