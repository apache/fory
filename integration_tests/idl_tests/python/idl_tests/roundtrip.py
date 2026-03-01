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
import auto_id
import any_example
import complex_fbs
import complex_pb
import collection
import evolving1
import evolving2
import monster
import optional_types
import graph
import tree
import root
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


def build_root_holder() -> "root.MultiHolder":
    owner = addressbook.Person(
        name="Alice",
        id=123,
        email="",
        tags=[],
        scores={},
        salary=0.0,
        phones=[],
        pet=addressbook.Animal.dog(addressbook.Dog(name="Rex", bark_volume=5)),
    )
    book = addressbook.AddressBook(
        people=[owner],
        people_by_name={owner.name: owner},
    )
    root_node = tree.TreeNode(
        id="root",
        name="root",
        children=[],
        parent=None,
    )
    return root.MultiHolder(
        book=book,
        root=root_node,
        owner=owner,
    )


def build_auto_id_envelope() -> "auto_id.Envelope":
    payload = auto_id.Envelope.Payload(value=42)
    detail = auto_id.Envelope.Detail.payload(payload)
    return auto_id.Envelope(
        id="env-1",
        payload=payload,
        detail=detail,
        status=auto_id.Status.OK,
    )


def build_auto_id_wrapper(envelope: "auto_id.Envelope") -> "auto_id.Wrapper":
    return auto_id.Wrapper.envelope(envelope)


def local_roundtrip(fory: pyfory.Fory, book: "addressbook.AddressBook") -> None:
    data = fory.serialize(book)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, addressbook.AddressBook)
    assert decoded == book


def bytes_roundtrip_addressbook(book: "addressbook.AddressBook") -> None:
    payload = book.to_bytes()
    decoded = addressbook.AddressBook.from_bytes(payload)
    assert decoded == book

    dog = addressbook.Dog(name="Rex", bark_volume=5)
    animal = addressbook.Animal.dog(dog)
    animal_payload = animal.to_bytes()
    decoded_animal = addressbook.Animal.from_bytes(animal_payload)
    assert decoded_animal == animal


def bytes_roundtrip_root(multi: "root.MultiHolder") -> None:
    payload = multi.to_bytes()
    decoded = root.MultiHolder.from_bytes(payload)
    assert decoded == multi


def file_roundtrip(fory: pyfory.Fory, book: "addressbook.AddressBook") -> None:
    data_file = os.environ.get("DATA_FILE")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, addressbook.AddressBook)
    assert decoded == book
    Path(data_file).write_bytes(fory.serialize(decoded))


def local_roundtrip_auto_id(fory: pyfory.Fory, envelope: "auto_id.Envelope") -> None:
    data = fory.serialize(envelope)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, auto_id.Envelope)
    assert decoded == envelope


def local_roundtrip_auto_wrapper(fory: pyfory.Fory, wrapper: "auto_id.Wrapper") -> None:
    data = fory.serialize(wrapper)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, auto_id.Wrapper)
    assert decoded == wrapper


def file_roundtrip_auto_id(fory: pyfory.Fory, envelope: "auto_id.Envelope") -> None:
    data_file = os.environ.get("DATA_FILE_AUTO_ID")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, auto_id.Envelope)
    assert decoded == envelope
    Path(data_file).write_bytes(fory.serialize(decoded))


def local_roundtrip_evolving() -> None:
    fory_v1 = pyfory.Fory(xlang=True, ref=False, compatible=True)
    fory_v2 = pyfory.Fory(xlang=True, ref=False, compatible=True)
    evolving1.register_evolving1_types(fory_v1)
    evolving2.register_evolving2_types(fory_v2)

    msg_v1 = evolving1.EvolvingMessage(id=1, name="Alice", city="NYC")
    data = fory_v1.serialize(msg_v1)
    msg_v2 = fory_v2.deserialize(data)
    assert isinstance(msg_v2, evolving2.EvolvingMessage)
    assert msg_v2.id == msg_v1.id
    assert msg_v2.name == msg_v1.name
    assert msg_v2.city == msg_v1.city
    msg_v2.email = "alice@example.com"
    round_bytes = fory_v2.serialize(msg_v2)
    msg_v1_round = fory_v1.deserialize(round_bytes)
    assert msg_v1_round == msg_v1

    fixed_v1 = evolving1.FixedMessage(id=10, name="Bob", score=90, note="note")
    fixed_bytes = fory_v1.serialize(fixed_v1)
    try:
        fixed_v2 = fory_v2.deserialize(fixed_bytes)
    except Exception:
        return
    round_fixed = fory_v2.serialize(fixed_v2)
    fixed_v1_round = fory_v1.deserialize(round_fixed)
    assert fixed_v1_round != fixed_v1


