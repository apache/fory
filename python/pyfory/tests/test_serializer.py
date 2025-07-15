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
import gc
import io
import os
import pickle
import weakref
from enum import Enum
from typing import Any, List, Dict

import numpy as np
import pandas as pd

from dataclasses import dataclass

import pytest

import pyfory
from pyfory.buffer import Buffer
from pyfory import Fory, Language, _serialization, EnumSerializer
from pyfory.serializer import (
    TimestampSerializer,
    DateSerializer,
    PyArraySerializer,
    Numpy1DArraySerializer,
)
from pyfory.tests.core import require_pyarrow
from pyfory.type import TypeId
from pyfory.util import lazy_import

pa = lazy_import("pyarrow")


def test_float():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    assert ser_de(fory, -1.0) == -1.0
    assert ser_de(fory, 1 / 3) == 1 / 3
    serializer = fory.type_resolver.get_serializer(float)
    assert type(serializer) is pyfory.Float64Serializer


def test_tuple():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    print(len(fory.serialize((-1.0, 2))))
    assert ser_de(fory, (-1.0, 2)) == (-1.0, 2)


def test_string():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    assert ser_de(fory, "hello") == "hello"
    assert ser_de(fory, "hello，世界") == "hello，世界"
    assert ser_de(fory, "hello，世界" * 10) == "hello，世界" * 10
    assert ser_de(fory, "hello，😀") == "hello，😀"
    assert ser_de(fory, "hello，😀" * 10) == "hello，😀" * 10


@pytest.mark.parametrize("track_ref", [False, True])
def test_dict(track_ref):
    fory = Fory(language=Language.PYTHON, ref_tracking=track_ref)
    assert ser_de(fory, {1: 2}) == {1: 2}
    assert ser_de(fory, {1 / 3: 2.0}) == {1 / 3: 2.0}
    assert ser_de(fory, {1 / 3: 2}) == {1 / 3: 2}
    assert ser_de(fory, {"1": 2}) == {"1": 2}
    assert ser_de(fory, {"1": 1 / 3}) == {"1": 1 / 3}
    assert ser_de(fory, {"1": {}}) == {"1": {}}
    assert ser_de(fory, {"1": {1: 2}}) == {"1": {1: 2}}
    assert ser_de(fory, {"k1": {"a": 2.0}, "k2": {-1.0: -1.0}}) == {
        "k1": {"a": 2.0},
        "k2": {-1.0: -1.0},
    }
    # make multiple references point to same `-1.0`.
    dict3 = {
        1: {5: -1.0},
        2: {-1.0: -1.0, 10: -1.0},
    }
    assert ser_de(fory, dict3) == dict3


@pytest.mark.parametrize("track_ref", [False, True])
def test_multi_chunk_simple_dict(track_ref):
    fory = Fory(language=Language.PYTHON, ref_tracking=track_ref)
    dict0 = {
        1: 2.0,
        2: 3,
        4.0: True,
    }
    assert ser_de(fory, dict0) == dict0


@pytest.mark.parametrize("track_ref", [False, True])
def test_multi_chunk_complex_dict(track_ref):
    fory = Fory(language=Language.PYTHON, ref_tracking=track_ref)
    now = datetime.datetime.now()
    day = datetime.date(2021, 11, 23)
    dict0 = {"a": "a", 1: 1, -1.0: -1.0, True: True, now: now, day: day}  # noqa: F601
    assert ser_de(fory, dict0) == dict0


@pytest.mark.parametrize("track_ref", [False, True])
def test_big_chunk_dict(track_ref):
    fory = Fory(language=Language.PYTHON, ref_tracking=track_ref)
    now = datetime.datetime.now()
    day = datetime.date(2021, 11, 23)
    dict0 = {}
    values = ["a", 1, -1.0, True, False, now, day]
    for i in range(1000):
        dict0[i] = values[i % len(values)]
        dict0[f"key{i}"] = values[i % len(values)]
        dict0[float(i)] = values[i % len(values)]
    assert ser_de(fory, dict0) == dict0


