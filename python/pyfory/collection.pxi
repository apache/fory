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

"""
Collection serialization format:
https://fory.apache.org/docs/specification/fory_xlang_serialization_spec/#list
Has the following changes:
* None has an independent type, so COLL_NOT_SAME_TYPE can also cover the concept of being nullable.
* No flag is needed to indicate that the element type is not the declared type.
"""
cdef int8_t COLL_DEFAULT_FLAG = 0b0
cdef int8_t COLL_TRACKING_REF = 0b1
cdef int8_t COLL_HAS_NULL = 0b10
cdef int8_t COLL_IS_DECL_ELEMENT_TYPE = 0b100
cdef int8_t COLL_IS_SAME_TYPE = 0b1000
cdef int8_t COLL_DECL_SAME_TYPE_TRACKING_REF = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE | COLL_TRACKING_REF
cdef int8_t COLL_DECL_SAME_TYPE_NOT_TRACKING_REF = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
cdef int8_t COLL_DECL_SAME_TYPE_HAS_NULL = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE | COLL_HAS_NULL
cdef int8_t COLL_DECL_SAME_TYPE_NOT_HAS_NULL = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE


cdef class CollectionSerializer(Serializer):
    cdef TypeResolver type_resolver
    cdef MapRefResolver ref_resolver
    cdef Serializer elem_serializer
    cdef int8_t elem_tracking_ref
    cdef elem_type
    cdef TypeInfo elem_type_info

    def __init__(self, fory, type_, elem_serializer=None, elem_tracking_ref=None):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.elem_serializer = elem_serializer
        if elem_serializer is None:
            self.elem_type = None
            self.elem_type_info = self.type_resolver.get_type_info(None)
            self.elem_tracking_ref = -1
        else:
            self.elem_type = elem_serializer.type_
            self.elem_type_info = fory.type_resolver.get_type_info(self.elem_type)
            self.elem_tracking_ref = <int8_t> (elem_serializer.need_to_write_ref)
            if elem_tracking_ref is not None:
                self.elem_tracking_ref = <int8_t> (1 if elem_tracking_ref else 0)

    cdef inline TypeInfo write_header(self, Buffer buffer, value, int8_t *collect_flag_ptr):
        cdef int8_t collect_flag = COLL_DEFAULT_FLAG
        elem_type = self.elem_type
        cdef TypeInfo elem_type_info = self.elem_type_info
        cdef c_bool has_null = False
        cdef c_bool has_same_type = True
        cdef PyObject **items = fory_sequence_get_items(value)
        cdef PyObject *item
        cdef PyTypeObject *first_type = NULL
        cdef Py_ssize_t i
        cdef Py_ssize_t size = Py_SIZE(value)
        if elem_type is None:
            if items != NULL:
                for i in range(size):
                    item = items[i]
                    if item == <PyObject *> None:
                        has_null = True
                        continue
                    if first_type == NULL:
                        first_type = item.ob_type
                    elif has_same_type and item.ob_type != first_type:
                        has_same_type = False
                if first_type != NULL:
                    elem_type = <object> first_type
            else:
                for s in value:
                    if not has_null and s is None:
                        has_null = True
                        continue
                    if elem_type is None:
                        elem_type = type(s)
                    elif has_same_type and type(s) is not elem_type:
                        has_same_type = False
            if has_same_type:
                collect_flag |= COLL_IS_SAME_TYPE
                elem_type_info = self.type_resolver.get_type_info(elem_type)
        else:
            collect_flag |= COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
            if items != NULL:
                for i in range(size):
                    if items[i] == <PyObject *> None:
                        has_null = True
                        break
            else:
                for s in value:
                    if s is None:
                        has_null = True
                        break
        if has_null:
            collect_flag |= COLL_HAS_NULL
        if self.fory.track_ref:
            if self.elem_tracking_ref == 1:
                collect_flag |= COLL_TRACKING_REF
            elif self.elem_tracking_ref == -1:
                if not has_same_type or elem_type_info.serializer.need_to_write_ref:
                    collect_flag |= COLL_TRACKING_REF
        buffer.write_var_uint32(len(value))
        buffer.write_int8(collect_flag)
        if (has_same_type and
                collect_flag & COLL_IS_DECL_ELEMENT_TYPE == 0):
            self.type_resolver.write_type_info(buffer, elem_type_info)
        collect_flag_ptr[0] = collect_flag
        return elem_type_info

    cpdef write(self, Buffer buffer, value):
        if len(value) == 0:
            buffer.write_var_uint64(0)
            return
        cdef int8_t collect_flag
        cdef TypeInfo elem_type_info = self.write_header(buffer, value, &collect_flag)
        cdef elem_type = elem_type_info.cls
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        cdef PyObject **items = fory_sequence_get_items(value)
        cdef Py_ssize_t size = Py_SIZE(value)
        cdef Py_ssize_t i
        cdef PyObject *item
        cdef PyTypeObject *item_type
        cdef uint8_t type_id = elem_type_info.type_id
        cdef c_bool tracking_ref
        cdef c_bool has_null
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if (collect_flag & COLL_HAS_NULL) == 0:
                if Fory_CanUsePrimitiveCollectionFastpath(type_id):
                    self._write_primitive_fastpath(buffer, value, type_id, items, size)
                elif (collect_flag & COLL_TRACKING_REF) == 0:
                    self._write_same_type_no_ref(buffer, value, elem_type_info)
                else:
                    self._write_same_type_ref(buffer, value, elem_type_info)
            elif (collect_flag & COLL_TRACKING_REF) != 0:
                self._write_same_type_ref(buffer, value, elem_type_info)
            else:
                self._write_same_type_has_null(buffer, value, elem_type_info)
        else:
            # Check tracking_ref and has_null flags for different types writing
            tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
            has_null = (collect_flag & COLL_HAS_NULL) != 0
            if tracking_ref:
                # When ref tracking is enabled, write with ref handling
                if items != NULL:
                    for i in range(size):
                        item = items[i]
                        s = <object> item
                        item_type = item.ob_type
                        if item_type == <PyTypeObject *> str:
                            buffer.write_int16(NOT_NULL_STRING_FLAG)
                            buffer.write_string(s)
                        elif item_type == <PyTypeObject *> int:
                            buffer.write_int8(NOT_NULL_VALUE_FLAG)
                            typeinfo = type_resolver.get_type_info(<object> item_type)
                            type_resolver.write_type_info(buffer, typeinfo)
                            typeinfo.serializer.write(buffer, s)
                        elif item_type == <PyTypeObject *> bool:
                            buffer.write_int16(NOT_NULL_BOOL_FLAG)
                            buffer.write_bool(s)
                        elif item_type == <PyTypeObject *> float:
                            buffer.write_int16(NOT_NULL_FLOAT64_FLAG)
                            buffer.write_double(s)
                        else:
                            if not ref_resolver.write_ref_or_null(buffer, s):
                                cls = <object> item_type
                                typeinfo = type_resolver.get_type_info(cls)
                                type_resolver.write_type_info(buffer, typeinfo)
                                typeinfo.serializer.write(buffer, s)
                else:
                    for s in value:
                        cls = type(s)
                        if cls is str:
                            buffer.write_int16(NOT_NULL_STRING_FLAG)
                            buffer.write_string(s)
                        elif cls is int:
                            buffer.write_int8(NOT_NULL_VALUE_FLAG)
                            typeinfo = type_resolver.get_type_info(cls)
                            type_resolver.write_type_info(buffer, typeinfo)
                            typeinfo.serializer.write(buffer, s)
                        elif cls is bool:
                            buffer.write_int16(NOT_NULL_BOOL_FLAG)
                            buffer.write_bool(s)
                        elif cls is float:
                            buffer.write_int16(NOT_NULL_FLOAT64_FLAG)
                            buffer.write_double(s)
                        else:
                            if not ref_resolver.write_ref_or_null(buffer, s):
                                typeinfo = type_resolver.get_type_info(cls)
                                type_resolver.write_type_info(buffer, typeinfo)
                                typeinfo.serializer.write(buffer, s)
            elif not has_null:
                # When ref tracking is disabled and no nulls, write type info directly
                if items != NULL:
                    for i in range(size):
                        item = items[i]
                        s = <object> item
                        cls = <object> item.ob_type
                        typeinfo = type_resolver.get_type_info(cls)
                        type_resolver.write_type_info(buffer, typeinfo)
                        typeinfo.serializer.write(buffer, s)
                else:
                    for s in value:
                        cls = type(s)
                        typeinfo = type_resolver.get_type_info(cls)
                        type_resolver.write_type_info(buffer, typeinfo)
                        typeinfo.serializer.write(buffer, s)
            else:
                # When ref tracking is disabled but has nulls, write null flag first
                if items != NULL:
                    for i in range(size):
                        item = items[i]
                        if item == <PyObject *> None:
                            buffer.write_int8(NULL_FLAG)
                        else:
                            s = <object> item
                            buffer.write_int8(NOT_NULL_VALUE_FLAG)
                            cls = <object> item.ob_type
                            typeinfo = type_resolver.get_type_info(cls)
                            type_resolver.write_type_info(buffer, typeinfo)
                            typeinfo.serializer.write(buffer, s)
                else:
                    for s in value:
                        if s is None:
                            buffer.write_int8(NULL_FLAG)
                        else:
                            buffer.write_int8(NOT_NULL_VALUE_FLAG)
                            cls = type(s)
                            typeinfo = type_resolver.get_type_info(cls)
                            type_resolver.write_type_info(buffer, typeinfo)
                            typeinfo.serializer.write(buffer, s)

    cdef inline _write_primitive_fastpath(self, Buffer buffer, value, uint8_t type_id, PyObject **items, Py_ssize_t size):
        if items != NULL:
            Fory_PyPrimitiveSequenceWriteToBuffer(items, size, &buffer.c_buffer, type_id)
            return
        Fory_PyPrimitiveCollectionWriteToBuffer(value, &buffer.c_buffer, type_id)

    cdef inline _read_primitive_fastpath(self, Buffer buffer, int64_t len_, object collection_, uint8_t type_id):
        Fory_PyPrimitiveCollectionReadFromBuffer(collection_, &buffer.c_buffer, len_, type_id)

    cpdef _write_same_type_no_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef PyObject **items = fory_sequence_get_items(value)
        cdef Py_ssize_t i
        cdef Py_ssize_t size
        cdef object s
        if items != NULL:
            size = Py_SIZE(value)
            for i in range(size):
                s = <object> items[i]
                typeinfo.serializer.write(buffer, s)
            return
        for s in value:
            typeinfo.serializer.write(buffer, s)

    cpdef _read_same_type_no_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef PyObject **items = fory_sequence_get_items(collection_)
        cdef c_bool is_list = type(collection_) is list
        self.fory.inc_depth()
        if items != NULL:
            for i in range(len_):
                obj = self.fory.read_no_ref(buffer, serializer=typeinfo.serializer)
                Py_INCREF(obj)
                if is_list:
                    PyList_SET_ITEM(collection_, i, obj)
                else:
                    PyTuple_SET_ITEM(collection_, i, obj)
        else:
            for i in range(len_):
                obj = self.fory.read_no_ref(buffer, serializer=typeinfo.serializer)
                self._add_element(collection_, i, obj)
        self.fory.dec_depth()

    cpdef _write_same_type_has_null(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef PyObject **items = fory_sequence_get_items(value)
        cdef PyObject *item
        cdef Py_ssize_t i
        cdef Py_ssize_t size
        cdef object s
        if items != NULL:
            size = Py_SIZE(value)
            for i in range(size):
                item = items[i]
                if item == <PyObject *> None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    s = <object> item
                    typeinfo.serializer.write(buffer, s)
            return
        for s in value:
            if s is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                typeinfo.serializer.write(buffer, s)

    cpdef _read_same_type_has_null(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef int8_t flag
        cdef PyObject **items = fory_sequence_get_items(collection_)
        cdef c_bool is_list = type(collection_) is list
        self.fory.inc_depth()
        if items != NULL:
            for i in range(len_):
                flag = buffer.read_int8()
                if flag == NULL_FLAG:
                    obj = None
                else:
                    obj = self.fory.read_no_ref(buffer, serializer=typeinfo.serializer)
                Py_INCREF(obj)
                if is_list:
                    PyList_SET_ITEM(collection_, i, obj)
                else:
                    PyTuple_SET_ITEM(collection_, i, obj)
        else:
            for i in range(len_):
                flag = buffer.read_int8()
                if flag == NULL_FLAG:
                    self._add_element(collection_, i, None)
                else:
                    self._add_element(
                        collection_,
                        i,
                        self.fory.read_no_ref(buffer, serializer=typeinfo.serializer),
                    )
        self.fory.dec_depth()

    cpdef _write_same_type_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef PyObject **items = fory_sequence_get_items(value)
        cdef Py_ssize_t i
        cdef Py_ssize_t size
        cdef object s
        if items != NULL:
            size = Py_SIZE(value)
            for i in range(size):
                s = <object> items[i]
                if not ref_resolver.write_ref_or_null(buffer, s):
                    typeinfo.serializer.write(buffer, s)
            return
        for s in value:
            if not ref_resolver.write_ref_or_null(buffer, s):
                typeinfo.serializer.write(buffer, s)

    cpdef _read_same_type_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef PyObject **items = fory_sequence_get_items(collection_)
        cdef c_bool is_list = type(collection_) is list
        self.fory.inc_depth()
        if items != NULL:
            for i in range(len_):
                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                if ref_id < NOT_NULL_VALUE_FLAG:
                    obj = ref_resolver.get_read_object()
                else:
                    obj = typeinfo.serializer.read(buffer)
                    ref_resolver.set_read_object(ref_id, obj)
                Py_INCREF(obj)
                if is_list:
                    PyList_SET_ITEM(collection_, i, obj)
                else:
                    PyTuple_SET_ITEM(collection_, i, obj)
        else:
            for i in range(len_):
                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                if ref_id < NOT_NULL_VALUE_FLAG:
                    obj = ref_resolver.get_read_object()
                else:
                    obj = typeinfo.serializer.read(buffer)
                    ref_resolver.set_read_object(ref_id, obj)
                self._add_element(collection_, i, obj)
        self.fory.dec_depth()

    cpdef _add_element(self, object collection_, int64_t index, object element):
        raise NotImplementedError

cdef class ListSerializer(CollectionSerializer):
    cpdef read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        cdef int32_t len_ = buffer.read_var_uint32()
        cdef list list_ = PyList_New(len_)
        if len_ == 0:
            return list_
        cdef int8_t collect_flag = buffer.read_int8()
        ref_resolver.reference(list_)
        cdef TypeInfo typeinfo
        cdef uint8_t type_id = 0
        cdef c_bool tracking_ref
        cdef c_bool has_null
        cdef int8_t head_flag
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if collect_flag & COLL_IS_DECL_ELEMENT_TYPE == 0:
                typeinfo = self.type_resolver.read_type_info(buffer)
            else:
                typeinfo = self.elem_type_info
            if (collect_flag & COLL_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if Fory_CanUsePrimitiveCollectionFastpath(type_id):
                    self._read_primitive_fastpath(buffer, len_, list_, type_id)
                    return list_
                elif (collect_flag & COLL_TRACKING_REF) == 0:
                    self._read_same_type_no_ref(buffer, len_, list_, typeinfo)
                else:
                    self._read_same_type_ref(buffer, len_, list_, typeinfo)
            elif (collect_flag & COLL_TRACKING_REF) != 0:
                self._read_same_type_ref(buffer, len_, list_, typeinfo)
            else:
                self._read_same_type_has_null(buffer, len_, list_, typeinfo)
        else:
            self.fory.inc_depth()
            # Check tracking_ref and has_null flags for different types handling
            tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
            has_null = (collect_flag & COLL_HAS_NULL) != 0
            if tracking_ref:
                # When ref tracking is enabled, read with ref handling
                for i in range(len_):
                    elem = get_next_element(buffer, ref_resolver, type_resolver)
                    Py_INCREF(elem)
                    PyList_SET_ITEM(list_, i, elem)
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    elem = self.fory.read_no_ref(
                        buffer, serializer=typeinfo.serializer
                    )
                    Py_INCREF(elem)
                    PyList_SET_ITEM(list_, i, elem)
            else:
                # When ref tracking is disabled but has nulls, read null flag first
                for i in range(len_):
                    head_flag = buffer.read_int8()
                    if head_flag == NULL_FLAG:
                        elem = None
                    else:
                        typeinfo = type_resolver.read_type_info(buffer)
                        elem = self.fory.read_no_ref(
                            buffer, serializer=typeinfo.serializer
                        )
                    Py_INCREF(elem)
                    PyList_SET_ITEM(list_, i, elem)
            self.fory.dec_depth()
        return list_

    cpdef _add_element(self, object collection_, int64_t index, object element):
        Py_INCREF(element)
        PyList_SET_ITEM(collection_, index, element)

cdef inline get_next_element(
        Buffer buffer,
        MapRefResolver ref_resolver,
        TypeResolver type_resolver,
):
    cdef int32_t ref_id
    cdef TypeInfo typeinfo
    ref_id = ref_resolver.try_preserve_ref_id(buffer)
    if ref_id < NOT_NULL_VALUE_FLAG:
        return ref_resolver.get_read_object()
    # Always route through serializer and preserve ref slot for cross-language
    # payloads where first-seen primitive values may still be emitted as REF_VALUE.
    typeinfo = type_resolver.read_type_info(buffer)
    o = typeinfo.serializer.read(buffer)
    ref_resolver.set_read_object(ref_id, o)
    return o


@cython.final
cdef class TupleSerializer(CollectionSerializer):
    cpdef inline read(self, Buffer buffer):
        cdef MapRefResolver ref_resolver = self.fory.ref_resolver
        cdef TypeResolver type_resolver = self.fory.type_resolver
        cdef int32_t len_ = buffer.read_var_uint32()
        cdef tuple tuple_ = PyTuple_New(len_)
        if len_ == 0:
            return tuple_
        cdef int8_t collect_flag = buffer.read_int8()
        cdef TypeInfo typeinfo
        cdef uint8_t type_id = 0
        cdef c_bool tracking_ref
        cdef c_bool has_null
        cdef int8_t head_flag
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if collect_flag & COLL_IS_DECL_ELEMENT_TYPE == 0:
                typeinfo = self.type_resolver.read_type_info(buffer)
            else:
                typeinfo = self.elem_type_info
            if (collect_flag & COLL_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if Fory_CanUsePrimitiveCollectionFastpath(type_id):
                    self._read_primitive_fastpath(buffer, len_, tuple_, type_id)
                    return tuple_
                elif (collect_flag & COLL_TRACKING_REF) == 0:
                    self._read_same_type_no_ref(buffer, len_, tuple_, typeinfo)
                else:
                    self._read_same_type_ref(buffer, len_, tuple_, typeinfo)
            elif (collect_flag & COLL_TRACKING_REF) != 0:
                self._read_same_type_ref(buffer, len_, tuple_, typeinfo)
            else:
                self._read_same_type_has_null(buffer, len_, tuple_, typeinfo)
        else:
            self.fory.inc_depth()
            # Check tracking_ref and has_null flags for different types handling
            tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
            has_null = (collect_flag & COLL_HAS_NULL) != 0
            if tracking_ref:
                # When ref tracking is enabled, read with ref handling
                for i in range(len_):
                    elem = get_next_element(buffer, ref_resolver, type_resolver)
                    Py_INCREF(elem)
                    PyTuple_SET_ITEM(tuple_, i, elem)
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    elem = self.fory.read_no_ref(
                        buffer, serializer=typeinfo.serializer
                    )
                    Py_INCREF(elem)
                    PyTuple_SET_ITEM(tuple_, i, elem)
            else:
                # When ref tracking is disabled but has nulls, read null flag first
                for i in range(len_):
                    head_flag = buffer.read_int8()
                    if head_flag == NULL_FLAG:
                        elem = None
                    else:
                        typeinfo = type_resolver.read_type_info(buffer)
                        elem = self.fory.read_no_ref(
                            buffer, serializer=typeinfo.serializer
                        )
                    Py_INCREF(elem)
                    PyTuple_SET_ITEM(tuple_, i, elem)
            self.fory.dec_depth()
        return tuple_

    cpdef inline _add_element(self, object collection_, int64_t index, object element):
        Py_INCREF(element)
        PyTuple_SET_ITEM(collection_, index, element)


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
        cdef int32_t len_ = buffer.read_var_uint32()
        if len_ == 0:
            return instance
        cdef int8_t collect_flag = buffer.read_int8()
        cdef int32_t ref_id
        cdef TypeInfo typeinfo
        cdef uint8_t type_id = 0
        cdef c_bool tracking_ref
        cdef c_bool has_null
        cdef int8_t head_flag
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if collect_flag & COLL_IS_DECL_ELEMENT_TYPE == 0:
                typeinfo = self.type_resolver.read_type_info(buffer)
            else:
                typeinfo = self.elem_type_info
            if (collect_flag & COLL_HAS_NULL) == 0:
                type_id = typeinfo.type_id
                if Fory_CanUsePrimitiveCollectionFastpath(type_id):
                    self._read_primitive_fastpath(buffer, len_, instance, type_id)
                    return instance
                elif (collect_flag & COLL_TRACKING_REF) == 0:
                    self._read_same_type_no_ref(buffer, len_, instance, typeinfo)
                else:
                    self._read_same_type_ref(buffer, len_, instance, typeinfo)
            elif (collect_flag & COLL_TRACKING_REF) != 0:
                self._read_same_type_ref(buffer, len_, instance, typeinfo)
            else:
                self._read_same_type_has_null(buffer, len_, instance, typeinfo)
        else:
            self.fory.inc_depth()
            # Check tracking_ref and has_null flags for different types handling
            tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
            has_null = (collect_flag & COLL_HAS_NULL) != 0
            if tracking_ref:
                # When ref tracking is enabled, read with ref handling
                for i in range(len_):
                    instance.add(get_next_element(buffer, ref_resolver, type_resolver))
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    type_id = typeinfo.type_id
                    if type_id == <uint8_t>TypeId.STRING:
                        instance.add(buffer.read_string())
                    elif type_id == <uint8_t>TypeId.INT8:
                        instance.add(buffer.read_int8())
                    elif type_id == <uint8_t>TypeId.INT16:
                        instance.add(buffer.read_int16())
                    elif type_id == <uint8_t>TypeId.INT32:
                        instance.add(buffer.read_int32())
                    elif type_id == <uint8_t>TypeId.BOOL:
                        instance.add(buffer.read_bool())
                    elif type_id == <uint8_t>TypeId.FLOAT64:
                        instance.add(buffer.read_double())
                    else:
                        instance.add(
                            self.fory.read_no_ref(
                                buffer, serializer=typeinfo.serializer
                            )
                        )
            else:
                # When ref tracking is disabled but has nulls, read null flag first
                for i in range(len_):
                    head_flag = buffer.read_int8()
                    if head_flag == NULL_FLAG:
                        instance.add(None)
                    else:
                        typeinfo = type_resolver.read_type_info(buffer)
                        type_id = typeinfo.type_id
                        if type_id == <uint8_t>TypeId.STRING:
                            instance.add(buffer.read_string())
                        elif type_id == <uint8_t>TypeId.INT8:
                            instance.add(buffer.read_int8())
                        elif type_id == <uint8_t>TypeId.INT16:
                            instance.add(buffer.read_int16())
                        elif type_id == <uint8_t>TypeId.INT32:
                            instance.add(buffer.read_int32())
                        elif type_id == <uint8_t>TypeId.BOOL:
                            instance.add(buffer.read_bool())
                        elif type_id == <uint8_t>TypeId.FLOAT64:
                            instance.add(buffer.read_double())
                        else:
                            instance.add(
                                self.fory.read_no_ref(
                                    buffer, serializer=typeinfo.serializer
                                )
                            )
            self.fory.dec_depth()
        return instance

    cpdef inline _add_element(self, object collection_, int64_t index, object element):
        collection_.add(element)


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
cdef int32_t NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF


@cython.final
cdef class MapSerializer(Serializer):
    cdef TypeResolver type_resolver
    cdef MapRefResolver ref_resolver
    cdef Serializer key_serializer
    cdef Serializer value_serializer
    cdef int8_t key_tracking_ref
    cdef int8_t value_tracking_ref

    def __init__(
        self,
        fory,
        type_,
        key_serializer=None,
        value_serializer=None,
        key_tracking_ref=None,
        value_tracking_ref=None,
    ):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.key_serializer = key_serializer
        self.value_serializer = value_serializer
        self.key_tracking_ref = 0
        self.value_tracking_ref = 0
        if key_serializer is not None:
            self.key_tracking_ref = <int8_t> (key_serializer.need_to_write_ref)
            if key_tracking_ref is not None:
                self.key_tracking_ref = <int8_t> (1 if key_tracking_ref and fory.track_ref else 0)
        if value_serializer is not None:
            self.value_tracking_ref = <int8_t> (value_serializer.need_to_write_ref)
            if value_tracking_ref is not None:
                self.value_tracking_ref = <int8_t> (1 if value_tracking_ref and fory.track_ref else 0)

    cpdef inline write(self, Buffer buffer, o):
        cdef dict obj = o
        cdef int32_t length = len(obj)
        buffer.write_var_uint32(length)
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
        cdef TypeInfo key_type_info, value_type_info
        cdef int32_t chunk_size_offset, chunk_header, chunk_size
        cdef c_bool key_write_ref, value_write_ref
        cdef int has_next = PyDict_Next(obj, &pos, <PyObject **>&key_addr, <PyObject **>&value_addr)
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
                        key_write_ref = self.key_tracking_ref == 1
                        if key_write_ref:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
                            if not self.ref_resolver.write_ref_or_null(buffer, key):
                                key_serializer.write(buffer, key)
                        else:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE)
                            key_serializer.write(buffer, key)
                    else:
                        buffer.write_int8(VALUE_HAS_NULL | TRACKING_KEY_REF)
                        fory.write_ref(buffer, key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            value_write_ref = self.value_tracking_ref == 1
                            if value_write_ref:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
                                if not self.ref_resolver.write_ref_or_null(buffer, value):
                                    value_serializer.write(buffer, value)
                            else:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE)
                                value_serializer.write(buffer, value)
                        else:
                            buffer.write_int8(KEY_HAS_NULL | TRACKING_VALUE_REF)
                            fory.write_ref(buffer, value)
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
            chunk_size_offset = buffer.get_writer_index() - 1
            chunk_header = 0
            if key_serializer is not None:
                chunk_header |= KEY_DECL_TYPE
            else:
                key_type_info = self.type_resolver.get_type_info(key_cls)
                type_resolver.write_type_info(buffer, key_type_info)
                key_serializer = key_type_info.serializer
            if value_serializer is not None:
                chunk_header |= VALUE_DECL_TYPE
            else:
                value_type_info = self.type_resolver.get_type_info(value_cls)
                type_resolver.write_type_info(buffer, value_type_info)
                value_serializer = value_type_info.serializer
            if self.key_serializer is not None:
                key_write_ref = self.key_tracking_ref == 1
            else:
                key_write_ref = key_serializer.need_to_write_ref
            if self.value_serializer is not None:
                value_write_ref = self.value_tracking_ref == 1
            else:
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
                        key_serializer.write(buffer, key)
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
                        value_serializer.write(buffer, value)
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
        cdef int32_t size = buffer.read_var_uint32()
        cdef dict map_ = _PyDict_NewPresized(size)
        ref_resolver.reference(map_)
        cdef int32_t ref_id
        cdef TypeInfo key_type_info, value_type_info
        cdef int32_t chunk_header = 0
        if size != 0:
            chunk_header = buffer.read_uint8()
        cdef Serializer key_serializer = self.key_serializer
        cdef Serializer value_serializer = self.value_serializer
        cdef c_bool key_has_null, value_has_null, track_key_ref, track_value_ref
        cdef c_bool key_is_declared_type, value_is_declared_type
        cdef type key_serializer_type, value_serializer_type
        cdef int32_t chunk_size
        self.fory.inc_depth()
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
                                    key = key_serializer.read(buffer)
                                    ref_resolver.set_read_object(ref_id, key)
                            else:
                                key = fory.read_no_ref(
                                    buffer, serializer=key_serializer
                                )
                        else:
                            key = fory.read_ref(buffer)
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
                                    value = (<object> value_serializer).read(buffer)
                                    ref_resolver.set_read_object(ref_id, value)
                            else:
                                value = fory.read_no_ref(
                                    buffer, serializer=value_serializer
                                )
                        else:
                            value = fory.read_ref(buffer)
                        map_[None] = value
                    else:
                        map_[None] = None
                size -= 1
                if size == 0:
                    self.fory.dec_depth()
                    return map_
                else:
                    chunk_header = buffer.read_uint8()
            track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
            track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
            key_is_declared_type = (chunk_header & KEY_DECL_TYPE) != 0
            value_is_declared_type = (chunk_header & VALUE_DECL_TYPE) != 0
            chunk_size = buffer.read_uint8()
            if not key_is_declared_type:
                key_serializer = type_resolver.read_type_info(buffer).serializer
            if not value_is_declared_type:
                value_serializer = type_resolver.read_type_info(buffer).serializer
            key_serializer_type = type(key_serializer)
            value_serializer_type = type(value_serializer)
            for i in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_resolver.get_read_object()
                    else:
                        key = key_serializer.read(buffer)
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
                        key = fory.read_no_ref(buffer, serializer=key_serializer)
                if track_value_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_resolver.get_read_object()
                    else:
                        value = value_serializer.read(buffer)
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
                        value = fory.read_no_ref(buffer, serializer=value_serializer)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = buffer.read_uint8()
        self.fory.dec_depth()
        return map_
