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

import dataclasses
from typing import List, Dict
from pyfory import Fory
import pyfory


@dataclasses.dataclass
class SimpleDataClass:
    name: str
    age: int
    active: bool


@dataclasses.dataclass
class SimpleNestedDataClass:
    value: int
    name: str


@dataclasses.dataclass
class ExtendedDataClass:
    name: str
    age: int
    active: bool
    email: str  # Additional field


@dataclasses.dataclass
class ReducedDataClass:
    name: str
    age: int
    # Missing 'active' field


@dataclasses.dataclass
class NestedStructClass:
    name: str
    nested: SimpleNestedDataClass


@dataclasses.dataclass
class NestedStructClassInconsistent:
    name: str
    nested: ExtendedDataClass  # Different nested type


@dataclasses.dataclass
class ListFieldsClass:
    name: str
    int_list: List[pyfory.Int32Type]
    str_list: List[str]


@dataclasses.dataclass
class ListFieldsClassInconsistent:
    name: str
    int_list: List[str]  # Changed from Int32Type to str
    str_list: List[pyfory.Int32Type]  # Changed from str to Int32Type


@dataclasses.dataclass
class DictFieldsClass:
    name: str
    int_dict: Dict[str, pyfory.Int32Type]
    str_dict: Dict[str, str]


@dataclasses.dataclass
class DictFieldsClassInconsistent:
    name: str
    int_dict: Dict[str, str]  # Changed from Int32Type to str
    str_dict: Dict[str, pyfory.Int32Type]  # Changed from str to Int32Type


