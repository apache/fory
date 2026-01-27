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
import typing
from typing import List
from pyfory.types import TypeId, is_primitive_type, is_polymorphic_type
from pyfory.buffer import Buffer
from pyfory.type_util import infer_field
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
        self, namespace: str, typename: str, cls: type, type_id: int, fields: List["FieldInfo"], encoded: bytes = None, is_compressed: bool = False
    ):
        self.namespace = namespace
        self.typename = typename
        self.cls = cls
        self.type_id = type_id
        self.fields = fields
        self.encoded = encoded
        self.is_compressed = is_compressed

    def create_fields_serializer(self, resolver, resolved_field_names=None):
        """Create serializers for each field.

        Args:
            resolver: The type resolver
            resolved_field_names: Optional list of resolved field names (for TAG_ID encoding).
                                  If None, uses field_info.name directly.
        """
        field_nullable = resolver.fory.field_nullable
        field_types = infer_field_types(self.cls, field_nullable=field_nullable)
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
        if self.type_id & 0xFF == TypeId.NAMED_EXT:
            return resolver.get_typeinfo_by_name(self.namespace, self.typename).serializer
        if self.type_id & 0xFF == TypeId.NAMED_ENUM:
            try:
                return resolver.get_typeinfo_by_name(self.namespace, self.typename).serializer
            except Exception:
                from pyfory.serializer import NonExistEnumSerializer

                return NonExistEnumSerializer(resolver.fory)

        from pyfory.struct import DataClassSerializer

        fory = resolver.fory

        # Resolve actual field names from TAG_ID encoding if needed
        field_names = self._resolve_field_names_from_tag_ids()

        # Build nullable_fields using resolved field names
        nullable_fields = {}
        for i, field_info in enumerate(self.fields):
            resolved_name = field_names[i]
            nullable_fields[resolved_name] = field_info.field_type.is_nullable

        return DataClassSerializer(
            fory,
            self.cls,
            xlang=not fory.is_py,
            field_names=field_names,
            serializers=self.create_fields_serializer(resolver, field_names),
            nullable_fields=nullable_fields,
        )

    def __repr__(self):
        return f"TypeDef(namespace={self.namespace}, typename={self.typename}, cls={self.cls}, type_id={self.type_id}, fields={self.fields}, is_compressed={self.is_compressed})"


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

    def xwrite(self, buffer: Buffer):
        self.field_type.xwrite(buffer, True)

    @classmethod
    def xread(cls, buffer: Buffer, resolver):
        field_type = FieldType.xread(buffer, resolver)
        # Note: name and defined_class would need to be read from the buffer
        # This is a simplified version
        return cls("", field_type, "")

    def __repr__(self):
        return f"FieldInfo(name={self.name}, field_type={self.field_type}, defined_class={self.defined_class}, tag_id={self.tag_id})"


