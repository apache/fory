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
import decimal
import enum
import math
from typing import Dict, Any, List, Set, Optional, Tuple

import pytest
import typing

import pyfory
from pyfory import Fory
from pyfory.error import ForyInvalidDataError, TypeUnregisteredError
from pyfory.resolver import NOT_NULL_VALUE_FLAG, REF_VALUE_FLAG
from pyfory.struct import DataClassSerializer, build_default_values_factory
from pyfory.types import TypeId


def ser_de(fory, obj):
    binary = fory.serialize(obj)
    return fory.deserialize(binary)


def compat_ser_de(remote_cls, local_cls, value, type_id, ref=False):
    writer = Fory(xlang=True, compatible=True, ref=ref)
    reader = Fory(xlang=True, compatible=True, ref=ref)
    writer.register_type(remote_cls, type_id=type_id)
    reader.register_type(local_cls, type_id=type_id)
    return reader.deserialize(writer.serialize(value))


def compat_ser(remote_cls, local_cls, value, type_id):
    writer = Fory(xlang=True, compatible=True, ref=False)
    reader = Fory(xlang=True, compatible=True, ref=False)
    writer.register_type(remote_cls, type_id=type_id)
    reader.register_type(local_cls, type_id=type_id)
    return writer, reader, writer.serialize(value)


@dataclass
class SimpleObject:
    f1: Optional[Dict[pyfory.Int32, pyfory.Float64]] = None


@dataclass
class ComplexObject:
    f1: Optional[Any] = None
    f2: Optional[Any] = None
    f3: pyfory.Int8 = 0
    f4: pyfory.Int16 = 0
    f5: pyfory.Int32 = 0
    f6: pyfory.Int64 = 0
    f7: pyfory.Float32 = 0
    f8: pyfory.Float64 = 0
    f9: Optional[List[pyfory.Int16]] = None
    f10: Optional[Dict[pyfory.Int32, pyfory.Float64]] = None


def test_struct():
    fory = Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(SimpleObject, name="SimpleObject")
    fory.register_type(ComplexObject, name="example.ComplexObject")
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
class NestedDeclaredEncodingObject:
    values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]] = dataclasses.field(default_factory=dict)
    flags: Set[pyfory.FixedUInt32] = dataclasses.field(default_factory=set)


def test_nested_declared_field_type_drives_local_roundtrip():
    fory = Fory(xlang=True, compatible=True, ref=False)
    fory.register_type(NestedDeclaredEncodingObject, type_id=701)
    serializer = fory.type_resolver.get_serializer(NestedDeclaredEncodingObject)
    field_infos = {field_info.name: field_info for field_info in serializer._field_infos}

    values_type = field_infos["values"].field_type
    assert values_type.type_id == TypeId.MAP
    assert values_type.key_type.type_id == TypeId.INT32
    assert values_type.value_type.type_id == TypeId.LIST
    assert values_type.value_type.element_type.type_id == TypeId.TAGGED_INT64
    assert field_infos["flags"].field_type.element_type.type_id == TypeId.UINT32

    value = NestedDeclaredEncodingObject(values={1: [2, -3], -4: [5]}, flags={1, 2**32 - 1})
    assert ser_de(fory, value) == value


@dataclass
class RemoteNestedFixedTagged:
    values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]] = dataclasses.field(default_factory=dict)


@dataclass
class LocalNestedVarint:
    values: Dict[pyfory.Int32, List[pyfory.Int64]] = dataclasses.field(default_factory=dict)


@dataclass
class RemoteNestedWide:
    values: Dict[pyfory.FixedInt64, List[pyfory.TaggedInt64]] = dataclasses.field(default_factory=dict)


@dataclass
class LocalNestedNarrow:
    values: Dict[pyfory.FixedInt32, List[pyfory.Int32]] = dataclasses.field(default_factory=lambda: {7: [8]})


@dataclass
class RemoteNestedUnsigned:
    values: Dict[pyfory.FixedUInt32, List[pyfory.TaggedUInt64]] = dataclasses.field(default_factory=dict)


@dataclass
class LocalNestedSignedDefault:
    values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]] = dataclasses.field(default_factory=lambda: {-1: [-1]})


@dataclass
class RemoteStringScalar:
    value: str = ""


@dataclass
class LocalBoolScalar:
    value: bool = False


@dataclass
class RemoteBoolScalar:
    value: bool = False


