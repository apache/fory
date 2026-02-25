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


class CSharpGenerator(BaseGenerator):
    """Generates C# models and registration helpers for Apache Fory."""

    language_name = "csharp"
    file_extension = ".cs"

    PRIMITIVE_MAP = {
        PrimitiveKind.BOOL: "bool",
        PrimitiveKind.INT8: "sbyte",
        PrimitiveKind.INT16: "short",
        PrimitiveKind.INT32: "int",
        PrimitiveKind.VARINT32: "int",
        PrimitiveKind.INT64: "long",
        PrimitiveKind.VARINT64: "long",
        PrimitiveKind.TAGGED_INT64: "long",
        PrimitiveKind.UINT8: "byte",
        PrimitiveKind.UINT16: "ushort",
        PrimitiveKind.UINT32: "uint",
        PrimitiveKind.VAR_UINT32: "uint",
        PrimitiveKind.UINT64: "ulong",
        PrimitiveKind.VAR_UINT64: "ulong",
        PrimitiveKind.TAGGED_UINT64: "ulong",
        PrimitiveKind.FLOAT16: "float",
        PrimitiveKind.BFLOAT16: "float",
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
        PrimitiveKind.BFLOAT16,
        PrimitiveKind.FLOAT32,
        PrimitiveKind.FLOAT64,
        PrimitiveKind.DATE,
        PrimitiveKind.TIMESTAMP,
        PrimitiveKind.DURATION,
        PrimitiveKind.DECIMAL,
    }

    CSHARP_KEYWORDS = {
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
        if self.options.package_override:
            return self.options.package_override
        csharp_ns = self.schema.get_option("csharp_namespace")
        if csharp_ns:
            return str(csharp_ns)
        if self.schema.package:
            return self.schema.package
        return "generated"

    def get_registration_class_name(self) -> str:
        return self._registration_class_name_for_namespace(self.get_csharp_namespace())

    def _registration_class_name_for_namespace(self, namespace_name: str) -> str:
        if namespace_name:
            leaf = namespace_name.split(".")[-1]
        else:
            leaf = "generated"
        return f"{self.to_pascal_case(leaf)}ForyRegistration"

    def _module_file_name(self) -> str:
        if self.schema.source_file and not self.schema.source_file.startswith("<"):
            return f"{Path(self.schema.source_file).stem}.cs"
        if self.schema.package:
            return f"{self.schema.package.replace('.', '_')}.cs"
        return "generated.cs"

    def _namespace_path(self, namespace_name: str) -> str:
        return namespace_name.replace(".", "/") if namespace_name else ""

    def safe_identifier(self, name: str) -> str:
        if name in self.CSHARP_KEYWORDS:
            return f"@{name}"
        return name

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
        value = schema.get_option("csharp_namespace")
        if value:
            return str(value)
        if schema.package:
            return schema.package
        return "generated"

    def _csharp_namespace_for_type(self, type_def: object) -> str:
        location = getattr(type_def, "location", None)
        file_path = getattr(location, "file", None) if location else None
        schema = self._load_schema(file_path)
        if schema is None:
            return self.get_csharp_namespace()
        return self._csharp_namespace_for_schema(schema)

    def _collect_imported_registrations(self) -> List[Tuple[str, str]]:
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
            registration_name = self._registration_class_name_for_namespace(
                namespace_name
            )
            file_info[normalized] = (namespace_name, registration_name)

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

        lines.extend(self.generate_registration_class())
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

    def _type_namespace(self, resolved: Optional[TypingUnion[Message, Enum, Union]]) -> str:
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
        return ".".join(self.safe_type_identifier(part) for part in qualified.split("."))

    def _named_type_reference(
        self, named_type: NamedType, parent_stack: Optional[List[Message]] = None
    ) -> str:
        resolved = self._resolve_named_type(named_type.name, parent_stack)
        ns = self._type_namespace(resolved)
        qname = self._qualified_type_name_for(resolved, named_type.name)
        if ns:
            return f"global::{ns}.{qname}"
        return qname

    def _is_value_type(self, field_type: FieldType, parent_stack: List[Message]) -> bool:
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
                raise ValueError(f"Unsupported primitive type for C#: {field_type.kind}")
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

        if isinstance(field_type, MapType):
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
            map_type = f"Dictionary<{key_type}, {value_type}>"
            if nullable:
                return f"{map_type}?"
            return map_type

        raise ValueError(f"Unknown field type: {field_type}")

    def _default_initializer(
        self, field: Field, parent_stack: List[Message]
    ) -> Optional[str]:
        if field.optional:
            return None

        field_type = field.field_type
        if isinstance(field_type, ListType) or isinstance(field_type, MapType):
            return " = new();"

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

    def _field_encoding(self, field: Field) -> Optional[str]:
        field_type = field.field_type
        if not isinstance(field_type, PrimitiveType):
            return None
        kind = field_type.kind
        if kind in {
            PrimitiveKind.INT32,
            PrimitiveKind.INT64,
            PrimitiveKind.UINT32,
            PrimitiveKind.UINT64,
        }:
            return "Fixed"
        if kind in {PrimitiveKind.TAGGED_INT64, PrimitiveKind.TAGGED_UINT64}:
            return "Tagged"
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
        lines.append(f"{ind}[ForyObject]")
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
        case_enum = self.safe_type_identifier(f"{union.name}Case")
        registration_class = self.get_registration_class_name()
        full_type_ref = self._type_reference_for_local(union)

        comment = self.format_type_id_comment(union, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}public sealed class {type_name} : Union")
        lines.append(f"{ind}{{")
        lines.append(f"{ind}{self.indent_str}public enum {case_enum}")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(f"{ind}{self.indent_str * 2}Unknown = 0,")
        for i, field in enumerate(union.fields):
            comma = "," if i < len(union.fields) - 1 else ""
            case_name = self.safe_identifier(self.to_pascal_case(field.name))
            lines.append(
                f"{ind}{self.indent_str * 2}{case_name} = {field.number}{comma}"
            )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        lines.append(
            f"{ind}{self.indent_str}private {type_name}(int index, object? value) : base(index, value)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        lines.append(
            f"{ind}{self.indent_str}public static {type_name} Of(int index, object? value)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return new {type_name}(index, value);"
        )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        for field in union.fields:
            case_name = self.safe_identifier(self.to_pascal_case(field.name))
            case_type = self.generate_type(
                field.field_type,
                nullable=False,
                parent_stack=parent_stack,
            )
            lines.append(
                f"{ind}{self.indent_str}public static {type_name} {case_name}({case_type} value)"
            )
            lines.append(f"{ind}{self.indent_str}{{")
            lines.append(
                f"{ind}{self.indent_str * 2}return new {type_name}({field.number}, value);"
            )
            lines.append(f"{ind}{self.indent_str}}}")
            lines.append("")

            lines.append(f"{ind}{self.indent_str}public bool Is{case_name} => Index == {field.number};")
            lines.append("")
            lines.append(
                f"{ind}{self.indent_str}public {case_type} {case_name}Value()"
            )
            lines.append(f"{ind}{self.indent_str}{{")
            lines.append(f"{ind}{self.indent_str * 2}if (!Is{case_name})")
            lines.append(f"{ind}{self.indent_str * 2}{{")
            lines.append(
                f'{ind}{self.indent_str * 3}throw new InvalidOperationException("Union does not hold case {case_name}");'
            )
            lines.append(f"{ind}{self.indent_str * 2}}}")
            lines.append(f"{ind}{self.indent_str * 2}return GetValue<{case_type}>();")
            lines.append(f"{ind}{self.indent_str}}}")
            lines.append("")

        lines.append(f"{ind}{self.indent_str}public {case_enum} Case()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(f"{ind}{self.indent_str * 2}return Index switch")
        lines.append(f"{ind}{self.indent_str * 2}{{")
        for field in union.fields:
            case_name = self.safe_identifier(self.to_pascal_case(field.name))
            lines.append(
                f"{ind}{self.indent_str * 3}{field.number} => {case_enum}.{case_name},"
            )
        lines.append(f"{ind}{self.indent_str * 3}_ => {case_enum}.Unknown,")
        lines.append(f"{ind}{self.indent_str * 2}}};")
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        lines.append(f"{ind}{self.indent_str}public int CaseId()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(f"{ind}{self.indent_str * 2}return Index;")
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")

        lines.append(f"{ind}{self.indent_str}public byte[] ToBytes()")
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {registration_class}.GetFory().Serialize(this);"
        )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")
        lines.append(
            f"{ind}{self.indent_str}public static {type_name} FromBytes(byte[] data)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {registration_class}.GetFory().Deserialize<{full_type_ref}>(data);"
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
        registration_class = self.get_registration_class_name()
        type_name = self.safe_type_identifier(message.name)
        full_type_ref = self._type_reference_for_local(message)

        comment = self.format_type_id_comment(message, f"{ind}//")
        if comment:
            lines.append(comment)
        lines.append(f"{ind}[ForyObject]")
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
            encoding = self._field_encoding(field)
            if encoding:
                lines.append(
                    f"{ind}{self.indent_str}[Field(Encoding = FieldEncoding.{encoding})]"
                )
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
            f"{ind}{self.indent_str * 2}return {registration_class}.GetFory().Serialize(this);"
        )
        lines.append(f"{ind}{self.indent_str}}}")
        lines.append("")
        lines.append(
            f"{ind}{self.indent_str}public static {type_name} FromBytes(byte[] data)"
        )
        lines.append(f"{ind}{self.indent_str}{{")
        lines.append(
            f"{ind}{self.indent_str * 2}return {registration_class}.GetFory().Deserialize<{full_type_ref}>(data);"
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

    def generate_registration_class(self) -> List[str]:
        lines: List[str] = []
        class_name = self.safe_type_identifier(self.get_registration_class_name())
        imported_regs = self._collect_imported_registrations()
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
            f"{self.indent_str * 2}ThreadSafeFory fory = Fory.Builder().Xlang(true).TrackRef(true).BuildThreadSafe();"
        )
        lines.append(f"{self.indent_str * 2}Register(fory);")
        lines.append(f"{self.indent_str * 2}return fory;")
        lines.append(f"{self.indent_str}}}")
        lines.append("")

        lines.append(f"{self.indent_str}public static void Register(Fory fory)")
        lines.append(f"{self.indent_str}{{")
        for namespace_name, reg_name in imported_regs:
            if namespace_name == self.get_csharp_namespace() and reg_name == class_name:
                continue
            lines.append(
                f"{self.indent_str * 2}global::{namespace_name}.{self.safe_type_identifier(reg_name)}.Register(fory);"
            )
        for type_def in local_types:
            for register_line in self._register_type_lines(type_def, "fory"):
                lines.append(f"{self.indent_str * 2}{register_line}")
        lines.append(f"{self.indent_str}}}")
        lines.append("")

        lines.append(f"{self.indent_str}public static void Register(ThreadSafeFory fory)")
        lines.append(f"{self.indent_str}{{")
        for namespace_name, reg_name in imported_regs:
            if namespace_name == self.get_csharp_namespace() and reg_name == class_name:
                continue
            lines.append(
                f"{self.indent_str * 2}global::{namespace_name}.{self.safe_type_identifier(reg_name)}.Register(fory);"
            )
        for type_def in local_types:
            for register_line in self._register_type_lines(type_def, "fory"):
                lines.append(f"{self.indent_str * 2}{register_line}")
        lines.append(f"{self.indent_str}}}")

        lines.append("}")
        return lines
