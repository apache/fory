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
import enum
from dataclasses import dataclass, make_dataclass
from typing import List, Dict, Optional

import pytest

import pyfory
import pyfory.meta.typedef_decoder as typedef_decoder
from pyfory.serialization import Buffer
from pyfory.meta.typedef import (
    TypeDef,
    FieldInfo,
    FieldType,
    CollectionFieldType,
    MapFieldType,
    DynamicFieldType,
    FIELD_NAME_ENCODINGS,
    COMPRESS_META_FLAG,
    META_SIZE_MASKS,
    TYPEDEF_HASH_MASK,
    TYPEDEF_HASH_SHIFT,
    _INT64_MIN,
    _UINT64_MASK,
    plan_field_assignment,
)
from pyfory.meta.typedef_encoder import (
    FIELD_NAME_ENCODER,
    encode_typedef,
    prepend_header,
)
from pyfory.meta.typedef_decoder import decode_typedef
from pyfory.serializer import PyArraySerializer
from pyfory.types import TypeId
from pyfory.union import UnionSerializer
from pyfory import Fory
from pyfory.error import TypeNotCompatibleError
from pyfory.lib.mmh3 import hash_buffer

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
class LateTypeDefNested:
    value: int


@dataclass
class LateTypeDefHolder:
    value: LateTypeDefNested


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


@dataclass
class Int32ListPayload:
    payload: List[pyfory.FixedInt32]


class IdLimitEnum(enum.Enum):
    A = 1
    B = 2


@dataclass
class IdLimitExt:
    value: int = 0


class IdLimitExtSerializer(pyfory.serializer.Serializer):
    def write(self, write_context, value):
        write_context.write_varint32(value.value)

    def read(self, read_context):
        return IdLimitExt(read_context.read_varint32())


class IdLimitUnion:
    def __init__(self, case_id: int, value):
        self._case_id = case_id
        self._value = value

    def case_id(self):
        return self._case_id

    @staticmethod
    def _from_case_id(case_id: int, value):
        return IdLimitUnion(case_id, value)

    def __eq__(self, other):
        return isinstance(other, IdLimitUnion) and self._case_id == other._case_id and self._value == other._value


@dataclass
class Int32VarintListPayload:
    payload: List[pyfory.Int32]


@dataclass
class NullableInt32ListPayload:
    payload: List[Optional[pyfory.FixedInt32]]


@dataclass
class StringListPayload:
    payload: List[str]


@dataclass
class Int32ArrayPayload:
    payload: pyfory.Array[pyfory.Int32]


@dataclass
class Int32NDArrayPayload:
    payload: pyfory.NDArray[pyfory.Int32]


@dataclass
class Int32PyArrayPayload:
    payload: pyfory.PyArray[pyfory.Int32]


@dataclass
class NestedInt32ListPayload:
    payload: List[List[pyfory.FixedInt32]]


@dataclass
class NestedInt32ArrayPayload:
    payload: List[pyfory.Array[pyfory.Int32]]


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


def test_nested_user_type_shape_matching():
    remote = CollectionFieldType(TypeId.LIST, True, False, False, DynamicFieldType(TypeId.UNKNOWN, False, False, False))
    local = CollectionFieldType(TypeId.LIST, True, False, False, DynamicFieldType(TypeId.STRUCT, False, False, False))

    can_assign, validation = plan_field_assignment(remote, local)

    assert can_assign
    assert validation is None


def test_nested_unknown_does_not_match_scalar():
    remote = CollectionFieldType(TypeId.LIST, True, False, False, DynamicFieldType(TypeId.UNKNOWN, False, False, False))
    local = CollectionFieldType(TypeId.LIST, True, False, False, FieldType(TypeId.INT32, True, False, False))

    can_assign, validation = plan_field_assignment(remote, local)

    assert not can_assign
    assert validation is None


def test_encode_decode_typedef():
    """Test encoding and decoding a TypeDef."""
    fory = Fory(xlang=True, compatible=False)
    fory.register(SimpleTypeDef, name="example.SimpleTypeDef")
    fory.register(TestTypeDef, name="example.TestTypeDef")
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


