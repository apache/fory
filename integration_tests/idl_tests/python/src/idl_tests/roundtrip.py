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

from __future__ import annotations

import datetime
import os
from pathlib import Path

import addressbook
import any_example
import complex_fbs
import monster
import optional_types
import graph
import tree
import numpy as np
import pyfory


def build_address_book() -> "addressbook.AddressBook":
    mobile = addressbook.Person.PhoneNumber(
        number="555-0100",
        phone_type=addressbook.Person.PhoneType.MOBILE,
    )
    work = addressbook.Person.PhoneNumber(
        number="555-0111",
        phone_type=addressbook.Person.PhoneType.WORK,
    )

    pet = addressbook.Animal.dog(addressbook.Dog(name="Rex", bark_volume=5))
    pet = addressbook.Animal.cat(addressbook.Cat(name="Mimi", lives=9))

    person = addressbook.Person(
        name="Alice",
        id=123,
        email="alice@example.com",
        tags=["friend", "colleague"],
        scores={"math": 100, "science": 98},
        salary=120000.5,
        phones=[mobile, work],
        pet=pet,
    )

    return addressbook.AddressBook(
        people=[person],
        people_by_name={person.name: person},
    )


def local_roundtrip(fory: pyfory.Fory, book: "addressbook.AddressBook") -> None:
    data = fory.serialize(book)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, addressbook.AddressBook)
    assert decoded == book


def file_roundtrip(fory: pyfory.Fory, book: "addressbook.AddressBook") -> None:
    data_file = os.environ.get("DATA_FILE")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, addressbook.AddressBook)
    assert decoded == book
    Path(data_file).write_bytes(fory.serialize(decoded))


def build_primitive_types() -> "addressbook.PrimitiveTypes":
    contact = addressbook.PrimitiveTypes.Contact.email("alice@example.com")
    contact = addressbook.PrimitiveTypes.Contact.phone(12345)
    return addressbook.PrimitiveTypes(
        bool_value=True,
        int8_value=12,
        int16_value=1234,
        int32_value=-123456,
        varint32_value=-12345,
        int64_value=-123456789,
        varint64_value=-987654321,
        tagged_int64_value=123456789,
        uint8_value=200,
        uint16_value=60000,
        uint32_value=1234567890,
        var_uint32_value=1234567890,
        uint64_value=9876543210,
        var_uint64_value=12345678901,
        tagged_uint64_value=2222222222,
        float32_value=2.5,
        float64_value=3.5,
        contact=contact,
    )


def build_optional_holder() -> "optional_types.OptionalHolder":
    all_types = optional_types.AllOptionalTypes(
        bool_value=True,
        int8_value=12,
        int16_value=1234,
        int32_value=-123456,
        fixed_int32_value=-123456,
        varint32_value=-12345,
        int64_value=-123456789,
        fixed_int64_value=-123456789,
        varint64_value=-987654321,
        tagged_int64_value=123456789,
        uint8_value=200,
        uint16_value=60000,
        uint32_value=1234567890,
        fixed_uint32_value=1234567890,
        var_uint32_value=1234567890,
        uint64_value=9876543210,
        fixed_uint64_value=9876543210,
        var_uint64_value=12345678901,
        tagged_uint64_value=2222222222,
        float32_value=2.5,
        float64_value=3.5,
        string_value="optional",
        bytes_value=b"\x01\x02\x03",
        date_value=datetime.date(2024, 1, 2),
        timestamp_value=datetime.datetime.fromtimestamp(1704164645),
        int32_list=np.array([1, 2, 3], dtype=np.int32),
        string_list=["alpha", "beta"],
        int64_map={"alpha": 10, "beta": 20},
    )
    union_value = optional_types.OptionalUnion.note("optional")
    return optional_types.OptionalHolder(all_types=all_types, choice=union_value)


