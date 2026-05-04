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

import array
from dataclasses import dataclass
from typing import List, Dict

import pytest

import pyfory
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

try:
    import numpy as np
except ImportError:
    np = None


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


@dataclass
class NestedEncodingTypeDef:
    """TypeDef with nested primitive encoding overrides."""

    values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]]


@dataclass
class PythonArrayTypeHints:
    """TypeDef with list and explicit array schema markers."""

    values: List[pyfory.Int32]
    dense_values: pyfory.Array[pyfory.Int32]
    numpy_values: pyfory.NDArray[pyfory.UInt8]
    py_values: pyfory.PyArray[pyfory.Float64]
    payload: bytes


@dataclass
class InvalidArrayModifierTypeDef:
    values: pyfory.Array[pyfory.FixedInt32]


@dataclass
class BytesPayload:
    payload: bytes


@dataclass
class UInt8ArrayPayload:
    payload: pyfory.Array[pyfory.UInt8]


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
        assert typedef.is_compressed is False

        # Create a buffer from the encoded data
        buffer = Buffer(typedef.encoded)

        # Decode the TypeDef
        decoded_typedef = decode_typedef(buffer, resolver)
        print(f"decoded_typedef: {decoded_typedef}")

        # Verify the decoded TypeDef has the expected properties
        assert decoded_typedef.type_id == typedef.type_id
        assert decoded_typedef.is_compressed is False
        assert decoded_typedef.is_compressed == typedef.is_compressed
        assert len(decoded_typedef.fields) == len(typedef.fields)

        # Verify field names match
        for i, field in enumerate(decoded_typedef.fields):
            assert field.name == typedef.fields[i].name
            assert field.field_type.type_id == typedef.fields[i].field_type.type_id
            assert field.field_type.is_nullable == typedef.fields[i].field_type.is_nullable


def test_nested_container_typedef_preserves_declared_encoding():
    fory = Fory(xlang=True)
    fory.register(NestedEncodingTypeDef, namespace="example", typename="NestedEncodingTypeDef")

    typedef = encode_typedef(fory.type_resolver, NestedEncodingTypeDef)
    values_field = next(field for field in typedef.fields if field.name == "values")
    assert values_field.field_type.type_id == TypeId.MAP
    assert values_field.field_type.key_type.type_id == TypeId.INT32
    assert values_field.field_type.value_type.type_id == TypeId.LIST
    assert values_field.field_type.value_type.element_type.type_id == TypeId.TAGGED_INT64

    decoded_typedef = decode_typedef(Buffer(typedef.encoded), fory.type_resolver)
    decoded_values_field = next(field for field in decoded_typedef.fields if field.name == "values")
    assert decoded_values_field.field_type.type_id == TypeId.MAP
    assert decoded_values_field.field_type.key_type.type_id == TypeId.INT32
    assert decoded_values_field.field_type.value_type.type_id == TypeId.LIST
    assert decoded_values_field.field_type.value_type.element_type.type_id == TypeId.TAGGED_INT64


def test_python_array_typehint_lowering_keeps_list_schema_distinct():
    fory = Fory(xlang=True)
    fory.register(PythonArrayTypeHints, namespace="example", typename="PythonArrayTypeHints")

    typedef = encode_typedef(fory.type_resolver, PythonArrayTypeHints)
    fields = {field.name: field.field_type for field in typedef.fields}

    assert fields["values"].type_id == TypeId.LIST
    assert fields["values"].element_type.type_id == TypeId.VARINT32
    assert fields["dense_values"].type_id == TypeId.INT32_ARRAY
    assert fields["numpy_values"].type_id == TypeId.UINT8_ARRAY
    assert fields["py_values"].type_id == TypeId.FLOAT64_ARRAY
    assert fields["payload"].type_id == TypeId.BINARY

    decoded_typedef = decode_typedef(Buffer(typedef.encoded), fory.type_resolver)
    decoded_fields = {field.name: field.field_type for field in decoded_typedef.fields}
    assert decoded_fields["values"].type_id == TypeId.LIST
    assert decoded_fields["values"].element_type.type_id == TypeId.VARINT32
    assert decoded_fields["dense_values"].type_id == TypeId.INT32_ARRAY


def test_python_array_typehint_rejects_scalar_encoding_modifier():
    fory = Fory(xlang=True)
    fory.register(InvalidArrayModifierTypeDef, namespace="example", typename="InvalidArrayModifierTypeDef")
    with pytest.raises(TypeError, match="array<T> does not allow scalar encoding modifier"):
        encode_typedef(fory.type_resolver, InvalidArrayModifierTypeDef)


def _register_byte_sequence(fory, cls):
    fory.register(cls, namespace="example", typename="ByteSequence")


def _uint8_array_value(values):
    if np is not None:
        return np.array(values, dtype=np.uint8)
    return array.array("B", values)


def _assert_uint8_array_value(value, expected):
    assert isinstance(value, pyfory.UInt8Array)
    assert list(value) == expected


def test_compatible_bytes_assigns_to_uint8_array():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_byte_sequence(writer, BytesPayload)
    _register_byte_sequence(reader, UInt8ArrayPayload)

    decoded = reader.deserialize(writer.serialize(BytesPayload(payload=b"\x01\x02\xff")))

    assert isinstance(decoded, UInt8ArrayPayload)
    _assert_uint8_array_value(decoded.payload, [1, 2, 255])


def test_compatible_uint8_array_assigns_to_bytes():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_byte_sequence(writer, UInt8ArrayPayload)
    _register_byte_sequence(reader, BytesPayload)

    decoded = reader.deserialize(writer.serialize(UInt8ArrayPayload(payload=_uint8_array_value([1, 2, 255]))))

    assert isinstance(decoded, BytesPayload)
    assert decoded.payload == b"\x01\x02\xff"


if __name__ == "__main__":
    test_collection_field_type()
    test_map_field_type()
    test_typedef_creation()
    test_field_info_creation()
    test_dynamic_field_type()
    test_encode_decode_typedef()
    print("All basic tests passed!")
