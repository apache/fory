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


class ProtoTranslator:
    """Translate Proto AST to Fory IR."""

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

    def __init__(self, proto_schema: ProtoSchema):
        self.proto_schema = proto_schema
        self.warnings: List[str] = []

    def _location(self, line: int, column: int) -> SourceLocation:
        return SourceLocation(
            file=self.proto_schema.source_file or "<input>",
            line=line,
            column=column,
            source_format="proto",
        )

    def translate(self) -> Schema:
        return Schema(
            package=self.proto_schema.package,
            package_alias=None,
            imports=self._translate_imports(),
            enums=[self._translate_enum(e) for e in self.proto_schema.enums],
            messages=[self._translate_message(m) for m in self.proto_schema.messages],
            services=[self._translate_service(s) for s in self.proto_schema.services],
            options=self._translate_file_options(self.proto_schema.options),
            source_file=self.proto_schema.source_file,
            source_format="proto",
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

    def _translate_message(self, proto_msg: ProtoMessage) -> Message:
        type_id, options = self._translate_type_options(proto_msg.options)
        fields = [self._translate_field(f) for f in proto_msg.fields]
        nested_unions = []
        for oneof in proto_msg.oneofs:
            oneof_type_name = self._oneof_type_name(oneof.name)
            nested_unions.append(self._translate_oneof(oneof, oneof_type_name, proto_msg))
            if not oneof.fields:
                continue
            union_field = self._translate_oneof_field_reference(
                oneof, oneof_type_name
            )
            fields.append(union_field)
        nested_messages = [
            self._translate_message(m) for m in proto_msg.nested_messages
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

    def _translate_field(self, proto_field: ProtoField) -> Field:
        field_type = self._translate_field_type(proto_field.field_type)
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
    ) -> Union:
        fields = [self._translate_oneof_case(f) for f in oneof.fields]
        return Union(
            name=oneof_type_name,
            type_id=None,
            fields=fields,
            options={},
            line=oneof.line,
            column=oneof.column,
            location=self._location(oneof.line, oneof.column),
        )

    def _translate_oneof_case(self, proto_field: ProtoField) -> Field:
        field_type = self._translate_field_type(proto_field.field_type)
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

    def _translate_field_type(self, proto_type: ProtoType):
        if proto_type.is_map:
            key_type = self._translate_type_name(proto_type.map_key_type or "")
            value_type = self._translate_type_name(proto_type.map_value_type or "")
            return MapType(
                key_type,
                value_type,
                location=self._location(proto_type.line, proto_type.column),
            )
        return self._translate_type_name(
            proto_type.name, proto_type.line, proto_type.column
        )

    def _translate_type_name(self, type_name: str, line: int = 0, column: int = 0):
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
        return NamedType(cleaned, location=self._location(line, column))

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
        return RpcMethod(
            name=proto_method.name,
            request_type=NamedType(
                name=proto_method.request_type,
                location=self._location(proto_method.line, proto_method.column),
            ),
            response_type=NamedType(
                name=proto_method.response_type,
                location=self._location(proto_method.line, proto_method.column),
            ),
            client_streaming=proto_method.client_streaming,
            server_streaming=proto_method.server_streaming,
            options=options,
            line=proto_method.line,
            column=proto_method.column,
            location=self._location(proto_method.line, proto_method.column),
        )
