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
from typing import Dict, List

import pytest

import pyfory
from pyfory import Fory


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
    email: str


@dataclasses.dataclass
class ReducedDataClass:
    name: str
    age: int


@dataclasses.dataclass
class NestedStructClass:
    name: str
    nested: SimpleNestedDataClass


@dataclasses.dataclass
class NestedStructClassInconsistent:
    name: str
    nested: ExtendedDataClass


@dataclasses.dataclass
class ListFieldsClass:
    name: str
    int_list: List[pyfory.int32]
    str_list: List[str]


@dataclasses.dataclass
class ListFieldsClassInconsistent:
    name: str
    int_list: List[str]
    str_list: List[pyfory.int32]


@dataclasses.dataclass
class DictFieldsClass:
    name: str
    int_dict: Dict[str, pyfory.int32]
    str_dict: Dict[str, str]


@dataclasses.dataclass
class DictFieldsClassInconsistent:
    name: str
    int_dict: Dict[str, str]
    str_dict: Dict[str, pyfory.int32]


class TestMetaShareMode:
    def test_meta_share_enabled(self):
        fory = Fory(xlang=True, compatible=True)
        assert fory.config.scoped_meta_share_enabled
        assert fory.write_context.meta_share_context is not None
        assert fory.read_context.meta_share_context is not None

    def test_meta_share_disabled(self):
        fory = Fory(xlang=True, compatible=False)
        assert not fory.config.scoped_meta_share_enabled
        assert fory.write_context.meta_share_context is None
        assert fory.read_context.meta_share_context is None

    def test_simple_dataclass_serialization(self):
        fory = Fory(xlang=True, compatible=True)
        fory.register_type(SimpleDataClass)
        obj = SimpleDataClass(name="test", age=25, active=True)
        deserialized = fory.deserialize(fory.serialize(obj))
        assert deserialized == obj

    def test_strict_reader_rejects_unknown_typedef(self):
        writer = Fory(xlang=True, compatible=True, strict=False)
        writer.register_type(SimpleDataClass)
        reader = Fory(xlang=True, compatible=True, strict=True)

        with pytest.raises(ValueError, match="not registered in strict mode"):
            reader.deserialize(writer.serialize(SimpleDataClass(name="test", age=25, active=True)))

    def test_multiple_objects_same_type(self):
        fory = Fory(xlang=True, compatible=True)
        fory.register_type(SimpleDataClass)
        payload = [
            SimpleDataClass(name="test1", age=25, active=True),
            SimpleDataClass(name="test2", age=30, active=False),
        ]
        deserialized = fory.deserialize(fory.serialize(payload))
        assert deserialized == payload

    def test_simple_nested_dataclass_serialization(self):
        fory = Fory(xlang=True, compatible=True)
        fory.register_type(SimpleNestedDataClass)
        obj = SimpleNestedDataClass(value=42, name="test")
        assert fory.deserialize(fory.serialize(obj)) == obj

    def test_serialization_without_meta_share(self):
        fory = Fory(xlang=True, compatible=False)
        fory.register_type(SimpleDataClass)
        obj = SimpleDataClass(name="test", age=25, active=True)
        assert fory.deserialize(fory.serialize(obj)) == obj

    def test_schema_evolution_more_fields(self):
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(SimpleDataClass)
        buffer = fory1.serialize(SimpleDataClass(name="test", age=25, active=True))

        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ExtendedDataClass)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, ExtendedDataClass)
        assert deserialized.name == "test"
        assert deserialized.age == 25
        assert deserialized.active is True
        assert deserialized.email == ""

    def test_schema_evolution_fewer_fields(self):
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(SimpleDataClass)
        buffer = fory1.serialize(SimpleDataClass(name="test", age=25, active=True))

        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ReducedDataClass)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, ReducedDataClass)
        assert deserialized.name == "test"
        assert deserialized.age == 25
        assert not hasattr(deserialized, "active")

    def test_schema_inconsistent_nested_struct(self):
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(NestedStructClass)
        fory1.register_type(SimpleNestedDataClass)
        buffer = fory1.serialize(NestedStructClass(name="test", nested=SimpleNestedDataClass(value=42, name="nested_test")))

        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(NestedStructClassInconsistent)
        fory2.register_type(ExtendedDataClass)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, NestedStructClassInconsistent)
        assert deserialized.name == "test"
        assert hasattr(deserialized, "nested")

    def test_schema_inconsistent_list_fields(self):
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(ListFieldsClass)
        buffer = fory1.serialize(ListFieldsClass(name="test", int_list=[1, 2, 3], str_list=["a", "b", "c"]))

        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(ListFieldsClassInconsistent)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, ListFieldsClassInconsistent)
        assert deserialized.name == "test"

    def test_schema_inconsistent_dict_fields(self):
        fory1 = Fory(xlang=True, compatible=True)
        fory1.register_type(DictFieldsClass)
        buffer = fory1.serialize(DictFieldsClass(name="test", int_dict={"a": 1}, str_dict={"b": "c"}))

        fory2 = Fory(xlang=True, compatible=True)
        fory2.register_type(DictFieldsClassInconsistent)
        deserialized = fory2.deserialize(buffer)

        assert isinstance(deserialized, DictFieldsClassInconsistent)
        assert deserialized.name == "test"
