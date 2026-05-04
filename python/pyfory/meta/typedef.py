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

import enum
import array
import typing
from typing import List
from pyfory.annotation import ArrayMeta
from pyfory.types import TypeId, is_polymorphic_type, is_union_type
from pyfory._fory import NO_USER_TYPE_ID
from pyfory.serialization import Buffer
from pyfory.type_util import get_homogeneous_tuple_elem_type, infer_field
from pyfory.meta.metastring import Encoding
from pyfory.type_util import infer_field_types


# Constants from the specification
SMALL_NUM_FIELDS_THRESHOLD = 0b11111
REGISTER_BY_NAME_FLAG = 0b100000
FIELD_NAME_SIZE_THRESHOLD = 0b1111  # 4-bit threshold for field names
BIG_NAME_THRESHOLD = 0b111111  # 6-bit threshold for namespace/typename
COMPRESS_META_FLAG = 0b1 << 9
HAS_FIELDS_META_FLAG = 0b1 << 8
META_SIZE_MASKS = 0xFF
NUM_HASH_BITS = 50

NAMESPACE_ENCODINGS = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL]
TYPE_NAME_ENCODINGS = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.FIRST_TO_LOWER_SPECIAL]

# Field name encoding constants
FIELD_NAME_ENCODING_UTF8 = 0b00
FIELD_NAME_ENCODING_ALL_TO_LOWER_SPECIAL = 0b01
FIELD_NAME_ENCODING_LOWER_UPPER_DIGIT_SPECIAL = 0b10
FIELD_NAME_ENCODING_TAG_ID = 0b11
FIELD_NAME_ENCODINGS = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL]

# TAG_ID encoding constants
TAG_ID_SIZE_THRESHOLD = 0b1111  # 4-bit threshold for tag IDs (0-14 inline, 15 = overflow)


class TypeDef:
    def __init__(
        self,
        namespace: str,
        typename: str,
        cls: type,
        type_id: int,
        fields: List["FieldInfo"],
        encoded: bytes = None,
        is_compressed: bool = False,
        user_type_id: int = NO_USER_TYPE_ID,
    ):
        self.namespace = namespace
        self.typename = typename
        self.cls = cls
        self.type_id = type_id
        self.user_type_id = user_type_id
        self.fields = fields
        self.encoded = encoded
        self.is_compressed = is_compressed

    def create_fields_serializer(self, resolver, resolved_field_names=None, local_field_types=None):
        """Create serializers for each field.

        Args:
            resolver: The type resolver
            resolved_field_names: Optional list of resolved field names (for TAG_ID encoding).
                                  If None, uses field_info.name directly.
            local_field_types: Optional precomputed local field type map.
        """
        field_types = local_field_types
        if field_types is None:
            field_types = infer_field_types(self.cls, field_nullable=resolver.field_nullable)
        serializers = []
        for i, field_info in enumerate(self.fields):
            # Use resolved name if provided, otherwise use original name
            lookup_name = resolved_field_names[i] if resolved_field_names else field_info.name
            serializer = field_info.field_type.create_serializer(resolver, field_types.get(lookup_name, None))
            serializers.append(serializer)
        return serializers

    def get_field_names(self):
        return [field_info.name for field_info in self.fields]

    def _resolve_field_names_from_tag_ids(self):
        """Resolve actual field names from TAG_ID encoding or wire field names.

        When TAG_ID encoding is used, field names in the TypeDef are placeholders like "__tag_N__".
        This method looks up the registered class's field metadata to find the actual field names
        that correspond to each tag_id.

        For field name encoding (non-TAG_ID), the wire field name may be in snake_case
        (Java's xlang convention) while the Python class may use either snake_case or camelCase.
        This method tries to match the wire name against the Python class fields.

        Returns:
            List of resolved field names (same order as self.fields)
        """
        import dataclasses
        from pyfory.field import extract_field_meta

        # Build tag_id -> actual field name mapping from the class
        tag_id_to_field_name = {}
        class_field_names = set()
        if dataclasses.is_dataclass(self.cls):
            for dc_field in dataclasses.fields(self.cls):
                class_field_names.add(dc_field.name)
                meta = extract_field_meta(dc_field)
                if meta is not None and meta.id >= 0:
                    tag_id_to_field_name[meta.id] = dc_field.name

        # Resolve field names
        resolved_names = []
        for field_info in self.fields:
            if field_info.tag_id >= 0 and field_info.tag_id in tag_id_to_field_name:
                # TAG_ID encoding: use the actual field name from the class
                resolved_names.append(tag_id_to_field_name[field_info.tag_id])
            else:
                # Field name encoding: try to match with class fields
                wire_name = field_info.name
                if wire_name in class_field_names:
                    # Wire name matches class field directly (e.g., snake_case or camelCase)
                    resolved_names.append(wire_name)
                else:
                    # Try converting snake_case to camelCase
                    camel_name = _snake_to_camel(wire_name)
                    if camel_name in class_field_names:
                        resolved_names.append(camel_name)
                    else:
                        # Fallback: use the wire name as-is
                        resolved_names.append(wire_name)
        return resolved_names

    def create_serializer(self, resolver):
        if self.type_id == TypeId.NAMED_EXT:
            return resolver.get_type_info_by_name(self.namespace, self.typename).serializer
        if self.type_id == TypeId.NAMED_ENUM:
            try:
                return resolver.get_type_info_by_name(self.namespace, self.typename).serializer
            except Exception:
                from pyfory.serializer import NonExistEnumSerializer

                return NonExistEnumSerializer(resolver)
        if self.type_id == TypeId.NAMED_UNION:
            return resolver.get_type_info_by_name(self.namespace, self.typename).serializer

        from pyfory.struct import DataClassSerializer
        from pyfory.struct import FieldInfo as StructFieldInfo
        from pyfory.type_util import get_type_hints, unwrap_optional

        # Resolve actual field names from TAG_ID encoding if needed
        field_names = self._resolve_field_names_from_tag_ids()

        local_field_infos = build_field_infos(resolver, self.cls)
        local_infos_by_name = {field_info.name: field_info for field_info in local_field_infos}
        local_infos_by_tag = {field_info.tag_id: field_info for field_info in local_field_infos if field_info.tag_id >= 0}
        local_field_types = infer_field_types(self.cls, field_nullable=resolver.field_nullable)
        type_hints = get_type_hints(self.cls)
        runtime_field_infos = []
        for i, field_info in enumerate(self.fields):
            resolved_name = field_names[i]
            local_info = None
            if field_info.tag_id >= 0:
                local_info = local_infos_by_tag.get(field_info.tag_id)
            if local_info is None:
                local_info = local_infos_by_name.get(resolved_name)
            can_assign, validation_field_type = plan_field_assignment(
                field_info.field_type,
                local_info.field_type if local_info is not None else None,
            )
            type_hint = type_hints.get(resolved_name, typing.Any)
            unwrapped_type, _ = unwrap_optional(type_hint, field_nullable=resolver.field_nullable)
            serializer = _create_compatible_field_serializer(
                resolver,
                resolved_name,
                type_hint,
                field_info.field_type,
                local_info.field_type if local_info is not None else None,
                local_field_types.get(resolved_name, None),
            )
            runtime_field_infos.append(
                StructFieldInfo(
                    name=resolved_name,
                    index=i,
                    type_hint=type_hint,
                    tag_id=field_info.tag_id,
                    nullable=field_info.field_type.is_nullable,
                    ref=field_info.field_type.is_tracking_ref,
                    dynamic=is_polymorphic_type(field_info.field_type.type_id),
                    runtime_ref_tracking=field_info.field_type.is_tracking_ref,
                    type_id=field_info.field_type.type_id,
                    serializer=serializer,
                    unwrapped_type=unwrapped_type,
                    field_type=field_info.field_type,
                    assign=can_assign,
                    validation_field_type=validation_field_type,
                )
            )

        return DataClassSerializer(
            resolver,
            self.cls,
            field_infos=runtime_field_infos,
            fields_from_typedef=True,
        )

    def __repr__(self):
        return (
            f"TypeDef(namespace={self.namespace}, typename={self.typename}, cls={self.cls}, "
            f"type_id={self.type_id}, user_type_id={self.user_type_id}, "
            f"fields={self.fields}, is_compressed={self.is_compressed})"
        )


