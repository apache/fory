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

from dataclasses import dataclass
from types import SimpleNamespace

import pytest

from pyfory import Buffer, Fory
from pyfory.context import (
    EncodedMetaString,
    MetaStringReader,
    MetaStringWriter,
    hash_meta_string_data,
)
from pyfory.error import TypeUnregisteredError
from pyfory.meta.metastring import Encoding, MetaStringDecoder, MetaStringEncoder
from pyfory.policy import DeserializationPolicy
from pyfory.registry import (
    MAX_CACHED_ENCODED_META_STRINGS,
    MAX_CACHED_ENCODED_META_STRING_LENGTH,
    SharedRegistry,
    TypeResolver,
)
from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
from pyfory.types import TypeId

try:
    from pyfory.serialization import MetaStringReader as CythonMetaStringReader
except ImportError:
    CythonMetaStringReader = None


@dataclass
class StrictWireNameType:
    value: int


@dataclass
class SmallHashNamedType:
    value: int


@dataclass
class NamespaceAliasType:
    value: int


_SMALL_HASH_NAME = "Taaaaaaaaaaaaaaaaaa1"
_SMALL_HASH_COLLISION_DATA = bytes.fromhex("a79d13e75281ae4a0000000000000000")


def _small_hash_collision(shared_registry):
    encoder = MetaStringEncoder("$", "_")
    decoder = MetaStringDecoder("$", "_")
    canonical = shared_registry.get_encoded_meta_string(encoder.encode(_SMALL_HASH_NAME))
    collision_hash = hash_meta_string_data(
        _SMALL_HASH_COLLISION_DATA,
        canonical.encoding,
    )
    assert canonical.length == len(_SMALL_HASH_COLLISION_DATA) == 16
    assert collision_hash == canonical.hashcode
    collision = EncodedMetaString(_SMALL_HASH_COLLISION_DATA, collision_hash)
    assert collision.decode(decoder) != _SMALL_HASH_NAME
    return canonical, collision


def _write_meta_string(buffer, encoded_meta_string):
    buffer.write_var_uint32(encoded_meta_string.length << 1)
    buffer.write_int8(encoded_meta_string.encoding)
    buffer.write_bytes(encoded_meta_string.data)


def _roundtrip_meta_string(encoded_meta_string):
    writer = MetaStringWriter()
    reader = MetaStringReader(SharedRegistry())
    buffer = Buffer.allocate(64)
    writer.write_encoded_meta_string(buffer, encoded_meta_string)
    writer.write_encoded_meta_string(buffer, encoded_meta_string)
    buffer.set_reader_index(0)
    assert reader.read_encoded_meta_string(buffer) == encoded_meta_string
    assert reader.read_encoded_meta_string(buffer) == encoded_meta_string


def test_meta_string_writer_reader():
    shared_registry = SharedRegistry()
    encoder = MetaStringEncoder("$", "_")

    _roundtrip_meta_string(shared_registry.get_encoded_meta_string(encoder.encode("hello, world")))
    _roundtrip_meta_string(
        EncodedMetaString(
            data=b"\xbf\x05\xa4q\xa9\x92S\x96\xa6IOr\x9ch)\x80",
            hashcode=-2270219110992250879,
        )
    )
    _roundtrip_meta_string(shared_registry.get_encoded_meta_string(encoder.encode("")))
    _roundtrip_meta_string(shared_registry.get_encoded_meta_string(encoder.encode("你好，世界")))
    _roundtrip_meta_string(shared_registry.get_encoded_meta_string(encoder.encode("こんにちは世界")))
    _roundtrip_meta_string(shared_registry.get_encoded_meta_string(encoder.encode("hello, world" * 10)))


def test_read_big_metastring_rejects_noncanonical_hash():
    shared_registry = SharedRegistry()
    encoder = MetaStringEncoder("$", "_")
    encoded_meta_string = shared_registry.get_encoded_meta_string(encoder.encode("hello, world" * 10))
    reader = MetaStringReader(shared_registry)
    buffer = Buffer.allocate(128)

    buffer.write_var_uint32(encoded_meta_string.length << 1)
    buffer.write_int64(encoded_meta_string.hashcode + 0x100)
    buffer.write_bytes(encoded_meta_string.data)
    buffer.set_reader_index(0)

    with pytest.raises(ValueError, match="Malformed metastring hash"):
        reader.read_encoded_meta_string(buffer)


