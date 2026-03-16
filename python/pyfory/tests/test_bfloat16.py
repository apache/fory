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

import array
import math
import struct

import pytest

from pyfory import Fory
from pyfory.bfloat16 import bfloat16
from pyfory.bfloat16_array import BFloat16Array
from pyfory.types import TypeId


def ser_de(fory, value):
    data = fory.serialize(value)
    return fory.deserialize(data)


# --------------- scalar construction ---------------


def test_bfloat16_basic():
    bf16 = bfloat16(3.14)
    assert isinstance(bf16, bfloat16)
    assert bf16.to_float32() == pytest.approx(3.14, abs=0.01)
    bits = bf16.to_bits()
    assert bfloat16.from_bits(bits).to_bits() == bits


def test_bfloat16_from_bits_roundtrip():
    for bits in (0x0000, 0x3F80, 0x4049, 0x7F80, 0xFF80, 0x7FC0):
        assert bfloat16.from_bits(bits).to_bits() == bits


# --------------- special values ---------------


def test_bfloat16_special_values():
    assert bfloat16(float("nan")).is_nan()
    assert bfloat16(float("inf")).is_inf()
    assert bfloat16(float("-inf")).is_inf()
    assert bfloat16(0.0).is_zero()
    assert bfloat16(1.0).is_finite()
    assert not bfloat16(1.0).is_nan()
    assert not bfloat16(1.0).is_inf()


def test_bfloat16_positive_zero():
    pz = bfloat16(0.0)
    assert pz.to_bits() == 0x0000
    assert pz.is_zero()
    assert pz.to_float32() == 0.0


def test_bfloat16_negative_zero():
    nz = bfloat16(-0.0)
    assert nz.to_bits() == 0x8000
    assert nz.is_zero()
    assert math.copysign(1.0, nz.to_float32()) == -1.0


def test_bfloat16_positive_infinity():
    pinf = bfloat16(float("inf"))
    assert pinf.to_bits() == 0x7F80
    assert pinf.is_inf()
    assert not pinf.is_nan()
    assert pinf.to_float32() == float("inf")


def test_bfloat16_negative_infinity():
    ninf = bfloat16(float("-inf"))
    assert ninf.to_bits() == 0xFF80
    assert ninf.is_inf()
    assert ninf.to_float32() == float("-inf")


def test_bfloat16_subnormal():
    bf = bfloat16.from_bits(0x0001)
    assert bf.is_finite()
    assert not bf.is_zero()
    assert bf.to_float32() != 0.0


def test_bfloat16_max_normal():
    bf = bfloat16.from_bits(0x7F7F)
    assert bf.is_finite()
    val = bf.to_float32()
    assert val > 3.0e38


def test_bfloat16_min_positive_normal():
    bf = bfloat16.from_bits(0x0080)
    assert bf.is_finite()
    val = bf.to_float32()
    assert 0 < val < 1e-37


# --------------- NaN behaviour ---------------


def test_bfloat16_nan_not_equal_to_itself():
    n = bfloat16(float("nan"))
    assert n != n


def test_bfloat16_signaling_nan_preserved():
    snan_f32_bits = 0x7F800001
    snan_f32 = struct.unpack("f", struct.pack("I", snan_f32_bits))[0]
    bf = bfloat16(snan_f32)
    assert bf.is_nan(), "signaling NaN must stay NaN, not become infinity"
    assert bf.to_bits() != 0x7F80, "must not collapse to infinity"


def test_bfloat16_quiet_nan_roundtrip():
    qnan = bfloat16.from_bits(0x7FC0)
    assert qnan.is_nan()
    assert qnan.to_bits() == 0x7FC0


# --------------- equality / hashing ---------------


def test_bfloat16_equality():
    assert bfloat16(1.0) == bfloat16(1.0)
    assert bfloat16(0.0) == bfloat16(-0.0)
    assert bfloat16(1.0) != bfloat16(2.0)


def test_bfloat16_hash_contract():
    pz = bfloat16(0.0)
    nz = bfloat16(-0.0)
    assert pz == nz
    assert hash(pz) == hash(nz), "+0 and -0 are equal so must share the same hash"