def test_decode_typedef_rejects_parsed_body_with_mismatched_hash():
    fory = Fory(xlang=True, compatible=False)
    fory.register(SimpleTypeDef, name="example.SimpleTypeDef")
    typedef = encode_typedef(fory.type_resolver, SimpleTypeDef)
    malformed = _corrupt_encoded_field_name(typedef, "value")

    with pytest.raises(ValueError, match="Invalid TypeDef metadata hash"):
        decode_typedef(Buffer(malformed), fory.type_resolver)


def test_decode_typedef_rejects_body_only_header_hash():
    fory = Fory(xlang=True, compatible=False)
    fory.register(SimpleTypeDef, name="example.SimpleTypeDef")
    typedef = encode_typedef(fory.type_resolver, SimpleTypeDef)
    malformed = _rewrite_header_with_body_only_hash(typedef.encoded)

    with pytest.raises(ValueError, match="Invalid TypeDef metadata hash"):
        decode_typedef(Buffer(malformed), fory.type_resolver)


def test_decode_typedef_rejects_hash_consistent_malformed_body():
    fory = Fory(xlang=True, compatible=False)
    encoded = prepend_header(b"\x00", False)

    with pytest.raises(Exception):
        decode_typedef(Buffer(encoded), fory.type_resolver)


def test_decode_typedef_rejects_compressed_xlang_metadata():
    fory = Fory(xlang=True, compatible=False)
    fory.register(SimpleTypeDef, name="example.SimpleTypeDef")
    typedef = encode_typedef(fory.type_resolver, SimpleTypeDef)
    source = Buffer(typedef.encoded)
    header = source.read_int64()
    malformed = Buffer.allocate(len(typedef.encoded))
    malformed.write_int64(header | COMPRESS_META_FLAG)
    malformed.write_bytes(typedef.encoded[8:])

    with pytest.raises(ValueError, match="Compressed xlang TypeDef"):
        decode_typedef(Buffer(malformed.to_bytes()), fory.type_resolver)


def test_decode_typedef_checks_body_before_encoded_allocation(monkeypatch):
    class GuardedBuffer:
        @staticmethod
        def allocate(_size):
            raise AssertionError("encoded buffer allocated before readable-byte proof")

    fory = Fory(xlang=True, compatible=False, max_type_meta_bytes=1024)
    header = 16
    truncated = header.to_bytes(8, "little", signed=False)
    original_buffer = typedef_decoder.Buffer
    monkeypatch.setattr(typedef_decoder, "Buffer", GuardedBuffer)
    with pytest.raises(Exception) as exc:
        typedef_decoder.decode_typedef(Buffer(truncated), fory.type_resolver)
    monkeypatch.setattr(typedef_decoder, "Buffer", original_buffer)

    assert "encoded buffer allocated" not in str(exc.value)


def test_skip_typedef_does_not_materialize_body():
    class SkipBuffer:
        def __init__(self):
            self.skipped = None

        def skip(self, size):
            self.skipped = size

        def read_bytes(self, _size):
            raise AssertionError("skip_typedef must not read metadata body bytes")

    buffer = SkipBuffer()
    typedef_decoder.skip_typedef(buffer, 7)
    assert buffer.skipped == 7


def test_skip_typedef_rejects_oversized_extended_body():
    class SkipBuffer:
        def read_var_uint32(self):
            return 1 << 31

        def skip(self, _size):
            raise AssertionError("oversized TypeDef body must not reach skip")

    with pytest.raises(ValueError, match="Invalid TypeDef metadata size"):
        typedef_decoder.skip_typedef(SkipBuffer(), META_SIZE_MASKS)


def test_id_registered_typedef_extended_field_count_header():
    many_fields_type = make_dataclass("ManyTypeDefFields", [(f"field_{i}", int) for i in range(32)])
    fory = Fory(xlang=True, compatible=False)
    fory.register(many_fields_type, type_id=701)
    typedef = encode_typedef(fory.type_resolver, many_fields_type)
    body_offset = _typedef_body_offset(typedef.encoded)

    assert typedef.encoded[body_offset] & 0x1F == 0x1F
    assert typedef.encoded[body_offset] & 0x20 == 0
    decoded_typedef = decode_typedef(Buffer(typedef.encoded), fory.type_resolver)
    assert len(decoded_typedef.fields) == 32


