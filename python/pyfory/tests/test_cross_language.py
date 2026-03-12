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
import datetime
import enum
import logging
import math
import os
import typing

import pyfory

try:
    import pytest
except ImportError:
    pytest = None
from dataclasses import dataclass
from pyfory.utils import lazy_import
from typing import List, Dict, Any


import numpy as np

pa = lazy_import("pyarrow")


def debug_print(*params):
    """print params if debug is needed."""
    print(*params)


def to_dict(obj):
    """Convert an object to a dictionary for comparison."""
    if hasattr(obj, "as_dict"):
        return obj.as_dict()
    elif hasattr(obj, "__dataclass_fields__"):
        from dataclasses import asdict

        return asdict(obj)
    else:
        return obj


def cross_language_test(test_func):
    env_key = "ENABLE_CROSS_LANGUAGE_TESTS"
    if pytest is not None:
        test_func = pytest.mark.skipif(
            env_key not in os.environ,
            reason=f"Pass {env_key} to enable cross-language tests",
        )(test_func)
    return test_func


Foo = pyfory.record_class_factory("Foo", ["f" + str(i) for i in range(1, 6)])
Bar = pyfory.record_class_factory("Bar", ["f" + str(i) for i in range(1, 3)])


def create_bar_schema():
    from pyfory.format import field, int32, utf8, schema

    bar_schema = schema(
        [
            field("f1", int32()),
            field("f2", utf8()),
        ]
    )
    return bar_schema


def create_foo_schema():
    from pyfory.format import field, int32, utf8, list_, map_, struct, schema

    bar_fields = [
        field("f1", int32()),
        field("f2", utf8()),
    ]
    foo_schema = schema(
        [
            field("f1", int32()),
            field("f2", utf8()),
            field("f3", list_(utf8())),
            field("f4", map_(utf8(), int32())),
            field("f5", struct(bar_fields)),
        ]
    )
    return foo_schema


@dataclass
class FooPOJO:
    f1: pyfory.int32
    f2: str
    f3: List[str]
    f4: Dict[str, pyfory.int32]
    f5: "BarPOJO"


@dataclass
class BarPOJO:
    f1: pyfory.int32
    f2: str


def create_foo(foo_cls=Foo, bar_cls=Bar):
    obj = foo_cls.__new__(foo_cls)
    obj.f1 = 1
    obj.f2 = "str"
    obj.f3 = ["str1", None, "str2"]
    obj.f4 = {"k" + str(i): i for i in range(1, 7)}
    obj.f5 = create_bar(bar_cls)
    return obj


def create_bar(cls):
    obj = cls.__new__(cls)
    obj.f1 = 1
    obj.f2 = "str"
    return obj


@dataclass
class A:
    f1: pyfory.int32
    f2: Dict[str, str]


@cross_language_test
def test_map_encoder(data_file_path):
    encoder = pyfory.encoder(A)
    a = A(f1=1, f2={"pid": "12345", "ip": "0.0.0.0", "k1": "v1"})
    with open(data_file_path, "rb+") as f:
        data_bytes = f.read()
        obj = encoder.decode(data_bytes)
        debug_print("deserialized obj", obj)
        # Compare by dict since decoder returns a record class, not the original class
        assert to_dict(obj) == to_dict(a)
        decoded_round_trip = encoder.decode(encoder.encode(a))
        assert to_dict(decoded_round_trip) == to_dict(a)
        f.seek(0)
        f.truncate()
        f.write(encoder.encode(a))


@cross_language_test
def test_encoder_without_schema(data_file_path):
    encoder = pyfory.encoder(FooPOJO)
    debug_print(encoder)
    foo = create_foo(foo_cls=FooPOJO, bar_cls=BarPOJO)
    with open(data_file_path, "rb+") as f:
        data_bytes = f.read()
        obj = encoder.decode(data_bytes)
        debug_print("deserialized foo", obj)
        # Compare by dict since decoder returns a record class, not the original class
        assert to_dict(obj) == to_dict(foo)
        f.seek(0)
        f.truncate()
        f.write(encoder.encode(foo))


