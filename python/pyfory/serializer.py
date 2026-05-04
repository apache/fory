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
import builtins
import dataclasses
import decimal
import importlib
import inspect
import marshal
import os
import pickle
import types
from typing import Tuple

from pyfory.serialization import Buffer
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory.policy import DEFAULT_POLICY

try:
    import numpy as np
except ImportError:
    np = None

from pyfory._fory import (
    NOT_NULL_INT64_FLAG,
    BufferObject,
)

_WINDOWS = os.name == "nt"

from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION


def _import_validated_module(policy, module_name):
    result = policy.validate_module(module_name)
    if result is not None:
        if isinstance(result, types.ModuleType):
            return result
        assert isinstance(result, str), f"validate_module must return module, str, or None, got {type(result)}"
        module_name = result
    return importlib.import_module(module_name)


def _resolve_validated_module_attr(policy, module_name, attr_name):
    module = _import_validated_module(policy, module_name)
    return getattr(module, attr_name)


def _resolve_validated_module_qualname(policy, module_name, qualname):
    obj = _import_validated_module(policy, module_name)
    for name in qualname.split("."):
        obj = getattr(obj, name)
    return obj


def _check_collection_size(read_context, size, kind):
    if size < 0:
        raise ValueError(f"{kind} size {size} must be non-negative")
    if size > read_context.max_collection_size:
        raise ValueError(f"{kind} size {size} exceeds the configured limit of {read_context.max_collection_size}")


def _validate_function_value(policy, func, is_local):
    if isinstance(func, type):
        result = policy.validate_class(func, is_local=is_local)
        if result is not None:
            func = result
        if isinstance(func, type):
            raise TypeError(f"Function serializer resolved class {func.__module__}.{func.__qualname__}")
    if not callable(func):
        raise TypeError(f"Function serializer resolved non-callable object {func!r}")
    result = policy.validate_function(func, is_local=is_local)
    if result is not None:
        func = result
    return func


def _bind_static_method(obj, method_name):
    cls = obj if isinstance(obj, type) else obj.__class__
    try:
        attr = inspect.getattr_static(obj, method_name)
    except AttributeError as exc:
        raise ValueError(f"Cannot resolve method {method_name!r} safely") from exc

    if isinstance(attr, staticmethod):
        method = attr.__func__
    elif isinstance(attr, classmethod):
        method = types.MethodType(attr.__func__, cls)
    elif isinstance(attr, types.FunctionType):
        method = types.MethodType(attr, obj)
    elif isinstance(attr, (types.MethodType, types.BuiltinMethodType)):
        method = attr
    elif isinstance(attr, (types.MethodDescriptorType, types.WrapperDescriptorType)):
        method = attr.__get__(obj, cls)
    elif callable(attr) and not hasattr(type(attr), "__get__"):
        method = attr
    else:
        raise ValueError(f"Cannot resolve method {method_name!r} safely")
    if not callable(method):
        raise ValueError(f"Resolved method {method_name!r} is not callable")
    return method


def _resolve_validated_bound_method(policy, obj, method_name, is_local):
    if policy is DEFAULT_POLICY:
        return getattr(obj, method_name)
    method = _bind_static_method(obj, method_name)
    result = policy.validate_method(method, is_local=is_local)
    if result is not None:
        method = result
    return method


# Keep the mode switch here instead of inside `_serializer.py`.
# In Cython mode the active hot-path serializer classes, including primitive,
# enum/slice, and collection serializers, must come from `pyfory.serialization`.
# `_serializer.py` and `collection.py` stay pure-Python shims for debugging and
# Python-only execution.
if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import (  # noqa: F401 # pylint: disable=unused-import
        Serializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
        FixedInt32Serializer,
        FixedInt64Serializer,
        Varint32Serializer,
        Varint64Serializer,
        TaggedInt64Serializer,
        Uint8Serializer,
        Uint16Serializer,
        Uint32Serializer,
        VarUint32Serializer,
        Uint64Serializer,
        VarUint64Serializer,
        TaggedUint64Serializer,
        Float32Serializer,
        Float64Serializer,
        Float16Serializer,
        BoolArraySerializer,
        Int8ArraySerializer,
        Int16ArraySerializer,
        Int32ArraySerializer,
        Int64ArraySerializer,
        UInt8ArraySerializer,
        UInt16ArraySerializer,
        UInt32ArraySerializer,
        UInt64ArraySerializer,
        Float16ArraySerializer,
        Float32ArraySerializer,
        Float64ArraySerializer,
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
        DurationSerializer,
        CollectionSerializer,
        ListSerializer,
        TupleSerializer,
        StringArraySerializer,
        SetSerializer,
        MapSerializer,
        EnumSerializer,
        SliceSerializer,
        BFloat16Serializer,
        BFloat16ArraySerializer,
    )
    from pyfory.union import UnionSerializer  # noqa: F401
else:
    from pyfory._serializer import (  # noqa: F401 # pylint: disable=unused-import
        Serializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
        FixedInt32Serializer,
        FixedInt64Serializer,
        Varint32Serializer,
        Varint64Serializer,
        TaggedInt64Serializer,
        Uint8Serializer,
        Uint16Serializer,
        Uint32Serializer,
        VarUint32Serializer,
        Uint64Serializer,
        VarUint64Serializer,
        TaggedUint64Serializer,
        Float32Serializer,
        Float64Serializer,
        Float16Serializer,
        BoolArraySerializer,
        Int8ArraySerializer,
        Int16ArraySerializer,
        Int32ArraySerializer,
        Int64ArraySerializer,
        UInt8ArraySerializer,
        UInt16ArraySerializer,
        UInt32ArraySerializer,
        UInt64ArraySerializer,
        Float16ArraySerializer,
        Float32ArraySerializer,
        Float64ArraySerializer,
        BFloat16Serializer,
        BFloat16ArraySerializer,
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
        DurationSerializer,
        EnumSerializer,
        SliceSerializer,
    )
    from pyfory.union import UnionSerializer  # noqa: F401
    from pyfory.collection import (
        CollectionSerializer,
        ListSerializer,
        TupleSerializer,
        StringArraySerializer,
        SetSerializer,
        MapSerializer,
    )

from pyfory.types import TypeId
from pyfory.annotation import (
    BFloat16Array,
    BoolArray,
    Float16Array,
    Float32Array,
    Float64Array,
    Int16Array,
    Int32Array,
    Int64Array,
    Int8Array,
    NDArray,
    PyArray,
    UInt16Array,
    UInt32Array,
    UInt64Array,
    UInt8Array,
    Float16,
    Float32,
    Float64,
    Int8,
    Int16,
    Int32,
    Int64,
    UInt8,
    UInt16,
    UInt32,
    UInt64,
)
from pyfory.utils import is_little_endian


class NoneSerializer(Serializer):
    def __init__(self, type_resolver):
        super().__init__(type_resolver, None)
        self.need_to_write_ref = False

    def write(self, buffer, value):
        pass

    def read(self, buffer):
        return None


_MIN_INT64 = -(1 << 63)
_MAX_INT64 = (1 << 63) - 1
_MAX_SMALL_ZIGZAG = (1 << 63) - 1
_MIN_INT32 = -(1 << 31)
_MAX_INT32 = (1 << 31) - 1
_UINT64_MOD = 1 << 64


def _encode_zigzag64(value: int) -> int:
    return (value << 1) ^ (value >> 63)


