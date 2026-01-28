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

"""Tests for the FlatBuffers frontend translation."""

from fory_compiler.frontend.fbs import FBSFrontend
from fory_compiler.ir.ast import ListType, PrimitiveType
from fory_compiler.ir.types import PrimitiveKind


def test_fbs_type_mapping_and_options():
    source = """
    namespace demo.example;

    enum Color : byte { Red = 0, Green, Blue }

    struct Vec3 {
      x: float;
      y: float;
      z: float;
    }

    table Monster (deprecated) {
      pos: Vec3;
      mana: short = 150;
      friendly: bool = false (deprecated, priority: 1);
      inventory: [ubyte];
      name: string;
      color: Color = Blue;
    }

    root_type Monster;
    """

    schema = FBSFrontend().parse(source)
    assert schema.package == "demo.example"

    messages = {m.name: m for m in schema.messages}
    assert messages["Vec3"].options.get("evolving") is False
    assert messages["Monster"].options.get("evolving") is True
    assert messages["Monster"].options.get("deprecated") is True

    fields = {f.name: f for f in messages["Monster"].fields}
    assert isinstance(fields["mana"].field_type, PrimitiveType)
    assert fields["mana"].field_type.kind == PrimitiveKind.INT16
    assert isinstance(fields["inventory"].field_type, ListType)
    assert fields["inventory"].field_type.element_type.kind == PrimitiveKind.UINT8
    assert fields["friendly"].options["deprecated"] is True
    assert fields["friendly"].options["priority"] == 1
    assert fields["pos"].number == 1
    assert fields["mana"].number == 2
    assert fields["pos"].tag_id == 1
    assert fields["mana"].tag_id == 2

    enum_values = {v.name: v.value for v in schema.enums[0].values}
    assert enum_values["Green"] == 1
    assert enum_values["Blue"] == 2


def test_fbs_union_translation():
    source = """
    namespace demo;
    union Event { Foo, Bar }
    """
    schema = FBSFrontend().parse(source)
    assert len(schema.unions) == 1
    union = schema.unions[0]
    assert union.name == "Event"
    assert [f.name for f in union.fields] == ["foo", "bar"]
    assert [f.number for f in union.fields] == [1, 2]


def test_fbs_fory_ref_attributes():
    source = """
    namespace demo;

    table Node {
      parent: Node (fory_weak_ref: true);
      children: [Node] (fory_ref: true);
      cached: Node (fory_ref: true, fory_thread_safe_pointer: false);
    }
    """
    schema = FBSFrontend().parse(source)
    node = schema.messages[0]
    fields = {f.name: f for f in node.fields}

    parent = fields["parent"]
    assert parent.ref is True
    assert parent.ref_options["weak_ref"] is True

    children = fields["children"]
    assert children.element_ref is True
    assert children.element_ref_options == {}

    cached = fields["cached"]
    assert cached.ref is True
    assert cached.ref_options["thread_safe_pointer"] is False
