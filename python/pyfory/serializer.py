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
import importlib
import inspect
import marshal
import os
import pickle
import types

from pyfory.serialization import Buffer
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG

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
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
        CollectionSerializer,
        ListSerializer,
        TupleSerializer,
        StringArraySerializer,
        SetSerializer,
        MapSerializer,
        EnumSerializer,
        SliceSerializer,
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
        StringSerializer,
        DateSerializer,
        TimestampSerializer,
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

from pyfory.types import (
    int8_array,
    uint8_array,
    int16_array,
    int32_array,
    int64_array,
    uint16_array,
    uint32_array,
    uint64_array,
    float32_array,
    float64_array,
    BoolNDArrayType,
    Int8NDArrayType,
    Uint8NDArrayType,
    Int16NDArrayType,
    Int32NDArrayType,
    Int64NDArrayType,
    Uint16NDArrayType,
    Uint32NDArrayType,
    Uint64NDArrayType,
    Float32NDArrayType,
    Float64NDArrayType,
    TypeId,
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
typecode_dict = (
    {
        # bytes use BytesSerializer; array.array uses explicit typecodes.
        "b": (1, int8_array, TypeId.INT8_ARRAY),
        "B": (1, uint8_array, TypeId.UINT8_ARRAY),
        "h": (2, int16_array, TypeId.INT16_ARRAY),
        "i": (4, int32_array, TypeId.INT32_ARRAY),
        "l": (8, int64_array, TypeId.INT64_ARRAY),
        "H": (2, uint16_array, TypeId.UINT16_ARRAY),
        "I": (4, uint32_array, TypeId.UINT32_ARRAY),
        "L": (8, uint64_array, TypeId.UINT64_ARRAY),
        "f": (4, float32_array, TypeId.FLOAT32_ARRAY),
        "d": (8, float64_array, TypeId.FLOAT64_ARRAY),
    }
    if not _WINDOWS
    else {
        "b": (1, int8_array, TypeId.INT8_ARRAY),
        "B": (1, uint8_array, TypeId.UINT8_ARRAY),
        "h": (2, int16_array, TypeId.INT16_ARRAY),
        "l": (4, int32_array, TypeId.INT32_ARRAY),
        "q": (8, int64_array, TypeId.INT64_ARRAY),
        "H": (2, uint16_array, TypeId.UINT16_ARRAY),
        "L": (4, uint32_array, TypeId.UINT32_ARRAY),
        "Q": (8, uint64_array, TypeId.UINT64_ARRAY),
        "f": (4, float32_array, TypeId.FLOAT32_ARRAY),
        "d": (8, float64_array, TypeId.FLOAT64_ARRAY),
    }
)

typeid_code = (
    {
        TypeId.INT8_ARRAY: "b",
        TypeId.UINT8_ARRAY: "B",
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "i",
        TypeId.INT64_ARRAY: "l",
        TypeId.UINT16_ARRAY: "H",
        TypeId.UINT32_ARRAY: "I",
        TypeId.UINT64_ARRAY: "L",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
    if not _WINDOWS
    else {
        TypeId.INT8_ARRAY: "b",
        TypeId.UINT8_ARRAY: "B",
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "l",
        TypeId.INT64_ARRAY: "q",
        TypeId.UINT16_ARRAY: "H",
        TypeId.UINT32_ARRAY: "L",
        TypeId.UINT64_ARRAY: "Q",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
)


class PyArraySerializer(Serializer):
    typecode_dict = typecode_dict
    typecodearray_type = (
        {
            "b": int8_array,
            "B": uint8_array,
            "h": int16_array,
            "i": int32_array,
            "l": int64_array,
            "H": uint16_array,
            "I": uint32_array,
            "L": uint64_array,
            "f": float32_array,
            "d": float64_array,
        }
        if not _WINDOWS
        else {
            "b": int8_array,
            "B": uint8_array,
            "h": int16_array,
            "l": int32_array,
            "q": int64_array,
            "H": uint16_array,
            "L": uint32_array,
            "Q": uint64_array,
            "f": float32_array,
            "d": float64_array,
        }
    )

    def __init__(self, type_resolver, ftype, type_id: str):
        super().__init__(type_resolver, ftype)
        self.typecode = typeid_code[type_id]
        self.itemsize, ftype, self.type_id = typecode_dict[self.typecode]

    def write(self, buffer, value):
        assert value.itemsize == self.itemsize
        view = memoryview(value)
        assert view.format == self.typecode
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


class DynamicPyArraySerializer(Serializer):
    """Serializer for dynamic Python arrays that handles any typecode."""

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)

    def write(self, buffer, value):
        itemsize, ftype, type_id = typecode_dict[value.typecode]
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
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int8): (1, "b", Int8NDArrayType, TypeId.INT8_ARRAY),
            np.dtype(np.uint8): (1, "B", Uint8NDArrayType, TypeId.UINT8_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "i", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "l", Int64NDArrayType, TypeId.INT64_ARRAY),
            np.dtype(np.uint16): (2, "H", Uint16NDArrayType, TypeId.UINT16_ARRAY),
            np.dtype(np.uint32): (4, "I", Uint32NDArrayType, TypeId.UINT32_ARRAY),
            np.dtype(np.uint64): (8, "L", Uint64NDArrayType, TypeId.UINT64_ARRAY),
            np.dtype(np.float32): (4, "f", Float32NDArrayType, TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", Float64NDArrayType, TypeId.FLOAT64_ARRAY),
        }
        if not _WINDOWS
        else {
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int8): (1, "b", Int8NDArrayType, TypeId.INT8_ARRAY),
            np.dtype(np.uint8): (1, "B", Uint8NDArrayType, TypeId.UINT8_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "l", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "q", Int64NDArrayType, TypeId.INT64_ARRAY),
            np.dtype(np.uint16): (2, "H", Uint16NDArrayType, TypeId.UINT16_ARRAY),
            np.dtype(np.uint32): (4, "L", Uint32NDArrayType, TypeId.UINT32_ARRAY),
            np.dtype(np.uint64): (8, "Q", Uint64NDArrayType, TypeId.UINT64_ARRAY),
            np.dtype(np.float32): (4, "f", Float32NDArrayType, TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", Float64NDArrayType, TypeId.FLOAT64_ARRAY),
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
        shape = tuple(read_context.read_var_uint32() for _ in range(ndim))
        if dtype.kind == "O":
            length = read_context.read_varint32()
            items = [read_context.read_ref() for _ in range(length)]
            return np.array(items, dtype=object)
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

        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state:
            read_context.policy.intercept_setstate(obj, state)
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
        assert reduce_data_num_items <= 6, read_context
        reduce_data = [None] * 6
        for i in range(reduce_data_num_items):
            reduce_data[i] = read_context.read_ref()

        if reduce_data[0] == 0:
            # Case 1: Global name
            global_name = reduce_data[1]
            # Import and return the global object
            if "." in global_name:
                module_name, obj_name = global_name.rsplit(".", 1)
                module = __import__(module_name, fromlist=[obj_name])
                return getattr(module, obj_name)
            else:
                # Handle case where global_name doesn't contain a dot
                # This might be a built-in type or a simple name
                try:
                    import builtins

                    return getattr(builtins, global_name)
                except AttributeError:
                    raise ValueError(f"Cannot resolve global name: {global_name}")
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
                obj = callable_obj(*args)

            # Restore state if present
            if state is not None:
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
        cls = importlib.import_module(module_name)
        for name in qualname.split("."):
            cls = getattr(cls, name)
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
        bases = tuple(read_context.read_ref() for _ in range(num_bases))
        cls = type(name, bases, {})
        read_context.set_read_ref(ref_id, cls)

        for _ in range(read_context.read_var_uint32()):
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
        result = read_context.policy.validate_module(mod_name)
        if result is not None:
            if isinstance(result, types.ModuleType):
                return result
            assert isinstance(result, str), f"validate_module must return module, str, or None, got {type(result)}"
            mod_name = result
        return importlib.import_module(mod_name)


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
            func = getattr(self_obj, method_name)
            result = read_context.policy.validate_function(func, is_local=False)
            if result is not None:
                func = result
            return func

        if func_type_id == 1:
            module = read_context.read_string()
            qualname = read_context.read_string()
            mod = importlib.import_module(module)
            for name in qualname.split("."):
                mod = getattr(mod, name)
            result = read_context.policy.validate_function(mod, is_local=False)
            if result is not None:
                mod = result
            return mod

        module = read_context.read_string()
        qualname = read_context.read_string()
        name = qualname.rsplit(".")[-1]

        marshalled_code = read_context.read_bytes_and_size()
        code = marshal.loads(marshalled_code)

        has_defaults = read_context.read_bool()
        defaults = None
        if has_defaults:
            num_defaults = read_context.read_var_uint32()
            default_values = []
            for _ in range(num_defaults):
                default_values.append(read_context.read_ref())
            defaults = tuple(default_values)

        has_closure = read_context.read_bool()
        num_freevars = read_context.read_var_uint32()
        closure = None

        closure_values = []
        if has_closure:
            for _ in range(num_freevars):
                closure_values.append(read_context.read_ref())

            closure = tuple(types.CellType(value) for value in closure_values)

        num_freevars = read_context.read_var_uint32()
        freevars = []
        for _ in range(num_freevars):
            freevars.append(read_context.read_string())

        globals_dict = read_context.read_ref()

        # Create a globals dictionary with module's globals as the base
        func_globals = {}
        try:
            mod = importlib.import_module(module)
            if mod:
                func_globals.update(mod.__dict__)
        except (KeyError, AttributeError):
            pass

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

        result = read_context.policy.validate_function(func, is_local=True)
        if result is not None:
            func = result
        return func

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
            mod = importlib.import_module(module)
            func = getattr(mod, name)
        else:
            obj = read_context.read_ref()
            func = getattr(obj, name)
        result = read_context.policy.validate_function(func, is_local=False)
        if result is not None:
            func = result
        return func


class MethodSerializer(Serializer):
    """Serializer for bound method objects."""

    def __init__(self, type_resolver, cls):
        super().__init__(type_resolver, cls)
        self.cls = cls

    def write(self, write_context, value):
        instance = value.__self__
        method_name = value.__func__.__name__

        write_context.write_ref(instance)
        write_context.write_string(method_name)

    def read(self, read_context):
        instance = read_context.read_ref()
        method_name = read_context.read_string()

        method = getattr(instance, method_name)
        cls = method.__self__.__class__
        is_local = cls.__module__ == "__main__" or "<locals>" in cls.__qualname__
        result = read_context.policy.validate_method(method, is_local=is_local)
        if result is not None:
            method = result
        return method


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
        read_context.policy.authorize_instantiation(self.type_)
        obj = self.type_.__new__(self.type_)
        read_context.reference(obj)
        num_fields = read_context.read_var_uint32()
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
    "Float32Serializer",
    "Float64Serializer",
    "StringSerializer",
    "DateSerializer",
    "TimestampSerializer",
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
