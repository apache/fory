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
import itertools
import marshal
import logging
import os
import pickle
import types
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
        self._field_names = self._get_field_names(clz)
        self._has_slots = hasattr(clz, "__slots__")

        if self._xlang:
            self._serializers = [None] * len(self._field_names)
            visitor = ComplexTypeVisitor(fory)
            for index, key in enumerate(self._field_names):
                serializer = infer_field(key, self._type_hints[key], visitor, types_path=[])
                self._serializers[index] = serializer
            self._serializers, self._field_names = _sort_fields(fory.type_resolver, self._field_names, self._serializers)
            self._hash = 0  # Will be computed on first xwrite/xread
            self._generated_xwrite_method = self._gen_xwrite_method()
            self._generated_xread_method = self._gen_xread_method()
            if _ENABLE_FORY_PYTHON_JIT:
                # don't use `__slots__`, which will make the instance method read-only
                self.xwrite = self._generated_xwrite_method
                self.xread = self._generated_xread_method
            if self.fory.language == Language.PYTHON:
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

    def _get_field_names(self, clz):
        if hasattr(clz, "__dict__"):
            # Regular object with __dict__
            # We can't know the fields without an instance, so we rely on type hints
            return sorted(self._type_hints.keys())
        elif hasattr(clz, "__slots__"):
            # Object with __slots__
            return sorted(clz.__slots__)
        return []

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

    def _gen_xwrite_method(self):
        context = {}
        counter = itertools.count(0)
        buffer, fory, value, value_dict = "buffer", "fory", "value", "value_dict"
        get_hash_func = "_get_hash"
        context[fory] = self.fory
        context[get_hash_func] = _get_hash
        context["_field_names"] = self._field_names
        context["_type_hints"] = self._type_hints
        context["_serializers"] = self._serializers
        # Compute hash at generation time since we're in xlang mode
        if self._hash == 0:
            self._hash = _get_hash(self.fory, self._field_names, self._type_hints)
        stmts = [
            f'"""xwrite method for {self.type_}"""',
            f"{buffer}.write_int32({self._hash})",
        ]
        if not self._has_slots:
            stmts.append(f"{value_dict} = {value}.__dict__")
        for index, field_name in enumerate(self._field_names):
            field_value = f"field_value{next(counter)}"
            serializer_var = f"serializer{index}"
            context[serializer_var] = self._serializers[index]
            if not self._has_slots:
                stmts.append(f"{field_value} = {value_dict}['{field_name}']")
            else:
                stmts.append(f"{field_value} = {value}.{field_name}")
            stmts.append(f"{fory}.xserialize_ref({buffer}, {field_value}, serializer={serializer_var})")
        self._xwrite_method_code, func = compile_function(
            f"xwrite_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
            [buffer, value],
            stmts,
            context,
        )
        return func

    def _gen_xread_method(self):
        context = dict(_jit_context)
        buffer, fory, obj_class, obj, obj_dict = (
            "buffer",
            "fory",
            "obj_class",
            "obj",
            "obj_dict",
        )
        ref_resolver = "ref_resolver"
        get_hash_func = "_get_hash"
        context[fory] = self.fory
        context[obj_class] = self.type_
        context[ref_resolver] = self.fory.ref_resolver
        context[get_hash_func] = _get_hash
        context["_field_names"] = self._field_names
        context["_type_hints"] = self._type_hints
        context["_serializers"] = self._serializers
        # Compute hash at generation time since we're in xlang mode
        if self._hash == 0:
            self._hash = _get_hash(self.fory, self._field_names, self._type_hints)
        stmts = [
            f'"""xread method for {self.type_}"""',
            f"read_hash = {buffer}.read_int32()",
            f"if read_hash != {self._hash}:",
            f"""   raise TypeNotCompatibleError(
            f"Hash {{read_hash}} is not consistent with {self._hash} for type {self.type_}")""",
            f"{obj} = {obj_class}.__new__({obj_class})",
            f"{ref_resolver}.reference({obj})",
        ]
        if not self._has_slots:
            stmts.append(f"{obj_dict} = {obj}.__dict__")

        for index, field_name in enumerate(self._field_names):
            serializer_var = f"serializer{index}"
            context[serializer_var] = self._serializers[index]
            field_value = f"field_value{index}"
            stmts.append(f"{field_value} = {fory}.xdeserialize_ref({buffer}, serializer={serializer_var})")
            if not self._has_slots:
                stmts.append(f"{obj_dict}['{field_name}'] = {field_value}")
            else:
                stmts.append(f"{obj}.{field_name} = {field_value}")
        stmts.append(f"return {obj}")
        self._xread_method_code, func = compile_function(
            f"xread_{self.type_.__module__}_{self.type_.__qualname__}".replace(".", "_"),
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
        # Use the xwrite method for Python arrays
        self.xwrite(buffer, value)

    def read(self, buffer):
        # Use the xread method for Python arrays
        return self.xread(buffer)


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
        # Use the xwrite method for NumPy arrays
        self.xwrite(buffer, value)

    def read(self, buffer):
        # Use the xread method for NumPy arrays
        return self.xread(buffer)


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
        # Use the xwrite method for NumPy arrays
        self.xwrite(buffer, value)

    def read(self, buffer):
        # Use the xread method for NumPy arrays
        return self.xread(buffer)


class BytesSerializer(CrossLanguageCompatibleSerializer):
    def xwrite(self, buffer, value):
        buffer.write_bytes_and_size(value)

    def xread(self, buffer):
        return buffer.read_bytes_and_size()

    def write(self, buffer, value):
        # Use the xwrite method for bytes
        self.xwrite(buffer, value)

    def read(self, buffer):
        # Use the xread method for bytes
        return self.xread(buffer)


class StatefulSerializer(CrossLanguageCompatibleSerializer):
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
        self.fory.serialize_ref(buffer, args)
        self.fory.serialize_ref(buffer, kwargs)

        # Then serialize the state
        self.fory.serialize_ref(buffer, state)

    def read(self, buffer):
        args = self.fory.deserialize_ref(buffer)
        kwargs = self.fory.deserialize_ref(buffer)
        state = self.fory.deserialize_ref(buffer)

        if args or kwargs:
            # Case 1: __getnewargs__ was used. Re-create by calling __init__.
            obj = self.cls(*args, **kwargs)
        else:
            # Case 2: Only __getstate__ was used. Create without calling __init__.
            obj = self.cls.__new__(self.cls)

        if state:
            obj.__setstate__(state)
        return obj


class ReduceSerializer(CrossLanguageCompatibleSerializer):
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
        # Try __reduce_ex__ first (with protocol 2), then __reduce__
        # Check if the object has a custom __reduce_ex__ method (not just the default from object)
        if hasattr(value, "__reduce_ex__") and value.__class__.__reduce_ex__ is not object.__reduce_ex__:
            try:
                reduce_result = value.__reduce_ex__(2)
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
            self.fory.serialize_ref(buffer, ("global", reduce_result, None, None, None))
        elif isinstance(reduce_result, tuple):
            if len(reduce_result) == 2:
                # Case 2: (callable, args)
                callable_obj, args = reduce_result
                self.fory.serialize_ref(buffer, ("callable", callable_obj, args, None, None))
            elif len(reduce_result) == 3:
                # Case 3: (callable, args, state)
                callable_obj, args, state = reduce_result
                self.fory.serialize_ref(buffer, ("callable", callable_obj, args, state, None))
            elif len(reduce_result) == 4:
                # Case 4: (callable, args, state, listitems)
                callable_obj, args, state, listitems = reduce_result
                self.fory.serialize_ref(buffer, ("callable", callable_obj, args, state, listitems))
            elif len(reduce_result) == 5:
                # Case 5: (callable, args, state, listitems, dictitems)
                callable_obj, args, state, listitems, dictitems = reduce_result
                self.fory.serialize_ref(buffer, ("callable", callable_obj, args, state, listitems, dictitems))
            else:
                raise ValueError(f"Invalid __reduce__ result length: {len(reduce_result)}")
        else:
            raise ValueError(f"Invalid __reduce__ result type: {type(reduce_result)}")

    def read(self, buffer):
        reduce_data = self.fory.deserialize_ref(buffer)

        if reduce_data[0] == "global":
            # Case 1: Global name
            global_name = reduce_data[1]
            # Import and return the global object
            module_name, obj_name = global_name.rsplit(".", 1)
            module = __import__(module_name, fromlist=[obj_name])
            return getattr(module, obj_name)
        elif reduce_data[0] == "callable":
            # Case 2-5: Callable with args and optional state/items
            callable_obj = reduce_data[1]
            args = reduce_data[2] or ()
            state = reduce_data[3]
            listitems = reduce_data[4]
            dictitems = reduce_data[5] if len(reduce_data) > 5 else None

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

            return obj
        else:
            raise ValueError(f"Invalid reduce data format: {reduce_data[0]}")


class FunctionSerializer(CrossLanguageCompatibleSerializer):
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
    serialize_ref/deserialize_ref pipeline to ensure proper type registration
    and reference tracking.
    """

    # Cache for function attributes that are handled separately
    _FUNCTION_ATTRS = frozenset(("__code__", "__name__", "__defaults__", "__closure__", "__globals__", "__module__", "__qualname__"))

    def _serialize_function(self, buffer, func):
        """Serialize a function by capturing all its components."""
        # Get function metadata
        is_method = hasattr(func, "__self__")
        if is_method:
            # Handle bound methods
            self_obj = func.__self__
            func_name = func.__name__
            # Serialize as a tuple (is_method, self_obj, method_name)
            buffer.write_bool(True)  # is a method
            # For the 'self' object, we need to use fory's serialization
            self.fory.serialize_ref(buffer, self_obj)
            buffer.write_string(func_name)
            return

        # Regular function or lambda
        code = func.__code__
        name = func.__name__
        defaults = func.__defaults__
        closure = func.__closure__
        globals_dict = func.__globals__
        module = func.__module__
        qualname = func.__qualname__

        # Serialize function metadata
        buffer.write_bool(False)  # Not a method
        buffer.write_string(name)
        buffer.write_string(module)
        buffer.write_string(qualname)

        # Instead of trying to serialize the code object in parts, use marshal
        # which is specifically designed for code objects
        marshalled_code = marshal.dumps(code)
        buffer.write_bytes_and_size(marshalled_code)

        # Serialize defaults (or None if no defaults)
        # Write whether defaults exist
        buffer.write_bool(defaults is not None)
        if defaults is not None:
            # Write the number of default arguments
            buffer.write_varuint32(len(defaults))
            # Serialize each default value individually
            for default_value in defaults:
                self.fory.serialize_ref(buffer, default_value)

        # Handle closure
        # We need to serialize both the closure values and the fact that there is a closure
        # The code object's co_freevars tells us what variables are in the closure
        buffer.write_bool(closure is not None)
        buffer.write_varuint32(len(code.co_freevars) if code.co_freevars else 0)

        if closure:
            # Extract and serialize each closure cell's contents
            for cell in closure:
                self.fory.serialize_ref(buffer, cell.cell_contents)

        # Serialize free variable names as a list of strings
        # Convert tuple to list since tuple might not be registered
        freevars_list = list(code.co_freevars) if code.co_freevars else []
        buffer.write_varuint32(len(freevars_list))
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
        self.fory.serialize_ref(buffer, globals_to_serialize)

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

        self.fory.serialize_ref(buffer, attrs)

    def _deserialize_function(self, buffer):
        """Deserialize a function from its components."""
        import sys

        # Check if it's a method
        is_method = buffer.read_bool()
        if is_method:
            # Handle bound methods
            self_obj = self.fory.deserialize_ref(buffer)
            method_name = buffer.read_string()
            return getattr(self_obj, method_name)

        # Regular function or lambda
        name = buffer.read_string()
        module = buffer.read_string()
        qualname = buffer.read_string()

        # Use marshal to load the code object, which handles all Python versions correctly
        marshalled_code = buffer.read_bytes_and_size()
        code = marshal.loads(marshalled_code)

        # Deserialize defaults
        has_defaults = buffer.read_bool()
        defaults = None
        if has_defaults:
            # Read the number of default arguments
            num_defaults = buffer.read_varuint32()
            # Deserialize each default value
            default_values = []
            for _ in range(num_defaults):
                default_values.append(self.fory.deserialize_ref(buffer))
            defaults = tuple(default_values)

        # Handle closure
        has_closure = buffer.read_bool()
        num_freevars = buffer.read_varuint32()
        closure = None

        # Read closure values if there are any
        closure_values = []
        if has_closure:
            for _ in range(num_freevars):
                closure_values.append(self.fory.deserialize_ref(buffer))

            # Create closure cells
            closure = tuple(types.CellType(value) for value in closure_values)

        # Read free variable names from strings
        num_freevars = buffer.read_varuint32()
        freevars = []
        for _ in range(num_freevars):
            freevars.append(buffer.read_string())

        # Handle globals
        globals_dict = self.fory.deserialize_ref(buffer)

        # Create a globals dictionary with module's globals as the base
        func_globals = {}
        try:
            mod = sys.modules.get(module)
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
        attrs = self.fory.deserialize_ref(buffer)
        for attr_name, attr_value in attrs.items():
            setattr(func, attr_name, attr_value)

        return func

    def xwrite(self, buffer, value):
        """Serialize a function for cross-language compatibility."""
        self._serialize_function(buffer, value)

    def xread(self, buffer):
        """Deserialize a function for cross-language compatibility."""
        return self._deserialize_function(buffer)

    def write(self, buffer, value):
        """Serialize a function for Python-only mode."""
        self._serialize_function(buffer, value)

    def read(self, buffer):
        """Deserialize a function for Python-only mode."""
        return self._deserialize_function(buffer)


class PickleSerializer(Serializer):
    PICKLE_TYPE_ID = 96

    def xwrite(self, buffer, value):
        raise NotImplementedError

    def xread(self, buffer):
        raise NotImplementedError

    def write(self, buffer, value):
        # Use standard pickle module instead of cloudpickle
        serialized = pickle.dumps(value)
        buffer.write_bytes_and_size(serialized)

    def read(self, buffer):
        # Use standard pickle module instead of cloudpickle
        serialized = buffer.read_bytes_and_size()
        return pickle.loads(serialized)


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

        buffer.write_varuint32(len(sorted_field_names))
        for field_name in sorted_field_names:
            buffer.write_string(field_name)
            field_value = getattr(value, field_name)
            self.fory.serialize_ref(buffer, field_value)

    def read(self, buffer):
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        num_fields = buffer.read_varuint32()
        for _ in range(num_fields):
            field_name = buffer.read_string()
            field_value = self.fory.deserialize_ref(buffer)
            setattr(obj, field_name, field_value)
        return obj

    def xwrite(self, buffer, value):
        # for cross-language or minimal framing, reuse the same logic
        return self.write(buffer, value)

    def xread(self, buffer):
        # symmetric to xwrite
        return self.read(buffer)


class ComplexObjectSerializer(DataClassSerializer):
    def __new__(cls, fory, clz):
        warnings.warn(
            "`ComplexObjectSerializer` is deprecated and will be removed in a future version. "
            "Use `DataClassSerializer(fory, clz, xlang=True)` instead.",
            DeprecationWarning,
            stacklevel=2,
        )
        return DataClassSerializer(fory, clz, xlang=True)