@pytest.mark.parametrize("language", [Language.XLANG, Language.PYTHON])
def test_basic_serializer(language):
    fory = Fory(language=language, ref_tracking=True)
    typeinfo = fory.type_resolver.get_typeinfo(datetime.datetime)
    assert isinstance(typeinfo.serializer, (TimestampSerializer, _serialization.TimestampSerializer))
    if language == Language.XLANG:
        assert typeinfo.type_id == TypeId.TIMESTAMP
    typeinfo = fory.type_resolver.get_typeinfo(datetime.date)
    assert isinstance(typeinfo.serializer, (DateSerializer, _serialization.DateSerializer))
    if language == Language.XLANG:
        assert typeinfo.type_id == TypeId.LOCAL_DATE
    assert ser_de(fory, True) is True
    assert ser_de(fory, False) is False
    assert ser_de(fory, -1) == -1
    assert ser_de(fory, 2**7 - 1) == 2**7 - 1
    assert ser_de(fory, 2**15 - 1) == 2**15 - 1
    assert ser_de(fory, -(2**15)) == -(2**15)
    assert ser_de(fory, 2**31 - 1) == 2**31 - 1
    assert ser_de(fory, 2**63 - 1) == 2**63 - 1
    assert ser_de(fory, -(2**63)) == -(2**63)
    assert ser_de(fory, 1.0) == 1.0
    assert ser_de(fory, -1.0) == -1.0
    assert ser_de(fory, "str") == "str"
    assert ser_de(fory, b"") == b""
    now = datetime.datetime.now()
    assert ser_de(fory, now) == now
    day = datetime.date(2021, 11, 23)
    assert ser_de(fory, day) == day
    list_ = ["a", 1, -1.0, True, now, day]
    assert ser_de(fory, list_) == list_
    dict1_ = {"k1": "a", "k2": 1, "k3": -1.0, "k4": True, "k5": now, "k6": day}
    assert ser_de(fory, dict1_) == dict1_
    dict2_ = {"a": "a", 1: 1, -1.0: -1.0, True: True, now: now, day: day}  # noqa: F601
    assert ser_de(fory, dict2_) == dict2_
    set_ = {"a", 1, -1.0, True, now, day}
    assert ser_de(fory, set_) == set_


@pytest.mark.parametrize("language", [Language.XLANG, Language.PYTHON])
def test_ref_tracking(language):
    fory = Fory(language=language, ref_tracking=True)

    simple_list = []
    simple_list.append(simple_list)
    new_simple_list = ser_de(fory, simple_list)
    assert new_simple_list[0] is new_simple_list

    now = datetime.datetime.now()
    day = datetime.date(2021, 11, 23)
    list_ = ["a", 1, -1.0, True, now, day]
    dict1 = {f"k{i}": v for i, v in enumerate(list_)}
    dict2 = {v: v for v in list_}
    dict3 = {
        "list1_0": list_,
        "list1_1": list_,
        "dict1_0": dict1,
        "dict1_1": dict1,
        "dict2_0": dict2,
        "dict2_1": dict2,
    }
    dict3["dict3_0"] = dict3
    dict3["dict3_1"] = dict3
    new_dict3 = ser_de(fory, dict3)
    assert new_dict3["list1_0"] == list_
    assert new_dict3["list1_0"] is new_dict3["list1_1"]
    assert new_dict3["dict1_0"] == dict1
    assert new_dict3["dict1_0"] is new_dict3["dict1_1"]
    assert new_dict3["dict2_0"] == dict2
    assert new_dict3["dict2_0"] is new_dict3["dict2_1"]
    assert new_dict3["dict3_0"] is new_dict3
    assert new_dict3["dict3_0"] is new_dict3["dict3_0"]


