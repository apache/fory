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

from pyfory.buffer import Buffer
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

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import (  # noqa: F401, F811
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
        BFloat16Serializer,
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
        BFloat16Serializer,
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
    def __init__(self, fory):
        super().__init__(fory, None)
        self.need_to_write_ref = False

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError

    def write(self, buffer, value):
        pass

    def read(self, buffer):
        return None


class PandasRangeIndexSerializer(Serializer):
    __slots__ = "_cached"

    def __init__(self, fory):
        import pandas as pd

        super().__init__(fory, pd.RangeIndex)

    def write(self, buffer, value):
        fory = self.fory
        start = value.start
        stop = value.stop
        step = value.step
        if type(start) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(start)
        else:
            if start is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, start)
        if type(stop) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, stop)
        if type(step) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.write_no_ref(buffer, step)
        fory.write_ref(buffer, value.dtype)
        fory.write_ref(buffer, value.name)

    def read(self, buffer):
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = self.fory.read_no_ref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = self.fory.read_no_ref(buffer)
        dtype = self.fory.read_ref(buffer)
        name = self.fory.read_ref(buffer)
        return self.type_(start, stop, step, dtype=dtype, name=name)

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


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
        TypeId.BFLOAT16_ARRAY: "H",  # bfloat16 uses 'H' typecode (uint16)
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

    def __init__(self, fory, ftype, type_id: str):
        super().__init__(fory, ftype)
        self.typecode = typeid_code[type_id]
        self.itemsize, ftype, self.type_id = typecode_dict[self.typecode]

    def xwrite(self, buffer, value):
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

    def xread(self, buffer):
        data = buffer.read_bytes_and_size()
        arr = array.array(self.typecode, [])
        arr.frombytes(data)
        if not is_little_endian and self.itemsize > 1:
            # Swap bytes on big-endian machines for multi-byte types
            arr.byteswap()
        return arr

    def write(self, buffer, value: array.array):
        nbytes = len(value) * value.itemsize
        buffer.write_string(value.typecode)
        buffer.write_var_uint32(nbytes)
        if is_little_endian or value.itemsize == 1:
            buffer.write_buffer(value)
        else:
            # Swap bytes on big-endian machines for multi-byte types
            swapped = array.array(value.typecode, value)
            swapped.byteswap()
            buffer.write_buffer(swapped)

    def read(self, buffer):
        typecode = buffer.read_string()
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode[0], [])  # Take first character
        arr.frombytes(data)
        if not is_little_endian and arr.itemsize > 1:
            # Swap bytes on big-endian machines for multi-byte types
            arr.byteswap()
        return arr


