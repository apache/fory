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

import datetime
import os
import platform
import time
from typing import TypeVar, Union

import cython
from libc.stdint cimport int32_t, int64_t, uint8_t, uint64_t
from libc.stdint cimport *
from libcpp cimport bool as c_bool
from libcpp.utility cimport pair
from libcpp.vector cimport vector
from cython.operator cimport dereference as deref
from cpython cimport PyObject
from cpython.object cimport PyTypeObject, PyObject_GetAttr, PyObject_SetAttr
from cpython.dict cimport PyDict_Next
from cpython.list cimport PyList_New, PyList_SET_ITEM
from cpython.tuple cimport PyTuple_New, PyTuple_SET_ITEM
from cpython.ref cimport Py_INCREF, Py_XDECREF
from pyfory.includes.libabsl cimport flat_hash_map
from pyfory.includes.libutil cimport FlatIntMap
from pyfory._fory import (
    NO_USER_TYPE_ID,
    NOT_NULL_INT64_FLAG,
)
from pyfory.meta.typedef_decoder import decode_typedef
from pyfory.meta.metastring import MetaStringDecoder
from pyfory.policy import DEFAULT_POLICY
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory.types import is_primitive_type
from pyfory.includes.libserialization cimport (
    TypeId,
    TypeRegistrationKind,
    get_type_registration_kind,
    is_namespaced_type,
    is_type_share_meta,
    Fory_IsInternalTypeId,
    Fory_CanUsePrimitiveCollectionFastpath,
    Fory_PyPrimitiveCollectionWriteToBuffer,
    Fory_PyPrimitiveCollectionReadFromBuffer,
    Fory_PyWriteBasicFieldToBuffer,
    Fory_PyReadBasicFieldFromBuffer,
)

cdef extern from *:
    """
    #define int2obj(obj_addr) ((PyObject *)(obj_addr))
    #define obj2int(obj_ref) (Py_INCREF(obj_ref), ((int64_t)(obj_ref)))
    #define fory_sequence_get_items(collection) \
      (PyList_CheckExact(collection) ? ((PyListObject *)(collection))->ob_item : \
      (PyTuple_CheckExact(collection) ? ((PyTupleObject *)(collection))->ob_item : NULL))
    """
    object int2obj(int64_t obj_addr)
    int64_t obj2int(object obj_ref)
    PyObject **fory_sequence_get_items(object collection)
    dict _PyDict_NewPresized(Py_ssize_t minused)
    Py_ssize_t Py_SIZE(object obj)

ENABLE_FORY_CYTHON_SERIALIZATION = os.environ.get(
    "ENABLE_FORY_CYTHON_SERIALIZATION", "True"
).lower() in ("true", "1")

cdef int32_t NOT_NULL_BOOL_FLAG = (NOT_NULL_VALUE_FLAG & 0xFF) | (<int32_t>TypeId.BOOL << 8)
cdef int32_t NOT_NULL_STRING_FLAG = (NOT_NULL_VALUE_FLAG & 0xFF) | (<int32_t>TypeId.STRING << 8)
cdef int32_t NOT_NULL_FLOAT64_FLAG = (NOT_NULL_VALUE_FLAG & 0xFF) | (<int32_t>TypeId.FLOAT64 << 8)