@pytest.mark.parametrize("xlang", [False, True])
def test_type_meta_field_limit_rejects_large_struct(xlang):
    reader = Fory(xlang=xlang, strict=False, compatible=True, max_type_fields=1)
    remote = make_dataclass("RemoteTooManyFields", [("value", int), ("extra", int)])
    type_id, typedef = _remote_typedef(xlang, "example.TooManyFields", remote)

    with pytest.raises(ValueError, match="max_type_fields"):
        _read_remote_typedef(reader, type_id, typedef)


@pytest.mark.parametrize("xlang", [False, True])
def test_type_meta_body_limit_rejects_large_metadata(xlang):
    reader = Fory(xlang=xlang, strict=False, compatible=True, max_type_meta_bytes=1)
    remote = make_dataclass("RemoteLargeTypeMeta", [("value", int)])
    type_id, typedef = _remote_typedef(xlang, "example.LargeTypeMeta", remote)

    with pytest.raises(ValueError, match="max_type_meta_bytes"):
        _read_remote_typedef(reader, type_id, typedef)


@pytest.mark.parametrize("xlang", [False, True])
def test_remote_schema_limit_rejects_extra_versions(xlang):
    reader = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_schema_versions_per_type=1,
    )
    first = make_dataclass("RemoteLimitV1", [("value", int)])
    second = make_dataclass("RemoteLimitV2", [("value", int), ("extra", int)])
    first_type_id, first_typedef = _remote_typedef(xlang, "example.Unknown", first)
    second_type_id, second_typedef = _remote_typedef(xlang, "example.Unknown", second)

    _read_remote_typedef(reader, first_type_id, first_typedef)

    second_header = Buffer(second_typedef).read_int64()
    with pytest.raises(ValueError, match="max_schema_versions_per_type"):
        _read_remote_typedef(reader, second_type_id, second_typedef)
    assert second_header not in reader.type_resolver._meta_shared_type_info


@pytest.mark.parametrize("xlang", [False, True])
def test_remote_schema_limit_keeps_unknown_types_separate(xlang):
    reader = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_schema_versions_per_type=1,
    )
    first = make_dataclass("RemoteUnknownA", [("value", int)])
    second = make_dataclass("RemoteUnknownB", [("value", int)])
    first_type_id, first_typedef = _remote_typedef(xlang, "example.UnknownA", first)
    second_type_id, second_typedef = _remote_typedef(xlang, "example.UnknownB", second)

    _read_remote_typedef(reader, first_type_id, first_typedef)
    _read_remote_typedef(reader, second_type_id, second_typedef)


@pytest.mark.parametrize("xlang", [False, True])
def test_exact_local_struct_typedef_populates_cache(xlang):
    reader = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_schema_versions_per_type=1,
    )
    reader.register(SimpleTypeDef, name="example.SimpleTypeDef")
    type_id, _ = reader.type_resolver.get_registered_type_ids(SimpleTypeDef)
    encoded = encode_typedef(reader.type_resolver, SimpleTypeDef).encoded
    header = Buffer(encoded).read_int64()

    type_info = _read_remote_typedef(reader, type_id, encoded)
    assert type_info.cls is SimpleTypeDef
    assert reader.type_resolver._meta_shared_type_info[header].cls is SimpleTypeDef

    invalid_body = bytearray(encoded)
    invalid_body[-1] ^= 1
    type_info = _read_remote_typedef(reader, type_id, bytes(invalid_body))
    assert type_info.cls is SimpleTypeDef


