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

"""C# code generator."""

from pathlib import Path
import re
from typing import Dict, List, Optional, Set, Tuple, Union as TypingUnion

from fory_compiler.frontend.utils import parse_idl_file
from fory_compiler.generators.base import BaseGenerator, GeneratedFile
from fory_compiler.generators.services.csharp import CSharpServiceMixin
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


_CSHARP_KEYWORDS = {
    "abstract",
    "as",
    "base",
    "bool",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "checked",
    "class",
    "const",
    "continue",
    "decimal",
    "default",
    "delegate",
    "do",
    "double",
    "else",
    "enum",
    "event",
    "explicit",
    "extern",
    "false",
    "finally",
    "fixed",
    "float",
    "for",
    "foreach",
    "goto",
    "if",
    "implicit",
    "in",
    "int",
    "interface",
    "internal",
    "is",
    "lock",
    "long",
    "namespace",
    "new",
    "null",
    "object",
    "operator",
    "out",
    "override",
    "params",
    "private",
    "protected",
    "public",
    "readonly",
    "ref",
    "return",
    "sbyte",
    "sealed",
    "short",
    "sizeof",
    "stackalloc",
    "static",
    "string",
    "struct",
    "switch",
    "this",
    "throw",
    "true",
    "try",
    "typeof",
    "uint",
    "ulong",
    "unchecked",
    "unsafe",
    "ushort",
    "using",
    "virtual",
    "void",
    "volatile",
    "while",
}


def csharp_safe_identifier(name: str) -> str:
    if name in _CSHARP_KEYWORDS:
        return f"@{name}"
    return name


def csharp_namespace_for_schema(schema: Schema) -> str:
    value = schema.get_option("csharp_namespace")
    if value:
        return str(value)
    if schema.package:
        return schema.package
    return "generated"


def csharp_module_file_name(schema: Schema) -> str:
    return f"{csharp_schema_owner_name(schema)}.cs"


def csharp_module_owner_stem(schema: Schema) -> str:
    if schema.source_file and not schema.source_file.startswith("<"):
        return Path(schema.source_file).stem
    if schema.package:
        return schema.package.replace(".", "_")
    return "generated"


def csharp_schema_owner_name(schema: Schema) -> str:
    stem = re.sub(r"[^0-9A-Za-z_]", "_", csharp_module_owner_stem(schema))
    parts = [part for part in stem.split("_") if part]
    owner_name = "".join(_pascal_identifier_part(part) for part in parts)
    if not owner_name or not (owner_name[0].isalpha() or owner_name[0] == "_"):
        owner_name = f"Schema{owner_name}"
    return owner_name


def _pascal_identifier_part(part: str) -> str:
    if part.isupper():
        return part.capitalize()
    return f"{part[0].upper()}{part[1:]}"


def csharp_module_class_name(schema: Schema) -> str:
    return f"{csharp_schema_owner_name(schema)}ForyModule"


def csharp_output_paths(
    schema: Schema, include_services: bool = False
) -> List[Tuple[str, str]]:
    namespace_name = csharp_namespace_for_schema(schema)
    namespace_path = namespace_name.replace(".", "/") if namespace_name else ""
    model_file = csharp_module_file_name(schema)
    model_path = f"{namespace_path}/{model_file}" if namespace_path else model_file
    outputs = [(model_path, f"schema module {csharp_module_class_name(schema)}")]
    if include_services:
        for service in schema.services:
            file_name = f"{service.name}Grpc.cs"
            path = f"{namespace_path}/{file_name}" if namespace_path else file_name
            outputs.append((path, f"service {service.name}"))
    return outputs