def test_cached_big_metastring_validates_bytes_before_reuse():
    shared_registry = SharedRegistry()
    encoder = MetaStringEncoder("$", "_")
    encoded_meta_string = shared_registry.get_encoded_meta_string(encoder.encode("hello, world" * 10))
    reader = MetaStringReader(shared_registry)
    buffer = Buffer.allocate(128)

    buffer.write_var_uint32(encoded_meta_string.length << 1)
    buffer.write_int64(encoded_meta_string.hashcode)
    buffer.write_bytes(encoded_meta_string.data)
    buffer.set_reader_index(0)
    assert reader.read_encoded_meta_string(buffer) is encoded_meta_string

    forged_data = bytes([encoded_meta_string.data[0] ^ 1]) + encoded_meta_string.data[1:]
    buffer.set_writer_index(0)
    buffer.set_reader_index(0)
    buffer.write_var_uint32(len(forged_data) << 1)
    buffer.write_int64(encoded_meta_string.hashcode)
    buffer.write_bytes(forged_data)
    buffer.set_reader_index(0)

    with pytest.raises(ValueError, match="Malformed metastring hash"):
        reader.read_encoded_meta_string(buffer)


@pytest.mark.skipif(CythonMetaStringReader is None, reason="Cython serialization extension is unavailable")
def test_cython_cached_big_metastring_validates_bytes_before_reuse():
    shared_registry = SharedRegistry()
    encoder = MetaStringEncoder("$", "_")
    encoded_meta_string = shared_registry.get_encoded_meta_string(encoder.encode("hello, world" * 10))
    reader = CythonMetaStringReader(shared_registry)
    buffer = Buffer.allocate(128)

    buffer.write_var_uint32(encoded_meta_string.length << 1)
    buffer.write_int64(encoded_meta_string.hashcode)
    buffer.write_bytes(encoded_meta_string.data)
    buffer.set_reader_index(0)
    assert reader.read_encoded_meta_string(buffer) is encoded_meta_string

    forged_data = bytes([encoded_meta_string.data[0] ^ 1]) + encoded_meta_string.data[1:]
    buffer.set_writer_index(0)
    buffer.set_reader_index(0)
    buffer.write_var_uint32(len(forged_data) << 1)
    buffer.write_int64(encoded_meta_string.hashcode)
    buffer.write_bytes(forged_data)
    buffer.set_reader_index(0)

    with pytest.raises(ValueError, match="Malformed metastring hash"):
        reader.read_encoded_meta_string(buffer)


@pytest.mark.skipif(CythonMetaStringReader is None, reason="Cython serialization extension is unavailable")
def test_cython_small_metastring_collision():
    shared_registry = SharedRegistry()
    canonical, collision = _small_hash_collision(shared_registry)
    reader = CythonMetaStringReader(shared_registry)
    buffer = Buffer.allocate(64)

    _write_meta_string(buffer, canonical)
    buffer.set_reader_index(0)
    assert reader.read_encoded_meta_string(buffer) is canonical

    reader.reset()
    buffer.set_writer_index(0)
    buffer.set_reader_index(0)
    _write_meta_string(buffer, collision)
    buffer.set_reader_index(0)

    assert reader.read_encoded_meta_string(buffer).data == collision.data


@pytest.mark.skipif(
    not ENABLE_FORY_CYTHON_SERIALIZATION,
    reason="Cython serialization extension is unavailable",
)
def test_cython_type_cache_collision():
    fory = Fory(xlang=True, compatible=False, strict=True)
    typeinfo = fory.register_type(
        SmallHashNamedType,
        name=f"security.{_SMALL_HASH_NAME}",
    )
    _, collision = _small_hash_collision(fory.type_resolver.shared_registry)
    buffer = Buffer.allocate(128)
    writer = MetaStringWriter()
    buffer.write_uint8(typeinfo.type_id)
    writer.write_encoded_meta_string(buffer, typeinfo.namespace_bytes)
    writer.write_encoded_meta_string(buffer, collision)
    buffer.set_reader_index(0)
    fory.read_context.reset()
    fory.read_context.prepare(buffer)

    with pytest.raises(TypeUnregisteredError):
        fory.type_resolver.read_type_info(fory.read_context)


