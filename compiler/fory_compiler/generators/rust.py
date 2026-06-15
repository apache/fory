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

from fory_compiler.generators.services.rust import RustServiceGeneratorMixin
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
    thread_safe_pointer_enabled,
)
from fory_compiler.ir.types import PrimitiveKind


class RustGenerator(RustServiceGeneratorMixin, BaseGenerator):
    """Generates Rust types with Fory derive macros."""

    language_name = "rust"
    file_extension = ".rs"
    RUST_ANY_TYPE = "::std::sync::Arc<dyn ::std::any::Any + Send + Sync>"

    # Mapping from FDL primitive types to Rust types
    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "i8",
        PrimitiveKind.INT16: "i16",
        PrimitiveKind.INT32: "i32",
        PrimitiveKind.INT64: "i64",
        PrimitiveKind.UINT8: "u8",
        PrimitiveKind.UINT16: "u16",
        PrimitiveKind.UINT32: "u32",
        PrimitiveKind.UINT64: "u64",
        PrimitiveKind.FLOAT16: "::fory::Float16",
        PrimitiveKind.BFLOAT16: "::fory::BFloat16",
        PrimitiveKind.FLOAT32: "f32",
        PrimitiveKind.FLOAT64: "f64",
        PrimitiveKind.STRING: "::std::string::String",
        PrimitiveKind.BYTES: "::std::vec::Vec<u8>",
        PrimitiveKind.DECIMAL: "::fory::Decimal",
        PrimitiveKind.ANY: RUST_ANY_TYPE,
    }

    FORY_TEMPORAL_MAP = {
        PrimitiveKind.DATE: "::fory::Date",
        PrimitiveKind.TIMESTAMP: "::fory::Timestamp",
        PrimitiveKind.DURATION: "::fory::Duration",
    }

    CHRONO_TEMPORAL_MAP = {
        PrimitiveKind.DATE: "::chrono::NaiveDate",
        PrimitiveKind.TIMESTAMP: "::chrono::NaiveDateTime",
        PrimitiveKind.DURATION: "::chrono::Duration",
    }

    def use_chrono_temporal_types(self) -> bool:
        return self.schema.get_option("rust_use_chrono_temporal_types") is True

    def primitive_type_name(self, kind: PrimitiveKind) -> str:
        if kind in self.FORY_TEMPORAL_MAP:
            temporal_map = (
                self.CHRONO_TEMPORAL_MAP
                if self.use_chrono_temporal_types()
                else self.FORY_TEMPORAL_MAP
            )
            return temporal_map[kind]
        return self.PRIMITIVE_MAP[kind]

    # Strict and reserved keywords defined in Rust (https://doc.rust-lang.org/reference/keywords.html).
    # Weak keywords are intentionally excluded because they are usable outside their special syntax contexts.
    RUST_RAW_IDENTIFIER_KEYWORDS = {
        "as",
        "async",
        "await",
        "abstract",
        "become",
        "box",
        "break",
        "const",
        "continue",
        "do",
        "dyn",
        "else",
        "enum",
        "extern",
        "false",
        "final",
        "fn",
        "for",
        "gen",
        "if",
        "impl",
        "in",
        "let",
        "loop",
        "macro",
        "match",
        "mod",
        "move",
        "mut",
        "override",
        "priv",
        "pub",
        "ref",
        "return",
        "static",
        "struct",
        "trait",
        "true",
        "try",
        "type",
        "typeof",
        "unsafe",
        "unsized",
        "use",
        "virtual",
        "where",
        "while",
        "yield",
    }

    # Reserved identifiers in Rust (https://doc.rust-lang.org/reference/identifiers.html#railroad-RESERVED_RAW_IDENTIFIER).
    # These tokens are invalid even with an `r#` prefix, so escape them by suffixing `_` instead.
    RUST_RESERVED_IDENTIFIERS = {"_", "self", "Self", "super", "crate"}

    def sanitize_identifier(self, normalized: str) -> str:
        """Escape an already-normalized Rust name."""
        if normalized in self.RUST_RESERVED_IDENTIFIERS:
            return f"{normalized}_"
        if normalized and normalized[0].isnumeric():
            return f"_{normalized}"  # Rust identifiers cannot start with a digit.
        if normalized in self.RUST_RAW_IDENTIFIER_KEYWORDS:
            return f"r#{normalized}"
        return normalized

    def to_rust_snake(self, source: str) -> str:
        """Convert an IDL name to a sanitized Rust snake_case identifier."""
        return self.sanitize_identifier(self.to_snake_case(source))

    def get_top_level_module_identifier(self, package: Optional[str]) -> str:
        """Get the Rust module identifier used to reference one schema file."""
        # e.g. `foo.bar` defined in the IDL will be `foo_bar` in the generated Rust code.
        module_name = package.replace(".", "_") if package else "generated"
        return self.to_rust_snake(module_name)

    def get_type_identifier(self, type_def: object) -> str:
        """Get the sanitized identifier for a type declaration or reference from the cache."""
        self._ensure_name_caches(self._schema_for_node(type_def))
        return self._type_identifier_cache[self._cache_key(type_def)]

    def get_module_identifier(self, message: Message) -> str:
        """Get the sanitized module name for a message's nested-type scope from the cache."""
        self._ensure_name_caches(self._schema_for_node(message))
        return self._module_identifier_cache[self._cache_key(message)]

    def get_field_identifier(self, message: Message, field: Field) -> str:
        """Get the sanitized field name within one message from the cache."""
        self._ensure_name_caches(self._schema_for_node(message))
        return self._field_identifier_cache[self._cache_key(message)][
            self._cache_key(field)
        ]

    def get_union_case_identifier(self, union: Union, field: Field) -> str:
        """Get the sanitized variant name for one union case from the cache"""
        self._ensure_name_caches(self._schema_for_node(union))
        return self._union_case_identifier_cache[self._cache_key(union)][
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
        services = [
            service
            for service in self.schema.services
            if str(Path(service.location.file).resolve()) == source_key
        ]
        if enums or unions or messages or services:
            schema = Schema(
                package=self._package_for_source_file(file_path),
                enums=enums,
                messages=messages,
                unions=unions,
                services=services,
                source_file=file_path,
                source_format=self.schema.source_format,
            )
            self._source_schema_cache[source_key] = schema
            return schema
        raise ValueError(
            f"Rust generator cannot find source schema for "
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
    ) -> str:
        """Allocate one sanitized identifier inside a single generated scope. Throw error on collision"""
        escaped = self.sanitize_identifier(normalized_name)
        if not escaped:
            raise ValueError(f"Rust identifier for {source_name!r} in {scope} is empty")
        previous_source = used_names.get(escaped)
        if previous_source is not None:
            raise ValueError(
                f"Rust name collision in {scope}: {previous_source!r} and "
                f"{source_name!r} both map to Rust identifier {escaped!r}"
            )
        used_names[escaped] = source_name
        return escaped

    def _allocate_scoped_type_identifiers(
        self, type_defs: List[object], scope: str
    ) -> None:
        """Allocate unique sanitized identifiers for type declarations in the scope and cache the results."""
        used_names: Dict[str, str] = {}
        for type_def in type_defs:
            self._type_identifier_cache[self._cache_key(type_def)] = (
                self._allocate_scoped_identifier(
                    self.to_pascal_case(type_def.name),
                    used_names,
                    scope,
                    type_def.name,
                )
            )

    def _allocate_scoped_module_identifiers(
        self, messages: List[Message], scope: str
    ) -> None:
        """Allocate unique sanitized identifiers for nested-type modules in the scope and cache the results."""
        used_names: Dict[str, str] = {}
        for message in messages:
            self._module_identifier_cache[self._cache_key(message)] = (
                self._allocate_scoped_identifier(
                    self.to_snake_case(message.name),
                    used_names,
                    scope,
                    message.name,
                )
            )

    def _allocate_scoped_enum_identifiers(self, enum: Enum) -> None:
        """Allocate unique sanitized variant names for the generated enum and cache the results."""
        used_names: Dict[str, str] = {}
        allocated: Dict[Tuple[object, ...], str] = {}
        for value in enum.values:
            allocated[self._cache_key(value)] = self._allocate_scoped_identifier(
                self.to_pascal_case(self.strip_enum_prefix(enum.name, value.name)),
                used_names,
                f"enum {enum.name}",
                value.name,
            )
        self._enum_value_identifier_cache[self._cache_key(enum)] = allocated

    def _allocate_scoped_union_identifiers(self, union: Union) -> None:
        """Allocate unique sanitized variant names for the generated union and cache the results."""
        used_names: Dict[str, str] = {}
        allocated: Dict[Tuple[object, ...], str] = {}
        for field in union.fields:
            allocated[self._cache_key(field)] = self._allocate_scoped_identifier(
                self.to_pascal_case(field.name),
                used_names,
                f"union {union.name}",
                field.name,
            )
        self._union_case_identifier_cache[self._cache_key(union)] = allocated

    def _allocate_scoped_message_identifiers(self, message: Message) -> None:
        """Allocate all scoped names that belong to the message."""
        used_fields: Dict[str, str] = {}
        field_names: Dict[Tuple[object, ...], str] = {}
        for field in message.fields:
            field_names[self._cache_key(field)] = self._allocate_scoped_identifier(
                self.to_snake_case(field.name),
                used_fields,
                f"message {message.name} fields",
                field.name,
            )
        self._field_identifier_cache[self._cache_key(message)] = field_names
        nested_types: List[object] = (
            list(message.nested_enums)
            + list(message.nested_unions)
            + list(message.nested_messages)
        )
        self._allocate_scoped_type_identifiers(
            nested_types, f"message {message.name} types"
        )
        self._allocate_scoped_module_identifiers(
            list(message.nested_messages), f"message {message.name} modules"
        )
        for nested_enum in message.nested_enums:
            self._allocate_scoped_enum_identifiers(nested_enum)
        for nested_union in message.nested_unions:
            self._allocate_scoped_union_identifiers(nested_union)
        for nested_message in message.nested_messages:
            self._allocate_scoped_message_identifiers(nested_message)

    def _ensure_name_caches(self, schema: Schema) -> None:
        """Construct the naming caches once for a schema file."""
        if not hasattr(self, "_named_schema_ids"):
            # Init everything.
            self._named_schema_ids: Set[int] = set()
            self._type_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._module_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._field_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._enum_value_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._union_case_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._named_service_schema_ids: Set[int] = set()
            self._service_trait_identifier_cache: Dict[Tuple[object, ...], str] = {}
            self._service_client_module_identifier_cache: Dict[
                Tuple[object, ...], str
            ] = {}
            self._service_server_module_identifier_cache: Dict[
                Tuple[object, ...], str
            ] = {}
            self._service_name_constant_identifier_cache: Dict[
                Tuple[object, ...], str
            ] = {}
            self._rpc_method_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._rpc_stream_type_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
            self._rpc_path_constant_identifier_cache: Dict[
                Tuple[object, ...], Dict[Tuple[object, ...], str]
            ] = {}
        schema_id = id(schema)
        if schema_id in self._named_schema_ids:
            # Cache exists.
            return
        enums, unions, messages = self._local_top_level_types(schema)
        self._allocate_scoped_type_identifiers(
            list(enums) + list(unions) + list(messages), "top-level Rust types"
        )
        self._allocate_scoped_module_identifiers(
            list(messages), "top-level Rust modules"
        )
        for enum in enums:
            self._allocate_scoped_enum_identifiers(enum)
        for union in unions:
            self._allocate_scoped_union_identifiers(union)
        for message in messages:
            self._allocate_scoped_message_identifiers(message)
        self._named_schema_ids.add(schema_id)

    def generate(self) -> List[GeneratedFile]:
        """Generate Rust files for the schema."""
        files = []

        # Generate a single module file with all types
        files.append(self.generate_module())

        return files

    def get_module_name(self) -> str:
        """Get the Rust module name."""
        module_name = self.get_top_level_module_identifier(self.package)
        # e.g., when resolving the file for `pub mod r#type`, Rust looks for `type.rs`, not `r#type.rs`.
        if module_name.startswith("r#"):
            return module_name[2:]
        return module_name

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
        return self.get_top_level_module_identifier(schema.package)

    def _module_name_for_type(self, type_def: object) -> str:
        schema = self._load_schema(type_def.location.file)
        if schema is None:
            return self.get_top_level_module_identifier(
                self._package_for_source_file(type_def.location.file)
            )
        return self._module_name_for_schema(schema)

    def _record_imported_module(
        self,
        module_sources: Dict[str, str],
        ordered_modules: List[str],
        module: str,
        source: str,
    ) -> None:
        """Record an imported module and reject module-name collisions."""
        previous_source = module_sources.get(module)
        if previous_source is not None:
            if previous_source != source:
                raise ValueError(
                    f"Rust module name collision: {previous_source!r} and "
                    f"{source!r} both map to Rust module {module!r}"
                )
            return
        module_sources[module] = source
        ordered_modules.append(module)

    def _collect_imported_modules(self) -> List[str]:
        modules: Dict[str, str] = {}
        for type_def in self.schema.enums + self.schema.unions + self.schema.messages:
            if not self.is_imported_type(type_def):
                continue
            module = self._module_name_for_type(type_def)
            source = type_def.location.file
            previous_source = modules.get(module)
            if previous_source is not None and previous_source != source:
                raise ValueError(
                    f"Rust module name collision: {previous_source!r} and "
                    f"{source!r} both map to Rust module {module!r}"
                )
            modules[module] = source
        ordered: List[str] = []
        module_sources: Dict[str, str] = {}
        base_dir = Path(self.schema.source_file).resolve().parent
        for imp in self.schema.imports:
            resolved_path = getattr(imp, "resolved_path", None)
            candidate = (
                Path(resolved_path).resolve()
                if resolved_path
                else (base_dir / imp.path).resolve()
            )
            schema = self._load_schema(str(candidate))
            if schema is None:
                package = self.schema.source_packages.get(str(candidate))
                if str(candidate) not in self.schema.source_packages:
                    raise ValueError(
                        f"Rust generator cannot determine package for import "
                        f"{imp.path!r} resolved to {str(candidate)!r}"
                    )
                module = self.get_top_level_module_identifier(package)
            else:
                module = self._module_name_for_schema(schema)
            self._record_imported_module(
                module_sources, ordered, module, str(candidate)
            )
        for module, source in sorted(modules.items()):
            self._record_imported_module(module_sources, ordered, module, source)
        return ordered

    def _format_imported_type_name(
        self,
        type_name: str,
        module: str,
        type_def: object,
    ) -> str:
        type_path = self.schema.resolve_type_name(type_name)
        if "." in type_path:
            parts = type_path.split(".")
            parents: List[str] = []
            schema = self._schema_for_node(type_def)
            parent_messages = self._resolve_message_path(schema, parts[:-1])
            if parent_messages:
                parents = [
                    self.get_module_identifier(parent) for parent in parent_messages
                ]
            if not parents:
                parents = [self.to_rust_snake(name) for name in parts[:-1]]
            path = "::".join(parents + [self.get_type_identifier(type_def)])
            return f"crate::{module}::{path}"
        return f"crate::{module}::{self.get_type_identifier(type_def)}"

    def generate_bytes_impl(self, type_name: str) -> List[str]:
        lines = []
        lines.append(f"impl {type_name} {{")
        lines.append(
            "    pub fn to_bytes(&self) -> ::std::result::Result<::std::vec::Vec<u8>, ::fory::Error> {"
        )
        lines.append("        let fory = detail::get_fory();")
        lines.append("        fory.serialize(self)")
        lines.append("    }")
        lines.append("")
        lines.append(
            f"    pub fn from_bytes(data: &[u8]) -> ::std::result::Result<{type_name}, ::fory::Error> {{"
        )
        lines.append("        let fory = detail::get_fory();")
        lines.append("        fory.deserialize(data)")
        lines.append("    }")
        lines.append("}")
        return lines

    def generate_module(self) -> GeneratedFile:
        """Generate a Rust module with all types."""
        lines = []

        # License header
        lines.append(self.get_license_header("//"))
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

    def message_has_nested_enum(self, message: Message) -> bool:
        if message.nested_enums:
            return True
        return any(
            self.message_has_nested_enum(nested_msg)
            for nested_msg in message.nested_messages
        )

    def message_has_nested_union(self, message: Message) -> bool:
        if message.nested_unions:
            return True
        return any(
            self.message_has_nested_union(nested_msg)
            for nested_msg in message.nested_messages
        )

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
        return "::".join(self.get_module_identifier(parent) for parent in parent_stack)

    def get_type_path(
        self, type_def: object, parent_stack: Optional[List[Message]]
    ) -> str:
        """Build a type path for nested types from the root module."""
        module_path = self.get_module_path(parent_stack)
        name = self.get_type_identifier(type_def)
        if module_path:
            return f"{module_path}::{name}"
        return name

    def build_relative_type_name(
        self,
        current_parents: List[Message],
        target_parents: List[Message],
        type_name: str,
    ) -> str:
        """Build a type path relative to the current module."""
        current_parts = [
            self.get_module_identifier(message) for message in current_parents
        ]
        target_parts = [
            self.get_module_identifier(message) for message in target_parents
        ]
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

        type_name = self.get_type_identifier(enum)

        # Derive macros
        lines.append(
            "#[derive(::fory::ForyEnum, Debug, Clone, PartialEq, Eq, Hash, Default)]"
        )
        lines.append("#[repr(i32)]")

        lines.append(f"pub enum {type_name} {{")

        # Enum values (strip prefix for scoped enums)
        for i, value in enumerate(enum.values):
            if i == 0:
                lines.append("    #[default]")
            value_name = self._enum_value_identifier_cache[self._cache_key(enum)][
                self._cache_key(value)
            ]
            lines.append(f"    {value_name} = {value.value},")

        lines.append("}")

        return lines

    def generate_union(
        self,
        union: Union,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Rust tagged union."""
        lines: List[str] = []

        union_name = self.get_type_identifier(union)
        comment = self.format_type_id_comment(union, "//")
        if comment:
            lines.append(comment)
        derives = ["::fory::ForyUnion"]
        for trait in ("Clone", "Debug", "PartialEq", "Eq", "Hash"):
            if self.union_supports_trait(union, trait, parent_stack):
                derives.append(trait)
        lines.append(f"#[derive({', '.join(derives)})]")
        lines.append(f"pub enum {union_name} {{")
        lines.append("    #[fory(unknown)]")
        lines.append("    Unknown(::fory::UnknownCase),")

        for index, field in enumerate(union.fields):
            variant_name = self.get_union_case_identifier(union, field)
            pointer_type = self.get_field_pointer_type(field)
            variant_type = self.generate_type(
                field.field_type,
                nullable=False,
                ref=field.ref,
                element_optional=field.element_optional,
                element_ref=field.element_ref,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            variant_type = self.qualify_union_payload_type(
                field.field_type, variant_type, variant_name
            )
            variant_attrs = [f"id = {field.number}"]
            if index == 0:
                variant_attrs.append("default")
            lines.append(f"    #[fory({', '.join(variant_attrs)})]")
            payload_attr = self.get_payload_field_attr(field)
            if payload_attr:
                lines.append(
                    f"    {variant_name}(#[fory({payload_attr})] {variant_type}),"
                )
            else:
                lines.append(f"    {variant_name}({variant_type}),")

        lines.append("}")
        lines.append("")

        if union.fields:
            default_field = union.fields[0]
            default_variant = self.get_union_case_identifier(union, default_field)
            default_pointer_type = self.get_field_pointer_type(default_field)
            default_type = self.generate_type(
                default_field.field_type,
                nullable=False,
                ref=default_field.ref,
                element_optional=default_field.element_optional,
                element_ref=default_field.element_ref,
                parent_stack=parent_stack,
                pointer_type=default_pointer_type,
            )
            default_type = self.qualify_union_payload_type(
                default_field.field_type, default_type, default_variant
            )
            lines.append(f"impl ::std::default::Default for {union_name} {{")
            lines.append("    fn default() -> Self {")
            lines.append(
                f"        Self::{default_variant}(<{default_type} as ::fory::ForyDefault>::fory_default())"
            )
            lines.append("    }")
            lines.append("}")
            lines.append("")

        lines.extend(self.generate_bytes_impl(union_name))

        return lines

    def qualify_union_payload_type(
        self,
        field_type: FieldType,
        rendered_type: str,
        variant_name: str,
    ) -> str:
        if rendered_type == variant_name and isinstance(field_type, NamedType):
            return f"self::{rendered_type}"
        return rendered_type

    def generate_message(
        self,
        message: Message,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        """Generate a Rust struct."""
        lines = []

        type_name = self.get_type_identifier(message)

        # Derive macros
        comment = self.format_type_id_comment(message, "//")
        if comment:
            lines.append(comment)
        needs_safe_debug = self.message_needs_safe_debug(message)
        derives = ["::fory::ForyStruct"]
        if not needs_safe_debug:
            derives.append("Debug")
        if self.message_supports_trait(message, "Clone"):
            derives.append("Clone")
        if self.message_supports_trait(message, "PartialEq"):
            derives.append("PartialEq")
        if self.message_supports_trait(message, "Eq"):
            derives.append("Eq")
        if self.message_supports_trait(message, "Hash"):
            derives.append("Hash")
        if self.message_supports_trait(message, "Default"):
            derives.append("Default")
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
        if isinstance(field_type, ArrayType):
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

    def field_needs_safe_debug(
        self, field: Field, parent_stack: Optional[List[Message]] = None
    ) -> bool:
        return (
            self.field_has_ref(field)
            or self.field_type_has_any(field.field_type)
            or not self.field_supports_trait(field, "Debug", parent_stack)
        )

    def message_needs_safe_debug(self, message: Message) -> bool:
        lineage = self._lineage_for_message(message)
        return any(
            self.field_needs_safe_debug(field, lineage) for field in message.fields
        )

    def _lineage_for_message(self, message: Message) -> List[Message]:
        lineage: List[Message] = []

        def visit(current: Message, parents: List[Message]) -> bool:
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

    def _parent_stack_for_type(self, type_def: object) -> List[Message]:
        def visit(current: Message, parents: List[Message]) -> Optional[List[Message]]:
            if current is type_def:
                return parents
            for nested_union in current.nested_unions:
                if nested_union is type_def:
                    return parents + [current]
            for nested_enum in current.nested_enums:
                if nested_enum is type_def:
                    return parents + [current]
            for nested_message in current.nested_messages:
                found = visit(nested_message, parents + [current])
                if found is not None:
                    return found
            return None

        for top in self.schema.messages:
            found = visit(top, [])
            if found is not None:
                return found
        return []

    def union_supports_trait(
        self,
        union: Union,
        trait: str,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
    ) -> bool:
        if trait == "Default":
            return bool(union.fields)
        if visiting is None:
            visiting = set()
        key = ("union", trait, id(union))
        if key in visiting:
            return False
        visiting.add(key)
        lineage = parent_stack or []
        result = all(
            self.field_supports_trait(field, trait, lineage, visiting)
            for field in union.fields
        )
        visiting.remove(key)
        return result

    def message_supports_trait(
        self,
        message: Message,
        trait: str,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
    ) -> bool:
        if trait == "Debug":
            return True
        if visiting is None:
            visiting = set()
        key = ("message", trait, id(message))
        if key in visiting:
            return False
        visiting.add(key)
        lineage = self._lineage_for_message(message)
        result = all(
            self.field_supports_trait(field, trait, lineage, visiting)
            for field in message.fields
        )
        visiting.remove(key)
        return result

    def field_supports_trait(
        self,
        field: Field,
        trait: str,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
    ) -> bool:
        if trait == "Default" and field.optional:
            return True
        if field.ref:
            return self.ref_type_supports_trait(
                field.field_type,
                trait,
                parent_stack,
                visiting,
                field.ref_options.get("weak_ref") is True,
            )
        if isinstance(field.field_type, ListType):
            element_ref = field.element_ref or field.field_type.element_ref
            if element_ref:
                if trait == "Default":
                    return True
                ref_options = (
                    field.field_type.element_ref_options
                    if field.field_type.element_ref
                    else field.element_ref_options
                )
                return self.ref_type_supports_trait(
                    field.field_type.element_type,
                    trait,
                    parent_stack,
                    visiting,
                    ref_options.get("weak_ref") is True,
                )
        if isinstance(field.field_type, MapType) and field.field_type.value_ref:
            if trait == "Default":
                return True
            if trait == "Hash":
                return False
            key_ok = self.type_supports_trait(
                field.field_type.key_type, "Eq", parent_stack, visiting
            ) and self.type_supports_trait(
                field.field_type.key_type, "Hash", parent_stack, visiting
            )
            value_trait = "PartialEq" if trait == "PartialEq" else trait
            value_ok = self.ref_type_supports_trait(
                field.field_type.value_type,
                value_trait,
                parent_stack,
                visiting,
                field.field_type.value_ref_options.get("weak_ref") is True,
            )
            if trait in ("PartialEq", "Eq"):
                return key_ok and value_ok
            return (
                self.type_supports_trait(
                    field.field_type.key_type, trait, parent_stack, visiting
                )
                and value_ok
            )
        return self.type_supports_trait(field.field_type, trait, parent_stack, visiting)

    def ref_type_supports_trait(
        self,
        field_type: FieldType,
        trait: str,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
        weak_ref: bool = False,
    ) -> bool:
        if weak_ref:
            return trait in ("Clone", "Debug", "PartialEq", "Eq", "Default")
        if trait == "Clone":
            return True
        if trait == "Default":
            return self.type_supports_trait(field_type, trait, parent_stack, visiting)
        if isinstance(field_type, NamedType):
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if self.named_type_is_visiting(named_type, trait, visiting):
                return True
        return self.type_supports_trait(field_type, trait, parent_stack, visiting)

    def named_type_is_visiting(
        self,
        named_type: object,
        trait: str,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
    ) -> bool:
        if visiting is None:
            return False
        if isinstance(named_type, Union):
            return ("union", trait, id(named_type)) in visiting
        if isinstance(named_type, Message):
            return ("message", trait, id(named_type)) in visiting
        return False

    def type_supports_trait(
        self,
        field_type: FieldType,
        trait: str,
        parent_stack: Optional[List[Message]] = None,
        visiting: Optional[Set[Tuple[str, str, int]]] = None,
    ) -> bool:
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return False
            if trait in ("Eq", "Hash") and field_type.kind in (
                PrimitiveKind.FLOAT16,
                PrimitiveKind.BFLOAT16,
                PrimitiveKind.FLOAT32,
                PrimitiveKind.FLOAT64,
            ):
                return False
            return True
        if isinstance(field_type, ListType):
            if trait == "Default":
                return True
            return self.type_supports_trait(
                field_type.element_type, trait, parent_stack, visiting
            )
        if isinstance(field_type, ArrayType):
            if trait == "Default":
                return True
            return self.type_supports_trait(
                field_type.element_type, trait, parent_stack, visiting
            )
        if isinstance(field_type, MapType):
            if trait == "Default":
                return True
            if trait == "Hash":
                return False
            key_ok = self.type_supports_trait(
                field_type.key_type, "Eq", parent_stack, visiting
            ) and self.type_supports_trait(
                field_type.key_type, "Hash", parent_stack, visiting
            )
            value_trait = "PartialEq" if trait == "PartialEq" else trait
            value_ok = self.type_supports_trait(
                field_type.value_type, value_trait, parent_stack, visiting
            )
            if trait in ("PartialEq", "Eq"):
                return key_ok and value_ok
            return self.type_supports_trait(
                field_type.key_type, trait, parent_stack, visiting
            ) and self.type_supports_trait(
                field_type.value_type, trait, parent_stack, visiting
            )
        if isinstance(field_type, NamedType):
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if isinstance(named_type, Union):
                return self.union_supports_trait(
                    named_type, trait, parent_stack, visiting
                )
            if isinstance(named_type, Message):
                return self.message_supports_trait(named_type, trait, visiting)
            if isinstance(named_type, Enum):
                return True
            return True
        return False

    def resolve_named_type(
        self, type_name: str, parent_stack: Optional[List[Message]] = None
    ) -> object:
        if "." in type_name:
            return self.schema.get_type(type_name)
        if parent_stack:
            for message in reversed(parent_stack):
                nested = message.get_nested_type(type_name)
                if nested is not None:
                    return nested
        return self.schema.get_type(type_name)

    def rust_string_literal(self, value: str) -> str:
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'

    def generate_debug_impl(self, message: Message) -> List[str]:
        """Generate a Debug impl that avoids recursive ref expansion."""
        lines: List[str] = []
        type_name = self.get_type_identifier(message)
        lines.append(f"impl ::std::fmt::Debug for {type_name} {{")
        lines.append(
            "    fn fmt(&self, f: &mut ::std::fmt::Formatter<'_>) -> ::std::fmt::Result {"
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
        lineage = self._lineage_for_message(message)
        for i, field in enumerate(message.fields):
            field_name = self.get_field_identifier(message, field)
            if i > 0:
                lines.append('        f.write_str(", ")?;')
            lines.append(
                f"        f.write_str({self.rust_string_literal(field_name + ': ')})?;"
            )
            if self.field_needs_safe_debug(field, lineage):
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
        module_name = self.get_module_identifier(message)
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
        nested_attr = self.get_nested_field_attr(field.field_type)
        if nested_attr:
            attrs.append(nested_attr)
        else:
            encoding = self.get_encoding_attr(field.field_type)
            if encoding:
                attrs.append(encoding)
        if attrs:
            lines.append(f"#[fory({', '.join(attrs)})]")

        pointer_type = self.get_field_pointer_type(field)
        rust_type = self.generate_type(
            field.field_type,
            nullable=field.optional,
            ref=field.ref,
            element_optional=field.element_optional,
            element_ref=field.element_ref,
            parent_stack=parent_stack,
            pointer_type=pointer_type,
        )
        field_name = self.get_field_identifier(parent_stack[-1], field)

        lines.append(f"pub {field_name}: {rust_type},")

        return lines

    def get_encoding_attr(self, field_type: FieldType) -> Optional[str]:
        """Return an encoding attribute for integer primitives."""
        if not isinstance(field_type, PrimitiveType):
            return None
        if field_type.encoding_modifier == "fixed":
            return "encoding = fixed"
        if field_type.encoding_modifier == "tagged":
            return "encoding = tagged"
        return None

    def get_nested_field_attr(self, field_type: FieldType) -> Optional[str]:
        """Return nested list/map field configuration."""
        if (
            isinstance(field_type, PrimitiveType)
            and field_type.kind == PrimitiveKind.BYTES
        ):
            return "bytes"
        if isinstance(field_type, ListType):
            element_attrs = self.get_nested_value_attrs(field_type.element_type)
            if element_attrs:
                return f"list(element({', '.join(element_attrs)}))"
            return None
        if isinstance(field_type, ArrayType):
            return "array"
        if isinstance(field_type, MapType):
            key_attrs = self.get_nested_value_attrs(field_type.key_type)
            value_attrs = self.get_nested_value_attrs(field_type.value_type)
            parts = []
            if key_attrs:
                parts.append(f"key({', '.join(key_attrs)})")
            if value_attrs or field_type.value_optional:
                if field_type.value_optional:
                    value_attrs = ["nullable = true", *value_attrs]
                parts.append(f"value({', '.join(value_attrs)})")
            if parts:
                return f"map({', '.join(parts)})"
        return None

    def get_payload_field_attr(self, field: Field) -> Optional[str]:
        """Return Rust derive metadata for a union payload field."""
        attrs: List[str] = []
        nested_attr = self.get_nested_field_attr(field.field_type)
        if nested_attr:
            attrs.append(nested_attr)
        else:
            encoding = self.get_encoding_attr(field.field_type)
            if encoding:
                attrs.append(encoding)
        if field.optional:
            attrs.append("nullable = true")
        if field.ref:
            attrs.append("ref = true")
        return ", ".join(attrs) if attrs else None

    def get_nested_value_attrs(self, field_type: FieldType) -> List[str]:
        attrs: List[str] = []
        if isinstance(field_type, PrimitiveType):
            encoding = self.get_encoding_attr(field_type)
            if encoding:
                attrs.append(encoding)
        nested = self.get_nested_field_attr(field_type)
        if nested:
            attrs.append(nested)
        return attrs

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
        pointer_type: str = "::std::sync::Arc",
    ) -> str:
        """Generate Rust type string."""
        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.ANY:
                return self.RUST_ANY_TYPE
            base_type = self.primitive_type_name(field_type.kind)
            if nullable:
                return f"::std::option::Option<{base_type}>"
            return base_type

        elif isinstance(field_type, NamedType):
            named_type = self.resolve_named_type(field_type.name, parent_stack)
            if named_type is None:
                raise ValueError(f"Unknown type {field_type.name!r}")
            type_name = self.resolve_nested_type_name(
                field_type.name,
                named_type,
                parent_stack,
            )
            if self.is_imported_type(named_type):
                module = self._module_name_for_type(named_type)
                type_name = self._format_imported_type_name(
                    field_type.name, module, named_type
                )
            if ref:
                type_name = f"{pointer_type}<{type_name}>"
            if nullable:
                type_name = f"::std::option::Option<{type_name}>"
            return type_name

        elif isinstance(field_type, ListType):
            effective_element_optional = element_optional or field_type.element_optional
            effective_element_ref = element_ref or field_type.element_ref
            element_pointer_type = pointer_type
            if field_type.element_ref:
                element_pointer_type = self.get_pointer_type(
                    field_type.element_ref_options,
                    field_type.element_ref_options.get("weak_ref") is True,
                )
            element_type = self.generate_type(
                field_type.element_type,
                nullable=effective_element_optional,
                ref=effective_element_ref,
                parent_stack=parent_stack,
                pointer_type=element_pointer_type,
            )
            list_type = f"::std::vec::Vec<{element_type}>"
            if ref:
                list_type = f"{pointer_type}<{list_type}>"
            if nullable:
                list_type = f"::std::option::Option<{list_type}>"
            return list_type

        elif isinstance(field_type, ArrayType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=False,
                ref=False,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            array_type = f"::std::vec::Vec<{element_type}>"
            if ref:
                array_type = f"{pointer_type}<{array_type}>"
            if nullable:
                array_type = f"::std::option::Option<{array_type}>"
            return array_type

        elif isinstance(field_type, MapType):
            key_type = self.generate_type(
                field_type.key_type,
                nullable=False,
                ref=False,
                parent_stack=parent_stack,
                pointer_type=pointer_type,
            )
            value_pointer_type = pointer_type
            if field_type.value_ref:
                value_pointer_type = self.get_pointer_type(
                    field_type.value_ref_options,
                    field_type.value_ref_options.get("weak_ref") is True,
                )
            value_type = self.generate_type(
                field_type.value_type,
                nullable=False,
                ref=field_type.value_ref,
                parent_stack=parent_stack,
                pointer_type=value_pointer_type,
            )
            map_type = f"::std::collections::HashMap<{key_type}, {value_type}>"
            if ref:
                map_type = f"{pointer_type}<{map_type}>"
            if nullable:
                map_type = f"::std::option::Option<{map_type}>"
            return map_type

        raise TypeError(f"Unsupported Rust field type: {field_type!r}")

    def resolve_nested_type_name(
        self,
        type_name: str,
        type_def: object,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        """Resolve nested type names to module-qualified Rust identifiers."""
        current_parents = (parent_stack or [])[:-1]
        type_path = self.schema.resolve_type_name(type_name)
        if "." in type_path:
            parts = type_path.split(".")
            schema = self._schema_for_node(type_def)
            target_parents = self._resolve_message_path(schema, parts[:-1])
            base_name = self.get_type_identifier(type_def)
            if not target_parents:
                down = [self.to_rust_snake(name) for name in parts[:-1]]
                return "::".join(down + [base_name])
            return self.build_relative_type_name(
                current_parents,
                target_parents,
                base_name,
            )
        resolved_name = self.get_type_identifier(type_def)
        if not parent_stack:
            return resolved_name

        for i in range(len(parent_stack) - 1, -1, -1):
            message = parent_stack[i]
            if message.get_nested_type(type_name) is not None:
                target_parents = parent_stack[: i + 1]
                return self.build_relative_type_name(
                    current_parents, target_parents, resolved_name
                )

        return resolved_name

    def field_uses_pointer(self, field: Field) -> bool:
        if field.ref:
            return True
        if isinstance(field.field_type, ListType) and field.element_ref:
            return True
        if isinstance(field.field_type, MapType) and field.field_type.value_ref:
            return True
        return False

    def get_field_pointer_type(self, field: Field) -> str:
        if isinstance(field.field_type, ListType) and field.element_ref:
            ref_options = field.element_ref_options
        elif isinstance(field.field_type, MapType) and field.field_type.value_ref:
            ref_options = field.field_type.value_ref_options
        else:
            ref_options = field.ref_options
        weak_ref = ref_options.get("weak_ref") is True
        return self.get_pointer_type(ref_options, weak_ref)

    def get_pointer_type(self, ref_options: dict, weak_ref: bool = False) -> str:
        """Determine pointer type for ref tracking based on field options."""
        if thread_safe_pointer_enabled(ref_options):
            return "::fory::ArcWeak" if weak_ref else "::std::sync::Arc"
        return "::fory::RcWeak" if weak_ref else "::std::rc::Rc"

    def generate_registration(self) -> List[str]:
        """Generate the Fory registration function."""
        lines = []

        lines.append(
            "pub fn register_types(fory: &mut ::fory::Fory) -> ::std::result::Result<(), ::fory::Error> {"
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

        lines.append("    ::std::result::Result::Ok(())")
        lines.append("}")

        return lines

    def generate_fory_helpers(self) -> List[str]:
        lines: List[str] = []
        lines.append("mod detail {")
        lines.append("    use super::*;")
        lines.append("")
        lines.append("    pub(super) fn get_fory() -> &'static ::fory::Fory {")
        lines.append(
            "        static FORY: ::std::sync::OnceLock<::fory::Fory> = ::std::sync::OnceLock::new();"
        )
        lines.append("        FORY.get_or_init(|| {")
        lines.append("            let mut fory = ::fory::Fory::builder()")
        lines.append("                .xlang(true)")
        lines.append("                .track_ref(true)")
        lines.append("                .compatible(true)")
        lines.append("                .build();")
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
        type_name = self.get_type_path(enum, parent_stack)
        reg_name = self.get_registration_type_name(enum.name, parent_stack)

        if self.should_register_by_id(enum):
            lines.append(f"    fory.register::<{type_name}>({enum.type_id})?;")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_by_name::<{type_name}>("{ns}", "{reg_name}")?;'
            )

    def generate_message_registration(
        self,
        lines: List[str],
        message: Message,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a message and its nested types."""
        type_name = self.get_type_path(message, parent_stack)
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
                f'    fory.register_by_name::<{type_name}>("{ns}", "{reg_name}")?;'
            )

    def generate_union_registration(
        self,
        lines: List[str],
        union: Union,
        parent_stack: Optional[List[Message]],
    ):
        """Generate registration code for a union."""
        type_name = self.get_type_path(union, parent_stack)
        reg_name = self.get_registration_type_name(union.name, parent_stack)

        if self.should_register_by_id(union):
            lines.append(f"    fory.register_union::<{type_name}>({union.type_id})?;")
        else:
            ns = self.package or "default"
            lines.append(
                f'    fory.register_union_by_name::<{type_name}>("{ns}", "{reg_name}")?;'
            )
