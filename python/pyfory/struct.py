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
import enum
import inspect
import logging
import os
import sys
import typing
from typing import List, Dict

from pyfory.lib.mmh3 import hash_buffer
from pyfory.types import (
    TypeId,
    int8,
    int16,
    int32,
    int64,
    fixed_int32,
    fixed_int64,
    tagged_int64,
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    float32,
    float64,
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
    infer_field,
    is_subclass,
    get_type_hints,
    unwrap_optional,
)
from pyfory.serialization import Buffer
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
_NO_MISSING_FIELD_DEFAULT = object()


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


def _default_field_meta(type_hint: type, field_nullable: bool = False) -> ForyFieldMeta:
    """Returns default field metadata for fields without pyfory.field().

    A field is considered nullable if:
    1. It's Optional[T], OR
    2. Global field_nullable is True

    For ref, defaults to False to preserve original serialization behavior.
    Non-nullable complex fields use write_no_ref (no ref header in buffer).
    Users can explicitly set ref=True in pyfory.field() to enable ref tracking.

    For dynamic, defaults to None (auto-detect):
    - Abstract classes: always True (type info must be written)
    - Concrete types use type-id based dynamic detection
    """
    unwrapped_type, is_optional = unwrap_optional(type_hint)
    nullable = is_optional or field_nullable
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
        # For non-dataclass, return empty - will use legacy path
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
            meta = _default_field_meta(field_type, global_field_nullable)

        field_metas[field_name] = meta

        if not meta.ignore:
            active_fields.append((field_name, dc_field))

    # Validate field metas
    validate_field_metas(clz, field_metas, type_hints)

    # Build FieldInfo list
    field_infos: List[FieldInfo] = []
    visitor = StructFieldSerializerVisitor(fory)
    global_ref_tracking = fory.track_ref

    for index, (field_name, dc_field) in enumerate(active_fields):
        meta = field_metas[field_name]
        type_hint = type_hints.get(field_name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(type_hint)

        # Optional[T] should always be nullable regardless of explicit meta.
        effective_nullable = meta.nullable or is_optional

        # Compute runtime ref tracking: field.ref AND global config
        runtime_ref = meta.ref and global_ref_tracking

        # Infer serializer
        serializer = infer_field(field_name, unwrapped_type, visitor, types_path=[])

        # Get type_id from serializer
        if serializer is not None:
            type_id = fory.type_resolver.get_type_info(serializer.type_).type_id
        else:
            type_id = TypeId.UNKNOWN

        # Compute effective dynamic based on type.
        # - Abstract classes: always True (type info must be written)
        # - If explicitly set (not None): use that value
        # - Otherwise: write type info for polymorphic types that are not registered by id
        is_abstract = _is_abstract_type(unwrapped_type)
        if is_abstract:
            # Abstract classes always need type info
            effective_dynamic = True
        elif meta.dynamic is not None:
            # Explicit configuration takes precedence
            effective_dynamic = meta.dynamic
        else:
            # Registered-by-id types have stable serializers, so no per-field type info is needed.
            effective_dynamic = is_polymorphic_type(type_id) and not fory.type_resolver.is_registered_by_id(unwrapped_type)

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
        )
        field_infos.append(field_info)

    return field_infos, field_metas


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
        fory,
        clz: type,
        field_names: List[str] = None,
        serializers: List[Serializer] = None,
        nullable_fields: Dict[str, bool] = None,
        dynamic_fields: Dict[str, bool] = None,
    ):
        super().__init__(fory, clz)

        self._type_hints = get_type_hints(clz)
        self._has_slots = hasattr(clz, "__slots__")

        self._fields_from_typedef = field_names is not None and serializers is not None
        if self._fields_from_typedef:
            self._field_names = list(field_names)
            self._serializers = list(serializers)
            self._nullable_fields = nullable_fields or {}
            self._ref_fields = {}
            self._dynamic_fields = dynamic_fields or {}
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
                    visitor = StructFieldSerializerVisitor(fory)
                    for index, key in enumerate(self._field_names):
                        unwrapped_type, _ = unwrap_optional(self._type_hints.get(key, typing.Any))
                        self._serializers[index] = infer_field(key, unwrapped_type, visitor, types_path=[])

        self._unwrapped_hints = self._compute_unwrapped_hints()
        if self._fields_from_typedef:
            hash_str = compute_struct_fingerprint(fory.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos)
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
                fory.type_resolver, self._field_names, self._serializers, self._nullable_fields, self._field_infos
            )

        self._field_name_interned = {name: sys.intern(name) for name in self._field_names}
        self._current_class_field_names = set(self._get_field_names(self.type_))
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
        if not self.fory.compatible or not dataclasses.is_dataclass(self.type_):
            return []
        missing_fields = self._current_class_field_names - set(self._field_names)
        if not missing_fields:
            return []
        defaults = []
        for dc_field in dataclasses.fields(self.type_):
            if dc_field.name not in missing_fields:
                continue
            resolved_default = self._resolve_missing_field_default(dc_field)
            if resolved_default is _NO_MISSING_FIELD_DEFAULT:
                continue
            default_value, default_factory = resolved_default
            defaults.append((dc_field.name, default_value, default_factory))
        return defaults

    def _resolve_missing_field_default(self, dc_field):
        type_hint = self._type_hints.get(dc_field.name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(type_hint)
        meta = extract_field_meta(dc_field)
        effective_nullable = (meta.nullable if meta is not None else self.fory.field_nullable) or is_optional

        if dc_field.default is not dataclasses.MISSING:
            default_value = dc_field.default
            if default_value is None and not effective_nullable and is_subclass(unwrapped_type, enum.Enum):
                members = tuple(unwrapped_type)
                if members:
                    default_value = members[0]
            return default_value, None

        if dc_field.default_factory is not dataclasses.MISSING:
            return None, dc_field.default_factory

        if not effective_nullable and is_subclass(unwrapped_type, enum.Enum):
            members = tuple(unwrapped_type)
            if members:
                return members[0], None
        return _NO_MISSING_FIELD_DEFAULT

    def _write_field_value(self, buffer, serializer, field_value, is_nullable, is_dynamic, is_basic):
        if is_nullable:
            if is_basic:
                if field_value is None:
                    buffer.write_int8(NULL_FLAG)
                else:
                    buffer.write_int8(NOT_NULL_VALUE_FLAG)
                    serializer.write(buffer, field_value)
            else:
                self.fory.write_ref(buffer, field_value, serializer=None if is_dynamic else serializer)
            return
        if is_basic:
            serializer.write(buffer, field_value)
        elif is_dynamic:
            self.fory.write_no_ref(buffer, field_value)
        else:
            self.fory.write_no_ref(buffer, field_value, serializer=serializer)

    def _read_field_value(self, buffer, serializer, is_nullable, is_dynamic, is_basic):
        if is_nullable:
            if is_basic:
                if buffer.read_int8() == NULL_FLAG:
                    return None
                return serializer.read(buffer)
            return self.fory.read_ref(buffer, serializer=None if is_dynamic else serializer)
        if is_basic:
            return serializer.read(buffer)
        if is_dynamic:
            return self.fory.read_no_ref(buffer)
        return self.fory.read_no_ref(buffer, serializer=serializer)

    def write(self, buffer: Buffer, value):
        if not self.fory.compatible:
            buffer.write_int32(self._hash)
        value_dict = value.__dict__ if not self._has_slots else None
        for index, field_name in enumerate(self._field_names):
            interned_name = self._field_name_interned[field_name]
            if value_dict is not None:
                if self.fory.compatible:
                    field_value = value_dict.get(interned_name)
                else:
                    field_value = value_dict[interned_name]
            else:
                if self.fory.compatible:
                    field_value = getattr(value, interned_name, None)
                else:
                    field_value = getattr(value, interned_name)
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            is_dynamic = self._dynamic_fields.get(field_name, False)
            is_basic = self._basic_field_flags[index]
            self._write_field_value(buffer, serializer, field_value, is_nullable, is_dynamic, is_basic)

    def read(self, buffer):
        if not self.fory.strict:
            self.fory.policy.authorize_instantiation(self.type_)
        if not self.fory.compatible:
            hash_ = buffer.read_int32()
            if hash_ != self._hash:
                raise TypeNotCompatibleError(
                    f"Hash {hash_} is not consistent with {self._hash} for type {self.type_}",
                )
        obj = self.type_.__new__(self.type_)
        self.fory.ref_resolver.reference(obj)
        obj_dict = obj.__dict__ if not self._has_slots else None
        for index, field_name in enumerate(self._field_names):
            serializer = self._serializers[index]
            is_nullable = self._nullable_fields.get(field_name, False)
            is_dynamic = self._dynamic_fields.get(field_name, False)
            is_basic = self._basic_field_flags[index]
            field_value = self._read_field_value(buffer, serializer, is_nullable, is_dynamic, is_basic)
            if field_name not in self._current_class_field_names:
                continue
            interned_name = self._field_name_interned[field_name]
            if obj_dict is not None:
                obj_dict[interned_name] = field_value
            else:
                setattr(obj, interned_name, field_value)

        if self._missing_field_defaults:
            for field_name, default_value, default_factory in self._missing_field_defaults:
                value = default_value if default_factory is None else default_factory()
                if obj_dict is not None:
                    obj_dict[field_name] = value
                else:
                    setattr(obj, field_name, value)
        return obj


class DataClassStubSerializer(DataClassSerializer):
    def __init__(self, fory, clz: type):
        Serializer.__init__(self, fory, clz)

    def write(self, buffer, value):
        self._replace().write(buffer, value)

    def read(self, buffer):
        return self._replace().read(buffer)

    def _replace(self):
        typeinfo = self.fory.type_resolver.get_type_info(self.type_)
        typeinfo.serializer = DataClassSerializer(self.fory, self.type_)
        return typeinfo.serializer


PythonDataClassSerializer = DataClassSerializer
try:
    from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
except ImportError:
    ENABLE_FORY_CYTHON_SERIALIZATION = False

if ENABLE_FORY_CYTHON_SERIALIZATION:
    try:
        from pyfory.serialization import DataClassSerializer as _CythonDataClassSerializer

        DataClassSerializer = _CythonDataClassSerializer
    except ImportError:
        DataClassSerializer = PythonDataClassSerializer


basic_types = {
    bool,
    # Signed integers
    int8,
    int16,
    int32,
    fixed_int32,
    int64,
    fixed_int64,
    tagged_int64,
    # Unsigned integers
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    # Floats
    float32,
    float64,
    # Python native types
    int,
    float,
    str,
    bytes,
    datetime.datetime,
    datetime.date,
    datetime.time,
}


class StructFieldSerializerVisitor(TypeVisitor):
    def __init__(
        self,
        fory,
    ):
        self.fory = fory

    def visit_list(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import ListSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as List[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return ListSerializer(self.fory, list, elem_serializer, elem_ref_override)

    def visit_set(self, field_name, elem_type, types_path=None):
        from pyfory.serializer import SetSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_type, elem_ref_override = unwrap_ref(elem_type)
        elem_serializer = infer_field("item", elem_type, self, types_path=types_path)
        return SetSerializer(self.fory, set, elem_serializer, elem_ref_override)

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        from pyfory.serializer import MapSerializer  # Local import
        from pyfory.type_util import unwrap_ref

        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_type, key_ref_override = unwrap_ref(key_type)
        value_type, value_ref_override = unwrap_ref(value_type)
        key_serializer = infer_field("key", key_type, self, types_path=types_path)
        value_serializer = infer_field("value", value_type, self, types_path=types_path)
        return MapSerializer(
            self.fory,
            dict,
            key_serializer,
            value_serializer,
            key_ref_override,
            value_ref_override,
        )

    def visit_customized(self, field_name, type_, types_path=None):
        if issubclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        # For custom types (dataclasses, etc.), try to get or create serializer
        # This enables field-level serializer resolution for types like inner structs
        typeinfo = self.fory.type_resolver.get_type_info(type_, create=False)
        if typeinfo is not None:
            return typeinfo.serializer
        return None

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return self.fory.type_resolver.get_serializer(type_)
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None
        serializer = self.fory.type_resolver.get_serializer(type_)
        return serializer


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
            sort_key = (0, str(tag_id), "")
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
        elif is_list_type(serializer.type_):
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
        # Sort by: compress flag, -size (largest first), -type_id (higher type ID first), field_name
        # Java sorts by size (largest first), then by primitive type ID (descending)
        return int(compress), -get_primitive_type_size(id_), -id_, item[3]

    boxed_types = sorted(boxed_types, key=numeric_sorter)
    nullable_boxed_types = sorted(nullable_boxed_types, key=numeric_sorter)
    collection_types = sorted(collection_types, key=sorter)
    set_types = sorted(set_types, key=sorter)
    internal_types = sorted(internal_types, key=sorter)
    map_types = sorted(map_types, key=sorter)
    other_types = sorted(other_types, key=lambda item: item[3])
    return (boxed_types, nullable_boxed_types, internal_types, collection_types, set_types, map_types, other_types)


def compute_struct_fingerprint(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes the fingerprint string for a struct type used in schema versioning.

    Fingerprint Format:
        Each field contributes: <field_id_or_name>,<type_id>,<ref>,<nullable>;
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

    # Build field info list for fingerprint: (sort_key, field_id_or_name, type_id, ref_flag, nullable_flag)
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
        ref_flag = "1" if (fi and fi.ref) else "0"

        if serializer is None:
            type_id = TypeId.UNKNOWN
            # For unknown serializers, use nullable from map (defaults to False for xlang)
            nullable_flag = "1" if nullable_map.get(field_name, False) else "0"
        else:
            type_id = type_resolver.get_type_info(serializer.type_).type_id
            if is_union_type(type_id):
                # customized types can't be detected at compile time for some languages
                type_id = TypeId.UNKNOWN
            is_nullable = nullable_map.get(field_name, False)

            # For polymorphic or enum types, set type_id to UNKNOWN but preserve nullable from map
            if is_polymorphic_type(type_id) or type_id in {
                TypeId.ENUM,
                TypeId.NAMED_ENUM,
            }:
                type_id = TypeId.UNKNOWN

            # Use nullable from map - for xlang, this is already computed correctly
            # (False by default except for Optional[T] or explicit annotation)
            nullable_flag = "1" if is_nullable else "0"

        # Determine field identifier for fingerprint
        if tag_id >= 0:
            field_id_or_name = str(tag_id)
            # Sort by tag ID string (lexicographic) for tag ID fields
            sort_key = (0, field_id_or_name, "")  # 0 = tag ID fields come first
        else:
            field_id_or_name = field_name
            # Sort by field name (lexicographic) for name-based fields
            sort_key = (1, field_name, "")  # 1 = name fields come after

        fp_fields.append((sort_key, field_id_or_name, type_id, ref_flag, nullable_flag))

    # Sort fields: tag ID fields first (by ID), then name fields (lexicographically)
    fp_fields.sort(key=lambda x: x[0])

    # Build fingerprint string
    hash_parts = []
    for _, field_id_or_name, type_id, ref_flag, nullable_flag in fp_fields:
        hash_parts.append(f"{field_id_or_name},{type_id},{ref_flag},{nullable_flag};")

    return "".join(hash_parts)


def compute_struct_meta(type_resolver, field_names, serializers, nullable_map=None, field_infos_list=None):
    """
    Computes struct metadata including version hash, sorted field names, and serializers.

    Uses compute_struct_fingerprint to build the fingerprint string, then hashes it
    with MurmurHash3 using seed 47, and takes the low 32 bits as signed int32.

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
        fory,
        cls,
    ):
        self.fory = fory
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.LIST, elem_ids

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_ids = infer_field("item", elem_type, self, types_path=types_path)
        return TypeId.SET, elem_ids

    def visit_dict(self, field_name, key_type, value_type, types_path=None):
        # Infer type recursively for type such as Dict[str, Dict[str, str]]
        key_ids = infer_field("key", key_type, self, types_path=types_path)
        value_ids = infer_field("value", value_type, self, types_path=types_path)
        return TypeId.MAP, key_ids, value_ids

    def visit_customized(self, field_name, type_, types_path=None):
        typeinfo = self.fory.type_resolver.get_type_info(type_, create=False)
        if typeinfo is None:
            return [TypeId.UNKNOWN]
        return [typeinfo.type_id]

    def visit_other(self, field_name, type_, types_path=None):
        if is_subclass(type_, enum.Enum):
            return [self.fory.type_resolver.get_type_info(type_).type_id]
        if type_ not in basic_types and not is_primitive_array_type(type_):
            return None, None
        typeinfo = self.fory.type_resolver.get_type_info(type_)
        return [typeinfo.type_id]


class StructTypeVisitor(TypeVisitor):
    def __init__(self, cls):
        self.cls = cls

    def visit_list(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as List[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.List, elem_types

    def visit_set(self, field_name, elem_type, types_path=None):
        # Infer type recursively for type such as Set[Dict[str, str]]
        elem_types = infer_field("item", elem_type, self, types_path=types_path)
        return typing.Set, elem_types

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
