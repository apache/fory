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

import pytest

import pyfory
from pyfory.serialization import Buffer


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

    def recvinto(self, buffer, size=-1):
        return self.recv_into(buffer, size)


@pytest.mark.parametrize("xlang", [False, True])
def test_stream_roundtrip_primitives_and_strings(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    values = [
        0,
        -123456789,
        3.1415926,
        "stream-hello",
        "stream-你好-😀",
        b"binary-data" * 8,
        [1, 2, 3, 5, 8],
    ]

    for value in values:
        data = fory.serialize(value)
        restored = fory.deserialize(Buffer.from_stream(OneByteStream(data)))
        assert restored == value


@pytest.mark.parametrize("xlang", [False, True])
def test_stream_roundtrip_nested_collections(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    value = {
        "name": "stream-object",
        "items": [1, 2, {"k1": "v1", "k2": [3, 4, 5]}],
        "scores": {"a": 10, "b": 20},
        "flags": [True, False, True],
    }

    data = fory.serialize(value)
    restored = fory.deserialize(Buffer.from_stream(OneByteStream(data)))
    assert restored == value


def test_stream_roundtrip_reference_graph_python_mode():
    fory = pyfory.Fory(xlang=False, ref=True)
    shared = ["x", 1, 2]
    value = {"a": shared, "b": shared}
    cycle = []
    cycle.append(cycle)

    data_ref = fory.serialize(value)
    restored_ref = fory.deserialize(Buffer.from_stream(OneByteStream(data_ref)))
    assert restored_ref["a"] == shared
    assert restored_ref["a"] is restored_ref["b"]

    data_cycle = fory.serialize(cycle)
    restored_cycle = fory.deserialize(Buffer.from_stream(OneByteStream(data_cycle)))
    assert restored_cycle[0] is restored_cycle


@pytest.mark.parametrize("xlang", [False, True])
def test_stream_deserialize_multiple_objects_from_single_stream(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    expected = [
        2026,
        "multi-object-stream",
        {"k": [1, 2, 3], "nested": {"x": True}},
        [10, 20, 30, 40],
    ]

    write_buffer = Buffer.allocate(1024)
    for obj in expected:
        fory.serialize(obj, write_buffer)

    reader = Buffer.from_stream(OneByteStream(write_buffer.get_bytes(0, write_buffer.get_writer_index())))
    for obj in expected:
        assert fory.deserialize(reader) == obj

    assert reader.get_reader_index() == reader.size()


@pytest.mark.parametrize("xlang", [False, True])
def test_stream_deserialize_truncated_error(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    data = fory.serialize({"k": "value", "numbers": [1, 2, 3, 4]})
    truncated = data[:-1]

    with pytest.raises(Exception):
        fory.deserialize(Buffer.from_stream(OneByteStream(truncated)))
