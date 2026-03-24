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
Test cases for collection serialization edge cases including None handling.
"""

from dataclasses import dataclass
from typing import List, Dict, Set, Optional

import pytest

import pyfory


class TestListWithNone:
    """Test list serialization with None elements."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_single_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [None]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_multiple_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [None, None, None]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_mixed_none_and_int(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [1, None, 2, None, 3]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_mixed_none_and_string(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = ["a", None, "b", None]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_nested_list_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [5, [5, None]]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_deeply_nested_list_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [1, [2, [3, None, [4, None]]]]
        result = fory.loads(fory.dumps(data))
        assert result == data


class TestSetWithNone:
    """Test set serialization with None elements."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_with_single_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {None}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_with_none_and_values(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {None, 1, 2, 3}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_set_with_none_and_strings(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {None, "a", "b"}
        result = fory.loads(fory.dumps(data))
        assert result == data


class TestTupleWithNone:
    """Test tuple serialization with None elements."""

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_with_single_none(self, ref):
        # Tuple is Python-only, xlang=False
        fory = pyfory.Fory(xlang=False, ref=ref)
        data = (None,)
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_with_multiple_none(self, ref):
        fory = pyfory.Fory(xlang=False, ref=ref)
        data = (None, None, None)
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_tuple_with_mixed_none(self, ref):
        fory = pyfory.Fory(xlang=False, ref=ref)
        data = (None, 1, None, "a")
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_nested_tuple_with_none(self, ref):
        fory = pyfory.Fory(xlang=False, ref=ref)
        data = (1, (2, None, (3, None)))
        result = fory.loads(fory.dumps(data))
        assert result == data


class TestTupleXlang:
    @pytest.mark.parametrize("ref", [False, True])
    def test_top_level_tuple_roundtrip_to_list(self, ref):
        fory = pyfory.Fory(xlang=True, ref=ref, strict=False)
        data = ("a", 1, ("nested", 2))
        result = fory.loads(fory.dumps(data))
        assert result == ["a", 1, ["nested", 2]]

    @pytest.mark.parametrize("ref", [False, True])
    def test_nested_dynamic_tuples_roundtrip_to_lists(self, ref):
        fory = pyfory.Fory(xlang=True, ref=ref, strict=False)
        data = [("a", 1), {"x": ("b", 2), "y": [("c", 3)]}]
        result = fory.loads(fory.dumps(data))
        assert result == [["a", 1], {"x": ["b", 2], "y": [["c", 3]]}]


class TestDictWithNone:
    """Test dict serialization with None keys/values."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_none_value(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"key": None}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_none_key(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {None: "value"}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_none_key_and_value(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {None: None}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_list_containing_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"a": [None]}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_with_multiple_none_values(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"a": None, "b": None, "c": 1}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_nested_dict_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"outer": {"inner": None, "list": [1, None, 3]}}
        result = fory.loads(fory.dumps(data))
        assert result == data


