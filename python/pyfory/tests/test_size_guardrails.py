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
Test max_collection_size guardrail to prevent OOM attacks from malicious payloads.

Collections preallocate memory based on declared size, so they need guardrails.
Binary/strings do NOT need limits - buffer checks availability before allocation.
"""

from dataclasses import dataclass
from typing import List

import pytest

import pyfory
from pyfory import Fory


def roundtrip(data, limit, xlang=False, ref=False):
    """Serialize and deserialize with given collection size limit."""
    writer = Fory(xlang=xlang, ref=ref)
    reader = Fory(xlang=xlang, ref=ref, max_collection_size=limit)
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


class TestBinaryAndStringNoLimit:
    """Binary/strings don't need limits - buffer validates before allocation."""

    @pytest.mark.parametrize("data", [b"x" * 10000, "x" * 10000])
    def test_large_data_works_with_small_collection_limit(self, data):
        assert roundtrip(data, limit=5) == data
