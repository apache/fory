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

import math
import pytest

from pyfory import Fory
from pyfory.bfloat16 import bfloat16
from pyfory.bfloat16_array import BFloat16Array
from pyfory.types import TypeId


def ser_de(fory, value):
    data = fory.serialize(value)
    return fory.deserialize(data)


def test_bfloat16_basic():
    bf16 = bfloat16(3.14)
    assert isinstance(bf16, bfloat16)
    assert bf16.to_float32() == pytest.approx(3.14, abs=0.01)
    bits = bf16.to_bits()
    assert bfloat16.from_bits(bits).to_bits() == bits


def test_bfloat16_special_values():
    assert bfloat16(float("nan")).is_nan()
    assert bfloat16(float("inf")).is_inf()
    assert bfloat16(float("-inf")).is_inf()
    assert bfloat16(0.0).is_zero()
    assert bfloat16(1.0).is_finite()
    assert not bfloat16(1.0).is_nan()
    assert not bfloat16(1.0).is_inf()


def test_bfloat16_conversion():
    assert bfloat16(0.0).to_float32() == 0.0
    assert bfloat16(1.0).to_float32() == 1.0
    assert bfloat16(-1.0).to_float32() == -1.0
    assert bfloat16(3.14).to_float32() == pytest.approx(3.14, abs=0.01)
    assert math.isnan(bfloat16(float("nan")).to_float32())
    assert math.isinf(bfloat16(float("inf")).to_float32())
    assert math.isinf(bfloat16(float("-inf")).to_float32())


def test_bfloat16_serialization():
    fory = Fory(xlang=True)
    assert ser_de(fory, bfloat16(0.0)).to_bits() == bfloat16(0.0).to_bits()
    assert ser_de(fory, bfloat16(1.0)).to_bits() == bfloat16(1.0).to_bits()
    assert ser_de(fory, bfloat16(3.14)).to_bits() == bfloat16(3.14).to_bits()
    assert ser_de(fory, bfloat16(float("inf"))).is_inf()
    assert ser_de(fory, bfloat16(float("nan"))).is_nan()


def test_bfloat16_array_basic():
    arr = BFloat16Array([1.0, 2.0, 3.14])
    assert len(arr) == 3
    assert arr[0].to_float32() == pytest.approx(1.0)
    arr[0] = bfloat16(5.0)
    assert arr[0].to_float32() == pytest.approx(5.0)


def test_bfloat16_array_serialization():
    fory = Fory(xlang=True)
    arr = BFloat16Array([1.0, 2.0, 3.14])
    result = ser_de(fory, arr)
    assert isinstance(result, BFloat16Array)
    assert len(result) == 3
    assert result[0].to_float32() == pytest.approx(1.0)


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


def test_bfloat16_type_registration():
    fory = Fory(xlang=True)
    type_info = fory.type_resolver.get_type_info(bfloat16)
    assert type_info.type_id == TypeId.BFLOAT16


def test_bfloat16_array_type_registration():
    fory = Fory(xlang=True)
    type_info = fory.type_resolver.get_type_info(BFloat16Array)
    assert type_info.type_id == TypeId.BFLOAT16_ARRAY
