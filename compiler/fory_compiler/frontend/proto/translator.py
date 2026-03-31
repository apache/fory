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

"""Translate Proto AST into Fory IR."""

from typing import Dict, List, Optional, Tuple

from fory_compiler.frontend.proto.ast import (
    ProtoSchema,
    ProtoMessage,
    ProtoEnum,
    ProtoField,
    ProtoType,
    ProtoOneof,
    ProtoService,
    ProtoRpcMethod,
)
from fory_compiler.ir.ast import (
    Schema,
    Service,
    RpcMethod,
    Message,
    Enum,
    Union,
    EnumValue,
    Field,
    FieldType,
    Import,
    PrimitiveType,
    NamedType,
    ListType,
    MapType,
    SourceLocation,
)
from fory_compiler.ir.types import PrimitiveKind


class TranslationError(Exception):
    """Raised when a type reference cannot be resolved during proto translation."""

    def __init__(self, message: str, line: int = 0, column: int = 0) -> None:
        super().__init__(message)
        self.line = line
        self.column = column


class ProtoTranslator:
    """Translate Proto AST to Fory IR.

    Accepts an optional list of *direct* import proto schemas so that type
    references are resolved to fully-qualified names and import-visibility is enforced during
    translation.
    Types from transitively-imported files (not in `direct_import_proto_schemas`)
    are absent from the symbol table and will cause a resolution error, matching protoc semantics.
    """

    TYPE_MAPPING: Dict[str, PrimitiveKind] = {
        "bool": PrimitiveKind.BOOL,
        "int8": PrimitiveKind.INT8,
        "int16": PrimitiveKind.INT16,
        "int32": PrimitiveKind.VAR_UINT32,
        "int64": PrimitiveKind.VAR_UINT64,
        "sint32": PrimitiveKind.VARINT32,
        "sint64": PrimitiveKind.VARINT64,
        "uint8": PrimitiveKind.UINT8,
        "uint16": PrimitiveKind.UINT16,
        "uint32": PrimitiveKind.VAR_UINT32,
        "uint64": PrimitiveKind.VAR_UINT64,
        "fixed32": PrimitiveKind.UINT32,
        "fixed64": PrimitiveKind.UINT64,
        "sfixed32": PrimitiveKind.INT32,
        "sfixed64": PrimitiveKind.INT64,
        "float16": PrimitiveKind.FLOAT16,
        "float": PrimitiveKind.FLOAT32,
        "double": PrimitiveKind.FLOAT64,
        "string": PrimitiveKind.STRING,
        "bytes": PrimitiveKind.BYTES,
    }

    WELL_KNOWN_TYPES: Dict[str, PrimitiveKind] = {
        "google.protobuf.Timestamp": PrimitiveKind.TIMESTAMP,
        "google.protobuf.Duration": PrimitiveKind.DURATION,
        "google.protobuf.Any": PrimitiveKind.ANY,
    }

    TYPE_OVERRIDES: Dict[str, PrimitiveKind] = {
        "tagged_int64": PrimitiveKind.TAGGED_INT64,
        "tagged_uint64": PrimitiveKind.TAGGED_UINT64,
    }

    def __init__(
        self,
        proto_schema: ProtoSchema,
        direct_import_proto_schemas: Optional[List[ProtoSchema]] = None,
    ):
        self.proto_schema = proto_schema
        self.direct_import_proto_schemas: List[ProtoSchema] = (
            direct_import_proto_schemas or []
        )
        self.warnings: List[str] = []
        # symbol table: fully-qualified name -> (source_file, package).
        # Only own file and directly-imported files are included in the symbol table while transitively-imported are excluded.
        self._symbol_table: Dict[str, Tuple[str, Optional[str]]] = (
            self._build_symbol_table()
        )

    def _build_symbol_table(self) -> Dict[str, Tuple[str, Optional[str]]]:
        table: Dict[str, Tuple[str, Optional[str]]] = {}
        own_file = self.proto_schema.source_file or "<input>"
        own_pkg = self.proto_schema.package
        self._collect_proto_message_qualified_names(
            self.proto_schema.messages, own_pkg, "", own_file, table
        )
        self._collect_proto_enum_qualified_names(
            self.proto_schema.enums, own_pkg, "", own_file, table
        )
        for imp_ps in self.direct_import_proto_schemas:
            imp_file = imp_ps.source_file or "<import>"
            imp_pkg = imp_ps.package
            self._collect_proto_message_qualified_names(
                imp_ps.messages, imp_pkg, "", imp_file, table
            )
            self._collect_proto_enum_qualified_names(
                imp_ps.enums, imp_pkg, "", imp_file, table
            )
        return table

    def _collect_proto_message_qualified_names(
        self,
        messages: List[ProtoMessage],
        package: Optional[str],
        parent_path: str,
        source_file: str,
        table: Dict[str, Tuple[str, Optional[str]]],
    ) -> None:
        for msg in messages:
            path = f"{parent_path}.{msg.name}" if parent_path else msg.name
            qualified_name = f"{package}.{path}" if package else path
            table[qualified_name] = (source_file, package)
            # Handle nested messages.
            self._collect_proto_message_qualified_names(
                msg.nested_messages, package, path, source_file, table
            )
            # Handle nested enums.
            self._collect_proto_enum_qualified_names(
                msg.nested_enums, package, path, source_file, table
            )

    def _collect_proto_enum_qualified_names(
        self,
        enums: List[ProtoEnum],
        package: Optional[str],
        parent_path: str,
        source_file: str,
        table: Dict[str, Tuple[str, Optional[str]]],
    ) -> None:
        for enum in enums:
            path = f"{parent_path}.{enum.name}" if parent_path else enum.name
            qualified_name = f"{package}.{path}" if package else path
            table[qualified_name] = (source_file, package)

    def _resolve_ref(
        self,
        raw_name: str,
        enclosing_path: List[str],
        line: int,
        column: int,
    ) -> str:
        """Resolve a proto type-reference string to its fully-qualified name.

        Raise `TranslationError` if the name cannot be resolved or is not
        visible (i.e. not in own file or a directly-imported files).
        """
        cleaned = raw_name.lstrip(".")
        is_absolute = raw_name.startswith(".")

        if is_absolute:
            # Absolute names (with leading dot) are looked up directly,
            # e.g.: ".com.example.Foo" -> "com.example.Foo".
            if cleaned in self._symbol_table:
                return cleaned
            raise TranslationError(f"Unknown type '{raw_name}'", line, column)

        parts = cleaned.split(".")
        own_pkg = self.proto_schema.package

        # Build scope-prefix list from innermost to outermost scope.
        # Ref: https://protobuf.dev/programming-guides/proto3/#name-resolution
        # e.g., for a reference inside package "com.example", message "Outer", nested
        # message "Inner" (enclosing_path = ["Outer", "Inner"]) the prefixes are:
        # ["com.example.Outer.Inner", "com.example.Outer", "com.example"].
        scope_prefixes: List[Optional[str]] = []
        for depth in range(len(enclosing_path), -1, -1):
            scope_parts = enclosing_path[:depth]
            if scope_parts:
                inner = ".".join(scope_parts)
                scope_prefixes.append(f"{own_pkg}.{inner}" if own_pkg else inner)
            else:
                scope_prefixes.append(own_pkg)

        for prefix in scope_prefixes:
            first_qualified_name = f"{prefix}.{parts[0]}" if prefix else parts[0]
            if first_qualified_name not in self._symbol_table:
                continue

            if len(parts) == 1:
                return first_qualified_name

            current = first_qualified_name
            for part in parts[1:]:
                nxt = f"{current}.{part}"
                if nxt not in self._symbol_table:
                    raise TranslationError(
                        f"Nested type '{part}' not found in '{current}'; "
                        f"cannot resolve '{raw_name}'",
                        line,
                        column,
                    )
                current = nxt
            return current

        if cleaned in self._symbol_table:
            return cleaned

        raise TranslationError(f"Unknown type '{raw_name}'", line, column)

    def _location(self, line: int, column: int) -> SourceLocation:
        return SourceLocation(
            file=self.proto_schema.source_file or "<input>",
            line=line,
            column=column,
            source_format="proto",
        )

    def translate(self) -> Schema:
        # Collect the file_packages mapping so the merged schema can do fully-qualified name lookup later
        file_packages: Dict[str, Optional[str]] = {
            self.proto_schema.source_file or "<input>": self.proto_schema.package
        }
        for imp_ps in self.direct_import_proto_schemas:
            imp_file = imp_ps.source_file or "<import>"
            file_packages[imp_file] = imp_ps.package

        return Schema(
            package=self.proto_schema.package,
            package_alias=None,
            imports=self._translate_imports(),
            enums=[self._translate_enum(e) for e in self.proto_schema.enums],
            messages=[
                self._translate_message(m, []) for m in self.proto_schema.messages
            ],
            services=[self._translate_service(s) for s in self.proto_schema.services],
            options=self._translate_file_options(self.proto_schema.options),
            source_file=self.proto_schema.source_file,
            source_format="proto",
            file_packages=file_packages,
        )

    def _translate_imports(self) -> List[Import]:
        return [Import(path=imp) for imp in self.proto_schema.imports]

    def _translate_file_options(self, options: Dict[str, object]) -> Dict[str, object]:
        translated = {}
        for name, value in options.items():
            if name.startswith("fory."):
                translated[name.removeprefix("fory.")] = value
            else:
                translated[name] = value
        return translated

    def _translate_enum(self, proto_enum: ProtoEnum) -> Enum:
        type_id, options = self._translate_type_options(proto_enum.options)
        values = [
            EnumValue(
                name=v.name,
                value=v.value,
                line=v.line,
                column=v.column,
                location=self._location(v.line, v.column),
            )
            for v in proto_enum.values
        ]
        return Enum(
            name=proto_enum.name,
            type_id=type_id,
            values=values,
            options=options,
            line=proto_enum.line,
            column=proto_enum.column,
            location=self._location(proto_enum.line, proto_enum.column),
        )

    def _translate_message(
        self, proto_msg: ProtoMessage, enclosing_path: List[str]
    ) -> Message:
        type_id, options = self._translate_type_options(proto_msg.options)
        msg_path = enclosing_path + [proto_msg.name]
        fields = [self._translate_field(f, msg_path) for f in proto_msg.fields]
        nested_unions = []
        for oneof in proto_msg.oneofs:
            oneof_type_name = self._oneof_type_name(oneof.name)
            nested_unions.append(
                self._translate_oneof(oneof, oneof_type_name, proto_msg, msg_path)
            )
            if not oneof.fields:
                continue
            union_field = self._translate_oneof_field_reference(oneof, oneof_type_name)
            fields.append(union_field)
        nested_messages = [
            self._translate_message(m, msg_path) for m in proto_msg.nested_messages
        ]
        nested_enums = [self._translate_enum(e) for e in proto_msg.nested_enums]
        return Message(
            name=proto_msg.name,
            type_id=type_id,
            fields=fields,
            nested_messages=nested_messages,
            nested_enums=nested_enums,
            nested_unions=nested_unions,
            options=options,
            line=proto_msg.line,
            column=proto_msg.column,
            location=self._location(proto_msg.line, proto_msg.column),
        )

    def _translate_field(
        self, proto_field: ProtoField, enclosing_path: List[str]
    ) -> Field:
        field_type = self._translate_field_type(proto_field.field_type, enclosing_path)
        ref, nullable, options, type_override = self._translate_field_options(
            proto_field.options
        )
        if type_override is not None:
            field_type = self._apply_type_override(
                field_type, type_override, proto_field.line, proto_field.column
            )

        if proto_field.label == "repeated":
            field_type = ListType(
                field_type,
                location=self._location(proto_field.line, proto_field.column),
            )
        optional = proto_field.label == "optional" or nullable
        element_ref = False
        ref_options = self._extract_ref_options(options)
        if ref_options.get("weak_ref") is True and not ref:
            ref = True
        field_ref_options: Dict[str, object] = {}
        element_ref_options: Dict[str, object] = {}
        if ref and isinstance(field_type, ListType):
            element_ref = True
            element_ref_options = ref_options
            field_type.element_ref = True
            field_type.element_ref_options = ref_options
            ref = False
        if ref and isinstance(field_type, MapType):
            field_type = MapType(
                field_type.key_type,
                field_type.value_type,
                value_ref=True,
                value_ref_options=ref_options,
                location=field_type.location,
            )
            ref = False
        elif isinstance(field_type, MapType) and ref_options:
            field_type = MapType(
                field_type.key_type,
                field_type.value_type,
                value_ref=field_type.value_ref,
                value_ref_options=ref_options,
                location=field_type.location,
            )

        if not isinstance(field_type, (ListType, MapType)) and ref_options:
            field_ref_options = ref_options

        return Field(
            name=proto_field.name,
            field_type=field_type,
            number=proto_field.number,
            tag_id=proto_field.number,
            optional=optional,
            ref=ref,
            ref_options=field_ref_options,
            element_ref=element_ref,
            element_ref_options=element_ref_options,
            options=options,
            line=proto_field.line,
            column=proto_field.column,
            location=self._location(proto_field.line, proto_field.column),
        )

    def _oneof_type_name(self, oneof_name: str) -> str:
        segments = [segment for segment in oneof_name.split("_") if segment]
        if not segments:
            return oneof_name[:1].upper() + oneof_name[1:]
        return "".join(segment[:1].upper() + segment[1:] for segment in segments)

    def _translate_oneof(
        self,
        oneof: ProtoOneof,
        oneof_type_name: str,
        _parent: ProtoMessage,
        enclosing_path: List[str],
    ) -> Union:
        fields = [self._translate_oneof_case(f, enclosing_path) for f in oneof.fields]
        return Union(
            name=oneof_type_name,
            type_id=None,
            fields=fields,
            options={},
            line=oneof.line,
            column=oneof.column,
            location=self._location(oneof.line, oneof.column),
        )

    def _translate_oneof_case(
        self, proto_field: ProtoField, enclosing_path: List[str]
    ) -> Field:
        field_type = self._translate_field_type(proto_field.field_type, enclosing_path)
        ref, _nullable, options, type_override = self._translate_field_options(
            proto_field.options
        )
        if type_override is not None:
            field_type = self._apply_type_override(
                field_type, type_override, proto_field.line, proto_field.column
            )

        return Field(
            name=proto_field.name,
            field_type=field_type,
            number=proto_field.number,
            optional=False,
            ref=ref,
            options=options,
            line=proto_field.line,
            column=proto_field.column,
            location=self._location(proto_field.line, proto_field.column),
        )

    def _translate_oneof_field_reference(
        self, oneof: ProtoOneof, oneof_type_name: str
    ) -> Field:
        first_case = min(oneof.fields, key=lambda f: f.number)
        return Field(
            name=oneof.name,
            field_type=NamedType(
                oneof_type_name, location=self._location(oneof.line, oneof.column)
            ),
            number=first_case.number,
            optional=True,
            ref=False,
            options={},
            line=oneof.line,
            column=oneof.column,
            location=self._location(oneof.line, oneof.column),
        )

    def _translate_field_type(
        self, proto_type: ProtoType, enclosing_path: List[str]
    ) -> FieldType:
        if proto_type.is_map:
            key_type = self._translate_type_name(
                proto_type.map_key_type or "", [], proto_type.line, proto_type.column
            )
            value_type = self._translate_type_name(
                proto_type.map_value_type or "",
                enclosing_path,
                proto_type.line,
                proto_type.column,
            )
            return MapType(
                key_type,
                value_type,
                location=self._location(proto_type.line, proto_type.column),
            )
        return self._translate_type_name(
            proto_type.name, enclosing_path, proto_type.line, proto_type.column
        )

    def _translate_type_name(
        self,
        type_name: str,
        enclosing_path: List[str],
        line: int = 0,
        column: int = 0,
    ) -> FieldType:
        cleaned = type_name.lstrip(".")
        if cleaned in self.WELL_KNOWN_TYPES:
            return PrimitiveType(
                self.WELL_KNOWN_TYPES[cleaned],
                location=self._location(line, column),
            )
        if cleaned in self.TYPE_MAPPING:
            return PrimitiveType(
                self.TYPE_MAPPING[cleaned],
                location=self._location(line, column),
            )
        # Resolve user-defined type reference to its fully-qualified name.
        try:
            qualified_name = self._resolve_ref(type_name, enclosing_path, line, column)
        except TranslationError as exc:
            from fory_compiler.frontend.base import FrontendError

            raise FrontendError(
                str(exc),
                self.proto_schema.source_file or "<input>",
                exc.line,
                exc.column,
            ) from exc
        # Compute display_name: the name that code generators should use as output type string.
        cleaned = type_name.lstrip(".")
        _, type_pkg = self._symbol_table.get(qualified_name, (None, None))
        own_pkg = self.proto_schema.package
        if type_pkg == own_pkg:
            # Same package: use the written reference, minus any redundant package prefix.
            if own_pkg and cleaned.startswith(own_pkg + "."):
                display_name: Optional[str] = cleaned[len(own_pkg) + 1 :]
            else:
                display_name = cleaned
        else:
            # Cross-package: strip the type's package prefix so generators get the type-local path.
            if type_pkg and qualified_name.startswith(type_pkg + "."):
                display_name = qualified_name[len(type_pkg) + 1 :]
            else:
                display_name = qualified_name
        return NamedType(
            qualified_name,
            location=self._location(line, column),
            display_name=display_name,
        )

    def _translate_type_options(
        self, options: Dict[str, object]
    ) -> Tuple[Optional[int], Dict[str, object]]:
        type_id = None
        translated: Dict[str, object] = {}
        for name, value in options.items():
            if name == "fory.id":
                type_id = value
            elif name.startswith("fory."):
                translated[name.removeprefix("fory.")] = value
            else:
                translated[name] = value
        return type_id, translated

    def _translate_field_options(
        self, options: Dict[str, object]
    ) -> Tuple[bool, bool, Dict[str, object], Optional[PrimitiveKind]]:
        ref = False
        nullable = False
        translated: Dict[str, object] = {}
        type_override: Optional[PrimitiveKind] = None
        for name, value in options.items():
            if name == "fory.ref" and value:
                ref = True
            elif name == "fory.nullable" and value:
                nullable = True
            elif name == "fory.type":
                if not isinstance(value, str):
                    raise ValueError("fory.type must be a string")
                override = self.TYPE_OVERRIDES.get(value)
                if override is None:
                    raise ValueError(f"Unsupported fory.type override '{value}'")
                type_override = override
            elif name.startswith("fory."):
                translated[name.removeprefix("fory.")] = value
        return ref, nullable, translated, type_override

    def _extract_ref_options(self, options: Dict[str, object]) -> Dict[str, object]:
        ref_options: Dict[str, object] = {}
        weak_ref = options.get("weak_ref")
        if weak_ref is not None:
            ref_options["weak_ref"] = weak_ref
        thread_safe = options.get("thread_safe_pointer")
        if thread_safe is not None:
            ref_options["thread_safe_pointer"] = thread_safe
        return ref_options

    def _apply_type_override(
        self,
        field_type: FieldType,
        override: PrimitiveKind,
        line: int,
        column: int,
    ) -> FieldType:
        if isinstance(field_type, PrimitiveType):
            return PrimitiveType(override, location=self._location(line, column))
        raise ValueError("fory.type overrides are only supported for primitive fields")

    def _translate_service(self, proto_service: ProtoService) -> Service:
        # Translate ProtoService to Service
        _, options = self._translate_type_options(proto_service.options)
        return Service(
            name=proto_service.name,
            methods=[self._translate_rpc_method(m) for m in proto_service.methods],
            options=options,
            line=proto_service.line,
            column=proto_service.column,
            location=self._location(proto_service.line, proto_service.column),
        )

    def _translate_rpc_method(self, proto_method: ProtoRpcMethod) -> RpcMethod:
        # Translate ProtoRpcMethod to RpcMethod
        _, options = self._translate_type_options(proto_method.options)
        req_type = self._translate_type_name(
            proto_method.request_type, [], proto_method.line, proto_method.column
        )
        resp_type = self._translate_type_name(
            proto_method.response_type, [], proto_method.line, proto_method.column
        )
        return RpcMethod(
            name=proto_method.name,
            request_type=req_type,
            response_type=resp_type,
            client_streaming=proto_method.client_streaming,
            server_streaming=proto_method.server_streaming,
            options=options,
            line=proto_method.line,
            column=proto_method.column,
            location=self._location(proto_method.line, proto_method.column),
        )