class TestComplexNestedStructures:
    """Test complex nested structures with None."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_complex_nested_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"a": [1, None, 3], "b": None, "c": [None, None]}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_of_dicts_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [{"a": None}, {"b": [None, 1]}, None]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_dict_of_sets_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {"set1": {1, None, 2}, "set2": {None}}
        result = fory.loads(fory.dumps(data))
        assert result == data


class TestStructWithCollections:
    """Test dataclass/struct fields containing collections with None."""

    @pytest.mark.parametrize("ref", [False, True])
    def test_struct_with_list_containing_none(self, ref):
        # Struct tests are Python-only
        @dataclass
        class MyStruct:
            items: List[Optional[int]]

        fory = pyfory.Fory(xlang=False, ref=ref)
        fory.register(MyStruct)
        data = MyStruct(items=[1, None, 3])
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_struct_with_dict_field(self, ref):
        @dataclass
        class MyStruct:
            mapping: Dict[Optional[str], Optional[int]]

        fory = pyfory.Fory(xlang=False, ref=ref)
        fory.register(MyStruct)
        # Test normal dict
        data = MyStruct(mapping={"a": 1, "b": 2})
        result = fory.loads(fory.dumps(data))
        assert result == data
        # Test with None value
        data2 = MyStruct(mapping={"a": None, "b": 2})
        result2 = fory.loads(fory.dumps(data2))
        assert result2 == data2
        # Test with None key
        data3 = MyStruct(mapping={None: 1, "b": 2})
        result3 = fory.loads(fory.dumps(data3))
        assert result3 == data3
        # Test with both None key and value
        data4 = MyStruct(mapping={None: None, "a": 1})
        result4 = fory.loads(fory.dumps(data4))
        assert result4 == data4

    @pytest.mark.parametrize("ref", [False, True])
    def test_struct_with_set_field(self, ref):
        @dataclass
        class MyStruct:
            items: Set[Optional[int]]

        fory = pyfory.Fory(xlang=False, ref=ref)
        fory.register(MyStruct)
        # Test normal set
        data = MyStruct(items={1, 2, 3})
        result = fory.loads(fory.dumps(data))
        assert result == data
        # Test set with None
        data2 = MyStruct(items={1, None, 3})
        result2 = fory.loads(fory.dumps(data2))
        assert result2 == data2
        # Test set with only None
        data3 = MyStruct(items={None})
        result3 = fory.loads(fory.dumps(data3))
        assert result3 == data3

    @pytest.mark.parametrize("ref", [False, True])
    def test_struct_with_nested_collections(self, ref):
        @dataclass
        class MyStruct:
            nested: List[Optional[Dict[Optional[str], Optional[List[Optional[int]]]]]]

        fory = pyfory.Fory(xlang=False, ref=ref)
        fory.register(MyStruct)
        # Test normal nested structure
        data = MyStruct(nested=[{"a": [1, 2]}, {"b": [3, 4]}])
        result = fory.loads(fory.dumps(data))
        assert result == data
        # Test with None in outer list
        data2 = MyStruct(nested=[{"a": [1, 2]}, None])
        result2 = fory.loads(fory.dumps(data2))
        assert result2 == data2
        # Test with None key in dict
        data3 = MyStruct(nested=[{None: [1, 2], "b": [3]}])
        result3 = fory.loads(fory.dumps(data3))
        assert result3 == data3
        # Test with None value in dict (list is None)
        data4 = MyStruct(nested=[{"a": None, "b": [1, 2]}])
        result4 = fory.loads(fory.dumps(data4))
        assert result4 == data4
        # Test with None in inner list
        data5 = MyStruct(nested=[{"a": [1, None, 3]}])
        result5 = fory.loads(fory.dumps(data5))
        assert result5 == data5
        # Test deeply nested None
        data6 = MyStruct(nested=[None, {None: None}, {"a": [None]}])
        result6 = fory.loads(fory.dumps(data6))
        assert result6 == data6


class TestEdgeCases:
    """Test edge cases for collection serialization."""

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_list(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = []
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_set(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = set()
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_dict(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = {}
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("ref", [False, True])
    def test_empty_tuple(self, ref):
        # Tuple is Python-only
        fory = pyfory.Fory(xlang=False, ref=ref)
        data = ()
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_single_element_collections(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        for data in [[1], {1}, {"a": 1}]:
            result = fory.loads(fory.dumps(data))
            assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_large_list_with_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [i if i % 3 != 0 else None for i in range(100)]
        result = fory.loads(fory.dumps(data))
        assert result == data

    @pytest.mark.parametrize("xlang", [False, True])
    @pytest.mark.parametrize("ref", [False, True])
    def test_list_with_different_types_and_none(self, xlang, ref):
        fory = pyfory.Fory(xlang=xlang, ref=ref)
        data = [1, "string", 3.14, None, True, [1, 2], {"a": 1}]
        result = fory.loads(fory.dumps(data))
        assert result == data