@cross_language_test
def test_serialization_without_schema(data_file_path, schema=None):
    schema = schema or create_foo_schema()
    encoder = pyfory.create_row_encoder(schema)
    foo = create_foo()
    with open(data_file_path, "rb+") as f:
        data_bytes = f.read()
        buf = pyfory.Buffer(data_bytes, 0, len(data_bytes))
        row = pyfory.RowData(schema, buf)
        debug_print("row", row)
        obj = encoder.from_row(row)
        debug_print("deserialized foo", obj)
        assert str(foo.f5) == str(obj.f5)
        # class of `f5` is generated, which may be different from class
        # of deserialized `f5`
        f5 = foo.f5
        foo.f5 = None
        obj.f5 = None
        # compare data using str instead of object to workaround different
        # class name
        assert str(obj) == str(foo)
        f.seek(0)
        f.truncate()
        foo.f5 = f5
        row = encoder.to_row(foo)
        f.write(row.to_bytes())


@cross_language_test
def test_serialization_with_schema(schema_file_path, data_file_path):
    from pyfory.format import Schema

    with open(schema_file_path, "rb") as f:
        schema_bytes = f.read()
        schema = Schema.from_bytes(schema_bytes)
        debug_print("deserialized schema", schema)
        test_serialization_without_schema(data_file_path, schema)


@cross_language_test
def test_record_batch_basic(data_file_path):
    with open(data_file_path, "rb") as f:
        record_batch_bytes = f.read()
        buf = pa.py_buffer(record_batch_bytes)
        reader = pa.ipc.open_stream(buf)
        batches = [batch for batch in reader]
        assert len(batches) == 1
        batch = batches[0]
        debug_print(f"batch {batch}")


@cross_language_test
def test_record_batch(data_file_path):
    with open(data_file_path, "rb") as f:
        record_batch_bytes = f.read()
        buf = pa.py_buffer(record_batch_bytes)
        reader = pa.ipc.open_stream(buf)
        foo_schema_without_meta = pa.schema([pa.field(f.name, f.type, f.nullable) for f in create_foo_schema()])
        assert reader.schema == foo_schema_without_meta
        # debug_print(f"reader.schema {reader.schema}")
        batches = [batch for batch in reader]
        assert len(batches) == 1
        batch = batches[0]
        # debug_print(f"batch[0] {batch[0]}")

        encoder = pyfory.create_row_encoder(create_foo_schema())
        writer = pyfory.format.ArrowWriter(create_foo_schema())
        num_rows = 128
        for i in range(num_rows):
            foo = create_foo()
            row = encoder.to_row(foo)
            writer.write(row)
        record_batch = writer.finish()
        assert batch == record_batch


@cross_language_test
def test_write_multi_record_batch(schema_file_path, data_file_path):
    with open(schema_file_path, "rb") as f:
        schema_bytes = f.read()
        schema = pa.ipc.read_schema(pa.py_buffer(schema_bytes))
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
        pa.ipc.read_record_batch(pa.py_buffer(data_bytes), schema)


