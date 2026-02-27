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
Test cases for configurable size guardrails for untrusted payloads.

These tests verify that:
- Binary byte-size limits are enforced during deserialization
- Collection (list/tuple/set) size limits are enforced during deserialization
- Map (dict) size limits are enforced using max_collection_size
- Defaults are set correctly (max_collection_size=1_000_000, max_binary_size=64MB)
"""

from dataclasses import dataclass
from typing import List

import pytest

import pyfory
from pyfory import Fory


class TestBinarySizeLimit:
    """Test binary byte-size limit enforcement."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_within_limit(self, xlang, ref):
        """Binary within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=100)

        data = b"hello world"
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_exceeds_limit(self, xlang, ref):
        """Binary exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=10)

        data = b"this binary data is longer than 10 bytes"
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_default_limit(self, xlang, ref):
        """Default limit should be 64MB."""
        fory = Fory(xlang=xlang, ref=ref)
        assert fory.max_binary_size == 64 * 1024 * 1024

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_binary_always_allowed(self, xlang, ref):
        """Empty binary should always be allowed regardless of limit."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=0)

        data = b""
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data


class TestCollectionSizeLimit:
    """Test collection (list/tuple/set) size limit enforcement."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_within_limit(self, xlang, ref):
        """List within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=10)

        data = [1, 2, 3, 4, 5]
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_exceeds_limit(self, xlang, ref):
        """List exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        data = list(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_within_limit(self, ref):
        """Tuple within limit should deserialize successfully."""
        # Tuple serialization only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_size=10)

        data = (1, 2, 3, 4, 5)
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_exceeds_limit(self, ref):
        """Tuple exceeding limit should raise ValueError."""
        # Tuple serialization only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_size=5)

        data = tuple(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_within_limit(self, xlang, ref):
        """Set within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=10)

        data = {1, 2, 3, 4, 5}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_exceeds_limit(self, xlang, ref):
        """Set exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        data = set(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_default_limit(self, xlang, ref):
        """Default limit should be 1,000,000."""
        fory = Fory(xlang=xlang, ref=ref)
        assert fory.max_collection_size == 1_000_000

    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_collection_always_allowed(self, ref):
        """Empty collections should always be allowed regardless of limit."""
        # Empty tuple test only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_size=0)

        for data in [[], (), set()]:
            serialized = fory_writer.serialize(data)
            result = fory_reader.deserialize(serialized)
            assert result == data


class TestMapSizeLimit:
    """Test map (dict) size limit enforcement using max_collection_size."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_within_limit(self, xlang, ref):
        """Dict within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=10)

        data = {"a": 1, "b": 2, "c": 3}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_exceeds_limit(self, xlang, ref):
        """Dict exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        data = {str(i): i for i in range(10)}
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_dict_always_allowed(self, xlang, ref):
        """Empty dict should always be allowed regardless of limit."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=0)

        data = {}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data


class TestNestedStructures:
    """Test limits on nested structures."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_nested_list_inner_exceeds_limit(self, xlang, ref):
        """Nested list where inner list exceeds limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        # Outer list has 2 elements, inner list has 10 elements
        data = [[1, 2], list(range(10))]
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)


class TestDataclassWithLimits:
    """Test limits with dataclass serialization."""

    def test_dataclass_list_field_exceeds_limit(self):
        """Dataclass with list field exceeding limit should raise ValueError."""

        @dataclass
        class Container:
            items: List[pyfory.int32]

        fory_writer = Fory(xlang=True, ref=False)
        fory_writer.register(Container)

        fory_reader = Fory(xlang=True, ref=False, max_collection_size=5)
        fory_reader.register(Container)

        data = Container(items=list(range(10)))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)


class TestIndependentLimits:
    """Test that binary and collection limits work independently."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_limit_does_not_affect_collection(self, xlang, ref):
        """Binary limit should not affect collection size."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=5)

        # Large list should work since collection limit is default
        data = list(range(100))
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_limit_does_not_affect_binary(self, xlang, ref):
        """Collection limit should not affect binary size."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        # Large binary should work since binary limit is default
        data = b"a" * 1000
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_both_limits_together(self, xlang, ref):
        """Both limits can be set together and work independently."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(
            xlang=xlang,
            ref=ref,
            max_binary_size=1000,
            max_collection_size=50,
        )

        # Data within all limits
        data = {
            "items": list(range(10)),
            "name": "short",
            "mapping": {"a": 1, "b": 2},
        }
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data


class TestBoundaryConditions:
    """Test boundary conditions for limits."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_exactly_at_limit(self, xlang, ref):
        """Collection exactly at limit should succeed."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        data = [1, 2, 3, 4, 5]  # Exactly 5 elements
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_one_over_limit(self, xlang, ref):
        """Collection one over limit should fail."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=5)

        data = [1, 2, 3, 4, 5, 6]  # 6 elements, limit is 5
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_exactly_at_limit(self, xlang, ref):
        """Map exactly at limit should succeed."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=3)

        data = {"a": 1, "b": 2, "c": 3}  # Exactly 3 entries
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_one_over_limit(self, xlang, ref):
        """Map one over limit should fail."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=3)

        data = {"a": 1, "b": 2, "c": 3, "d": 4}  # 4 entries, limit is 3
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_limit_of_zero_blocks_non_empty(self, xlang, ref):
        """Limit of 0 should block any non-empty collection."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_size=0)

        data = [1]  # Single element
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_exactly_at_limit(self, xlang, ref):
        """Binary exactly at limit should succeed."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=5)

        data = b"12345"  # Exactly 5 bytes
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_binary_one_over_limit(self, xlang, ref):
        """Binary one over limit should fail."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_binary_size=5)

        data = b"123456"  # 6 bytes, limit is 5
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)


class TestStringNotLimited:
    """Test that strings are NOT limited (per mentor feedback)."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_large_string_works_regardless_of_limits(self, xlang, ref):
        """Large strings should work regardless of any limits set."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        # Set small limits - strings should still work
        fory_reader = Fory(
            xlang=xlang,
            ref=ref,
            max_binary_size=5,
            max_collection_size=5,
        )

        data = "a" * 10000  # Large string
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data