def _snake_to_camel(s: str) -> str:
    """Convert snake_case to camelCase.

    This reverses Java's lowerCamelToLowerUnderscore conversion:
    - new_object -> newObject
    - old_object -> oldObject
    - my_field_name -> myFieldName

    If there are no underscores, the string is returned unchanged.
    """
    if "_" not in s:
        return s
    parts = s.split("_")
    # First part stays lowercase, rest are capitalized
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


class FieldInfo:
    def __init__(self, name: str, field_type: "FieldType", defined_class: str, tag_id: int = -1):
        self.name = name
        self.field_type = field_type
        self.defined_class = defined_class
        self.tag_id = tag_id  # -1 = use field name encoding, >=0 = use tag ID encoding

    def uses_tag_id(self) -> bool:
        """Returns True if this field uses TAG_ID encoding."""
        return self.tag_id >= 0

    def write(self, buffer: Buffer):
        self.field_type.write(buffer, True)

    @classmethod
    def read(cls, buffer: Buffer, resolver):
        field_type = FieldType.read(buffer, resolver)
        # Note: name and defined_class would need to be read from the buffer
        # This is a simplified version
        return cls("", field_type, "")

    def __repr__(self):
        return f"FieldInfo(name={self.name}, field_type={self.field_type}, defined_class={self.defined_class}, tag_id={self.tag_id})"


