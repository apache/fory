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
from typing import Any, List

import pytest

import pyfory
from pyfory import _fory as fmod
from pyfory.resolver import REF_FLAG, REF_VALUE_FLAG


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


@dataclass
class RefOverrideDisabled:
    left: Any = pyfory.field(default=None, ref=False, nullable=True)
    right: Any = pyfory.field(default=None, ref=False, nullable=True)


@dataclass
class RefOverrideEnabled:
    left: Any = pyfory.field(default=None, ref=True, nullable=True)
    right: Any = pyfory.field(default=None, ref=True, nullable=True)


@dataclass
class FixedUint64Pair:
    a: pyfory.fixed_uint64 = None
    b: pyfory.fixed_uint64 = None


@dataclass
class Holder:
    values: List[pyfory.int64]


class EvilIndex:
    def __init__(self):
        self.owner = None

    def __index__(self):
        # Reallocate list storage and inject invalid element types.
        self.owner.clear()
        self.owner.extend([bytearray(16)] * 1024)
        return 7


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


@pytest.mark.parametrize("xlang", [False, True])
def test_struct_field_ref_override_controls_alias_preservation(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True, strict=False)
    if xlang:
        fory.register_type(RefOverrideDisabled, typename="example.RefOverrideDisabled")
        fory.register_type(RefOverrideEnabled, typename="example.RefOverrideEnabled")
    else:
        fory.register(RefOverrideDisabled)
        fory.register(RefOverrideEnabled)

    shared = {"v": [1, 2, 3]}

    disabled = _roundtrip(fory, RefOverrideDisabled(shared, shared))
    assert disabled.left == shared
    assert disabled.right == shared
    assert disabled.left is not disabled.right

    enabled = _roundtrip(fory, RefOverrideEnabled(shared, shared))
    assert enabled.left == shared
    assert enabled.right == shared
    assert enabled.left is enabled.right


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


def test_collection_mixed_type_primitive_ref_value_regression():
    fory = pyfory.Fory(xlang=True, ref=True, strict=False)
    buffer = pyfory.Buffer.allocate(256)

    # Fory payload framing + top-level list object.
    buffer.write_int8(0b10)
    buffer.write_int8(REF_VALUE_FLAG)
    fory.type_resolver.write_type_info(buffer, fory.type_resolver.get_type_info(list))

    # List with tracking-ref and mixed element types.
    value = "primitive-ref-value-regression-string-0123456789"
    buffer.write_var_uint32(2)
    buffer.write_int8(0b1)  # COLL_TRACKING_REF
    # elem0: first-seen primitive string as REF_VALUE + typeinfo + payload.
    buffer.write_int8(REF_VALUE_FLAG)
    buffer.write_var_uint32(fmod.STRING_TYPE_ID)
    buffer.write_string(value)
    # elem1: REF back to elem0 slot.
    buffer.write_int8(REF_FLAG)
    buffer.write_var_uint32(1)

    payload = buffer.to_bytes(0, buffer.get_writer_index())
    restored = fory.deserialize(payload)
    assert restored[0] == value
    assert restored[0] is restored[1]


def test_invalid_top_level_ref_id_raises_value_error():
    fory = pyfory.Fory(xlang=True, ref=True, strict=False)
    buffer = pyfory.Buffer.allocate(32)

    buffer.write_int8(0b10)
    buffer.write_int8(REF_FLAG)
    buffer.write_var_uint32(12345)

    payload = buffer.to_bytes(0, buffer.get_writer_index())
    with pytest.raises(ValueError, match="Invalid ref id"):
        fory.deserialize(payload)


def test_invalid_collection_element_ref_id_raises_value_error():
    fory = pyfory.Fory(xlang=True, ref=True, strict=False)
    buffer = pyfory.Buffer.allocate(64)

    buffer.write_int8(0b10)
    buffer.write_int8(REF_VALUE_FLAG)
    fory.type_resolver.write_type_info(buffer, fory.type_resolver.get_type_info(list))
    buffer.write_var_uint32(1)
    buffer.write_int8(0b1)  # COLL_TRACKING_REF
    buffer.write_int8(REF_FLAG)
    buffer.write_var_uint32(12345)

    payload = buffer.to_bytes(0, buffer.get_writer_index())
    with pytest.raises(ValueError, match="Invalid ref id"):
        fory.deserialize(payload)


@pytest.mark.parametrize("xlang", [False, True])
def test_optional_fixed_uint64_roundtrip(xlang):
    value = 1234567890123456789
    fory = pyfory.Fory(xlang=xlang, ref=True, strict=False)
    if xlang:
        fory.register_type(FixedUint64Pair, typename="example.FixedUint64Pair")
    else:
        fory.register(FixedUint64Pair)

    serializer = fory.type_resolver.get_serializer(pyfory.fixed_uint64)
    assert serializer.need_to_write_ref is False
    restored = _roundtrip(fory, FixedUint64Pair(value, value))
    assert restored.a == value
    assert restored.b == value


def test_primitive_list_fastpath_mutation_typeerror():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(Holder)
    for _ in range(10):
        lst = [EvilIndex() for _ in range(64)]
        for element in lst:
            element.owner = lst
        with pytest.raises(TypeError):
            fory.serialize(Holder(values=lst))