def _decode_zigzag64(value: int) -> int:
    return (value >> 1) ^ -(value & 1)


def _can_use_small_decimal_encoding(unscaled: int) -> bool:
    if unscaled < _MIN_INT64 or unscaled > _MAX_INT64:
        return False
    return _encode_zigzag64(unscaled) <= _MAX_SMALL_ZIGZAG


def _decimal_parts(value: decimal.Decimal) -> Tuple[int, int]:
    if not value.is_finite():
        raise ValueError(f"Decimal value must be finite, got {value!r}")
    sign, digits, exponent = value.as_tuple()
    scale = -exponent
    if scale < _MIN_INT32 or scale > _MAX_INT32:
        raise ValueError(f"Decimal scale {scale} is outside signed Int32 range")
    unscaled = 0
    for digit in digits:
        unscaled = unscaled * 10 + digit
    if sign:
        unscaled = -unscaled
    return scale, unscaled


def _decimal_from_parts(scale: int, unscaled: int) -> decimal.Decimal:
    if unscaled == 0:
        digits = (0,)
        sign = 0
    else:
        sign = 1 if unscaled < 0 else 0
        digits = tuple(int(ch) for ch in str(abs(unscaled)))
    return decimal.Decimal((sign, digits, -scale))


def _write_decimal_parts(write_context, scale: int, unscaled: int):
    write_context.write_varint32(scale)
    if _can_use_small_decimal_encoding(unscaled):
        header = _encode_zigzag64(unscaled) << 1
        _write_var_uint64(write_context, header)
        return
    magnitude = abs(unscaled)
    if magnitude == 0:
        raise ValueError("Zero must use the small decimal encoding")
    payload = magnitude.to_bytes((magnitude.bit_length() + 7) // 8, "little", signed=False)
    meta = (len(payload) << 1) | (1 if unscaled < 0 else 0)
    _write_var_uint64(write_context, (meta << 1) | 1)
    write_context.write_bytes(payload)


def _write_var_uint64(write_context, value: int):
    try:
        write_context.write_var_uint64(value)
    except OverflowError:
        write_context.write_var_uint64(value - _UINT64_MOD)


def _read_decimal_parts(read_context) -> Tuple[int, int]:
    scale = read_context.read_varint32()
    header = read_context.read_var_uint64()
    if header < 0:
        header += _UINT64_MOD
    if (header & 1) == 0:
        return scale, _decode_zigzag64(header >> 1)
    meta = header >> 1
    sign = meta & 1
    length = meta >> 1
    if length <= 0:
        raise ValueError(f"Invalid decimal magnitude length {length}")
    payload = read_context.read_bytes(length)
    if payload[-1] == 0:
        raise ValueError("Non-canonical decimal payload: trailing zero byte")
    magnitude = int.from_bytes(payload, "little", signed=False)
    if magnitude == 0:
        raise ValueError("Big decimal encoding must not represent zero")
    return scale, -magnitude if sign else magnitude


class DecimalSerializer(Serializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False

    def write(self, write_context, value: decimal.Decimal):
        scale, unscaled = _decimal_parts(value)
        _write_decimal_parts(write_context, scale, unscaled)

    def read(self, read_context):
        scale, unscaled = _read_decimal_parts(read_context)
        return _decimal_from_parts(scale, unscaled)


class PandasRangeIndexSerializer(Serializer):
    __slots__ = "_cached"

    def __init__(self, type_resolver):
        import pandas as pd

        super().__init__(type_resolver, pd.RangeIndex)

    def write(self, write_context, value):
        start = value.start
        stop = value.stop
        step = value.step
        if type(start) is int:
            write_context.write_int16(NOT_NULL_INT64_FLAG)
            write_context.write_varint64(start)
        else:
            if start is None:
                write_context.write_int8(NULL_FLAG)
            else:
                write_context.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(start)
        if type(stop) is int:
            write_context.write_int16(NOT_NULL_INT64_FLAG)
            write_context.write_varint64(stop)
        else:
            if stop is None:
                write_context.write_int8(NULL_FLAG)
            else:
                write_context.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(stop)
        if type(step) is int:
            write_context.write_int16(NOT_NULL_INT64_FLAG)
            write_context.write_varint64(step)
        else:
            if step is None:
                write_context.write_int8(NULL_FLAG)
            else:
                write_context.write_int8(NOT_NULL_VALUE_FLAG)
                write_context.write_no_ref(step)
        write_context.write_ref(value.dtype)
        write_context.write_ref(value.name)

    def read(self, read_context):
        if read_context.read_int8() == NULL_FLAG:
            start = None
        else:
            start = read_context.read_no_ref()
        if read_context.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = read_context.read_no_ref()
        if read_context.read_int8() == NULL_FLAG:
            step = None
        else:
            step = read_context.read_no_ref()
        dtype = read_context.read_ref()
        name = read_context.read_ref()
        return self.type_(start, stop, step, dtype=dtype, name=name)


# Use numpy array or python array module.
_TYPECODE_SPECS = {
    "b": {1: (PyArray[Int8], TypeId.INT8_ARRAY)},
    "B": {1: (PyArray[UInt8], TypeId.UINT8_ARRAY)},
    "h": {2: (PyArray[Int16], TypeId.INT16_ARRAY)},
    "H": {2: (PyArray[UInt16], TypeId.UINT16_ARRAY)},
    "i": {4: (PyArray[Int32], TypeId.INT32_ARRAY)},
    "I": {4: (PyArray[UInt32], TypeId.UINT32_ARRAY)},
    "l": {
        4: (PyArray[Int32], TypeId.INT32_ARRAY),
        8: (PyArray[Int64], TypeId.INT64_ARRAY),
    },
    "L": {
        4: (PyArray[UInt32], TypeId.UINT32_ARRAY),
        8: (PyArray[UInt64], TypeId.UINT64_ARRAY),
    },
    "q": {8: (PyArray[Int64], TypeId.INT64_ARRAY)},
    "Q": {8: (PyArray[UInt64], TypeId.UINT64_ARRAY)},
    "f": {4: (PyArray[Float32], TypeId.FLOAT32_ARRAY)},
    "d": {8: (PyArray[Float64], TypeId.FLOAT64_ARRAY)},
}

_PREFERRED_TYPECODES = {
    TypeId.INT8_ARRAY: ("b",),
    TypeId.UINT8_ARRAY: ("B",),
    TypeId.INT16_ARRAY: ("h",),
    TypeId.UINT16_ARRAY: ("H",),
    TypeId.INT32_ARRAY: ("i", "l"),
    TypeId.UINT32_ARRAY: ("I", "L"),
    TypeId.INT64_ARRAY: ("q", "l"),
    TypeId.UINT64_ARRAY: ("Q", "L"),
    TypeId.FLOAT32_ARRAY: ("f",),
    TypeId.FLOAT64_ARRAY: ("d",),
}


def _build_pyarray_typecode_tables():
    typecodes = {}
    typeid_to_code = {}
    for typecode, by_size in _TYPECODE_SPECS.items():
        try:
            itemsize = array.array(typecode).itemsize
        except (TypeError, ValueError):
            continue
        typed_entry = by_size.get(itemsize)
        if typed_entry is None:
            continue
        ftype, type_id = typed_entry
        typecodes[typecode] = (itemsize, ftype, type_id)
    for type_id, preferred_typecodes in _PREFERRED_TYPECODES.items():
        for typecode in preferred_typecodes:
            entry = typecodes.get(typecode)
            if entry is not None and entry[2] == type_id:
                typeid_to_code[type_id] = typecode
                break
    return typecodes, typeid_to_code


typecode_dict, typeid_code = _build_pyarray_typecode_tables()


class PyArraySerializer(Serializer):
    typecode_dict = typecode_dict
    typecodearray_type = {typecode: ftype for typecode, (_itemsize, ftype, _type_id) in typecode_dict.items()}

    def __init__(self, type_resolver, ftype, type_id: str):
        super().__init__(type_resolver, ftype)
        self.typecode = typeid_code[type_id]
        self.itemsize, ftype, self.type_id = typecode_dict[self.typecode]

    def _array_type_id(self, value):
        entry = typecode_dict.get(value.typecode)
        if entry is None:
            raise TypeError(f"Unsupported array.array typecode {value.typecode!r}")
        itemsize, _ftype, type_id = entry
        if value.itemsize != itemsize:
            raise TypeError(f"array.array typecode {value.typecode!r} has itemsize {value.itemsize}, expected {itemsize}")
        return type_id

    def write(self, buffer, value):
        actual_type_id = self._array_type_id(value)
        if actual_type_id != self.type_id:
            raise TypeError(f"array.array typecode {value.typecode!r} maps to type id {actual_type_id}, expected {self.type_id}")
        view = memoryview(value)
        assert view.itemsize == self.itemsize
        assert view.c_contiguous  # TODO handle contiguous
        nbytes = len(value) * self.itemsize
        buffer.write_var_uint32(nbytes)
        if is_little_endian or self.itemsize == 1:
            buffer.write_buffer(value)
        else:
            # Swap bytes on big-endian machines for multi-byte types
            swapped = array.array(self.typecode, value)
            swapped.byteswap()
            buffer.write_buffer(swapped)

    def read(self, buffer):
        data = buffer.read_bytes_and_size()
        arr = array.array(self.typecode, [])
        arr.frombytes(data)
        if not is_little_endian and self.itemsize > 1:
            # Swap bytes on big-endian machines for multi-byte types
            arr.byteswap()
        return arr


FORY_ARRAY_WRAPPERS = {
    TypeId.BOOL_ARRAY: BoolArray,
    TypeId.INT8_ARRAY: Int8Array,
    TypeId.INT16_ARRAY: Int16Array,
    TypeId.INT32_ARRAY: Int32Array,
    TypeId.INT64_ARRAY: Int64Array,
    TypeId.UINT8_ARRAY: UInt8Array,
    TypeId.UINT16_ARRAY: UInt16Array,
    TypeId.UINT32_ARRAY: UInt32Array,
    TypeId.UINT64_ARRAY: UInt64Array,
    TypeId.FLOAT16_ARRAY: Float16Array,
    TypeId.BFLOAT16_ARRAY: BFloat16Array,
    TypeId.FLOAT32_ARRAY: Float32Array,
    TypeId.FLOAT64_ARRAY: Float64Array,
}

FORY_ARRAY_SERIALIZERS = {
    TypeId.BOOL_ARRAY: BoolArraySerializer,
    TypeId.INT8_ARRAY: Int8ArraySerializer,
    TypeId.INT16_ARRAY: Int16ArraySerializer,
    TypeId.INT32_ARRAY: Int32ArraySerializer,
    TypeId.INT64_ARRAY: Int64ArraySerializer,
    TypeId.UINT8_ARRAY: UInt8ArraySerializer,
    TypeId.UINT16_ARRAY: UInt16ArraySerializer,
    TypeId.UINT32_ARRAY: UInt32ArraySerializer,
    TypeId.UINT64_ARRAY: UInt64ArraySerializer,
    TypeId.FLOAT16_ARRAY: Float16ArraySerializer,
    TypeId.BFLOAT16_ARRAY: BFloat16ArraySerializer,
    TypeId.FLOAT32_ARRAY: Float32ArraySerializer,
    TypeId.FLOAT64_ARRAY: Float64ArraySerializer,
}


def fory_array_wrapper_type(type_id):
    return FORY_ARRAY_WRAPPERS[type_id]


def fory_array_serializer_type(type_id):
    return FORY_ARRAY_SERIALIZERS[type_id]


class ForyArrayListAdapterSerializer(Serializer):
    def __init__(self, type_resolver, wrapper_type, wrapper_serializer, field_name=None):
        super().__init__(type_resolver, wrapper_type)
        self.wrapper_type = wrapper_type
        self.wrapper_serializer = wrapper_serializer
        self.field_name = field_name or "<array>"
        self.need_to_write_ref = False

    def _copy_list_to_wrapper(self, value):
        if type(value) is not list:
            raise TypeError(f"pyfory.Array list adapter for {self.field_name!r} requires list, got {type(value)!r}")
        wrapper = self.wrapper_type()
        for index, item in enumerate(value):
            try:
                wrapper.append(item)
            except (TypeError, ValueError, OverflowError) as exc:
                raise type(exc)(f"{self.field_name}[{index}] invalid for {self.wrapper_type.__name__}: {exc}") from exc
        return wrapper

    def write(self, buffer, value):
        self.wrapper_serializer.write(buffer, self._copy_list_to_wrapper(value))

    def read(self, buffer):
        return self.wrapper_serializer.read(buffer)


class ForyArrayFieldSerializer(Serializer):
    def __init__(self, type_resolver, wrapper_type, type_id, field_name=None):
        super().__init__(type_resolver, wrapper_type)
        self.wrapper_type = wrapper_type
        self.type_id = type_id
        self.field_name = field_name or "<array>"
        wrapper_serializer_type = fory_array_serializer_type(type_id)
        self.wrapper_serializer = wrapper_serializer_type(type_resolver, wrapper_type)
        self.list_adapter_serializer = ForyArrayListAdapterSerializer(
            type_resolver,
            wrapper_type,
            self.wrapper_serializer,
            self.field_name,
        )
        self.pyarray_serializer = self._build_pyarray_serializer(type_resolver, type_id)
        self.ndarray_serializer = self._build_ndarray_serializer(type_resolver, type_id)
        self.need_to_write_ref = False

    def _build_pyarray_serializer(self, type_resolver, type_id):
        typecode = typeid_code.get(type_id)
        if typecode is None:
            return None
        _itemsize, ftype, _type_id = typecode_dict[typecode]
        return PyArraySerializer(type_resolver, ftype, type_id)

    def _build_ndarray_serializer(self, type_resolver, type_id):
        if np is None:
            return None
        for dtype, (_itemsize, _format, ftype, dtype_type_id) in Numpy1DArraySerializer.dtypes_dict.items():
            if dtype_type_id == type_id:
                return Numpy1DArraySerializer(type_resolver, ftype, dtype)
        return None

    def write(self, buffer, value):
        value_type = type(value)
        if value_type is self.wrapper_type:
            self.wrapper_serializer.write(buffer, value)
            return
        if value_type is list:
            self.list_adapter_serializer.write(buffer, value)
            return
        if value_type is array.array:
            if self.pyarray_serializer is None:
                raise TypeError(f"pyfory.Array field {self.field_name!r} does not support array.array for this element type")
            actual_type_id = self.pyarray_serializer._array_type_id(value)
            if actual_type_id != self.type_id:
                raise TypeError(
                    f"pyfory.Array field {self.field_name!r} requires array.array typecode for type id {self.type_id}, got {value.typecode!r}"
                )
            self.pyarray_serializer.write(buffer, value)
            return
        if np is not None and value_type is np.ndarray:
            if self.ndarray_serializer is None:
                raise TypeError(f"pyfory.Array field {self.field_name!r} does not support numpy ndarray for this element type")
            if value.dtype != self.ndarray_serializer.dtype or value.ndim != 1:
                raise TypeError(
                    f"pyfory.Array field {self.field_name!r} requires 1D ndarray with dtype "
                    f"{self.ndarray_serializer.dtype}, got dtype={value.dtype}, ndim={value.ndim}"
                )
            self.ndarray_serializer.write(buffer, value)
            return
        if value is None:
            raise TypeError(f"pyfory.Array field {self.field_name!r} value must not be None")
        raise TypeError(
            f"pyfory.Array field {self.field_name!r} requires {self.wrapper_type.__name__}, list, numpy.ndarray, or array.array, got {type(value)!r}"
        )

    def read(self, buffer):
        return self.wrapper_serializer.read(buffer)


class DynamicPyArraySerializer(Serializer):
    """Serializer for dynamic Python arrays that handles any typecode."""

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)

    def write(self, buffer, value):
        try:
            itemsize, ftype, type_id = typecode_dict[value.typecode]
        except KeyError as exc:
            raise TypeError(f"Unsupported array.array typecode {value.typecode!r}") from exc
        if value.itemsize != itemsize:
            raise TypeError(f"array.array typecode {value.typecode!r} has itemsize {value.itemsize}, expected {itemsize}")
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_uint8(type_id)
        buffer.write_var_uint32(nbytes)
        if not view.c_contiguous:
            data = value.tobytes()
            if not is_little_endian and itemsize > 1:
                swapped = array.array(value.typecode, [])
                swapped.frombytes(data)
                swapped.byteswap()
                data = swapped.tobytes()
            buffer.write_bytes(data)
        elif is_little_endian or itemsize == 1:
            buffer.write_buffer(value)
        else:
            # Swap bytes on big-endian machines for multi-byte types
            swapped = array.array(value.typecode, value)
            swapped.byteswap()
            buffer.write_buffer(swapped)

    def read(self, buffer):
        type_id = buffer.read_uint8()
        typecode = typeid_code[type_id]
        itemsize = typecode_dict[typecode][0]
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode, [])
        arr.frombytes(data)
        if not is_little_endian and itemsize > 1:
            arr.byteswap()
        return arr