class FieldType:
    def __init__(
        self,
        type_id: int,
        is_monomorphic: bool,
        is_nullable: bool,
        is_tracking_ref: bool,
        user_type_id: int = NO_USER_TYPE_ID,
    ):
        self.type_id = type_id
        self.user_type_id = user_type_id
        self.is_monomorphic = is_monomorphic
        self.is_nullable = is_nullable
        self.is_tracking_ref = is_tracking_ref
        self.tracking_ref_override = None

    def write(self, buffer: Buffer, write_flags: bool = True):
        xtype_id = self.type_id
        if write_flags:
            xtype_id = xtype_id << 2
            if self.is_nullable:
                xtype_id |= 0b10
            if self.is_tracking_ref:
                xtype_id |= 0b1
            buffer.write_var_uint32(xtype_id)
        else:
            buffer.write_uint8(xtype_id)
        # Handle nested types
        if self.type_id in [TypeId.LIST, TypeId.SET]:
            self.element_type.write(buffer, True)
        elif self.type_id == TypeId.MAP:
            self.key_type.write(buffer, True)
            self.value_type.write(buffer, True)

    @classmethod
    def read(cls, buffer: Buffer, resolver):
        xtype_id = buffer.read_var_uint32()
        is_tracking_ref = (xtype_id & 0b1) != 0
        is_nullable = (xtype_id & 0b10) != 0
        xtype_id = xtype_id >> 2
        return cls.read_with_type(buffer, resolver, xtype_id, is_nullable, is_tracking_ref)

    @classmethod
    def read_with_type(cls, buffer: Buffer, resolver, xtype_id: int, is_nullable: bool, is_tracking_ref: bool):
        user_type_id = NO_USER_TYPE_ID
        if xtype_id in [TypeId.LIST, TypeId.SET]:
            element_type = cls.read(buffer, resolver)
            return CollectionFieldType(xtype_id, True, is_nullable, is_tracking_ref, element_type)
        elif xtype_id == TypeId.MAP:
            key_type = cls.read(buffer, resolver)
            value_type = cls.read(buffer, resolver)
            return MapFieldType(xtype_id, True, is_nullable, is_tracking_ref, key_type, value_type)
        elif xtype_id == TypeId.UNKNOWN:
            return DynamicFieldType(xtype_id, False, is_nullable, is_tracking_ref, user_type_id=user_type_id)
        else:
            # For primitive types, determine if they are monomorphic based on the type
            is_monomorphic = not is_polymorphic_type(xtype_id)
            return FieldType(xtype_id, is_monomorphic, is_nullable, is_tracking_ref, user_type_id=user_type_id)

    def create_serializer(self, resolver, type_):
        # Handle list wrapper
        if isinstance(type_, list):
            type_ = type_[0]
        if self.type_id in _ARRAY_TYPE_IDS:
            array_meta = type_ if isinstance(type_, ArrayMeta) else None
            if array_meta is None and type_ is not None:
                from pyfory.type_util import unwrap_array

                array_meta = unwrap_array(type_)
            if array_meta is not None:
                from pyfory.struct import StructFieldSerializerVisitor

                return StructFieldSerializerVisitor(resolver).visit_array(
                    "<array>",
                    array_meta.element_type,
                    array_meta.carrier,
                )
        if is_union_type(self.type_id):
            if type_ is None:
                return None
            try:
                return resolver.get_type_info(cls=type_).serializer
            except Exception:
                return None
        # Types that need to be handled dynamically during deserialization
        # For these types, we don't know the concrete type at compile time
        if self.type_id in [
            TypeId.EXT,
            TypeId.NAMED_EXT,
            TypeId.STRUCT,
            TypeId.NAMED_STRUCT,
            TypeId.COMPATIBLE_STRUCT,
            TypeId.NAMED_COMPATIBLE_STRUCT,
            TypeId.UNKNOWN,
        ]:
            if type_ is None:
                return None
            try:
                return resolver.get_type_info(cls=type_).serializer
            except Exception:
                return None
        if self.type_id in [TypeId.ENUM]:
            try:
                if issubclass(type_, enum.Enum):
                    return resolver.get_type_info(cls=type_).serializer
            except Exception:
                pass
            from pyfory.serializer import NonExistEnumSerializer

            return NonExistEnumSerializer(resolver)
        typeinfo = resolver.get_type_info_by_id(self.type_id)
        return typeinfo.serializer

    def __repr__(self):
        return (
            f"FieldType(type_id={self.type_id}, user_type_id={self.user_type_id}, "
            f"is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, "
            f"is_tracking_ref={self.is_tracking_ref})"
        )


class CollectionFieldType(FieldType):
    def __init__(
        self,
        type_id: int,
        is_monomorphic: bool,
        is_nullable: bool,
        is_tracking_ref: bool,
        element_type: FieldType,
    ):
        super().__init__(type_id, is_monomorphic, is_nullable, is_tracking_ref)
        self.element_type = element_type

    def create_serializer(self, resolver, type_):
        from pyfory.serializer import ListSerializer, SetSerializer, TupleSerializer

        declared_root_type = type_
        elem_type = None
        if isinstance(type_, list):
            declared_root_type = type_[0]
        if isinstance(declared_root_type, tuple):
            if declared_root_type:
                declared_root_type, *extra = declared_root_type
                elem_type = extra[0] if extra else None
        elif type_ and len(type_) >= 2:
            elem_type = type_[1]
        elem_serializer = self.element_type.create_serializer(resolver, elem_type)
        elem_override = getattr(self.element_type, "tracking_ref_override", None)
        if self.type_id == TypeId.LIST:
            if declared_root_type in (tuple, typing.Tuple):
                return TupleSerializer(resolver, tuple, elem_serializer, elem_override)
            return ListSerializer(resolver, list, elem_serializer, elem_override)
        elif self.type_id == TypeId.SET:
            return SetSerializer(resolver, set, elem_serializer, elem_override)
        else:
            raise ValueError(f"Unknown collection type: {self.type_id}")


