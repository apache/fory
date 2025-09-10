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

from typing import List
import typing
from pyfory.type import TypeId
from pyfory._util import Buffer
from pyfory.type import infer_field, is_polymorphic_type
from pyfory.meta.metastring import Encoding


# Constants from the specification
SMALL_NUM_FIELDS_THRESHOLD = 0b11111
REGISTER_BY_NAME_FLAG = 0b100000
FIELD_NAME_SIZE_THRESHOLD = 0b1111
COMPRESS_META_FLAG = 0b1 << 13
HAS_FIELDS_META_FLAG = 0b1 << 12
META_SIZE_MASKS = 0xFFF
NUM_HASH_BITS = 50

NAMESPACE_ENCODINGS = [Encoding.UTF_8, Encoding.ALL_TO_LOWER_SPECIAL, Encoding.LOWER_UPPER_DIGIT_SPECIAL]
TYPE_NAME_ENCODINGS = [Encoding.UTF_8, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.FIRST_TO_LOWER_SPECIAL, Encoding.ALL_TO_LOWER_SPECIAL]

# Field name encoding constants
FIELD_NAME_ENCODING_UTF8 = 0b00
FIELD_NAME_ENCODING_ALL_TO_LOWER_SPECIAL = 0b01
FIELD_NAME_ENCODING_LOWER_UPPER_DIGIT_SPECIAL = 0b10
FIELD_NAME_ENCODING_TAG_ID = 0b11
FIELD_NAME_ENCODINGS = [Encoding.UTF_8, Encoding.LOWER_UPPER_DIGIT_SPECIAL, Encoding.ALL_TO_LOWER_SPECIAL]


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

    def create_fields_serializer(self, resolver):
        serializers = [field_info.field_type.create_serializer(resolver) for field_info in self.fields]
        return serializers

    def get_field_names(self):
        return [field_info.name for field_info in self.fields]

    def create_serializer(self, resolver):
        from pyfory.serializer import DataClassSerializer

        fory = resolver.fory
        return DataClassSerializer(
            fory, self.cls, xlang=not fory.is_py, field_names=self.get_field_names(), serializers=self.create_fields_serializer(resolver)
        )

    def __repr__(self):
        return f"TypeDef(namespace={self.namespace}, typename={self.typename}, cls={self.cls}, type_id={self.type_id}, fields={self.fields}, is_compressed={self.is_compressed})"


class FieldInfo:
    def __init__(self, name: str, field_type: "FieldType", defined_class: str):
        self.name = name
        self.field_type = field_type
        self.defined_class = defined_class

    def xwrite(self, buffer: Buffer):
        self.field_type.xwrite(buffer, True)

    @classmethod
    def xread(cls, buffer: Buffer, resolver):
        field_type = FieldType.xread(buffer, resolver)
        # Note: name and defined_class would need to be read from the buffer
        # This is a simplified version
        return cls("", field_type, "")

    def __repr__(self):
        return f"FieldInfo(name={self.name}, field_type={self.field_type}, defined_class={self.defined_class})"


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
            from pyfory.type import is_polymorphic_type

            is_monomorphic = not is_polymorphic_type(xtype_id)
            return FieldType(xtype_id, is_monomorphic, is_nullable, is_tracking_ref)

    def create_serializer(self, resolver):
        if self.type_id in [TypeId.EXT, TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT, TypeId.UNKNOWN]:
            return None
        return resolver.get_typeinfo_by_id(self.type_id).serializer

    def __repr__(self):
        return f"FieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, is_tracking_ref={self.is_tracking_ref})"


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

    def create_serializer(self, resolver):
        from pyfory.serializer import ListSerializer, SetSerializer

        if self.type_id == TypeId.LIST:
            return ListSerializer(resolver.fory, list, self.element_type.create_serializer(resolver))
        elif self.type_id == TypeId.SET:
            return SetSerializer(resolver.fory, set, self.element_type.create_serializer(resolver))
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

    def create_serializer(self, resolver):
        key_serializer = self.key_type.create_serializer(resolver)
        value_serializer = self.value_type.create_serializer(resolver)
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

    def create_serializer(self, resolver):
        return None

    def __repr__(self):
        return f"DynamicFieldType(type_id={self.type_id}, is_monomorphic={self.is_monomorphic}, is_nullable={self.is_nullable}, is_tracking_ref={self.is_tracking_ref})"


def build_field_infos(type_resolver, cls):
    """Build field information for the class."""
    from pyfory._struct import _sort_fields, StructTypeIdVisitor, get_field_names

    field_names = get_field_names(cls)
    type_hints = typing.get_type_hints(cls)

    field_infos = []
    visitor = StructTypeIdVisitor(type_resolver.fory, cls)

    for field_name in field_names:
        field_type_hint = type_hints.get(field_name, typing.Any)
        field_type = build_field_type(type_resolver, field_name, field_type_hint, visitor)
        field_info = FieldInfo(field_name, field_type, cls.__name__)
        field_infos.append(field_info)

    serializers = [field_info.field_type.create_serializer(type_resolver) for field_info in field_infos]

    field_names, serializers = _sort_fields(type_resolver, field_names, serializers)
    field_infos_map = {field_info.name: field_info for field_info in field_infos}
    new_field_infos = []
    for field_name in field_names:
        field_info = field_infos_map[field_name]
        new_field_infos.append(field_info)
    return new_field_infos


def build_field_type(type_resolver, field_name: str, type_hint, visitor):
    """Build field type from type hint."""
    type_ids = infer_field(field_name, type_hint, visitor)
    try:
        return build_field_type_from_type_ids(type_resolver, field_name, type_ids, visitor)
    except Exception as e:
        raise TypeError(f"Error building field type for field: {field_name} with type hint: {type_hint} in class: {visitor.cls}") from e


def build_field_type_from_type_ids(type_resolver, field_name: str, type_ids, visitor):
    tracking_ref = type_resolver.fory.ref_tracking
    type_id = type_ids[0]
    if type_id is None:
        type_id = TypeId.UNKNOWN
    assert type_id >= 0, f"Unknown type: {type_id} for field: {field_name}"
    type_id = type_id & 0xFF
    morphic = not is_polymorphic_type(type_id)
    if type_id in [TypeId.SET, TypeId.LIST]:
        elem_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[1], visitor)
        return CollectionFieldType(type_id, morphic, True, tracking_ref, elem_type)
    elif type_id == TypeId.MAP:
        key_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[1], visitor)
        value_type = build_field_type_from_type_ids(type_resolver, field_name, type_ids[2], visitor)
        return MapFieldType(type_id, morphic, True, tracking_ref, key_type, value_type)
    elif type_id in [TypeId.UNKNOWN, TypeId.EXT, TypeId.STRUCT, TypeId.NAMED_STRUCT, TypeId.COMPATIBLE_STRUCT, TypeId.NAMED_COMPATIBLE_STRUCT]:
        return DynamicFieldType(type_id, False, True, tracking_ref)
    else:
        if type_id <= 0 or type_id >= TypeId.BOUND:
            raise TypeError(f"Unknown type: {type_id} for field: {field_name}")
        return FieldType(type_id, morphic, True, tracking_ref)