@cross_language_test
def test_buffer(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
        buffer = pyfory.Buffer(data_bytes)
        assert buffer.read_bool() is True
        assert buffer.read_int8() == 2**7 - 1
        assert buffer.read_int16() == 2**15 - 1
        assert buffer.read_int32() == 2**31 - 1
        assert buffer.read_int64() == 2**63 - 1
        assert math.isclose(buffer.read_float(), -1.1, rel_tol=1e-03)
        assert math.isclose(buffer.read_double(), -1.1, rel_tol=1e-03)
        assert buffer.read_var_uint32() == 100
        binary = b"ab"
        assert buffer.read_bytes(buffer.read_int32()) == binary
        buffer.write_bool(True)
        buffer.write_int8(2**7 - 1)
        buffer.write_int16(2**15 - 1)
        buffer.write_int32(2**31 - 1)
        buffer.write_int64(2**63 - 1)
        buffer.write_float(-1.1)
        buffer.write_double(-1.1)
        buffer.write_var_uint32(100)
        buffer.write_int32(len(binary))
        buffer.write_bytes(binary)
    with open(data_file_path, "wb+") as f:
        f.write(buffer.get_bytes(0, buffer.get_writer_index()))


@cross_language_test
def test_murmurhash3(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
        buffer = pyfory.Buffer(data_bytes)
        h1, h2 = pyfory.lib.mmh3.hash_buffer(bytearray([1, 2, 8]), seed=47)
        assert buffer.read_int64() == h1
        assert buffer.read_int64() == h2


@cross_language_test
def test_cross_language_serializer(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
        buffer = pyfory.Buffer(data_bytes)
        fory = pyfory.Fory(xlang=True, ref=True)
        objects = []
        assert _deserialize_and_append(fory, buffer, objects) is True
        assert _deserialize_and_append(fory, buffer, objects) is False
        assert _deserialize_and_append(fory, buffer, objects) == -1
        assert _deserialize_and_append(fory, buffer, objects) == 2**7 - 1
        assert _deserialize_and_append(fory, buffer, objects) == -(2**7)
        assert _deserialize_and_append(fory, buffer, objects) == 2**15 - 1
        assert _deserialize_and_append(fory, buffer, objects) == -(2**15)
        assert _deserialize_and_append(fory, buffer, objects) == 2**31 - 1
        assert _deserialize_and_append(fory, buffer, objects) == -(2**31)
        x = _deserialize_and_append(fory, buffer, objects)
        assert x == 2**63 - 1, x
        assert _deserialize_and_append(fory, buffer, objects) == -(2**63)
        assert _deserialize_and_append(fory, buffer, objects) == -1.0
        assert _deserialize_and_append(fory, buffer, objects) == -1.0
        assert _deserialize_and_append(fory, buffer, objects) == "str"
        day = datetime.date(2021, 11, 23)
        assert _deserialize_and_append(fory, buffer, objects) == day
        instant = datetime.datetime.fromtimestamp(100, tz=datetime.timezone.utc)
        assert _deserialize_and_append(fory, buffer, objects) == instant
        list_ = ["a", 1, -1.0, instant, day]
        assert _deserialize_and_append(fory, buffer, objects) == list_
        dict_ = {f"k{i}": v for i, v in enumerate(list_)}
        dict_.update({v: v for v in list_})
        assert _deserialize_and_append(fory, buffer, objects) == dict_
        set_ = set(list_)
        assert _deserialize_and_append(fory, buffer, objects) == set_

        # test primitive arrays
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([True, False], dtype=np.bool_),
        )
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([1, 2**15 - 1], dtype=np.int16),
        )
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([1, 2**31 - 1], dtype=np.int32),
        )
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([1, 2**63 - 1], dtype=np.int64),
        )
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([1, 2], dtype=np.float32),
        )
        np.testing.assert_array_equal(
            _deserialize_and_append(fory, buffer, objects),
            np.array([1, 2], dtype=np.float64),
        )
        new_buf = pyfory.Buffer.allocate(32)
        for obj in objects:
            fory.serialize(obj, buffer=new_buf)
    with open(data_file_path, "wb+") as f:
        f.write(new_buf.get_bytes(0, new_buf.get_writer_index()))


@cross_language_test
def _deserialize_and_append(fory, buffer, objects: list):
    obj = fory.deserialize(buffer)
    objects.append(obj)
    return obj


