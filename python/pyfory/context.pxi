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

from pyfory.context import EncodedMetaString, EMPTY_ENCODED_META_STRING
from pyfory.resolver import NULL_FLAG, REF_FLAG, NOT_NULL_VALUE_FLAG, REF_VALUE_FLAG


INT64_TYPE_ID = TypeId.VARINT64
FLOAT64_TYPE_ID = TypeId.FLOAT64
BOOL_TYPE_ID = TypeId.BOOL
STRING_TYPE_ID = TypeId.STRING
SMALL_STRING_THRESHOLD = 16


cdef inline uint64_t _mix64(uint64_t x):
    x ^= x >> 33
    x *= <uint64_t> 0xff51afd7ed558ccd
    x ^= x >> 33
    x *= <uint64_t> 0xc4ceb9fe1a85ec53
    x ^= x >> 33
    return x


cdef inline int64_t _hash_small_metastring(
    int64_t v1,
    int64_t v2,
    int32_t length,
    uint8_t encoding,
):
    cdef uint64_t k = <uint64_t> 0x9e3779b97f4a7c15
    cdef uint64_t x = (<uint64_t> v1) ^ ((<uint64_t> v2) * k)
    x ^= (<uint64_t> length) << 56
    cdef uint64_t h = _mix64(x)
    h = (h & <uint64_t> 0xffffffffffffff00) | encoding
    return <int64_t> h


cdef class WriteContext
cdef class ReadContext


@cython.final
cdef class RefWriter:
    cdef flat_hash_map[uint64_t, int32_t] written_objects_id
    cdef vector[PyObject *] written_objects
    cdef readonly bint track_ref

    def __cinit__(self, bint track_ref):
        self.track_ref = track_ref

    def __dealloc__(self):
        self.reset()

    cdef inline bint write_ref_or_null(self, CBuffer * c_buffer, obj):
        cdef uint64_t object_id
        cdef int32_t next_id
        cdef flat_hash_map[uint64_t, int32_t].iterator it
        if not self.track_ref:
            if obj is None:
                deref(c_buffer).write_int8(NULL_FLAG)
                return True
            deref(c_buffer).write_int8(NOT_NULL_VALUE_FLAG)
            return False
        if obj is None:
            deref(c_buffer).write_int8(NULL_FLAG)
            return True
        object_id = <uintptr_t> <PyObject *> obj
        it = self.written_objects_id.find(object_id)
        if it == self.written_objects_id.end():
            next_id = self.written_objects_id.size()
            self.written_objects_id[object_id] = next_id
            self.written_objects.push_back(<PyObject *> obj)
            Py_INCREF(obj)
            deref(c_buffer).write_int8(REF_VALUE_FLAG)
            return False
        deref(c_buffer).write_int8(REF_FLAG)
        deref(c_buffer).write_var_uint32(<uint64_t> deref(it).second)
        return True

    cdef inline bint write_ref_value_flag(self, CBuffer * c_buffer, obj):
        cdef uint64_t object_id
        cdef int32_t next_id
        cdef flat_hash_map[uint64_t, int32_t].iterator it
        assert obj is not None
        if not self.track_ref:
            deref(c_buffer).write_int8(NOT_NULL_VALUE_FLAG)
            return True
        object_id = <uintptr_t> <PyObject *> obj
        it = self.written_objects_id.find(object_id)
        if it == self.written_objects_id.end():
            next_id = self.written_objects_id.size()
            self.written_objects_id[object_id] = next_id
            self.written_objects.push_back(<PyObject *> obj)
            Py_INCREF(obj)
            deref(c_buffer).write_int8(REF_VALUE_FLAG)
            return True
        deref(c_buffer).write_int8(REF_FLAG)
        deref(c_buffer).write_var_uint32(<uint64_t> deref(it).second)
        return False

    cdef inline bint write_null_flag(self, CBuffer * c_buffer, obj):
        if obj is None:
            deref(c_buffer).write_int8(NULL_FLAG)
            return True
        return False

    cpdef inline reset(self):
        cdef PyObject *item
        if not self.track_ref:
            return
        self.written_objects_id.clear()
        for item in self.written_objects:
            Py_XDECREF(item)
        self.written_objects.clear()


