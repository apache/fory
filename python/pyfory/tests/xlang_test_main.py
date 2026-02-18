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
Cross-language xlang tests for Python <-> Java/Rust/Go/etc.

This module is invoked by PythonXlangTest.java and other language xlang tests.
The test cases follow the same pattern as test_cross_language.rs (Rust).
Data file path is passed via DATA_FILE environment variable.
"""

import enum
import logging
import os
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set

import pyfory
from pyfory import Ref
from pyfory.meta.meta_compressor import NoOpMetaCompressor


def debug_print(*params):
    """Print params if debug is needed."""
    if os.environ.get("ENABLE_FORY_DEBUG_OUTPUT") == "1":
        print(*params)


def get_data_file() -> str:
    """Get the data file path from environment variable."""
    return os.environ["DATA_FILE"]


# ============================================================================
# Test Data Classes - Must match XlangTestBase.java definitions
# ============================================================================


class Color(enum.Enum):
    Green = 0
    Red = 1
    Blue = 2
    White = 3


@dataclass
class Item:
    name: str = ""


@dataclass
class SimpleStruct:
    f1: Dict[pyfory.int32, pyfory.float64] = None
    f2: pyfory.int32 = 0
    f3: Item = None
    f4: str = ""
    f5: Color = None
    f6: List[str] = None
    f7: pyfory.int32 = 0
    f8: pyfory.int32 = 0
    last: pyfory.int32 = 0


@dataclass
class VersionCheckStruct:
    f1: pyfory.int32 = 0
    f2: Optional[str] = None
    f3: pyfory.float64 = 0.0


@dataclass
class Dog:
    age: pyfory.int32 = 0
    name: Optional[str] = None


@dataclass
class Cat:
    age: pyfory.int32 = 0
    lives: pyfory.int32 = 0


@dataclass
class AnimalListHolder:
    animals: List[Any] = None


@dataclass
class AnimalMapHolder:
    animal_map: Dict[Optional[str], Any] = None


@dataclass
class MyStruct:
    id: pyfory.int32 = 0


@dataclass
class MyExt:
    id: pyfory.int32 = 0


class MyExtSerializer(pyfory.serializer.Serializer):
    def write(self, buffer, value):
        buffer.write_varint32(value.id)

    def read(self, buffer):
        obj = MyExt()
        obj.id = buffer.read_varint32()
        return obj


@dataclass
class MyWrapper:
    color: Color = None
    my_struct: MyStruct = None
    my_ext: MyExt = None


@dataclass
class EmptyWrapper:
    pass


@dataclass
class EmptyStruct:
    pass


@dataclass
class OneStringFieldStruct:
    f1: Optional[str] = None


@dataclass
class TwoStringFieldStruct:
    f1: str = ""
    f2: str = ""


class TestEnum(enum.Enum):
    VALUE_A = 0
    VALUE_B = 1
    VALUE_C = 2


@dataclass
class OneEnumFieldStruct:
    f1: TestEnum = None


@dataclass
class TwoEnumFieldStruct:
    f1: TestEnum = None
    f2: TestEnum = None


# ============================================================================
# Collection Element Reference Override Test Types
# ============================================================================


@dataclass
class RefOverrideElement:
    id: pyfory.int32 = 0
    name: str = ""


@dataclass
class RefOverrideContainer:
    list_field: List[Ref[RefOverrideElement]] = None
    map_field: Dict[str, Ref[RefOverrideElement]] = None


# ============================================================================
# Nullable Field Test Types
# ============================================================================


@dataclass
class NullableComprehensiveSchemaConsistent:
    """
    Comprehensive struct for testing nullable fields in SCHEMA_CONSISTENT mode.

    Fields are organized as:
    - Base non-nullable fields: byte, short, int, long, float, double, bool, string, list, set, map
    - Nullable fields (first half - boxed numeric types): Integer, Long, Float
    - Nullable fields (second half): Double, Boolean, String, List, Set, Map
    """

    # Base non-nullable primitive fields
    byte_field: pyfory.int8 = 0
    short_field: pyfory.int16 = 0
    int_field: pyfory.int32 = 0
    long_field: pyfory.int64 = 0
    float_field: pyfory.float32 = 0.0
    double_field: pyfory.float64 = 0.0
    bool_field: bool = False

    # Base non-nullable reference fields
    string_field: str = ""
    list_field: List[str] = None
    set_field: Set[str] = None
    map_field: Dict[str, str] = None

    # Nullable fields - first half (boxed types)
    nullable_int: Optional[pyfory.int32] = None
    nullable_long: Optional[pyfory.int64] = None
    nullable_float: Optional[pyfory.float32] = None

    # Nullable fields - second half
    nullable_double: Optional[pyfory.float64] = None
    nullable_bool: Optional[bool] = None
    nullable_string: Optional[str] = None
    nullable_list: Optional[List[str]] = None
    nullable_set: Optional[Set[str]] = None
    nullable_map: Optional[Dict[str, str]] = None


# ============================================================================
# Reference Tracking Test Types
# ============================================================================


@dataclass
class RefInnerSchemaConsistent:
    """Inner struct for reference tracking test (SCHEMA_CONSISTENT mode)."""

    id: pyfory.int32 = 0
    name: str = ""


@dataclass
class RefOuterSchemaConsistent:
    """Outer struct with two fields pointing to the same inner object (SCHEMA_CONSISTENT mode)."""

    inner1: Optional[RefInnerSchemaConsistent] = pyfory.field(default=None, ref=True, nullable=True)
    inner2: Optional[RefInnerSchemaConsistent] = pyfory.field(default=None, ref=True, nullable=True)


@dataclass
class RefInnerCompatible:
    """Inner struct for reference tracking test (COMPATIBLE mode)."""

    id: pyfory.int32 = 0
    name: str = ""


@dataclass
class RefOuterCompatible:
    """Outer struct with two fields pointing to the same inner object (COMPATIBLE mode)."""

    inner1: Optional[RefInnerCompatible] = pyfory.field(default=None, ref=True, nullable=True)
    inner2: Optional[RefInnerCompatible] = pyfory.field(default=None, ref=True, nullable=True)


@dataclass
class NullableComprehensiveCompatible:
    """
    Cross-language schema evolution test struct for COMPATIBLE mode.
    All fields are Optional in Python to properly handle both null and non-null values from Java:
    - Group 1: Non-nullable in Java (always has values)
    - Group 2: Nullable in Java (@ForyField(nullable=true)) - can be null

    Python uses Optional for all fields so it can correctly receive and re-serialize
    values from Java, whether they are null or non-null.
    """

    # Group 1: Nullable in Python (Optional), Non-nullable in Java
    # Primitive fields
    byte_field: Optional[pyfory.int8] = None
    short_field: Optional[pyfory.int16] = None
    int_field: Optional[pyfory.int32] = None
    long_field: Optional[pyfory.int64] = None
    float_field: Optional[pyfory.float32] = None
    double_field: Optional[pyfory.float64] = None
    bool_field: Optional[bool] = None

    # Boxed fields - also nullable in Python
    boxed_int: Optional[pyfory.int32] = None
    boxed_long: Optional[pyfory.int64] = None
    boxed_float: Optional[pyfory.float32] = None
    boxed_double: Optional[pyfory.float64] = None
    boxed_bool: Optional[bool] = None

    # Reference fields - also nullable in Python
    string_field: Optional[str] = None
    list_field: Optional[List[str]] = None
    set_field: Optional[Set[str]] = None
    map_field: Optional[Dict[str, str]] = None

    # Group 2: Also Nullable in Python (must match Java's nullable annotation)
    # When Java sends null for these fields, Python must be able to receive and re-serialize None.
    # Boxed types - use Optional to handle None from Java
    nullable_int1: Optional[pyfory.int32] = None
    nullable_long1: Optional[pyfory.int64] = None
    nullable_float1: Optional[pyfory.float32] = None
    nullable_double1: Optional[pyfory.float64] = None
    nullable_bool1: Optional[bool] = None

    # Reference types - also Optional
    nullable_string2: Optional[str] = None
    nullable_list2: Optional[List[str]] = None
    nullable_set2: Optional[Set[str]] = None
    nullable_map2: Optional[Dict[str, str]] = None


# ============================================================================
# Test Functions - Each function handles read -> verify -> write back
# ============================================================================


def test_string_serializer():
    """Test string serialization with various encodings."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()
    buffer = pyfory.Buffer(data_bytes)

    fory = pyfory.Fory(xlang=True, compatible=True)
    test_strings = [
        # Latin1
        "ab",
        "Rust123",
        "Çüéâäàåçêëèïî",
        # UTF16
        "こんにちは",
        "Привет",
        "𝄞🎵🎶",
        # UTF8
        "Hello, 世界",
    ]
    for expected in test_strings:
        value = fory.deserialize(buffer)
        assert value == expected, f"string mismatch: {value} != {expected}"

    # Write strings back
    new_buffer = pyfory.Buffer.allocate(512)
    for s in test_strings:
        fory.serialize(s, buffer=new_buffer)

    with open(data_file, "wb") as f:
        f.write(new_buffer.get_bytes(0, new_buffer.get_writer_index()))


