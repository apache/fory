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

import array
import typing

from pyfory.type_id import TypeId

try:
    from typing import Annotated
except ImportError:
    from typing_extensions import Annotated

if typing.TYPE_CHECKING:
    from pyfory.serialization import (
        BFloat16Array,
        BoolArray,
        Float16Array,
        Float32Array,
        Float64Array,
        Int16Array,
        Int32Array,
        Int64Array,
        Int8Array,
        UInt16Array,
        UInt32Array,
        UInt64Array,
        UInt8Array,
    )

Bool = bool
Int8 = Annotated[int, TypeId.INT8]
UInt8 = Annotated[int, TypeId.UINT8]
Int16 = Annotated[int, TypeId.INT16]
UInt16 = Annotated[int, TypeId.UINT16]
Int32 = Annotated[int, TypeId.VARINT32]
UInt32 = Annotated[int, TypeId.VAR_UINT32]
FixedInt32 = Annotated[int, TypeId.INT32]
FixedUInt32 = Annotated[int, TypeId.UINT32]
Int64 = Annotated[int, TypeId.VARINT64]
UInt64 = Annotated[int, TypeId.VAR_UINT64]
FixedInt64 = Annotated[int, TypeId.INT64]
TaggedInt64 = Annotated[int, TypeId.TAGGED_INT64]
FixedUInt64 = Annotated[int, TypeId.UINT64]
TaggedUInt64 = Annotated[int, TypeId.TAGGED_UINT64]
Float16 = Annotated[float, TypeId.FLOAT16]
BFloat16 = Annotated[float, TypeId.BFLOAT16]
Float32 = Annotated[float, TypeId.FLOAT32]
Float64 = Annotated[float, TypeId.FLOAT64]

_ARRAY_EXPORTS = {
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


def __getattr__(name):
    if name in _ARRAY_EXPORTS:
        from pyfory import serialization

        value = getattr(serialization, name)
        globals()[name] = value
        return value
    raise AttributeError(name)


class RefMeta:
    __slots__ = ("enable",)

    def __init__(self, enable: bool = True):
        self.enable = enable


class Ref:
    def __class_getitem__(cls, params):
        if not isinstance(params, tuple):
            params = (params,)
        if len(params) == 0 or len(params) > 2:
            raise TypeError("Ref expects Ref[T] or Ref[T, bool]")
        target = params[0]
        enable = True
        if len(params) == 2:
            enable = params[1]
        if not isinstance(enable, bool):
            raise TypeError("Ref enable must be a bool")
        return Annotated[target, RefMeta(enable)]


class ArrayMeta:
    __slots__ = ("element_type", "carrier")

    def __init__(self, element_type, carrier: str):
        self.element_type = element_type
        self.carrier = carrier

    def __eq__(self, other):
        return type(other) is ArrayMeta and self.element_type == other.element_type and self.carrier == other.carrier

    def __hash__(self):
        return hash((self.element_type, self.carrier))

    def __repr__(self):
        return f"ArrayMeta(element_type={self.element_type!r}, carrier={self.carrier!r})"


class _ArrayHint:
    _carrier = "array"

    @classmethod
    def _base_type(cls, element_type):
        return typing.List[element_type]

    def __class_getitem__(cls, element_type):
        if isinstance(element_type, tuple):
            if len(element_type) != 1:
                raise TypeError(f"{cls.__name__} expects exactly one element type")
            element_type = element_type[0]
        return Annotated[cls._base_type(element_type), ArrayMeta(element_type, cls._carrier)]


class Array(_ArrayHint):
    """Dense Fory ``array<T>`` schema with Fory-owned dense carrier semantics."""

    _carrier = "array"


class NDArray(_ArrayHint):
    """Dense Fory ``array<T>`` schema with a numpy ndarray carrier contract."""

    _carrier = "ndarray"

    @classmethod
    def _base_type(cls, element_type):
        return object


class PyArray(_ArrayHint):
    """Dense Fory ``array<T>`` schema with a Python ``array.array`` carrier contract."""

    _carrier = "pyarray"

    @classmethod
    def _base_type(cls, element_type):
        return array.array


__all__ = [
    "Array",
    "ArrayMeta",
    "BFloat16Array",
    "BFloat16",
    "Bool",
    "BoolArray",
    "Float16",
    "Float16Array",
    "Float32",
    "Float32Array",
    "Float64",
    "Float64Array",
    "FixedInt32",
    "FixedInt64",
    "FixedUInt32",
    "FixedUInt64",
    "Int16",
    "Int16Array",
    "Int32",
    "Int32Array",
    "Int64",
    "Int64Array",
    "Int8",
    "Int8Array",
    "NDArray",
    "Ref",
    "RefMeta",
    "PyArray",
    "TaggedInt64",
    "TaggedUInt64",
    "UInt16",
    "UInt16Array",
    "UInt32",
    "UInt32Array",
    "UInt64",
    "UInt64Array",
    "UInt8",
    "UInt8Array",
]
