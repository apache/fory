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
- String byte-length limits are enforced during deserialization
- Collection (list/tuple/set) length limits are enforced during deserialization
- Map (dict) length limits are enforced during deserialization
- Limits can be configured independently
- Default value (-1) disables the limit
"""

from dataclasses import dataclass
from typing import List

import pytest

import pyfory
from pyfory import Fory


class TestStringBytesLengthLimit:
    """Test string byte-length limit enforcement."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_string_within_limit(self, xlang, ref):
        """String within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=100)

        data = "hello world"
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_string_exceeds_limit(self, xlang, ref):
        """String exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=10)

        data = "this string is longer than 10 bytes"
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_string_limit_disabled_by_default(self, xlang, ref):
        """Default limit (-1) should allow any string size."""
        fory = Fory(xlang=xlang, ref=ref)
        assert fory.max_string_bytes_length == -1

        data = "a" * 10000
        result = fory.deserialize(fory.serialize(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_unicode_string_limit(self, xlang, ref):
        """Unicode strings should be measured in serialized bytes."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        # Use a longer unicode string to ensure it exceeds the limit
        # regardless of the internal encoding (UTF-8, UTF-16, or Latin-1)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=5)

        data = "你好世界测试"  # Multiple unicode characters
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_string_always_allowed(self, xlang, ref):
        """Empty string should always be allowed regardless of limit."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=0)

        data = ""
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data


class TestCollectionLengthLimit:
    """Test collection (list/tuple/set) length limit enforcement."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_within_limit(self, xlang, ref):
        """List within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=10)

        data = [1, 2, 3, 4, 5]
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_exceeds_limit(self, xlang, ref):
        """List exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        data = list(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_within_limit(self, ref):
        """Tuple within limit should deserialize successfully."""
        # Tuple serialization only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_length=10)

        data = (1, 2, 3, 4, 5)
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_exceeds_limit(self, ref):
        """Tuple exceeding limit should raise ValueError."""
        # Tuple serialization only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_length=5)

        data = tuple(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_within_limit(self, xlang, ref):
        """Set within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=10)

        data = {1, 2, 3, 4, 5}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_exceeds_limit(self, xlang, ref):
        """Set exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        data = set(range(10))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_limit_disabled_by_default(self, xlang, ref):
        """Default limit (-1) should allow any collection size."""
        fory = Fory(xlang=xlang, ref=ref)
        assert fory.max_collection_length == -1

        data = list(range(1000))
        result = fory.deserialize(fory.serialize(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_collection_always_allowed(self, ref):
        """Empty collections should always be allowed regardless of limit."""
        # Empty tuple test only works in Python-native mode (xlang=False)
        fory_writer = Fory(xlang=False, ref=ref)
        fory_reader = Fory(xlang=False, ref=ref, max_collection_length=0)

        for data in [[], (), set()]:
            serialized = fory_writer.serialize(data)
            result = fory_reader.deserialize(serialized)
            assert result == data


class TestMapLengthLimit:
    """Test map (dict) length limit enforcement."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_within_limit(self, xlang, ref):
        """Dict within limit should deserialize successfully."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=10)

        data = {"a": 1, "b": 2, "c": 3}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_exceeds_limit(self, xlang, ref):
        """Dict exceeding limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=5)

        data = {str(i): i for i in range(10)}
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_limit_disabled_by_default(self, xlang, ref):
        """Default limit (-1) should allow any map size."""
        fory = Fory(xlang=xlang, ref=ref)
        assert fory.max_map_length == -1

        data = {str(i): i for i in range(1000)}
        result = fory.deserialize(fory.serialize(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_dict_always_allowed(self, xlang, ref):
        """Empty dict should always be allowed regardless of limit."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=0)

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
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        # Outer list has 2 elements, inner list has 10 elements
        data = [[1, 2], list(range(10))]
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_string_exceeds_string_limit(self, xlang, ref):
        """List containing string that exceeds string limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=5)

        data = ["short", "this is a long string"]
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_string_key_exceeds_limit(self, xlang, ref):
        """Dict with string key that exceeds limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=5)

        data = {"this_key_is_too_long": 1}
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_string_value_exceeds_limit(self, xlang, ref):
        """Dict with string value that exceeds limit should raise ValueError."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=5)

        data = {"k": "this_value_is_too_long"}
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)


class TestDataclassWithLimits:
    """Test limits with dataclass serialization."""

    def test_dataclass_string_field_exceeds_limit(self):
        """Dataclass with string field exceeding limit should raise ValueError."""

        @dataclass
        class Person:
            name: str
            age: pyfory.int32

        fory_writer = Fory(xlang=True, ref=False)
        fory_writer.register(Person)

        fory_reader = Fory(xlang=True, ref=False, max_string_bytes_length=5)
        fory_reader.register(Person)

        data = Person(name="a_very_long_name", age=25)
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    def test_dataclass_list_field_exceeds_limit(self):
        """Dataclass with list field exceeding limit should raise ValueError."""

        @dataclass
        class Container:
            items: List[pyfory.int32]

        fory_writer = Fory(xlang=True, ref=False)
        fory_writer.register(Container)

        fory_reader = Fory(xlang=True, ref=False, max_collection_length=5)
        fory_reader.register(Container)

        data = Container(items=list(range(10)))
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)


class TestIndependentLimits:
    """Test that different limits work independently."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_string_limit_does_not_affect_collection(self, xlang, ref):
        """String limit should not affect collection size."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_string_bytes_length=5)

        # Large list with short strings should work
        data = ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j"]
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_limit_does_not_affect_map(self, xlang, ref):
        """Collection limit should not affect map size."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        # Large dict should work since map limit is not set
        data = {str(i): i for i in range(100)}
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_limit_does_not_affect_collection(self, xlang, ref):
        """Map limit should not affect collection size."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=5)

        # Large list should work since collection limit is not set
        data = list(range(100))
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_all_limits_together(self, xlang, ref):
        """All limits can be set together and work independently."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(
            xlang=xlang,
            ref=ref,
            max_string_bytes_length=100,
            max_collection_length=50,
            max_map_length=50,
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
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        data = [1, 2, 3, 4, 5]  # Exactly 5 elements
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_collection_one_over_limit(self, xlang, ref):
        """Collection one over limit should fail."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=5)

        data = [1, 2, 3, 4, 5, 6]  # 6 elements, limit is 5
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_exactly_at_limit(self, xlang, ref):
        """Map exactly at limit should succeed."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=3)

        data = {"a": 1, "b": 2, "c": 3}  # Exactly 3 entries
        serialized = fory_writer.serialize(data)
        result = fory_reader.deserialize(serialized)
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_map_one_over_limit(self, xlang, ref):
        """Map one over limit should fail."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_map_length=3)

        data = {"a": 1, "b": 2, "c": 3, "d": 4}  # 4 entries, limit is 3
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_limit_of_zero_blocks_non_empty(self, xlang, ref):
        """Limit of 0 should block any non-empty collection."""
        fory_writer = Fory(xlang=xlang, ref=ref)
        fory_reader = Fory(xlang=xlang, ref=ref, max_collection_length=0)

        data = [1]  # Single element
        serialized = fory_writer.serialize(data)

        with pytest.raises(ValueError, match="exceeds the configured limit"):
            fory_reader.deserialize(serialized)
