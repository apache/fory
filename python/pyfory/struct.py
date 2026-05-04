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

from __future__ import annotations

import dataclasses
import datetime
import decimal
import enum
import inspect
import logging
import os
import sys
import typing
from typing import List, Dict

from pyfory.annotation import (
    ArrayMeta,
    BFloat16,
    Float16,
    Float32,
    Float64,
    FixedInt32,
    FixedInt64,
    FixedUInt32,
    FixedUInt64,
    Int8,
    Int16,
    Int32,
    Int64,
    TaggedInt64,
    TaggedUInt64,
    UInt8,
    UInt16,
    UInt32,
    UInt64,
)
from pyfory.lib.mmh3 import hash_buffer
from pyfory.policy import DEFAULT_POLICY
from pyfory.types import (
    TypeId,
    is_primitive_array_type,
    is_list_type,
    is_map_type,
    get_primitive_type_size,
    is_polymorphic_type,
    is_primitive_type,
    is_union_type,
)
from pyfory.type_util import (
    TypeVisitor,
    _get_args,
    _get_origin,
    infer_field,
    infer_field_types,
    get_homogeneous_tuple_elem_type,
    is_subclass,
    get_type_hints,
    unwrap_array,
    unwrap_optional,
    unwrap_ref,
)
from pyfory.serialization import Buffer
from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory.error import TypeNotCompatibleError
from pyfory.resolver import NULL_FLAG, NOT_NULL_VALUE_FLAG
from pyfory.field import (
    ForyFieldMeta,
    extract_field_meta,
    validate_field_metas,
)

from pyfory import (
    Serializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    Float32Serializer,
    Float64Serializer,
    StringSerializer,
)

logger = logging.getLogger(__name__)

_MISSING_DEFAULT_INT_TYPES = {
    int,
    Int8,
    Int16,
    Int32,
    FixedInt32,
    Int64,
    FixedInt64,
    TaggedInt64,
    UInt8,
    UInt16,
    UInt32,
    FixedUInt32,
    UInt64,
    FixedUInt64,
    TaggedUInt64,
}

_MISSING_DEFAULT_FLOAT_TYPES = {
    float,
    Float32,
    Float64,
}


@dataclasses.dataclass
class FieldInfo:
    """Pre-computed field information for serialization."""

    # Identity
    name: str  # Field name (snake_case)
    index: int  # Field index in the serialization order
    type_hint: type  # Type annotation

    # Fory metadata (from pyfory.field()) - used for hash computation
    tag_id: int  # -1 = use field name, >=0 = use tag ID
    nullable: bool  # Effective nullable flag (considers Optional[T])
    ref: bool  # Field-level ref setting (for hash computation)
    dynamic: bool  # Whether type info is written for this field

    # Runtime flags (combines field metadata with global Fory config)
    runtime_ref_tracking: bool  # Actual ref tracking: field.ref AND fory.track_ref

    # Derived info
    type_id: int  # Fory TypeId
    serializer: Serializer  # Field serializer
    unwrapped_type: type  # Type with Optional unwrapped
    field_type: object = None  # Recursive TypeDef schema node for this field
    assign: bool = True  # Whether a remote field value may be assigned locally
    validation_field_type: object = None  # Local schema used for compatible-read validation


def _is_abstract_type(type_hint: type) -> bool:
    """Check if a type is abstract (has abstract methods or is ABC subclass)."""
    if type_hint is None:
        return False
    try:
        # Check if it's an abstract class using inspect.isabstract
        return inspect.isabstract(type_hint)
    except TypeError:
        # Not a class (e.g., generic type)
        return False


def _is_dynamic_nullable_default(type_hint: type) -> bool:
    if type_hint is typing.Any:
        return True
    origin = _get_origin(type_hint)
    args = _get_args(type_hint)
    if origin in (list, dict, set, tuple) and not args:
        return True
    return type_hint in (list, dict, set, tuple, typing.List, typing.Dict, typing.Set, typing.Tuple)


def _default_field_meta(type_hint: type, field_nullable: bool = False, xlang: bool = False) -> ForyFieldMeta:
    """Returns default field metadata for fields without pyfory.field().

    A field is considered nullable if:
    1. It's Optional[T], OR
    2. Global field_nullable is True, OR
    3. In Python-native mode, it is a dynamic field such as Any or a bare collection alias.

    Xlang fields are non-nullable by default so schema fingerprints and payload flags stay aligned
    with other runtimes unless users opt into nullability explicitly.

    For ref, defaults to False to preserve original serialization behavior.
    Non-nullable complex fields use write_no_ref (no ref header in buffer).
    Users can explicitly set ref=True in pyfory.field() to enable ref tracking.

    For dynamic, defaults to None (auto-detect):
    - Abstract classes: always True (type info must be written)
    - Concrete types use type-id based dynamic detection
    """
    unwrapped_type, is_optional = unwrap_optional(type_hint)
    nullable = is_optional or field_nullable or (not xlang and _is_dynamic_nullable_default(unwrapped_type))
    # Default ref=False to preserve original serialization behavior where non-nullable
    # fields use write_no_ref. Users can explicitly set ref=True in pyfory.field()
    # to enable per-field ref tracking when fory.track_ref is enabled.
    # Default dynamic=None for auto-detection based on type and mode
    return ForyFieldMeta(id=-1, nullable=nullable, ref=False, ignore=False, dynamic=None)


