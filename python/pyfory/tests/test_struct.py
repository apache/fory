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
from dataclasses import dataclass
import datetime
import enum
from typing import Dict, Any, List, Set, Optional, Tuple

import pytest
import typing

import pyfory
from pyfory import Fory
from pyfory.error import TypeUnregisteredError
from pyfory.struct import DataClassSerializer, build_default_values_factory
from pyfory.types import TypeId


def ser_de(fory, obj):
    binary = fory.serialize(obj)
    return fory.deserialize(binary)


@dataclass
class SimpleObject:
    f1: Optional[Dict[pyfory.int32, pyfory.float64]] = None


@dataclass
class ComplexObject:
    f1: Optional[Any] = None
    f2: Optional[Any] = None
    f3: pyfory.int8 = 0
    f4: pyfory.int16 = 0
    f5: pyfory.int32 = 0
    f6: pyfory.int64 = 0
    f7: pyfory.float32 = 0
    f8: pyfory.float64 = 0
    f9: Optional[List[pyfory.int16]] = None
    f10: Optional[Dict[pyfory.int32, pyfory.float64]] = None


def test_struct():
    fory = Fory(xlang=True, ref=True)
    fory.register_type(SimpleObject, typename="SimpleObject")
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    o = SimpleObject(f1={1: 1.0 / 3})
    assert ser_de(fory, o) == o

    o = ComplexObject(
        f1="str",
        f2={"k1": -1, "k2": [1, 2]},
        f3=2**7 - 1,
        f4=2**15 - 1,
        f5=2**31 - 1,
        f6=2**63 - 1,
        f7=1.0 / 2,
        f8=2.0 / 3,
        f9=[1, 2],
        f10={1: 1.0 / 3, 100: 2 / 7.0},
    )
    assert ser_de(fory, o) == o
    with pytest.raises(AssertionError):
        assert ser_de(fory, ComplexObject(f7=1.0 / 3)) == ComplexObject(f7=1.0 / 3)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f3=2**8)) == ComplexObject(f3=2**8)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f4=2**16)) == ComplexObject(f4=2**16)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f5=2**32)) == ComplexObject(f5=2**32)
    with pytest.raises(OverflowError):
        assert ser_de(fory, ComplexObject(f6=2**64)) == ComplexObject(f6=2**64)


@dataclass
class SuperClass1:
    f1: Optional[Any] = None
    f2: pyfory.int8 = 0


@dataclass
class ChildClass1(SuperClass1):
    f3: Optional[Dict[str, pyfory.float64]] = None


def test_strict():
    fory = Fory(xlang=False, ref=True)
    obj = ChildClass1(f1="a", f2=-10, f3={"a": -10.0, "b": 1 / 3})
    with pytest.raises(TypeUnregisteredError):
        fory.serialize(obj)


def test_inheritance():
    type_hints = typing.get_type_hints(ChildClass1)
    print(type_hints)
    assert type_hints.keys() == {"f1", "f2", "f3"}
    fory = Fory(xlang=False, ref=True, strict=False)
    obj = ChildClass1(f1="a", f2=-10, f3={"a": -10.0, "b": 1 / 3})
    assert ser_de(fory, obj) == obj
    assert type(fory.type_resolver.get_serializer(ChildClass1)) is pyfory.DataClassSerializer


@dataclass
class DataClassObject:
    f_int: int
    f_float: float
    f_str: str
    f_bool: bool
    f_list: List[int]
    f_dict: Dict[str, float]
    f_any: Optional[Any]
    f_complex: Optional[ComplexObject] = None

    @classmethod
    def create(cls):
        return cls(
            f_int=42,
            f_float=3.14159,
            f_str="test_codegen",
            f_bool=True,
            f_list=[1, 2, 3],
            f_dict={"key": 1.5},
            f_any="any_data",
            f_complex=None,
        )


@dataclass
class BoolCoercionObject:
    b: bool


@dataclass(frozen=True)
class TupleFieldObject:
    bar: Tuple[str, int]


@dataclass(frozen=True)
class XlangTupleFieldObject:
    bar: Tuple[str, int]


