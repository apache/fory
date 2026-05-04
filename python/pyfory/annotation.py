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


Bool = bool
Int8 = TypeVar("Int8", bound=int)
UInt8 = TypeVar("UInt8", bound=int)
Int16 = TypeVar("Int16", bound=int)
UInt16 = TypeVar("UInt16", bound=int)
Int32 = TypeVar("Int32", bound=int)
UInt32 = TypeVar("UInt32", bound=int)
FixedInt32 = TypeVar("FixedInt32", bound=int)
FixedUInt32 = TypeVar("FixedUInt32", bound=int)
Int64 = TypeVar("Int64", bound=int)
UInt64 = TypeVar("UInt64", bound=int)
FixedInt64 = TypeVar("FixedInt64", bound=int)
TaggedInt64 = TypeVar("TaggedInt64", bound=int)
FixedUInt64 = TypeVar("FixedUInt64", bound=int)
TaggedUInt64 = TypeVar("TaggedUInt64", bound=int)
Float16 = TypeVar("Float16", bound=float)
BFloat16 = TypeVar("BFloat16", bound=float)
Float32 = TypeVar("Float32", bound=float)
Float64 = TypeVar("Float64", bound=float)

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