def csharp_top_level_symbols(
    schema: Schema, include_services: bool = False
) -> List[Tuple[str, str]]:
    module_name = csharp_module_class_name(schema)
    symbols = [
        (
            csharp_safe_identifier(module_name),
            f"schema module {module_name}",
        )
    ]
    for type_def in schema.enums + schema.unions + schema.messages:
        symbols.append(
            (
                csharp_safe_identifier(type_def.name),
                f"schema type {type_def.name}",
            )
        )
    if include_services:
        for service in schema.services:
            symbols.append(
                (
                    csharp_safe_identifier(service.name),
                    f"service {service.name}",
                )
            )
    return symbols


def validate_csharp_generation(
    graph: List[Tuple[Path, Schema]], grpc: bool = False
) -> bool:
    output_owners: Dict[str, List[str]] = {}
    module_owners: Dict[Tuple[str, str], List[str]] = {}
    symbol_owners: Dict[Tuple[str, str], List[str]] = {}
    for path, schema in graph:
        for output_path, owner in csharp_output_paths(schema, include_services=grpc):
            output_owners.setdefault(output_path, []).append(f"{path} {owner}")
        namespace_name = csharp_namespace_for_schema(schema)
        module_name = csharp_module_class_name(schema)
        module_owners.setdefault((namespace_name, module_name), []).append(str(path))
        for symbol_name, owner in csharp_top_level_symbols(
            schema, include_services=grpc
        ):
            symbol_owners.setdefault((namespace_name, symbol_name), []).append(
                f"{path} {owner}"
            )

    output_collisions = {
        output_path: owners
        for output_path, owners in output_owners.items()
        if len(owners) > 1
    }
    if output_collisions:
        details = ", ".join(
            f"{output_path}: {', '.join(owners)}"
            for output_path, owners in sorted(output_collisions.items())
        )
        raise ValueError(
            "C# generated file path collision; rename schema files or services, "
            f"or use distinct C# namespaces. Collisions: {details}"
        )

    module_collisions = {
        owner: paths for owner, paths in module_owners.items() if len(paths) > 1
    }
    if module_collisions:
        details = ", ".join(
            f"{namespace_name}.{module_name}: {', '.join(paths)}"
            for (namespace_name, module_name), paths in sorted(
                module_collisions.items()
            )
        )
        raise ValueError(
            "C# schema module owner collision; rename schema files or use "
            f"distinct C# namespaces. Collisions: {details}"
        )
    symbol_collisions = {
        owner: paths for owner, paths in symbol_owners.items() if len(paths) > 1
    }
    if symbol_collisions:
        details = ", ".join(
            f"{namespace_name}.{symbol_name}: {', '.join(paths)}"
            for (namespace_name, symbol_name), paths in sorted(
                symbol_collisions.items()
            )
        )
        raise ValueError(
            "C# top-level symbol collision; rename schema files, schema types, "
            f"or services, or use distinct C# namespaces. Collisions: {details}"
        )
    return True