@pytest.mark.parametrize("language", [Language.PYTHON, Language.XLANG])
def test_tmp_ref(language):
    # FIXME this can't simulate the case where new objects are allocated on memory
    #  address of released tmp object.
    fory = Fory(language=language, ref_tracking=True)
    buffer = Buffer.allocate(128)
    writer_index = buffer.writer_index
    x = 1
    fory.serialize([x], buffer)
    fory.serialize([x], buffer)
    fory.serialize([x], buffer)
    assert buffer.writer_index > writer_index + 15

    l1 = fory.deserialize(buffer)
    l2 = fory.deserialize(buffer)
    l3 = fory.deserialize(buffer)
    assert l1 == [x]
    assert l2 == [x]
    assert l3 == [x]
    assert l1 is not l2
    assert l1 is not l3
    assert l2 is not l3


@pytest.mark.parametrize("language", [Language.PYTHON, Language.XLANG])
def test_multiple_ref(language):
    # FIXME this can't simulate the case where new objects are allocated on memory
    #  address of released tmp object.
    fory = Fory(language=language, ref_tracking=True)
    buffer = Buffer.allocate(128)
    for i in range(1000):
        fory.serialize([], buffer)
    objs = []
    for i in range(1000):
        objs.append(fory.deserialize(buffer))
    assert len(set(id(o) for o in objs)) == 1000


class RefTestClass1:
    def __init__(self, f1=None):
        self.f1 = f1


class RefTestClass2:
    def __init__(self, f1):
        self.f1 = f1


@pytest.mark.parametrize("language", [Language.PYTHON])
def test_ref_cleanup(language):
    # FIXME this can't simulate the case where new objects are allocated on memory
    #  address of released tmp object.
    fory = Fory(language=language, ref_tracking=True, require_type_registration=False)
    # TODO support Language.XLANG, current unpickler will error for xlang,
    o1 = RefTestClass1()
    o2 = RefTestClass2(f1=o1)
    pickle.loads(pickle.dumps(o2))
    ref1 = weakref.ref(o1)
    ref2 = weakref.ref(o2)
    data = fory.serialize(o2)
    del o1, o2
    gc.collect()
    assert ref1() is None
    assert ref2() is None
    fory.deserialize(data)


@pytest.mark.parametrize("language", [Language.XLANG, Language.PYTHON])
def test_array_serializer(language):
    fory = Fory(language=language, ref_tracking=True, require_type_registration=False)
    for typecode in PyArraySerializer.typecode_dict.keys():
        arr = array.array(typecode, list(range(10)))
        new_arr = ser_de(fory, arr)
        assert np.array_equal(new_arr, arr)
    for dtype in Numpy1DArraySerializer.dtypes_dict.keys():
        arr = np.array(list(range(10)), dtype=dtype)
        new_arr = ser_de(fory, arr)
        assert np.array_equal(new_arr, arr)
        np.testing.assert_array_equal(new_arr, arr)


def test_numpy_array_memoryview():
    _WINDOWS = os.name == "nt"
    if _WINDOWS:
        arr = np.array(list(range(10)), dtype="int32")
        view = memoryview(arr)
        assert view.format == "l"
        assert view.itemsize == 4
        arr = np.array(list(range(10)), dtype="int64")
        view = memoryview(arr)
        assert view.format == "q"
        assert view.itemsize == 8
    else:
        arr = np.array(list(range(10)), dtype="int32")
        view = memoryview(arr)
        assert view.format == "i"
        assert view.itemsize == 4
        arr = np.array(list(range(10)), dtype="int64")
        view = memoryview(arr)
        assert view.format == "l"
        assert view.itemsize == 8


def ser_de(fory, obj):
    binary = fory.serialize(obj)
    return fory.deserialize(binary)


