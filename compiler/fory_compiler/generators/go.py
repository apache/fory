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

"""Go code generator."""

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple, Union as TypingUnion

from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.ir.ast import (
    Message,
    Enum,
    Union,
    Field,
    FieldType,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    Schema,
)
from fory_compiler.ir.types import PrimitiveKind


class GoGenerator(BaseGenerator):
    """Generates Go structs with fory tags."""

    language_name = "go"
    file_extension = ".go"
    indent_str = "\t"  # Go uses tabs

    def get_go_package_info(self) -> Tuple[Optional[str], str]:
        """Parse go_package option and return (import_path, package_name).

        Supports format: "github.com/mycorp/apis/gen/payment/v1;paymentv1"
        - Part before ';' is the import path
        - Part after ';' is the package name
        - If no ';', the last element of the import path is used as package name
        - If no go_package option, falls back to FDL package

        Returns:
            Tuple of (import_path, package_name). import_path may be None.
        """
        go_package = self.schema.get_option("go_package")
        if go_package:
            if ";" in go_package:
                import_path, package_name = go_package.split(";", 1)
                return (import_path, package_name)
            else:
                # Use last element of path as package name
                parts = go_package.rstrip("/").split("/")
                return (go_package, parts[-1])

        # Fall back to FDL package
        if self.schema.package:
            parts = self.schema.package.split(".")
            return (None, parts[-1])

        return (None, "generated")

    def get_nested_type_style(self) -> str:
        """Get the nested type naming style for Go."""
        style = self.options.go_nested_type_style or self.schema.get_option(
            "go_nested_type_style"
        )
        if style is None:
            return "underscore"
        style = str(style).strip().lower()
        if style not in ("camelcase", "underscore"):
            raise ValueError(
                f"Invalid go_nested_type_style: {style}. Use 'camelcase' or 'underscore'."
            )
        return style

    def get_type_name(self, name: str, parent_stack: Optional[List[Message]]) -> str:
        """Build a Go type name for a nested or top-level type."""
        parts = [parent.name for parent in parent_stack or []] + [name]
        if len(parts) == 1:
            return parts[0]
        if self.get_nested_type_style() == "underscore":
            return "_".join(parts)
        return "".join(parts)

    def get_registration_type_name(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> str:
        """Build a dot-qualified name for registration."""
        parts = [parent.name for parent in parent_stack or []] + [name]
        if len(parts) == 1:
            return parts[0]
        return ".".join(parts)

    def validate_type_names(self) -> None:
        """Detect Go type name collisions for nested types."""
        name_map: Dict[str, List[str]] = {}

        def add_name(go_name: str, qualified: str) -> None:
            name_map.setdefault(go_name, []).append(qualified)

        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            add_name(enum.name, enum.name)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            add_name(union.name, union.name)

        def visit_message(message: Message, parents: List[Message]) -> None:
            qualified = ".".join([p.name for p in parents] + [message.name])
            add_name(self.get_type_name(message.name, parents), qualified)
            for nested_enum in message.nested_enums:
                enum_qualified = f"{qualified}.{nested_enum.name}"
                add_name(
                    self.get_type_name(nested_enum.name, parents + [message]),
                    enum_qualified,
                )
            for nested_union in message.nested_unions:
                union_qualified = f"{qualified}.{nested_union.name}"
                add_name(
                    self.get_type_name(nested_union.name, parents + [message]),
                    union_qualified,
                )
            for nested_msg in message.nested_messages:
                visit_message(nested_msg, parents + [message])

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            visit_message(message, [])

        duplicates = {name: types for name, types in name_map.items() if len(types) > 1}
        if duplicates:
            details = ", ".join(
                f"{name}: {', '.join(types)}"
                for name, types in sorted(duplicates.items())
            )
            raise ValueError(f"Go type name collision detected: {details}")

    def schema_has_unions(self) -> bool:
        """Return True if schema contains any unions (including nested)."""
        for union in self.schema.unions:
            if not self.is_imported_type(union):
                return True
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            if self.message_has_unions(message):
                return True
        return False

    def message_has_unions(self, message: Message) -> bool:
        if message.nested_unions:
            return True
        for nested_msg in message.nested_messages:
            if self.message_has_unions(nested_msg):
                return True
        return False

    # Mapping from FDL primitive types to Go types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "int8",
        PrimitiveKind.INT16: "int16",
        PrimitiveKind.INT32: "int32",
        PrimitiveKind.VARINT32: "int32",
        PrimitiveKind.INT64: "int64",
        PrimitiveKind.VARINT64: "int64",
        PrimitiveKind.TAGGED_INT64: "int64",
        PrimitiveKind.UINT8: "uint8",
        PrimitiveKind.UINT16: "uint16",
        PrimitiveKind.UINT32: "uint32",
        PrimitiveKind.VAR_UINT32: "uint32",
        PrimitiveKind.UINT64: "uint64",
        PrimitiveKind.VAR_UINT64: "uint64",
        PrimitiveKind.TAGGED_UINT64: "uint64",
        PrimitiveKind.FLOAT16: "float16.Float16",
        PrimitiveKind.BFLOAT16: "bfloat16.BFloat16",
        PrimitiveKind.FLOAT32: "float32",
        PrimitiveKind.FLOAT64: "float64",
        PrimitiveKind.STRING: "string",
        PrimitiveKind.BYTES: "[]byte",
        PrimitiveKind.DATE: "fory.Date",
        PrimitiveKind.TIMESTAMP: "time.Time",
        PrimitiveKind.ANY: "any",
    }

    def generate(self) -> List[GeneratedFile]:
        """Generate Go files for the schema."""
        files = []

        self.validate_type_names()

        # Generate a single Go file with all types
        files.append(self.generate_file())

        return files

    def get_package_name(self) -> str:
        """Get the Go package name."""
        _, package_name = self.get_go_package_info()
        return package_name

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
        return imported, local

    def _normalize_import_path(self, path_str: str) -> str:
        if not path_str:
            return path_str
        try:
            return str(Path(path_str).resolve())
        except Exception:
            return path_str

    def _collect_imported_type_infos(self) -> List[Tuple[str, str, str]]:
        file_info: Dict[str, Tuple[str, str, str]] = {}
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
            info = self._import_info_for_type(type_def)
            if info is None:
                continue
            file_info[normalized] = info

        ordered: List[Tuple[str, str, str]] = []
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

        return ordered

    def _load_schema(self, file_path: str) -> Optional[Schema]:
        if not file_path:
            return None
        if not hasattr(self, "_schema_cache"):
            self._schema_cache = {}
        cache: Dict[Path, Schema] = self._schema_cache
        path = Path(file_path).resolve()
        if path in cache:
            return cache[path]
        try:
            schema = parse_idl_file(path)
        except Exception:
            return None
        cache[path] = schema
        return schema

    def _get_go_package_info_for_schema(
        self, schema: Schema
    ) -> Tuple[Optional[str], str]:
        go_package = schema.get_option("go_package")
        if go_package:
            if ";" in go_package:
                import_path, package_name = go_package.split(";", 1)
                return (import_path, package_name)
            parts = go_package.rstrip("/").split("/")
            return (go_package, parts[-1])
        if schema.package:
            parts = schema.package.split(".")
            return (None, parts[-1])
        return (None, "generated")

    def _get_nested_type_style_for_schema(self, schema: Schema) -> str:
        style = schema.get_option("go_nested_type_style")
        if style is None:
            return "underscore"
        style = str(style).strip().lower()
        if style not in ("camelcase", "underscore"):
            return "underscore"
        return style

    def _format_imported_type_name(self, type_name: str, schema: Schema) -> str:
        if "." not in type_name:
            return type_name
        parts = type_name.split(".")
        style = self._get_nested_type_style_for_schema(schema)
        if style == "underscore":
            return "_".join(parts)
        return "".join(parts)

    def _import_info_for_file(self, file_path: str) -> Optional[Tuple[str, str, str]]:
        if not file_path:
            return None
        if not hasattr(self, "_import_info_cache"):
            self._import_info_cache = {}
            self._import_aliases = {}
        cache: Dict[str, Tuple[str, str, str]] = self._import_info_cache
        normalized = self._normalize_import_path(file_path)
        if normalized in cache:
            return cache[normalized]
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        import_path, package_name = self._get_go_package_info_for_schema(schema)
        if not import_path:
            import_path = package_name
        alias_base = package_name
        if not alias_base:
            alias_base = "imported"
        alias = alias_base
        if not hasattr(self, "_used_import_aliases"):
            self._used_import_aliases = set()
        used: Set[str] = self._used_import_aliases
        if alias == self.get_package_name() or alias in used:
            suffix = 1
            while f"{alias_base}{suffix}" in used:
                suffix += 1
            alias = f"{alias_base}{suffix}"
        used.add(alias)
        cache[normalized] = (alias, import_path, package_name)
        return cache[normalized]

    def _import_info_for_type(self, type_def: object) -> Optional[Tuple[str, str, str]]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        if not file_path:
            return None
        return self._import_info_for_file(file_path)

    def get_file_name(self) -> str:
        """Get the Go file name."""
        if self.package:
            return self.package.replace(".", "_")
        return "generated"

    def generate_file(self) -> GeneratedFile:
        """Generate a Go file with all types."""
        lines = []
        imports: Set[str] = set()

        # Collect imports (including from nested types)
        imports.add('fory "github.com/apache/fory/go/fory"')
        imports.add('threadsafe "github.com/apache/fory/go/fory/threadsafe"')
        imports.add('"sync"')

        for message in self.schema.messages:
            self.collect_message_imports(message, imports)
        for union in self.schema.unions:
            self.collect_union_imports(union, imports)

        if self.schema_has_unions():
            imports.add('"fmt"')
            imports.add('"reflect"')

        for alias, import_path, package_name in self._collect_imported_type_infos():
            if not import_path:
                continue
            if alias == package_name:
                imports.add(f'"{import_path}"')
            else:
                imports.add(f'{alias} "{import_path}"')

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        # Package declaration
        lines.append(f"package {self.get_package_name()}")
        lines.append("")

        # Imports
        if imports:
            lines.append("import (")
            for imp in sorted(imports):
                lines.append(f"\t{imp}")
            lines.append(")")
            lines.append("")

        # Generate enums (top-level)
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            lines.extend(self.generate_enum(enum))
            lines.append("")

        # Generate unions (top-level)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            lines.extend(self.generate_union(union))
            lines.append("")

        # Generate messages (including nested as flat types with qualified names)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.extend(self.generate_message_with_nested(message))

        # Generate registration function
        lines.extend(self.generate_registration())
        lines.append("")
        lines.extend(self.generate_fory_helpers())
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_file_name()}.go",
            content="\n".join(lines),
        )

    def collect_message_imports(self, message: Message, imports: Set[str]):
        """Collect imports for a message and its nested types recursively."""
        for field in message.fields:
            self.collect_imports(field.field_type, imports)
            if self.field_uses_option(field):
                imports.add('optional "github.com/apache/fory/go/fory/optional"')
        for nested_msg in message.nested_messages:
            self.collect_message_imports(nested_msg, imports)
        for nested_union in message.nested_unions:
            self.collect_union_imports(nested_union, imports)

    def collect_union_imports(self, union: Union, imports: Set[str]):
        """Collect imports for a union and its cases."""
        for field in union.fields:
            self.collect_imports(field.field_type, imports)

    def generate_enum(
        self,
        enum: Enum,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Go enum (using type alias and constants)."""
        lines = []

        type_name = self.get_type_name(enum.name, parent_stack)

        # Type definition
        lines.append(f"type {type_name} int32")
        lines.append("")

        # Constants (strip prefix first, then add enum name back for Go's unscoped style)
        lines.append("const (")
        for value in enum.values:
            # Strip the proto-style prefix (e.g., DEVICE_TIER_UNKNOWN -> UNKNOWN)
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            # Add enum name prefix for Go (e.g., DeviceTierUnknown)
            const_name = f"{type_name}{self.to_pascal_case(stripped_name)}"
            lines.append(f"\t{const_name} {type_name} = {value.value}")
        lines.append(")")

        return lines

    def generate_union(
        self,
        union: Union,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Go tagged union."""
        lines: List[str] = []

        type_name = self.get_type_name(union.name, parent_stack)
        case_type = f"{type_name}Case"
        has_zero_case = any(field.number == 0 for field in union.fields)
        invalid_value = (
            f"{case_type}(^uint32(0))" if has_zero_case else f"{case_type}(0)"
        )

        lines.append(f"type {case_type} uint32")
        lines.append("")
        lines.append("const (")
        lines.append(f"\t{case_type}Invalid {case_type} = {invalid_value}")
        for field in union.fields:
            const_name = f"{case_type}{self.to_pascal_case(field.name)}"
            lines.append(f"\t{const_name} {case_type} = {field.number}")
        lines.append(")")
        lines.append("")

        comment = self.format_type_id_comment(union, "//")
        if comment:
            lines.append(comment)
        lines.append(f"type {type_name} struct {{")
        lines.append(f"\tcase_ {case_type}")
        lines.append("\tvalue any")
        lines.append("}")
        lines.append("")

        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            ctor_name = f"{case_name}{type_name}"
            case_type_name = self.get_union_case_type(field, parent_stack)
            lines.append(f"func {ctor_name}(v {case_type_name}) {type_name} {{")
            if case_type_name.startswith("*"):
                lines.append("\tif v == nil {")
                lines.append(f'\t\tpanic("{ctor_name}: nil pointer")')
                lines.append("\t}")
            lines.append(
                f"\treturn {type_name}{{case_: {case_type}{case_name}, value: v}}"
            )
            lines.append("}")
            lines.append("")

        lines.append(f"func (u {type_name}) Case() {case_type} {{ return u.case_ }}")
        lines.append(
            f"func (u {type_name}) IsSet() bool {{ return u.case_ != {case_type}Invalid && u.value != nil }}"
        )
        lines.append("")

        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            case_type_name = self.get_union_case_type(field, parent_stack)
            method_name = f"As{case_name}"
            zero_decl = f"var zero {case_type_name}"
            lines.append(
                f"func (u {type_name}) {method_name}() ({case_type_name}, bool) {{"
            )
            lines.append(f"\tif u.case_ != {case_type}{case_name} {{")
            lines.append(f"\t\t{zero_decl}")
            lines.append("\t\treturn zero, false")
            lines.append("\t}")
            lines.append(f"\tv, ok := u.value.({case_type_name})")
            lines.append("\tif !ok {")
            lines.append(f"\t\t{zero_decl}")
            lines.append("\t\treturn zero, false")
            lines.append("\t}")
            lines.append("\treturn v, true")
            lines.append("}")
            lines.append("")

        lines.append(f"func (u {type_name}) Visit(visitor {type_name}Visitor) error {{")
        lines.append(f"\tif u.case_ == {case_type}Invalid || u.value == nil {{")
        lines.append("\t\tif visitor.Invalid != nil {")
        lines.append("\t\t\treturn visitor.Invalid()")
        lines.append("\t\t}")
        lines.append("\t\treturn nil")
        lines.append("\t}")
        lines.append("\tswitch u.case_ {")
        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            case_type_name = self.get_union_case_type(field, parent_stack)
            lines.append(f"\tcase {case_type}{case_name}:")
            lines.append(f"\t\tv, ok := u.value.({case_type_name})")
            lines.append("\t\tif !ok {")
            lines.append(
                f'\t\t\treturn fmt.Errorf("corrupted {type_name}: case={case_name} but invalid value")'
            )
            lines.append("\t\t}")
            if case_type_name.startswith("*"):
                lines.append("\t\tif v == nil {")
                lines.append(
                    f'\t\t\treturn fmt.Errorf("corrupted {type_name}: case={case_name} but nil value")'
                )
                lines.append("\t\t}")
            lines.append(f"\t\tif visitor.{case_name} != nil {{")
            lines.append(f"\t\t\treturn visitor.{case_name}(v)")
            lines.append("\t\t}")
            lines.append("\t\treturn nil")
        lines.append("\tdefault:")
        lines.append(f'\t\treturn fmt.Errorf("unknown {type_name} case: %d", u.case_)')
        lines.append("\t}")
        lines.append("}")
        lines.append("")

        lines.append(f"type {type_name}Visitor struct {{")
        lines.append("\tInvalid func() error")
        for field in union.fields:
            case_name = self.to_pascal_case(field.name)
            case_type_name = self.get_union_case_type(field, parent_stack)
            lines.append(f"\t{case_name} func({case_type_name}) error")
        lines.append("}")

        lines.append("")

        lines.append(f"func (u {type_name}) ForyUnionMarker() {{}}")
        lines.append("")
        lines.append(
            f"func (u {type_name}) ForyUnionGet() (uint32, any) {{ return uint32(u.case_), u.value }}"
        )
        lines.append(f"func (u *{type_name}) ForyUnionSet(caseId uint32, value any) {{")
        lines.append(f"\tu.case_ = {case_type}(caseId)")
        lines.append("\tu.value = value")
        lines.append("}")
        lines.append("")
        lines.append(f"func (u *{type_name}) ToBytes() ([]byte, error) {{")
        lines.append("\treturn getFory().Serialize(u)")
        lines.append("}")
        lines.append("")
        lines.append(f"func (u *{type_name}) FromBytes(data []byte) error {{")
        lines.append("\treturn getFory().Deserialize(data, u)")
        lines.append("}")

        return lines

    def get_union_case_type_id_expr(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        """Return the Go expression for a union case value type id."""
        if isinstance(field.field_type, PrimitiveType):
            kind = field.field_type.kind
            primitive_type_ids = {
                PrimitiveKind.BOOL: "fory.BOOL",
                PrimitiveKind.INT8: "fory.INT8",
                PrimitiveKind.INT16: "fory.INT16",
                PrimitiveKind.INT32: "fory.INT32",
                PrimitiveKind.VARINT32: "fory.VARINT32",
                PrimitiveKind.INT64: "fory.INT64",
                PrimitiveKind.VARINT64: "fory.VARINT64",
                PrimitiveKind.TAGGED_INT64: "fory.TAGGED_INT64",
                PrimitiveKind.UINT8: "fory.UINT8",
                PrimitiveKind.UINT16: "fory.UINT16",
                PrimitiveKind.UINT32: "fory.UINT32",
                PrimitiveKind.VAR_UINT32: "fory.VAR_UINT32",
                PrimitiveKind.UINT64: "fory.UINT64",
                PrimitiveKind.VAR_UINT64: "fory.VAR_UINT64",
                PrimitiveKind.TAGGED_UINT64: "fory.TAGGED_UINT64",
                PrimitiveKind.FLOAT16: "fory.FLOAT16",
                PrimitiveKind.FLOAT32: "fory.FLOAT32",
                PrimitiveKind.FLOAT64: "fory.FLOAT64",
                PrimitiveKind.STRING: "fory.STRING",
                PrimitiveKind.BYTES: "fory.BINARY",
                PrimitiveKind.DATE: "fory.DATE",
                PrimitiveKind.TIMESTAMP: "fory.TIMESTAMP",
                PrimitiveKind.ANY: "fory.UNKNOWN",
            }
            return primitive_type_ids.get(kind, "fory.UNKNOWN")
        if isinstance(field.field_type, ListType):
            return "fory.LIST"
        if isinstance(field.field_type, MapType):
            return "fory.MAP"
        if isinstance(field.field_type, NamedType):
            type_def = self.resolve_named_type(field.field_type.name, parent_stack)
            if isinstance(type_def, Enum):
                if type_def.type_id is None:
                    return "fory.NAMED_ENUM"
                return "fory.ENUM"
            if isinstance(type_def, Union):
                if type_def.type_id is None:
                    return "fory.NAMED_UNION"
                return "fory.UNION"
            if isinstance(type_def, Message):
                evolving = self.get_effective_evolving(type_def)
                if type_def.type_id is None:
                    if evolving:
                        return "fory.NAMED_COMPATIBLE_STRUCT"
                    return "fory.NAMED_STRUCT"
                if evolving:
                    return "fory.COMPATIBLE_STRUCT"
                return "fory.STRUCT"
        return "fory.UNKNOWN"

    def get_union_case_reflect_type_expr(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        """Return the Go expression for reflect.Type of a union case."""
        case_type = self.get_union_case_type(field, parent_stack)
        if case_type.startswith("*"):
            return f"reflect.TypeOf((*{case_type[1:]})(nil))"
        return f"reflect.TypeOf((*{case_type})(nil)).Elem()"

    def resolve_named_type(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        """Resolve a named type to a schema definition."""
        parts = name.split(".")
        if len(parts) > 1:
            current = self.find_top_level_type(parts[0])
            for part in parts[1:]:
                if isinstance(current, Message):
                    current = current.get_nested_type(part)
                else:
                    return None
            return current
        if parent_stack:
            for msg in reversed(parent_stack):
                nested = msg.get_nested_type(name)
                if nested is not None:
                    return nested
        return self.find_top_level_type(name)

    def find_top_level_type(
        self, name: str
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        """Find a top-level type definition by name."""
        for msg in self.schema.messages:
            if msg.name == name:
                return msg
        for enum in self.schema.enums:
            if enum.name == name:
                return enum
        for union in self.schema.unions:
            if union.name == name:
                return union
        return None

    def generate_message(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Go struct."""
        lines = []

        type_name = self.get_type_name(message.name, parent_stack)
        lineage = (parent_stack or []) + [message]

        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        lines.append(f"type {type_name} struct {{")

        # Fields
        for field in message.fields:
            field_lines = self.generate_field(field, lineage)
            for line in field_lines:
                lines.append(f"\t{line}")

        lines.append("}")
        lines.append("")
        if not self.get_effective_evolving(message):
            lines.append(f"func (*{type_name}) ForyEvolving() bool {{")
            lines.append("\treturn false")
            lines.append("}")
            lines.append("")
        lines.append(f"func (m *{type_name}) ToBytes() ([]byte, error) {{")
        lines.append("\treturn getFory().Serialize(m)")
        lines.append("}")
        lines.append("")
        lines.append(f"func (m *{type_name}) FromBytes(data []byte) error {{")
        lines.append("\treturn getFory().Deserialize(data, m)")
        lines.append("}")

        return lines

    def generate_message_with_nested(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Go struct and all its nested types (flattened)."""
        lines = []

        lineage = (parent_stack or []) + [message]

        # First, generate all nested enums
        for nested_enum in message.nested_enums:
            lines.extend(self.generate_enum(nested_enum, lineage))
            lines.append("")

        for nested_union in message.nested_unions:
            lines.extend(self.generate_union(nested_union, lineage))
            lines.append("")

        # Then, generate all nested messages (recursively)
        for nested_msg in message.nested_messages:
            lines.extend(self.generate_message_with_nested(nested_msg, lineage))

        # Finally, generate this message
        lines.extend(self.generate_message(message, parent_stack))
        lines.append("")

        return lines

    def generate_field(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a struct field."""
        lines = []

        go_type = self.generate_type(
            field.field_type,
            field.optional,
            field.ref,
            field.element_optional,
            field.element_ref,
            parent_stack,
        )
        field_name = self.to_pascal_case(
            field.name
        )  # Go uses PascalCase for exported fields

        # Build fory tag
        tags = []
        is_list = isinstance(field.field_type, ListType)
        is_map = isinstance(field.field_type, MapType)
        is_collection = is_list or is_map
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        nullable_tag: Optional[bool] = None
        ref_tag: Optional[bool] = None
        if field.tag_id is not None:
            tags.append(f"id={field.tag_id}")
        if field.optional or is_any:
            nullable_tag = True
        elif is_collection and (
            field.ref or (is_list and (field.element_optional or field.element_ref))
        ):
            nullable_tag = False

        if field.ref:
            ref_tag = True

        if nullable_tag is True:
            tags.append("nullable")
        elif nullable_tag is False:
            tags.append("nullable=false")

        if ref_tag is True:
            tags.append("ref")
        elif ref_tag is False:
            tags.append("ref=false")

        encoding_tag = self.get_encoding_tag(field.field_type)
        if encoding_tag:
            tags.append(encoding_tag)

        array_tag = self.get_array_type_tag(field)
        if array_tag:
            tags.append(array_tag)

        nested_ref_tag = self.get_nested_ref_tag(field)
        if nested_ref_tag:
            tags.append(nested_ref_tag)

        if tags:
            tag_str = ",".join(tags)
            lines.append(f'{field_name} {go_type} `fory:"{tag_str}"`')
        else:
            lines.append(f"{field_name} {go_type}")

        return lines

    def get_encoding_tag(self, field_type: FieldType) -> Optional[str]:
        """Return encoding tag for integer primitives."""
        if not isinstance(field_type, PrimitiveType):
            return None
        kind = field_type.kind
        if kind in (PrimitiveKind.INT32, PrimitiveKind.UINT32):
            return "compress=false"
        if kind in (PrimitiveKind.VARINT32, PrimitiveKind.VAR_UINT32):
            return "compress=true"
        if kind in (PrimitiveKind.INT64, PrimitiveKind.UINT64):
            return "encoding=fixed"
        if kind in (PrimitiveKind.VARINT64, PrimitiveKind.VAR_UINT64):
            return "encoding=varint"
        if kind in (PrimitiveKind.TAGGED_INT64, PrimitiveKind.TAGGED_UINT64):
            return "encoding=tagged"
        return None

    def get_array_type_tag(self, field: Field) -> Optional[str]:
        """Return type override tag for uint8/int8 arrays."""
        if not isinstance(field.field_type, ListType):
            return None
        if field.element_optional or field.element_ref:
            return None
        element_type = field.field_type.element_type
        if not isinstance(element_type, PrimitiveType):
            return None
        if element_type.kind == PrimitiveKind.INT8:
            return "type=int8_array"
        if element_type.kind == PrimitiveKind.UINT8:
            return "type=uint8_array"
        return None

    def get_nested_ref_tag(self, field: Field) -> Optional[str]:
        if isinstance(field.field_type, ListType):
            if not self.is_ref_target_type(field.field_type.element_type):
                return None
            entry = "[true]" if field.element_ref else "[]"
            return f"nested_ref=[{entry}]"
        if isinstance(field.field_type, MapType):
            if not self.is_ref_target_type(field.field_type.value_type):
                return None
            value_entry = "[true]" if field.field_type.value_ref else "[]"
            return f"nested_ref=[[],{value_entry}]"
        return None

    def is_ref_target_type(self, field_type: FieldType) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.schema.get_type(field_type.name)
        return isinstance(resolved, (Message, Union))

    def field_uses_option(self, field: Field) -> bool:
        """Return True if field should use optional.Optional in generated Go code."""
        if not field.optional or field.ref:
            return False
        if isinstance(field.field_type, PrimitiveType):
            if field.field_type.kind == PrimitiveKind.ANY:
                return False
            base_type = self.PRIMITIVE_MAP[field.field_type.kind]
            return base_type not in ("[]byte", "time.Time", "fory.Date")
        if isinstance(field.field_type, NamedType):
            named_type = self.schema.get_type(field.field_type.name)
            return isinstance(named_type, Enum)
        return False

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        ref: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
        use_option: bool = True,
    ) -> str:
        """Generate Go type string."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return "any"
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable and base_type not in ("[]byte",):
                if (
                    use_option
                    and not ref
                    and base_type not in ("time.Time", "fory.Date")
                ):
                    return f"optional.Optional[{base_type}]"
                return f"*{base_type}"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                info = self._import_info_for_type(named_type)
                schema = self._load_schema(
                    getattr(getattr(named_type, "location", None), "file", None)
                )
                if schema is not None:
                    type_name = self._format_imported_type_name(field_type.name, schema)
                if info is not None:
                    alias, _, _ = info
                    type_name = f"{alias}.{type_name}"
            if nullable:
                if use_option and not ref:
                    named_type = self.schema.get_type(field_type.name)
                    if isinstance(named_type, Enum):
                        return f"optional.Optional[{type_name}]"
                return f"*{type_name}"
            if ref:
                return f"*{type_name}"
            return type_name

        elif isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                element_optional,
                element_ref,
                False,
                False,
                parent_stack,
                use_option=False,
            )
            return f"[]{element_type}"

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type,
                False,
                False,
                False,
                False,
                parent_stack,
            )
            value_type = self.generate_type(
                field_type.value_type,
                False,
                field_type.value_ref,
                False,
                False,
                parent_stack,
            )
            return f"map[{key_type}]{value_type}"

        return "interface{}"

    def get_union_case_type(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Return the Go type for a union case."""
        if isinstance(field.field_type, NamedType):
            type_name = self.resolve_nested_type_name(
                field.field_type.name, parent_stack
            )
            named_type = self.schema.get_type(field.field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                info = self._import_info_for_type(named_type)
                schema = self._load_schema(
                    getattr(getattr(named_type, "location", None), "file", None)
                )
                if schema is not None:
                    type_name = self._format_imported_type_name(
                        field.field_type.name, schema
                    )
                if info is not None:
                    alias, _, _ = info
                    type_name = f"{alias}.{type_name}"
            return f"*{type_name}"
        return self.generate_type(
            field.field_type,
            nullable=False,
            ref=False,
            element_optional=False,
            element_ref=False,
            parent_stack=parent_stack,
        )

    def resolve_nested_type_name(
        self,
        type_name: str,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Resolve nested type names to flattened Go identifiers."""
        if "." in type_name:
            parts = type_name.split(".")
            if len(parts) == 1:
                return parts[0]
            if self.get_nested_type_style() == "underscore":
                return "_".join(parts)
            return "".join(parts)
        if not parent_stack:
            return type_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                return self.get_type_name(
                    type_name,
                    parent_stack[: i + 1],
                )

        return type_name

    def collect_imports(self, field_type: FieldType, imports: Set[str]):
        """Collect required imports for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind in (PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP):
                imports.add('"time"')
            elif field_type.kind == PrimitiveKind.FLOAT16:
                imports.add('float16 "github.com/apache/fory/go/fory/float16"')
            elif field_type.kind == PrimitiveKind.BFLOAT16:
                imports.add('bfloat16 "github.com/apache/fory/go/fory/bfloat16"')

        elif isinstance(field_type, ListType):
            self.collect_imports(field_type.element_type, imports)

        elif isinstance(field_type, MapType):
            self.collect_imports(field_type.key_type, imports)
            self.collect_imports(field_type.value_type, imports)

        elif isinstance(field_type, NamedType):
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                info = self._import_info_for_type(named_type)
                if info is not None:
                    alias, import_path, package_name = info
                    if import_path:
                        if alias == package_name:
                            imports.add(f'"{import_path}"')
                        else:
                            imports.add(f'{alias} "{import_path}"')

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        lines.append("func RegisterTypes(f *fory.Fory) error {")

        # Register enums (top-level)
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            self.generate_enum_registration(lines, enum, None)

        # Register unions (top-level)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            self.generate_union_registration(lines, union, None)

        # Register messages (including nested types)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self.generate_message_registration(lines, message, None)

        lines.append("\treturn nil")
        lines.append("}")

        return lines

    def generate_fory_helpers(self) -> List[str]:
        lines: List[str] = []
        lines.append("func createFory() *fory.Fory {")
        lines.append(
            "\tf := fory.New(fory.WithXlang(true), fory.WithRefTracking(true), fory.WithCompatible(true))"
        )
        for alias, _, _ in self._collect_imported_type_infos():
            lines.append(f"\tif err := {alias}.RegisterTypes(f); err != nil {{")
            lines.append("\t\tpanic(err)")
            lines.append("\t}")
        lines.append("\tif err := RegisterTypes(f); err != nil {")
        lines.append("\t\tpanic(err)")
        lines.append("\t}")
        lines.append("\treturn f")
        lines.append("}")
        lines.append("")
        lines.append("var (")
        lines.append("\tforyOnce sync.Once")
        lines.append("\tforyInstance *threadsafe.Fory")
        lines.append(")")
        lines.append("")
        lines.append("func getFory() *threadsafe.Fory {")
        lines.append("\tforyOnce.Do(func() {")
        lines.append("\t\tforyInstance = threadsafe.NewWithFactory(createFory)")
        lines.append("\t})")
        lines.append("\treturn foryInstance")
        lines.append("}")
        return lines

    def generate_enum_registration(
        self,
        lines: List[str],
        enum: Enum,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for an enum."""
        code_name = self.get_type_name(enum.name, parent_stack)
        type_name = self.get_registration_type_name(enum.name, parent_stack)

        if self.should_register_by_id(enum):
            lines.append(
                f"\tif err := f.RegisterEnum({code_name}(0), {enum.type_id}); err != nil {{"
            )
            lines.append("\t\treturn err")
            lines.append("\t}")
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(
                f'\tif err := f.RegisterNamedEnum({code_name}(0), "{ns}.{type_name}"); err != nil {{'
            )
            lines.append("\t\treturn err")
            lines.append("\t}")

    def generate_message_registration(
        self,
        lines: List[str],
        message: Message,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a message and its nested types."""
        code_name = self.get_type_name(message.name, parent_stack)
        type_name = self.get_registration_type_name(message.name, parent_stack)

        # Register nested enums first
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(
                lines, nested_enum, (parent_stack or []) + [message]
            )

        for nested_union in message.nested_unions:
            self.generate_union_registration(
                lines, nested_union, (parent_stack or []) + [message]
            )

        # Register nested messages recursively
        for nested_msg in message.nested_messages:
            self.generate_message_registration(
                lines, nested_msg, (parent_stack or []) + [message]
            )

        # Register this message
        if self.should_register_by_id(message):
            lines.append(
                f"\tif err := f.RegisterStruct({code_name}{{}}, {message.type_id}); err != nil {{"
            )
            lines.append("\t\treturn err")
            lines.append("\t}")
        else:
            # Use FDL package for namespace (consistent across languages)
            ns = self.schema.package or "default"
            lines.append(
                f'\tif err := f.RegisterNamedStruct({code_name}{{}}, "{ns}.{type_name}"); err != nil {{'
            )
            lines.append("\t\treturn err")
            lines.append("\t}")

    def generate_union_registration(
        self,
        lines: List[str],
        union: Union,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a union."""
        code_name = self.get_type_name(union.name, parent_stack)
        type_name = self.get_registration_type_name(union.name, parent_stack)
        cases = []
        for field in union.fields:
            type_expr = self.get_union_case_reflect_type_expr(field, parent_stack)
            type_id_expr = self.get_union_case_type_id_expr(field, parent_stack)
            cases.append(
                f"fory.UnionCase{{ID: {field.number}, Type: {type_expr}, TypeID: {type_id_expr}}}"
            )
        serializer_expr = f"fory.NewUnionSerializer({', '.join(cases)})"

        if self.should_register_by_id(union):
            lines.append(
                f"\tif err := f.RegisterUnion({code_name}{{}}, {union.type_id}, {serializer_expr}); err != nil {{"
            )
            lines.append("\t\treturn err")
            lines.append("\t}")
        else:
            ns = self.schema.package or "default"
            lines.append(
                f'\tif err := f.RegisterNamedUnion({code_name}{{}}, "{ns}.{type_name}", {serializer_expr}); err != nil {{'
            )
            lines.append("\t\treturn err")
            lines.append("\t}")
