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

# distutils: language = c++
# cython: embedsignature = True
# cython: language_level = 3
# cython: annotate = True
import datetime
import logging
import os
import platform
import time
import warnings
from typing import TypeVar, Union, Iterable

from pyfory._util import get_bit, set_bit, clear_bit
from pyfory import _fory as fmod
from pyfory._fory import Language
from pyfory._fory import _ENABLE_TYPE_REGISTRATION_FORCIBLY
from pyfory.lib import mmh3
from pyfory.meta.metastring import Encoding
from pyfory.type import is_primitive_type
from pyfory.util import is_little_endian
from pyfory.includes.libserialization cimport \
    (TypeId, IsNamespacedType, Fory_PyBooleanSequenceWriteToBuffer, Fory_PyFloatSequenceWriteToBuffer)

from libc.stdint cimport int8_t, int16_t, int32_t, int64_t, uint64_t
from libc.stdint cimport *
from libcpp.vector cimport vector
from cpython cimport PyObject
from cpython.dict cimport PyDict_Next
from cpython.ref cimport *
from cpython.list cimport PyList_New, PyList_SET_ITEM
from cpython.tuple cimport PyTuple_New, PyTuple_SET_ITEM
from libcpp cimport bool as c_bool
from libcpp.utility cimport pair
from cython.operator cimport dereference as deref
from pyfory._util cimport Buffer
from pyfory.includes.libabsl cimport flat_hash_map
from pyfory.meta.metastring import MetaStringDecoder

try:
    import numpy as np
except ImportError:
    np = None

cimport cython

logger = logging.getLogger(__name__)
ENABLE_FORY_CYTHON_SERIALIZATION = os.environ.get(
    "ENABLE_FORY_CYTHON_SERIALIZATION", "True").lower() in ("true", "1")

cdef extern from *:
    """
    #define int2obj(obj_addr) ((PyObject *)(obj_addr))
    #define obj2int(obj_ref) (Py_INCREF(obj_ref), ((int64_t)(obj_ref)))
    """
    object int2obj(int64_t obj_addr)
    int64_t obj2int(object obj_ref)
    dict _PyDict_NewPresized(Py_ssize_t minused)
    Py_ssize_t Py_SIZE(object obj)


cdef int8_t NULL_FLAG = -3
# This flag indicates that object is a not-null value.
# We don't use another byte to indicate REF, so that we can save one byte.
cdef int8_t REF_FLAG = -2
# this flag indicates that the object is a non-null value.
cdef int8_t NOT_NULL_VALUE_FLAG = -1
# this flag indicates that the object is a referencable and first read.
cdef int8_t REF_VALUE_FLAG = 0
# Global MetaString decoder for namespace bytes to str
namespace_decoder = MetaStringDecoder(".", "_")
# Global MetaString decoder for typename bytes to str
typename_decoder = MetaStringDecoder("$", "_")


@cython.final
cdef class MapRefResolver:
    cdef flat_hash_map[uint64_t, int32_t] written_objects_id  # id(obj) -> ref_id
    # Hold object to avoid tmp object gc when serialize nested fields/objects.
    cdef vector[PyObject *] written_objects
    cdef vector[PyObject *] read_objects
    cdef vector[int32_t] read_ref_ids
    cdef object read_object
    cdef c_bool ref_tracking

    def __cinit__(self, c_bool ref_tracking):
        self.read_object = None
        self.ref_tracking = ref_tracking

    # Special methods of extension types must be declared with def, not cdef.
    def __dealloc__(self):
        self.reset()

    cpdef inline c_bool write_ref_or_null(self, Buffer buffer, obj):
        if not self.ref_tracking:
            if obj is None:
                buffer.write_int8(NULL_FLAG)
                return True
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                return False
        if obj is None:
            buffer.write_int8(NULL_FLAG)
            return True
        cdef uint64_t object_id = <uintptr_t> <PyObject *> obj
        cdef int32_t next_id
        cdef flat_hash_map[uint64_t, int32_t].iterator it = \
            self.written_objects_id.find(object_id)
        if it == self.written_objects_id.end():
            next_id = self.written_objects_id.size()
            self.written_objects_id[object_id] = next_id
            self.written_objects.push_back(<PyObject *> obj)
            Py_INCREF(obj)
            buffer.write_int8(REF_VALUE_FLAG)
            return False
        else:
            # The obj has been written previously.
            buffer.write_int8(REF_FLAG)
            buffer.write_varuint32(<uint64_t> deref(it).second)
            return True

    cpdef inline int8_t read_ref_or_null(self, Buffer buffer):
        cdef int8_t head_flag = buffer.read_int8()
        if not self.ref_tracking:
            return head_flag
        cdef int32_t ref_id
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_varuint32()
            self.read_object = <object> (self.read_objects[ref_id])
            return REF_FLAG
        else:
            self.read_object = None
            return head_flag

    cpdef inline int32_t preserve_ref_id(self):
        if not self.ref_tracking:
            return -1
        next_read_ref_id = self.read_objects.size()
        self.read_objects.push_back(NULL)
        self.read_ref_ids.push_back(next_read_ref_id)
        return next_read_ref_id

    cpdef inline int32_t try_preserve_ref_id(self, Buffer buffer):
        if not self.ref_tracking:
            # `NOT_NULL_VALUE_FLAG` can be used as stub reference id because we use
            # `refId >= NOT_NULL_VALUE_FLAG` to read data.
            return buffer.read_int8()
        head_flag = buffer.read_int8()
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_varuint32()
            self.read_object = <object> (self.read_objects[ref_id])
            # `head_flag` except `REF_FLAG` can be used as stub reference id because
            # we use `refId >= NOT_NULL_VALUE_FLAG` to read data.
            return head_flag
        else:
            self.read_object = None
            if head_flag == REF_VALUE_FLAG:
                return self.preserve_ref_id()
            return head_flag

    cpdef inline reference(self, obj):
        if not self.ref_tracking:
            return
        cdef int32_t ref_id = self.read_ref_ids.back()
        self.read_ref_ids.pop_back()
        cdef c_bool need_inc = self.read_objects[ref_id] == NULL
        if need_inc:
            Py_INCREF(obj)
        self.read_objects[ref_id] = <PyObject *> obj

    cpdef inline get_read_object(self, id_=None):
        if not self.ref_tracking:
            return None
        if id_ is None:
            return self.read_object
        cdef int32_t ref_id = id_
        return <object> (self.read_objects[ref_id])

    cpdef inline set_read_object(self, int32_t ref_id, obj):
        if not self.ref_tracking:
            return
        if ref_id >= 0:
            need_inc = self.read_objects[ref_id] == NULL
            if need_inc:
                Py_INCREF(obj)
            self.read_objects[ref_id] = <PyObject *> obj

    cpdef inline reset(self):
        self.reset_write()
        self.reset_read()

    cpdef inline reset_write(self):
        self.written_objects_id.clear()
        for item in self.written_objects:
            Py_XDECREF(item)
        self.written_objects.clear()

    cpdef inline reset_read(self):
        if not self.ref_tracking:
            return
        for item in self.read_objects:
            Py_XDECREF(item)
        self.read_objects.clear()
        self.read_ref_ids.clear()
        self.read_object = None


cdef int8_t USE_TYPE_NAME = 0
cdef int8_t USE_TYPE_ID = 1
# preserve 0 as flag for type id not set in TypeInfo`
cdef int8_t NO_TYPE_ID = 0
cdef int8_t DEFAULT_DYNAMIC_WRITE_META_STR_ID = fmod.DEFAULT_DYNAMIC_WRITE_META_STR_ID
cdef int8_t INT64_TYPE_ID = fmod.INT64_TYPE_ID
cdef int8_t FLOAT64_TYPE_ID = fmod.FLOAT64_TYPE_ID
cdef int8_t BOOL_TYPE_ID = fmod.BOOL_TYPE_ID
cdef int8_t STRING_TYPE_ID = fmod.STRING_TYPE_ID

cdef int16_t MAGIC_NUMBER = fmod.MAGIC_NUMBER
cdef int32_t NOT_NULL_INT64_FLAG = fmod.NOT_NULL_INT64_FLAG
cdef int32_t NOT_NULL_FLOAT64_FLAG = fmod.NOT_NULL_FLOAT64_FLAG
cdef int32_t NOT_NULL_BOOL_FLAG = fmod.NOT_NULL_BOOL_FLAG
cdef int32_t NOT_NULL_STRING_FLAG = fmod.NOT_NULL_STRING_FLAG
cdef int32_t SMALL_STRING_THRESHOLD = fmod.SMALL_STRING_THRESHOLD


@cython.final
cdef class MetaStringBytes:
    cdef public bytes data
    cdef int16_t length
    cdef public int8_t encoding
    cdef public int64_t hashcode
    cdef public int16_t dynamic_write_string_id

    def __init__(self, data, hashcode):
        self.data = data
        self.length = len(data)
        self.hashcode = hashcode
        self.encoding = hashcode & 0xff
        self.dynamic_write_string_id = DEFAULT_DYNAMIC_WRITE_META_STR_ID

    def __eq__(self, other):
        return type(other) is MetaStringBytes and other.hashcode == self.hashcode

    def __hash__(self):
        return self.hashcode

    def decode(self, decoder):
        return decoder.decode(self.data, Encoding(self.encoding))

    def __repr__(self):
        return f"MetaStringBytes(data={self.data}, hashcode={self.hashcode})"