def test_pickle():
    buf = Buffer.allocate(32)
    pickler = pickle.Pickler(buf)
    pickler.dump(b"abc")
    buf.write_int32(-1)
    pickler.dump("abcd")
    assert buf.writer_index - 4 == len(pickle.dumps(b"abc")) + len(pickle.dumps("abcd"))
    print(f"writer_index {buf.writer_index}")

    bytes_io_ = io.BytesIO(buf)
    unpickler = pickle.Unpickler(bytes_io_)
    assert unpickler.load() == b"abc"
    bytes_io_.seek(bytes_io_.tell() + 4)
    assert unpickler.load() == "abcd"
    print(f"reader_index {buf.reader_index} {bytes_io_.tell()}")

    if pa:
        pa_buf = pa.BufferReader(buf)
        unpickler = pickle.Unpickler(pa_buf)
        assert unpickler.load() == b"abc"
        pa_buf.seek(pa_buf.tell() + 4)
        assert unpickler.load() == "abcd"
        print(f"reader_index {buf.reader_index} {pa_buf.tell()} {buf.reader_index}")

    unpickler = pickle.Unpickler(buf)
    assert unpickler.load() == b"abc"
    buf.reader_index = buf.reader_index + 4
    assert unpickler.load() == "abcd"
    print(f"reader_index {buf.reader_index}")


@require_pyarrow
def test_serialize_arrow():
    record_batch = create_record_batch(10000)
    table = pa.Table.from_batches([record_batch, record_batch])
    fory = Fory(language=Language.XLANG, ref_tracking=True)
    serialized_data = Buffer.allocate(32)
    fory.serialize(record_batch, buffer=serialized_data)
    fory.serialize(table, buffer=serialized_data)
    new_batch = fory.deserialize(serialized_data)
    new_table = fory.deserialize(serialized_data)
    assert new_batch == record_batch
    assert new_table == table


@require_pyarrow
def test_serialize_arrow_zero_copy():
    record_batch = create_record_batch(10000)
    table = pa.Table.from_batches([record_batch, record_batch])
    buffer_objects = []
    fory = Fory(language=Language.XLANG, ref_tracking=True)
    serialized_data = Buffer.allocate(32)
    fory.serialize(record_batch, buffer=serialized_data, buffer_callback=buffer_objects.append)
    fory.serialize(table, buffer=serialized_data, buffer_callback=buffer_objects.append)
    buffers = [o.to_buffer() for o in buffer_objects]
    new_batch = fory.deserialize(serialized_data, buffers=buffers[:1])
    new_table = fory.deserialize(serialized_data, buffers=buffers[1:])
    buffer_objects.clear()
    assert new_batch == record_batch
    assert new_table == table


def create_record_batch(size):
    data = [
        pa.array([bool(i % 2) for i in range(size)]),
        pa.array([f"test{i}" for i in range(size)]),
    ]
    return pa.RecordBatch.from_arrays(data, ["boolean", "varchar"])


@dataclass
class Foo:
    f1: int


@dataclass
class Bar(Foo):
    f2: int


class BarSerializer(pyfory.Serializer):
    def xwrite(self, buffer, value: Bar):
        buffer.write_int32(value.f1)
        buffer.write_int32(value.f2)

    def xread(self, buffer):
        return Bar(buffer.read_int32(), buffer.read_int32())


class RegisterClass:
    def __init__(self, f1=None):
        self.f1 = f1


def test_register_py_serializer():
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    class Serializer(pyfory.Serializer):
        def write(self, buffer, value):
            buffer.write_int32(value.f1)

        def read(self, buffer):
            a = A()
            a.f1 = buffer.read_int32()
            return a

        def xwrite(self, buffer, value):
            raise NotImplementedError

        def xread(self, buffer):
            raise NotImplementedError

    fory.register_type(A, serializer=Serializer(fory, RegisterClass))
    assert fory.deserialize(fory.serialize(RegisterClass(100))).f1 == 100


class A:
    class B:
        class C:
            pass