@dataclass
class RemoteOptionalBoolScalar:
    value: Optional[bool] = None


@dataclass
class RemoteOptionalRefBoolScalar:
    value: Optional[bool] = pyfory.field(default=True, ref=True)


@dataclass
class LocalStringScalar:
    value: str = ""


@dataclass
class RemoteInt64Scalar:
    value: pyfory.Int64 = 0


@dataclass
class LocalInt8Scalar:
    value: pyfory.Int8 = 0


@dataclass
class RemoteUInt64Scalar:
    value: pyfory.UInt64 = 0


@dataclass
class LocalInt64Scalar:
    value: pyfory.Int64 = 0


@dataclass
class LocalDecimalScalar:
    value: decimal.Decimal = decimal.Decimal(0)


@dataclass
class RemoteDecimalScalar:
    value: decimal.Decimal = decimal.Decimal(0)


@dataclass
class LocalFloat32Scalar:
    value: pyfory.Float32 = 0.0


@dataclass
class RemoteFloat64Scalar:
    value: pyfory.Float64 = 0.0


@dataclass
class RemoteOptionalStringScalar:
    value: Optional[str] = None


@dataclass
class RemoteTwoStringScalars:
    flag: str = pyfory.field(default="", ref=True)
    count: str = pyfory.field(default="", ref=True)


@dataclass
class RemoteRefBoolScalar:
    value: bool = pyfory.field(default=False, ref=True)


@dataclass
class LocalRefBoolScalar:
    value: bool = pyfory.field(default=False, ref=True)


@dataclass
class LocalOptionalRefBoolScalar:
    value: Optional[bool] = pyfory.field(default=None, ref=True)


@dataclass
class RemoteRefFixedInt32Scalar:
    value: pyfory.FixedInt32 = pyfory.field(default=0, ref=True)


@dataclass
class LocalRefInt32Scalar:
    value: pyfory.Int32 = pyfory.field(default=0, ref=True)


@dataclass
class LocalBoolIntScalars:
    flag: bool = False
    count: pyfory.Int32 = 0


def test_compatible_scalar_conversions():
    assert compat_ser_de(RemoteStringScalar, LocalBoolScalar, RemoteStringScalar("true"), 720) == LocalBoolScalar(True)
    assert compat_ser_de(RemoteStringScalar, LocalBoolScalar, RemoteStringScalar("0"), 721) == LocalBoolScalar(False)
    assert compat_ser_de(RemoteBoolScalar, LocalStringScalar, RemoteBoolScalar(True), 722) == LocalStringScalar("true")
    assert compat_ser_de(RemoteInt64Scalar, LocalBoolScalar, RemoteInt64Scalar(1), 723) == LocalBoolScalar(True)
    assert compat_ser_de(RemoteBoolScalar, LocalInt8Scalar, RemoteBoolScalar(True), 724) == LocalInt8Scalar(1)
    assert compat_ser_de(RemoteInt64Scalar, LocalInt8Scalar, RemoteInt64Scalar(127), 725) == LocalInt8Scalar(127)
    assert compat_ser_de(RemoteUInt64Scalar, LocalInt64Scalar, RemoteUInt64Scalar(42), 726) == LocalInt64Scalar(42)
    assert compat_ser_de(RemoteStringScalar, LocalFloat32Scalar, RemoteStringScalar("0.5"), 727) == LocalFloat32Scalar(0.5)
    assert compat_ser_de(RemoteFloat64Scalar, LocalStringScalar, RemoteFloat64Scalar(-0.0), 728) == LocalStringScalar("-0.0")
    assert compat_ser_de(RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("1.2300"), 729) == LocalDecimalScalar(decimal.Decimal("1.23"))
    assert compat_ser_de(RemoteDecimalScalar, LocalInt64Scalar, RemoteDecimalScalar(decimal.Decimal("1.0")), 730) == LocalInt64Scalar(1)
    assert compat_ser_de(RemoteInt64Scalar, LocalDecimalScalar, RemoteInt64Scalar(7), 731) == LocalDecimalScalar(decimal.Decimal(7))
    digits_256 = "1" * 256
    digit_bound = compat_ser_de(RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar(digits_256), 743)
    assert digit_bound.value == decimal.Decimal(digits_256)
    exponent_bound = compat_ser_de(RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("1e255"), 744)
    assert len(str(int(exponent_bound.value))) == 256
    result = compat_ser_de(RemoteStringScalar, LocalFloat32Scalar, RemoteStringScalar("-0e0"), 737)
    assert result.value == 0.0
    assert math.copysign(1.0, result.value) < 0.0