def build_any_holder() -> "any_example.AnyHolder":
    inner = any_example.AnyInner(name="inner")
    union_value = any_example.AnyUnion.text("union")
    return any_example.AnyHolder(
        bool_value=True,
        string_value="hello",
        date_value=datetime.date(2024, 1, 2),
        timestamp_value=datetime.datetime.fromtimestamp(1704164645),
        message_value=inner,
        union_value=union_value,
        list_value=["alpha", "beta"],
        map_value={"k1": "v1", "k2": "v2"},
    )


def local_roundtrip_any(fory: pyfory.Fory, holder: "any_example.AnyHolder") -> None:
    data = fory.serialize(holder)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, any_example.AnyHolder)
    assert decoded == holder


def build_monster() -> "monster.Monster":
    pos = monster.Vec3(x=1.0, y=2.0, z=3.0)
    return monster.Monster(
        pos=pos,
        mana=200,
        hp=80,
        name="Orc",
        friendly=True,
        inventory=np.array([1, 2, 3], dtype=np.uint8),
        color=monster.Color.Blue,
    )


def assert_monster_equal(
    decoded: "monster.Monster", expected: "monster.Monster"
) -> None:
    assert decoded.pos == expected.pos
    assert decoded.mana == expected.mana
    assert decoded.hp == expected.hp
    assert decoded.name == expected.name
    assert decoded.friendly == expected.friendly
    np.testing.assert_array_equal(decoded.inventory, expected.inventory)
    assert decoded.color == expected.color


def local_roundtrip_monster(
    fory: pyfory.Fory, monster_value: "monster.Monster"
) -> None:
    data = fory.serialize(monster_value)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, monster.Monster)
    assert_monster_equal(decoded, monster_value)


def file_roundtrip_monster(fory: pyfory.Fory, monster_value: "monster.Monster") -> None:
    data_file = os.environ.get("DATA_FILE_FLATBUFFERS_MONSTER")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, monster.Monster)
    assert_monster_equal(decoded, monster_value)
    Path(data_file).write_bytes(fory.serialize(decoded))


def build_container() -> "complex_fbs.Container":
    scalars = complex_fbs.ScalarPack(
        b=-8,
        ub=200,
        s=-1234,
        us=40000,
        i=-123456,
        ui=123456,
        l=-123456789,
        ul=987654321,
        f=1.5,
        d=2.5,
        ok=True,
    )
    payload = complex_fbs.Payload.note(complex_fbs.Note(text="alpha"))
    payload = complex_fbs.Payload.metric(complex_fbs.Metric(value=42.0))
    return complex_fbs.Container(
        id=9876543210,
        status=complex_fbs.Status.STARTED,
        bytes=np.array([1, 2, 3], dtype=np.int8),
        numbers=np.array([10, 20, 30], dtype=np.int32),
        scalars=scalars,
        names=["alpha", "beta"],
        flags=np.array([True, False], dtype=np.bool_),
        payload=payload,
    )


def assert_container_equal(
    decoded: "complex_fbs.Container", expected: "complex_fbs.Container"
) -> None:
    assert decoded.id == expected.id
    assert decoded.status == expected.status
    np.testing.assert_array_equal(decoded.bytes, expected.bytes)
    np.testing.assert_array_equal(decoded.numbers, expected.numbers)
    assert decoded.scalars == expected.scalars
    assert decoded.names == expected.names
    np.testing.assert_array_equal(decoded.flags, expected.flags)
    assert decoded.payload == expected.payload


def local_roundtrip_container(
    fory: pyfory.Fory, container: "complex_fbs.Container"
) -> None:
    data = fory.serialize(container)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, complex_fbs.Container)
    assert_container_equal(decoded, container)


def file_roundtrip_container(
    fory: pyfory.Fory, container: "complex_fbs.Container"
) -> None:
    data_file = os.environ.get("DATA_FILE_FLATBUFFERS_TEST2")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, complex_fbs.Container)
    assert_container_equal(decoded, container)
    Path(data_file).write_bytes(fory.serialize(decoded))


