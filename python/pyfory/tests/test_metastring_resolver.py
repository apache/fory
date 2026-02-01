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
from pyfory.serialization import MetaStringResolver, MetaStringBytes
from pyfory.meta.metastring import MetaStringEncoder


def test_metastring_resolver():
    resolver = MetaStringResolver()
    encoder = MetaStringEncoder("$", "_")
    try:

        # Test 1: Regular English string
        metastr1 = encoder.encode("hello, world")
        metabytes1 = resolver.get_metastr_bytes(metastr1)
        buffer = Buffer.allocate(32)
        resolver.write_meta_string_bytes(buffer, metabytes1)
        assert resolver.read_meta_string_bytes(buffer) == metabytes1

        # Test 2: Manually constructed MetaStringBytes
        metabytes2 = MetaStringBytes(
            data=b"\xbf\x05\xa4q\xa9\x92S\x96\xa6IOr\x9ch)\x80",
            hashcode=-2270219110992250879,
        )
        resolver.write_meta_string_bytes(buffer, metabytes2)
        assert resolver.read_meta_string_bytes(buffer) == metabytes2

        # Test 3: Empty string
        metastr_null = encoder.encode("")
        metabytes_null = resolver.get_metastr_bytes(metastr_null)
        resolver.write_meta_string_bytes(buffer, metabytes_null)
        assert resolver.read_meta_string_bytes(buffer) == metabytes_null

        # Test 4: Chinese string
        metastr_cn = encoder.encode("你好，世界")
        metabytes_cn = resolver.get_metastr_bytes(metastr_cn)
        resolver.write_meta_string_bytes(buffer, metabytes_cn)
        assert resolver.read_meta_string_bytes(buffer) == metabytes_cn

        # Test 5: Japanese string (more than 16 bytes, triggers hash-based encoding)
        metastr_jp = encoder.encode("こんにちは世界")
        metabytes_jp = resolver.get_metastr_bytes(metastr_jp)
        resolver.write_meta_string_bytes(buffer, metabytes_jp)
        assert resolver.read_meta_string_bytes(buffer) == metabytes_jp

        # Test 6: Long string (more than 16 bytes, triggers hash-based encoding)
        long_str = "hello, world" * 10
        metastr_long = encoder.encode(long_str)
        metabytes_long = resolver.get_metastr_bytes(metastr_long)
        resolver.write_meta_string_bytes(buffer, metabytes_long)
        assert resolver.read_meta_string_bytes(buffer) == metabytes_long
    finally:
        resolver.reset_write()
        resolver.reset_read()
