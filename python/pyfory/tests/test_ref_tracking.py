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
from typing import Any

import pytest

import pyfory


def _roundtrip(fory, value):
    return fory.deserialize(fory.serialize(value))


class HashKey:
    def __init__(self, label: str):
        self.label = label

    def __hash__(self):
        return hash(self.label)

    def __eq__(self, other):
        return isinstance(other, HashKey) and self.label == other.label


@dataclass
class RefNode:
    name: str
    left: Any = pyfory.field(default=None, ref=True, nullable=True)
    right: Any = pyfory.field(default=None, ref=True, nullable=True)
    items: Any = pyfory.field(default=None, ref=True, nullable=True)
    mapping: Any = pyfory.field(default=None, ref=True, nullable=True)
    self_ref: Any = pyfory.field(default=None, ref=True, nullable=True)


@pytest.mark.parametrize("xlang", [False, True])
def test_collection_list_mixed_type_shared_reference(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True, strict=False)
    shared = {"name": "shared", "nums": [1, 2, 3]}
    payload = [1, True, 3.14, "v", shared, shared, [shared, {"alias": shared}]]
    restored = _roundtrip(fory, payload)

    assert restored[4] is restored[5]
    assert restored[6][0] is restored[4]
    assert restored[6][1]["alias"] is restored[4]


def test_collection_tuple_shared_reference_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    shared = {"k": [1, 2]}
    payload = (shared, shared, [shared])
    restored = _roundtrip(fory, payload)

    assert restored[0] is restored[1]
    assert restored[2][0] is restored[0]


def test_collection_set_element_alias_with_outer_reference_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    token = HashKey("shared-key")
    payload = [{token}, token]
    restored = _roundtrip(fory, payload)

    elem = next(iter(restored[0]))
    assert elem is restored[1]


@pytest.mark.parametrize("xlang", [False, True])
def test_map_shared_value_aliases_with_none_key(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True, strict=False)
    shared = [1, 2, 3]
    payload = {None: shared, "a": shared, "nested": {"v": shared}}
    restored = _roundtrip(fory, payload)

    assert restored[None] is restored["a"]
    assert restored["nested"]["v"] is restored["a"]


def test_map_self_cycle_and_shared_submap_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    shared_submap = {"x": 1}
    payload = {"left": shared_submap, "right": shared_submap}
    payload["self"] = payload
    restored = _roundtrip(fory, payload)

    assert restored["left"] is restored["right"]
    assert restored["self"] is restored


def test_map_key_alias_with_outer_reference_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    key = HashKey("k")
    payload = [{key: "value"}, key]
    restored = _roundtrip(fory, payload)

    key_from_map = next(iter(restored[0].keys()))
    assert key_from_map is restored[1]


def test_struct_shared_fields_and_cross_container_alias_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(RefNode)

    shared = {"inner": [1, 2]}
    node = RefNode(
        name="root",
        left=shared,
        right=shared,
        items=[shared],
        mapping={"alias": shared},
    )
    restored = _roundtrip(fory, node)

    assert restored.left is restored.right
    assert restored.items[0] is restored.left
    assert restored.mapping["alias"] is restored.left


def test_struct_self_cycle_and_nested_alias_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(RefNode)

    shared_list = []
    node = RefNode(name="cycle")
    node.items = [shared_list, {"list": shared_list}]
    node.mapping = {"node": node, "items": node.items}
    node.self_ref = node
    restored = _roundtrip(fory, node)

    assert restored.self_ref is restored
    assert restored.mapping["node"] is restored
    assert restored.mapping["items"] is restored.items
    assert restored.items[0] is restored.items[1]["list"]
