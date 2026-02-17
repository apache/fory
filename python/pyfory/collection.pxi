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
    cdef TypeInfo list_type_info
    cdef TypeInfo tuple_type_info
    cdef TypeInfo set_type_info
    cdef TypeInfo dict_type_info

    def __init__(self, fory, type_, elem_serializer=None, elem_tracking_ref=None):
        super().__init__(fory, type_)
        self.type_resolver = fory.type_resolver
        self.ref_resolver = fory.ref_resolver
        self.list_type_info = None
        self.tuple_type_info = None
        self.set_type_info = None
        self.dict_type_info = None
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

    cdef inline TypeInfo _get_type_info_fast(self, type cls):
        if cls is list:
            if self.list_type_info is None:
                self.list_type_info = self.type_resolver.get_type_info(list)
            return self.list_type_info
        if cls is tuple:
            if self.tuple_type_info is None:
                self.tuple_type_info = self.type_resolver.get_type_info(tuple)
            return self.tuple_type_info
        if cls is set:
            if self.set_type_info is None:
                self.set_type_info = self.type_resolver.get_type_info(set)
            return self.set_type_info
        if cls is dict:
            if self.dict_type_info is None:
                self.dict_type_info = self.type_resolver.get_type_info(dict)
            return self.dict_type_info
        return self.type_resolver.get_type_info(cls)

    cdef inline pair[int8_t, int64_t] write_header(self, Buffer buffer, value):
        cdef int8_t collect_flag = COLL_DEFAULT_FLAG
        elem_type = self.elem_type
        cdef TypeInfo elem_type_info = self.elem_type_info
        cdef c_bool has_null = False
        cdef c_bool has_same_type = True
        cdef int fast_kind = kForyPySequenceValueNone
        cdef type value_type = type(value)
        cdef Py_ssize_t seq_size
        cdef Py_ssize_t i
        cdef int has_null_i
        cdef int has_same_type_i
        cdef int64_t elem_type_addr
        cdef object s
        if elem_type is None:
            if value_type is list or value_type is tuple:
                fast_kind = Fory_PyDetectSequenceNoNullExactTypeKind(value)
                if fast_kind == kForyPySequenceValueString:
                    elem_type = str
                elif fast_kind == kForyPySequenceValueInt64:
                    elem_type = int
                elif fast_kind == kForyPySequenceValueBool:
                    elem_type = bool
                elif fast_kind == kForyPySequenceValueFloat64:
                    elem_type = float
            if fast_kind == kForyPySequenceValueNone:
                if value_type is list or value_type is tuple:
                    has_null_i = 0
                    has_same_type_i = 1
                    elem_type_addr = 0
                    if Fory_PyDetectSequenceTypeAndNull(
                            value, &has_null_i, &has_same_type_i, &elem_type_addr) == 0:
                        has_null = has_null_i != 0
                        has_same_type = has_same_type_i != 0
                        if elem_type_addr != 0:
                            if elem_type_addr == <int64_t><uintptr_t><PyObject *>list:
                                elem_type = list
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>tuple:
                                elem_type = tuple
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>set:
                                elem_type = set
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>dict:
                                elem_type = dict
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>str:
                                elem_type = str
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>int:
                                elem_type = int
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>bool:
                                elem_type = bool
                            elif elem_type_addr == <int64_t><uintptr_t><PyObject *>float:
                                elem_type = float
                            else:
                                seq_size = Py_SIZE(value)
                                for i in range(seq_size):
                                    if value_type is list:
                                        s = <object>PyList_GET_ITEM(value, i)
                                    else:
                                        s = <object>PyTuple_GET_ITEM(value, i)
                                    if s is not None:
                                        elem_type = type(s)
                                        break
                    else:
                        seq_size = Py_SIZE(value)
                        for i in range(seq_size):
                            if value_type is list:
                                s = <object>PyList_GET_ITEM(value, i)
                            else:
                                s = <object>PyTuple_GET_ITEM(value, i)
                            if not has_null and s is None:
                                has_null = True
                                continue
                            if elem_type is None:
                                elem_type = type(s)
                            elif has_same_type and type(s) is not elem_type:
                                has_same_type = False
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
            if value_type is list or value_type is tuple:
                has_null_i = Fory_PySequenceHasNull(value)
                if has_null_i >= 0:
                    has_null = has_null_i != 0
                else:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_type is list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if s is None:
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
        return pair[int8_t, int64_t](collect_flag, obj2int(elem_type_info))

    cpdef write(self, Buffer buffer, value):
        if len(value) == 0:
            buffer.write_var_uint64(0)
            return
        cdef pair[int8_t, int64_t] header_pair = self.write_header(buffer, value)
        cdef int8_t collect_flag = header_pair.first
        cdef int64_t elem_type_info_ptr = header_pair.second
        cdef TypeInfo elem_type_info = <type> int2obj(elem_type_info_ptr)
        cdef elem_type = elem_type_info.cls
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        cdef c_bool is_py = not self.fory.xlang
        cdef serializer = type(elem_type_info.serializer)
        cdef c_bool tracking_ref
        cdef c_bool has_null
        cdef type cls
        cdef type value_type = type(value)
        cdef object s
        cdef TypeInfo typeinfo
        cdef Py_ssize_t i
        cdef Py_ssize_t seq_size
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if (collect_flag & COLL_HAS_NULL) == 0:
                if elem_type is str:
                    self._write_string(buffer, value)
                elif serializer is Int64Serializer:
                    self._write_int(buffer, value)
                elif elem_type is bool:
                    self._write_bool(buffer, value)
                elif serializer is Float64Serializer:
                    self._write_float(buffer, value)
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
                if value_type is list or value_type is tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_type is list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
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
                                typeinfo = self._get_type_info_fast(cls)
                                type_resolver.write_type_info(buffer, typeinfo)
                                if is_py:
                                    typeinfo.serializer.write(buffer, s)
                                else:
                                    typeinfo.serializer.xwrite(buffer, s)
                else:
                    # When ref tracking is enabled, write with ref handling
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
                                typeinfo = self._get_type_info_fast(cls)
                                type_resolver.write_type_info(buffer, typeinfo)
                                if is_py:
                                    typeinfo.serializer.write(buffer, s)
                                else:
                                    typeinfo.serializer.xwrite(buffer, s)
            elif not has_null:
                # When ref tracking is disabled and no nulls, write type info directly
                for s in value:
                    cls = type(s)
                    typeinfo = self._get_type_info_fast(cls)
                    type_resolver.write_type_info(buffer, typeinfo)
                    if is_py:
                        typeinfo.serializer.write(buffer, s)
                    else:
                        typeinfo.serializer.xwrite(buffer, s)
            else:
                # When ref tracking is disabled but has nulls, write null flag first
                for s in value:
                    if s is None:
                        buffer.write_int8(NULL_FLAG)
                    else:
                        buffer.write_int8(NOT_NULL_VALUE_FLAG)
                        cls = type(s)
                        typeinfo = self._get_type_info_fast(cls)
                        type_resolver.write_type_info(buffer, typeinfo)
                        if is_py:
                            typeinfo.serializer.write(buffer, s)
                        else:
                            typeinfo.serializer.xwrite(buffer, s)

    cdef inline _write_string(self, Buffer buffer, value):
        value_type = type(value)
        if value_type is list or value_type is tuple:
            if Fory_PyStringSequenceWriteToBuffer(value, &buffer.c_buffer) == 0:
                return
        for s in value:
            buffer.write_string(s)

    cdef inline _read_string(self, Buffer buffer, int64_t len_, object collection_):
        collection_type = type(collection_)
        if collection_type is list or collection_type is tuple:
            if Fory_PyStringSequenceReadFromBuffer(collection_, &buffer.c_buffer, len_) == 0:
                return
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_string())

    cdef inline _write_int(self, Buffer buffer, value):
        value_type = type(value)
        if (value_type is list or value_type is tuple) and (
                Fory_PyInt64SequenceWriteVarintToBuffer(value, &buffer.c_buffer) == 0):
            return
        for s in value:
            buffer.write_varint64(s)

    cdef inline _read_int(self, Buffer buffer, int64_t len_, object collection_):
        collection_type = type(collection_)
        if collection_type is list or collection_type is tuple:
            if Fory_PyInt64SequenceReadVarintFromBuffer(collection_, &buffer.c_buffer, len_) == 0:
                return
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_varint64())

    cdef inline _write_bool(self, Buffer buffer, value):
        cdef int32_t writer_index
        value_type = type(value)
        if value_type is list or value_type is tuple:
            size = sizeof(bool) * Py_SIZE(value)
            buffer.grow(<int32_t>size)
            writer_index = buffer.get_writer_index()
            Fory_PyBooleanSequenceWriteToBuffer(value, &buffer.c_buffer, writer_index)
            buffer.set_writer_index(writer_index + size)
        else:
            for s in value:
                buffer.write_bool(s)

    cdef inline _read_bool(self, Buffer buffer, int64_t len_, object collection_):
        collection_type = type(collection_)
        if collection_type is list or collection_type is tuple:
            if Fory_PyBooleanSequenceReadFromBuffer(collection_, &buffer.c_buffer, len_) == 0:
                return
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_bool())

    cdef inline _write_float(self, Buffer buffer, value):
        cdef int32_t writer_index
        value_type = type(value)
        if value_type is list or value_type is tuple:
            size = sizeof(double) * Py_SIZE(value)
            buffer.grow(<int32_t>size)
            writer_index = buffer.get_writer_index()
            Fory_PyFloatSequenceWriteToBuffer(value, &buffer.c_buffer, writer_index)
            buffer.set_writer_index(writer_index + size)
        else:
            for s in value:
                buffer.write_double(s)

    cdef inline _read_float(self, Buffer buffer, int64_t len_, object collection_):
        collection_type = type(collection_)
        if collection_type is list or collection_type is tuple:
            if Fory_PyFloatSequenceReadFromBuffer(collection_, &buffer.c_buffer, len_) == 0:
                return
        for i in range(len_):
            self._add_element(collection_, i, buffer.read_double())

    cpdef _write_same_type_no_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        if not self.fory.xlang:
            for s in value:
                typeinfo.serializer.write(buffer, s)
        else:
            for s in value:
                typeinfo.serializer.xwrite(buffer, s)

    cpdef _read_same_type_no_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef TypeResolver type_resolver = self.type_resolver
        self.fory.inc_depth()
        if not self.fory.xlang:
            for i in range(len_):
                obj = typeinfo.serializer.read(buffer)
                self._add_element(collection_, i, obj)
        else:
            for i in range(len_):
                obj = self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer)
                self._add_element(collection_, i, obj)
        self.fory.dec_depth()

    cpdef _write_same_type_has_null(self, Buffer buffer, value, TypeInfo typeinfo):
        if not self.fory.xlang:
            for s in value:
                if s is None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    typeinfo.serializer.write(buffer, s)
        else:
            for s in value:
                if s is None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    typeinfo.serializer.xwrite(buffer, s)

    cpdef _read_same_type_has_null(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef int8_t flag
        self.fory.inc_depth()
        if not self.fory.xlang:
            for i in range(len_):
                flag = buffer.read_int8()
                if flag == NULL_FLAG:
                    self._add_element(collection_, i, None)
                else:
                    self._add_element(collection_, i, typeinfo.serializer.read(buffer))
        else:
            for i in range(len_):
                flag = buffer.read_int8()
                if flag == NULL_FLAG:
                    self._add_element(collection_, i, None)
                else:
                    self._add_element(
                        collection_,
                        i,
                        self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer),
                    )
        self.fory.dec_depth()

    cpdef _write_same_type_ref(self, Buffer buffer, value, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef Serializer item_serializer = typeinfo.serializer
        cdef type item_cls = typeinfo.cls
        cdef ListSerializer list_serializer
        cdef TupleSerializer tuple_serializer
        cdef SetSerializer set_serializer
        cdef MapSerializer map_serializer
        cdef type value_type = type(value)
        cdef object s
        cdef Py_ssize_t i
        cdef Py_ssize_t seq_size
        cdef c_bool value_is_list = value_type is list
        cdef c_bool value_is_tuple = value_type is tuple
        cdef c_bool is_py = not self.fory.xlang
        if item_cls is list:
            list_serializer = <ListSerializer> item_serializer
            if is_py:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            list_serializer.write(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            list_serializer.write(buffer, s)
            else:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            list_serializer.xwrite(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            list_serializer.xwrite(buffer, s)
            return
        if item_cls is tuple:
            tuple_serializer = <TupleSerializer> item_serializer
            if is_py:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            tuple_serializer.write(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            tuple_serializer.write(buffer, s)
            else:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            tuple_serializer.xwrite(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            tuple_serializer.xwrite(buffer, s)
            return
        if item_cls is set:
            set_serializer = <SetSerializer> item_serializer
            if is_py:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            set_serializer.write(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            set_serializer.write(buffer, s)
            else:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            set_serializer.xwrite(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            set_serializer.xwrite(buffer, s)
            return
        if item_cls is dict:
            map_serializer = <MapSerializer> item_serializer
            if is_py:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            map_serializer.write(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            map_serializer.write(buffer, s)
            else:
                if value_is_list or value_is_tuple:
                    seq_size = Py_SIZE(value)
                    for i in range(seq_size):
                        if value_is_list:
                            s = <object>PyList_GET_ITEM(value, i)
                        else:
                            s = <object>PyTuple_GET_ITEM(value, i)
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            map_serializer.xwrite(buffer, s)
                else:
                    for s in value:
                        if not ref_resolver.write_ref_or_null(buffer, s):
                            map_serializer.xwrite(buffer, s)
            return
        if is_py:
            if value_is_list or value_is_tuple:
                seq_size = Py_SIZE(value)
                for i in range(seq_size):
                    if value_is_list:
                        s = <object>PyList_GET_ITEM(value, i)
                    else:
                        s = <object>PyTuple_GET_ITEM(value, i)
                    if not ref_resolver.write_ref_or_null(buffer, s):
                        item_serializer.write(buffer, s)
            else:
                for s in value:
                    if not ref_resolver.write_ref_or_null(buffer, s):
                        item_serializer.write(buffer, s)
        else:
            if value_is_list or value_is_tuple:
                seq_size = Py_SIZE(value)
                for i in range(seq_size):
                    if value_is_list:
                        s = <object>PyList_GET_ITEM(value, i)
                    else:
                        s = <object>PyTuple_GET_ITEM(value, i)
                    if not ref_resolver.write_ref_or_null(buffer, s):
                        item_serializer.xwrite(buffer, s)
            else:
                for s in value:
                    if not ref_resolver.write_ref_or_null(buffer, s):
                        item_serializer.xwrite(buffer, s)

    cpdef _read_same_type_ref(self, Buffer buffer, int64_t len_, object collection_, TypeInfo typeinfo):
        cdef MapRefResolver ref_resolver = self.ref_resolver
        cdef Serializer item_serializer = typeinfo.serializer
        cdef type item_cls = typeinfo.cls
        cdef ListSerializer list_serializer
        cdef TupleSerializer tuple_serializer
        cdef SetSerializer set_serializer
        cdef MapSerializer map_serializer
        cdef c_bool is_py = not self.fory.xlang
        cdef int32_t ref_id
        cdef object obj
        cdef int64_t i
        self.fory.inc_depth()
        if item_cls is list:
            list_serializer = <ListSerializer> item_serializer
            if is_py:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = list_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            else:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = list_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            self.fory.dec_depth()
            return
        if item_cls is tuple:
            tuple_serializer = <TupleSerializer> item_serializer
            if is_py:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = tuple_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            else:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = tuple_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            self.fory.dec_depth()
            return
        if item_cls is set:
            set_serializer = <SetSerializer> item_serializer
            if is_py:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = set_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            else:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = set_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            self.fory.dec_depth()
            return
        if item_cls is dict:
            map_serializer = <MapSerializer> item_serializer
            if is_py:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = map_serializer.read(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            else:
                for i in range(len_):
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        obj = ref_resolver.read_object
                    else:
                        obj = map_serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, obj)
                    self._add_element(collection_, i, obj)
            self.fory.dec_depth()
            return
        if is_py:
            for i in range(len_):
                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                if ref_id < NOT_NULL_VALUE_FLAG:
                    obj = ref_resolver.read_object
                else:
                    obj = item_serializer.read(buffer)
                    ref_resolver.set_read_object(ref_id, obj)
                self._add_element(collection_, i, obj)
        else:
            for i in range(len_):
                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                if ref_id < NOT_NULL_VALUE_FLAG:
                    obj = ref_resolver.read_object
                else:
                    obj = item_serializer.xread(buffer)
                    ref_resolver.set_read_object(ref_id, obj)
                self._add_element(collection_, i, obj)
        self.fory.dec_depth()

    cpdef _add_element(self, object collection_, int64_t index, object element):
        raise NotImplementedError

    cpdef xwrite(self, Buffer buffer, value):
        self.write(buffer, value)

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
        cdef c_bool is_py = not self.fory.xlang
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
                if type_id == <uint8_t>TypeId.STRING:
                    self._read_string(buffer, len_, list_)
                    return list_
                elif type_id == <uint8_t>TypeId.VARINT64:
                    self._read_int(buffer, len_, list_)
                    return list_
                elif type_id == <uint8_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, list_)
                    return list_
                elif type_id == <uint8_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, list_)
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
                    elem = get_next_element(buffer, ref_resolver, type_resolver, is_py)
                    Py_INCREF(elem)
                    PyList_SET_ITEM(list_, i, elem)
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    if is_py:
                        elem = typeinfo.serializer.read(buffer)
                    else:
                        elem = self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer)
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
                        if is_py:
                            elem = typeinfo.serializer.read(buffer)
                        else:
                            elem = self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer)
                    Py_INCREF(elem)
                    PyList_SET_ITEM(list_, i, elem)
            self.fory.dec_depth()
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
    cdef object o
    ref_id = ref_resolver.try_preserve_ref_id(buffer)
    if ref_id < NOT_NULL_VALUE_FLAG:
        return ref_resolver.read_object
    cdef uint8_t type_id
    cdef int32_t type_id_offset
    if ref_id == NOT_NULL_VALUE_FLAG:
        type_id = buffer.read_uint8()
        if type_id == <uint8_t>TypeId.STRING:
            return buffer.read_string()
        elif type_id == <uint8_t>TypeId.VARINT32:
            return buffer.read_varint32()
        elif type_id == <uint8_t>TypeId.VARINT64:
            return buffer.read_varint64()
        elif type_id == <uint8_t>TypeId.BOOL:
            return buffer.read_bool()
        elif type_id == <uint8_t>TypeId.FLOAT64:
            return buffer.read_double()
        if not is_namespaced_type(<TypeId>type_id):
            typeinfo = type_resolver.get_type_info_by_id(type_id)
        else:
            type_id_offset = buffer.get_reader_index() - 1
            buffer.set_reader_index(type_id_offset)
            typeinfo = type_resolver.read_type_info(buffer)
    else:
        type_id = buffer.read_uint8()
        if type_id == <uint8_t>TypeId.STRING:
            o = buffer.read_string()
            ref_resolver.set_read_object(ref_id, o)
            return o
        elif type_id == <uint8_t>TypeId.VARINT32:
            o = buffer.read_varint32()
            ref_resolver.set_read_object(ref_id, o)
            return o
        elif type_id == <uint8_t>TypeId.VARINT64:
            o = buffer.read_varint64()
            ref_resolver.set_read_object(ref_id, o)
            return o
        elif type_id == <uint8_t>TypeId.BOOL:
            o = buffer.read_bool()
            ref_resolver.set_read_object(ref_id, o)
            return o
        elif type_id == <uint8_t>TypeId.FLOAT64:
            o = buffer.read_double()
            ref_resolver.set_read_object(ref_id, o)
            return o
        if not is_namespaced_type(<TypeId>type_id):
            typeinfo = type_resolver.get_type_info_by_id(type_id)
        else:
            type_id_offset = buffer.get_reader_index() - 1
            buffer.set_reader_index(type_id_offset)
            typeinfo = type_resolver.read_type_info(buffer)
    # Note that all read operations in fast paths of list/tuple/set/dict/sub_dict
    # must match corresponding writing operations. Otherwise, ref tracking will
    # error.
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
        cdef int32_t len_ = buffer.read_var_uint32()
        cdef tuple tuple_ = PyTuple_New(len_)
        if len_ == 0:
            return tuple_
        cdef int8_t collect_flag = buffer.read_int8()
        cdef c_bool is_py = not self.fory.xlang
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
                if type_id == <uint8_t>TypeId.STRING:
                    self._read_string(buffer, len_, tuple_)
                    return tuple_
                if type_id == <uint8_t>TypeId.VARINT64:
                    self._read_int(buffer, len_, tuple_)
                    return tuple_
                if type_id == <uint8_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, tuple_)
                    return tuple_
                if type_id == <uint8_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, tuple_)
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
                    elem = get_next_element(buffer, ref_resolver, type_resolver, is_py)
                    Py_INCREF(elem)
                    PyTuple_SET_ITEM(tuple_, i, elem)
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    if is_py:
                        elem = typeinfo.serializer.read(buffer)
                    else:
                        elem = self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer)
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
                        if is_py:
                            elem = typeinfo.serializer.read(buffer)
                        else:
                            elem = self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer)
                    Py_INCREF(elem)
                    PyTuple_SET_ITEM(tuple_, i, elem)
            self.fory.dec_depth()
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
        cdef int32_t len_ = buffer.read_var_uint32()
        if len_ == 0:
            return instance
        cdef int8_t collect_flag = buffer.read_int8()
        cdef int32_t ref_id
        cdef TypeInfo typeinfo
        cdef uint8_t type_id = 0
        cdef c_bool is_py = not self.fory.xlang
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
                if type_id == <uint8_t>TypeId.STRING:
                    self._read_string(buffer, len_, instance)
                    return instance
                if type_id == <uint8_t>TypeId.VARINT64:
                    self._read_int(buffer, len_, instance)
                    return instance
                if type_id == <uint8_t>TypeId.BOOL:
                    self._read_bool(buffer, len_, instance)
                    return instance
                if type_id == <uint8_t>TypeId.FLOAT64:
                    self._read_float(buffer, len_, instance)
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
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        instance.add(ref_resolver.read_object)
                        continue
                    # indicates that the object is first read.
                    typeinfo = type_resolver.read_type_info(buffer)
                    type_id = typeinfo.type_id
                    if type_id == <uint8_t>TypeId.STRING:
                        instance.add(buffer.read_string())
                    elif type_id == <uint8_t>TypeId.VARINT64:
                        instance.add(buffer.read_varint64())
                    elif type_id == <uint8_t>TypeId.BOOL:
                        instance.add(buffer.read_bool())
                    elif type_id == <uint8_t>TypeId.FLOAT64:
                        instance.add(buffer.read_double())
                    else:
                        if is_py:
                            o = typeinfo.serializer.read(buffer)
                        else:
                            o = typeinfo.serializer.xread(buffer)
                        ref_resolver.set_read_object(ref_id, o)
                        instance.add(o)
            elif not has_null:
                # When ref tracking is disabled and no nulls, read type info directly
                for i in range(len_):
                    typeinfo = type_resolver.read_type_info(buffer)
                    type_id = typeinfo.type_id
                    if type_id == <uint8_t>TypeId.STRING:
                        instance.add(buffer.read_string())
                    elif type_id == <uint8_t>TypeId.VARINT64:
                        instance.add(buffer.read_varint64())
                    elif type_id == <uint8_t>TypeId.BOOL:
                        instance.add(buffer.read_bool())
                    elif type_id == <uint8_t>TypeId.FLOAT64:
                        instance.add(buffer.read_double())
                    else:
                        if is_py:
                            instance.add(typeinfo.serializer.read(buffer))
                        else:
                            instance.add(self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer))
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
                        elif type_id == <uint8_t>TypeId.VARINT64:
                            instance.add(buffer.read_varint64())
                        elif type_id == <uint8_t>TypeId.BOOL:
                            instance.add(buffer.read_bool())
                        elif type_id == <uint8_t>TypeId.FLOAT64:
                            instance.add(buffer.read_double())
                        else:
                            if is_py:
                                instance.add(typeinfo.serializer.read(buffer))
                            else:
                                instance.add(self.fory.xread_no_ref(buffer, serializer=typeinfo.serializer))
            self.fory.dec_depth()
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

    cdef inline c_bool _write_homogeneous_string_key_map_fast_path(
            self, Buffer buffer, dict obj, int32_t length):
        if self.key_serializer is not None or self.value_serializer is not None:
            return False
        cdef int value_kind = Fory_PyDetectStringKeyMapValueKind(obj)
        if value_kind == kForyPyStringMapValueNone:
            return False
        cdef TypeResolver type_resolver = self.type_resolver
        cdef TypeInfo key_type_info = type_resolver.get_type_info(str)
        cdef TypeInfo value_type_info
        if value_kind == kForyPyStringMapValueInt64:
            value_type_info = type_resolver.get_type_info(int)
        else:
            value_type_info = type_resolver.get_type_info(str)
        cdef Py_ssize_t pos = 0
        cdef int32_t remaining = length
        cdef int32_t chunk_size
        cdef int status
        while remaining > 0:
            chunk_size = remaining
            if chunk_size > MAX_CHUNK_SIZE:
                chunk_size = MAX_CHUNK_SIZE
            # Homogeneous string key/value chunks do not track refs and do not use declared types.
            buffer.write_uint8(0)
            buffer.write_uint8(chunk_size)
            type_resolver.write_type_info(buffer, key_type_info)
            type_resolver.write_type_info(buffer, value_type_info)
            if value_kind == kForyPyStringMapValueInt64:
                status = Fory_PyStringInt64MapWriteChunkToBuffer(
                    obj, &pos, chunk_size, &buffer.c_buffer
                )
            else:
                status = Fory_PyStringStringMapWriteChunkToBuffer(
                    obj, &pos, chunk_size, &buffer.c_buffer
                )
            if status != 0:
                raise RuntimeError("c++ fast path failed for homogeneous string-key map")
            remaining -= chunk_size
        return True

    cpdef inline write(self, Buffer buffer, o):
        cdef dict obj = o
        cdef int32_t length = len(obj)
        buffer.write_var_uint32(length)
        if length == 0:
            return
        if self._write_homogeneous_string_key_map_fast_path(buffer, obj, length):
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
        cdef Py_ssize_t fast_chunk_size
        cdef int64_t next_key_addr, next_value_addr
        cdef c_bool key_write_ref, value_write_ref
        cdef int has_next = PyDict_Next(obj, &pos, <PyObject **>&key_addr, <PyObject **>&value_addr)
        cdef c_bool is_py = not self.fory.xlang
        cdef int status
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
                            fory.write_ref(buffer, key)
                        else:
                            fory.xwrite_ref(buffer, key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            value_write_ref = self.value_tracking_ref == 1
                            if value_write_ref:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
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
                                fory.write_ref(buffer, value)
                            else:
                                fory.xwrite_ref(buffer, value)
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
            if (not key_write_ref and not value_write_ref and key_cls is str and
                    (value_serializer_type is Int64Serializer or
                     value_serializer_type is StringSerializer)):
                if value_serializer_type is Int64Serializer:
                    status = Fory_PyStringInt64MapWriteContiguousChunkToBuffer(
                        obj,
                        &pos,
                        key,
                        value,
                        MAX_CHUNK_SIZE,
                        &buffer.c_buffer,
                        &fast_chunk_size,
                        &has_next,
                        &next_key_addr,
                        &next_value_addr,
                    )
                else:
                    status = Fory_PyStringStringMapWriteContiguousChunkToBuffer(
                        obj,
                        &pos,
                        key,
                        value,
                        MAX_CHUNK_SIZE,
                        &buffer.c_buffer,
                        &fast_chunk_size,
                        &has_next,
                        &next_key_addr,
                        &next_value_addr,
                    )
                if status != 0:
                    raise RuntimeError(
                        "c++ fast path failed for contiguous string-key map write chunk"
                    )
                chunk_size = <int32_t>fast_chunk_size
                if has_next != 0:
                    key_addr = next_key_addr
                    value_addr = next_value_addr
                key_serializer = self.key_serializer
                value_serializer = self.value_serializer
                buffer.put_int8(chunk_size_offset, chunk_size)
                continue
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
        cdef int32_t size = buffer.read_var_uint32()
        cdef dict map_ = _PyDict_NewPresized(size)
        ref_resolver.reference(map_)
        if size == 0:
            return map_
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
        cdef int status
        cdef c_bool is_py = not self.fory.xlang
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
                                    key = ref_resolver.read_object
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
                                    key = fory.xread_no_ref(buffer, serializer=key_serializer)
                        else:
                            if is_py:
                                key = fory.read_ref(buffer)
                            else:
                                key = fory.xread_ref(buffer)
                        map_[key] = None
                else:
                    if not value_has_null:
                        track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
                        if (chunk_header & VALUE_DECL_TYPE) != 0:
                            if track_value_ref:
                                ref_id = ref_resolver.try_preserve_ref_id(buffer)
                                if ref_id < NOT_NULL_VALUE_FLAG:
                                    value = ref_resolver.read_object
                                else:
                                    if is_py:
                                        value = (<object> value_serializer).read(buffer)
                                    else:
                                        value = (<object> value_serializer).xread(buffer)
                                    ref_resolver.set_read_object(ref_id, value)
                            else:
                                if is_py:
                                    value = (<object> value_serializer).read(buffer)
                                else:
                                    value = fory.xread_no_ref(buffer, serializer=value_serializer)
                        else:
                            if is_py:
                                value = fory.read_ref(buffer)
                            else:
                                value = fory.xread_ref(buffer)
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
            if (not track_key_ref and not track_value_ref and
                    key_serializer_type is StringSerializer and
                    (value_serializer_type is StringSerializer or
                     value_serializer_type is Int64Serializer)):
                if value_serializer_type is Int64Serializer:
                    status = Fory_PyStringInt64MapReadChunkFromBuffer(
                        map_, chunk_size, &buffer.c_buffer
                    )
                else:
                    status = Fory_PyStringStringMapReadChunkFromBuffer(
                        map_, chunk_size, &buffer.c_buffer
                    )
                if status != 0:
                    raise RuntimeError(
                        "c++ fast path failed for homogeneous string-key map read"
                    )
                size -= chunk_size
                if size != 0:
                    chunk_header = buffer.read_uint8()
                continue
            for i in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_resolver.read_object
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
                            key = (<object> key_serializer).read(buffer)
                        else:
                            key = fory.xread_no_ref(buffer, serializer=key_serializer)
                if track_value_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_resolver.read_object
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
                            value = (<object> value_serializer).read(buffer)
                        else:
                            value = fory.xread_no_ref(buffer, serializer=value_serializer)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = buffer.read_uint8()
        self.fory.dec_depth()
        return map_

    cpdef inline xwrite(self, Buffer buffer, o):
        self.write(buffer, o)

    cpdef inline xread(self, Buffer buffer):
        return self.read(buffer)