@cython.final
cdef class MetaStringResolver:
    cdef:
        int16_t dynamic_write_string_id
        vector[PyObject *] _c_dynamic_written_enum_string
        vector[PyObject *] _c_dynamic_id_to_enum_string_vec
        # hash -> MetaStringBytes
        flat_hash_map[int64_t, PyObject *] _c_hash_to_metastr_bytes
        flat_hash_map[pair[int64_t, int64_t], PyObject *] _c_hash_to_small_metastring_bytes
        set _enum_str_set
        dict _metastr_to_metastr_bytes

    def __init__(self):
        self._enum_str_set = set()
        self._metastr_to_metastr_bytes = dict()

    cpdef inline write_meta_string_bytes(
            self, Buffer buffer, MetaStringBytes metastr_bytes):
        cdef int16_t dynamic_type_id = metastr_bytes.dynamic_write_string_id
        cdef int32_t length = metastr_bytes.length
        if dynamic_type_id == DEFAULT_DYNAMIC_WRITE_META_STR_ID:
            dynamic_type_id = self.dynamic_write_string_id
            metastr_bytes.dynamic_write_string_id = dynamic_type_id
            self.dynamic_write_string_id += 1
            self._c_dynamic_written_enum_string.push_back(<PyObject *> metastr_bytes)
            buffer.write_varuint32(length << 1)
            if length <= SMALL_STRING_THRESHOLD:
                buffer.write_int8(metastr_bytes.encoding)
            else:
                buffer.write_int64(metastr_bytes.hashcode)
            buffer.write_bytes(metastr_bytes.data)
        else:
            buffer.write_varuint32(((dynamic_type_id + 1) << 1) | 1)

    cpdef inline MetaStringBytes read_meta_string_bytes(self, Buffer buffer):
        cdef int32_t header = buffer.read_varuint32()
        cdef int32_t length = header >> 1
        if header & 0b1 != 0:
            return <MetaStringBytes> self._c_dynamic_id_to_enum_string_vec[length - 1]
        cdef int64_t v1 = 0, v2 = 0, hashcode
        cdef PyObject * enum_str_ptr
        cdef int32_t reader_index
        cdef encoding = 0
        if length <= SMALL_STRING_THRESHOLD:
            encoding = buffer.read_int8()
            if length <= 8:
                v1 = buffer.read_bytes_as_int64(length)
            else:
                v1 = buffer.read_int64()
                v2 = buffer.read_bytes_as_int64(length - 8)
            hashcode = ((v1 * 31 + v2) >> 8 << 8) | encoding
            enum_str_ptr = self._c_hash_to_small_metastring_bytes[pair[int64_t, int64_t](v1, v2)]
            if enum_str_ptr == NULL:
                reader_index = buffer.reader_index
                str_bytes = buffer.get_bytes(reader_index - length, length)
                enum_str = MetaStringBytes(str_bytes, hashcode=hashcode)
                self._enum_str_set.add(enum_str)
                enum_str_ptr = <PyObject *> enum_str
                self._c_hash_to_small_metastring_bytes[pair[int64_t, int64_t](v1, v2)] = enum_str_ptr
        else:
            hashcode = buffer.read_int64()
            reader_index = buffer.reader_index
            buffer.check_bound(reader_index, length)
            buffer.reader_index = reader_index + length
            enum_str_ptr = self._c_hash_to_metastr_bytes[hashcode]
            if enum_str_ptr == NULL:
                str_bytes = buffer.get_bytes(reader_index, length)
                enum_str = MetaStringBytes(str_bytes, hashcode=hashcode)
                self._enum_str_set.add(enum_str)
                enum_str_ptr = <PyObject *> enum_str
                self._c_hash_to_metastr_bytes[hashcode] = enum_str_ptr
        self._c_dynamic_id_to_enum_string_vec.push_back(enum_str_ptr)
        return <MetaStringBytes> enum_str_ptr

    def get_metastr_bytes(self, metastr):
        metastr_bytes = self._metastr_to_metastr_bytes.get(metastr)
        if metastr_bytes is not None:
            return metastr_bytes
        cdef int64_t v1 = 0, v2 = 0, hashcode
        length = len(metastr.encoded_data)
        if length <= SMALL_STRING_THRESHOLD:
            data_buf = Buffer(metastr.encoded_data)
            if length <= 8:
                v1 = data_buf.read_bytes_as_int64(length)
            else:
                v1 = data_buf.read_int64()
                v2 = data_buf.read_bytes_as_int64(length - 8)
            value_hash = ((v1 * 31 + v2) >> 8 << 8) | metastr.encoding.value
        else:
            value_hash = mmh3.hash_buffer(metastr.encoded_data, seed=47)[0]
            value_hash = value_hash >> 8 << 8
            value_hash |= metastr.encoding.value & 0xFF
        self._metastr_to_metastr_bytes[metastr] = metastr_bytes = MetaStringBytes(metastr.encoded_data, value_hash)
        return metastr_bytes

    cpdef inline reset_read(self):
        self._c_dynamic_id_to_enum_string_vec.clear()

    cpdef inline reset_write(self):
        if self.dynamic_write_string_id != 0:
            self.dynamic_write_string_id = 0
            for ptr in self._c_dynamic_written_enum_string:
                (<MetaStringBytes> ptr).dynamic_write_string_id = \
                    DEFAULT_DYNAMIC_WRITE_META_STR_ID
            self._c_dynamic_written_enum_string.clear()


@cython.final
cdef class TypeInfo:
    """
    If dynamic_type is true, the serializer will be a dynamic typed serializer
    and it will write type info when writing the data.
    In such cases, the `write_typeinfo` should not write typeinfo.
    In general, if we have 4 type for one class, we will have 5 serializers.
    For example, we have int8/16/32/64/128 for python `int` type, then we have 6 serializers
    for python `int`: `Int8/1632/64/128Serializer` for `int8/16/32/64/128` each, and another
    `IntSerializer` for `int` which will dispatch to different `int8/16/32/64/128` type
    according the actual value.
    We do not get the acutal type here, because it will introduce extra computing.
    For example, we have want to get actual `Int8/16/32/64Serializer`, we must check and
    extract the actutal here which will introduce cost, and we will do same thing again
    when serializing the actual data.
    """
    cdef public object cls
    cdef public int16_t type_id
    cdef public Serializer serializer
    cdef public MetaStringBytes namespace_bytes
    cdef public MetaStringBytes typename_bytes
    cdef public c_bool dynamic_type

    def __init__(
            self,
            cls: Union[type, TypeVar] = None,
            type_id: int = NO_TYPE_ID,
            serializer: Serializer = None,
            namespace_bytes: MetaStringBytes = None,
            typename_bytes: MetaStringBytes = None,
            dynamic_type: bool = False,
    ):
        self.cls = cls
        self.type_id = type_id
        self.serializer = serializer
        self.namespace_bytes = namespace_bytes
        self.typename_bytes = typename_bytes
        self.dynamic_type = dynamic_type

    def __repr__(self):
        return f"TypeInfo(cls={self.cls}, type_id={self.type_id}, " \
               f"serializer={self.serializer})"

    cpdef str decode_namespace(self):
        if self.namespace_bytes is None:
            return ""
        return self.namespace_bytes.decode(namespace_decoder)

    cpdef str decode_typename(self):
        if self.typename_bytes is None:
            return ""
        return self.typename_bytes.decode(typename_decoder)