def test_compatible_scalar_rejects_invalid_bool_payload():
    _, reader, payload = compat_ser(RemoteBoolScalar, LocalStringScalar, RemoteBoolScalar(True), 745)
    corrupted = bytearray(payload)
    corrupted[-1] = 2
    with pytest.raises(ForyInvalidDataError):
        reader.deserialize(bytes(corrupted))


def test_compatible_scalar_rejects_ref_value_flag():
    _, reader, payload = compat_ser(RemoteOptionalStringScalar, LocalBoolScalar, RemoteOptionalStringScalar("1"), 746)
    corrupted = bytearray(payload)
    flag_offset = corrupted.rfind(bytes([NOT_NULL_VALUE_FLAG & 0xFF]))
    assert flag_offset >= 0
    corrupted[flag_offset] = REF_VALUE_FLAG & 0xFF
    with pytest.raises(ForyInvalidDataError):
        reader.deserialize(bytes(corrupted))


def test_compatible_scalar_same_type_nullable_uses_strict_source_read():
    _, reader, payload = compat_ser(RemoteOptionalBoolScalar, LocalBoolScalar, RemoteOptionalBoolScalar(True), 747)
    corrupted = bytearray(payload)
    flag_offset = corrupted.rfind(bytes([NOT_NULL_VALUE_FLAG & 0xFF]))
    assert flag_offset >= 0
    corrupted[flag_offset] = REF_VALUE_FLAG & 0xFF
    with pytest.raises(ForyInvalidDataError):
        reader.deserialize(bytes(corrupted))

    corrupted = bytearray(payload)
    corrupted[-1] = 2
    with pytest.raises(ForyInvalidDataError):
        reader.deserialize(bytes(corrupted))


@pytest.mark.parametrize(
    ("remote_cls", "local_cls", "value"),
    [
        (RemoteStringScalar, LocalBoolScalar, RemoteStringScalar("True")),
        (RemoteInt64Scalar, LocalBoolScalar, RemoteInt64Scalar(2)),
        (RemoteStringScalar, LocalInt64Scalar, RemoteStringScalar("01")),
        (RemoteStringScalar, LocalInt64Scalar, RemoteStringScalar("1.5")),
        (RemoteStringScalar, LocalFloat32Scalar, RemoteStringScalar("0.1")),
        (RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("1" * 257)),
        (RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("0." + "0" * 319)),
        (RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("1e1000000")),
        (RemoteStringScalar, LocalDecimalScalar, RemoteStringScalar("1e256")),
        (RemoteInt64Scalar, LocalInt8Scalar, RemoteInt64Scalar(128)),
        (RemoteDecimalScalar, LocalInt64Scalar, RemoteDecimalScalar(decimal.Decimal(1 << 63))),
        (RemoteDecimalScalar, LocalStringScalar, RemoteDecimalScalar(decimal.Decimal((0, (1,), 256)))),
        (RemoteFloat64Scalar, LocalStringScalar, RemoteFloat64Scalar(float("inf"))),
    ],
)
def test_compatible_scalar_conversion_errors(remote_cls, local_cls, value):
    _, reader, payload = compat_ser(remote_cls, local_cls, value, 732)
    with pytest.raises(ForyInvalidDataError):
        reader.deserialize(payload)


def test_compatible_scalar_optional_composition():
    assert compat_ser_de(RemoteOptionalStringScalar, LocalBoolScalar, RemoteOptionalStringScalar("false"), 733) == LocalBoolScalar(False)
    assert compat_ser_de(RemoteOptionalStringScalar, LocalBoolScalar, RemoteOptionalStringScalar("1"), 734) == LocalBoolScalar(True)
    assert compat_ser_de(RemoteOptionalStringScalar, LocalBoolScalar, RemoteOptionalStringScalar(None), 735) == LocalBoolScalar(False)


def test_scalar_tracking_ref_is_not_converted():
    writer = Fory(xlang=True, compatible=True, ref=True)
    reader = Fory(xlang=True, compatible=True, ref=True)
    writer.register_type(RemoteTwoStringScalars, type_id=738)
    reader.register_type(LocalBoolIntScalars, type_id=738)
    field_infos = writer.type_resolver.get_serializer(RemoteTwoStringScalars)._field_infos
    assert [field_info.runtime_ref_tracking for field_info in field_infos] == [True, True]
    assert [field_info.field_type.is_tracking_ref for field_info in field_infos] == [True, True]

    shared = "".join(["", "1"])
    result = reader.deserialize(writer.serialize(RemoteTwoStringScalars(shared, shared)))
    assert result == LocalBoolIntScalars(False, 0)