if np:
    _np_dtypes_dict = (
        {
            np.dtype(np.bool_): (1, "?", NDArray[bool], TypeId.BOOL_ARRAY),
            np.dtype(np.int8): (1, "b", NDArray[Int8], TypeId.INT8_ARRAY),
            np.dtype(np.uint8): (1, "B", NDArray[UInt8], TypeId.UINT8_ARRAY),
            np.dtype(np.int16): (2, "h", NDArray[Int16], TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "i", NDArray[Int32], TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "l", NDArray[Int64], TypeId.INT64_ARRAY),
            np.dtype(np.uint16): (2, "H", NDArray[UInt16], TypeId.UINT16_ARRAY),
            np.dtype(np.uint32): (4, "I", NDArray[UInt32], TypeId.UINT32_ARRAY),
            np.dtype(np.uint64): (8, "L", NDArray[UInt64], TypeId.UINT64_ARRAY),
            np.dtype(np.float16): (2, "e", NDArray[Float16], TypeId.FLOAT16_ARRAY),
            np.dtype(np.float32): (4, "f", NDArray[Float32], TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", NDArray[Float64], TypeId.FLOAT64_ARRAY),
        }
        if not _WINDOWS
        else {
            np.dtype(np.bool_): (1, "?", NDArray[bool], TypeId.BOOL_ARRAY),
            np.dtype(np.int8): (1, "b", NDArray[Int8], TypeId.INT8_ARRAY),
            np.dtype(np.uint8): (1, "B", NDArray[UInt8], TypeId.UINT8_ARRAY),
            np.dtype(np.int16): (2, "h", NDArray[Int16], TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "l", NDArray[Int32], TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "q", NDArray[Int64], TypeId.INT64_ARRAY),
            np.dtype(np.uint16): (2, "H", NDArray[UInt16], TypeId.UINT16_ARRAY),
            np.dtype(np.uint32): (4, "L", NDArray[UInt32], TypeId.UINT32_ARRAY),
            np.dtype(np.uint64): (8, "Q", NDArray[UInt64], TypeId.UINT64_ARRAY),
            np.dtype(np.float16): (2, "e", NDArray[Float16], TypeId.FLOAT16_ARRAY),
            np.dtype(np.float32): (4, "f", NDArray[Float32], TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", NDArray[Float64], TypeId.FLOAT64_ARRAY),
        }
    )
else:
    _np_dtypes_dict = {}
_np_typeid_to_dtype = {type_id: dtype for dtype, (_, _, _, type_id) in _np_dtypes_dict.items()}


class Numpy1DArraySerializer(Serializer):
    dtypes_dict = _np_dtypes_dict

    def __init__(self, type_resolver, ftype, dtype):
        super().__init__(type_resolver, ftype)
        self.dtype = dtype
        self.itemsize, self.typecode, _, self.type_id = _np_dtypes_dict[self.dtype]

    def write(self, buffer, value):
        assert value.itemsize == self.itemsize
        view = memoryview(value)
        try:
            assert view.format == self.typecode
        except AssertionError as e:
            raise e
        assert view.itemsize == self.itemsize
        nbytes = len(value) * self.itemsize
        buffer.write_var_uint32(nbytes)
        if self.dtype == np.dtype("bool") or not view.c_contiguous:
            if not is_little_endian and self.itemsize > 1:
                # Swap bytes on big-endian machines for multi-byte types
                buffer.write_bytes(value.astype(value.dtype.newbyteorder("<")).tobytes())
            else:
                buffer.write_bytes(value.tobytes())
        elif is_little_endian or self.itemsize == 1:
            buffer.write_buffer(value)
        else:
            # Swap bytes on big-endian machines for multi-byte types
            buffer.write_bytes(value.astype(value.dtype.newbyteorder("<")).tobytes())

    def read(self, buffer):
        data = buffer.read_bytes_and_size()
        arr = np.frombuffer(data, dtype=self.dtype.newbyteorder("<"))
        if self.itemsize > 1:
            if is_little_endian:
                # Normalize to native byte order to keep view.format stable.
                arr = arr.view(self.dtype)
            else:
                # Convert from little-endian to native byte order.
                arr = arr.astype(self.dtype)
        return arr


class NDArraySerializer(Serializer):
    def write(self, buffer, value):
        # Write concrete 1D primitive ndarray using type id + bytes payload.
        dtype_info = _np_dtypes_dict.get(value.dtype)
        if dtype_info is None or value.ndim != 1:
            raise NotImplementedError(f"Unsupported ndarray: dtype={value.dtype}, ndim={value.ndim}")
        itemsize, _typecode, _ftype, type_id = dtype_info
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_uint8(type_id)
        buffer.write_var_uint32(nbytes)
        if value.dtype == np.dtype("bool") or not view.c_contiguous:
            if not is_little_endian and itemsize > 1:
                buffer.write_bytes(value.astype(value.dtype.newbyteorder("<")).tobytes())
            else:
                buffer.write_bytes(value.tobytes())
        elif is_little_endian or itemsize == 1:
            buffer.write_buffer(value)
        else:
            buffer.write_bytes(value.astype(value.dtype.newbyteorder("<")).tobytes())

    def read(self, buffer):
        type_id = buffer.read_uint8()
        dtype = _np_typeid_to_dtype.get(type_id)
        if dtype is None:
            raise NotImplementedError(f"Unsupported ndarray type id: {type_id}")
        data = buffer.read_bytes_and_size()
        arr = np.frombuffer(data, dtype=dtype.newbyteorder("<"))
        if dtype.itemsize > 1:
            if is_little_endian:
                arr = arr.view(dtype)
            else:
                arr = arr.astype(dtype)
        return arr


class PythonNDArraySerializer(NDArraySerializer):
    def write(self, write_context, value):
        dtype_info = _np_dtypes_dict.get(value.dtype)
        if dtype_info is not None and value.ndim == 1:
            super().write(write_context, value)
            return

        dtype = value.dtype
        write_context.write_string(dtype.str)
        write_context.write_var_uint32(len(value.shape))
        for dim in value.shape:
            write_context.write_var_uint32(dim)
        if dtype.kind == "O":
            write_context.write_varint32(len(value))
            for item in value:
                write_context.write_ref(item)
        else:
            write_context.write_buffer_object(NDArrayBufferObject(value))

    def read(self, read_context):
        reader_index = read_context.get_reader_index()
        type_id = read_context.read_uint8()
        dtype = _np_typeid_to_dtype.get(type_id)
        if dtype is not None:
            data = read_context.read_bytes_and_size()
            arr = np.frombuffer(data, dtype=dtype.newbyteorder("<"))
            if dtype.itemsize > 1:
                if is_little_endian:
                    arr = arr.view(dtype)
                else:
                    arr = arr.astype(dtype)
            return arr

        read_context.set_reader_index(reader_index)
        dtype = np.dtype(read_context.read_string())
        ndim = read_context.read_var_uint32()
        _check_collection_size(read_context, ndim, "ndarray dimension")
        shape = tuple(read_context.read_var_uint32() for _ in range(ndim))
        if dtype.kind == "O":
            length = read_context.read_varint32()
            _check_collection_size(read_context, length, "ndarray object")
            items = [read_context.read_ref() for _ in range(length)]
            return np.array(items, dtype=object)
        for dim in shape:
            _check_collection_size(read_context, dim, "ndarray dimension")
        fory_buf = read_context.read_buffer_object()
        if isinstance(fory_buf, memoryview):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        elif isinstance(fory_buf, bytes):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        return np.frombuffer(fory_buf.to_pybytes(), dtype=dtype).reshape(shape)


class BytesSerializer(Serializer):
    def write(self, write_context, value):
        if write_context.buffer_callback is None:
            write_context.write_bytes_and_size(value)
            return
        write_context.write_buffer_object(BytesBufferObject(value))

    def read(self, read_context):
        if not read_context.peer_out_of_band_enabled:
            return read_context.read_bytes_and_size()
        fory_buf = read_context.read_buffer_object()
        if isinstance(fory_buf, memoryview):
            return bytes(fory_buf)
        elif isinstance(fory_buf, bytes):
            return fory_buf
        return fory_buf.to_pybytes()


class BytesBufferObject(BufferObject):
    __slots__ = ("binary",)

    def __init__(self, binary: bytes):
        self.binary = binary

    def total_bytes(self) -> int:
        return len(self.binary)

    def write_to(self, stream):
        if hasattr(stream, "write_bytes"):
            stream.write_bytes(self.binary)
        else:
            stream.write(self.binary)

    def getbuffer(self) -> memoryview:
        return memoryview(self.binary)


class PickleBufferSerializer(Serializer):
    def write(self, write_context, value):
        write_context.write_buffer_object(PickleBufferObject(value))

    def read(self, read_context):
        fory_buf = read_context.read_buffer_object()
        if isinstance(fory_buf, (bytes, memoryview, bytearray, Buffer)):
            return pickle.PickleBuffer(fory_buf)
        return pickle.PickleBuffer(fory_buf.to_pybytes())


class PickleBufferObject(BufferObject):
    __slots__ = ("pickle_buffer",)

    def __init__(self, pickle_buffer):
        self.pickle_buffer = pickle_buffer

    def total_bytes(self) -> int:
        return len(self.pickle_buffer.raw())

    def write_to(self, stream):
        raw = self.pickle_buffer.raw()
        if hasattr(stream, "write_buffer"):
            stream.write_buffer(raw)
        else:
            stream.write(bytes(raw) if isinstance(raw, memoryview) else raw)

    def getbuffer(self) -> memoryview:
        raw = self.pickle_buffer.raw()
        if isinstance(raw, memoryview):
            return raw
        return memoryview(bytes(raw))


class NDArrayBufferObject(BufferObject):
    __slots__ = ("array", "dtype", "shape")

    def __init__(self, array):
        self.array = array
        self.dtype = array.dtype
        self.shape = array.shape

    def total_bytes(self) -> int:
        return self.array.nbytes

    def write_to(self, stream):
        data = self.array.tobytes()
        if hasattr(stream, "write_buffer"):
            stream.write_buffer(data)
        else:
            stream.write(data)

    def getbuffer(self) -> memoryview:
        if self.array.flags.c_contiguous:
            return memoryview(self.array.data)
        return memoryview(self.array.tobytes())


class StatefulSerializer(Serializer):
    """
    Serializer for objects that support __getstate__ and __setstate__.
    Uses Fory's native serialization for better cross-language support.
    """

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def write(self, write_context, value):
        state = value.__getstate__()
        args = ()
        kwargs = {}
        if self._getnewargs_ex is not None:
            args, kwargs = self._getnewargs_ex(value)
        elif self._getnewargs is not None:
            args = self._getnewargs(value)

        # Serialize constructor arguments first
        write_context.write_ref(args)
        write_context.write_ref(kwargs)

        # Then serialize the state
        write_context.write_ref(state)

    def read(self, read_context):
        args = read_context.read_ref()
        kwargs = read_context.read_ref()
        state = read_context.read_ref()

        read_context.policy.authorize_instantiation(self.cls)
        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state is not None:
            read_context.policy.intercept_setstate(obj, state)
            obj.__setstate__(state)
        return obj


class _DefaultPolicyStatefulSerializer(StatefulSerializer):
    def read(self, read_context):
        args = read_context.read_ref()
        kwargs = read_context.read_ref()
        state = read_context.read_ref()

        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state is not None:
            obj.__setstate__(state)
        return obj


class ReduceSerializer(Serializer):
    """
    Serializer for objects that support __reduce__ or __reduce_ex__.
    Uses Fory's native serialization for better cross-language support.
    Has higher precedence than StatefulSerializer.
    """

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._reduce_ex = getattr(cls, "__reduce_ex__", None)
        self._reduce = getattr(cls, "__reduce__", None)
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def _validate_global_object(self, policy, obj):
        result = None
        if isinstance(obj, type):
            result = policy.validate_class(obj, is_local=False)
        elif isinstance(
            obj,
            (
                types.FunctionType,
                types.BuiltinFunctionType,
                types.MethodType,
                types.BuiltinMethodType,
            ),
        ):
            result = policy.validate_function(obj, is_local=False)
        if result is not None:
            obj = result
        return obj

    def _resolve_global_name(self, read_context, global_name):
        policy = read_context.policy
        if "." in global_name:
            module_name, obj_name = global_name.rsplit(".", 1)
        else:
            module_name, obj_name = "builtins", global_name
        try:
            obj = _resolve_validated_module_attr(policy, module_name, obj_name)
        except AttributeError:
            raise ValueError(f"Cannot resolve global name: {global_name}")
        return self._validate_global_object(policy, obj)

    def write(self, write_context, value):
        # Try __reduce_ex__ first (with protocol 5 for pickle5 out-of-band buffer support), then __reduce__
        # Check if the object has a custom __reduce_ex__ method (not just the default from object)
        if hasattr(value, "__reduce_ex__") and value.__class__.__reduce_ex__ is not object.__reduce_ex__:
            try:
                reduce_result = value.__reduce_ex__(5)
            except TypeError:
                # Some objects don't support protocol argument
                reduce_result = value.__reduce_ex__()
        elif hasattr(value, "__reduce__"):
            reduce_result = value.__reduce__()
        else:
            raise ValueError(f"Object {value} has no __reduce__ or __reduce_ex__ method")

        # Handle different __reduce__ return formats
        if isinstance(reduce_result, str):
            # Case 1: Just a global name (simple case)
            reduce_data = (0, reduce_result)
        elif isinstance(reduce_result, tuple):
            if len(reduce_result) == 2:
                # Case 2: (callable, args)
                callable_obj, args = reduce_result
                reduce_data = (1, callable_obj, args)
            elif len(reduce_result) == 3:
                # Case 3: (callable, args, state)
                callable_obj, args, state = reduce_result
                reduce_data = (1, callable_obj, args, state)
            elif len(reduce_result) == 4:
                # Case 4: (callable, args, state, listitems)
                callable_obj, args, state, listitems = reduce_result
                reduce_data = (1, callable_obj, args, state, listitems)
            elif len(reduce_result) == 5:
                # Case 5: (callable, args, state, listitems, dictitems)
                callable_obj, args, state, listitems, dictitems = reduce_result
                reduce_data = (
                    1,
                    callable_obj,
                    args,
                    state,
                    listitems,
                    dictitems,
                )
            else:
                raise ValueError(f"Invalid __reduce__ result length: {len(reduce_result)}")
        else:
            raise ValueError(f"Invalid __reduce__ result type: {type(reduce_result)}")
        write_context.write_var_uint32(len(reduce_data))
        for item in reduce_data:
            write_context.write_ref(item)

    def read(self, read_context):
        reduce_data_num_items = read_context.read_var_uint32()
        if reduce_data_num_items > 6:
            raise ValueError(f"Invalid reduce data length: {reduce_data_num_items}")
        reduce_data = [None] * 6
        for i in range(reduce_data_num_items):
            reduce_data[i] = read_context.read_ref()

        if reduce_data[0] == 0:
            # Case 1: Global name
            return self._resolve_global_name(read_context, reduce_data[1])
        elif reduce_data[0] == 1:
            # Case 2-5: Callable with args and optional state/items
            callable_obj = reduce_data[1]
            args = reduce_data[2] or ()
            state = reduce_data[3]
            listitems = reduce_data[4]
            dictitems = reduce_data[5] if len(reduce_data) > 5 else None

            obj = read_context.policy.intercept_reduce_call(callable_obj, args)
            if obj is None:
                # Create the object using the callable and args
                if isinstance(callable_obj, type):
                    read_context.policy.authorize_instantiation(callable_obj)
                obj = callable_obj(*args)

            # Restore state if present
            if state is not None:
                read_context.policy.intercept_setstate(obj, state)
                if hasattr(obj, "__setstate__"):
                    obj.__setstate__(state)
                else:
                    # Fallback: update __dict__ directly
                    if hasattr(obj, "__dict__"):
                        obj.__dict__.update(state)

            # Restore list items if present
            if listitems is not None:
                obj.extend(listitems)

            # Restore dict items if present
            if dictitems is not None:
                for key, value in dictitems:
                    obj[key] = value

            result = read_context.policy.inspect_reduced_object(obj)
            if result is not None:
                obj = result
            return obj
        else:
            raise ValueError(f"Invalid reduce data format flag: {reduce_data[0]}")


__skip_class_attr_names__ = ("__module__", "__qualname__", "__dict__", "__weakref__")


class TypeSerializer(Serializer):
    """Serializer for Python type objects (classes), including local classes."""

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)
        self.cls = cls

    def write(self, write_context, value):
        module_name = value.__module__
        qualname = value.__qualname__

        if module_name == "__main__" or "<locals>" in qualname:
            write_context.write_int8(1)
            self._serialize_local_class(write_context, value)
            return
        write_context.write_int8(0)
        write_context.write_string(module_name)
        write_context.write_string(qualname)

    def read(self, read_context):
        class_type = read_context.read_int8()

        if class_type == 1:
            return self._deserialize_local_class(read_context)
        module_name = read_context.read_string()
        qualname = read_context.read_string()
        cls = _resolve_validated_module_qualname(read_context.policy, module_name, qualname)
        result = read_context.policy.validate_class(cls, is_local=False)
        if result is not None:
            cls = result
        return cls

    def _serialize_local_class(self, write_context, cls):
        """Serialize a local class by capturing its creation context."""
        assert self.type_resolver.track_ref, "Reference tracking must be enabled for local classes serialization"
        module = cls.__module__
        qualname = cls.__qualname__
        write_context.write_string(module)
        write_context.write_string(qualname)
        bases = cls.__bases__
        write_context.write_var_uint32(len(bases))
        for base in bases:
            write_context.write_ref(base)

        # Serialize class dictionary (excluding special attributes)
        # FunctionSerializer will automatically handle methods with closures
        class_dict = {}
        attr_names, class_methods = [], []
        for attr_name, attr_value in cls.__dict__.items():
            # Skip special attributes that are handled by type() constructor
            if attr_name in __skip_class_attr_names__:
                continue
            if isinstance(attr_value, classmethod):
                attr_names.append(attr_name)
                class_methods.append(attr_value)
            else:
                class_dict[attr_name] = attr_value
        write_context.write_var_uint32(len(class_methods))
        for i in range(len(class_methods)):
            write_context.write_string(attr_names[i])
            class_method = class_methods[i]
            write_context.write_ref(class_method.__func__)

        write_context.write_ref(class_dict)

    def _deserialize_local_class(self, read_context):
        """Deserialize a local class by recreating it with the captured context."""
        assert self.type_resolver.track_ref, "Reference tracking must be enabled for local classes deserialization"
        module = read_context.read_string()
        qualname = read_context.read_string()
        name = qualname.rsplit(".", 1)[-1]
        ref_id = read_context.last_preserved_ref_id()

        num_bases = read_context.read_var_uint32()
        _check_collection_size(read_context, num_bases, "local class base")
        bases = tuple(read_context.read_ref() for _ in range(num_bases))
        read_context.policy.authorize_instantiation(type, module=module, qualname=qualname, bases=bases)
        cls = type(name, bases, {})
        read_context.set_read_ref(ref_id, cls)
        result = read_context.policy.validate_class(cls, is_local=True)
        if result is not None:
            cls = result

        num_class_methods = read_context.read_var_uint32()
        _check_collection_size(read_context, num_class_methods, "local class method")
        for _ in range(num_class_methods):
            attr_name = read_context.read_string()
            func = read_context.read_ref()
            method = types.MethodType(func, cls)
            setattr(cls, attr_name, method)
        class_dict = read_context.read_ref()
        for k, v in class_dict.items():
            setattr(cls, k, v)

        # Set module and qualname
        cls.__module__ = module
        cls.__qualname__ = qualname
        result = read_context.policy.validate_class(cls, is_local=True)
        if result is not None:
            cls = result
        return cls


class ModuleSerializer(Serializer):
    """Serializer for python module"""

    def __init__(self, type_resolver):
        super().__init__(type_resolver, types.ModuleType)

    def write(self, buffer, value):
        buffer.write_string(value.__name__)

    def read(self, read_context):
        mod_name = read_context.read_string()
        return _import_validated_module(read_context.policy, mod_name)


class MappingProxySerializer(Serializer):
    def __init__(self, type_resolver):
        super().__init__(type_resolver, types.MappingProxyType)

    def write(self, write_context, value):
        write_context.write_ref(dict(value))

    def read(self, read_context):
        return types.MappingProxyType(read_context.read_ref())


class FunctionSerializer(Serializer):
    """Serializer for function objects

    This serializer captures all the necessary information to recreate a function:
    - Function code
    - Function name
    - Module name
    - Closure variables
    - Global variables
    - Default arguments
    - Function attributes

    The code object is serialized with marshal, and all other components
    (defaults, globals, closure cells, attrs) go through Fory’s own
    write_ref/read_ref pipeline to ensure proper type registration
    and reference tracking.
    """

    # Cache for function attributes that are handled separately
    _FUNCTION_ATTRS = frozenset(
        (
            "__code__",
            "__name__",
            "__defaults__",
            "__closure__",
            "__globals__",
            "__module__",
            "__qualname__",
        )
    )

    def _serialize_function(self, write_context, func):
        """Serialize a function by capturing all its components."""
        # Get function metadata
        instance = getattr(func, "__self__", None)
        if instance is not None and not inspect.ismodule(instance):
            # Handle bound methods
            self_obj = instance
            func_name = func.__name__
            # Serialize as a tuple (is_method, self_obj, method_name)
            write_context.write_int8(0)
            write_context.write_ref(self_obj)
            write_context.write_string(func_name)
            return

        # Regular function or lambda
        code = func.__code__
        module = func.__module__
        qualname = func.__qualname__

        if "<locals>" not in qualname and module != "__main__":
            write_context.write_int8(1)
            write_context.write_string(module)
            write_context.write_string(qualname)
            return

        write_context.write_int8(2)
        write_context.write_string(module)
        write_context.write_string(qualname)

        defaults = func.__defaults__
        closure = func.__closure__
        globals_dict = func.__globals__

        # Instead of trying to serialize the code object in parts, use marshal
        # which is specifically designed for code objects
        marshalled_code = marshal.dumps(code)
        write_context.write_bytes_and_size(marshalled_code)

        # Serialize defaults (or None if no defaults)
        # Write whether defaults exist
        write_context.write_bool(defaults is not None)
        if defaults is not None:
            write_context.write_var_uint32(len(defaults))
            for default_value in defaults:
                write_context.write_ref(default_value)

        # Handle closure
        # We need to serialize both the closure values and the fact that there is a closure
        # The code object's co_freevars tells us what variables are in the closure
        write_context.write_bool(closure is not None)
        write_context.write_var_uint32(len(code.co_freevars) if code.co_freevars else 0)

        if closure:
            for cell in closure:
                write_context.write_ref(cell.cell_contents)

        # Serialize free variable names as a list of strings
        # Convert tuple to list since tuple might not be registered
        freevars_list = list(code.co_freevars) if code.co_freevars else []
        write_context.write_var_uint32(len(freevars_list))
        for name in freevars_list:
            write_context.write_string(name)

        # Handle globals
        # Identify which globals are actually used by the function
        global_names = set()
        for name in code.co_names:
            if name in globals_dict and not hasattr(builtins, name):
                global_names.add(name)

        # Add any globals referenced by nested functions in co_consts
        for const in code.co_consts:
            if isinstance(const, types.CodeType):
                for name in const.co_names:
                    if name in globals_dict and not hasattr(builtins, name):
                        global_names.add(name)

        # Create and serialize a dictionary with only the necessary globals
        globals_to_serialize = {name: globals_dict[name] for name in global_names if name in globals_dict}
        write_context.write_ref(globals_to_serialize)

        # Handle additional attributes
        attrs = {}
        for attr in dir(func):
            if attr.startswith("__") and attr.endswith("__"):
                continue
            if attr in self._FUNCTION_ATTRS:
                continue
            try:
                attrs[attr] = getattr(func, attr)
            except (AttributeError, TypeError):
                pass

        write_context.write_ref(attrs)

    def _deserialize_function(self, read_context):
        """Deserialize a function from its components."""

        func_type_id = read_context.read_int8()
        if func_type_id == 0:
            self_obj = read_context.read_ref()
            method_name = read_context.read_string()
            policy = read_context.policy
            if policy is DEFAULT_POLICY:
                return getattr(self_obj, method_name)
            return _resolve_validated_bound_method(policy, self_obj, method_name, is_local=False)

        if func_type_id == 1:
            module = read_context.read_string()
            qualname = read_context.read_string()
            mod = _resolve_validated_module_qualname(read_context.policy, module, qualname)
            return _validate_function_value(read_context.policy, mod, is_local=False)

        module = read_context.read_string()
        qualname = read_context.read_string()
        mod = _import_validated_module(read_context.policy, module)
        name = qualname.rsplit(".")[-1]

        marshalled_code = read_context.read_bytes_and_size()
        code = marshal.loads(marshalled_code)

        has_defaults = read_context.read_bool()
        defaults = None
        if has_defaults:
            num_defaults = read_context.read_var_uint32()
            _check_collection_size(read_context, num_defaults, "function default")
            default_values = []
            for _ in range(num_defaults):
                default_values.append(read_context.read_ref())
            defaults = tuple(default_values)

        has_closure = read_context.read_bool()
        num_freevars = read_context.read_var_uint32()
        _check_collection_size(read_context, num_freevars, "function closure")
        closure = None

        closure_values = []
        if has_closure:
            for _ in range(num_freevars):
                closure_values.append(read_context.read_ref())

            closure = tuple(types.CellType(value) for value in closure_values)

        num_freevars = read_context.read_var_uint32()
        _check_collection_size(read_context, num_freevars, "function free variable")
        freevars = []
        for _ in range(num_freevars):
            freevars.append(read_context.read_string())

        globals_dict = read_context.read_ref()

        # Create a globals dictionary with module's globals as the base
        func_globals = {}
        if mod:
            func_globals.update(mod.__dict__)

        func_globals.update(globals_dict)

        # Ensure __builtins__ is available
        if "__builtins__" not in func_globals:
            func_globals["__builtins__"] = builtins

        func = types.FunctionType(code, func_globals, name, defaults, closure)

        func.__module__ = module
        func.__qualname__ = qualname

        attrs = read_context.read_ref()
        for attr_name, attr_value in attrs.items():
            setattr(func, attr_name, attr_value)

        return _validate_function_value(read_context.policy, func, is_local=True)

    def write(self, write_context, value):
        self._serialize_function(write_context, value)

    def read(self, read_context):
        return self._deserialize_function(read_context)


class NativeFuncMethodSerializer(Serializer):
    def write(self, write_context, func):
        name = func.__name__
        write_context.write_string(name)
        obj = getattr(func, "__self__", None)
        if obj is None or inspect.ismodule(obj):
            write_context.write_bool(True)
            module = func.__module__
            write_context.write_string(module)
        else:
            write_context.write_bool(False)
            write_context.write_ref(obj)

    def read(self, read_context):
        name = read_context.read_string()
        if read_context.read_bool():
            module = read_context.read_string()
            func = _resolve_validated_module_attr(read_context.policy, module, name)
            func = _validate_function_value(read_context.policy, func, is_local=False)
        else:
            obj = read_context.read_ref()
            policy = read_context.policy
            if policy is DEFAULT_POLICY:
                func = getattr(obj, name)
            else:
                func = _resolve_validated_bound_method(policy, obj, name, is_local=False)
        return func


class MethodSerializer(Serializer):
    """Serializer for bound method objects."""

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)
        self.cls = cls
        self._use_default_policy = type_resolver.policy is DEFAULT_POLICY

    def write(self, write_context, value):
        instance = value.__self__
        method_name = value.__func__.__name__

        write_context.write_ref(instance)
        write_context.write_string(method_name)

    def read(self, read_context):
        instance = read_context.read_ref()
        method_name = read_context.read_string()

        if self._use_default_policy:
            return getattr(instance, method_name)
        cls = instance if isinstance(instance, type) else instance.__class__
        is_local = cls.__module__ == "__main__" or "<locals>" in cls.__qualname__
        return _resolve_validated_bound_method(read_context.policy, instance, method_name, is_local=is_local)