@pytest.mark.parametrize("xlang", [False, True])
def test_exact_local_non_struct_typedef_bypasses_schema_limit(xlang):
    reader = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_schema_versions_per_type=1,
    )
    reader.register(IdLimitEnum, name="example.RemoteEnum")
    type_id, _ = reader.type_resolver.get_registered_type_ids(IdLimitEnum)
    encoded = encode_typedef(reader.type_resolver, IdLimitEnum).encoded

    type_info = _read_remote_typedef(reader, type_id, encoded)
    assert type_info.cls is IdLimitEnum

    if hasattr(reader.type_resolver, "_check_remote_type_def_limit"):
        second = TypeDef("example", "RemoteEnum", IdLimitEnum, TypeId.NAMED_EXT, [])
        reader.type_resolver._check_remote_type_def_limit(second)


@pytest.mark.parametrize("xlang", [False, True])
def test_id_enum_does_not_use_type_meta_limits(xlang):
    fory = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_type_meta_bytes=1,
        max_schema_versions_per_type=1,
    )
    fory.register_type(IdLimitEnum, type_id=310)

    assert fory.deserialize(fory.serialize(IdLimitEnum.B)) == IdLimitEnum.B


@pytest.mark.parametrize("xlang", [False, True])
def test_id_ext_does_not_use_type_meta_limits(xlang):
    fory = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_type_meta_bytes=1,
        max_schema_versions_per_type=1,
    )
    fory.register_type(
        IdLimitExt,
        type_id=311,
        serializer=IdLimitExtSerializer(fory.type_resolver, IdLimitExt),
    )

    assert fory.deserialize(fory.serialize(IdLimitExt(42))) == IdLimitExt(42)


@pytest.mark.parametrize("xlang", [False, True])
def test_id_union_does_not_use_type_meta_limits(xlang):
    fory = Fory(
        xlang=xlang,
        strict=False,
        compatible=True,
        max_type_meta_bytes=1,
        max_schema_versions_per_type=1,
    )
    fory.register_union(
        IdLimitUnion,
        type_id=312,
        serializer=UnionSerializer(fory.type_resolver, IdLimitUnion, {0: str}),
    )

    assert fory.deserialize(fory.serialize(IdLimitUnion(0, "hello"))) == IdLimitUnion(0, "hello")


def test_non_struct_typedef_uses_schema_limit():
    from pyfory.registry import SharedRegistry, TypeResolver

    fory = Fory(
        xlang=True,
        strict=False,
        compatible=True,
        max_schema_versions_per_type=1,
    )
    resolver = TypeResolver(fory.config, shared_registry=SharedRegistry())
    first = TypeDef("example", "RemoteEnum", IdLimitEnum, TypeId.NAMED_ENUM, [])
    second = TypeDef("example", "RemoteEnum", IdLimitEnum, TypeId.NAMED_EXT, [])

    type_key = resolver._check_remote_type_def_limit(first)
    resolver._record_remote_type_def(type_key)

    with pytest.raises(ValueError, match="max_schema_versions_per_type"):
        resolver._check_remote_type_def_limit(second)


def _remote_typedef(xlang, remote_name, cls):
    writer = Fory(xlang=xlang, strict=False, compatible=True)
    writer.register(cls, name=remote_name)
    type_id, _ = writer.type_resolver.get_registered_type_ids(cls)
    return type_id, encode_typedef(writer.type_resolver, cls).encoded


def _read_remote_typedef(fory, type_id, encoded):
    buffer = Buffer.allocate(len(encoded) + 8)
    buffer.write_uint8(type_id)
    buffer.write_var_uint32(0)
    buffer.write_bytes(encoded)
    fory.read_context.reset()
    fory.read_context.prepare(Buffer(buffer.to_bytes()))
    return fory.type_resolver.read_type_info(fory.read_context)


def _corrupt_encoded_field_name(typedef, field_name):
    malformed = bytearray(typedef.encoded)
    needle = FIELD_NAME_ENCODER.encode(field_name, FIELD_NAME_ENCODINGS).encoded_data
    index = bytes(malformed).find(needle, 8)
    assert index >= 8
    malformed[index + len(needle) - 1] ^= 1
    return bytes(malformed)


def _typedef_body_offset(encoded):
    buffer = Buffer(encoded)
    header = buffer.read_int64()
    if header & META_SIZE_MASKS == META_SIZE_MASKS:
        buffer.read_var_uint32()
    return buffer.get_reader_index()


