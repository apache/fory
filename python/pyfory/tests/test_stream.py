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
import io
import pickle

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


class OneByteWriteStream:
    def __init__(self):
        self._data = bytearray()

    def write(self, payload):
        if not payload:
            return 0
        view = memoryview(payload).cast("B")
        self._data.append(view[0])
        return 1

    def to_bytes(self):
        return bytes(self._data)


class CountingWriteStream:
    def __init__(self):
        self._data = bytearray()
        self.write_calls = 0
        self.flush_calls = 0

    def write(self, payload):
        view = memoryview(payload).cast("B")
        self.write_calls += 1
        self._data.extend(view)
        return len(view)

    def flush(self):
        self.flush_calls += 1

    def to_bytes(self):
        return bytes(self._data)


@dataclass
class StreamStructValue:
    idx: int
    name: str
    values: list


@dataclass
class StreamPickleBufferValue:
    idx: int
    payload: pickle.PickleBuffer


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
def test_stream_backed_buffer_struct_deserialize_shrinks_each_struct(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    fory.register(StreamStructValue)
    first = StreamStructValue(101, "first", list(range(6000)))
    second = StreamStructValue(202, "second", list(range(6000, 0, -1)))

    payload = fory.dumps(first) + fory.dumps(second)
    reader = Buffer.from_stream(io.BytesIO(payload), 4096)

    first_result = fory.deserialize(reader)
    assert first_result == first
    assert reader.get_reader_index() == 0

    second_result = fory.deserialize(reader)
    assert second_result == second
    assert reader.get_reader_index() == 0


def test_stream_backed_buffer_pickle_buffer_not_corrupted_after_next_struct():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(StreamPickleBufferValue)
    first_payload = b"a" * 7000
    second_payload = b"b" * 7000
    first = StreamPickleBufferValue(101, pickle.PickleBuffer(first_payload))
    second = StreamPickleBufferValue(202, pickle.PickleBuffer(second_payload))

    writer = Buffer.allocate(32768)
    fory.serialize(first, writer)
    fory.serialize(second, writer)
    stream_data = writer.get_bytes(0, writer.get_writer_index())
    reader = Buffer.from_stream(io.BytesIO(stream_data), 4096)

    first_result = fory.deserialize(reader)
    assert first_result.idx == first.idx
    assert bytes(first_result.payload.raw()) == first_payload

    second_result = fory.deserialize(reader)
    assert second_result.idx == second.idx
    assert bytes(second_result.payload.raw()) == second_payload

    # Ensure previously returned zero-copy-like payloads remain stable even
    # after later stream reads trigger shrink logic.
    assert bytes(first_result.payload.raw()) == first_payload


@pytest.mark.parametrize("xlang", [False, True])
def test_stream_deserialize_truncated_error(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    data = fory.serialize({"k": "value", "numbers": [1, 2, 3, 4]})
    truncated = data[:-1]

    with pytest.raises(Exception):
        fory.deserialize(Buffer.from_stream(OneByteStream(truncated)))


@pytest.mark.parametrize("xlang", [False, True])
def test_dump_matches_dumps_bytes(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    value = {
        "k": [1, 2, 3, 4],
        "nested": {"x": True, "y": "hello"},
        "f": 3.14,
    }

    sink = OneByteWriteStream()
    fory.dump(value, sink)
    expected = fory.dumps(value)
    assert sink.to_bytes() == expected


@pytest.mark.parametrize("xlang", [False, True])
def test_dump_map_chunk_path_matches_dumps(xlang):
    fory = pyfory.Fory(xlang=xlang, ref=True)
    value = {f"k{i}": i for i in range(300)}

    sink = OneByteWriteStream()
    fory.dump(value, sink)
    expected = fory.dumps(value)
    assert sink.to_bytes() == expected

    restored = fory.deserialize(Buffer.from_stream(OneByteStream(sink.to_bytes())))
    assert restored == value


def test_dump_large_list_of_structs_multiple_flushes_matches_dumps():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(StreamStructValue)
    value = [StreamStructValue(i, f"item-{i}-{'x' * 56}", [i, i + 1, i + 2, i + 3, i + 4]) for i in range(1800)]

    sink = CountingWriteStream()
    fory.dump(value, sink)
    expected = fory.dumps(value)
    assert sink.to_bytes() == expected
    assert len(expected) > 4096 * 4
    assert sink.write_calls >= 4

    restored = fory.deserialize(Buffer.from_stream(OneByteStream(sink.to_bytes())))
    assert restored == value


def test_dump_large_map_with_struct_values_matches_dumps():
    fory = pyfory.Fory(xlang=False, ref=True, strict=False)
    fory.register(StreamStructValue)
    value = {f"k{i}": StreamStructValue(i, "y" * 96, [i, i + 1, i + 2, i + 3]) for i in range(900)}

    sink = OneByteWriteStream()
    fory.dump(value, sink)
    expected = fory.dumps(value)
    assert sink.to_bytes() == expected

    restored = fory.deserialize(Buffer.from_stream(OneByteStream(sink.to_bytes())))
    assert restored == value
