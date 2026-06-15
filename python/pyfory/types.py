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

import typing

from pyfory.type_id import TypeId
from pyfory.annotation import (
    BFloat16 as _BFloat16,
    Float16 as _Float16,
    Float32 as _Float32,
    Float64 as _Float64,
    FixedInt32 as _FixedInt32,
    FixedInt64 as _FixedInt64,
    FixedUInt32 as _FixedUInt32,
    FixedUInt64 as _FixedUInt64,
    Int8 as _Int8,
    Int16 as _Int16,
    Int32 as _Int32,
    Int64 as _Int64,
    NDArray as _NDArray,
    PyArray as _PyArray,
    TaggedInt64 as _TaggedInt64,
    TaggedUInt64 as _TaggedUInt64,
    UInt8 as _UInt8,
    UInt16 as _UInt16,
    UInt32 as _UInt32,
    UInt64 as _UInt64,
)

__all__ = ["TypeId"]


_primitive_types = {
    int,
    float,
    _Int8,
    _Int16,
    _Int32,
    _Int64,
    _UInt8,
    _UInt16,
    _UInt32,
    _UInt64,
    _FixedInt32,
    _FixedInt64,
    _FixedUInt32,
    _FixedUInt64,
    _TaggedInt64,
    _TaggedUInt64,
    _Float16,
    _BFloat16,
    _Float32,
    _Float64,
}


def _is_special_compiled_primitive_type(type_) -> bool:
    return False


_primitive_types_ids = {
    TypeId.BOOL,
    # Signed integers
    TypeId.INT8,
    TypeId.INT16,
    TypeId.INT32,
    TypeId.VARINT32,
    TypeId.INT64,
    TypeId.VARINT64,
    TypeId.TAGGED_INT64,
    # Unsigned integers
    TypeId.UINT8,
    TypeId.UINT16,
    TypeId.UINT32,
    TypeId.VAR_UINT32,
    TypeId.UINT64,
    TypeId.VAR_UINT64,
    TypeId.TAGGED_UINT64,
    # Floats
    TypeId.FLOAT8,
    TypeId.FLOAT16,
    TypeId.BFLOAT16,
    TypeId.FLOAT32,
    TypeId.FLOAT64,
}


def is_primitive_type(type_) -> bool:
    if type(type_) is int:
        return type_ in _primitive_types_ids
    return type_ in _primitive_types or _is_special_compiled_primitive_type(type_)


_primitive_type_sizes = {
    TypeId.BOOL: 1,
    # Signed integers
    TypeId.INT8: 1,
    TypeId.INT16: 2,
    TypeId.INT32: 4,
    TypeId.VARINT32: 4,
    TypeId.INT64: 8,
    TypeId.VARINT64: 8,
    TypeId.TAGGED_INT64: 8,
    # Unsigned integers
    TypeId.UINT8: 1,
    TypeId.UINT16: 2,
    TypeId.UINT32: 4,
    TypeId.VAR_UINT32: 4,
    TypeId.UINT64: 8,
    TypeId.VAR_UINT64: 8,
    TypeId.TAGGED_UINT64: 8,
    # Floats
    TypeId.FLOAT8: 1,
    TypeId.FLOAT16: 2,
    TypeId.BFLOAT16: 2,
    TypeId.FLOAT32: 4,
    TypeId.FLOAT64: 8,
}


def get_primitive_type_size(type_id) -> int:
    return _primitive_type_sizes.get(type_id, -1)


