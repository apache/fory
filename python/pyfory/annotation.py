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
from typing import TypeVar

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

try:
    from typing import Annotated as _Annotated
except ImportError:
    try:
        from typing_extensions import Annotated as _Annotated
    except ImportError:
        _Annotated = None


int8 = TypeVar("int8", bound=int)
uint8 = TypeVar("uint8", bound=int)
int16 = TypeVar("int16", bound=int)
uint16 = TypeVar("uint16", bound=int)
int32 = TypeVar("int32", bound=int)
uint32 = TypeVar("uint32", bound=int)
fixed_int32 = TypeVar("fixed_int32", bound=int)
fixed_uint32 = TypeVar("fixed_uint32", bound=int)
int64 = TypeVar("int64", bound=int)
uint64 = TypeVar("uint64", bound=int)
fixed_int64 = TypeVar("fixed_int64", bound=int)
tagged_int64 = TypeVar("tagged_int64", bound=int)
fixed_uint64 = TypeVar("fixed_uint64", bound=int)
tagged_uint64 = TypeVar("tagged_uint64", bound=int)
float32 = TypeVar("float32", bound=float)
float64 = TypeVar("float64", bound=float)
float16 = TypeVar("float16", bound=float)
bfloat16 = TypeVar("bfloat16", bound=float)

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
        if _Annotated is None:
            return target
        return _Annotated[target, RefMeta(enable)]


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


class _ArrayTypeHint:
    __slots__ = ("__origin__", "__args__", "__fory_array_meta__")

    def __init__(self, origin, element_type, carrier: str):
        self.__origin__ = origin
        self.__args__ = (element_type,)
        self.__fory_array_meta__ = ArrayMeta(element_type, carrier)

    def __repr__(self):
        return f"{self.__origin__.__name__}[{self.__args__[0]!r}]"

    def __eq__(self, other):
        return (
            type(other) is _ArrayTypeHint
            and self.__origin__ is other.__origin__
            and self.__args__ == other.__args__
            and self.__fory_array_meta__ == other.__fory_array_meta__
        )

    def __hash__(self):
        return hash((self.__origin__, self.__args__, self.__fory_array_meta__))


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
        if _Annotated is None:
            return _ArrayTypeHint(cls, element_type, cls._carrier)
        return _Annotated[cls._base_type(element_type), ArrayMeta(element_type, cls._carrier)]


class Array(_ArrayHint):
    """Dense Fory ``array<T>`` schema with Fory-owned dense carrier semantics."""

    _carrier = "array"


class NDArray(_ArrayHint):
    """Dense Fory ``array<T>`` schema with a numpy ndarray carrier contract."""

    _carrier = "ndarray"

    @classmethod
    def _base_type(cls, element_type):
        return object


class StdArray(_ArrayHint):
    """Dense Fory ``array<T>`` schema with a Python ``array.array`` carrier contract."""

    _carrier = "stdarray"

    @classmethod
    def _base_type(cls, element_type):
        return array.array


__all__ = [
    "Array",
    "ArrayMeta",
    "BFloat16Array",
    "BoolArray",
    "Float16Array",
    "Float32Array",
    "Float64Array",
    "Int16Array",
    "Int32Array",
    "Int64Array",
    "Int8Array",
    "NDArray",
    "Ref",
    "RefMeta",
    "StdArray",
    "UInt16Array",
    "UInt32Array",
    "UInt64Array",
    "UInt8Array",
    "bfloat16",
    "fixed_int32",
    "fixed_int64",
    "fixed_uint32",
    "fixed_uint64",
    "float16",
    "float32",
    "float64",
    "int8",
    "int16",
    "int32",
    "int64",
    "tagged_int64",
    "tagged_uint64",
    "uint8",
    "uint16",
    "uint32",
    "uint64",
]