@cython.final
cdef class RefReader:
    cdef vector[PyObject *] read_objects
    cdef vector[int32_t] read_ref_ids
    # Keep the last resolved reference in its own object slot so hot REF_FLAG
    # reads can return it directly instead of probing read_objects again.
    cdef object read_object
    cdef readonly bint track_ref

    def __cinit__(self, bint track_ref):
        self.track_ref = track_ref
        self.read_object = None

    def __dealloc__(self):
        self.reset()

    cdef inline int32_t read_ref_or_null(self, Buffer buffer):
        cdef int8_t head_flag = buffer.c_buffer.read_int8(buffer._error)
        cdef int32_t ref_id
        cdef int32_t size
        cdef PyObject *obj
        if not self.track_ref:
            return head_flag
        if head_flag == REF_FLAG:
            ref_id = buffer.c_buffer.read_var_uint32(buffer._error)
            size = self.read_objects.size()
            if ref_id < 0 or ref_id >= size:
                raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
            obj = self.read_objects[ref_id]
            if obj == NULL:
                raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
            self.read_object = <object>obj
            return REF_FLAG
        self.read_object = None
        return head_flag

    cdef inline int32_t preserve_next_ref_id(self):
        cdef int32_t ref_id
        if not self.track_ref:
            return -1
        ref_id = self.read_objects.size()
        self.read_objects.push_back(NULL)
        self.read_ref_ids.push_back(ref_id)
        return ref_id

    cdef inline int32_t preserve_ref_id(self, int32_t ref_id):
        if not self.track_ref:
            return -1
        self.read_ref_ids.push_back(ref_id)
        return ref_id

    cdef inline int32_t try_preserve_ref_id(self, Buffer buffer):
        cdef int8_t head_flag
        cdef int32_t ref_id
        cdef int32_t size
        cdef PyObject *obj
        if not self.track_ref:
            return buffer.c_buffer.read_int8(buffer._error)
        head_flag = buffer.c_buffer.read_int8(buffer._error)
        if head_flag == REF_FLAG:
            ref_id = buffer.c_buffer.read_var_uint32(buffer._error)
            size = self.read_objects.size()
            if ref_id < 0 or ref_id >= size:
                raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
            obj = self.read_objects[ref_id]
            if obj == NULL:
                raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
            self.read_object = <object>obj
            return head_flag
        self.read_object = None
        if head_flag == REF_VALUE_FLAG:
            return self.preserve_next_ref_id()
        self.read_ref_ids.push_back(-1)
        return head_flag

    cdef inline int32_t last_preserved_ref_id(self):
        cdef int32_t length
        if not self.track_ref:
            return -1
        length = self.read_ref_ids.size()
        assert length > 0
        return self.read_ref_ids[length - 1]

    cdef inline bint has_preserved_ref_id(self):
        if not self.track_ref:
            return False
        return self.read_ref_ids.size() != 0

    cdef inline reference(self, obj):
        cdef int32_t ref_id
        cdef bint need_inc
        if not self.track_ref:
            return
        ref_id = self.read_ref_ids.back()
        self.read_ref_ids.pop_back()
        if ref_id < 0:
            return
        need_inc = self.read_objects[ref_id] == NULL
        if need_inc:
            Py_INCREF(obj)
        self.read_objects[ref_id] = <PyObject *>obj

    cdef inline get_read_ref(self, id_=None):
        cdef int32_t ref_id
        cdef int32_t size
        cdef PyObject *obj
        if not self.track_ref:
            return None
        if id_ is None:
            return self.read_object
        ref_id = id_
        size = self.read_objects.size()
        if ref_id < 0 or ref_id >= size:
            raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
        obj = self.read_objects[ref_id]
        if obj == NULL:
            raise ValueError(f"Invalid ref id {ref_id}, current size {size}")
        return <object> obj

    cdef inline set_read_ref(self, int32_t ref_id, obj):
        if not self.track_ref:
            return
        if ref_id >= 0:
            # ref_id < 0 is the NOT_NULL_VALUE_FLAG sentinel path and has no
            # slot in read_objects. Referenceable containers/structs populate
            # their slot eagerly through reference(), so the follow-up store here
            # should only fill slots that are still empty.
            if self.read_objects[ref_id] == NULL:
                Py_INCREF(obj)
                self.read_objects[ref_id] = <PyObject *>obj

    cpdef inline reset(self):
        cdef PyObject *item
        if self.track_ref:
            for item in self.read_objects:
                Py_XDECREF(item)
            self.read_objects.clear()
            self.read_ref_ids.clear()
        self.read_object = None