def test_scalar_tracking_ref_rules():
    assert compat_ser_de(RemoteRefBoolScalar, LocalBoolScalar, RemoteRefBoolScalar(True), 739, ref=True) == LocalBoolScalar(False)
    assert compat_ser_de(RemoteBoolScalar, LocalRefBoolScalar, RemoteBoolScalar(True), 740, ref=True) == LocalRefBoolScalar(False)
    assert compat_ser_de(RemoteRefBoolScalar, LocalRefBoolScalar, RemoteRefBoolScalar(True), 741, ref=True) == LocalRefBoolScalar(True)
    assert compat_ser_de(RemoteRefFixedInt32Scalar, LocalRefInt32Scalar, RemoteRefFixedInt32Scalar(7), 742, ref=True) == LocalRefInt32Scalar(0)
    assert compat_ser_de(
        RemoteOptionalRefBoolScalar,
        LocalRefBoolScalar,
        RemoteOptionalRefBoolScalar(True),
        748,
        ref=True,
    ) == LocalRefBoolScalar(False)
    assert compat_ser_de(
        RemoteRefBoolScalar,
        LocalOptionalRefBoolScalar,
        RemoteRefBoolScalar(True),
        749,
        ref=True,
    ) == LocalOptionalRefBoolScalar(None)
    assert compat_ser_de(
        RemoteOptionalRefBoolScalar,
        LocalOptionalRefBoolScalar,
        RemoteOptionalRefBoolScalar(True),
        750,
        ref=True,
    ) == LocalOptionalRefBoolScalar(True)


def test_same_schema_scalar_read_is_direct():
    from pyfory.converter import CompatibleScalarFieldSerializer

    fory = Fory(xlang=True, compatible=True, ref=False)
    fory.register_type(RemoteStringScalar, type_id=736)
    serializer = fory.type_resolver.get_serializer(RemoteStringScalar)
    assert all(not isinstance(field_serializer, CompatibleScalarFieldSerializer) for field_serializer in serializer._serializers)
    value = RemoteStringScalar("true")
    assert fory.deserialize(fory.serialize(value)) == value


def test_compatible_read_accepts_nested_same_domain_integer_encoding():
    result = compat_ser_de(
        RemoteNestedFixedTagged,
        LocalNestedVarint,
        RemoteNestedFixedTagged(values={1: [2, -3], -4: [5]}),
        702,
    )
    assert result == LocalNestedVarint(values={1: [2, -3], -4: [5]})


def test_compatible_read_validates_nested_integer_narrowing():
    result = compat_ser_de(
        RemoteNestedWide,
        LocalNestedNarrow,
        RemoteNestedWide(values={1: [2, -3]}),
        703,
    )
    assert result == LocalNestedNarrow(values={1: [2, -3]})

    result = compat_ser_de(
        RemoteNestedWide,
        LocalNestedNarrow,
        RemoteNestedWide(values={1: [1 << 40]}),
        704,
    )
    assert result == LocalNestedNarrow()


def test_compatible_read_skips_nested_signed_unsigned_mismatch():
    result = compat_ser_de(
        RemoteNestedUnsigned,
        LocalNestedSignedDefault,
        RemoteNestedUnsigned(values={1: [2]}),
        705,
    )
    assert result == LocalNestedSignedDefault()


@dataclass
class SuperClass1:
    f1: Optional[Any] = None
    f2: pyfory.Int8 = 0


@dataclass
class ChildClass1(SuperClass1):
    f3: Optional[Dict[str, pyfory.Float64]] = None


def test_strict():
    fory = Fory(xlang=False, ref=True, compatible=False)
    obj = ChildClass1(f1="a", f2=-10, f3={"a": -10.0, "b": 1 / 3})
    with pytest.raises(TypeUnregisteredError):
        fory.serialize(obj)