class TestMetaShareMode:
    def setup_method(self):
        """Setup method to register dataclasses for each test."""
        pass

    def test_meta_share_enabled(self):
        """Test that meta share mode can be enabled."""
        fory = Fory(xlang=True, compatible=True)
        assert fory.serialization_context.scoped_meta_share_enabled
        assert fory.serialization_context.meta_context is not None

    def test_meta_share_disabled(self):
        """Test that meta share mode can be disabled."""
        fory = Fory(xlang=True, compatible=False)
        assert not fory.serialization_context.scoped_meta_share_enabled
        assert fory.serialization_context.meta_context is None

    def test_simple_dataclass_serialization(self):
        """Test serialization of simple dataclass with meta share."""
        fory = Fory(xlang=True, compatible=True)

        # Register the dataclass
        fory.register_type(SimpleDataClass)

        obj = SimpleDataClass(name="test", age=25, active=True)
        buffer = fory.serialize(obj)

        # Deserialize
        deserialized = fory.deserialize(buffer)
        assert deserialized.name == obj.name
        assert deserialized.age == obj.age
        assert deserialized.active == obj.active

    def test_multiple_objects_same_type(self):
        """Test that multiple objects of same type reuse type definition."""
        fory = Fory(xlang=True, compatible=True)

        # Register the dataclass
        fory.register_type(SimpleDataClass)

        obj1 = SimpleDataClass(name="test1", age=25, active=True)
        obj2 = SimpleDataClass(name="test2", age=30, active=False)

        # Serialize both objects
        buffer1 = fory.serialize(obj1)
        buffer2 = fory.serialize(obj2)

        # Create a new fory instance with the same meta context for deserialization
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(SimpleDataClass)
        # Copy the meta context from the first fory instance
        fory2.serialization_context.meta_context = fory.serialization_context.meta_context

        # Deserialize both
        deserialized1 = fory2.deserialize(buffer1)
        deserialized2 = fory2.deserialize(buffer2)

        assert deserialized1.name == obj1.name
        assert deserialized2.name == obj2.name
        assert deserialized1.age == obj1.age
        assert deserialized2.age == obj2.age

    def test_simple_nested_dataclass_serialization(self):
        """Test serialization of simple nested dataclass with meta share."""
        fory = Fory(xlang=True, compatible=True)

        # Register the dataclass
        fory.register_type(SimpleNestedDataClass)

        obj = SimpleNestedDataClass(value=42, name="test")

        buffer = fory.serialize(obj)
        deserialized = fory.deserialize(buffer)

        assert deserialized.value == obj.value
        assert deserialized.name == obj.name

    def test_serialization_without_meta_share(self):
        """Test that serialization works without meta share mode."""
        fory = Fory(xlang=True, compatible=False)

        # Register the dataclass
        fory.register_type(SimpleDataClass)

        obj = SimpleDataClass(name="test", age=25, active=True)
        buffer = fory.serialize(obj)
        deserialized = fory.deserialize(buffer)

        assert deserialized.name == obj.name
        assert deserialized.age == obj.age
        assert deserialized.active == obj.active

    def test_schema_evolution_more_fields(self):
        # Serialize with original schema
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(SimpleDataClass)

        obj = SimpleDataClass(name="test", age=25, active=True)
        buffer = fory1.serialize(obj)

        # Deserialize with extended schema (more fields)
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ExtendedDataClass)
        deserialized = fory2.deserialize(buffer)

        # Current behavior: deserialized object is of the new registered type
        assert isinstance(deserialized, ExtendedDataClass)
        assert deserialized.name == obj.name
        assert deserialized.age == obj.age
        assert deserialized.active == obj.active
        assert not hasattr(deserialized, "email")

    def test_schema_evolution_fewer_fields(self):
        # Serialize with original schema
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(SimpleDataClass)
        obj = SimpleDataClass(name="test", age=25, active=True)
        buffer = fory1.serialize(obj)

        # Deserialize with reduced schema (fewer fields)
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ReducedDataClass)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, ReducedDataClass)
        assert deserialized.name == obj.name
        assert deserialized.age == obj.age
        # The missing field should not be present
        assert not hasattr(deserialized, "active")

    def test_schema_inconsistent_nested_struct(self):
        """Test schema inconsistency with nested struct types."""
        # Serialize with original schema
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(NestedStructClass)
        fory1.register_type(SimpleNestedDataClass)

        obj = NestedStructClass(name="test", nested=SimpleNestedDataClass(value=42, name="nested_test"))
        buffer = fory1.serialize(obj)

        # Deserialize with inconsistent schema (different nested type)
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(NestedStructClassInconsistent)
        fory2.register_type(ExtendedDataClass)

        # This should handle the schema inconsistency gracefully
        deserialized = fory2.deserialize(buffer)
        assert isinstance(deserialized, NestedStructClassInconsistent)
        assert deserialized.name == obj.name
        # The nested field type has changed, so we expect different behavior
        assert hasattr(deserialized, "nested")

    def test_schema_inconsistent_list_fields(self):
        """Test schema inconsistency with List field types."""
        # Serialize with original schema
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(ListFieldsClass)

        obj = ListFieldsClass(name="test", int_list=[1, 2, 3], str_list=["a", "b", "c"])
        buffer = fory1.serialize(obj)

        # Deserialize with inconsistent schema (swapped List types)
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ListFieldsClassInconsistent)

        # This should handle the schema inconsistency gracefully
        deserialized = fory2.deserialize(buffer)
        assert isinstance(deserialized, ListFieldsClassInconsistent)
        assert deserialized.name == obj.name
        # The field types have been swapped, so we expect different behavior
        assert hasattr(deserialized, "int_list")
        assert hasattr(deserialized, "str_list")

    def test_schema_inconsistent_dict_fields(self):
        """Test schema inconsistency with Dict field types."""
        # Serialize with original schema
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(DictFieldsClass)

        obj = DictFieldsClass(
            name="test",
            int_dict={"key1": 1, "key2": 2},
            str_dict={"key1": "value1", "key2": "value2"},
        )
        buffer = fory1.serialize(obj)

        # Deserialize with inconsistent schema (swapped Dict value types)
        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(DictFieldsClassInconsistent)

        # This should handle the schema inconsistency gracefully
        deserialized = fory2.deserialize(buffer)
        assert isinstance(deserialized, DictFieldsClassInconsistent)
        assert deserialized.name == obj.name
        # The field value types have been swapped, so we expect different behavior
        assert hasattr(deserialized, "int_dict")
        assert hasattr(deserialized, "str_dict")
