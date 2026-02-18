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

from dataclasses import dataclass

import pyfory
import pytest
from pyfory import Fory
from pyfory.meta.typedef_encoder import encode_typedef
from pyfory.struct import DataClassSerializer, compute_struct_fingerprint
from pyfory.types import TypeId

try:
    import numpy as np
except ImportError:
    np = None


@dataclass
class UnsignedArrays:
    u8: pyfory.uint8_array = pyfory.field(0)
    u16: pyfory.uint16_array = pyfory.field(1)
    u32: pyfory.uint32_array = pyfory.field(2)
    u64: pyfory.uint64_array = pyfory.field(3)


def test_unsigned_array_typedef_type_ids():
    fory = Fory(xlang=True)
    fory.register_type(UnsignedArrays, namespace="test", typename="UnsignedArrays")

    typedef = encode_typedef(fory.type_resolver, UnsignedArrays)
    field_type_ids = {field.name: field.field_type.type_id for field in typedef.fields}

    assert field_type_ids == {
        "u8": TypeId.UINT8_ARRAY,
        "u16": TypeId.UINT16_ARRAY,
        "u32": TypeId.UINT32_ARRAY,
        "u64": TypeId.UINT64_ARRAY,
    }


def test_unsigned_array_fingerprint_type_ids():
    fory = Fory(xlang=True)
    serializer = DataClassSerializer(fory, UnsignedArrays)

    fingerprint = compute_struct_fingerprint(
        fory.type_resolver,
        serializer._field_names,
        serializer._serializers,
        serializer._nullable_fields,
        serializer._field_infos,
    )

    expected = f"0,{TypeId.UINT8_ARRAY},0,0;1,{TypeId.UINT16_ARRAY},0,0;2,{TypeId.UINT32_ARRAY},0,0;3,{TypeId.UINT64_ARRAY},0,0;"
    assert fingerprint == expected


@pytest.mark.skipif(np is None, reason="Requires numpy")
@pytest.mark.parametrize(
    "dtype,values",
    [
        (np.uint8, [0, 1, 255]),
        (np.uint16, [0, 1, 65535]),
        (np.uint32, [0, 1, 4294967295]),
        (np.uint64, [0, 1, 18446744073709551615]),
    ],
)
def test_unsigned_numpy_array_roundtrip_top_level(dtype, values):
    fory = Fory(xlang=True)
    arr = np.array(values, dtype=dtype)
    data = fory.serialize(arr)
    out = fory.deserialize(data)

    assert isinstance(out, np.ndarray)
    assert out.dtype == arr.dtype
    np.testing.assert_array_equal(out, arr)


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_unsigned_numpy_array_roundtrip_struct():
    fory = Fory(xlang=True)
    fory.register_type(UnsignedArrays, namespace="test", typename="UnsignedArrays")
    obj = UnsignedArrays(
        u8=np.array([0, 1, 255], dtype=np.uint8),
        u16=np.array([0, 1, 65535], dtype=np.uint16),
        u32=np.array([0, 1, 4294967295], dtype=np.uint32),
        u64=np.array([0, 1, 18446744073709551615], dtype=np.uint64),
    )

    data = fory.serialize(obj)
    out = fory.deserialize(data)

    assert isinstance(out, UnsignedArrays)
    np.testing.assert_array_equal(out.u8, obj.u8)
    np.testing.assert_array_equal(out.u16, obj.u16)
    np.testing.assert_array_equal(out.u32, obj.u32)
    np.testing.assert_array_equal(out.u64, obj.u64)