class DynamicPyArraySerializer(Serializer):
    """Serializer for dynamic Python arrays that handles any typecode."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self._serializer = ReduceSerializer(fory, cls)

    def xwrite(self, buffer, value):
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

    def xread(self, buffer):
        type_id = buffer.read_uint8()
        typecode = typeid_code[type_id]
        itemsize = typecode_dict[typecode][0]
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode, [])
        arr.frombytes(data)
        if not is_little_endian and itemsize > 1:
            arr.byteswap()
        return arr

    def write(self, buffer, value):
        self._serializer.write(buffer, value)

    def read(self, buffer):
        return self._serializer.read(buffer)


class BFloat16ArraySerializer(Serializer):
    def __init__(self, fory, ftype, type_id: int):
        super().__init__(fory, ftype)
        self.type_id = type_id
        self.itemsize = 2

    def write(self, buffer, value):
        from pyfory.bfloat16_array import BFloat16Array

        if isinstance(value, BFloat16Array):
            arr_data = value._data
        elif isinstance(value, array.array) and value.typecode == "H":
            arr_data = value
        else:
            arr_data = BFloat16Array(value)._data
        nbytes = len(arr_data) * 2
        buffer.write_var_uint32(nbytes)
        if nbytes > 0:
            if is_little_endian:
                buffer.write_buffer(arr_data)
            else:
                swapped = array.array("H", arr_data)
                swapped.byteswap()
                buffer.write_buffer(swapped)

    def read(self, buffer):
        from pyfory.bfloat16_array import BFloat16Array

        data = buffer.read_bytes_and_size()
        arr = array.array("H", [])
        arr.frombytes(data)
        if not is_little_endian:
            arr.byteswap()
        bf16_arr = BFloat16Array.__new__(BFloat16Array)
        bf16_arr._data = arr
        return bf16_arr

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


class Numpy1DArraySerializer(Serializer):
    dtypes_dict = _np_dtypes_dict

    def __init__(self, fory, ftype, dtype):
        super().__init__(fory, ftype)
        self.dtype = dtype
        self.itemsize, self.typecode, _, self.type_id = _np_dtypes_dict[self.dtype]
        self._serializer = ReduceSerializer(fory, np.ndarray)

    def xwrite(self, buffer, value):
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

    def xread(self, buffer):
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

    def write(self, buffer, value):
        self._serializer.write(buffer, value)

    def read(self, buffer):
        return self._serializer.read(buffer)


class NDArraySerializer(Serializer):
    def xwrite(self, buffer, value):
        itemsize, typecode, ftype, type_id = _np_dtypes_dict[value.dtype]
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_uint8(type_id)
        buffer.write_var_uint32(nbytes)
        if value.dtype == np.dtype("bool") or not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        raise NotImplementedError("Multi-dimensional array not supported currently")

    def write(self, buffer, value):
        fory = self.fory
        dtype = value.dtype
        fory.write_ref(buffer, dtype)
        buffer.write_var_uint32(len(value.shape))
        for dim in value.shape:
            buffer.write_var_uint32(dim)
        if dtype.kind == "O":
            buffer.write_varint32(len(value))
            for item in value:
                fory.write_ref(buffer, item)
        else:
            fory.write_buffer_object(buffer, NDArrayBufferObject(value))

    def read(self, buffer):
        fory = self.fory
        dtype = fory.read_ref(buffer)
        ndim = buffer.read_var_uint32()
        shape = tuple(buffer.read_var_uint32() for _ in range(ndim))
        if dtype.kind == "O":
            length = buffer.read_varint32()
            items = [fory.read_ref(buffer) for _ in range(length)]
            return np.array(items, dtype=object)
        fory_buf = fory.read_buffer_object(buffer)
        if isinstance(fory_buf, memoryview):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        elif isinstance(fory_buf, bytes):
            return np.frombuffer(fory_buf, dtype=dtype).reshape(shape)
        return np.frombuffer(fory_buf.to_pybytes(), dtype=dtype).reshape(shape)


class BytesSerializer(Serializer):
    def write(self, buffer, value):
        if self.fory.buffer_callback is None:
            buffer.write_bytes_and_size(value)
            return
        self.fory.write_buffer_object(buffer, BytesBufferObject(value))

    def read(self, buffer):
        if not self.fory.is_peer_out_of_band_enabled:
            return buffer.read_bytes_and_size()
        fory_buf = self.fory.read_buffer_object(buffer)
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
    def write(self, buffer, value):
        self.fory.write_buffer_object(buffer, PickleBufferObject(value))

    def read(self, buffer):
        fory_buf = self.fory.read_buffer_object(buffer)
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

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def write(self, buffer, value):
        state = value.__getstate__()
        args = ()
        kwargs = {}
        if self._getnewargs_ex is not None:
            args, kwargs = self._getnewargs_ex(value)
        elif self._getnewargs is not None:
            args = self._getnewargs(value)

        # Serialize constructor arguments first
        self.fory.write_ref(buffer, args)
        self.fory.write_ref(buffer, kwargs)

        # Then serialize the state
        self.fory.write_ref(buffer, state)

    def read(self, buffer):
        fory = self.fory
        args = fory.read_ref(buffer)
        kwargs = fory.read_ref(buffer)
        state = fory.read_ref(buffer)

        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state:
            fory.policy.intercept_setstate(obj, state)
            obj.__setstate__(state)
        return obj


class ReduceSerializer(Serializer):
    """
    Serializer for objects that support __reduce__ or __reduce_ex__.
    Uses Fory's native serialization for better cross-language support.
    Has higher precedence than StatefulSerializer.
    """

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls
        # Cache the method references as fields in the serializer.
        self._reduce_ex = getattr(cls, "__reduce_ex__", None)
        self._reduce = getattr(cls, "__reduce__", None)
        self._getnewargs_ex = getattr(cls, "__getnewargs_ex__", None)
        self._getnewargs = getattr(cls, "__getnewargs__", None)

    def write(self, buffer, value):
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
        buffer.write_var_uint32(len(reduce_data))
        fory = self.fory
        for item in reduce_data:
            fory.write_ref(buffer, item)

    def read(self, buffer):
        reduce_data_num_items = buffer.read_var_uint32()
        assert reduce_data_num_items <= 6, buffer
        reduce_data = [None] * 6
        fory = self.fory
        for i in range(reduce_data_num_items):
            reduce_data[i] = fory.read_ref(buffer)

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

            obj = fory.policy.intercept_reduce_call(callable_obj, args)
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

            result = fory.policy.inspect_reduced_object(obj)
            if result is not None:
                obj = result
            return obj
        else:
            raise ValueError(f"Invalid reduce data format flag: {reduce_data[0]}")


__skip_class_attr_names__ = ("__module__", "__qualname__", "__dict__", "__weakref__")


class TypeSerializer(Serializer):
    """Serializer for Python type objects (classes), including local classes."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls

    def write(self, buffer, value):
        module_name = value.__module__
        qualname = value.__qualname__

        if module_name == "__main__" or "<locals>" in qualname:
            # Local class - serialize full context
            buffer.write_int8(1)  # Local class marker
            self._serialize_local_class(buffer, value)
        else:
            buffer.write_int8(0)  # Global class marker
            buffer.write_string(module_name)
            buffer.write_string(qualname)

    def read(self, buffer):
        class_type = buffer.read_int8()

        if class_type == 1:
            # Local class - deserialize from full context
            return self._deserialize_local_class(buffer)
        else:
            # Global class - import by module and name
            module_name = buffer.read_string()
            qualname = buffer.read_string()
            cls = importlib.import_module(module_name)
            for name in qualname.split("."):
                cls = getattr(cls, name)
            result = self.fory.policy.validate_class(cls, is_local=False)
            if result is not None:
                cls = result
            return cls

    def _serialize_local_class(self, buffer, cls):
        """Serialize a local class by capturing its creation context."""
        assert self.fory.track_ref, "Reference tracking must be enabled for local classes serialization"
        # Basic class information
        module = cls.__module__
        qualname = cls.__qualname__
        buffer.write_string(module)
        buffer.write_string(qualname)
        fory = self.fory

        # Serialize base classes
        # Let Fory's normal serialization handle bases (including other local classes)
        bases = cls.__bases__
        buffer.write_var_uint32(len(bases))
        for base in bases:
            fory.write_ref(buffer, base)

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
        # serialize method specially to avoid circular deps in method deserialization
        buffer.write_var_uint32(len(class_methods))
        for i in range(len(class_methods)):
            buffer.write_string(attr_names[i])
            class_method = class_methods[i]
            fory.write_ref(buffer, class_method.__func__)

        # Let Fory's normal serialization handle the class dict
        # This will use FunctionSerializer for methods, which handles closures properly
        fory.write_ref(buffer, class_dict)

    def _deserialize_local_class(self, buffer):
        """Deserialize a local class by recreating it with the captured context."""
        fory = self.fory
        assert fory.track_ref, "Reference tracking must be enabled for local classes deserialization"
        # Read basic class information
        module = buffer.read_string()
        qualname = buffer.read_string()
        name = qualname.rsplit(".", 1)[-1]
        ref_id = fory.ref_resolver.last_preserved_ref_id()

        # Read base classes
        num_bases = buffer.read_var_uint32()
        bases = tuple([fory.read_ref(buffer) for _ in range(num_bases)])
        # Create the class using type() constructor
        cls = type(name, bases, {})
        # `class_dict` may reference to `cls`, which is a circular reference
        fory.ref_resolver.set_read_object(ref_id, cls)

        # classmethods
        for i in range(buffer.read_var_uint32()):
            attr_name = buffer.read_string()
            func = fory.read_ref(buffer)
            method = types.MethodType(func, cls)
            setattr(cls, attr_name, method)
        # Read class dictionary
        # Fory's normal deserialization will handle methods via FunctionSerializer
        class_dict = fory.read_ref(buffer)
        for k, v in class_dict.items():
            setattr(cls, k, v)

        # Set module and qualname
        cls.__module__ = module
        cls.__qualname__ = qualname
        result = fory.policy.validate_class(cls, is_local=True)
        if result is not None:
            cls = result
        return cls