class MapFieldType(FieldType):
    def __init__(
        self,
        type_id: int,
        is_monomorphic: bool,
        is_nullable: bool,
        is_tracking_ref: bool,
        key_type: FieldType,
        value_type: FieldType,
    ):
        super().__init__(type_id, is_monomorphic, is_nullable, is_tracking_ref)
        self.key_type = key_type
        self.value_type = value_type

    def create_serializer(self, resolver, type_):
        key_type, value_type = None, None
        if type_ and len(type_) >= 2:
            key_type = type_[1]
        if type_ and len(type_) >= 3:
            value_type = type_[2]
        key_serializer = self.key_type.create_serializer(resolver, key_type)
        value_serializer = self.value_type.create_serializer(resolver, value_type)
        key_override = getattr(self.key_type, "tracking_ref_override", None)
        value_override = getattr(self.value_type, "tracking_ref_override", None)
        from pyfory.serializer import MapSerializer

        return MapSerializer(
            resolver,
            dict,
            key_serializer,
            value_serializer,
            key_override,
            value_override,
        )

    def __repr__(self):
        return (
            f"MapFieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, "
            f"is_tracking_ref={self.is_tracking_ref}, key_type={self.key_type}, value_type={self.value_type})"
        )


class DynamicFieldType(FieldType):
    def __init__(
        self,
        type_id: int,
        is_monomorphic: bool,
        is_nullable: bool,
        is_tracking_ref: bool,
        user_type_id: int = NO_USER_TYPE_ID,
    ):
        super().__init__(type_id, is_monomorphic, is_nullable, is_tracking_ref, user_type_id=user_type_id)

    def create_serializer(self, resolver, type_):
        # For dynamic field types (UNKNOWN, STRUCT, etc.), default to None so
        # type info is written/read at runtime for cross-language compatibility.
        # Exception: union fields are declared, so we should use the union serializer
        # to write/read the union payload correctly.
        if isinstance(type_, list):
            type_ = type_[0]
        assert not is_union_type(self.type_id), (
            "Union fields don't write field type info, \
            they are not dynamic field types"
        )
        if self.type_id != TypeId.UNKNOWN:
            return FieldType.create_serializer(self, resolver, type_)
        return None

    def __repr__(self):
        return f"DynamicFieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, is_tracking_ref={self.is_tracking_ref})"


_ARRAY_TYPE_IDS = frozenset(
    (
        TypeId.BOOL_ARRAY,
        TypeId.INT8_ARRAY,
        TypeId.INT16_ARRAY,
        TypeId.INT32_ARRAY,
        TypeId.INT64_ARRAY,
        TypeId.UINT8_ARRAY,
        TypeId.UINT16_ARRAY,
        TypeId.UINT32_ARRAY,
        TypeId.UINT64_ARRAY,
        TypeId.FLOAT16_ARRAY,
        TypeId.BFLOAT16_ARRAY,
        TypeId.FLOAT32_ARRAY,
        TypeId.FLOAT64_ARRAY,
    )
)


def _payload_shape_matches(remote_field_type: FieldType, local_field_type: FieldType) -> bool:
    if local_field_type is None:
        return False
    remote_type_id = remote_field_type.type_id
    local_type_id = local_field_type.type_id
    if _is_bytes_uint8_array_pair(remote_type_id, local_type_id):
        return True
    if remote_type_id != local_type_id:
        return False
    if remote_type_id in (TypeId.LIST, TypeId.SET):
        return _payload_shape_matches(remote_field_type.element_type, local_field_type.element_type)
    if remote_type_id == TypeId.MAP:
        return _payload_shape_matches(remote_field_type.key_type, local_field_type.key_type) and _payload_shape_matches(
            remote_field_type.value_type,
            local_field_type.value_type,
        )
    return True


def _payload_shape_needs_local_carrier(remote_field_type: FieldType, local_field_type: FieldType) -> bool:
    remote_type_id = remote_field_type.type_id
    local_type_id = local_field_type.type_id
    if _is_bytes_uint8_array_pair(remote_type_id, local_type_id):
        return True
    if remote_type_id != local_type_id:
        return False
    if remote_type_id in _ARRAY_TYPE_IDS:
        return True
    if remote_type_id in (TypeId.LIST, TypeId.SET):
        return _payload_shape_needs_local_carrier(remote_field_type.element_type, local_field_type.element_type)
    if remote_type_id == TypeId.MAP:
        return _payload_shape_needs_local_carrier(
            remote_field_type.key_type,
            local_field_type.key_type,
        ) or _payload_shape_needs_local_carrier(remote_field_type.value_type, local_field_type.value_type)
    return False


def _create_local_typehint_serializer(resolver, field_name, type_hint):
    from pyfory.struct import StructFieldSerializerVisitor
    from pyfory.type_util import infer_field, unwrap_optional

    unwrapped_type, _ = unwrap_optional(type_hint, field_nullable=resolver.field_nullable)
    return infer_field(field_name, unwrapped_type, StructFieldSerializerVisitor(resolver))


def _create_compatible_field_serializer(
    resolver,
    field_name,
    type_hint,
    remote_field_type: FieldType,
    local_field_type: typing.Optional[FieldType],
    local_declared_type,
):
    if _payload_shape_matches(remote_field_type, local_field_type) and _payload_shape_needs_local_carrier(remote_field_type, local_field_type):
        serializer = _create_local_typehint_serializer(resolver, field_name, type_hint)
        if serializer is not None:
            return serializer
    return remote_field_type.create_serializer(resolver, local_declared_type)


