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
    ArrayType,
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
        PrimitiveKind.INT8: "::int8_t",
        PrimitiveKind.INT16: "::int16_t",
        PrimitiveKind.INT32: "::int32_t",
        PrimitiveKind.INT64: "::int64_t",
        PrimitiveKind.UINT8: "::uint8_t",
        PrimitiveKind.UINT16: "::uint16_t",
        PrimitiveKind.UINT32: "::uint32_t",
        PrimitiveKind.UINT64: "::uint64_t",
        PrimitiveKind.FLOAT16: "::fory::float16_t",
        PrimitiveKind.BFLOAT16: "::fory::bfloat16_t",
        PrimitiveKind.FLOAT32: "float",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "::std::string",
        PrimitiveKind.BYTES: "::std::vector<::uint8_t>",
        PrimitiveKind.DECIMAL: "::fory::serialization::Decimal",
        PrimitiveKind.DATE: "::fory::serialization::Date",
        PrimitiveKind.TIMESTAMP: "::fory::serialization::Timestamp",
        PrimitiveKind.DURATION: "::fory::serialization::Duration",
        PrimitiveKind.ANY: "::std::any",
    }
    NUMERIC_PRIMITIVES = {
        PrimitiveKind.BOOL,
        PrimitiveKind.INT8,
        PrimitiveKind.INT16,
        PrimitiveKind.INT32,
        PrimitiveKind.INT64,
        PrimitiveKind.UINT8,
        PrimitiveKind.UINT16,
        PrimitiveKind.UINT32,
        PrimitiveKind.UINT64,
        PrimitiveKind.FLOAT16,
        PrimitiveKind.BFLOAT16,
        PrimitiveKind.FLOAT32,
        PrimitiveKind.FLOAT64,
    }
    # Taken from kKeywordList defined in helpers.cc in protobuf C++ compiler.
    CPP_KEYWORDS = {
        "NULL",
        "alignas",
        "alignof",
        "and",
        "and_eq",
        "asm",
        "assert",
        "auto",
        "bitand",
        "bitor",
        "bool",
        "break",
        "case",
        "catch",
        "char",
        "class",
        "compl",
        "const",
        "constexpr",
        "const_cast",
        "continue",
        "decltype",
        "default",
        "delete",
        "do",
        "double",
        "dynamic_cast",
        "else",
        "enum",
        "explicit",
        "export",
        "extern",
        "false",
        "float",
        "for",
        "friend",
        "goto",
        "if",
        "inline",
        "int",
        "long",
        "mutable",
        "namespace",
        "new",
        "noexcept",
        "not",
        "not_eq",
        "nullptr",
        "operator",
        "or",
        "or_eq",
        "private",
        "protected",
        "public",
        "register",
        "reinterpret_cast",
        "return",
        "short",
        "signed",
        "sizeof",
        "static",
        "static_assert",
        "static_cast",
        "struct",
        "switch",
        "template",
        "this",
        "thread_local",
        "throw",
        "true",
        "try",
        "typedef",
        "typeid",
        "typename",
        "union",
        "unsigned",
        "using",
        "virtual",
        "void",
        "volatile",
        "wchar_t",
        "while",
        "xor",
        "xor_eq",
        "char8_t",
        "char16_t",
        "char32_t",
        "concept",
        "consteval",
        "constinit",
        "co_await",
        "co_return",
        "co_yield",
        "requires",
    }
    # In C++, a non-static data member and a member function cannot have the same unqualified name within the same class.
    MESSAGE_HELPER_RESERVED_NAMES = {"to_bytes", "from_bytes"}
    UNION_HELPER_RESERVED_NAMES = {
        "visit",
        "to_bytes",
        "from_bytes",
        "fory_case_id",
        "value_",
    }
    TOP_LEVEL_TYPE_RESERVED_NAMES = {"detail", "std", "fory", "register_types"}
    # Generated headers expose these names or namespaces directly at global
    # scope. They cannot be used for a no-package type or the first package
    # segment, both of which are emitted at global scope.
    GLOBAL_NAMESPACE_RESERVED_NAMES = {
        "abs",
        "acos",
        "acosh",
        "asin",
        "asinh",
        "atan",
        "atan2",
        "atanh",
        "cbrt",
        "ceil",
        "copysign",
        "cos",
        "cosh",
        "erf",
        "erfc",
        "exp",
        "exp2",
        "expm1",
        "fabs",
        "fdim",
        "floor",
        "fma",
        "fmax",
        "fmin",
        "fmod",
        "fory",
        "frexp",
        "hypot",
        "ilogb",
        "int8_t",
        "int16_t",
        "int32_t",
        "int64_t",
        "int_fast8_t",
        "int_fast16_t",
        "int_fast32_t",
        "int_fast64_t",
        "int_least8_t",
        "int_least16_t",
        "int_least32_t",
        "int_least64_t",
        "intmax_t",
        "intptr_t",
        "ldexp",
        "lgamma",
        "log",
        "log10",
        "log1p",
        "log2",
        "logb",
        "max_align_t",
        "modf",
        "nearbyint",
        "nextafter",
        "nexttoward",
        "nullptr_t",
        "pow",
        "ptrdiff_t",
        "remainder",
        "remquo",
        "rint",
        "round",
        "scalbln",
        "scalbn",
        "sin",
        "sinh",
        "size_t",
        "sqrt",
        "std",
        "tan",
        "tanh",
        "tgamma",
        "trunc",
        "uint8_t",
        "uint16_t",
        "uint32_t",
        "uint64_t",
        "uint_fast8_t",
        "uint_fast16_t",
        "uint_fast32_t",
        "uint_fast64_t",
        "uint_least8_t",
        "uint_least16_t",
        "uint_least32_t",
        "uint_least64_t",
        "uintmax_t",
        "uintptr_t",
    }
    # Fory object-like macros. They expand in every identifier position,
    # so IDL identifiers should not collide with them.
    FORY_OBJECT_LIKE_MACRO_NAMES = {
        "FORY_ALWAYS_INLINE",
        "FORY_BYTE_SWAP32",
        "FORY_BYTE_SWAP64",
        "FORY_FILE_LINE",
        "FORY_HAS_IMMINTRIN",
        "FORY_HAS_NEON",
        "FORY_HAS_RISCV_VECTOR",
        "FORY_HAS_SSE2",
        "FORY_LITTLE_ENDIAN",
        "FORY_NOINLINE",
        "FORY_NORETURN",
        "FORY_PP_IS_BASE_TAG_PROBE_FORY_BASE_TAG",
        "FORY_PP_IS_EMPTY_CASE_0001",
        "FORY_PP_IS_PROPERTY_TAG_PROBE_FORY_PROPERTY_TAG",
        "FORY_PP_NOT_0",
        "FORY_PP_NOT_1",
        "FORY_PRETTY_FUNCTION",
        "FORY_TARGET_AVX2_ATTR",
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
        return self._namespace_for_package(self.package)

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

    def sanitize_cpp_identifier(self, normalized: str) -> str:
        """Escape an already-normalized C++ identifier."""
        if (
            normalized in self.CPP_KEYWORDS
            or normalized in self.FORY_OBJECT_LIKE_MACRO_NAMES
        ):
            return f"{normalized}_"
        return normalized

    def get_type_identifier(self, type_def: object) -> str:
        """Get the sanitized identifier for a type declaration or reference."""
        self._ensure_name_caches(self._schema_for_node(type_def))
        return self._type_identifier_cache[self._cache_key(type_def)]

    def get_field_identifier(self, message: Message, field: Field) -> str:
        """Get the sanitized accessor base for a field in one message."""
        self._ensure_name_caches(self._schema_for_node(message))
        return self._field_identifier_cache[self._cache_key(message)][
            self._cache_key(field)
        ]

    def get_field_member_name(self, message: Message, field: Field) -> str:
        """Get the sanitized private member name for a field in one message."""
        self._ensure_name_caches(self._schema_for_node(message))
        return self._field_member_identifier_cache[self._cache_key(message)][
            self._cache_key(field)
        ]

    def get_enum_value_identifier(self, enum: Enum, value: object) -> str:
        """Get the sanitized value name for an enum constant."""
        self._ensure_name_caches(self._schema_for_node(enum))
        return self._enum_value_identifier_cache[self._cache_key(enum)][
            self._cache_key(value)
        ]

    def get_union_case_identifier(self, union: Union, field: Field) -> str:
        """Get the sanitized method base for one union case."""
        self._ensure_name_caches(self._schema_for_node(union))
        return self._union_case_identifier_cache[self._cache_key(union)][
            self._cache_key(field)
        ]

    def get_union_case_enum_value_identifier(self, union: Union, field: Field) -> str:
        """Get the sanitized enum value name for one union case."""
        self._ensure_name_caches(self._schema_for_node(union))
        return self._union_case_enum_value_identifier_cache[self._cache_key(union)][
            self._cache_key(field)
        ]

    def _cache_key(self, node: object) -> Tuple[object, ...]:
        """Get a cache key for an IR node."""
        # Use the location as the key due to its stability.
        location = node.location
        return (
            type(node).__name__,
            str(Path(location.file).resolve()),
            location.line,
            location.column,
        )

    def _namespace_for_package(self, package: Optional[str]) -> str:
        """Get the sanitized C++ namespace for a package."""
        if not package:
            return ""
        if not hasattr(self, "_namespace_identifier_cache"):
            self._namespace_identifier_cache: Dict[Optional[str], str] = {}
        if package not in self._namespace_identifier_cache:
            parts = []
            for index, part in enumerate(package.split(".")):
                identifier = self.sanitize_cpp_identifier(part)
                if index == 0 and identifier in self.GLOBAL_NAMESPACE_RESERVED_NAMES:
                    identifier = f"{identifier}_"
                parts.append(identifier)
            self._namespace_identifier_cache[package] = "::".join(parts)
        return self._namespace_identifier_cache[package]

    def _package_for_source_file(self, file_path: str) -> Optional[str]:
        """Get the package name that a file declares."""
        source_key = str(Path(file_path).resolve())
        schema_source_key = str(Path(self.schema.source_file).resolve())
        # `file_path` is the self schema file.
        if source_key == schema_source_key:
            return self.schema.package
        # `file_path` corresponds to an imported schema file.
        return self.schema.source_packages[source_key]

    def _schema_for_node(self, node: object) -> Schema:
        """Get the schema an IR node belongs to."""
        file_path = node.location.file
        source_key = str(Path(file_path).resolve())
        # `node` belongs to the self schema.
        if source_key == str(Path(self.schema.source_file).resolve()):
            return self.schema
        # `node` belongs to an imported schema.
        if not hasattr(self, "_source_schema_cache"):
            self._source_schema_cache: Dict[str, Schema] = {}
        if source_key in self._source_schema_cache:
            return self._source_schema_cache[source_key]
        enums = [
            enum
            for enum in self.schema.enums
            if str(Path(enum.location.file).resolve()) == source_key
        ]
        unions = [
            union
            for union in self.schema.unions
            if str(Path(union.location.file).resolve()) == source_key
        ]
        messages = [
            message
            for message in self.schema.messages
            if str(Path(message.location.file).resolve()) == source_key
        ]
        if enums or unions or messages:
            schema = Schema(
                package=self._package_for_source_file(file_path),
                enums=enums,
                messages=messages,
                unions=unions,
                source_file=file_path,
                source_format=self.schema.source_format,
            )
            self._source_schema_cache[source_key] = schema
            return schema
        raise ValueError(
            f"C++ generator cannot find source schema for "
            f"{type(node).__name__} {getattr(node, 'name', '<unnamed>')!r}"
        )

    def _local_top_level_types(
        self, schema: Schema
    ) -> Tuple[List[Enum], List[Union], List[Message]]:
        """Get top-level types that are declared directly in the schema file."""
        schema_source_key = str(Path(schema.source_file).resolve())
        enums = [
            enum
            for enum in schema.enums
            if str(Path(enum.location.file).resolve()) == schema_source_key
        ]
        unions = [
            union
            for union in schema.unions
            if str(Path(union.location.file).resolve()) == schema_source_key
        ]
        messages = [
            message
            for message in schema.messages
            if str(Path(message.location.file).resolve()) == schema_source_key
        ]
        return enums, unions, messages

    def _resolve_message_path(self, schema: Schema, parts: List[str]) -> List[Message]:
        """Resolve a dotted message path to the concrete message lineage."""
        lineage: List[Message] = []
        scope = self._local_top_level_types(schema)[2]
        for part in parts:
            match = next((message for message in scope if message.name == part), None)
            if match is None:
                return []
            lineage.append(match)
            scope = match.nested_messages
        return lineage

    def _allocate_scoped_identifier(
        self,
        normalized_name: str,
        used_names: Dict[str, str],
        scope: str,
        source_name: str,
        reserved_names: Optional[Set[str]] = None,
    ) -> str:
        """Allocate one sanitized identifier inside a single generated scope. Throw error on collision"""
        escaped = self.sanitize_cpp_identifier(normalized_name)
        if reserved_names and escaped in reserved_names:
            escaped = f"{escaped}_"
        previous_source = used_names.get(escaped)
        if previous_source is not None:
            raise ValueError(
                f"C++ name collision in {scope}: {previous_source!r} and "
                f"{source_name!r} both map to C++ identifier {escaped!r}"
            )
        used_names[escaped] = source_name
        return escaped

    def _allocate_scoped_type_identifiers(
        self,
        type_defs: List[object],
        scope: str,
        reserved_names: Optional[Set[str]] = None,
    ) -> None:
        """Allocate unique sanitized identifiers for type declarations in the scope and cache the results."""
        used_names: Dict[str, str] = {}
        scope_reserved_names = reserved_names or set()
        for type_def in type_defs:
            type_reserved_names = scope_reserved_names
            if isinstance(type_def, Message):
                type_reserved_names = (
                    scope_reserved_names | self.MESSAGE_HELPER_RESERVED_NAMES
                )
            elif isinstance(type_def, Union):
                type_reserved_names = (
                    scope_reserved_names | self.UNION_HELPER_RESERVED_NAMES
                )
            self._type_identifier_cache[self._cache_key(type_def)] = (
                self._allocate_scoped_identifier(
                    type_def.name,  # Unlike Rust, there is no universal naming conventions (e.g. PascalCase or snake_case) for C++. So pass as it is.
                    used_names,
                    scope,
                    type_def.name,
                    type_reserved_names,
                )
            )

    def _visible_type_identifiers(
        self, schema: Schema, parent_stack: List[Message]
    ) -> Set[str]:
        """Get generated type identifiers visible from the current class scope."""
        enums, unions, messages = self._local_top_level_types(schema)
        type_defs: List[object] = list(enums) + list(unions) + list(messages)
        for message in parent_stack:
            type_defs.extend(message.nested_enums)
            type_defs.extend(message.nested_unions)
            type_defs.extend(message.nested_messages)
        names: Set[str] = set()
        for type_def in type_defs:
            names.add(self._type_identifier_cache[self._cache_key(type_def)])
        return names

    def _field_generated_member_names(
        self,
        field: Field,
        parent_stack: List[Message],
        field_name: str,
        member_name: str,
    ) -> Set[str]:
        """Get every class member name generated for one field."""
        # C++ fields generate a public accessor family plus a private data member
        # in the same class scope, so all derived names must be checked together.
        # Rust has no equivalent helper because it emits the field identifier as a
        # struct field directly instead of generating per-field accessors.
        names = {field_name, member_name}
        weak_ref = self.get_field_weak_ref(field)
        is_message = self.is_message_type(field.field_type, parent_stack)
        if is_message and (field.ref or weak_ref):
            names.update(
                {
                    f"has_{field_name}",
                    f"mutable_{field_name}",
                    f"set_{field_name}",
                    f"clear_{field_name}",
                }
            )
            return names
        if is_message:
            names.update(
                {
                    f"has_{field_name}",
                    f"mutable_{field_name}",
                    f"clear_{field_name}",
                }
            )
            return names
        if field.optional:
            names.add(f"has_{field_name}")
        is_union = self.is_union_type(field.field_type, parent_stack)
        is_collection = isinstance(field.field_type, (ListType, ArrayType, MapType))
        is_bytes = self.is_bytes_field(field)
        is_string = self.is_string_field(field)
        if is_string or is_collection or is_bytes or is_union:
            names.add(f"mutable_{field_name}")
        if not (is_collection or is_bytes or is_union):
            names.add(f"set_{field_name}")
        if field.optional:
            names.add(f"clear_{field_name}")
        return names

    def _allocate_scoped_message_identifiers(
        self, message: Message, parent_stack: Optional[List[Message]] = None
    ) -> None:
        """Allocate all scoped names that belong to the message."""
        lineage = (parent_stack or []) + [message]
        nested_types: List[object] = (
            list(message.nested_enums)
            + list(message.nested_unions)
            + list(message.nested_messages)
        )
        self._allocate_scoped_type_identifiers(
            nested_types,
            f"message {message.name} types",
        )
        fixed_members = (
            self.MESSAGE_HELPER_RESERVED_NAMES
            | self._visible_type_identifiers(self._schema_for_node(message), lineage)
        )
        used_members: Dict[str, str] = {
            name: f"generated helper {name!r}" for name in fixed_members
        }
        field_names: Dict[Tuple[object, ...], str] = {}
        field_members: Dict[Tuple[object, ...], str] = {}
        for field in message.fields:
            field_name = self.sanitize_cpp_identifier(self.to_snake_case(field.name))
            while (
                self._field_generated_member_names(
                    field, lineage, field_name, f"{field_name}_"
                )
                & fixed_members
            ):
                field_name = f"{field_name}_"  # Keep adding a `_` suffix until this is a fresh identifier.
            member_name = f"{field_name}_"
            generated_names = self._field_generated_member_names(
                field, lineage, field_name, member_name
            )
            for generated_name in generated_names:
                previous_source = used_members.get(generated_name)
                if previous_source is not None:
                    raise ValueError(
                        f"C++ name collision in message {message.name} members: "
                        f"{previous_source!r} and {field.name!r} both map to "
                        f"C++ identifier {generated_name!r}"
                    )
            for generated_name in generated_names:
                used_members[generated_name] = field.name
            field_names[self._cache_key(field)] = field_name
            field_members[self._cache_key(field)] = member_name
        self._field_identifier_cache[self._cache_key(message)] = field_names
        self._field_member_identifier_cache[self._cache_key(message)] = field_members
        for nested_enum in message.nested_enums:
            self._allocate_scoped_enum_identifiers(nested_enum)
        for nested_union in message.nested_unions:
            self._allocate_scoped_union_identifiers(nested_union, lineage)
        for nested_message in message.nested_messages:
            self._allocate_scoped_message_identifiers(nested_message, lineage)

    def _allocate_scoped_enum_identifiers(self, enum: Enum) -> None:
        """Allocate all scoped names that belong to the enum."""
        used_names: Dict[str, str] = {}
        allocated: Dict[Tuple[object, ...], str] = {}
        for value in enum.values:
            allocated[self._cache_key(value)] = self._allocate_scoped_identifier(
                self.strip_enum_prefix(enum.name, value.name),
                used_names,
                f"enum {enum.name}",
                value.name,
            )
        self._enum_value_identifier_cache[self._cache_key(enum)] = allocated

    def _allocate_scoped_union_identifiers(
        self, union: Union, parent_stack: Optional[List[Message]] = None
    ) -> None:
        """Allocate all scoped names that belong to the union."""
        lineage = parent_stack or []
        used_case_values: Dict[str, str] = {}
        case_values: Dict[Tuple[object, ...], str] = {}
        for field in union.fields:
            case_values[self._cache_key(field)] = self._allocate_scoped_identifier(
                self.to_upper_snake_case(field.name),
                used_case_values,
                f"union {union.name} case enum",
                field.name,
            )
        self._union_case_enum_value_identifier_cache[self._cache_key(union)] = (
            case_values
        )
        selector_base = self._get_union_selector_base(union)
        fixed_members = (
            self.UNION_HELPER_RESERVED_NAMES
            | {
                f"{selector_base}_case",
                f"{selector_base}_case_id",
            }
            | self._visible_type_identifiers(self._schema_for_node(union), lineage)
        )
        used_members: Dict[str, str] = {
            name: f"generated helper {name!r}" for name in fixed_members
        }
        case_names: Dict[Tuple[object, ...], str] = {}
        for field in union.fields:
            case_name = self.sanitize_cpp_identifier(self.to_snake_case(field.name))
            while {case_name, f"is_{case_name}", f"as_{case_name}"} & fixed_members:
                case_name = f"{case_name}_"  # Keep adding a `_` suffix until this is a fresh identifier.
            generated_names = {case_name, f"is_{case_name}", f"as_{case_name}"}
            for generated_name in generated_names:
                previous_source = used_members.get(generated_name)
                if previous_source is not None:
                    raise ValueError(
                        f"C++ name collision in union {union.name} members: "
                        f"{previous_source!r} and {field.name!r} both map to "
                        f"C++ identifier {generated_name!r}"
                    )
            for generated_name in generated_names:
                used_members[generated_name] = field.name
            case_names[self._cache_key(field)] = case_name
        self._union_case_identifier_cache[self._cache_key(union)] = case_names

    def _get_union_selector_base(self, union: Union) -> str:
        """Get the union-specific case selector base without helper collisions."""
        selector_base = self.sanitize_cpp_identifier(self.to_snake_case(union.name))
        selector_names = {f"{selector_base}_case", f"{selector_base}_case_id"}
        if selector_names & self.UNION_HELPER_RESERVED_NAMES:
            selector_base = f"{selector_base}_"
        return selector_base

    def _ensure_name_caches(self, schema: Schema) -> None:
        """Construct the naming caches once for a schema file."""
        if not hasattr(self, "_named_schema_ids"):
            # Init everything.
            self._named_schema_ids: Set[int] = set()
            self._type_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._field_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._field_member_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._enum_value_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._union_case_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._union_case_enum_value_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
        schema_id = id(schema)
        if schema_id in self._named_schema_ids:
            return
        enums, unions, messages = self._local_top_level_types(schema)
        top_level_reserved_names = set(self.TOP_LEVEL_TYPE_RESERVED_NAMES)
        if not schema.package:
            top_level_reserved_names.update(self.GLOBAL_NAMESPACE_RESERVED_NAMES)
        self._allocate_scoped_type_identifiers(
            list(enums) + list(unions) + list(messages),
            "top-level C++ types",
            top_level_reserved_names,
        )
        for enum in enums:
            self._allocate_scoped_enum_identifiers(enum)
        for union in unions:
            self._allocate_scoped_union_identifiers(union, [])
        for message in messages:
            self._allocate_scoped_message_identifiers(message)
        self._named_schema_ids.add(schema_id)

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
        return self._namespace_for_package(schema.package)

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
        namespace = self.get_namespace()
        detail = f"::{namespace}::detail" if namespace else "::detail"
        lines.append(
            f"{indent}::fory::Result<::std::vector<::uint8_t>, ::fory::Error> to_bytes() const {{"
        )
        lines.append(f"{indent}  return {detail}::get_fory().serialize(*this);")
        lines.append(f"{indent}}}")
        lines.append("")
        lines.append(
            f"{indent}static ::fory::Result<{class_name}, ::fory::Error> from_bytes(const ::std::vector<::uint8_t>& data) {{"
        )
        lines.append(
            f"{indent}  return {detail}::get_fory().deserialize<{class_name}>(data);"
        )
        lines.append(f"{indent}}}")
        return lines

    def generate_header(self) -> GeneratedFile:
        """Generate a C++ header file with all types."""
        lines = []
        includes: Set[str] = set()
        enum_macros: List[str] = []
        union_macros: List[str] = []
        evolving_macros: List[str] = []
        definition_items = self.get_definition_order()
        self._ensure_name_caches(self.schema)

        # Collect includes (including from nested types)
        includes.add("<cstdint>")
        includes.add("<memory>")
        includes.add("<string>")
        includes.add("<unordered_map>")
        includes.add("<vector>")
        includes.add("<utility>")
        includes.add('"fory/serialization/fory.h"')
        if self.schema_has_unions():
            includes.add("<cstddef>")  # todo: what's this??
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
        lines.append("::fory::serialization::ThreadSafeFory& get_fory();")
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
                    evolving_macros,
                    "",
                )
            )
            lines.append("")

        if union_macros:
            lines.extend(union_macros)
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
            lines.append(f"class {self.get_type_identifier(message)};")

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
        if isinstance(field_type, ArrayType):
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
        value_names = []
        for value in enum.values:
            value_names.append(self.get_enum_value_identifier(enum, value))
        return value_names

    def generate_enum_definition(self, enum: Enum, indent: str = "") -> List[str]:
        """Generate a C++ enum class definition."""
        lines = []
        type_name = self.get_type_identifier(enum)
        lines.append(f"{indent}enum class {type_name} : ::int32_t {{")
        for value in enum.values:
            value_name = self.get_enum_value_identifier(enum, value)
            lines.append(f"{indent}    {value_name} = {value.value},")
        lines.append(f"{indent}}};")
        return lines

    def generate_enum_macro(
        self,
        enum: Enum,
        parent_stack: List[Message],
    ) -> str:
        """Generate a FORY_ENUM macro line for an enum."""
        value_names = ", ".join(self.get_enum_value_names(enum))
        qualified_name = f"::{self.get_namespaced_type_name(enum.name, parent_stack)}"
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
            return f"::std::unique_ptr<{type_name}>"
        return self.generate_type(
            field.field_type,
            (
                False
                if (
                    self.is_message_type(field.field_type, parent_stack)
                    and (field.ref or weak_ref)
                )
                else field.optional
            ),
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
        message = parent_stack[-1]
        member_name = self.get_field_member_name(message, field)
        other_member = f"other.{member_name}"
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

    def message_has_any(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, int]]] = None,
    ) -> bool:
        if visiting is None:
            visiting = set()
        key = ("message", id(message))
        if key in visiting:
            return False
        visiting.add(key)
        try:
            lineage = (parent_stack or []) + [message]
            return any(
                self.field_type_has_any(field.field_type, lineage, visiting)
                for field in message.fields
            )
        finally:
            visiting.remove(key)

    def union_has_any(
        self,
        union: Union,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, int]]] = None,
    ) -> bool:
        if visiting is None:
            visiting = set()
        key = ("union", id(union))
        if key in visiting:
            return False
        visiting.add(key)
        try:
            return any(
                self.field_type_has_any(field.field_type, parent_stack, visiting)
                for field in union.fields
            )
        finally:
            visiting.remove(key)

    def field_type_has_any(
        self,
        field_type: FieldType,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, int]]] = None,
    ) -> bool:
        """Return True when a field type or its children contain `any`."""
        if isinstance(field_type, PrimitiveType):
            return field_type.kind == PrimitiveKind.ANY
        if isinstance(field_type, ListType):
            return self.field_type_has_any(
                field_type.element_type, parent_stack, visiting
            )
        if isinstance(field_type, ArrayType):
            return self.field_type_has_any(
                field_type.element_type, parent_stack, visiting
            )
        if isinstance(field_type, MapType):
            # `any` is not allowed as map key (rejected first by the validator),
            # so we only check map value here.
            return self.field_type_has_any(
                field_type.value_type, parent_stack, visiting
            )
        if isinstance(field_type, NamedType):
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if isinstance(named_type, Message):
                return self.message_has_any(
                    named_type, self._parent_stack_for_type(named_type), visiting
                )
            if isinstance(named_type, Union):
                return self.union_has_any(
                    named_type, self._parent_stack_for_type(named_type), visiting
                )
        return False

    def _parent_stack_for_type(self, type_def: object) -> List[Message]:
        def visit(message: Message, parents: List[Message]) -> Optional[List[Message]]:
            if message is type_def:
                return parents
            for nested_union in message.nested_unions:
                if nested_union is type_def:
                    return parents + [message]
            for nested_enum in message.nested_enums:
                if nested_enum is type_def:
                    return parents + [message]
            for nested_message in message.nested_messages:
                found = visit(nested_message, parents + [message])
                if found is not None:
                    return found
            return None

        for top in self.schema.messages:
            found = visit(top, [])
            if found is not None:
                return found
        return []

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
        message = parent_stack[-1]
        field_name = self.get_field_identifier(message, field)
        member_name = self.get_field_member_name(message, field)
        value_type = self.get_field_value_type(field, parent_stack)
        weak_ref = self.get_field_weak_ref(field)
        is_union = self.is_union_type(field.field_type, parent_stack)
        is_enum = self.is_enum_type(field.field_type, parent_stack)
        is_collection = isinstance(field.field_type, (ListType, ArrayType, MapType))
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
            lines.append(f"{indent}  {member_name} = ::std::move(value);")
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
                f"{indent}    {member_name} = ::std::make_unique<{value_type}>();"
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
                        f"{indent}  {member_name}.emplace(::std::forward<Arg>(arg), ::std::forward<Args>(args)...);"
                    )
                else:
                    lines.append(
                        f"{indent}  {member_name} = {value_type}(::std::forward<Arg>(arg), ::std::forward<Args>(args)...);"
                    )
                lines.append(f"{indent}}}")
            else:
                lines.append(f"{indent}void set_{field_name}({value_type} value) {{")
                lines.append(f"{indent}  {member_name} = ::std::move(value);")
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
        evolving_macros: List[str],
        indent: str,
    ) -> List[str]:
        """Generate a C++ class definition with nested types."""
        lines: List[str] = []
        class_name = self.get_type_identifier(message)
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

        # We don't generate equality method for message containing `any`
        # since C++ doesn't support std::any == std::any.
        if not self.message_has_any(message, parent_stack):
            lines.append(
                f"{body_indent}bool operator==(const {class_name}& other) const {{"
            )
            if message.fields:
                conditions = [
                    self.get_field_eq_expression(field, lineage)
                    for field in message.fields
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
                member_name = self.get_field_member_name(message, field)
                lines.append(f"{field_indent}{field_type} {member_name};")
            lines.append("")
            lines.append(f"{body_indent}public:")
            field_members = ", ".join(
                self.get_field_macro_entry(message, f) for f in message.fields
            )
            lines.append(
                f"{body_indent}FORY_STRUCT({struct_type_name}, {field_members});"
            )
        else:
            lines.append(f"{body_indent}FORY_STRUCT({struct_type_name});")

        if not self.get_effective_evolving(message):
            qualified_name = (
                f"::{self.get_namespaced_type_name(message.name, parent_stack)}"
            )
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
        class_name = self.get_type_identifier(union)
        body_indent = f"{indent}  "
        selector_base = self._get_union_selector_base(union)
        macro_guard = class_name.startswith("FORY_")
        # Unlike object-like macros that expand everywhere, a function-like macro expands only when followed by `(`.
        # So a union class name can co-exist with a same-named function-like macro.
        # Union class names appear in constructor and factory declarations,
        # so temporarily hide a same-named macro while emitting the class.
        # e.g. IDL is like
        # union FORY_CHECK {
        #   string text = 1
        # }
        # but Fory already has `#define FORY_CHECK(condition)`.
        if macro_guard:
            lines.append(f'{indent}#pragma push_macro("{class_name}")')
            lines.append(f"{indent}#undef {class_name}")

        case_enum = f"{class_name}Case"
        raw_case_types = [
            self.get_union_case_type(field, parent_stack) for field in union.fields
        ]
        case_aliases = [
            f"ForyCase{self.to_pascal_case(field.name)}Type"
            if "," in case_type
            else None
            for field, case_type in zip(union.fields, raw_case_types)
        ]
        case_types = [
            alias if alias is not None else case_type
            for alias, case_type in zip(case_aliases, raw_case_types)
        ]
        variant_type = f"::std::variant<{', '.join(case_types)}>"

        comment = self.format_type_id_comment(union, f"{indent}//")
        if comment:
            lines.append(comment)
        lines.append(f"{indent}class {class_name} final {{")
        lines.append(f"{body_indent}public:")
        lines.append(f"{body_indent}  enum class {case_enum} : ::uint32_t {{")
        for field in union.fields:
            case_name = self.get_union_case_enum_value_identifier(union, field)
            lines.append(f"{body_indent}    {case_name} = {field.number},")
        lines.append(f"{body_indent}  }};")
        lines.append("")

        for alias, case_type in zip(case_aliases, raw_case_types):
            if alias is not None:
                lines.append(f"{body_indent}  using {alias} = {case_type};")
        if any(alias is not None for alias in case_aliases):
            lines.append("")

        lines.append(f"{body_indent}  {class_name}() = default;")
        lines.append("")

        for index, (field, case_type) in enumerate(zip(union.fields, case_types)):
            case_name = self.get_union_case_identifier(union, field)
            lines.append(
                f"{body_indent}  static {class_name} {case_name}({case_type} v) {{"
            )
            # We used to generate `std::in_place_type<T>`, but consider this IDL:
            # union Foo {
            #   int32 first = 1;
            #   int32 second = 2;
            # }
            # `std::in_place_type<T>` fails when two union cases map to the
            # same C++ type because `std::variant<T, T>` cannot select a unique T.
            # The case index always identifies one alternative, so
            # `std::in_place_index<I>` supports duplicate payload types.
            lines.append(
                f"{body_indent}    return {class_name}(::std::in_place_index<{index}>, ::std::move(v));"
            )
            lines.append(f"{body_indent}  }}")
            lines.append("")

        lines.append(
            f"{body_indent}  {case_enum} {selector_base}_case() const noexcept {{"
        )
        lines.append(f"{body_indent}    switch (value_.index()) {{")
        for index, field in enumerate(union.fields):
            case_name = self.get_union_case_enum_value_identifier(union, field)
            lines.append(
                f"{body_indent}      case {index}: return {case_enum}::{case_name};"
            )
        default_case = self.get_union_case_enum_value_identifier(union, union.fields[0])
        lines.append(f"{body_indent}      default: return {case_enum}::{default_case};")
        lines.append(f"{body_indent}    }}")
        lines.append(f"{body_indent}  }}")
        lines.append("")

        lines.append(
            f"{body_indent}  ::uint32_t {selector_base}_case_id() const noexcept {{"
        )
        lines.append(
            f"{body_indent}    return static_cast<::uint32_t>({selector_base}_case());"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        lines.append(f"{body_indent}  ::uint32_t fory_case_id() const noexcept {{")
        lines.append(f"{body_indent}    return {selector_base}_case_id();")
        lines.append(f"{body_indent}  }}")
        lines.append("")

        for index, (field, case_type) in enumerate(zip(union.fields, case_types)):
            case_snake = self.get_union_case_identifier(union, field)
            lines.append(f"{body_indent}  bool is_{case_snake}() const noexcept {{")
            lines.append(f"{body_indent}    return value_.index() == {index};")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(
                f"{body_indent}  const {case_type}* as_{case_snake}() const noexcept {{"
            )
            lines.append(f"{body_indent}    return ::std::get_if<{index}>(&value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  {case_type}* as_{case_snake}() noexcept {{")
            lines.append(f"{body_indent}    return ::std::get_if<{index}>(&value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  const {case_type}& {case_snake}() const {{")
            lines.append(f"{body_indent}    return ::std::get<{index}>(value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")
            lines.append(f"{body_indent}  {case_type}& {case_snake}() {{")
            lines.append(f"{body_indent}    return ::std::get<{index}>(value_);")
            lines.append(f"{body_indent}  }}")
            lines.append("")

        lines.append(f"{body_indent}  template <class Visitor>")
        lines.append(f"{body_indent}  decltype(auto) visit(Visitor&& vis) const {{")
        lines.append(
            f"{body_indent}    return ::std::visit(::std::forward<Visitor>(vis), value_);"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        lines.append(f"{body_indent}  template <class Visitor>")
        lines.append(f"{body_indent}  decltype(auto) visit(Visitor&& vis) {{")
        lines.append(
            f"{body_indent}    return ::std::visit(::std::forward<Visitor>(vis), value_);"
        )
        lines.append(f"{body_indent}  }}")
        lines.append("")
        # We don't generate equality method for union containing `any`
        # since C++ doesn't support std::any == std::any.
        if not self.union_has_any(union, parent_stack):
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
        lines.append(f"{body_indent}  template <::std::size_t I, class... Args>")
        lines.append(
            f"{body_indent}  explicit {class_name}(::std::in_place_index_t<I> tag, Args&&... args)"
        )
        lines.append(
            f"{body_indent}      : value_(tag, ::std::forward<Args>(args)...) {{}}"
        )
        lines.append(f"{indent}}};")
        if macro_guard:
            lines.append(f'{indent}#pragma pop_macro("{class_name}")')

        return lines

    def _append_union_metadata_macro(
        self,
        lines: List[str],
        union: Union,
        macro_name: str,
        macro_lines: List[str],
    ) -> None:
        """Append one union metadata macro with an exact same-name guard."""
        class_name = self.get_type_identifier(union)
        macro_guard = class_name.startswith("FORY_") and class_name != macro_name
        if macro_guard:
            lines.append(f'#pragma push_macro("{class_name}")')
            lines.append(f"#undef {class_name}")
        lines.extend(macro_lines)
        if macro_guard:
            lines.append(f'#pragma pop_macro("{class_name}")')

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
            union_type = f"::{self.get_namespaced_type_name(union.name, parent_stack)}"
            macro_lines = [f"FORY_UNION({union_type},"]
            for index, field in enumerate(union.fields):
                case_type = self.get_union_case_macro_type(
                    field, union_type, parent_stack
                )
                case_ctor = self.get_union_case_identifier(union, field)
                meta = self.get_union_field_meta(field)
                suffix = "," if index + 1 < len(union.fields) else ""
                macro_lines.append(f"  ({case_ctor}, {case_type}, {meta}){suffix}")
            macro_lines.append(");")
            self._append_union_metadata_macro(lines, union, "FORY_UNION", macro_lines)
            return lines

        union_type = f"::{self.get_namespaced_type_name(union.name, parent_stack)}"
        case_ids = ", ".join(str(field.number) for field in union.fields)
        self._append_union_metadata_macro(
            lines,
            union,
            "FORY_UNION_IDS",
            [f"FORY_UNION_IDS({union_type}, {case_ids});"],
        )
        for field in union.fields:
            case_type = self.get_union_case_macro_type(field, union_type, parent_stack)
            case_ctor = self.get_union_case_identifier(union, field)
            meta = self.get_union_field_meta(field)
            self._append_union_metadata_macro(
                lines,
                union,
                "FORY_UNION_CASE",
                [
                    f"FORY_UNION_CASE({union_type}, {field.number}, {case_type}, {union_type}::{case_ctor}, {meta});"
                ],
            )

        return lines

    def get_union_case_macro_type(
        self,
        field: Field,
        union_type: str,
        parent_stack: List[Message],
    ) -> str:
        """Return the C++ type name used in FORY_UNION and FORY_UNION_CASE macros."""
        case_type = self.generate_namespaced_type(
            field.field_type,
            False,
            field.ref,
            field.element_optional,
            field.element_ref,
            False,
            False,
            parent_stack,
            True,
        )
        # FORY_UNION and FORY_UNION_CASE split macro arguments on commas,
        # so raw template types such as std::unordered_map<K, V> need an alias.
        if "," in case_type:
            return f"{union_type}::ForyCase{self.to_pascal_case(field.name)}Type"
        return case_type

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
        default_ctor = self.get_union_case_identifier(union, default_field)
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
            case_ctor = self.get_union_case_identifier(union, field)
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
        global_qualify: bool = False,
    ) -> str:
        """Generate C++ type string with package namespace."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return self.PRIMITIVE_MAP[field_type.kind]
            base_type = self.PRIMITIVE_MAP[field_type.kind]
            if nullable:
                return f"::std::optional<{base_type}>"
            return base_type

        if isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if named_type is None:
                named_type = self.schema.get_type(field_type.name)
            imported = named_type is not None and self.is_imported_type(named_type)
            if imported:
                namespace = self._namespace_for_type(named_type)
            else:
                namespace = self.get_namespace()
            if imported:
                type_name = (
                    f"::{namespace}::{type_name}" if namespace else f"::{type_name}"
                )
            elif namespace:
                prefix = f"::{namespace}" if global_qualify else namespace
                type_name = f"{prefix}::{type_name}"
            elif global_qualify:
                type_name = f"::{type_name}"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                type_name = f"{wrapper}<{type_name}>"
            if nullable:
                type_name = f"::std::optional<{type_name}>"
            return type_name

        if isinstance(field_type, ListType):
            effective_element_optional = element_optional or field_type.element_optional
            effective_element_ref = element_ref or field_type.element_ref
            effective_element_weak_ref = element_weak_ref
            if field_type.element_ref:
                effective_element_weak_ref = self.is_weak_ref(
                    field_type.element_ref_options
                )
            element_type = self.generate_namespaced_type(
                field_type.element_type,
                effective_element_optional,
                effective_element_ref,
                False,
                False,
                effective_element_weak_ref,
                False,
                parent_stack,
                global_qualify,
            )
            list_type = f"::std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                list_type = f"{wrapper}<{list_type}>"
            if nullable:
                list_type = f"::std::optional<{list_type}>"
            return list_type

        if isinstance(field_type, ArrayType):
            element_type = self.generate_namespaced_type(
                field_type.element_type,
                False,
                False,
                False,
                False,
                False,
                False,
                parent_stack,
                global_qualify,
            )
            array_type = f"::std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                array_type = f"{wrapper}<{array_type}>"
            if nullable:
                array_type = f"::std::optional<{array_type}>"
            return array_type

        if isinstance(field_type, MapType):
            key_type = self.generate_namespaced_type(
                field_type.key_type,
                False,
                False,
                False,
                False,
                False,
                False,
                parent_stack,
                global_qualify,
            )
            value_weak_ref = (
                self.is_weak_ref(field_type.value_ref_options)
                if field_type.value_ref
                else False
            )
            value_type = self.generate_namespaced_type(
                field_type.value_type,
                False,
                field_type.value_ref,
                False,
                False,
                value_weak_ref,
                False,
                parent_stack,
                global_qualify,
            )
            map_type = f"::std::unordered_map<{key_type}, {value_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                map_type = f"{wrapper}<{map_type}>"
            if nullable:
                map_type = f"::std::optional<{map_type}>"
            return map_type

        return "void*"

    def get_field_macro_entry(self, message: Message, field: Field) -> str:
        """Build one FORY_STRUCT field entry."""
        return (
            f"({self.get_field_member_name(message, field)}, "
            f"{self.get_field_meta(field)})"
        )

    def get_field_meta(self, field: Field) -> str:
        """Build FieldMeta expression for a field."""
        if field.tag_id is not None:
            meta = f"::fory::F({field.tag_id})"
        else:
            meta = "::fory::F()"
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        if field.optional or is_any:
            meta += ".nullable()"
        if field.ref:
            meta += ".ref()"
        spec = self.get_field_type_spec(field)
        if spec:
            if field.optional or field.ref:
                meta += f".inner({spec})"
            elif spec.startswith("::fory::T::list("):
                meta += f".list({spec[len('::fory::T::list(') : -1]})"
            elif spec.startswith("::fory::T::array("):
                meta += f".array({spec[len('::fory::T::array(') : -1]})"
            elif spec.startswith("::fory::T::set("):
                meta += f".set({spec[len('::fory::T::set(') : -1]})"
            elif spec.startswith("::fory::T::map("):
                meta += f".map({spec[len('::fory::T::map(') : -1]})"
            elif spec.endswith(".fixed()"):
                meta += ".fixed()"
            elif spec.endswith(".varint()"):
                meta += ".varint()"
            elif spec.endswith(".tagged()"):
                meta += ".tagged()"
        return meta

    def get_union_field_meta(self, field: Field) -> str:
        """Build FieldMeta expression for a union case."""
        meta = f"::fory::F({field.number})"
        is_any = (
            isinstance(field.field_type, PrimitiveType)
            and field.field_type.kind == PrimitiveKind.ANY
        )
        if field.optional or is_any:
            meta += ".nullable()"
        if field.ref:
            meta += ".ref()"
        spec = self.get_field_type_spec(field)
        if spec:
            if field.optional or field.ref:
                meta += f".inner({spec})"
            elif spec.startswith("::fory::T::list("):
                meta += f".list({spec[len('::fory::T::list(') : -1]})"
            elif spec.startswith("::fory::T::array("):
                meta += f".array({spec[len('::fory::T::array(') : -1]})"
            elif spec.startswith("::fory::T::set("):
                meta += f".set({spec[len('::fory::T::set(') : -1]})"
            elif spec.startswith("::fory::T::map("):
                meta += f".map({spec[len('::fory::T::map(') : -1]})"
            elif spec.endswith(".fixed()"):
                meta += ".fixed()"
            elif spec.endswith(".varint()"):
                meta += ".varint()"
            elif spec.endswith(".tagged()"):
                meta += ".tagged()"
        return meta

    def get_field_type_spec(self, field: Field) -> str:
        """Return the T-node spec for a field when generated metadata needs it."""
        return self.get_type_spec(
            field.field_type,
            field.element_optional,
            field.element_ref,
        )

    def get_type_spec(
        self,
        field_type: FieldType,
        optional: bool = False,
        ref: bool = False,
    ) -> str:
        """Return a recursive ::fory::T spec for generated C++ metadata."""
        if isinstance(field_type, PrimitiveType):
            spec = self.get_primitive_type_spec(field_type)
        elif isinstance(field_type, ListType):
            element = self.get_type_spec(
                field_type.element_type,
                field_type.element_optional,
                field_type.element_ref,
            )
            if not element:
                element = "::fory::FieldNodeSpec{}"
            spec = f"::fory::T::list({element})"
        elif isinstance(field_type, ArrayType):
            element = self.get_array_element_type_spec(field_type.element_type)
            if not element:
                element = "::fory::FieldNodeSpec{}"
            spec = f"::fory::T::array({element})"
        elif isinstance(field_type, MapType):
            key = self.get_type_spec(field_type.key_type)
            element = self.get_type_spec(
                field_type.value_type,
                field_type.value_optional,
                field_type.value_ref,
            )
            if key or element:
                if not key:
                    key = "::fory::FieldNodeSpec{}"
                if not element:
                    element = "::fory::FieldNodeSpec{}"
                spec = f"::fory::T::map({key}, {element})"
            else:
                spec = ""
        else:
            spec = ""

        if (optional or ref) and spec:
            return f"::fory::T::inner({spec})"
        return spec

    def get_array_element_type_spec(self, field_type: FieldType) -> str:
        if not isinstance(field_type, PrimitiveType):
            return self.get_type_spec(field_type)
        typed = {
            PrimitiveKind.BOOL: "::fory::T::boolean()",
            PrimitiveKind.INT8: "::fory::T::int8()",
            PrimitiveKind.INT16: "::fory::T::int16()",
            PrimitiveKind.INT32: "::fory::T::int32()",
            PrimitiveKind.INT64: "::fory::T::int64()",
            PrimitiveKind.UINT8: "::fory::T::uint8()",
            PrimitiveKind.UINT16: "::fory::T::uint16()",
            PrimitiveKind.UINT32: "::fory::T::uint32()",
            PrimitiveKind.UINT64: "::fory::T::uint64()",
            PrimitiveKind.FLOAT16: "::fory::T::float16()",
            PrimitiveKind.BFLOAT16: "::fory::T::bfloat16()",
            PrimitiveKind.FLOAT32: "::fory::T::float32()",
            PrimitiveKind.FLOAT64: "::fory::T::float64()",
        }
        return typed.get(field_type.kind, "")

    def get_primitive_type_spec(self, field_type: PrimitiveType) -> str:
        """Return a scalar T-node spec for primitive encoding metadata."""
        kind = field_type.kind
        typed = {
            PrimitiveKind.BOOL: "::fory::T::boolean()",
            PrimitiveKind.INT8: "::fory::T::int8()",
            PrimitiveKind.INT16: "::fory::T::int16()",
            PrimitiveKind.UINT8: "::fory::T::uint8()",
            PrimitiveKind.UINT16: "::fory::T::uint16()",
            PrimitiveKind.FLOAT16: "::fory::T::float16()",
            PrimitiveKind.BFLOAT16: "::fory::T::bfloat16()",
            PrimitiveKind.FLOAT32: "::fory::T::float32()",
            PrimitiveKind.FLOAT64: "::fory::T::float64()",
            PrimitiveKind.STRING: "::fory::T::string()",
        }
        if kind in (
            PrimitiveKind.INT32,
            PrimitiveKind.INT64,
            PrimitiveKind.UINT32,
            PrimitiveKind.UINT64,
        ):
            base = {
                PrimitiveKind.INT32: "::fory::T::int32()",
                PrimitiveKind.INT64: "::fory::T::int64()",
                PrimitiveKind.UINT32: "::fory::T::uint32()",
                PrimitiveKind.UINT64: "::fory::T::uint64()",
            }[kind]
            encoding = field_type.encoding_modifier or "varint"
            if encoding == "fixed":
                return f"{base}.fixed()"
            if encoding == "tagged":
                return f"{base}.tagged()"
            return f"{base}.varint()"
        return typed.get(kind, "")

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
                return f"::std::optional<{base_type}>"
            return base_type

        elif isinstance(field_type, NamedType):
            type_name = self.resolve_nested_type_name(field_type.name, parent_stack)
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if named_type is None:
                named_type = self.schema.get_type(field_type.name)
            if named_type is not None and self.is_imported_type(named_type):
                ns = self._namespace_for_type(named_type)
                type_name = f"::{ns}::{type_name}" if ns else f"::{type_name}"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                type_name = f"{wrapper}<{type_name}>"
            if nullable:
                type_name = f"::std::optional<{type_name}>"
            return type_name

        elif isinstance(field_type, ListType):
            effective_element_optional = element_optional or field_type.element_optional
            effective_element_ref = element_ref or field_type.element_ref
            effective_element_weak_ref = element_weak_ref
            if field_type.element_ref:
                effective_element_weak_ref = self.is_weak_ref(
                    field_type.element_ref_options
                )
            element_type = self.generate_type(
                field_type.element_type,
                effective_element_optional,
                effective_element_ref,
                False,
                False,
                effective_element_weak_ref,
                False,
                parent_stack,
            )
            list_type = f"::std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                list_type = f"{wrapper}<{list_type}>"
            if nullable:
                list_type = f"::std::optional<{list_type}>"
            return list_type

        elif isinstance(field_type, ArrayType):
            element_type = self.generate_type(
                field_type.element_type,
                False,
                False,
                False,
                False,
                False,
                False,
                parent_stack,
            )
            array_type = f"::std::vector<{element_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                array_type = f"{wrapper}<{array_type}>"
            if nullable:
                array_type = f"::std::optional<{array_type}>"
            return array_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type, False, False, False, False, parent_stack
            )
            value_weak_ref = (
                self.is_weak_ref(field_type.value_ref_options)
                if field_type.value_ref
                else False
            )
            value_type = self.generate_type(
                field_type.value_type,
                False,
                field_type.value_ref,
                False,
                False,
                value_weak_ref,
                False,
                parent_stack,
            )
            map_type = f"::std::unordered_map<{key_type}, {value_type}>"
            if ref:
                wrapper = (
                    "::fory::serialization::SharedWeak"
                    if weak_ref
                    else "::std::shared_ptr"
                )
                map_type = f"{wrapper}<{map_type}>"
            if nullable:
                map_type = f"::std::optional<{map_type}>"
            return map_type

        return "void*"

    def get_qualified_type_name(
        self,
        type_name: str,
        parent_stack: List[Message],
    ) -> str:
        """Get the C++ qualified type name for nested types."""
        parts = [self.get_type_identifier(parent) for parent in parent_stack]
        type_def = None
        if parent_stack:
            type_def = parent_stack[-1].get_nested_type(type_name)
        else:
            type_def = self.find_top_level_type(type_name)
        if type_def is not None:
            parts.append(self.get_type_identifier(type_def))
        else:
            parts.append(self.sanitize_cpp_identifier(type_name))
        return "::".join(parts)

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
        named_type = self.resolve_named_type(type_name, parent_stack)
        if named_type is None:
            named_type = self.schema.get_type(type_name)
        if named_type is None:
            return "::".join(
                self.sanitize_cpp_identifier(part) for part in type_name.split(".")
            )

        type_path = self.schema.resolve_type_name(type_name)
        if "." in type_path:
            parts = type_path.split(".")
            schema = self._schema_for_node(named_type)
            parent_messages = self._resolve_message_path(schema, parts[:-1])
            if parent_messages:
                parents = [
                    self.get_type_identifier(parent) for parent in parent_messages
                ]
            else:
                parents = [self.sanitize_cpp_identifier(part) for part in parts[:-1]]
            return "::".join(parents + [self.get_type_identifier(named_type)])

        resolved_name = self.get_type_identifier(named_type)
        if not parent_stack:
            return resolved_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                prefix = "::".join(
                    self.get_type_identifier(parent) for parent in parent_stack[: i + 1]
                )
                return f"{prefix}::{resolved_name}"

        return resolved_name

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
            elif field_type.kind == PrimitiveKind.DECIMAL:
                includes.add('"fory/serialization/decimal_serializers.h"')
            elif field_type.kind in (
                PrimitiveKind.DATE,
                PrimitiveKind.TIMESTAMP,
                PrimitiveKind.DURATION,
            ):
                includes.add('"fory/serialization/temporal_serializers.h"')
            elif field_type.kind == PrimitiveKind.ANY:
                includes.add("<any>")
            elif field_type.kind == PrimitiveKind.FLOAT16:
                includes.add('"fory/util/float16.h"')
            elif field_type.kind == PrimitiveKind.BFLOAT16:
                includes.add('"fory/util/bfloat16.h"')

        elif isinstance(field_type, ListType):
            includes.add("<vector>")
            effective_element_optional = element_optional or field_type.element_optional
            effective_element_ref = element_ref or field_type.element_ref
            effective_element_weak_ref = element_weak_ref
            if field_type.element_ref:
                effective_element_weak_ref = self.is_weak_ref(
                    field_type.element_ref_options
                )
            self.collect_includes(
                field_type.element_type,
                effective_element_optional,
                effective_element_ref,
                includes,
                False,
                False,
                effective_element_weak_ref,
                False,
            )

        elif isinstance(field_type, ArrayType):
            includes.add("<vector>")
            self.collect_includes(
                field_type.element_type,
                False,
                False,
                includes,
                False,
                False,
                False,
                False,
            )

        elif isinstance(field_type, MapType):
            includes.add("<unordered_map>")
            value_weak_ref = (
                self.is_weak_ref(field_type.value_ref_options)
                if field_type.value_ref
                else False
            )
            if field_type.value_ref:
                includes.add("<memory>")
                if value_weak_ref:
                    includes.add('"fory/serialization/weak_ptr_serializer.h"')
            self.collect_includes(field_type.key_type, False, False, includes)
            self.collect_includes(
                field_type.value_type,
                False,
                field_type.value_ref,
                includes,
                False,
                False,
                value_weak_ref,
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
            "inline void register_types(::fory::serialization::BaseFory& fory) {"
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
        lines.append("inline ::fory::serialization::ThreadSafeFory& get_fory() {")
        lines.append(
            "  static ::fory::serialization::ThreadSafeFory fory = "
            "::fory::serialization::Fory::builder().xlang(true).track_ref(true)"
            ".compatible(true).build_thread_safe();"
        )
        lines.append("  static const bool initialized = []() {")
        for ns in self._collect_imported_namespaces():
            lines.append(f"    ::{ns}::register_types(fory);")
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