@cython.final
cdef class Config:
    """
    Immutable runtime configuration shared by `Fory`, `TypeResolver`, and the
    directional read/write contexts.

    The Cython runtime treats this object as the single source of truth for
    execution-mode flags and guardrail limits. Higher-level facades may expose
    convenience accessors, but runtime code should read these values from the
    config instance instead of mirroring them onto other owners.

    Attributes:
        xlang: Enables cross-language wire format instead of Python-native mode.
        track_ref: Enables reference tracking for shared and circular object graphs.
        strict: Requires type registration before serialization/deserialization.
        compatible: Enables compatible mode and schema-evolution metadata paths.
        meta_share: Enables shared type metadata on the resolver/type-info path.
        scoped_meta_share_enabled: Enables per-operation meta-share state.
        max_depth: Maximum allowed nesting depth during deserialization.
        field_nullable: Treats struct/dataclass fields as nullable by default.
        policy: Deserialization policy used for security-sensitive checks.
        meta_compressor: Optional typedef/meta compressor implementation.
        max_collection_size: Upper bound for declared collection/map sizes.
        max_binary_size: Upper bound for a single binary payload read.
    """

    cdef public bint xlang
    cdef public bint track_ref
    cdef public bint strict
    cdef public bint compatible
    cdef public bint meta_share
    cdef public bint scoped_meta_share_enabled
    cdef public int32_t max_depth
    cdef public bint field_nullable
    cdef public object policy
    cdef public object meta_compressor
    cdef public int32_t max_collection_size
    cdef public int32_t max_binary_size

    def __init__(
        self,
        *,
        xlang,
        track_ref,
        strict,
        compatible,
        meta_share,
        scoped_meta_share_enabled,
        max_depth,
        field_nullable,
        policy,
        meta_compressor,
        max_collection_size,
        max_binary_size,
    ):
        """
        Build a runtime config object for one Python or Cython Fory instance.

        Args:
            xlang: Enable cross-language serialization mode.
            track_ref: Enable reference tracking for object graphs.
            strict: Require registered types on dynamic resolution paths.
            compatible: Enable compatible mode and meta-share flows.
            meta_share: Enable shared type metadata on resolver/type-info paths.
            scoped_meta_share_enabled: Enable per-operation meta-share state.
            max_depth: Maximum allowed read depth before failing deserialization.
            field_nullable: Treat all struct fields as nullable by default.
            policy: Deserialization policy implementation.
            meta_compressor: Optional typedef/meta compressor.
            max_collection_size: Maximum declared collection/map size.
            max_binary_size: Maximum binary payload size for one read.
        """
        self.xlang = xlang
        self.track_ref = track_ref
        self.strict = strict
        self.compatible = compatible
        self.meta_share = meta_share
        self.scoped_meta_share_enabled = scoped_meta_share_enabled
        self.max_depth = max_depth
        self.field_nullable = field_nullable
        self.policy = policy
        self.meta_compressor = meta_compressor
        self.max_collection_size = max_collection_size
        self.max_binary_size = max_binary_size


cdef inline bint _is_struct_type_id(uint8_t type_id):
    return (
        type_id == <uint8_t>TypeId.STRUCT
        or type_id == <uint8_t>TypeId.COMPATIBLE_STRUCT
        or type_id == <uint8_t>TypeId.NAMED_STRUCT
        or type_id == <uint8_t>TypeId.NAMED_COMPATIBLE_STRUCT
    )


cdef class WriteContext
cdef class ReadContext


