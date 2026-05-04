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
from dataclasses import dataclass
from typing import Dict, List

import pyfory
import pytest
from pyfory import Fory
from pyfory.meta.typedef_encoder import encode_typedef
from pyfory.serializer import (
    ForyArrayFieldSerializer,
    ForyArrayListAdapterSerializer,
    Numpy1DArraySerializer,
    PyArraySerializer,
    fory_array_serializer_type,
)
from pyfory.struct import DataClassSerializer, compute_struct_fingerprint
from pyfory.types import TypeId

try:
    import numpy as np
except ImportError:
    np = None


@dataclass
class UnsignedArrays:
    u8: pyfory.NDArray[pyfory.UInt8] = pyfory.field(0)
    u16: pyfory.NDArray[pyfory.UInt16] = pyfory.field(1)
    u32: pyfory.NDArray[pyfory.UInt32] = pyfory.field(2)
    u64: pyfory.NDArray[pyfory.UInt64] = pyfory.field(3)


@dataclass
class DenseListArray:
    values: pyfory.Array[pyfory.Int32] = pyfory.field(0)
    flags: pyfory.Array[bool] = pyfory.field(1)


@dataclass
class ReducedPrecisionArrays:
    halves: pyfory.NDArray[pyfory.Float16] = pyfory.field(0)
    dense_halves: pyfory.Array[pyfory.Float16] = pyfory.field(1)


@dataclass
class PyDenseArray:
    values: pyfory.PyArray[pyfory.Int32] = pyfory.field(0)


@dataclass
class CompatibleArrayCarrier:
    values: pyfory.Array[pyfory.Int32] = pyfory.field(0)


@dataclass
class CompatibleNDArrayCarrier:
    values: pyfory.NDArray[pyfory.Int32] = pyfory.field(0)


@dataclass
class CompatiblePyArrayCarrier:
    values: pyfory.PyArray[pyfory.Int32] = pyfory.field(0)


@dataclass
class NestedDenseArrays:
    values: List[pyfory.Array[pyfory.Int32]] = pyfory.field(0)
    by_name: Dict[str, pyfory.Array[pyfory.UInt8]] = pyfory.field(1)


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
    serializer = DataClassSerializer(fory.type_resolver, UnsignedArrays)

    fingerprint = compute_struct_fingerprint(
        fory.type_resolver,
        serializer._field_names,
        serializer._serializers,
        serializer._nullable_fields,
        serializer._field_infos,
    )

    expected = f"0,{TypeId.UINT8_ARRAY},0,0;1,{TypeId.UINT16_ARRAY},0,0;2,{TypeId.UINT32_ARRAY},0,0;3,{TypeId.UINT64_ARRAY},0,0;"
    assert fingerprint == expected


def test_array_typehint_roundtrips_public_dense_wrappers():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=pyfory.Int32Array([1, -2, 3]), flags=pyfory.BoolArray([True, False, True]))

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.values, pyfory.Int32Array)
    assert isinstance(out.flags, pyfory.BoolArray)
    out.values.append(4)
    assert out.values.pop() == 4
    assert out.values == [1, -2, 3]
    assert out.flags == [True, False, True]


def test_array_typehint_roundtrips_list_value_through_adapter():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=[1, -2, 3], flags=[True, False, True])

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.values, pyfory.Int32Array)
    assert isinstance(out.flags, pyfory.BoolArray)
    assert out.values == [1, -2, 3]
    assert out.flags == [True, False, True]


def test_array_typehint_roundtrips_array_array_value():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=array.array("i", [1, -2, 3]), flags=[True])

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.values, pyfory.Int32Array)
    assert out.values == [1, -2, 3]


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_array_typehint_roundtrips_ndarray_value():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=np.array([1, -2, 3], dtype=np.int32), flags=[True])

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.values, pyfory.Int32Array)
    assert out.values == [1, -2, 3]


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_nested_array_typehint_uses_declared_carrier_dispatch():
    fory = Fory(xlang=True)
    fory.register_type(NestedDenseArrays, namespace="test", typename="NestedDenseArrays")
    obj = NestedDenseArrays(
        values=[
            np.array([1, -2, 3], dtype=np.int32),
            np.array([4, 5], dtype=np.int32),
        ],
        by_name={"u8": np.array([200, 250], dtype=np.uint8)},
    )

    out = fory.deserialize(fory.serialize(obj))

    assert [list(values) for values in out.values] == [[1, -2, 3], [4, 5]]
    assert list(out.by_name["u8"]) == [200, 250]
    assert all(isinstance(values, pyfory.Int32Array) for values in out.values)
    assert isinstance(out.by_name["u8"], pyfory.UInt8Array)