def test_bfloat16_hash_consistency():
    for v in (1.0, -1.0, 3.14, float("inf")):
        a = bfloat16(v)
        b = bfloat16(v)
        assert hash(a) == hash(b)


# --------------- rounding ---------------


def test_bfloat16_round_to_nearest_even():
    bf1 = bfloat16(1.0)
    bf2 = bfloat16(1.0 + 2**-8)
    assert bf1.to_bits() == bf2.to_bits() or abs(bf1.to_bits() - bf2.to_bits()) <= 1


# --------------- conversion ---------------


def test_bfloat16_conversion():
    assert bfloat16(0.0).to_float32() == 0.0
    assert bfloat16(1.0).to_float32() == 1.0
    assert bfloat16(-1.0).to_float32() == -1.0
    assert bfloat16(3.14).to_float32() == pytest.approx(3.14, abs=0.01)
    assert math.isnan(bfloat16(float("nan")).to_float32())
    assert math.isinf(bfloat16(float("inf")).to_float32())
    assert math.isinf(bfloat16(float("-inf")).to_float32())


def test_bfloat16_float_dunder():
    bf = bfloat16(2.5)
    assert float(bf) == pytest.approx(2.5)


def test_bfloat16_repr_and_str():
    bf = bfloat16(1.0)
    r = repr(bf)
    assert "bfloat16" in r
    assert "1.0" in str(bf) or "1" in str(bf)


# --------------- scalar serialization ---------------


def test_bfloat16_serialization():
    fory = Fory(xlang=True)
    assert ser_de(fory, bfloat16(0.0)).to_bits() == bfloat16(0.0).to_bits()
    assert ser_de(fory, bfloat16(1.0)).to_bits() == bfloat16(1.0).to_bits()
    assert ser_de(fory, bfloat16(3.14)).to_bits() == bfloat16(3.14).to_bits()
    assert ser_de(fory, bfloat16(float("inf"))).is_inf()
    assert ser_de(fory, bfloat16(float("nan"))).is_nan()


def test_bfloat16_serialization_special_bits():
    fory = Fory(xlang=True)
    for bits in (0x0000, 0x8000, 0x7F80, 0xFF80, 0x7FC0, 0x0001, 0x7F7F):
        original = bfloat16.from_bits(bits)
        result = ser_de(fory, original)
        assert result.to_bits() == bits


# --------------- array construction ---------------


def test_bfloat16_array_basic():
    arr = BFloat16Array([1.0, 2.0, 3.14])
    assert len(arr) == 3
    assert arr[0].to_float32() == pytest.approx(1.0)
    arr[0] = bfloat16(5.0)
    assert arr[0].to_float32() == pytest.approx(5.0)


def test_bfloat16_array_empty():
    arr = BFloat16Array()
    assert len(arr) == 0
    assert arr.tobytes() == b""


def test_bfloat16_array_from_bfloat16_values():
    values = [bfloat16(1.0), bfloat16(2.0)]
    arr = BFloat16Array(values)
    assert len(arr) == 2
    assert arr[0].to_bits() == bfloat16(1.0).to_bits()


def test_bfloat16_array_copy_constructor():
    original = BFloat16Array([1.0, 2.0, 3.0])
    copy = BFloat16Array(original)
    assert copy == original
    copy[0] = bfloat16(99.0)
    assert copy != original


# --------------- array bytes round-trip ---------------


def test_bfloat16_array_tobytes_frombytes():
    arr = BFloat16Array([1.0, 2.0, 3.0])
    raw = arr.tobytes()
    assert len(raw) == 6
    restored = BFloat16Array.frombytes(raw)
    assert restored == arr


def test_bfloat16_array_frombytes_odd_length():
    with pytest.raises(ValueError):
        BFloat16Array.frombytes(b"\x00\x01\x02")


# --------------- array append / extend ---------------


def test_bfloat16_array_append_extend():
    arr = BFloat16Array()
    arr.append(1.0)
    arr.append(bfloat16(2.0))
    assert len(arr) == 2
    arr.extend(BFloat16Array([3.0, 4.0]))
    assert len(arr) == 4
    arr.extend([5.0])
    assert len(arr) == 5


