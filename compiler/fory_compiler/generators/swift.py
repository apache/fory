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

"""Swift code generator."""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar

from fory_compiler.frontend.base import FrontendError
from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.generators.base import BaseGenerator, GeneratedFile, GeneratorOptions
from fory_compiler.generators.services.swift import SwiftServiceMixin
from fory_compiler.ir.ast import (
    ArrayType,
    Enum,
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


class SwiftGenerator(SwiftServiceMixin, BaseGenerator):
    """Generates Swift types using Fory Swift model macros."""

    language_name = "swift"
    file_extension = ".swift"

    PRIMITIVE_MAP: ClassVar[dict[PrimitiveKind, str]] = {
        PrimitiveKind.BOOL: "Bool",
        PrimitiveKind.INT8: "Int8",
        PrimitiveKind.INT16: "Int16",
        PrimitiveKind.INT32: "Int32",
        PrimitiveKind.INT64: "Int64",
        PrimitiveKind.UINT8: "UInt8",
        PrimitiveKind.UINT16: "UInt16",
        PrimitiveKind.UINT32: "UInt32",
        PrimitiveKind.UINT64: "UInt64",
        PrimitiveKind.FLOAT16: "Float16",
        PrimitiveKind.BFLOAT16: "BFloat16",
        PrimitiveKind.FLOAT32: "Float",
        PrimitiveKind.FLOAT64: "Double",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "Data",
        PrimitiveKind.DATE: "LocalDate",
        PrimitiveKind.TIMESTAMP: "Date",
        PrimitiveKind.DURATION: "Duration",
        PrimitiveKind.DECIMAL: "Decimal",
        PrimitiveKind.ANY: "Any",
    }

    SWIFT_KEYWORDS: ClassVar[set[str]] = {
        "associatedtype",
        "class",
        "deinit",
        "enum",
        "extension",
        "fileprivate",
        "func",
        "import",
        "init",
        "inout",
        "internal",
        "let",
        "open",
        "operator",
        "private",
        "protocol",
        "public",
        "rethrows",
        "static",
        "struct",
        "subscript",
        "typealias",
        "var",
        "break",
        "case",
        "continue",
        "default",
        "defer",
        "do",
        "else",
        "fallthrough",
        "for",
        "guard",
        "if",
        "in",
        "repeat",
        "return",
        "switch",
        "where",
        "while",
        "as",
        "Any",
        "catch",
        "false",
        "is",
        "nil",
        "super",
        "self",
        "Self",
        "throw",
        "throws",
        "true",
        "try",
        "_",
        "__COLUMN__",
        "__FILE__",
        "__FUNCTION__",
        "__LINE__",
    }

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self._qualified_type_names: dict[int, str] = {}
        self._schema_cache: dict[Path, Schema] = {}
        self._equatable_cache: dict[int, bool] = {}
        self._messages_requiring_class: set[int] = set()
        self._indirect_unions: set[int] = set()
        self._build_qualified_type_name_index()
        self._collect_messages_requiring_class()
        self._analyze_recursive_value_types()

    def generate(self) -> list[GeneratedFile]:
        return [self.generate_file()]

    def generate_file(self) -> GeneratedFile:
        for type_def in self.collect_local_types():
            if isinstance(type_def, (Message, Union)):
                self._ensure_default_constructible(type_def)

        lines: list[str] = []
        lines.append(self.get_license_header("//"))
        lines.append("")
        lines.append("import Foundation")
        lines.append("import Fory")
        lines.append("")

        namespace_components = self.get_namespace_components()
        indent_level = 0
        for component in namespace_components:
            lines.append(f"{self.indent_str * indent_level}public enum {component} {{")
            indent_level += 1
        if namespace_components:
            lines.append("")

        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            lines.extend(self.generate_enum(enum, indent=indent_level))
            lines.append("")

        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            lines.extend(self.generate_union(union, indent=indent_level))
            lines.append("")

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.extend(self.generate_message(message, indent=indent_level))
            lines.append("")

        lines.extend(self.generate_module_type(indent=indent_level))
        lines.append("")

        if namespace_components:
            for i in range(len(namespace_components) - 1, -1, -1):
                lines.append(f"{self.indent_str * i}}}")
            lines.append("")

        content = "\n".join(lines).rstrip() + "\n"
        return GeneratedFile(path=self.output_file_path(), content=content)

    def output_file_path(self) -> str:
        file_name = self.module_file_name()
        if self.schema.package:
            package_path = self.schema.package.replace(".", "/")
            return f"{package_path}/{file_name}"
        return file_name

    def swift_model_top_level_symbols(self) -> set[str]:
        # In enum style with a package, types nest under a namespace enum, so the
        # only file-scope declaration is that enum's first component. Flatten and
        # default packages put each type and the module helper at file scope.
        components = self._namespace_components_for_schema(self.schema)
        if self.get_namespace_style() == "enum" and components:
            return {components[0]}
        symbols: set[str] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if self.is_imported_type(type_def):
                continue
            symbols.add(self._declared_type_name(type_def.name))
        symbols.add(self._module_helper_name_for_schema(self.schema))
        return symbols

    def module_file_name(self) -> str:
        if self.schema.source_file and not self.schema.source_file.startswith("<"):
            stem = Path(self.schema.source_file).stem
            if self.schema.package:
                package_leaf = self.schema.package.split(".")[-1]
                if package_leaf and package_leaf != stem:
                    stem = package_leaf
            return f"{stem}.swift"
        if self.schema.package:
            return f"{self.schema.package.replace('.', '_')}.swift"
        return "generated.swift"

    def get_namespace_components(self) -> list[str]:
        return self._namespace_components_for_schema(self.schema)

    def namespace_path(self) -> str:
        return ".".join(self.get_namespace_components())

    def get_namespace_style(self) -> str:
        style = self.options.swift_namespace_style
        if style is None:
            style = self.schema.get_option("swift_namespace_style")
        if style is None:
            return "enum"
        normalized = str(style).strip().lower()
        if normalized not in ("enum", "flatten"):
            raise ValueError(
                f"Invalid swift_namespace_style: {style}. Use 'enum' or 'flatten'."
            )
        return normalized

    def _module_helper_name_for_schema(self, schema: Schema) -> str:
        prefix = self._namespace_prefix_for_schema(schema)
        if prefix:
            return self.safe_type_identifier(f"{prefix}_ForyModule")
        return "ForyModule"

    def _module_type_path_for_schema(self, schema: Schema) -> str:
        namespace_path = ".".join(self._namespace_components_for_schema(schema))
        module_type_name = self._module_helper_name_for_schema(schema)
        if namespace_path:
            return f"{namespace_path}.{module_type_name}"
        return module_type_name

    def module_type_path(self) -> str:
        return self._module_type_path_for_schema(self.schema)

    def safe_identifier(self, name: str) -> str:
        if not name:
            return "_"
        candidate = name
        if candidate[0].isdigit():
            candidate = f"_{candidate}"
        if candidate in self.SWIFT_KEYWORDS:
            return f"`{candidate}`"
        return candidate

    def safe_type_identifier(self, name: str) -> str:
        return self.safe_identifier(name)

    def safe_member_name(self, name: str) -> str:
        return self.safe_identifier(self.to_camel_case(name))

    def safe_enum_case_name(self, name: str) -> str:
        return self.safe_identifier(self.to_camel_case(name))

    def sanitize_qualified_name(self, qualified_name: str) -> str:
        return ".".join(
            self.safe_type_identifier(self.to_pascal_case(part))
            for part in qualified_name.split(".")
        )

    def is_imported_type(self, type_def: object) -> bool:
        if not self.schema.source_file:
            return False
        location = getattr(type_def, "location", None)
        if location is None or not location.file:
            return False
        try:
            return (
                Path(location.file).resolve() != Path(self.schema.source_file).resolve()
            )
        except (OSError, RuntimeError):
            return location.file != self.schema.source_file

    def _normalize_import_path(self, path_str: str) -> str:
        if not path_str:
            return path_str
        try:
            return str(Path(path_str).resolve())
        except (OSError, RuntimeError):
            return path_str

    def _load_schema(self, file_path: str) -> Schema | None:
        if not file_path:
            return None
        path = Path(file_path).resolve()
        if path in self._schema_cache:
            return self._schema_cache[path]
        try:
            schema = parse_idl_file(path)
        except (FrontendError, OSError, ValueError):
            return None
        self._schema_cache[path] = schema
        return schema

    def _package_components_for_schema(self, schema: Schema) -> list[str]:
        package = schema.package
        if not package:
            return []
        return [
            self.safe_type_identifier(self.to_pascal_case(part))
            for part in package.split(".")
            if part
        ]

    def _namespace_components_for_schema(self, schema: Schema) -> list[str]:
        if self.get_namespace_style() != "enum":
            return []
        components = self._package_components_for_schema(schema)
        if not components:
            return []
        top_level_types = self._local_top_level_type_names(schema)
        if components and components[-1] in top_level_types:
            components[-1] = self.safe_type_identifier(f"{components[-1]}Namespace")
        return components

    def _namespace_prefix_for_schema(self, schema: Schema) -> str:
        if self.get_namespace_style() != "flatten":
            return ""
        components = self._package_components_for_schema(schema)
        if not components:
            return ""
        return "_".join(components)

    def _flattened_type_path(self, schema: Schema, qualified_name: str) -> str:
        parts = qualified_name.split(".")
        if not parts:
            return qualified_name
        top_level = self.safe_type_identifier(self.to_pascal_case(parts[0]))
        prefix = self._namespace_prefix_for_schema(schema)
        if prefix:
            top_level = self.safe_type_identifier(f"{prefix}_{top_level}")
        nested = [
            self.safe_type_identifier(self.to_pascal_case(part)) for part in parts[1:]
        ]
        return ".".join([top_level, *nested])

    def _local_top_level_type_names(self, schema: Schema) -> set[str]:
        names: set[str] = set()
        source_path: Path | None = None
        if schema.source_file:
            try:
                source_path = Path(schema.source_file).resolve()
            except (OSError, RuntimeError):
                source_path = None

        for type_def in schema.enums + schema.unions + schema.messages:
            if source_path is not None:
                location = getattr(type_def, "location", None)
                type_file = getattr(location, "file", None) if location else None
                if type_file:
                    try:
                        if Path(type_file).resolve() != source_path:
                            continue
                    except (OSError, RuntimeError):
                        if type_file != schema.source_file:
                            continue
            names.add(self.safe_type_identifier(self.to_pascal_case(type_def.name)))
        return names

    def _namespace_path_for_type(self, type_def: object) -> str:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return self.namespace_path()
        return ".".join(self._namespace_components_for_schema(schema))

    def _collect_imported_module_paths(self) -> list[str]:
        by_file: dict[str, str] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            location = getattr(type_def, "location", None)
            file_path = getattr(location, "file", None) if location else None
            if not file_path:
                continue
            normalized = self._normalize_import_path(file_path)
            if normalized in by_file:
                continue
            schema = self._load_schema(file_path)
            if schema is None:
                continue
            by_file[normalized] = self._module_type_path_for_schema(schema)

        ordered: list[str] = []
        used_paths: set[str] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = self._normalize_import_path(
                    str((base_dir / imp.path).resolve())
                )
                if candidate in by_file and candidate not in used_paths:
                    ordered.append(by_file[candidate])
                    used_paths.add(candidate)
        for key in sorted(by_file.keys()):
            if key in used_paths:
                continue
            ordered.append(by_file[key])

        deduped: list[str] = []
        seen: set[str] = set()
        for path in ordered:
            if path == self.module_type_path():
                continue
            if path in seen:
                continue
            seen.add(path)
            deduped.append(path)
        return deduped

    def _build_qualified_type_name_index(self) -> None:
        for enum in self.schema.enums:
            self._qualified_type_names[id(enum)] = enum.name
        for union in self.schema.unions:
            self._qualified_type_names[id(union)] = union.name

        def visit_message(message: Message, parents: list[str]) -> None:
            path = ".".join(parents + [message.name])
            self._qualified_type_names[id(message)] = path
            for nested_enum in message.nested_enums:
                self._qualified_type_names[id(nested_enum)] = (
                    f"{path}.{nested_enum.name}"
                )
            for nested_union in message.nested_unions:
                self._qualified_type_names[id(nested_union)] = (
                    f"{path}.{nested_union.name}"
                )
            for nested_message in message.nested_messages:
                visit_message(nested_message, parents + [message.name])

        for message in self.schema.messages:
            visit_message(message, [])

    def _collect_messages_requiring_class(self) -> None:
        for message in self.schema.messages:
            self._collect_messages_requiring_class_for_message(message)

    def _collect_messages_requiring_class_for_message(self, message: Message) -> None:
        if self.message_has_weak_field(message):
            self._messages_requiring_class.add(id(message))

        for field in message.fields:
            ref_target_type: FieldType | None = None
            has_ref_semantics = False
            if field.ref:
                ref_target_type = field.field_type
                has_ref_semantics = True
            elif isinstance(field.field_type, ListType) and field.element_ref:
                ref_target_type = field.field_type.element_type
                has_ref_semantics = True
            elif isinstance(field.field_type, MapType) and field.field_type.value_ref:
                ref_target_type = field.field_type.value_type
                has_ref_semantics = True

            if has_ref_semantics:
                # In xlang track-ref mode, messages containing ref-bearing fields must be
                # reference-trackable objects to keep wire-level ref flags compatible with
                # Java/C++ implementations (for example graph/root objects).
                self._messages_requiring_class.add(id(message))

            if ref_target_type is None:
                continue

            if isinstance(ref_target_type, NamedType):
                resolved = self._resolve_named_type(ref_target_type.name, [message])
                if isinstance(resolved, Message):
                    self._messages_requiring_class.add(id(resolved))

        for nested in message.nested_messages:
            self._collect_messages_requiring_class_for_message(nested)

    def _analyze_recursive_value_types(self) -> None:
        value_types = [
            type_def
            for type_def in self.schema.get_all_types()
            if isinstance(type_def, (Message, Union))
        ]
        value_types_by_id = {id(type_def): type_def for type_def in value_types}
        dependencies: dict[int, set[int]] = {}

        for type_def in value_types:
            if isinstance(type_def, Message):
                parent_stack = self._lineage_for_message(type_def)
            else:
                parent_stack = self._parent_stack_for_nested_type(type_def)

            resolved_dependencies: set[int] = set()
            for field in type_def.fields:
                if not isinstance(field.field_type, NamedType):
                    continue
                resolved = self._resolve_named_type(
                    field.field_type.name,
                    parent_stack,
                )
                if not isinstance(resolved, (Message, Union)):
                    continue
                if isinstance(resolved, Message) and self.message_is_class(resolved):
                    continue
                resolved_dependencies.add(id(resolved))
            dependencies[id(type_def)] = resolved_dependencies

        def reaches(
            target: int,
            current: int,
            visited: set[int],
        ) -> bool:
            for dependency in dependencies.get(current, set()):
                if dependency == target:
                    return True
                if dependency in visited:
                    continue
                visited.add(dependency)
                if reaches(target, dependency, visited):
                    return True
            return False

        for type_def in value_types:
            type_id = id(type_def)
            if isinstance(type_def, Union) and reaches(type_id, type_id, {type_id}):
                self._indirect_unions.add(type_id)

        for type_def in value_types:
            if not isinstance(type_def, Message) or self.message_is_class(type_def):
                continue
            type_id = id(type_def)
            message_dependencies = {
                dependency
                for dependency in dependencies.get(type_id, set())
                if isinstance(value_types_by_id.get(dependency), Message)
            }
            dependencies[type_id] = message_dependencies

        for type_def in value_types:
            if not isinstance(type_def, Message) or self.message_is_class(type_def):
                continue
            type_id = id(type_def)
            if reaches(type_id, type_id, {type_id}):
                type_name = self._qualified_type_path(type_def, type_def.name)
                raise ValueError(
                    f"Swift value message {type_name} is recursively stored; "
                    "mark a cycle edge ref so the generated target is a class"
                )

    def message_has_weak_field(self, message: Message) -> bool:
        for field in message.fields:
            if not field.ref:
                continue
            if field.ref_options.get("weak_ref") is True:
                return True
        return False

    def message_is_class(self, message: Message) -> bool:
        return id(message) in self._messages_requiring_class

    def _resolve_named_type(
        self,
        name: str,
        parent_stack: list[Message] | None = None,
    ) -> Message | Enum | Union | None:
        parent_stack = parent_stack or []
        if "." in name:
            return self.schema.get_type(name)
        for message in reversed(parent_stack):
            nested = message.get_nested_type(name)
            if nested is not None:
                return nested
        return self.schema.get_type(name)

    def _schema_for_type(self, type_def: object) -> Schema:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path) if file_path else None
        if schema is not None:
            return schema
        return self.schema

    def _declared_type_name(
        self,
        name: str,
        parent_stack: list[Message] | None = None,
    ) -> str:
        type_name = self.safe_type_identifier(self.to_pascal_case(name))
        if parent_stack:
            return type_name
        prefix = self._namespace_prefix_for_schema(self.schema)
        if not prefix:
            return type_name
        return self.safe_type_identifier(f"{prefix}_{type_name}")

    def _qualified_type_path(
        self,
        resolved: Message | Enum | Union | None,
        fallback_name: str,
    ) -> str:
        owner_schema = self.schema
        if resolved is None:
            qualified_name = fallback_name
        else:
            owner_schema = self._schema_for_type(resolved)
            qualified_name = self._qualified_type_names.get(id(resolved), fallback_name)

        if self.get_namespace_style() == "flatten":
            return self._flattened_type_path(owner_schema, qualified_name)

        namespace = ".".join(self._namespace_components_for_schema(owner_schema))
        sanitized = self.sanitize_qualified_name(qualified_name)
        if not namespace:
            return sanitized
        return f"{namespace}.{sanitized}"

    def _named_type_reference(
        self,
        named_type: NamedType,
        parent_stack: list[Message] | None = None,
        nullable: bool = False,
    ) -> str:
        resolved = self._resolve_named_type(named_type.name, parent_stack)
        type_name = self._qualified_type_path(resolved, named_type.name)
        if nullable:
            return f"{type_name}?"
        return type_name

    def generate_type(  # type: ignore[override]
        self,
        field_type: FieldType,
        nullable: bool = False,
        parent_stack: list[Message] | None = None,
    ) -> str:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            base = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"{base}?"
            return base

        if isinstance(field_type, NamedType):
            return self._named_type_reference(
                field_type, parent_stack=parent_stack, nullable=nullable
            )

        if isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=field_type.element_optional,
                parent_stack=parent_stack,
            )
            result = f"[{element_type}]"
            if nullable:
                return f"{result}?"
            return result

        if isinstance(field_type, ArrayType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            result = f"[{element_type}]"
            if nullable:
                return f"{result}?"
            return result

        if isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            value_type = self.generate_type(
                field_type.value_type,
                nullable=field_type.value_optional,
                parent_stack=parent_stack,
            )
            result = f"[{key_type}: {value_type}]"
            if nullable:
                return f"{result}?"
            return result

        return "Any"

    def field_encoding_argument(self, field: Field) -> str | None:
        field_type = field.field_type
        if not isinstance(field_type, PrimitiveType):
            return None
        if field_type.encoding_modifier == "fixed":
            return ".fixed"
        if field_type.encoding_modifier == "tagged":
            return ".tagged"
        return None

    def type_hint_expression(
        self,
        field_type: FieldType,
        nullable: bool = False,
        *,
        array_element: bool = False,
    ) -> str | None:
        if isinstance(field_type, PrimitiveType):
            return self.scalar_type_hint_expression(
                field_type,
                nullable=nullable,
                include_integer_encoding=not array_element,
            )

        if isinstance(field_type, ListType):
            element_hint = self.type_hint_expression(
                field_type.element_type, nullable=field_type.element_optional
            )
            if element_hint is None:
                return None
            return f".list(element: {element_hint})"

        if isinstance(field_type, ArrayType):
            element_hint = self.type_hint_expression(
                field_type.element_type,
                array_element=True,
            )
            if element_hint is None:
                return None
            return f".array(element: {element_hint})"

        if isinstance(field_type, MapType):
            key_hint = self.type_hint_expression(field_type.key_type)
            value_hint = self.type_hint_expression(
                field_type.value_type, nullable=field_type.value_optional
            )
            parts: list[str] = []
            if key_hint is not None:
                parts.append(f"key: {key_hint}")
            if value_hint is not None:
                parts.append(f"value: {value_hint}")
            if not parts:
                return None
            return f".map({', '.join(parts)})"

        return None

    def scalar_type_hint_expression(
        self,
        field_type: PrimitiveType,
        nullable: bool = False,
        *,
        include_integer_encoding: bool = True,
    ) -> str | None:
        kind = field_type.kind
        member_hints = {
            PrimitiveKind.BOOL: "bool",
            PrimitiveKind.INT8: "int8",
            PrimitiveKind.INT16: "int16",
            PrimitiveKind.UINT8: "uint8",
            PrimitiveKind.UINT16: "uint16",
            PrimitiveKind.FLOAT16: "float16",
            PrimitiveKind.BFLOAT16: "bfloat16",
            PrimitiveKind.FLOAT32: "float32",
            PrimitiveKind.FLOAT64: "float64",
            PrimitiveKind.STRING: "string",
            PrimitiveKind.BYTES: "binary",
            PrimitiveKind.DATE: "date",
            PrimitiveKind.TIMESTAMP: "timestamp",
            PrimitiveKind.DURATION: "duration",
            PrimitiveKind.DECIMAL: "decimal",
        }
        member_hint = member_hints.get(kind)
        if member_hint is not None:
            return f".{member_hint}"

        callable_hints = {
            PrimitiveKind.INT32: "int32",
            PrimitiveKind.INT64: "int64",
            PrimitiveKind.UINT32: "uint32",
            PrimitiveKind.UINT64: "uint64",
        }
        callable_hint = callable_hints.get(kind)
        if callable_hint is None:
            return None

        name = callable_hint
        encoding = field_type.encoding_modifier
        args: list[str] = []
        if nullable:
            args.append("nullable: true")
        if include_integer_encoding and encoding is not None:
            args.append(f"encoding: .{encoding}")
        if not args:
            return f".{name}()"
        return f".{name}({', '.join(args)})"

    def nested_field_attribute(self, field: Field) -> str | None:
        field_type = field.field_type
        if isinstance(field_type, ListType):
            element_hint = self.type_hint_expression(
                field_type.element_type, nullable=field_type.element_optional
            )
            if element_hint is None:
                return None
            return f"@ListField(element: {element_hint})"

        if isinstance(field_type, ArrayType):
            element_hint = self.type_hint_expression(
                field_type.element_type,
                array_element=True,
            )
            if element_hint is None:
                return None
            return f"@ArrayField(element: {element_hint})"

        if isinstance(field_type, MapType):
            key_hint = self.type_hint_expression(field_type.key_type)
            value_hint = self.type_hint_expression(
                field_type.value_type, nullable=field_type.value_optional
            )
            parts: list[str] = []
            if key_hint is not None:
                parts.append(f"key: {key_hint}")
            if value_hint is not None:
                parts.append(f"value: {value_hint}")
            if not parts:
                return None
            return f"@MapField({', '.join(parts)})"

        return None

    def message_field_id_argument(self, field: Field) -> int | None:
        return field.tag_id

    def union_case_id_argument(self, field: Field) -> int:
        if field.tag_id is not None:
            return field.tag_id
        return field.number

    def is_weak_ref_field(self, field: Field) -> bool:
        if not field.ref:
            return False
        return field.ref_options.get("weak_ref") is True

    def field_swift_type(
        self,
        field: Field,
        parent_stack: list[Message],
    ) -> str:
        weak_ref = self.is_weak_ref_field(field)
        nullable = field.optional or weak_ref
        return self.generate_type(
            field.field_type,
            nullable=nullable,
            parent_stack=parent_stack,
        )

    def field_default_expression(
        self,
        field: Field,
        parent_stack: list[Message] | None = None,
    ) -> str | None:
        if self.is_weak_ref_field(field):
            return None
        if field.optional:
            return "nil"

        return self.type_default_expression(field.field_type, parent_stack)

    def type_default_expression(
        self,
        field_type: FieldType,
        parent_stack: list[Message] | None = None,
    ) -> str:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            defaults = {
                PrimitiveKind.BOOL: "false",
                PrimitiveKind.INT8: "0",
                PrimitiveKind.INT16: "0",
                PrimitiveKind.INT32: "0",
                PrimitiveKind.INT64: "0",
                PrimitiveKind.UINT8: "0",
                PrimitiveKind.UINT16: "0",
                PrimitiveKind.UINT32: "0",
                PrimitiveKind.UINT64: "0",
                PrimitiveKind.FLOAT16: "0",
                PrimitiveKind.BFLOAT16: "BFloat16()",
                PrimitiveKind.FLOAT32: "0",
                PrimitiveKind.FLOAT64: "0",
                PrimitiveKind.STRING: '""',
                PrimitiveKind.BYTES: "Data()",
                PrimitiveKind.DATE: "LocalDate()",
                PrimitiveKind.TIMESTAMP: "Date(timeIntervalSince1970: 0)",
                PrimitiveKind.DURATION: "Duration.zero",
                PrimitiveKind.DECIMAL: "Decimal.zero",
                PrimitiveKind.ANY: "ForyAnyNullValue()",
            }
            return defaults[field_type.kind]

        if isinstance(field_type, ListType):
            return "[]"
        if isinstance(field_type, ArrayType):
            return "[]"
        if isinstance(field_type, MapType):
            return "[:]"

        if not isinstance(field_type, NamedType):
            raise TypeError(f"Unsupported Swift default type: {field_type!r}")

        resolved = self._resolve_named_type(field_type.name, parent_stack)
        if resolved is None:
            raise ValueError(f"Unresolved Swift default type: {field_type.name}")
        type_name = self._qualified_type_path(resolved, field_type.name)
        if isinstance(resolved, Message):
            return f"{type_name}()"
        if isinstance(resolved, Enum):
            if not resolved.values:
                raise ValueError(f"Swift enum {field_type.name} has no default case")
            value = resolved.values[0]
            case_name = self.safe_enum_case_name(
                self.strip_enum_prefix(resolved.name, value.name)
            )
            return f"{type_name}.{case_name}"
        if isinstance(resolved, Union):
            if not resolved.fields:
                raise ValueError(f"Swift union {field_type.name} has no default case")
            default_field = resolved.fields[0]
            case_name = self.safe_enum_case_name(default_field.name)
            declaration_stack = self._parent_stack_for_nested_type(resolved)
            payload = self.type_default_expression(
                default_field.field_type,
                declaration_stack,
            )
            return f"{type_name}.{case_name}({payload})"
        raise ValueError(f"Unsupported Swift default type: {field_type.name}")

    def _ensure_default_constructible(
        self,
        type_def: Message | Union,
        stack: list[Message | Union] | None = None,
    ) -> None:
        stack = stack or []
        for index, ancestor in enumerate(stack):
            if ancestor is not type_def:
                continue
            cycle = [*stack[index:], type_def]
            names = [
                self._qualified_type_path(item, self.schema_type_name(item))
                for item in cycle
            ]
            raise ValueError(
                "Swift generated default is recursive: "
                f"{' -> '.join(names)}; first union cases and nonoptional "
                "message fields must form a finite default"
            )

        next_stack = [*stack, type_def]
        if isinstance(type_def, Union):
            if not type_def.fields:
                raise ValueError(f"Swift union {type_def.name} has no default case")
            default_field = type_def.fields[0]
            parent_stack = self._parent_stack_for_nested_type(type_def)
            self._ensure_field_default_constructible(
                default_field.field_type,
                parent_stack,
                next_stack,
            )
            return

        parent_stack = self._lineage_for_message(type_def)
        for field in type_def.fields:
            if self.is_weak_ref_field(field) or field.optional:
                continue
            self._ensure_field_default_constructible(
                field.field_type,
                parent_stack,
                next_stack,
            )

    def _ensure_field_default_constructible(
        self,
        field_type: FieldType,
        parent_stack: list[Message],
        stack: list[Message | Union],
    ) -> None:
        if isinstance(field_type, (PrimitiveType, ListType, ArrayType, MapType)):
            return
        if not isinstance(field_type, NamedType):
            raise TypeError(f"Unsupported Swift default type: {field_type!r}")

        resolved = self._resolve_named_type(field_type.name, parent_stack)
        if resolved is None:
            raise ValueError(f"Unresolved Swift default type: {field_type.name}")
        if isinstance(resolved, (Message, Union)):
            self._ensure_default_constructible(resolved, stack)

    def _parent_stack_for_nested_type(
        self,
        target: Enum | Union,
    ) -> list[Message]:
        def visit(message: Message, parents: list[Message]) -> list[Message] | None:
            nested_types = [*message.nested_enums, *message.nested_unions]
            if any(nested is target for nested in nested_types):
                return [*parents, message]
            for nested in message.nested_messages:
                result = visit(nested, [*parents, message])
                if result is not None:
                    return result
            return None

        for message in self.schema.messages:
            result = visit(message, [])
            if result is not None:
                return result
        return []

    def type_supports_equatable(
        self,
        field_type: FieldType,
        parent_stack: list[Message] | None = None,
        visiting: set[int] | None = None,
    ) -> bool:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            return field_type.kind != PrimitiveKind.ANY

        if isinstance(field_type, ListType):
            return self.type_supports_equatable(
                field_type.element_type, parent_stack=parent_stack, visiting=visiting
            )

        if isinstance(field_type, ArrayType):
            return self.type_supports_equatable(
                field_type.element_type, parent_stack=parent_stack, visiting=visiting
            )

        if isinstance(field_type, MapType):
            return self.type_supports_equatable(
                field_type.key_type, parent_stack=parent_stack, visiting=visiting
            ) and self.type_supports_equatable(
                field_type.value_type, parent_stack=parent_stack, visiting=visiting
            )

        if not isinstance(field_type, NamedType):
            return False

        resolved = self._resolve_named_type(field_type.name, parent_stack)
        if resolved is None:
            return False
        if isinstance(resolved, Message):
            return self.message_supports_equatable(resolved, visiting=visiting)
        if isinstance(resolved, Union):
            return self.union_supports_equatable(resolved, visiting=visiting)
        return isinstance(resolved, Enum)

    def union_supports_equatable(
        self,
        union: Union,
        parent_stack: list[Message] | None = None,
        visiting: set[int] | None = None,
    ) -> bool:
        if visiting is None:
            visiting = set()
        key = id(union)
        if key in visiting:
            return True
        visiting.add(key)
        lineage = parent_stack or []
        for field in union.fields:
            if not self.type_supports_equatable(
                field.field_type, parent_stack=lineage, visiting=visiting
            ):
                visiting.remove(key)
                return False
        visiting.remove(key)
        return True

    def message_supports_equatable(
        self,
        message: Message,
        visiting: set[int] | None = None,
    ) -> bool:
        if self.message_is_class(message):
            return False

        key = id(message)
        if key in self._equatable_cache:
            return self._equatable_cache[key]
        if visiting is None:
            visiting = set()
        if key in visiting:
            return True
        visiting.add(key)

        lineage = self._lineage_for_message(message)
        for field in message.fields:
            if not self.type_supports_equatable(
                field.field_type, parent_stack=lineage, visiting=visiting
            ):
                self._equatable_cache[key] = False
                visiting.remove(key)
                return False

        self._equatable_cache[key] = True
        visiting.remove(key)
        return True

    def _lineage_for_message(self, message: Message) -> list[Message]:
        lineage: list[Message] = []

        def visit(current: Message, parents: list[Message]) -> bool:
            if current is message:
                lineage.extend(parents + [current])
                return True
            for nested in current.nested_messages:
                if visit(nested, parents + [current]):
                    return True
            return False

        for top in self.schema.messages:
            if visit(top, []):
                break
        return lineage

    def generate_enum(
        self,
        enum: Enum,
        indent: int = 0,
        parent_stack: list[Message] | None = None,
    ) -> list[str]:
        lines: list[str] = []
        ind = self.indent_str * indent
        type_name = self._declared_type_name(enum.name, parent_stack)
        comment = self.format_type_id_comment(enum, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}@ForyEnum")
        lines.append(f"{ind}public enum {type_name}: Int32, CaseIterable {{")
        for value in enum.values:
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            case_name = self.safe_enum_case_name(stripped_name)
            lines.append(f"{ind}{self.indent_str}case {case_name} = {value.value}")
        lines.append("")
        lines.extend(self.generate_bytes_methods(type_name, indent + 1))
        lines.append(f"{ind}}}")
        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: list[Message] | None = None,
    ) -> list[str]:
        lines: list[str] = []
        ind = self.indent_str * indent
        type_name = self._declared_type_name(union.name, parent_stack)
        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}@ForyUnion")
        conformances = (
            ": Equatable"
            if self.union_supports_equatable(union, parent_stack=parent_stack)
            else ""
        )
        indirect = "indirect " if id(union) in self._indirect_unions else ""
        lines.append(f"{ind}public {indirect}enum {type_name}{conformances} {{")
        lineage = parent_stack or []
        lines.append(f"{ind}{self.indent_str}@ForyUnknownCase")
        lines.append(f"{ind}{self.indent_str}case unknown(UnknownCase)")
        lines.append("")
        for field in union.fields:
            field_type = self.generate_type(
                field.field_type,
                nullable=False,
                parent_stack=lineage,
            )
            case_name = self.safe_enum_case_name(field.name)
            attr_parts = [f"id: {self.union_case_id_argument(field)}"]
            payload_hint = self.type_hint_expression(field.field_type)
            if payload_hint is not None:
                attr_parts.append(f"payload: {payload_hint}")
            attr = f"@ForyCase({', '.join(attr_parts)})"
            lines.append(f"{ind}{self.indent_str}{attr}")
            lines.append(f"{ind}{self.indent_str}case {case_name}({field_type})")
        lines.append("")
        lines.extend(self.generate_bytes_methods(type_name, indent + 1))
        lines.append(f"{ind}}}")
        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: list[Message] | None = None,
    ) -> list[str]:
        lines: list[str] = []
        ind = self.indent_str * indent
        parent_stack = parent_stack or []
        lineage = parent_stack + [message]
        type_name = self._declared_type_name(message.name, parent_stack)
        is_class = self.message_is_class(message)

        comment = self.format_type_id_comment(message, f"{ind}//")
        if comment:
            lines.append(comment)
        if self.get_effective_evolving(message):
            lines.append(f"{ind}@ForyStruct")
        else:
            lines.append(f"{ind}@ForyStruct(evolving: false)")

        if is_class:
            declaration = f"public final class {type_name}"
        else:
            conformance = (
                ": Equatable" if self.message_supports_equatable(message) else ""
            )
            declaration = f"public struct {type_name}{conformance}"
        lines.append(f"{ind}{declaration} {{")

        for nested_enum in message.nested_enums:
            lines.append("")
            lines.extend(
                self.generate_enum(
                    nested_enum,
                    indent=indent + 1,
                    parent_stack=lineage,
                )
            )

        for nested_union in message.nested_unions:
            lines.append("")
            lines.extend(
                self.generate_union(
                    nested_union,
                    indent=indent + 1,
                    parent_stack=lineage,
                )
            )

        for nested_msg in message.nested_messages:
            lines.append("")
            lines.extend(
                self.generate_message(
                    nested_msg,
                    indent=indent + 1,
                    parent_stack=lineage,
                )
            )

        field_lines = self.generate_message_fields(message, lineage, indent + 1)
        if field_lines:
            lines.append("")
            lines.extend(field_lines)

        lines.append("")
        if is_class:
            lines.append(f"{ind}{self.indent_str}public required init() {{}}")
        else:
            lines.extend(self.generate_struct_init(message, lineage, indent + 1))

        lines.append("")
        lines.extend(self.generate_bytes_methods(type_name, indent + 1))
        lines.append(f"{ind}}}")
        return lines

    def generate_message_fields(
        self,
        message: Message,
        lineage: list[Message],
        indent: int,
    ) -> list[str]:
        lines: list[str] = []
        ind = self.indent_str * indent
        used_names: set[str] = set()

        for field in message.fields:
            field_name = self.safe_member_name(field.name)
            suffix = 1
            base_name = field_name
            while field_name in used_names:
                field_name = f"{base_name}{suffix}"
                suffix += 1
            used_names.add(field_name)

            encoding = self.field_encoding_argument(field)
            field_id = self.message_field_id_argument(field)
            attr_parts: list[str] = []
            if field_id is not None:
                attr_parts.append(f"id: {field_id}")
            if encoding is not None:
                attr_parts.append(f"encoding: {encoding}")
            if attr_parts:
                lines.append(f"{ind}@ForyField({', '.join(attr_parts)})")
            nested_attr = self.nested_field_attribute(field)
            if nested_attr is not None:
                lines.append(f"{ind}{nested_attr}")

            field_type = self.field_swift_type(field, lineage)
            weak_prefix = "weak " if self.is_weak_ref_field(field) else ""
            default_expr = self.field_default_expression(field, lineage)
            if default_expr is None:
                lines.append(f"{ind}public {weak_prefix}var {field_name}: {field_type}")
            else:
                lines.append(
                    f"{ind}public {weak_prefix}var {field_name}: {field_type} = {default_expr}"
                )

        return lines

    def generate_struct_init(
        self,
        message: Message,
        lineage: list[Message],
        indent: int,
    ) -> list[str]:
        ind = self.indent_str * indent
        lines: list[str] = []
        if not message.fields:
            lines.append(f"{ind}public init() {{}}")
            return lines

        params: list[str] = []
        assignments: list[str] = []
        used_names: set[str] = set()
        for field in message.fields:
            field_name = self.safe_member_name(field.name)
            suffix = 1
            base_name = field_name
            while field_name in used_names:
                field_name = f"{base_name}{suffix}"
                suffix += 1
            used_names.add(field_name)

            field_type = self.field_swift_type(field, lineage)
            default_expr = self.field_default_expression(field, lineage)
            if default_expr is None:
                default_expr = "nil"
            params.append(f"{field_name}: {field_type} = {default_expr}")
            assignments.append(f"self.{field_name} = {field_name}")

        lines.append(f"{ind}public init(")
        for i, param in enumerate(params):
            comma = "," if i < len(params) - 1 else ""
            lines.append(f"{ind}{self.indent_str}{param}{comma}")
        lines.append(f"{ind}) {{")
        for assignment in assignments:
            lines.append(f"{ind}{self.indent_str}{assignment}")
        lines.append(f"{ind}}}")
        return lines

    def generate_bytes_methods(self, type_name: str, indent: int) -> list[str]:
        ind = self.indent_str * indent
        lines: list[str] = []
        module_path = self.module_type_path()
        lines.append(f"{ind}public func toBytes() throws -> Data {{")
        lines.append(
            f"{ind}{self.indent_str}try {module_path}.getFory().serialize(self)"
        )
        lines.append(f"{ind}}}")
        lines.append("")
        lines.append(
            f"{ind}public static func fromBytes(_ data: Data) throws -> {type_name} {{"
        )
        lines.append(
            f"{ind}{self.indent_str}try {module_path}.getFory().deserialize(data)"
        )
        lines.append(f"{ind}}}")
        return lines

    def collect_local_types(self) -> list[Message | Enum | Union]:
        local: list[Message | Enum | Union] = []
        for enum in self.schema.enums:
            if not self.is_imported_type(enum):
                local.append(enum)
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                local.append(union)

        def visit_message(message: Message) -> None:
            local.extend(message.nested_enums)
            local.extend(message.nested_unions)
            for nested_msg in message.nested_messages:
                visit_message(nested_msg)
            local.append(message)

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            visit_message(message)

        return local

    def schema_type_name(
        self,
        type_def: Message | Enum | Union,
    ) -> str:
        return self._qualified_type_names.get(id(type_def), type_def.name)

    def schema_swift_type(
        self,
        type_def: Message | Enum | Union,
    ) -> str:
        return self._qualified_type_path(type_def, self.schema_type_name(type_def))

    def generate_module_type(self, indent: int = 0) -> list[str]:
        lines: list[str] = []
        ind = self.indent_str * indent
        module_type_name = self._module_helper_name_for_schema(self.schema)
        lines.append(f"{ind}public enum {module_type_name} {{")

        lines.append(f"{ind}{self.indent_str}private enum Holder {{")
        lines.append(
            f"{ind}{self.indent_str * 2}nonisolated(unsafe) static let fory: Fory = {{"
        )
        lines.append(
            f"{ind}{self.indent_str * 3}let fory = Fory(config: .init(trackRef: true, compatible: true))"
        )
        lines.append(f"{ind}{self.indent_str * 3}do {{")
        lines.append(f"{ind}{self.indent_str * 4}try install(fory)")
        lines.append(f"{ind}{self.indent_str * 3}}} catch {{")
        lines.append(
            f'{ind}{self.indent_str * 4}fatalError("failed to install generated Fory module: \\(error)")'
        )
        lines.append(f"{ind}{self.indent_str * 3}" + "}")
        lines.append(f"{ind}{self.indent_str * 3}return fory")
        lines.append(f"{ind}{self.indent_str * 2}" + "}()")
        lines.append(f"{ind}{self.indent_str}" + "}")
        lines.append("")

        lines.append(f"{ind}{self.indent_str}public static func getFory() -> Fory {{")
        lines.append(f"{ind}{self.indent_str * 2}Holder.fory")
        lines.append(f"{ind}{self.indent_str}" + "}")
        lines.append("")

        lines.append(
            f"{ind}{self.indent_str}public static func install(_ fory: Fory) throws {{"
        )
        for imported_module in self._collect_imported_module_paths():
            lines.append(
                f"{ind}{self.indent_str * 2}try {imported_module}.install(fory)"
            )

        namespace = self.package or ""
        for type_def in self.collect_local_types():
            type_name = self.schema_swift_type(type_def)
            if self.should_register_by_id(type_def):
                lines.append(
                    f"{ind}{self.indent_str * 2}try fory.register({type_name}.self, id: {type_def.type_id})"
                )
                continue

            schema_type_name = self.schema_type_name(type_def)
            if namespace:
                schema_type_name = f"{namespace}.{schema_type_name}"
            escaped_name = schema_type_name.replace('"', '\\"')
            lines.append(
                f'{ind}{self.indent_str * 2}try fory.register({type_name}.self, name: "{escaped_name}")'
            )
        lines.append(f"{ind}{self.indent_str}" + "}")

        lines.append(f"{ind}" + "}")
        return lines


