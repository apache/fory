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

"""C++ code generator."""

from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple
import typing

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


class CppGenerator(BaseGenerator):
    """Generates C++ classes with FORY_STRUCT macros."""

    language_name = "cpp"
    file_extension = ".h"

    # Mapping from FDL primitive types to C++ types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "int8_t",
        PrimitiveKind.INT16: "int16_t",
        PrimitiveKind.INT32: "int32_t",
        PrimitiveKind.VARINT32: "int32_t",
        PrimitiveKind.INT64: "int64_t",
        PrimitiveKind.VARINT64: "int64_t",
        PrimitiveKind.TAGGED_INT64: "int64_t",
        PrimitiveKind.UINT8: "uint8_t",
        PrimitiveKind.UINT16: "uint16_t",
        PrimitiveKind.UINT32: "uint32_t",
        PrimitiveKind.VAR_UINT32: "uint32_t",
        PrimitiveKind.UINT64: "uint64_t",
        PrimitiveKind.VAR_UINT64: "uint64_t",
        PrimitiveKind.TAGGED_UINT64: "uint64_t",
        PrimitiveKind.FLOAT16: "float",
        PrimitiveKind.FLOAT32: "float",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "std::string",
        PrimitiveKind.BYTES: "std::vector<uint8_t>",
        PrimitiveKind.DATE: "fory::serialization::Date",
        PrimitiveKind.TIMESTAMP: "fory::serialization::Timestamp",
        PrimitiveKind.ANY: "std::any",
    }
    NUMERIC_PRIMITIVES = {
        PrimitiveKind.BOOL,
        PrimitiveKind.INT8,
        PrimitiveKind.INT16,
        PrimitiveKind.INT32,
        PrimitiveKind.VARINT32,
        PrimitiveKind.INT64,
        PrimitiveKind.VARINT64,
        PrimitiveKind.TAGGED_INT64,
        PrimitiveKind.UINT8,
        PrimitiveKind.UINT16,
        PrimitiveKind.UINT32,
        PrimitiveKind.VAR_UINT32,
        PrimitiveKind.UINT64,
        PrimitiveKind.VAR_UINT64,
        PrimitiveKind.TAGGED_UINT64,
        PrimitiveKind.FLOAT16,
        PrimitiveKind.FLOAT32,
        PrimitiveKind.FLOAT64,
    }

    def generate(self) -> List[GeneratedFile]:
        """Generate C++ files for the schema."""
        files = []

        # Generate a single header file with all types
        files.append(self.generate_header())

        return files

    def get_header_name(self) -> str:
        """Get the header file name."""
        if self.package:
            return self.package.replace(".", "_")
        return "generated"

    def get_namespace(self) -> str:
        """Get the C++ namespace."""
        if self.package:
            return self.package.replace(".", "::")
        return ""

    def get_namespaced_type_name(
        self,
        type_name: str,
        parent_stack: List[Message],
    ) -> str:
        """Get a C++ type name including namespace for global macros."""
        qualified_name = self.get_qualified_type_name(type_name, parent_stack)
        namespace = self.get_namespace()
        if namespace:
            return f"{namespace}::{qualified_name}"
        return qualified_name

    def get_field_config_type(
        self,
        type_name: str,
        parent_stack: List[Message],
    ) -> str:
        """Get type name for FORY_FIELD_CONFIG."""
        return self.get_namespaced_type_name(type_name, parent_stack)

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

    def _namespace_for_schema(self, schema: Schema) -> str:
        if schema.package:
            return schema.package.replace(".", "::")
        return ""

    def _namespace_for_type(self, type_def: object) -> str:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return ""
        return self._namespace_for_schema(schema)

    def _header_for_schema(self, schema: Schema) -> str:
        if schema.package:
            return f"{schema.package.replace('.', '_')}.h"
        return "generated.h"

    def _header_for_type(self, type_def: object) -> Optional[str]:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return None
        return self._header_for_schema(schema)

    def _collect_imported_namespaces(self) -> List[str]:
        namespaces: Set[str] = set()
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            ns = self._namespace_for_type(type_def)
            if ns:
                namespaces.add(ns)
        ordered: List[str] = []
        used: Set[str] = set()
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = (base_dir / imp.path).resolve()
                schema = self._load_schema(str(candidate))
                if schema is None:
                    continue
                ns = self._namespace_for_schema(schema)
                if not ns or ns in used:
                    continue
                ordered.append(ns)
                used.add(ns)
        for ns in sorted(namespaces):
            if ns in used:
                continue
            ordered.append(ns)
        return ordered

    def generate_bytes_methods(self, class_name: str, indent: str) -> List[str]:
        lines: List[str] = []
        lines.append(
            f"{indent}fory::Result<std::vector<uint8_t>, fory::Error> to_bytes() const {{"
        )
        lines.append(f"{indent}  return detail::get_fory().serialize(*this);")
        lines.append(f"{indent}}}")
        lines.append("")
        lines.append(
            f"{indent}static fory::Result<{class_name}, fory::Error> from_bytes(const std::vector<uint8_t>& data) {{"
        )
        lines.append(
            f"{indent}  return detail::get_fory().deserialize<{class_name}>(data);"
        )
        lines.append(f"{indent}}}")
        return lines

    def generate_header(self) -> GeneratedFile:
        """Generate a C++ header file with all types."""
        lines = []
        includes: Set[str] = set()
        enum_macros: List[str] = []
        union_macros: List[str] = []
        field_config_macros: List[str] = []
        evolving_macros: List[str] = []
        definition_items = self.get_definition_order()

        # Collect includes (including from nested types)
        includes.add("<cstdint>")
        includes.add("<map>")
        includes.add("<memory>")
        includes.add("<string>")
        includes.add("<vector>")
        includes.add("<utility>")
        includes.add('"fory/serialization/fory.h"')
        if self.schema_has_unions():
            includes.add("<utility>")
            includes.add("<variant>")
            includes.add("<memory>")
            includes.add("<typeindex>")
            includes.add('"fory/serialization/union_serializer.h"')
        if self.schema.source_file:
            base_dir = Path(self.schema.source_file).resolve().parent
            for imp in self.schema.imports:
                candidate = (base_dir / imp.path).resolve()
                schema = self._load_schema(str(candidate))
                if schema is None:
                    continue
                includes.add(f'"{self._header_for_schema(schema)}"')

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self.collect_message_includes(message, includes)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            self.collect_union_includes(union, includes)

        # License header
        lines.append("/*")
        for line in self.get_license_header(" *").split("\n"):
            lines.append(line)
        lines.append(" */")
        lines.append("")

        # Header guard
        guard_name = f"{self.get_header_name().upper()}_H_"
        lines.append(f"#ifndef {guard_name}")
        lines.append(f"#define {guard_name}")
        lines.append("")

        # Includes
        for inc in sorted(includes):
            lines.append(f"#include {inc}")
        lines.append("")

        # Namespace
        namespace = self.get_namespace()
        if namespace:
            lines.append(f"namespace {namespace} {{")
            lines.append("")

        # Forward declarations (top-level messages only)
        self.generate_forward_declarations(lines)
        if self.schema.messages:
            lines.append("")
        lines.append("namespace detail {")
        lines.append("fory::serialization::ThreadSafeFory& get_fory();")
        lines.append("} // namespace detail")
        lines.append("")

        # Generate enums (top-level)
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            lines.extend(self.generate_enum_definition(enum))
            enum_macros.append(self.generate_enum_macro(enum, []))
            lines.append("")

        # Generate top-level unions/messages in dependency order
        for kind, item in definition_items:
            if kind == "union":
                lines.extend(self.generate_union_definition(item, [], ""))
                union_macros.extend(self.generate_union_macros(item, []))
                lines.append("")
                continue
            lines.extend(
                self.generate_message_definition(
                    item,
                    [],
                    enum_macros,
                    union_macros,
                    field_config_macros,
                    evolving_macros,
                    "",
                )
            )
            lines.append("")

        if union_macros:
            lines.extend(union_macros)
            lines.append("")

        if field_config_macros:
            lines.extend(field_config_macros)
            lines.append("")

        if enum_macros:
            lines.extend(enum_macros)
            lines.append("")

        # Generate registration function (after FORY_STRUCT/FORY_ENUM)
        lines.extend(self.generate_registration())
        lines.append("")

        if namespace:
            lines.append(f"}} // namespace {namespace}")
            lines.append("")

        if evolving_macros:
            lines.extend(evolving_macros)
            lines.append("")

        # End header guard
        lines.append(f"#endif // {guard_name}")
        lines.append("")

        return GeneratedFile(
            path=f"{self.get_header_name()}.h",
            content="\n".join(lines),
        )

    def collect_message_includes(self, message: Message, includes: Set[str]):
        """Collect includes for a message and its nested types recursively."""
        for field in message.fields:
            weak_ref = self.get_field_weak_ref(field)
            element_weak_ref = self.get_element_weak_ref(field)
            if isinstance(field.field_type, MapType):
                weak_ref = self.get_map_value_weak_ref(field)
            self.collect_includes(
                field.field_type,
                field.optional,
                field.ref,
                includes,
                field.element_optional,
                field.element_ref,
                weak_ref,
                element_weak_ref,
            )
        for nested_msg in message.nested_messages:
            self.collect_message_includes(nested_msg, includes)
        for nested_union in message.nested_unions:
            self.collect_union_includes(nested_union, includes)

    def collect_union_includes(self, union: Union, includes: Set[str]):
        """Collect includes for a union and its cases."""
        for field in union.fields:
            self.collect_includes(
                field.field_type,
                False,
                False,
                includes,
                field.element_optional,
                field.element_ref,
                False,
                False,
            )

    def generate_forward_declarations(self, lines: List[str]):
        """Generate forward declarations for top-level messages."""
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.append(f"class {message.name};")

    def get_definition_order(self) -> List:
        """Return top-level unions/messages in dependency order."""
        items: List = []
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            items.append(("union", union))
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            items.append(("message", message))

        name_to_index = {}
        for idx, (kind, item) in enumerate(items):
            name_to_index[item.name] = idx

        dependencies: Dict[int, Set[int]] = {i: set() for i in range(len(items))}
        reverse_edges: Dict[int, Set[int]] = {i: set() for i in range(len(items))}

        for idx, (kind, item) in enumerate(items):
            deps: Set[str] = set()
            if kind == "union":
                self.collect_union_dependencies(item, [], deps)
            else:
                self.collect_message_dependencies(item, [], deps)
            for dep_name in deps:
                dep_idx = name_to_index.get(dep_name)
                if dep_idx is None or dep_idx == idx:
                    continue
                dependencies[idx].add(dep_idx)
                reverse_edges[dep_idx].add(idx)

        in_degree = {idx: len(dependencies[idx]) for idx in dependencies}
        available = [idx for idx, degree in in_degree.items() if degree == 0]
        ordered: List = []

        while available:
            available.sort()
            idx = available.pop(0)
            ordered.append(items[idx])
            for neighbor in reverse_edges[idx]:
                in_degree[neighbor] -= 1
                if in_degree[neighbor] == 0:
                    available.append(neighbor)

        if len(ordered) != len(items):
            raise ValueError("C++ generator cannot resolve type order for unions.")

        return ordered

    def collect_message_dependencies(
        self, message: Message, parent_stack: List[Message], deps: Set[str]
    ) -> None:
        """Collect top-level type dependencies for a message."""
        lineage = parent_stack + [message]
        for field in message.fields:
            if field.ref or field.element_ref:
                continue
            if isinstance(field.field_type, MapType) and field.field_type.value_ref:
                continue
            self.collect_type_dependencies(field.field_type, lineage, deps)
        for nested_union in message.nested_unions:
            self.collect_union_dependencies(nested_union, lineage, deps)
        for nested_msg in message.nested_messages:
            self.collect_message_dependencies(nested_msg, lineage, deps)

    def collect_union_dependencies(
        self, union: Union, parent_stack: List[Message], deps: Set[str]
    ) -> None:
        """Collect top-level type dependencies for a union."""
        for field in union.fields:
            self.collect_type_dependencies(field.field_type, parent_stack, deps)

    def collect_type_dependencies(
        self, field_type: FieldType, parent_stack: List[Message], deps: Set[str]
    ) -> None:
        if isinstance(field_type, PrimitiveType):
            return
        if isinstance(field_type, NamedType):
            type_name = field_type.name
            if self.is_nested_type_reference(type_name, parent_stack):
                return
            top_level = type_name.split(".")[0]
            deps.add(top_level)
            return
        if isinstance(field_type, ListType):
            self.collect_type_dependencies(field_type.element_type, parent_stack, deps)
            return
        if isinstance(field_type, MapType):
            self.collect_type_dependencies(field_type.key_type, parent_stack, deps)
            self.collect_type_dependencies(field_type.value_type, parent_stack, deps)
            return

    def is_nested_type_reference(
        self, type_name: str, parent_stack: List[Message]
    ) -> bool:
        if not parent_stack:
            return False
        root_name = parent_stack[0].name
        if "." in type_name:
            return type_name.split(".")[0] == root_name
        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                return True
        return False

    def get_enum_value_names(self, enum: Enum) -> List[str]:
        """Get enum value names without the enum prefix."""
        stripped_names = []
        for value in enum.values:
            stripped_names.append(self.strip_enum_prefix(enum.name, value.name))
        return stripped_names

    def generate_enum_definition(self, enum: Enum, indent: str = "") -> List[str]:
        """Generate a C++ enum class definition."""
        lines = []
        lines.append(f"{indent}enum class {enum.name} : int32_t {{")
        for value in enum.values:
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            lines.append(f"{indent}    {stripped_name} = {value.value},")
        lines.append(f"{indent}}};")
        return lines

    def generate_enum_macro(
        self,
        enum: Enum,
        parent_stack: List[Message],
    ) -> str:
        """Generate a FORY_ENUM macro line for an enum."""
        value_names = ", ".join(self.get_enum_value_names(enum))
        qualified_name = self.get_namespaced_type_name(enum.name, parent_stack)
        return f"FORY_ENUM({qualified_name}, {value_names});"

    def resolve_named_type(
        self, name: str, parent_stack: Optional[List[Message]]
    ) -> Optional[typing.Union[Message, Enum, Union]]:
        """Resolve a named type to its schema definition."""
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
    ) -> Optional[typing.Union[Message, Enum, Union]]:
        """Find a top-level type definition by name."""
        for enum in self.schema.enums:
            if enum.name == name:
                return enum
        for union in self.schema.unions:
            if union.name == name:
                return union
        for message in self.schema.messages:
            if message.name == name:
                return message
        return None

    def is_message_type(
        self, field_type: FieldType, parent_stack: Optional[List[Message]]
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.resolve_named_type(field_type.name, parent_stack)
        return isinstance(resolved, Message)

    def is_weak_ref(self, options: dict) -> bool:
        return options.get("weak_ref") is True

    def get_field_weak_ref(self, field: Field) -> bool:
        return self.is_weak_ref(field.ref_options)

    def get_element_weak_ref(self, field: Field) -> bool:
        return self.is_weak_ref(field.element_ref_options)

    def get_map_value_weak_ref(self, field: Field) -> bool:
        if isinstance(field.field_type, MapType):
            return self.is_weak_ref(field.field_type.value_ref_options)
        return False

    def is_union_type(
        self, field_type: FieldType, parent_stack: Optional[List[Message]]
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.resolve_named_type(field_type.name, parent_stack)
        return isinstance(resolved, Union)

    def is_enum_type(
        self, field_type: FieldType, parent_stack: Optional[List[Message]]
    ) -> bool:
        if not isinstance(field_type, NamedType):
            return False
        resolved = self.resolve_named_type(field_type.name, parent_stack)
        return isinstance(resolved, Enum)

    def get_field_member_name(self, field: Field) -> str:
        return f"{self.to_snake_case(field.name)}_"

    def get_field_storage_type(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        weak_ref = self.get_field_weak_ref(field)
        element_weak_ref = self.get_element_weak_ref(field)
        if isinstance(field.field_type, MapType):
            weak_ref = self.get_map_value_weak_ref(field)
        if self.is_message_type(field.field_type, parent_stack) and not (
            field.ref or weak_ref
        ):
            type_name = self.generate_type(
                field.field_type,
                False,
                False,
                False,
                False,
                weak_ref,
                element_weak_ref,
                parent_stack,
            )
            return f"std::unique_ptr<{type_name}>"
        return self.generate_type(
            field.field_type,
            False
            if (
                self.is_message_type(field.field_type, parent_stack)
                and (field.ref or weak_ref)
            )
            else field.optional,
            field.ref,
            field.element_optional,
            field.element_ref,
            weak_ref,
            element_weak_ref,
            parent_stack,
        )

    def get_field_value_type(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        weak_ref = self.get_field_weak_ref(field)
        element_weak_ref = self.get_element_weak_ref(field)
        if isinstance(field.field_type, MapType):
            weak_ref = self.get_map_value_weak_ref(field)
        if self.is_message_type(field.field_type, parent_stack) and not (
            field.ref or weak_ref
        ):
            return self.generate_type(
                field.field_type,
                False,
                False,
                False,
                False,
                weak_ref,
                element_weak_ref,
                parent_stack,
            )
        return self.generate_type(
            field.field_type,
            False,
            field.ref,
            field.element_optional,
            field.element_ref,
            weak_ref,
            element_weak_ref,
            parent_stack,
        )

    def get_field_eq_expression(
        self, field: Field, parent_stack: Optional[List[Message]]
    ) -> str:
        member_name = self.get_field_member_name(field)
        other_member = f"other.{member_name}"
        if isinstance(field.field_type, PrimitiveType) and (
            field.field_type.kind == PrimitiveKind.ANY
        ):
            return (
                f"((!{member_name}.has_value() && !{other_member}.has_value()) || "
                f"({member_name}.type() == {other_member}.type()))"
            )
        if self.is_message_type(
            field.field_type, parent_stack
        ) and self.get_field_weak_ref(field):
            return (
                f"(([&]() {{ auto left = {member_name}.upgrade(); "
                f"auto right = {other_member}.upgrade(); "
                f"return left == right; }})())"
            )
        if self.is_message_type(field.field_type, parent_stack) and field.ref:
            return f"{member_name} == {other_member}"
        if self.is_message_type(field.field_type, parent_stack):
            return (
                f"(({member_name} && {other_member}) ? "
                f"(*{member_name} == *{other_member}) : "
                f"({member_name} == {other_member}))"
            )
        return f"{member_name} == {other_member}"

    def is_numeric_field(self, field: Field) -> bool:
        if not isinstance(field.field_type, PrimitiveType):
            return False
        return field.field_type.kind in self.NUMERIC_PRIMITIVES

    def is_string_field(self, field: Field) -> bool:
        return isinstance(field.field_type, PrimitiveType) and (
            field.field_type.kind == PrimitiveKind.STRING
        )

    def is_bytes_field(self, field: Field) -> bool:
        return isinstance(field.field_type, PrimitiveType) and (
            field.field_type.kind == PrimitiveKind.BYTES
        )

    def generate_field_accessors(
        self, field: Field, parent_stack: Optional[List[Message]], indent: str
    ) -> List[str]:
        lines: List[str] = []
        field_name = self.to_snake_case(field.name)
        member_name = self.get_field_member_name(field)
        value_type = self.get_field_value_type(field, parent_stack)
        weak_ref = self.get_field_weak_ref(field)
        is_union = self.is_union_type(field.field_type, parent_stack)
        is_enum = self.is_enum_type(field.field_type, parent_stack)
        is_collection = isinstance(field.field_type, (ListType, MapType))
        is_bytes = self.is_bytes_field(field)
        is_string = self.is_string_field(field)
        needs_mutable = is_string or is_collection or is_bytes or is_union
        no_setter = is_collection or is_bytes or is_union
        value_getter = self.is_numeric_field(field) or is_enum

        if self.is_message_type(field.field_type, parent_stack) and (
            field.ref or weak_ref
        ):
            lines.append(f"{indent}bool has_{field_name}() const {{")
            if weak_ref:
                lines.append(f"{indent}  return !{member_name}.expired();")
            else:
                lines.append(f"{indent}  return {member_name} != nullptr;")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}const {value_type}& {field_name}() const {{")
            lines.append(f"{indent}  return {member_name};")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}{value_type}* mutable_{field_name}() {{")
            lines.append(f"{indent}  return &{member_name};")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}void set_{field_name}({value_type} value) {{")
            lines.append(f"{indent}  {member_name} = std::move(value);")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}void clear_{field_name}() {{")
            lines.append(f"{indent}  {member_name} = {value_type}();")
            lines.append(f"{indent}}}")
            return lines

        if self.is_message_type(field.field_type, parent_stack):
            lines.append(f"{indent}bool has_{field_name}() const {{")
            lines.append(f"{indent}  return {member_name} != nullptr;")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}const {value_type}& {field_name}() const {{")
            lines.append(f"{indent}  return *{member_name};")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}{value_type}* mutable_{field_name}() {{")
            lines.append(f"{indent}  if (!{member_name}) {{")
            lines.append(
                f"{indent}    {member_name} = std::make_unique<{value_type}>();"
            )
            lines.append(f"{indent}  }}")
            lines.append(f"{indent}  return {member_name}.get();")
            lines.append(f"{indent}}}")
            lines.append("")
            lines.append(f"{indent}void clear_{field_name}() {{")
            lines.append(f"{indent}  {member_name}.reset();")
            lines.append(f"{indent}}}")
            return lines

        if field.optional:
            lines.append(f"{indent}bool has_{field_name}() const {{")
            lines.append(f"{indent}  return {member_name}.has_value();")
            lines.append(f"{indent}}}")
            lines.append("")

        if value_getter:
            lines.append(f"{indent}{value_type} {field_name}() const {{")
        else:
            lines.append(f"{indent}const {value_type}& {field_name}() const {{")
        if field.optional:
            lines.append(f"{indent}  return *{member_name};")
        else:
            lines.append(f"{indent}  return {member_name};")
        lines.append(f"{indent}}}")

        if needs_mutable:
            lines.append("")
            lines.append(f"{indent}{value_type}* mutable_{field_name}() {{")
            if field.optional:
                lines.append(f"{indent}  if (!{member_name}) {{")
                lines.append(f"{indent}    {member_name}.emplace();")
                lines.append(f"{indent}  }}")
                lines.append(f"{indent}  return &{member_name}.value();")
            else:
                lines.append(f"{indent}  return &{member_name};")
            lines.append(f"{indent}}}")

        if not no_setter:
            lines.append("")
            if is_string:
                lines.append(f"{indent}template <class Arg, class... Args>")
                lines.append(
                    f"{indent}void set_{field_name}(Arg&& arg, Args&&... args) {{"
                )
                if field.optional:
                    lines.append(
                        f"{indent}  {member_name}.emplace(std::forward<Arg>(arg), std::forward<Args>(args)...);"
                    )
                else:
                    lines.append(
                        f"{indent}  {member_name} = {value_type}(std::forward<Arg>(arg), std::forward<Args>(args)...);"
                    )
                lines.append(f"{indent}}}")
            else:
                lines.append(f"{indent}void set_{field_name}({value_type} value) {{")
                lines.append(f"{indent}  {member_name} = std::move(value);")
                lines.append(f"{indent}}}")

        if field.optional:
            lines.append("")
            lines.append(f"{indent}void clear_{field_name}() {{")
            lines.append(f"{indent}  {member_name}.reset();")
            lines.append(f"{indent}}}")

        return lines

    def generate_message_definition(
        self,
        message: Message,
        parent_stack: List[Message],
        enum_macros: List[str],
        union_macros: List[str],
        field_config_macros: List[str],
        evolving_macros: List[str],
        indent: str,
    ) -> List[str]:
        """Generate a C++ class definition with nested types."""
        lines: List[str] = []
        class_name = message.name
        lineage = parent_stack + [message]
        body_indent = f"{indent}  "
        field_indent = f"{indent}    "
        comment = self.format_type_id_comment(message, f"{indent}//")
        if comment:
            lines.append(comment)
        lines.append(f"{indent}class {class_name} final {{")
        lines.append(f"{body_indent}public:")
        if message.fields:
            lines.append("")

        for nested_enum in message.nested_enums:
            lines.extend(self.generate_enum_definition(nested_enum, body_indent))
            lines.append("")
            enum_macros.append(self.generate_enum_macro(nested_enum, lineage))

        for nested_msg in message.nested_messages:
            lines.extend(
                self.generate_message_definition(
                    nested_msg,
                    lineage,
                    enum_macros,
                    union_macros,
                    field_config_macros,
                    evolving_macros,
                    body_indent,
                )
            )
            lines.append("")

        for nested_union in message.nested_unions:
            lines.extend(
                self.generate_union_definition(
                    nested_union,
                    lineage,
                    body_indent,
                )
            )
            union_macros.extend(self.generate_union_macros(nested_union, lineage))
            lines.append("")

        if message.fields:
            for index, field in enumerate(message.fields):
                lines.extend(self.generate_field_accessors(field, lineage, body_indent))
                if index + 1 < len(message.fields):
                    lines.append("")
            lines.append("")

        lines.append(
            f"{body_indent}bool operator==(const {class_name}& other) const {{"
        )
        if message.fields:
            conditions = [
                self.get_field_eq_expression(field, lineage) for field in message.fields
            ]
            lines.append(f"{body_indent}  return {' && '.join(conditions)};")
        else:
            lines.append(f"{body_indent}  return true;")
        lines.append(f"{body_indent}}}")

        lines.append("")
        lines.extend(self.generate_bytes_methods(class_name, body_indent))

        struct_type_name = self.get_qualified_type_name(message.name, parent_stack)
        if message.fields:
            lines.append("")
            lines.append(f"{body_indent}private:")
            for field in message.fields:
                field_type = self.get_field_storage_type(field, lineage)
                member_name = self.get_field_member_name(field)
                lines.append(f"{field_indent}{field_type} {member_name};")
            lines.append("")
            lines.append(f"{body_indent}public:")
            field_members = ", ".join(
                self.get_field_member_name(f) for f in message.fields
            )
            field_config_type_name = self.get_field_config_type(
                message.name, parent_stack
            )
            field_config_macros.append(
                self.generate_field_config_macro(message, field_config_type_name)
            )
            lines.append(
                f"{body_indent}FORY_STRUCT({struct_type_name}, {field_members});"
            )
        else:
            lines.append(f"{body_indent}FORY_STRUCT({struct_type_name});")

        if not self.get_effective_evolving(message):
            qualified_name = self.get_namespaced_type_name(message.name, parent_stack)
            evolving_macros.append(f"FORY_STRUCT_EVOLVING({qualified_name}, false);")

        lines.append(f"{indent}}};")

        return lines

    def generate_union_definition(
        self,
        union: Union,
        parent_stack: List[Message],
        indent: str,
    ) -> List[str]:
        """Generate a C++ union class definition."""
        lines: List[str] = []
        class_name = union.name
        body_indent = f"{indent}  "

        case_enum = f"{class_name}Case"
        case_types = [
            self.get_union_case_type(field, parent_stack) for field in union.fields
        ]
        variant_type = f"std::variant<{', '.join(case_types)}>"

        comment = self.format_type_id_comment(union, f"{indent}//")
        if comment:
            lines.append(comment)
        lines.append(f"{indent}class {class_name} final {{")
        lines.append(f"{body_indent}public:")
        lines.append(f"{body_indent}  enum class {case_enum} : uint32_t {{")
        for field in union.fields:
            case_name = self.to_upper_snake_case(field.name)
            lines.append(f"{body_indent}    {case_name} = {field.number},")
        lines.append(f"{body_indent}  }};")
        lines.append("")

        lines.append(f"{body_indent}  {class_name}() = default;")
        lines.append("")

        for field, case_type in zip(union.fields, case_types):
            case_name = self.to_snake_case(field.name)
            lines.append(
                f"{body_indent}  static {class_name} {case_name}({case_type} v) {{"
            )
            lines.append(
                f"{body_indent}    return {class_name}(std::in_place_type<{case_type}>, std::move(v));"
            )
            lines.append(f"{body_indent}  }}")
            lines.append("")

        lines.append(
            f"{body_indent}  {case_enum} {self.to_snake_case(class_name)}_case() const noexcept {{"
        )
        for i, (field, case_type) in enumerate(zip(union.fields, case_types)):
            case_name = self.to_upper_snake_case(field.name)
            if i < len(case_types) - 1:
                lines.append(
                    f"{body_indent}    if (std::holds_alternative<{case_type}>(value_)) return {case_enum}::{case_name};"
                )
            else:
                lines.append(f"{body_indent}    return {case_enum}::{case_name};")
        lines.append(f"{body_indent}  }}")
        lines.append("")

        lines.append(
            f"{body_indent}  uint32_t {self.to_snake_case(class_name)}_case_id() const noexcept {{"
        )
        lines.append(
            f"{body_indent}    return static_cast<uint32_t>({self.to_snake_case(class_name)}_case());"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        lines.append(f"{body_indent}  uint32_t fory_case_id() const noexcept {{")
        lines.append(
            f"{body_indent}    return {self.to_snake_case(class_name)}_case_id();"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")

        for field, case_type in zip(union.fields, case_types):
            case_snake = self.to_snake_case(field.name)
            lines.append(f"{body_indent}  bool is_{case_snake}() const noexcept {{")
            lines.append(
                f"{body_indent}    return std::holds_alternative<{case_type}>(value_);"
            )
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(
                f"{body_indent}  const {case_type}* as_{case_snake}() const noexcept {{"
            )
            lines.append(f"{body_indent}    return std::get_if<{case_type}>(&value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  {case_type}* as_{case_snake}() noexcept {{")
            lines.append(f"{body_indent}    return std::get_if<{case_type}>(&value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  const {case_type}& {case_snake}() const {{")
            lines.append(f"{body_indent}    return std::get<{case_type}>(value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  {case_type}& {case_snake}() {{")
            lines.append(f"{body_indent}    return std::get<{case_type}>(value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")

        lines.append(f"{body_indent}  template <class Visitor>")
        lines.append(f"{body_indent}  decltype(auto) visit(Visitor&& vis) const {{")
        lines.append(
            f"{body_indent}    return std::visit(std::forward<Visitor>(vis), value_);"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        lines.append(f"{body_indent}  template <class Visitor>")
        lines.append(f"{body_indent}  decltype(auto) visit(Visitor&& vis) {{")
        lines.append(
            f"{body_indent}    return std::visit(std::forward<Visitor>(vis), value_);"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        lines.append(
            f"{body_indent}  bool operator==(const {class_name}& other) const {{"
        )
        lines.append(f"{body_indent}    return value_ == other.value_;")
        lines.append(f"{body_indent}  }}")
        lines.append("")

        lines.extend(self.generate_bytes_methods(class_name, f"{body_indent}  "))

        lines.append(f"{body_indent}private:")
        lines.append(f"{body_indent}  {variant_type} value_;")
        lines.append("")
        lines.append(f"{body_indent}  template <class T, class... Args>")
        lines.append(
            f"{body_indent}  explicit {class_name}(std::in_place_type_t<T> tag, Args&&... args)"
        )
        lines.append(
            f"{body_indent}      : value_(tag, std::forward<Args>(args)...) {{}}"
        )
        lines.append(f"{indent}}};")

        return lines

    def generate_union_macros(
        self,
        union: Union,
        parent_stack: List[Message],
    ) -> List[str]:
        """Generate FORY_UNION metadata macros for a union."""
        if not union.fields:
            return []

        lines: List[str] = []
        if len(union.fields) <= 16:
            union_type = self.get_namespaced_type_name(union.name, parent_stack)
            lines.append(f"FORY_UNION({union_type},")
            for index, field in enumerate(union.fields):
                case_type = self.generate_namespaced_type(
                    field.field_type,
                    False,
                    field.ref,
                    field.element_optional,
                    field.element_ref,
                    False,
                    False,
                    parent_stack,
                )
                case_ctor = self.to_snake_case(field.name)
                meta = self.get_union_field_meta(field)
                suffix = "," if index + 1 < len(union.fields) else ""
                lines.append(f"  ({case_type}, {case_ctor}, {meta}){suffix}")
            lines.append(");")
            return lines

        union_type = self.get_namespaced_type_name(union.name, parent_stack)
        case_ids = ", ".join(str(field.number) for field in union.fields)
        lines.append(f"FORY_UNION_IDS({union_type}, {case_ids});")
        for field in union.fields:
            case_type = self.generate_namespaced_type(
                field.field_type,
                False,
                field.ref,
                field.element_optional,
                field.element_ref,
                False,
                False,
                parent_stack,
            )
            case_ctor = self.to_snake_case(field.name)
            meta = self.get_union_field_meta(field)
            lines.append(
                f"FORY_UNION_CASE({union_type}, {field.number}, {case_type}, {union_type}::{case_ctor}, {meta});"
            )

        return lines

    def get_union_case_type(self, field: Field, parent_stack: List[Message]) -> str:
        """Return the C++ type for a union case."""
        return self.generate_type(
            field.field_type,
            False,
            field.ref,
            field.element_optional,
            field.element_ref,
            False,
            False,
            parent_stack,
        )

    def schema_has_unions(self) -> bool:
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

    def collect_union_serializers(
        self,
        message: Message,
        parent_stack: List[Message],
        union_serializers: List[str],
    ) -> None:
        """Collect serializer specializations for nested unions."""
        lineage = parent_stack + [message]
        for nested_union in message.nested_unions:
            union_serializers.extend(
                self.generate_union_serializer(nested_union, lineage)
            )
        for nested_msg in message.nested_messages:
            self.collect_union_serializers(nested_msg, lineage, union_serializers)

    def generate_union_serializer(
        self,
        union: Union,
        parent_stack: List[Message],
    ) -> List[str]:
        """Generate a C++ union serializer specialization."""
        lines: List[str] = []
        qualified_name = self.get_namespaced_type_name(union.name, parent_stack)
        case_id_method = f"{self.to_snake_case(union.name)}_case_id"
        case_types = [
            self.generate_namespaced_type(
                field.field_type,
                False,
                field.ref,
                field.element_optional,
                field.element_ref,
                False,
                False,
                parent_stack,
            )
            for field in union.fields
        ]

        if not union.fields:
            return lines

        default_field = union.fields[0]
        default_ctor = self.to_snake_case(default_field.name)
        default_type = case_types[0]

        lines.append("template <>")
        lines.append(f"struct Serializer<{qualified_name}> {{")
        lines.append("  static constexpr TypeId type_id = TypeId::UNION;")
        lines.append("")
        lines.append("  static inline void write_type_info(WriteContext &ctx) {")
        lines.append(
            f"    auto result = ctx.write_any_type_info(static_cast<uint32_t>(TypeId::TYPED_UNION), std::type_index(typeid({qualified_name})));"
        )
        lines.append("    if (FORY_PREDICT_FALSE(!result.ok())) {")
        lines.append("      ctx.set_error(std::move(result).error());")
        lines.append("    }")
        lines.append("  }")
        lines.append("")
        lines.append("  static inline void read_type_info(ReadContext &ctx) {")
        lines.append(
            f"    auto type_info_res = ctx.type_resolver().template get_type_info<{qualified_name}>();"
        )
        lines.append("    if (FORY_PREDICT_FALSE(!type_info_res.ok())) {")
        lines.append("      ctx.set_error(std::move(type_info_res).error());")
        lines.append("      return;")
        lines.append("    }")
        lines.append("    const TypeInfo *expected = type_info_res.value();")
        lines.append(
            "    const TypeInfo *remote = ctx.read_any_type_info(ctx.error());"
        )
        lines.append("    if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("      return;")
        lines.append("    }")
        lines.append("    if (!remote || remote->type_id != expected->type_id) {")
        lines.append(
            "      ctx.set_error(Error::type_mismatch(remote ? remote->type_id : 0u, expected->type_id));"
        )
        lines.append("    }")
        lines.append("  }")
        lines.append("")
        lines.append(
            f"  static inline void write(const {qualified_name} &obj, WriteContext &ctx,"
        )
        lines.append("                           RefMode ref_mode, bool write_type,")
        lines.append("                           bool has_generics = false) {")
        lines.append("    (void)has_generics;")
        lines.append("    if (ref_mode == RefMode::Tracking && ctx.track_ref()) {")
        lines.append("      ctx.write_int8(REF_VALUE_FLAG);")
        lines.append("      ctx.ref_writer().reserve_ref_id();")
        lines.append("    } else if (ref_mode != RefMode::None) {")
        lines.append("      ctx.write_int8(NOT_NULL_VALUE_FLAG);")
        lines.append("    }")
        lines.append("    if (write_type) {")
        lines.append("      write_type_info(ctx);")
        lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("        return;")
        lines.append("      }")
        lines.append("    }")
        lines.append("    write_data(obj, ctx);")
        lines.append("  }")
        lines.append("")
        lines.append(
            f"  static inline void write_data(const {qualified_name} &obj, WriteContext &ctx) {{"
        )
        lines.append(f"    ctx.write_var_uint32(obj.{case_id_method}());")
        lines.append("    obj.visit([&](const auto &value) {")
        lines.append("      using Alt = std::decay_t<decltype(value)>;")
        lines.append(
            "      Serializer<Alt>::write(value, ctx, RefMode::Tracking, true);"
        )
        lines.append("    });")
        lines.append("  }")
        lines.append("")
        lines.append(
            f"  static inline void write_data_generic(const {qualified_name} &obj, WriteContext &ctx,"
        )
        lines.append("                                      bool has_generics) {")
        lines.append("    (void)has_generics;")
        lines.append("    write_data(obj, ctx);")
        lines.append("  }")
        lines.append("")
        lines.append(
            f"  static inline {qualified_name} read(ReadContext &ctx, RefMode ref_mode,"
        )
        lines.append("                           bool read_type) {")
        lines.append("    int8_t ref_flag = NOT_NULL_VALUE_FLAG;")
        lines.append("    if (ref_mode != RefMode::None) {")
        lines.append("      ref_flag = ctx.read_int8(ctx.error());")
        lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("    }")
        lines.append("    if (ref_flag == NULL_FLAG) {")
        lines.append(
            '      ctx.set_error(Error::invalid_data("Null value encountered for union"));'
        )
        lines.append("      return default_value();")
        lines.append("    }")
        lines.append("    if (ref_flag == REF_FLAG) {")
        lines.append(
            '      ctx.set_error(Error::invalid_ref("Unexpected reference flag for union"));'
        )
        lines.append("      return default_value();")
        lines.append("    }")
        lines.append(
            "    if (ref_flag != NOT_NULL_VALUE_FLAG && ref_flag != REF_VALUE_FLAG) {"
        )
        lines.append(
            '      ctx.set_error(Error::invalid_ref("Unknown ref flag for union"));'
        )
        lines.append("      return default_value();")
        lines.append("    }")
        lines.append("    if (ctx.track_ref() && ref_flag == REF_VALUE_FLAG) {")
        lines.append("      ctx.ref_reader().reserve_ref_id();")
        lines.append("    }")
        lines.append("    if (read_type) {")
        lines.append("      read_type_info(ctx);")
        lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("    }")
        lines.append("    return read_data(ctx);")
        lines.append("  }")
        lines.append("")
        lines.append(f"  static inline {qualified_name} read_data(ReadContext &ctx) {{")
        lines.append("    uint32_t case_id = ctx.read_varuint32(ctx.error());")
        lines.append("    if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("      return default_value();")
        lines.append("    }")
        lines.append("    switch (case_id) {")
        for field, case_type in zip(union.fields, case_types):
            case_ctor = self.to_snake_case(field.name)
            lines.append(f"    case {field.number}: {{")
            lines.append(
                f"      auto value = Serializer<{case_type}>::read(ctx, RefMode::Tracking, true);"
            )
            lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
            lines.append("        return default_value();")
            lines.append("      }")
            lines.append(
                f"      return {qualified_name}::{case_ctor}(std::move(value));"
            )
            lines.append("    }")
        lines.append("    default: {")
        lines.append("      int8_t ref_flag = ctx.read_int8(ctx.error());")
        lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("      if (ref_flag == NULL_FLAG) {")
        lines.append(
            '        ctx.set_error(Error::invalid_data("Unknown union case id"));'
        )
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("      if (ref_flag == REF_FLAG) {")
        lines.append("        (void)ctx.read_varuint32(ctx.error());")
        lines.append(
            '        ctx.set_error(Error::invalid_data("Unknown union case id"));'
        )
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append(
            "      if (ref_flag != NOT_NULL_VALUE_FLAG && ref_flag != REF_VALUE_FLAG) {"
        )
        lines.append(
            '        ctx.set_error(Error::invalid_data("Unknown reference flag in union value"));'
        )
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append(
            "      const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());"
        )
        lines.append("      if (FORY_PREDICT_FALSE(ctx.has_error())) {")
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("      if (!type_info) {")
        lines.append(
            '        ctx.set_error(Error::type_error("TypeInfo not found for union skip"));'
        )
        lines.append("        return default_value();")
        lines.append("      }")
        lines.append("      FieldType field_type;")
        lines.append("      field_type.type_id = type_info->type_id;")
        lines.append("      field_type.nullable = false;")
        lines.append("      skip_field_value(ctx, field_type, RefMode::None);")
        lines.append(
            '      ctx.set_error(Error::invalid_data("Unknown union case id"));'
        )
        lines.append("      return default_value();")
        lines.append("    }")
        lines.append("    }")
        lines.append("  }")
        lines.append("")
        lines.append(
            f"  static inline {qualified_name} read_with_type_info(ReadContext &ctx, RefMode ref_mode,"
        )
        lines.append("                           const TypeInfo &type_info) {")
        lines.append("    (void)type_info;")
        lines.append("    return read(ctx, ref_mode, false);")
        lines.append("  }")
        lines.append("")
        lines.append("private:")
        lines.append(f"  static inline {qualified_name} default_value() {{")
        lines.append(
            f"    return {qualified_name}::{default_ctor}({default_type}{{}});"
        )
        lines.append("  }")
        lines.append("};")
        lines.append("")

        return lines

    def generate_namespaced_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        ref: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        weak_ref: bool = False,
        element_weak_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Generate C++ type string with package namespace."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return self.PRIMITIVE_MAP[field_type.kind]
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"std::optional<{base_type}>"
            return base_type

        if isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                namespace = self._namespace_for_type(named_type)
            else:
                namespace = self.get_namespace()
            if namespace:
                type_name = f"{namespace}::{type_name}"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                type_name = f"{wrapper}<{type_name}>"
            if nullable:
                type_name = f"std::optional<{type_name}>"
            return type_name

        if isinstance(field_type, ListType):
            element_type = self.generate_namespaced_type(
                field_type.element_type,
                element_optional,
                element_ref,
                False,
                False,
                element_weak_ref,
                False,
                parent_stack,
            )
            list_type = f"std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                list_type = f"{wrapper}<{list_type}>"
            if nullable:
                list_type = f"std::optional<{list_type}>"
            return list_type

        if isinstance(field_type, MapType):
            key_type = self.generate_namespaced_type(
                field_type.key_type, False, False, False, False, parent_stack
            )
            value_type = self.generate_namespaced_type(
                field_type.value_type,
                False,
                field_type.value_ref,
                False,
                False,
                weak_ref and field_type.value_ref,
                False,
                parent_stack,
            )
            map_type = f"std::map<{key_type}, {value_type}>"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                map_type = f"{wrapper}<{map_type}>"
            if nullable:
                map_type = f"std::optional<{map_type}>"
            return map_type

        return "void*"

    def generate_field_config_macro(
        self,
        message: Message,
        qualified_name: str,
    ) -> str:
        """Generate FORY_FIELD_CONFIG macro for a message."""
        entries = []
        for field in message.fields:
            field_name = self.get_field_member_name(field)
            meta = self.get_field_meta(field)
            entries.append(f"({field_name}, {meta})")
        joined = ", ".join(entries)
        return f"FORY_FIELD_CONFIG({qualified_name}, {joined});"

    def get_field_meta(self, field: Field) -> str:
        """Build FieldMeta expression for a field."""
        meta = "fory::F()"
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        if field.tag_id is not None:
            meta += f".id({field.tag_id})"
        if field.optional or is_any:
            meta += ".nullable()"
        if field.ref:
            meta += ".ref()"
        encoding = self.get_encoding_config(field.field_type)
        if encoding:
            meta += encoding
        array_type = self.get_array_type_config(field)
        if array_type:
            meta += array_type
        return meta

    def get_union_field_meta(self, field: Field) -> str:
        """Build FieldMeta expression for a union case."""
        meta = f"fory::F({field.number})"
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        if field.optional or is_any:
            meta += ".nullable()"
        if field.ref:
            meta += ".ref()"
        encoding = self.get_encoding_config(field.field_type)
        if encoding:
            meta += encoding
        array_type = self.get_array_type_config(field)
        if array_type:
            meta += array_type
        return meta

    def get_encoding_config(self, field_type: FieldType) -> str:
        """Return encoding config for primitive types."""
        kind = None
        if isinstance(field_type, PrimitiveType):
            kind = field_type.kind
        elif isinstance(field_type, ListType) and isinstance(
            field_type.element_type, PrimitiveType
        ):
            kind = field_type.element_type.kind
        if kind is None:
            return ""
        if kind in (
            PrimitiveKind.INT32,
            PrimitiveKind.INT64,
            PrimitiveKind.UINT32,
            PrimitiveKind.UINT64,
        ):
            return ".fixed()"
        if kind in (PrimitiveKind.VAR_UINT32, PrimitiveKind.VAR_UINT64):
            return ".varint()"
        if kind in (PrimitiveKind.TAGGED_INT64, PrimitiveKind.TAGGED_UINT64):
            return ".tagged()"
        return ""

    def get_array_type_config(self, field: Field) -> str:
        """Return array type override for int8/uint8 arrays."""
        if not isinstance(field.field_type, ListType):
            return ""
        if field.element_optional or field.element_ref:
            return ""
        element_type = field.field_type.element_type
        if not isinstance(element_type, PrimitiveType):
            return ""
        if element_type.kind == PrimitiveKind.INT8:
            return ".int8_array()"
        if element_type.kind == PrimitiveKind.UINT8:
            return ".uint8_array()"
        return ""

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        ref: bool = False,
        element_optional: bool = False,
        element_ref: bool = False,
        weak_ref: bool = False,
        element_weak_ref: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Generate C++ type string."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return self.PRIMITIVE_MAP[field_type.kind]
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"std::optional<{base_type}>"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                ns = self._namespace_for_type(named_type)
                if ns:
                    type_name = f"{ns}::{type_name}"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                type_name = f"{wrapper}<{type_name}>"
            if nullable:
                type_name = f"std::optional<{type_name}>"
            return type_name

        elif isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                element_optional,
                element_ref,
                False,
                False,
                element_weak_ref,
                False,
                parent_stack,
            )
            list_type = f"std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                list_type = f"{wrapper}<{list_type}>"
            if nullable:
                list_type = f"std::optional<{list_type}>"
            return list_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type, False, False, False, False, parent_stack
            )
            value_type = self.generate_type(
                field_type.value_type,
                False,
                field_type.value_ref,
                False,
                False,
                weak_ref and field_type.value_ref,
                False,
                parent_stack,
            )
            map_type = f"std::map<{key_type}, {value_type}>"
            if ref:
                wrapper = (
                    "fory::serialization::SharedWeak" if weak_ref else "std::shared_ptr"
                )
                map_type = f"{wrapper}<{map_type}>"
            if nullable:
                map_type = f"std::optional<{map_type}>"
            return map_type

        return "void*"

    def get_qualified_type_name(
        self,
        type_name: str,
        parent_stack: List[Message],
    ) -> str:
        """Get the C++ qualified type name for nested types."""
        if not parent_stack:
            return type_name
        prefix = "::".join(parent.name for parent in parent_stack)
        return f"{prefix}::{type_name}"

    def get_registration_type_name(
        self,
        type_name: str,
        parent_stack: List[Message],
    ) -> str:
        """Get the dot-qualified type name used in registration."""
        if not parent_stack:
            return type_name
        prefix = ".".join(parent.name for parent in parent_stack)
        return f"{prefix}.{type_name}"

    def resolve_nested_type_name(
        self,
        type_name: str,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Resolve nested type names to qualified C++ identifiers."""
        if "." in type_name:
            return type_name.replace(".", "::")
        if not parent_stack:
            return type_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                prefix = "::".join(parent.name for parent in parent_stack[: i + 1])
                return f"{prefix}::{type_name}"

        return type_name

    def collect_includes(
        self,
        field_type: FieldType,
        nullable: bool,
        ref: bool,
        includes: Set[str],
        element_optional: bool = False,
        element_ref: bool = False,
        weak_ref: bool = False,
        element_weak_ref: bool = False,
    ):
        """Collect required includes for a field type."""
        if nullable:
            includes.add("<optional>")
        if ref:
            includes.add("<memory>")
        if weak_ref:
            includes.add('"fory/serialization/weak_ptr_serializer.h"')

        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.STRING:
                includes.add("<string>")
            elif field_type.kind == PrimitiveKind.BYTES:
                includes.add("<vector>")
            elif field_type.kind in (PrimitiveKind.DATE, PrimitiveKind.TIMESTAMP):
                includes.add('"fory/serialization/temporal_serializers.h"')
            elif field_type.kind == PrimitiveKind.ANY:
                includes.add("<any>")

        elif isinstance(field_type, ListType):
            includes.add("<vector>")
            self.collect_includes(
                field_type.element_type,
                element_optional,
                element_ref,
                includes,
                False,
                False,
                element_weak_ref,
                False,
            )

        elif isinstance(field_type, MapType):
            includes.add("<map>")
            if field_type.value_ref:
                includes.add("<memory>")
                if weak_ref:
                    includes.add('"fory/serialization/weak_ptr_serializer.h"')
            self.collect_includes(field_type.key_type, False, False, includes)
            self.collect_includes(
                field_type.value_type,
                False,
                field_type.value_ref,
                includes,
                False,
                False,
                weak_ref and field_type.value_ref,
                False,
            )

        elif isinstance(field_type, NamedType):
            named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                header = self._header_for_type(named_type)
                if header:
                    includes.add(f'"{header}"')

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        lines.append(
            "inline void register_types(fory::serialization::BaseFory& fory) {"
        )

        # Register enums (top-level)
        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            self.generate_enum_registration(lines, enum, [])

        # Register unions (top-level)
        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            self.generate_union_registration(lines, union, [])

        # Register messages (including nested types)
        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            self.generate_message_registration(lines, message, [])

        lines.append("}")

        lines.append("")
        lines.append("namespace detail {")
        lines.append("inline fory::serialization::ThreadSafeFory& get_fory() {")
        lines.append(
            "  static fory::serialization::ThreadSafeFory fory = "
            "fory::serialization::Fory::builder().xlang(true).track_ref(true)"
            ".compatible(true).build_thread_safe();"
        )
        lines.append("  static const bool initialized = []() {")
        for ns in self._collect_imported_namespaces():
            lines.append(f"    {ns}::register_types(fory);")
        lines.append("    register_types(fory);")
        lines.append("    return true;")
        lines.append("  }();")
        lines.append("  (void)initialized;")
        lines.append("  return fory;")
        lines.append("}")
        lines.append("} // namespace detail")

        return lines

    def generate_enum_registration(
        self, lines: List[str], enum: Enum, parent_stack: List[Message]
    ):
        """Generate registration code for an enum."""
        code_name = self.get_qualified_type_name(enum.name, parent_stack)
        type_name = self.get_registration_type_name(enum.name, parent_stack)

        if self.should_register_by_id(enum):
            lines.append(f"    fory.register_enum<{code_name}>({enum.type_id});")
        else:
            ns = self.package or "default"
            lines.append(f'    fory.register_enum<{code_name}>("{ns}", "{type_name}");')

    def generate_message_registration(
        self, lines: List[str], message: Message, parent_stack: List[Message]
    ):
        """Generate registration code for a message and its nested types."""
        code_name = self.get_qualified_type_name(message.name, parent_stack)
        type_name = self.get_registration_type_name(message.name, parent_stack)

        # Register nested enums first
        for nested_enum in message.nested_enums:
            self.generate_enum_registration(
                lines, nested_enum, parent_stack + [message]
            )

        for nested_union in message.nested_unions:
            self.generate_union_registration(
                lines, nested_union, parent_stack + [message]
            )

        # Register nested messages recursively
        for nested_msg in message.nested_messages:
            self.generate_message_registration(
                lines, nested_msg, parent_stack + [message]
            )

        # Register this message
        if self.should_register_by_id(message):
            lines.append(f"    fory.register_struct<{code_name}>({message.type_id});")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_struct<{code_name}>("{ns}", "{type_name}");'
            )

    def generate_union_registration(
        self, lines: List[str], union: Union, parent_stack: List[Message]
    ):
        """Generate registration code for a union."""
        code_name = self.get_qualified_type_name(union.name, parent_stack)
        type_name = self.get_registration_type_name(union.name, parent_stack)

        if self.should_register_by_id(union):
            lines.append(f"    fory.register_union<{code_name}>({union.type_id});")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_union<{code_name}>("{ns}", "{type_name}");'
            )