_SIGNED_INT32_TYPE_IDS = frozenset((TypeId.INT32, TypeId.VARINT32))
_SIGNED_INT64_TYPE_IDS = frozenset((TypeId.INT64, TypeId.VARINT64, TypeId.TAGGED_INT64))
_UNSIGNED_INT32_TYPE_IDS = frozenset((TypeId.UINT32, TypeId.VAR_UINT32))
_UNSIGNED_INT64_TYPE_IDS = frozenset((TypeId.UINT64, TypeId.VAR_UINT64, TypeId.TAGGED_UINT64))
_INT_TYPE_DOMAINS = {type_id: (True, 32) for type_id in _SIGNED_INT32_TYPE_IDS}
_INT_TYPE_DOMAINS.update({type_id: (True, 64) for type_id in _SIGNED_INT64_TYPE_IDS})
_INT_TYPE_DOMAINS.update({type_id: (False, 32) for type_id in _UNSIGNED_INT32_TYPE_IDS})
_INT_TYPE_DOMAINS.update({type_id: (False, 64) for type_id in _UNSIGNED_INT64_TYPE_IDS})
_INT_RANGES = {
    (True, 32): (-(1 << 31), (1 << 31) - 1),
    (True, 64): (-(1 << 63), (1 << 63) - 1),
    (False, 32): (0, (1 << 32) - 1),
    (False, 64): (0, (1 << 64) - 1),
}


def _requires_nullable_validation(remote_field_type: FieldType, local_field_type: FieldType) -> bool:
    return remote_field_type.is_nullable and not local_field_type.is_nullable


def _is_bytes_uint8_array_pair(remote_type_id: int, local_type_id: int) -> bool:
    return (remote_type_id == TypeId.BINARY and local_type_id == TypeId.UINT8_ARRAY) or (
        remote_type_id == TypeId.UINT8_ARRAY and local_type_id == TypeId.BINARY
    )


def _field_type_assignment(remote_field_type: FieldType, local_field_type: FieldType) -> typing.Tuple[bool, bool]:
    if local_field_type is None:
        return False, False
    needs_validation = _requires_nullable_validation(remote_field_type, local_field_type)
    remote_type_id = remote_field_type.type_id
    local_type_id = local_field_type.type_id
    if local_type_id == TypeId.UNKNOWN:
        return True, needs_validation
    if remote_type_id == TypeId.UNKNOWN:
        return True, True
    if remote_type_id in (TypeId.LIST, TypeId.SET):
        if local_type_id != remote_type_id:
            return False, False
        child_assignable, child_needs_validation = _field_type_assignment(
            remote_field_type.element_type,
            local_field_type.element_type,
        )
        return child_assignable, needs_validation or child_needs_validation
    if remote_type_id == TypeId.MAP:
        if local_type_id != TypeId.MAP:
            return False, False
        key_assignable, key_needs_validation = _field_type_assignment(
            remote_field_type.key_type,
            local_field_type.key_type,
        )
        value_assignable, value_needs_validation = _field_type_assignment(
            remote_field_type.value_type,
            local_field_type.value_type,
        )
        return key_assignable and value_assignable, needs_validation or key_needs_validation or value_needs_validation
    if _is_bytes_uint8_array_pair(remote_type_id, local_type_id):
        return True, True
    remote_int_domain = _INT_TYPE_DOMAINS.get(remote_type_id)
    local_int_domain = _INT_TYPE_DOMAINS.get(local_type_id)
    if remote_int_domain is not None or local_int_domain is not None:
        if remote_int_domain is None or local_int_domain is None:
            return False, False
        remote_signed, remote_width = remote_int_domain
        local_signed, local_width = local_int_domain
        if remote_signed != local_signed:
            return False, False
        return True, needs_validation or remote_width > local_width
    if remote_type_id == local_type_id:
        return True, needs_validation
    return False, False


def plan_field_assignment(
    remote_field_type: FieldType, local_field_type: typing.Optional[FieldType]
) -> typing.Tuple[bool, typing.Optional[FieldType]]:
    assignable, needs_validation = _field_type_assignment(remote_field_type, local_field_type)
    if not assignable:
        return False, None
    return True, local_field_type if needs_validation else None


def _validate_int_value(value, type_id: int) -> bool:
    domain = _INT_TYPE_DOMAINS.get(type_id)
    if domain is None:
        return True
    if type(value) is not int:
        return False
    min_value, max_value = _INT_RANGES[domain]
    return min_value <= value <= max_value


def _numpy_uint8_type():
    try:
        import numpy as np

        return np, np.ndarray, np.dtype(np.uint8)
    except ImportError:
        return None, None, None


def _is_bytes_like(value) -> bool:
    return isinstance(value, (bytes, bytearray, memoryview))


def _is_fory_uint8_array(value) -> bool:
    try:
        from pyfory import serialization

        return type(value) is serialization.UInt8Array
    except AttributeError:
        return False


def _is_uint8_array_like(value) -> bool:
    if _is_fory_uint8_array(value):
        return True
    if isinstance(value, array.array):
        return value.typecode == "B"
    np, ndarray, uint8_dtype = _numpy_uint8_type()
    return np is not None and isinstance(value, ndarray) and value.ndim == 1 and value.dtype == uint8_dtype


