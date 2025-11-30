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

import timeit
import pickle

import pyfory
from pyfory.format import (
    schema,
    field,
    int8,
    int16,
    int32,
    int64,
    utf8,
    binary,
    boolean,
    date32,
    timestamp,
    list_,
    map_,
    struct,
)

from dataclasses import dataclass
from typing import List, Dict


@dataclass
class Bar:
    f1: int
    f2: str


@dataclass
class Foo:
    f1: int
    f2: str
    f3: List[str]
    f4: Dict[str, int]
    f5: List[int]
    f6: int
    f7: Bar


def create_foo():
    return Foo(
        f1=1,
        f2="hello",
        f3=["a", "b", "c"],
        f4={"x": 1, "y": 2},
        f5=[10, 20, 30],
        f6=42,
        f7=Bar(f1=100, f2="world"),
    )


def foo_schema():
    return schema(
        [
            field("f1", int64()),
            field("f2", utf8()),
            field("f3", list_(utf8())),
            field("f4", map_(utf8(), int64())),
            field("f5", list_(int64())),
            field("f6", int64()),
            field("f7", struct([field("f1", int64()), field("f2", utf8())])),
        ]
    )


def test_encode():
    print(foo_schema())
    encoder = pyfory.create_row_encoder(foo_schema())
    foo = create_foo()
    print("foo", foo)
    row = encoder.to_row(foo)
    print("row bytes length", len(row.to_bytes()))
    print("row bytes", row.to_bytes())
    print("row", row)  # test __str__
    new_foo = encoder.from_row(row)
    print("new_foo", new_foo)
    assert foo.f1 == new_foo.f1
    assert foo.f2 == new_foo.f2
    assert foo.f3 == new_foo.f3
    assert foo.f4 == new_foo.f4
    assert foo.f5 == new_foo.f5
    assert foo.f6 == new_foo.f6


def test_encoder():
    foo = create_foo()
    encoder = pyfory.encoder(Foo)
    new_foo = encoder.decode(encoder.encode(foo))
    assert foo.f1 == new_foo.f1
    assert foo.f2 == new_foo.f2


def test_encoder_with_schema():
    foo = create_foo()
    encoder = pyfory.encoder(schema=foo_schema())
    new_foo = encoder.decode(encoder.encode(foo))
    assert foo.f1 == new_foo.f1
    assert foo.f2 == new_foo.f2


def test_dict():
    dict_ = {"f1": 1, "f2": "str"}
    s = schema([field("f1", int64()), field("f2", utf8())])
    encoder = pyfory.create_row_encoder(s)
    row = encoder.to_row(dict_)
    new_obj = encoder.from_row(row)
    assert new_obj.f1 == dict_["f1"]
    assert new_obj.f2 == dict_["f2"]


def test_ints():
    cls = pyfory.record_class_factory("TestNumeric", ["f" + str(i) for i in range(1, 9)])
    s = schema(
        [
            field("f1", int64()),
            field("f2", int64()),
            field("f3", int32()),
            field("f4", int32()),
            field("f5", int16()),
            field("f6", int16()),
            field("f7", int8()),
            field("f8", int8()),
        ]
    )
    print("pyfory.cls", pyfory.get_qualified_classname(cls))
    obj = cls(
        f1=2**63 - 1,
        f2=-(2**63),
        f3=2**31 - 1,
        f4=-(2**31),
        f5=2**15 - 1,
        f6=-(2**15),
        f7=2**7 - 1,
        f8=-(2**7),
    )
    print("obj", obj)
    encoder = pyfory.create_row_encoder(s)
    row = encoder.to_row(obj)
    print("row", row)
    new_obj = encoder.from_row(row)
    print("new_obj", new_obj)
    assert new_obj.f1 == obj.f1
    assert new_obj.f2 == obj.f2
    assert new_obj.f3 == obj.f3
    assert new_obj.f4 == obj.f4
    assert new_obj.f5 == obj.f5
    assert new_obj.f6 == obj.f6
    assert new_obj.f7 == obj.f7
    assert new_obj.f8 == obj.f8


def test_basic():
    cls = pyfory.record_class_factory("TestBasic", ["f" + str(i) for i in range(1, 6)])
    s = schema(
        [
            field("f1", utf8()),
            field("f2", binary()),
            field("f3", boolean()),
            field("f4", date32()),
            field("f5", timestamp()),
        ]
    )
    from datetime import date, datetime

    obj = cls(f1="str", f2=b"123456", f3=True, f4=date(1970, 1, 1), f5=datetime.now())
    print("obj", obj)
    encoder = pyfory.create_row_encoder(s)
    row = encoder.to_row(obj)
    print("row", row)
    new_obj = encoder.from_row(row)
    print("new_obj", new_obj)
    print("new_obj", type(new_obj))
    assert new_obj.f1 == obj.f1
    assert new_obj.f2 == obj.f2
    assert new_obj.f3 == obj.f3
    assert new_obj.f4 == obj.f4
    # Timestamp precision may differ
    assert abs((new_obj.f5 - obj.f5).total_seconds()) < 1


@dataclass
class BarNested:
    f1: str
    f2: List[int]


@dataclass
class FooNested:
    f1: int
    f2: List[int]
    f3: Dict[str, int]
    f4: List[BarNested]


def test_binary_row_access():
    encoder = pyfory.encoder(FooNested)
    foo = FooNested(
        f1=10,
        f2=list(range(1000)),
        f3={f"k{i}": i for i in range(1000)},
        f4=[BarNested(f1=f"s{i}", f2=list(range(10))) for i in range(10)],
    )
    binary_data = encoder.to_row(foo).to_bytes()
    foo_row = pyfory.RowData(encoder.schema, binary_data)
    print(foo_row.f2[2], foo_row.f4[2].f1, foo_row.f4[2].f2[5])


def benchmark_row_access():
    encoder = pyfory.encoder(FooNested)
    foo = FooNested(
        f1=10,
        f2=list(range(1000_000)),
        f3={f"k{i}": i for i in range(1000_000)},
        f4=[BarNested(f1=f"s{i}", f2=list(range(10))) for i in range(1000_000)],
    )

    binary_data = encoder.to_row(foo).to_bytes()

    def benchmark_fory():
        foo_row = pyfory.RowData(encoder.schema, binary_data)
        print(foo_row.f2[100000], foo_row.f4[100000].f1, foo_row.f4[200000].f2[5])

    print(timeit.timeit(benchmark_fory, number=10))
    binary_data = pickle.dumps(foo)

    def benchmark_pickle():
        new_foo = pickle.loads(binary_data)
        print(new_foo.f2[100000], new_foo.f4[100000].f1, new_foo.f4[200000].f2[5])

    print(timeit.timeit(benchmark_pickle, number=10))
