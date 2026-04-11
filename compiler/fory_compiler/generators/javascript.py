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

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union as TypingUnion

from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.ir.ast import (
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


class JavaScriptGenerator(BaseGenerator):
    """Generates JavaScript type definitions and Fory registration helpers from IDL."""

    language_name = "javascript"
    file_extension = ".ts"

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
        # DECIMAL is not supported by the JS runtime; rejected in _field_type_expr.
        PrimitiveKind.ANY: "any",
    }

    # Mapping from FDL primitive types to Fory JS runtime Type.xxx() calls
    PRIMITIVE_RUNTIME_MAP = {
        PrimitiveKind.BOOL: "Type.bool()",
        PrimitiveKind.INT8: "Type.int8()",
        PrimitiveKind.INT16: "Type.int16()",
        PrimitiveKind.INT32: "Type.int32()",
        PrimitiveKind.VARINT32: "Type.varInt32()",
        PrimitiveKind.INT64: "Type.int64()",
        PrimitiveKind.VARINT64: "Type.varInt64()",
        PrimitiveKind.TAGGED_INT64: "Type.sliInt64()",
        PrimitiveKind.UINT8: "Type.uint8()",
        PrimitiveKind.UINT16: "Type.uint16()",
        PrimitiveKind.UINT32: "Type.uint32()",
        PrimitiveKind.VAR_UINT32: "Type.varUInt32()",
        PrimitiveKind.UINT64: "Type.uint64()",
        PrimitiveKind.VAR_UINT64: "Type.varUInt64()",
        PrimitiveKind.TAGGED_UINT64: "Type.taggedUInt64()",
        PrimitiveKind.FLOAT16: "Type.float16()",
        PrimitiveKind.BFLOAT16: "Type.bfloat16()",
        PrimitiveKind.FLOAT32: "Type.float32()",
        PrimitiveKind.FLOAT64: "Type.float64()",
        PrimitiveKind.STRING: "Type.string()",
        PrimitiveKind.BYTES: "Type.binary()",
        PrimitiveKind.DATE: "Type.date()",
        PrimitiveKind.TIMESTAMP: "Type.timestamp()",
        PrimitiveKind.DURATION: "Type.duration()",
        # DECIMAL is not yet supported by the JS runtime; omitted intentionally.
        PrimitiveKind.ANY: "Type.any()",
    }

    # Mapping from FDL primitive types to Fory JS typed array Type.xxx() calls
    PRIMITIVE_ARRAY_RUNTIME_MAP = {
        PrimitiveKind.BOOL: "Type.boolArray()",
        PrimitiveKind.INT8: "Type.int8Array()",
        PrimitiveKind.INT16: "Type.int16Array()",
        PrimitiveKind.INT32: "Type.int32Array()",
        PrimitiveKind.VARINT32: "Type.int32Array()",
        PrimitiveKind.INT64: "Type.int64Array()",
        PrimitiveKind.VARINT64: "Type.int64Array()",
        PrimitiveKind.TAGGED_INT64: "Type.int64Array()",
        PrimitiveKind.UINT8: "Type.uint8Array()",
        PrimitiveKind.UINT16: "Type.uint16Array()",
        PrimitiveKind.UINT32: "Type.uint32Array()",
        PrimitiveKind.VAR_UINT32: "Type.uint32Array()",
        PrimitiveKind.UINT64: "Type.uint64Array()",
        PrimitiveKind.VAR_UINT64: "Type.uint64Array()",
        PrimitiveKind.TAGGED_UINT64: "Type.uint64Array()",
        PrimitiveKind.FLOAT16: "Type.float16Array()",
        PrimitiveKind.BFLOAT16: "Type.bfloat16Array()",
        PrimitiveKind.FLOAT32: "Type.float32Array()",
        PrimitiveKind.FLOAT64: "Type.float64Array()",
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
            if imported_schema and imported_schema.package:
                return imported_schema.package
        return self.schema.package or "default"

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
        """Get the name of the registration function."""
        return f"register{self.to_pascal_case(self.get_module_name())}Types"

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

    def _registration_fn_for_schema(self, schema: Schema) -> str:
        """Derive the registration function name for an imported schema."""
        mod = self._module_name_for_schema(schema)
        return f"register{self.to_pascal_case(mod)}Types"

    def _collect_imported_registrations(self) -> List[Tuple[str, str]]:
        """Collect (module_path, registration_fn) pairs for imported schemas."""
        file_info: Dict[str, Tuple[str, str]] = {}
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
            reg_fn = self._registration_fn_for_schema(imported_schema)
            mod_name = self._module_name_for_schema(imported_schema)
            file_info[normalized] = (f"./{mod_name}", reg_fn)

        ordered: List[Tuple[str, str]] = []
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

        deduped: List[Tuple[str, str]] = []
        seen: Set[Tuple[str, str]] = set()
        for item in ordered:
            if item in seen:
                continue
            seen.add(item)
            deduped.append(item)
        return deduped

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
                    raise ValueError(
                        f"Unsupported primitive type for JavaScript: {pk}"
                    )
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
        """Generate import statements for imported types and registration functions."""
        lines: List[str] = []
        imported_regs = self._collect_imported_registrations()

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

        # Add registration functions to the imports
        for mod_path, reg_fn in imported_regs:
            if mod_path not in imported_types_by_module:
                imported_types_by_module[mod_path] = set()
            imported_types_by_module[mod_path].add(reg_fn)

        # Generate import statements
        for mod_path, types in sorted(imported_types_by_module.items()):
            if types:
                types_str = ", ".join(sorted(types))
                lines.append(f"import {{ {types_str} }} from '{mod_path}';")

        return lines

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

        # Generate registration function
        lines.extend(self.generate_registration())
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
    ) -> str:
        """Return the Fory JS runtime ``Type.xxx()`` expression for a field type."""
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.DECIMAL:
                raise ValueError(
                    "decimal is not supported by the JavaScript runtime. "
                    "Use a different type or wait for runtime support."
                )
            expr = self.PRIMITIVE_RUNTIME_MAP.get(field_type.kind)
            if expr is None:
                return "Type.any()"
            return expr
        elif isinstance(field_type, NamedType):
            # Check for primitive-like shorthand names (e.g. "float", "double")
            lower = field_type.name.lower()
            shorthand_map = {
                "float": PrimitiveKind.FLOAT32,
                "double": PrimitiveKind.FLOAT64,
            }
            if lower in shorthand_map:
                return self.PRIMITIVE_RUNTIME_MAP[shorthand_map[lower]]
            for pk in PrimitiveKind:
                if pk.value == lower:
                    if pk == PrimitiveKind.DECIMAL:
                        raise ValueError(
                            "decimal is not supported by the JavaScript runtime. "
                            "Use a different type or wait for runtime support."
                        )
                    expr = self.PRIMITIVE_RUNTIME_MAP.get(pk)
                    if expr is None:
                        raise ValueError(
                            f"Primitive type '{pk.value}' has no JavaScript "
                            f"runtime mapping."
                        )
                    return expr

            # Named type — could be a Message, Enum, or Union
            resolved = self._resolve_named_type(field_type.name, parent_stack)
            if isinstance(resolved, Enum):
                if self.should_register_by_id(resolved):
                    name_info = str(resolved.type_id)
                else:
                    ns = self._get_type_package(resolved)
                    qname = self._qualified_type_names.get(id(resolved), resolved.name)
                    name_info = f'"{ns}.{qname}"'
                props = ", ".join(
                    f"{self.strip_enum_prefix(resolved.name, v.name)}: {v.value}"
                    for v in resolved.values
                )
                return f"Type.enum({name_info}, {{ {props} }})"
            if isinstance(resolved, Union):
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
                cases_arg = f", {{ {', '.join(case_parts)} }}" if case_parts else ""
                if self.should_register_by_id(resolved):
                    name_info = str(resolved.type_id)
                else:
                    ns = self._get_type_package(resolved)
                    qname = self._qualified_type_names.get(id(resolved), resolved.name)
                    name_info = f'{{ namespace: "{ns}", typeName: "{qname}" }}'
                return f"Type.union({name_info}{cases_arg})"
            if isinstance(resolved, Message):
                evolving = self.get_effective_evolving(resolved)
                if self.should_register_by_id(resolved):
                    if evolving:
                        return f"Type.struct({resolved.type_id})"
                    else:
                        return f"Type.struct({{ typeId: {resolved.type_id}, evolving: false }})"
                ns = self._get_type_package(resolved)
                qname = self._qualified_type_names.get(id(resolved), resolved.name)
                if evolving:
                    return f'Type.struct({{ namespace: "{ns}", typeName: "{qname}" }})'
                else:
                    return f'Type.struct({{ namespace: "{ns}", typeName: "{qname}", evolving: false }})'
            # Unresolved — fall back to any
            return "Type.any()"
        elif isinstance(field_type, ListType):
            if isinstance(field_type.element_type, PrimitiveType):
                array_expr = self.PRIMITIVE_ARRAY_RUNTIME_MAP.get(
                    field_type.element_type.kind
                )
                if array_expr:
                    return array_expr
            elif isinstance(field_type.element_type, NamedType):
                lower = field_type.element_type.name.lower()
                shorthand_map = {
                    "float": PrimitiveKind.FLOAT32,
                    "double": PrimitiveKind.FLOAT64,
                }
                found_kind = shorthand_map.get(lower)
                if not found_kind:
                    for pk in PrimitiveKind:
                        if pk.value == lower:
                            found_kind = pk
                            break
                if found_kind:
                    array_expr = self.PRIMITIVE_ARRAY_RUNTIME_MAP.get(found_kind)
                    if array_expr:
                        return array_expr

            inner = self._field_type_expr(field_type.element_type, parent_stack)
            return f"Type.array({inner})"
        elif isinstance(field_type, MapType):
            key = self._field_type_expr(field_type.key_type, parent_stack)
            value = self._field_type_expr(field_type.value_type, parent_stack)
            return f"Type.map({key}, {value})"
        return "Type.any()"

    def _register_type_line(
        self,
        type_def: TypingUnion[Message, Enum, Union],
        target_var: str = "fory",
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Return a single ``fory.registerSerializer(Type.struct(...))`` statement."""
        if isinstance(type_def, Union):
            return ""
        if not isinstance(type_def, Message):
            return ""

        field_parent_stack = (parent_stack or []) + [type_def]
        props_parts: List[str] = []
        used_field_names: Set[str] = set()
        for field in type_def.fields:
            member = self._field_member_name(field, type_def, used_field_names)
            expr = self._field_type_expr(field.field_type, field_parent_stack)
            if field.tag_id is not None:
                expr += f".setId({field.tag_id})"
            if field.optional:
                expr += ".setNullable(true)"
            if field.ref:
                expr += ".setTrackingRef(true)"
            props_parts.append(f"{member}: {expr}")

        props_str = ", ".join(props_parts)
        props_arg = f", {{ {props_str} }}" if props_parts else ""

        evolving = self.get_effective_evolving(type_def)
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

        return f"{target_var}.registerSerializer(Type.struct({name_info}{props_arg}));"

    def _register_union_line(
        self,
        union_def,
        target_var: str = "fory",
    ) -> str:
        """Return a ``fory.registerSerializer(Type.union(...))`` statement."""
        case_parts: List[str] = []
        for field in union_def.fields:
            case_num = field.tag_id if field.tag_id is not None else field.number
            if case_num is not None:
                case_type_expr = self._field_type_expr(field.field_type, [])
                case_parts.append(f"{case_num}: {case_type_expr}")
        cases_str = ", ".join(case_parts)
        cases_arg = f", {{ {cases_str} }}" if case_parts else ""

        if self.should_register_by_id(union_def):
            name_info = str(union_def.type_id)
        else:
            ns = self.schema.package or "default"
            qname = self._qualified_type_names.get(id(union_def), union_def.name)
            name_info = f'{{ namespace: "{ns}", typeName: "{qname}" }}'

        return f"{target_var}.registerSerializer(Type.union({name_info}{cases_arg}));"

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
            elif isinstance(ft, MapType):
                visit(ft.key_type)
                visit(ft.value_type)

        for field in message.fields:
            visit(field.field_type)
        return deps

    def generate_registration(self) -> List[str]:
        """Generate a registration function that registers all local and
        imported types with a Fory instance.

        Types are emitted in dependency order (leaf types first) via a
        simple DFS so that the Fory JS runtime does not prematurely
        register bare ``Type.struct(id)`` references with empty fields."""
        lines: List[str] = []
        fn_name = self.get_registration_function_name()
        imported_regs = self._collect_imported_registrations()

        lines.append("// Registration helper")
        lines.append(f"export function {fn_name}(fory: any, Type: any): void {{")

        # Delegate to imported registration functions first
        for _module_path, reg_fn in imported_regs:
            if reg_fn == fn_name:
                continue
            lines.append(f"  {reg_fn}(fory, Type);")

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
            reg_line = self._register_type_line(msg, "fory", parents)
            if reg_line:
                lines.append(f"  {reg_line}")
            for nested_msg in msg.nested_messages:
                emit_message(nested_msg)

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            emit_message(message)

        # Ensure message-typed union variants are registered even when they are
        # not reachable through any struct field in the DFS above (e.g. a union
        # whose variants are messages defined in the same file but not used as
        # direct struct fields).
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            for ufield in union.fields:
                if isinstance(ufield.field_type, NamedType):
                    resolved = self._resolve_named_type(ufield.field_type.name)
                    if isinstance(resolved, Message) and not self.is_imported_type(
                        resolved
                    ):
                        emit_message(resolved)
            reg_line = self._register_union_line(union, "fory")
            if reg_line:
                lines.append(f"  {reg_line}")

        lines.append("}")

        return lines
