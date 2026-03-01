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
Tests for xlang TypeDef implementation.
"""

from dataclasses import dataclass
from typing import List, Dict
from pyfory.serialization import Buffer
from pyfory.meta.typedef import (
    TypeDef,
    FieldInfo,
    FieldType,
    CollectionFieldType,
    MapFieldType,
    DynamicFieldType,
)
from pyfory.meta.typedef_encoder import encode_typedef
from pyfory.meta.typedef_decoder import decode_typedef
from pyfory.types import TypeId
from pyfory import Fory


@dataclass
class TestTypeDef:
    """Test class for TypeDef functionality."""

    name: str
    age: int
    scores: List[float]
    metadata: Dict[str, str]


@dataclass
class SimpleTypeDef:
    """Simple test class."""

    value: int


def test_collection_field_type():
    """Test collection field type creation and serialization."""
    element_type = FieldType(TypeId.INT32, True, True, False)
    list_field = CollectionFieldType(TypeId.LIST, True, True, False, element_type)

    assert list_field.type_id == TypeId.LIST
    assert list_field.element_type == element_type
    assert list_field.is_nullable


def test_map_field_type():
    """Test map field type creation and serialization."""
    key_type = FieldType(TypeId.STRING, True, True, False)
    value_type = FieldType(TypeId.INT32, True, True, False)
    map_field = MapFieldType(TypeId.MAP, True, True, False, key_type, value_type)

    assert map_field.type_id == TypeId.MAP
    assert map_field.key_type == key_type
    assert map_field.value_type == value_type


def test_typedef_creation():
    """Test TypeDef creation."""
    fields = [
        FieldInfo("name", FieldType(TypeId.STRING, True, True, False), "TestTypeDef"),
        FieldInfo("age", FieldType(TypeId.INT32, True, True, False), "TestTypeDef"),
    ]

    typedef = TypeDef("", "TestTypeDef", None, TypeId.STRUCT, fields, b"encoded_data", False)

    assert typedef.namespace == ""
    assert typedef.typename == "TestTypeDef"
    assert typedef.type_id == TypeId.STRUCT
    assert len(typedef.fields) == 2
    assert typedef.encoded == b"encoded_data"
    assert typedef.is_compressed is False


def test_field_info_creation():
    """Test FieldInfo creation."""
    field_type = FieldType(TypeId.STRING, True, True, False)
    field_info = FieldInfo("test_field", field_type, "TestClass")

    assert field_info.name == "test_field"
    assert field_info.field_type == field_type
    assert field_info.defined_class == "TestClass"


def test_dynamic_field_type():
    """Test dynamic field type."""
    dynamic_field = DynamicFieldType(TypeId.EXT, False, True, False)

    assert dynamic_field.type_id == TypeId.EXT
    assert dynamic_field.is_monomorphic is False
    assert dynamic_field.is_nullable
    assert dynamic_field.is_tracking_ref is False


def test_encode_decode_typedef():
    """Test encoding and decoding a TypeDef."""
    fory = Fory(xlang=True)
    fory.register(SimpleTypeDef, namespace="example", typename="SimpleTypeDef")
    fory.register(TestTypeDef, namespace="example", typename="TestTypeDef")
    # Create a mock resolver
    resolver = fory.type_resolver

    types = [SimpleTypeDef, TestTypeDef]
    for type_ in types:
        # Encode a TypeDef
        typedef = encode_typedef(resolver, type_)
        print(f"typedef: {typedef}")

        # Create a buffer from the encoded data
        buffer = Buffer(typedef.encoded)

        # Decode the TypeDef
        decoded_typedef = decode_typedef(buffer, resolver)
        print(f"decoded_typedef: {decoded_typedef}")

        # Verify the decoded TypeDef has the expected properties
        assert decoded_typedef.type_id == typedef.type_id
        assert decoded_typedef.is_compressed == typedef.is_compressed
        assert len(decoded_typedef.fields) == len(typedef.fields)

        # Verify field names match
        for i, field in enumerate(decoded_typedef.fields):
            assert field.name == typedef.fields[i].name
            assert field.field_type.type_id == typedef.fields[i].field_type.type_id
            assert field.field_type.is_nullable == typedef.fields[i].field_type.is_nullable


if __name__ == "__main__":
    test_collection_field_type()
    test_map_field_type()
    test_typedef_creation()
    test_field_info_creation()
    test_dynamic_field_type()
    test_encode_decode_typedef()
    print("All basic tests passed!")