@cross_language_test
def test_cross_language_reference(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
        buffer = pyfory.Buffer(data_bytes)
        fory = pyfory.Fory(xlang=True, ref=True)
        new_list = fory.deserialize(buffer)
        assert new_list[0] is new_list
        new_map = new_list[1]
        assert new_map["k1"] is new_map
        assert new_map["k2"] is new_list

        new_list2 = fory.deserialize(fory.serialize(new_list))
        assert new_list2[0] is new_list2
        new_map = new_list2[1]
        assert new_map["k1"] is new_map
        assert new_map["k2"] is new_list2

        new_buf = pyfory.Buffer.allocate(32)
        fory.serialize(new_list, buffer=new_buf)
    with open(data_file_path, "wb+") as f:
        f.write(new_buf.get_bytes(0, new_buf.get_writer_index()))


@dataclass
class ComplexObject1:
    f1: Any = None
    f2: str = None
    f3: List[str] = None
    f4: Dict[pyfory.int8, pyfory.int32] = None
    f5: pyfory.int8 = None
    f6: pyfory.int16 = None
    f7: pyfory.int32 = None
    f8: pyfory.int64 = None
    f9: pyfory.float32 = None
    f10: pyfory.float64 = None
    f11: pyfory.int16_array = None
    f12: List[pyfory.int16] = None


@dataclass
class ComplexObject2:
    f1: Any
    f2: Dict[pyfory.int8, pyfory.int32]


def test_serialize_simple_struct_local():
    fory = pyfory.Fory(xlang=True, ref=True)
    fory.register_type(ComplexObject2, namespace="test", typename="ComplexObject2")
    obj = ComplexObject2(f1=True, f2={-1: 2})
    new_buf = fory.serialize(obj)
    assert fory.deserialize(new_buf) == obj


@cross_language_test
def test_serialize_simple_struct(data_file_path):
    compatible = "compatible" in data_file_path
    fory = pyfory.Fory(xlang=True, ref=True, compatible=compatible)
    fory.register_type(ComplexObject2, namespace="test", typename="ComplexObject2")
    obj = ComplexObject2(f1=True, f2={-1: 2})
    struct_round_back(data_file_path, fory, obj)


@cross_language_test
def test_register_by_id(data_file_path):
    compatible = "compatible" in data_file_path
    fory = pyfory.Fory(xlang=True, ref=True, compatible=compatible)
    fory.register_type(ComplexObject2, type_id=100)
    obj = ComplexObject2(f1=True, f2={-1: 2})
    struct_round_back(data_file_path, fory, obj)


class SomeClass:
    f1: "SomeClass"
    f2: Dict[str, str]
    f3: Dict[str, str]


def test_custom_class_roundtrip():
    fory = pyfory.Fory(ref=True)
    fory.register_type(SomeClass, typename="example.SomeClass")
    obj1 = SomeClass()
    obj1.f2 = {"k1": "v1", "k2": "v2"}
    obj1.f1, obj1.f3 = obj1, obj1.f2
    data1 = fory.serialize(obj1)
    obj2 = fory.deserialize(data1)
    data2 = fory.serialize(obj2)
    assert data1 == data2
    # bytes can be data serialized by other languages.
    # due to the self-referential nature of this object,
    # direct `==` comparison will fail.
    # 1. Serialize `obj1` to `data1`
    # 2. Deserialize `data1` to `obj2`
    # 3. Serialize `obj2` to `data2`
    # If `data1 == data2`, the round-trip preserves value equivalence.
    # print(data1)
    # print(data2)
    # print(obj1)
    # print(obj2)


class EnumTestClass(enum.Enum):
    FOO = 0
    BAR = 1


@dataclass
class EnumFieldStruct:
    f1: EnumTestClass
    f2: EnumTestClass
    f3: str


@cross_language_test
def test_enum_field(data_file_path):
    compatible = "compatible" in data_file_path
    fory = pyfory.Fory(xlang=True, ref=False, compatible=compatible)
    fory.register_type(EnumTestClass, namespace="test", typename="EnumTestClass")
    fory.register_type(EnumFieldStruct, namespace="test", typename="EnumFieldStruct")
    obj = EnumFieldStruct(f1=EnumTestClass.FOO, f2=EnumTestClass.BAR, f3="abc")
    struct_round_back(data_file_path, fory, obj)


@cross_language_test
def test_enum_field_register_by_id(data_file_path):
    compatible = "compatible" in data_file_path
    fory = pyfory.Fory(xlang=True, ref=False, compatible=compatible)
    fory.register_type(EnumTestClass, type_id=1)
    fory.register_type(EnumFieldStruct, type_id=2)
    obj = EnumFieldStruct(f1=EnumTestClass.FOO, f2=EnumTestClass.BAR, f3="abc")
    struct_round_back(data_file_path, fory, obj)


@cross_language_test
def test_struct_hash(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
    debug_print(f"len {len(data_bytes)}")
    read_hash = pyfory.Buffer(data_bytes).read_int32()
    fory = pyfory.Fory(xlang=True, ref=True)
    fory.register_type(ComplexObject1, typename="ComplexObject1")
    serializer = fory.type_resolver.get_serializer(ComplexObject1)._replace()
    from pyfory.struct import compute_struct_meta

    v = compute_struct_meta(
        fory.type_resolver,
        serializer._field_names,
        serializer._serializers,
        serializer._nullable_fields,
    )[0]
    assert read_hash == v, (read_hash, v)


@cross_language_test
def test_serialize_complex_struct(data_file_path):
    compatible = "compatible" in data_file_path
    fory = pyfory.Fory(xlang=True, ref=True, compatible=compatible)
    fory.register_type(ComplexObject1, namespace="test", typename="ComplexObject1")
    fory.register_type(ComplexObject2, namespace="test", typename="ComplexObject2")

    obj2 = ComplexObject2(f1=True, f2={-1: 2})
    obj1 = ComplexObject1(
        f1=obj2,
        f2="abc",
        f3=["abc", "abc"],
        f4={1: 2},
        f5=2**7 - 1,
        f6=2**15 - 1,
        f7=2**31 - 1,
        f8=2**63 - 1,
        f9=1.0 / 2,
        f10=1 / 3.0,
        f11=array.array("h", [1, 2]),
        f12=[-1, 4],
    )
    struct_round_back(data_file_path, fory, obj1)


def _deep_equal(left, right):
    if np is not None:
        if isinstance(left, np.ndarray) or isinstance(right, np.ndarray):
            if isinstance(left, np.ndarray) and isinstance(right, np.ndarray):
                return np.array_equal(left, right)
            if isinstance(left, np.ndarray) and isinstance(right, array.array):
                return np.array_equal(left, np.array(right, dtype=left.dtype))
            if isinstance(right, np.ndarray) and isinstance(left, array.array):
                return np.array_equal(right, np.array(left, dtype=right.dtype))
    if isinstance(left, array.array) and isinstance(right, array.array):
        return left == right
    if hasattr(left, "__dataclass_fields__") and hasattr(right, "__dataclass_fields__"):
        if left.__class__ is not right.__class__:
            return False
        for field in left.__dataclass_fields__.keys():
            if not _deep_equal(getattr(left, field), getattr(right, field)):
                return False
        return True
    if isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return False
        for l_item, r_item in zip(left, right):
            if not _deep_equal(l_item, r_item):
                return False
        return True
    if isinstance(left, tuple) and isinstance(right, tuple):
        if len(left) != len(right):
            return False
        for l_item, r_item in zip(left, right):
            if not _deep_equal(l_item, r_item):
                return False
        return True
    if isinstance(left, dict) and isinstance(right, dict):
        if left.keys() != right.keys():
            return False
        for key in left.keys():
            if not _deep_equal(left[key], right[key]):
                return False
        return True
    return left == right


def _assert_deep_equal(left, right, msg_prefix=""):
    assert _deep_equal(left, right), f"{msg_prefix}left {left}\n right {right}"


def struct_round_back(data_file_path, fory, obj1):
    # new_buf = fory.serialize(obj1)
    # assert fory.deserialize(new_buf) == obj1
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
    debug_print(f"len {len(data_bytes)}")
    debug_print(f"first 100 bytes: {data_bytes[:100].hex()}")
    new_obj = fory.deserialize(data_bytes)
    debug_print(new_obj)
    _assert_deep_equal(new_obj, obj1, "struct_round_back mismatch: ")
    new_buf = fory.serialize(new_obj)
    debug_print(f"new_buf size {len(new_buf)}")
    _assert_deep_equal(fory.deserialize(new_buf), new_obj, "round-trip mismatch: ")
    with open(data_file_path, "wb+") as f:
        f.write(new_buf)


class ComplexObject1Serializer(pyfory.serializer.Serializer):
    def write(self, buffer, value):
        self.fory.write_ref(buffer, value.f1)
        self.fory.write_ref(buffer, value.f2)
        self.fory.write_ref(buffer, value.f3)

    def read(self, buffer):
        obj = ComplexObject1(*([None] * len(typing.get_type_hints(ComplexObject1).keys())))
        self.fory.ref_resolver.reference(obj)
        obj.f1 = self.fory.read_ref(buffer)
        obj.f2 = self.fory.read_ref(buffer)
        obj.f3 = self.fory.read_ref(buffer)
        return obj


@cross_language_test
def test_register_serializer(data_file_path):
    with open(data_file_path, "rb") as f:
        data_bytes = f.read()
    buffer = pyfory.Buffer(data_bytes)

    fory = pyfory.Fory(xlang=True, ref=True)
    fory.register_type(
        ComplexObject1,
        typename="test.ComplexObject1",
        serializer=ComplexObject1Serializer(fory, ComplexObject1),
    )
    expected = ComplexObject1(*[None] * 12)
    expected.f1, expected.f2, expected.f3 = True, "abc", ["abc", "abc"]
    bytes1 = fory.serialize(expected)
    assert fory.deserialize(bytes1) == expected
    new_obj = fory.deserialize(buffer)

    debug_print(new_obj)
    assert new_obj == expected
    new_buf = pyfory.Buffer.allocate(32)
    fory.serialize(new_obj, buffer=new_buf)
    bytes1 = fory.serialize(new_obj)
    assert len(bytes1) == len(data_bytes), (bytes1, data_bytes)
    # header can differ across writers
    assert bytes1[8:] == data_bytes[8:]
    assert fory.deserialize(fory.serialize(new_obj)) == new_obj, new_obj
    print(f"test_register_serializer: {new_obj}")
    with open(data_file_path, "wb+") as f:
        f.write(new_buf.get_bytes(0, new_buf.get_writer_index()))


@cross_language_test
def test_oob_buffer(in_band_file_path, out_of_band_file_path):
    with open(in_band_file_path, "rb") as f:
        in_band_bytes = f.read()
    with open(out_of_band_file_path, "rb") as f:
        out_of_band_buffer = pyfory.Buffer(f.read())
    fory = pyfory.Fory(xlang=True, ref=True)
    n_buffers = out_of_band_buffer.read_int32()
    buffers = []
    for i in range(n_buffers):
        length = out_of_band_buffer.read_int32()
        reader_index = out_of_band_buffer.get_reader_index()
        buffers.append(out_of_band_buffer.slice(reader_index, length))
        out_of_band_buffer.set_reader_index(out_of_band_buffer.get_reader_index() + length)
    new_obj = fory.deserialize(in_band_bytes, buffers)
    obj = [bytes(bytearray([0, 1])) for _ in range(10)]
    assert new_obj == obj, (obj, new_obj)

    buffer_objects = []
    counter = 0

    def buffer_callback(binary_object):
        nonlocal counter
        counter += 1
        if counter % 2 == 0:
            buffer_objects.append(binary_object)
            return False
        else:
            return True

    serialized = fory.serialize(obj, buffer_callback=buffer_callback)
    # in_band_bytes size may be different because it may contain language-specific meta.
    debug_print(f"{len(serialized), len(in_band_bytes)}")
    debug_print(f"deserialized from other language {new_obj}")
    debug_print(f"deserialized from python {fory.deserialize(serialized, [o.getbuffer() for o in buffer_objects])}")
    fory.deserialize(serialized, [o.getbuffer() for o in buffer_objects])
    with open(in_band_file_path, "wb+") as f:
        f.write(serialized)
    out_of_band_buffer.write_int32(len(buffer_objects))
    for buffer_object in buffer_objects:
        out_of_band_buffer.write_int32(buffer_object.total_bytes())
        buffer_object.write_to(out_of_band_buffer)
    with open(out_of_band_file_path, "wb+") as f:
        f.write(out_of_band_buffer.to_bytes(0, out_of_band_buffer.get_writer_index()))


@cross_language_test
def test_cross_language_meta_share(data_file_path):
    """Test cross-language meta sharing with ComplexObject2."""
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)

    @dataclass
    class ComplexObject2:
        f1: Any
        f2: Dict[pyfory.int8, pyfory.int32]

    fory.register_type(ComplexObject2, namespace="test", typename="ComplexObject2")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize Java-generated data with meta share
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized object: {obj}")

    # Verify the object structure
    assert obj.f1 is True  # Boolean value
    assert isinstance(obj.f2, dict)
    assert obj.f2[-1] == 2  # Dict[Int8, Int32] with key=-1, value=2

    # Serialize back with meta share
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized data length: {len(new_serialized)}")

    # Verify round-trip
    round_trip_obj = fory.deserialize(new_serialized)
    _assert_deep_equal(round_trip_obj, obj, "meta share round-trip mismatch: ")

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


@cross_language_test
def test_cross_language_meta_share_complex(data_file_path):
    """Test cross-language meta sharing with complex nested objects."""
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)

    @dataclass
    class ComplexObject2:
        f1: Any
        f2: Dict[pyfory.int8, pyfory.int32]

    @dataclass
    class ComplexObject1:
        f1: Any
        f2: str
        f3: List[str]
        f4: Dict[pyfory.int8, pyfory.int32]
        f5: pyfory.int8
        f6: pyfory.int16
        f7: pyfory.int32
        f8: pyfory.int64
        f9: pyfory.float32
        f10: pyfory.float64
        f11: pyfory.int16_array
        f12: List[pyfory.int16]

    fory.register_type(ComplexObject1, namespace="test", typename="ComplexObject1")
    fory.register_type(ComplexObject2, namespace="test", typename="ComplexObject2")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading complex data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize Java-generated complex object with meta share
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized complex object: {obj}")

    # Verify the nested object structure
    assert hasattr(obj, "f1") and hasattr(obj.f1, "f1") and hasattr(obj.f1, "f2")
    assert obj.f1.f1 is True
    assert isinstance(obj.f1.f2, dict)
    assert obj.f2 == "meta_share_test"
    assert obj.f3 == ["compatible", "mode"]

    # Serialize back with meta share
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized complex data length: {len(new_serialized)}")

    # Verify round-trip
    round_trip_obj = fory.deserialize(new_serialized)
    _assert_deep_equal(round_trip_obj, obj, "meta share complex round-trip mismatch: ")

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


