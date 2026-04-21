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

"""Dart code generator."""

import posixpath
from pathlib import Path, PurePosixPath
from typing import Dict, List, Optional, Set, Tuple

from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.ir.ast import (
    Enum,
    EnumValue,
    Field,
    FieldType,
    ListType,
    MapType,
    Message,
    NamedType,
    PrimitiveType,
    Schema,
    Union,
)
from fory_compiler.ir.types import PrimitiveKind


class DartGenerator(BaseGenerator):
    language_name = "dart"
    file_extension = ".dart"

    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "Int8",
        PrimitiveKind.INT16: "Int16",
        PrimitiveKind.INT32: "Int32",
        PrimitiveKind.VARINT32: "Int32",
        PrimitiveKind.INT64: "int",
        PrimitiveKind.VARINT64: "int",
        PrimitiveKind.TAGGED_INT64: "int",
        PrimitiveKind.UINT8: "UInt8",
        PrimitiveKind.UINT16: "UInt16",
        PrimitiveKind.UINT32: "UInt32",
        PrimitiveKind.VAR_UINT32: "UInt32",
        PrimitiveKind.UINT64: "int",
        PrimitiveKind.VAR_UINT64: "int",
        PrimitiveKind.TAGGED_UINT64: "int",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.BFLOAT16: "Float16",
        PrimitiveKind.FLOAT32: "Float32",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "Uint8List",
        PrimitiveKind.DATE: "LocalDate",
        PrimitiveKind.TIMESTAMP: "Timestamp",
        PrimitiveKind.DECIMAL: "Decimal",
        PrimitiveKind.ANY: "Object?",
    }

    DART_KEYWORDS = {
        "abstract",
        "as",
        "assert",
        "async",
        "await",
        "base",
        "break",
        "case",
        "catch",
        "class",
        "const",
        "continue",
        "covariant",
        "default",
        "deferred",
        "do",
        "dynamic",
        "else",
        "enum",
        "export",
        "extends",
        "extension",
        "external",
        "factory",
        "false",
        "final",
        "finally",
        "for",
        "Function",
        "get",
        "hide",
        "if",
        "implements",
        "import",
        "in",
        "interface",
        "is",
        "late",
        "library",
        "mixin",
        "new",
        "null",
        "of",
        "on",
        "operator",
        "part",
        "required",
        "rethrow",
        "return",
        "sealed",
        "set",
        "show",
        "static",
        "super",
        "switch",
        "sync",
        "this",
        "throw",
        "true",
        "try",
        "typedef",
        "var",
        "void",
        "when",
        "while",
        "with",
        "yield",
    }

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self._qualified_names: Dict[int, str] = {}
        self._schema_cache: Dict[Path, Schema] = {}
        self._requires_ref_class: Set[int] = set()
        self._build_indexes()

    def generate(self) -> List[GeneratedFile]:
        return [self.generate_file()]

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        return self.dart_type(field_type, nullable, parent_stack)

    def generate_file(self) -> GeneratedFile:
        lines: List[str] = [
            self.get_license_header("//"),
            "",
            "// ignore_for_file: camel_case_types, constant_identifier_names, non_constant_identifier_names, unnecessary_brace_in_string_interps, avoid_init_to_null",
            "",
            "library;",
            "",
            "import 'dart:typed_data';",
            "",
            "import 'package:fory/fory.dart';",
        ]
        imports = self._import_statements()
        if imports:
            lines.append("")
            lines.extend(imports)
        lines.extend(
            [
                "",
                f"part '{self.part_file_name()}';",
            ]
        )
        lines.append("")

        indent = 0

        for enum in self.schema.enums:
            if not self.is_imported_type(enum):
                lines.extend(self.generate_enum(enum, indent))
                lines.append("")
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                lines.extend(self.generate_union(union, indent))
                lines.append("")
        for message in self.schema.messages:
            if not self.is_imported_type(message):
                lines.extend(self.generate_message(message, indent))
                lines.append("")

        lines.extend(self.generate_registration_type(indent))
        lines.append("")
        return GeneratedFile(self.output_file_path(), "\n".join(lines).rstrip() + "\n")

    def output_file_path(self) -> str:
        return str(self._schema_output_path(self.schema))

    def module_file_name(self) -> str:
        if self.schema.source_file and not self.schema.source_file.startswith("<"):
            stem = Path(self.schema.source_file).stem
            if self.schema.package:
                leaf = self.schema.package.split(".")[-1]
                if leaf and leaf != stem:
                    stem = leaf
            return f"{stem}.dart"
        return (
            f"{self.schema.package.replace('.', '_')}.dart"
            if self.schema.package
            else "generated.dart"
        )

    def part_file_name(self) -> str:
        return f"{Path(self.module_file_name()).stem}.fory.dart"

    def generated_api_name(self) -> str:
        return f"{self.to_pascal_case(Path(self.module_file_name()).stem)}Fory"

    def safe_identifier(self, name: str) -> str:
        if not name:
            return "_"
        if name[0].isdigit():
            name = f"_{name}"
        return f"{name}_" if name in self.DART_KEYWORDS else name

    def safe_type_identifier(self, name: str) -> str:
        return self.safe_identifier(name)

    def _top_level_names(self) -> Set[str]:
        names: Set[str] = set()
        for defs in (self.schema.enums, self.schema.unions, self.schema.messages):
            for item in defs:
                names.add(self.safe_type_identifier(self.to_pascal_case(item.name)))
        return names

    def _build_indexes(self) -> None:
        def visit_message(message: Message, parents: List[str]) -> None:
            q = ".".join(parents + [message.name])
            self._qualified_names[id(message)] = q
            for field in message.fields:
                value_ref = (
                    isinstance(field.field_type, MapType) and field.field_type.value_ref
                )
                if field.ref or field.element_ref or value_ref:
                    self._requires_ref_class.add(id(message))
            for enum in message.nested_enums:
                self._qualified_names[id(enum)] = ".".join(
                    parents + [message.name, enum.name]
                )
            for union in message.nested_unions:
                self._qualified_names[id(union)] = ".".join(
                    parents + [message.name, union.name]
                )
            for nested in message.nested_messages:
                visit_message(nested, parents + [message.name])

        for enum in self.schema.enums:
            self._qualified_names[id(enum)] = enum.name
        for union in self.schema.unions:
            self._qualified_names[id(union)] = union.name
        for message in self.schema.messages:
            visit_message(message, [])

    def resolve_type(
        self,
        name: str,
        parent_stack: Optional[List[Message]] = None,
    ):
        parent_stack = parent_stack or []
        if "." in name:
            resolved = self.schema.get_type(name)
            if resolved is not None:
                return resolved
        else:
            for message in reversed(parent_stack):
                nested = message.get_nested_type(name)
                if nested is not None:
                    return nested
            resolved = self.schema.get_type(name)
            if resolved is not None:
                return resolved

        exact_match = None
        suffix_matches: List[object] = []
        leaf_matches: List[object] = []
        local_name_matches: List[object] = []
        candidate_local_name = "_".join(
            self.safe_type_identifier(self.to_pascal_case(part))
            for part in name.split(".")
            if part
        )
        for type_def in self._iter_type_defs():
            qualified_name = self._qualified_names[id(type_def)]
            local_name = self.local_name(type_def)
            if qualified_name == name:
                exact_match = type_def
                break
            if qualified_name.endswith(f".{name}"):
                suffix_matches.append(type_def)
            if qualified_name.split(".")[-1] == name:
                leaf_matches.append(type_def)
            if local_name == candidate_local_name or local_name.endswith(
                f"_{candidate_local_name}"
            ):
                local_name_matches.append(type_def)
        if exact_match is not None:
            return exact_match
        if len(suffix_matches) == 1:
            return suffix_matches[0]
        if len(leaf_matches) == 1:
            return leaf_matches[0]
        if len(local_name_matches) == 1:
            return local_name_matches[0]
        return None

    def _iter_type_defs(self):
        for enum in self.schema.enums:
            yield enum
        for union in self.schema.unions:
            yield union

        def visit_message(message: Message):
            yield message
            for enum in message.nested_enums:
                yield enum
            for union in message.nested_unions:
                yield union
            for nested in message.nested_messages:
                yield from visit_message(nested)

        for message in self.schema.messages:
            yield from visit_message(message)

    def is_imported_type(self, type_def: object) -> bool:
        if not self.schema.source_file:
            return False
        location = getattr(type_def, "location", None)
        file = getattr(location, "file", None) if location else None
        if not file:
            return False
        try:
            return Path(file).resolve() != Path(self.schema.source_file).resolve()
        except Exception:
            return file != self.schema.source_file

    def _load_schema(self, file_path: str) -> Optional[Schema]:
        path = Path(file_path).resolve()
        if path in self._schema_cache:
            return self._schema_cache[path]
        try:
            schema = parse_idl_file(path)
        except Exception:
            return None
        self._schema_cache[path] = schema
        return schema

    def _import_statements(self) -> List[str]:
        seen: Dict[str, Tuple[str, str]] = {}
        for item in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(item):
                continue
            file = item.location.file
            schema = self._load_schema(file)
            if schema is None:
                continue
            path = self._relative_import_path(schema)
            alias = self.safe_identifier(
                schema.package.replace(".", "_") if schema.package else Path(file).stem
            )
            seen[file] = (path, alias)
        return [f"import '{path}' as {alias};" for path, alias in seen.values()]

    def _schema_output_path(self, schema: Schema) -> PurePosixPath:
        name = self._module_file_name_for_schema(schema)
        if schema.package:
            return PurePosixPath(*schema.package.split(".")) / name
        return PurePosixPath(name)

    def _relative_import_path(self, schema: Schema) -> str:
        current_parent = str(self._schema_output_path(self.schema).parent)
        target = str(self._schema_output_path(schema))
        base = "." if current_parent == "." else current_parent
        return posixpath.relpath(target, base)

    def _module_file_name_for_schema(self, schema: Schema) -> str:
        if schema.source_file and not schema.source_file.startswith("<"):
            stem = Path(schema.source_file).stem
            if schema.package:
                leaf = schema.package.split(".")[-1]
                if leaf and leaf != stem:
                    stem = leaf
            return f"{stem}.dart"
        return (
            f"{schema.package.replace('.', '_')}.dart"
            if schema.package
            else "generated.dart"
        )

    def local_name(self, type_def: object) -> str:
        q = self._qualified_names[id(type_def)]
        return "_".join(
            self.safe_type_identifier(self.to_pascal_case(p)) for p in q.split(".")
        )

    def ref_name(self, type_def: object) -> str:
        local_type_name = self.local_name(type_def)
        if self.is_imported_type(type_def):
            schema = self._load_schema(type_def.location.file)
            alias = self.safe_identifier(
                schema.package.replace(".", "_")
                if schema and schema.package
                else Path(type_def.location.file).stem
            )
            return f"{alias}.{local_type_name}"
        return local_type_name

    def registration_type_name(self, type_def: object) -> str:
        return self._qualified_names[id(type_def)]

    def _dart_string_literal(self, value: str) -> str:
        escaped = value.replace("\\", "\\\\").replace("'", "\\'")
        return f"'{escaped}'"

    def _registration_defaults(self, type_def: object) -> Tuple[str, str, str]:
        if self.should_register_by_id(type_def):
            return str(type_def.type_id), "null", "null"
        return (
            "null",
            self._dart_string_literal(self.package or ""),
            self._dart_string_literal(self.registration_type_name(type_def)),
        )

    def _supports_direct_container_cast(
        self,
        field_type: FieldType,
        parent_stack: Optional[List[Message]] = None,
    ) -> bool:
        del parent_stack
        return not isinstance(field_type, (ListType, MapType))

    def _conversion_expression(
        self,
        field_type: FieldType,
        value_expr: str,
        nullable: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        parent_stack = parent_stack or []
        if nullable:
            converted = self._conversion_expression(
                field_type,
                value_expr,
                False,
                parent_stack,
            )
            return f"{value_expr} == null ? null : {converted}"
        if isinstance(field_type, PrimitiveType):
            return f"{value_expr} as {self.dart_type(field_type, False, parent_stack)}"
        if isinstance(field_type, NamedType):
            return f"{value_expr} as {self.dart_type(field_type, False, parent_stack)}"
        if isinstance(field_type, ListType):
            element_type = self.dart_type(
                field_type.element_type,
                field_type.element_optional,
                parent_stack,
            )
            list_type = self._typed_array_or_list(field_type, parent_stack)
            if list_type != f"List<{element_type}>":
                return f"{value_expr} as {list_type}"
            if self._supports_direct_container_cast(
                field_type.element_type,
                parent_stack,
            ):
                return f"List.castFrom<dynamic, {element_type}>({value_expr} as List)"
            converted_item = self._conversion_expression(
                field_type.element_type,
                "item",
                field_type.element_optional,
                parent_stack,
            )
            return f"List<{element_type}>.of((({value_expr} as List)).map((item) => {converted_item}))"
        if isinstance(field_type, MapType):
            key_type = self.dart_type(field_type.key_type, False, parent_stack)
            value_type = self.dart_type(field_type.value_type, False, parent_stack)
            if self._supports_direct_container_cast(
                field_type.key_type, parent_stack
            ) and self._supports_direct_container_cast(
                field_type.value_type, parent_stack
            ):
                return f"Map.castFrom<dynamic, dynamic, {key_type}, {value_type}>({value_expr} as Map)"
            converted_key = self._conversion_expression(
                field_type.key_type,
                "key",
                False,
                parent_stack,
            )
            converted_value = self._conversion_expression(
                field_type.value_type,
                "mapValue",
                False,
                parent_stack,
            )
            return f"Map<{key_type}, {value_type}>.of((({value_expr} as Map)).map((key, mapValue) => MapEntry({converted_key}, {converted_value})))"
        return f"{value_expr} as {self.dart_type(field_type, False, parent_stack)}"

    def dart_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            base = self.PRIMITIVE_MAP[field_type.kind]
        elif isinstance(field_type, ListType):
            base = self._typed_array_or_list(field_type, parent_stack)
        elif isinstance(field_type, MapType):
            base = (
                f"Map<{self.dart_type(field_type.key_type, parent_stack=parent_stack)}, "
                f"{self.dart_type(field_type.value_type, parent_stack=parent_stack)}>"
            )
        elif isinstance(field_type, NamedType):
            resolved = self.resolve_type(field_type.name, parent_stack)
            base = (
                self.ref_name(resolved)
                if resolved is not None
                else self.safe_type_identifier(
                    self.to_pascal_case(field_type.name.split(".")[-1])
                )
            )
        else:
            base = "Object?"
        if nullable and base != "Object?" and not base.endswith("?"):
            return f"{base}?"
        return base

    def _typed_array_or_list(
        self,
        field_type: ListType,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        if (
            not field_type.element_optional
            and not field_type.element_ref
            and isinstance(field_type.element_type, PrimitiveType)
        ):
            array_map = {
                PrimitiveKind.INT8: "Int8List",
                PrimitiveKind.INT16: "Int16List",
                PrimitiveKind.INT32: "Int32List",
                PrimitiveKind.VARINT32: "Int32List",
                PrimitiveKind.INT64: "Int64List",
                PrimitiveKind.VARINT64: "Int64List",
                PrimitiveKind.TAGGED_INT64: "Int64List",
                PrimitiveKind.UINT8: "Uint8List",
                PrimitiveKind.UINT16: "Uint16List",
                PrimitiveKind.UINT32: "Uint32List",
                PrimitiveKind.VAR_UINT32: "Uint32List",
                PrimitiveKind.UINT64: "Uint64List",
                PrimitiveKind.VAR_UINT64: "Uint64List",
                PrimitiveKind.TAGGED_UINT64: "Uint64List",
                PrimitiveKind.FLOAT32: "Float32List",
                PrimitiveKind.FLOAT64: "Float64List",
            }
            mapped = array_map.get(field_type.element_type.kind)
            if mapped:
                return mapped
        return f"List<{self.dart_type(field_type.element_type, field_type.element_optional, parent_stack)}>"

    def _default_value_for_type(
        self,
        field_type: FieldType,
        optional: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        parent_stack = parent_stack or []
        if optional:
            return "null"
        t = field_type
        if isinstance(t, PrimitiveType):
            return {
                PrimitiveKind.BOOL: "false",
                PrimitiveKind.INT8: "Int8(0)",
                PrimitiveKind.INT16: "Int16(0)",
                PrimitiveKind.INT32: "Int32(0)",
                PrimitiveKind.VARINT32: "Int32(0)",
                PrimitiveKind.INT64: "0",
                PrimitiveKind.VARINT64: "0",
                PrimitiveKind.TAGGED_INT64: "0",
                PrimitiveKind.UINT8: "UInt8(0)",
                PrimitiveKind.UINT16: "UInt16(0)",
                PrimitiveKind.UINT32: "UInt32(0)",
                PrimitiveKind.VAR_UINT32: "UInt32(0)",
                PrimitiveKind.UINT64: "0",
                PrimitiveKind.VAR_UINT64: "0",
                PrimitiveKind.TAGGED_UINT64: "0",
                PrimitiveKind.FLOAT16: "Float16.zero",
                PrimitiveKind.BFLOAT16: "Float16.zero",
                PrimitiveKind.FLOAT32: "Float32(0)",
                PrimitiveKind.FLOAT64: "0.0",
                PrimitiveKind.STRING: "''",
                PrimitiveKind.BYTES: "Uint8List(0)",
                PrimitiveKind.DATE: "const LocalDate(1970, 1, 1)",
                PrimitiveKind.TIMESTAMP: "Timestamp(0, 0)",
                PrimitiveKind.DECIMAL: "const Decimal.zero()",
                PrimitiveKind.ANY: "null",
            }[t.kind]
        if isinstance(t, ListType):
            arr = self._typed_array_or_list(t, parent_stack)
            return (
                f"{arr}(0)"
                if arr.endswith("List") and arr != "List"
                else f"<{self.dart_type(t.element_type, t.element_optional, parent_stack)}>[]"
            )
        if isinstance(t, MapType):
            key_type = self.dart_type(t.key_type, parent_stack=parent_stack)
            value_type = self.dart_type(t.value_type, parent_stack=parent_stack)
            return f"<{key_type}, {value_type}>{{}}"
        if isinstance(t, NamedType):
            resolved = self.resolve_type(t.name, parent_stack)
            if isinstance(resolved, Enum):
                first = resolved.values[0]
                return (
                    f"{self.ref_name(resolved)}.{self.enum_case_name(resolved, first)}"
                )
            if isinstance(resolved, Union):
                first = resolved.fields[0]
                case_name = self.safe_identifier(self.to_camel_case(first.name))
                payload_default = self._default_value_for_type(
                    first.field_type, parent_stack=parent_stack
                )
                return f"{self.ref_name(resolved)}.{case_name}({payload_default})"
            if resolved is not None:
                return f"{self.ref_name(resolved)}()"
        return "null"

    def default_value(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        return self._default_value_for_type(
            field.field_type,
            field.optional,
            parent_stack,
        )

    def struct_annotation(self, message: Message) -> str:
        if message.options.get("evolving", True):
            return "@ForyStruct()"
        return "@ForyStruct(evolving: false)"

    def field_annotations(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        annotations: List[str] = []
        numeric_annotation = self.numeric_annotation(field.field_type)
        if numeric_annotation is not None:
            annotations.append(numeric_annotation)
        args: List[str] = []
        if field.tag_id is not None:
            args.append(f"id: {field.tag_id}")
        if field.ref:
            args.append("ref: true")
        if args:
            annotations.append(f"@ForyField({', '.join(args)})")
        # Emit container TypeSpec annotations for nested ref tracking.
        if isinstance(field.field_type, ListType) and (
            field.element_ref or field.field_type.element_ref
        ):
            annotations.append("@ListType(element: ValueType.ref())")
        if isinstance(field.field_type, MapType) and field.field_type.value_ref:
            annotations.append("@MapType(value: ValueType.ref())")
        return annotations

    def numeric_annotation(self, field_type: FieldType) -> Optional[str]:
        if not isinstance(field_type, PrimitiveType):
            return None
        return {
            PrimitiveKind.INT32: "@Int32Type(compress: false)",
            PrimitiveKind.INT64: "@Int64Type(encoding: LongEncoding.fixed)",
            PrimitiveKind.TAGGED_INT64: "@Int64Type(encoding: LongEncoding.tagged)",
            PrimitiveKind.VAR_UINT32: "@Uint32Type()",
            PrimitiveKind.UINT64: "@Uint64Type(encoding: LongEncoding.fixed)",
            PrimitiveKind.VAR_UINT64: "@Uint64Type()",
            PrimitiveKind.TAGGED_UINT64: "@Uint64Type(encoding: LongEncoding.tagged)",
        }.get(field_type.kind)

    def enum_case_name(self, enum: Enum, value: EnumValue) -> str:
        name = value.name
        prefix = f"{enum.name}_".upper()
        if name.upper().startswith(prefix):
            name = name[len(prefix) :]
        return self.safe_identifier(self.to_camel_case(name.lower()))

    def generate_enum(self, enum: Enum, indent: int) -> List[str]:
        name = self.local_name(enum)
        lines = [f"{self.indent_str * indent}enum {name} {{"]
        for i, value in enumerate(enum.values):
            suffix = "," if i < len(enum.values) - 1 else ";"
            lines.append(
                f"{self.indent_str * (indent + 1)}{self.enum_case_name(enum, value)}{suffix}"
            )
        lines.extend(
            [
                "",
                f"{self.indent_str * (indent + 1)}int get rawValue => switch (this) {{",
            ]
        )
        for value in enum.values:
            lines.append(
                f"{self.indent_str * (indent + 2)}{name}.{self.enum_case_name(enum, value)} => {value.value},"
            )
        lines.extend(
            [
                f"{self.indent_str * (indent + 1)}}};",
                "",
                f"{self.indent_str * (indent + 1)}static {name} fromRawValue(int value) => switch (value) {{",
            ]
        )
        for value in enum.values:
            lines.append(
                f"{self.indent_str * (indent + 2)}{value.value} => {name}.{self.enum_case_name(enum, value)},"
            )
        lines.extend(
            [
                f"{self.indent_str * (indent + 2)}_ => throw StateError('Unknown {name} raw value ${{value}}.'),",
                f"{self.indent_str * (indent + 1)}}};",
                f"{self.indent_str * indent}}}",
            ]
        )
        return lines

    def generate_union(
        self,
        union: Union,
        indent: int,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        parent_stack = parent_stack or []
        name = self.local_name(union)
        full = self.ref_name(union)
        case_name = f"{name}Case"
        lines = [f"{self.indent_str * indent}enum {case_name} {{"]
        for i, field in enumerate(union.fields):
            suffix = "," if i < len(union.fields) - 1 else ";"
            lines.append(
                f"{self.indent_str * (indent + 1)}{self.safe_identifier(self.to_camel_case(field.name))}{suffix}"
            )
        lines.extend(
            [
                "",
                f"{self.indent_str * (indent + 1)}int get id => switch (this) {{",
            ]
        )
        for field in union.fields:
            case = self.safe_identifier(self.to_camel_case(field.name))
            lines.append(
                f"{self.indent_str * (indent + 2)}{case_name}.{case} => {field.number},"
            )
        lines.extend(
            [
                f"{self.indent_str * (indent + 1)}}};",
                f"{self.indent_str * indent}}}",
                "",
                f"{self.indent_str * indent}@ForyUnion()",
                f"{self.indent_str * indent}final class {name} {{",
                f"{self.indent_str * (indent + 1)}final {case_name} _case;",
                f"{self.indent_str * (indent + 1)}final Object? _value;",
                "",
                f"{self.indent_str * (indent + 1)}const {name}._(this._case, this._value);",
                "",
            ]
        )
        for field in union.fields:
            case = self.safe_identifier(self.to_camel_case(field.name))
            lines.append(
                f"{self.indent_str * (indent + 1)}factory {name}.{case}({self.dart_type(field.field_type, parent_stack=parent_stack)} value) => {name}._({case_name}.{case}, value);"
            )
        lines.extend(
            [
                "",
                f"{self.indent_str * (indent + 1)}{case_name} get caseValue => _case;",
                f"{self.indent_str * (indent + 1)}int get caseId => _case.id;",
                f"{self.indent_str * (indent + 1)}Object? get value => _value;",
                "",
            ]
        )
        for field in union.fields:
            case = self.safe_identifier(self.to_camel_case(field.name))
            t = self.dart_type(field.field_type, parent_stack=parent_stack)
            lines.extend(
                [
                    f"{self.indent_str * (indent + 1)}bool get is{self.to_pascal_case(field.name)} => _case == {case_name}.{case};",
                    f"{self.indent_str * (indent + 1)}{t} get {case}Value {{",
                    f"{self.indent_str * (indent + 2)}if (_case != {case_name}.{case}) throw StateError('Expected {name}.{case}, got $_case.');",
                    f"{self.indent_str * (indent + 2)}return _value as {t};",
                    f"{self.indent_str * (indent + 1)}}}",
                    "",
                ]
            )
        lines.extend(
            [
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}bool operator ==(Object other) => identical(this, other) || (other is {name} && other._case == _case && other._value == _value);",
                "",
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}int get hashCode => Object.hash(_case, _value);",
                "",
                f"{self.indent_str * (indent + 1)}Uint8List toBytes() => ForyRegistration.getFory().serialize(this);",
                f"{self.indent_str * (indent + 1)}static {full} fromBytes(Uint8List bytes) => ForyRegistration.getFory().deserialize<{full}>(bytes);",
                f"{self.indent_str * indent}}}",
                "",
                f"{self.indent_str * indent}final class _{name}ForySerializer extends UnionSerializer<{full}> {{",
                f"{self.indent_str * (indent + 1)}const _{name}ForySerializer();",
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}void write(WriteContext context, {full} value) {{",
                f"{self.indent_str * (indent + 2)}context.writeVarUint32(value.caseId);",
                f"{self.indent_str * (indent + 2)}context.writeRef(value.value);",
                f"{self.indent_str * (indent + 1)}}}",
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}{full} read(ReadContext context) {{",
                f"{self.indent_str * (indent + 2)}final caseId = context.readVarUint32();",
                f"{self.indent_str * (indent + 2)}final value = context.readRef();",
            ]
        )
        for field in union.fields:
            case = self.safe_identifier(self.to_camel_case(field.name))
            converted_value = self._conversion_expression(
                field.field_type,
                "value",
                field.optional,
                parent_stack,
            )
            lines.append(
                f"{self.indent_str * (indent + 2)}if (caseId == {field.number}) return {name}.{case}({converted_value});"
            )
        lines.extend(
            [
                f"{self.indent_str * (indent + 2)}throw StateError('Unknown {name} case id ${{caseId}}.');",
                f"{self.indent_str * (indent + 1)}}}",
                f"{self.indent_str * indent}}}",
            ]
        )
        return lines

    def generate_message(
        self,
        message: Message,
        indent: int,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        parent_stack = parent_stack or []
        current_stack = parent_stack + [message]
        lines: List[str] = []
        for enum in message.nested_enums:
            lines.extend(self.generate_enum(enum, indent))
            lines.append("")
        for union in message.nested_unions:
            lines.extend(self.generate_union(union, indent, current_stack))
            lines.append("")
        for nested in message.nested_messages:
            lines.extend(self.generate_message(nested, indent, current_stack))
            lines.append("")

        name = self.local_name(message)
        full = self.ref_name(message)
        lines.extend(
            [
                f"{self.indent_str * indent}{self.struct_annotation(message)}",
                f"{self.indent_str * indent}final class {name} {{",
                f"{self.indent_str * (indent + 1)}{name}();",
                "",
            ]
        )
        for field in message.fields:
            for annotation in self.field_annotations(field, current_stack):
                lines.append(f"{self.indent_str * (indent + 1)}{annotation}")
            lines.append(
                f"{self.indent_str * (indent + 1)}{self.dart_type(field.field_type, field.optional, current_stack)} {self.safe_identifier(self.to_camel_case(field.name))} = {self.default_value(field, current_stack)};"
            )
        lines.extend(
            [
                "",
                f"{self.indent_str * (indent + 1)}Uint8List toBytes() => ForyRegistration.getFory().serialize(this);",
                f"{self.indent_str * (indent + 1)}static {full} fromBytes(Uint8List bytes) => ForyRegistration.getFory().deserialize<{full}>(bytes);",
                "",
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}bool operator ==(Object other) => identical(this, other) || (other is {name}"
                + "".join(
                    f" && other.{self.safe_identifier(self.to_camel_case(f.name))} == {self.safe_identifier(self.to_camel_case(f.name))}"
                    for f in message.fields
                )
                + ");",
                "",
                f"{self.indent_str * (indent + 1)}@override",
                f"{self.indent_str * (indent + 1)}int get hashCode => Object.hashAll([{', '.join(self.safe_identifier(self.to_camel_case(f.name)) for f in message.fields)}]);",
                f"{self.indent_str * indent}}}",
            ]
        )
        return lines

    def _field_info_lines(
        self,
        field: Field,
        indent: int,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        id_literal = str(field.number) if field.tag_id is not None else "null"
        ident_literal = (
            str(field.number)
            if field.tag_id is not None
            else self.to_snake_case(field.name)
        )
        lines = [
            f"{self.indent_str * indent}GeneratedFieldInfo(",
            f"{self.indent_str * (indent + 1)}name: '{self.safe_identifier(self.to_camel_case(field.name))}',",
            f"{self.indent_str * (indent + 1)}identifier: '{ident_literal}',",
            f"{self.indent_str * (indent + 1)}id: {id_literal},",
            f"{self.indent_str * (indent + 1)}fieldType: {self._field_type_literal(field, indent + 1, parent_stack)},",
            f"{self.indent_str * indent})",
        ]
        return lines

    def _field_type_literal(
        self,
        field: Field,
        indent: int,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        return self._field_type_literal_from_type(
            field.field_type,
            field.optional,
            field.ref,
            indent,
            parent_stack,
        )

    def _field_type_literal_from_type(
        self,
        field_type: FieldType,
        nullable: bool,
        ref: bool,
        indent: int,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        parent_stack = parent_stack or []
        type_expr, type_id = self._type_expr_and_id(field_type, parent_stack)
        args: List[str] = []
        if isinstance(field_type, ListType):
            child_ref = field_type.element_ref
            if isinstance(field_type.element_type, NamedType):
                resolved = self.resolve_type(field_type.element_type.name, parent_stack)
                if (
                    isinstance(resolved, Message)
                    and id(resolved) in self._requires_ref_class
                ):
                    child_ref = True
            args.append(
                self._field_type_literal_from_type(
                    field_type.element_type,
                    field_type.element_optional,
                    child_ref,
                    indent + 2,
                    parent_stack,
                )
            )
        elif isinstance(field_type, MapType):
            args.append(
                self._field_type_literal_from_type(
                    field_type.key_type, False, False, indent + 2, parent_stack
                )
            )
            args.append(
                self._field_type_literal_from_type(
                    field_type.value_type,
                    False,
                    field_type.value_ref,
                    indent + 2,
                    parent_stack,
                )
            )
        args_block = (
            "<GeneratedFieldType>[]"
            if not args
            else "<GeneratedFieldType>[\n"
            + ",\n".join(args)
            + f"\n{self.indent_str * (indent + 1)}]"
        )
        dynamic_literal = (
            "null"
            if isinstance(field_type, PrimitiveType)
            else (
                "false"
                if isinstance(field_type, NamedType)
                and isinstance(
                    self.resolve_type(field_type.name, parent_stack), (Enum, Message)
                )
                else "true"
            )
        )
        return (
            "GeneratedFieldType(\n"
            f"{self.indent_str * (indent + 1)}type: {type_expr},\n"
            f"{self.indent_str * (indent + 1)}typeId: {type_id},\n"
            f"{self.indent_str * (indent + 1)}nullable: {str(nullable).lower()},\n"
            f"{self.indent_str * (indent + 1)}ref: {str(ref).lower()},\n"
            f"{self.indent_str * (indent + 1)}dynamic: {dynamic_literal},\n"
            f"{self.indent_str * (indent + 1)}arguments: {args_block},\n"
            f"{self.indent_str * indent})"
        )

    def _type_expr_and_id(
        self,
        field_type: FieldType,
        parent_stack: Optional[List[Message]] = None,
    ) -> Tuple[str, str]:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            return {
                PrimitiveKind.BOOL: ("bool", "TypeIds.boolType"),
                PrimitiveKind.INT8: ("Int8", "TypeIds.int8"),
                PrimitiveKind.INT16: ("Int16", "TypeIds.int16"),
                PrimitiveKind.INT32: ("Int32", "TypeIds.int32"),
                PrimitiveKind.VARINT32: ("Int32", "TypeIds.varInt32"),
                PrimitiveKind.INT64: ("int", "TypeIds.int64"),
                PrimitiveKind.VARINT64: ("int", "TypeIds.varInt64"),
                PrimitiveKind.TAGGED_INT64: ("int", "TypeIds.taggedInt64"),
                PrimitiveKind.UINT8: ("UInt8", "TypeIds.uint8"),
                PrimitiveKind.UINT16: ("UInt16", "TypeIds.uint16"),
                PrimitiveKind.UINT32: ("UInt32", "TypeIds.uint32"),
                PrimitiveKind.VAR_UINT32: ("UInt32", "TypeIds.varUint32"),
                PrimitiveKind.UINT64: ("int", "TypeIds.uint64"),
                PrimitiveKind.VAR_UINT64: ("int", "TypeIds.varUint64"),
                PrimitiveKind.TAGGED_UINT64: ("int", "TypeIds.taggedUint64"),
                PrimitiveKind.FLOAT16: ("Float16", "TypeIds.float16"),
                PrimitiveKind.BFLOAT16: ("Float16", "TypeIds.float16"),
                PrimitiveKind.FLOAT32: ("Float32", "TypeIds.float32"),
                PrimitiveKind.FLOAT64: ("double", "TypeIds.float64"),
                PrimitiveKind.STRING: ("String", "TypeIds.string"),
                PrimitiveKind.BYTES: ("Uint8List", "TypeIds.binary"),
                PrimitiveKind.DATE: ("LocalDate", "TypeIds.date"),
                PrimitiveKind.TIMESTAMP: ("Timestamp", "TypeIds.timestamp"),
                PrimitiveKind.ANY: ("Object", "TypeIds.unknown"),
            }[field_type.kind]
        if isinstance(field_type, ListType):
            arr = self._typed_array_or_list(field_type, parent_stack)
            if (
                arr
                != f"List<{self.dart_type(field_type.element_type, field_type.element_optional, parent_stack)}>"
                and arr.endswith("List")
            ):
                return arr, {
                    "Int8List": "TypeIds.int8Array",
                    "Int16List": "TypeIds.int16Array",
                    "Int32List": "TypeIds.int32Array",
                    "Int64List": "TypeIds.int64Array",
                    "Uint8List": "TypeIds.uint8Array",
                    "Uint16List": "TypeIds.uint16Array",
                    "Uint32List": "TypeIds.uint32Array",
                    "Uint64List": "TypeIds.uint64Array",
                    "Float32List": "TypeIds.float32Array",
                    "Float64List": "TypeIds.float64Array",
                }[arr]
            return "List", "TypeIds.list"
        if isinstance(field_type, MapType):
            return "Map", "TypeIds.map"
        if isinstance(field_type, NamedType):
            resolved = self.resolve_type(field_type.name, parent_stack)
            if isinstance(resolved, Enum):
                return self.ref_name(resolved), "TypeIds.enumById"
            if isinstance(resolved, Union):
                return self.ref_name(resolved), "TypeIds.typedUnion"
            if isinstance(resolved, Message):
                return self.ref_name(
                    resolved
                ), "TypeIds.compatibleStruct" if resolved.options.get(
                    "evolving", True
                ) else "TypeIds.struct"
            return self.safe_type_identifier(
                self.to_pascal_case(field_type.name)
            ), "TypeIds.struct"
        return "Object", "TypeIds.unknown"

    def generate_registration_type(self, indent: int) -> List[str]:
        generated_api = self.generated_api_name()
        lines = [
            f"{self.indent_str * indent}abstract final class ForyRegistration {{",
            f"{self.indent_str * (indent + 1)}static Fory? _fory;",
            "",
            f"{self.indent_str * (indent + 1)}static void setFory(Fory fory) => _fory = fory;",
            f"{self.indent_str * (indent + 1)}static Fory getFory() {{",
            f"{self.indent_str * (indent + 2)}final fory = _fory;",
            f"{self.indent_str * (indent + 2)}if (fory == null) throw StateError('Call ForyRegistration.register(...) before using generated helpers.');",
            f"{self.indent_str * (indent + 2)}return fory;",
            f"{self.indent_str * (indent + 1)}}}",
            "",
            f"{self.indent_str * (indent + 1)}static ({'{'}int? id, String? namespace, String? typeName{'}'}) _registrationMode({{",
            f"{self.indent_str * (indent + 2)}int? id,",
            f"{self.indent_str * (indent + 2)}String? namespace,",
            f"{self.indent_str * (indent + 2)}String? typeName,",
            f"{self.indent_str * (indent + 2)}int? defaultId,",
            f"{self.indent_str * (indent + 2)}String? defaultNamespace,",
            f"{self.indent_str * (indent + 2)}String? defaultTypeName,",
            f"{self.indent_str * (indent + 1)}}}) {{",
            f"{self.indent_str * (indent + 2)}if (id != null || namespace != null || typeName != null) {{",
            f"{self.indent_str * (indent + 3)}return (id: id, namespace: namespace, typeName: typeName);",
            f"{self.indent_str * (indent + 2)}}}",
            f"{self.indent_str * (indent + 2)}return (id: defaultId, namespace: defaultNamespace, typeName: defaultTypeName);",
            f"{self.indent_str * (indent + 1)}}}",
            "",
            f"{self.indent_str * (indent + 1)}static void register(Fory fory, Type type, {{int? id, String? namespace, String? typeName}}) {{",
            f"{self.indent_str * (indent + 2)}setFory(fory);",
        ]

        def registration_lines(type_def: object, call_line: str) -> List[str]:
            n = self.local_name(type_def)
            default_id, default_namespace, default_type_name = (
                self._registration_defaults(type_def)
            )
            return [
                f"{self.indent_str * (indent + 2)}if (type == {n}) {{",
                f"{self.indent_str * (indent + 3)}final registrationMode = _registrationMode(",
                f"{self.indent_str * (indent + 4)}id: id,",
                f"{self.indent_str * (indent + 4)}namespace: namespace,",
                f"{self.indent_str * (indent + 4)}typeName: typeName,",
                f"{self.indent_str * (indent + 4)}defaultId: {default_id},",
                f"{self.indent_str * (indent + 4)}defaultNamespace: {default_namespace},",
                f"{self.indent_str * (indent + 4)}defaultTypeName: {default_type_name},",
                f"{self.indent_str * (indent + 3)});",
                f"{self.indent_str * (indent + 3)}{call_line.format(mode='registrationMode')}",
                f"{self.indent_str * (indent + 3)}return;",
                f"{self.indent_str * (indent + 2)}}}",
            ]

        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            n = self.local_name(enum)
            lines.extend(
                registration_lines(
                    enum,
                    f"{generated_api}.register(fory, {n}, id: {{mode}}.id, namespace: {{mode}}.namespace, typeName: {{mode}}.typeName);",
                )
            )
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            n = self.local_name(union)
            lines.extend(
                registration_lines(
                    union,
                    f"fory.registerSerializer({n}, const _{n}ForySerializer(), id: {{mode}}.id, namespace: {{mode}}.namespace, typeName: {{mode}}.typeName);",
                )
            )

        def visit_message(message: Message):
            if self.is_imported_type(message):
                return
            n = self.local_name(message)
            lines.extend(
                registration_lines(
                    message,
                    f"{generated_api}.register(fory, {n}, id: {{mode}}.id, namespace: {{mode}}.namespace, typeName: {{mode}}.typeName);",
                )
            )
            for enum in message.nested_enums:
                en = self.local_name(enum)
                lines.extend(
                    registration_lines(
                        enum,
                        f"{generated_api}.register(fory, {en}, id: {{mode}}.id, namespace: {{mode}}.namespace, typeName: {{mode}}.typeName);",
                    )
                )
            for union in message.nested_unions:
                un = self.local_name(union)
                lines.extend(
                    registration_lines(
                        union,
                        f"fory.registerSerializer({un}, const _{un}ForySerializer(), id: {{mode}}.id, namespace: {{mode}}.namespace, typeName: {{mode}}.typeName);",
                    )
                )
            for nested in message.nested_messages:
                visit_message(nested)

        for message in self.schema.messages:
            visit_message(message)
        seen: Set[str] = set()
        for item in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(item):
                continue
            schema = self._load_schema(item.location.file)
            if schema is None:
                continue
            alias = self.safe_identifier(
                schema.package.replace(".", "_")
                if schema.package
                else Path(item.location.file).stem
            )
            call = f"{alias}.ForyRegistration.register(fory, type, id: id, namespace: namespace, typeName: typeName);"
            if call not in seen:
                lines.extend(
                    [
                        f"{self.indent_str * (indent + 2)}try {{",
                        f"{self.indent_str * (indent + 3)}{call}",
                        f"{self.indent_str * (indent + 3)}return;",
                        f"{self.indent_str * (indent + 2)}}} on ArgumentError {{",
                        f"{self.indent_str * (indent + 2)}}}",
                    ]
                )
                seen.add(call)
        lines.extend(
            [
                f"{self.indent_str * (indent + 2)}throw ArgumentError('Unknown generated Dart type: $type');",
                f"{self.indent_str * (indent + 1)}}}",
                f"{self.indent_str * indent}}}",
            ]
        )
        return lines
