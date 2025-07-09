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
import itertools
import os
import pickle
import typing
import warnings
from weakref import WeakValueDictionary

import pyfory.lib.mmh3
from pyfory.buffer import Buffer
from pyfory.codegen import (
    gen_write_nullable_basic_stmts,
    gen_read_nullable_basic_stmts,
    compile_function,
)
from pyfory.error import TypeNotCompatibleError
from pyfory.lib.collection import WeakIdentityKeyDictionary
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory import Language

try:
    import numpy as np
except ImportError:
    np = None

from pyfory._fory import (
    NOT_NULL_INT64_FLAG,
    BufferObject,
)

_WINDOWS = os.name == "nt"

from pyfory._serialization import ENABLE_FORY_CYTHON_SERIALIZATION

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory._serialization import (  # noqa: F401, F811
        Serializer,
        CrossLanguageCompatibleSerializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
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
        SubMapSerializer,
        EnumSerializer,
        SliceSerializer,
    )
else:
    from pyfory._serializer import (  # noqa: F401 # pylint: disable=unused-import
        Serializer,
        CrossLanguageCompatibleSerializer,
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
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
        SubMapSerializer,
        EnumSerializer,
        SliceSerializer,
    )

from pyfory.type import (
    Int16ArrayType,
    Int32ArrayType,
    Int64ArrayType,
    Float32ArrayType,
    Float64ArrayType,
    BoolNDArrayType,
    Int16NDArrayType,
    Int32NDArrayType,
    Int64NDArrayType,
    Float32NDArrayType,
    Float64NDArrayType,
    TypeId,
    infer_field,  # Added infer_field
)


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


class _PickleStub:
    pass


class PickleStrongCacheStub:
    pass


class PickleCacheStub:
    pass


class PickleStrongCacheSerializer(Serializer):
    """If we can't create weak ref to object, use this cache serializer instead.
    clear cache by threshold to avoid memory leak."""

    __slots__ = "_cached", "_clear_threshold", "_counter"

    def __init__(self, fory, clear_threshold: int = 1000):
        super().__init__(fory, PickleStrongCacheStub)
        self._cached = {}
        self._clear_threshold = clear_threshold

    def write(self, buffer, value):
        serialized = self._cached.get(value)
        if serialized is None:
            serialized = pickle.dumps(value)
            self._cached[value] = serialized
        buffer.write_bytes_and_size(serialized)
        if len(self._cached) == self._clear_threshold:
            self._cached.clear()

    def read(self, buffer):
        return pickle.loads(buffer.read_bytes_and_size())

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


class PickleCacheSerializer(Serializer):
    __slots__ = "_cached", "_reverse_cached"

    def __init__(self, fory):
        super().__init__(fory, PickleCacheStub)
        self._cached = WeakIdentityKeyDictionary()
        self._reverse_cached = WeakValueDictionary()

    def write(self, buffer, value):
        cache = self._cached.get(value)
        if cache is None:
            serialized = pickle.dumps(value)
            value_hash = pyfory.lib.mmh3.hash_buffer(serialized)[0]
            cache = value_hash, serialized
            self._cached[value] = cache
        buffer.write_int64(cache[0])
        buffer.write_bytes_and_size(cache[1])

    def read(self, buffer):
        value_hash = buffer.read_int64()
        value = self._reverse_cached.get(value_hash)
        if value is None:
            value = pickle.loads(buffer.read_bytes_and_size())
            self._reverse_cached[value_hash] = value
        else:
            size = buffer.read_int32()
            buffer.skip(size)
        return value

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


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
                fory.serialize_nonref(buffer, start)
        if type(stop) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.serialize_nonref(buffer, stop)
        if type(step) is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                fory.serialize_nonref(buffer, step)
        fory.serialize_ref(buffer, value.dtype)
        fory.serialize_ref(buffer, value.name)

    def read(self, buffer):
        if buffer.read_int8() == NULL_FLAG:
            start = None
        else:
            start = self.fory.deserialize_nonref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            stop = None
        else:
            stop = self.fory.deserialize_nonref(buffer)
        if buffer.read_int8() == NULL_FLAG:
            step = None
        else:
            step = self.fory.deserialize_nonref(buffer)
        dtype = self.fory.deserialize_ref(buffer)
        name = self.fory.deserialize_ref(buffer)
        return self.type_(start, stop, step, dtype=dtype, name=name)

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError


_jit_context = locals()


_ENABLE_FORY_PYTHON_JIT = os.environ.get("ENABLE_FORY_PYTHON_JIT", "True").lower() in (
    "true",
    "1",
)

# Moved from L32 to here, after all Serializer base classes and specific serializers
# like ListSerializer, MapSerializer, PickleSerializer are defined or imported
# and before DataClassSerializer which uses ComplexTypeVisitor from _struct.
from pyfory._struct import _get_hash, _sort_fields, ComplexTypeVisitor


class DataClassSerializer(Serializer):
    def __init__(self, fory, clz: type, xlang: bool = False):
        super().__init__(fory, clz)
        self._xlang = xlang
        # This will get superclass type hints too.
        self._type_hints = typing.get_type_hints(clz)
        self._field_names = sorted(self._type_hints.keys())
        self._has_slots = hasattr(clz, "__slots__")

        if self._xlang:
            self._serializers = [None] * len(self._field_names)
            visitor = ComplexTypeVisitor(fory)
            for index, key in enumerate(self._field_names):
                # Changed from self.fory.infer_field to infer_field
                serializer = infer_field(key, self._type_hints[key], visitor, types_path=[])
                self._serializers[index] = serializer
            self._serializers, self._field_names = _sort_fields(fory.type_resolver, self._field_names, self._serializers)
            self._hash = 0  # Will be computed on first xwrite/xread
            if self.fory.language == Language.PYTHON:
                import logging  # Import here to avoid circular dependency

                logger = logging.getLogger(__name__)
                logger.warning(
                    "Type of class %s shouldn't be serialized using cross-language serializer",
                    clz,
                )
        else:
            # TODO compute hash for non-xlang mode more robustly
            self._hash = len(self._field_names)
            self._generated_write_method = self._gen_write_method()
            self._generated_read_method = self._gen_read_method()
            if _ENABLE_FORY_PYTHON_JIT:
                # don't use `__slots__`, which will make instance method readonly
                self.write = self._generated_write_method
                self.read = self._generated_read_method

    def _gen_write_method(self):
        context = {}
        counter = itertools.count(0)
        buffer, fory, value, value_dict = "buffer", "fory", "value", "value_dict"
        context[fory] = self.fory
        stmts = [
            f'"""write method for {self.type_}"""',
            f"{buffer}.write_int32({self._hash})",
        ]
        if not self._has_slots:
            stmts.append(f"{value_dict} = {value}.__dict__")
        for field_name in self._field_names:
            field_type = self._type_hints[field_name]
            field_value = f"field_value{next(counter)}"
            if not self._has_slots:
                stmts.append(f"{field_value} = {value_dict}['{field_name}']")
            else:
                stmts.append(f"{field_value} = {value}.{field_name}")
            if field_type is bool:
                stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, bool))
            elif field_type is int:
                stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, int))
            elif field_type is float:
                stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, float))
            elif field_type is str:
                stmts.extend(gen_write_nullable_basic_stmts(buffer, field_value, str))
            else:
                stmts.append(f"{fory}.write_ref_pyobject({buffer}, {field_value})")
        self._write_method_code, func = compile_function(
            f"write_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer, value],
            stmts,
            context,
        )
        return func

    def _gen_read_method(self):
        context = dict(_jit_context)
        buffer, fory, obj_class, obj, obj_dict = (
            "buffer",
            "fory",
            "obj_class",
            "obj",
            "obj_dict",
        )
        ref_resolver = "ref_resolver"
        context[fory] = self.fory
        context[obj_class] = self.type_
        context[ref_resolver] = self.fory.ref_resolver
        stmts = [
            f'"""read method for {self.type_}"""',
            f"{obj} = {obj_class}.__new__({obj_class})",
            f"{ref_resolver}.reference({obj})",
            f"read_hash = {buffer}.read_int32()",
            f"if read_hash != {self._hash}:",
            f"""   raise TypeNotCompatibleError(
            "Hash read_hash is not consistent with {self._hash} for {self.type_}")""",
        ]
        if not self._has_slots:
            stmts.append(f"{obj_dict} = {obj}.__dict__")

        def set_action(value: str):
            if not self._has_slots:
                return f"{obj_dict}['{field_name}'] = {value}"
            else:
                return f"{obj}.{field_name} = {value}"

        for field_name in self._field_names:
            field_type = self._type_hints[field_name]
            if field_type is bool:
                stmts.extend(gen_read_nullable_basic_stmts(buffer, bool, set_action))
            elif field_type is int:
                stmts.extend(gen_read_nullable_basic_stmts(buffer, int, set_action))
            elif field_type is float:
                stmts.extend(gen_read_nullable_basic_stmts(buffer, float, set_action))
            elif field_type is str:
                stmts.extend(gen_read_nullable_basic_stmts(buffer, str, set_action))
            else:
                stmts.append(f"{obj}.{field_name} = {fory}.read_ref_pyobject({buffer})")
        stmts.append(f"return {obj}")
        self._read_method_code, func = compile_function(
            f"read_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer],
            stmts,
            context,
        )
        return func

    def write(self, buffer, value):
        buffer.write_int32(self._hash)
        for field_name in self._field_names:
            field_value = getattr(value, field_name)
            self.fory.serialize_ref(buffer, field_value)

    def read(self, buffer):
        hash_ = buffer.read_int32()
        if hash_ != self._hash:
            raise TypeNotCompatibleError(
                f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
            )
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        for field_name in self._field_names:
            field_value = self.fory.deserialize_ref(buffer)
            setattr(
                obj,
                field_name,
                field_value,
            )
        return obj

    def xwrite(self, buffer: Buffer, value):
        if not self._xlang:
            raise TypeError("xwrite can only be called when DataClassSerializer is in xlang mode")
        if self._hash == 0:
            self._hash = _get_hash(self.fory, self._field_names, self._type_hints)
        buffer.write_int32(self._hash)
        for index, field_name in enumerate(self._field_names):
            field_value = getattr(value, field_name)
            serializer = self._serializers[index]
            self.fory.xserialize_ref(buffer, field_value, serializer=serializer)

    def xread(self, buffer):
        if not self._xlang:
            raise TypeError("xread can only be called when DataClassSerializer is in xlang mode")
        if self._hash == 0:
            self._hash = _get_hash(self.fory, self._field_names, self._type_hints)
        hash_ = buffer.read_int32()
        if hash_ != self._hash:
            raise TypeNotCompatibleError(
                f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
            )
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        for index, field_name in enumerate(self._field_names):
            serializer = self._serializers[index]
            field_value = self.fory.xdeserialize_ref(buffer, serializer=serializer)
            setattr(
                obj,
                field_name,
                field_value,
            )
        return obj


