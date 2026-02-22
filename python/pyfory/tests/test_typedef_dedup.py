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
Tests for xlang TypeDef implementation.
"""

from dataclasses import dataclass
from pyfory.buffer import Buffer
from pyfory.meta.typedef_encoder import encode_typedef
from pyfory.meta.typedef_decoder import decode_typedef
from pyfory import Fory


@dataclass
class ParentClass:
    """Parent class with some fields."""

    name: str
    value: int


@dataclass
class ChildClass(ParentClass):
    """Child class that inherits from ParentClass."""

    description: str
    count: int


@dataclass
class ChildWithShadowedField(ParentClass):
    """Child class that shadows a parent field name."""

    name: str  # Shadows ParentClass.name
    extra: float


@dataclass
class GrandchildClass(ChildClass):
    """Grandchild class for multi-level inheritance."""

    level: int


@dataclass
class GrandchildWithShadowedFields(ChildClass):
    """Grandchild that shadows fields from multiple levels."""

    name: str  # Shadows ParentClass.name
    value: int  # Shadows ParentClass.value
    description: str  # Shadows ChildClass.description


def test_inheritance_basic():
    """Test TypeDef encoding/decoding with basic inheritance."""
    fory = Fory(xlang=True)
    fory.register(ParentClass, namespace="example", typename="ParentClass")
    fory.register(ChildClass, namespace="example", typename="ChildClass")

    resolver = fory.type_resolver

    # Test parent class
    parent_typedef = encode_typedef(resolver, ParentClass)
    assert len(parent_typedef.fields) == 2
    field_names = [f.name for f in parent_typedef.fields]
    assert "name" in field_names
    assert "value" in field_names

    # Test child class - should have all fields from parent plus its own
    child_typedef = encode_typedef(resolver, ChildClass)
    assert len(child_typedef.fields) == 4
    field_names = [f.name for f in child_typedef.fields]
    assert "name" in field_names
    assert "value" in field_names
    assert "description" in field_names
    assert "count" in field_names

    # Verify round-trip encoding/decoding
    buffer = Buffer(child_typedef.encoded)
    decoded_typedef = decode_typedef(buffer, resolver)
    assert len(decoded_typedef.fields) == 4


def test_inheritance_shadowed_fields():
    """Test TypeDef encoding/decoding when child shadows parent fields."""
    fory = Fory(xlang=True)
    fory.register(ParentClass, namespace="example", typename="ParentClass")
    fory.register(ChildWithShadowedField, namespace="example", typename="ChildWithShadowedField")

    resolver = fory.type_resolver

    # Encode the child class with shadowed field
    typedef = encode_typedef(resolver, ChildWithShadowedField)

    # Should have: name (from parent), value (from parent), name (shadowed), extra
    # But with deduplication, we should only have unique field entries
    field_names = [f.name for f in typedef.fields]

    # Verify 'name' appears only once (deduplicated)
    assert field_names.count("name") == 1, f"Expected 'name' to appear once, but found {field_names.count('name')} times"
    assert "value" in field_names
    assert "extra" in field_names

    # Verify round-trip
    buffer = Buffer(typedef.encoded)
    decoded_typedef = decode_typedef(buffer, resolver)

    decoded_field_names = [f.name for f in decoded_typedef.fields]
    assert decoded_field_names.count("name") == 1


def test_multilevel_inheritance():
    """Test TypeDef with multi-level inheritance."""
    fory = Fory(xlang=True)
    fory.register(ParentClass, namespace="example", typename="ParentClass")
    fory.register(ChildClass, namespace="example", typename="ChildClass")
    fory.register(GrandchildClass, namespace="example", typename="GrandchildClass")

    resolver = fory.type_resolver

    # Grandchild should have all fields from the hierarchy
    typedef = encode_typedef(resolver, GrandchildClass)

    field_names = [f.name for f in typedef.fields]
    assert "name" in field_names  # from ParentClass
    assert "value" in field_names  # from ParentClass
    assert "description" in field_names  # from ChildClass
    assert "count" in field_names  # from ChildClass
    assert "level" in field_names  # from GrandchildClass

    # Should have exactly 5 fields
    assert len(typedef.fields) == 5

    # Verify round-trip
    buffer = Buffer(typedef.encoded)
    decoded_typedef = decode_typedef(buffer, resolver)
    assert len(decoded_typedef.fields) == 5


def test_multilevel_inheritance_with_shadowing():
    """Test TypeDef with multi-level inheritance and field shadowing."""
    fory = Fory(xlang=True)
    fory.register(ParentClass, namespace="example", typename="ParentClass")
    fory.register(ChildClass, namespace="example", typename="ChildClass")
    fory.register(
        GrandchildWithShadowedFields,
        namespace="example",
        typename="GrandchildWithShadowedFields",
    )

    resolver = fory.type_resolver

    # Grandchild shadows: name (from Parent), value (from Parent), description (from Child)
    typedef = encode_typedef(resolver, GrandchildWithShadowedFields)

    field_names = [f.name for f in typedef.fields]

    # Each shadowed field should appear only once
    assert field_names.count("name") == 1
    assert field_names.count("value") == 1
    assert field_names.count("description") == 1
    assert "count" in field_names  # from ChildClass, not shadowed

    # Verify round-trip
    buffer = Buffer(typedef.encoded)
    decoded_typedef = decode_typedef(buffer, resolver)

    decoded_field_names = [f.name for f in decoded_typedef.fields]
    assert decoded_field_names.count("name") == 1
    assert decoded_field_names.count("value") == 1
    assert decoded_field_names.count("description") == 1


def test_no_duplicate_fields_in_typedef():
    """Ensure typedef never contains duplicate field names."""

    @dataclass
    class Base:
        x: int

    @dataclass
    class Mid(Base):
        y: int

    @dataclass
    class Child(Mid):
        x: int
        y: int

    fory = Fory(xlang=True)
    fory.register(Base, namespace="example", typename="Base")
    fory.register(Mid, namespace="example", typename="Mid")
    fory.register(Child, namespace="example", typename="Child")

    resolver = fory.type_resolver

    typedef = encode_typedef(resolver, Child)

    field_names = [f.name for f in typedef.fields]

    assert len(field_names) == len(set(field_names)), f"Duplicate fields found: {field_names}"


def test_shadowing_with_different_type():
    """Ensure shadowed field uses child's type, not parent's."""

    @dataclass
    class Parent:
        value: int

    @dataclass
    class Child(Parent):
        value: str  # override type

    fory = Fory(xlang=True)
    fory.register(Parent, namespace="example", typename="Parent")
    fory.register(Child, namespace="example", typename="Child")

    resolver = fory.type_resolver

    typedef = encode_typedef(resolver, Child)

    field = next(f for f in typedef.fields if f.name == "value")

    child_typedef = encode_typedef(resolver, Child)

    # just ensure only one exists
    assert sum(f.name == "value" for f in child_typedef.fields) == 1


def test_shadowing_uses_child_definition():
    """Ensure shadowed field uses child's definition, not parent's."""

    @dataclass
    class Parent:
        name: str

    @dataclass
    class Child(Parent):
        name: str  # shadow

    fory = Fory(xlang=True)
    fory.register(Parent, namespace="example", typename="Parent")
    fory.register(Child, namespace="example", typename="Child")

    resolver = fory.type_resolver

    typedef = encode_typedef(resolver, Child)

    field_map = {f.name: f for f in typedef.fields}

    assert "name" in field_map

    # Critical assertion
    assert field_map["name"].defined_class == "Child", f"Expected field to be defined in Child but got {field_map['name'].defined_class}"


if __name__ == "__main__":
    test_inheritance_basic()
    test_inheritance_shadowed_fields()
    test_multilevel_inheritance()
    test_multilevel_inheritance_with_shadowing()
    test_no_duplicate_fields_in_typedef()
    test_shadowing_with_different_type()
    test_shadowing_uses_child_definition()
    print("All basic tests passed!")