@cython.final
cdef class TypeResolver:
    """
    Cython accelerator for type-info lookup and wire-level type metadata IO.

    The source of truth for registration and non-hotpath bookkeeping remains the
    Python `pyfory.registry.TypeResolver`. This Cython companion caches the hot
    lookup tables needed by serialization and deserialization so active runtime
    paths can avoid Python-level dispatch where the layout is stable.
    """

    cdef object resolver
    cdef readonly object shared_registry
    cdef readonly bint xlang
    cdef readonly bint track_ref
    cdef readonly bint strict
    cdef readonly bint compatible
    cdef readonly bint field_nullable
    cdef readonly object policy
    cdef readonly int32_t max_collection_size
    cdef readonly int32_t max_binary_size
    cdef readonly bint meta_share
    cdef readonly dict _types_info
    cdef readonly dict _type_id_to_type_info
    cdef readonly dict _user_type_id_to_type_info
    cdef readonly dict _ns_type_to_type_info
    cdef readonly dict _meta_shared_type_info
    cdef vector[PyObject *] _c_registered_id_to_type_info
    cdef flat_hash_map[uint32_t, PyObject *] _c_user_type_id_to_type_info
    cdef flat_hash_map[uint64_t, PyObject *] _c_types_info
    cdef flat_hash_map[pair[int64_t, int64_t], PyObject *] _c_meta_hash_to_type_info

    def __init__(self, Config config, *, shared_registry):
        """
        Build the Cython resolver and its hot caches.

        Args:
            config: Runtime configuration shared by the owning `Fory`.
            shared_registry: Shared encoded meta-string registry.
        """
        from pyfory.registry import TypeResolver as PyTypeResolver

        resolver = PyTypeResolver(
            config,
            shared_registry=shared_registry,
        )
        self.resolver = resolver
        self.shared_registry = resolver.shared_registry
        self.xlang = resolver.xlang
        self.track_ref = resolver.track_ref
        self.strict = resolver.strict
        self.compatible = resolver.compatible
        self.field_nullable = resolver.field_nullable
        self.policy = resolver.policy
        self.max_collection_size = resolver.max_collection_size
        self.max_binary_size = resolver.max_binary_size
        self.meta_share = resolver.meta_share
        self._types_info = resolver._types_info
        self._type_id_to_type_info = resolver._type_id_to_type_info
        self._user_type_id_to_type_info = resolver._user_type_id_to_type_info
        self._ns_type_to_type_info = resolver._ns_type_to_type_info
        self._meta_shared_type_info = resolver._meta_shared_type_info
        for typeinfo in resolver._types_info.values():
            self._populate_type_info(typeinfo)

    def initialize(self):
        cdef object typeinfo
        self.resolver._set_actual_resolver(self)
        self.resolver.initialize()
        for typeinfo in self.resolver._types_info.values():
            self._populate_type_info(typeinfo)

    def register_type(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        cdef TypeInfo typeinfo = self.resolver.register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )
        self._populate_type_info(typeinfo)
        return typeinfo

    def register_union(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        cdef TypeInfo typeinfo = self.resolver.register_union(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )
        self._populate_type_info(typeinfo)
        return typeinfo

    def register_serializer(self, cls, serializer):
        cdef TypeInfo typeinfo
        cdef uint8_t previous_type_id
        cdef uint32_t previous_user_type_id
        typeinfo = self.resolver.get_type_info(cls)
        previous_type_id = typeinfo.type_id
        previous_user_type_id = typeinfo.user_type_id
        self.resolver.register_serializer(cls, serializer)
        typeinfo = self.resolver.get_type_info(cls)
        if previous_type_id != typeinfo.type_id or previous_user_type_id != typeinfo.user_type_id:
            if (
                previous_type_id == <uint8_t>TypeId.ENUM
                or previous_type_id == <uint8_t>TypeId.STRUCT
                or previous_type_id == <uint8_t>TypeId.COMPATIBLE_STRUCT
                or previous_type_id == <uint8_t>TypeId.EXT
                or previous_type_id == <uint8_t>TypeId.TYPED_UNION
            ):
                if previous_user_type_id != <uint32_t>NO_USER_TYPE_ID:
                    self._c_user_type_id_to_type_info[previous_user_type_id] = NULL
            elif previous_type_id > 0 and previous_type_id < self._c_registered_id_to_type_info.size():
                self._c_registered_id_to_type_info[previous_type_id] = NULL
        self._populate_type_info(typeinfo)

    cpdef inline TypeInfo get_type_info(self, cls, create=True):
        cdef PyObject * typeinfo_ptr = self._c_types_info[<uintptr_t> <PyObject *> cls]
        cdef TypeInfo typeinfo
        if typeinfo_ptr != NULL:
            typeinfo = <TypeInfo> typeinfo_ptr
            if typeinfo.serializer is None:
                typeinfo = self.resolver.get_type_info(cls, create=create)
                self._populate_type_info(typeinfo)
            return typeinfo
        if not create:
            return None
        typeinfo = self.resolver.get_type_info(cls, create=create)
        self._populate_type_info(typeinfo)
        return typeinfo

    cpdef inline Serializer get_serializer(self, cls):
        cdef TypeInfo typeinfo = self.get_type_info(cls)
        return None if typeinfo is None else typeinfo.serializer

    def get_type_info_by_id(self, type_id, user_type_id=NO_USER_TYPE_ID):
        return self.resolver.get_type_info_by_id(type_id, user_type_id=user_type_id)

    def get_type_info_by_name(self, namespace, typename):
        return self.resolver.get_type_info_by_name(namespace, typename)

    def get_registered_name(self, cls):
        return self.resolver.get_registered_name(cls)

    def get_registered_type_ids(self, cls):
        return self.resolver.get_registered_type_ids(cls)

    def is_registered_by_name(self, cls):
        return self.resolver.is_registered_by_name(cls)

    cpdef inline write_type_info(self, WriteContext write_context, TypeInfo typeinfo):
        cdef uint8_t type_id
        cdef TypeRegistrationKind reg_kind
        if typeinfo.dynamic_type:
            return
        type_id = typeinfo.type_id
        write_context.write_uint8(type_id)
        if (
            type_id == <uint8_t>TypeId.COMPATIBLE_STRUCT
            or type_id == <uint8_t>TypeId.NAMED_COMPATIBLE_STRUCT
        ):
            self.write_shared_type_meta(write_context, typeinfo)
            return
        if Fory_IsInternalTypeId(type_id):
            return
        reg_kind = get_type_registration_kind(<TypeId>type_id)
        if reg_kind == TypeRegistrationKind.BY_ID:
            if typeinfo.user_type_id == <uint32_t>NO_USER_TYPE_ID:
                raise TypeError(f"user_type_id required for type_id {type_id}")
            write_context.write_var_uint32(typeinfo.user_type_id)
            return
        if reg_kind == TypeRegistrationKind.BY_NAME:
            if self.meta_share:
                self.write_shared_type_meta(write_context, typeinfo)
            else:
                write_context.meta_string_writer.write_encoded_meta_string(
                    write_context.buffer, typeinfo.namespace_bytes
                )
                write_context.meta_string_writer.write_encoded_meta_string(
                    write_context.buffer, typeinfo.typename_bytes
                )

    cpdef inline TypeInfo read_type_info(self, ReadContext read_context):
        cdef Buffer buffer = read_context.buffer
        cdef uint8_t type_id = buffer.read_uint8()
        cdef TypeRegistrationKind reg_kind
        cdef uint32_t user_type_id
        cdef object ns_metabytes
        cdef object type_metabytes
        cdef PyObject * typeinfo_ptr = NULL
        if (
            type_id == <uint8_t>TypeId.COMPATIBLE_STRUCT
            or type_id == <uint8_t>TypeId.NAMED_COMPATIBLE_STRUCT
        ):
            return self.read_shared_type_meta(read_context, type_id=type_id)
        if Fory_IsInternalTypeId(type_id):
            # Hot type-id reads must stay on the C caches. Internal ids are
            # populated during resolver initialization, so falling back into the
            # Python resolver here would add avoidable overhead to every nested
            # collection/struct read.
            if type_id >= self._c_registered_id_to_type_info.size():
                raise ValueError(f"Unexpected type_id {type_id}")
            typeinfo_ptr = self._c_registered_id_to_type_info[type_id]
            if typeinfo_ptr == NULL:
                raise ValueError(f"Unexpected type_id {type_id}")
            return <TypeInfo>typeinfo_ptr
        reg_kind = get_type_registration_kind(<TypeId>type_id)
        if reg_kind == TypeRegistrationKind.BY_NAME:
            if self.meta_share:
                return self.read_shared_type_meta(read_context, type_id=type_id)
            ns_metabytes = read_context.meta_string_reader.read_encoded_meta_string(
                buffer
            )
            type_metabytes = read_context.meta_string_reader.read_encoded_meta_string(
                buffer
            )
            return self._load_bytes_to_type_info(ns_metabytes, type_metabytes)
        if reg_kind == TypeRegistrationKind.BY_ID:
            user_type_id = buffer.read_var_uint32()
            typeinfo_ptr = self._c_user_type_id_to_type_info[user_type_id]
            if typeinfo_ptr == NULL:
                raise ValueError(f"Unexpected user_type_id {user_type_id}")
            return <TypeInfo>typeinfo_ptr
        if type_id >= self._c_registered_id_to_type_info.size():
            raise ValueError(f"Unexpected type_id {type_id}")
        typeinfo_ptr = self._c_registered_id_to_type_info[type_id]
        if typeinfo_ptr == NULL:
            raise ValueError(f"Unexpected type_id {type_id}")
        return <TypeInfo>typeinfo_ptr

    def is_registered_by_id(self, cls=None, type_id=None, user_type_id=NO_USER_TYPE_ID):
        return self.resolver.is_registered_by_id(
            cls=cls,
            type_id=type_id,
            user_type_id=user_type_id,
        )

    cdef _populate_type_info(self, TypeInfo typeinfo):
        cdef uint8_t type_id = typeinfo.type_id
        if (
            type_id == <uint8_t>TypeId.ENUM
            or type_id == <uint8_t>TypeId.STRUCT
            or type_id == <uint8_t>TypeId.COMPATIBLE_STRUCT
            or type_id == <uint8_t>TypeId.EXT
            or type_id == <uint8_t>TypeId.TYPED_UNION
        ):
            if typeinfo.user_type_id != NO_USER_TYPE_ID:
                self._c_user_type_id_to_type_info[typeinfo.user_type_id] = <PyObject *> typeinfo
        else:
            if type_id >= self._c_registered_id_to_type_info.size():
                self._c_registered_id_to_type_info.resize(type_id * 2 if type_id > 0 else 1, NULL)
            if type_id > 0 and not is_namespaced_type(<TypeId>type_id):
                self._c_registered_id_to_type_info[type_id] = <PyObject *> typeinfo
        self._c_types_info[<uintptr_t> <PyObject *> typeinfo.cls] = <PyObject *> typeinfo
        if self._c_types_info.size() * 10 >= self._c_types_info.bucket_count() * 5:
            self._c_types_info.rehash(self._c_types_info.size() * 2)
        if typeinfo.typename_bytes is not None:
            self._c_meta_hash_to_type_info[
                pair[int64_t, int64_t](
                    typeinfo.namespace_bytes.hashcode,
                    typeinfo.typename_bytes.hashcode,
                )
            ] = <PyObject *>typeinfo

    cpdef inline write_shared_type_meta(self, WriteContext write_context, TypeInfo typeinfo):
        cdef MetaShareWriteContext meta_context = write_context.meta_share_context
        cdef object type_cls
        cdef uint8_t type_id
        cdef object type_def
        cdef uint64_t type_addr
        cdef flat_hash_map[uint64_t, int32_t].iterator it
        cdef int32_t index
        if meta_context is None:
            raise AssertionError(
                "Meta share write context must be set when compatible mode is enabled"
            )
        type_cls = typeinfo.cls
        type_id = typeinfo.type_id
        if not is_type_share_meta(<TypeId>type_id):
            write_context.write_var_uint32(0)
            write_context.write_bytes(typeinfo.type_def.encoded)
            return
        type_addr = <uint64_t> <PyObject *> type_cls
        it = meta_context.class_map.find(type_addr)
        if it != meta_context.class_map.end():
            write_context.write_var_uint32((deref(it).second << 1) | 1)
            return
        index = meta_context.class_map.size()
        meta_context.class_map[type_addr] = index
        write_context.write_var_uint32(index << 1)
        type_def = typeinfo.type_def
        if type_def is None:
            self.resolver._set_type_info(typeinfo)
            type_def = typeinfo.type_def
        write_context.write_bytes(type_def.encoded)

    cpdef inline TypeInfo read_shared_type_meta(self, ReadContext read_context, type_id=None):
        cdef MetaShareReadContext meta_context = read_context.meta_share_context
        cdef uint32_t index_marker
        cdef uint32_t index
        cdef TypeInfo typeinfo
        if meta_context is None:
            raise AssertionError(
                "Meta share read context must be set when compatible mode is enabled"
            )
        if type_id is None:
            type_id = read_context.read_uint8()
        index_marker = read_context.read_var_uint32()
        index = index_marker >> 1
        if index_marker & 1:
            return meta_context.read_type_infos[index]
        typeinfo = self._read_and_build_type_info(read_context.buffer)
        meta_context.read_type_infos.append(typeinfo)
        return typeinfo

    cdef inline TypeInfo _read_and_build_type_info(self, Buffer buffer):
        cdef int64_t header = buffer.read_int64()
        cdef TypeInfo typeinfo = self._meta_shared_type_info.get(header)
        cdef object type_def
        if typeinfo is not None:
            _skip_typedef_fast(buffer, header)
            return typeinfo
        type_def = decode_typedef(buffer, self.resolver, header=header)
        typeinfo = self.resolver._build_type_info_from_typedef(type_def)
        self._meta_shared_type_info[header] = typeinfo
        return typeinfo

    cdef inline TypeInfo _load_bytes_to_type_info(self, object ns_metabytes, object type_metabytes):
        cdef PyObject * typeinfo_ptr = self._c_meta_hash_to_type_info[
            pair[int64_t, int64_t](
                ns_metabytes.hashcode,
                type_metabytes.hashcode,
            )
        ]
        cdef TypeInfo typeinfo
        if typeinfo_ptr != NULL:
            return <TypeInfo>typeinfo_ptr
        typeinfo = self.resolver._load_metabytes_to_type_info(ns_metabytes, type_metabytes)
        self._c_meta_hash_to_type_info[
            pair[int64_t, int64_t](
                ns_metabytes.hashcode,
                type_metabytes.hashcode,
            )
        ] = <PyObject *>typeinfo
        return typeinfo

cdef inline void _skip_typedef_fast(Buffer buffer, int64_t header):
    cdef int32_t meta_size = <int32_t>(header & 0xFF)
    cdef int32_t reader_index
    if meta_size == 0xFF:
        meta_size += buffer.read_var_uint32()
    if buffer.has_input_stream():
        buffer.read_bytes(meta_size)
        return
    reader_index = buffer.get_reader_index()
    buffer.check_bound(reader_index, meta_size)
    buffer.set_reader_index(reader_index + meta_size)


namespace_decoder = MetaStringDecoder(".", "_")
typename_decoder = MetaStringDecoder("$", "_")

include "buffer.pxi"


cdef inline object _wrap_buffer(shared_ptr[CBuffer] c_buffer):
    return Buffer.wrap(c_buffer)


cdef class Serializer:
    """
    Base serializer contract for the active Cython runtime.

    Concrete Cython serializers and Python serializers used in Cython mode both
    implement this API: `write(write_context, value)` and `read(read_context)`.
    Serializers must remain stateless with respect to one serialization call and
    must not retain `Fory`, `WriteContext`, or `ReadContext`.
    """

    cdef readonly TypeResolver type_resolver
    cdef readonly object type_
    cdef public bint need_to_write_ref

    def __init__(self, TypeResolver type_resolver, type_: Union[type, TypeVar]):
        """
        Initialize a serializer for one declared Python type.

        Args:
            type_resolver: Active Cython resolver for type lookup and configuration.
            type_: Declared Python type handled by this serializer.
        """
        self.type_resolver = type_resolver
        self.type_ = type_
        self.need_to_write_ref = self.type_resolver.track_ref and not is_primitive_type(type_)

    cpdef write(self, WriteContext write_context, value):
        raise NotImplementedError(f"write method not implemented in {type(self)}")

    cpdef read(self, ReadContext read_context):
        raise NotImplementedError(f"read method not implemented in {type(self)}")


    @classmethod
    def support_subclass(cls) -> bool:
        return False


@cython.final
cdef class EnumSerializer(Serializer):
    cdef tuple _members
    cdef dict _wire_value_by_member
    cdef dict _member_by_wire_value

    def __init__(self, TypeResolver type_resolver, type_):
        cdef dict explicit_wire_values
        cdef bint use_explicit_ids
        cdef object member
        cdef object raw_value
        cdef uint32_t wire_value
        super().__init__(type_resolver, type_)
        self.need_to_write_ref = False
        self._members = tuple(type_)
        self._wire_value_by_member = {member: idx for idx, member in enumerate(self._members)}
        self._member_by_wire_value = {idx: member for idx, member in enumerate(self._members)}
        if type_resolver.xlang:
            explicit_wire_values = {}
            use_explicit_ids = True
            for member in self._members:
                raw_value = member.value
                if isinstance(raw_value, bool) or not isinstance(raw_value, int) or raw_value < 0:
                    use_explicit_ids = False
                    break
                wire_value = raw_value
                if wire_value in explicit_wire_values:
                    use_explicit_ids = False
                    break
                explicit_wire_values[wire_value] = member
            if use_explicit_ids:
                self._wire_value_by_member = {
                    member: int(member.value) for member in self._members
                }
                self._member_by_wire_value = explicit_wire_values

    @classmethod
    def support_subclass(cls) -> bool:
        return True

    cpdef inline write(self, WriteContext write_context, value):
        write_context.write_var_uint32(self._wire_value_by_member[value])

    cpdef inline read(self, ReadContext read_context):
        cdef uint32_t wire_value = read_context.read_var_uint32()
        cdef object value = self._member_by_wire_value.get(wire_value)
        if value is None:
            raise ValueError(
                f"Unknown enum value {wire_value} for {self.type_.__qualname__}"
            )
        return value


@cython.final
cdef class SliceSerializer(Serializer):
    cpdef inline write(self, WriteContext write_context, v):
        cdef slice value = v
        start, stop, step = value.start, value.stop, value.step
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

    cpdef inline read(self, ReadContext read_context):
        cdef object start
        cdef object stop
        cdef object step
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
        return slice(start, stop, step)


@cython.final
cdef class TypeInfo:
    cdef public object cls
    cdef public uint8_t type_id
    cdef public uint32_t user_type_id
    cdef public Serializer serializer
    cdef public object namespace_bytes
    cdef public object typename_bytes
    cdef public bint dynamic_type
    cdef public object type_def

    def __init__(
        self,
        cls: Union[type, TypeVar] = None,
        type_id: int = 0,
        user_type_id: int = 0xFFFFFFFF,
        serializer=None,
        namespace_bytes=None,
        typename_bytes=None,
        dynamic_type: bool = False,
        type_def: object = None,
    ):
        self.cls = cls
        self.type_id = <uint8_t>(0 if type_id is None or type_id < 0 else type_id)
        self.user_type_id = <uint32_t>user_type_id
        self.serializer = serializer
        self.namespace_bytes = namespace_bytes
        self.typename_bytes = typename_bytes
        self.dynamic_type = dynamic_type
        self.type_def = type_def

    def __repr__(self):
        return (
            f"TypeInfo(cls={self.cls}, type_id={self.type_id}, "
            f"user_type_id={self.user_type_id}, serializer={self.serializer})"
        )

    cpdef str decode_namespace(self):
        if self.namespace_bytes is None:
            return ""
        return self.namespace_bytes.decode(namespace_decoder)

    cpdef str decode_typename(self):
        if self.typename_bytes is None:
            return ""
        return self.typename_bytes.decode(typename_decoder)


include "context.pxi"

@cython.final
cdef class Fory:
    """
    High-performance serialization facade for the active Cython runtime.

    `Fory` owns the immutable runtime config, the Python registration resolver,
    the Cython resolver cache, and one reusable read/write context pair. It is
    the root entry point for top-level serialize/deserialize operations; nested
    runtime state lives on `WriteContext` and `ReadContext`.
    """

    cdef public bint xlang
    cdef public bint track_ref
    cdef public bint strict
    cdef public bint compatible
    cdef public bint field_nullable
    cdef public int32_t max_depth
    cdef public object policy
    cdef public int32_t max_collection_size
    cdef public int32_t max_binary_size
    cdef public Config config
    cdef public TypeResolver type_resolver
    cdef public WriteContext write_context
    cdef public ReadContext read_context
    cdef public Buffer buffer

    def __init__(
        self,
        xlang=False,
        ref=False,
        strict=True,
        compatible=False,
        max_depth=50,
        policy=None,
        field_nullable=False,
        meta_compressor=None,
        max_collection_size=1_000_000,
        max_binary_size=64 * 1024 * 1024,
    ):
        """
        Initialize a Cython-backed Fory runtime instance.

        Args:
            xlang: Enable cross-language serialization mode.
            ref: Enable reference tracking for shared and circular references.
            strict: Require registered types on dynamic resolution paths.
            compatible: Enable compatible mode and meta-share type exchange.
            max_depth: Maximum allowed read depth before rejecting payloads.
            policy: Optional deserialization policy implementation.
            field_nullable: Treat struct fields as nullable by default.
            meta_compressor: Optional typedef/meta compressor implementation.
            max_collection_size: Maximum allowed declared collection/map size.
            max_binary_size: Maximum allowed binary payload size for one read.
        """
        self.xlang = xlang
        self.track_ref = ref
        self.strict = strict
        if strict is not True:
            from pyfory._fory import _ENABLE_TYPE_REGISTRATION_FORCIBLY

            if _ENABLE_TYPE_REGISTRATION_FORCIBLY:
                self.strict = True
        self.policy = DEFAULT_POLICY if policy is None else policy
        self.compatible = compatible
        self.field_nullable = field_nullable
        self.max_depth = max_depth
        self.max_collection_size = max_collection_size
        self.max_binary_size = max_binary_size
        self.config = Config(
            xlang=xlang,
            track_ref=ref,
            strict=self.strict,
            compatible=compatible,
            meta_share=compatible,
            scoped_meta_share_enabled=compatible,
            max_depth=max_depth,
            field_nullable=field_nullable,
            policy=self.policy,
            meta_compressor=meta_compressor,
            max_collection_size=max_collection_size,
            max_binary_size=max_binary_size,
        )
        from pyfory.registry import SharedRegistry

        shared_registry = SharedRegistry()
        self.type_resolver = TypeResolver(
            self.config,
            shared_registry=shared_registry,
        )
        self.type_resolver.initialize()
        self.write_context = WriteContext(self.config, self.type_resolver)
        self.read_context = ReadContext(self.config, self.type_resolver)
        self.buffer = Buffer.allocate(32, max_binary_size=max_binary_size)

    def register(
        self,
        cls,
        *,
        type_id=None,
        namespace=None,
        typename=None,
        serializer=None,
    ):
        self.register_type(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

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
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

    def register_union(
        self,
        cls: Union[type, TypeVar],
        *,
        type_id: int = None,
        namespace: str = None,
        typename: str = None,
        serializer=None,
    ):
        self.type_resolver.register_union(
            cls,
            type_id=type_id,
            namespace=namespace,
            typename=typename,
            serializer=serializer,
        )

    def register_serializer(self, cls, serializer):
        self.type_resolver.register_serializer(cls, serializer)

    def dumps(
        self,
        obj,
        buffer=None,
        buffer_callback=None,
        unsupported_callback=None,
    ):
        return self.serialize(
            obj,
            buffer=buffer,
            buffer_callback=buffer_callback,
            unsupported_callback=unsupported_callback,
        )

    def dump(self, obj, stream):
        try:
            self.buffer.set_writer_index(0)
            self.buffer.bind_output_stream(Buffer.wrap_output_stream(stream))
            self._serialize(
                obj,
                self.buffer,
                buffer_callback=None,
                unsupported_callback=None,
            )
            self.force_flush()
        finally:
            self.buffer.bind_output_stream(None)
            self.reset_write()

    def loads(self, buffer, buffers=None, unsupported_objects=None):
        return self.deserialize(
            buffer,
            buffers=buffers,
            unsupported_objects=unsupported_objects,
        )

    def serialize(self, obj, Buffer buffer=None, buffer_callback=None, unsupported_callback=None):
        cdef Buffer write_buffer
        try:
            write_buffer = self._serialize(
                obj,
                buffer,
                buffer_callback=buffer_callback,
                unsupported_callback=unsupported_callback,
            )
            if write_buffer is not self.buffer:
                return write_buffer
            if write_buffer.get_output_stream() is not None:
                return write_buffer
            return write_buffer.to_bytes(0, write_buffer.get_writer_index())
        finally:
            self.reset_write()

    cdef Buffer _serialize(self, obj, Buffer buffer=None, buffer_callback=None, unsupported_callback=None):
        cdef WriteContext write_context = self.write_context
        cdef int32_t mask_index
        if buffer is None:
            self.buffer.set_writer_index(0)
            buffer = self.buffer
        # Keep the root context setup inline. Top-level serialize is a hot path,
        # so it should not pay an extra method call just to bind the active buffer.
        write_context.buffer = buffer
        write_context.c_buffer = buffer.c_buffer
        write_context.buffer_callback = buffer_callback
        write_context.unsupported_callback = unsupported_callback
        write_context.depth = 0
        mask_index = buffer.get_writer_index()
        buffer.grow(1)
        buffer.set_writer_index(mask_index + 1)
        buffer.put_int8(mask_index, 0)
        if obj is None:
            set_bit(buffer, mask_index, 0)
        else:
            clear_bit(buffer, mask_index, 0)
        set_bit(buffer, mask_index, 1)
        if buffer_callback is not None:
            set_bit(buffer, mask_index, 2)
        else:
            clear_bit(buffer, mask_index, 2)
        write_context.write_ref(obj)
        return buffer

    def deserialize(self, buffer, buffers=None, unsupported_objects=None):
        try:
            return self._deserialize(
                buffer,
                buffers=buffers,
                unsupported_objects=unsupported_objects,
            )
        finally:
            self.reset_read()

    cdef object _deserialize(self, buffer, buffers=None, unsupported_objects=None):
        cdef ReadContext read_context = self.read_context
        cdef Buffer read_buffer
        cdef int32_t reader_index
        cdef bint peer_out_of_band_enabled
        if isinstance(buffer, bytes):
            buffer = Buffer(buffer, max_binary_size=self.max_binary_size)
        read_buffer = buffer
        reader_index = read_buffer.get_reader_index()
        read_buffer.set_reader_index(reader_index + 1)
        if get_bit(read_buffer, reader_index, 0):
            return None
        peer_out_of_band_enabled = get_bit(read_buffer, reader_index, 2)
        if peer_out_of_band_enabled:
            assert buffers is not None, (
                "buffers shouldn't be null when the serialized stream is produced with buffer_callback not null."
            )
        else:
            assert buffers is None, (
                "buffers should be null when the serialized stream is produced with buffer_callback null."
            )
        # Keep the root context setup inline. Top-level deserialize is a hot path,
        # so it should not pay an extra method call just to bind the active buffer.
        read_context.buffer = read_buffer
        read_context.c_buffer = read_buffer.c_buffer
        read_context.buffers = iter(buffers) if buffers is not None else None
        read_context.unsupported_objects = (
            iter(unsupported_objects) if unsupported_objects is not None else None
        )
        read_context.peer_out_of_band_enabled = peer_out_of_band_enabled
        read_context.depth = 0
        return read_context.read_ref()

    cpdef enter_flush_barrier(self):
        self.write_context.enter_flush_barrier()

    cpdef exit_flush_barrier(self):
        self.write_context.exit_flush_barrier()

    cpdef try_flush(self):
        self.write_context.try_flush()

    cpdef force_flush(self):
        self.write_context.force_flush()

    cpdef reset_write(self):
        self.write_context.reset()

    cpdef reset_read(self):
        self.read_context.reset()

    cpdef reset(self):
        self.reset_write()
        self.reset_read()

include "primitive.pxi"
include "float16.pxi"
include "bfloat16.pxi"
include "collection.pxi"
include "struct.pxi"


cpdef inline write_nullable_pybool(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_bool(value)


cpdef inline write_nullable_int8(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_int8(value)


cpdef inline write_nullable_int16(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_int16(value)


cpdef inline write_nullable_int32(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_varint32(value)


cpdef inline write_nullable_pyint64(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_varint64(value)


cpdef inline write_nullable_float32(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_float32(value)


cpdef inline write_nullable_pyfloat64(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_double(value)


cpdef inline write_nullable_pystr(buffer, value):
    if value is None:
        buffer.write_int8(NULL_FLAG)
    else:
        buffer.write_int8(NOT_NULL_VALUE_FLAG)
        buffer.write_string(value)


cpdef inline read_nullable_pybool(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_bool()
    return None


cpdef inline read_nullable_int8(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_int8()
    return None


cpdef inline read_nullable_int16(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_int16()
    return None


cpdef inline read_nullable_int32(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_varint32()
    return None


cpdef inline read_nullable_pyint64(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_varint64()
    return None


cpdef inline read_nullable_float32(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_float32()
    return None


cpdef inline read_nullable_pyfloat64(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_double()
    return None


cpdef inline read_nullable_pystr(buffer):
    if buffer.read_int8() == NOT_NULL_VALUE_FLAG:
        return buffer.read_string()
    return None