class ModuleSerializer(Serializer):
    """Serializer for python module"""

    def __init__(self, fory):
        super().__init__(fory, types.ModuleType)

    def write(self, buffer, value):
        buffer.write_string(value.__name__)

    def read(self, buffer):
        mod_name = buffer.read_string()
        result = self.fory.policy.validate_module(mod_name)
        if result is not None:
            if isinstance(result, types.ModuleType):
                return result
            assert isinstance(result, str), f"validate_module must return module, str, or None, got {type(result)}"
            mod_name = result
        return importlib.import_module(mod_name)


class MappingProxySerializer(Serializer):
    def __init__(self, fory):
        super().__init__(fory, types.MappingProxyType)

    def write(self, buffer, value):
        self.fory.write_ref(buffer, dict(value))

    def read(self, buffer):
        return types.MappingProxyType(self.fory.read_ref(buffer))


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

    def _serialize_function(self, buffer, func):
        """Serialize a function by capturing all its components."""
        # Get function metadata
        instance = getattr(func, "__self__", None)
        if instance is not None and not inspect.ismodule(instance):
            # Handle bound methods
            self_obj = instance
            func_name = func.__name__
            # Serialize as a tuple (is_method, self_obj, method_name)
            buffer.write_int8(0)  # is a method
            # For the 'self' object, we need to use fory's serialization
            self.fory.write_ref(buffer, self_obj)
            buffer.write_string(func_name)
            return

        # Regular function or lambda
        code = func.__code__
        module = func.__module__
        qualname = func.__qualname__

        if "<locals>" not in qualname and module != "__main__":
            buffer.write_int8(1)  # Not a method
            buffer.write_string(module)
            buffer.write_string(qualname)
            return

        # Serialize function metadata
        buffer.write_int8(2)  # Not a method
        buffer.write_string(module)
        buffer.write_string(qualname)

        defaults = func.__defaults__
        closure = func.__closure__
        globals_dict = func.__globals__

        # Instead of trying to serialize the code object in parts, use marshal
        # which is specifically designed for code objects
        marshalled_code = marshal.dumps(code)
        buffer.write_bytes_and_size(marshalled_code)

        # Serialize defaults (or None if no defaults)
        # Write whether defaults exist
        buffer.write_bool(defaults is not None)
        if defaults is not None:
            # Write the number of default arguments
            buffer.write_var_uint32(len(defaults))
            # Serialize each default value individually
            for default_value in defaults:
                self.fory.write_ref(buffer, default_value)

        # Handle closure
        # We need to serialize both the closure values and the fact that there is a closure
        # The code object's co_freevars tells us what variables are in the closure
        buffer.write_bool(closure is not None)
        buffer.write_var_uint32(len(code.co_freevars) if code.co_freevars else 0)

        if closure:
            # Extract and serialize each closure cell's contents
            for cell in closure:
                self.fory.write_ref(buffer, cell.cell_contents)

        # Serialize free variable names as a list of strings
        # Convert tuple to list since tuple might not be registered
        freevars_list = list(code.co_freevars) if code.co_freevars else []
        buffer.write_var_uint32(len(freevars_list))
        for name in freevars_list:
            buffer.write_string(name)

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
        self.fory.write_ref(buffer, globals_to_serialize)

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

        self.fory.write_ref(buffer, attrs)

    def _deserialize_function(self, buffer):
        """Deserialize a function from its components."""

        # Check if it's a method
        func_type_id = buffer.read_int8()
        if func_type_id == 0:
            # Handle bound methods
            self_obj = self.fory.read_ref(buffer)
            method_name = buffer.read_string()
            func = getattr(self_obj, method_name)
            result = self.fory.policy.validate_function(func, is_local=False)
            if result is not None:
                func = result
            return func

        if func_type_id == 1:
            module = buffer.read_string()
            qualname = buffer.read_string()
            mod = importlib.import_module(module)
            for name in qualname.split("."):
                mod = getattr(mod, name)
            result = self.fory.policy.validate_function(mod, is_local=False)
            if result is not None:
                mod = result
            return mod

        # Regular function or lambda
        module = buffer.read_string()
        qualname = buffer.read_string()
        name = qualname.rsplit(".")[-1]

        # Use marshal to load the code object, which handles all Python versions correctly
        marshalled_code = buffer.read_bytes_and_size()
        code = marshal.loads(marshalled_code)

        # Deserialize defaults
        has_defaults = buffer.read_bool()
        defaults = None
        if has_defaults:
            # Read the number of default arguments
            num_defaults = buffer.read_var_uint32()
            # Deserialize each default value
            default_values = []
            for _ in range(num_defaults):
                default_values.append(self.fory.read_ref(buffer))
            defaults = tuple(default_values)

        # Handle closure
        has_closure = buffer.read_bool()
        num_freevars = buffer.read_var_uint32()
        closure = None

        # Read closure values if there are any
        closure_values = []
        if has_closure:
            for _ in range(num_freevars):
                closure_values.append(self.fory.read_ref(buffer))

            # Create closure cells
            closure = tuple(types.CellType(value) for value in closure_values)

        # Read free variable names from strings
        num_freevars = buffer.read_var_uint32()
        freevars = []
        for _ in range(num_freevars):
            freevars.append(buffer.read_string())

        # Handle globals
        globals_dict = self.fory.read_ref(buffer)

        # Create a globals dictionary with module's globals as the base
        func_globals = {}
        try:
            mod = importlib.import_module(module)
            if mod:
                func_globals.update(mod.__dict__)
        except (KeyError, AttributeError):
            pass

        # Add the deserialized globals
        func_globals.update(globals_dict)

        # Ensure __builtins__ is available
        if "__builtins__" not in func_globals:
            func_globals["__builtins__"] = builtins

        # Create function
        func = types.FunctionType(code, func_globals, name, defaults, closure)

        # Set function attributes
        func.__module__ = module
        func.__qualname__ = qualname

        # Deserialize and set additional attributes
        attrs = self.fory.read_ref(buffer)
        for attr_name, attr_value in attrs.items():
            setattr(func, attr_name, attr_value)

        result = self.fory.policy.validate_function(func, is_local=True)
        if result is not None:
            func = result
        return func

    def xwrite(self, buffer, value):
        raise NotImplementedError()

    def xread(self, buffer):
        raise NotImplementedError()

    def write(self, buffer, value):
        """Serialize a function for Python-only mode."""
        self._serialize_function(buffer, value)

    def read(self, buffer):
        """Deserialize a function for Python-only mode."""
        return self._deserialize_function(buffer)


