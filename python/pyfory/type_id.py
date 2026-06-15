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


__all__ = ["TypeId"]
