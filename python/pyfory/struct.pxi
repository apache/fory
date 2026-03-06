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

import dataclasses
import typing

from cpython.unicode cimport PyUnicode_InternFromString


cdef uint8_t _BASIC_FIELD_UNSUPPORTED = 0xFF


cdef struct FieldRuntimeInfo:
    uint8_t basic_type_id
    uint8_t is_nullable
    uint8_t track_ref
    uint8_t is_dynamic
    uint8_t field_exists
    PyObject *field_name
    PyObject *serializer


@cython.final
cdef class DataClassSerializer(Serializer):
    cdef public object _type_hints
    cdef public bint _has_slots
    cdef public bint _fields_from_typedef
    cdef public object _field_names
    cdef public object _serializers
    cdef public object _nullable_fields
    cdef public object _ref_fields
    cdef public object _dynamic_fields
    cdef public object _field_infos
    cdef public object _field_metas
    cdef public object _unwrapped_hints
    cdef public int32_t _hash
    cdef public tuple _field_name_interned
    cdef tuple _serializer_owner
    cdef public object _default_values_factory
    cdef object _missing_field_defaults
    cdef vector[FieldRuntimeInfo] _field_runtime_infos

    def __init__(
        self,
        fory,
        clz: type,
        field_names: list = None,
        serializers: list = None,
        nullable_fields: dict = None,
        dynamic_fields: dict = None,
        ref_fields: dict = None,
    ):
        super().__init__(fory, clz)

        from pyfory.lib.mmh3 import hash_buffer
        from pyfory.struct import (
            _extract_field_infos,
            build_default_values_factory,
            compute_struct_fingerprint,
            compute_struct_meta,
            StructFieldSerializerVisitor,
        )
        from pyfory.type_util import get_type_hints, unwrap_optional, infer_field
        from pyfory.types import TypeId, is_primitive_type

        self._type_hints = get_type_hints(clz)
        self._has_slots = hasattr(clz, "__slots__")

        self._fields_from_typedef = field_names is not None and serializers is not None
        if self._fields_from_typedef:
            self._field_names = list(field_names)
            self._serializers = list(serializers)
            self._nullable_fields = dict(nullable_fields) if nullable_fields is not None else {}
            self._ref_fields = dict(ref_fields) if ref_fields is not None else {}
            self._dynamic_fields = dict(dynamic_fields) if dynamic_fields is not None else {}
            self._field_infos = []
            self._field_metas = {}
        else:
            self._field_infos, self._field_metas = _extract_field_infos(fory, clz, self._type_hints)

            if self._field_infos:
                self._field_names = [fi.name for fi in self._field_infos]
                self._serializers = [fi.serializer for fi in self._field_infos]
                self._nullable_fields = {fi.name: fi.nullable for fi in self._field_infos}
                self._ref_fields = {fi.name: fi.runtime_ref_tracking for fi in self._field_infos}
                self._dynamic_fields = {fi.name: fi.dynamic for fi in self._field_infos}
            else:
                self._field_names = self._get_field_names(clz)
                self._nullable_fields = dict(nullable_fields) if nullable_fields is not None else {}
                self._ref_fields = {}
                self._dynamic_fields = {}

                if self._field_names and not self._nullable_fields:
                    for field_name in self._field_names:
                        if field_name in self._type_hints:
                            unwrapped_type, is_optional = unwrap_optional(self._type_hints[field_name])
                            self._nullable_fields[field_name] = is_optional or not is_primitive_type(unwrapped_type)

                if serializers is None:
                    self._serializers = [None] * len(self._field_names)
                    visitor = StructFieldSerializerVisitor(fory)
                    for index, key in enumerate(self._field_names):
                        unwrapped_type, _ = unwrap_optional(self._type_hints.get(key, typing.Any))
                        self._serializers[index] = infer_field(key, unwrapped_type, visitor, types_path=[])
                else:
                    self._serializers = list(serializers)

        self._unwrapped_hints = self._compute_unwrapped_hints()

        if self._fields_from_typedef:
            hash_str = compute_struct_fingerprint(
                fory.type_resolver,
                self._field_names,
                self._serializers,
                self._nullable_fields,
                self._field_infos,
            )
            hash_bytes = hash_str.encode("utf-8")
            if len(hash_bytes) == 0:
                self._hash = 47
            else:
                full_hash = hash_buffer(hash_bytes, seed=47)[0]
                type_hash_32 = full_hash & 0xFFFFFFFF
                if full_hash & 0x80000000:
                    type_hash_32 -= 0x100000000
                self._hash = type_hash_32
        else:
            self._hash, self._field_names, self._serializers = compute_struct_meta(
                fory.type_resolver,
                self._field_names,
                self._serializers,
                self._nullable_fields,
                self._field_infos,
            )

        self._field_name_interned = tuple(self._intern_field_name(name) for name in self._field_names)
        self._serializer_owner = tuple(self._serializers)
        if dataclasses.is_dataclass(clz):
            self._default_values_factory = build_default_values_factory(self.fory, self._type_hints, dataclasses.fields(clz))
        else:
            self._default_values_factory = {}
        self._build_fastpath_metadata()
        self._build_missing_field_defaults()

    cdef object _intern_field_name(self, str name):
        cdef bytes encoded = name.encode("utf-8")
        cdef const char *ptr = encoded
        cdef object interned = PyUnicode_InternFromString(ptr)
        if interned is None:
            raise MemoryError("failed to intern field name")
        return interned

    cdef list _get_field_names(self, object clz):
        if hasattr(clz, "__dict__"):
            if dataclasses.is_dataclass(clz):
                return [field.name for field in dataclasses.fields(clz)]
            return sorted(self._type_hints.keys())
        if hasattr(clz, "__slots__"):
            slots = clz.__slots__
            if type(slots) is str:
                return [slots]
            return sorted(slots)
        return []

    cdef dict _compute_unwrapped_hints(self):
        from pyfory.type_util import unwrap_optional

        return {field_name: unwrap_optional(hint)[0] for field_name, hint in self._type_hints.items()}

    cdef inline uint8_t _resolve_basic_type_id(self, Serializer serializer, bint is_dynamic):
        cdef uint8_t type_id
        if is_dynamic or serializer is None:
            return _BASIC_FIELD_UNSUPPORTED
        type_id = <uint8_t>self.fory.type_resolver.get_type_info(serializer.type_).type_id
        if type_id == <uint8_t>TypeId.BOOL:
            return type_id
        if type_id == <uint8_t>TypeId.INT8:
            return type_id
        if type_id == <uint8_t>TypeId.INT16:
            return type_id
        if type_id == <uint8_t>TypeId.INT32:
            return type_id
        if type_id == <uint8_t>TypeId.VARINT32:
            return type_id
        if type_id == <uint8_t>TypeId.INT64:
            return type_id
        if type_id == <uint8_t>TypeId.VARINT64:
            return type_id
        if type_id == <uint8_t>TypeId.TAGGED_INT64:
            return type_id
        if type_id == <uint8_t>TypeId.UINT8:
            return type_id
        if type_id == <uint8_t>TypeId.UINT16:
            return type_id
        if type_id == <uint8_t>TypeId.UINT32:
            return type_id
        if type_id == <uint8_t>TypeId.VAR_UINT32:
            return type_id
        if type_id == <uint8_t>TypeId.UINT64:
            return type_id
        if type_id == <uint8_t>TypeId.VAR_UINT64:
            return type_id
        if type_id == <uint8_t>TypeId.TAGGED_UINT64:
            return type_id
        if type_id == <uint8_t>TypeId.FLOAT32:
            return type_id
        if type_id == <uint8_t>TypeId.FLOAT64:
            return type_id
        if type_id == <uint8_t>TypeId.STRING:
            return type_id
        return _BASIC_FIELD_UNSUPPORTED

    cdef void _build_fastpath_metadata(self):
        cdef Py_ssize_t i
        cdef object field_name
        cdef object serializer
        cdef set current_fields
        cdef bint is_dynamic
        cdef bint is_nullable
        cdef bint is_tracking_ref
        cdef FieldRuntimeInfo runtime_info

        self._field_runtime_infos.clear()

        current_fields = set(self._get_field_names(self.type_))
        self._field_runtime_infos.reserve(len(self._field_names))

        for i in range(len(self._field_names)):
            field_name = self._field_names[i]
            serializer = self._serializer_owner[i]
            is_nullable = bool(self._nullable_fields.get(field_name, False))
            is_tracking_ref = bool(self._ref_fields.get(field_name, False))
            is_dynamic = bool(self._dynamic_fields.get(field_name, False))

            runtime_info.basic_type_id = self._resolve_basic_type_id(serializer, is_dynamic)
            runtime_info.is_nullable = 1 if is_nullable else 0
            runtime_info.track_ref = 1 if is_tracking_ref else 0
            runtime_info.is_dynamic = 1 if is_dynamic else 0
            runtime_info.field_exists = 1 if field_name in current_fields else 0
            runtime_info.field_name = <PyObject *> self._field_name_interned[i]
            runtime_info.serializer = <PyObject *> serializer
            self._field_runtime_infos.push_back(runtime_info)

    cdef void _build_missing_field_defaults(self):
        cdef object read_field_names
        cdef object current_class_field_names
        cdef object missing_fields
        cdef list defaults
        cdef object field_name
        cdef object default_factory

        self._missing_field_defaults = ()
        if not self.fory.compatible or not self._default_values_factory:
            return

        read_field_names = set(self._field_names)
        current_class_field_names = set(self._get_field_names(self.type_))
        missing_fields = current_class_field_names - read_field_names
        if not missing_fields:
            return

        defaults = []
        for field_name, default_factory in self._default_values_factory.items():
            if field_name not in missing_fields:
                continue
            defaults.append((self._intern_field_name(field_name), default_factory))
        self._missing_field_defaults = tuple(defaults)

    cpdef inline write(self, Buffer buffer, value):
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        if self._has_slots:
            self._write_slots(buffer, value)
        else:
            self._write_dict(buffer, value)
        self.fory.try_flush()

    cdef inline void _write_dict(self, Buffer buffer, object value):
        cdef dict value_dict = value.__dict__
        cdef Py_ssize_t i
        cdef Py_ssize_t field_count = self._field_runtime_infos.size()
        cdef object field_value
        cdef object field_name
        cdef FieldRuntimeInfo *field_info

        for i in range(field_count):
            field_info = &self._field_runtime_infos[i]
            field_name = <object> field_info.field_name
            field_value = value_dict[field_name]
            self._write_field_value(buffer, field_info, field_value)

    cdef inline void _write_slots(self, Buffer buffer, object value):
        cdef Py_ssize_t i
        cdef Py_ssize_t field_count = self._field_runtime_infos.size()
        cdef object field_name
        cdef object field_value
        cdef FieldRuntimeInfo *field_info

        if self.fory.compatible:
            for i in range(field_count):
                field_info = &self._field_runtime_infos[i]
                field_name = <object> field_info.field_name
                field_value = PyObject_GetAttr(value, field_name)
                self._write_field_value(buffer, field_info, field_value)
        else:
            for i in range(field_count):
                field_info = &self._field_runtime_infos[i]
                field_name = <object> field_info.field_name
                field_value = PyObject_GetAttr(value, field_name)
                self._write_field_value(buffer, field_info, field_value)

    cdef inline void _write_field_value(self, Buffer buffer, FieldRuntimeInfo *field_info, object field_value):
        cdef uint8_t type_id = field_info.basic_type_id
        cdef bint is_nullable = field_info.is_nullable != 0
        cdef bint is_tracking_ref = field_info.track_ref != 0
        cdef bint is_dynamic = field_info.is_dynamic != 0
        cdef Serializer serializer

        if type_id != _BASIC_FIELD_UNSUPPORTED:
            if is_nullable:
                if field_value is None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    Fory_PyWriteBasicFieldToBuffer(field_value, &buffer.c_buffer, type_id)
            else:
                Fory_PyWriteBasicFieldToBuffer(field_value, &buffer.c_buffer, type_id)
            return

        serializer = <object> field_info.serializer
        if is_tracking_ref:
            if is_dynamic:
                self.fory.write_ref(buffer, field_value)
            else:
                self.fory.write_ref(buffer, field_value, serializer=serializer)
        else:
            if is_nullable:
                if field_value is None:
                    buffer.write_int8(NULL_FLAG)
                    return
                buffer.write_int8(NOT_NULL_VALUE_FLAG)
            if is_dynamic:
                self.fory.write_no_ref(buffer, field_value)
            else:
                self.fory.write_no_ref(buffer, field_value, serializer=serializer)

    cpdef inline read(self, Buffer buffer):
        cdef object obj

        if not self.fory.strict:
            self.fory.policy.authorize_instantiation(self.type_)

        if not self.fory.compatible:
            read_hash = buffer.read_int32()
            if read_hash != self._hash:
                from pyfory.error import TypeNotCompatibleError

                raise TypeNotCompatibleError(f"Hash {read_hash} is not consistent with {self._hash} for type {self.type_}")

        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)

        if self._has_slots:
            self._read_slots(buffer, obj)
        else:
            self._read_dict(buffer, obj)

        if self._missing_field_defaults:
            if self._has_slots:
                self._apply_missing_defaults_slots(obj)
            else:
                self._apply_missing_defaults_dict(obj.__dict__)
        buffer.shrink_input_buffer()
        return obj

    cdef inline void _read_dict(self, Buffer buffer, object obj):
        cdef dict obj_dict = obj.__dict__
        cdef Py_ssize_t i
        cdef Py_ssize_t field_count = self._field_runtime_infos.size()
        cdef object field_value
        cdef object field_name
        cdef FieldRuntimeInfo *field_info

        for i in range(field_count):
            field_info = &self._field_runtime_infos[i]
            field_value = self._read_field_value(buffer, field_info)
            if field_info.field_exists == 0:
                continue
            field_name = <object> field_info.field_name
            obj_dict[field_name] = field_value

    cdef inline void _read_slots(self, Buffer buffer, object obj):
        cdef Py_ssize_t i
        cdef Py_ssize_t field_count = self._field_runtime_infos.size()
        cdef object field_value
        cdef object field_name
        cdef FieldRuntimeInfo *field_info

        for i in range(field_count):
            field_info = &self._field_runtime_infos[i]
            field_value = self._read_field_value(buffer, field_info)
            if field_info.field_exists == 0:
                continue
            field_name = <object> field_info.field_name
            PyObject_SetAttr(obj, field_name, field_value)

    cdef inline object _read_field_value(self, Buffer buffer, FieldRuntimeInfo *field_info):
        cdef uint8_t type_id = field_info.basic_type_id
        cdef bint is_nullable = field_info.is_nullable != 0
        cdef bint is_tracking_ref = field_info.track_ref != 0
        cdef bint is_dynamic = field_info.is_dynamic != 0
        cdef Serializer serializer

        if type_id != _BASIC_FIELD_UNSUPPORTED:
            if is_nullable and buffer.read_int8() == NULL_FLAG:
                return None
            return Fory_PyReadBasicFieldFromBuffer(&buffer.c_buffer, type_id)

        serializer = <object> field_info.serializer
        if is_tracking_ref:
            if is_dynamic:
                return self.fory.read_ref(buffer)
            return self.fory.read_ref(buffer, serializer=serializer)

        if is_nullable and buffer.read_int8() == NULL_FLAG:
            return None

        if is_dynamic:
            return self.fory.read_no_ref(buffer)
        return self.fory.read_no_ref(buffer, serializer=serializer)

    cdef inline void _apply_missing_defaults_dict(self, dict obj_dict):
        cdef object field_name
        cdef object default_factory

        for field_name, default_factory in self._missing_field_defaults:
            obj_dict[field_name] = default_factory()

    cdef inline void _apply_missing_defaults_slots(self, object obj):
        cdef object field_name
        cdef object default_factory

        for field_name, default_factory in self._missing_field_defaults:
            PyObject_SetAttr(obj, field_name, default_factory())
