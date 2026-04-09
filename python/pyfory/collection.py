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
Pure Python collection serializers for debugging and Python-only execution.

In Cython mode the active collection serializers live in `collection.pxi` and
are imported through `pyfory.serialization`. This module is the pure-Python
fallback only.
"""

from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory._serializer import Serializer, StringSerializer
from pyfory.resolver import NOT_NULL_VALUE_FLAG, NULL_FLAG

COLL_DEFAULT_FLAG = 0b0
COLL_TRACKING_REF = 0b1
COLL_HAS_NULL = 0b10
COLL_IS_DECL_ELEMENT_TYPE = 0b100
COLL_IS_SAME_TYPE = 0b1000


class CollectionSerializer(Serializer):
    __slots__ = (
        "elem_serializer",
        "elem_tracking_ref",
        "elem_type",
        "elem_type_info",
    )

    def __init__(self, type_resolver, type_, elem_serializer=None, elem_tracking_ref=None):
        super().__init__(type_resolver, type_)
        self.elem_serializer = elem_serializer
        if elem_serializer is None:
            self.elem_type = None
            self.elem_type_info = self.type_resolver.get_type_info(None)
            self.elem_tracking_ref = -1
        else:
            self.elem_type = elem_serializer.type_
            self.elem_type_info = self.type_resolver.get_type_info(self.elem_type)
            self.elem_tracking_ref = int(elem_serializer.need_to_write_ref)
            if elem_tracking_ref is not None:
                self.elem_tracking_ref = 1 if elem_tracking_ref else 0

    def write_header(self, write_context, value):
        collect_flag = COLL_DEFAULT_FLAG
        elem_type = self.elem_type
        elem_type_info = self.elem_type_info
        has_null = False
        has_same_type = True
        if elem_type is None:
            for item in value:
                if item is None:
                    has_null = True
                    continue
                if elem_type is None:
                    elem_type = type(item)
                elif has_same_type and type(item) is not elem_type:
                    has_same_type = False
            if has_same_type:
                collect_flag |= COLL_IS_SAME_TYPE
                if elem_type is not None:
                    elem_type_info = self.type_resolver.get_type_info(elem_type)
        else:
            collect_flag |= COLL_IS_DECL_ELEMENT_TYPE | COLL_IS_SAME_TYPE
            for item in value:
                if item is None:
                    has_null = True
                    break

        if has_null:
            collect_flag |= COLL_HAS_NULL
        if write_context.track_ref:
            if self.elem_tracking_ref == 1:
                collect_flag |= COLL_TRACKING_REF
            elif self.elem_tracking_ref == -1:
                if not has_same_type or elem_type_info.serializer.need_to_write_ref:
                    collect_flag |= COLL_TRACKING_REF
        write_context.write_var_uint32(len(value))
        write_context.write_int8(collect_flag)
        if has_same_type and (collect_flag & COLL_IS_DECL_ELEMENT_TYPE) == 0:
            self.type_resolver.write_type_info(write_context, elem_type_info)
        return collect_flag, elem_type_info

    def write(self, write_context, value):
        if len(value) == 0:
            write_context.write_var_uint32(0)
            return
        collect_flag, typeinfo = self.write_header(write_context, value)
        serializer = (
            self.elem_serializer if (collect_flag & COLL_IS_DECL_ELEMENT_TYPE) != 0 and self.elem_serializer is not None else typeinfo.serializer
        )
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if (collect_flag & COLL_TRACKING_REF) != 0:
                self._write_same_type_ref(write_context, value, serializer)
            elif (collect_flag & COLL_HAS_NULL) == 0:
                self._write_same_type_no_ref(write_context, value, serializer)
            else:
                self._write_same_type_has_null(write_context, value, serializer)
        else:
            self._write_different_types(write_context, value, collect_flag)

    def _write_same_type_no_ref(self, write_context, value, serializer):
        for item in value:
            serializer.write(write_context, item)

    def _write_same_type_has_null(self, write_context, value, serializer):
        for item in value:
            if item is None:
                write_context.write_int8(NULL_FLAG)
            else:
                write_context.write_int8(NOT_NULL_VALUE_FLAG)
                serializer.write(write_context, item)

    def _write_same_type_ref(self, write_context, value, serializer):
        ref_writer = write_context.ref_writer
        for item in value:
            if not ref_writer.write_ref_or_null(write_context, item):
                serializer.write(write_context, item)

    def _write_different_types(self, write_context, value, collect_flag=0):
        tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
        has_null = (collect_flag & COLL_HAS_NULL) != 0
        ref_writer = write_context.ref_writer
        if tracking_ref:
            for item in value:
                if not ref_writer.write_ref_or_null(write_context, item):
                    typeinfo = self.type_resolver.get_type_info(type(item))
                    self.type_resolver.write_type_info(write_context, typeinfo)
                    typeinfo.serializer.write(write_context, item)
            return
        if not has_null:
            for item in value:
                typeinfo = self.type_resolver.get_type_info(type(item))
                self.type_resolver.write_type_info(write_context, typeinfo)
                typeinfo.serializer.write(write_context, item)
            return
        for item in value:
            if item is None:
                write_context.write_int8(NULL_FLAG)
            else:
                write_context.write_int8(NOT_NULL_VALUE_FLAG)
                typeinfo = self.type_resolver.get_type_info(type(item))
                self.type_resolver.write_type_info(write_context, typeinfo)
                typeinfo.serializer.write(write_context, item)

    def read(self, read_context):
        length = read_context.read_var_uint32()
        if length > read_context.max_collection_size:
            raise ValueError(f"Collection size {length} exceeds the configured limit of {read_context.max_collection_size}")
        collection_ = self.new_instance(read_context, self.type_)
        if length == 0:
            return collection_
        collect_flag = read_context.read_int8()
        if (collect_flag & COLL_IS_SAME_TYPE) != 0:
            if (collect_flag & COLL_IS_DECL_ELEMENT_TYPE) == 0:
                typeinfo = self.type_resolver.read_type_info(read_context)
                serializer = typeinfo.serializer
            else:
                serializer = self.elem_serializer
            if (collect_flag & COLL_TRACKING_REF) != 0:
                self._read_same_type_ref(read_context, length, collection_, serializer)
            elif (collect_flag & COLL_HAS_NULL) == 0:
                self._read_same_type_no_ref(read_context, length, collection_, serializer)
            else:
                self._read_same_type_has_null(read_context, length, collection_, serializer)
        else:
            self._read_different_types(read_context, length, collection_, collect_flag)
        return collection_

    def new_instance(self, read_context, type_):
        raise NotImplementedError

    def _add_element(self, collection_, element):
        raise NotImplementedError

    def _read_same_type_no_ref(self, read_context, length, collection_, serializer):
        read_context.increase_depth()
        for _ in range(length):
            self._add_element(collection_, read_context.read_no_ref(serializer=serializer))
        read_context.decrease_depth()

    def _read_same_type_has_null(self, read_context, length, collection_, serializer):
        read_context.increase_depth()
        for _ in range(length):
            if read_context.read_int8() == NULL_FLAG:
                self._add_element(collection_, None)
            else:
                self._add_element(collection_, read_context.read_no_ref(serializer=serializer))
        read_context.decrease_depth()

    def _read_same_type_ref(self, read_context, length, collection_, serializer):
        read_context.increase_depth()
        ref_reader = read_context.ref_reader
        for _ in range(length):
            ref_id = ref_reader.try_preserve_ref_id(read_context)
            if ref_id < NOT_NULL_VALUE_FLAG:
                obj = ref_reader.get_read_ref()
            else:
                obj = serializer.read(read_context)
                ref_reader.set_read_ref(ref_id, obj)
            self._add_element(collection_, obj)
        read_context.decrease_depth()

    def _read_different_types(self, read_context, length, collection_, collect_flag):
        read_context.increase_depth()
        tracking_ref = (collect_flag & COLL_TRACKING_REF) != 0
        has_null = (collect_flag & COLL_HAS_NULL) != 0
        if tracking_ref:
            for _ in range(length):
                self._add_element(collection_, get_next_element(read_context))
            read_context.decrease_depth()
            return
        if not has_null:
            for _ in range(length):
                typeinfo = self.type_resolver.read_type_info(read_context)
                elem = None if typeinfo is None else read_context.read_no_ref(serializer=typeinfo.serializer)
                self._add_element(collection_, elem)
            read_context.decrease_depth()
            return
        for _ in range(length):
            head_flag = read_context.read_int8()
            if head_flag == NULL_FLAG:
                elem = None
            else:
                typeinfo = self.type_resolver.read_type_info(read_context)
                elem = None if typeinfo is None else read_context.read_no_ref(serializer=typeinfo.serializer)
            self._add_element(collection_, elem)
        read_context.decrease_depth()


class ListSerializer(CollectionSerializer):
    def new_instance(self, read_context, type_):
        instance = []
        read_context.reference(instance)
        return instance

    def _add_element(self, collection_, element):
        collection_.append(element)


class TupleSerializer(CollectionSerializer):
    def new_instance(self, read_context, type_):
        return []

    def _add_element(self, collection_, element):
        collection_.append(element)

    def read(self, read_context):
        return tuple(super().read(read_context))


class StringArraySerializer(ListSerializer):
    def __init__(self, type_resolver, type_):
        super().__init__(type_resolver, type_, StringSerializer(type_resolver, str))


class SetSerializer(CollectionSerializer):
    def new_instance(self, read_context, type_):
        instance = set()
        read_context.reference(instance)
        return instance

    def _add_element(self, collection_, element):
        collection_.add(element)


def get_next_element(read_context):
    ref_reader = read_context.ref_reader
    ref_id = ref_reader.try_preserve_ref_id(read_context)
    if ref_id < NOT_NULL_VALUE_FLAG:
        return ref_reader.get_read_ref()
    typeinfo = read_context.type_resolver.read_type_info(read_context)
    obj = typeinfo.serializer.read(read_context)
    ref_reader.set_read_ref(ref_id, obj)
    return obj


MAX_CHUNK_SIZE = 255
TRACKING_KEY_REF = 0b1
KEY_HAS_NULL = 0b10
KEY_DECL_TYPE = 0b100
TRACKING_VALUE_REF = 0b1000
VALUE_HAS_NULL = 0b10000
VALUE_DECL_TYPE = 0b100000
KV_NULL = KEY_HAS_NULL | VALUE_HAS_NULL
NULL_KEY_VALUE_DECL_TYPE = KEY_HAS_NULL | VALUE_DECL_TYPE
NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF = KEY_HAS_NULL | VALUE_DECL_TYPE | TRACKING_VALUE_REF
NULL_VALUE_KEY_DECL_TYPE = VALUE_HAS_NULL | KEY_DECL_TYPE
NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF = VALUE_HAS_NULL | KEY_DECL_TYPE | TRACKING_KEY_REF


class MapSerializer(Serializer):
    def __init__(
        self,
        type_resolver,
        type_,
        key_serializer=None,
        value_serializer=None,
        key_tracking_ref=None,
        value_tracking_ref=None,
    ):
        super().__init__(type_resolver, type_)
        self.key_serializer = key_serializer
        self.value_serializer = value_serializer
        self.key_tracking_ref = False
        self.value_tracking_ref = False
        if key_serializer is not None:
            self.key_tracking_ref = bool(key_serializer.need_to_write_ref)
            if key_tracking_ref is not None:
                self.key_tracking_ref = bool(key_tracking_ref) and type_resolver.track_ref
        if value_serializer is not None:
            self.value_tracking_ref = bool(value_serializer.need_to_write_ref)
            if value_tracking_ref is not None:
                self.value_tracking_ref = bool(value_tracking_ref) and type_resolver.track_ref

    def write(self, write_context, obj):
        length = len(obj)
        write_context.write_var_uint32(length)
        if length == 0:
            return
        type_resolver = self.type_resolver
        ref_writer = write_context.ref_writer
        key_serializer = self.key_serializer
        value_serializer = self.value_serializer

        items_iter = iter(obj.items())
        key, value = next(items_iter)
        has_next = True
        while has_next:
            while True:
                if key is not None:
                    if value is not None:
                        break
                    if key_serializer is not None:
                        key_write_ref = self.key_tracking_ref
                        if key_write_ref:
                            write_context.write_int8(NULL_VALUE_KEY_DECL_TYPE_TRACKING_REF)
                            if not ref_writer.write_ref_or_null(write_context, key):
                                self._write_obj(key_serializer, write_context, key)
                        else:
                            write_context.write_int8(NULL_VALUE_KEY_DECL_TYPE)
                            self._write_obj(key_serializer, write_context, key)
                    else:
                        write_context.write_int8(VALUE_HAS_NULL | TRACKING_KEY_REF)
                        write_context.write_ref(key)
                else:
                    if value is not None:
                        if value_serializer is not None:
                            value_write_ref = self.value_tracking_ref
                            if value_write_ref:
                                write_context.write_int8(NULL_KEY_VALUE_DECL_TYPE_TRACKING_REF)
                                if not ref_writer.write_ref_or_null(write_context, value):
                                    value_serializer.write(write_context, value)
                            else:
                                write_context.write_int8(NULL_KEY_VALUE_DECL_TYPE)
                                value_serializer.write(write_context, value)
                        else:
                            write_context.write_int8(KEY_HAS_NULL | TRACKING_VALUE_REF)
                            write_context.write_ref(value)
                    else:
                        write_context.write_int8(KV_NULL)
                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            if not has_next:
                break

            key_cls = type(key)
            value_cls = type(value)
            write_context.enter_flush_barrier()
            write_context.write_int16(-1)
            chunk_size_offset = write_context.get_writer_index() - 1
            chunk_header = 0

            if key_serializer is not None:
                chunk_header |= KEY_DECL_TYPE
            else:
                key_type_info = type_resolver.get_type_info(key_cls)
                type_resolver.write_type_info(write_context, key_type_info)
                key_serializer = key_type_info.serializer

            if value_serializer is not None:
                chunk_header |= VALUE_DECL_TYPE
            else:
                value_type_info = type_resolver.get_type_info(value_cls)
                type_resolver.write_type_info(write_context, value_type_info)
                value_serializer = value_type_info.serializer

            key_write_ref = self.key_tracking_ref if self.key_serializer is not None else bool(key_serializer.need_to_write_ref)
            value_write_ref = self.value_tracking_ref if self.value_serializer is not None else bool(value_serializer.need_to_write_ref)
            if key_write_ref:
                chunk_header |= TRACKING_KEY_REF
            if value_write_ref:
                chunk_header |= TRACKING_VALUE_REF

            write_context.put_uint8(chunk_size_offset - 1, chunk_header)
            chunk_size = 0

            while chunk_size < MAX_CHUNK_SIZE:
                if key is None or value is None or type(key) is not key_cls or type(value) is not value_cls:
                    break
                if not key_write_ref or not ref_writer.write_ref_or_null(write_context, key):
                    self._write_obj(key_serializer, write_context, key)
                if not value_write_ref or not ref_writer.write_ref_or_null(write_context, value):
                    self._write_obj(value_serializer, write_context, value)
                chunk_size += 1
                try:
                    key, value = next(items_iter)
                except StopIteration:
                    has_next = False
                    break

            key_serializer = self.key_serializer
            value_serializer = self.value_serializer
            write_context.put_uint8(chunk_size_offset, chunk_size)
            write_context.exit_flush_barrier()
            write_context.try_flush()

    def read(self, read_context):
        size = read_context.read_var_uint32()
        if size > read_context.max_collection_size:
            raise ValueError(f"Map size {size} exceeds the configured limit of {read_context.max_collection_size}")
        map_ = {}
        ref_reader = read_context.ref_reader
        read_context.reference(map_)
        chunk_header = read_context.read_uint8() if size != 0 else 0
        key_serializer = self.key_serializer
        value_serializer = self.value_serializer
        read_context.increase_depth()
        while size > 0:
            while True:
                key_has_null = (chunk_header & KEY_HAS_NULL) != 0
                value_has_null = (chunk_header & VALUE_HAS_NULL) != 0
                if not key_has_null and not value_has_null:
                    break
                if not key_has_null:
                    track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
                    if (chunk_header & KEY_DECL_TYPE) != 0:
                        if track_key_ref:
                            ref_id = ref_reader.try_preserve_ref_id(read_context)
                            if ref_id < NOT_NULL_VALUE_FLAG:
                                key = ref_reader.get_read_ref()
                            else:
                                key = self._read_obj(key_serializer, read_context)
                                ref_reader.set_read_ref(ref_id, key)
                        else:
                            key = self._read_obj_no_ref(key_serializer, read_context)
                    else:
                        key = read_context.read_ref()
                    map_[key] = None
                elif not value_has_null:
                    track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
                    if (chunk_header & VALUE_DECL_TYPE) != 0:
                        if track_value_ref:
                            ref_id = ref_reader.try_preserve_ref_id(read_context)
                            if ref_id < NOT_NULL_VALUE_FLAG:
                                value = ref_reader.get_read_ref()
                            else:
                                value = self._read_obj(value_serializer, read_context)
                                ref_reader.set_read_ref(ref_id, value)
                        else:
                            value = self._read_obj_no_ref(value_serializer, read_context)
                    else:
                        value = read_context.read_ref()
                    map_[None] = value
                else:
                    map_[None] = None
                size -= 1
                if size == 0:
                    read_context.decrease_depth()
                    return map_
                chunk_header = read_context.read_uint8()

            track_key_ref = (chunk_header & TRACKING_KEY_REF) != 0
            track_value_ref = (chunk_header & TRACKING_VALUE_REF) != 0
            key_is_declared_type = (chunk_header & KEY_DECL_TYPE) != 0
            value_is_declared_type = (chunk_header & VALUE_DECL_TYPE) != 0
            chunk_size = read_context.read_uint8()
            if not key_is_declared_type:
                key_serializer = self.type_resolver.read_type_info(read_context).serializer
            if not value_is_declared_type:
                value_serializer = self.type_resolver.read_type_info(read_context).serializer
            for _ in range(chunk_size):
                if track_key_ref:
                    ref_id = ref_reader.try_preserve_ref_id(read_context)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        key = ref_reader.get_read_ref()
                    else:
                        key = self._read_obj(key_serializer, read_context)
                        ref_reader.set_read_ref(ref_id, key)
                else:
                    key = self._read_obj_no_ref(key_serializer, read_context)
                if track_value_ref:
                    ref_id = ref_reader.try_preserve_ref_id(read_context)
                    if ref_id < NOT_NULL_VALUE_FLAG:
                        value = ref_reader.get_read_ref()
                    else:
                        value = self._read_obj(value_serializer, read_context)
                        ref_reader.set_read_ref(ref_id, value)
                else:
                    value = self._read_obj_no_ref(value_serializer, read_context)
                map_[key] = value
                size -= 1
            if size != 0:
                chunk_header = read_context.read_uint8()
        read_context.decrease_depth()
        return map_

    def _write_obj(self, serializer, write_context, obj):
        serializer.write(write_context, obj)

    def _read_obj(self, serializer, read_context):
        return serializer.read(read_context)

    def _read_obj_no_ref(self, serializer, read_context):
        return read_context.read_no_ref(serializer=serializer)


SubMapSerializer = MapSerializer


if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import (
        CollectionSerializer as CythonCollectionSerializer,
        ListSerializer as CythonListSerializer,
        TupleSerializer as CythonTupleSerializer,
        StringArraySerializer as CythonStringArraySerializer,
        SetSerializer as CythonSetSerializer,
        MapSerializer as CythonMapSerializer,
    )

    CollectionSerializer = CythonCollectionSerializer
    ListSerializer = CythonListSerializer
    TupleSerializer = CythonTupleSerializer
    StringArraySerializer = CythonStringArraySerializer
    SetSerializer = CythonSetSerializer
    MapSerializer = CythonMapSerializer
    SubMapSerializer = CythonMapSerializer