_py_array_types = {
    _PyArray[_Int8],
    _PyArray[_UInt8],
    _PyArray[_Int16],
    _PyArray[_Int32],
    _PyArray[_Int64],
    _PyArray[_UInt16],
    _PyArray[_UInt32],
    _PyArray[_UInt64],
    _PyArray[_Float32],
    _PyArray[_Float64],
}
_np_array_types = {
    _NDArray[bool],
    _NDArray[_Int8],
    _NDArray[_UInt8],
    _NDArray[_Int16],
    _NDArray[_Int32],
    _NDArray[_Int64],
    _NDArray[_UInt16],
    _NDArray[_UInt32],
    _NDArray[_UInt64],
    _NDArray[_Float16],
    _NDArray[_Float32],
    _NDArray[_Float64],
}
_primitive_array_types = _py_array_types.union(_np_array_types)
_fory_array_types = None
_FORY_ARRAY_TYPE_NAMES = {
    "BoolArray",
    "Int8Array",
    "Int16Array",
    "Int32Array",
    "Int64Array",
    "UInt8Array",
    "UInt16Array",
    "UInt32Array",
    "UInt64Array",
    "Float16Array",
    "BFloat16Array",
    "Float32Array",
    "Float64Array",
}


def _get_fory_array_types():
    global _fory_array_types
    if _fory_array_types is None:
        from pyfory import serialization

        _fory_array_types = {getattr(serialization, name) for name in _FORY_ARRAY_TYPE_NAMES}
    return _fory_array_types


def _is_special_compiled_primitive_array_type(type_) -> bool:
    return False


def is_py_array_type(type_) -> bool:
    return type_ in _py_array_types


_primitive_array_type_ids = {
    TypeId.BOOL_ARRAY,
    TypeId.INT8_ARRAY,
    TypeId.INT16_ARRAY,
    TypeId.INT32_ARRAY,
    TypeId.INT64_ARRAY,
    TypeId.UINT8_ARRAY,
    TypeId.UINT16_ARRAY,
    TypeId.UINT32_ARRAY,
    TypeId.UINT64_ARRAY,
    TypeId.FLOAT16_ARRAY,
    TypeId.BFLOAT16_ARRAY,
    TypeId.FLOAT32_ARRAY,
    TypeId.FLOAT64_ARRAY,
}


def is_primitive_array_type(type_) -> bool:
    if type(type_) is int:
        return type_ in _primitive_array_type_ids
    return type_ in _primitive_array_types or type_ in _get_fory_array_types() or _is_special_compiled_primitive_array_type(type_)


def is_list_type(type_):
    try:
        # type_ may not be a instance of type
        return issubclass(type_, typing.List)
    except TypeError:
        return False


def is_map_type(type_):
    try:
        # type_ may not be a instance of type
        return issubclass(type_, typing.Dict)
    except TypeError:
        return False


_polymorphic_type_ids = {
    TypeId.STRUCT,
    TypeId.COMPATIBLE_STRUCT,
    TypeId.NAMED_STRUCT,
    TypeId.NAMED_COMPATIBLE_STRUCT,
    TypeId.EXT,
    TypeId.NAMED_EXT,
    TypeId.UNKNOWN,
}

_struct_type_ids = {
    TypeId.STRUCT,
    TypeId.COMPATIBLE_STRUCT,
    TypeId.NAMED_STRUCT,
    TypeId.NAMED_COMPATIBLE_STRUCT,
}

_union_type_ids = {
    TypeId.UNION,
    TypeId.TYPED_UNION,
    TypeId.NAMED_UNION,
}

_user_type_id_required = {
    TypeId.ENUM,
    TypeId.STRUCT,
    TypeId.COMPATIBLE_STRUCT,
    TypeId.EXT,
    TypeId.TYPED_UNION,
}


def is_polymorphic_type(type_id: int) -> bool:
    return type_id in _polymorphic_type_ids


def is_struct_type(type_id: int) -> bool:
    return type_id in _struct_type_ids


def is_union_type(type_or_id) -> bool:
    if type_or_id is None:
        return False
    if isinstance(type_or_id, int):
        type_id = type_or_id
    else:
        type_id = getattr(type_or_id, "type_id", None)
    if type_id is None or not isinstance(type_id, int):
        return False
    return type_id in _union_type_ids


def needs_user_type_id(type_id: int) -> bool:
    return type_id in _user_type_id_required