@cross_language_test
def test_schema_evolution(data_file_path):
    """Test schema evolution compatibility."""
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)

    # Same V1 class reading V1 data - should work perfectly
    @dataclass
    class CompatTestV1:
        name: str
        age: pyfory.int32  # Use specific fory type to match Java Integer

    fory.register_type(CompatTestV1, namespace="test", typename="CompatTest")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading schema evolution data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize V1 data into V1 object
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized V1 object: {obj}")

    # Verify the object structure
    assert obj.name == "Schema Evolution Test"
    assert obj.age == 42

    # Test round trip
    round_trip_obj = fory.deserialize(fory.serialize(obj))
    assert round_trip_obj == obj

    # Serialize back
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized data length: {len(new_serialized)}")
    assert fory.deserialize(new_serialized) == obj

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


@cross_language_test
def test_backward_compatibility(data_file_path):
    """Test backward compatibility - old version reading new data."""
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)

    # Version 1 class (original) reading Version 2 data (should ignore unknown fields)
    @dataclass
    class CompatTestV1:
        name: str
        age: pyfory.int32

    fory.register_type(CompatTestV1, namespace="test", typename="CompatTest")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading V2 data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize V2 data into V1 object (ignoring email field)
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized V1 object from V2 data: {obj}")

    # Verify the object structure - only V1 fields should be present
    assert obj.name == "Bob"
    assert obj.age == 30
    # email field should be ignored/not present

    # Serialize back with V1 schema
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized V1 data length: {len(new_serialized)}")

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


