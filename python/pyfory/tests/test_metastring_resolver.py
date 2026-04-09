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

from pyfory import Buffer
from pyfory.context import EncodedMetaString, MetaStringReader, MetaStringWriter
from pyfory.meta.metastring import MetaStringEncoder
from pyfory.registry import SharedRegistry


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