def _rewrite_header_with_body_only_hash(encoded):
    malformed = bytearray(encoded)
    buffer = Buffer(encoded)
    header = buffer.read_int64() & _UINT64_MASK
    body_offset = _typedef_body_offset(encoded)
    body_only_hash = _body_only_typedef_hash_bits(encoded[body_offset:])
    assert header & TYPEDEF_HASH_MASK != body_only_hash
    rewritten_header = body_only_hash | (header & ~TYPEDEF_HASH_MASK)
    malformed[:8] = rewritten_header.to_bytes(8, "little", signed=False)
    return bytes(malformed)


def _body_only_typedef_hash_bits(encoded_body):
    hash_value = hash_buffer(encoded_body, 47)[0]
    shifted = (hash_value << TYPEDEF_HASH_SHIFT) & _UINT64_MASK
    if shifted >= (1 << 63):
        shifted -= 1 << 64
    if shifted != _INT64_MIN and shifted < 0:
        shifted = -shifted
    return (shifted & _UINT64_MASK) & TYPEDEF_HASH_MASK


def test_nested_container_typedef_preserves_declared_encoding():
    fory = Fory(xlang=True, compatible=False)
    fory.register(NestedEncodingTypeDef, name="example.NestedEncodingTypeDef")

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


def test_typedef_uses_late_registered_field_type():
    fory = Fory(xlang=True, compatible=True)
    fory.register(LateTypeDefHolder, name="example.LateTypeDefHolder")
    fory.register(LateTypeDefNested, name="example.LateTypeDefNested")

    typedef = encode_typedef(fory.type_resolver, LateTypeDefHolder)
    field = next(field for field in typedef.fields if field.name == "value")
    assert field.field_type.type_id == TypeId.NAMED_COMPATIBLE_STRUCT


def test_python_array_typehint_lowering_keeps_list_schema_distinct():
    fory = Fory(xlang=True, compatible=False)
    fory.register(PythonArrayTypeHints, name="example.PythonArrayTypeHints")

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
    fory = Fory(xlang=True, compatible=False)
    fory.register(
        InvalidArrayModifierTypeDef,
        name="example.InvalidArrayModifierTypeDef",
    )
    with pytest.raises(TypeError, match="array<T> does not allow scalar encoding modifier"):
        encode_typedef(fory.type_resolver, InvalidArrayModifierTypeDef)


def _register_byte_sequence(fory, cls):
    fory.register(cls, name="example.ByteSequence")


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


def _register_int32_payload(fory, cls):
    fory.register(cls, name="example.Int32Sequence")


def _pyarray_int32_value(values):
    for typecode, (_itemsize, _ftype, type_id) in PyArraySerializer.typecode_dict.items():
        if type_id == TypeId.INT32_ARRAY:
            return array.array(typecode, values)
    raise AssertionError("No array.array typecode maps to INT32_ARRAY")


def test_compatible_int32_list_assigns_to_array():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ListPayload)
    _register_int32_payload(reader, Int32ArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32ListPayload(payload=[1, 2, 3])))

    assert isinstance(decoded, Int32ArrayPayload)
    assert isinstance(decoded.payload, pyfory.Int32Array)
    assert list(decoded.payload) == [1, 2, 3]


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_compatible_int32_list_assigns_to_ndarray():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ListPayload)
    _register_int32_payload(reader, Int32NDArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32ListPayload(payload=[1, 2, 3])))

    assert isinstance(decoded, Int32NDArrayPayload)
    assert isinstance(decoded.payload, np.ndarray)
    assert decoded.payload.dtype == np.dtype(np.int32)
    np.testing.assert_array_equal(decoded.payload, np.array([1, 2, 3], dtype=np.int32))


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_compatible_empty_int32_list_assigns_to_ndarray():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ListPayload)
    _register_int32_payload(reader, Int32NDArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32ListPayload(payload=[])))

    assert isinstance(decoded, Int32NDArrayPayload)
    assert isinstance(decoded.payload, np.ndarray)
    assert decoded.payload.dtype == np.dtype(np.int32)
    assert decoded.payload.size == 0


