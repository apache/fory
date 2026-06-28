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

import pytest

from pyfory import Buffer, Fory
from pyfory.context import EncodedMetaString, MetaStringReader, MetaStringWriter
from pyfory.meta.metastring import MetaStringEncoder
from pyfory.registry import MAX_CACHED_ENCODED_META_STRINGS, SharedRegistry
from pyfory.types import TypeId

try:
    from pyfory.serialization import MetaStringReader as CythonMetaStringReader
except ImportError:
    CythonMetaStringReader = None


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