@cross_language_test
def test_field_reordering_compatibility(data_file_path):
    """Test field reordering compatibility in metashare mode."""
    fory = pyfory.Fory(xlang=True, ref=True, compatible=True)

    # Version 3 class with reordered fields matching Java CompatTestV3
    @dataclass
    class CompatTestV3:
        age: pyfory.int32  # Reordered (was second in V1)
        name: str  # Reordered (was first in V1)
        email: str
        active: bool  # New field

    fory.register_type(CompatTestV3, namespace="test", typename="CompatTest")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading V3 reordered data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize V3 data into V3 object
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized V3 object from V3 reordered data: {obj}")

    # Verify the object structure
    assert obj.age == 35
    assert obj.name == "Charlie"
    assert obj.email == "charlie@example.com"
    assert obj.active

    # Serialize back with V3 schema
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized V3 data length: {len(new_serialized)}")

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


@cross_language_test
def test_cross_version_compatibility(data_file_path):
    """Test mixed version compatibility."""
    fory = pyfory.Fory(xlang=True, compatible=True, ref=True)

    @dataclass
    class CompatTestV1:
        name: str
        age: pyfory.int32

    @dataclass
    class CompatTestV2:
        name: str
        age: pyfory.int32
        email: str = "default@example.com"

    @dataclass
    class CompatContainer:
        oldObject: CompatTestV1
        newObject: CompatTestV2

    fory.register_type(CompatContainer, namespace="test", typename="CompatContainer")
    fory.register_type(CompatTestV1, namespace="test", typename="CompatTestV1")
    fory.register_type(CompatTestV2, namespace="test", typename="CompatTestV2")

    with open(data_file_path, "rb") as f:
        data_bytes = f.read()

    debug_print(f"Reading mixed version data of length {len(data_bytes)} from {data_file_path}")

    # Deserialize mixed version container
    obj = fory.deserialize(data_bytes)
    debug_print(f"Deserialized mixed version container: {obj}")

    # Verify the nested objects
    assert obj.oldObject.name == "Old Format", obj.oldObject.name
    assert obj.oldObject.age == 20, obj.oldObject.age
    assert obj.newObject.name == "New Format", obj.newObject.name
    assert obj.newObject.age == 25, obj.newObject.age
    assert obj.newObject.email == "new@example.com", obj.newObject.email

    # Serialize back
    new_serialized = fory.serialize(obj)
    debug_print(f"Re-serialized mixed version data length: {len(new_serialized)}")

    # Write back for Java to verify
    with open(data_file_path, "wb") as f:
        f.write(new_serialized)


if __name__ == "__main__":
    """This file will be executed by CrossLanguageTest.java in fory-core/fory-format module and
    fory_xlang_test.go in go/fory module
    The test cases in this file are used to test the cross-language serialization and deserialization
    functionality.
    """

    import sys

    print(f"Execute {sys.argv}")
    try:
        args = sys.argv[1:]
        assert len(args) > 0
        func = getattr(sys.modules[__name__], args[0].replace("_compatible", ""))
        if not func:
            raise Exception("Unknown args {}".format(args))
        func(*args[1:])
    except BaseException as e:
        logging.exception("Execute %s failed with %s", args, e)
        raise