@cython.final
cdef class MetaStringWriter:
    cdef flat_hash_map[uint64_t, int32_t] _written_encoded_meta_strings
    cdef vector[PyObject *] _written_objects

    def __dealloc__(self):
        self.reset()

    cpdef inline write_encoded_meta_string(self, Buffer buffer, encoded_meta_string):
        cdef uint64_t object_id = <uintptr_t> <PyObject *> encoded_meta_string
        cdef int32_t length = encoded_meta_string.length
        cdef int32_t dynamic_id
        cdef flat_hash_map[uint64_t, int32_t].iterator it = self._written_encoded_meta_strings.find(object_id)
        if it == self._written_encoded_meta_strings.end():
            dynamic_id = self._written_encoded_meta_strings.size()
            self._written_encoded_meta_strings[object_id] = dynamic_id
            self._written_objects.push_back(<PyObject *> encoded_meta_string)
            Py_INCREF(encoded_meta_string)
            buffer.write_var_uint32(length << 1)
            if length <= SMALL_STRING_THRESHOLD:
                if length != 0:
                    buffer.write_int8(encoded_meta_string.encoding)
            else:
                buffer.write_int64(encoded_meta_string.hashcode)
            buffer.write_bytes(encoded_meta_string.data)
            return
        buffer.write_var_uint32(((deref(it).second + 1) << 1) | 1)

    cpdef inline reset(self):
        cdef PyObject *item
        self._written_encoded_meta_strings.clear()
        for item in self._written_objects:
            Py_XDECREF(item)
        self._written_objects.clear()


@cython.final
cdef class MetaStringReader:
    cdef object shared_registry
    cdef vector[PyObject *] _c_dynamic_id_to_encoded_meta_string_vec
    cdef flat_hash_map[int64_t, PyObject *] _c_hash_to_encoded_meta_string
    cdef flat_hash_map[int64_t, PyObject *] _c_hash_to_small_encoded_meta_string

    def __init__(self, shared_registry):
        self.shared_registry = shared_registry

    cpdef inline read_encoded_meta_string(self, Buffer buffer):
        cdef int32_t header = buffer.read_var_uint32()
        cdef int32_t length = header >> 1
        cdef int64_t v1 = 0
        cdef int64_t v2 = 0
        cdef int64_t hashcode
        cdef PyObject *encoded_meta_string_ptr
        cdef int32_t reader_index
        cdef int8_t encoding = 0
        cdef bytes data
        cdef object encoded_meta_string
        if header & 0b1:
            if length <= 0:
                raise ValueError("Invalid dynamic metastring id 0")
            return <object> self._c_dynamic_id_to_encoded_meta_string_vec[length - 1]
        if length <= SMALL_STRING_THRESHOLD:
            if length == 0:
                encoded_meta_string_ptr = <PyObject *> EMPTY_ENCODED_META_STRING
                self._c_dynamic_id_to_encoded_meta_string_vec.push_back(encoded_meta_string_ptr)
                return <object> encoded_meta_string_ptr
            encoding = buffer.read_int8()
            if length <= 8:
                v1 = buffer.read_bytes_as_int64(length)
            else:
                v1 = buffer.read_int64()
                v2 = buffer.read_bytes_as_int64(length - 8)
            hashcode = _hash_small_metastring(v1, v2, length, <uint8_t> encoding)
            encoded_meta_string_ptr = self._c_hash_to_small_encoded_meta_string[hashcode]
            if encoded_meta_string_ptr == NULL:
                reader_index = buffer.get_reader_index()
                data = buffer.get_bytes(reader_index - length, length)
                encoded_meta_string = self.shared_registry.get_or_create_encoded_meta_string(
                    data,
                    hashcode,
                )
                encoded_meta_string_ptr = <PyObject *> encoded_meta_string
                self._c_hash_to_small_encoded_meta_string[hashcode] = encoded_meta_string_ptr
        else:
            hashcode = buffer.read_int64()
            reader_index = buffer.get_reader_index()
            buffer.check_bound(reader_index, length)
            buffer.set_reader_index(reader_index + length)
            encoded_meta_string_ptr = self._c_hash_to_encoded_meta_string[hashcode]
            if encoded_meta_string_ptr == NULL:
                data = buffer.get_bytes(reader_index, length)
                encoded_meta_string = self.shared_registry.get_or_create_encoded_meta_string(
                    data,
                    hashcode,
                )
                encoded_meta_string_ptr = <PyObject *> encoded_meta_string
                self._c_hash_to_encoded_meta_string[hashcode] = encoded_meta_string_ptr
        self._c_dynamic_id_to_encoded_meta_string_vec.push_back(encoded_meta_string_ptr)
        return <object> encoded_meta_string_ptr

    cpdef inline reset(self):
        self._c_dynamic_id_to_encoded_meta_string_vec.clear()


