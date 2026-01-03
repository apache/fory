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
from pyfory.policy import DeserializationPolicy, DEFAULT_POLICY
from pyfory.util import is_little_endian
from pyfory.includes.libserialization cimport \
    (TypeId, IsNamespacedType, IsTypeShareMeta, Fory_PyBooleanSequenceWriteToBuffer, Fory_PyFloatSequenceWriteToBuffer)

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
    """
    Manages object reference tracking during serialization and deserialization.

    Handles shared and circular references by assigning unique IDs to objects
    during serialization and resolving them during deserialization. This enables
    efficient serialization of object graphs with duplicate references and prevents
    infinite recursion with circular references.

    When ref_tracking is enabled, duplicate object references are serialized only once,
    with subsequent references storing only the reference ID. During deserialization,
    the resolver maintains a mapping to reconstruct the exact same object graph structure.

    Note:
        This is an internal class used by the Fory serializer. Users typically don't
        interact with this class directly.
    """
    cdef flat_hash_map[uint64_t, int32_t] written_objects_id  # id(obj) -> ref_id
    # Hold object to avoid tmp object gc when serialize nested fields/objects.
    cdef vector[PyObject *] written_objects
    cdef vector[PyObject *] read_objects
    cdef vector[int32_t] read_ref_ids
    cdef object read_object
    cdef c_bool ref_tracking

    def __cinit__(self, c_bool ref):
        self.read_object = None
        self.ref_tracking = ref

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
        cdef PyObject * obj
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_varuint32()
            assert 0 <= ref_id < self.read_objects.size(), f"Invalid ref id {ref_id}, current size {self.read_objects.size()}"
            obj = self.read_objects[ref_id]
            assert obj != NULL, f"Invalid ref id {ref_id}, current size {self.read_objects.size()}"
            self.read_object = <object> obj
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
        cdef int32_t ref_id
        cdef PyObject *obj
        if head_flag == REF_FLAG:
            # read reference id and get object from reference resolver
            ref_id = buffer.read_varuint32()
            # avoid wrong id cause crash
            assert 0 <= ref_id < self.read_objects.size(), f"Invalid ref id {ref_id}, current size {self.read_objects.size()}"
            obj = self.read_objects[ref_id]
            assert obj != NULL, f"Invalid ref id {ref_id}, current size {self.read_objects.size()}"
            self.read_object = <object> obj
            # `head_flag` except `REF_FLAG` can be used as stub reference id because
            # we use `refId >= NOT_NULL_VALUE_FLAG` to read data.
            return head_flag
        else:
            self.read_object = None
            if head_flag == REF_VALUE_FLAG:
                return self.preserve_ref_id()
            # For NOT_NULL_VALUE_FLAG, push -1 to read_ref_ids so reference() knows
            # this object is not referenceable (it's a value type, not a reference type)
            self.read_ref_ids.push_back(-1)
            return head_flag

    cpdef inline int32_t last_preserved_ref_id(self):
        cdef int32_t length = self.read_ref_ids.size()
        assert length > 0
        return self.read_ref_ids[length - 1]

    cpdef inline reference(self, obj):
        if not self.ref_tracking:
            return
        cdef int32_t ref_id = self.read_ref_ids.back()
        self.read_ref_ids.pop_back()
        # When NOT_NULL_VALUE_FLAG was read instead of REF_VALUE_FLAG,
        # -1 is pushed to read_ref_ids. This means the object is a value type
        # (not a reference type), so we skip reference tracking.
        if ref_id < 0:
            return
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
        cdef PyObject * obj = self.read_objects[ref_id]
        if obj == NULL:
            return None
        return <object> obj

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

    cpdef inline get_metastr_bytes(self, metastr):
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
    We do not get the actual type here, because it will introduce extra computing.
    For example, we have want to get actual `Int8/16/32/64Serializer`, we must check and
    extract the actual here which will introduce cost, and we will do same thing again
    when serializing the actual data.
    """
    cdef public object cls
    cdef public int32_t type_id
    cdef public Serializer serializer
    cdef public MetaStringBytes namespace_bytes
    cdef public MetaStringBytes typename_bytes
    cdef public c_bool dynamic_type
    cdef public object type_def

    def __init__(
            self,
            cls: Union[type, TypeVar] = None,
            type_id: int = NO_TYPE_ID,
            serializer: Serializer = None,
            namespace_bytes: MetaStringBytes = None,
            typename_bytes: MetaStringBytes = None,
            dynamic_type: bool = False,
            type_def: object = None
    ):
        self.cls = cls
        self.type_id = type_id
        self.serializer = serializer
        self.namespace_bytes = namespace_bytes
        self.typename_bytes = typename_bytes
        self.dynamic_type = dynamic_type
        self.type_def = type_def

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
    """
    Manages type registration, resolution, and serializer dispatch.

    TypeResolver maintains mappings between Python types and their corresponding
    serialization metadata (TypeInfo), including serializers, type IDs, and cross-
    language type names. It handles both registered types (with explicit type IDs)
    and dynamic types (resolved at runtime).

    For cross-language serialization, TypeResolver coordinates namespace and typename
    encoding using MetaString compression, and manages type definition sharing when
    compatible mode is enabled.

    The resolver uses high-performance C++ hash maps for fast type lookups during
    serialization and deserialization.

    Note:
        This is an internal class used by the Fory serializer. Users typically don't
        interact with this class directly, but instead use Fory.register() methods.
    """
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
        c_bool meta_share
        readonly SerializationContext serialization_context

    def __init__(self, fory, meta_share=False, meta_compressor=None):
        self.fory = fory
        self.metastring_resolver = fory.metastring_resolver
        self.meta_share = meta_share
        from pyfory._registry import TypeResolver
        self._resolver = TypeResolver(fory, meta_share=meta_share, meta_compressor=meta_compressor)

    def initialize(self):
        self._resolver.initialize()
        for typeinfo in self._resolver._types_info.values():
            self._populate_typeinfo(typeinfo)
        self.serialization_context = self.fory.serialization_context

    def register(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        self.register_type(cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer)

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
        # Resize if load factor >= 0.4 (using integer arithmetic: size/capacity >= 4/10)
        if self._c_types_info.size() * 10 >= self._c_types_info.bucket_count() * 5:
            self._c_types_info.rehash(self._c_types_info.size() * 2)
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
                type_info.serializer = self._resolver.get_typeinfo(cls).serializer
                return type_info
        elif not create:
            return None
        else:
            type_info = self._resolver.get_typeinfo(cls, create=create)
            self._c_types_info[<uintptr_t> <PyObject *> cls] = <PyObject *> type_info
            self._populate_typeinfo(type_info)
            return type_info

    cpdef inline is_registered_by_name(self, cls):
        return self._resolver.is_registered_by_name(cls)

    cpdef inline is_registered_by_id(self, cls):
        return self._resolver.is_registered_by_id(cls)

    cpdef inline get_registered_name(self, cls):
        return self._resolver.get_registered_name(cls)

    cpdef inline get_registered_id(self, cls):
        return self._resolver.get_registered_id(cls)

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

    cpdef inline write_typeinfo(self, Buffer buffer, TypeInfo typeinfo):
        if typeinfo.dynamic_type:
            return
        cdef:
            int32_t type_id = typeinfo.type_id
            int32_t internal_type_id = type_id & 0xFF

        if self.meta_share:
            self.write_shared_type_meta(buffer, typeinfo)
            return

        buffer.write_varuint32(type_id)
        if IsNamespacedType(internal_type_id):
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.namespace_bytes)
            self.metastring_resolver.write_meta_string_bytes(buffer, typeinfo.typename_bytes)

    cpdef inline TypeInfo read_typeinfo(self, Buffer buffer):
        if self.meta_share:
            return self.read_shared_type_meta(buffer)

        cdef:
            int32_t type_id = buffer.read_varuint32()
        if type_id < 0:
            type_id = -type_id
        if type_id >= self._c_registered_id_to_type_info.size():
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

    cpdef inline TypeInfo get_typeinfo_by_id(self, int32_t type_id):
        if type_id >= self._c_registered_id_to_type_info.size() or type_id < 0 or IsNamespacedType(type_id & 0xFF):
            raise ValueError(f"Unexpected type_id {type_id}")
        typeinfo_ptr = self._c_registered_id_to_type_info[type_id]
        if typeinfo_ptr == NULL:
            raise ValueError(f"Unexpected type_id {type_id}")
        typeinfo = <TypeInfo> typeinfo_ptr
        return typeinfo

    cpdef inline get_typeinfo_by_name(self, namespace, typename):
        return self._resolver.get_typeinfo_by_name(namespace=namespace, typename=typename)

    cpdef inline _set_typeinfo(self, typeinfo):
        self._resolver._set_typeinfo(typeinfo)

    cpdef inline get_meta_compressor(self):
        return self._resolver.get_meta_compressor()

    cpdef inline write_shared_type_meta(self, Buffer buffer, TypeInfo typeinfo):
        """Write shared type meta information."""
        meta_context = self.serialization_context.meta_context
        meta_context.write_shared_typeinfo(buffer, typeinfo)

    cpdef inline TypeInfo read_shared_type_meta(self, Buffer buffer):
        """Read shared type meta information."""
        meta_context = self.serialization_context.meta_context
        typeinfo = meta_context.read_shared_typeinfo(buffer)
        return typeinfo

    cpdef inline write_type_defs(self, Buffer buffer):
        """Write all type definitions that need to be sent."""
        self._resolver.write_type_defs(buffer)

    cpdef inline read_type_defs(self, Buffer buffer):
        """Read all type definitions from the buffer."""
        self._resolver.read_type_defs(buffer)

    cpdef inline reset(self):
        pass

    cpdef inline reset_read(self):
        pass

    cpdef inline reset_write(self):
        pass


@cython.final
cdef class MetaContext:
    """
    Manages type metadata sharing across serializations in compatible mode.

    When compatible mode is enabled, MetaContext tracks type definitions (type names,
    field names, field types) to enable efficient schema evolution. Instead of sending
    full type metadata with every serialized object, the context sends type definitions
    once and references them by ID in subsequent serializations.

    This enables forward/backward compatibility when struct fields are added or removed
    between different versions of an application.

    Note:
        This is an internal class used by SerializationContext. It is not thread-safe
        and should only be used with a single Fory instance.
    """
    cdef:
        # Types which have sent definitions to peer
        # Maps type objects to their assigned IDs
        flat_hash_map[uint64_t, int32_t] _c_type_map

        # Counter for assigning new IDs
        list _writing_type_defs
        list _read_type_infos
        object fory
        object type_resolver

    def __cinit__(self, object fory):
        self.fory = fory
        self.type_resolver = fory.type_resolver
        self._writing_type_defs = []
        self._read_type_infos = []

    cpdef inline void write_shared_typeinfo(self, Buffer buffer, typeinfo):
        """Add a type definition to the writing queue."""
        type_cls = typeinfo.cls
        cdef int32_t type_id = typeinfo.type_id
        cdef int32_t internal_type_id = type_id & 0xFF
        buffer.write_varuint32(type_id)
        if not IsTypeShareMeta(internal_type_id):
            return

        cdef uint64_t type_addr = <uint64_t> <PyObject *> type_cls
        cdef flat_hash_map[uint64_t, int32_t].iterator it = self._c_type_map.find(type_addr)
        if it != self._c_type_map.end():
            buffer.write_varuint32(deref(it).second)
            return

        cdef index = self._c_type_map.size()
        buffer.write_varuint32(index)
        self._c_type_map[type_addr] = index
        type_def = typeinfo.type_def
        if type_def is None:
            self.type_resolver._set_typeinfo(typeinfo)
            type_def = typeinfo.type_def
        self._writing_type_defs.append(type_def)

    cpdef inline list get_writing_type_defs(self):
        """Get all type definitions that need to be written."""
        return self._writing_type_defs

    cpdef inline reset_write(self):
        """Reset write state."""
        self._writing_type_defs.clear()
        self._c_type_map.clear()

    cpdef inline add_read_typeinfo(self, type_info):
        """Add a type info read from peer."""
        self._read_type_infos.append(type_info)

    cpdef inline read_shared_typeinfo(self, Buffer buffer):
        """Read a type info from buffer."""
        cdef type_id = buffer.read_varuint32()
        if IsTypeShareMeta(type_id & 0xFF):
            return self._read_type_infos[buffer.read_varuint32()]
        return self.type_resolver.get_typeinfo_by_id(type_id)

    cpdef inline reset_read(self):
        """Reset read state."""
        self._read_type_infos.clear()

    cpdef inline reset(self):
        """Reset both read and write state."""
        self.reset_write()
        self.reset_read()

    def __str__(self):
        return self.__repr__()

    def __repr__(self):
        return (f"MetaContext("
                f"read_infos={self._read_type_infos}, "
                f"writing_defs={self._writing_type_defs})")


@cython.final
cdef class SerializationContext:
    """
    Manages serialization state and metadata sharing across operations.

    SerializationContext provides a scoped storage for sharing data during serialization
    and deserialization operations. When compatible mode is enabled, it maintains a
    MetaContext for efficient type metadata sharing to support schema evolution.

    The context stores temporary objects needed during serialization (e.g., class
    definitions, custom serialization state) and coordinates type definition exchange
    between serializer and deserializer.

    Note:
        This is an internal class used by the Fory serializer. It is not thread-safe
        and should only be used with a single Fory instance.
    """
    cdef dict objects
    cdef readonly bint scoped_meta_share_enabled
    cdef public MetaContext meta_context
    cdef public object fory

    def __init__(self, object fory, scoped_meta_share_enabled: bool = False):
        self.objects = dict()
        self.scoped_meta_share_enabled = scoped_meta_share_enabled
        if scoped_meta_share_enabled:
            self.meta_context = MetaContext(fory)
        else:
            self.meta_context = None
        self.fory = fory

    cpdef inline add(self, key, obj):
        self.objects[id(key)] = obj

    def __contains__(self, key):
        return id(key) in self.objects

    def __getitem__(self, key):
        return self.objects[id(key)]

    def get(self, key):
        return self.objects.get(id(key))

    cpdef inline reset(self):
        if len(self.objects) > 0:
            self.objects.clear()

    cpdef inline reset_write(self):
        if len(self.objects) > 0:
            self.objects.clear()
        if self.scoped_meta_share_enabled and self.meta_context is not None:
            self.meta_context.reset_write()

    cpdef inline reset_read(self):
        if len(self.objects) > 0:
            self.objects.clear()
        if self.scoped_meta_share_enabled and self.meta_context is not None:
            self.meta_context.reset_read()


@cython.final
cdef class Fory:
    """
    High-performance cross-language serialization framework.

    Fory provides blazingly-fast serialization for Python objects with support for
    both Python-native mode and cross-language mode. It handles complex object graphs,
    reference tracking, and circular references automatically.

    In Python-native mode (xlang=False), Fory can serialize all Python objects
    including dataclasses, classes with custom serialization methods, and local
    functions/classes, making it a drop-in replacement for pickle.

    In cross-language mode (xlang=True), Fory serializes objects in a format that
    can be deserialized by other Fory-supported languages (Java, Go, Rust, C++, etc).

    Examples:
        >>> import pyfory
        >>> from dataclasses import dataclass
        >>>
        >>> @dataclass
        >>> class Person:
        ...     name: str
        ...     age: pyfory.int32
        >>>
        >>> # Python-native mode
        >>> fory = pyfory.Fory()
        >>> fory.register(Person)
        >>> data = fory.serialize(Person("Alice", 30))
        >>> person = fory.deserialize(data)
        >>>
        >>> # Cross-language mode
        >>> fory_xlang = pyfory.Fory(xlang=True)
        >>> fory_xlang.register(Person)
        >>> data = fory_xlang.serialize(Person("Bob", 25))

    See Also:
        ThreadSafeFory: Thread-safe wrapper for concurrent usage
    """
    cdef readonly object language
    cdef readonly c_bool ref_tracking
    cdef readonly c_bool strict
    cdef readonly c_bool is_py
    cdef readonly c_bool compatible
    cdef readonly c_bool field_nullable
    cdef readonly object policy
    cdef readonly MapRefResolver ref_resolver
    cdef readonly TypeResolver type_resolver
    cdef readonly MetaStringResolver metastring_resolver
    cdef readonly SerializationContext serialization_context
    cdef Buffer buffer
    cdef public object buffer_callback
    cdef object _buffers  # iterator
    cdef object _unsupported_callback
    cdef object _unsupported_objects  # iterator
    cdef object _peer_language
    cdef int32_t max_depth
    cdef int32_t depth

    def __init__(
            self,
            xlang: bool = False,
            ref: bool = False,
            strict: bool = True,
            policy: DeserializationPolicy = None,
            compatible: bool = False,
            max_depth: int = 50,
            field_nullable: bool = False,
            meta_compressor=None,
            **kwargs,
    ):
        """
        Initialize a Fory serialization instance.

        Args:
            xlang: Enable cross-language serialization mode. When False (default), uses
                Python-native mode supporting all Python objects (dataclasses, __reduce__,
                local functions/classes). With ref=True and strict=False, serves as a
                drop-in replacement for pickle. When True, uses cross-language format
                compatible with other Fory languages (Java, Go, Rust, etc), but Python-
                specific features like functions and __reduce__ methods are not supported.

            ref: Enable reference tracking for shared and circular references. When enabled,
                duplicate objects are stored once and circular references are supported.
                Disabled by default for better performance.

            strict: Require type registration before serialization (default: True). When
                disabled, unknown types can be deserialized, which may be insecure if
                malicious code exists in __new__/__init__/__eq__/__hash__ methods.
                **WARNING**: Only disable in trusted environments. When disabling strict
                mode, you should provide a custom `policy` parameter to control which types
                are allowed. We are not responsible for security risks when this option
                is disabled without proper policy controls.

            compatible: Enable schema evolution for cross-language serialization. When
                enabled, supports forward/backward compatibility for struct field
                additions and removals.

            max_depth: Maximum nesting depth for deserialization (default: 50). Raises
                an exception if exceeded to prevent malicious deeply-nested data attacks.

            policy: Custom deserialization policy for security checks. When provided,
                it controls which types can be deserialized, overriding the default policy.
                **Strongly recommended** when strict=False to maintain security controls.

            field_nullable: Treat all dataclass fields as nullable in Python-native mode
                (xlang=False), regardless of Optional annotation. Ignored in cross-language
                mode.

        Example:
            >>> # Python-native mode with reference tracking
            >>> fory = Fory(ref=True)
            >>>
            >>> # Cross-language mode with schema evolution
            >>> fory = Fory(xlang=True, compatible=True)
        """
        self.language = Language.XLANG if xlang else Language.PYTHON
        if kwargs.get("language") is not None:
            self.language = kwargs.get("language")
        if kwargs.get("ref_tracking") is not None:
            ref = kwargs.get("ref_tracking")
        if kwargs.get("require_type_registration") is not None:
            strict = kwargs.get("require_type_registration")
        if _ENABLE_TYPE_REGISTRATION_FORCIBLY or strict:
            self.strict = True
        else:
            self.strict = False
        self.policy = policy or DEFAULT_POLICY
        self.compatible = compatible
        self.ref_tracking = ref
        self.ref_resolver = MapRefResolver(ref)
        self.is_py = self.language == Language.PYTHON
        self.field_nullable = field_nullable if self.is_py else False
        self.metastring_resolver = MetaStringResolver()
        self.type_resolver = TypeResolver(self, meta_share=compatible, meta_compressor=meta_compressor)
        self.serialization_context = SerializationContext(fory=self, scoped_meta_share_enabled=compatible)
        self.type_resolver.initialize()
        self.buffer = Buffer.allocate(32)
        self.buffer_callback = None
        self._buffers = None
        self._unsupported_callback = None
        self._unsupported_objects = None
        self._peer_language = None
        self.depth = 0
        self.max_depth = max_depth

    def register_serializer(self, cls: Union[type, TypeVar], Serializer serializer):
        """
        Register a custom serializer for a type.

        Allows you to provide a custom serializer implementation for a specific type,
        overriding Fory's default serialization behavior.

        Args:
            cls: The Python type to associate with the serializer
            serializer: Custom serializer instance implementing the Serializer protocol

        Example:
            >>> fory = Fory()
            >>> fory.register_serializer(MyClass, MyCustomSerializer())
        """
        self.type_resolver.register_serializer(cls, serializer)

    def register(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        This is an alias for `register_type()`. Type registration enables Fory to
        efficiently serialize and deserialize objects by pre-computing serialization
        metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **namespace + typename**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to namespace/typename matching.
            namespace: Optional namespace for cross-language type matching by name.
                Used when type_id is not specified.
            typename: Optional type name for cross-language type matching by name.
                Defaults to class name if not specified. Used with namespace.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register(Person, type_id=100)
            >>>
            >>> # Register with namespace and typename (more flexible)
            >>> fory.register(Person, namespace="com.example", typename="Person")
            >>>
            >>> # Python-native mode (no cross-language matching needed)
            >>> fory = Fory()
            >>> fory.register(Person)
        """
        self.type_resolver.register_type(
            cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer)

    def register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        """
        Register a type for serialization.

        Type registration enables Fory to efficiently serialize and deserialize objects
        by pre-computing serialization metadata.

        For cross-language serialization, types can be matched between languages using:
        1. **type_id** (recommended): Numeric ID matching - faster and more compact
        2. **namespace + typename**: String-based matching - more flexible but larger overhead

        Args:
            cls: The Python type to register
            type_id: Optional unique numeric ID for cross-language type matching.
                Using type_id provides better performance and smaller serialized size
                compared to namespace/typename matching.
            namespace: Optional namespace for cross-language type matching by name.
                Used when type_id is not specified.
            typename: Optional type name for cross-language type matching by name.
                Defaults to class name if not specified. Used with namespace.
            serializer: Optional custom serializer instance for this type

        Example:
            >>> # Register with type_id (recommended for performance)
            >>> fory = Fory(xlang=True)
            >>> fory.register_type(Person, type_id=100)
            >>>
            >>> # Register with namespace and typename (more flexible)
            >>> fory.register_type(Person, namespace="com.example", typename="Person")
            >>>
            >>> # Python-native mode (no cross-language matching needed)
            >>> fory = Fory()
            >>> fory.register_type(Person)
        """
        self.type_resolver.register_type(
            cls, type_id=type_id, namespace=namespace, typename=typename, serializer=serializer)

    def dumps(
        self,
        obj,
        buffer: Buffer = None,
        buffer_callback=None,
        unsupported_callback=None,
    ) -> Union[Buffer, bytes]:
        """
        Serialize an object to bytes, alias for `serialize` method.
        """
        return self.serialize(obj, buffer, buffer_callback, unsupported_callback)

    def loads(
        self,
        buffer: Union[Buffer, bytes],
        buffers: Iterable = None,
        unsupported_objects: Iterable = None,
    ):
        """
        Deserialize bytes to an object, alias for `deserialize` method.
        """
        return self.deserialize(buffer, buffers, unsupported_objects)

    def serialize(
            self, obj,
            Buffer buffer=None,
            buffer_callback=None,
            unsupported_callback=None
    ) -> Union[Buffer, bytes]:
        """
        Serialize a Python object to bytes.

        Converts the object into Fory's binary format. The serialization process
        automatically handles reference tracking (if enabled), type information,
        and nested objects.

        Args:
            obj: The object to serialize
            buffer: Optional pre-allocated buffer to write to. If None, uses internal buffer
            buffer_callback: Optional callback for out-of-band buffer serialization
            unsupported_callback: Optional callback for handling unsupported types

        Returns:
            Serialized bytes if buffer is None, otherwise returns the provided buffer

        Example:
            >>> fory = Fory()
            >>> data = fory.serialize({"key": "value", "num": 42})
            >>> print(type(data))
            <class 'bytes'>
        """
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
        assert self.depth == 0, "Nested serialization should use write_ref/write_no_ref/xwrite_ref/xwrite_no_ref."
        self.depth += 1
        self.buffer_callback = buffer_callback
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
        if self.buffer_callback is not None:
            set_bit(buffer, mask_index, 3)
        else:
            clear_bit(buffer, mask_index, 3)
        # Reserve space for type definitions offset, similar to Java implementation
        cdef int32_t type_defs_offset_pos = -1
        if self.serialization_context.scoped_meta_share_enabled:
            type_defs_offset_pos = buffer.writer_index
            buffer.write_int32(-1)  # Reserve 4 bytes for type definitions offset

        cdef int32_t start_offset
        if self.language == Language.PYTHON:
            self.write_ref(buffer, obj)
        else:
            self.xwrite_ref(buffer, obj)

        # Write type definitions at the end, similar to Java implementation
        if self.serialization_context.scoped_meta_share_enabled:
            meta_context = self.serialization_context.meta_context
            if meta_context is not None and len(meta_context.get_writing_type_defs()) > 0:
                # Update the offset to point to current position
                current_pos = buffer.writer_index
                buffer.put_int32(type_defs_offset_pos, current_pos - type_defs_offset_pos - 4)
                self.type_resolver.write_type_defs(buffer)

        if buffer is not self.buffer:
            return buffer
        else:
            return buffer.to_bytes(0, buffer.writer_index)

    cpdef inline write_ref(
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

    cpdef inline write_no_ref(self, Buffer buffer, obj):
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

    cpdef inline xwrite_ref(
            self, Buffer buffer, obj, Serializer serializer=None):
        if serializer is None or serializer.need_to_write_ref:
            if not self.ref_resolver.write_ref_or_null(buffer, obj):
                self.xwrite_no_ref(
                    buffer, obj, serializer=serializer
                )
        else:
            if obj is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.xwrite_no_ref(
                    buffer, obj, serializer=serializer
                )

    cpdef inline xwrite_no_ref(
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
        """
        Deserialize bytes back to a Python object.

        Reconstructs an object from Fory's binary format. The deserialization process
        automatically handles reference resolution (if enabled), type instantiation,
        and nested objects.

        Args:
            buffer: Serialized bytes or Buffer to deserialize from
            buffers: Optional iterable of buffers for out-of-band deserialization
            unsupported_objects: Optional iterable of objects for unsupported type handling

        Returns:
            The deserialized Python object

        Example:
            >>> fory = Fory()
            >>> data = fory.serialize({"key": "value"})
            >>> obj = fory.deserialize(data)
            >>> print(obj)
            {'key': 'value'}
        """
        try:
            if type(buffer) == bytes:
                buffer = Buffer(buffer)
            return self._deserialize(buffer, buffers, unsupported_objects)
        finally:
            self.reset_read()

    cpdef inline _deserialize(
            self, Buffer buffer, buffers=None, unsupported_objects=None):
        assert self.depth == 0, "Nested deserialization should use read_ref/read_no_ref/xread_ref/xread_no_ref."
        self.depth += 1
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

        # Read type definitions at the start, similar to Java implementation
        cdef int32_t end_reader_index = -1
        if self.serialization_context.scoped_meta_share_enabled:
            relative_type_defs_offset = buffer.read_int32()
            if relative_type_defs_offset != -1:
                # Save current reader position
                current_reader_index = buffer.reader_index
                # Jump to type definitions
                buffer.reader_index = current_reader_index + relative_type_defs_offset
                # Read type definitions
                self.type_resolver.read_type_defs(buffer)
                # Save the end position (after type defs) - this is the true end of serialized data
                end_reader_index = buffer.reader_index
                # Jump back to continue with object deserialization
                buffer.reader_index = current_reader_index

        if not is_target_x_lang:
            obj = self.read_ref(buffer)
        else:
            obj = self.xread_ref(buffer)

        # After reading the object, position buffer at the end of serialized data
        # (which is after the type definitions, not after the object data)
        if end_reader_index != -1:
            buffer.reader_index = end_reader_index

        return obj

    cpdef inline read_ref(self, Buffer buffer):
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
        self.inc_depth()
        o = typeinfo.serializer.read(buffer)
        self.depth -= 1
        ref_resolver.set_read_object(ref_id, o)
        return o

    cpdef inline read_no_ref(self, Buffer buffer):
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
        self.inc_depth()
        o = typeinfo.serializer.read(buffer)
        self.depth -= 1
        return o

    cpdef inline xread_ref(self, Buffer buffer, Serializer serializer=None):
        cdef MapRefResolver ref_resolver
        cdef int32_t ref_id
        if serializer is None or serializer.need_to_write_ref:
            ref_resolver = self.ref_resolver
            ref_id = ref_resolver.try_preserve_ref_id(buffer)
            # indicates that the object is first read.
            if ref_id >= NOT_NULL_VALUE_FLAG:
                # Don't push -1 here - try_preserve_ref_id already pushed ref_id
                o = self._xread_no_ref_internal(buffer, serializer)
                ref_resolver.set_read_object(ref_id, o)
                return o
            else:
                return ref_resolver.get_read_object()
        cdef int8_t head_flag = buffer.read_int8()
        if head_flag == NULL_FLAG:
            return None
        return self.xread_no_ref(
            buffer, serializer=serializer
        )

    cpdef inline xread_no_ref(
            self, Buffer buffer, Serializer serializer=None):
        if serializer is None:
            serializer = self.type_resolver.read_typeinfo(buffer).serializer
        # Push -1 to read_ref_ids so reference() can pop it and skip reference tracking
        # This handles the case where xread_no_ref is called directly without xread_ref
        if self.ref_resolver.ref_tracking:
            self.ref_resolver.read_ref_ids.push_back(-1)
        return self._xread_no_ref_internal(buffer, serializer)

    cdef inline _xread_no_ref_internal(
            self, Buffer buffer, Serializer serializer):
        """Internal method to read without pushing to read_ref_ids."""
        if serializer is None:
            serializer = self.type_resolver.read_typeinfo(buffer).serializer
        self.inc_depth()
        o = serializer.xread(buffer)
        self.depth -= 1
        return o

    cpdef inline inc_depth(self):
        self.depth += 1
        if self.depth > self.max_depth:
            self.throw_depth_limit_exceeded_exception()

    cpdef inline dec_depth(self):
        self.depth -= 1

    cpdef inline throw_depth_limit_exceeded_exception(self):
        raise Exception(
            f"Read depth exceed max depth: {self.depth}, the deserialization data may be malicious. If it's not malicious, "
            "please increase max read depth by Fory(..., max_depth=...)"
        )

    cpdef inline write_buffer_object(self, Buffer buffer, buffer_object):
        cdef int32_t size
        cdef int32_t writer_index
        cdef Buffer buf
        if self.buffer_callback is None or self.buffer_callback(buffer_object):
            buffer.write_bool(True)
            size = buffer_object.total_bytes()
            # writer length.
            buffer.write_varuint32(size)
            writer_index = buffer.writer_index
            buffer.ensure(writer_index + size)
            buf = buffer.slice(buffer.writer_index, size)
            buffer_object.write_to(buf)
            buffer.writer_index += size
        else:
            buffer.write_bool(False)

    cpdef inline object read_buffer_object(self, Buffer buffer):
        cdef c_bool in_band = buffer.read_bool()
        if not in_band:
            assert self._buffers is not None
            return next(self._buffers)
        cdef int32_t size = buffer.read_varuint32()
        cdef Buffer buf = buffer.slice(buffer.reader_index, size)
        buffer.reader_index += size
        return buf

    cpdef handle_unsupported_write(self, buffer, obj):
        if self._unsupported_callback is None or self._unsupported_callback(obj):
            raise NotImplementedError(f"{type(obj)} is not supported for write")

    cpdef handle_unsupported_read(self, buffer):
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
        self.inc_depth()
        o = typeinfo.serializer.read(buffer)
        self.depth -= 1
        ref_resolver.set_read_object(ref_id, o)
        return o

    cpdef inline reset_write(self):
        """
        Reset write state after serialization.

        Clears internal write buffers, reference tracking state, and type resolution
        caches. This method is automatically called after each serialization.
        """
        self.depth = 0
        self.ref_resolver.reset_write()
        self.type_resolver.reset_write()
        self.metastring_resolver.reset_write()
        self.serialization_context.reset_write()
        self._unsupported_callback = None

    cpdef inline reset_read(self):
        """
        Reset read state after deserialization.

        Clears internal read buffers, reference tracking state, and type resolution
        caches. This method is automatically called after each deserialization.
        """
        self.depth = 0
        self.ref_resolver.reset_read()
        self.type_resolver.reset_read()
        self.metastring_resolver.reset_read()
        self.serialization_context.reset_read()
        self._buffers = None
        self._unsupported_objects = None

    cpdef inline reset(self):
        """
        Reset both write and read state.

        Clears all internal state including buffers, reference tracking, and type
        resolution caches. Use this to ensure a clean state before reusing a Fory
        instance.
        """
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


cdef class Serializer:
    """
    Base class for type-specific serializers.

    Serializer defines the interface for serializing and deserializing objects of a
    specific type. Each serializer implements two modes:

    - Python-native mode (write/read): Optimized for Python-to-Python serialization,
      supporting all Python-specific features like __reduce__, local functions, etc.

    - Cross-language mode (xwrite/xread): Serializes to a cross-language format
      compatible with other Fory implementations (Java, Go, Rust, C++, etc).

    Custom serializers can be registered for user-defined types using
    Fory.register_serializer() to override default serialization behavior.

    Attributes:
        fory: The Fory instance this serializer belongs to
        type_: The Python type this serializer handles
        need_to_write_ref: Whether reference tracking is needed for this type

    Note:
        This is a base class for implementing custom serializers. Subclasses must
        implement write(), read(), xwrite(), and xread() methods.
    """
    cdef readonly Fory fory
    cdef readonly object type_
    cdef public c_bool need_to_write_ref

    def __init__(self, fory, type_: Union[type, TypeVar]):
        self.fory = fory
        self.type_ = type_
        self.need_to_write_ref = fory.ref_tracking and not is_primitive_type(type_)

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

cdef class XlangCompatibleSerializer(Serializer):
    cpdef xwrite(self, Buffer buffer, value):
        self.write(buffer, value)

    cpdef xread(self, Buffer buffer):
        return self.read(buffer)


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
                self.fory.write_no_ref(buffer, start)
        if type(stop) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(stop)
        else:
            if stop is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.write_no_ref(buffer, stop)
        if type(step) is int:
            # TODO support varint128
            buffer.write_int16(NOT_NULL_INT64_FLAG)
            buffer.write_varint64(step)
        else:
            if step is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                self.fory.write_no_ref(buffer, step)

    cpdef inline read(self, Buffer buffer):
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
        return slice(start, stop, step)

    cpdef xwrite(self, Buffer buffer, value):
        raise NotImplementedError

    cpdef xread(self, Buffer buffer):
        raise NotImplementedError


include "primitive.pxi"
include "collection.pxi"