def test_inheritance():
    type_hints = typing.get_type_hints(ChildClass1)
    print(type_hints)
    assert type_hints.keys() == {"f1", "f2", "f3"}
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
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
        f1: pyfory.Int32
        f2: List[pyfory.Int16]
        f3: Dict[str, pyfory.Float64]
        f4: str
        f5: pyfory.Float32
        f6: bytes
        f7: bool
        f8: Any
        f9: Dict[pyfory.Int32, pyfory.Float64]
        f10: List[str]
        f11: pyfory.Int8
        f12: pyfory.Int64
        f13: pyfory.Float64
        f14: Set[pyfory.Int32]
        f15: datetime.datetime

    fory = Fory(xlang=True, compatible=False, ref=True)
    serializer = DataClassSerializer(fory.type_resolver, TestClass)
    # Sorting order:
    # 1. Non-compressed primitives (compress=0) by -size, then ascending type_id, then name:
    #    Float64(8), Float32(4), bool(1), Int8(1) => f13, f5, f7, f11
    # 2. Compressed primitives (compress=1) by -size, then name:
    #    Int64(8), Int32(4) => f12, f1
    # 3. All non-primitives directly by field identifier.
    assert serializer._field_names == [
        "f13",
        "f5",
        "f7",
        "f11",
        "f12",
        "f1",
        "f10",
        "f14",
        "f15",
        "f2",
        "f3",
        "f4",
        "f6",
        "f8",
        "f9",
    ]


def test_tagged_non_primitive_fields_sort_by_identifier():
    @dataclass
    class TaggedClass:
        later_int: pyfory.Int32 = pyfory.field(id=20, default=0)
        early_string: str = pyfory.field(id=10, default="")
        middle_map: Dict[str, pyfory.Int64] = pyfory.field(id=5, default_factory=dict)
        first_bool: bool = pyfory.field(id=1, default=False)

    fory = Fory(xlang=True, compatible=False, ref=True)
    serializer = DataClassSerializer(fory.type_resolver, TaggedClass)
    assert serializer._field_names == [
        "first_bool",
        "later_int",
        "middle_map",
        "early_string",
    ]


def test_name_based_fields_sort_by_snake_case_identifier():
    @dataclass
    class CamelCaseClass:
        alphaString: str = ""
        alpha_list: List[str] = dataclasses.field(default_factory=list)

    fory = Fory(xlang=True, compatible=False, ref=True)
    serializer = DataClassSerializer(fory.type_resolver, CamelCaseClass)
    assert serializer._field_names == ["alpha_list", "alphaString"]


def test_duration_and_decimal_fields_use_declared_serializers():
    @dataclass
    class TemporalNumberClass:
        duration: datetime.timedelta = pyfory.field(id=1, default=None)
        decimal_value: decimal.Decimal = pyfory.field(id=2, default=decimal.Decimal("0"))

    fory = Fory(xlang=True, compatible=False, ref=True)
    serializer = DataClassSerializer(fory.type_resolver, TemporalNumberClass)
    serializers = dict(zip(serializer._field_names, serializer._serializers))
    assert serializers["duration"].type_ is datetime.timedelta
    assert serializers["decimal_value"].type_ is decimal.Decimal


@pytest.mark.parametrize(
    "value, expected",
    [
        (1, True),
        (0, False),
    ],
)
def test_bool_field_coercion(value, expected):
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
    result = ser_de(fory, BoolCoercionObject(value))
    assert result.b is expected


def test_bool_field_coercion_numpy_bool():
    np = pytest.importorskip("numpy")
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)

    result_true = ser_de(fory, BoolCoercionObject(np.bool_(True)))
    assert result_true.b is True

    result_false = ser_de(fory, BoolCoercionObject(np.bool_(False)))
    assert result_false.b is False


@pytest.mark.parametrize(
    "numeric_type",
    [
        pyfory.Int8,
        pyfory.Int16,
        pyfory.Int32,
        pyfory.FixedInt32,
        pyfory.Int64,
        pyfory.FixedInt64,
        pyfory.TaggedInt64,
        pyfory.UInt8,
        pyfory.UInt16,
        pyfory.UInt32,
        pyfory.FixedUInt32,
        pyfory.UInt64,
        pyfory.FixedUInt64,
        pyfory.TaggedUInt64,
        pyfory.Float32,
        pyfory.Float64,
    ],
)
def test_numeric_serializer_need_to_write_ref_disabled(numeric_type):
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
    serializer = fory.type_resolver.get_serializer(numeric_type)
    assert serializer.need_to_write_ref is False


