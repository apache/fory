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

from pyfory.type import (
    TypeVisitor,
    infer_field,
    TypeId,
    Int8Type,
    Int16Type,
    Int32Type,
    Int64Type,
    Float32Type,
    Float64Type,
    is_py_array_type,
    compute_string_hash,
    is_primitive_type,
)

from pyfory.type import (
    is_list_type,
    is_map_type,
    get_primitive_type_size,
    is_primitive_array_type,
)

from pyfory.type import is_subclass

logger = logging.getLogger(__name__)


basic_types = {
    bool,
    Int8Type,
    Int16Type,
    Int32Type,
    Int64Type,
    Float32Type,
    Float64Type,
    int,
    float,
    str,
    bytes,
    datetime.datetime,
    datetime.date,
    datetime.time,
}


class ComplexTypeVisitor(TypeVisitor):
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

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        from pyfory.serializer import MapSerializer  # Local import

        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_serializer = infer_field("key", key_type, self, types_path=types_path)
        value_serializer = infer_field("value", value_type, self, types_path=types_path)
        return MapSerializer(self.fory, dict, key_serializer, value_serializer)

    def visit_customized(self, field_name, type_, types_path=None):
        return None

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        if type_ not in basic_types and not is_py_array_type(type_):
            return None
        serializer = self.fory.type_resolver.get_serializer(type_)
        return serializer


def _get_hash(fory, field_names: list, type_hints: dict):
    visitor = StructHashVisitor(fory)
    for index, key in enumerate(field_names):
        infer_field(key, type_hints[key], visitor, types_path=[])
    hash_ = visitor.get_hash()
    assert hash_ != 0
    return hash_


_UNKNOWN_TYPE_ID = -1
_time_types = {datetime.date, datetime.datetime, datetime.timedelta}


def _sort_fields(type_resolver, field_names, serializers):
    boxed_types = []
    collection_types = []
    map_types = []
    final_types = []
    other_types = []
    type_ids = []
    for field_name, serializer in zip(field_names, serializers):
        if serializer is None:
            other_types.append((_UNKNOWN_TYPE_ID, serializer, field_name))
        else:
            type_ids.append(
                (
                    type_resolver.get_typeinfo(serializer.type_).type_id,
                    serializer,
                    field_name,
                )
            )
    for type_id, serializer, field_name in type_ids:
        if is_primitive_type(type_id):
            container = boxed_types
        elif is_list_type(serializer.type_):
            container = collection_types
        elif is_map_type(serializer.type_):
            container = map_types
        elif (
            type_id in {TypeId.STRING} or is_primitive_array_type(type_id) or is_subclass(serializer.type_, enum.Enum)
        ) or serializer.type_ in _time_types:
            container = final_types
        else:
            container = other_types
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
    collection_types = sorted(collection_types, key=sorter)
    final_types = sorted(final_types, key=sorter)
    map_types = sorted(map_types, key=sorter)
    other_types = sorted(other_types, key=sorter)
    all_types = boxed_types + final_types + other_types + collection_types + map_types
    return [t[1] for t in all_types], [t[2] for t in all_types]


class StructHashVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
    ):
        self.fory = fory
        self._hash = 17

    def visit_list(self, field_name, elem_type, types_path=None):
        # TODO add list element type to hash.
        xtype_id = self.fory.type_resolver.get_typeinfo(list).type_id
        self._hash = self._compute_field_hash(self._hash, abs(xtype_id))

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # TODO add map key/value type to hash.
        xtype_id = self.fory.type_resolver.get_typeinfo(dict).type_id
        self._hash = self._compute_field_hash(self._hash, abs(xtype_id))

    def visit_customized(self, field_name, type_, types_path=None):
        typeinfo = self.fory.type_resolver.get_typeinfo(type_, create=False)
        hash_value = 0
        if typeinfo is not None:
            hash_value = typeinfo.type_id
            if TypeId.is_namespaced_type(typeinfo.type_id):
                namespace_str = typeinfo.decode_namespace()
                typename_str = typeinfo.decode_typename()
                hash_value = compute_string_hash(namespace_str + typename_str)
        self._hash = self._compute_field_hash(self._hash, hash_value)

    def visit_other(self, field_name, type_, types_path=None):
        typeinfo = self.fory.type_resolver.get_typeinfo(type_, create=False)
        if typeinfo is None:
            id_ = 0
        else:
            serializer = typeinfo.serializer
            id_ = typeinfo.type_id
            assert id_ is not None, serializer
        id_ = abs(id_)
        self._hash = self._compute_field_hash(self._hash, id_)

    @staticmethod
    def _compute_field_hash(hash_, id_):
        new_hash = hash_ * 31 + id_
        while new_hash >= 2**31 - 1:
            new_hash = new_hash // 7
        return new_hash

    def get_hash(self):
        return self._hash
