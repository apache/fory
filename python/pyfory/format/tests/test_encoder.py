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

import pyfory
import pickle

from dataclasses import dataclass
from pyfory.tests.core import require_pyarrow
from pyfory.tests.record import create_foo, foo_schema, FooPOJO, create_foo_pojo
from pyfory.util import lazy_import
from typing import List, Dict

pa = lazy_import("pyarrow")


@require_pyarrow
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
    assert foo == new_foo


@require_pyarrow
def test_encoder():
    foo = create_foo_pojo()
    encoder = pyfory.encoder(FooPOJO)
    new_foo = encoder.decode(encoder.encode(foo))
    assert foo == new_foo


@require_pyarrow
def test_encoder_with_schema():
    foo = create_foo()
    encoder = pyfory.encoder(schema=foo_schema())
    new_foo = encoder.decode(encoder.encode(foo))
    assert foo == new_foo


@require_pyarrow
def test_dict():
    dict_ = {"f1": 1, "f2": "str"}
    encoder = pyfory.create_row_encoder(pa.schema([("f1", pa.int32()), ("f2", pa.utf8())]))
    row = encoder.to_row(dict_)
    new_obj = encoder.from_row(row)
    assert new_obj.f1 == dict_["f1"]
    assert new_obj.f2 == dict_["f2"]


@require_pyarrow
def test_ints():
    cls = pyfory.record_class_factory("TestNumeric", ["f" + str(i) for i in range(1, 9)])
    schema = pa.schema(
        [
            ("f1", pa.int64()),
            ("f2", pa.int64()),
            ("f3", pa.int32()),
            ("f4", pa.int32()),
            ("f5", pa.int16()),
            ("f6", pa.int16()),
            ("f7", pa.int8()),
            ("f8", pa.int8()),
        ],
        metadata={"cls": pyfory.get_qualified_classname(cls)},
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
    encoder = pyfory.create_row_encoder(schema)
    row = encoder.to_row(obj)
    print("row", row)
    new_obj = encoder.from_row(row)
    print("new_obj", new_obj)
    assert new_obj == obj


@require_pyarrow
def test_basic():
    cls = pyfory.record_class_factory("TestBasic", ["f" + str(i) for i in range(1, 6)])
    schema = pa.schema(
        [
            ("f1", pa.string()),
            ("f2", pa.binary()),
            ("f3", pa.bool_()),
            ("f4", pa.date32()),
            ("f5", pa.timestamp("us")),
        ],
        metadata={"cls": pyfory.get_qualified_classname(cls)},
    )
    from datetime import date, datetime

    obj = cls(f1="str", f2=b"123456", f3=True, f4=date(1970, 1, 1), f5=datetime.now())
    print("obj", obj)
    encoder = pyfory.create_row_encoder(schema)
    row = encoder.to_row(obj)
    print("row", row)
    new_obj = encoder.from_row(row)
    print("new_obj", new_obj)
    print("new_obj", type(new_obj))
    assert new_obj == obj


@dataclass
class Bar:
    f1: str
    f2: List["pa.int64"]


@dataclass
class Foo:
    f1: "pa.int32"
    f2: List["pa.int32"]
    f3: Dict[str, "pa.int32"]
    f4: List[Bar]


@require_pyarrow
def test_binary_row_access():
    encoder = pyfory.encoder(Foo)
    foo = Foo(
        f1=10,
        f2=list(range(1000)),
        f3={f"k{i}": i for i in range(1000)},
        f4=[Bar(f1=f"s{i}", f2=list(range(10))) for i in range(10)],
    )
    binary = encoder.to_row(foo).to_bytes()
    foo_row = pyfory.RowData(encoder.schema, binary)
    print(foo_row.f2[2], foo_row.f4[2].f1, foo_row.f4[2].f2[5])


def benchmark_row_access():
    encoder = pyfory.encoder(Foo)
    foo = Foo(
        f1=10,
        f2=list(range(1000_000)),
        f3={f"k{i}": i for i in range(1000_000)},
        f4=[Bar(f1=f"s{i}", f2=list(range(10))) for i in range(1000_000)],
    )

    binary = encoder.to_row(foo).to_bytes()

    def benchmark_fory():
        foo_row = pyfory.RowData(encoder.schema, binary)
        print(foo_row.f2[100000], foo_row.f4[100000].f1, foo_row.f4[200000].f2[5])

    print(timeit.timeit(benchmark_fory, number=10))
    binary = pickle.dumps(foo)

    def benchmark_pickle():
        new_foo = pickle.loads(binary)
        print(new_foo.f2[100000], new_foo.f4[100000].f1, new_foo.f4[200000].f2[5])

    print(timeit.timeit(benchmark_pickle, number=10))
