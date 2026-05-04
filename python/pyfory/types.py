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


class TypeId:
    """
    Fory type for cross-language serialization.
    See `org.apache.fory.types.Type`
    """

    # Unknown/polymorphic type marker.
    UNKNOWN = 0
    # a boolean value (true or false).
    BOOL = 1
    # a 8-bit signed integer.
    INT8 = 2
    # a 16-bit signed integer.
    INT16 = 3
    # a 32-bit signed integer.
    INT32 = 4
    # a 32-bit signed integer using variable-length encoding.
    VARINT32 = 5
    # a 64-bit signed integer.
    INT64 = 6
    # a 64-bit signed integer using variable-length encoding.
    VARINT64 = 7
    # a 64-bit signed integer using tagged encoding.
    TAGGED_INT64 = 8
    # an 8-bit unsigned integer.
    UINT8 = 9
    # a 16-bit unsigned integer.
    UINT16 = 10
    # a 32-bit unsigned integer.
    UINT32 = 11
    # a 32-bit unsigned integer using variable-length encoding.
    VAR_UINT32 = 12
    # a 64-bit unsigned integer.
    UINT64 = 13
    # a 64-bit unsigned integer using variable-length encoding.
    VAR_UINT64 = 14
    # a 64-bit unsigned integer using tagged encoding.
    TAGGED_UINT64 = 15
    # an 8-bit floating point number.
    FLOAT8 = 16
    # a 16-bit floating point number.
    FLOAT16 = 17
    # a 16-bit brain floating point number.
    BFLOAT16 = 18
    # a 32-bit floating point number.
    FLOAT32 = 19
    # a 64-bit floating point number including NaN and Infinity.
    FLOAT64 = 20
    # a text string encoded using Latin1/UTF16/UTF-8 encoding.
    STRING = 21
    # a sequence of objects.
    LIST = 22
    # an unordered set of unique elements.
    SET = 23
    # a map of key-value pairs. Mutable types such as `list/map/set/array/tensor/arrow` are not allowed as key of map.
    MAP = 24
    # a data type consisting of a set of named values. Rust enum with non-predefined field values are not supported as
    # an enum.
    ENUM = 25
    # an enum whose value will be serialized as the registered name.
    NAMED_ENUM = 26
    # a morphic(final) type serialized by Fory Struct serializer. i.e., it doesn't have subclasses. Suppose we're
    # deserializing `List[SomeClass]`, we can save dynamic serializer dispatch since `SomeClass` is morphic(final).
    STRUCT = 27
    # a morphic(final) type serialized by Fory compatible Struct serializer.
    COMPATIBLE_STRUCT = 28
    # a `struct` whose type mapping will be encoded as a name.
    NAMED_STRUCT = 29
    # a `compatible_struct` whose type mapping will be encoded as a name.
    NAMED_COMPATIBLE_STRUCT = 30
    # a type which will be serialized by a customized serializer.
    EXT = 31
    # an `ext` type whose type mapping will be encoded as a name.
    NAMED_EXT = 32
    # a union value whose schema identity is not embedded.
    UNION = 33
    # a union value with embedded numeric union type ID.
    TYPED_UNION = 34
    # a union value with embedded union type name/TypeDef.
    NAMED_UNION = 35
    # represents an empty/unit value with no data (e.g., for empty union alternatives).
    NONE = 36
    # an absolute length of time, independent of any calendar/timezone, as a count of nanoseconds.
    DURATION = 37
    # a point in time, independent of any calendar/timezone, as a count of nanoseconds. The count is relative
    # to an epoch at UTC midnight on January 1, 1970.
    TIMESTAMP = 38
    # a naive date without timezone. The count is days relative to an epoch at UTC midnight on Jan 1, 1970.
    DATE = 39
    # exact decimal value represented as an integer value in two's complement.
    DECIMAL = 40
    # a variable-length array of bytes.
    BINARY = 41
    # generic dense array descriptor, reserved for future shaped-array metadata.
    ARRAY = 42
    # one dimensional bool array.
    BOOL_ARRAY = 43
    # one dimensional Int8 array.
    INT8_ARRAY = 44
    # one dimensional Int16 array.
    INT16_ARRAY = 45
    # one dimensional Int32 array.
    INT32_ARRAY = 46
    # one dimensional Int64 array.
    INT64_ARRAY = 47
    # one dimensional UInt8 array.
    UINT8_ARRAY = 48
    # one dimensional UInt16 array.
    UINT16_ARRAY = 49
    # one dimensional UInt32 array.
    UINT32_ARRAY = 50
    # one dimensional UInt64 array.
    UINT64_ARRAY = 51
    # one dimensional float8 array.
    FLOAT8_ARRAY = 52
    # one dimensional Float16 array.
    FLOAT16_ARRAY = 53
    # one dimensional BFloat16 array.
    BFLOAT16_ARRAY = 54
    # one dimensional Float32 array.
    FLOAT32_ARRAY = 55
    # one dimensional Float64 array.
    FLOAT64_ARRAY = 56

    # Bound value for range checks (types with id >= BOUND are not internal types).
    BOUND = 64

    @staticmethod
    def is_namespaced_type(type_id: int) -> bool:
        return type_id in __NAMESPACED_TYPES__

    @staticmethod
    def is_type_share_meta(type_id: int) -> bool:
        return type_id in __TYPE_SHARE_META__


__NAMESPACED_TYPES__ = {
    TypeId.NAMED_EXT,
    TypeId.NAMED_ENUM,
    TypeId.NAMED_STRUCT,
    TypeId.NAMED_COMPATIBLE_STRUCT,
    TypeId.NAMED_UNION,
}

__TYPE_SHARE_META__ = {
    TypeId.NAMED_ENUM,
    TypeId.NAMED_STRUCT,
    TypeId.NAMED_EXT,
    TypeId.COMPATIBLE_STRUCT,
    TypeId.NAMED_COMPATIBLE_STRUCT,
    TypeId.NAMED_UNION,
}


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


# `Union[type, TypeVar]` is not supported in py3.6, so skip adding type hints for `type_`  # noqa: E501
# See more at https://github.com/python/typing/issues/492 and
# https://stackoverflow.com/questions/69427175/how-to-pass-forwardref-as-args-to-typevar-in-python-3-6  # noqa: E501
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