@cython.final
cdef class TypeResolver:
    cdef:
        readonly Fory fory
        readonly MetaStringResolver metastring_resolver
        object _resolver
        vector[PyObject *] _c_registered_id_to_type_info
        # cls -> TypeInfo
        flat_hash_map[uint64_t, PyObject *] _c_types_info
        # hash -> TypeInfo
        flat_hash_map[pair[int64_t, int64_t], PyObject *] _c_meta_hash_to_typeinfo
        MetaStringResolver meta_string_resolver

    def __init__(self, fory):
        self.fory = fory
        self.metastring_resolver = fory.metastring_resolver
        from pyfory._registry import TypeResolver
        self._resolver = TypeResolver(fory)

    def initialize(self):
        self._resolver.initialize()
        for typeinfo in self._resolver._types_info.values():
            self._populate_typeinfo(typeinfo)

    def register_type(
            self,
            cls: Union[type, TypeVar],
            *,
            type_id: int = None,
            namespace: str = None,
            typename: str = None,
            serializer=None,
    ):
        typeinfo = self._resolver.register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )
        self._populate_typeinfo(typeinfo)

    cdef _populate_typeinfo(self, typeinfo):
        type_id = typeinfo.type_id
        if type_id >= self._c_registered_id_to_type_info.size():
            self._c_registered_id_to_type_info.resize(type_id * 2, NULL)
        if type_id > 0 and (self.fory.language == Language.PYTHON or not IsNamespacedType(type_id)):
            self._c_registered_id_to_type_info[type_id] = <PyObject *> typeinfo
        self._c_types_info[<uintptr_t> <PyObject *> typeinfo.cls] = <PyObject *> typeinfo
        if typeinfo.typename_bytes is not None:
            self._load_bytes_to_typeinfo(type_id, typeinfo.namespace_bytes, typeinfo.typename_bytes)

    def register_serializer(self, cls: Union[type, TypeVar], serializer):
        typeinfo1 = self._resolver.get_typeinfo(cls)
        self._resolver.register_serializer(cls, serializer)
        typeinfo2 = self._resolver.get_typeinfo(cls)
        if typeinfo1.type_id != typeinfo2.type_id:
            self._c_registered_id_to_type_info[typeinfo1.type_id] = NULL
            self._populate_typeinfo(typeinfo2)

    cpdef inline Serializer get_serializer(self, cls):
        """
        Returns
        -------
            Returns or create serializer for the provided type
        """
        return self.get_typeinfo(cls).serializer

    cpdef inline TypeInfo get_typeinfo(self, cls, create=True):
        cdef PyObject * typeinfo_ptr = self._c_types_info[<uintptr_t> <PyObject *> cls]
        cdef TypeInfo type_info
        if typeinfo_ptr != NULL:
            type_info = <object> typeinfo_ptr
            if type_info.serializer is not None:
                return type_info
            else:
                type_info.serializer = self._resolver._create_serializer(cls)
                return type_info
        elif not create:
            return None
        else:
            type_info = self._resolver.get_typeinfo(cls, create=create)
            self._c_types_info[<uintptr_t> <PyObject *> cls] = <PyObject *> type_info
            self._populate_typeinfo(type_info)
            return type_info

    cdef inline TypeInfo _load_bytes_to_typeinfo(
            self, int32_t type_id, MetaStringBytes ns_metabytes, MetaStringBytes type_metabytes):
        cdef PyObject * typeinfo_ptr = self._c_meta_hash_to_typeinfo[
            pair[int64_t, int64_t](ns_metabytes.hashcode, type_metabytes.hashcode)]
        if typeinfo_ptr != NULL:
            return <TypeInfo> typeinfo_ptr
        typeinfo = self._resolver._load_metabytes_to_typeinfo(ns_metabytes, type_metabytes)
        typeinfo_ptr = <PyObject *> typeinfo
        self._c_meta_hash_to_typeinfo[pair[int64_t, int64_t](
            ns_metabytes.hashcode, type_metabytes.hashcode)] = typeinfo_ptr
        return typeinfo

    cpdef write_typeinfo(self, Buffer buffer, TypeInfo typeinfo):
        if typeinfo.dynamic_type:
            return
        cdef:
            int32_t type_id = typeinfo.type_id
            int32_t internal_type_id = type_id & 0xFF
        buffer.write_varuint32(type_id)
        if IsNamespacedType(internal_type_id):
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.namespace_bytes)
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.typename_bytes)

    cpdef inline TypeInfo read_typeinfo(self, Buffer buffer):
        cdef:
            int32_t type_id = buffer.read_varuint32()
        if type_id < 0:
            type_id = -type_id
        if type_id > self._c_registered_id_to_type_info.size():
            raise ValueError(f"Unexpected type_id {type_id}")
        cdef:
            int32_t internal_type_id = type_id & 0xFF
            MetaStringBytes namespace_bytes, typename_bytes
        if IsNamespacedType(internal_type_id):
            namespace_bytes = self.metastring_resolver.read_meta_string_bytes(buffer)
            typename_bytes = self.metastring_resolver.read_meta_string_bytes(buffer)
            return self._load_bytes_to_typeinfo(type_id, namespace_bytes, typename_bytes)
        typeinfo_ptr = self._c_registered_id_to_type_info[type_id]
        if typeinfo_ptr == NULL:
            raise ValueError(f"Unexpected type_id {type_id}")
        typeinfo = <TypeInfo> typeinfo_ptr
        return typeinfo

    cpdef inline reset(self):
        pass

    cpdef inline reset_read(self):
        pass

    cpdef inline reset_write(self):
        pass


