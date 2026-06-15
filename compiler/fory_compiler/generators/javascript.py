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

"""JavaScript code generator."""

import re
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union as TypingUnion

from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.generators.services.javascript import JavaScriptServiceGeneratorMixin
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


class JavaScriptGenerator(JavaScriptServiceGeneratorMixin, BaseGenerator):
    """Generates JavaScript type definitions and Fory registration helpers from IDL."""

    language_name = "javascript"
    file_extension = ".ts"
    RUNTIME_FORY = "__foryRuntime$Fory"
    RUNTIME_TYPE = "__foryRuntime$Type"
    RUNTIME_DECIMAL = "__foryRuntime$Decimal"
    RUNTIME_SERIALIZER = "__foryRuntime$Serializer"
    ROOT_REGISTRATION = "__foryGenerated$RootRegistration"

    # JavaScript reserved keywords that cannot be used as identifiers
    TS_KEYWORDS = {
        "abstract",
        "any",
        "as",
        "asserts",
        "async",
        "await",
        "bigint",
        "boolean",
        "break",
        "case",
        "catch",
        "class",
        "const",
        "continue",
        "debugger",
        "declare",
        "default",
        "delete",
        "do",
        "else",
        "enum",
        "export",
        "extends",
        "false",
        "finally",
        "for",
        "from",
        "function",
        "get",
        "if",
        "implements",
        "import",
        "in",
        "infer",
        "instanceof",
        "interface",
        "is",
        "keyof",
        "let",
        "module",
        "namespace",
        "never",
        "new",
        "null",
        "number",
        "object",
        "of",
        "package",
        "private",
        "protected",
        "public",
        "readonly",
        "require",
        "return",
        "set",
        "static",
        "string",
        "super",
        "switch",
        "symbol",
        "this",
        "throw",
        "true",
        "try",
        "type",
        "typeof",
        "undefined",
        "unique",
        "unknown",
        "var",
        "void",
        "while",
        "with",
        "yield",
    }

    # Mapping from FDL primitive types to JavaScript types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "boolean",
        PrimitiveKind.INT8: "number",
        PrimitiveKind.INT16: "number",
        PrimitiveKind.INT32: "number",
        PrimitiveKind.INT64: "bigint | number",
        PrimitiveKind.UINT8: "number",
        PrimitiveKind.UINT16: "number",
        PrimitiveKind.UINT32: "number",
        PrimitiveKind.UINT64: "bigint | number",
        PrimitiveKind.FLOAT16: "number",
        PrimitiveKind.BFLOAT16: "number",
        PrimitiveKind.FLOAT32: "number",
        PrimitiveKind.FLOAT64: "number",
        PrimitiveKind.STRING: "string",
        PrimitiveKind.BYTES: "Uint8Array",
        PrimitiveKind.DATE: "Date",
        PrimitiveKind.TIMESTAMP: "Date",
        PrimitiveKind.DURATION: "number",
        PrimitiveKind.DECIMAL: RUNTIME_DECIMAL,
        PrimitiveKind.ANY: "any",
    }

    # Mapping from FDL primitive types to Fory JS runtime Type.xxx() calls
    PRIMITIVE_RUNTIME_MAP = {
        PrimitiveKind.BOOL: f"{RUNTIME_TYPE}.bool()",
        PrimitiveKind.INT8: f"{RUNTIME_TYPE}.int8()",
        PrimitiveKind.INT16: f"{RUNTIME_TYPE}.int16()",
        PrimitiveKind.INT32: f"{RUNTIME_TYPE}.int32()",
        PrimitiveKind.INT64: f"{RUNTIME_TYPE}.int64()",
        PrimitiveKind.UINT8: f"{RUNTIME_TYPE}.uint8()",
        PrimitiveKind.UINT16: f"{RUNTIME_TYPE}.uint16()",
        PrimitiveKind.UINT32: f"{RUNTIME_TYPE}.uint32()",
        PrimitiveKind.UINT64: f"{RUNTIME_TYPE}.uint64()",
        PrimitiveKind.FLOAT16: f"{RUNTIME_TYPE}.float16()",
        PrimitiveKind.BFLOAT16: f"{RUNTIME_TYPE}.bfloat16()",
        PrimitiveKind.FLOAT32: f"{RUNTIME_TYPE}.float32()",
        PrimitiveKind.FLOAT64: f"{RUNTIME_TYPE}.float64()",
        PrimitiveKind.STRING: f"{RUNTIME_TYPE}.string()",
        PrimitiveKind.BYTES: f"{RUNTIME_TYPE}.binary()",
        PrimitiveKind.DATE: f"{RUNTIME_TYPE}.date()",
        PrimitiveKind.TIMESTAMP: f"{RUNTIME_TYPE}.timestamp()",
        PrimitiveKind.DURATION: f"{RUNTIME_TYPE}.duration()",
        PrimitiveKind.DECIMAL: f"{RUNTIME_TYPE}.decimal()",
        PrimitiveKind.ANY: f"{RUNTIME_TYPE}.any()",
    }

    PRIMITIVE_ARRAY_TS_MAP = {
        PrimitiveKind.BOOL: "boolean[]",
        PrimitiveKind.INT8: "Int8Array",
        PrimitiveKind.INT16: "Int16Array",
        PrimitiveKind.INT32: "Int32Array",
        PrimitiveKind.INT64: "BigInt64Array | bigint[] | number[]",
        PrimitiveKind.UINT8: "Uint8Array",
        PrimitiveKind.UINT16: "Uint16Array",
        PrimitiveKind.UINT32: "Uint32Array",
        PrimitiveKind.UINT64: "BigUint64Array | bigint[] | number[]",
        PrimitiveKind.FLOAT16: "number[]",
        PrimitiveKind.BFLOAT16: "number[]",
        PrimitiveKind.FLOAT32: "Float32Array",
        PrimitiveKind.FLOAT64: "Float64Array",
    }

    PRIMITIVE_ARRAY_RUNTIME_MAP = {
        PrimitiveKind.BOOL: f"{RUNTIME_TYPE}.boolArray()",
        PrimitiveKind.INT8: f"{RUNTIME_TYPE}.int8Array()",
        PrimitiveKind.INT16: f"{RUNTIME_TYPE}.int16Array()",
        PrimitiveKind.INT32: f"{RUNTIME_TYPE}.int32Array()",
        PrimitiveKind.INT64: f"{RUNTIME_TYPE}.int64Array()",
        PrimitiveKind.UINT8: f"{RUNTIME_TYPE}.uint8Array()",
        PrimitiveKind.UINT16: f"{RUNTIME_TYPE}.uint16Array()",
        PrimitiveKind.UINT32: f"{RUNTIME_TYPE}.uint32Array()",
        PrimitiveKind.UINT64: f"{RUNTIME_TYPE}.uint64Array()",
        PrimitiveKind.FLOAT16: f"{RUNTIME_TYPE}.float16Array()",
        PrimitiveKind.BFLOAT16: f"{RUNTIME_TYPE}.bfloat16Array()",
        PrimitiveKind.FLOAT32: f"{RUNTIME_TYPE}.float32Array()",
        PrimitiveKind.FLOAT64: f"{RUNTIME_TYPE}.float64Array()",
    }

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self.indent_str = "  "  # JavaScript uses 2 spaces
        self._qualified_type_names: Dict[int, str] = {}
        self._ts_type_names: Dict[int, str] = {}
        self._schema_cache: Dict[Path, Schema] = {}
        self._build_qualified_type_name_index()

    def _build_qualified_type_name_index(self) -> None:
        """Build an index mapping type object ids to their qualified names."""
        for enum in self.schema.enums:
            self._qualified_type_names[id(enum)] = enum.name
            self._ts_type_names[id(enum)] = self.safe_type_identifier(enum.name)
        for union in self.schema.unions:
            self._qualified_type_names[id(union)] = union.name
            self._ts_type_names[id(union)] = self.safe_type_identifier(union.name)

        def visit_message(
            message: Message, ts_parents: List[str], schema_parents: List[str]
        ) -> None:
            schema_path = ".".join(schema_parents + [message.name])
            ts_path = ".".join(ts_parents + [self.safe_type_identifier(message.name)])

            self._qualified_type_names[id(message)] = schema_path
            self._ts_type_names[id(message)] = ts_path

            for nested_enum in message.nested_enums:
                self._qualified_type_names[id(nested_enum)] = (
                    f"{schema_path}.{nested_enum.name}"
                )
                self._ts_type_names[id(nested_enum)] = (
                    f"{ts_path}.{self.safe_type_identifier(nested_enum.name)}"
                )
            for nested_union in message.nested_unions:
                self._qualified_type_names[id(nested_union)] = (
                    f"{schema_path}.{nested_union.name}"
                )
                self._ts_type_names[id(nested_union)] = (
                    f"{ts_path}.{self.safe_type_identifier(nested_union.name)}"
                )
            for nested_msg in message.nested_messages:
                visit_message(
                    nested_msg,
                    ts_parents + [self.safe_type_identifier(message.name)],
                    schema_parents + [message.name],
                )

        for message in self.schema.messages:
            visit_message(message, [], [])

    def safe_identifier(self, name: str) -> str:
        """Escape identifiers that collide with JavaScript reserved words."""
        if name in self.TS_KEYWORDS:
            return f"{name}_"
        return name

    def safe_type_identifier(self, name: str) -> str:
        """Escape type names that collide with JavaScript reserved words."""
        return self.safe_identifier(self.to_pascal_case(name))

    def safe_member_name(self, name: str) -> str:
        """Generate a safe camelCase member name."""
        return self.safe_identifier(self.to_camel_case(name))

    def _nested_type_names_for_message(self, message: Message) -> Set[str]:
        """Collect safe type names of nested types to detect collisions."""
        names: Set[str] = set()
        for nested in (
            list(message.nested_enums)
            + list(message.nested_unions)
            + list(message.nested_messages)
        ):
            ts_name = self._ts_type_names.get(id(nested))
            if ts_name:
                names.add(ts_name)
            else:
                names.add(self.safe_type_identifier(nested.name))
        return names

    def _field_member_name(
        self,
        field: Field,
        message: Message,
        used_names: Set[str],
    ) -> str:
        """Produce a unique safe member name for a field, avoiding collisions."""
        base = self.safe_member_name(field.name)
        nested_type_names = self._nested_type_names_for_message(message)
        if base in nested_type_names:
            base = f"{base}Value"

        candidate = base
        suffix = 1
        while candidate in used_names:
            candidate = f"{base}{suffix}"
            suffix += 1
        used_names.add(candidate)
        return candidate

    def is_imported_type(self, type_def: object) -> bool:
        """Return True if a type definition comes from an imported IDL file."""
        if not self.schema.source_file:
            return False
        location = getattr(type_def, "location", None)
        if location is None or not location.file:
            return False
        try:
            return (
                Path(location.file).resolve() != Path(self.schema.source_file).resolve()
            )
        except Exception:
            return location.file != self.schema.source_file

    def _get_type_package(self, type_def: object) -> str:
        """Return the package that owns *type_def*.

        For types defined in the current schema this is ``self.schema.package``.
        For imported types it is the package declared in the imported schema.
        """
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        if file_path and self.is_imported_type(type_def):
            imported_schema = self._load_schema(file_path)
            if imported_schema is not None:
                return imported_schema.package or "default"
        return self.schema.package or "default"

    def _type_evolving(self, message: Message) -> bool:
        if "evolving" in message.options:
            return bool(message.options.get("evolving"))
        location = getattr(message, "location", None)
        file_path = getattr(location, "file", None) if location else None
        if file_path and self.is_imported_type(message):
            imported_schema = self._load_schema(file_path)
            if imported_schema is not None:
                file_default = imported_schema.get_option("evolving")
                return True if file_default is None else bool(file_default)
        return self.get_effective_evolving(message)

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
        """Get the JavaScript module name from source file or package."""
        if self.schema.source_file and not self.schema.source_file.startswith("<"):
            return Path(self.schema.source_file).stem
        if self.package:
            return self.package.replace(".", "_")
        return "generated"

    def get_registration_function_name(self) -> str:
        """Get the name of the public schema registration function."""
        return f"register{self.to_pascal_case(self.get_module_name())}Types"

    def _safe_module_alias(self, module_path: str, index: int) -> str:
        stem = module_path.rsplit("/", 1)[-1]
        normalized = re.sub(r"[^0-9A-Za-z_]", "_", stem)
        if not normalized or normalized[0].isdigit():
            normalized = f"module_{normalized}"
        return f"_{self.safe_identifier(self.to_camel_case(normalized))}Module{index}"

    def _normalize_import_path(self, path_str: str) -> str:
        if not path_str:
            return path_str
        try:
            return str(Path(path_str).resolve())
        except Exception:
            return path_str

    def _load_schema(self, file_path: str) -> Optional[Schema]:
        if not file_path:
            return None
        path = Path(file_path).resolve()
        if path in self._schema_cache:
            return self._schema_cache[path]
        try:
            schema = parse_idl_file(path)
        except Exception:
            return None
        self._schema_cache[path] = schema
        return schema

    def _module_name_for_schema(self, schema: Schema) -> str:
        """Derive a module name from another schema."""
        if schema.source_file and not schema.source_file.startswith("<"):
            return Path(schema.source_file).stem
        if schema.package:
            return schema.package.replace(".", "_")
        return "generated"

    def _registration_fn_for_module_path(self, module_path: str) -> str:
        stem = module_path.rsplit("/", 1)[-1]
        return f"register{self.to_pascal_case(stem)}Types"

    def _collect_imported_modules(self) -> List[str]:
        """Collect generated module paths that own imported schema types."""
        file_info: Dict[str, str] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            location = getattr(type_def, "location", None)
            file_path = getattr(location, "file", None) if location else None
            if not file_path:
                continue
            normalized = self._normalize_import_path(file_path)
            if normalized in file_info:
                continue
            imported_schema = self._load_schema(file_path)
            if imported_schema is None:
                continue
            mod_name = self._module_name_for_schema(imported_schema)
            file_info[normalized] = f"./{mod_name}"

        ordered: List[str] = []
        used: Set[str] = set()

        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = self._normalize_import_path(
                    str((base_dir / imp.path).resolve())
                )
                if candidate in file_info and candidate not in used:
                    ordered.append(file_info[candidate])
                    used.add(candidate)

        for key in sorted(file_info.keys()):
            if key in used:
                continue
            ordered.append(file_info[key])

        deduped: List[str] = []
        seen: Set[str] = set()
        for item in ordered:
            if item in seen:
                continue
            seen.add(item)
            deduped.append(item)
        return deduped

    def _imported_module_aliases(self) -> List[Tuple[str, str]]:
        return [
            (module_path, self._safe_module_alias(module_path, index))
            for index, module_path in enumerate(self._collect_imported_modules())
        ]

    def _resolve_named_type(
        self, name: str, parent_stack: Optional[List[Message]] = None
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        """Resolve a named type reference to its definition."""
        parent_stack = parent_stack or []
        if "." in name:
            return self.schema.get_type(name)
        for msg in reversed(parent_stack):
            nested = msg.get_nested_type(name)
            if nested is not None:
                return nested
        return self.schema.get_type(name)

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Generate JavaScript type string for a field type."""
        parent_stack = parent_stack or []
        type_str = ""

        if isinstance(field_type, PrimitiveType):
            if field_type.kind not in self.PRIMITIVE_MAP:
                raise ValueError(
                    f"Unsupported primitive type for JavaScript: {field_type.kind}"
                )
            type_str = self.PRIMITIVE_MAP[field_type.kind]
        elif isinstance(field_type, NamedType):
            # Check if this NamedType matches a primitive type name
            primitive_name = field_type.name.lower()
            # Map common shorthand names to primitive kinds
            shorthand_map = {
                "float": PrimitiveKind.FLOAT32,
                "double": PrimitiveKind.FLOAT64,
            }
            # Reject unsupported primitives referenced by name
            for pk in PrimitiveKind:
                if pk.value == primitive_name and pk not in self.PRIMITIVE_MAP:
                    raise ValueError(f"Unsupported primitive type for JavaScript: {pk}")
            if primitive_name in shorthand_map:
                type_str = self.PRIMITIVE_MAP.get(shorthand_map[primitive_name], "any")
            else:
                # Check if it matches any primitive kind directly
                for primitive_kind, ts_type in self.PRIMITIVE_MAP.items():
                    if primitive_kind.value == primitive_name:
                        type_str = ts_type
                        break
                if not type_str:
                    resolved = self._resolve_named_type(field_type.name, parent_stack)
                    if resolved is not None:
                        type_str = self._ts_type_names.get(
                            id(resolved),
                            self.safe_type_identifier(resolved.name),
                        )
                    else:
                        type_str = self.safe_type_identifier(
                            self.to_pascal_case(field_type.name)
                        )
        elif isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=field_type.element_optional,
                parent_stack=parent_stack,
            )
            if "|" in element_type:
                type_str = f"({element_type})[]"
            else:
                type_str = f"{element_type}[]"
        elif isinstance(field_type, ArrayType):
            type_str = self.PRIMITIVE_ARRAY_TS_MAP[field_type.element_type.kind]
        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            value_type = self.generate_type(
                field_type.value_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            type_str = f"Map<{key_type}, {value_type}>"
        else:
            type_str = "any"

        if nullable:
            type_str += " | null"

        return type_str

    def generate_imports(self) -> List[str]:
        """Generate import statements for Fory runtime and imported types."""
        lines: List[str] = []
        imported_modules = self._imported_module_aliases()

        core_imports = [f"Type as {self.RUNTIME_TYPE}"]
        if self._schema_uses_primitive_kind(PrimitiveKind.DECIMAL):
            core_imports.append(f"Decimal as {self.RUNTIME_DECIMAL}")
        lines.append(
            f"import {self.RUNTIME_FORY}, {{ {', '.join(core_imports)} }} from '@apache-fory/core';"
        )
        if self._local_registration_roots():
            lines.append(
                f"import type {{ Serializer as {self.RUNTIME_SERIALIZER} }} "
                "from '@apache-fory/core';"
            )

        for mod_path, alias in imported_modules:
            lines.append(f"import * as {alias} from '{mod_path}';")

        # Collect all imported types used in this schema
        imported_types_by_module: Dict[str, Set[str]] = {}

        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue

            location = getattr(type_def, "location", None)
            file_path = getattr(location, "file", None) if location else None
            if not file_path:
                continue

            imported_schema = self._load_schema(file_path)
            if imported_schema is None:
                continue

            mod_name = self._module_name_for_schema(imported_schema)
            mod_path = f"./{mod_name}"

            if mod_path not in imported_types_by_module:
                imported_types_by_module[mod_path] = set()

            imported_types_by_module[mod_path].add(
                self.safe_type_identifier(type_def.name)
            )

            # If it's a union, also import the Case enum
            if isinstance(type_def, Union):
                imported_types_by_module[mod_path].add(
                    self.safe_type_identifier(f"{type_def.name}Case")
                )

        # Generate import statements
        for mod_path, types in sorted(imported_types_by_module.items()):
            if types:
                types_str = ", ".join(sorted(types))
                lines.append(f"import {{ {types_str} }} from '{mod_path}';")

        return lines

    def _schema_uses_primitive_kind(self, primitive_kind: PrimitiveKind) -> bool:
        def uses_field_type(field_type: FieldType) -> bool:
            if isinstance(field_type, PrimitiveType):
                return field_type.kind == primitive_kind
            if isinstance(field_type, NamedType):
                return field_type.name.lower() == primitive_kind.value
            if isinstance(field_type, ListType):
                return uses_field_type(field_type.element_type)
            if isinstance(field_type, MapType):
                return uses_field_type(field_type.key_type) or uses_field_type(
                    field_type.value_type
                )
            return False

        def uses_message(message: Message) -> bool:
            for field in message.fields:
                if uses_field_type(field.field_type):
                    return True
            for nested_union in message.nested_unions:
                if any(
                    uses_field_type(field.field_type) for field in nested_union.fields
                ):
                    return True
            return any(uses_message(nested) for nested in message.nested_messages)

        if any(
            uses_field_type(field.field_type)
            for union in self.schema.unions
            for field in union.fields
        ):
            return True
        return any(uses_message(message) for message in self.schema.messages)

    def generate(self) -> List[GeneratedFile]:
        """Generate JavaScript files for the schema."""
        return [self.generate_file()]

    def generate_file(self) -> GeneratedFile:
        """Generate a single JavaScript module with all types."""
        lines: List[str] = []

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        imports = self.generate_imports()
        if imports:
            for imp in imports:
                lines.append(imp)
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

        # Generate schema module helpers
        root_registration_type = self.generate_root_registration_type()
        if root_registration_type:
            lines.extend(root_registration_type)
            lines.append("")
        lines.extend(self.generate_registration())
        lines.append("")
        lines.extend(self.generate_default_helpers())
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_module_name()}{self.file_extension}",
            content="\n".join(lines),
        )

    def generate_enum(
        self,
        enum: Enum,
        indent: int = 0,
        ts_name: Optional[str] = None,
    ) -> List[str]:
        """Generate a JavaScript enum."""
        lines: List[str] = []
        ind = self.indent_str * indent
        comment = self.format_type_id_comment(enum, f"{ind}//")
        if comment:
            lines.append(comment)

        enum_name = ts_name or self._ts_type_names.get(
            id(enum), self.safe_type_identifier(enum.name)
        )
        lines.append(f"{ind}export enum {enum_name} {{")
        for value in enum.values:
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            value_name = self.safe_identifier(stripped_name)
            lines.append(f"{ind}{self.indent_str}{value_name} = {value.value},")
        lines.append(f"{ind}}}")

        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
        ts_name: Optional[str] = None,
    ) -> List[str]:
        """Generate a JavaScript interface for a message."""
        lines: List[str] = []
        ind = self.indent_str * indent
        parent_stack = parent_stack or []
        lineage = parent_stack + [message]
        type_name = ts_name or self._ts_type_names.get(
            id(message), self.safe_type_identifier(message.name)
        )

        comment = self.format_type_id_comment(message, f"{ind}//")
        if comment:
            lines.append(comment)

        lines.append(f"{ind}export interface {type_name} {{")

        # Generate fields with safe, deduplicated names
        used_field_names: Set[str] = set()
        for field in message.fields:
            field_name = self._field_member_name(field, message, used_field_names)
            field_type = self.generate_type(
                field.field_type,
                nullable=field.optional,
                parent_stack=lineage,
            )
            optional_marker = "?" if field.optional else ""
            lines.append(
                f"{ind}{self.indent_str}{field_name}{optional_marker}: {field_type};"
            )

        lines.append(f"{ind}}}")

        # Generate nested types inside a namespace matching the message name
        has_nested = (
            message.nested_enums or message.nested_unions or message.nested_messages
        )
        if has_nested:
            lines.append("")
            lines.append(
                f"{ind}export namespace {self.safe_type_identifier(message.name)} {{"
            )

            for nested_enum in message.nested_enums:
                lines.append("")
                nested_ts = self.safe_type_identifier(nested_enum.name)
                lines.extend(
                    self.generate_enum(
                        nested_enum, indent=indent + 1, ts_name=nested_ts
                    )
                )

            for nested_union in message.nested_unions:
                lines.append("")
                nested_ts = self.safe_type_identifier(nested_union.name)
                lines.extend(
                    self.generate_union(
                        nested_union,
                        indent=indent + 1,
                        parent_stack=lineage,
                        ts_name=nested_ts,
                    )
                )

            for nested_msg in message.nested_messages:
                lines.append("")
                nested_ts = self.safe_type_identifier(nested_msg.name)
                lines.extend(
                    self.generate_message(
                        nested_msg,
                        indent=indent + 1,
                        parent_stack=lineage,
                        ts_name=nested_ts,
                    )
                )
            lines.append(f"{ind}}}")

        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
        ts_name: Optional[str] = None,
    ) -> List[str]:
        """Generate a JavaScript discriminated union.

        ts_name overrides the emitted identifier for the union type alias and
        its companion case enum; used for nested unions prefixed with the
        parent chain (e.g. PersonAnimal / PersonAnimalCase).
        """
        lines: List[str] = []
        ind = self.indent_str * indent
        union_name = ts_name or self._ts_type_names.get(
            id(union), self.safe_type_identifier(union.name)
        )

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)

        # Case-enum name is derived from the union's TS name so nested unions
        # get e.g. PersonAnimalCase rather than a bare AnimalCase that could
        # collide with another parent's nested union of the same short name.
        case_enum_name = f"{union_name}Case"
        lines.append(f"{ind}export enum {case_enum_name} {{")
        for field in union.fields:
            case_name = self.safe_identifier(self.to_upper_snake_case(field.name))
            lines.append(f"{ind}{self.indent_str}{case_name} = {field.number},")
        lines.append(f"{ind}}}")
        lines.append("")

        # Generate union type as discriminated union
        union_cases = []
        for field in union.fields:
            field_type_str = self.generate_type(
                field.field_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            case_value = self.safe_identifier(self.to_upper_snake_case(field.name))
            union_cases.append(
                f"{ind}{self.indent_str}| ( {{ case: {case_enum_name}.{case_value}; value: {field_type_str} }} )"
            )

        lines.append(f"{ind}export type {union_name} =")
        lines.extend(union_cases)
        lines.append(f"{ind}{self.indent_str};")

        return lines

    def _field_type_expr(
        self,
        field_type: FieldType,
        parent_stack: Optional[List[Message]] = None,
        *,
        nullable: bool = False,
        ref: bool = False,
        element_nullable_override: bool = False,
        element_ref_override: bool = False,
    ) -> str:
        """Return the Fory JS runtime ``Type.xxx()`` expression for a field type."""
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            integer_expr = self._integer_type_expr(field_type)
            if integer_expr is not None:
                expr = integer_expr
            else:
                expr = self.PRIMITIVE_RUNTIME_MAP.get(field_type.kind)
                if expr is None:
                    expr = f"{self.RUNTIME_TYPE}.any()"
        elif isinstance(field_type, NamedType):
            # Check for primitive-like shorthand names (e.g. "float", "double")
            lower = field_type.name.lower()
            shorthand_map = {
                "float": PrimitiveKind.FLOAT32,
                "double": PrimitiveKind.FLOAT64,
            }
            if lower in shorthand_map:
                expr = self.PRIMITIVE_RUNTIME_MAP[shorthand_map[lower]]
            else:
                primitive_expr: Optional[str] = None
                for pk in PrimitiveKind:
                    if pk.value == lower:
                        primitive_expr = self.PRIMITIVE_RUNTIME_MAP.get(pk)
                        if primitive_expr is None:
                            raise ValueError(
                                f"Primitive type '{pk.value}' has no JavaScript "
                                f"runtime mapping."
                            )
                        break
                if primitive_expr is not None:
                    expr = primitive_expr
                else:
                    # Named type — could be a Message, Enum, or Union
                    resolved = self._resolve_named_type(field_type.name, parent_stack)
                    if isinstance(resolved, Enum):
                        if self.should_register_by_id(resolved):
                            name_info = str(resolved.type_id)
                        else:
                            ns = self._get_type_package(resolved)
                            qname = self._qualified_type_names.get(
                                id(resolved), resolved.name
                            )
                            name_info = f'"{ns}.{qname}"'
                        props = ", ".join(
                            f"{self.strip_enum_prefix(resolved.name, v.name)}: {v.value}"
                            for v in resolved.values
                        )
                        expr = f"{self.RUNTIME_TYPE}.enum({name_info}, {{ {props} }})"
                    elif isinstance(resolved, Union):
                        case_parts = []
                        for case_field in resolved.fields:
                            case_num = (
                                case_field.tag_id
                                if case_field.tag_id is not None
                                else case_field.number
                            )
                            if case_num is not None:
                                case_type_expr = self._field_type_expr(
                                    case_field.field_type, parent_stack
                                )
                                case_parts.append(f"{case_num}: {case_type_expr}")
                        cases_arg = (
                            f", {{ {', '.join(case_parts)} }}" if case_parts else ""
                        )
                        if self.should_register_by_id(resolved):
                            name_info = str(resolved.type_id)
                        else:
                            ns = self._get_type_package(resolved)
                            qname = self._qualified_type_names.get(
                                id(resolved), resolved.name
                            )
                            name_info = f'{{ namespace: "{ns}", typeName: "{qname}" }}'
                        expr = f"{self.RUNTIME_TYPE}.union({name_info}{cases_arg})"
                    elif isinstance(resolved, Message):
                        evolving = self._type_evolving(resolved)
                        if self.should_register_by_id(resolved):
                            if evolving:
                                expr = f"{self.RUNTIME_TYPE}.struct({resolved.type_id})"
                            else:
                                expr = (
                                    f"{self.RUNTIME_TYPE}.struct({{ typeId: {resolved.type_id}, "
                                    "evolving: false })"
                                )
                        else:
                            ns = self._get_type_package(resolved)
                            qname = self._qualified_type_names.get(
                                id(resolved), resolved.name
                            )
                            if evolving:
                                expr = (
                                    f'{self.RUNTIME_TYPE}.struct({{ namespace: "{ns}", '
                                    f'typeName: "{qname}" }})'
                                )
                            else:
                                expr = (
                                    f'{self.RUNTIME_TYPE}.struct({{ namespace: "{ns}", '
                                    f'typeName: "{qname}", evolving: false }})'
                                )
                    else:
                        # Unresolved — fall back to any
                        expr = f"{self.RUNTIME_TYPE}.any()"
        elif isinstance(field_type, ListType):
            inner = self._field_type_expr(
                field_type.element_type,
                parent_stack,
                nullable=field_type.element_optional or element_nullable_override,
                ref=field_type.element_ref or element_ref_override,
            )
            expr = f"{self.RUNTIME_TYPE}.list({inner})"
        elif isinstance(field_type, ArrayType):
            if isinstance(field_type.element_type, PrimitiveType):
                type_expr = self.PRIMITIVE_ARRAY_RUNTIME_MAP.get(
                    field_type.element_type.kind
                )
                if type_expr:
                    expr = type_expr
                else:
                    expr = f"{self.RUNTIME_TYPE}.any()"
            else:
                expr = f"{self.RUNTIME_TYPE}.any()"
        elif isinstance(field_type, MapType):
            key = self._field_type_expr(field_type.key_type, parent_stack)
            value = self._field_type_expr(
                field_type.value_type,
                parent_stack,
                nullable=field_type.value_optional,
                ref=field_type.value_ref,
            )
            expr = f"{self.RUNTIME_TYPE}.map({key}, {value})"
        else:
            expr = f"{self.RUNTIME_TYPE}.any()"
        if nullable or ref:
            expr += ".setNullable(true)"
        if ref:
            expr += ".setTrackingRef(true)"
        return expr

    def _integer_type_expr(self, field_type: PrimitiveType) -> Optional[str]:
        method = {
            PrimitiveKind.INT32: "int32",
            PrimitiveKind.INT64: "int64",
            PrimitiveKind.UINT32: "uint32",
            PrimitiveKind.UINT64: "uint64",
        }.get(field_type.kind)
        if method is None:
            return None
        encoding = field_type.encoding_modifier
        if encoding in ("fixed", "tagged"):
            return f'{self.RUNTIME_TYPE}.{method}({{ encoding: "{encoding}" }})'
        return f"{self.RUNTIME_TYPE}.{method}()"

    def _register_type_line(
        self,
        type_def: TypingUnion[Message, Enum, Union],
        target_var: str = "fory",
        parent_stack: Optional[List[Message]] = None,
        registration_var: Optional[str] = None,
        registration_type: Optional[str] = None,
    ) -> str:
        """Return a single ``fory.register(Type.struct(...))`` statement."""
        if isinstance(type_def, Union):
            return ""
        if not isinstance(type_def, Message):
            return ""

        field_parent_stack = (parent_stack or []) + [type_def]
        props_parts: List[str] = []
        used_field_names: Set[str] = set()
        for field in type_def.fields:
            member = self._field_member_name(field, type_def, used_field_names)
            expr = self._field_type_expr(
                field.field_type,
                field_parent_stack,
                nullable=field.optional or field.ref,
                ref=field.ref,
                element_nullable_override=field.element_optional,
                element_ref_override=field.element_ref,
            )
            if field.tag_id is not None:
                expr += f".setId({field.tag_id})"
            props_parts.append(f"{member}: {expr}")

        props_str = ", ".join(props_parts)
        props_arg = f", {{ {props_str} }}" if props_parts else ""

        evolving = self._type_evolving(type_def)
        if self.should_register_by_id(type_def):
            if evolving:
                name_info = str(type_def.type_id)
            else:
                name_info = f"{{ typeId: {type_def.type_id}, evolving: false }}"
        else:
            ns = self.schema.package or "default"
            qname = self._qualified_type_names.get(id(type_def), type_def.name)
            if evolving:
                name_info = f'{{ namespace: "{ns}", typeName: "{qname}" }}'
            else:
                name_info = (
                    f'{{ namespace: "{ns}", typeName: "{qname}", evolving: false }}'
                )

        register_expr = (
            f"{target_var}.register({self.RUNTIME_TYPE}.struct({name_info}{props_arg}))"
        )
        if registration_var is not None:
            cast = f" as unknown as {registration_type}" if registration_type else ""
            return f"const {registration_var} = {register_expr}{cast};"
        return f"{register_expr};"

    def _register_union_line(
        self,
        union_def,
        target_var: str = "fory",
        parent_stack: Optional[List[Message]] = None,
        registration_var: Optional[str] = None,
        registration_type: Optional[str] = None,
    ) -> str:
        """Return a ``fory.register(Type.union(...))`` statement."""
        parent_stack = parent_stack or []
        case_parts: List[str] = []
        for field in union_def.fields:
            case_num = field.tag_id if field.tag_id is not None else field.number
            if case_num is not None:
                case_type_expr = self._field_type_expr(field.field_type, parent_stack)
                case_parts.append(f"{case_num}: {case_type_expr}")
        cases_str = ", ".join(case_parts)
        cases_arg = f", {{ {cases_str} }}" if case_parts else ""

        if self.should_register_by_id(union_def):
            name_info = str(union_def.type_id)
        else:
            ns = self.schema.package or "default"
            qname = self._qualified_type_names.get(id(union_def), union_def.name)
            name_info = f'{{ namespace: "{ns}", typeName: "{qname}" }}'

        register_expr = (
            f"{target_var}.register({self.RUNTIME_TYPE}.union({name_info}{cases_arg}))"
        )
        if registration_var is not None:
            cast = f" as unknown as {registration_type}" if registration_type else ""
            return f"const {registration_var} = {register_expr}{cast};"
        return f"{register_expr};"

    def _local_registration_roots(self) -> List[TypingUnion[Message, Union]]:
        roots: List[TypingUnion[Message, Union]] = []

        def visit_message(message: Message) -> None:
            if self.is_imported_type(message):
                return
            roots.append(message)
            for nested_msg in message.nested_messages:
                visit_message(nested_msg)
            for nested_union in message.nested_unions:
                if not self.is_imported_type(nested_union):
                    roots.append(nested_union)

        for message in self.schema.messages:
            visit_message(message)
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                roots.append(union)
        return roots

    def _root_name_parts(self, type_def: TypingUnion[Message, Union]) -> List[str]:
        qname = self._qualified_type_names.get(id(type_def), type_def.name)
        return [part for part in qname.split(".") if part]

    def _root_helper_base_name(self, type_def: TypingUnion[Message, Union]) -> str:
        return "".join(
            self.safe_type_identifier(part) for part in self._root_name_parts(type_def)
        )

    def _root_registration_field_name(
        self, type_def: TypingUnion[Message, Union]
    ) -> str:
        name = self.safe_identifier(
            self.to_camel_case(self._root_helper_base_name(type_def))
        )
        if name == "fory":
            return "fory_"
        return name

    def _root_serialize_helper_name(self, type_def: TypingUnion[Message, Union]) -> str:
        return f"serialize{self._root_helper_base_name(type_def)}"

    def _root_deserialize_helper_name(
        self, type_def: TypingUnion[Message, Union]
    ) -> str:
        return f"deserialize{self._root_helper_base_name(type_def)}"

    def _root_ts_type_name(self, type_def: TypingUnion[Message, Union]) -> str:
        return self._ts_type_names.get(
            id(type_def),
            self.safe_type_identifier(type_def.name),
        )

    def _root_registration_type(self, type_def: TypingUnion[Message, Union]) -> str:
        return f"{self.ROOT_REGISTRATION}<{self._root_ts_type_name(type_def)}>"

    def generate_root_registration_type(self) -> List[str]:
        if not self._local_registration_roots():
            return []
        return [
            f"type {self.ROOT_REGISTRATION}<T> = {{",
            f"  serializer: {self.RUNTIME_SERIALIZER};",
            "  serialize: (value: T | null) => Uint8Array;",
            "  deserialize: (bytes: Uint8Array) => T;",
            "};",
        ]

    def _validate_local_root_helper_names(
        self, roots: List[TypingUnion[Message, Union]]
    ) -> None:
        used_fields: Dict[str, str] = {}
        used_helpers: Dict[str, str] = {}
        for root in roots:
            type_name = self._qualified_type_names.get(id(root), root.name)
            field = self._root_registration_field_name(root)
            previous = used_fields.get(field)
            if previous is not None:
                raise ValueError(
                    "JavaScript registration field collision: "
                    f"{previous} and {type_name} both generate {field}"
                )
            used_fields[field] = type_name

            for helper in (
                self._root_serialize_helper_name(root),
                self._root_deserialize_helper_name(root),
            ):
                previous = used_helpers.get(helper)
                if previous is not None:
                    raise ValueError(
                        "JavaScript serialization helper collision: "
                        f"{previous} and {type_name} both generate {helper}"
                    )
                used_helpers[helper] = type_name

    def _resolve_field_deps(
        self,
        message: Message,
        parent_stack: List[Message],
    ) -> List[Message]:
        """Return the local Message objects that *message* directly references
        in its fields (excluding self-references and imported types)."""
        lineage = parent_stack + [message]
        deps: List[Message] = []
        seen: Set[int] = set()

        def visit(ft: FieldType) -> None:
            if isinstance(ft, NamedType):
                resolved = self._resolve_named_type(ft.name, lineage)
                if (
                    isinstance(resolved, Message)
                    and not self.is_imported_type(resolved)
                    and id(resolved) != id(message)
                    and id(resolved) not in seen
                ):
                    seen.add(id(resolved))
                    deps.append(resolved)
            elif isinstance(ft, ListType):
                visit(ft.element_type)
            elif isinstance(ft, ArrayType):
                visit(ft.element_type)
            elif isinstance(ft, MapType):
                visit(ft.key_type)
                visit(ft.value_type)

        for field in message.fields:
            visit(field.field_type)
        return deps

    def generate_registration(self) -> List[str]:
        """Generate a module-level registration function for this schema.

        Types are emitted in dependency order (leaf types first) via a
        simple DFS so that the Fory JS runtime does not prematurely
        register bare ``Type.struct(id)`` references with empty fields."""
        lines: List[str] = []
        imported_modules = self._imported_module_aliases()
        fn_name = self.get_registration_function_name()
        local_roots = self._local_registration_roots()
        self._validate_local_root_helper_names(local_roots)
        registered_roots: List[TypingUnion[Message, Union]] = []

        lines.append("// Registration helper")
        lines.append(f"export function {fn_name}(fory: {self.RUNTIME_FORY}) {{")

        # Delegate to imported schema modules first.
        for _module_path, alias in imported_modules:
            reg_fn = self._registration_fn_for_module_path(_module_path)
            lines.append(f"  {alias}.{reg_fn}(fory);")

        # DFS emit: visit dependencies before the type itself.
        # The visited set also breaks cycles (e.g. self-referential trees).
        emitted: Set[int] = set()
        # Pre-build a mapping from message id -> parent_stack so that
        # dependencies emitted out of tree order still get the right context.
        parent_map: Dict[int, List[Message]] = {}

        def build_parent_map(msg: Message, parents: List[Message]) -> None:
            parent_map[id(msg)] = parents
            for nested_msg in msg.nested_messages:
                build_parent_map(nested_msg, parents + [msg])

        for message in self.schema.messages:
            if not self.is_imported_type(message):
                build_parent_map(message, [])

        def emit_message(msg: Message) -> None:
            if id(msg) in emitted or self.is_imported_type(msg):
                return
            # Mark visited early to break cycles (e.g. Node <-> Edge)
            emitted.add(id(msg))
            parents = parent_map.get(id(msg), [])
            # Emit field-level struct dependencies first
            for dep in self._resolve_field_deps(msg, parents):
                emit_message(dep)
            reg_line = self._register_type_line(
                msg,
                "fory",
                parents,
                registration_var=self._root_registration_field_name(msg),
                registration_type=self._root_registration_type(msg),
            )
            if reg_line:
                lines.append(f"  {reg_line}")
                registered_roots.append(msg)
            for nested_msg in msg.nested_messages:
                emit_message(nested_msg)
            for nested_union in msg.nested_unions:
                emit_union(nested_union, parents + [msg])

        emitted_unions: Set[int] = set()

        def emit_union(union: Union, parent_stack: List[Message]) -> None:
            if id(union) in emitted_unions or self.is_imported_type(union):
                return
            emitted_unions.add(id(union))
            for ufield in union.fields:
                if isinstance(ufield.field_type, NamedType):
                    resolved = self._resolve_named_type(
                        ufield.field_type.name, parent_stack
                    )
                    if isinstance(resolved, Message) and not self.is_imported_type(
                        resolved
                    ):
                        emit_message(resolved)
            reg_line = self._register_union_line(
                union,
                "fory",
                parent_stack,
                registration_var=self._root_registration_field_name(union),
                registration_type=self._root_registration_type(union),
            )
            if reg_line:
                lines.append(f"  {reg_line}")
                registered_roots.append(union)

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            emit_message(message)

        # Ensure message-typed union variants are registered even when they are
        # not reachable through any struct field in the DFS above (e.g. a union
        # whose variants are messages defined in the same file but not used as
        # direct struct fields).
        for union in self.schema.unions:
            emit_union(union, [])

        if registered_roots:
            lines.append("  return {")
            for root in registered_roots:
                lines.append(f"    {self._root_registration_field_name(root)},")
            lines.append("  };")
        else:
            lines.append("  return {};")
        lines.append("}")

        return lines

    def generate_default_helpers(self) -> List[str]:
        """Generate private default Fory and root serialization helpers."""
        needs_ref = "true" if self._schema_needs_ref_tracking() else "false"
        lines = [
            f"const MODEL_NEEDS_REF = {needs_ref};",
            f"const DEFAULT_FORY = new {self.RUNTIME_FORY}({{ ref: MODEL_NEEDS_REF }});",
            f"const DEFAULT_TYPES = {self.get_registration_function_name()}(DEFAULT_FORY);",
        ]
        roots = self._local_registration_roots()
        if roots:
            lines.append("")
            for index, root in enumerate(roots):
                field = self._root_registration_field_name(root)
                lines.append(
                    f"export const {self._root_serialize_helper_name(root)} = "
                    f"DEFAULT_TYPES.{field}.serialize;"
                )
                lines.append(
                    f"export const {self._root_deserialize_helper_name(root)} = "
                    f"DEFAULT_TYPES.{field}.deserialize;"
                )
                if index != len(roots) - 1:
                    lines.append("")
        return lines

    def _schema_needs_ref_tracking(self) -> bool:
        def field_type_needs_ref(field_type: FieldType) -> bool:
            if isinstance(field_type, ListType):
                return field_type.element_ref or field_type_needs_ref(
                    field_type.element_type
                )
            if isinstance(field_type, ArrayType):
                return field_type_needs_ref(field_type.element_type)
            if isinstance(field_type, MapType):
                return (
                    field_type.value_ref
                    or field_type_needs_ref(field_type.key_type)
                    or field_type_needs_ref(field_type.value_type)
                )
            return False

        def message_needs_ref(message: Message) -> bool:
            for field in message.fields:
                if field.ref or field_type_needs_ref(field.field_type):
                    return True
            for nested_union in message.nested_unions:
                if union_needs_ref(nested_union):
                    return True
            return any(message_needs_ref(nested) for nested in message.nested_messages)

        def union_needs_ref(union: Union) -> bool:
            return any(
                field.ref or field_type_needs_ref(field.field_type)
                for field in union.fields
            )

        return any(
            message_needs_ref(message) for message in self.schema.messages
        ) or any(union_needs_ref(union) for union in self.schema.unions)