def test_strict_wire_name_no_import():
    class NoImportPolicy(DeserializationPolicy):
        def __init__(self):
            self.validate_module_calls = 0

        def validate_module(self, module_name, *, is_local, **kwargs):
            self.validate_module_calls += 1
            raise AssertionError("strict wire-name misses must not import")

    writer = Fory(xlang=False, compatible=False, strict=True)
    policy = NoImportPolicy()
    reader = Fory(
        xlang=False,
        compatible=False,
        strict=True,
        policy=policy,
    )
    writer.register_type(
        StrictWireNameType,
        name=(f"{StrictWireNameType.__module__}.{StrictWireNameType.__qualname__}"),
    )
    reader.register_type(StrictWireNameType, name="security.StrictWireNameType")

    with pytest.raises(TypeUnregisteredError):
        reader.deserialize(writer.serialize(StrictWireNameType(1)))
    assert policy.validate_module_calls == 0


@pytest.mark.skipif(
    ENABLE_FORY_CYTHON_SERIALIZATION,
    reason="pure TypeResolver regression",
)
def test_namespace_alias_not_cached():
    config = Fory(xlang=True, compatible=False, strict=False).config
    resolver = TypeResolver(config, shared_registry=SharedRegistry())
    resolver.initialize()
    typeinfo = resolver.register_type(
        NamespaceAliasType,
        name="trusted.NamespaceAliasType",
    )
    namespace = resolver.shared_registry.get_encoded_meta_string(resolver.namespace_encoder.encode("attacker"))
    typename = resolver.shared_registry.get_encoded_meta_string(resolver.typename_encoder.encode("NamespaceAliasType"))
    buffer = Buffer.allocate(128)
    writer = MetaStringWriter()
    buffer.write_uint8(typeinfo.type_id)
    writer.write_encoded_meta_string(buffer, namespace)
    writer.write_encoded_meta_string(buffer, typename)
    buffer.set_reader_index(0)
    read_context = SimpleNamespace(
        buffer=buffer,
        meta_string_reader=MetaStringReader(resolver.shared_registry),
    )

    assert resolver.read_type_info(read_context) is typeinfo
    assert (namespace, typename) not in resolver._ns_type_to_type_info
    assert (
        typeinfo.namespace_bytes,
        typeinfo.typename_bytes,
    ) in resolver._ns_type_to_type_info


def test_wire_type_alias_cache_is_bounded():
    fory = Fory(xlang=True, compatible=False, strict=False)
    resolver = fory.type_resolver
    typeinfo = resolver.register_type(
        NamespaceAliasType,
        name="trusted.NamespaceAliasType",
    )
    for i in range(MAX_CACHED_ENCODED_META_STRINGS):
        resolver._ns_type_to_type_info[(i, i)] = typeinfo

    namespace = resolver.shared_registry.get_encoded_meta_string(MetaStringEncoder(".", "_").encode("trusted"))
    typename = resolver.shared_registry.get_encoded_meta_string(MetaStringEncoder("$", "_").encode("namespaceAliasType"))
    buffer = Buffer.allocate(128)
    writer = MetaStringWriter()
    buffer.write_uint8(typeinfo.type_id)
    writer.write_encoded_meta_string(buffer, namespace)
    writer.write_encoded_meta_string(buffer, typename)
    buffer.set_reader_index(0)
    try:
        fory.read_context.prepare(buffer)
        assert resolver.read_type_info(fory.read_context) is typeinfo
        assert (namespace, typename) not in resolver._ns_type_to_type_info
    finally:
        fory.reset_read()