@dataclass(frozen=True)
class XlangNestedTupleObject:
    tuple_field: Tuple[List[int], Dict[str, int]]
    list_of_tuples: List[Tuple[str, int]]
    map_of_tuples: Dict[str, Tuple[str, int]]
    set_of_tuples: Set[Tuple[str, int]]
    tuple_of_tuples: Tuple[Tuple[str, int], Tuple[str, int]]


def test_sort_fields():
    @dataclass
    class TestClass:
        f1: pyfory.int32
        f2: List[pyfory.int16]
        f3: Dict[str, pyfory.float64]
        f4: str
        f5: pyfory.float32
        f6: bytes
        f7: bool
        f8: Any
        f9: Dict[pyfory.int32, pyfory.float64]
        f10: List[str]
        f11: pyfory.int8
        f12: pyfory.int64
        f13: pyfory.float64
        f14: Set[pyfory.int32]
        f15: datetime.datetime

    fory = Fory(xlang=True, ref=True)
    serializer = DataClassSerializer(fory, TestClass)
    # Sorting order:
    # 1. Non-compressed primitives (compress=0) by -size, then name:
    #    float64(8), float32(4), bool(1), int8(1) => f13, f5, f11, f7
    #    (f11 < f7 alphabetically since '1' < '7')
    # 2. Compressed primitives (compress=1) by -size, then name:
    #    int64(8), int32(4) => f12, f1
    # 3. Internal types by type_id, then name: str, datetime, bytes => f4, f15, f6
    # 4. Collection types by type_id, then name: list => f10, f2
    # 5. Set types by type_id, then name: set => f14
    # 6. Map types by type_id, then name: dict => f3, f9
    # 7. Other types (polymorphic/any) by name: any => f8
    assert serializer._field_names == ["f13", "f5", "f11", "f7", "f12", "f1", "f4", "f15", "f6", "f10", "f2", "f14", "f3", "f9", "f8"]


@pytest.mark.parametrize(
    "value, expected",
    [
        (1, True),
        (0, False),
    ],
)
def test_bool_field_coercion(value, expected):
    fory = Fory(xlang=False, ref=True, strict=False)
    result = ser_de(fory, BoolCoercionObject(value))
    assert result.b is expected


def test_bool_field_coercion_numpy_bool():
    np = pytest.importorskip("numpy")
    fory = Fory(xlang=False, ref=True, strict=False)

    result_true = ser_de(fory, BoolCoercionObject(np.bool_(True)))
    assert result_true.b is True

    result_false = ser_de(fory, BoolCoercionObject(np.bool_(False)))
    assert result_false.b is False


@pytest.mark.parametrize(
    "numeric_type",
    [
        pyfory.int8,
        pyfory.int16,
        pyfory.int32,
        pyfory.fixed_int32,
        pyfory.int64,
        pyfory.fixed_int64,
        pyfory.tagged_int64,
        pyfory.uint8,
        pyfory.uint16,
        pyfory.uint32,
        pyfory.fixed_uint32,
        pyfory.uint64,
        pyfory.fixed_uint64,
        pyfory.tagged_uint64,
        pyfory.float32,
        pyfory.float64,
    ],
)
def test_numeric_serializer_need_to_write_ref_disabled(numeric_type):
    fory = Fory(xlang=False, ref=True, strict=False)
    serializer = fory.type_resolver.get_serializer(numeric_type)
    assert serializer.need_to_write_ref is False