def test_simple_struct():
    """Test simple struct serialization."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(Color, type_id=101)
    fory.register_type(Item, type_id=102)
    fory.register_type(SimpleStruct, type_id=103)

    expected = SimpleStruct(
        f1={1: 1.0, 2: 2.0},
        f2=39,
        f3=Item(name="item"),
        f4="f4",
        f5=Color.White,
        f6=["f6"],
        f7=40,
        f8=41,
        last=42,
    )

    debug_print(f"Java bytes length: {len(data_bytes)}")
    debug_print(f"Java bytes (first 50): {data_bytes[:50].hex()}")

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    debug_print(f"Python bytes length: {len(new_bytes)}")
    debug_print(f"Python bytes (first 50): {new_bytes[:50].hex()}")
    debug_print(f"Bytes match: {data_bytes == new_bytes}")
    new_value = fory.deserialize(new_bytes)
    assert new_value == expected, f"new_value: {new_value},\n expected: {expected}"

    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_named_simple_struct():
    """Test named simple struct serialization."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(Color, namespace="demo", typename="color")
    fory.register_type(Item, namespace="demo", typename="item")
    fory.register_type(SimpleStruct, namespace="demo", typename="simple_struct")

    expected = SimpleStruct(
        f1={1: 1.0, 2: 2.0},
        f2=39,
        f3=Item(name="item"),
        f4="f4",
        f5=Color.White,
        f6=["f6"],
        f7=40,
        f8=41,
        last=42,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    assert fory.deserialize(new_bytes) == expected

    with open(data_file, "wb") as f:
        f.write(new_bytes)


def _test_skip_custom(fory1, fory2):
    """Helper for skip custom type tests."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    obj = fory1.deserialize(data_bytes)
    assert obj == EmptyWrapper(), f"Expected EmptyWrapper, got {obj}"

    wrapper = MyWrapper(color=Color.White, my_struct=MyStruct(id=42), my_ext=MyExt(id=43))
    new_bytes = fory2.serialize(wrapper)

    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_skip_id_custom():
    """Test skipping custom types registered by ID."""
    fory1 = pyfory.Fory(xlang=True, compatible=True)
    fory1.register_type(MyExt, type_id=103, serializer=MyExtSerializer(fory1, MyExt))
    fory1.register_type(EmptyWrapper, type_id=104)

    fory2 = pyfory.Fory(xlang=True, compatible=True)
    fory2.register_type(Color, type_id=101)
    fory2.register_type(MyStruct, type_id=102)
    fory2.register_type(MyExt, type_id=103, serializer=MyExtSerializer(fory2, MyExt))
    fory2.register_type(MyWrapper, type_id=104)

    _test_skip_custom(fory1, fory2)


def test_skip_name_custom():
    """Test skipping custom types registered by name."""
    fory1 = pyfory.Fory(xlang=True, compatible=True)
    fory1.register_type(MyExt, typename="my_ext", serializer=MyExtSerializer(fory1, MyExt))
    fory1.register_type(EmptyWrapper, typename="my_wrapper")

    fory2 = pyfory.Fory(xlang=True, compatible=True)
    fory2.register_type(Color, typename="color")
    fory2.register_type(MyStruct, typename="my_struct")
    fory2.register_type(MyExt, typename="my_ext", serializer=MyExtSerializer(fory2, MyExt))
    fory2.register_type(MyWrapper, typename="my_wrapper")

    _test_skip_custom(fory1, fory2)


def test_consistent_named():
    """Test consistent mode with named types."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()
    buffer = pyfory.Buffer(data_bytes)

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(Color, typename="color")
    fory.register_type(MyStruct, typename="my_struct")
    fory.register_type(MyExt, typename="my_ext", serializer=MyExtSerializer(fory, MyExt))

    color = Color.White
    my_struct = MyStruct(id=42)
    my_ext = MyExt(id=43)

    for _ in range(3):
        assert fory.deserialize(buffer) == color
    for _ in range(3):
        assert fory.deserialize(buffer) == my_struct
    for _ in range(3):
        assert fory.deserialize(buffer) == my_ext

    new_buffer = pyfory.Buffer.allocate(256)
    for _ in range(3):
        fory.serialize(color, buffer=new_buffer)
    for _ in range(3):
        fory.serialize(my_struct, buffer=new_buffer)
    for _ in range(3):
        fory.serialize(my_ext, buffer=new_buffer)

    with open(data_file, "wb") as f:
        f.write(new_buffer.get_bytes(0, new_buffer.get_writer_index()))


def test_struct_version_check():
    """Test struct version check."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(VersionCheckStruct, type_id=201)

    expected = VersionCheckStruct(f1=10, f2="test", f3=3.2)
    obj = fory.deserialize(data_bytes)
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    assert fory.deserialize(new_bytes) == expected

    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_polymorphic_list():
    """Test polymorphic list serialization."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()
    buffer = pyfory.Buffer(data_bytes)

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(Dog, type_id=302)
    fory.register_type(Cat, type_id=303)
    fory.register_type(AnimalListHolder, type_id=304)

    # Part 1: Read List<Animal> with polymorphic elements
    animals = fory.deserialize(buffer)
    assert len(animals) == 2

    dog = animals[0]
    assert isinstance(dog, Dog)
    assert dog.age == 3
    assert dog.name == "Buddy"

    cat = animals[1]
    assert isinstance(cat, Cat)
    assert cat.age == 5
    assert cat.lives == 9

    # Part 2: Read AnimalListHolder
    holder = fory.deserialize(buffer)
    assert len(holder.animals) == 2

    dog2 = holder.animals[0]
    assert isinstance(dog2, Dog)
    assert dog2.name == "Rex"

    cat2 = holder.animals[1]
    assert isinstance(cat2, Cat)
    assert cat2.lives == 7

    # Write back
    new_buffer = pyfory.Buffer.allocate(256)
    fory.serialize(animals, buffer=new_buffer)
    fory.serialize(holder, buffer=new_buffer)

    with open(data_file, "wb") as f:
        f.write(new_buffer.get_bytes(0, new_buffer.get_writer_index()))


def test_polymorphic_map():
    """Test polymorphic map serialization."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()
    buffer = pyfory.Buffer(data_bytes)

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(Dog, type_id=302)
    fory.register_type(Cat, type_id=303)
    fory.register_type(AnimalMapHolder, type_id=305)

    # Part 1: Read Map<String, Animal> with polymorphic values
    animal_map = fory.deserialize(buffer)
    assert len(animal_map) == 2

    dog1 = animal_map.get("dog1")
    assert isinstance(dog1, Dog)
    assert dog1.name == "Rex"
    assert dog1.age == 2

    cat1 = animal_map.get("cat1")
    assert isinstance(cat1, Cat)
    assert cat1.lives == 9
    assert cat1.age == 4

    # Part 2: Read AnimalMapHolder
    holder = fory.deserialize(buffer)
    assert len(holder.animal_map) == 2

    my_dog = holder.animal_map.get("myDog")
    assert isinstance(my_dog, Dog)
    assert my_dog.name == "Fido"

    my_cat = holder.animal_map.get("myCat")
    assert isinstance(my_cat, Cat)
    assert my_cat.lives == 8

    # Write back
    new_buffer = pyfory.Buffer.allocate(256)
    fory.serialize(animal_map, buffer=new_buffer)
    fory.serialize(holder, buffer=new_buffer)

    with open(data_file, "wb") as f:
        f.write(new_buffer.get_bytes(0, new_buffer.get_writer_index()))


def test_one_string_field_schema():
    """Test one string field struct with schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(OneStringFieldStruct, type_id=200)

    expected = OneStringFieldStruct(f1="hello")
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_one_string_field_compatible():
    """Test one string field struct with compatible mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(OneStringFieldStruct, type_id=200)

    expected = OneStringFieldStruct(f1="hello")
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_two_string_field_compatible():
    """Test two string field struct with compatible mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TwoStringFieldStruct, type_id=201)

    expected = TwoStringFieldStruct(f1="first", f2="second")
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_schema_evolution_compatible():
    """Test schema evolution: deserialize TwoStringFieldStruct as EmptyStruct."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Deserialize TwoStringFieldStruct as EmptyStruct (should skip all fields)
    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(EmptyStruct, type_id=200)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized as EmptyStruct: {obj}")
    assert isinstance(obj, EmptyStruct), f"Expected EmptyStruct, got {type(obj)}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_schema_evolution_compatible_reverse():
    """Test schema evolution: deserialize OneStringFieldStruct as TwoStringFieldStruct."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Deserialize OneStringFieldStruct as TwoStringFieldStruct
    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TwoStringFieldStruct, type_id=200)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized as TwoStringFieldStruct: {obj}")
    assert isinstance(obj, TwoStringFieldStruct), f"Expected TwoStringFieldStruct, got {type(obj)}"
    assert obj.f1 == "only_one", f"Expected f1='only_one', got f1='{obj.f1}'"
    # f2 should be None (missing field)
    assert obj.f2 is None or obj.f2 == "", f"Expected f2=None or empty, got f2='{obj.f2}'"

    # Set f2 to empty string for serialization (match Go behavior)
    if obj.f2 is None:
        obj.f2 = ""

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_one_enum_field_schema():
    """Test one enum field struct with schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(TestEnum, type_id=210)
    fory.register_type(OneEnumFieldStruct, type_id=211)

    expected = OneEnumFieldStruct(f1=TestEnum.VALUE_B)
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_one_enum_field_compatible():
    """Test one enum field struct with compatible mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TestEnum, type_id=210)
    fory.register_type(OneEnumFieldStruct, type_id=211)

    expected = OneEnumFieldStruct(f1=TestEnum.VALUE_A)
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_two_enum_field_compatible():
    """Test two enum field struct with compatible mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TestEnum, type_id=210)
    fory.register_type(TwoEnumFieldStruct, type_id=212)

    expected = TwoEnumFieldStruct(f1=TestEnum.VALUE_A, f2=TestEnum.VALUE_C)
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")
    assert obj == expected, f"Mismatch: {obj} != {expected}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_enum_schema_evolution_compatible():
    """Test enum schema evolution: deserialize TwoEnumFieldStruct as EmptyStruct."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Deserialize TwoEnumFieldStruct as EmptyStruct (should skip all fields)
    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TestEnum, type_id=210)
    fory.register_type(EmptyStruct, type_id=211)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized as EmptyStruct: {obj}")
    assert isinstance(obj, EmptyStruct), f"Expected EmptyStruct, got {type(obj)}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_enum_schema_evolution_compatible_reverse():
    """Test enum schema evolution: deserialize OneEnumFieldStruct as TwoEnumFieldStruct."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Deserialize OneEnumFieldStruct as TwoEnumFieldStruct
    fory = pyfory.Fory(xlang=True, compatible=True)
    fory.register_type(TestEnum, type_id=210)
    fory.register_type(TwoEnumFieldStruct, type_id=211)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized as TwoEnumFieldStruct: {obj}")
    assert isinstance(obj, TwoEnumFieldStruct), f"Expected TwoEnumFieldStruct, got {type(obj)}"
    assert obj.f1 == TestEnum.VALUE_C, f"Expected f1=VALUE_C, got f1={obj.f1}"
    # f2 should be None (missing field due to schema evolution)
    f2_value = getattr(obj, "f2", None)
    assert f2_value is None, f"Expected f2=None, got f2={f2_value}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


# ============================================================================
# Nullable Field Tests
# ============================================================================


def test_nullable_field_schema_consistent_not_null():
    """Test nullable fields with non-null values in schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(NullableComprehensiveSchemaConsistent, type_id=401)

    expected = NullableComprehensiveSchemaConsistent(
        # Base non-nullable primitive fields
        byte_field=1,
        short_field=2,
        int_field=42,
        long_field=123456789,
        float_field=1.5,
        double_field=2.5,
        bool_field=True,
        # Base non-nullable reference fields
        string_field="hello",
        list_field=["a", "b", "c"],
        set_field={"x", "y"},
        map_field={"key1": "value1", "key2": "value2"},
        # Nullable fields - first half (boxed types) - all have values
        nullable_int=100,
        nullable_long=200,
        nullable_float=1.5,
        # Nullable fields - second half - all have values
        nullable_double=2.5,
        nullable_bool=False,
        nullable_string="nullable_value",
        nullable_list=["p", "q"],
        nullable_set={"m", "n"},
        nullable_map={"nk1": "nv1"},
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify base primitive fields
    assert obj.byte_field == expected.byte_field, f"byte_field: {obj.byte_field} != {expected.byte_field}"
    assert obj.short_field == expected.short_field, f"short_field: {obj.short_field} != {expected.short_field}"
    assert obj.int_field == expected.int_field, f"int_field: {obj.int_field} != {expected.int_field}"
    assert obj.long_field == expected.long_field, f"long_field: {obj.long_field} != {expected.long_field}"
    assert abs(obj.float_field - expected.float_field) < 0.01, f"float_field: {obj.float_field} != {expected.float_field}"
    assert abs(obj.double_field - expected.double_field) < 0.000001, f"double_field: {obj.double_field} != {expected.double_field}"
    assert obj.bool_field == expected.bool_field, f"bool_field: {obj.bool_field} != {expected.bool_field}"

    # Verify base reference fields
    assert obj.string_field == expected.string_field, f"string_field: {obj.string_field} != {expected.string_field}"
    assert obj.list_field == expected.list_field, f"list_field: {obj.list_field} != {expected.list_field}"
    assert obj.set_field == expected.set_field, f"set_field: {obj.set_field} != {expected.set_field}"
    assert obj.map_field == expected.map_field, f"map_field: {obj.map_field} != {expected.map_field}"

    # Verify nullable fields - first half (boxed types)
    assert obj.nullable_int == expected.nullable_int, f"nullable_int: {obj.nullable_int} != {expected.nullable_int}"
    assert obj.nullable_long == expected.nullable_long, f"nullable_long: {obj.nullable_long} != {expected.nullable_long}"
    assert abs(obj.nullable_float - expected.nullable_float) < 0.01, f"nullable_float: {obj.nullable_float} != {expected.nullable_float}"

    # Verify nullable fields - second half
    assert abs(obj.nullable_double - expected.nullable_double) < 0.01, f"nullable_double: {obj.nullable_double} != {expected.nullable_double}"
    assert obj.nullable_bool == expected.nullable_bool, f"nullable_bool: {obj.nullable_bool} != {expected.nullable_bool}"
    assert obj.nullable_string == expected.nullable_string, f"nullable_string: {obj.nullable_string} != {expected.nullable_string}"
    assert obj.nullable_list == expected.nullable_list, f"nullable_list: {obj.nullable_list} != {expected.nullable_list}"
    assert obj.nullable_set == expected.nullable_set, f"nullable_set: {obj.nullable_set} != {expected.nullable_set}"
    assert obj.nullable_map == expected.nullable_map, f"nullable_map: {obj.nullable_map} != {expected.nullable_map}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_nullable_field_schema_consistent_null():
    """Test nullable fields with null values in schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(NullableComprehensiveSchemaConsistent, type_id=401)

    expected = NullableComprehensiveSchemaConsistent(
        # Base non-nullable primitive fields - must have values
        byte_field=1,
        short_field=2,
        int_field=42,
        long_field=123456789,
        float_field=1.5,
        double_field=2.5,
        bool_field=True,
        # Base non-nullable reference fields - must have values
        string_field="hello",
        list_field=["a", "b", "c"],
        set_field={"x", "y"},
        map_field={"key1": "value1", "key2": "value2"},
        # Nullable fields - first half (boxed types) - all null
        nullable_int=None,
        nullable_long=None,
        nullable_float=None,
        # Nullable fields - second half - all null
        nullable_double=None,
        nullable_bool=None,
        nullable_string=None,
        nullable_list=None,
        nullable_set=None,
        nullable_map=None,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify base primitive fields
    assert obj.byte_field == expected.byte_field, f"byte_field: {obj.byte_field} != {expected.byte_field}"
    assert obj.short_field == expected.short_field, f"short_field: {obj.short_field} != {expected.short_field}"
    assert obj.int_field == expected.int_field, f"int_field: {obj.int_field} != {expected.int_field}"
    assert obj.long_field == expected.long_field, f"long_field: {obj.long_field} != {expected.long_field}"
    assert abs(obj.float_field - expected.float_field) < 0.01, f"float_field: {obj.float_field} != {expected.float_field}"
    assert abs(obj.double_field - expected.double_field) < 0.000001, f"double_field: {obj.double_field} != {expected.double_field}"
    assert obj.bool_field == expected.bool_field, f"bool_field: {obj.bool_field} != {expected.bool_field}"

    # Verify base reference fields
    assert obj.string_field == expected.string_field, f"string_field: {obj.string_field} != {expected.string_field}"
    assert obj.list_field == expected.list_field, f"list_field: {obj.list_field} != {expected.list_field}"
    assert obj.set_field == expected.set_field, f"set_field: {obj.set_field} != {expected.set_field}"
    assert obj.map_field == expected.map_field, f"map_field: {obj.map_field} != {expected.map_field}"

    # Verify nullable fields - first half (boxed types) - all null
    assert obj.nullable_int is None, f"nullable_int: {obj.nullable_int} != None"
    assert obj.nullable_long is None, f"nullable_long: {obj.nullable_long} != None"
    assert obj.nullable_float is None, f"nullable_float: {obj.nullable_float} != None"

    # Verify nullable fields - second half - all null
    assert obj.nullable_double is None, f"nullable_double: {obj.nullable_double} != None"
    assert obj.nullable_bool is None, f"nullable_bool: {obj.nullable_bool} != None"
    assert obj.nullable_string is None, f"nullable_string: {obj.nullable_string} != None"
    assert obj.nullable_list is None, f"nullable_list: {obj.nullable_list} != None"
    assert obj.nullable_set is None, f"nullable_set: {obj.nullable_set} != None"
    assert obj.nullable_map is None, f"nullable_map: {obj.nullable_map} != None"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_nullable_field_compatible_not_null():
    """
    Test cross-language schema evolution - all fields have values.
    Java sends: Group 1 (non-nullable) + Group 2 (nullable with values)
    Python reads: Group 1 (nullable/Optional) + Group 2 (non-nullable)
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Use NoOpMetaCompressor to match Java's test configuration
    fory = pyfory.Fory(xlang=True, compatible=True, meta_compressor=NoOpMetaCompressor())
    fory.register_type(NullableComprehensiveCompatible, type_id=402)

    expected = NullableComprehensiveCompatible(
        # Group 1: Nullable in Python (read from Java's non-nullable)
        byte_field=1,
        short_field=2,
        int_field=42,
        long_field=123456789,
        float_field=1.5,
        double_field=2.5,
        bool_field=True,
        boxed_int=10,
        boxed_long=20,
        boxed_float=1.1,
        boxed_double=2.2,
        boxed_bool=True,
        string_field="hello",
        list_field=["a", "b", "c"],
        set_field={"x", "y"},
        map_field={"key1": "value1", "key2": "value2"},
        # Group 2: Non-nullable in Python (read from Java's nullable with values)
        nullable_int1=100,
        nullable_long1=200,
        nullable_float1=1.5,
        nullable_double1=2.5,
        nullable_bool1=False,
        nullable_string2="nullable_value",
        nullable_list2=["p", "q"],
        nullable_set2={"m", "n"},
        nullable_map2={"nk1": "nv1"},
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify Group 1: Nullable in Python (read from Java's non-nullable)
    assert obj.byte_field == expected.byte_field, f"byte_field: {obj.byte_field} != {expected.byte_field}"
    assert obj.short_field == expected.short_field, f"short_field: {obj.short_field} != {expected.short_field}"
    assert obj.int_field == expected.int_field, f"int_field: {obj.int_field} != {expected.int_field}"
    assert obj.long_field == expected.long_field, f"long_field: {obj.long_field} != {expected.long_field}"
    assert abs(obj.float_field - expected.float_field) < 0.01, f"float_field: {obj.float_field} != {expected.float_field}"
    assert abs(obj.double_field - expected.double_field) < 0.000001, f"double_field: {obj.double_field} != {expected.double_field}"
    assert obj.bool_field == expected.bool_field, f"bool_field: {obj.bool_field} != {expected.bool_field}"

    assert obj.boxed_int == expected.boxed_int, f"boxed_int: {obj.boxed_int} != {expected.boxed_int}"
    assert obj.boxed_long == expected.boxed_long, f"boxed_long: {obj.boxed_long} != {expected.boxed_long}"
    assert abs(obj.boxed_float - expected.boxed_float) < 0.01, f"boxed_float: {obj.boxed_float} != {expected.boxed_float}"
    assert abs(obj.boxed_double - expected.boxed_double) < 0.01, f"boxed_double: {obj.boxed_double} != {expected.boxed_double}"
    assert obj.boxed_bool == expected.boxed_bool, f"boxed_bool: {obj.boxed_bool} != {expected.boxed_bool}"

    assert obj.string_field == expected.string_field, f"string_field: {obj.string_field} != {expected.string_field}"
    assert obj.list_field == expected.list_field, f"list_field: {obj.list_field} != {expected.list_field}"
    assert obj.set_field == expected.set_field, f"set_field: {obj.set_field} != {expected.set_field}"
    assert obj.map_field == expected.map_field, f"map_field: {obj.map_field} != {expected.map_field}"

    # Verify Group 2: Non-nullable in Python (read from Java's nullable with values)
    assert obj.nullable_int1 == expected.nullable_int1, f"nullable_int1: {obj.nullable_int1} != {expected.nullable_int1}"
    assert obj.nullable_long1 == expected.nullable_long1, f"nullable_long1: {obj.nullable_long1} != {expected.nullable_long1}"
    assert abs(obj.nullable_float1 - expected.nullable_float1) < 0.01, f"nullable_float1: {obj.nullable_float1} != {expected.nullable_float1}"
    assert abs(obj.nullable_double1 - expected.nullable_double1) < 0.01, f"nullable_double1: {obj.nullable_double1} != {expected.nullable_double1}"
    assert obj.nullable_bool1 == expected.nullable_bool1, f"nullable_bool1: {obj.nullable_bool1} != {expected.nullable_bool1}"

    assert obj.nullable_string2 == expected.nullable_string2, f"nullable_string2: {obj.nullable_string2} != {expected.nullable_string2}"
    assert obj.nullable_list2 == expected.nullable_list2, f"nullable_list2: {obj.nullable_list2} != {expected.nullable_list2}"
    assert obj.nullable_set2 == expected.nullable_set2, f"nullable_set2: {obj.nullable_set2} != {expected.nullable_set2}"
    assert obj.nullable_map2 == expected.nullable_map2, f"nullable_map2: {obj.nullable_map2} != {expected.nullable_map2}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_nullable_field_compatible_null():
    """
    Test cross-language schema evolution - nullable fields are null.
    Java sends: Group 1 (non-nullable with values) + Group 2 (nullable with null)
    Python reads: Group 1 (nullable/Optional) + Group 2 (non-nullable -> defaults)

    When Java sends null for Group 2 fields, Python's non-nullable fields receive
    default values (0 for numbers, False for bool, empty/None for collections/strings).
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    # Use NoOpMetaCompressor to match Java's test configuration
    fory = pyfory.Fory(xlang=True, compatible=True, meta_compressor=NoOpMetaCompressor())
    fory.register_type(NullableComprehensiveCompatible, type_id=402)

    expected = NullableComprehensiveCompatible(
        # Group 1: Nullable in Python (read from Java's non-nullable)
        byte_field=1,
        short_field=2,
        int_field=42,
        long_field=123456789,
        float_field=1.5,
        double_field=2.5,
        bool_field=True,
        boxed_int=10,
        boxed_long=20,
        boxed_float=1.1,
        boxed_double=2.2,
        boxed_bool=True,
        string_field="hello",
        list_field=["a", "b", "c"],
        set_field={"x", "y"},
        map_field={"key1": "value1", "key2": "value2"},
        # Group 2: Java sends null, Python receives null (like C++)
        # Python properly preserves null values from the wire format
        nullable_int1=None,
        nullable_long1=None,
        nullable_float1=None,
        nullable_double1=None,
        nullable_bool1=None,
        nullable_string2=None,
        nullable_list2=None,
        nullable_set2=None,
        nullable_map2=None,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify Group 1: Nullable in Python (read from Java's non-nullable)
    assert obj.byte_field == expected.byte_field, f"byte_field: {obj.byte_field} != {expected.byte_field}"
    assert obj.short_field == expected.short_field, f"short_field: {obj.short_field} != {expected.short_field}"
    assert obj.int_field == expected.int_field, f"int_field: {obj.int_field} != {expected.int_field}"
    assert obj.long_field == expected.long_field, f"long_field: {obj.long_field} != {expected.long_field}"
    assert abs(obj.float_field - expected.float_field) < 0.01, f"float_field: {obj.float_field} != {expected.float_field}"
    assert abs(obj.double_field - expected.double_field) < 0.000001, f"double_field: {obj.double_field} != {expected.double_field}"
    assert obj.bool_field == expected.bool_field, f"bool_field: {obj.bool_field} != {expected.bool_field}"

    assert obj.boxed_int == expected.boxed_int, f"boxed_int: {obj.boxed_int} != {expected.boxed_int}"
    assert obj.boxed_long == expected.boxed_long, f"boxed_long: {obj.boxed_long} != {expected.boxed_long}"
    assert abs(obj.boxed_float - expected.boxed_float) < 0.01, f"boxed_float: {obj.boxed_float} != {expected.boxed_float}"
    assert abs(obj.boxed_double - expected.boxed_double) < 0.01, f"boxed_double: {obj.boxed_double} != {expected.boxed_double}"
    assert obj.boxed_bool == expected.boxed_bool, f"boxed_bool: {obj.boxed_bool} != {expected.boxed_bool}"

    assert obj.string_field == expected.string_field, f"string_field: {obj.string_field} != {expected.string_field}"
    assert obj.list_field == expected.list_field, f"list_field: {obj.list_field} != {expected.list_field}"
    assert obj.set_field == expected.set_field, f"set_field: {obj.set_field} != {expected.set_field}"
    assert obj.map_field == expected.map_field, f"map_field: {obj.map_field} != {expected.map_field}"

    # Verify Group 2: Java sent null, Python receives null (like C++ with std::optional)
    assert obj.nullable_int1 is None, f"nullable_int1: {obj.nullable_int1} != None"
    assert obj.nullable_long1 is None, f"nullable_long1: {obj.nullable_long1} != None"
    assert obj.nullable_float1 is None, f"nullable_float1: {obj.nullable_float1} != None"
    assert obj.nullable_double1 is None, f"nullable_double1: {obj.nullable_double1} != None"
    assert obj.nullable_bool1 is None, f"nullable_bool1: {obj.nullable_bool1} != None"
    assert obj.nullable_string2 is None, f"nullable_string2: {obj.nullable_string2} != None"
    assert obj.nullable_list2 is None, f"nullable_list2: {obj.nullable_list2} != None"
    assert obj.nullable_set2 is None, f"nullable_set2: {obj.nullable_set2} != None"
    assert obj.nullable_map2 is None, f"nullable_map2: {obj.nullable_map2} != None"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


# ============================================================================
# Reference Tracking Tests
# ============================================================================


def test_ref_schema_consistent():
    """
    Test cross-language reference tracking in SCHEMA_CONSISTENT mode (compatible=false).

    This test verifies that when Java serializes an object where two fields point to
    the same instance, Python can properly deserialize it and both fields will reference
    the same object. When re-serializing, the reference relationship should be preserved.
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(RefInnerSchemaConsistent, type_id=501)
    fory.register_type(RefOuterSchemaConsistent, type_id=502)

    outer = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {outer}")

    # Both inner1 and inner2 should have values
    assert outer.inner1 is not None, "inner1 should not be None"
    assert outer.inner2 is not None, "inner2 should not be None"

    # Both should have the same values (they reference the same object in Java)
    assert outer.inner1.id == 42, f"inner1.id should be 42, got {outer.inner1.id}"
    assert outer.inner1.name == "shared_inner", f"inner1.name should be 'shared_inner', got {outer.inner1.name}"
    assert outer.inner1 == outer.inner2, "inner1 and inner2 should be equal (same reference)"

    # In Python, after deserialization with reference tracking, inner1 and inner2
    # should point to the same object (identity check)
    assert outer.inner1 is outer.inner2, "inner1 and inner2 should be the same object (reference identity)"

    # Re-serialize and write back
    new_bytes = fory.serialize(outer)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_ref_compatible():
    """
    Test cross-language reference tracking in COMPATIBLE mode (compatible=true).

    This test verifies reference tracking works correctly with schema evolution support.
    The inner object is shared between two fields, and this relationship should be
    preserved through serialization/deserialization.
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)
    fory.register_type(RefInnerCompatible, type_id=503)
    fory.register_type(RefOuterCompatible, type_id=504)

    outer = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {outer}")

    # Both inner1 and inner2 should have values
    assert outer.inner1 is not None, "inner1 should not be None"
    assert outer.inner2 is not None, "inner2 should not be None"

    # Both should have the same values (they reference the same object in Java)
    assert outer.inner1.id == 99, f"inner1.id should be 99, got {outer.inner1.id}"
    assert outer.inner1.name == "compatible_shared", f"inner1.name should be 'compatible_shared', got {outer.inner1.name}"
    assert outer.inner1 == outer.inner2, "inner1 and inner2 should be equal (same reference)"

    # In Python, after deserialization with reference tracking, inner1 and inner2
    # should point to the same object (identity check)
    assert outer.inner1 is outer.inner2, "inner1 and inner2 should be the same object (reference identity)"

    # Re-serialize and write back
    new_bytes = fory.serialize(outer)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_collection_element_ref_override():
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(RefOverrideElement, type_id=701)
    fory.register_type(RefOverrideContainer, type_id=702)

    outer = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {outer}")

    assert outer.list_field, "list_field should not be empty"
    shared = outer.list_field[0]

    new_outer = RefOverrideContainer(
        list_field=[shared, shared],
        map_field={"k1": shared, "k2": shared},
    )

    new_bytes = fory.serialize(new_outer)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


# ============================================================================
# Circular Reference Test Types
# ============================================================================


@dataclass
class CircularRefStruct:
    """
    Struct for circular reference tests.
    Contains a self-referencing field and a name field.
    The 'self_ref' field points back to the same object, creating a circular reference.
    Matches Java CircularRefStruct (type id 601 for schema consistent, 602 for compatible)
    """

    name: str = ""
    self_ref: Optional["CircularRefStruct"] = pyfory.field(default=None, ref=True, nullable=True)


# ============================================================================
# Circular Reference Tests
# ============================================================================


def test_circular_ref_schema_consistent():
    """
    Test circular reference in SCHEMA_CONSISTENT mode (compatible=false).

    Creates a struct where the 'self_ref' field points back to the same object.
    Verifies that after serialization/deserialization across languages,
    the circular reference is preserved.
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(CircularRefStruct, type_id=601)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify the struct has the expected name
    assert obj.name == "circular_test", f"name should be 'circular_test', got {obj.name}"

    # Verify circular reference is preserved (self_ref points to itself)
    assert obj.self_ref is not None, "self_ref should not be None"
    assert obj.self_ref is obj, "self_ref should point to the same object (circular reference)"
    debug_print("Circular reference verified: obj.self_ref is obj")

    # Re-serialize and write back
    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_circular_ref_compatible():
    """
    Test circular reference in COMPATIBLE mode (compatible=true).

    Creates a struct where the 'self_ref' field points back to the same object.
    Verifies that circular references work with schema evolution support.
    """
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)
    fory.register_type(CircularRefStruct, type_id=602)

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify the struct has the expected name
    assert obj.name == "compatible_circular", f"name should be 'compatible_circular', got {obj.name}"

    # Verify circular reference is preserved (self_ref points to itself)
    assert obj.self_ref is not None, "self_ref should not be None"
    assert obj.self_ref is obj, "self_ref should point to the same object (circular reference)"
    debug_print("Circular reference verified: obj.self_ref is obj")

    # Re-serialize and write back
    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


# ============================================================================
# Unsigned Number Test Types
# ============================================================================


@dataclass
class UnsignedSchemaConsistent:
    """
    Test struct for unsigned number schema consistent tests (Python side).
    Primitive fields first, then nullable boxed fields (using Optional).

    Must match Java UnsignedSchemaConsistent (type id 501).
    """

    # Primitive unsigned fields (non-nullable)
    u8_field: pyfory.uint8 = 0
    u16_field: pyfory.uint16 = 0
    u32_var_field: pyfory.uint32 = 0  # VAR_UINT32 encoding
    u32_fixed_field: pyfory.fixed_uint32 = 0  # Fixed 4-byte encoding
    u64_var_field: pyfory.uint64 = 0  # VAR_UINT64 encoding
    u64_fixed_field: pyfory.fixed_uint64 = 0  # Fixed 8-byte encoding
    u64_tagged_field: pyfory.tagged_uint64 = 0  # Tagged encoding

    # Boxed nullable unsigned fields (using Optional)
    u8_nullable_field: Optional[pyfory.uint8] = None
    u16_nullable_field: Optional[pyfory.uint16] = None
    u32_var_nullable_field: Optional[pyfory.uint32] = None
    u32_fixed_nullable_field: Optional[pyfory.fixed_uint32] = None
    u64_var_nullable_field: Optional[pyfory.uint64] = None
    u64_fixed_nullable_field: Optional[pyfory.fixed_uint64] = None
    u64_tagged_nullable_field: Optional[pyfory.tagged_uint64] = None


@dataclass
class UnsignedSchemaCompatible:
    """
    Test struct for unsigned number schema compatible tests (Python side).
    Group 1: Optional fields (nullable in Python, non-nullable in Java).
    Group 2: Non-Optional fields with field2 suffix (non-nullable in Python, nullable in Java).

    Must match Java UnsignedSchemaCompatible (type id 502).
    """

    # Group 1: Optional unsigned fields (nullable in Python, non-nullable in Java)
    u8_field1: Optional[pyfory.uint8] = None
    u16_field1: Optional[pyfory.uint16] = None
    u32_var_field1: Optional[pyfory.uint32] = None  # VAR_UINT32 encoding
    u32_fixed_field1: Optional[pyfory.fixed_uint32] = None  # Fixed 4-byte encoding
    u64_var_field1: Optional[pyfory.uint64] = None  # VAR_UINT64 encoding
    u64_fixed_field1: Optional[pyfory.fixed_uint64] = None  # Fixed 8-byte encoding
    u64_tagged_field1: Optional[pyfory.tagged_uint64] = None  # Tagged encoding

    # Group 2: Non-Optional unsigned fields (non-nullable in Python, nullable in Java)
    u8_field2: pyfory.uint8 = 0
    u16_field2: pyfory.uint16 = 0
    u32_var_field2: pyfory.uint32 = 0
    u32_fixed_field2: pyfory.fixed_uint32 = 0
    u64_var_field2: pyfory.uint64 = 0
    u64_fixed_field2: pyfory.fixed_uint64 = 0
    u64_tagged_field2: pyfory.tagged_uint64 = 0


@dataclass
class UnsignedSchemaConsistentSimple:
    """
    Simple test struct for tagged uint64 in schema consistent mode.
    Must match Java UnsignedSchemaConsistentSimple (type id 1).
    """

    u64_tagged: pyfory.tagged_uint64 = 0
    u64_tagged_nullable: Optional[pyfory.tagged_uint64] = None


# ============================================================================
# Unsigned Number Tests
# ============================================================================


def test_unsigned_schema_consistent_simple():
    """Test simple tagged uint64 in schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(UnsignedSchemaConsistentSimple, type_id=1)

    expected = UnsignedSchemaConsistentSimple(
        u64_tagged=1000000000,
        u64_tagged_nullable=500000000,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    assert obj.u64_tagged == expected.u64_tagged, f"u64_tagged: {obj.u64_tagged} != {expected.u64_tagged}"
    assert obj.u64_tagged_nullable == expected.u64_tagged_nullable, (
        f"u64_tagged_nullable: {obj.u64_tagged_nullable} != {expected.u64_tagged_nullable}"
    )

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_unsigned_schema_consistent():
    """Test unsigned number types with schema consistent mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=False)
    fory.register_type(UnsignedSchemaConsistent, type_id=501)

    expected = UnsignedSchemaConsistent(
        # Primitive fields
        u8_field=200,
        u16_field=60000,
        u32_var_field=3000000000,
        u32_fixed_field=4000000000,
        u64_var_field=10000000000,
        u64_fixed_field=15000000000,
        u64_tagged_field=1000000000,
        # Nullable boxed fields with values
        u8_nullable_field=128,
        u16_nullable_field=40000,
        u32_var_nullable_field=2500000000,
        u32_fixed_nullable_field=3500000000,
        u64_var_nullable_field=8000000000,
        u64_fixed_nullable_field=12000000000,
        u64_tagged_nullable_field=500000000,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify primitive unsigned fields
    assert obj.u8_field == expected.u8_field, f"u8_field: {obj.u8_field} != {expected.u8_field}"
    assert obj.u16_field == expected.u16_field, f"u16_field: {obj.u16_field} != {expected.u16_field}"
    assert obj.u32_var_field == expected.u32_var_field, f"u32_var_field: {obj.u32_var_field} != {expected.u32_var_field}"
    assert obj.u32_fixed_field == expected.u32_fixed_field, f"u32_fixed_field: {obj.u32_fixed_field} != {expected.u32_fixed_field}"
    assert obj.u64_var_field == expected.u64_var_field, f"u64_var_field: {obj.u64_var_field} != {expected.u64_var_field}"
    assert obj.u64_fixed_field == expected.u64_fixed_field, f"u64_fixed_field: {obj.u64_fixed_field} != {expected.u64_fixed_field}"
    assert obj.u64_tagged_field == expected.u64_tagged_field, f"u64_tagged_field: {obj.u64_tagged_field} != {expected.u64_tagged_field}"

    # Verify nullable boxed fields
    assert obj.u8_nullable_field == expected.u8_nullable_field, f"u8_nullable_field: {obj.u8_nullable_field} != {expected.u8_nullable_field}"
    assert obj.u16_nullable_field == expected.u16_nullable_field, f"u16_nullable_field: {obj.u16_nullable_field} != {expected.u16_nullable_field}"
    assert obj.u32_var_nullable_field == expected.u32_var_nullable_field, (
        f"u32_var_nullable_field: {obj.u32_var_nullable_field} != {expected.u32_var_nullable_field}"
    )
    assert obj.u32_fixed_nullable_field == expected.u32_fixed_nullable_field, (
        f"u32_fixed_nullable_field: {obj.u32_fixed_nullable_field} != {expected.u32_fixed_nullable_field}"
    )
    assert obj.u64_var_nullable_field == expected.u64_var_nullable_field, (
        f"u64_var_nullable_field: {obj.u64_var_nullable_field} != {expected.u64_var_nullable_field}"
    )
    assert obj.u64_fixed_nullable_field == expected.u64_fixed_nullable_field, (
        f"u64_fixed_nullable_field: {obj.u64_fixed_nullable_field} != {expected.u64_fixed_nullable_field}"
    )
    assert obj.u64_tagged_nullable_field == expected.u64_tagged_nullable_field, (
        f"u64_tagged_nullable_field: {obj.u64_tagged_nullable_field} != {expected.u64_tagged_nullable_field}"
    )

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


def test_unsigned_schema_compatible():
    """Test unsigned number types with schema compatible mode."""
    data_file = get_data_file()
    with open(data_file, "rb") as f:
        data_bytes = f.read()

    fory = pyfory.Fory(xlang=True, compatible=True, meta_compressor=NoOpMetaCompressor())
    fory.register_type(UnsignedSchemaCompatible, type_id=502)

    expected = UnsignedSchemaCompatible(
        # Group 1: Optional fields (values from Java's non-nullable fields)
        u8_field1=200,
        u16_field1=60000,
        u32_var_field1=3000000000,
        u32_fixed_field1=4000000000,
        u64_var_field1=10000000000,
        u64_fixed_field1=15000000000,
        u64_tagged_field1=1000000000,
        # Group 2: Non-Optional fields (values from Java's nullable fields)
        u8_field2=128,
        u16_field2=40000,
        u32_var_field2=2500000000,
        u32_fixed_field2=3500000000,
        u64_var_field2=8000000000,
        u64_fixed_field2=12000000000,
        u64_tagged_field2=500000000,
    )

    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized: {obj}")

    # Verify Group 1: Optional unsigned fields
    assert obj.u8_field1 == expected.u8_field1, f"u8_field1: {obj.u8_field1} != {expected.u8_field1}"
    assert obj.u16_field1 == expected.u16_field1, f"u16_field1: {obj.u16_field1} != {expected.u16_field1}"
    assert obj.u32_var_field1 == expected.u32_var_field1, f"u32_var_field1: {obj.u32_var_field1} != {expected.u32_var_field1}"
    assert obj.u32_fixed_field1 == expected.u32_fixed_field1, f"u32_fixed_field1: {obj.u32_fixed_field1} != {expected.u32_fixed_field1}"
    assert obj.u64_var_field1 == expected.u64_var_field1, f"u64_var_field1: {obj.u64_var_field1} != {expected.u64_var_field1}"
    assert obj.u64_fixed_field1 == expected.u64_fixed_field1, f"u64_fixed_field1: {obj.u64_fixed_field1} != {expected.u64_fixed_field1}"
    assert obj.u64_tagged_field1 == expected.u64_tagged_field1, f"u64_tagged_field1: {obj.u64_tagged_field1} != {expected.u64_tagged_field1}"

    # Verify Group 2: Non-Optional fields
    assert obj.u8_field2 == expected.u8_field2, f"u8_field2: {obj.u8_field2} != {expected.u8_field2}"
    assert obj.u16_field2 == expected.u16_field2, f"u16_field2: {obj.u16_field2} != {expected.u16_field2}"
    assert obj.u32_var_field2 == expected.u32_var_field2, f"u32_var_field2: {obj.u32_var_field2} != {expected.u32_var_field2}"
    assert obj.u32_fixed_field2 == expected.u32_fixed_field2, f"u32_fixed_field2: {obj.u32_fixed_field2} != {expected.u32_fixed_field2}"
    assert obj.u64_var_field2 == expected.u64_var_field2, f"u64_var_field2: {obj.u64_var_field2} != {expected.u64_var_field2}"
    assert obj.u64_fixed_field2 == expected.u64_fixed_field2, f"u64_fixed_field2: {obj.u64_fixed_field2} != {expected.u64_fixed_field2}"
    assert obj.u64_tagged_field2 == expected.u64_tagged_field2, f"u64_tagged_field2: {obj.u64_tagged_field2} != {expected.u64_tagged_field2}"

    new_bytes = fory.serialize(obj)
    with open(data_file, "wb") as f:
        f.write(new_bytes)


if __name__ == "__main__":
    """
    This file is executed by PythonXlangTest.java and other cross-language tests.
    The test case name is passed as the first argument.
    """
    import sys

    print(f"Execute {sys.argv}")
    try:
        args = sys.argv[1:]
        assert len(args) > 0, "Test case name required"
        test_name = args[0]
        func = getattr(sys.modules[__name__], test_name)
        if not func:
            raise Exception(f"Unknown test case: {test_name}")
        func(*args[1:])
    except BaseException as e:
        logging.exception("Execute %s failed with %s", args, e)
        raise