def _extract_field_infos(
    fory,
    clz: type,
    type_hints: dict,
) -> tuple[list[FieldInfo], dict[str, ForyFieldMeta]]:
    """
    Extract FieldInfo list from a dataclass.

    This handles:
    - Extracting field metadata from pyfory.field() annotations
    - Filtering out ignored fields
    - Computing effective nullable based on Optional[T]
    - Computing runtime ref tracking based on global config
    - Inheritance: parent fields first, subclass fields override parent fields

    Returns:
        Tuple of (field_infos, field_metas) where field_metas maps field name to ForyFieldMeta
    """
    if not dataclasses.is_dataclass(clz):
        # Non-dataclass registration uses the runtime type inspection path.
        return [], {}

    # Collect fields from class hierarchy (parent first, child last)
    # Child fields override parent fields with same name
    all_fields: Dict[str, dataclasses.Field] = {}
    for klass in clz.__mro__[::-1]:  # Reverse MRO: base classes first
        if dataclasses.is_dataclass(klass) and klass is not clz:
            for f in dataclasses.fields(klass):
                all_fields[f.name] = f
    # Add current class fields (override parent)
    for f in dataclasses.fields(clz):
        all_fields[f.name] = f

    # Extract field metas and filter ignored fields
    field_metas: Dict[str, ForyFieldMeta] = {}
    active_fields: List[tuple] = []

    # Check if fory has field_nullable global setting
    global_field_nullable = getattr(fory, "field_nullable", False)

    for field_name, dc_field in all_fields.items():
        meta = extract_field_meta(dc_field)
        if meta is None:
            # Field without pyfory.field() - use defaults
            # Auto-detect Optional[T] for nullable, also respect global field_nullable
            field_type = type_hints.get(field_name, typing.Any)
            meta = _default_field_meta(field_type, global_field_nullable, getattr(fory, "xlang", False))

        field_metas[field_name] = meta

        if not meta.ignore:
            active_fields.append((field_name, dc_field))

    # Validate field metas
    validate_field_metas(clz, field_metas, type_hints)

    # Build FieldInfo list
    field_infos: List[FieldInfo] = []
    from pyfory.meta.typedef import build_field_type_with_ref

    visitor = StructTypeIdVisitor(fory, clz)
    field_types = infer_field_types(clz, field_nullable=global_field_nullable)
    global_ref_tracking = fory.track_ref

    for index, (field_name, dc_field) in enumerate(active_fields):
        meta = field_metas[field_name]
        type_hint = type_hints.get(field_name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(type_hint)

        # Optional[T] should always be nullable regardless of explicit meta.
        effective_nullable = meta.nullable or is_optional

        # Compute runtime ref tracking: field.ref AND global config
        runtime_ref = meta.ref and global_ref_tracking

        field_type = build_field_type_with_ref(
            fory,
            field_name,
            unwrapped_type,
            visitor,
            effective_nullable,
            runtime_ref,
        )
        array_meta = unwrap_array(unwrapped_type)
        if array_meta is not None:
            serializer = StructFieldSerializerVisitor(fory).visit_array(
                field_name,
                array_meta.element_type,
                array_meta.carrier,
            )
        else:
            serializer = field_type.create_serializer(fory, field_types.get(field_name, None))

        type_id = field_type.type_id

        # Compute effective dynamic based on type.
        # - Abstract classes: always True (type info must be written)
        # - Compatible ref-tracked struct fields carry type info for xlang
        #   parity with other runtimes
        # - If explicitly set (not None): use that value for non-ref fields
        # - Otherwise: write type info for polymorphic types that are not registered by id
        is_abstract = _is_abstract_type(unwrapped_type)
        if fory.compatible and runtime_ref and is_polymorphic_type(type_id):
            effective_dynamic = True
        elif is_abstract:
            # Abstract classes always need type info
            effective_dynamic = True
        elif meta.dynamic is not None:
            # Explicit configuration takes precedence
            effective_dynamic = meta.dynamic
        else:
            # Registered-by-id types have stable serializers, so no per-field type info is needed.
            effective_dynamic = is_polymorphic_type(type_id) and not fory.is_registered_by_id(unwrapped_type)

        field_info = FieldInfo(
            name=field_name,
            index=index,
            type_hint=type_hint,
            tag_id=meta.id,
            nullable=effective_nullable,
            ref=meta.ref,
            dynamic=effective_dynamic,
            runtime_ref_tracking=runtime_ref,
            type_id=type_id,
            serializer=serializer,
            unwrapped_type=unwrapped_type,
            field_type=field_type,
        )
        field_infos.append(field_info)

    return field_infos, field_metas


def resolve_missing_field_default(
    dc_field: dataclasses.Field,
    type_resolver,
    type_hints: dict[str, typing.Any],
) -> typing.Callable[[], typing.Any]:
    type_hint = type_hints.get(dc_field.name, typing.Any)
    unwrapped_type, is_optional = unwrap_optional(type_hint)
    meta = extract_field_meta(dc_field)
    effective_nullable = (meta.nullable if meta is not None else type_resolver.field_nullable) or is_optional

    if dc_field.default is not dataclasses.MISSING:
        default_value = dc_field.default
        if default_value is None and not effective_nullable and is_subclass(unwrapped_type, enum.Enum):
            members = tuple(unwrapped_type)
            if members:
                default_value = members[0]
        return lambda value=default_value: value

    if dc_field.default_factory is not dataclasses.MISSING:
        return dc_field.default_factory

    if not effective_nullable:
        origin = typing.get_origin(unwrapped_type) if hasattr(typing, "get_origin") else getattr(unwrapped_type, "__origin__", None)
        origin = origin or unwrapped_type
        if is_subclass(unwrapped_type, enum.Enum):
            members = tuple(unwrapped_type)
            if members:
                default_value = members[0]
                return lambda value=default_value: value
        if origin is list or origin == typing.List:
            return lambda: []
        if origin is set or origin == typing.Set:
            return lambda: set()
        if origin is dict or origin == typing.Dict:
            return lambda: {}
        if unwrapped_type is bool:
            return lambda: False
        if unwrapped_type in _MISSING_DEFAULT_INT_TYPES:
            return lambda: 0
        if unwrapped_type in _MISSING_DEFAULT_FLOAT_TYPES:
            return lambda: 0.0
        if unwrapped_type is str:
            return lambda: ""
        if unwrapped_type is bytes:
            return lambda: b""
    return lambda: None


def _resolve_missing_field_default(dc_field, type_resolver, type_hints):
    return resolve_missing_field_default(dc_field, type_resolver, type_hints)


def build_default_values_factory(type_resolver, type_hints, dc_fields=()):
    return {dc_field.name: _resolve_missing_field_default(dc_field, type_resolver, type_hints) for dc_field in dc_fields}


class DataClassSerializer(Serializer):
    _BASIC_SERIALIZERS = (
        BooleanSerializer,
        ByteSerializer,
        Int16Serializer,
        Int32Serializer,
        Int64Serializer,
        Float32Serializer,
        Float64Serializer,
        StringSerializer,
    )

    def __init__(
        self,
        type_resolver,
        clz: type,
        field_names: List[str] = None,
        serializers: List[Serializer] = None,
        nullable_fields: Dict[str, bool] = None,
        dynamic_fields: Dict[str, bool] = None,
        ref_fields: Dict[str, bool] = None,
        field_infos: List[FieldInfo] = None,
        fields_from_typedef: bool = False,
    ):
        super().__init__(type_resolver, clz)

        self._type_hints = get_type_hints(clz)
        self._has_slots = hasattr(clz, "__slots__")

        self._fields_from_typedef = fields_from_typedef or (field_names is not None and serializers is not None)
        if field_infos is not None:
            self._field_infos = list(field_infos)
            self._field_names = [fi.name for fi in self._field_infos]
            self._serializers = [fi.serializer for fi in self._field_infos]
            self._nullable_fields = {fi.name: fi.nullable for fi in self._field_infos}
            self._ref_fields = {fi.name: fi.runtime_ref_tracking for fi in self._field_infos}
            self._dynamic_fields = {fi.name: fi.dynamic for fi in self._field_infos}
            self._field_metas = {}
        elif self._fields_from_typedef:
            self._field_names = list(field_names)
            self._serializers = list(serializers)
            self._nullable_fields = nullable_fields or {}
            self._ref_fields = ref_fields or {}
            self._dynamic_fields = dynamic_fields or {}
            self._field_infos = []
            self._field_metas = {}
        else:
            self._field_infos, self._field_metas = _extract_field_infos(type_resolver, clz, self._type_hints)
            if self._field_infos:
                self._field_names = [fi.name for fi in self._field_infos]
                self._serializers = [fi.serializer for fi in self._field_infos]
                self._nullable_fields = {fi.name: fi.nullable for fi in self._field_infos}
                self._ref_fields = {fi.name: fi.runtime_ref_tracking for fi in self._field_infos}
                self._dynamic_fields = {fi.name: fi.dynamic for fi in self._field_infos}
            else:
                self._field_names = field_names or self._get_field_names(clz)
                self._nullable_fields = nullable_fields or {}
                self._ref_fields = {}
                self._dynamic_fields = {}
                if self._field_names and not self._nullable_fields:
                    for field_name in self._field_names:
                        if field_name in self._type_hints:
                            unwrapped_type, is_optional = unwrap_optional(self._type_hints[field_name])
                            self._nullable_fields[field_name] = is_optional or not is_primitive_type(unwrapped_type)
                self._serializers = serializers or [None] * len(self._field_names)
                if serializers is None:
                    visitor = StructFieldSerializerVisitor(type_resolver)
                    for index, key in enumerate(self._field_names):
                        unwrapped_type, _ = unwrap_optional(self._type_hints.get(key, typing.Any))
                        self._serializers[index] = infer_field(key, unwrapped_type, visitor, types_path=[])

        self._unwrapped_hints = self._compute_unwrapped_hints()
        if self._fields_from_typedef:
            hash_str = compute_struct_fingerprint(self.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos)
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
                self.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos
            )

        self._field_name_interned = {name: sys.intern(name) for name in self._field_names}
        self._current_class_field_names = set(self._get_field_names(self.type_))
        self._assign_fields = [
            bool(getattr(self._field_infos[index], "assign", True)) if index < len(self._field_infos) else True
            for index in range(len(self._field_names))
        ]
        self._validation_field_types = [
            getattr(self._field_infos[index], "validation_field_type", None) if index < len(self._field_infos) else None
            for index in range(len(self._field_names))
        ]
        self._has_validation_fields = any(field_type is not None for field_type in self._validation_field_types)
        self._assigned_field_names = {
            field_name
            for index, field_name in enumerate(self._field_names)
            if self._assign_fields[index] and field_name in self._current_class_field_names
        }
        self._has_missing_fields = any(
            field_name not in self._current_class_field_names or not self._assign_fields[index] for index, field_name in enumerate(self._field_names)
        )
        self._default_values_factory = (
            build_default_values_factory(self.type_resolver, self._type_hints, dataclasses.fields(self.type_))
            if dataclasses.is_dataclass(self.type_)
            else {}
        )
        self._missing_field_defaults = self._build_missing_field_defaults()
        self._basic_field_flags = [
            (not self._dynamic_fields.get(field_name, False)) and isinstance(self._serializers[index], self._BASIC_SERIALIZERS)
            for index, field_name in enumerate(self._field_names)
        ]

    def _get_field_names(self, clz):
        if hasattr(clz, "__dict__"):
            if dataclasses.is_dataclass(clz):
                return [field.name for field in dataclasses.fields(clz)]
            return sorted(self._type_hints.keys())
        if hasattr(clz, "__slots__"):
            slots = clz.__slots__
            if isinstance(slots, str):
                return [slots]
            return sorted(slots)
        return []

    def _compute_unwrapped_hints(self):
        return {field_name: unwrap_optional(hint)[0] for field_name, hint in self._type_hints.items()}

    def _build_missing_field_defaults(self):
        if not self.type_resolver.compatible or not self._default_values_factory:
            return []
        missing_fields = self._current_class_field_names - self._assigned_field_names
        if not missing_fields:
            return []
        return [(field_name, default_factory) for field_name, default_factory in self._default_values_factory.items() if field_name in missing_fields]

    def _write_field_value(self, write_context, serializer, field_value, is_nullable, is_dynamic, is_basic, is_tracking_ref):
        if is_basic:
            if is_nullable:
                if field_value is None:
                    write_context.write_int8(NULL_FLAG)
                else:
                    write_context.write_int8(NOT_NULL_VALUE_FLAG)
                    serializer.write(write_context, field_value)
            else:
                serializer.write(write_context, field_value)
            return
        if is_tracking_ref:
            write_context.write_ref(field_value, serializer=None if is_dynamic else serializer)
            return
        if is_nullable:
            if field_value is None:
                write_context.write_int8(NULL_FLAG)
                return
            write_context.write_int8(NOT_NULL_VALUE_FLAG)
        if is_dynamic:
            write_context.write_no_ref(field_value)
        else:
            write_context.write_no_ref(field_value, serializer=serializer)

    def _read_field_value(self, read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref):
        if is_nullable and is_basic:
            if read_context.read_int8() == NULL_FLAG:
                return None
            return serializer.read(read_context)
        if is_basic:
            return serializer.read(read_context)
        if is_tracking_ref:
            return read_context.read_ref(serializer=None if is_dynamic else serializer)
        if is_nullable and read_context.read_int8() == NULL_FLAG:
            return None
        if is_dynamic:
            return read_context.read_no_ref()
        return read_context.read_no_ref(serializer=serializer)

    def _default_field_value(self, field_name):
        default_factory = self._default_values_factory.get(field_name)
        if default_factory is None:
            return None
        return default_factory()

    def _assign_read_field_value(self, obj, obj_dict, field_name, field_value, validation_field_type):
        if validation_field_type is not None:
            from pyfory.meta.typedef import coerce_assignable_value, is_value_assignable

            if not is_value_assignable(field_value, validation_field_type):
                field_value = self._default_field_value(field_name)
            else:
                field_value = coerce_assignable_value(field_value, validation_field_type)
        interned_name = self._field_name_interned[field_name]
        if obj_dict is not None:
            obj_dict[interned_name] = field_value
        else:
            setattr(obj, interned_name, field_value)

    def write(self, write_context: Buffer, value):
        if not self.type_resolver.compatible:
            write_context.write_int32(self._hash)
        value_dict = value.__dict__ if not self._has_slots else None
        if value_dict is not None:
            if self.type_resolver.compatible:
                for index, field_name in enumerate(self._field_names):
                    interned_name = self._field_name_interned[field_name]
                    field_value = value_dict.get(interned_name)
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    self._write_field_value(write_context, serializer, field_value, is_nullable, is_dynamic, is_basic, is_tracking_ref)
            else:
                for index, field_name in enumerate(self._field_names):
                    interned_name = self._field_name_interned[field_name]
                    field_value = value_dict[interned_name]
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    self._write_field_value(write_context, serializer, field_value, is_nullable, is_dynamic, is_basic, is_tracking_ref)
        else:
            if self.type_resolver.compatible:
                for index, field_name in enumerate(self._field_names):
                    interned_name = self._field_name_interned[field_name]
                    field_value = getattr(value, interned_name, None)
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    self._write_field_value(write_context, serializer, field_value, is_nullable, is_dynamic, is_basic, is_tracking_ref)
            else:
                for index, field_name in enumerate(self._field_names):
                    interned_name = self._field_name_interned[field_name]
                    field_value = getattr(value, interned_name)
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    self._write_field_value(write_context, serializer, field_value, is_nullable, is_dynamic, is_basic, is_tracking_ref)
        write_context.try_flush()

    def read(self, read_context):
        if read_context.policy is not DEFAULT_POLICY:
            read_context.policy.authorize_instantiation(self.type_)
        if not self.type_resolver.compatible:
            hash_ = read_context.read_int32()
            if hash_ != self._hash:
                raise TypeNotCompatibleError(
                    f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
                )
        obj = self.type_.__new__(self.type_)
        read_context.reference(obj)
        obj_dict = obj.__dict__ if not self._has_slots else None
        if self._has_missing_fields:
            for index, field_name in enumerate(self._field_names):
                serializer = self._serializers[index]
                is_nullable = self._nullable_fields.get(field_name, False)
                is_dynamic = self._dynamic_fields.get(field_name, False)
                is_tracking_ref = self._ref_fields.get(field_name, False)
                is_basic = self._basic_field_flags[index]
                if field_name not in self._current_class_field_names or not self._assign_fields[index]:
                    self._read_missing_field_value(read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref)
                    continue
                field_value = self._read_field_value(read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref)
                validation_field_type = self._validation_field_types[index]
                if validation_field_type is None:
                    interned_name = self._field_name_interned[field_name]
                    if obj_dict is not None:
                        obj_dict[interned_name] = field_value
                    else:
                        setattr(obj, interned_name, field_value)
                else:
                    self._assign_read_field_value(obj, obj_dict, field_name, field_value, validation_field_type)
        else:
            if not self._has_validation_fields:
                for index, field_name in enumerate(self._field_names):
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    field_value = self._read_field_value(read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref)
                    interned_name = self._field_name_interned[field_name]
                    if obj_dict is not None:
                        obj_dict[interned_name] = field_value
                    else:
                        setattr(obj, interned_name, field_value)
            else:
                for index, field_name in enumerate(self._field_names):
                    serializer = self._serializers[index]
                    is_nullable = self._nullable_fields.get(field_name, False)
                    is_dynamic = self._dynamic_fields.get(field_name, False)
                    is_tracking_ref = self._ref_fields.get(field_name, False)
                    is_basic = self._basic_field_flags[index]
                    field_value = self._read_field_value(read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref)
                    validation_field_type = self._validation_field_types[index]
                    if validation_field_type is None:
                        interned_name = self._field_name_interned[field_name]
                        if obj_dict is not None:
                            obj_dict[interned_name] = field_value
                        else:
                            setattr(obj, interned_name, field_value)
                    else:
                        self._assign_read_field_value(obj, obj_dict, field_name, field_value, validation_field_type)

        if self._missing_field_defaults:
            for field_name, default_factory in self._missing_field_defaults:
                value = default_factory()
                if obj_dict is not None:
                    obj_dict[field_name] = value
                else:
                    setattr(obj, field_name, value)
        read_context.shrink_input_buffer()
        return obj

    def _read_missing_field_value(self, read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref):
        previous = self.type_resolver._allow_unregistered_typedef
        self.type_resolver._allow_unregistered_typedef = True
        try:
            return self._read_field_value(read_context, serializer, is_nullable, is_dynamic, is_basic, is_tracking_ref)
        finally:
            self.type_resolver._allow_unregistered_typedef = previous