@pytest.mark.skipif(
    ENABLE_FORY_CYTHON_SERIALIZATION,
    reason="pure TypeResolver regression",
)
@pytest.mark.parametrize(
    ("namespace_name", "type_name"),
    [
        ("trusted", "namespaceAliasType"),
        ("", "trusted.NamespaceAliasType"),
    ],
)
def test_strict_wire_alias_rejected(namespace_name, type_name):
    config = Fory(xlang=True, compatible=False, strict=True).config
    resolver = TypeResolver(config, shared_registry=SharedRegistry())
    resolver.initialize()
    typeinfo = resolver.register_type(
        NamespaceAliasType,
        name="trusted.NamespaceAliasType",
    )
    namespace = resolver.shared_registry.get_encoded_meta_string(resolver.namespace_encoder.encode(namespace_name))
    typename = resolver.shared_registry.get_encoded_meta_string(resolver.typename_encoder.encode(type_name))
    buffer = Buffer.allocate(256)
    writer = MetaStringWriter()
    buffer.write_uint8(typeinfo.type_id)
    writer.write_encoded_meta_string(buffer, namespace)
    writer.write_encoded_meta_string(buffer, typename)
    buffer.set_reader_index(0)
    read_context = SimpleNamespace(
        buffer=buffer,
        meta_string_reader=MetaStringReader(resolver.shared_registry),
    )

    with pytest.raises(TypeUnregisteredError):
        resolver.read_type_info(read_context)
    assert (namespace, typename) not in resolver._ns_type_to_type_info


def test_malformed_metastring_ref_raises_value_error():
    data = bytes([1, 255, TypeId.NAMED_STRUCT, 3])
    with pytest.raises(ValueError, match="Invalid dynamic metastring id"):
        Fory(xlang=True, compatible=False, strict=False).deserialize(data)


def test_read_metastring_reset_clears_dynamic_ids_only():
    shared_registry = SharedRegistry()
    encoded_meta_string = shared_registry.get_encoded_meta_string(MetaStringEncoder("$", "_").encode("hello"))
    shared_registry._encoded_metastrings.clear()
    reader = MetaStringReader(shared_registry)
    buffer = Buffer.allocate(64)

    buffer.write_var_uint32(encoded_meta_string.length << 1)
    buffer.write_int8(encoded_meta_string.encoding)
    buffer.write_bytes(encoded_meta_string.data)
    buffer.set_reader_index(0)

    assert reader.read_encoded_meta_string(buffer) == encoded_meta_string
    assert reader._small_encoded_meta_strings
    assert shared_registry._encoded_metastrings
    reader.reset()
    assert reader._small_encoded_meta_strings

    ref_buffer = Buffer.allocate(8)
    ref_buffer.write_var_uint32((1 << 1) | 1)
    ref_buffer.set_reader_index(0)
    with pytest.raises(ValueError, match="Invalid dynamic metastring id 1"):
        reader.read_encoded_meta_string(ref_buffer)


def test_encoded_metastring_registry_cache_is_bounded():
    shared_registry = SharedRegistry()
    for i in range(MAX_CACHED_ENCODED_META_STRINGS):
        shared_registry.get_or_create_encoded_meta_string(f"name-{i}".encode(), i << 8)

    encoded_meta_string = shared_registry.get_or_create_encoded_meta_string(b"overflow", 123 << 8)

    assert encoded_meta_string.data == b"overflow"
    assert len(shared_registry._encoded_metastrings) == MAX_CACHED_ENCODED_META_STRINGS
    assert ((123 << 8), b"overflow") not in shared_registry._encoded_metastrings

    shared_registry = SharedRegistry()
    encoder = MetaStringEncoder("$", "_")
    for i in range(MAX_CACHED_ENCODED_META_STRINGS):
        shared_registry.get_encoded_meta_string(encoder.encode(f"name-{i}"))
    overflow_meta_string = encoder.encode("overflow")
    shared_registry.get_encoded_meta_string(overflow_meta_string)

    assert len(shared_registry._metastr_to_bytes) == MAX_CACHED_ENCODED_META_STRINGS
    assert overflow_meta_string not in shared_registry._metastr_to_bytes


def test_oversized_encoded_metastring_not_retained():
    shared_registry = SharedRegistry()
    data = b"x" * (MAX_CACHED_ENCODED_META_STRING_LENGTH + 1)
    encoded = shared_registry.get_or_create_encoded_meta_string(
        data,
        hash_meta_string_data(data, Encoding.UTF_8.value),
    )

    assert not shared_registry._encoded_metastrings
    assert (
        shared_registry.get_or_create_encoded_meta_string(
            data,
            encoded.hashcode,
        )
        is not encoded
    )

    meta_string = MetaStringEncoder("$", "_").encode_with_encoding(
        "x" * (MAX_CACHED_ENCODED_META_STRING_LENGTH + 1),
        Encoding.UTF_8,
    )
    shared_registry.get_encoded_meta_string(meta_string)
    assert meta_string not in shared_registry._metastr_to_bytes
