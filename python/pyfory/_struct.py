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

import datetime
import enum
import logging
import os
import typing

from pyfory.lib.mmh3 import hash_buffer
from pyfory.type import (
    TypeVisitor,
    infer_field,
    TypeId,
    int8,
    int16,
    int32,
    int64,
    float32,
    float64,
    is_py_array_type,
    is_primitive_type,
)

from pyfory.type import (
    is_list_type,
    is_map_type,
    get_primitive_type_size,
    is_polymorphic_type,
)

from pyfory.type import is_subclass

logger = logging.getLogger(__name__)


_TYPE_HASH_SEED = 47


def _extract_primary_type_id(type_ids):
    if isinstance(type_ids, (list, tuple)):
        if not type_ids:
            return TypeId.UNKNOWN
        return type_ids[0]
    return type_ids


def _normalize_type_id(raw_type_id):
    if not isinstance(raw_type_id, int):
        return TypeId.UNKNOWN
    base_type = raw_type_id & 0xFF
    if base_type >= TypeId.BOUND:
        return TypeId.UNKNOWN
    if TypeId.is_namespaced_type(base_type):
        return TypeId.UNKNOWN
    return base_type


def _to_snow_case(name: str) -> str:
    chars = []
    for index, ch in enumerate(name):
        if ch.isupper() and index > 0:
            chars.append("_")
        chars.append(ch.lower())
    return "".join(chars)


basic_types = {
    bool,
    int8,
    int16,
    int32,
    int64,
    float32,
    float64,
    int,
    float,
    str,
    bytes,
    datetime.datetime,
    datetime.date,
    datetime.time,
}


class StructFieldSerializerVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
    ):
        self.fory = fory

    def visit_list(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import ListSerializer  # Local import

        # Infer type recursively for type such as List[Dict[str, str]]
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return ListSerializer(self.fory, list, elem_serializer)

    def visit_set(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import SetSerializer  # Local import

        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return SetSerializer(self.fory, set, elem_serializer)

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        from pyfory.serializer import MapSerializer  # Local import

        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_serializer = infer_field("key", key_type, self, types_path=types_path)
        value_serializer = infer_field("value", value_type, self, types_path=types_path)
        return MapSerializer(self.fory, dict, key_serializer, value_serializer)

    def visit_customized(self, field_name, type_, types_path=None):
        if issubclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        return None

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        if type_ not in basic_types and not is_py_array_type(type_):
            return None
        serializer = self.fory.type_resolver.get_serializer(type_)
        return serializer


_UNKNOWN_TYPE_ID = -1
_time_types = {datetime.date, datetime.datetime, datetime.timedelta}


def _sort_fields(type_resolver, field_names, serializers, nullable_map=None):
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map
    )
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    return [t[2] for t in all_types], [t[1] for t in all_types]


def group_fields(type_resolver, field_names, serializers, nullable_map=None):
    nullable_map = nullable_map or {}
    boxed_types = []
    nullable_boxed_types = []
    collection_types = []
    set_types = []
    map_types = []
    internal_types = []
    other_types = []
    type_ids = []
    for field_name, serializer in zip(field_names, serializers):
        if serializer is None:
            other_types.append((_UNKNOWN_TYPE_ID, serializer, field_name))
        else:
            type_ids.append(
                (
                    type_resolver.get_typeinfo(serializer.type_).type_id & 0xFF,
                    serializer,
                    field_name,
                )
            )
    for type_id, serializer, field_name in type_ids:
        is_nullable = nullable_map.get(field_name, False)
        if is_primitive_type(type_id):
            container = nullable_boxed_types if is_nullable else boxed_types
        elif type_id == TypeId.SET:
            container = set_types
        elif is_list_type(serializer.type_):
            container = collection_types
        elif is_map_type(serializer.type_):
            container = map_types
        elif is_polymorphic_type(type_id) or type_id in {
            TypeId.ENUM,
            TypeId.NAMED_ENUM,
        }:
            container = other_types
        else:
            assert TypeId.LOWER_BOUND < type_id < TypeId.UNKNOWN, (type_id,)
            assert type_id != TypeId.UNKNOWN, serializer
            container = internal_types
        container.append((type_id, serializer, field_name))

    def sorter(item):
        return item[0], item[2]

    def numeric_sorter(item):
        id_ = item[0]
        compress = id_ in {
            TypeId.INT32,
            TypeId.INT64,
            TypeId.VAR_INT32,
            TypeId.VAR_INT64,
        }
        return int(compress), -get_primitive_type_size(id_), item[2]

    boxed_types = sorted(boxed_types, key=numeric_sorter)
    nullable_boxed_types = sorted(nullable_boxed_types, key=numeric_sorter)
    collection_types = sorted(collection_types, key=sorter)
    internal_types = sorted(internal_types, key=sorter)
    map_types = sorted(map_types, key=sorter)
    other_types = sorted(other_types, key=lambda item: item[2])
    return (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types)


def compute_struct_meta(type_resolver, field_names, serializers, nullable_map=None):
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map
    )

    # Build hash string
    hash_parts = []

    # boxed_types => non-nullable
    for field in boxed_types:
        type_id = field[0]
        field_name = field[2]  # already snake_case
        nullable_flag = "0"
        hash_parts.append(f"{field_name},{type_id},{nullable_flag};")

    # All other groups => nullable
    for group in (
        nullable_boxed_types,
        internal_types,
        collection_types,
        set_types,
        map_types,
    ):
        for field in group:
            type_id = field[0]
            field_name = field[2]
            nullable_flag = "1"
            hash_parts.append(f"{field_name},{type_id},{nullable_flag};")
    for field in other_types:
        type_id = TypeId.UNKNOWN
        field_name = field[2]
        nullable_flag = "1"
        hash_parts.append(f"{field_name},{type_id},{nullable_flag};")

    hash_str = "".join(hash_parts)
    hash_bytes = hash_str.encode("utf-8")

    full_hash = hash_buffer(hash_bytes, seed=47)[0]
    type_hash_32 = full_hash & 0xFFFFFFFF
    if full_hash & 0x80000000:
        # If the sign bit is set, it's a negative number in 2's complement
        # Subtract 2^32 to get the correct negative value
        type_hash_32 = type_hash_32 - 0x100000000
    assert type_hash_32 != 0
    if os.environ.get("ENABLE_FORY_DEBUG_OUTPUT", "").lower() in ("1", "true"):
        print(f'[fory-debug] struct version fingerprint="{hash_str}" version hash={type_hash_32}')

    # Flatten all groups in correct order (already sorted from group_fields)
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    sorted_field_names = [f[2] for f in all_types]
    sorted_serializers = [f[1] for f in all_types]

    return type_hash_32, sorted_field_names, sorted_serializers


class StructTypeIdVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
        cls,
    ):
        self.fory = fory
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.LIST, elem_ids

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.SET, elem_ids

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_ids = infer_field("key", key_type, self, types_path=types_path)
        value_ids = infer_field("value", value_type, self, types_path=types_path)
        return TypeId.MAP, key_ids, value_ids

    def visit_customized(self, field_name, type_, types_path=None):
        typeinfo = self.fory.type_resolver.get_typeinfo(type_, create=False)
        if typeinfo is None:
            return [TypeId.UNKNOWN]
        return [typeinfo.type_id]

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return [self.fory.type_resolver.get_typeinfo(type_).type_id]
        if type_ not in basic_types and not is_py_array_type(type_):
            return None, None
        typeinfo = self.fory.type_resolver.get_typeinfo(type_)
        return [typeinfo.type_id]


class StructTypeVisitor(TypeVisitor):
    def __init__(self, cls):
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.List, elem_types

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.Set, elem_types

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_types = infer_field("key", key_type, self, types_path=types_path)
        value_types = infer_field("value", value_type, self, types_path=types_path)
        return typing.Dict, key_types, value_types

    def visit_customized(self, field_name, type_, types_path=None):
        return [type_]

    def visit_other(self, field_name, type_, types_path=None):
        return [type_]


def get_field_names(clz, type_hints=None):
    if hasattr(clz, "__dict__"):
        # Regular object with __dict__
        # We can't know the fields without an instance, so we rely on type hints
        if type_hints is None:
            type_hints = typing.get_type_hints(clz)
        return sorted(type_hints.keys())
    elif hasattr(clz, "__slots__"):
        # Object with __slots__
        return sorted(clz.__slots__)
    return []