def test_data_class_serializer_xlang():
    fory = Fory(xlang=True, ref=True)
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    fory.register_type(DataClassObject, typename="example.TestDataClassObject")

    complex_data = ComplexObject(
        f1="nested_str",
        f5=100,
        f8=3.14,
        f10={10: 1.0, 20: 2.0},
    )
    obj_original = DataClassObject(
        f_int=123,
        f_float=45.67,
        f_str="hello xlang",
        f_bool=True,
        f_list=[1, 2, 3, 4, 5],
        f_dict={"a": 1.1, "b": 2.2},
        f_any="any_value",
        f_complex=complex_data,
    )

    obj_deserialized = ser_de(fory, obj_original)

    assert obj_deserialized == obj_original
    assert obj_deserialized.f_int == obj_original.f_int
    assert obj_deserialized.f_float == obj_original.f_float
    assert obj_deserialized.f_str == obj_original.f_str
    assert obj_deserialized.f_bool == obj_original.f_bool
    assert obj_deserialized.f_list == obj_original.f_list
    assert obj_deserialized.f_dict == obj_original.f_dict
    assert obj_deserialized.f_any == obj_original.f_any
    assert obj_deserialized.f_complex == obj_original.f_complex
    assert type(fory.type_resolver.get_serializer(DataClassObject)) is pyfory.DataClassSerializer
    # Ensure it's using xlang mode indirectly, by checking no JIT methods if possible,
    # or by ensuring it was registered with _register_xtype which now uses DataClassSerializer.
    # For now, the registration path check is implicit via xlang=True usage.
    # We can also check if the hash is non-zero if it was computed,
    # or if the _serializers attribute exists.
    serializer_instance = fory.type_resolver.get_serializer(DataClassObject)
    assert hasattr(serializer_instance, "_serializers")
    assert not hasattr(serializer_instance, "_xlang")

    # Test with None for a complex field
    obj_with_none_complex = DataClassObject(
        f_int=789,
        f_float=12.34,
        f_str="another string",
        f_bool=False,
        f_list=[10, 20],
        f_dict={"x": 7.7, "y": 8.8},
        f_any=None,
        f_complex=None,
    )
    obj_deserialized_none = ser_de(fory, obj_with_none_complex)
    assert obj_deserialized_none == obj_with_none_complex


@pytest.mark.parametrize("track_ref", [False, True])
def test_dataclass_with_typed_tuple_field(track_ref):
    fory = Fory(xlang=False, ref=track_ref, strict=False)
    obj = TupleFieldObject(bar=("a", 1))
    assert ser_de(fory, obj) == obj


@pytest.mark.parametrize("track_ref", [False, True])
def test_xlang_dataclass_tuple_field(track_ref):
    fory = Fory(xlang=True, ref=track_ref, strict=False)
    fory.register_type(XlangTupleFieldObject, typename="example.XlangTupleFieldObject")
    obj = XlangTupleFieldObject(bar=("a", 1))
    result = ser_de(fory, obj)
    assert result == obj
    assert isinstance(result.bar, tuple)


@pytest.mark.parametrize("track_ref", [False, True])
def test_xlang_nested_tuple_container_fields(track_ref):
    fory = Fory(xlang=True, ref=track_ref, strict=False)
    fory.register_type(XlangNestedTupleObject, typename="example.XlangNestedTupleObject")
    obj = XlangNestedTupleObject(
        tuple_field=([1, 2], {"a": 1, "b": 2}),
        list_of_tuples=[("a", 1), ("b", 2)],
        map_of_tuples={"left": ("c", 3), "right": ("d", 4)},
        set_of_tuples={("e", 5), ("f", 6)},
        tuple_of_tuples=(("g", 7), ("h", 8)),
    )
    result = ser_de(fory, obj)
    assert result == obj
    assert isinstance(result.tuple_field, tuple)
    assert all(isinstance(value, tuple) for value in result.list_of_tuples)
    assert all(isinstance(value, tuple) for value in result.map_of_tuples.values())
    assert all(isinstance(value, tuple) for value in result.set_of_tuples)
    assert isinstance(result.tuple_of_tuples, tuple)
    assert all(isinstance(value, tuple) for value in result.tuple_of_tuples)


def test_struct_evolving_override():
    @pyfory.dataclass
    class EvolvingStruct:
        f1: pyfory.int32 = 0

    @pyfory.dataclass(evolving=False)
    class FixedStruct:
        f1: pyfory.int32 = 0

    fory = Fory(xlang=True, compatible=True)
    fory.register_type(EvolvingStruct, namespace="test", typename="EvolvingStruct")
    fory.register_type(FixedStruct, namespace="test", typename="FixedStruct")
    evolving_info = fory.type_resolver.get_type_info(EvolvingStruct)
    fixed_info = fory.type_resolver.get_type_info(FixedStruct)
    assert evolving_info.type_id == TypeId.NAMED_COMPATIBLE_STRUCT
    assert fixed_info.type_id == TypeId.NAMED_STRUCT

    evolving = EvolvingStruct(f1=123)
    fixed = FixedStruct(f1=123)
    evolving_bytes = fory.serialize(evolving)
    fixed_bytes = fory.serialize(fixed)

    assert len(fixed_bytes) < len(evolving_bytes)
    assert fory.deserialize(evolving_bytes) == evolving
    assert fory.deserialize(fixed_bytes) == fixed