class NativeFuncMethodSerializer(Serializer):
    def write(self, buffer, func):
        name = func.__name__
        buffer.write_string(name)
        obj = getattr(func, "__self__", None)
        if obj is None or inspect.ismodule(obj):
            buffer.write_bool(True)
            module = func.__module__
            buffer.write_string(module)
        else:
            buffer.write_bool(False)
            self.fory.write_ref(buffer, obj)

    def read(self, buffer):
        name = buffer.read_string()
        if buffer.read_bool():
            module = buffer.read_string()
            mod = importlib.import_module(module)
            func = getattr(mod, name)
        else:
            obj = self.fory.read_ref(buffer)
            func = getattr(obj, name)
        result = self.fory.policy.validate_function(func, is_local=False)
        if result is not None:
            func = result
        return func


class MethodSerializer(Serializer):
    """Serializer for bound method objects."""

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls

    def write(self, buffer, value):
        # Serialize bound method as (instance, method_name)
        instance = value.__self__
        method_name = value.__func__.__name__

        self.fory.write_ref(buffer, instance)
        buffer.write_string(method_name)

    def read(self, buffer):
        instance = self.fory.read_ref(buffer)
        method_name = buffer.read_string()

        method = getattr(instance, method_name)
        cls = method.__self__.__class__
        is_local = cls.__module__ == "__main__" or "<locals>" in cls.__qualname__
        result = self.fory.policy.validate_method(method, is_local=is_local)
        if result is not None:
            method = result
        return method

    def xwrite(self, buffer, value):
        return self.write(buffer, value)

    def xread(self, buffer):
        return self.read(buffer)


