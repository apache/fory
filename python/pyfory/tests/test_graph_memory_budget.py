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
import dataclasses
import struct
import sys
from typing import Any

import pytest

import pyfory
from pyfory.serialization import Buffer
from pyfory.serializer import ListSerializer

try:
    import numpy as np
except ImportError:
    np = None


DEFAULT_GRAPH_MEMORY_BYTES = 128 * 1024 * 1024
REFERENCE_BYTES = struct.calcsize("P")
PY_OBJECT_OWNER_BYTES = 4 * REFERENCE_BYTES
LIST_OWNER_BYTES = 4 * REFERENCE_BYTES
TUPLE_OWNER_BYTES = 3 * REFERENCE_BYTES
DICT_OWNER_BYTES = 8 * REFERENCE_BYTES
MAX_GRAPH_MEMORY_BYTES = (1 << 63) - 1


class OneByteStream:
    def __init__(self, data: bytes):
        self._data = data
        self._offset = 0

    def read(self, size=-1):
        if self._offset >= len(self._data):
            return b""
        if size < 0:
            size = len(self._data) - self._offset
        if size == 0:
            return b""
        read_size = min(1, size, len(self._data) - self._offset)
        start = self._offset
        self._offset += read_size
        return self._data[start : start + read_size]

    def readinto(self, buffer):
        if self._offset >= len(self._data):
            return 0
        view = memoryview(buffer).cast("B")
        if len(view) == 0:
            return 0
        read_size = min(1, len(view), len(self._data) - self._offset)
        start = self._offset
        self._offset += read_size
        view[:read_size] = self._data[start : start + read_size]
        return read_size

    def recv_into(self, buffer, size=-1):
        if self._offset >= len(self._data):
            return 0
        view = memoryview(buffer).cast("B")
        if size < 0 or size > len(view):
            size = len(view)
        if size == 0:
            return 0
        read_size = min(1, size, len(self._data) - self._offset)
        start = self._offset
        self._offset += read_size
        view[:read_size] = self._data[start : start + read_size]
        return read_size


@dataclasses.dataclass
class BudgetItem:
    value: int


class BudgetObject:
    pass


class BudgetStatefulObject:
    def __getstate__(self):
        return None

    def __setstate__(self, state):
        pass


class BudgetReduceObject:
    def __reduce__(self):
        return (BudgetReduceObject, ())


SKIP_CLASS_ATTR_NAMES = (
    "__module__",
    "__qualname__",
    "__dict__",
    "__weakref__",
    "__annotate__",
    "__annotate_func__",
    "__annotations_cache__",
)


@dataclasses.dataclass
class BudgetRefNode:
    value: int = 0
    next: Any = pyfory.field(default=None, ref=True, nullable=True)
    children: Any = pyfory.field(default_factory=list, ref=True, nullable=True)


def collection_memory(num_elements):
    return LIST_OWNER_BYTES + num_elements * REFERENCE_BYTES


def tuple_memory(num_elements):
    return TUPLE_OWNER_BYTES + num_elements * REFERENCE_BYTES


def map_memory(num_entries):
    return DICT_OWNER_BYTES + num_entries * 2 * REFERENCE_BYTES


def object_memory(num_fields):
    return PY_OBJECT_OWNER_BYTES + num_fields * REFERENCE_BYTES


def new_fory(limit=DEFAULT_GRAPH_MEMORY_BYTES, *, xlang=True):
    return pyfory.Fory(
        xlang=xlang,
        ref=True,
        strict=False,
        compatible=xlang,
        max_graph_memory_bytes=limit,
    )


def expect_budget(value, budget, *, xlang=True):
    writer = new_fory(xlang=xlang)
    data = writer.serialize(value)
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        new_fory(budget - 1, xlang=xlang).deserialize(data)
    return new_fory(budget, xlang=xlang).deserialize(data)


def varuint_payload(value):
    buffer = Buffer.allocate(16)
    buffer.write_var_uint32(value)
    return buffer.to_bytes(0, buffer.get_writer_index())


def test_fixed_default_budget():
    assert pyfory.Fory(xlang=False, ref=True).max_graph_memory_bytes == DEFAULT_GRAPH_MEMORY_BYTES
    fory = new_fory(xlang=False)
    value = [[], [], []]
    assert fory.deserialize(fory.serialize(value)) == value


def test_stream_default_budget():
    fory = new_fory(xlang=False)
    value = [[], [], []]
    data = fory.serialize(value)
    assert fory.deserialize(Buffer.from_stream(OneByteStream(data))) == value


def test_explicit_budget():
    value = [1]
    budget = collection_memory(1)
    assert expect_budget(value, budget) == value


def test_nested_empty_containers():
    value = [[]]
    budget = collection_memory(1) + collection_memory(0)
    assert expect_budget(value, budget) == value


def test_sibling_cumulative_budget():
    value = [[], [], []]
    budget = collection_memory(3) + 3 * collection_memory(0)
    assert expect_budget(value, budget) == value


def test_empty_object_owner_is_charged():
    fory = new_fory(xlang=False)
    fory.register_type(BudgetItem)
    value = BudgetItem(1)
    budget = object_memory(1)
    data = fory.serialize(value)
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        reader = new_fory(budget - 1, xlang=False)
        reader.register_type(BudgetItem)
        reader.deserialize(data)
    reader = new_fory(budget, xlang=False)
    reader.register_type(BudgetItem)
    assert reader.deserialize(data) == value


def test_dynamic_object_budget():
    value = BudgetObject()
    value.left = 1
    value.right = "x"
    budget = object_memory(2)

    writer = new_fory(xlang=False)
    writer.register_type(BudgetObject)
    data = writer.serialize(value)
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        reader = new_fory(budget - 1, xlang=False)
        reader.register_type(BudgetObject)
        reader.deserialize(data)
    reader = new_fory(budget, xlang=False)
    reader.register_type(BudgetObject)
    restored = reader.deserialize(data)
    assert restored.left == value.left
    assert restored.right == value.right