@cython.final
cdef class MetaShareWriteContext:
    cdef flat_hash_map[uint64_t, int32_t] class_map

    cpdef inline reset(self):
        self.class_map.clear()


@cython.final
cdef class MetaShareReadContext:
    cdef public list read_type_infos

    def __init__(self):
        self.read_type_infos = []

    cpdef inline reset(self):
        self.read_type_infos.clear()


@cython.final
cdef class WriteContext:
    """
    Per-operation serialization state for the active Cython runtime.
    """

    cdef readonly TypeResolver type_resolver
    cdef readonly bint xlang
    cdef readonly bint track_ref
    cdef readonly bint strict
    cdef readonly bint compatible
    cdef readonly bint field_nullable
    cdef readonly object policy
    cdef readonly int32_t max_collection_size
    cdef readonly int32_t max_binary_size
    cdef readonly RefWriter ref_writer
    cdef readonly MetaStringWriter meta_string_writer
    cdef readonly MetaShareWriteContext meta_share_context
    cdef public Buffer buffer
    cdef CBuffer * c_buffer
    cdef public object buffer_callback
    cdef public object unsupported_callback
    cdef dict context_objects
    cdef public int32_t depth

    def __init__(self, Config config, TypeResolver type_resolver):
        self.type_resolver = type_resolver
        self.xlang = config.xlang
        self.track_ref = config.track_ref
        self.strict = config.strict
        self.compatible = config.compatible
        self.field_nullable = config.field_nullable
        self.policy = config.policy
        self.max_collection_size = config.max_collection_size
        self.max_binary_size = config.max_binary_size
        self.ref_writer = RefWriter(self.track_ref)
        self.meta_string_writer = MetaStringWriter()
        self.meta_share_context = MetaShareWriteContext() if config.scoped_meta_share_enabled else None
        self.buffer = None
        self.c_buffer = NULL
        self.buffer_callback = None
        self.unsupported_callback = None
        self.context_objects = {}
        self.depth = 0

    cpdef inline prepare(self, Buffer buffer, buffer_callback=None, unsupported_callback=None):
        self.buffer = buffer
        self.c_buffer = buffer.c_buffer
        self.buffer_callback = buffer_callback
        self.unsupported_callback = unsupported_callback
        self.depth = 0

    cpdef inline reset(self):
        self.ref_writer.reset()
        self.meta_string_writer.reset()
        if self.meta_share_context is not None:
            self.meta_share_context.reset()
        if self.context_objects:
            self.context_objects.clear()
        self.buffer = None
        self.c_buffer = NULL
        self.buffer_callback = None
        self.unsupported_callback = None
        self.depth = 0

    cpdef inline add_context_object(self, key, obj):
        self.context_objects[id(key)] = obj

    cpdef inline bint has_context_object(self, key):
        return id(key) in self.context_objects

    cpdef inline get_context_object(self, key, default=None):
        return self.context_objects.get(id(key), default)

    cpdef inline increase_depth(self, int32_t diff=1):
        self.depth += diff

    cpdef inline decrease_depth(self, int32_t diff=1):
        self.depth -= diff

    cpdef inline bint write_ref_or_null(self, obj):
        return self.ref_writer.write_ref_or_null(self.c_buffer, obj)

    cpdef inline bint write_ref_value_flag(self, obj):
        return self.ref_writer.write_ref_value_flag(self.c_buffer, obj)

    cpdef inline bint write_null_flag(self, obj):
        return self.ref_writer.write_null_flag(self.c_buffer, obj)

    cpdef inline write_ref(self, obj, TypeInfo typeinfo=None, Serializer serializer=None):
        if serializer is None and typeinfo is not None:
            serializer = typeinfo.serializer
        if serializer is None or serializer.need_to_write_ref:
            if self.ref_writer.write_ref_or_null(self.c_buffer, obj):
                return
            self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)
            return
        if obj is None:
            self.write_int8(NULL_FLAG)
            return
        self.write_int8(NOT_NULL_VALUE_FLAG)
        self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)

    cpdef inline write_non_ref(self, obj, Serializer serializer=None, TypeInfo typeinfo=None):
        cdef object cls
        cdef TypeInfo c_typeinfo
        if serializer is not None:
            serializer.write(self, obj)
            return
        cls = type(obj)
        if cls is str:
            self.write_uint8(STRING_TYPE_ID)
            self.buffer.write_string(obj)
            return
        if cls is int:
            self.write_uint8(INT64_TYPE_ID)
            self.write_varint64(obj)
            return
        if cls is bool:
            self.write_uint8(BOOL_TYPE_ID)
            self.write_bool(obj)
            return
        if cls is float:
            self.write_uint8(FLOAT64_TYPE_ID)
            self.write_double(obj)
            return
        if typeinfo is None:
            typeinfo = self.type_resolver.get_type_info(cls)
        c_typeinfo = <TypeInfo> typeinfo
        self.type_resolver.write_type_info(self, c_typeinfo)
        c_typeinfo.serializer.write(self, obj)

    cpdef inline write_no_ref(self, obj, Serializer serializer=None, TypeInfo typeinfo=None):
        self.write_non_ref(obj, serializer=serializer, typeinfo=typeinfo)

    cpdef write_buffer_object(self, buffer_object):
        cdef int32_t size
        cdef int32_t writer_index
        cdef Buffer buf
        if self.buffer_callback is None:
            size = buffer_object.total_bytes()
            self.write_var_uint32(size)
            writer_index = self.buffer.get_writer_index()
            self.buffer.ensure(writer_index + size)
            buf = self.buffer.slice(writer_index, size)
            buffer_object.write_to(buf)
            self.buffer.set_writer_index(writer_index + size)
            return
        if self.buffer_callback(buffer_object):
            self.write_bool(True)
            size = buffer_object.total_bytes()
            self.write_var_uint32(size)
            writer_index = self.buffer.get_writer_index()
            self.buffer.ensure(writer_index + size)
            buf = self.buffer.slice(writer_index, size)
            buffer_object.write_to(buf)
            self.buffer.set_writer_index(writer_index + size)
            return
        self.write_bool(False)

    cpdef handle_unsupported_write(self, obj):
        if self.unsupported_callback is None or self.unsupported_callback(obj):
            raise NotImplementedError(f"{type(obj)} is not supported for write")

    cpdef enter_flush_barrier(self):
        cdef object output_stream = None if self.buffer is None else self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.enter_flush_barrier()

    cpdef exit_flush_barrier(self):
        cdef object output_stream = None if self.buffer is None else self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.exit_flush_barrier()

    cpdef try_flush(self):
        cdef object output_stream
        if self.buffer is None or self.buffer.get_writer_index() <= 4096:
            return
        output_stream = self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.try_flush()

    cpdef force_flush(self):
        cdef object output_stream
        if self.buffer is None:
            return
        output_stream = self.buffer.get_output_stream()
        if output_stream is not None:
            output_stream.force_flush()

    cpdef inline void write_bool(self, bint value):
        self.c_buffer.write_uint8(<uint8_t> value)

    cpdef inline void write_int8(self, int8_t value):
        self.c_buffer.write_int8(value)

    cpdef inline void write_uint8(self, uint8_t value):
        self.c_buffer.write_uint8(value)

    cpdef inline void write_int16(self, int16_t value):
        self.c_buffer.write_int16(value)

    cpdef inline void write_uint16(self, uint16_t value):
        self.c_buffer.write_uint16(value)

    cpdef inline void write_int32(self, int32_t value):
        self.c_buffer.write_int32(value)

    cpdef inline void write_uint32(self, uint32_t value):
        self.c_buffer.write_uint32(value)

    cpdef inline void write_int64(self, int64_t value):
        self.c_buffer.write_int64(value)

    cpdef inline void write_uint64(self, uint64_t value):
        self.c_buffer.write_int64(<int64_t> value)

    cpdef inline void write_varint32(self, int32_t value):
        self.c_buffer.write_var_int32(value)

    cpdef inline void write_var_uint32(self, uint32_t value):
        self.c_buffer.write_var_uint32(value)

    cpdef inline void write_varint64(self, int64_t value):
        self.c_buffer.write_var_int64(value)

    cpdef inline void write_var_uint64(self, uint64_t value):
        self.c_buffer.write_var_uint64(value)

    cpdef inline void write_tagged_int64(self, int64_t value):
        self.c_buffer.write_tagged_int64(value)

    cpdef inline void write_tagged_uint64(self, uint64_t value):
        self.c_buffer.write_tagged_uint64(value)

    cpdef inline void write_float(self, float value):
        self.c_buffer.write_float(value)

    cpdef inline void write_float32(self, float value):
        self.c_buffer.write_float(value)

    cpdef inline void write_double(self, double value):
        self.c_buffer.write_double(value)

    cpdef inline void write_float64(self, double value):
        self.c_buffer.write_double(value)

    cpdef write_string(self, str value):
        self.buffer.write_string(value)

    cpdef write_bytes(self, bytes value):
        self.buffer.write_bytes(value)

    cpdef inline void write_bytes_and_size(self, bytes value):
        self.buffer.write_bytes_and_size(value)

    cpdef write_buffer(self, value, src_index=0, length_=None):
        self.buffer.write_buffer(value, src_index=src_index, length_=length_)

    cpdef inline int32_t get_writer_index(self):
        return self.buffer.get_writer_index()

    cpdef inline put_uint8(self, uint32_t offset, uint8_t value):
        self.buffer.put_uint8(offset, value)