class ObjectSerializer(Serializer):
    """Serializer for regular Python objects.
    It serializes objects based on `__dict__` or `__slots__`.
    """

    def __init__(self, fory, clz: type):
        super().__init__(fory, clz)
        # If the class defines __slots__, compute and store a sorted list once
        slots = getattr(clz, "__slots__", None)
        self._slot_field_names = None
        if slots is not None:
            # __slots__ can be a string or iterable of strings
            if isinstance(slots, str):
                slots = [slots]
            self._slot_field_names = sorted(slots)

    def write(self, buffer, value):
        # Use precomputed slots if available, otherwise sort instance __dict__ keys
        if self._slot_field_names is not None:
            sorted_field_names = self._slot_field_names
        else:
            sorted_field_names = sorted(value.__dict__.keys())

        buffer.write_var_uint32(len(sorted_field_names))
        for field_name in sorted_field_names:
            buffer.write_string(field_name)
            field_value = getattr(value, field_name)
            self.fory.write_ref(buffer, field_value)

    def read(self, buffer):
        fory = self.fory
        fory.policy.authorize_instantiation(self.type_)
        obj = self.type_.__new__(self.type_)
        fory.ref_resolver.reference(obj)
        num_fields = buffer.read_var_uint32()
        for _ in range(num_fields):
            field_name = buffer.read_string()
            field_value = fory.read_ref(buffer)
            setattr(obj, field_name, field_value)
        return obj

    def xwrite(self, buffer, value):
        # for cross-language or minimal framing, reuse the same logic
        return self.write(buffer, value)

    def xread(self, buffer):
        # symmetric to xwrite
        return self.read(buffer)


@dataclasses.dataclass
class NonExistEnum:
    value: int = -1
    name: str = ""


class NonExistEnumSerializer(Serializer):
    def __init__(self, fory):
        super().__init__(fory, NonExistEnum)
        self.need_to_write_ref = False

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    def write(self, buffer, value):
        buffer.write_string(value.name)

    def read(self, buffer):
        name = buffer.read_string()
        return NonExistEnum(name=name)

    def xwrite(self, buffer, value):
        buffer.write_var_uint32(value.value)

    def xread(self, buffer):
        value = buffer.read_var_uint32()
        return NonExistEnum(value=value)


class UnsupportedSerializer(Serializer):
    def write(self, buffer, value):
        self.fory.handle_unsupported_write(value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)

    def xwrite(self, buffer, value):
        raise NotImplementedError(f"{self.type_} is not supported for xwrite")

    def xread(self, buffer):
        raise NotImplementedError(f"{self.type_} is not supported for xread")


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
    "BFloat16Serializer",
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
