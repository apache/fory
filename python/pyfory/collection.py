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
Pure Python collection serializers for debugging purposes only.

This module provides pure Python implementations of collection serializers
(List, Tuple, Set, Map, etc.) used when Cython serialization is disabled
via ENABLE_FORY_CYTHON_SERIALIZATION=0.

For production use, see collection.pxi which contains the optimized Cython
implementations that are included in serialization.pyx.
"""

from pyfory._serializer import Serializer, StringSerializer
from pyfory.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG

COLL_DEFAULT_FLAG = 0b0
COLL_TRACKING_REF = 0b1
COLL_HAS_NULL = 0b10
COLL_IS_DECL_ELEMENT_TYPE = 0b100
COLL_IS_SAME_TYPE = 0b1000
COLL_DECL_SAME_TYPE_TRACKING_REF = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE | COLL_TRACKING_REF
COLL_DECL_SAME_TYPE_NOT_TRACKING_REF = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
COLL_DECL_SAME_TYPE_HAS_NULL = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE | COLL_HAS_NULL
COLL_DECL_SAME_TYPE_NOT_HAS_NULL = COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE


class CollectionSerializer(Serializer):
    __slots__ = (
        "type_resolver",
        "ref_resolver",
        "elem_serializer",
        "elem_tracking_ref",
        "elem_type",
        "elem_type_info",
    )

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
            self.elem_tracking_ref = int(elem_serializer.need_to_write_ref)
            if elem_tracking_ref is not None:
                self.elem_tracking_ref = 1 if elem_tracking_ref else 0

    def write_header(self, buffer, value):
        collect_flag = COLL_DEFAULT_FLAG
        elem_type = self.elem_type
        elem_type_info = self.elem_type_info
        has_null = False
        has_same_type = True
        if elem_type is None:
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
                if elem_type is not None:
                    elem_type_info = self.type_resolver.get_type_info(elem_type)
        else:
            collect_flag |= COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
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
        if has_same_type and (collect_flag & COLL_IS_DECL_ELEMENT_TYPE) == 0:
            self.type_resolver.write_type_info(buffer, elem_type_info)
        return collect_flag, elem_type_info

    def write(self, buffer, value):
        if len(value) == 0:
            buffer.write_var_uint32(0)
            return
        collect_flag, typeinfo = self.write_header(buffer, value)
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if (collect_flag & COLL_TRACKING_REF) != 0:
                self._write_same_type_ref(buffer, value, typeinfo)
            elif (collect_flag & COLL_HAS_NULL) == 0:
                self._write_same_type_no_ref(buffer, value, typeinfo)
            else:
                self._write_same_type_has_null(buffer, value, typeinfo)
        else:
            self._write_different_types(buffer, value, collect_flag)

    def _write_same_type_no_ref(self, buffer, value, typeinfo):
        for s in value:
            typeinfo.serializer.write(buffer, s)

    def _write_same_type_has_null(self, buffer, value, typeinfo):
        for s in value:
            if s is None:
                buffer.write_int8(NULL_FLAG)
            else:
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
                typeinfo.serializer.write(buffer, s)

    def _write_same_type_ref(self, buffer, value, typeinfo):
        for s in value:
            if not self.ref_resolver.write_ref_or_null(buffer, s):
                typeinfo.serializer.write(buffer, s)

    def _write_different_types(self, buffer, value, collect_flag=0):
        tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
        has_null = (collect_flag & COLL_HAS_NULL) != 0
        if tracking_ref:
            # When ref tracking is enabled, write with ref handling
            for s in value:
                if not self.ref_resolver.write_ref_or_null(buffer, s):
                    typeinfo = self.type_resolver.get_type_info(type(s))
                    self.type_resolver.write_type_info(buffer, typeinfo)
                    typeinfo.serializer.write(buffer, s)
        elif not has_null:
            # When ref tracking is disabled and no nulls, write type info directly
            for s in value:
                typeinfo = self.type_resolver.get_type_info(type(s))
                self.type_resolver.write_type_info(buffer, typeinfo)
                typeinfo.serializer.write(buffer, s)
        else:
            # When ref tracking is disabled but has nulls, write null flag first
            for s in value:
                if s is None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    typeinfo = self.type_resolver.get_type_info(type(s))
                    self.type_resolver.write_type_info(buffer, typeinfo)
                    typeinfo.serializer.write(buffer, s)

    def read(self, buffer):
        len_ = buffer.read_var_uint32()
        collection_ = self.new_instance(self.type_)
        if len_ == 0:
            return collection_
        collect_flag = buffer.read_int8()
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if collect_flag & COLL_IS_DECL_ELEMENT_TYPE == 0:
                typeinfo = self.type_resolver.read_type_info(buffer)
            else:
                typeinfo = self.elem_type_info
            if (collect_flag & COLL_TRACKING_REF) != 0:
                self._read_same_type_ref(buffer, len_, collection_, typeinfo)
            elif (collect_flag & COLL_HAS_NULL) == 0:
                self._read_same_type_no_ref(buffer, len_, collection_, typeinfo)
            else:
                self._read_same_type_has_null(buffer, len_, collection_, typeinfo)
        else:
            self._read_different_types(buffer, len_, collection_, collect_flag)
        return collection_

    def new_instance(self, type_):
        raise NotImplementedError

    def _add_element(self, collection_, element):
        raise NotImplementedError

    def _read_same_type_no_ref(self, buffer, len_, collection_, typeinfo):
        self.fory.inc_depth()
        for _ in range(len_):
            self._add_element(
                collection_,
                self.fory.read_no_ref(buffer, serializer=typeinfo.serializer),
            )
        self.fory.dec_depth()

    def _read_same_type_has_null(self, buffer, len_, collection_, typeinfo):
        self.fory.inc_depth()
        for _ in range(len_):
            if buffer.read_int8() == NULL_FLAG:
                self._add_element(collection_, None)
            else:
                self._add_element(
                    collection_,
                    self.fory.read_no_ref(buffer, serializer=typeinfo.serializer),
                )
        self.fory.dec_depth()

    def _read_same_type_ref(self, buffer, len_, collection_, typeinfo):
        self.fory.inc_depth()
        for _ in range(len_):
            ref_id = self.ref_resolver.try_preserve_ref_id(buffer)
            if ref_id < NOT_NULL_VALUE_FLAG:
                obj = self.ref_resolver.get_read_object()
            else:
                obj = typeinfo.serializer.read(buffer)
                self.ref_resolver.set_read_object(ref_id, obj)
            self._add_element(collection_, obj)
        self.fory.dec_depth()

    def _read_different_types(self, buffer, len_, collection_, collect_flag):
        self.fory.inc_depth()
        tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
        has_null = (collect_flag & COLL_HAS_NULL) != 0
        if tracking_ref:
            # When ref tracking is enabled, read with ref handling
            for i in range(len_):
                elem = get_next_element(buffer, self.ref_resolver, self.type_resolver)
                self._add_element(collection_, elem)
        elif not has_null:
            # When ref tracking is disabled and no nulls, read type info directly
            for i in range(len_):
                typeinfo = self.type_resolver.read_type_info(buffer)
                if typeinfo is None:
                    elem = None
                else:
                    elem = self.fory.read_no_ref(buffer, serializer=typeinfo.serializer)
                self._add_element(collection_, elem)
        else:
            # When ref tracking is disabled but has nulls, read null flag first
            for i in range(len_):
                head_flag = buffer.read_int8()
                if head_flag == NULL_FLAG:
                    elem = None
                else:
                    typeinfo = self.type_resolver.read_type_info(buffer)
                    if typeinfo is None:
                        elem = None
                    else:
                        elem = self.fory.read_no_ref(buffer, serializer=typeinfo.serializer)
                self._add_element(collection_, elem)
        self.fory.dec_depth()


class ListSerializer(CollectionSerializer):
    def new_instance(self, type_):
        instance = []
        self.fory.ref_resolver.reference(instance)
        return instance

    def _add_element(self, collection_, element):
        collection_.append(element)


class TupleSerializer(CollectionSerializer):
    def new_instance(self, type_):
        return []

    def _add_element(self, collection_, element):
        collection_.append(element)

    def read(self, buffer):
        return tuple(super().read(buffer))


class StringArraySerializer(ListSerializer):
    def __init__(self, fory, type_):
        super().__init__(fory, type_, StringSerializer(fory, str))


class SetSerializer(CollectionSerializer):
    def new_instance(self, type_):
        instance = set()
        self.fory.ref_resolver.reference(instance)
        return instance

    def _add_element(self, collection_, element):
        collection_.add(element)


def get_next_element(buffer, ref_resolver, type_resolver):
    ref_id = ref_resolver.try_preserve_ref_id(buffer)
    if ref_id < NOT_NULL_VALUE_FLAG:
        return ref_resolver.get_read_object()
    typeinfo = type_resolver.read_type_info(buffer)
    obj = typeinfo.serializer.read(buffer)
    ref_resolver.set_read_object(ref_id, obj)
    return obj


MAX_CHUNK_SIZE = 255
# Whether track key ref.
TRACKING_KEY_REF = 0b1
# Whether key has null.
KEY_HAS_NULL = 0b10
# Whether key is not declare type.
KEY_DECL_TYPE = 0b100
# Whether track value ref.
TRACKING_VALUE_REF = 0b1000
# Whether value has null.
VALUE_HAS_NULL = 0b10000
# Whether value is not declare type.
VALUE_DECL_TYPE = 0b100000
# When key or value is null that entry will be serialized as a new chunk with size 1.
# In such cases, chunk size will be skipped writing.
# Both key and value are null.
KV_NULL = KEY_HAS_NULL | VALUE_HAS_NULL
# Key is null, value type is declared type, and ref tracking for value is disabled.
NULL_KEY_VALUE_DECL_TYPE = KEY_HAS_NULL | VALUE_DECL_TYPE
# Key is null, value type is declared type, and ref tracking for value is enabled.
NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF = KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF
# Value is null, key type is declared type, and ref tracking for key is disabled.
NULL_VALUE_KEY_DECL_TYPE = VALUE_HAS_NULL | KEY_DECL_TYPE
# Value is null, key type is declared type, and ref tracking for key is enabled.
NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF


class MapSerializer(Serializer):
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
        self.key_tracking_ref = False
        self.value_tracking_ref = False
        if key_serializer is not None:
            self.key_tracking_ref = bool(key_serializer.need_to_write_ref)
            if key_tracking_ref is not None:
                self.key_tracking_ref = bool(key_tracking_ref) and fory.track_ref
        if value_serializer is not None:
            self.value_tracking_ref = bool(value_serializer.need_to_write_ref)
            if value_tracking_ref is not None:
                self.value_tracking_ref = bool(value_tracking_ref) and fory.track_ref

    def write(self, buffer, o):
        obj = o
        length = len(obj)
        buffer.write_var_uint32(length)
        if length == 0:
            return
        fory = self.fory
        type_resolver = fory.type_resolver
        ref_resolver = fory.ref_resolver
        key_serializer = self.key_serializer
        value_serializer = self.value_serializer

        items_iter = iter(obj.items())
        key, value = next(items_iter)
        has_next = True
        write_ref = fory.write_ref
        while has_next:
            while True:
                if key is not None:
                    if value is not None:
                        break
                    if key_serializer is not None:
                        key_write_ref = self.key_tracking_ref
                        if key_write_ref:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
                            if not ref_resolver.write_ref_or_null(buffer, key):
                                self._write_obj(key_serializer, buffer, key)
                        else:
                            buffer.write_int8(NULL_VALUE_KEY_DECL_TYPE)
                            self._write_obj(key_serializer, buffer, key)
                    else:
                        buffer.write_int8(VALUE_HAS_NULL | TRACKING_KEY_REF)
                        write_ref(buffer, key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            value_write_ref = self.value_tracking_ref
                            if value_write_ref:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
                                if not ref_resolver.write_ref_or_null(buffer, key):
                                    value_serializer.write(buffer, key)
                                if not ref_resolver.write_ref_or_null(buffer, value):
                                    value_serializer.write(buffer, value)
                            else:
                                buffer.write_int8(NULL_KEY_VALUE_DECL_TYPE)
                                value_serializer.write(buffer, value)
                        else:
                            buffer.write_int8(KEY_HAS_NULL | TRACKING_VALUE_REF)
                            write_ref(buffer, value)
                    else:
                        buffer.write_int8(KV_NULL)
                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            if not has_next:
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
                key_write_ref = self.key_tracking_ref
            else:
                key_write_ref = key_serializer.need_to_write_ref if key_serializer else False
            if self.value_serializer is not None:
                value_write_ref = self.value_tracking_ref
            else:
                value_write_ref = value_serializer.need_to_write_ref if value_serializer else False
            if key_write_ref:
                chunk_header |= TRACKING_KEY_REF
            if value_write_ref:
                chunk_header |= TRACKING_VALUE_REF

            buffer.put_uint8(chunk_size_offset - 1, chunk_header)
            chunk_size = 0

            while chunk_size < MAX_CHUNK_SIZE:
                if key is None or value is None or type(key) is not key_cls or type(value) is not value_cls:
                    break
                if not key_write_ref or not ref_resolver.write_ref_or_null(buffer, key):
                    self._write_obj(key_serializer, buffer, key)
                if not value_write_ref or not ref_resolver.write_ref_or_null(buffer, value):
                    self._write_obj(value_serializer, buffer, value)

                chunk_size += 1
                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            key_serializer = self.key_serializer
            value_serializer = self.value_serializer
            buffer.put_uint8(chunk_size_offset, chunk_size)

    def read(self, buffer):
        fory = self.fory
        ref_resolver = self.ref_resolver
        type_resolver = self.type_resolver
        size = buffer.read_var_uint32()
        map_ = {}
        ref_resolver.reference(map_)
        chunk_header = 0
        if size != 0:
            chunk_header = buffer.read_uint8()
        key_serializer, value_serializer = self.key_serializer, self.value_serializer
        read_ref = fory.read_ref
        fory.inc_depth()
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
                                    key = self._read_obj(key_serializer, buffer)
                                    ref_resolver.set_read_object(ref_id, key)
                            else:
                                key = self._read_obj_no_ref(key_serializer, buffer)
                        else:
                            key = read_ref(buffer)
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
                                    value = self._read_obj(value_serializer, buffer)
                                    ref_resolver.set_read_object(ref_id, value)
                            else:
                                value = self._read_obj_no_ref(value_serializer, buffer)
                        else:
                            value = read_ref(buffer)
                        map_[None] = value
                    else:
                        map_[None] = None
                size -= 1
                if size == 0:
                    fory.dec_depth()
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
            for i in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_resolver.get_read_object()
                    else:
                        key = self._read_obj(key_serializer, buffer)
                        ref_resolver.set_read_object(ref_id, key)
                else:
                    key = self._read_obj_no_ref(key_serializer, buffer)
                if track_value_ref:
                    ref_id = ref_resolver.try_preserve_ref_id(buffer)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_resolver.get_read_object()
                    else:
                        value = self._read_obj(value_serializer, buffer)
                        ref_resolver.set_read_object(ref_id, value)
                else:
                    value = self._read_obj_no_ref(value_serializer, buffer)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = buffer.read_uint8()
        fory.dec_depth()
        return map_

    def _write_obj(self, serializer, buffer, obj):
        serializer.write(buffer, obj)

    def _read_obj(self, serializer, buffer):
        return serializer.read(buffer)

    def _read_obj_no_ref(self, serializer, buffer):
        return self.fory.read_no_ref(buffer, serializer=serializer)


SubMapSerializer = MapSerializer
