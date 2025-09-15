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

import pyfory as fory
from typing import List, Dict

from pyfory.util import lazy_import

pa = lazy_import("pyarrow")


class Foo:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)

    def __repr__(self):
        return str(self.__dict__)

    def __eq__(self, other):
        return self.__dict__ == other.__dict__


class Bar:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)

    def __repr__(self):
        return str(self.__dict__)

    def __eq__(self, other):
        return self.__dict__ == other.__dict__

    @classmethod
    def create(cls):
        obj = cls.__new__(cls)
        obj.f1 = 1
        obj.f2 = "str"
        return obj


@dataclass
class FooPOJO:
    f1: "pa.int32"
    f2: str
    f3: List[str]
    f4: Dict[str, "pa.int32"]
    f5: List["pa.int32"]
    f6: "pa.int32"
    f7: "BarPOJO"


@dataclass
class BarPOJO:
    f1: "pa.int32"
    f2: str


def create_foo(foo_cls=Foo, bar_cls=Bar):
    obj = foo_cls.__new__(foo_cls)
    size = 10
    data = {
        "f1": 1,
        "f2": "str",
        "f3": ["str" + str(i) for i in range(size)],
        "f4": {"k" + str(i): i for i in range(size)},
        "f5": [-i for i in range(size)],
        "f6": -100,
        "f7": create_bar(bar_cls),
    }
    obj.__dict__.update(**data)
    return obj


def create_bar(cls):
    obj = cls.__new__(cls)
    obj.f1 = 1
    obj.f2 = "str"
    return obj


def create_foo_pojo():
    size = 10
    return FooPOJO(
        f1=1,
        f2="str",
        f3=["str" + str(i) for i in range(size)],
        f4={"k" + str(i): i for i in range(size)},
        f5=[-i for i in range(size)],
        f6=-100,
        f7=BarPOJO(f1=1, f2="str"),
    )


def foo_schema():
    bar_struct = pa.struct([("f1", pa.int32()), ("f2", pa.string())])
    return pa.schema(
        [
            ("f1", pa.int32()),
            ("f2", pa.string()),
            ("f3", pa.list_(pa.string())),
            ("f4", pa.map_(pa.string(), pa.int32())),
            ("f5", pa.list_(pa.int32())),
            ("f6", pa.int32()),
            pa.field("f7", bar_struct, metadata={"cls": fory.get_qualified_classname(Bar)}),
        ],
        metadata={"cls": fory.get_qualified_classname(Foo)},
    )