def local_roundtrip_primitives(
    fory: pyfory.Fory, types: "addressbook.PrimitiveTypes"
) -> None:
    data = fory.serialize(types)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, addressbook.PrimitiveTypes)
    assert decoded == types


def file_roundtrip_primitives(
    fory: pyfory.Fory, types: "addressbook.PrimitiveTypes"
) -> None:
    data_file = os.environ.get("DATA_FILE_PRIMITIVES")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, addressbook.PrimitiveTypes)
    assert decoded == types
    Path(data_file).write_bytes(fory.serialize(decoded))


def assert_optional_types_equal(
    decoded: "optional_types.AllOptionalTypes",
    expected: "optional_types.AllOptionalTypes",
) -> None:
    assert decoded.bool_value == expected.bool_value
    assert decoded.int8_value == expected.int8_value
    assert decoded.int16_value == expected.int16_value
    assert decoded.int32_value == expected.int32_value
    assert decoded.fixed_int32_value == expected.fixed_int32_value
    assert decoded.varint32_value == expected.varint32_value
    assert decoded.int64_value == expected.int64_value
    assert decoded.fixed_int64_value == expected.fixed_int64_value
    assert decoded.varint64_value == expected.varint64_value
    assert decoded.tagged_int64_value == expected.tagged_int64_value
    assert decoded.uint8_value == expected.uint8_value
    assert decoded.uint16_value == expected.uint16_value
    assert decoded.uint32_value == expected.uint32_value
    assert decoded.fixed_uint32_value == expected.fixed_uint32_value
    assert decoded.var_uint32_value == expected.var_uint32_value
    assert decoded.uint64_value == expected.uint64_value
    assert decoded.fixed_uint64_value == expected.fixed_uint64_value
    assert decoded.var_uint64_value == expected.var_uint64_value
    assert decoded.tagged_uint64_value == expected.tagged_uint64_value
    assert decoded.float32_value == expected.float32_value
    assert decoded.float64_value == expected.float64_value
    assert decoded.string_value == expected.string_value
    assert decoded.bytes_value == expected.bytes_value
    assert decoded.date_value == expected.date_value
    assert decoded.timestamp_value == expected.timestamp_value
    if expected.int32_list is None:
        assert decoded.int32_list is None
    else:
        np.testing.assert_array_equal(decoded.int32_list, expected.int32_list)
    assert decoded.string_list == expected.string_list
    assert decoded.int64_map == expected.int64_map


def assert_optional_holder_equal(
    decoded: "optional_types.OptionalHolder",
    expected: "optional_types.OptionalHolder",
) -> None:
    assert decoded.all_types is not None
    assert expected.all_types is not None
    assert_optional_types_equal(decoded.all_types, expected.all_types)
    assert decoded.choice is not None
    assert expected.choice is not None
    assert decoded.choice.case() == expected.choice.case()
    if decoded.choice.is_payload():
        assert_optional_types_equal(
            decoded.choice.payload_value(), expected.choice.payload_value()
        )
    elif decoded.choice.is_note():
        assert decoded.choice.note_value() == expected.choice.note_value()
    else:
        assert decoded.choice.code_value() == expected.choice.code_value()


def local_roundtrip_optional_types(
    fory: pyfory.Fory, holder: "optional_types.OptionalHolder"
) -> None:
    data = fory.serialize(holder)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, optional_types.OptionalHolder)
    assert_optional_holder_equal(decoded, holder)


def file_roundtrip_optional_types(
    fory: pyfory.Fory, holder: "optional_types.OptionalHolder"
) -> None:
    data_file = os.environ.get("DATA_FILE_OPTIONAL_TYPES")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, optional_types.OptionalHolder)
    assert_optional_holder_equal(decoded, holder)
    Path(data_file).write_bytes(fory.serialize(decoded))