def test_stateful_object_budget():
    value = BudgetStatefulObject()
    writer = new_fory(xlang=False)
    writer.register_type(BudgetStatefulObject)
    data = writer.serialize(value)

    constructor_state_budget = collection_memory(0) + map_memory(0)
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        reader = new_fory(constructor_state_budget, xlang=False)
        reader.register_type(BudgetStatefulObject)
        reader.deserialize(data)

    reader = new_fory(constructor_state_budget + PY_OBJECT_OWNER_BYTES, xlang=False)
    reader.register_type(BudgetStatefulObject)
    assert isinstance(reader.deserialize(data), BudgetStatefulObject)


def test_reduce_object_budget():
    value = BudgetReduceObject()
    writer = new_fory(xlang=False)
    writer.register_type(BudgetReduceObject)
    data = writer.serialize(value)

    reduce_args_budget = tuple_memory(0) + PY_OBJECT_OWNER_BYTES
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        reader = new_fory(reduce_args_budget - 1, xlang=False)
        reader.register_type(BudgetReduceObject)
        reader.deserialize(data)

    reader = new_fory(reduce_args_budget, xlang=False)
    reader.register_type(BudgetReduceObject)
    assert isinstance(reader.deserialize(data), BudgetReduceObject)


def test_local_function_budget():
    captured = 7

    def local_func(value=5):
        return value + captured

    writer = new_fory(xlang=False)
    data = writer.serialize(local_func)

    module_entries = len(sys.modules[local_func.__module__].__dict__)
    closure_memory = PY_OBJECT_OWNER_BYTES + REFERENCE_BYTES + PY_OBJECT_OWNER_BYTES
    budget = (
        PY_OBJECT_OWNER_BYTES
        + collection_memory(1)
        + closure_memory
        + map_memory(0)
        + map_memory(module_entries)
        + PY_OBJECT_OWNER_BYTES
        + map_memory(0)
    )
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        new_fory(budget - PY_OBJECT_OWNER_BYTES, xlang=False).deserialize(data)

    restored = new_fory(budget, xlang=False).deserialize(data)
    assert restored() == local_func()


def test_local_class_budget():
    def make_class():
        class LocalBudgetClass:
            pass

        return LocalBudgetClass

    cls = make_class()
    writer = new_fory(xlang=False)
    data = writer.serialize(cls)

    class_attrs = {name: value for name, value in cls.__dict__.items() if name not in SKIP_CLASS_ATTR_NAMES}
    class_attr_value_budget = sum(collection_memory(len(value)) for value in class_attrs.values() if isinstance(value, tuple))
    budget = collection_memory(1) + PY_OBJECT_OWNER_BYTES + map_memory(len(class_attrs)) + class_attr_value_budget
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        new_fory(collection_memory(1), xlang=False).deserialize(data)

    restored = new_fory(budget, xlang=False).deserialize(data)
    assert restored.__name__ == cls.__name__
    assert restored.__bases__ == cls.__bases__


def test_self_ref_budget():
    value = BudgetRefNode(value=7)
    value.next = value
    value.children.append(value)
    budget = object_memory(3) + collection_memory(1)

    writer = new_fory(xlang=False)
    writer.register_type(BudgetRefNode)
    data = writer.serialize(value)
    with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
        reader = new_fory(budget - 1, xlang=False)
        reader.register_type(BudgetRefNode)
        reader.deserialize(data)
    reader = new_fory(budget, xlang=False)
    reader.register_type(BudgetRefNode)
    restored = reader.deserialize(data)
    assert restored.next is restored
    assert restored.children == [restored]


def test_map_entry_budget_and_overflow():
    value = {"a": 1}
    assert expect_budget(value, map_memory(1)) == value

    fory = new_fory(xlang=False)
    try:
        with pytest.raises(ValueError, match="Estimated graph memory overflow"):
            fory.read_context.reserve_graph_memory(MAX_GRAPH_MEMORY_BYTES + 1)
    finally:
        fory.reset_read()


def test_object_reference_array_budget():
    value = (1, 2, 3)
    assert expect_budget(value, tuple_memory(3), xlang=False) == value


def test_object_ndarray_budget():
    if np is None:
        pytest.skip("numpy is not installed")
    value = np.array([1, 2, 3], dtype=object)
    restored = expect_budget(value, collection_memory(3), xlang=False)
    np.testing.assert_array_equal(restored, value)


def test_dense_leaf_owners_skipped():
    values = [
        "x" * 256,
        b"x" * 256,
        array.array("i", range(32)),
    ]
    if np is not None:
        values.append(np.array(list(range(32)), dtype=np.int32))
    for value in values:
        fory = new_fory(1, xlang=False)
        restored = fory.deserialize(fory.serialize(value))
        if np is not None and isinstance(value, np.ndarray):
            np.testing.assert_array_equal(restored, value)
        else:
            assert restored == value


def test_large_list_needs_bytes():
    fory = new_fory(10_000_000, xlang=False)
    serializer = ListSerializer(fory.type_resolver, list)
    try:
        fory.read_context.prepare(Buffer(varuint_payload(1000)))
        with pytest.raises(Exception) as exc_info:
            serializer.read(fory.read_context)
        assert "Estimated graph memory" not in str(exc_info.value)
    finally:
        fory.reset_read()


@pytest.mark.parametrize("limit", [0, -2, 1 << 63])
def test_invalid_config(limit):
    with pytest.raises(ValueError, match="max_graph_memory_bytes"):
        new_fory(limit)