def test_data_class_serializer_xlang_serializer():
    """Test DataClassSerializer round-trip behavior in xlang mode."""
    fory = Fory(xlang=True, ref=True)

    # Register types first
    fory.register_type(ComplexObject, typename="example.ComplexObject")
    fory.register_type(DataClassObject, typename="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory.serialize(DataClassObject.create())
    # Get the serializer that was created during registration
    serializer = fory.type_resolver.get_serializer(DataClassObject)

    # Serializer API is unified: no mode-specific serializer attribute.
    assert not hasattr(serializer, "_xlang")
    assert hasattr(serializer, "_serializers")
    assert len(serializer._serializers) == len(serializer._field_names)

    # Test that the generated methods work correctly through the normal serialization flow
    test_obj = DataClassObject(
        f_int=42,
        f_float=3.14159,
        f_str="test_codegen",
        f_bool=True,
        f_list=[1, 2, 3],
        f_dict={"key": 1.5},
        f_any="any_data",
        f_complex=None,
    )

    # Test serialization and deserialization using the normal fory flow
    binary = fory.serialize(test_obj)
    deserialized_obj = fory.deserialize(binary)

    # Verify the results
    assert deserialized_obj.f_int == test_obj.f_int
    assert deserialized_obj.f_float == test_obj.f_float
    assert deserialized_obj.f_str == test_obj.f_str
    assert deserialized_obj.f_bool == test_obj.f_bool
    assert deserialized_obj.f_list == test_obj.f_list
    assert deserialized_obj.f_dict == test_obj.f_dict
    assert deserialized_obj.f_any == test_obj.f_any
    assert deserialized_obj.f_complex == test_obj.f_complex


def test_data_class_serializer_xlang_vs_non_xlang():
    """Test that xlang and non-xlang modes use the same dataclass serializer behavior."""
    fory_xlang = Fory(xlang=True, ref=True)
    fory_python = Fory(xlang=False, ref=True, strict=False)

    # Register types for xlang
    fory_xlang.register_type(ComplexObject, typename="example.ComplexObject")
    fory_xlang.register_type(DataClassObject, typename="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory_xlang.serialize(DataClassObject.create())
    # For Python mode, we can create the serializer directly since it doesn't require registration
    serializer_xlang = fory_xlang.type_resolver.get_serializer(DataClassObject)
    serializer_python = DataClassSerializer(fory_python, DataClassObject)

    assert not hasattr(serializer_xlang, "_xlang")
    assert not hasattr(serializer_python, "_xlang")

    # Unified serializer metadata should be mode-independent.
    assert serializer_xlang._field_names == serializer_python._field_names
    assert serializer_xlang._nullable_fields == serializer_python._nullable_fields
    assert serializer_xlang._dynamic_fields == serializer_python._dynamic_fields
    assert serializer_xlang._hash == serializer_python._hash


class MissingDefaultEnum(enum.Enum):
    A = 1
    B = 2


@dataclass
class MissingDefaultFactoryFields:
    required: int
    required_float: float
    required_str: str
    required_bytes: bytes
    required_list: List[int]
    required_set: Set[int]
    required_dict: Dict[str, int]
    plain_default: int = 7
    list_default: List[int] = dataclasses.field(default_factory=list)
    enum_default_none: MissingDefaultEnum = None


def test_build_default_values_factory():
    fory = Fory(xlang=False, ref=True, strict=False)
    type_hints = typing.get_type_hints(MissingDefaultFactoryFields)
    default_factories = build_default_values_factory(
        fory,
        type_hints,
        dataclasses.fields(MissingDefaultFactoryFields),
    )

    assert callable(default_factories["required"])
    assert callable(default_factories["required_float"])
    assert callable(default_factories["required_str"])
    assert callable(default_factories["required_bytes"])
    assert callable(default_factories["required_list"])
    assert callable(default_factories["required_set"])
    assert callable(default_factories["required_dict"])
    assert callable(default_factories["plain_default"])
    assert callable(default_factories["list_default"])
    assert callable(default_factories["enum_default_none"])

    assert default_factories["required"]() == 0
    assert default_factories["required_float"]() == 0.0
    assert default_factories["required_str"]() == ""
    assert default_factories["required_bytes"]() == b""
    list_required_one = default_factories["required_list"]()
    list_required_two = default_factories["required_list"]()
    assert list_required_one == []
    assert list_required_two == []
    assert list_required_one is not list_required_two
    set_required_one = default_factories["required_set"]()
    set_required_two = default_factories["required_set"]()
    assert set_required_one == set()
    assert set_required_two == set()
    assert set_required_one is not set_required_two
    dict_required_one = default_factories["required_dict"]()
    dict_required_two = default_factories["required_dict"]()
    assert dict_required_one == {}
    assert dict_required_two == {}
    assert dict_required_one is not dict_required_two
    assert default_factories["plain_default"]() == 7
    assert default_factories["enum_default_none"]() is MissingDefaultEnum.A
    list_one = default_factories["list_default"]()
    list_two = default_factories["list_default"]()
    assert list_one == []
    assert list_two == []
    assert list_one is not list_two


@dataclass
class OptionalFieldsObject:
    f1: Optional[int] = None
    f2: Optional[str] = None
    f3: Optional[List[int]] = None
    f4: int = 0
    f5: str = ""


@pytest.mark.parametrize("xlang", [False, True])
@pytest.mark.parametrize("compatible", [False, True])
def test_optional_fields(xlang, compatible):
    fory = Fory(xlang=xlang, ref=True, compatible=compatible, strict=False)
    if xlang:
        fory.register_type(OptionalFieldsObject, typename="example.OptionalFieldsObject")

    obj_with_none = OptionalFieldsObject(f1=None, f2=None, f3=None, f4=42, f5="test")
    result = ser_de(fory, obj_with_none)
    assert result.f1 is None
    assert result.f2 is None
    assert result.f3 is None
    assert result.f4 == 42
    assert result.f5 == "test"

    obj_with_values = OptionalFieldsObject(f1=100, f2="hello", f3=[1, 2, 3], f4=42, f5="test")
    result = ser_de(fory, obj_with_values)
    assert result.f1 == 100
    assert result.f2 == "hello"
    assert result.f3 == [1, 2, 3]
    assert result.f4 == 42
    assert result.f5 == "test"

    obj_mixed = OptionalFieldsObject(f1=100, f2=None, f3=[1, 2, 3], f4=42, f5="test")
    result = ser_de(fory, obj_mixed)
    assert result.f1 == 100
    assert result.f2 is None
    assert result.f3 == [1, 2, 3]
    assert result.f4 == 42
    assert result.f5 == "test"


@dataclass
class NestedOptionalObject:
    f1: Optional[ComplexObject] = None
    f2: Optional[Dict[str, int]] = None
    f3: str = ""


@pytest.mark.parametrize("xlang", [False, True])
@pytest.mark.parametrize("compatible", [False, True])
def test_nested_optional_fields(xlang, compatible):
    fory = Fory(xlang=xlang, ref=True, compatible=compatible, strict=False)
    if xlang:
        fory.register_type(ComplexObject, typename="example.ComplexObject")
        fory.register_type(NestedOptionalObject, typename="example.NestedOptionalObject")

    obj_with_none = NestedOptionalObject(f1=None, f2=None, f3="test")
    result = ser_de(fory, obj_with_none)
    assert result.f1 is None
    assert result.f2 is None
    assert result.f3 == "test"

    complex_obj = ComplexObject(f1="nested", f5=100, f8=3.14)
    obj_with_values = NestedOptionalObject(f1=complex_obj, f2={"a": 1, "b": 2}, f3="test")
    result = ser_de(fory, obj_with_values)
    assert result.f1.f1 == "nested"
    assert result.f1.f5 == 100
    assert result.f2 == {"a": 1, "b": 2}
    assert result.f3 == "test"


@dataclass
class OptionalV1:
    f1: Optional[int] = None
    f2: str = ""
    f3: Optional[List[int]] = None


@dataclass
class OptionalV2:
    f1: Optional[int] = None
    f2: str = ""
    f3: Optional[List[int]] = None
    f4: Optional[str] = None


@dataclass
class OptionalV3:
    f1: Optional[int] = None
    f2: str = ""


@dataclass
class CompatibleV1:
    f1: int = 0
    f2: str = ""
    f3: float = 0.0


@dataclass
class CompatibleV2:
    f1: int = 0
    f2: str = ""
    f3: float = 0.0
    f4: bool = False


@dataclass
class CompatibleV3:
    f1: int = 0
    f2: str = ""


@dataclass
class CompatibleRequiredFieldV1:
    f1: int


@dataclass
class CompatibleRequiredFieldV2:
    f1: int
    f2: int


@dataclass
class CompatibleRequiredDefaultsV1:
    f1: int


@dataclass
class CompatibleRequiredDefaultsV2:
    f1: int
    f_int: int
    f_float: float
    f_str: str
    f_bytes: bytes
    f_list: List[int]
    f_set: Set[int]
    f_dict: Dict[str, int]


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_add_field(xlang):
    """Test that adding a field with default value works in compatible mode."""
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleV1, typename="example.Compatible")
    fory_v2.register_type(CompatibleV2, typename="example.Compatible")

    # V1 object serialized
    v1_obj = CompatibleV1(f1=100, f2="test", f3=3.14)
    v1_binary = fory_v1.serialize(v1_obj)

    # V2 can read V1 data, new field gets default value
    v2_result = fory_v2.deserialize(v1_binary)
    assert v2_result.f1 == 100
    assert v2_result.f2 == "test"
    assert v2_result.f3 == 3.14
    assert v2_result.f4 is False  # Default value


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_remove_field(xlang):
    """Test that removing a field works in compatible mode."""
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v3 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v2.register_type(CompatibleV2, typename="example.Compatible")
    fory_v3.register_type(CompatibleV3, typename="example.Compatible")

    # V2 object with all fields
    v2_obj = CompatibleV2(f1=200, f2="hello", f3=2.71, f4=True)
    v2_binary = fory_v2.serialize(v2_obj)

    # V3 can read V2 data, extra fields are ignored
    v3_result = fory_v3.deserialize(v2_binary)
    assert v3_result.f1 == 200
    assert v3_result.f2 == "hello"
    # f3 and f4 from V2 are ignored


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_bidirectional(xlang):
    """Test bidirectional compatible serialization."""
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleV1, typename="example.Compatible")
    fory_v2.register_type(CompatibleV2, typename="example.Compatible")

    # V1 -> V2
    v1_obj = CompatibleV1(f1=100, f2="test", f3=3.14)
    v1_binary = fory_v1.serialize(v1_obj)
    v2_result = fory_v2.deserialize(v1_binary)
    assert v2_result.f1 == 100
    assert v2_result.f2 == "test"
    assert v2_result.f3 == 3.14
    assert v2_result.f4 is False

    # V2 -> V1
    v2_obj = CompatibleV2(f1=200, f2="hello", f3=2.71, f4=True)
    v2_binary = fory_v2.serialize(v2_obj)
    v1_result = fory_v1.deserialize(v2_binary)
    assert v1_result.f1 == 200
    assert v1_result.f2 == "hello"
    assert v1_result.f3 == 2.71


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_add_required_field_without_default_uses_zero_value(xlang):
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleRequiredFieldV1, typename="example.CompatibleRequiredField")
    fory_v2.register_type(CompatibleRequiredFieldV2, typename="example.CompatibleRequiredField")

    v1_binary = fory_v1.serialize(CompatibleRequiredFieldV1(f1=321))
    v2_result = fory_v2.deserialize(v1_binary)

    assert v2_result.f1 == 321
    assert hasattr(v2_result, "f2")
    assert v2_result.f2 == 0

    serializer_v2 = fory_v2.type_resolver.get_serializer(CompatibleRequiredFieldV2)
    assert hasattr(serializer_v2, "_default_values_factory")
    assert callable(serializer_v2._default_values_factory["f2"])
    assert serializer_v2._default_values_factory["f2"]() == 0
    assert ser_de(fory_v2, v2_result) == v2_result


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_add_required_fields_use_type_defaults(xlang):
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleRequiredDefaultsV1, typename="example.CompatibleRequiredDefaults")
    fory_v2.register_type(CompatibleRequiredDefaultsV2, typename="example.CompatibleRequiredDefaults")

    v1_binary = fory_v1.serialize(CompatibleRequiredDefaultsV1(f1=11))
    v2_result = fory_v2.deserialize(v1_binary)

    assert v2_result.f1 == 11
    assert v2_result.f_int == 0
    assert v2_result.f_float == 0.0
    assert v2_result.f_str == ""
    assert v2_result.f_bytes == b""
    assert v2_result.f_list == []
    assert v2_result.f_set == set()
    assert v2_result.f_dict == {}
    assert ser_de(fory_v2, v2_result) == v2_result