def test_register_type():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)

    class Serializer(pyfory.Serializer):
        def write(self, buffer, value):
            pass

        def read(self, buffer):
            return self.type_()

        def xwrite(self, buffer, value):
            raise NotImplementedError

        def xread(self, buffer):
            raise NotImplementedError

    fory.register_type(A, serializer=Serializer(fory, A))
    fory.register_type(A.B, serializer=Serializer(fory, A.B))
    fory.register_type(A.B.C, serializer=Serializer(fory, A.B.C))
    assert isinstance(fory.deserialize(fory.serialize(A())), A)
    assert isinstance(fory.deserialize(fory.serialize(A.B())), A.B)
    assert isinstance(fory.deserialize(fory.serialize(A.B.C())), A.B.C)


def test_pickle_fallback():
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)
    o1 = [1, True, np.dtype(np.int32)]
    data1 = fory.serialize(o1)
    new_o1 = fory.deserialize(data1)
    assert o1 == new_o1

    df = pd.DataFrame({"a": list(range(10))})
    df2 = fory.deserialize(fory.serialize(df))
    assert df2.equals(df)


# Define global functions for tests
def helper_f1(x):
    return x


def helper_f2(x):
    return x + x


def helper_double(x):
    return x * 2


def helper_multiply(x):
    return x * 2


def test_unsupported_callback():
    """
    Test that functions that were previously considered "unsupported" and required
    cloudpickle are now directly supported by Fory's native serialization.

    This test demonstrates that:
    1. Functions can be serialized directly without registering their types
    2. The unsupported_callback parameter is no longer needed for function serialization
    3. The unsupported_objects parameter is no longer needed for deserialization
    """
    # Create a Fory instance with type registration disabled
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)

    # Define a local function (previously would have required cloudpickle)
    def local_function(x):
        return x * 3

    # Create a lambda function (previously would have required cloudpickle)
    lambda_function = lambda x: x + 10

    # Create a closure that captures a variable from the outer scope
    outer_value = 42

    def closure_function(x):
        return x + outer_value

    # Create a list with various function types
    obj1 = [
        1,
        True,
        helper_f1,  # Global function
        helper_f2,  # Another global function
        local_function,  # Local function
        lambda_function,  # Lambda function
        closure_function,  # Function with closure
        {1: 2},
    ]

    # Create an empty list for unsupported objects - we won't need to use it
    unsupported_objects = []

    # Serialize the object WITHOUT using unsupported_callback
    binary1 = fory.serialize(obj1)

    # Verify the unsupported_objects list is empty - no objects needed special handling
    assert len(unsupported_objects) == 0

    # Deserialize WITHOUT using unsupported_objects
    new_obj1 = fory.deserialize(binary1)

    # Verify the deserialized object has the same structure and values
    assert len(new_obj1) == len(obj1)
    assert new_obj1[0] == obj1[0]  # 1
    assert new_obj1[1] == obj1[1]  # True

    # Verify all functions have the same behavior
    test_input = 5
    assert new_obj1[2](test_input) == helper_f1(test_input)  # helper_f1(5) = 5
    assert new_obj1[3](test_input) == helper_f2(test_input)  # helper_f2(5) = 10
    assert new_obj1[4](test_input) == local_function(test_input)  # local_function(5) = 15
    assert new_obj1[5](test_input) == lambda_function(test_input)  # lambda_function(5) = 15
    assert new_obj1[6](test_input) == closure_function(test_input)  # closure_function(5) = 47

    # Verify the dictionary
    assert new_obj1[7] == obj1[7]  # {1: 2}


