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
from typing import Union

from pyfory import Fory


def test_union_basic_types():
    """Test Union with basic types like int and str"""
    fory = Fory()

    # Test with int value
    value_int: Union[int, str] = 42
    serialized = fory.serialize(value_int)
    deserialized = fory.deserialize(serialized)
    assert deserialized == 42
    assert type(deserialized) is int

    # Test with str value
    value_str: Union[int, str] = "hello"
    serialized = fory.serialize(value_str)
    deserialized = fory.deserialize(serialized)
    assert deserialized == "hello"
    assert type(deserialized) is str


def test_union_multiple_types():
    """Test Union with more than two types"""
    fory = Fory()

    # Test with int
    value1: Union[int, str, float] = 123
    serialized = fory.serialize(value1)
    deserialized = fory.deserialize(serialized)
    assert deserialized == 123
    assert type(deserialized) is int

    # Test with str
    value2: Union[int, str, float] = "test"
    serialized = fory.serialize(value2)
    deserialized = fory.deserialize(serialized)
    assert deserialized == "test"
    assert type(deserialized) is str

    # Test with float
    value3: Union[int, str, float] = 3.14
    serialized = fory.serialize(value3)
    deserialized = fory.deserialize(serialized)
    assert abs(deserialized - 3.14) < 0.0001
    assert type(deserialized) is float


def test_union_with_collections():
    """Test Union with collection types"""
    fory = Fory()

    # Test with list
    value_list: Union[list, dict] = [1, 2, 3]
    serialized = fory.serialize(value_list)
    deserialized = fory.deserialize(serialized)
    assert deserialized == [1, 2, 3]
    assert type(deserialized) is list

    # Test with dict
    value_dict: Union[list, dict] = {"a": 1, "b": 2}
    serialized = fory.serialize(value_dict)
    deserialized = fory.deserialize(serialized)
    assert deserialized == {"a": 1, "b": 2}
    assert type(deserialized) is dict


def test_union_with_optional():
    """Test Union with Optional (Union[T, None])"""
    fory = Fory(ref_tracking=True)

    # Test with non-None value
    value: Union[int, None] = 42
    serialized = fory.serialize(value)
    deserialized = fory.deserialize(serialized)
    assert deserialized == 42

    # Test with None value
    value_none: Union[int, None] = None
    serialized = fory.serialize(value_none)
    deserialized = fory.deserialize(serialized)
    assert deserialized is None


def test_union_with_dataclass():
    """Test Union with dataclass types"""

    @dataclasses.dataclass
    class Person:
        name: str
        age: int

    @dataclasses.dataclass
    class Company:
        name: str
        employees: int

    fory = Fory()
    fory.register(Person)
    fory.register(Company)

    # Test with Person
    person = Person("Alice", 30)
    value: Union[Person, Company] = person
    serialized = fory.serialize(value)
    deserialized = fory.deserialize(serialized)
    assert deserialized == person
    assert type(deserialized) is Person

    # Test with Company
    company = Company("TechCorp", 100)
    value2: Union[Person, Company] = company
    serialized = fory.serialize(value2)
    deserialized = fory.deserialize(serialized)
    assert deserialized == company
    assert type(deserialized) is Company


def test_union_nested_in_dataclass():
    """Test Union type as a field in a dataclass"""

    @dataclasses.dataclass
    class Container:
        value: Union[int, str]
        name: str

    fory = Fory()
    fory.register(Container)

    # Test with int value
    obj1 = Container(value=42, name="test1")
    serialized = fory.serialize(obj1)
    deserialized = fory.deserialize(serialized)
    assert deserialized.value == 42
    assert deserialized.name == "test1"
    assert type(deserialized.value) is int

    # Test with str value
    obj2 = Container(value="hello", name="test2")
    serialized = fory.serialize(obj2)
    deserialized = fory.deserialize(serialized)
    assert deserialized.value == "hello"
    assert deserialized.name == "test2"
    assert type(deserialized.value) is str


def test_union_with_bytes():
    """Test Union with bytes type"""
    fory = Fory()

    # Test with bytes
    value_bytes: Union[bytes, str] = b"hello"
    serialized = fory.serialize(value_bytes)
    deserialized = fory.deserialize(serialized)
    assert deserialized == b"hello"
    assert type(deserialized) is bytes

    # Test with str
    value_str: Union[bytes, str] = "world"
    serialized = fory.serialize(value_str)
    deserialized = fory.deserialize(serialized)
    assert deserialized == "world"
    assert type(deserialized) is str


def test_union_cross_language():
    """Test Union with cross-language serialization"""
    fory = Fory(language="xlang")

    # Test with int value
    value_int: Union[int, str] = 42
    serialized = fory.serialize(value_int)
    deserialized = fory.deserialize(serialized)
    assert deserialized == 42
    assert type(deserialized) is int

    # Test with str value
    value_str: Union[int, str] = "test"
    serialized = fory.serialize(value_str)
    deserialized = fory.deserialize(serialized)
    assert deserialized == "test"
    assert type(deserialized) is str