def _bytes_from_uint8_value(value) -> bytes:
    if isinstance(value, bytes):
        return value
    if isinstance(value, bytearray):
        return bytes(value)
    if isinstance(value, memoryview):
        return value.tobytes()
    if _is_fory_uint8_array(value):
        return bytes(value)
    if isinstance(value, array.array) and value.typecode == "B":
        return value.tobytes()
    if _is_uint8_array_like(value):
        return value.tobytes()
    raise TypeError(f"Expected bytes or array<uint8> compatible value, got {type(value)!r}")


def _uint8_array_from_bytes(value):
    data = _bytes_from_uint8_value(value)
    from pyfory import serialization

    return serialization.UInt8Array(data)


def is_value_assignable(value, local_field_type: FieldType) -> bool:
    if value is None:
        return local_field_type.is_nullable
    type_id = local_field_type.type_id
    if type_id == TypeId.UNKNOWN:
        return True
    if type_id in (TypeId.LIST, TypeId.SET):
        if not isinstance(value, (list, tuple, set)):
            return False
        return all(is_value_assignable(element, local_field_type.element_type) for element in value)
    if type_id == TypeId.MAP:
        if not isinstance(value, dict):
            return False
        return all(
            is_value_assignable(key, local_field_type.key_type) and is_value_assignable(map_value, local_field_type.value_type)
            for key, map_value in value.items()
        )
    if type_id in _INT_TYPE_DOMAINS:
        return _validate_int_value(value, type_id)
    if type_id == TypeId.BINARY:
        return _is_bytes_like(value) or _is_uint8_array_like(value)
    if type_id == TypeId.UINT8_ARRAY:
        return _is_bytes_like(value) or _is_uint8_array_like(value)
    if type_id == TypeId.BOOL:
        return type(value) is bool
    if type_id in (TypeId.FLOAT32, TypeId.FLOAT64):
        return type(value) is float
    if type_id == TypeId.STRING:
        return type(value) is str
    return True


def coerce_assignable_value(value, local_field_type: FieldType):
    if value is None:
        return None
    type_id = local_field_type.type_id
    if type_id == TypeId.BINARY:
        return _bytes_from_uint8_value(value)
    if type_id == TypeId.UINT8_ARRAY and _is_bytes_like(value):
        return _uint8_array_from_bytes(value)
    if type_id == TypeId.LIST:
        return [coerce_assignable_value(element, local_field_type.element_type) for element in value]
    if type_id == TypeId.SET:
        return {coerce_assignable_value(element, local_field_type.element_type) for element in value}
    if type_id == TypeId.MAP:
        return {
            coerce_assignable_value(key, local_field_type.key_type): coerce_assignable_value(map_value, local_field_type.value_type)
            for key, map_value in value.items()
        }
    return value