class FieldType:
    def __init__(self, type_id: int, is_monomorphic: bool, is_nullable: bool, is_tracking_ref: bool):
        self.type_id = type_id
        self.is_monomorphic = is_monomorphic
        self.is_nullable = is_nullable
        self.is_tracking_ref = is_tracking_ref

    def xwrite(self, buffer: Buffer, write_flags: bool = True):
        xtype_id = self.type_id
        if write_flags:
            xtype_id = xtype_id << 2
            if self.is_nullable:
                xtype_id |= 0b10
            if self.is_tracking_ref:
                xtype_id |= 0b1
        buffer.write_varuint32(xtype_id)
        # Handle nested types
        if self.type_id in [TypeId.LIST, TypeId.SET]:
            self.element_type.xwrite(buffer, True)
        elif self.type_id == TypeId.MAP:
            self.key_type.xwrite(buffer, True)
            self.value_type.xwrite(buffer, True)

    @classmethod
    def xread(cls, buffer: Buffer, resolver):
        xtype_id = buffer.read_varuint32()
        is_tracking_ref = (xtype_id & 0b1) != 0
        is_nullable = (xtype_id & 0b10) != 0
        xtype_id = xtype_id >> 2
        return cls.xread_with_type(buffer, resolver, xtype_id, is_nullable, is_tracking_ref)

    @classmethod
    def xread_with_type(cls, buffer: Buffer, resolver, xtype_id: int, is_nullable: bool, is_tracking_ref: bool):
        if xtype_id in [TypeId.LIST, TypeId.SET]:
            element_type = cls.xread(buffer, resolver)
            return CollectionFieldType(xtype_id, True, is_nullable, is_tracking_ref, element_type)
        elif xtype_id == TypeId.MAP:
            key_type = cls.xread(buffer, resolver)
            value_type = cls.xread(buffer, resolver)
            return MapFieldType(xtype_id, True, is_nullable, is_tracking_ref, key_type, value_type)
        elif xtype_id == TypeId.UNKNOWN:
            return DynamicFieldType(xtype_id, False, is_nullable, is_tracking_ref)
        else:
            # For primitive types, determine if they are monomorphic based on the type
            is_monomorphic = not is_polymorphic_type(xtype_id)
            return FieldType(xtype_id, is_monomorphic, is_nullable, is_tracking_ref)

    def create_serializer(self, resolver, type_):
        # Handle list wrapper
        if isinstance(type_, list):
            type_ = type_[0]
        # Types that need to be handled dynamically during deserialization
        # For these types, we don't know the concrete type at compile time
        if self.type_id & 0xFF in [
            TypeId.EXT,
            TypeId.NAMED_EXT,
            TypeId.STRUCT,
            TypeId.NAMED_STRUCT,
            TypeId.COMPATIBLE_STRUCT,
            TypeId.NAMED_COMPATIBLE_STRUCT,
            TypeId.UNKNOWN,
        ]:
            return None
        if self.type_id & 0xFF in [TypeId.ENUM, TypeId.NAMED_ENUM]:
            try:
                if issubclass(type_, enum.Enum):
                    return resolver.get_typeinfo(cls=type_).serializer
            except Exception:
                pass
            from pyfory.serializer import NonExistEnumSerializer

            return NonExistEnumSerializer(resolver.fory)
        typeinfo = resolver.get_typeinfo_by_id(self.type_id)
        return typeinfo.serializer

    def __repr__(self):
        type_id = self.type_id
        if type_id > 128:
            type_id = f"{type_id}, fory_id={type_id & 0xFF}, user_id={type_id >> 8}"
        return (
            f"FieldType(type_id={type_id}, is_monomorphic={self.is_monomorphic}, "
            f"is_nullable={self.is_nullable}, is_tracking_ref={self.is_tracking_ref})"
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
        from pyfory.serializer import ListSerializer, SetSerializer

        elem_type = type_[1] if type_ and len(type_) >= 2 else None
        elem_serializer = self.element_type.create_serializer(resolver, elem_type)
        if self.type_id == TypeId.LIST:
            return ListSerializer(resolver.fory, list, elem_serializer)
        elif self.type_id == TypeId.SET:
            return SetSerializer(resolver.fory, set, elem_serializer)
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
        from pyfory.serializer import MapSerializer

        return MapSerializer(resolver.fory, dict, key_serializer, value_serializer)

    def __repr__(self):
        return (
            f"MapFieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, "
            f"is_tracking_ref={self.is_tracking_ref}, key_type={self.key_type}, value_type={self.value_type})"
        )


class DynamicFieldType(FieldType):
    def __init__(self, type_id: int, is_monomorphic: bool, is_nullable: bool, is_tracking_ref: bool):
        super().__init__(type_id, is_monomorphic, is_nullable, is_tracking_ref)

    def create_serializer(self, resolver, type_):
        # For dynamic field types (UNKNOWN, STRUCT, etc.), always return None
        # This ensures type info is written/read at runtime, which is required
        # for cross-language compatibility (Java always writes type info for struct fields)
        return None

    def __repr__(self):
        return f"DynamicFieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, is_tracking_ref={self.is_tracking_ref})"


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
    type_hints = typing.get_type_hints(cls)

    # Extract field metadata from dataclass fields if available
    field_metas = {}
    if dataclasses.is_dataclass(cls):
        for dc_field in dataclasses.fields(cls):
            meta = extract_field_meta(dc_field)
            if meta is not None:
                field_metas[dc_field.name] = meta

    field_infos = []
    nullable_map = {}
    visitor = StructTypeIdVisitor(type_resolver.fory, cls)
    field_nullable = type_resolver.fory.field_nullable
    global_ref_tracking = type_resolver.fory.ref_tracking

    for field_name in field_names:
        field_type_hint = type_hints.get(field_name, typing.Any)
        unwrapped_type, is_optional = unwrap_optional(field_type_hint, field_nullable=field_nullable)

        # Get field metadata if available
        fory_meta = field_metas.get(field_name)
        if fory_meta is not None and fory_meta.ignore:
            # Skip ignored fields
            continue

        # Determine nullable: use explicit metadata or fallback to type inference
        if fory_meta is not None:
            is_nullable = fory_meta.nullable
        else:
            # For xlang mode: only Optional[T] types are nullable by default
            # For native mode: all reference types are nullable by default
            if type_resolver.fory.is_py:
                is_nullable = is_optional or not is_primitive_type(unwrapped_type)
            else:
                # For xlang: only Optional[T] types are nullable
                is_nullable = is_optional

        # Determine ref tracking: field.ref AND global ref_tracking
        # For xlang mode: ref tracking defaults to false unless explicitly annotated
        # This matches Java's behavior in TypeResolver.getFieldDescriptors()
        if fory_meta is not None:
            is_tracking_ref = fory_meta.ref and global_ref_tracking
        else:
            # In xlang mode, default to false (matches Java's xlang behavior)
            # In native mode, use global ref_tracking setting
            is_tracking_ref = global_ref_tracking if type_resolver.fory.is_py else False

        # Get tag_id from metadata (-1 if not specified)
        tag_id = fory_meta.id if fory_meta is not None else -1

        nullable_map[field_name] = is_nullable
        field_type = build_field_type_with_ref(type_resolver, field_name, unwrapped_type, visitor, is_nullable, is_tracking_ref)
        field_info = FieldInfo(field_name, field_type, cls.__name__, tag_id)
        field_infos.append(field_info)

    field_types = infer_field_types(cls)
    serializers = [field_info.field_type.create_serializer(type_resolver, field_types.get(field_info.name, None)) for field_info in field_infos]

    # Get just the field names for sorting
    current_field_names = [fi.name for fi in field_infos]
    sorted_field_names, serializers = _sort_fields(type_resolver, current_field_names, serializers, nullable_map)
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
        return build_field_type_from_type_ids_with_ref(type_resolver, field_name, type_ids, visitor, is_nullable, is_tracking_ref)
    except Exception as e:
        raise TypeError(f"Error building field type for field: {field_name} with type hint: {type_hint} in class: {visitor.cls}") from e


def build_field_type_from_type_ids_with_ref(type_resolver, field_name: str, type_ids, visitor, is_nullable=False, is_tracking_ref=True):
    """Build field type from type IDs with explicit ref tracking control."""
    type_id = type_ids[0]
    if type_id is None:
        type_id = TypeId.UNKNOWN
    assert type_id >= 0, f"Unknown type: {type_id} for field: {field_name}"
    type_id = type_id & 0xFF
    morphic = not is_polymorphic_type(type_id)
    if type_id in [TypeId.SET, TypeId.LIST]:
        elem_type = build_field_type_from_type_ids_with_ref(
            type_resolver, field_name, type_ids[1], visitor, is_nullable=False, is_tracking_ref=is_tracking_ref
        )
        return CollectionFieldType(type_id, morphic, is_nullable, is_tracking_ref, elem_type)
    elif type_id == TypeId.MAP:
        key_type = build_field_type_from_type_ids_with_ref(
            type_resolver, field_name, type_ids[1], visitor, is_nullable=False, is_tracking_ref=is_tracking_ref
        )
        value_type = build_field_type_from_type_ids_with_ref(
            type_resolver, field_name, type_ids[2], visitor, is_nullable=False, is_tracking_ref=is_tracking_ref
        )
        return MapFieldType(type_id, morphic, is_nullable, is_tracking_ref, key_type, value_type)
    elif type_id in [TypeId.UNKNOWN, TypeId.EXT, TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT]:
        return DynamicFieldType(type_id, False, is_nullable, is_tracking_ref)
    else:
        if type_id <= 0 or type_id >= TypeId.BOUND:
            raise TypeError(f"Unknown type: {type_id} for field: {field_name}")
        return FieldType(type_id, morphic, is_nullable, is_tracking_ref)


def build_field_type(type_resolver, field_name: str, type_hint, visitor, is_nullable=False):
    """Build field type from type hint."""
    type_ids = infer_field(field_name, type_hint, visitor)
    try:
        return build_field_type_from_type_ids(type_resolver, field_name, type_ids, visitor, is_nullable)
    except Exception as e:
        raise TypeError(f"Error building field type for field: {field_name} with type hint: {type_hint} in class: {visitor.cls}") from e


def build_field_type_from_type_ids(type_resolver, field_name: str, type_ids, visitor, is_nullable=False):
    tracking_ref = type_resolver.fory.ref_tracking
    type_id = type_ids[0]
    if type_id is None:
        type_id = TypeId.UNKNOWN
    assert type_id >= 0, f"Unknown type: {type_id} for field: {field_name}"
    type_id = type_id & 0xFF
    morphic = not is_polymorphic_type(type_id)
    if type_id in [TypeId.SET, TypeId.LIST]:
        elem_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[1], visitor, is_nullable=False)
        return CollectionFieldType(type_id, morphic, is_nullable, tracking_ref, elem_type)
    elif type_id == TypeId.MAP:
        key_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[1], visitor, is_nullable=False)
        value_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[2], visitor, is_nullable=False)
        return MapFieldType(type_id, morphic, is_nullable, tracking_ref, key_type, value_type)
    elif type_id in [TypeId.UNKNOWN, TypeId.EXT, TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT]:
        return DynamicFieldType(type_id, False, is_nullable, tracking_ref)
    else:
        if type_id <= 0 or type_id >= TypeId.BOUND:
            raise TypeError(f"Unknown type: {type_id} for field: {field_name}")
        return FieldType(type_id, morphic, is_nullable, tracking_ref)