def build_primitive_types() -> "complex_pb.PrimitiveTypes":
    contact = complex_pb.PrimitiveTypes.Contact.email("alice@example.com")
    contact = complex_pb.PrimitiveTypes.Contact.phone(12345)
    return complex_pb.PrimitiveTypes(
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


def build_numeric_collections() -> "collection.NumericCollections":
    return collection.NumericCollections(
        int8_values=np.array([1, -2, 3], dtype=np.int8),
        int16_values=np.array([100, -200, 300], dtype=np.int16),
        int32_values=np.array([1000, -2000, 3000], dtype=np.int32),
        int64_values=np.array([10000, -20000, 30000], dtype=np.int64),
        uint8_values=np.array([200, 250], dtype=np.uint8),
        uint16_values=np.array([50000, 60000], dtype=np.uint16),
        uint32_values=np.array([2000000000, 2100000000], dtype=np.uint32),
        uint64_values=np.array([9000000000, 12000000000], dtype=np.uint64),
        float32_values=np.array([1.5, 2.5], dtype=np.float32),
        float64_values=np.array([3.5, 4.5], dtype=np.float64),
    )


def build_numeric_collection_union() -> "collection.NumericCollectionUnion":
    return collection.NumericCollectionUnion.int32_values(
        np.array([7, 8, 9], dtype=np.int32)
    )


def build_numeric_collections_array() -> "collection.NumericCollectionsArray":
    return collection.NumericCollectionsArray(
        int8_values=np.array([1, -2, 3], dtype=np.int8),
        int16_values=np.array([100, -200, 300], dtype=np.int16),
        int32_values=np.array([1000, -2000, 3000], dtype=np.int32),
        int64_values=np.array([10000, -20000, 30000], dtype=np.int64),
        uint8_values=np.array([200, 250], dtype=np.uint8),
        uint16_values=np.array([50000, 60000], dtype=np.uint16),
        uint32_values=np.array([2000000000, 2100000000], dtype=np.uint32),
        uint64_values=np.array([9000000000, 12000000000], dtype=np.uint64),
        float32_values=np.array([1.5, 2.5], dtype=np.float32),
        float64_values=np.array([3.5, 4.5], dtype=np.float64),
    )


def build_numeric_collection_array_union() -> "collection.NumericCollectionArrayUnion":
    return collection.NumericCollectionArrayUnion.uint16_values(
        np.array([1000, 2000, 3000], dtype=np.uint16)
    )


def assert_numeric_collections_equal(
    decoded: "collection.NumericCollections",
    expected: "collection.NumericCollections",
) -> None:
    assert np.array_equal(decoded.int8_values, expected.int8_values)
    assert np.array_equal(decoded.int16_values, expected.int16_values)
    assert np.array_equal(decoded.int32_values, expected.int32_values)
    assert np.array_equal(decoded.int64_values, expected.int64_values)
    assert np.array_equal(decoded.uint8_values, expected.uint8_values)
    assert np.array_equal(decoded.uint16_values, expected.uint16_values)
    assert np.array_equal(decoded.uint32_values, expected.uint32_values)
    assert np.array_equal(decoded.uint64_values, expected.uint64_values)
    assert np.array_equal(decoded.float32_values, expected.float32_values)
    assert np.array_equal(decoded.float64_values, expected.float64_values)


def assert_numeric_collections_array_equal(
    decoded: "collection.NumericCollectionsArray",
    expected: "collection.NumericCollectionsArray",
) -> None:
    assert np.array_equal(decoded.int8_values, expected.int8_values)
    assert np.array_equal(decoded.int16_values, expected.int16_values)
    assert np.array_equal(decoded.int32_values, expected.int32_values)
    assert np.array_equal(decoded.int64_values, expected.int64_values)
    assert np.array_equal(decoded.uint8_values, expected.uint8_values)
    assert np.array_equal(decoded.uint16_values, expected.uint16_values)
    assert np.array_equal(decoded.uint32_values, expected.uint32_values)
    assert np.array_equal(decoded.uint64_values, expected.uint64_values)
    assert np.array_equal(decoded.float32_values, expected.float32_values)
    assert np.array_equal(decoded.float64_values, expected.float64_values)


def assert_numeric_collection_union_equal(
    decoded: "collection.NumericCollectionUnion",
    expected: "collection.NumericCollectionUnion",
) -> None:
    assert decoded.case() == expected.case()
    if decoded.case() == collection.NumericCollectionUnionCase.INT32_VALUES:
        assert np.array_equal(
            decoded.int32_values_value(), expected.int32_values_value()
        )
        return
    raise AssertionError(f"unexpected union case: {decoded.case()}")


def assert_numeric_collection_array_union_equal(
    decoded: "collection.NumericCollectionArrayUnion",
    expected: "collection.NumericCollectionArrayUnion",
) -> None:
    assert decoded.case() == expected.case()
    if decoded.case() == collection.NumericCollectionArrayUnionCase.UINT16_VALUES:
        assert np.array_equal(
            decoded.uint16_values_value(), expected.uint16_values_value()
        )
        return
    raise AssertionError(f"unexpected union case: {decoded.case()}")


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
        timestamp_value=datetime.datetime.fromtimestamp(1704164645, tz=datetime.timezone.utc),
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
        timestamp_value=datetime.datetime.fromtimestamp(1704164645, tz=datetime.timezone.utc),
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
    fory: pyfory.Fory, types: "complex_pb.PrimitiveTypes"
) -> None:
    data = fory.serialize(types)
    decoded = fory.deserialize(data)
    assert isinstance(decoded, complex_pb.PrimitiveTypes)
    assert decoded == types