class ObjectSerializer(Serializer):
    """Serializer for regular Python objects.
    It serializes objects based on `__dict__` or `__slots__`.
    """

    def __init__(self, type_resolver, clz: type):
        super().__init__(type_resolver, clz)
        # If the class defines __slots__, compute and store a sorted list once
        slots = getattr(clz, "__slots__", None)
        self._slot_field_names = None
        if slots is not None:
            # __slots__ can be a string or iterable of strings
            if isinstance(slots, str):
                slots = [slots]
            self._slot_field_names = sorted(slots)

    def write(self, write_context, value):
        if self._slot_field_names is not None:
            sorted_field_names = self._slot_field_names
        else:
            value_dict = getattr(value, "__dict__", None)
            sorted_field_names = [] if value_dict is None else sorted(value_dict.keys())

        write_context.write_var_uint32(len(sorted_field_names))
        for field_name in sorted_field_names:
            write_context.write_string(field_name)
            field_value = getattr(value, field_name)
            write_context.write_ref(field_value)

    def read(self, read_context):
        policy = read_context.policy
        policy.authorize_instantiation(self.type_)
        obj = self.type_.__new__(self.type_)
        read_context.reference(obj)
        num_fields = read_context.read_var_uint32()
        if num_fields > read_context.max_collection_size:
            raise ValueError(f"object field size {num_fields} exceeds the configured limit of {read_context.max_collection_size}")
        state = {}
        for _ in range(num_fields):
            field_name = read_context.read_string()
            field_value = read_context.read_ref()
            state[field_name] = field_value
        policy.intercept_setstate(obj, state)
        for field_name, field_value in state.items():
            setattr(obj, field_name, field_value)
        return obj


