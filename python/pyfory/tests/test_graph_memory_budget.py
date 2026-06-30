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

import pytest

import pyfory
from pyfory.serialization import Buffer
from pyfory.serializer import ListSerializer

try:
    import numpy as np
except ImportError:
    np = None


KNOWN_ROOT_BUDGET_MULTIPLIER = 8
KNOWN_ROOT_BUDGET_SLACK_BYTES = 64 * 1024
STREAM_ROOT_BUDGET_BYTES = 128 * 1024 * 1024
REFERENCE_BYTES = struct.calcsize("P")
OWNER_BYTES = 1
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


def collection_memory(num_elements):
    return OWNER_BYTES + num_elements * REFERENCE_BYTES


def map_memory(num_entries):
    return OWNER_BYTES + num_entries * 2 * REFERENCE_BYTES


def object_memory(num_fields):
    return OWNER_BYTES + num_fields * REFERENCE_BYTES


def new_fory(limit=-1, *, xlang=True):
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


def test_known_length_auto_budget():
    fory = new_fory(xlang=False)
    root_input_bytes = 17
    try:
        fory.read_context.prepare(Buffer(b"x" * root_input_bytes), root_input_bytes=root_input_bytes)
        expected = root_input_bytes * KNOWN_ROOT_BUDGET_MULTIPLIER + KNOWN_ROOT_BUDGET_SLACK_BYTES
        assert fory.read_context.graph_memory_limit_bytes == expected
        fory.read_context.reserve_graph_memory(expected)
        with pytest.raises(ValueError, match="Estimated graph memory budget exceeded"):
            fory.read_context.reserve_graph_memory(1)
    finally:
        fory.reset_read()


def test_stream_auto_budget():
    fory = new_fory(xlang=False)
    try:
        buffer = Buffer.from_stream(OneByteStream(b"streamed"))
        fory.read_context.prepare(buffer, root_input_bytes=1)
        assert fory.read_context.graph_memory_limit_bytes == STREAM_ROOT_BUDGET_BYTES
    finally:
        fory.reset_read()


def test_explicit_config_overrides_auto():
    value = [1]
    budget = collection_memory(1)
    assert expect_budget(value, budget) == value


def test_nested_empty_containers_use_parent_storage():
    value = [[]]
    budget = collection_memory(1) + collection_memory(0)
    assert expect_budget(value, budget) == value


def test_sibling_nested_containers_are_cumulative():
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


def test_dynamic_object_owner_is_charged():
    value = BudgetObject()
    value.left = 1
    value.right = "x"
    budget = object_memory(2)
    restored = expect_budget(value, budget, xlang=False)
    assert restored.left == value.left
    assert restored.right == value.right


def test_map_entry_budget_and_overflow():
    value = {"a": 1}
    assert expect_budget(value, map_memory(1)) == value

    fory = new_fory(xlang=False)
    try:
        fory.read_context.prepare(Buffer(b""), root_input_bytes=0)
        max_map_entries = MAX_GRAPH_MEMORY_BYTES // (2 * REFERENCE_BYTES)
        with pytest.raises(ValueError, match="Estimated graph memory overflow"):
            fory.read_context.reserve_counted_graph_memory(max_map_entries + 1, 2 * REFERENCE_BYTES)
    finally:
        fory.reset_read()


def test_object_reference_array_budget():
    value = (1, 2, 3)
    assert expect_budget(value, collection_memory(3), xlang=False) == value


def test_object_ndarray_budget():
    if np is None:
        pytest.skip("numpy is not installed")
    value = np.array([1, 2, 3], dtype=object)
    restored = expect_budget(value, collection_memory(3), xlang=False)
    np.testing.assert_array_equal(restored, value)


def test_string_binary_and_dense_arrays_skip_budget():
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


def test_declared_large_list_still_needs_bytes():
    fory = new_fory(10_000_000, xlang=False)
    serializer = ListSerializer(fory.type_resolver, list)
    try:
        fory.read_context.prepare(Buffer(varuint_payload(1000)), root_input_bytes=1)
        with pytest.raises(Exception) as exc_info:
            serializer.read(fory.read_context)
        assert "Estimated graph memory" not in str(exc_info.value)
    finally:
        fory.reset_read()


@pytest.mark.parametrize("limit", [0, -2, 1 << 63])
def test_invalid_config(limit):
    with pytest.raises(ValueError, match="max_graph_memory_bytes"):
        new_fory(limit)