@dataclass
class CompatibleWithOptional:
    f1: Optional[int] = None
    f2: str = ""
    f3: Optional[List[int]] = None


@dataclass
class CompatibleWithOptionalV2:
    f1: Optional[int] = None
    f2: str = ""
    f3: Optional[List[int]] = None
    f4: Optional[str] = None


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_with_optional_fields(xlang):
    """Test compatible mode with optional fields."""
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleWithOptional, typename="example.CompatibleOptional")
    fory_v2.register_type(CompatibleWithOptionalV2, typename="example.CompatibleOptional")

    # V1 with None values
    v1_obj = CompatibleWithOptional(f1=None, f2="test", f3=None)
    v1_binary = fory_v1.serialize(v1_obj)
    v2_result = fory_v2.deserialize(v1_binary)
    assert v2_result.f1 is None
    assert v2_result.f2 == "test"
    assert v2_result.f3 is None
    assert v2_result.f4 is None

    # V1 with values
    v1_obj2 = CompatibleWithOptional(f1=100, f2="test", f3=[1, 2, 3])
    v1_binary2 = fory_v1.serialize(v1_obj2)
    v2_result2 = fory_v2.deserialize(v1_binary2)
    assert v2_result2.f1 == 100
    assert v2_result2.f2 == "test"
    assert v2_result2.f3 == [1, 2, 3]
    assert v2_result2.f4 is None