@cython.final
cdef class Fory:
    cdef readonly object language
    cdef readonly c_bool ref_tracking
    cdef readonly c_bool require_type_registration
    cdef readonly c_bool is_py
    cdef readonly MapRefResolver ref_resolver
    cdef readonly TypeResolver type_resolver
    cdef readonly MetaStringResolver metastring_resolver
    cdef readonly SerializationContext serialization_context
    cdef Buffer buffer
    cdef object _buffer_callback
    cdef object _buffers  # iterator
    cdef object _unsupported_callback
    cdef object _unsupported_objects  # iterator
    cdef object _peer_language

    def __init__(
            self,
            language=Language.XLANG,
            ref_tracking: bool = False,
            require_type_registration: bool = True,
    ):
        """
       :param require_type_registration:
        Whether to require registering types for serialization, enabled by default.
         If disabled, unknown insecure types can be deserialized, which can be
         insecure and cause remote code execution attack if the types
         `__new__`/`__init__`/`__eq__`/`__hash__` method contain malicious code.
         Do not disable type registration if you can't ensure your environment are
         *indeed secure*. We are not responsible for security risks if
         you disable this option.
       """
        self.language = language
        if _ENABLE_TYPE_REGISTRATION_FORCIBLY or require_type_registration:
            self.require_type_registration = True
        else:
            self.require_type_registration = False
        self.ref_tracking = ref_tracking
        self.ref_resolver = MapRefResolver(ref_tracking)
        self.is_py = self.language == Language.PYTHON
        self.metastring_resolver = MetaStringResolver()
        self.type_resolver = TypeResolver(self)
        self.type_resolver.initialize()
        self.serialization_context = SerializationContext()
        self.buffer = Buffer.allocate(32)
        if not require_type_registration:
            warnings.warn(
                "Type registration is disabled, unknown types can be deserialized "
                "which may be insecure.",
                RuntimeWarning,
                stacklevel=2,
            )
        self._buffer_callback = None
        self._buffers = None
        self._unsupported_callback = None
        self._unsupported_objects = None
        self._peer_language = None

    def register_serializer(self, cls: Union[type, TypeVar], Serializer serializer):
        self.type_resolver.register_serializer(cls, serializer)

    def register_type(
            self,
            cls: Union[type, TypeVar],
            *,
            type_id: int = None,
            namespace: str = None,
            typename: str = None,
            serializer=None,
    ):
        self.type_resolver.register_type(
            cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer)

    def serialize(
            self, obj,
            Buffer buffer=None,
            buffer_callback=None,
            unsupported_callback=None
    ) -> Union[Buffer, bytes]:
        try:
            return self._serialize(
                obj,
                buffer,
                buffer_callback=buffer_callback,
                unsupported_callback=unsupported_callback)
        finally:
            self.reset_write()

    cpdef inline _serialize(
            self, obj, Buffer buffer, buffer_callback=None, unsupported_callback=None):
        self._buffer_callback = buffer_callback
        self._unsupported_callback = unsupported_callback
        if buffer is None:
            self.buffer.writer_index = 0
            buffer = self.buffer
        if self.language == Language.XLANG:
            buffer.write_int16(MAGIC_NUMBER)
        cdef int32_t mask_index = buffer.writer_index
        # 1byte used for bit mask
        buffer.grow(1)
        buffer.writer_index = mask_index + 1
        if obj is None:
            set_bit(buffer, mask_index, 0)
        else:
            clear_bit(buffer, mask_index, 0)
        # set endian
        if is_little_endian:
            set_bit(buffer, mask_index, 1)
        else:
            clear_bit(buffer, mask_index, 1)

        if self.language == Language.XLANG:
            # set reader as x_lang.
            set_bit(buffer, mask_index, 2)
            # set writer language.
            buffer.write_int8(Language.PYTHON.value)
        else:
            # set reader as native.
            clear_bit(buffer, mask_index, 2)
        if self._buffer_callback is not None:
            set_bit(buffer, mask_index, 3)
        else:
            clear_bit(buffer, mask_index, 3)
        cdef int32_t start_offset
        if self.language == Language.PYTHON:
            self.serialize_ref(buffer, obj)
        else:
            self.xserialize_ref(buffer, obj)
        if buffer is not self.buffer:
            return buffer
        else:
            return buffer.to_bytes(0, buffer.writer_index)

    cpdef inline serialize_ref(
            self, Buffer buffer, obj, TypeInfo typeinfo=None):
        cls = type(obj)
        if cls is str:
            buffer.write_int16(NOT_NULL_STRING_FLAG)
            buffer.write_string(obj)
            return
        elif cls is int:
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(obj)
            return
        elif cls is bool:
            buffer.write_int16(NOT_NULL_BOOL_FLAG)
            buffer.write_bool(obj)
            return
        elif cls is float:
            buffer.write_int16(NOT_NULL_FLOAT64_FLAG)
            buffer.write_double(obj)
            return
        if self.ref_resolver.write_ref_or_null(buffer, obj):
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_typeinfo(cls)
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.write(buffer, obj)

    cpdef inline serialize_nonref(self, Buffer buffer, obj):
        cls = type(obj)
        if cls is str:
            buffer.write_varuint32(STRING_TYPE_ID)
            buffer.write_string(obj)
            return
        elif cls is int:
            buffer.write_varuint32(INT64_TYPE_ID)
            buffer.write_varint64(obj)
            return
        elif cls is bool:
            buffer.write_varuint32(BOOL_TYPE_ID)
            buffer.write_bool(obj)
            return
        elif cls is float:
            buffer.write_varuint32(FLOAT64_TYPE_ID)
            buffer.write_double(obj)
            return
        cdef TypeInfo typeinfo = self.type_resolver.get_typeinfo(cls)
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.write(buffer, obj)

    cpdef inline xserialize_ref(
            self, Buffer buffer, obj, Serializer serializer=None):
        if serializer is None or serializer.need_to_write_ref:
            if not self.ref_resolver.write_ref_or_null(buffer, obj):
                self.xserialize_nonref(
                    buffer, obj, serializer=serializer
                )
        else:
            if obj is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.xserialize_nonref(
                    buffer, obj, serializer=serializer
                )

    cpdef inline xserialize_nonref(
            self, Buffer buffer, obj, Serializer serializer=None):
        if serializer is None:
            typeinfo = self.type_resolver.get_typeinfo(type(obj))
            self.type_resolver.write_typeinfo(buffer, typeinfo)
            serializer = typeinfo.serializer
        serializer.xwrite(buffer, obj)

    def deserialize(
            self,
            buffer: Union[Buffer, bytes],
            buffers: Iterable = None,
            unsupported_objects: Iterable = None,
    ):
        try:
            if type(buffer) == bytes:
                buffer = Buffer(buffer)
            return self._deserialize(buffer, buffers, unsupported_objects)
        finally:
            self.reset_read()

    cpdef inline _deserialize(
            self, Buffer buffer, buffers=None, unsupported_objects=None):
        if unsupported_objects is not None:
            self._unsupported_objects = iter(unsupported_objects)
        if self.language == Language.XLANG:
            magic_numer = buffer.read_int16()
            assert magic_numer == MAGIC_NUMBER, (
                f"The fory xlang serialization must start with magic number {hex(MAGIC_NUMBER)}. "
                "Please check whether the serialization is based on the xlang protocol and the "
                "data didn't corrupt."
            )
        cdef int32_t reader_index = buffer.reader_index
        buffer.reader_index = reader_index + 1
        if get_bit(buffer, reader_index, 0):
            return None
        cdef c_bool is_little_endian_ = get_bit(buffer, reader_index, 1)
        assert is_little_endian_, (
            "Big endian is not supported for now, "
            "please ensure peer machine is little endian."
        )
        cdef c_bool is_target_x_lang = get_bit(buffer, reader_index, 2)
        if is_target_x_lang:
            self._peer_language = Language(buffer.read_int8())
        else:
            self._peer_language = Language.PYTHON
        cdef c_bool is_out_of_band_serialization_enabled = \
            get_bit(buffer, reader_index, 3)
        if is_out_of_band_serialization_enabled:
            assert buffers is not None, (
                "buffers shouldn't be null when the serialized stream is "
                "produced with buffer_callback not null."
            )
            self._buffers = iter(buffers)
        else:
            assert buffers is None, (
                "buffers should be null when the serialized stream is "
                "produced with buffer_callback null."
            )
        if not is_target_x_lang:
            return self.deserialize_ref(buffer)
        return self.xdeserialize_ref(buffer)

    cpdef inline deserialize_ref(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef int32_t ref_id = ref_resolver.try_preserve_ref_id(buffer)
        if ref_id < NOT_NULL_VALUE_FLAG:
            return ref_resolver.get_read_object()
        # indicates that the object is first read.
        cdef TypeInfo typeinfo = self.type_resolver.read_typeinfo(buffer)
        cls = typeinfo.cls
        if cls is str:
            return buffer.read_string()
        elif cls is int:
            return buffer.read_varint64()
        elif cls is bool:
            return buffer.read_bool()
        elif cls is float:
            return buffer.read_double()
        o = typeinfo.serializer.read(buffer)
        ref_resolver.set_read_object(ref_id, o)
        return o

    cpdef inline deserialize_nonref(self, Buffer buffer):
        """Deserialize not-null and non-reference object from buffer."""
        cdef TypeInfo typeinfo = self.type_resolver.read_typeinfo(buffer)
        cls = typeinfo.cls
        if cls is str:
            return buffer.read_string()
        elif cls is int:
            return buffer.read_varint64()
        elif cls is bool:
            return buffer.read_bool()
        elif cls is float:
            return buffer.read_double()
        return typeinfo.serializer.read(buffer)

    cpdef inline xdeserialize_ref(self, Buffer buffer, Serializer serializer=None):
        cdef MapRefResolver ref_resolver
        cdef int32_t ref_id
        if serializer is None or serializer.need_to_write_ref:
            ref_resolver = self.ref_resolver
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            # indicates that the object is first read.
            if ref_id >= NOT_NULL_VALUE_FLAG:
                o = self.xdeserialize_nonref(
                    buffer, serializer=serializer
                )
                ref_resolver.set_read_object(ref_id, o)
                return o
            else:
                return ref_resolver.get_read_object()
        cdef int8_t head_flag = buffer.read_int8()
        if head_flag == NULL_FLAG:
            return None
        return self.xdeserialize_nonref(
            buffer, serializer=serializer
        )

    cpdef inline xdeserialize_nonref(
            self, Buffer buffer, Serializer serializer=None):
        if serializer is None:
            serializer = self.type_resolver.read_typeinfo(buffer).serializer
        return serializer.xread(buffer)

    cpdef inline write_buffer_object(self, Buffer buffer, buffer_object):
        if self._buffer_callback is not None and self._buffer_callback(buffer_object):
            buffer.write_bool(False)
            return
        buffer.write_bool(True)
        cdef int32_t size = buffer_object.total_bytes()
        # writer length.
        buffer.write_varuint32(size)
        cdef int32_t writer_index = buffer.writer_index
        buffer.ensure(writer_index + size)
        cdef Buffer buf = buffer.slice(buffer.writer_index, size)
        buffer_object.write_to(buf)
        buffer.writer_index += size

    cpdef inline Buffer read_buffer_object(self, Buffer buffer):
        cdef c_bool in_band = buffer.read_bool()
        if not in_band:
            assert self._buffers is not None
            return next(self._buffers)
        cdef int32_t size = buffer.read_varuint32()
        cdef Buffer buf = buffer.slice(buffer.reader_index, size)
        buffer.reader_index += size
        return buf

    cpdef inline handle_unsupported_write(self, Buffer buffer, obj):
        if self._unsupported_callback is None or self._unsupported_callback(obj):
            buffer.write_bool(True)
            # Use native serialization for all objects
            self.serialize_ref(buffer, obj)
        else:
            buffer.write_bool(False)

    cpdef inline handle_unsupported_read(self, Buffer buffer):
        cdef c_bool in_band = buffer.read_bool()
        if in_band:
            # The appropriate serializer will be determined by the type ID
            return self.deserialize_ref(buffer)
        else:
            assert self._unsupported_objects is not None
            return next(self._unsupported_objects)

    cpdef inline write_ref_pyobject(
            self, Buffer buffer, value, TypeInfo typeinfo=None):
        if self.ref_resolver.write_ref_or_null(buffer, value):
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_typeinfo(type(value))
        self.type_resolver.write_typeinfo(buffer, typeinfo)
        typeinfo.serializer.write(buffer, value)

    cpdef inline read_ref_pyobject(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef int32_t ref_id = ref_resolver.try_preserve_ref_id(buffer)
        if ref_id < NOT_NULL_VALUE_FLAG:
            return ref_resolver.get_read_object()
        # indicates that the object is first read.
        cdef TypeInfo typeinfo = self.type_resolver.read_typeinfo(buffer)
        o = typeinfo.serializer.read(buffer)
        ref_resolver.set_read_object(ref_id, o)
        return o

    cpdef inline reset_write(self):
        self.ref_resolver.reset_write()
        self.type_resolver.reset_write()
        self.metastring_resolver.reset_write()
        self.serialization_context.reset()
        self._unsupported_callback = None

    cpdef inline reset_read(self):
        self.ref_resolver.reset_read()
        self.type_resolver.reset_read()
        self.metastring_resolver.reset_read()
        self.serialization_context.reset()
        self._buffers = None
        self._unsupported_objects = None

    cpdef inline reset(self):
        self.reset_write()
        self.reset_read()

cpdef inline write_nullable_pybool(Buffer buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_bool(value)

cpdef inline write_nullable_pyint64(Buffer buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_varint64(value)

cpdef inline write_nullable_pyfloat64(Buffer buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_double(value)

cpdef inline write_nullable_pystr(Buffer buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_string(value)

cpdef inline read_nullable_pybool(Buffer buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_bool()
    else:
        return None

cpdef inline read_nullable_pyint64(Buffer buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_varint64()
    else:
        return None

cpdef inline read_nullable_pyfloat64(Buffer buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_double()
    else:
        return None

cpdef inline read_nullable_pystr(Buffer buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_string()
    else:
        return None


@cython.final
cdef class SerializationContext:
    cdef dict objects

    def __init__(self):
        self.objects = dict()

    def add(self, key, obj):
        self.objects[id(key)] = obj

    def __contains__(self, key):
        return id(key) in self.objects

    def __getitem__(self, key):
        return self.objects[id(key)]

    def get(self, key):
        return self.objects.get(id(key))

    def reset(self):
        if len(self.objects) > 0:
            self.objects.clear()

cdef class Serializer:
    cdef readonly Fory fory
    cdef readonly object type_
    cdef public c_bool need_to_write_ref

    def __init__(self, fory, type_: Union[type, TypeVar]):
        self.fory = fory
        self.type_ = type_
        self.need_to_write_ref = not is_primitive_type(type_)

    cpdef write(self, Buffer buffer, value):
        raise NotImplementedError(f"write method not implemented in {type(self)}")

    cpdef read(self, Buffer buffer):
        raise NotImplementedError(f"read method not implemented in {type(self)}")

    cpdef xwrite(self, Buffer buffer, value):
        raise NotImplementedError(f"xwrite method not implemented in {type(self)}")

    cpdef xread(self, Buffer buffer):
        raise NotImplementedError(f"xread method not implemented in {type(self)}")

    @classmethod
    def support_subclass(cls) -> bool:
        return False

cdef class CrossLanguageCompatibleSerializer(Serializer):
    cpdef xwrite(self, Buffer buffer, value):
        self.write(buffer, value)

    cpdef xread(self, Buffer buffer):
        return self.read(buffer)


@cython.final
cdef class BooleanSerializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_bool(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_bool()


@cython.final
cdef class ByteSerializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int8(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int8()


@cython.final
cdef class Int16Serializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_int16(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_int16()


@cython.final
cdef class Int32Serializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint32(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint32()


@cython.final
cdef class Int64Serializer(CrossLanguageCompatibleSerializer):
    cpdef inline xwrite(self, Buffer buffer, value):
        buffer.write_varint64(value)

    cpdef inline xread(self, Buffer buffer):
        return buffer.read_varint64()

    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varint64(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_varint64()

cdef int64_t INT8_MIN_VALUE = -1 << 7
cdef int64_t INT8_MAX_VALUE = 1 << 7 - 1
cdef int64_t INT16_MIN_VALUE = -1 << 15
cdef int64_t INT16_MAX_VALUE = 1 << 15 - 1
cdef int64_t INT32_MIN_VALUE = -1 << 31
cdef int64_t INT32_MAX_VALUE = 1 << 31 - 1
cdef float FLOAT32_MIN_VALUE = 1.17549e-38
cdef float FLOAT32_MAX_VALUE = 3.40282e+38


@cython.final
cdef class Float32Serializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_float(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_float()


@cython.final
cdef class Float64Serializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        buffer.write_double(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_double()


@cython.final
cdef class StringSerializer(CrossLanguageCompatibleSerializer):
    def __init__(self, fory, type_, track_ref=False):
        super().__init__(fory, type_)
        self.need_to_write_ref = track_ref

    cpdef inline write(self, Buffer buffer, value):
        buffer.write_string(value)

    cpdef inline read(self, Buffer buffer):
        return buffer.read_string()


cdef _base_date = datetime.date(1970, 1, 1)


@cython.final
cdef class DateSerializer(CrossLanguageCompatibleSerializer):
    cpdef inline write(self, Buffer buffer, value):
        if type(value) is not datetime.date:
            raise TypeError(
                "{} should be {} instead of {}".format(
                    value, datetime.date, type(value)
                )
            )
        days = (value - _base_date).days
        buffer.write_int32(days)

    cpdef inline read(self, Buffer buffer):
        days = buffer.read_int32()
        return _base_date + datetime.timedelta(days=days)


@cython.final
cdef class TimestampSerializer(CrossLanguageCompatibleSerializer):
    cdef bint win_platform

    def __init__(self, fory, type_: Union[type, TypeVar]):
        super().__init__(fory, type_)
        self.win_platform = platform.system() == "Windows"

    cdef inline _get_timestamp(self, value):
        seconds_offset = 0
        if self.win_platform and value.tzinfo is None:
            is_dst = time.daylight and time.localtime().tm_isdst > 0
            seconds_offset = time.altzone if is_dst else time.timezone
            value = value.replace(tzinfo=datetime.timezone.utc)
        return int((value.timestamp() + seconds_offset) * 1000000)

    cpdef inline write(self, Buffer buffer, value):
        if type(value) is not datetime.datetime:
            raise TypeError(
                "{} should be {} instead of {}".format(value, datetime, type(value))
            )
        # TimestampType represent micro seconds
        buffer.write_int64(self._get_timestamp(value))

    cpdef inline read(self, Buffer buffer):
        ts = buffer.read_int64() / 1000000
        # TODO support timezone
        return datetime.datetime.fromtimestamp(ts)


"""
Collection serialization format:
https://fory.apache.org/docs/specification/fory_xlang_serialization_spec/#list
Has the following changes:
* None has an independent NonType type, so COLLECTION_NOT_SAME_TYPE can also cover the concept of being nullable.
* No flag is needed to indicate that the element type is not the declared type.
"""
cdef int8_t COLLECTION_DEFAULT_FLAG = 0b0
cdef int8_t COLLECTION_TRACKING_REF = 0b1
cdef int8_t COLLECTION_HAS_NULL = 0b10
cdef int8_t COLLECTION_NOT_DECL_ELEMENT_TYPE = 0b100
cdef int8_t COLLECTION_NOT_SAME_TYPE = 0b1000


cdef class CollectionSerializer(Serializer):
    cdef TypeResolver type_resolver
    cdef MapRefResolver ref_resolver
    cdef Serializer elem_serializer
    cdef c_bool is_py
    cdef int8_t elem_tracking_ref
    cdef elem_type
    cdef TypeInfo elem_typeinfo

    def __init__(self, fory, type_, elem_serializer=None):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.elem_serializer = elem_serializer
        if elem_serializer is None:
            self.elem_type = None
            self.elem_typeinfo = self.type_resolver.get_typeinfo(None)
            self.elem_tracking_ref = -1
        else:
            self.elem_type = elem_serializer.type_
            self.elem_typeinfo = fory.type_resolver.get_typeinfo(self.elem_type)
            self.elem_tracking_ref = <int8_t> (elem_serializer.need_to_write_ref)
        self.is_py = fory.is_py

    cdef pair[int8_t, int64_t] write_header(self, Buffer buffer, value):
        cdef int8_t collect_flag = COLLECTION_DEFAULT_FLAG
        elem_type = self.elem_type
        cdef TypeInfo elem_typeinfo = self.elem_typeinfo
        cdef c_bool has_null = False
        cdef c_bool has_different_type = False
        if elem_type is None:
            collect_flag = COLLECTION_NOT_DECL_ELEMENT_TYPE
            for s in value:
                if not has_null and s is None:
                    has_null = True
                    continue
                if elem_type is None:
                    elem_type = type(s)
                elif not has_different_type and type(s) is not elem_type:
                    collect_flag |= COLLECTION_NOT_SAME_TYPE
                    has_different_type = True
            if not has_different_type:
                elem_typeinfo = self.type_resolver.get_typeinfo(elem_type)
        else:
            for s in value:
                if s is None:
                    has_null = True
                    break
        if has_null:
            collect_flag |= COLLECTION_HAS_NULL
        if self.fory.ref_tracking:
            if self.elem_tracking_ref == 1:
                collect_flag |= COLLECTION_TRACKING_REF
            elif self.elem_tracking_ref == -1:
                if has_different_type or elem_typeinfo.serializer.need_to_write_ref:
                    collect_flag |= COLLECTION_TRACKING_REF
        buffer.write_varuint32(len(value))
        buffer.write_int8(collect_flag)
        if (not has_different_type and
                collect_flag & COLLECTION_NOT_DECL_ELEMENT_TYPE != 0):
            self.type_resolver.write_typeinfo(buffer, elem_typeinfo)
        return pair[int8_t, int64_t](collect_flag, obj2int(elem_typeinfo))

    cpdef write(self, Buffer buffer, value):
        if len(value) == 0:
            buffer.write_varuint64(0)
            return
        cdef pair[int8_t, int64_t] header_pair = self.write_header(buffer, value)
        cdef int8_t collect_flag = header_pair.first
        cdef int64_t elem_typeinfo_ptr = header_pair.second
        cdef TypeInfo elem_typeinfo = <type> int2obj(elem_typeinfo_ptr)
        cdef elem_type = elem_typeinfo.cls
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        cdef c_bool is_py = self.is_py
        cdef serializer = type(elem_typeinfo.serializer)
        if (collect_flag & COLLECTION_NOT_SAME_TYPE) == 0:
            if elem_type is str:
                self._write_string(buffer, value)
            elif serializer is Int64Serializer:
                self._write_int(buffer, value)
            elif elem_type is bool:
                self._write_bool(buffer, value)
            elif serializer is Float64Serializer:
                self._write_float(buffer, value)
            else:
                if (collect_flag & COLLECTION_TRACKING_REF) == 0:
                    self._write_same_type_no_ref(buffer, value, elem_typeinfo)
                else:
                    self._write_same_type_ref(buffer, value, elem_typeinfo)
        else:
            for s in value:
                cls = type(s)
                if cls is str:
                    buffer.write_int16(NOT_NULL_STRING_FLAG)
                    buffer.write_string(s)
                elif cls is int:
                    buffer.write_int16(NOT_NULL_INT64_FLAG)
                    buffer.write_varint64(s)
                elif cls is bool:
                    buffer.write_int16(NOT_NULL_BOOL_FLAG)
                    buffer.write_bool(s)
                elif cls is float:
                    buffer.write_int16(NOT_NULL_FLOAT64_FLAG)
                    buffer.write_double(s)
                else:
                    if not ref_resolver.write_ref_or_null(buffer, s):
                        typeinfo = type_resolver.get_typeinfo(cls)
                        type_resolver.write_typeinfo(buffer, typeinfo)
                        if is_py:
                            typeinfo.serializer.write(buffer, s)
                        else:
                            typeinfo.serializer.xwrite(buffer, s)

    cdef inline _write_string(self, Buffer buffer, value):
        for s in value:
            buffer.write_string(s)

    cdef inline _read_string(self, Buffer buffer, int64_t len_, object collection_):
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_string())

    cdef inline _write_int(self, Buffer buffer, value):
        for s in value:
            buffer.write_varint64(s)

    cdef inline _read_int(self, Buffer buffer, int64_t len_, object collection_):
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_varint64())

    cdef inline _write_bool(self, Buffer buffer, value):
        value_type = type(value)
        if value_type is list or value_type is tuple:
            size = sizeof(bool) * Py_SIZE(value)
            buffer.grow(<int32_t>size)
            Fory_PyBooleanSequenceWriteToBuffer(value, buffer.c_buffer.get(), buffer.writer_index)
            buffer.writer_index += size
        else:
            for s in value:
                buffer.write_bool(s)

    cdef inline _read_bool(self, Buffer buffer, int64_t len_, object collection_):
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_bool())

    cdef inline _write_float(self, Buffer buffer, value):
        value_type = type(value)
        if value_type is list or value_type is tuple:
            size = sizeof(double) * Py_SIZE(value)
            buffer.grow(<int32_t>size)
            Fory_PyFloatSequenceWriteToBuffer(value, buffer.c_buffer.get(), buffer.writer_index)
            buffer.writer_index += size
        else:
            for s in value:
                buffer.write_double(s)

    cdef inline _read_float(self, Buffer buffer, int64_t len_, object collection_):
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_double())

    cpdef _write_same_type_no_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        if self.is_py:
            for s in value:
                typeinfo.serializer.write(buffer, s)
        else:
            for s in value:
                typeinfo.serializer.xwrite(buffer, s)

    cpdef _read_same_type_no_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        if self.is_py:
            for i in range(len_):
                obj = typeinfo.serializer.read(buffer)
                self._add_element(collection_, i, obj)
        else:
            for i in range(len_):
                obj = typeinfo.serializer.xread(buffer)
                self._add_element(collection_, i, obj)

    cpdef _write_same_type_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        if self.is_py:
            for s in value:
                if not ref_resolver.write_ref_or_null(buffer, s):
                    typeinfo.serializer.write(buffer, s)
        else:
            for s in value:
                if not ref_resolver.write_ref_or_null(buffer, s):
                    typeinfo.serializer.xwrite(buffer, s)

    cpdef _read_same_type_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        cdef c_bool is_py = self.is_py
        for i in range(len_):
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            if ref_id < NOT_NULL_VALUE_FLAG:
                obj = ref_resolver.get_read_object()
            else:
                if is_py:
                    obj = typeinfo.serializer.read(buffer)
                else:
                    obj = typeinfo.serializer.xread(buffer)
                ref_resolver.set_read_object(ref_id, obj)
            self._add_element(collection_, i, obj)

    cpdef _add_element(self, object collection_, int64_t index, object element):
        raise NotImplementedError

    cpdef xwrite(self, Buffer buffer, value):
        self.write(buffer, value)

cdef class ListSerializer(CollectionSerializer):
    cpdef read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        cdef int32_t len_ = buffer.read_varuint32()
        cdef list list_ = PyList_New(len_)
        if len_ == 0:
            return list_
        cdef int8_t collect_flag = buffer.read_int8()
        ref_resolver.reference(list_)
        cdef c_bool is_py = self.is_py
        cdef TypeInfo typeinfo
        cdef int32_t type_id = -1
        if (collect_flag & COLLECTION_NOT_SAME_TYPE) == 0:
            if collect_flag & COLLECTION_NOT_DECL_ELEMENT_TYPE != 0:
                typeinfo = self.type_resolver.read_typeinfo(buffer)
            else:
                typeinfo = self.elem_typeinfo
            if (collect_flag & COLLECTION_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if type_id == <int32_t>TypeId.STRING:
                    self._read_string(buffer, len_, list_)
                    return list_
                elif type_id == <int32_t>TypeId.VAR_INT64:
                    self._read_int(buffer, len_, list_)
                    return list_
                elif type_id == <int32_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, list_)
                    return list_
                elif type_id == <int32_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, list_)
                    return list_
            if (collect_flag & COLLECTION_TRACKING_REF) == 0:
                self._read_same_type_no_ref(buffer, len_, list_, typeinfo)
            else:
                self._read_same_type_ref(buffer, len_, list_, typeinfo)
        else:
            for i in range(len_):
                elem = get_next_element(buffer, ref_resolver, type_resolver, is_py)
                Py_INCREF(elem)
                PyList_SET_ITEM(list_, i, elem)
        return list_

    cpdef _add_element(self, object collection_, int64_t index, object element):
        Py_INCREF(element)
        PyList_SET_ITEM(collection_, index, element)

    cpdef xread(self, Buffer buffer):
        return self.read(buffer)

cdef inline get_next_element(
        Buffer buffer,
        MapRefResolver ref_resolver,
        TypeResolver type_resolver,
        c_bool is_py,
):
    cdef int32_t ref_id
    cdef TypeInfo typeinfo
    ref_id = ref_resolver.try_preserve_ref_id(buffer)
    if ref_id < NOT_NULL_VALUE_FLAG:
        return ref_resolver.get_read_object()
    # indicates that the object is first read.
    typeinfo = type_resolver.read_typeinfo(buffer)
    cdef int32_t type_id = typeinfo.type_id
    # Note that all read operations in fast paths of list/tuple/set/dict/sub_dict
    # ust match corresponding writing operations. Otherwise, ref tracking will
    # error.
    if type_id == <int32_t>TypeId.STRING:
        return buffer.read_string()
    elif type_id == <int32_t>TypeId.VAR_INT32:
        return buffer.read_varint64()
    elif type_id == <int32_t>TypeId.BOOL:
        return buffer.read_bool()
    elif type_id == <int32_t>TypeId.FLOAT64:
        return buffer.read_double()
    else:
        if is_py:
            o = typeinfo.serializer.read(buffer)
        else:
            o = typeinfo.serializer.xread(buffer)
        ref_resolver.set_read_object(ref_id, o)
        return o


@cython.final
cdef class TupleSerializer(CollectionSerializer):
    cpdef inline read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        cdef int32_t len_ = buffer.read_varuint32()
        cdef tuple tuple_ = PyTuple_New(len_)
        if len_ == 0:
            return tuple_
        cdef int8_t collect_flag = buffer.read_int8()
        cdef c_bool is_py = self.is_py
        cdef TypeInfo typeinfo
        cdef int32_t type_id = -1
        if (collect_flag & COLLECTION_NOT_SAME_TYPE) == 0:
            if collect_flag & COLLECTION_NOT_DECL_ELEMENT_TYPE != 0:
                typeinfo = self.type_resolver.read_typeinfo(buffer)
            else:
                typeinfo = self.elem_typeinfo
            if (collect_flag & COLLECTION_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if type_id == <int32_t>TypeId.STRING:
                    self._read_string(buffer, len_, tuple_)
                    return tuple_
                if type_id == <int32_t>TypeId.VAR_INT64:
                    self._read_int(buffer, len_, tuple_)
                    return tuple_
                if type_id == <int32_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, tuple_)
                    return tuple_
                if type_id == <int32_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, tuple_)
                    return tuple_
            if (collect_flag & COLLECTION_TRACKING_REF) == 0:
                self._read_same_type_no_ref(buffer, len_, tuple_, typeinfo)
            else:
                self._read_same_type_ref(buffer, len_, tuple_, typeinfo)
        else:
            for i in range(len_):
                elem = get_next_element(buffer, ref_resolver, type_resolver, is_py)
                Py_INCREF(elem)
                PyTuple_SET_ITEM(tuple_, i, elem)
        return tuple_

    cpdef inline _add_element(self, object collection_, int64_t index, object element):
        Py_INCREF(element)
        PyTuple_SET_ITEM(collection_, index, element)

    cpdef inline xread(self, Buffer buffer):
        return self.read(buffer)


@cython.final
cdef class StringArraySerializer(ListSerializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_, StringSerializer(fory, str))


@cython.final
cdef class SetSerializer(CollectionSerializer):
    cpdef inline read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        cdef set instance = set()
        ref_resolver.reference(instance)
        cdef int32_t len_ = buffer.read_varuint32()
        if len_ == 0:
            return instance
        cdef int8_t collect_flag = buffer.read_int8()
        cdef int32_t ref_id
        cdef TypeInfo typeinfo
        cdef int32_t type_id = -1
        cdef c_bool is_py = self.is_py
        if (collect_flag & COLLECTION_NOT_SAME_TYPE) == 0:
            if collect_flag & COLLECTION_NOT_DECL_ELEMENT_TYPE != 0:
                typeinfo = self.type_resolver.read_typeinfo(buffer)
            else:
                typeinfo = self.elem_typeinfo
            if (collect_flag & COLLECTION_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if type_id == <int32_t>TypeId.STRING:
                    self._read_string(buffer, len_, instance)
                    return instance
                if type_id == <int32_t>TypeId.VAR_INT64:
                    self._read_int(buffer, len_, instance)
                    return instance
                if type_id == <int32_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, instance)
                    return instance
                if type_id == <int32_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, instance)
                    return instance
            if (collect_flag & COLLECTION_TRACKING_REF) == 0:
                self._read_same_type_no_ref(buffer, len_, instance, typeinfo)
            else:
                self._read_same_type_ref(buffer, len_, instance, typeinfo)
        else:
            for i in range(len_):
                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                if ref_id < NOT_NULL_VALUE_FLAG:
                    instance.add(ref_resolver.get_read_object())
                    continue
                # indicates that the object is first read.
                typeinfo = type_resolver.read_typeinfo(buffer)
                type_id = typeinfo.type_id
                if type_id == <int32_t>TypeId.STRING:
                    instance.add(buffer.read_string())
                elif type_id == <int32_t>TypeId.VAR_INT64:
                    instance.add(buffer.read_varint64())
                elif type_id == <int32_t>TypeId.BOOL:
                    instance.add(buffer.read_bool())
                elif type_id == <int32_t>TypeId.FLOAT64:
                    instance.add(buffer.read_double())
                else:
                    if is_py:
                        o = typeinfo.serializer.read(buffer)
                    else:
                        o = typeinfo.serializer.xread(buffer)
                    ref_resolver.set_read_object(ref_id, o)
                    instance.add(o)
        return instance

    cpdef inline _add_element(self, object collection_, int64_t index, object element):
        collection_.add(element)

    cpdef inline xread(self, Buffer buffer):
        return self.read(buffer)


cdef int32_t MAX_CHUNK_SIZE = 255
# Whether track key ref.
cdef int32_t TRACKING_KEY_REF = 0b1
# Whether key has null.
cdef int32_t KEY_HAS_NULL = 0b10
# Whether key is not declare type.
cdef int32_t KEY_DECL_TYPE = 0b100
# Whether track value ref.
cdef int32_t TRACKING_VALUE_REF = 0b1000
# Whether value has null.
cdef int32_t VALUE_HAS_NULL = 0b10000
# Whether value is not declare type.
cdef int32_t VALUE_DECL_TYPE = 0b100000
# When key or value is null that entry will be serialized as a new chunk with size 1.
# In such cases, chunk size will be skipped writing.
# Both key and value are null.
cdef int32_t KV_NULL = KEY_HAS_NULL | VALUE_HAS_NULL
# Key is null, value type is declared type, and ref tracking for value is disabled.
cdef int32_t NULL_KEY_VALUE_DECL_TYPE = KEY_HAS_NULL | VALUE_DECL_TYPE
# Key is null, value type is declared type, and ref tracking for value is enabled.
cdef int32_t NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF =KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF
# Value is null, key type is declared type, and ref tracking for key is disabled.
cdef int32_t NULL_VALUE_KEY_DECL_TYPE = VALUE_HAS_NULL | KEY_DECL_TYPE
# Value is null, key type is declared type, and ref tracking for key is enabled.
cdef int32_t NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_VALUE_REF


@cython.final
cdef class MapSerializer(Serializer):
    cdef TypeResolver type_resolver
    cdef MapRefResolver ref_resolver
    cdef Serializer key_serializer
    cdef Serializer value_serializer
    cdef c_bool is_py

    def __init__(self, fory, type_, key_serializer=None, value_serializer=None):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.key_serializer = key_serializer
        self.value_serializer = value_serializer
        self.is_py = fory.is_py

    cpdef inline write(self, Buffer buffer, o):
        cdef dict obj = o
        cdef int32_t length = len(obj)
        buffer.write_varuint32(length)
        if length == 0:
            return
        cdef int64_t key_addr, value_addr
        cdef Py_ssize_t pos = 0
        cdef Fory fory = self.fory
        cdef TypeResolver type_resolver = fory.type_resolver
        cdef MapRefResolver ref_resolver = fory.ref_resolver
        cdef Serializer key_serializer = self.key_serializer
        cdef Serializer value_serializer = self.value_serializer
        cdef type key_cls, value_cls, key_serializer_type, value_serializer_type
        cdef TypeInfo key_typeinfo, value_typeinfo
        cdef int32_t chunk_size_offset, chunk_header, chunk_size
        cdef c_bool key_write_ref, value_write_ref
        cdef int has_next = PyDict_Next(obj, &pos, <PyObject **>&key_addr, <PyObject **>&value_addr)
        cdef c_bool is_py = self.is_py
        while has_next != 0:
            key = int2obj(key_addr)
            Py_INCREF(key)
            value = int2obj(value_addr)
            Py_INCREF(value)
            while has_next != 0:
                if key is not None:
                    if value is not None:
                        break
                    if key_serializer is not None:
                        if key_serializer.need_to_write_ref:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
                            if not self.ref_resolver.write_ref_or_null(buffer, key):
                                if is_py:
                                    key_serializer.write(buffer, key)
                                else:
                                    key_serializer.xwrite(buffer, key)
                        else:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE)
                            if is_py:
                                key_serializer.write(buffer, key)
                            else:
                                key_serializer.xwrite(buffer, key)
                    else:
                        buffer.write_int8(VALUE_HAS_NULL | TRACKING_KEY_REF)
                        if is_py:
                            fory.serialize_ref(buffer, key)
                        else:
                            fory.xserialize_ref(buffer, key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            if value_serializer.need_to_write_ref:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
                                if not self.ref_resolver.write_ref_or_null(buffer, value):
                                    if is_py:
                                        value_serializer.write(buffer, value)
                                    else:
                                        value_serializer.xwrite(buffer, value)
                                if not self.ref_resolver.write_ref_or_null(buffer, value):
                                    if is_py:
                                        value_serializer.write(buffer, value)
                                    else:
                                        value_serializer.xwrite(buffer, value)
                            else:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE)
                                if is_py:
                                    value_serializer.write(buffer, value)
                                else:
                                    value_serializer.xwrite(buffer, value)
                        else:
                            buffer.write_int8(KEY_HAS_NULL | TRACKING_VALUE_REF)
                            if is_py:
                                fory.serialize_ref(buffer, value)
                            else:
                                fory.xserialize_ref(buffer, value)
                    else:
                        buffer.write_int8(KV_NULL)
                has_next = PyDict_Next(obj, &pos, <PyObject **>&key_addr, <PyObject **>&value_addr)
                key = int2obj(key_addr)
                Py_INCREF(key)
                value = int2obj(value_addr)
                Py_INCREF(value)
            if has_next == 0:
                break
            key_cls = type(key)
            value_cls = type(value)
            buffer.write_int16(-1)
            chunk_size_offset = buffer.writer_index - 1
            chunk_header = 0
            if key_serializer is not None:
                chunk_header |= KEY_DECL_TYPE
            else:
                key_typeinfo = self.type_resolver.get_typeinfo(key_cls)
                type_resolver.write_typeinfo(buffer, key_typeinfo)
                key_serializer = key_typeinfo.serializer
            if value_serializer is not None:
                chunk_header |= VALUE_DECL_TYPE
            else:
                value_typeinfo = self.type_resolver.get_typeinfo(value_cls)
                type_resolver.write_typeinfo(buffer, value_typeinfo)
                value_serializer = value_typeinfo.serializer
            key_write_ref = key_serializer.need_to_write_ref
            value_write_ref = value_serializer.need_to_write_ref
            if key_write_ref:
                chunk_header |= TRACKING_KEY_REF
            if value_write_ref:
                chunk_header |= TRACKING_VALUE_REF
            buffer.put_int8(chunk_size_offset - 1, chunk_header)
            key_serializer_type = type(key_serializer)
            value_serializer_type = type(value_serializer)
            chunk_size = 0
            while True:
                if (key is None or value is None or
                        type(key) is not key_cls or type(value) is not value_cls):
                    break
                if not key_write_ref or not ref_resolver.write_ref_or_null(buffer, key):
                    if key_cls is str:
                        buffer.write_string(key)
                    elif key_serializer_type is Int64Serializer:
                        buffer.write_varint64(key)
                    elif key_serializer_type is Float64Serializer:
                        buffer.write_double(key)
                    elif key_serializer_type is Int32Serializer:
                        buffer.write_varint32(key)
                    elif key_serializer_type is Float32Serializer:
                        buffer.write_float(key)
                    else:
                        if is_py:
                            key_serializer.write(buffer, key)
                        else:
                            key_serializer.xwrite(buffer, key)
                if not value_write_ref or not ref_resolver.write_ref_or_null(buffer, value):
                    if value_cls is str:
                        buffer.write_string(value)
                    elif value_serializer_type is Int64Serializer:
                        buffer.write_varint64(value)
                    elif value_serializer_type is Float64Serializer:
                        buffer.write_double(value)
                    elif value_serializer_type is Int32Serializer:
                        buffer.write_varint32(value)
                    elif value_serializer_type is Float32Serializer:
                        buffer.write_float(value)
                    elif value_serializer_type is BooleanSerializer:
                        buffer.write_bool(value)
                    else:
                        if is_py:
                            value_serializer.write(buffer, value)
                        else:
                            value_serializer.xwrite(buffer, value)
                chunk_size += 1
                has_next = PyDict_Next(obj, &pos, <PyObject **>&key_addr, <PyObject **>&value_addr)
                if has_next == 0:
                    break
                if chunk_size == MAX_CHUNK_SIZE:
                    break
                key = int2obj(key_addr)
                Py_INCREF(key)
                value = int2obj(value_addr)
                Py_INCREF(value)
            key_serializer = self.key_serializer
            value_serializer = self.value_serializer
            buffer.put_int8(chunk_size_offset, chunk_size)

    cpdef inline read(self, Buffer buffer):
        cdef Fory fory = self.fory
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        cdef int32_t size = buffer.read_varuint32()
        cdef dict map_ = _PyDict_NewPresized(size)
        ref_resolver.reference(map_)
        cdef int32_t ref_id
        cdef TypeInfo key_typeinfo, value_typeinfo
        cdef int32_t chunk_header = 0
        if size != 0:
            chunk_header = buffer.read_uint8()
        cdef Serializer key_serializer = self.key_serializer
        cdef Serializer value_serializer = self.value_serializer
        cdef c_bool key_has_null, value_has_null, track_key_ref, track_value_ref
        cdef c_bool key_is_declared_type, value_is_declared_type
        cdef type key_serializer_type, value_serializer_type
        cdef int32_t chunk_size
        cdef c_bool is_py = self.is_py
        while size > 0:
            while True:
                key_has_null = (chunk_header & KEY_HAS_NULL) != 0
                value_has_null = (chunk_header & VALUE_HAS_NULL) != 0
                if not key_has_null:
                    if not value_has_null:
                        break
                    else:
                        track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
                        if (chunk_header & KEY_DECL_TYPE) != 0:
                            if track_key_ref:
                                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                                if ref_id < NOT_NULL_VALUE_FLAG:
                                    key = ref_resolver.get_read_object()
                                else:
                                    if is_py:
                                        key = key_serializer.read(buffer)
                                    else:
                                        key = key_serializer.xread(buffer)
                                    ref_resolver.set_read_object(ref_id, key)
                            else:
                                if is_py:
                                    key = key_serializer.read(buffer)
                                else:
                                    key = key_serializer.xread(buffer)
                        else:
                            if is_py:
                                key = fory.deserialize_ref(buffer)
                            else:
                                key = fory.xdeserialize_ref(buffer)
                        map_[key] = None
                else:
                    if not value_has_null:
                        track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
                        if (chunk_header & VALUE_DECL_TYPE) != 0:
                            if track_value_ref:
                                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                                if ref_id < NOT_NULL_VALUE_FLAG:
                                    value = ref_resolver.get_read_object()
                                else:
                                    if is_py:
                                        value = value_serializer.read(buffer)
                                    else:
                                        value = value_serializer.xread(buffer)
                                    ref_resolver.set_read_object(ref_id, value)
                        else:
                            if is_py:
                                value = fory.deserialize_ref(buffer)
                            else:
                                value = fory.xdeserialize_ref(buffer)
                        map_[None] = value
                    else:
                        map_[None] = None
                size -= 1
                if size == 0:
                    return map_
                else:
                    chunk_header = buffer.read_uint8()
            track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
            track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
            key_is_declared_type = (chunk_header & KEY_DECL_TYPE) != 0
            value_is_declared_type = (chunk_header & VALUE_DECL_TYPE) != 0
            chunk_size = buffer.read_uint8()
            if not key_is_declared_type:
                key_serializer = type_resolver.read_typeinfo(buffer).serializer
            if not value_is_declared_type:
                value_serializer = type_resolver.read_typeinfo(buffer).serializer
            key_serializer_type = type(key_serializer)
            value_serializer_type = type(value_serializer)
            for i in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_resolver.get_read_object()
                    else:
                        if is_py:
                            key = key_serializer.read(buffer)
                        else:
                            key = key_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, key)
                else:
                    if key_serializer_type is StringSerializer:
                        key = buffer.read_string()
                    elif key_serializer_type is Int64Serializer:
                        key = buffer.read_varint64()
                    elif key_serializer_type is Float64Serializer:
                        key = buffer.read_double()
                    elif key_serializer_type is Int32Serializer:
                        key = buffer.read_varint32()
                    elif key_serializer_type is Float32Serializer:
                        key = buffer.read_float()
                    else:
                        if is_py:
                            key = key_serializer.read(buffer)
                        else:
                            key = key_serializer.xread(buffer)
                if track_value_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_resolver.get_read_object()
                    else:
                        if is_py:
                            value = value_serializer.read(buffer)
                        else:
                            value = value_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, value)
                else:
                    if value_serializer_type is StringSerializer:
                        value = buffer.read_string()
                    elif value_serializer_type is Int64Serializer:
                        value = buffer.read_varint64()
                    elif value_serializer_type is Float64Serializer:
                        value = buffer.read_double()
                    elif value_serializer_type is Int32Serializer:
                        value = buffer.read_varint32()
                    elif value_serializer_type is Float32Serializer:
                        value = buffer.read_float()
                    elif value_serializer_type is BooleanSerializer:
                        value = buffer.read_bool()
                    else:
                        if is_py:
                            value = value_serializer.read(buffer)
                        else:
                            value = value_serializer.xread(buffer)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = buffer.read_uint8()
        return map_

    cpdef inline xwrite(self, Buffer buffer, o):
        self.write(buffer, o)

    cpdef inline xread(self, Buffer buffer):
        return self.read(buffer)


@cython.final
cdef class SubMapSerializer(Serializer):
    cdef TypeResolver type_resolver
    cdef MapRefResolver ref_resolver
    cdef Serializer key_serializer
    cdef Serializer value_serializer

    def __init__(self, fory, type_, key_serializer=None, value_serializer=None):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.key_serializer = key_serializer
        self.value_serializer = value_serializer

    cpdef inline write(self, Buffer buffer, value):
        buffer.write_varuint32(len(value))
        cdef TypeInfo key_typeinfo
        cdef TypeInfo value_typeinfo
        for k, v in value.items():
            key_cls = type(k)
            if key_cls is str:
                buffer.write_int16(NOT_NULL_STRING_FLAG)
                buffer.write_string(k)
            else:
                if not self.ref_resolver.write_ref_or_null(buffer, k):
                    key_typeinfo = self.type_resolver.get_typeinfo(key_cls)
                    self.type_resolver.write_typeinfo(buffer, key_typeinfo)
                    key_typeinfo.serializer.write(buffer, k)
            value_cls = type(v)
            if value_cls is str:
                buffer.write_int16(NOT_NULL_STRING_FLAG)
                buffer.write_string(v)
            elif value_cls is int:
                buffer.write_int16(NOT_NULL_INT64_FLAG)
                buffer.write_varint64(v)
            elif value_cls is bool:
                buffer.write_int16(NOT_NULL_BOOL_FLAG)
                buffer.write_bool(v)
            elif value_cls is float:
                buffer.write_int16(NOT_NULL_FLOAT64_FLAG)
                buffer.write_double(v)
            else:
                if not self.ref_resolver.write_ref_or_null(buffer, v):
                    value_typeinfo = self.type_resolver. \
                        get_typeinfo(value_cls)
                    self.type_resolver.write_typeinfo(buffer, value_typeinfo)
                    value_typeinfo.serializer.write(buffer, v)

    cpdef inline read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        map_ = self.type_()
        ref_resolver.reference(map_)
        cdef int32_t len_ = buffer.read_varuint32()
        cdef int32_t ref_id
        cdef TypeInfo key_typeinfo
        cdef TypeInfo value_typeinfo
        for i in range(len_):
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            if ref_id < NOT_NULL_VALUE_FLAG:
                key = ref_resolver.get_read_object()
            else:
                key_typeinfo = type_resolver.read_typeinfo(buffer)
                if key_typeinfo.cls is str:
                    key = buffer.read_string()
                else:
                    key = key_typeinfo.serializer.read(buffer)
                    ref_resolver.set_read_object(ref_id, key)
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            if ref_id < NOT_NULL_VALUE_FLAG:
                value = ref_resolver.get_read_object()
            else:
                value_typeinfo = type_resolver.read_typeinfo(buffer)
                cls = value_typeinfo.cls
                if cls is str:
                    value = buffer.read_string()
                elif cls is int:
                    value = buffer.read_varint64()
                elif cls is bool:
                    value = buffer.read_bool()
                elif cls is float:
                    value = buffer.read_double()
                else:
                    value = value_typeinfo.serializer.read(buffer)
                    ref_resolver.set_read_object(ref_id, value)
            map_[key] = value
        return map_


@cython.final
cdef class EnumSerializer(Serializer):
    @classmethod
    def support_subclass(cls) -> bool:
        return True

    cpdef inline write(self, Buffer buffer, value):
        buffer.write_string(value.name)

    cpdef inline read(self, Buffer buffer):
        name = buffer.read_string()
        return getattr(self.type_, name)

    cpdef inline xwrite(self, Buffer buffer, value):
        buffer.write_varuint32(value.value)

    cpdef inline xread(self, Buffer buffer):
        ordinal = buffer.read_varuint32()
        return self.type_(ordinal)


@cython.final
cdef class SliceSerializer(Serializer):
    cpdef inline write(self, Buffer buffer, v):
        cdef slice value = v
        start, stop, step = value.start, value.stop, value.step
        if type(start) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(start)
        else:
            if start is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.serialize_nonref(buffer, start)
        if type(stop) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.serialize_nonref(buffer, stop)
        if type(step) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.serialize_nonref(buffer, step)

    cpdef inline read(self, Buffer buffer):
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
        return slice(start, stop, step)

    cpdef xwrite(self, Buffer buffer, value):
        raise NotImplementedError

    cpdef xread(self, Buffer buffer):
        raise NotImplementedError
