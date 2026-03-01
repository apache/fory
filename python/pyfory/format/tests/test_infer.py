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

import datetime
import pyfory

from dataclasses import dataclass
from pyfory.format.infer import infer_schema, infer_field, ForyTypeVisitor
from pyfory.format import (
    TypeId,
)
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


def _infer_field(field_name, type_, types_path=None):
    return infer_field(field_name, type_, ForyTypeVisitor(), types_path=types_path)


def test_infer_field():
    assert _infer_field("", int).type.id == TypeId.INT64
    assert _infer_field("", float).type.id == TypeId.FLOAT64
    assert _infer_field("", str).type.id == TypeId.STRING
    assert _infer_field("", bytes).type.id == TypeId.BINARY
    assert _infer_field("", List[str]).type.id == TypeId.LIST
    assert _infer_field("", Dict[str, str]).type.id == TypeId.MAP
    assert _infer_field("", List[Dict[str, str]]).type.id == TypeId.LIST

    # Custom class is treated as a struct
    class X:
        pass

    result = _infer_field("", X)
    assert result.type.id == TypeId.STRUCT


def test_infer_field_builtin_types_not_treated_as_struct():
    """Built-in types must NOT be routed to visit_customized (regression guard)."""
    assert _infer_field("", int).type.id == TypeId.INT64
    assert _infer_field("", float).type.id == TypeId.FLOAT64
    assert _infer_field("", str).type.id == TypeId.STRING
    assert _infer_field("", bytes).type.id == TypeId.BINARY
    assert _infer_field("", bool).type.id == TypeId.BOOL


def test_infer_field_nested_custom_class():
    """Custom class nested inside a List should also be handled correctly."""

    class Inner:
        pass

    result = _infer_field("", List[Inner])
    assert result.type.id == TypeId.LIST


def test_infer_class_schema():
    schema = infer_schema(Foo)
    assert schema.num_fields == 7
    assert schema.field(0).name == "f1"
    assert schema.field(0).type.id == TypeId.INT64
    assert schema.field(1).name == "f2"
    assert schema.field(1).type.id == TypeId.STRING
    assert schema.field(2).name == "f3"
    assert schema.field(2).type.id == TypeId.LIST
    assert schema.field(3).name == "f4"
    assert schema.field(3).type.id == TypeId.MAP
    assert schema.field(4).name == "f5"
    assert schema.field(4).type.id == TypeId.LIST
    assert schema.field(5).name == "f6"
    assert schema.field(5).type.id == TypeId.INT64
    assert schema.field(6).name == "f7"
    assert schema.field(6).type.id == TypeId.STRUCT


def test_type_id():
    assert pyfory.format.infer.get_type_id(str) == TypeId.STRING
    assert pyfory.format.infer.get_type_id(datetime.date) == TypeId.DATE
    assert pyfory.format.infer.get_type_id(datetime.datetime) == TypeId.TIMESTAMP


if __name__ == "__main__":
    test_infer_class_schema()