# --------------- array iteration / equality ---------------


def test_bfloat16_array_iteration():
    arr = BFloat16Array([1.0, 2.0, 3.0])
    floats = [float(v) for v in arr]
    assert floats == [pytest.approx(1.0), pytest.approx(2.0), pytest.approx(3.0)]


def test_bfloat16_array_equality():
    a = BFloat16Array([1.0, 2.0])
    b = BFloat16Array([1.0, 2.0])
    c = BFloat16Array([1.0, 3.0])
    assert a == b
    assert a != c
    assert a != "not an array"


def test_bfloat16_array_repr():
    arr = BFloat16Array([1.0])
    assert "BFloat16Array" in repr(arr)


def test_bfloat16_array_itemsize():
    arr = BFloat16Array()
    assert arr.itemsize == 2


def test_bfloat16_array_to_bits_array():
    arr = BFloat16Array([1.0, 2.0])
    bits = arr.to_bits_array()
    assert isinstance(bits, array.array)
    assert bits.typecode == "H"
    assert len(bits) == 2


def test_bfloat16_array_from_bits_array():
    bits = array.array("H", [bfloat16(1.0).to_bits(), bfloat16(2.0).to_bits()])
    arr = BFloat16Array.from_bits_array(bits)
    assert len(arr) == 2
    assert arr[0].to_float32() == pytest.approx(1.0)


# --------------- array special values ---------------


def test_bfloat16_array_special_values():
    arr = BFloat16Array([0.0, -0.0, float("inf"), float("-inf"), float("nan")])
    assert arr[0].is_zero()
    assert arr[1].is_zero()
    assert arr[2].is_inf()
    assert arr[3].is_inf()
    assert arr[4].is_nan()


# --------------- array serialization ---------------


def test_bfloat16_array_serialization():
    fory = Fory(xlang=True)
    arr = BFloat16Array([1.0, 2.0, 3.14])
    result = ser_de(fory, arr)
    assert isinstance(result, BFloat16Array)
    assert len(result) == 3
    assert result[0].to_float32() == pytest.approx(1.0)


def test_bfloat16_array_serialization_empty():
    fory = Fory(xlang=True)
    arr = BFloat16Array()
    result = ser_de(fory, arr)
    assert isinstance(result, BFloat16Array)
    assert len(result) == 0


def test_bfloat16_array_serialization_special():
    fory = Fory(xlang=True)
    arr = BFloat16Array([0.0, float("inf"), float("nan")])
    result = ser_de(fory, arr)
    assert result[0].is_zero()
    assert result[1].is_inf()
    assert result[2].is_nan()


# --------------- integration ---------------


def test_bfloat16_in_dataclass():
    from dataclasses import dataclass

    @dataclass
    class TestStruct:
        value: bfloat16
        arr: BFloat16Array

    fory = Fory(xlang=True)
    fory.register_type(TestStruct)
    obj = TestStruct(value=bfloat16(3.14), arr=BFloat16Array([1.0, 2.0]))
    result = ser_de(fory, obj)
    assert result.value.to_float32() == pytest.approx(3.14, abs=0.01)
    assert len(result.arr) == 2


def test_bfloat16_in_list():
    fory = Fory(xlang=True)
    values = [bfloat16(1.0), bfloat16(2.0)]
    result = ser_de(fory, values)
    assert len(result) == 2
    assert result[0].to_float32() == pytest.approx(1.0)


def test_bfloat16_in_map():
    fory = Fory(xlang=True)
    data = {"a": bfloat16(1.0), "b": bfloat16(2.0)}
    result = ser_de(fory, data)
    assert result["a"].to_float32() == pytest.approx(1.0)


# --------------- type registration ---------------


def test_bfloat16_type_registration():
    fory = Fory(xlang=True)
    type_info = fory.type_resolver.get_type_info(bfloat16)
    assert type_info.type_id == TypeId.BFLOAT16


def test_bfloat16_array_type_registration():
    fory = Fory(xlang=True)
    type_info = fory.type_resolver.get_type_info(BFloat16Array)
    assert type_info.type_id == TypeId.BFLOAT16_ARRAY