@cython.final
cdef class ReadContext:
    """
    Per-operation deserialization state for the active Cython runtime.
    """

    cdef readonly TypeResolver type_resolver
    cdef readonly bint xlang
    cdef readonly bint track_ref
    cdef readonly bint strict
    cdef readonly bint compatible
    cdef readonly bint field_nullable
    cdef readonly object policy
    cdef readonly int32_t max_depth
    cdef readonly int32_t max_collection_size
    cdef readonly int32_t max_binary_size
    cdef readonly RefReader ref_reader
    cdef readonly MetaStringReader meta_string_reader
    cdef readonly MetaShareReadContext meta_share_context
    cdef public Buffer buffer
    cdef CBuffer * c_buffer
    cdef public object buffers
    cdef public object unsupported_objects
    cdef public bint peer_out_of_band_enabled
    cdef dict context_objects
    cdef public int32_t depth

    def __init__(self, Config config, TypeResolver type_resolver):
        self.type_resolver = type_resolver
        self.xlang = config.xlang
        self.track_ref = config.track_ref
        self.strict = config.strict
        self.compatible = config.compatible
        self.field_nullable = config.field_nullable
        self.policy = config.policy
        self.max_depth = config.max_depth
        self.max_collection_size = config.max_collection_size
        self.max_binary_size = config.max_binary_size
        self.ref_reader = RefReader(self.track_ref)
        self.meta_string_reader = MetaStringReader(self.type_resolver.shared_registry)
        self.meta_share_context = MetaShareReadContext() if config.scoped_meta_share_enabled else None
        self.buffer = None
        self.c_buffer = NULL
        self.buffers = None
        self.unsupported_objects = None
        self.peer_out_of_band_enabled = False
        self.context_objects = {}
        self.depth = 0

    cpdef inline prepare(
        self,
        Buffer buffer,
        buffers=None,
        unsupported_objects=None,
        bint peer_out_of_band_enabled=False,
    ):
        self.buffer = buffer
        self.c_buffer = buffer.c_buffer
        self.buffers = iter(buffers) if buffers is not None else None
        self.unsupported_objects = iter(unsupported_objects) if unsupported_objects is not None else None
        self.peer_out_of_band_enabled = peer_out_of_band_enabled
        self.depth = 0

    cpdef inline reset(self):
        self.ref_reader.reset()
        self.meta_string_reader.reset()
        if self.meta_share_context is not None:
            self.meta_share_context.reset()
        if self.context_objects:
            self.context_objects.clear()
        self.buffer = None
        self.c_buffer = NULL
        self.buffers = None
        self.unsupported_objects = None
        self.peer_out_of_band_enabled = False
        self.depth = 0

    cpdef inline add_context_object(self, key, obj):
        self.context_objects[id(key)] = obj

    cpdef inline bint has_context_object(self, key):
        return id(key) in self.context_objects

    cpdef inline get_context_object(self, key, default=None):
        return self.context_objects.get(id(key), default)

    cpdef inline increase_depth(self, int32_t diff=1):
        # Depth accounting is paired on the successful path only.
        # If a nested read raises, the top-level deserialize/reset path clears
        # `depth`, so nested readers must not add local try/finally wrappers
        # around increase/decrease pairs.
        self.depth += diff
        if self.depth > self.max_depth:
            raise Exception(
                f"Read depth exceed max depth: {self.depth}, the deserialization data may be malicious. "
                "If it's not malicious, please increase max read depth by Fory(..., max_depth=...)"
            )

    cpdef inline decrease_depth(self, int32_t diff=1):
        # Only call this after the matching nested read completed successfully.
        self.depth -= diff

    cpdef inline int32_t read_ref_or_null(self):
        return self.ref_reader.read_ref_or_null(self.buffer)

    cpdef inline int32_t preserve_ref_id(self, ref_id=None):
        if ref_id is None:
            return self.ref_reader.preserve_next_ref_id()
        return self.ref_reader.preserve_ref_id(ref_id)

    cpdef inline int32_t try_preserve_ref_id(self):
        return self.ref_reader.try_preserve_ref_id(self.buffer)

    cpdef inline int32_t last_preserved_ref_id(self):
        return self.ref_reader.last_preserved_ref_id()

    cpdef inline bint has_preserved_ref_id(self):
        return self.ref_reader.has_preserved_ref_id()

    cpdef inline reference(self, obj):
        self.ref_reader.reference(obj)

    cpdef inline get_read_ref(self, ref_id=None):
        return self.ref_reader.get_read_ref(ref_id)

    cpdef inline set_read_ref(self, int32_t ref_id, obj):
        self.ref_reader.set_read_ref(ref_id, obj)

    cpdef inline read_ref(self, Serializer serializer=None):
        cdef int32_t ref_id
        cdef TypeInfo typeinfo
        cdef uint8_t type_id
        cdef object obj
        cdef Buffer buffer = self.buffer
        if serializer is None or serializer.need_to_write_ref:
            ref_id = self.ref_reader.try_preserve_ref_id(buffer)
            if ref_id < NOT_NULL_VALUE_FLAG:
                return self.ref_reader.get_read_ref()
            if serializer is None:
                typeinfo = self.type_resolver.read_type_info(self)
                type_id = typeinfo.type_id
                if type_id == STRING_TYPE_ID:
                    obj = self.buffer.read_string()
                    if ref_id >= 0 and self.ref_reader.read_objects[ref_id] == NULL:
                        Py_INCREF(obj)
                        self.ref_reader.read_objects[ref_id] = <PyObject *>obj
                    return obj
                if type_id == INT64_TYPE_ID:
                    obj = self.read_varint64()
                    if ref_id >= 0 and self.ref_reader.read_objects[ref_id] == NULL:
                        Py_INCREF(obj)
                        self.ref_reader.read_objects[ref_id] = <PyObject *>obj
                    return obj
                if type_id == BOOL_TYPE_ID:
                    obj = self.read_bool()
                    if ref_id >= 0 and self.ref_reader.read_objects[ref_id] == NULL:
                        Py_INCREF(obj)
                        self.ref_reader.read_objects[ref_id] = <PyObject *>obj
                    return obj
                if type_id == FLOAT64_TYPE_ID:
                    obj = self.read_double()
                    if ref_id >= 0 and self.ref_reader.read_objects[ref_id] == NULL:
                        Py_INCREF(obj)
                        self.ref_reader.read_objects[ref_id] = <PyObject *>obj
                    return obj
                serializer = typeinfo.serializer
            obj = self._read_non_ref_internal(serializer)
            if ref_id >= 0 and self.ref_reader.read_objects[ref_id] == NULL:
                Py_INCREF(obj)
                self.ref_reader.read_objects[ref_id] = <PyObject *>obj
            return obj
        if self.read_int8() == NULL_FLAG:
            return None
        return self._read_non_ref_internal(serializer)

    cpdef inline read_non_ref(self, Serializer serializer=None):
        if self.track_ref:
            self.ref_reader.read_ref_ids.push_back(-1)
        return self._read_non_ref_internal(serializer)

    cpdef inline read_no_ref(self, Serializer serializer=None):
        return self.read_non_ref(serializer=serializer)

    cpdef inline read_nullable(self, Serializer serializer=None):
        if self.read_int8() == NULL_FLAG:
            return None
        return self._read_non_ref_internal(serializer)

    cdef inline object _read_non_ref_internal(self, Serializer serializer=None):
        cdef TypeInfo typeinfo
        cdef uint8_t type_id
        cdef object obj
        if serializer is None:
            typeinfo = self.type_resolver.read_type_info(self)
            type_id = typeinfo.type_id
            if type_id == STRING_TYPE_ID:
                return self.buffer.read_string()
            if type_id == INT64_TYPE_ID:
                return self.read_varint64()
            if type_id == BOOL_TYPE_ID:
                return self.read_bool()
            if type_id == FLOAT64_TYPE_ID:
                return self.read_double()
            serializer = typeinfo.serializer
        self.increase_depth()
        obj = serializer.read(self)
        self.decrease_depth()
        return obj

    cpdef read_buffer_object(self):
        cdef int32_t size
        cdef int32_t reader_index
        cdef Buffer buf
        if not self.peer_out_of_band_enabled:
            size = self.read_var_uint32()
            if self.buffer.has_input_stream():
                return self.buffer.read_bytes(size)
            reader_index = self.buffer.get_reader_index()
            buf = self.buffer.slice(reader_index, size)
            self.buffer.set_reader_index(reader_index + size)
            return buf
        if not self.read_bool():
            assert self.buffers is not None
            return next(self.buffers)
        size = self.read_var_uint32()
        if self.buffer.has_input_stream():
            return self.buffer.read_bytes(size)
        reader_index = self.buffer.get_reader_index()
        buf = self.buffer.slice(reader_index, size)
        self.buffer.set_reader_index(reader_index + size)
        return buf

    cpdef handle_unsupported_read(self):
        assert self.unsupported_objects is not None
        return next(self.unsupported_objects)

    cpdef inline bint read_bool(self):
        cdef Buffer buffer = self.buffer
        cdef uint8_t value = self.c_buffer.read_uint8(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value != 0

    cpdef inline uint8_t read_uint8(self):
        cdef Buffer buffer = self.buffer
        cdef uint8_t value = self.c_buffer.read_uint8(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int8_t read_int8(self):
        cdef Buffer buffer = self.buffer
        cdef int8_t value = self.c_buffer.read_int8(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int16_t read_int16(self):
        cdef Buffer buffer = self.buffer
        cdef int16_t value = self.c_buffer.read_int16(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint16_t read_uint16(self):
        cdef Buffer buffer = self.buffer
        cdef uint16_t value = self.c_buffer.read_uint16(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int32_t read_int32(self):
        cdef Buffer buffer = self.buffer
        cdef int32_t value = self.c_buffer.read_int32(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint32_t read_uint32(self):
        cdef Buffer buffer = self.buffer
        cdef uint32_t value = self.c_buffer.read_uint32(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int64_t read_int64(self):
        cdef Buffer buffer = self.buffer
        cdef int64_t value = self.c_buffer.read_int64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint64_t read_uint64(self):
        cdef Buffer buffer = self.buffer
        cdef uint64_t value = self.c_buffer.read_uint64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int32_t read_varint32(self):
        cdef Buffer buffer = self.buffer
        cdef int32_t value = self.c_buffer.read_var_int32(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint32_t read_var_uint32(self):
        cdef Buffer buffer = self.buffer
        cdef uint32_t value = self.c_buffer.read_var_uint32(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int64_t read_varint64(self):
        cdef Buffer buffer = self.buffer
        cdef int64_t value = self.c_buffer.read_var_int64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint64_t read_var_uint64(self):
        cdef Buffer buffer = self.buffer
        cdef uint64_t value = self.c_buffer.read_var_uint64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline int64_t read_tagged_int64(self):
        cdef Buffer buffer = self.buffer
        cdef int64_t value = self.c_buffer.read_tagged_int64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline uint64_t read_tagged_uint64(self):
        cdef Buffer buffer = self.buffer
        cdef uint64_t value = self.c_buffer.read_tagged_uint64(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline float read_float(self):
        cdef Buffer buffer = self.buffer
        cdef float value = self.c_buffer.read_float(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline float read_float32(self):
        return self.read_float()

    cpdef inline double read_double(self):
        cdef Buffer buffer = self.buffer
        cdef double value = self.c_buffer.read_double(buffer._error)
        if not buffer._error.ok():
            buffer._raise_if_error()
        return value

    cpdef inline double read_float64(self):
        return self.read_double()

    cpdef read_string(self):
        return self.buffer.read_string()

    cpdef read_bytes(self, int32_t length):
        return self.buffer.read_bytes(length)

    cpdef read_bytes_and_size(self):
        return self.buffer.read_bytes_and_size()

    cpdef inline int32_t get_reader_index(self):
        return self.buffer.get_reader_index()

    cpdef inline set_reader_index(self, int32_t reader_index):
        self.buffer.set_reader_index(reader_index)

    cpdef shrink_input_buffer(self):
        self.buffer.shrink_input_buffer()
