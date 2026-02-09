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

"""Rust code generator."""

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

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


class RustGenerator(BaseGenerator):
    """Generates Rust structs with ForyObject derive macro."""

    language_name = "rust"
    file_extension = ".rs"

    # Mapping from FDL primitive types to Rust types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "i8",
        PrimitiveKind.INT16: "i16",
        PrimitiveKind.INT32: "i32",
        PrimitiveKind.VARINT32: "i32",
        PrimitiveKind.INT64: "i64",
        PrimitiveKind.VARINT64: "i64",
        PrimitiveKind.TAGGED_INT64: "i64",
        PrimitiveKind.UINT8: "u8",
        PrimitiveKind.UINT16: "u16",
        PrimitiveKind.UINT32: "u32",
        PrimitiveKind.VAR_UINT32: "u32",
        PrimitiveKind.UINT64: "u64",
        PrimitiveKind.VAR_UINT64: "u64",
        PrimitiveKind.TAGGED_UINT64: "u64",
        PrimitiveKind.FLOAT16: "f32",
        PrimitiveKind.FLOAT32: "f32",
        PrimitiveKind.FLOAT64: "f64",
        PrimitiveKind.STRING: "String",
        PrimitiveKind.BYTES: "Vec<u8>",
        PrimitiveKind.DATE: "chrono::NaiveDate",
        PrimitiveKind.TIMESTAMP: "chrono::NaiveDateTime",
        PrimitiveKind.ANY: "Box<dyn Any>",
    }

    def generate(self) -> List[GeneratedFile]:
        """Generate Rust files for the schema."""
        files = []

        # Generate a single module file with all types
        files.append(self.generate_module())

        return files

    def get_module_name(self) -> str:
        """Get the Rust module name."""
        if self.package:
            return self.package.replace(".", "_")
        return "generated"

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

    def _module_name_for_schema(self, schema: Schema) -> str:
        if schema.package:
            return schema.package.replace(".", "_")
        return "generated"

    def _module_name_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._module_name_for_schema(schema)

    def _collect_imported_modules(self) -> List[str]:
        modules: Set[str] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            module = self._module_name_for_type(type_def)
            if module:
                modules.add(module)
        ordered: List[str] = []
        used: Set[str] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = (base_dir / imp.path).resolve()
                schema = self._load_schema(str(candidate))
                if schema is None:
                    continue
                module = self._module_name_for_schema(schema)
                if module in used:
                    continue
                ordered.append(module)
                used.add(module)
        for module in sorted(modules):
            if module in used:
                continue
            ordered.append(module)
        return ordered

    def _format_imported_type_name(self, type_name: str, module: str) -> str:
        if "." in type_name:
            parts = type_name.split(".")
            parents = [self.to_snake_case(name) for name in parts[:-1]]
            path = "::".join(parents + [parts[-1]])
            return f"crate::{module}::{path}"
        return f"crate::{module}::{type_name}"

    def generate_bytes_impl(self, type_name: str) -> List[str]:
        lines = []
        lines.append(f"impl {type_name} {{")
        lines.append("    pub fn to_bytes(&self) -> Result<Vec<u8>, fory::Error> {")
        lines.append("        let fory = detail::get_fory();")
        lines.append("        fory.serialize(self)")
        lines.append("    }")
        lines.append("")
        lines.append(
            f"    pub fn from_bytes(data: &[u8]) -> Result<{type_name}, fory::Error> {{"
        )
        lines.append("        let fory = detail::get_fory();")
        lines.append("        fory.deserialize(data)")
        lines.append("    }")
        lines.append("}")
        return lines

    def generate_module(self) -> GeneratedFile:
        """Generate a Rust module with all types."""
        lines = []
        uses: Set[str] = set()

        # Collect uses (including from nested types)
        uses.add("use fory::{Fory, ForyObject}")
        uses.add("use std::sync::OnceLock")

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self.collect_message_uses(message, uses)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            self.collect_union_uses(union, uses)

        # License header
        lines.append(self.get_license_header("//"))
        lines.append("")

        # Uses
        for use in sorted(uses):
            lines.append(f"{use};")
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

        # Generate modules for nested types
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            module_lines = self.generate_nested_module(message)
            if module_lines:
                lines.extend(module_lines)
                lines.append("")

        # Generate messages (top-level only)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.extend(self.generate_message(message))
            lines.append("")

        # Generate registration function
        lines.extend(self.generate_registration())
        lines.append("")
        lines.extend(self.generate_fory_helpers())
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_module_name()}.rs",
            content="\n".join(lines),
        )

    def collect_message_uses(self, message: Message, uses: Set[str]):
        """Collect uses for a message and its nested types recursively."""
        for field in message.fields:
            self.collect_uses_for_field(field, uses)
        for nested_msg in message.nested_messages:
            self.collect_message_uses(nested_msg, uses)
        for nested_union in message.nested_unions:
            self.collect_union_uses(nested_union, uses)

    def collect_union_uses(self, union: Union, uses: Set[str]):
        """Collect uses for a union and its cases."""
        for field in union.fields:
            if self.field_uses_pointer(field):
                uses.add("use std::sync::Arc")
            self.collect_uses(field.field_type, uses)

    def get_registration_type_name(
        self, name: str, parent_stack: Optional[List[Message]] = None
    ) -> str:
        """Build dot-qualified type name for registration."""
        parts = [parent.name for parent in parent_stack or []] + [name]
        if len(parts) == 1:
            return parts[0]
        return ".".join(parts)

    def get_module_path(self, parent_stack: Optional[List[Message]]) -> str:
        """Build module path from parent message names."""
        if not parent_stack:
            return ""
        return "::".join(self.to_snake_case(parent.name) for parent in parent_stack)

    def get_type_path(self, name: str, parent_stack: Optional[List[Message]]) -> str:
        """Build a type path for nested types from the root module."""
        module_path = self.get_module_path(parent_stack)
        if module_path:
            return f"{module_path}::{name}"
        return name

    def build_relative_type_name(
        self,
        current_parents: List[str],
        target_parents: List[str],
        type_name: str,
    ) -> str:
        """Build a type path relative to the current module."""
        current_parts = [self.to_snake_case(name) for name in current_parents]
        target_parts = [self.to_snake_case(name) for name in target_parents]
        common = 0
        for left, right in zip(current_parts, target_parts):
            if left != right:
                break
            common += 1
        up = len(current_parts) - common
        down = target_parts[common:]
        parts = ["super"] * up + down
        if parts:
            return "::".join(parts + [type_name])
        return type_name

    def indent_lines(self, lines: List[str], level: int) -> List[str]:
        """Indent a list of lines by the given level."""
        prefix = self.indent_str * level
        return [f"{prefix}{line}" if line else line for line in lines]

    def generate_enum(
        self,
        enum: Enum,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Rust enum."""
        lines = []

        type_name = enum.name

        # Derive macros
        lines.append("#[derive(ForyObject, Debug, Clone, PartialEq, Default)]")
        lines.append("#[repr(i32)]")

        lines.append(f"pub enum {type_name} {{")

        # Enum values (strip prefix for scoped enums)
        for i, value in enumerate(enum.values):
            if i == 0:
                lines.append("    #[default]")
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"    {self.to_pascal_case(stripped_name)} = {value.value},")

        lines.append("}")

        return lines

    def generate_union(
        self,
        union: Union,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Rust tagged union."""
        lines: List[str] = []

        has_any = any(
            self.field_type_has_any(field.field_type) for field in union.fields
        )
        if self.to_pascal_case(union.name) != union.name:
            lines.append("#[allow(non_camel_case_types)]")
        comment = self.format_type_id_comment(union, "//")
        if comment:
            lines.append(comment)
        derives = ["ForyObject", "Debug"]
        if not has_any:
            derives.extend(["Clone", "PartialEq"])
        lines.append(f"#[derive({', '.join(derives)})]")
        lines.append(f"pub enum {union.name} {{")

        for field in union.fields:
            variant_name = self.to_pascal_case(field.name)
            variant_type = self.generate_type(
                field.field_type,
                nullable=False,
                ref=field.ref,
                element_optional=field.element_optional,
                element_ref=field.element_ref,
                parent_stack=parent_stack,
                pointer_type="Arc",
            )
            lines.append(f"    #[fory(id = {field.number})]")
            lines.append(f"    {variant_name}({variant_type}),")

        lines.append("}")
        lines.append("")

        if union.fields:
            first_field = union.fields[0]
            first_variant = self.to_pascal_case(first_field.name)
            lines.append(f"impl Default for {union.name} {{")
            lines.append("    fn default() -> Self {")
            lines.append(f"        Self::{first_variant}(Default::default())")
            lines.append("    }")
            lines.append("}")
            lines.append("")

        lines.extend(self.generate_bytes_impl(union.name))

        return lines

    def generate_message(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Rust struct."""
        lines = []

        type_name = message.name

        # Derive macros
        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        needs_safe_debug = self.message_needs_safe_debug(message)
        derives = ["ForyObject"]
        if not needs_safe_debug:
            derives.append("Debug")
        if not self.message_has_any(message):
            derives.extend(["Clone", "PartialEq", "Default"])
        lines.append(f"#[derive({', '.join(derives)})]")
        if not self.get_effective_evolving(message):
            lines.append("#[fory(evolving = false)]")

        lines.append(f"pub struct {type_name} {{")

        # Fields
        lineage = (parent_stack or []) + [message]
        for field in message.fields:
            field_lines = self.generate_field(field, lineage)
            for line in field_lines:
                lines.append(f"    {line}")

        lines.append("}")
        lines.append("")
        if needs_safe_debug:
            lines.extend(self.generate_debug_impl(message))
            lines.append("")
        lines.extend(self.generate_bytes_impl(type_name))

        return lines

    def message_has_any(self, message: Message) -> bool:
        """Return True if a message contains any type fields."""
        return any(
            self.field_type_has_any(field.field_type) for field in message.fields
        )

    def field_type_has_any(self, field_type: FieldType) -> bool:
        """Return True if field type or its children is any."""
        if isinstance(field_type, PrimitiveType):
            return field_type.kind == PrimitiveKind.ANY
        if isinstance(field_type, ListType):
            return self.field_type_has_any(field_type.element_type)
        if isinstance(field_type, MapType):
            return self.field_type_has_any(
                field_type.key_type
            ) or self.field_type_has_any(field_type.value_type)
        return False

    def field_has_ref(self, field: Field) -> bool:
        if field.ref:
            return True
        if isinstance(field.field_type, ListType):
            return field.element_ref
        if isinstance(field.field_type, MapType):
            return field.field_type.value_ref
        return False

    def field_needs_safe_debug(self, field: Field) -> bool:
        return self.field_has_ref(field) or self.field_type_has_any(field.field_type)

    def message_needs_safe_debug(self, message: Message) -> bool:
        return any(self.field_needs_safe_debug(field) for field in message.fields)

    def rust_string_literal(self, value: str) -> str:
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'

    def generate_debug_impl(self, message: Message) -> List[str]:
        """Generate a Debug impl that avoids recursive ref expansion."""
        lines: List[str] = []
        type_name = message.name
        lines.append(f"impl std::fmt::Debug for {type_name} {{")
        lines.append(
            "    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {"
        )
        if not message.fields:
            lines.append(
                f"        f.write_str({self.rust_string_literal(type_name + ' {}')})"
            )
            lines.append("    }")
            lines.append("}")
            return lines
        lines.append(
            f"        f.write_str({self.rust_string_literal(type_name + ' { ')})?;"
        )
        for i, field in enumerate(message.fields):
            field_name = self.to_snake_case(field.name)
            if i > 0:
                lines.append('        f.write_str(", ")?;')
            lines.append(
                f"        f.write_str({self.rust_string_literal(field_name + ': ')})?;"
            )
            if self.field_needs_safe_debug(field):
                placeholder = f"{self.format_idl_type(field.field_type)}(...)"
                placeholder_literal = self.rust_string_literal(placeholder)
                if field.optional:
                    lines.append(f"        if self.{field_name}.is_some() {{")
                    lines.append(f"            f.write_str({placeholder_literal})?;")
                    lines.append("        } else {")
                    lines.append('            f.write_str("None")?;')
                    lines.append("        }")
                else:
                    lines.append(f"        f.write_str({placeholder_literal})?;")
            else:
                lines.append(f'        write!(f, "{{:?}}", self.{field_name})?;')
        lines.append('        f.write_str(" }")')
        lines.append("    }")
        lines.append("}")
        return lines

    def generate_nested_module(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
        indent: int = 0,
    ) -> List[str]:
        """Generate a nested Rust module containing message nested types."""
        if (
            not message.nested_enums
            and not message.nested_unions
            and not message.nested_messages
        ):
            return []

        lines: List[str] = []
        ind = self.indent_str * indent
        module_name = self.to_snake_case(message.name)
        lines.append(f"{ind}pub mod {module_name} {{")
        lines.append(f"{ind}{self.indent_str}use super::*;")
        lines.append("")

        lineage = (parent_stack or []) + [message]

        # Nested enums
        for nested_enum in message.nested_enums:
            enum_lines = self.generate_enum(nested_enum, lineage)
            lines.extend(self.indent_lines(enum_lines, indent + 1))
            lines.append("")

        # Nested unions
        for nested_union in message.nested_unions:
            union_lines = self.generate_union(nested_union, lineage)
            lines.extend(self.indent_lines(union_lines, indent + 1))
            lines.append("")

        # Nested messages and their modules
        for nested_msg in message.nested_messages:
            nested_module_lines = self.generate_nested_module(
                nested_msg, lineage, indent + 1
            )
            if nested_module_lines:
                lines.extend(nested_module_lines)
                lines.append("")

            msg_lines = self.generate_message(nested_msg, lineage)
            lines.extend(self.indent_lines(msg_lines, indent + 1))
            lines.append("")

        if lines[-1] == "":
            lines.pop()
        lines.append(f"{ind}}}")
        return lines

    def generate_field(
        self,
        field: Field,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a struct field."""
        lines = []

        attrs = []
        if field.tag_id is not None:
            attrs.append(f"id = {field.tag_id}")
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        if field.optional or is_any:
            attrs.append("nullable = true")
        if field.ref:
            attrs.append("ref = true")
        encoding = self.get_encoding_attr(field.field_type)
        if encoding:
            attrs.append(encoding)
        array_attr = self.get_array_attr(field)
        if array_attr:
            attrs.append(array_attr)
        if self.is_union_type(field.field_type, parent_stack):
            attrs.append('type_id = "union"')
        if attrs:
            lines.append(f"#[fory({', '.join(attrs)})]")

        if isinstance(field.field_type, ListType) and field.element_ref:
            ref_options = field.element_ref_options
            weak_ref = ref_options.get("weak_ref") is True
        elif isinstance(field.field_type, MapType) and field.field_type.value_ref:
            ref_options = field.field_type.value_ref_options
            weak_ref = ref_options.get("weak_ref") is True
        else:
            ref_options = field.ref_options
            weak_ref = ref_options.get("weak_ref") is True
        pointer_type = self.get_pointer_type(ref_options, weak_ref)
        rust_type = self.generate_type(
            field.field_type,
            nullable=field.optional,
            ref=field.ref,
            element_optional=field.element_optional,
            element_ref=field.element_ref,
            parent_stack=parent_stack,
            pointer_type=pointer_type,
        )
        field_name = self.to_snake_case(field.name)

        lines.append(f"pub {field_name}: {rust_type},")

        return lines

    def get_encoding_attr(self, field_type: FieldType) -> Optional[str]:
        """Return an encoding attribute for integer primitives."""
        if not isinstance(field_type, PrimitiveType):
            return None
        kind = field_type.kind
        if kind in (
            PrimitiveKind.INT32,
            PrimitiveKind.INT64,
            PrimitiveKind.UINT32,
            PrimitiveKind.UINT64,
        ):
            return 'encoding = "fixed"'
        if kind in (PrimitiveKind.TAGGED_INT64, PrimitiveKind.TAGGED_UINT64):
            return 'encoding = "tagged"'
        return None

    def get_array_attr(self, field: Field) -> Optional[str]:
        """Return type_id attribute for int8/uint8 arrays."""
        if not isinstance(field.field_type, ListType):
            return None
        if field.element_optional or field.element_ref:
            return None
        element_type = field.field_type.element_type
        if not isinstance(element_type, PrimitiveType):
            return None
        if element_type.kind == PrimitiveKind.INT8:
            return 'type_id = "int8_array"'
        if element_type.kind == PrimitiveKind.UINT8:
            return 'type_id = "uint8_array"'
        return None

    def is_union_type(
        self, field_type: FieldType, parent_stack: Optional[List[Message]]
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        type_name = field_type.name
        if "." in type_name:
            resolved = self.schema.get_type(type_name)
            return isinstance(resolved, Union)
        if parent_stack:
            for i in range(len(parent_stack) - 1, -1, -1):
                nested = parent_stack[i].get_nested_type(type_name)
                if nested is not None:
                    return isinstance(nested, Union)
        resolved = self.schema.get_type(type_name)
        return isinstance(resolved, Union)

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        ref: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
        pointer_type: str = "Arc",
    ) -> str:
        """Generate Rust type string."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return "Box<dyn Any>"
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"Option<{base_type}>"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                module = self._module_name_for_type(named_type)
                if module:
                    type_name = self._format_imported_type_name(field_type.name, module)
            if ref:
                type_name = f"{pointer_type}<{type_name}>"
            if nullable:
                type_name = f"Option<{type_name}>"
            return type_name

        elif isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=element_optional,
                ref=element_ref,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            list_type = f"Vec<{element_type}>"
            if ref:
                list_type = f"{pointer_type}<{list_type}>"
            if nullable:
                list_type = f"Option<{list_type}>"
            return list_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type,
                nullable=False,
                ref=False,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            value_type = self.generate_type(
                field_type.value_type,
                nullable=False,
                ref=field_type.value_ref,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            map_type = f"HashMap<{key_type}, {value_type}>"
            if ref:
                map_type = f"{pointer_type}<{map_type}>"
            if nullable:
                map_type = f"Option<{map_type}>"
            return map_type

        return "()"

    def resolve_nested_type_name(
        self,
        type_name: str,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Resolve nested type names to module-qualified Rust identifiers."""
        current_parents = [msg.name for msg in (parent_stack or [])[:-1]]
        if "." in type_name:
            parts = type_name.split(".")
            target_parents = parts[:-1]
            base_name = parts[-1]
            return self.build_relative_type_name(
                current_parents, target_parents, base_name
            )
        if not parent_stack:
            return type_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                target_parents = [msg.name for msg in parent_stack[: i + 1]]
                return self.build_relative_type_name(
                    current_parents, target_parents, type_name
                )

        return type_name

    def collect_uses(self, field_type: FieldType, uses: Set[str]):
        """Collect required use statements for a field type."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind in (PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP):
                uses.add("use chrono")
            if field_type.kind == PrimitiveKind.ANY:
                uses.add("use std::any::Any")

        elif isinstance(field_type, NamedType):
            pass  # No additional uses needed

        elif isinstance(field_type, ListType):
            self.collect_uses(field_type.element_type, uses)

        elif isinstance(field_type, MapType):
            uses.add("use std::collections::HashMap")
            self.collect_uses(field_type.key_type, uses)
            self.collect_uses(field_type.value_type, uses)

    def collect_uses_for_field(self, field: Field, uses: Set[str]):
        """Collect uses for a field, including ref tracking."""
        if isinstance(field.field_type, ListType) and field.element_ref:
            ref_options = field.element_ref_options
            weak_ref = ref_options.get("weak_ref") is True
        elif isinstance(field.field_type, MapType) and field.field_type.value_ref:
            ref_options = field.field_type.value_ref_options
            weak_ref = ref_options.get("weak_ref") is True
        else:
            ref_options = field.ref_options
            weak_ref = ref_options.get("weak_ref") is True
        pointer_type = self.get_pointer_type(ref_options, weak_ref)
        if weak_ref and self.field_uses_pointer(field):
            if pointer_type == "RcWeak":
                uses.add("use fory::RcWeak")
            else:
                uses.add("use fory::ArcWeak")
        elif self.field_uses_pointer(field):
            if pointer_type == "Rc":
                uses.add("use std::rc::Rc")
            else:
                uses.add("use std::sync::Arc")
        self.collect_uses(field.field_type, uses)

    def field_uses_pointer(self, field: Field) -> bool:
        if field.ref:
            return True
        if isinstance(field.field_type, ListType) and field.element_ref:
            return True
        if isinstance(field.field_type, MapType) and field.field_type.value_ref:
            return True
        return False

    def get_pointer_type(self, ref_options: dict, weak_ref: bool = False) -> str:
        """Determine pointer type for ref tracking based on field options."""
        thread_safe = ref_options.get("thread_safe_pointer")
        if thread_safe is False:
            return "RcWeak" if weak_ref else "Rc"
        return "ArcWeak" if weak_ref else "Arc"

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        lines.append(
            "pub fn register_types(fory: &mut Fory) -> Result<(), fory::Error> {"
        )

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

        lines.append("    Ok(())")
        lines.append("}")

        return lines

    def generate_fory_helpers(self) -> List[str]:
        lines: List[str] = []
        lines.append("mod detail {")
        lines.append("    use super::*;")
        lines.append("")
        lines.append("    pub(super) fn get_fory() -> &'static Fory {")
        lines.append("        static FORY: OnceLock<Fory> = OnceLock::new();")
        lines.append("        FORY.get_or_init(|| {")
        lines.append("            let mut fory = Fory::default()")
        lines.append("                .xlang(true)")
        lines.append("                .track_ref(true)")
        lines.append("                .compatible(true);")
        for module in self._collect_imported_modules():
            lines.append(
                f'            crate::{module}::register_types(&mut fory).expect("failed to register fory types");'
            )
        lines.append(
            '            register_types(&mut fory).expect("failed to register fory types");'
        )
        lines.append("            fory")
        lines.append("        })")
        lines.append("    }")
        lines.append("}")
        return lines

    def generate_enum_registration(
        self,
        lines: List[str],
        enum: Enum,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for an enum."""
        type_name = self.get_type_path(enum.name, parent_stack)
        reg_name = self.get_registration_type_name(enum.name, parent_stack)

        if self.should_register_by_id(enum):
            lines.append(f"    fory.register::<{type_name}>({enum.type_id})?;")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_by_namespace::<{type_name}>("{ns}", "{reg_name}")?;'
            )

    def generate_message_registration(
        self,
        lines: List[str],
        message: Message,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a message and its nested types."""
        type_name = self.get_type_path(message.name, parent_stack)
        reg_name = self.get_registration_type_name(message.name, parent_stack)

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
            lines.append(f"    fory.register::<{type_name}>({message.type_id})?;")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_by_namespace::<{type_name}>("{ns}", "{reg_name}")?;'
            )

    def generate_union_registration(
        self,
        lines: List[str],
        union: Union,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a union."""
        type_name = self.get_type_path(union.name, parent_stack)
        reg_name = self.get_registration_type_name(union.name, parent_stack)

        if self.should_register_by_id(union):
            lines.append(f"    fory.register_union::<{type_name}>({union.type_id})?;")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_union_by_namespace::<{type_name}>("{ns}", "{reg_name}")?;'
            )