class CSharpGenerator(CSharpServiceMixin, BaseGenerator):
    """Generates C# models and registration helpers for Apache Fory."""

    language_name = "csharp"
    file_extension = ".cs"

    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "sbyte",
        PrimitiveKind.INT16: "short",
        PrimitiveKind.INT32: "int",
        PrimitiveKind.INT64: "long",
        PrimitiveKind.UINT8: "byte",
        PrimitiveKind.UINT16: "ushort",
        PrimitiveKind.UINT32: "uint",
        PrimitiveKind.UINT64: "ulong",
        PrimitiveKind.FLOAT16: "Half",
        PrimitiveKind.BFLOAT16: "BFloat16",
        PrimitiveKind.FLOAT32: "float",
        PrimitiveKind.FLOAT64: "double",
        PrimitiveKind.STRING: "string",
        PrimitiveKind.BYTES: "byte[]",
        PrimitiveKind.DATE: "DateOnly",
        PrimitiveKind.TIMESTAMP: "DateTimeOffset",
        PrimitiveKind.DURATION: "TimeSpan",
        PrimitiveKind.DECIMAL: "decimal",
        PrimitiveKind.ANY: "object",
    }

    VALUE_TYPE_KINDS = {
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
        PrimitiveKind.DATE,
        PrimitiveKind.TIMESTAMP,
        PrimitiveKind.DURATION,
        PrimitiveKind.DECIMAL,
    }

    CSHARP_KEYWORDS = _CSHARP_KEYWORDS

    def __init__(self, schema: Schema, options):
        super().__init__(schema, options)
        self._qualified_type_names: Dict[int, str] = {}
        self._build_qualified_type_name_index()

    def _build_qualified_type_name_index(self) -> None:
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

    def get_csharp_namespace(self) -> str:
        return csharp_namespace_for_schema(self.schema)

    def get_module_class_name(self) -> str:
        return csharp_module_class_name(self.schema)

    def _module_class_name_for_schema(self, schema: Schema) -> str:
        return csharp_module_class_name(schema)

    def _module_file_name(self) -> str:
        return csharp_module_file_name(self.schema)

    def _namespace_path(self, namespace_name: str) -> str:
        return namespace_name.replace(".", "/") if namespace_name else ""

    def safe_identifier(self, name: str) -> str:
        return csharp_safe_identifier(name)

    def safe_type_identifier(self, name: str) -> str:
        return self.safe_identifier(name)

    def safe_member_name(self, name: str) -> str:
        return self.safe_identifier(self.to_pascal_case(name))

    def _nested_type_names_for_message(self, message: Message) -> Set[str]:
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

    def _csharp_namespace_for_schema(self, schema: Schema) -> str:
        return csharp_namespace_for_schema(schema)

    def _csharp_namespace_for_type(self, type_def: object) -> str:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return self.get_csharp_namespace()
        return self._csharp_namespace_for_schema(schema)

    def _collect_imported_modules(self) -> List[Tuple[str, str]]:
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
            namespace_name = self._csharp_namespace_for_schema(imported_schema)
            module_name = self._module_class_name_for_schema(imported_schema)
            file_info[normalized] = (namespace_name, module_name)

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

        return ordered

    def generate(self) -> List[GeneratedFile]:
        return [self.generate_file()]

    def generate_file(self) -> GeneratedFile:
        lines: List[str] = []
        namespace_name = self.get_csharp_namespace()

        lines.append(self.get_license_header("//"))
        lines.append("")
        lines.append("using System;")
        lines.append("using System.Collections.Generic;")
        lines.append("using Apache.Fory;")
        lines.append("using S = Apache.Fory.Schema.Types;")
        lines.append("")
        lines.append(f"namespace {namespace_name};")
        lines.append("")

        for enum in self.schema.enums:
            if self.is_imported_type(enum):
                continue
            lines.extend(self.generate_enum(enum))
            lines.append("")

        for union in self.schema.unions:
            if self.is_imported_type(union):
                continue
            lines.extend(self.generate_union(union))
            lines.append("")

        for message in self.schema.messages:
            if self.is_imported_type(message):
                continue
            lines.extend(self.generate_message(message, parent_stack=[]))
            lines.append("")

        lines.extend(self.generate_module_class())
        lines.append("")

        file_name = self._module_file_name()
        ns_path = self._namespace_path(namespace_name)
        if ns_path:
            path = f"{ns_path}/{file_name}"
        else:
            path = file_name

        return GeneratedFile(path=path, content="\n".join(lines))

    def _resolve_named_type(
        self, name: str, parent_stack: Optional[List[Message]] = None
    ) -> Optional[TypingUnion[Message, Enum, Union]]:
        parent_stack = parent_stack or []
        if "." in name:
            return self.schema.get_type(name)
        for msg in reversed(parent_stack):
            nested = msg.get_nested_type(name)
            if nested is not None:
                return nested
        return self.schema.get_type(name)

    def _type_namespace(
        self, resolved: Optional[TypingUnion[Message, Enum, Union]]
    ) -> str:
        if resolved is None:
            return self.get_csharp_namespace()
        if self.is_imported_type(resolved):
            return self._csharp_namespace_for_type(resolved)
        return self.get_csharp_namespace()

    def _qualified_type_name_for(
        self,
        resolved: Optional[TypingUnion[Message, Enum, Union]],
        fallback_name: str,
    ) -> str:
        if resolved is None:
            return ".".join(
                self.safe_type_identifier(part) for part in fallback_name.split(".")
            )
        qualified = self._qualified_type_names.get(id(resolved), fallback_name)
        return ".".join(
            self.safe_type_identifier(part) for part in qualified.split(".")
        )

    def _named_type_reference(
        self, named_type: NamedType, parent_stack: Optional[List[Message]] = None
    ) -> str:
        resolved = self._resolve_named_type(named_type.name, parent_stack)
        ns = self._type_namespace(resolved)
        qname = self._qualified_type_name_for(resolved, named_type.name)
        if ns:
            return f"global::{ns}.{qname}"
        return qname

    def _is_value_type(
        self, field_type: FieldType, parent_stack: List[Message]
    ) -> bool:
        if isinstance(field_type, PrimitiveType):
            return field_type.kind in self.VALUE_TYPE_KINDS
        if isinstance(field_type, NamedType):
            resolved = self._resolve_named_type(field_type.name, parent_stack)
            return isinstance(resolved, Enum)
        return False

    def generate_type(
        self,
        field_type: FieldType,
        nullable: bool = False,
        parent_stack: Optional[List[Message]] = None,
    ) -> str:
        parent_stack = parent_stack or []
        if isinstance(field_type, PrimitiveType):
            if field_type.kind not in self.PRIMITIVE_MAP:
                raise ValueError(
                    f"Unsupported primitive type for C#: {field_type.kind}"
                )
            type_name = self.PRIMITIVE_MAP[field_type.kind]
            if nullable and field_type.kind in self.VALUE_TYPE_KINDS:
                return f"{type_name}?"
            if nullable and field_type.kind not in self.VALUE_TYPE_KINDS:
                return f"{type_name}?"
            return type_name

        if isinstance(field_type, NamedType):
            type_name = self._named_type_reference(field_type, parent_stack)
            if nullable and self._is_value_type(field_type, parent_stack):
                return f"{type_name}?"
            if nullable and not self._is_value_type(field_type, parent_stack):
                return f"{type_name}?"
            return type_name

        if isinstance(field_type, ListType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=field_type.element_optional,
                parent_stack=parent_stack,
            )
            list_type = f"List<{element_type}>"
            if nullable:
                return f"{list_type}?"
            return list_type

        if isinstance(field_type, ArrayType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            array_type = f"{element_type}[]"
            if nullable:
                return f"{array_type}?"
            return array_type

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
            map_type = f"Dictionary<{key_type}, {value_type}>"
            if nullable:
                return f"{map_type}?"
            return map_type

        raise ValueError(f"Unknown field type: {field_type}")

    def _union_case_type_name(self, field: Field) -> str:
        return self.safe_identifier(self.to_pascal_case(field.name))

    def _default_initializer(
        self, field: Field, parent_stack: List[Message]
    ) -> Optional[str]:
        if field.optional:
            return None

        field_type = field.field_type
        if isinstance(field_type, ListType) or isinstance(field_type, MapType):
            return " = new();"

        if isinstance(field_type, ArrayType):
            element_type = self.generate_type(
                field_type.element_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            return f" = Array.Empty<{element_type}>();"

        if isinstance(field_type, PrimitiveType):
            if field_type.kind == PrimitiveKind.STRING:
                return " = string.Empty;"
            if field_type.kind == PrimitiveKind.BYTES:
                return " = Array.Empty<byte>();"
            if field_type.kind == PrimitiveKind.ANY:
                return " = null!;"
            return None

        if isinstance(field_type, NamedType):
            resolved = self._resolve_named_type(field_type.name, parent_stack)
            if isinstance(resolved, Enum):
                return None
            return " = null!;"

        return None

    def _schema_type_hint(
        self, field_type: FieldType, force: bool = False
    ) -> Optional[str]:
        if isinstance(field_type, PrimitiveType):
            return self._primitive_schema_type_hint(field_type, force)

        if isinstance(field_type, ListType):
            element_hint = self._schema_type_hint(field_type.element_type, force=True)
            if element_hint is None:
                return None
            return f"S.List<{element_hint}>"

        if isinstance(field_type, ArrayType):
            element_hint = self._schema_type_hint(field_type.element_type, force=True)
            if element_hint is None:
                return None
            return f"S.Array<{element_hint}>"

        if isinstance(field_type, MapType):
            key_hint = self._schema_type_hint(field_type.key_type, force=True)
            value_hint = self._schema_type_hint(field_type.value_type, force=True)
            if key_hint is None and value_hint is None:
                return None
            if key_hint is None:
                key_hint = self._schema_type_hint(field_type.key_type, force=True)
            if value_hint is None:
                value_hint = self._schema_type_hint(field_type.value_type, force=True)
            if key_hint is None or value_hint is None:
                return None
            return f"S.Map<{key_hint}, {value_hint}>"

        return None

    def _primitive_schema_type_hint(
        self, field_type: PrimitiveType, force: bool
    ) -> Optional[str]:
        kind = field_type.kind
        encoding = field_type.encoding_modifier
        if kind in (
            PrimitiveKind.INT32,
            PrimitiveKind.INT64,
            PrimitiveKind.UINT32,
            PrimitiveKind.UINT64,
        ):
            base = {
                PrimitiveKind.INT32: "S.Int32",
                PrimitiveKind.INT64: "S.Int64",
                PrimitiveKind.UINT32: "S.UInt32",
                PrimitiveKind.UINT64: "S.UInt64",
            }[kind]
            if encoding == "fixed":
                return f"S.Fixed<{base}>"
            if encoding == "tagged":
                return f"S.Tagged<{base}>"
            if force or encoding == "varint":
                return base
            return None

        hints = {
            PrimitiveKind.BOOL: "S.Bool",
            PrimitiveKind.INT8: "S.Int8",
            PrimitiveKind.INT16: "S.Int16",
            PrimitiveKind.UINT8: "S.UInt8",
            PrimitiveKind.UINT16: "S.UInt16",
            PrimitiveKind.FLOAT16: "S.Float16",
            PrimitiveKind.BFLOAT16: "S.BFloat16",
            PrimitiveKind.FLOAT32: "S.Float32",
            PrimitiveKind.FLOAT64: "S.Float64",
            PrimitiveKind.STRING: "S.String",
            PrimitiveKind.BYTES: "S.Binary",
            PrimitiveKind.DATE: "S.Date",
            PrimitiveKind.TIMESTAMP: "S.Timestamp",
            PrimitiveKind.DURATION: "S.Duration",
            PrimitiveKind.DECIMAL: "S.Decimal",
        }
        if force:
            return hints.get(kind)
        return None

    def _type_reference_for_local(
        self,
        type_def: TypingUnion[Message, Enum, Union],
    ) -> str:
        namespace_name = self.get_csharp_namespace()
        type_name = self._qualified_type_name_for(
            type_def, getattr(type_def, "name", "Unknown")
        )
        return f"global::{namespace_name}.{type_name}"

    def generate_enum(self, enum: Enum, indent: int = 0) -> List[str]:
        lines: List[str] = []
        ind = self.indent_str * indent
        comment = self.format_type_id_comment(enum, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}[ForyEnum]")
        lines.append(f"{ind}public enum {self.safe_type_identifier(enum.name)}")
        lines.append(f"{ind}{{")

        for i, value in enumerate(enum.values):
            comma = "," if i < len(enum.values) - 1 else ""
            stripped_name = self.strip_enum_prefix(enum.name, value.name)
            value_name = self.safe_identifier(self.to_pascal_case(stripped_name))
            lines.append(f"{ind}{self.indent_str}{value_name} = {value.value}{comma}")

        lines.append(f"{ind}}}")
        return lines

    def generate_union(
        self,
        union: Union,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        lines: List[str] = []
        ind = self.indent_str * indent
        type_name = self.safe_type_identifier(union.name)
        module_class = self.get_module_class_name()
        full_type_ref = self._type_reference_for_local(union)

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}[ForyUnion]")
        lines.append(f"{ind}public abstract partial record {type_name}")
        lines.append(f"{ind}{{")
        lines.append(f"{ind}{self.indent_str}private {type_name}()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        lines.append(f"{ind}{self.indent_str}[ForyUnknownCase]")
        lines.append(
            f"{ind}{self.indent_str}public sealed partial record Unknown(UnknownCase Value) : {type_name};"
        )
        lines.append("")

        for field in union.fields:
            case_name = self._union_case_type_name(field)
            case_type = self.generate_type(
                field.field_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            schema_type = self._schema_type_hint(field.field_type)
            if schema_type:
                lines.append(
                    f"{ind}{self.indent_str}[ForyCase({field.number}, Type = typeof({schema_type}))]"
                )
            else:
                lines.append(f"{ind}{self.indent_str}[ForyCase({field.number})]")
            lines.append(
                f"{ind}{self.indent_str}public sealed partial record {case_name}({case_type} Value) : {type_name};"
            )
            lines.append("")

        lines.append(f"{ind}{self.indent_str}public byte[] ToBytes()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {module_class}.GetFory().Serialize(this);"
        )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")
        lines.append(
            f"{ind}{self.indent_str}public static {type_name} FromBytes(byte[] data)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {module_class}.GetFory().Deserialize<{full_type_ref}>(data);"
        )
        lines.append(f"{ind}{self.indent_str}}}")

        lines.append(f"{ind}}}")
        return lines

    def generate_message(
        self,
        message: Message,
        indent: int = 0,
        parent_stack: Optional[List[Message]] = None,
    ) -> List[str]:
        lines: List[str] = []
        ind = self.indent_str * indent
        parent_stack = parent_stack or []
        lineage = parent_stack + [message]
        module_class = self.get_module_class_name()
        type_name = self.safe_type_identifier(message.name)
        full_type_ref = self._type_reference_for_local(message)

        comment = self.format_type_id_comment(message, f"{ind}//")
        if comment:
            lines.append(comment)
        if self.get_effective_evolving(message):
            lines.append(f"{ind}[ForyStruct]")
        else:
            lines.append(f"{ind}[ForyStruct(Evolving = false)]")
        lines.append(f"{ind}public sealed partial class {type_name}")
        lines.append(f"{ind}{{")

        for nested_enum in message.nested_enums:
            lines.append("")
            lines.extend(self.generate_enum(nested_enum, indent + 1))

        for nested_union in message.nested_unions:
            lines.append("")
            lines.extend(self.generate_union(nested_union, indent + 1, lineage))

        for nested_msg in message.nested_messages:
            lines.append("")
            lines.extend(self.generate_message(nested_msg, indent + 1, lineage))

        used_field_names: Set[str] = set()
        for field in message.fields:
            lines.append("")
            schema_type = self._schema_type_hint(field.field_type)
            if schema_type:
                lines.append(
                    f"{ind}{self.indent_str}[ForyField({field.number}, Type = typeof({schema_type}))]"
                )
            else:
                lines.append(f"{ind}{self.indent_str}[ForyField({field.number})]")
            field_name = self._field_member_name(field, message, used_field_names)
            field_type = self.generate_type(
                field.field_type,
                nullable=field.optional,
                parent_stack=lineage,
            )
            init = self._default_initializer(field, lineage) or ""
            lines.append(
                f"{ind}{self.indent_str}public {field_type} {field_name} {{ get; set; }}{init}"
            )

        lines.append("")
        lines.append(f"{ind}{self.indent_str}public byte[] ToBytes()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {module_class}.GetFory().Serialize(this);"
        )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")
        lines.append(
            f"{ind}{self.indent_str}public static {type_name} FromBytes(byte[] data)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {module_class}.GetFory().Deserialize<{full_type_ref}>(data);"
        )
        lines.append(f"{ind}{self.indent_str}}}")

        lines.append(f"{ind}}}")
        return lines

    def _register_type_lines(
        self,
        type_def: TypingUnion[Message, Enum, Union],
        target_var: str,
    ) -> List[str]:
        type_ref = self._type_reference_for_local(type_def)
        type_name = self._qualified_type_names.get(id(type_def), type_def.name)
        if self.should_register_by_id(type_def):
            return [f"{target_var}.Register<{type_ref}>((uint){type_def.type_id});"]

        namespace_name = self.schema.package or "default"
        return [
            f'{target_var}.Register<{type_ref}>("{namespace_name}", "{type_name}");'
        ]

    def _collect_local_types(self) -> List[TypingUnion[Message, Enum, Union]]:
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

    def generate_module_class(self) -> List[str]:
        lines: List[str] = []
        class_name = self.safe_type_identifier(self.get_module_class_name())
        imported_modules = self._collect_imported_modules()
        local_types = self._collect_local_types()

        lines.append(f"public static class {class_name}")
        lines.append("{")
        lines.append(
            f"{self.indent_str}private static readonly Lazy<ThreadSafeFory> LazyFory = new(CreateFory);"
        )
        lines.append("")
        lines.append(f"{self.indent_str}internal static ThreadSafeFory GetFory()")
        lines.append(f"{self.indent_str}{{")
        lines.append(f"{self.indent_str * 2}return LazyFory.Value;")
        lines.append(f"{self.indent_str}}}")
        lines.append("")

        lines.append(f"{self.indent_str}private static ThreadSafeFory CreateFory()")
        lines.append(f"{self.indent_str}{{")
        lines.append(
            f"{self.indent_str * 2}ThreadSafeFory fory = Fory.Builder().TrackRef(true).BuildThreadSafe();"
        )
        lines.append(f"{self.indent_str * 2}Install(fory);")
        lines.append(f"{self.indent_str * 2}return fory;")
        lines.append(f"{self.indent_str}}}")
        lines.append("")

        lines.append(f"{self.indent_str}public static void Install(Fory fory)")
        lines.append(f"{self.indent_str}{{")
        for namespace_name, module_name in imported_modules:
            if (
                namespace_name == self.get_csharp_namespace()
                and module_name == class_name
            ):
                continue
            lines.append(
                f"{self.indent_str * 2}global::{namespace_name}.{self.safe_type_identifier(module_name)}.Install(fory);"
            )
        for type_def in local_types:
            for register_line in self._register_type_lines(type_def, "fory"):
                lines.append(f"{self.indent_str * 2}{register_line}")
        lines.append(f"{self.indent_str}}}")
        lines.append("")

        lines.append(
            f"{self.indent_str}public static void Install(ThreadSafeFory fory)"
        )
        lines.append(f"{self.indent_str}{{")
        for namespace_name, module_name in imported_modules:
            if (
                namespace_name == self.get_csharp_namespace()
                and module_name == class_name
            ):
                continue
            lines.append(
                f"{self.indent_str * 2}global::{namespace_name}.{self.safe_type_identifier(module_name)}.Install(fory);"
            )
        for type_def in local_types:
            for register_line in self._register_type_lines(type_def, "fory"):
                lines.append(f"{self.indent_str * 2}{register_line}")
        lines.append(f"{self.indent_str}}}")

        lines.append("}")
        return lines