def build_tree() -> "tree.TreeNode":
    child_a = tree.TreeNode(id="child-a", name="child-a")
    child_b = tree.TreeNode(id="child-b", name="child-b")
    child_a.parent = child_b
    child_b.parent = child_a

    root = tree.TreeNode(id="root", name="root")
    root.children = [child_a, child_a, child_b]
    return root


def assert_tree(root: "tree.TreeNode") -> None:
    children = root.children
    assert len(children) == 3
    assert children[0] is children[1]
    assert children[0] is not children[2]
    assert children[0].parent is children[2]
    assert children[2].parent is children[0]


def local_roundtrip_tree(fory: pyfory.Fory, root: "tree.TreeNode") -> None:
    data = fory.serialize(root)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, tree.TreeNode)
    assert_tree(decoded)


def file_roundtrip_tree(fory: pyfory.Fory, root: "tree.TreeNode") -> None:
    data_file = os.environ.get("DATA_FILE_TREE")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, tree.TreeNode)
    assert_tree(decoded)
    Path(data_file).write_bytes(fory.serialize(decoded))


def build_graph() -> "graph.Graph":
    node_a = graph.Node(id="node-a")
    node_b = graph.Node(id="node-b")
    edge = graph.Edge(id="edge-1", weight=1.5, from_=node_a, to=node_b)

    node_a.out_edges = [edge]
    node_a.in_edges = [edge]
    node_b.in_edges = [edge]
    node_b.out_edges = []

    return graph.Graph(nodes=[node_a, node_b], edges=[edge])


def assert_graph(value: "graph.Graph") -> None:
    assert len(value.nodes) == 2
    assert len(value.edges) == 1
    node_a = value.nodes[0]
    node_b = value.nodes[1]
    edge = value.edges[0]
    assert edge is node_a.out_edges[0]
    assert edge is node_a.in_edges[0]
    assert edge.from_ is node_a
    assert edge.to is node_b


def local_roundtrip_graph(fory: pyfory.Fory, graph_value: "graph.Graph") -> None:
    data = fory.serialize(graph_value)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, graph.Graph)
    assert_graph(decoded)


def file_roundtrip_graph(fory: pyfory.Fory, graph_value: "graph.Graph") -> None:
    data_file = os.environ.get("DATA_FILE_GRAPH")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, graph.Graph)
    assert_graph(decoded)
    Path(data_file).write_bytes(fory.serialize(decoded))


def main() -> int:
    fory = pyfory.Fory(xlang=True)
    addressbook.register_addressbook_types(fory)
    monster.register_monster_types(fory)
    complex_fbs.register_complex_fbs_types(fory)
    optional_types.register_optional_types_types(fory)
    any_example.register_any_example_types(fory)

    book = build_address_book()
    local_roundtrip(fory, book)
    file_roundtrip(fory, book)

    primitives = build_primitive_types()
    local_roundtrip_primitives(fory, primitives)
    file_roundtrip_primitives(fory, primitives)

    monster_value = build_monster()
    local_roundtrip_monster(fory, monster_value)
    file_roundtrip_monster(fory, monster_value)

    container = build_container()
    local_roundtrip_container(fory, container)
    file_roundtrip_container(fory, container)

    holder = build_optional_holder()
    local_roundtrip_optional_types(fory, holder)
    file_roundtrip_optional_types(fory, holder)

    any_holder = build_any_holder()
    local_roundtrip_any(fory, any_holder)

    ref_fory = pyfory.Fory(xlang=True, ref=True)
    tree.register_tree_types(ref_fory)
    graph.register_graph_types(ref_fory)
    tree_root = build_tree()
    local_roundtrip_tree(ref_fory, tree_root)
    file_roundtrip_tree(ref_fory, tree_root)
    graph_value = build_graph()
    local_roundtrip_graph(ref_fory, graph_value)
    file_roundtrip_graph(ref_fory, graph_value)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
