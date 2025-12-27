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
from typing import Any, Dict, List, Optional

import pyfory


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
        self.xwrite(buffer, value)

    def read(self, buffer):
        return self.xread(buffer)

    def xwrite(self, buffer, value):
        buffer.write_varint32(value.id)

    def xread(self, buffer):
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
        f.write(new_buffer.get_bytes(0, new_buffer.writer_index))


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
        f.write(new_buffer.get_bytes(0, new_buffer.writer_index))


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
        f.write(new_buffer.get_bytes(0, new_buffer.writer_index))


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
        f.write(new_buffer.get_bytes(0, new_buffer.writer_index))


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