def test_data_class_serializer_xlang():
    fory = Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(ComplexObject, name="example.ComplexObject")
    fory.register_type(DataClassObject, name="example.TestDataClassObject")

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
    fory = Fory(xlang=False, ref=track_ref, strict=False, compatible=False)
    obj = TupleFieldObject(bar=("a", 1))
    assert ser_de(fory, obj) == obj


@pytest.mark.parametrize("track_ref", [False, True])
def test_xlang_dataclass_tuple_field(track_ref):
    fory = Fory(xlang=True, compatible=False, ref=track_ref, strict=False)
    fory.register_type(XlangTupleFieldObject, name="example.XlangTupleFieldObject")
    obj = XlangTupleFieldObject(bar=("a", 1))
    result = ser_de(fory, obj)
    assert result == obj
    assert isinstance(result.bar, tuple)


@pytest.mark.parametrize("track_ref", [False, True])
def test_xlang_nested_tuple_container_fields(track_ref):
    fory = Fory(xlang=True, compatible=False, ref=track_ref, strict=False)
    fory.register_type(XlangNestedTupleObject, name="example.XlangNestedTupleObject")
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
        f1: pyfory.Int32 = 0

    @pyfory.dataclass(evolving=False)
    class FixedStruct:
        f1: pyfory.Int32 = 0

    fory = Fory(xlang=True, compatible=True)
    fory.register_type(EvolvingStruct, name="test.EvolvingStruct")
    fory.register_type(FixedStruct, name="test.FixedStruct")
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
    fory = Fory(xlang=True, compatible=False, ref=True)

    # Register types first
    fory.register_type(ComplexObject, name="example.ComplexObject")
    fory.register_type(DataClassObject, name="example.TestDataClassObject")

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
    fory_xlang = Fory(xlang=True, compatible=False, ref=True)
    fory_python = Fory(xlang=False, ref=True, strict=False, compatible=False)

    # Register types for xlang
    fory_xlang.register_type(ComplexObject, name="example.ComplexObject")
    fory_xlang.register_type(DataClassObject, name="example.TestDataClassObject")

    # trigger lazy serializer replace
    fory_xlang.serialize(DataClassObject.create())
    # For Python mode, we can create the serializer directly since it doesn't require registration
    serializer_xlang = fory_xlang.type_resolver.get_serializer(DataClassObject)
    serializer_python = DataClassSerializer(fory_python.type_resolver, DataClassObject)

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
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
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
        fory.register_type(OptionalFieldsObject, name="example.OptionalFieldsObject")

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
        fory.register_type(ComplexObject, name="example.ComplexObject")
        fory.register_type(NestedOptionalObject, name="example.NestedOptionalObject")

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

    fory_v1.register_type(CompatibleV1, name="example.Compatible")
    fory_v2.register_type(CompatibleV2, name="example.Compatible")

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

    fory_v2.register_type(CompatibleV2, name="example.Compatible")
    fory_v3.register_type(CompatibleV3, name="example.Compatible")

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

    fory_v1.register_type(CompatibleV1, name="example.Compatible")
    fory_v2.register_type(CompatibleV2, name="example.Compatible")

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

    fory_v1.register_type(CompatibleRequiredFieldV1, name="example.CompatibleRequiredField")
    fory_v2.register_type(CompatibleRequiredFieldV2, name="example.CompatibleRequiredField")

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

    fory_v1.register_type(CompatibleRequiredDefaultsV1, name="example.CompatibleRequiredDefaults")
    fory_v2.register_type(CompatibleRequiredDefaultsV2, name="example.CompatibleRequiredDefaults")

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

    fory_v1.register_type(CompatibleWithOptional, name="example.CompatibleOptional")
    fory_v2.register_type(CompatibleWithOptionalV2, name="example.CompatibleOptional")

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

    fory_v1.register_type(CompatibleAllTypes, name="example.CompatibleAllTypes")
    fory_v2.register_type(CompatibleAllTypesV2, name="example.CompatibleAllTypes")

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

    fory_v1.register_type(OptionalV1, name="example.OptionalVersioned")
    fory_v2.register_type(OptionalV2, name="example.OptionalVersioned")
    fory_v3.register_type(OptionalV3, name="example.OptionalVersioned")

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
    fory = Fory(xlang=False, ref=True, strict=False, compatible=False)
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
    fory = Fory(xlang=True, compatible=False, ref=True)
    fory.register_type(Animal, name="example.Animal")
    fory.register_type(Dog, name="example.Dog")
    fory.register_type(Zoo, name="example.Zoo")

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