def test_array_typehint_uses_distinct_carrier_serializers():
    fory = Fory(xlang=True)
    serializer = DataClassSerializer(fory.type_resolver, DenseListArray)
    value_serializer = serializer._serializers[serializer._field_names.index("values")]

    assert isinstance(value_serializer, ForyArrayFieldSerializer)
    assert isinstance(value_serializer.wrapper_serializer, fory_array_serializer_type(TypeId.INT32_ARRAY))
    assert isinstance(value_serializer.list_adapter_serializer, ForyArrayListAdapterSerializer)
    assert isinstance(value_serializer.pyarray_serializer, PyArraySerializer)
    assert np is None or isinstance(value_serializer.ndarray_serializer, Numpy1DArraySerializer)
    assert type(value_serializer.wrapper_serializer) is not type(value_serializer.list_adapter_serializer)
    assert type(value_serializer.wrapper_serializer) is not type(value_serializer.pyarray_serializer)
    if np is not None:
        assert type(value_serializer.wrapper_serializer) is not type(value_serializer.ndarray_serializer)


def test_array_typehint_list_adapter_reports_invalid_index():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=[1, 2**31], flags=[True])

    with pytest.raises(OverflowError, match=r"values\[1\]"):
        fory.serialize(obj)


def test_array_typehint_rejects_wrong_array_array_typecode():
    fory = Fory(xlang=True)
    fory.register_type(DenseListArray, namespace="test", typename="DenseListArray")
    obj = DenseListArray(values=array.array("h", [1, 2, 3]), flags=[True])

    with pytest.raises(TypeError, match="typecode"):
        fory.serialize(obj)


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_float16_ndarray_and_array_typehints_roundtrip():
    fory = Fory(xlang=True)
    fory.register_type(ReducedPrecisionArrays, namespace="test", typename="ReducedPrecisionArrays")
    obj = ReducedPrecisionArrays(
        halves=np.array([0.0, 1.0, -2.0], dtype=np.float16),
        dense_halves=np.array([0.0, 1.0, -2.0], dtype=np.float16),
    )

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.halves, np.ndarray)
    assert out.halves.dtype == np.dtype(np.float16)
    np.testing.assert_array_equal(out.halves, obj.halves)
    assert isinstance(out.dense_halves, pyfory.Float16Array)
    assert list(out.dense_halves.to_buffer()) == [0x0000, 0x3C00, 0xC000]


def test_pyarray_typehint_roundtrips_python_array_carrier():
    fory = Fory(xlang=True)
    fory.register_type(PyDenseArray, namespace="test", typename="PyDenseArray")
    obj = PyDenseArray(values=array.array("i", [1, -2, 3]))

    out = fory.deserialize(fory.serialize(obj))

    assert isinstance(out.values, array.array)
    assert out.values.tolist() == [1, -2, 3]


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_compatible_array_field_reads_as_ndarray_carrier():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    writer.register_type(CompatibleArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")
    reader.register_type(CompatibleNDArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")

    out = reader.deserialize(writer.serialize(CompatibleArrayCarrier(values=pyfory.Int32Array([1, 2, 3]))))

    assert isinstance(out, CompatibleNDArrayCarrier)
    assert isinstance(out.values, np.ndarray)
    assert out.values.dtype == np.dtype(np.int32)
    np.testing.assert_array_equal(out.values, np.array([1, 2, 3], dtype=np.int32))


@pytest.mark.skipif(np is None, reason="Requires numpy")
def test_compatible_ndarray_field_reads_as_pyarray_carrier():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    writer.register_type(CompatibleNDArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")
    reader.register_type(CompatiblePyArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")

    out = reader.deserialize(writer.serialize(CompatibleNDArrayCarrier(values=np.array([4, 5, 6], dtype=np.int32))))

    assert isinstance(out, CompatiblePyArrayCarrier)
    assert isinstance(out.values, array.array)
    assert out.values.itemsize == 4
    assert PyArraySerializer.typecode_dict[out.values.typecode][2] == TypeId.INT32_ARRAY
    assert out.values.tolist() == [4, 5, 6]


def test_compatible_pyarray_field_reads_as_fory_array_carrier():
    writer = Fory(xlang=True, compatible=True)
    reader = Fory(xlang=True, compatible=True)
    writer.register_type(CompatiblePyArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")
    reader.register_type(CompatibleArrayCarrier, namespace="test", typename="CompatibleArrayCarrier")

    out = reader.deserialize(writer.serialize(CompatiblePyArrayCarrier(values=array.array("i", [7, 8, 9]))))

    assert isinstance(out, CompatibleArrayCarrier)
    assert isinstance(out.values, pyfory.Int32Array)
    assert out.values == [7, 8, 9]


@pytest.mark.skipif(np is None, reason="Requires numpy")
@pytest.mark.parametrize(
    "dtype,values,wrapper_type",
    [
        (np.uint8, [0, 1, 255], pyfory.UInt8Array),
        (np.uint16, [0, 1, 65535], pyfory.UInt16Array),
        (np.uint32, [0, 1, 4294967295], pyfory.UInt32Array),
        (np.uint64, [0, 1, 18446744073709551615], pyfory.UInt64Array),
    ],
)
def test_unsigned_numpy_array_roundtrip_top_level(dtype, values, wrapper_type):
    fory = Fory(xlang=True)
    arr = np.array(values, dtype=dtype)
    data = fory.serialize(arr)
    out = fory.deserialize(data)

    assert isinstance(out, wrapper_type)
    assert out == values


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