def test_compatible_int32_list_assigns_to_pyarray():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ListPayload)
    _register_int32_payload(reader, Int32PyArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32ListPayload(payload=[1, 2, 3])))

    assert isinstance(decoded, Int32PyArrayPayload)
    assert isinstance(decoded.payload, array.array)
    assert PyArraySerializer.typecode_dict[decoded.payload.typecode][2] == TypeId.INT32_ARRAY
    assert decoded.payload.tolist() == [1, 2, 3]


def test_compatible_empty_int32_list_assigns_to_pyarray():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ListPayload)
    _register_int32_payload(reader, Int32PyArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32ListPayload(payload=[])))

    assert isinstance(decoded, Int32PyArrayPayload)
    assert isinstance(decoded.payload, array.array)
    assert PyArraySerializer.typecode_dict[decoded.payload.typecode][2] == TypeId.INT32_ARRAY
    assert decoded.payload.tolist() == []


def test_compatible_varint_int32_list_assigns_to_array():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32VarintListPayload)
    _register_int32_payload(reader, Int32ArrayPayload)

    decoded = reader.deserialize(writer.serialize(Int32VarintListPayload(payload=[-1, 2, 3])))

    assert isinstance(decoded, Int32ArrayPayload)
    assert isinstance(decoded.payload, pyfory.Int32Array)
    assert list(decoded.payload) == [-1, 2, 3]


def test_compatible_int32_array_assigns_to_list():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32ArrayPayload)
    _register_int32_payload(reader, Int32ListPayload)

    decoded = reader.deserialize(writer.serialize(Int32ArrayPayload(payload=pyfory.Int32Array([1, 2, 3]))))

    assert isinstance(decoded, Int32ListPayload)
    assert decoded.payload == [1, 2, 3]


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_compatible_int32_ndarray_assigns_to_list():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32NDArrayPayload)
    _register_int32_payload(reader, Int32ListPayload)

    decoded = reader.deserialize(writer.serialize(Int32NDArrayPayload(payload=np.array([1, 2, 3], dtype=np.int32))))

    assert isinstance(decoded, Int32ListPayload)
    assert decoded.payload == [1, 2, 3]


def test_compatible_int32_pyarray_assigns_to_list():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, Int32PyArrayPayload)
    _register_int32_payload(reader, Int32ListPayload)

    decoded = reader.deserialize(writer.serialize(Int32PyArrayPayload(payload=_pyarray_int32_value([1, 2, 3]))))

    assert isinstance(decoded, Int32ListPayload)
    assert decoded.payload == [1, 2, 3]


def test_compatible_nullable_int32_list_schema_assigns_to_array():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, NullableInt32ListPayload)
    _register_int32_payload(reader, Int32ArrayPayload)

    decoded = reader.deserialize(writer.serialize(NullableInt32ListPayload(payload=[1, 2, 3])))
    assert isinstance(decoded, Int32ArrayPayload)
    assert isinstance(decoded.payload, pyfory.Int32Array)
    assert list(decoded.payload) == [1, 2, 3]

    with pytest.raises(TypeNotCompatibleError):
        reader.deserialize(writer.serialize(NullableInt32ListPayload(payload=[1, None, 3])))


def test_compatible_incompatible_list_array_elements_reject():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, StringListPayload)
    _register_int32_payload(reader, Int32ArrayPayload)

    with pytest.raises(TypeNotCompatibleError):
        reader.deserialize(writer.serialize(StringListPayload(payload=["1", "2"])))


def test_nested_list_array_mismatch_rejects():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    _register_int32_payload(writer, NestedInt32ListPayload)
    _register_int32_payload(reader, NestedInt32ArrayPayload)

    with pytest.raises(TypeNotCompatibleError):
        reader.deserialize(writer.serialize(NestedInt32ListPayload(payload=[[1, 2], [3]])))


if __name__ == "__main__":
    test_collection_field_type()
    test_map_field_type()
    test_typedef_creation()
    test_field_info_creation()
    test_dynamic_field_type()
    test_encode_decode_typedef()
    print("All basic tests passed!")