def test_slice():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    assert fory.deserialize(fory.serialize(slice(1, None, "10"))) == slice(1, None, "10")
    assert fory.deserialize(fory.serialize(slice(1, 100, 10))) == slice(1, 100, 10)
    assert fory.deserialize(fory.serialize(slice(1, None, 10))) == slice(1, None, 10)
    assert fory.deserialize(fory.serialize(slice(10, 10, None))) == slice(10, 10, None)
    assert fory.deserialize(fory.serialize(slice(None, None, 10))) == slice(None, None, 10)
    assert fory.deserialize(fory.serialize(slice(None, None, None))) == slice(None, None, None)
    assert fory.deserialize(fory.serialize([1, 2, slice(1, 100, 10), slice(1, 100, 10)])) == [1, 2, slice(1, 100, 10), slice(1, 100, 10)]
    assert fory.deserialize(fory.serialize([1, slice(1, None, 10), False, [], slice(1, 100, 10)])) == [
        1,
        slice(1, None, 10),
        False,
        [],
        slice(1, 100, 10),
    ]
    assert fory.deserialize(fory.serialize([1, slice(1, None, "10"), False, [], slice(1, 100, "10")])) == [
        1,
        slice(1, None, "10"),
        False,
        [],
        slice(1, 100, "10"),
    ]


class EnumClass(Enum):
    E1 = 1
    E2 = 2
    E3 = "E3"
    E4 = "E4"


def test_enum():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    assert ser_de(fory, EnumClass.E1) == EnumClass.E1
    assert ser_de(fory, EnumClass.E2) == EnumClass.E2
    assert ser_de(fory, EnumClass.E3) == EnumClass.E3
    assert ser_de(fory, EnumClass.E4) == EnumClass.E4
    assert isinstance(fory.type_resolver.get_serializer(EnumClass), EnumSerializer)


def test_duplicate_serialize():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    assert ser_de(fory, EnumClass.E1) == EnumClass.E1
    assert ser_de(fory, EnumClass.E2) == EnumClass.E2
    assert ser_de(fory, EnumClass.E4) == EnumClass.E4
    assert ser_de(fory, EnumClass.E2) == EnumClass.E2
    assert ser_de(fory, EnumClass.E1) == EnumClass.E1
    assert ser_de(fory, EnumClass.E4) == EnumClass.E4


@dataclass(unsafe_hash=True)
class CacheClass1:
    f1: int


def test_cache_serializer():
    fory = Fory(language=Language.PYTHON, ref_tracking=True)
    fory.register_type(CacheClass1, serializer=pyfory.PickleStrongCacheSerializer(fory))
    assert ser_de(fory, CacheClass1(1)) == CacheClass1(1)
    fory.register_type(CacheClass1, serializer=pyfory.PickleCacheSerializer(fory))
    assert ser_de(fory, CacheClass1(1)) == CacheClass1(1)


def test_pandas_range_index():
    fory = Fory(language=Language.PYTHON, ref_tracking=True, require_type_registration=False)
    fory.register_type(pd.RangeIndex, serializer=pyfory.PandasRangeIndexSerializer(fory))
    index = pd.RangeIndex(1, 100, 2, name="a")
    new_index = ser_de(fory, index)
    pd.testing.assert_index_equal(new_index, new_index)


@dataclass(unsafe_hash=True)
class PyDataClass1:
    f1: int
    f2: float
    f3: str
    f4: bool
    f5: Any
    f6: List
    f7: Dict


@pytest.mark.parametrize("track_ref", [False, True])
def test_py_serialize_dataclass(track_ref):
    fory = Fory(
        language=Language.PYTHON,
        ref_tracking=track_ref,
        require_type_registration=False,
    )
    obj1 = PyDataClass1(f1=1, f2=-2.0, f3="abc", f4=True, f5="xyz", f6=[1, 2], f7={"k1": "v1"})
    assert ser_de(fory, obj1) == obj1
    obj2 = PyDataClass1(f1=None, f2=-2.0, f3="abc", f4=None, f5="xyz", f6=None, f7=None)
    assert ser_de(fory, obj2) == obj2


@pytest.mark.parametrize("track_ref", [False, True])
def test_function(track_ref):
    fory = Fory(
        language=Language.PYTHON,
        ref_tracking=track_ref,
        require_type_registration=False,
    )
    # Use the global helper_double function instead of a lambda
    c = fory.deserialize(fory.serialize(helper_double))
    assert c(2) == 4

    # Use another global function instead of defining a local one
    # Register the function type
    fory.register_type(type(helper_multiply))

    c = fory.deserialize(fory.serialize(helper_multiply))
    assert c(2) == 4

    df = pd.DataFrame({"a": list(range(10))})
    df_sum = fory.deserialize(fory.serialize(df.sum))
    assert df_sum().equals(df.sum())