def file_roundtrip_primitives(
    fory: pyfory.Fory, types: "complex_pb.PrimitiveTypes"
) -> None:
    data_file = os.environ.get("DATA_FILE_PRIMITIVES")
    if not data_file:
        return
    payload = Path(data_file).read_bytes()
    decoded = fory.deserialize(payload)
    assert isinstance(decoded, complex_pb.PrimitiveTypes)
    assert decoded == types
    Path(data_file).write_bytes(fory.serialize(decoded))


def local_roundtrip_collections(
    fory: pyfory.Fory,
    collections_value: "collection.NumericCollections",
    union_value: "collection.NumericCollectionUnion",
    array_value: "collection.NumericCollectionsArray",
    array_union_value: "collection.NumericCollectionArrayUnion",
) -> None:
    decoded = fory.deserialize(fory.serialize(collections_value))
    assert isinstance(decoded, collection.NumericCollections)
    assert_numeric_collections_equal(decoded, collections_value)

    decoded_union = fory.deserialize(fory.serialize(union_value))
    assert isinstance(decoded_union, collection.NumericCollectionUnion)
    assert_numeric_collection_union_equal(decoded_union, union_value)

    decoded_array = fory.deserialize(fory.serialize(array_value))
    assert isinstance(decoded_array, collection.NumericCollectionsArray)
    assert_numeric_collections_array_equal(decoded_array, array_value)

    decoded_array_union = fory.deserialize(fory.serialize(array_union_value))
    assert isinstance(decoded_array_union, collection.NumericCollectionArrayUnion)
    assert_numeric_collection_array_union_equal(decoded_array_union, array_union_value)