def validate_swift_generation(
    graph: list[tuple[Path, Schema]],
    namespace_style: str | None = None,
    grpc: bool = False,
) -> bool:
    """Preflight Swift output paths and top-level symbol owners before writing."""
    output_owners: dict[str, list[str]] = {}
    symbol_owners: dict[str, list[str]] = {}
    for path, schema in graph:
        options = GeneratorOptions(
            output_dir=Path("."), swift_namespace_style=namespace_style
        )
        generator = SwiftGenerator(schema, options)
        output_owners.setdefault(generator.output_file_path(), []).append(
            f"{path} schema module"
        )
        for symbol in generator.swift_model_top_level_symbols():
            symbol_owners.setdefault(symbol, []).append(f"{path} schema type")
        if grpc:
            for service in schema.services:
                if generator.is_imported_type(service):
                    continue
                output_owners.setdefault(
                    generator.swift_grpc_output_path(service), []
                ).append(f"{path} service {service.name}")
                for symbol in generator.swift_grpc_service_symbols(service):
                    symbol_owners.setdefault(symbol, []).append(
                        f"{path} service {service.name}"
                    )

    _raise_swift_collision(
        output_owners,
        "Swift generated file path collision; rename schema files or services, "
        "or use distinct packages",
    )
    _raise_swift_collision(
        symbol_owners,
        "Swift top-level symbol collision; rename schema types or services, "
        "or use distinct packages",
    )
    return True


def _raise_swift_collision(owners: dict[str, list[str]], message: str) -> None:
    collisions = {key: names for key, names in owners.items() if len(names) > 1}
    if not collisions:
        return
    details = ", ".join(
        f"{key}: {', '.join(names)}" for key, names in sorted(collisions.items())
    )
    raise ValueError(f"{message}. Collisions: {details}")