def build_field_infos(type_resolver, cls):
    """Build field information for the class.

    Extracts field metadata from pyfory.field() if present, including tag_id,
    nullable, and ref settings.
    """
    from pyfory.struct import _sort_fields, StructTypeIdVisitor, get_field_names
    from pyfory.type_util import unwrap_optional
    from pyfory.field import extract_field_meta
    import dataclasses

    field_names = get_field_names(cls)
    from pyfory.type_util import get_type_hints

    type_hints = get_type_hints(cls)

    # Extract field metadata from dataclass fields if available
    field_metas = {}
    if dataclasses.is_dataclass(cls):
        for dc_field in dataclasses.fields(cls):
            meta = extract_field_meta(dc_field)
            if meta is not None:
                field_metas[dc_field.name] = meta

    field_infos = []
    nullable_map = {}
    visitor = StructTypeIdVisitor(type_resolver, cls)
    field_nullable = type_resolver.field_nullable
    global_ref_tracking = type_resolver.track_ref

    for field_name in field_names:
        field_type_hint = type_hints.get(field_name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(field_type_hint, field_nullable=field_nullable)

        # Get field metadata if available
        fory_meta = field_metas.get(field_name)
        if fory_meta is not None and fory_meta.ignore:
            # Skip ignored fields
            continue

        # Determine nullable: Optional[T] stays nullable even when field metadata
        # exists only to carry a tag id or default.
        if fory_meta is not None:
            is_nullable = fory_meta.nullable or is_optional or field_nullable
        else:
            # Default behavior: Optional[T] fields are nullable. Global field_nullable
            # can force nullable for all fields.
            is_nullable = is_optional or field_nullable

        # Determine ref tracking: field.ref AND global track_ref
        if fory_meta is not None:
            is_tracking_ref = fory_meta.ref and global_ref_tracking
        else:
            # By default, field-level ref tracking is off unless explicitly annotated.
            is_tracking_ref = False

        # Get tag_id from metadata (-1 if not specified)
        tag_id = fory_meta.id if fory_meta is not None else -1

        nullable_map[field_name] = is_nullable
        field_type = build_field_type_with_ref(type_resolver, field_name, unwrapped_type, visitor, is_nullable, is_tracking_ref)
        field_info = FieldInfo(field_name, field_type, cls.__name__, tag_id)
        field_infos.append(field_info)

    field_types = infer_field_types(cls, field_nullable=field_nullable)
    serializers = [field_info.field_type.create_serializer(type_resolver, field_types.get(field_info.name, None)) for field_info in field_infos]

    # Get just the field names for sorting
    current_field_names = [fi.name for fi in field_infos]
    sorted_field_names, serializers = _sort_fields(
        type_resolver,
        current_field_names,
        serializers,
        nullable_map,
        field_infos,
    )
    field_infos_map = {field_info.name: field_info for field_info in field_infos}
    new_field_infos = []
    for field_name in sorted_field_names:
        field_info = field_infos_map[field_name]
        new_field_infos.append(field_info)
    return new_field_infos


def build_field_type_with_ref(type_resolver, field_name: str, type_hint, visitor, is_nullable=False, is_tracking_ref=True):
    """Build field type from type hint with explicit ref tracking control."""
    type_ids = infer_field(field_name, type_hint, visitor)
    try:
        return build_field_type_from_type_ids_with_ref(
            type_resolver,
            field_name,
            type_ids,
            visitor,
            is_nullable,
            is_tracking_ref,
            type_hint=type_hint,
        )
    except Exception as e:
        raise TypeError(f"Error building field type for field: {field_name} with type hint: {type_hint} in class: {visitor.cls}") from e


def build_field_type_from_type_ids_with_ref(
    type_resolver,
    field_name: str,
    type_ids,
    visitor,
    is_nullable=False,
    is_tracking_ref=True,
    type_hint=None,
):
    """Build field type from type IDs with explicit ref tracking control."""
    from pyfory.type_util import unwrap_ref
    from pyfory.type_util import unwrap_optional

    type_id = type_ids[0]
    if type_id is None:
        type_id = TypeId.UNKNOWN
    if type_id == TypeId.NAMED_ENUM:
        type_id = TypeId.ENUM
    if type_id in (TypeId.NAMED_UNION, TypeId.TYPED_UNION):
        type_id = TypeId.UNION
    assert type_id >= 0, f"Unknown type: {type_id} for field: {field_name}"
    morphic = not is_polymorphic_type(type_id)
    if type_id in [TypeId.SET, TypeId.LIST]:
        elem_hint = None
        elem_nullable = False
        elem_ref_override = None
        if type_hint is not None:
            origin = typing.get_origin(type_hint) if hasattr(typing, "get_origin") else getattr(type_hint, "__origin__", None)
            if origin in (list, typing.List, set, typing.Set):
                args = typing.get_args(type_hint) if hasattr(typing, "get_args") else getattr(type_hint, "__args__", ())
                if args:
                    elem_hint, elem_ref_override = unwrap_ref(args[0])
                    elem_hint, elem_nullable = unwrap_optional(elem_hint)
            elif origin in (tuple, typing.Tuple):
                tuple_elem_hint = get_homogeneous_tuple_elem_type(type_hint)
                if tuple_elem_hint is not None:
                    elem_hint, elem_ref_override = unwrap_ref(tuple_elem_hint)
                    elem_hint, elem_nullable = unwrap_optional(elem_hint)
        elem_tracking_ref = is_tracking_ref
        if elem_ref_override is not None:
            elem_tracking_ref = elem_ref_override and type_resolver.track_ref
        elem_type = build_field_type_from_type_ids_with_ref(
            type_resolver,
            field_name,
            type_ids[1],
            visitor,
            is_nullable=elem_nullable,
            is_tracking_ref=elem_tracking_ref,
            type_hint=elem_hint,
        )
        if elem_ref_override is not None:
            elem_type.tracking_ref_override = elem_ref_override
        return CollectionFieldType(type_id, morphic, is_nullable, is_tracking_ref, elem_type)
    elif type_id == TypeId.MAP:
        key_hint = None
        value_hint = None
        key_nullable = False
        value_nullable = False
        key_ref_override = None
        value_ref_override = None
        if type_hint is not None:
            origin = typing.get_origin(type_hint) if hasattr(typing, "get_origin") else getattr(type_hint, "__origin__", None)
            if origin in (dict, typing.Dict):
                args = typing.get_args(type_hint) if hasattr(typing, "get_args") else getattr(type_hint, "__args__", ())
                if len(args) >= 2:
                    key_hint, key_ref_override = unwrap_ref(args[0])
                    key_hint, key_nullable = unwrap_optional(key_hint)
                    value_hint, value_ref_override = unwrap_ref(args[1])
                    value_hint, value_nullable = unwrap_optional(value_hint)
        key_tracking_ref = is_tracking_ref
        if key_ref_override is not None:
            key_tracking_ref = key_ref_override and type_resolver.track_ref
        value_tracking_ref = is_tracking_ref
        if value_ref_override is not None:
            value_tracking_ref = value_ref_override and type_resolver.track_ref
        key_type = build_field_type_from_type_ids_with_ref(
            type_resolver,
            field_name,
            type_ids[1],
            visitor,
            is_nullable=key_nullable,
            is_tracking_ref=key_tracking_ref,
            type_hint=key_hint,
        )
        value_type = build_field_type_from_type_ids_with_ref(
            type_resolver,
            field_name,
            type_ids[2],
            visitor,
            is_nullable=value_nullable,
            is_tracking_ref=value_tracking_ref,
            type_hint=value_hint,
        )
        if key_ref_override is not None:
            key_type.tracking_ref_override = key_ref_override
        if value_ref_override is not None:
            value_type.tracking_ref_override = value_ref_override
        return MapFieldType(type_id, morphic, is_nullable, is_tracking_ref, key_type, value_type)
    elif type_id in [
        TypeId.UNKNOWN,
        TypeId.EXT,
        TypeId.STRUCT,
        TypeId.NAMED_STRUCT,
        TypeId.COMPATIBLE_STRUCT,
        TypeId.NAMED_COMPATIBLE_STRUCT,
    ]:
        return DynamicFieldType(type_id, False, is_nullable, is_tracking_ref, user_type_id=NO_USER_TYPE_ID)
    else:
        if type_id <= 0 or type_id >= TypeId.BOUND:
            raise TypeError(f"Unknown type: {type_id} for field: {field_name}")
        # union/enum go here too
        return FieldType(type_id, morphic, is_nullable, is_tracking_ref, user_type_id=NO_USER_TYPE_ID)


def build_field_type(type_resolver, field_name: str, type_hint, visitor, is_nullable=False):
    """Build field type from type hint."""
    type_ids = infer_field(field_name, type_hint, visitor)
    try:
        return build_field_type_from_type_ids(type_resolver, field_name, type_ids, visitor, is_nullable, type_hint=type_hint)
    except Exception as e:
        raise TypeError(f"Error building field type for field: {field_name} with type hint: {type_hint} in class: {visitor.cls}") from e


def build_field_type_from_type_ids(type_resolver, field_name: str, type_ids, visitor, is_nullable=False, type_hint=None):
    from pyfory.type_util import unwrap_optional, unwrap_ref

    tracking_ref = type_resolver.track_ref
    type_id = type_ids[0]
    if type_id is None:
        type_id = TypeId.UNKNOWN
    if type_id == TypeId.NAMED_ENUM:
        type_id = TypeId.ENUM
    if type_id in (TypeId.NAMED_UNION, TypeId.TYPED_UNION):
        type_id = TypeId.UNION
    assert type_id >= 0, f"Unknown type: {type_id} for field: {field_name}"
    morphic = not is_polymorphic_type(type_id)
    if type_id in [TypeId.SET, TypeId.LIST]:
        elem_hint = None
        elem_nullable = False
        if type_hint is not None:
            origin = typing.get_origin(type_hint) if hasattr(typing, "get_origin") else getattr(type_hint, "__origin__", None)
            if origin in (list, typing.List, set, typing.Set):
                args = typing.get_args(type_hint) if hasattr(typing, "get_args") else getattr(type_hint, "__args__", ())
                if args:
                    elem_hint, _ = unwrap_ref(args[0])
                    elem_hint, elem_nullable = unwrap_optional(elem_hint)
            elif origin in (tuple, typing.Tuple):
                tuple_elem_hint = get_homogeneous_tuple_elem_type(type_hint)
                if tuple_elem_hint is not None:
                    elem_hint, _ = unwrap_ref(tuple_elem_hint)
                    elem_hint, elem_nullable = unwrap_optional(elem_hint)
        elem_type = build_field_type_from_type_ids(
            type_resolver,
            field_name,
            type_ids[1],
            visitor,
            is_nullable=elem_nullable,
            type_hint=elem_hint,
        )
        return CollectionFieldType(type_id, morphic, is_nullable, tracking_ref, elem_type)
    elif type_id == TypeId.MAP:
        key_hint = None
        value_hint = None
        key_nullable = False
        value_nullable = False
        if type_hint is not None:
            origin = typing.get_origin(type_hint) if hasattr(typing, "get_origin") else getattr(type_hint, "__origin__", None)
            if origin in (dict, typing.Dict):
                args = typing.get_args(type_hint) if hasattr(typing, "get_args") else getattr(type_hint, "__args__", ())
                if len(args) >= 2:
                    key_hint, _ = unwrap_ref(args[0])
                    key_hint, key_nullable = unwrap_optional(key_hint)
                    value_hint, _ = unwrap_ref(args[1])
                    value_hint, value_nullable = unwrap_optional(value_hint)
        key_type = build_field_type_from_type_ids(
            type_resolver,
            field_name,
            type_ids[1],
            visitor,
            is_nullable=key_nullable,
            type_hint=key_hint,
        )
        value_type = build_field_type_from_type_ids(
            type_resolver,
            field_name,
            type_ids[2],
            visitor,
            is_nullable=value_nullable,
            type_hint=value_hint,
        )
        return MapFieldType(type_id, morphic, is_nullable, tracking_ref, key_type, value_type)
    elif type_id in [
        TypeId.UNKNOWN,
        TypeId.EXT,
        TypeId.STRUCT,
        TypeId.NAMED_STRUCT,
        TypeId.COMPATIBLE_STRUCT,
        TypeId.NAMED_COMPATIBLE_STRUCT,
    ]:
        return DynamicFieldType(type_id, False, is_nullable, tracking_ref, user_type_id=NO_USER_TYPE_ID)
    else:
        if type_id <= 0 or type_id >= TypeId.BOUND:
            raise TypeError(f"Unknown type: {type_id} for field: {field_name}")
        return FieldType(type_id, morphic, is_nullable, tracking_ref, user_type_id=NO_USER_TYPE_ID)