class _DefaultPolicyObjectSerializer(ObjectSerializer):
    def read(self, read_context):
        obj = self.type_.__new__(self.type_)
        read_context.reference(obj)
        num_fields = read_context.read_var_uint32()
        if num_fields > read_context.max_collection_size:
            raise ValueError(f"object field size {num_fields} exceeds the configured limit of {read_context.max_collection_size}")
        for _ in range(num_fields):
            field_name = read_context.read_string()
            field_value = read_context.read_ref()
            setattr(obj, field_name, field_value)
        return obj


@dataclasses.dataclass
class NonExistEnum:
    value: int = -1
    name: str = ""


class NonExistEnumSerializer(Serializer):
    def __init__(self, type_resolver):
        super().__init__(type_resolver, NonExistEnum)
        self.need_to_write_ref = False

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_var_uint32(value.value)

    def read(self, buffer):
        value = buffer.read_var_uint32()
        return NonExistEnum(value=value)


class UnsupportedSerializer(Serializer):
    def write(self, write_context, value):
        write_context.handle_unsupported_write(value)

    def read(self, read_context):
        return read_context.handle_unsupported_read()


__all__ = [
    # Base serializers (imported)
    "Serializer",
    # Primitive serializers (imported)
    "BooleanSerializer",
    "ByteSerializer",
    "Int16Serializer",
    "Int32Serializer",
    "Int64Serializer",
    "FixedInt32Serializer",
    "FixedInt64Serializer",
    "Varint32Serializer",
    "Varint64Serializer",
    "TaggedInt64Serializer",
    "Uint8Serializer",
    "Uint16Serializer",
    "Uint32Serializer",
    "VarUint32Serializer",
    "Uint64Serializer",
    "VarUint64Serializer",
    "TaggedUint64Serializer",
    "Float16Serializer",
    "BoolArraySerializer",
    "Int8ArraySerializer",
    "Int16ArraySerializer",
    "Int32ArraySerializer",
    "Int64ArraySerializer",
    "UInt8ArraySerializer",
    "UInt16ArraySerializer",
    "UInt32ArraySerializer",
    "UInt64ArraySerializer",
    "Float16ArraySerializer",
    "BFloat16ArraySerializer",
    "Float32ArraySerializer",
    "Float64ArraySerializer",
    "Float32Serializer",
    "Float64Serializer",
    "BFloat16Serializer",
    "StringSerializer",
    "DateSerializer",
    "TimestampSerializer",
    "DurationSerializer",
    # Collection serializers (imported)
    "CollectionSerializer",
    "ListSerializer",
    "TupleSerializer",
    "StringArraySerializer",
    "SetSerializer",
    "MapSerializer",
    # Enum and slice serializers (imported)
    "EnumSerializer",
    "SliceSerializer",
    "UnionSerializer",
    # None serializer
    "NoneSerializer",
    # Pandas serializers
    "PandasRangeIndexSerializer",
    # Array serializers
    "FORY_ARRAY_SERIALIZERS",
    "FORY_ARRAY_WRAPPERS",
    "ForyArrayFieldSerializer",
    "ForyArrayListAdapterSerializer",
    "fory_array_serializer_type",
    "fory_array_wrapper_type",
    "PyArraySerializer",
    "DynamicPyArraySerializer",
    "Numpy1DArraySerializer",
    "NDArraySerializer",
    # Bytes serializers
    "BytesSerializer",
    "BytesBufferObject",
    "PickleBufferSerializer",
    "PickleBufferObject",
    "NDArrayBufferObject",
    # Object serializers
    "StatefulSerializer",
    "ReduceSerializer",
    "TypeSerializer",
    "ModuleSerializer",
    "MappingProxySerializer",
    "ObjectSerializer",
    # Function serializers
    "FunctionSerializer",
    "NativeFuncMethodSerializer",
    "MethodSerializer",
    # Enum helpers
    "NonExistEnum",
    "NonExistEnumSerializer",
    # Unsupported
    "UnsupportedSerializer",
    # Constants
    "typecode_dict",
    "typeid_code",
]