@dataclass
class CompatibleAllTypes:
    f_int: int = 0
    f_str: str = ""
    f_float: float = 0.0
    f_bool: bool = False
    f_list: Optional[List[int]] = None
    f_dict: Optional[Dict[str, int]] = None


@dataclass
class CompatibleAllTypesV2:
    f_int: int = 0
    f_str: str = ""
    f_float: float = 0.0
    f_bool: bool = False
    f_list: Optional[List[int]] = None
    f_dict: Optional[Dict[str, int]] = None
    f_new: str = "default"


@pytest.mark.parametrize("xlang", [False, True])
def test_compatible_mode_all_basic_types(xlang):
    """Test compatible mode with all basic types."""
    fory_v1 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)
    fory_v2 = Fory(xlang=xlang, ref=True, compatible=True, strict=False)

    fory_v1.register_type(CompatibleAllTypes, typename="example.CompatibleAllTypes")
    fory_v2.register_type(CompatibleAllTypesV2, typename="example.CompatibleAllTypes")

    v1_obj = CompatibleAllTypes(f_int=42, f_str="hello", f_float=3.14, f_bool=True, f_list=[1, 2, 3], f_dict={"a": 1, "b": 2})
    v1_binary = fory_v1.serialize(v1_obj)
    v2_result = fory_v2.deserialize(v1_binary)

    assert v2_result.f_int == 42
    assert v2_result.f_str == "hello"
    assert v2_result.f_float == 3.14
    assert v2_result.f_bool is True
    assert v2_result.f_list == [1, 2, 3]
    assert v2_result.f_dict == {"a": 1, "b": 2}
    assert v2_result.f_new == "default"


