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
Test max_collection_size and max_binary_size guardrails to prevent OOM attacks
from malicious payloads.

Collections preallocate memory based on declared size, so they need guardrails.
Binary reads are guarded by max_binary_size on the Buffer.
"""

from dataclasses import dataclass
from typing import List

import pytest

import pyfory
from pyfory import Fory
from pyfory.serialization import Buffer
from pyfory.types import TypeId


class ObjectPayload:
    pass


def roundtrip(data, limit, xlang=False, ref=False):
    """Serialize and deserialize with given collection size limit."""
    writer = Fory(xlang=xlang, ref=ref)
    reader = Fory(xlang=xlang, ref=ref, max_collection_size=limit)
    return reader.deserialize(writer.serialize(data))


def roundtrip_binary(data, max_binary_size, xlang=False, ref=False):
    """Serialize and deserialize with given binary size limit."""
    writer = Fory(xlang=xlang, ref=ref)
    reader = Fory(xlang=xlang, ref=ref, max_binary_size=max_binary_size)
    return reader.deserialize(writer.serialize(data))


class TestCollectionSizeLimit:
    """Collections (list/set/dict) preallocate memory, so need size limits."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize(
        "data,limit",
        [
            ([1, 2, 3], 10),  # list within limit
            ({1, 2, 3}, 10),  # set within limit
            ({"a": 1}, 10),  # dict within limit
            ([], 0),  # empty list ok
            (set(), 0),  # empty set ok
            ({}, 0),  # empty dict ok
        ],
    )
    def test_within_limit_succeeds(self, xlang, data, limit):
        assert roundtrip(data, limit, xlang=xlang) == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize(
        "data,limit",
        [
            (list(range(10)), 5),  # list exceeds
            (set(range(10)), 5),  # set exceeds
            ({str(i): i for i in range(10)}, 5),  # dict exceeds
            ([[1], list(range(10))], 5),  # nested inner exceeds
        ],
    )
    def test_exceeds_limit_fails(self, xlang, data, limit):
        with pytest.raises(ValueError, match="exceeds the configured limit"):
            roundtrip(data, limit, xlang=xlang)

    @pytest.mark.parametrize("ref", [False, True])
    @pytest.mark.parametrize(
        "data,limit,should_fail",
        [
            ((1, 2, 3), 10, False),
            (tuple(range(10)), 5, True),
        ],
    )
    def test_tuple_limit(self, ref, data, limit, should_fail):
        """Tuple only works in xlang=False mode."""
        if should_fail:
            with pytest.raises(ValueError, match="exceeds the configured limit"):
                roundtrip(data, limit, xlang=False, ref=ref)
        else:
            assert roundtrip(data, limit, xlang=False, ref=ref) == data

    def test_default_limit_is_one_million(self):
        assert Fory().max_collection_size == 1_000_000

    def test_dataclass_list_field_exceeds_limit(self):
        @dataclass
        class Container:
            items: List[pyfory.int32]

        writer = Fory(xlang=True)
        writer.register(Container)
        reader = Fory(xlang=True, max_collection_size=5)
        reader.register(Container)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            reader.deserialize(writer.serialize(Container(items=list(range(10)))))

    def test_object_field_count_exceeds_limit(self):
        obj = ObjectPayload()
        obj.value = 1
        writer = Fory(ref=True, strict=False)
        reader = Fory(ref=True, strict=False, max_collection_size=0)
        writer.register(ObjectPayload)
        reader.register(ObjectPayload)

        with pytest.raises(ValueError, match="object field size 1 exceeds"):
            reader.deserialize(writer.serialize(obj))

    def test_local_class_base_count_exceeds_limit(self):
        def make_local_class():
            class LocalPayload:
                pass

            return LocalPayload

        writer = Fory(ref=True, strict=False)
        reader = Fory(ref=True, strict=False, max_collection_size=0)

        with pytest.raises(ValueError, match="local class base size 1 exceeds"):
            reader.deserialize(writer.serialize(make_local_class()))

    def test_local_function_defaults_exceed_limit(self):
        def local_function(value=1):
            return value

        writer = Fory(ref=True, strict=False)
        reader = Fory(ref=True, strict=False, max_collection_size=0)

        with pytest.raises(ValueError, match="function default size 1 exceeds"):
            reader.deserialize(writer.serialize(local_function))

    def test_object_ndarray_length_exceeds_limit(self):
        np = pytest.importorskip("numpy")
        arr = np.array([object(), object()], dtype=object)
        writer = Fory(ref=True, strict=False)
        reader = Fory(ref=True, strict=False, max_collection_size=1)

        with pytest.raises(ValueError, match="ndarray object size 2 exceeds"):
            reader.deserialize(writer.serialize(arr))


class TestBinarySizeLimit:
    """Binary reads are guarded by max_binary_size on the Buffer."""

    def test_default_limit_is_64mib(self):
        assert Fory().max_binary_size == 64 * 1024 * 1024

    @pytest.mark.parametrize("xlang", [False, True])
    def test_within_limit_succeeds(self, xlang):
        assert roundtrip_binary(b"x" * 100, max_binary_size=1024, xlang=xlang) == b"x" * 100

    @pytest.mark.parametrize("xlang", [False, True])
    def test_exceeds_limit_fails(self, xlang):
        with pytest.raises(ValueError, match="exceeds the configured limit"):
            roundtrip_binary(b"x" * 200, max_binary_size=100, xlang=xlang)

    @pytest.mark.parametrize("xlang", [False, True])
    def test_string_exceeds_limit_fails(self, xlang):
        writer = Fory(xlang=xlang)
        reader = Fory(xlang=xlang, max_binary_size=1)
        with pytest.raises(ValueError, match="String size 2 exceeds"):
            reader.deserialize(writer.serialize("xx"))

    def test_from_stream_respects_limit(self):
        import io

        payload = Fory().serialize(b"x" * 200)
        buf = Buffer.from_stream(io.BytesIO(payload), max_binary_size=100)
        with pytest.raises(ValueError, match="exceeds the configured limit"):
            Fory(max_binary_size=100).deserialize(buf)

    def test_in_band_buffer_object_respects_limit(self):
        payload = b"x" * 200
        data = Fory(ref=True).serialize(payload, buffer_callback=lambda _buffer: True)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            Fory(ref=True, max_binary_size=100).deserialize(data, buffers=[])

    def test_malformed_metastring_ref_raises_value_error(self):
        payload = bytes([2, 255, TypeId.NAMED_STRUCT, 3])
        with pytest.raises(ValueError, match="Invalid dynamic metastring id"):
            Fory(xlang=True, strict=False).deserialize(payload)