# Use numpy array or python array module.
typecode_dict = (
    {
        # use bytes serializer for byte array.
        "h": (2, Int16ArrayType, TypeId.INT16_ARRAY),
        "i": (4, Int32ArrayType, TypeId.INT32_ARRAY),
        "l": (8, Int64ArrayType, TypeId.INT64_ARRAY),
        "f": (4, Float32ArrayType, TypeId.FLOAT32_ARRAY),
        "d": (8, Float64ArrayType, TypeId.FLOAT64_ARRAY),
    }
    if not _WINDOWS
    else {
        "h": (2, Int16ArrayType, TypeId.INT16_ARRAY),
        "l": (4, Int32ArrayType, TypeId.INT32_ARRAY),
        "q": (8, Int64ArrayType, TypeId.INT64_ARRAY),
        "f": (4, Float32ArrayType, TypeId.FLOAT32_ARRAY),
        "d": (8, Float64ArrayType, TypeId.FLOAT64_ARRAY),
    }
)

typeid_code = (
    {
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "i",
        TypeId.INT64_ARRAY: "l",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
    if not _WINDOWS
    else {
        TypeId.INT16_ARRAY: "h",
        TypeId.INT32_ARRAY: "l",
        TypeId.INT64_ARRAY: "q",
        TypeId.FLOAT32_ARRAY: "f",
        TypeId.FLOAT64_ARRAY: "d",
    }
)


class PyArraySerializer(CrossLanguageCompatibleSerializer):
    typecode_dict = typecode_dict
    typecodearray_type = (
        {
            "h": Int16ArrayType,
            "i": Int32ArrayType,
            "l": Int64ArrayType,
            "f": Float32ArrayType,
            "d": Float64ArrayType,
        }
        if not _WINDOWS
        else {
            "h": Int16ArrayType,
            "l": Int32ArrayType,
            "q": Int64ArrayType,
            "f": Float32ArrayType,
            "d": Float64ArrayType,
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
        buffer.write_varuint32(nbytes)
        buffer.write_buffer(value)

    def xread(self, buffer):
        data = buffer.read_bytes_and_size()
        arr = array.array(self.typecode, [])
        arr.frombytes(data)
        return arr

    def write(self, buffer, value: array.array):
        nbytes = len(value) * value.itemsize
        buffer.write_string(value.typecode)
        buffer.write_varuint32(nbytes)
        buffer.write_buffer(value)

    def read(self, buffer):
        typecode = buffer.read_string()
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode, [])
        arr.frombytes(data)
        return arr


class DynamicPyArraySerializer(Serializer):
    def xwrite(self, buffer, value):
        itemsize, ftype, type_id = typecode_dict[value.typecode]
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_varuint32(type_id)
        buffer.write_varuint32(nbytes)
        if not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        type_id = buffer.read_varint32()
        typecode = typeid_code[type_id]
        data = buffer.read_bytes_and_size()
        arr = array.array(typecode, [])
        arr.frombytes(data)
        return arr

    def write(self, buffer, value):
        buffer.write_varuint32(PickleSerializer.PICKLE_TYPE_ID)
        self.fory.handle_unsupported_write(buffer, value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)


if np:
    _np_dtypes_dict = (
        {
            # use bytes serializer for byte array.
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "i", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "l", Int64NDArrayType, TypeId.INT64_ARRAY),
            np.dtype(np.float32): (4, "f", Float32NDArrayType, TypeId.FLOAT32_ARRAY),
            np.dtype(np.float64): (8, "d", Float64NDArrayType, TypeId.FLOAT64_ARRAY),
        }
        if not _WINDOWS
        else {
            np.dtype(np.bool_): (1, "?", BoolNDArrayType, TypeId.BOOL_ARRAY),
            np.dtype(np.int16): (2, "h", Int16NDArrayType, TypeId.INT16_ARRAY),
            np.dtype(np.int32): (4, "l", Int32NDArrayType, TypeId.INT32_ARRAY),
            np.dtype(np.int64): (8, "q", Int64NDArrayType, TypeId.INT64_ARRAY),
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
        self.itemsize, self.format, self.typecode, self.type_id = _np_dtypes_dict[self.dtype]

    def xwrite(self, buffer, value):
        assert value.itemsize == self.itemsize
        view = memoryview(value)
        try:
            assert view.format == self.typecode
        except AssertionError as e:
            raise e
        assert view.itemsize == self.itemsize
        nbytes = len(value) * self.itemsize
        buffer.write_varuint32(nbytes)
        if self.dtype == np.dtype("bool") or not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        data = buffer.read_bytes_and_size()
        return np.frombuffer(data, dtype=self.dtype)

    def write(self, buffer, value):
        buffer.write_int8(PickleSerializer.PICKLE_TYPE_ID)
        self.fory.handle_unsupported_write(buffer, value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)


class NDArraySerializer(Serializer):
    def xwrite(self, buffer, value):
        itemsize, typecode, ftype, type_id = _np_dtypes_dict[value.dtype]
        view = memoryview(value)
        nbytes = len(value) * itemsize
        buffer.write_varuint32(type_id)
        buffer.write_varuint32(nbytes)
        if value.dtype == np.dtype("bool") or not view.c_contiguous:
            buffer.write_bytes(value.tobytes())
        else:
            buffer.write_buffer(value)

    def xread(self, buffer):
        raise NotImplementedError("Multi-dimensional array not supported currently")

    def write(self, buffer, value):
        buffer.write_int8(PickleSerializer.PICKLE_TYPE_ID)
        self.fory.handle_unsupported_write(buffer, value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)


class BytesSerializer(CrossLanguageCompatibleSerializer):
    def write(self, buffer, value):
        self.fory.write_buffer_object(buffer, BytesBufferObject(value))

    def read(self, buffer):
        fory_buf = self.fory.read_buffer_object(buffer)
        return fory_buf.to_pybytes()


class BytesBufferObject(BufferObject):
    __slots__ = ("binary",)

    def __init__(self, binary: bytes):
        self.binary = binary

    def total_bytes(self) -> int:
        return len(self.binary)

    def write_to(self, buffer: "Buffer"):
        buffer.write_bytes(self.binary)

    def to_buffer(self) -> "Buffer":
        return Buffer(self.binary)


class StatefulSerializer(CrossLanguageCompatibleSerializer):
    """
    Serializer for objects that support __getstate__ and __setstate__.
    Uses Fory's native serialization for better cross-language support.
    """

    def __init__(self, fory, cls):
        super().__init__(fory, cls)
        self.cls = cls

    def write(self, buffer, value):
        # Get the state using __getstate__
        state = value.__getstate__()

        # Check for constructor arguments
        args = ()
        kwargs = {}

        if hasattr(value, "__getnewargs_ex__"):
            args, kwargs = value.__getnewargs_ex__()
        elif hasattr(value, "__getnewargs__"):
            args = value.__getnewargs__()

        # Serialize constructor arguments first
        self.fory.serialize_ref(buffer, args)
        self.fory.serialize_ref(buffer, kwargs)

        # Then serialize the state
        self.fory.serialize_ref(buffer, state)

    def read(self, buffer):
        args = self.fory.deserialize_ref(buffer)
        kwargs = self.fory.deserialize_ref(buffer)
        state = self.fory.deserialize_ref(buffer)

        # If the class actually declared __getnewargs_ex__ or __getnewargs__,
        # we expect args/kwargs to be meaningful. Redundant but defensive.
        has_newargs = hasattr(self.cls, "__getnewargs_ex__") or hasattr(self.cls, "__getnewargs__")

        # Create an instance based on whether constructor arguments are available
        if has_newargs and (args or kwargs):
            # Object has __getnewargs_ex__ or __getnewargs__ - use constructor
            obj = self.cls(*args, **kwargs)
        else:
            # Object only has __getstate__/__setstate__ - create bare instance
            obj = self.cls.__new__(self.cls)

        # Restore state using __setstate__
        obj.__setstate__(state)

        return obj


class PickleSerializer(Serializer):
    PICKLE_TYPE_ID = 96

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError

    def write(self, buffer, value):
        self.fory.handle_unsupported_write(buffer, value)

    def read(self, buffer):
        return self.fory.handle_unsupported_read(buffer)


class ComplexObjectSerializer(DataClassSerializer):
    def __new__(cls, fory, clz):
        warnings.warn(
            "`ComplexObjectSerializer` is deprecated and will be removed in a future version. "
            "Use `DataClassSerializer(fory, clz, xlang=True)` instead.",
            DeprecationWarning,
            stacklevel=2,
        )
        return DataClassSerializer(fory, clz, xlang=True)