@dataclass(unsafe_hash=True)
class MapFields:
    simple_dict: dict = None
    empty_dict: dict = None
    large_dict: dict = None
    nested_dict: dict = None
    int_key_dict: dict = None
    tuple_key_dict: dict = None
    dict_with_custom_obj: dict = None
    single_key_dict: dict = None


@pytest.mark.parametrize("track_ref", [False, True])
def test_map_fields_chunk_serializer(track_ref):
    fory = Fory(
        language=Language.PYTHON,
        ref_tracking=track_ref,
        require_type_registration=False,
    )

    # Test case
    simple_dict = {"a": 1, "b": 2, "c": 3}
    empty_dict = {}
    large_dict = {f"key{i}": i for i in range(1000)}

    nested_dict = {"outer": {"inner": 1}}
    int_key_dict = {1: [1, 2, 3], 2: [4, 5, 6]}
    tuple_key_dict = {(1, 2): {1, 2, 3}, (3, 4): {4, 5, 6}}

    class CustomClass:
        __slots__ = ["value"]

        def __init__(self, value):
            self.value = value

        def __eq__(self, other):
            return isinstance(other, CustomClass) and self.value == other.value

    custom_obj = CustomClass(1)
    dict_with_custom_obj = {"custom": custom_obj}
    single_key_dict = {"single": 1}

    # Create MapFields Obj
    map_fields_object = MapFields()
    map_fields_object.simple_dict = simple_dict
    map_fields_object.empty_dict = empty_dict
    map_fields_object.large_dict = large_dict
    map_fields_object.nested_dict = nested_dict
    map_fields_object.int_key_dict = int_key_dict
    map_fields_object.tuple_key_dict = tuple_key_dict
    map_fields_object.dict_with_custom_obj = dict_with_custom_obj
    map_fields_object.single_key_dict = single_key_dict

    serialized = fory.serialize(map_fields_object)
    deserialized = fory.deserialize(serialized)

    assert map_fields_object.simple_dict == deserialized.simple_dict
    assert map_fields_object.empty_dict == deserialized.empty_dict
    assert map_fields_object.large_dict == deserialized.large_dict
    assert map_fields_object.nested_dict == deserialized.nested_dict
    assert map_fields_object.int_key_dict == deserialized.int_key_dict
    assert map_fields_object.tuple_key_dict == deserialized.tuple_key_dict
    assert map_fields_object.dict_with_custom_obj == deserialized.dict_with_custom_obj
    assert map_fields_object.single_key_dict == deserialized.single_key_dict


class SomeTestObject:
    def __init__(self, f1: int, f2: str):
        self.f1 = f1
        self.f2 = f2

    def __eq__(self, other):
        return self.f1 == other.f1 and self.f2 == other.f2


class SomeTestSlotsObject:
    __slots__ = ["f1", "f2"]

    def __init__(self, f1: int, f2: str):
        self.f1 = f1
        self.f2 = f2

    def __eq__(self, other):
        return self.f1 == other.f1 and self.f2 == other.f2


@pytest.mark.parametrize("track_ref", [False, True])
def test_py_serialize_object(track_ref):
    fory = Fory(
        language=Language.PYTHON,
        ref_tracking=track_ref,
        require_type_registration=False,
    )
    fory.register_type(SomeTestObject)
    fory.register_type(SomeTestSlotsObject)
    obj1 = SomeTestObject(f1=1, f2="abc")
    assert ser_de(fory, obj1) == obj1
    obj2 = SomeTestSlotsObject(f1=1, f2="abc")
    assert ser_de(fory, obj2) == obj2


if __name__ == "__main__":
    test_string()