class DataClassStubSerializer(DataClassSerializer):
    def __init__(self, type_resolver, clz: type):
        Serializer.__init__(self, type_resolver, clz)

    def write(self, write_context, value):
        self._replace().write(write_context, value)

    def read(self, read_context):
        return self._replace().read(read_context)

    def _replace(self):
        typeinfo = self.type_resolver.get_type_info(self.type_)
        typeinfo.serializer = DataClassSerializer(self.type_resolver, self.type_)
        return typeinfo.serializer


basic_types = {
    bool,
    # Signed integers
    Int8,
    Int16,
    Int32,
    FixedInt32,
    Int64,
    FixedInt64,
    TaggedInt64,
    # Unsigned integers
    UInt8,
    UInt16,
    UInt32,
    FixedUInt32,
    UInt64,
    FixedUInt64,
    TaggedUInt64,
    # Floats
    Float16,
    BFloat16,
    Float32,
    Float64,
    # Python native types
    int,
    float,
    str,
    bytes,
    datetime.datetime,
    datetime.date,
    datetime.time,
    datetime.timedelta,
    decimal.Decimal,
}

_ARRAY_ELEMENT_TYPE_IDS = {
    bool: TypeId.BOOL_ARRAY,
    Int8: TypeId.INT8_ARRAY,
    Int16: TypeId.INT16_ARRAY,
    Int32: TypeId.INT32_ARRAY,
    Int64: TypeId.INT64_ARRAY,
    UInt8: TypeId.UINT8_ARRAY,
    UInt16: TypeId.UINT16_ARRAY,
    UInt32: TypeId.UINT32_ARRAY,
    UInt64: TypeId.UINT64_ARRAY,
    Float16: TypeId.FLOAT16_ARRAY,
    BFloat16: TypeId.BFLOAT16_ARRAY,
    Float32: TypeId.FLOAT32_ARRAY,
    Float64: TypeId.FLOAT64_ARRAY,
}