def file_roundtrip_collections(
    fory: pyfory.Fory,
    collections_value: "collection.NumericCollections",
    union_value: "collection.NumericCollectionUnion",
    array_value: "collection.NumericCollectionsArray",
    array_union_value: "collection.NumericCollectionArrayUnion",
) -> None:
    data_file = os.environ.get("DATA_FILE_COLLECTION")
    if data_file:
        payload = Path(data_file).read_bytes()
        decoded = fory.deserialize(payload)
        assert isinstance(decoded, collection.NumericCollections)
        assert_numeric_collections_equal(decoded, collections_value)
        Path(data_file).write_bytes(fory.serialize(decoded))

    union_file = os.environ.get("DATA_FILE_COLLECTION_UNION")
    if union_file:
        payload = Path(union_file).read_bytes()
        decoded = fory.deserialize(payload)
        assert isinstance(decoded, collection.NumericCollectionUnion)
        assert_numeric_collection_union_equal(decoded, union_value)
        Path(union_file).write_bytes(fory.serialize(decoded))

    array_file = os.environ.get("DATA_FILE_COLLECTION_ARRAY")
    if array_file:
        payload = Path(array_file).read_bytes()
        decoded = fory.deserialize(payload)
        assert isinstance(decoded, collection.NumericCollectionsArray)
        assert_numeric_collections_array_equal(decoded, array_value)
        Path(array_file).write_bytes(fory.serialize(decoded))

    array_union_file = os.environ.get("DATA_FILE_COLLECTION_ARRAY_UNION")
    if array_union_file:
        payload = Path(array_union_file).read_bytes()
        decoded = fory.deserialize(payload)
        assert isinstance(decoded, collection.NumericCollectionArrayUnion)
        assert_numeric_collection_array_union_equal(decoded, array_union_value)
        Path(array_union_file).write_bytes(fory.serialize(decoded))


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


def run_roundtrip(compatible: bool) -> None:
    fory = pyfory.Fory(xlang=True, compatible=compatible)
    complex_pb.register_complex_pb_types(fory)
    addressbook.register_addressbook_types(fory)
    auto_id.register_auto_id_types(fory)
    monster.register_monster_types(fory)
    complex_fbs.register_complex_fbs_types(fory)
    collection.register_collection_types(fory)
    optional_types.register_optional_types_types(fory)
    any_example.register_any_example_types(fory)

    book = build_address_book()
    bytes_roundtrip_addressbook(book)
    bytes_roundtrip_root(build_root_holder())
    local_roundtrip(fory, book)
    file_roundtrip(fory, book)

    auto_envelope = build_auto_id_envelope()
    auto_wrapper = build_auto_id_wrapper(auto_envelope)
    local_roundtrip_auto_id(fory, auto_envelope)
    local_roundtrip_auto_wrapper(fory, auto_wrapper)
    file_roundtrip_auto_id(fory, auto_envelope)

    primitives = build_primitive_types()
    local_roundtrip_primitives(fory, primitives)
    file_roundtrip_primitives(fory, primitives)

    collections_value = build_numeric_collections()
    union_value = build_numeric_collection_union()
    array_value = build_numeric_collections_array()
    array_union_value = build_numeric_collection_array_union()
    local_roundtrip_collections(
        fory, collections_value, union_value, array_value, array_union_value
    )
    file_roundtrip_collections(
        fory, collections_value, union_value, array_value, array_union_value
    )

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

    ref_fory = pyfory.Fory(xlang=True, ref=True, compatible=compatible)
    tree.register_tree_types(ref_fory)
    graph.register_graph_types(ref_fory)
    tree_root = build_tree()
    local_roundtrip_tree(ref_fory, tree_root)
    file_roundtrip_tree(ref_fory, tree_root)
    graph_value = build_graph()
    local_roundtrip_graph(ref_fory, graph_value)
    file_roundtrip_graph(ref_fory, graph_value)

    if compatible:
        local_roundtrip_evolving()


def resolve_compatible_modes() -> list[bool]:
    value = os.environ.get("IDL_COMPATIBLE")
    if value is None or value.strip() == "":
        return [False, True]
    normalized = value.strip().lower()
    if normalized in ("1", "true", "yes"):
        return [True]
    if normalized in ("0", "false", "no"):
        return [False]
    raise ValueError(f"Unsupported IDL_COMPATIBLE value: {value}")


def main() -> int:
    for compatible in resolve_compatible_modes():
        run_roundtrip(compatible)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
