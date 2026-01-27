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

"""Translate FlatBuffers AST into Fory IR."""

from typing import Dict, List

from fory_compiler.frontend.fbs.ast import (
    FbsEnum,
    FbsSchema,
    FbsStruct,
    FbsTable,
    FbsTypeName,
    FbsTypeRef,
    FbsVectorType,
    FbsUnion,
)
from fory_compiler.ir.ast import (
    Enum,
    EnumValue,
    Field,
    Import,
    ListType,
    Message,
    NamedType,
    PrimitiveType,
    Schema,
    SourceLocation,
    Union,
)
from fory_compiler.ir.types import PrimitiveKind


class FbsTranslator:
    """Translate FlatBuffers AST to Fory IR."""

    TYPE_MAPPING: Dict[str, PrimitiveKind] = {
        "byte": PrimitiveKind.INT8,
        "ubyte": PrimitiveKind.UINT8,
        "short": PrimitiveKind.INT16,
        "ushort": PrimitiveKind.UINT16,
        "int": PrimitiveKind.VARINT32,
        "uint": PrimitiveKind.VAR_UINT32,
        "long": PrimitiveKind.VARINT64,
        "ulong": PrimitiveKind.VAR_UINT64,
        "float": PrimitiveKind.FLOAT32,
        "double": PrimitiveKind.FLOAT64,
        "bool": PrimitiveKind.BOOL,
        "string": PrimitiveKind.STRING,
    }

    def __init__(self, schema: FbsSchema):
        self.schema = schema

    def _location(self, line: int, column: int) -> SourceLocation:
        return SourceLocation(
            file=self.schema.source_file or "<input>",
            line=line,
            column=column,
            source_format="fbs",
        )

    def translate(self) -> Schema:
        return Schema(
            package=self.schema.namespace,
            imports=[Import(path=inc) for inc in self.schema.includes],
            enums=[self._translate_enum(e) for e in self.schema.enums],
            unions=[self._translate_union(u) for u in self.schema.unions],
            messages=self._translate_messages(),
            options={},
            source_file=self.schema.source_file,
            source_format="fbs",
        )

    def _translate_enum(self, fbs_enum: FbsEnum) -> Enum:
        values = [
            EnumValue(
                name=v.name,
                value=v.value,
                line=v.line,
                column=v.column,
                location=self._location(v.line, v.column),
            )
            for v in fbs_enum.values
        ]
        return Enum(
            name=fbs_enum.name,
            type_id=None,
            values=values,
            options=dict(fbs_enum.attributes),
            line=fbs_enum.line,
            column=fbs_enum.column,
            location=self._location(fbs_enum.line, fbs_enum.column),
        )

    def _translate_messages(self) -> List[Message]:
        messages: List[Message] = []
        ordered = []
        for struct in self.schema.structs:
            ordered.append(("struct", struct.line, struct.column, struct))
        for table in self.schema.tables:
            ordered.append(("table", table.line, table.column, table))
        for kind, _line, _column, item in sorted(ordered, key=lambda v: (v[1], v[2])):
            if kind == "struct":
                messages.append(self._translate_struct(item))
            else:
                messages.append(self._translate_table(item))
        return messages

    def _translate_struct(self, fbs_struct: FbsStruct) -> Message:
        options = dict(fbs_struct.attributes)
        options["evolving"] = False
        fields = self._translate_fields(fbs_struct.fields)
        return Message(
            name=fbs_struct.name,
            type_id=None,
            fields=fields,
            options=options,
            line=fbs_struct.line,
            column=fbs_struct.column,
            location=self._location(fbs_struct.line, fbs_struct.column),
        )

    def _translate_table(self, fbs_table: FbsTable) -> Message:
        options = dict(fbs_table.attributes)
        options["evolving"] = True
        fields = self._translate_fields(fbs_table.fields)
        return Message(
            name=fbs_table.name,
            type_id=None,
            fields=fields,
            options=options,
            line=fbs_table.line,
            column=fbs_table.column,
            location=self._location(fbs_table.line, fbs_table.column),
        )

    def _translate_fields(self, fields) -> List[Field]:
        translated: List[Field] = []
        for index, field in enumerate(fields, start=1):
            field_type = self._translate_type(field.field_type)
            translated.append(
                Field(
                    name=field.name,
                    field_type=field_type,
                    number=index,
                    tag_id=index,
                    options=dict(field.attributes),
                    line=field.line,
                    column=field.column,
                    location=self._location(field.line, field.column),
                )
            )
        return translated

    def _translate_union(self, fbs_union: FbsUnion) -> Union:
        fields: List[Field] = []
        for index, type_name in enumerate(fbs_union.types, start=1):
            field_name = self._lower_name(type_name)
            fields.append(
                Field(
                    name=field_name,
                    field_type=NamedType(
                        type_name,
                        location=self._location(fbs_union.line, fbs_union.column),
                    ),
                    number=index,
                    line=fbs_union.line,
                    column=fbs_union.column,
                    location=self._location(fbs_union.line, fbs_union.column),
                )
            )
        return Union(
            name=fbs_union.name,
            type_id=None,
            fields=fields,
            options=dict(fbs_union.attributes),
            line=fbs_union.line,
            column=fbs_union.column,
            location=self._location(fbs_union.line, fbs_union.column),
        )

    def _lower_name(self, name: str) -> str:
        if not name:
            return name
        if "." in name:
            name = name.split(".")[-1]
        result = []
        for i, char in enumerate(name):
            if char.isupper():
                if i > 0 and (
                    name[i - 1].islower()
                    or (i + 1 < len(name) and name[i + 1].islower())
                ):
                    result.append("_")
                result.append(char.lower())
            else:
                result.append(char)
        return "".join(result)

    def _translate_type(self, fbs_type: FbsTypeRef):
        if isinstance(fbs_type, FbsVectorType):
            element_type = self._translate_type(fbs_type.element_type)
            return ListType(
                element_type, location=self._location(fbs_type.line, fbs_type.column)
            )
        if isinstance(fbs_type, FbsTypeName):
            primitive = self.TYPE_MAPPING.get(fbs_type.name)
            if primitive is not None:
                return PrimitiveType(
                    primitive, location=self._location(fbs_type.line, fbs_type.column)
                )
            return NamedType(
                fbs_type.name, location=self._location(fbs_type.line, fbs_type.column)
            )
        raise ValueError("Unknown FlatBuffers type")