_ARRAY_INVALID_SCALAR_MODIFIERS = {
    FixedInt32,
    FixedInt64,
    FixedUInt32,
    FixedUInt64,
    TaggedInt64,
    TaggedUInt64,
}


def _array_type_id(elem_type, carrier):
    elem_type, ref_override = unwrap_ref(elem_type)
    elem_type, elem_nullable = unwrap_optional(elem_type)
    if elem_nullable:
        raise TypeError("array<T> does not allow optional elements")
    if ref_override is not None:
        raise TypeError("array<T> does not allow ref-tracked elements")
    if elem_type in _ARRAY_INVALID_SCALAR_MODIFIERS:
        raise TypeError(f"array<T> does not allow scalar encoding modifier {elem_type}")
    if carrier == "ndarray" and elem_type is BFloat16:
        raise TypeError("pyfory.NDArray does not support BFloat16 arrays")
    if carrier == "pyarray" and elem_type in (bool, Float16, BFloat16):
        raise TypeError("pyfory.PyArray supports Python array.array numeric typecodes only")
    type_id = _ARRAY_ELEMENT_TYPE_IDS.get(elem_type)
    if type_id is None:
        raise TypeError(f"array<T> element type must be a number or bool marker, got {elem_type}")
    return type_id


class StructFieldSerializerVisitor(TypeVisitor):
    def __init__(
        self,
        type_resolver,
    ):
        self.type_resolver = type_resolver

    def visit_array(self, field_name, elem_type, carrier, types_path=None):
        type_id = _array_type_id(elem_type, carrier)
        from pyfory.serializer import (
            ForyArrayFieldSerializer,
            Numpy1DArraySerializer,
            PyArraySerializer,
            fory_array_wrapper_type,
            typecode_dict,
            typeid_code,
        )

        if carrier == "array":
            wrapper_type = fory_array_wrapper_type(type_id)
            return ForyArrayFieldSerializer(self.type_resolver, wrapper_type, type_id, field_name)
        if carrier == "pyarray":
            typecode = typeid_code.get(type_id)
            if typecode is None:
                raise TypeError(f"pyfory.PyArray does not support array type id {type_id}")
            _itemsize, ftype, _type_id = typecode_dict[typecode]
            return PyArraySerializer(self.type_resolver, ftype, type_id)
        if carrier == "ndarray":
            for dtype, (_itemsize, _format, ftype, dtype_type_id) in Numpy1DArraySerializer.dtypes_dict.items():
                if dtype_type_id == type_id:
                    return Numpy1DArraySerializer(self.type_resolver, ftype, dtype)
            raise TypeError(f"pyfory.NDArray does not support array type id {type_id}")
        raise TypeError(f"Unknown array carrier {carrier!r}")

    def visit_list(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import ListSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as List[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return ListSerializer(self.type_resolver, list, elem_serializer, elem_ref_override)

    def visit_set(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import SetSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return SetSerializer(self.type_resolver, set, elem_serializer, elem_ref_override)

    def visit_tuple(self, field_name, elem_types, types_path=None):
        from pyfory.serializer import TupleSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        elem_type = get_homogeneous_tuple_elem_type(elem_types)
        if elem_type is not None:
            elem_type, elem_ref_override = unwrap_ref(elem_type)
            elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
            return TupleSerializer(self.type_resolver, tuple, elem_serializer, elem_ref_override)
        return TupleSerializer(self.type_resolver, tuple)

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        from pyfory.serializer import MapSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_type, key_ref_override = unwrap_ref(key_type)
        value_type, value_ref_override = unwrap_ref(value_type)
        key_serializer = infer_field("key", key_type, self, types_path=types_path)
        value_serializer = infer_field("value", value_type, self, types_path=types_path)
        return MapSerializer(
            self.type_resolver,
            dict,
            key_serializer,
            value_serializer,
            key_ref_override,
            value_ref_override,
        )

    def visit_customized(self, field_name, type_, types_path=None):
        if issubclass(type_, enum.Enum):
            return self.type_resolver.get_serializer(type_)
        # For custom types (dataclasses, etc.), try to get or create serializer
        # This enables field-level serializer resolution for types like inner structs
        typeinfo = self.type_resolver.get_type_info(type_, create=False)
        if typeinfo is not None:
            return typeinfo.serializer
        return None

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return self.type_resolver.get_serializer(type_)
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None
        return self.type_resolver.get_serializer(type_)


_UNKNOWN_TYPE_ID = -1


def _sort_fields(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map, field_infos_list
    )
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    return [t[2] for t in all_types], [t[1] for t in all_types]


def group_fields(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    nullable_map = nullable_map or {}
    field_info_map = {}
    if field_infos_list:
        field_info_map = {fi.name: fi for fi in field_infos_list}
    boxed_types = []
    nullable_boxed_types = []
    collection_types = []
    set_types = []
    map_types = []
    internal_types = []
    other_types = []
    type_ids = []
    for field_name, serializer in zip(field_names, serializers):
        fi = field_info_map.get(field_name)
        tag_id = fi.tag_id if fi else -1
        if tag_id >= 0:
            sort_key = (0, tag_id, "")
        else:
            sort_key = (1, field_name, "")
        if serializer is None:
            other_types.append((_UNKNOWN_TYPE_ID, serializer, field_name, sort_key))
        else:
            type_ids.append(
                (
                    type_resolver.get_type_info(serializer.type_).type_id,
                    serializer,
                    field_name,
                    sort_key,
                )
            )
    for type_id, serializer, field_name, sort_key in type_ids:
        if is_union_type(type_id):
            type_id = TypeId.UNION
        is_nullable = nullable_map.get(field_name, False)
        if is_primitive_type(type_id):
            container = nullable_boxed_types if is_nullable else boxed_types
        elif type_id == TypeId.SET:
            container = set_types
        elif type_id == TypeId.LIST or is_list_type(serializer.type_):
            container = collection_types
        elif is_map_type(serializer.type_):
            container = map_types
        elif is_polymorphic_type(type_id) or type_id in {TypeId.ENUM, TypeId.NAMED_ENUM} or is_union_type(type_id):
            container = other_types
        elif type_id >= TypeId.BOUND:
            # Native mode user-registered types have type_id >= BOUND
            container = other_types
        else:
            assert TypeId.UNKNOWN < type_id < TypeId.BOUND, (type_id,)
            container = internal_types
        container.append((type_id, serializer, field_name, sort_key))

    def sorter(item):
        return item[0], item[3]

    def numeric_sorter(item):
        id_ = item[0]
        compress = id_ in {
            # Signed compressed types
            TypeId.VARINT32,
            TypeId.VARINT64,
            TypeId.TAGGED_INT64,
            # Unsigned compressed types
            TypeId.VAR_UINT32,
            TypeId.VAR_UINT64,
            TypeId.TAGGED_UINT64,
        }
        # Sort by: compress flag, -size (largest first), type_id (lower first), field_name
        return int(compress), -get_primitive_type_size(id_), id_, item[3]

    boxed_types = sorted(boxed_types, key=numeric_sorter)
    nullable_boxed_types = sorted(nullable_boxed_types, key=numeric_sorter)
    collection_types = sorted(collection_types, key=lambda item: item[3])
    set_types = sorted(set_types, key=lambda item: item[3])
    internal_types = sorted(internal_types, key=sorter)
    map_types = sorted(map_types, key=lambda item: item[3])
    other_types = sorted(other_types, key=lambda item: item[3])
    return (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types)


def compute_struct_fingerprint(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes the fingerprint string for a struct type used in schema versioning.

    Fingerprint Format:
        Each field contributes: <field_id_or_name>,<type_id>,<ref>,<nullable>[<child...>];
        Fields are sorted by tag ID (if >=0) or field name (if id=-1).

    Field Components:
        - field_id_or_name: Tag ID as string if id >= 0, otherwise field name
        - type_id: Fory TypeId as decimal string (e.g., "4" for INT32)
        - ref: "1" if field has ref=True in pyfory.field(), "0" otherwise
              (based on field annotation, NOT runtime config)
        - nullable: "1" if null flag is written, "0" otherwise

    Example fingerprints:
        With tag IDs: "0,4,0,0;1,12,0,1;2,0,0,1;"
        With field names: "age,4,0,0;email,12,0,1;name,9,0,0;"

    This format is consistent across Go, Java, Rust, C++, and Python implementations.
    """
    if nullable_map is None:
        nullable_map = {}

    # Build field info list for fingerprint: (sort_key, field_id_or_name, type_fingerprint)
    fp_fields = []

    # Build a lookup for field_infos by name if available
    field_info_map = {}
    if field_infos_list:
        field_info_map = {fi.name: fi for fi in field_infos_list}

    for i, field_name in enumerate(field_names):
        serializer = serializers[i]

        # Get field metadata if available
        fi = field_info_map.get(field_name)
        tag_id = fi.tag_id if fi else -1
        nullable = fi.nullable if fi else nullable_map.get(field_name, False)
        ref = fi.ref if fi else False
        type_hint = fi.type_hint if fi else (serializer.type_ if serializer is not None else typing.Any)
        type_fingerprint = _build_schema_fingerprint_type(
            type_resolver,
            type_hint,
            nullable=nullable,
            track_ref=ref,
            include_ref=True,
        )

        # Determine field identifier for fingerprint
        if tag_id >= 0:
            field_id_or_name = str(tag_id)
            sort_key = (0, tag_id, "")  # 0 = tag ID fields come first
        else:
            field_id_or_name = field_name
            # Sort by field name (lexicographic) for name-based fields
            sort_key = (1, field_name, "")  # 1 = name fields come after

        fp_fields.append((sort_key, field_id_or_name, type_fingerprint))

    # Sort fields: tag ID fields first (by ID), then name fields (lexicographically)
    fp_fields.sort(key=lambda x: x[0])

    # Build fingerprint string
    hash_parts = []
    for _, field_id_or_name, type_fingerprint in fp_fields:
        hash_parts.append(f"{field_id_or_name},{type_fingerprint};")

    return "".join(hash_parts)


def _normalize_schema_fingerprint_type_id(type_id):
    if type_id in {
        TypeId.UNKNOWN,
        TypeId.ENUM,
        TypeId.NAMED_ENUM,
        TypeId.STRUCT,
        TypeId.COMPATIBLE_STRUCT,
        TypeId.NAMED_STRUCT,
        TypeId.NAMED_COMPATIBLE_STRUCT,
        TypeId.EXT,
        TypeId.NAMED_EXT,
        TypeId.UNION,
        TypeId.TYPED_UNION,
        TypeId.NAMED_UNION,
    }:
        return TypeId.UNKNOWN
    return type_id


def _leaf_schema_fingerprint_type_id(type_resolver, type_hint):
    if type_hint is typing.Any or type_hint is object:
        return TypeId.UNKNOWN
    if is_primitive_array_type(type_hint):
        return type_resolver.get_type_info(type_hint).type_id
    if is_subclass(type_hint, enum.Enum):
        return _normalize_schema_fingerprint_type_id(type_resolver.get_type_info(type_hint).type_id)
    if type_hint in basic_types:
        return _normalize_schema_fingerprint_type_id(type_resolver.get_type_info(type_hint).type_id)
    typeinfo = type_resolver.get_type_info(type_hint, create=False)
    if typeinfo is None:
        return TypeId.UNKNOWN
    return _normalize_schema_fingerprint_type_id(typeinfo.type_id)


def _build_schema_fingerprint_type(type_resolver, type_hint, nullable, track_ref, include_ref, include_nullable=True):
    type_hint, ref_override = unwrap_ref(type_hint)
    if include_ref and ref_override is not None:
        track_ref = ref_override
    if not include_ref:
        track_ref = False

    unwrapped_type, is_optional = unwrap_optional(type_hint)
    nullable = nullable or is_optional
    origin = _get_origin(unwrapped_type) or getattr(unwrapped_type, "__origin__", unwrapped_type)
    args = _get_args(unwrapped_type)

    ref_flag = "1" if track_ref else "0"
    nullable_flag = "1" if include_nullable and nullable else "0"

    array_meta = unwrap_array(unwrapped_type)
    if array_meta is not None:
        type_id = _array_type_id(array_meta.element_type, array_meta.carrier)
        return f"{type_id},{ref_flag},{nullable_flag}"

    if args:
        if origin is list or origin == typing.List:
            elem_type = args[0]
            child = _build_schema_fingerprint_type(
                type_resolver,
                elem_type,
                nullable=True,
                track_ref=False,
                include_ref=False,
                include_nullable=False,
            )
            return f"{TypeId.LIST},{ref_flag},{nullable_flag}[{child}]"
        if origin is set or origin == typing.Set:
            elem_type = args[0]
            child = _build_schema_fingerprint_type(
                type_resolver,
                elem_type,
                nullable=True,
                track_ref=False,
                include_ref=False,
                include_nullable=False,
            )
            return f"{TypeId.SET},{ref_flag},{nullable_flag}[{child}]"
        if origin is tuple or origin == typing.Tuple:
            elem_type = get_homogeneous_tuple_elem_type(args)
            if elem_type is None:
                child = f"{TypeId.UNKNOWN},0,0"
            else:
                child = _build_schema_fingerprint_type(
                    type_resolver,
                    elem_type,
                    nullable=True,
                    track_ref=False,
                    include_ref=False,
                    include_nullable=False,
                )
            return f"{TypeId.LIST},{ref_flag},{nullable_flag}[{child}]"
        if origin is dict or origin == typing.Dict:
            key_type, value_type = args
            key = _build_schema_fingerprint_type(
                type_resolver,
                key_type,
                nullable=True,
                track_ref=False,
                include_ref=False,
                include_nullable=False,
            )
            value = _build_schema_fingerprint_type(
                type_resolver,
                value_type,
                nullable=True,
                track_ref=False,
                include_ref=False,
                include_nullable=False,
            )
            return f"{TypeId.MAP},{ref_flag},{nullable_flag}[{key}|{value}]"
        if origin is typing.Union:
            type_id = TypeId.UNKNOWN
            return f"{type_id},{ref_flag},{nullable_flag}"

    type_id = _leaf_schema_fingerprint_type_id(type_resolver, unwrapped_type)
    return f"{type_id},{ref_flag},{nullable_flag}"


def compute_struct_meta(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes struct metadata including version hash, sorted field names, and serializers.

    Uses compute_struct_fingerprint to build the fingerprint string, then hashes it
    with MurmurHash3 using seed 47, and takes the low 32 bits as signed Int32.

    This provides the cross-language struct version ID used by class version checking,
    consistent with Go, Java, Rust, and C++ implementations.
    """
    (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types) = group_fields(
        type_resolver, field_names, serializers, nullable_map, field_infos_list
    )

    # Compute fingerprint string using the new format with field infos
    hash_str = compute_struct_fingerprint(type_resolver, field_names, serializers, nullable_map, field_infos_list)
    hash_bytes = hash_str.encode("utf-8")

    # Handle empty hash_bytes (no fields or all fields are unknown/dynamic)
    if len(hash_bytes) == 0:
        full_hash = 47  # Use seed as default hash for empty structs
    else:
        full_hash = hash_buffer(hash_bytes, seed=47)[0]
    type_hash_32 = full_hash & 0xFFFFFFFF
    if full_hash & 0x80000000:
        # If the sign bit is set, it's a negative number in 2's complement
        # Subtract 2^32 to get the correct negative value
        type_hash_32 = type_hash_32 - 0x100000000
    assert type_hash_32 != 0
    if os.environ.get("ENABLE_FORY_DEBUG_OUTPUT", "").lower() in ("1", "true"):
        print(f'[Python][fory-debug] struct version fingerprint="{hash_str}" version hash={type_hash_32}')

    # Flatten all groups in correct order (already sorted from group_fields)
    all_types = boxed_types + nullable_boxed_types + internal_types + collection_types + set_types + map_types + other_types
    sorted_field_names = [f[2] for f in all_types]
    sorted_serializers = [f[1] for f in all_types]

    return type_hash_32, sorted_field_names, sorted_serializers


class StructTypeIdVisitor(TypeVisitor):
    def __init__(
        self,
        type_resolver,
        cls,
    ):
        self.type_resolver = type_resolver
        self.cls = cls

    def visit_array(self, field_name, elem_type, carrier, types_path=None):
        return [_array_type_id(elem_type, carrier)]

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.LIST, elem_ids

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.SET, elem_ids

    def visit_tuple(self, field_name, elem_types, types_path=None):
        elem_type = get_homogeneous_tuple_elem_type(elem_types)
        if elem_type is None:
            return TypeId.LIST, [TypeId.UNKNOWN]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.LIST, elem_ids

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_ids = infer_field("key", key_type, self, types_path=types_path)
        value_ids = infer_field("value", value_type, self, types_path=types_path)
        return TypeId.MAP, key_ids, value_ids

    def visit_customized(self, field_name, type_, types_path=None):
        typeinfo = self.type_resolver.get_type_info(type_, create=False)
        if typeinfo is None:
            return [TypeId.UNKNOWN]
        return [typeinfo.type_id]

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return [self.type_resolver.get_type_info(type_).type_id]
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None, None
        typeinfo = self.type_resolver.get_type_info(type_)
        return [typeinfo.type_id]


class StructTypeVisitor(TypeVisitor):
    def __init__(self, cls):
        self.cls = cls

    def visit_array(self, field_name, elem_type, carrier, types_path=None):
        _array_type_id(elem_type, carrier)
        return ArrayMeta(elem_type, carrier)

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.List, elem_types

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.Set, elem_types

    def visit_tuple(self, field_name, elem_types, types_path=None):
        elem_type = get_homogeneous_tuple_elem_type(elem_types)
        if elem_type is None:
            return tuple, None
        elem_types_ = infer_field("item", elem_type, self, types_path=types_path)
        return tuple, elem_types_

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_types = infer_field("key", key_type, self, types_path=types_path)
        value_types = infer_field("value", value_type, self, types_path=types_path)
        return typing.Dict, key_types, value_types

    def visit_customized(self, field_name, type_, types_path=None):
        return [type_]

    def visit_other(self, field_name, type_, types_path=None):
        return [type_]


def get_field_names(clz, type_hints=None):
    if hasattr(clz, "__dict__"):
        # Regular object with __dict__
        # We can't know the fields without an instance, so we rely on type hints
        if type_hints is None:
            type_hints = get_type_hints(clz)
        return sorted(type_hints.keys())
    elif hasattr(clz, "__slots__"):
        # Object with __slots__
        return sorted(clz.__slots__)
    return []


if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import (
        DataClassSerializer as CythonDataClassSerializer,
        DataClassStubSerializer as CythonDataClassStubSerializer,
    )

    DataClassSerializer = CythonDataClassSerializer
    DataClassStubSerializer = CythonDataClassStubSerializer