def test_optional_compatible_mode_evolution():
    fory_v1 = Fory(xlang=True, ref=True, compatible=True)
    fory_v2 = Fory(xlang=True, ref=True, compatible=True)
    fory_v3 = Fory(xlang=True, ref=True, compatible=True)

    fory_v1.register_type(OptionalV1, typename="example.OptionalVersioned")
    fory_v2.register_type(OptionalV2, typename="example.OptionalVersioned")
    fory_v3.register_type(OptionalV3, typename="example.OptionalVersioned")

    v1_obj = OptionalV1(f1=100, f2="test", f3=[1, 2, 3])
    v1_binary = fory_v1.serialize(v1_obj)

    v2_result = fory_v2.deserialize(v1_binary)
    assert v2_result.f1 == 100
    assert v2_result.f2 == "test"
    assert v2_result.f3 == [1, 2, 3]
    assert v2_result.f4 is None

    v1_obj_with_none = OptionalV1(f1=None, f2="test", f3=None)
    v1_binary_with_none = fory_v1.serialize(v1_obj_with_none)

    v2_result_with_none = fory_v2.deserialize(v1_binary_with_none)
    assert v2_result_with_none.f1 is None
    assert v2_result_with_none.f2 == "test"
    assert v2_result_with_none.f3 is None
    assert v2_result_with_none.f4 is None

    v2_obj = OptionalV2(f1=200, f2="test2", f3=[4, 5], f4="extra")
    v2_binary = fory_v2.serialize(v2_obj)

    v3_result = fory_v3.deserialize(v2_binary)
    assert v3_result.f1 == 200
    assert v3_result.f2 == "test2"

    v2_obj_partial_none = OptionalV2(f1=None, f2="test2", f3=None, f4=None)
    v2_binary_partial_none = fory_v2.serialize(v2_obj_partial_none)

    v3_result_partial_none = fory_v3.deserialize(v2_binary_partial_none)
    assert v3_result_partial_none.f1 is None
    assert v3_result_partial_none.f2 == "test2"

    v3_obj = OptionalV3(f1=300, f2="test3")
    v3_binary = fory_v3.serialize(v3_obj)

    v1_result = fory_v1.deserialize(v3_binary)
    assert v1_result.f1 == 300
    assert v1_result.f2 == "test3"
    assert v1_result.f3 is None


