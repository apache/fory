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

from __future__ import annotations

from pyfory.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG
from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import Serializer
else:
    from pyfory._serializer import Serializer


class Union:
    __slots__ = ("_case_id", "_value")

    def __init__(self, case_id: int, value: object) -> None:
        self._case_id = case_id
        self._value = value

    def case_id(self) -> int:
        return self._case_id

    def value(self) -> object:
        return self._value

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(case_id={self._case_id}, value={self._value})"


class UnionSerializer(Serializer):
    """
    Serializer for generated union classes and typing.Union.

    For generated unions, the payload is:
    | case_id (varuint32) | case_value (Any-style value) |
    """

    __slots__ = (
        "type_resolver",
        "_typing_union",
        "_alternative_types",
        "_alternative_serializers",
        "_case_types",
        "_case_type_infos",
    )

    def __init__(self, type_resolver, type_, alternative_types):
        super().__init__(type_resolver, type_)
        self.type_resolver = type_resolver
        if isinstance(alternative_types, dict):
            self._typing_union = False
            self._case_types = alternative_types
            self._case_type_infos = {}
            self._alternative_types = None
            self._alternative_serializers = None
        else:
            self._typing_union = True
            self._alternative_types = alternative_types
            self._case_types = None
            self._case_type_infos = None
            self._alternative_serializers = []
            for alt_type in alternative_types:
                serializer = type_resolver.get_serializer(alt_type)
                self._alternative_serializers.append((alt_type, serializer))

    def write(self, write_context, value):
        if self._typing_union:
            self._write_typing_union(write_context, value)
            return
        case_id = value.case_id()
        write_context.write_var_uint32(case_id)
        typeinfo = self._get_case_type_info(case_id)
        serializer = typeinfo.serializer
        if serializer.need_to_write_ref:
            if write_context.write_ref_or_null(value._value):
                return
        else:
            if value._value is None:
                write_context.write_int8(NULL_FLAG)
                return
            write_context.write_int8(NOT_NULL_VALUE_FLAG)
        self.type_resolver.write_type_info(write_context, typeinfo)
        serializer.write(write_context, value._value)

    def read(self, read_context):
        if self._typing_union:
            return self._read_typing_union(read_context)
        case_id = read_context.read_var_uint32()
        typeinfo = self._get_case_type_info(case_id)
        serializer = typeinfo.serializer
        if serializer.need_to_write_ref:
            ref_id = read_context.try_preserve_ref_id()
            if ref_id < NOT_NULL_VALUE_FLAG:
                value = read_context.get_read_ref()
                return self._build_union(case_id, value)
            self.type_resolver.read_type_info(read_context)
            value = self._read_case_payload(read_context, serializer)
            read_context.set_read_ref(ref_id, value)
        else:
            if read_context.read_int8() == NULL_FLAG:
                value = None
            else:
                self.type_resolver.read_type_info(read_context)
                value = self._read_case_payload(read_context, serializer)
        return self._build_union(case_id, value)

    def _read_case_payload(self, read_context, serializer):
        read_context.increase_depth()
        try:
            return serializer.read(read_context)
        finally:
            read_context.decrease_depth()

    def _get_case_type_info(self, case_id: int):
        typeinfo = self._case_type_infos.get(case_id)
        if typeinfo is None:
            case_type = self._case_types.get(case_id)
            if case_type is None:
                raise ValueError(f"unknown union case id: {case_id}")
            try:
                typeinfo = self.type_resolver.get_type_info(case_type)
            except (AttributeError, TypeError):
                from pyfory.registry import TypeInfo
                from pyfory.struct import StructFieldSerializerVisitor
                from pyfory.type_util import infer_field

                serializer = infer_field(
                    "union_case",
                    case_type,
                    StructFieldSerializerVisitor(self.type_resolver),
                )
                if serializer is None:
                    raise TypeError(f"union case type {case_type} is not registered")
                declared_typeinfo = self.type_resolver.get_type_info(serializer.type_)
                typeinfo = TypeInfo(
                    case_type,
                    declared_typeinfo.type_id,
                    declared_typeinfo.user_type_id,
                    serializer,
                    declared_typeinfo.namespace_bytes,
                    declared_typeinfo.typename_bytes,
                    declared_typeinfo.dynamic_type,
                    declared_typeinfo.type_def,
                )
            self._case_type_infos[case_id] = typeinfo
        return typeinfo

    def _build_union(self, case_id: int, value: object):
        if case_id not in self._case_types:
            raise ValueError(f"unknown union case id: {case_id}")
        builder = getattr(self.type_, "_from_case_id", None)
        if builder is None:
            raise TypeError(f"{self.type_} must define _from_case_id for union deserialization")
        return builder(case_id, value)

    def _write_typing_union(self, write_context, value):
        active_index = None
        active_serializer = None
        active_type = None

        for i, (alt_type, serializer) in enumerate(self._alternative_serializers):
            if isinstance(value, alt_type):
                active_index = i
                active_serializer = serializer
                active_type = alt_type
                break

        if active_index is None:
            raise TypeError(f"Value {value} of type {type(value)} doesn't match any alternative in Union{self._alternative_types}")

        write_context.write_var_uint32(active_index)
        typeinfo = self.type_resolver.get_type_info(active_type)
        self.type_resolver.write_type_info(write_context, typeinfo)
        active_serializer.write(write_context, value)

    def _read_typing_union(self, read_context):
        stored_index = read_context.read_var_uint32()
        if stored_index >= len(self._alternative_serializers):
            raise ValueError(f"Union index out of bounds: {stored_index} (max: {len(self._alternative_serializers) - 1})")
        typeinfo = self.type_resolver.read_type_info(read_context)
        return typeinfo.serializer.read(read_context)
