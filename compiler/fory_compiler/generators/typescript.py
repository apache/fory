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

"""TypeScript/JavaScript code generator."""

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


class TypeScriptGenerator(BaseGenerator):
    """Generates TypeScript type definitions and Fory registration helpers from IDL."""

    language_name = "typescript"
    file_extension = ".ts"

    # TypeScript/JavaScript reserved keywords that cannot be used as identifiers
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

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self.indent_str = "  "  # TypeScript uses 2 spaces
        self._qualified_type_names: Dict[int, str] = {}
        self._build_qualified_type_name_index()

    def _build_qualified_type_name_index(self) -> None:
        """Build an index mapping type object ids to their qualified names."""
        for enum in self.schema.enums:
            self._qualified_type_names[id(enum)] = enum.name
        for union in self.schema.unions:
            self._qualified_type_names[id(union)] = union.name

        def visit_message(message: Message, parents: List[str]) -> None:
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
            for nested_msg in message.nested_messages:
                visit_message(nested_msg, parents + [message.name])

        for message in self.schema.messages:
            visit_message(message, [])

    def safe_identifier(self, name: str) -> str:
        """Escape identifiers that collide with TypeScript reserved words."""
        if name in self.TS_KEYWORDS:
            return f"{name}_"
        return name

    def safe_type_identifier(self, name: str) -> str:
        """Escape type names that collide with TypeScript reserved words."""
        return self.safe_identifier(name)

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
                Path(location.file).resolve()
                != Path(self.schema.source_file).resolve()
            )
        except Exception:
            return location.file != self.schema.source_file

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
            parts = self.package.split(".")
            return self.to_camel_case(parts[-1])
        return "generated"

    def _module_file_name(self) -> str:
        """Determine the output file name."""
        if self.schema.source_file and not self.schema.source_file.startswith("<"):
            return f"{Path(self.schema.source_file).stem}.ts"
        if self.schema.package:
            return f"{self.schema.package.replace('.', '_')}.ts"
        return "generated.ts"

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
        if not hasattr(self, "_schema_cache"):
            self._schema_cache: Dict[Path, Schema] = {}
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
        if schema.package:
            parts = schema.package.split(".")
            return self.to_camel_case(parts[-1])
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
        """Generate TypeScript type string for a field type."""
        parent_stack = parent_stack or []
        type_str = ""

        if isinstance(field_type, PrimitiveType):
            if field_type.kind not in self.PRIMITIVE_MAP:
                raise ValueError(
                    f"Unsupported primitive type for TypeScript: {field_type.kind}"
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
                    type_str = self.safe_type_identifier(
                        self.to_pascal_case(field_type.name)
                    )
        elif isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=field_type.element_optional,
                parent_stack=parent_stack,
            )
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
            type_str = f"Record<{key_type}, {value_type}>"
        else:
            type_str = "any"

        if nullable:
            type_str += " | undefined"

        return type_str

    def _default_initializer(
        self, field: Field, parent_stack: List[Message]
    ) -> Optional[str]:
        """Return a TS default initializer expression, or None."""
        if field.optional:
            return None

        field_type = field.field_type
        if isinstance(field_type, ListType):
            return "[]"
        if isinstance(field_type, MapType):
            return "{}"
        if isinstance(field_type, PrimitiveType):
            kind = field_type.kind
            if kind == PrimitiveKind.BOOL:
                return "false"
            if kind == PrimitiveKind.STRING:
                return '""'
            if kind == PrimitiveKind.BYTES:
                return "new Uint8Array(0)"
            if kind == PrimitiveKind.ANY:
                return "undefined"
            if kind in {PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP}:
                return "new Date(0)"
            return "0"
        if isinstance(field_type, NamedType):
            resolved = self._resolve_named_type(field_type.name, parent_stack)
            if isinstance(resolved, Enum):
                return "0"
            return "undefined"
        return None

    def _collect_local_types(
        self,
    ) -> List[TypingUnion[Message, Enum, Union]]:
        """Collect all non-imported types (including nested) for registration."""
        local_types: List[TypingUnion[Message, Enum, Union]] = []

        for enum in self.schema.enums:
            if not self.is_imported_type(enum):
                local_types.append(enum)
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                local_types.append(union)

        def visit_message(message: Message) -> None:
            local_types.append(message)
            for nested_enum in message.nested_enums:
                local_types.append(nested_enum)
            for nested_union in message.nested_unions:
                local_types.append(nested_union)
            for nested_msg in message.nested_messages:
                visit_message(nested_msg)

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            visit_message(message)

        return local_types

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
                
            imported_types_by_module[mod_path].add(self.safe_type_identifier(type_def.name))
            
            # If it's a union, also import the Case enum
            if isinstance(type_def, Union):
                imported_types_by_module[mod_path].add(self.safe_type_identifier(f"{type_def.name}Case"))

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
        """Generate TypeScript files for the schema."""
        return [self.generate_file()]

    def generate_file(self) -> GeneratedFile:
        """Generate a single TypeScript module with all types."""
        lines: List[str] = []

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

        # Add imports at the top
        imports = self.generate_imports()
        if imports:
            # Insert after package comment or license
            insert_idx = 0
            for i, line in enumerate(lines):
                if line.startswith("// Package:") or line.startswith("// Licensed"):
                    insert_idx = i + 2
            
            lines.insert(insert_idx, "")
            for imp in reversed(imports):
                lines.insert(insert_idx, imp)
            lines.insert(insert_idx, "")

        return GeneratedFile(
            path=f"{self.get_module_name()}{self.file_extension}",
            content="\n".join(lines),
        )

    def generate_enum(self, enum: Enum, indent: int = 0) -> List[str]:
        """Generate a TypeScript enum."""
        lines: List[str] = []
        ind = self.indent_str * indent
        comment = self.format_type_id_comment(enum, f"{ind}//")
        if comment:
            lines.append(comment)

        enum_name = self.safe_type_identifier(enum.name)
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
    ) -> List[str]:
        """Generate a TypeScript interface for a message."""
        lines: List[str] = []
        ind = self.indent_str * indent
        parent_stack = parent_stack or []
        lineage = parent_stack + [message]
        type_name = self.safe_type_identifier(message.name)

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
        lines: List[str] = []
        ind = self.indent_str * indent
        union_name = self.safe_type_identifier(union.name)

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)

        # Generate case enum
        case_enum_name = self.safe_type_identifier(f"{union.name}Case")
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

    def _register_type_line(
        self,
        type_def: TypingUnion[Message, Enum, Union],
        target_var: str = "fory",
    ) -> str:
        """Return a single registration statement for *type_def*."""
        type_name = self.safe_type_identifier(type_def.name)
        is_union = isinstance(type_def, Union)
        method = "registerUnion" if is_union else "register"

        # In TypeScript, interfaces and types don't exist at runtime.
        # We need to pass a string name or a dummy object for registration.
        # For now, we'll pass the string name of the type.
        if self.should_register_by_id(type_def):
            return f"{target_var}.{method}('{type_name}', {type_def.type_id});"

        namespace_name = self.schema.package or "default"
        qualified_name = self._qualified_type_names.get(id(type_def), type_def.name)
        return (
            f'{target_var}.{method}("{type_name}", "{namespace_name}", "{qualified_name}");'
        )

    def generate_registration(self) -> List[str]:
        """Generate a registration function that registers all local and
        imported types with a Fory instance."""
        lines: List[str] = []
        fn_name = self.get_registration_function_name()
        imported_regs = self._collect_imported_registrations()
        local_types = self._collect_local_types()

        lines.append("// Registration helper")
        lines.append(f"export function {fn_name}(fory: any): void {{")

        # Delegate to imported registration functions first
        for _module_path, reg_fn in imported_regs:
            if reg_fn == fn_name:
                continue
            lines.append(f"  {reg_fn}(fory);")

        # Register every local type
        for type_def in local_types:
            # Skip enums for registration in TypeScript since they are just numbers
            if isinstance(type_def, Enum):
                continue
            lines.append(f"  {self._register_type_line(type_def, 'fory')}")

        lines.append("}")

        return lines