# ============================================================================
# Tests for dynamic field configuration
# ============================================================================


@dataclass
class Animal:
    name: str = pyfory.field(id=0, default="")


@dataclass
class Dog(Animal):
    breed: str = pyfory.field(id=1, default="")


@dataclass
class Zoo:
    # dynamic=True: can hold Dog instance in Animal field
    animal: Animal = pyfory.field(id=0, dynamic=True)
    # dynamic=False: use declared type's serializer, subclass info lost
    animal2: Animal = pyfory.field(id=1, dynamic=False)


def test_dynamic_with_inheritance():
    """Test dynamic=True allows polymorphic serialization with inheritance."""
    fory = Fory(xlang=False, ref=True, strict=False)
    fory.register_type(Animal)
    fory.register_type(Dog)
    fory.register_type(Zoo)

    dog1 = Dog(name="Buddy", breed="Labrador")
    dog2 = Dog(name="Rex", breed="German Shepherd")
    zoo = Zoo(animal=dog1, animal2=dog2)

    result = ser_de(fory, zoo)
    # dynamic=True: Dog type preserved
    assert isinstance(result.animal, Dog)
    assert result.animal.name == "Buddy"
    assert result.animal.breed == "Labrador"
    # dynamic=False: subclass info lost, only Animal fields deserialized
    assert isinstance(result.animal2, Animal)
    assert not isinstance(result.animal2, Dog)
    assert result.animal2.name == "Rex"
    assert not hasattr(result.animal2, "breed") or getattr(result.animal2, "breed", None) != "German Shepherd"


def test_dynamic_with_inheritance_xlang():
    """Test dynamic=True allows polymorphic serialization in xlang mode."""
    fory = Fory(xlang=True, ref=True)
    fory.register_type(Animal, typename="example.Animal")
    fory.register_type(Dog, typename="example.Dog")
    fory.register_type(Zoo, typename="example.Zoo")

    dog1 = Dog(name="Max", breed="Husky")
    dog2 = Dog(name="Luna", breed="Poodle")
    zoo = Zoo(animal=dog1, animal2=dog2)

    result = ser_de(fory, zoo)
    # dynamic=True: Dog type preserved
    assert isinstance(result.animal, Dog)
    assert result.animal.name == "Max"
    assert result.animal.breed == "Husky"
    # dynamic=False: subclass info lost, only Animal fields deserialized
    assert isinstance(result.animal2, Animal)
    assert not isinstance(result.animal2, Dog)
    assert result.animal2.name == "Luna"
    assert not hasattr(result.animal2, "breed") or getattr(result.animal2, "breed", None) != "Poodle"
