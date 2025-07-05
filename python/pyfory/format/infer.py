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
import typing

import pyarrow as pa

from functools import partial
from typing import Optional
from pyfory.type import get_qualified_classname, TypeVisitor, infer_field

__type_map__ = {}
__schemas__ = {}  # ensure `id(schema)` doesn't get duplicate.


def get_cls_by_schema(schema):
    id_ = id(schema)
    if id_ not in __type_map__:
        meta = {} if schema.metadata is None else schema.metadata
        cls_name = meta.get(b"cls", b"").decode()
        if cls_name:
            import importlib

            module_name, class_name = cls_name.rsplit(".", 1)
            mod = importlib.import_module(module_name)
            cls_ = getattr(mod, class_name)
        else:
            from pyfory.type import record_class_factory

            cls_ = record_class_factory("Record" + str(id(schema)), [f.name for f in schema])
        __type_map__[id_] = cls_
        __schemas__[id_] = schema
    return __type_map__[id_]


def remove_schema(schema):
    __schemas__.pop(id(schema))


def reset():
    __type_map__.clear()
    __schemas__.clear()


_supported_types = {
    pa.bool_,
    pa.int8,
    pa.int16,
    pa.int32,
    pa.int64,
    pa.float32,
    pa.float64,
    str,
    bytes,
    typing.List,
    typing.Dict,
}
_supported_types_str = [f"{t.__module__}.{getattr(t, '__name__', t)}" for t in _supported_types]
_supported_types_mapping = {t: t for t in _supported_types}
_supported_types_mapping.update(
    {
        str: pa.utf8,
        bytes: pa.binary,
        list: pa.list_,
        dict: pa.map_,
        typing.List: pa.list_,
        typing.Dict: pa.map_,
        bool: pa.bool_,
        datetime.date: pa.date32,
        datetime.datetime: partial(pa.timestamp, "us"),
    }
)


def infer_schema(clz, types_path=None) -> pa.Schema:
    types_path = list(types_path or [])
    type_hints = typing.get_type_hints(clz)
    keys = sorted(type_hints.keys())
    fields = [
        infer_field(
            field_name,
            type_hints[field_name],
            ArrowTypeVisitor(),
            types_path=types_path,
        )
        for field_name in keys
    ]
    return pa.schema(fields, metadata={"cls": get_qualified_classname(clz)})


class ArrowTypeVisitor(TypeVisitor):
    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_field = infer_field("item", elem_type, self, types_path=types_path)
        return pa.field(field_name, pa.list_(elem_field.type))

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_field = infer_field("key", key_type, self, types_path=types_path)
        value_field = infer_field("value", value_type, self, types_path=types_path)
        return pa.field(field_name, pa.map_(key_field.type, value_field.type))

    def visit_customized(self, field_name, type_, types_path=None):
        # type_ is a pojo
        pojo_schema = infer_schema(type_)
        fields = list(pojo_schema)
        return pa.field(
            field_name,
            pa.struct(fields),
            metadata={"cls": get_qualified_classname(type_)},
        )

    def visit_other(self, field_name, type_, types_path=None):
        # use _supported_types_mapping instead of _supported_types, because
        # typing.List/typing.Dict's origin will be list/dict
        if type_ not in _supported_types_mapping:
            raise TypeError(
                f"Type {type_} not supported, currently only compositions of {_supported_types_str} are supported. types_path is {types_path}"
            )
        arrow_type_func = _supported_types_mapping.get(type_)
        return pa.field(field_name, arrow_type_func())


def infer_data_type(clz) -> Optional[pa.DataType]:
    try:
        return infer_field("", clz, ArrowTypeVisitor()).type
    except TypeError:
        return None


def get_type_id(clz) -> Optional[int]:
    type_ = infer_data_type(clz)
    if type_:
        return type_.id
    else:
        return None


def compute_schema_hash(schema: pa.Schema):
    hash_ = 17
    for f in schema:
        hash_ = _compute_hash(hash_, f.type)
    return hash_


def _compute_hash(hash_: int, type_: pa.DataType):
    while True:
        h = hash_ * 31 + type_.id
        if h > 2**63 - 1:
            hash_ = hash_ >> 2
        else:
            hash_ = h
            break
    types = []
    if isinstance(type_, pa.ListType):
        types.append(type_.value_type)
    elif isinstance(type_, pa.MapType):
        types.append(type_.key_type)
        types.append(type_.item_type)
    elif isinstance(type_, pa.StructType):
        types.extend([f.type for f in type_])
    else:
        assert type_.num_fields == 0, f"field type should not be nested, but got type {type_}."

    for t in types:
        hash_ = _compute_hash(hash_, t)
    return hash_
