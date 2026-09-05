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

import pytest

import pyfory
from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION, Buffer
from pyfory.tests.core import require_pyarrow
from pyfory.tests.test_stream import OneByteStream
from pyfory.utils import clear_bit, get_bit, lazy_import, set_bit, set_bit_to

pa = lazy_import("pyarrow")


class RecvIntoOnlyStream:
    def __init__(self, data: bytes):
        self._data = data
        self._offset = 0

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


class LegacyRecvIntoOnlyStream:
    def __init__(self, data: bytes):
        self._data = data
        self._offset = 0

    def recvinto(self, buffer, size=-1):
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


class PartialWriteStream:
    def __init__(self):
        self._data = bytearray()

    def write(self, payload):
        if not payload:
            return 0
        view = memoryview(payload).cast("B")
        wrote = min(2, len(view))
        self._data.extend(view[:wrote])
        return wrote

    def to_bytes(self):
        return bytes(self._data)


class RecordingOneByteStream:
    def __init__(self, data: bytes):
        self._data = data
        self._offset = 0
        self.offered_sizes = []

    def readinto(self, buffer):
        view = memoryview(buffer).cast("B")
        self.offered_sizes.append(len(view))
        if self._offset >= len(self._data):
            return 0
        view[0] = self._data[self._offset]
        self._offset += 1
        return 1


def test_buffer():
    buffer = Buffer.allocate(8)
    buffer.write_bool(True)
    buffer.write_int8(-1)
    buffer.write_int8(2**7 - 1)
    buffer.write_int8(-(2**7))
    buffer.write_int16(2**15 - 1)
    buffer.write_int16(-(2**15))
    buffer.write_int32(2**31 - 1)
    buffer.write_int32(-(2**31))
    buffer.write_int64(2**63 - 1)
    buffer.write_int64(-(2**63))
    buffer.write_float(1.0)
    buffer.write_float(-1.0)
    buffer.write_double(1.0)
    buffer.write_double(-1.0)
    buffer.write_bytes(b"")  # write empty buffer
    buffer.write_buffer(b"")  # write empty buffer
    binary = b"b" * 100
    buffer.write_bytes(binary)
    buffer.write_bytes_and_size(binary)
    new_buffer = Buffer(buffer.get_bytes(0, buffer.get_writer_index()))
    assert new_buffer.read_bool() is True
    assert new_buffer.read_int8() == -1
    assert new_buffer.read_int8() == 2**7 - 1
    assert new_buffer.read_int8() == -(2**7)
    assert new_buffer.read_int16() == 2**15 - 1
    assert new_buffer.read_int16() == -(2**15)
    assert new_buffer.read_int32() == 2**31 - 1
    assert new_buffer.read_int32() == -(2**31)
    assert new_buffer.read_int64() == 2**63 - 1
    assert new_buffer.read_int64() == -(2**63)
    assert new_buffer.read_float() == 1.0
    assert new_buffer.read_float() == -1.0
    assert new_buffer.read_double() == 1.0
    assert new_buffer.read_double() == -1.0
    assert new_buffer.read_bytes(0) == b""
    assert new_buffer.read_bytes(0) == b""
    assert new_buffer.read_bytes(len(binary)) == binary
    assert new_buffer.read_bytes_and_size() == binary
    assert new_buffer.hex() == new_buffer.to_pybytes().hex()
    assert new_buffer[:10].to_pybytes() == new_buffer.to_pybytes()[:10]
    assert new_buffer[5:30].to_pybytes() == new_buffer.to_pybytes()[5:30]
    assert new_buffer[-30:].to_pybytes() == new_buffer.to_pybytes()[-30:]
    for i in range(len(new_buffer)):
        assert new_buffer[i] == new_buffer.to_pybytes()[i]
        assert new_buffer[-i + 1] == new_buffer.to_pybytes()[-i + 1]


def test_empty_buffer():
    writable_buffer = Buffer.allocate(8)
    for buffer in [
        Buffer.allocate(0),
        Buffer(b""),
        Buffer.allocate(8).slice(8),
        Buffer(b"1").slice(1),
    ]:
        assert buffer.to_bytes() == b""
        assert buffer.to_pybytes() == b""
        assert buffer.slice().to_bytes() == b""
        assert buffer.hex() == ""
        writable_buffer.put_int32(0, 10)
        writable_buffer.put_buffer(0, buffer, 0, 0)
        writable_buffer.write_buffer(buffer)
        assert writable_buffer.get_int32(0) == 10


def test_to_bytes_rejects_out_of_bounds_range():
    buffer = Buffer(b"abc")
    assert buffer.to_bytes(1) == b"bc"
    assert buffer.to_bytes(1, 2) == b"bc"
    with pytest.raises(ValueError, match="offset 99 out of bound"):
        buffer.to_bytes(99, 1)
    with pytest.raises(ValueError, match="out of bound"):
        buffer.to_bytes(2, 2)


def test_buffer_native_ranges():
    with pytest.raises(Exception):
        Buffer(b"abc", 4)
    with pytest.raises(Exception):
        Buffer(b"abc", 1, -1)
    with pytest.raises(Exception):
        Buffer(memoryview(b"abcd")[::2])
    negative_singleton = memoryview(bytearray(b"x"))[::-1]
    assert negative_singleton.c_contiguous
    assert Buffer(negative_singleton).to_bytes() == b"x"
    with pytest.raises(Exception):
        Buffer.allocate(-1)

    buffer = Buffer.allocate(4)
    with pytest.raises(Exception):
        buffer.set_writer_index(5)
    with pytest.raises(Exception):
        buffer.grow(-1)
    with pytest.raises(Exception):
        buffer.reserve(-1)
    buffer.set_writer_index(1)
    with pytest.raises(Exception):
        buffer.grow(2**31 - 1)
    with pytest.raises(Exception):
        buffer.put_bytes(2**32 - 1, b"x")


def test_buffer_copy_ranges():
    source = array.array("I", [0x01020304, 0x05060708, 0x090A0B0C])
    wrapped = Buffer(source)
    assert wrapped.size() == memoryview(source).nbytes
    assert wrapped.to_bytes() == memoryview(source).cast("B").tobytes()

    target = Buffer.allocate(source.itemsize * 2)
    target.put_buffer(0, source, 1, 2)
    assert target.to_bytes() == memoryview(source).cast("B")[source.itemsize :].tobytes()

    writer = Buffer.allocate(1)
    writer.write_buffer(source, src_index=1, length_=2)
    assert writer.get_writer_index() == source.itemsize * 2
    assert writer.get_bytes(0, writer.get_writer_index()) == target.to_bytes()

    with pytest.raises(Exception):
        target.put_buffer(0, source, -1, 1)
    with pytest.raises(Exception):
        target.put_buffer(0, source, 2, 2)
    with pytest.raises(Exception):
        target.put_buffer(0, memoryview(b"abcd")[::2], 0, 1)
    with pytest.raises(Exception):
        target.put_buffer(1, source, 0, 2)
    with pytest.raises(Exception):
        writer.write_buffer(source, src_index=4)


def test_buffer_export_mutability():
    immutable = Buffer(b"\0" * 16)
    immutable_view = memoryview(immutable)
    assert immutable_view.readonly
    with pytest.raises(TypeError):
        immutable_view[0] = 1
    with pytest.raises(BufferError):
        immutable.reserve(32)
    immutable_view.release()

    immutable.reserve(32)
    immutable.grow(1)
    immutable.ensure(32)
    immutable.put_bytes(0, b"")
    immutable.put_buffer(0, b"", 0, 0)
    immutable.write_bytes(b"")
    immutable.write_buffer(b"")
    immutable.write(b"")
    assert immutable.get_bytes(0, 16) == b"\0" * 16

    mutations = (
        lambda: immutable.put_uint8(0, 1),
        lambda: immutable.put_bytes(0, b"x"),
        lambda: immutable.put_buffer(0, b"x", 0, 1),
        lambda: immutable.write_uint8(1),
        lambda: immutable.write_bytes(b"x"),
        lambda: immutable.write_buffer(b"x"),
        lambda: immutable.write(b"x"),
        lambda: immutable.write_string("x"),
        lambda: set_bit(immutable, 0, 0),
        lambda: clear_bit(immutable, 0, 0),
        lambda: set_bit_to(immutable, 0, 0, True),
    )
    for mutate in mutations:
        with pytest.raises(Exception):
            mutate()

    backing = bytearray(b"abcd")
    writable = Buffer(backing)
    writable_view = memoryview(writable)
    assert not writable_view.readonly
    writable.put_uint8(0, ord("x"))
    writable_view[1] = ord("y")
    assert backing == bytearray(b"xycd")
    writable.set_writer_index(4)
    with pytest.raises(BufferError):
        writable.write_uint8(1)
    assert bytes(writable_view) == b"xycd"
    writable_view.release()
    writable.write_uint8(1)

    with pytest.raises(BufferError):
        memoryview(Buffer.from_stream(OneByteStream(b"x")))


def test_write_context_writable_buffer():
    fory = pyfory.Fory()
    readonly = Buffer(b"\0")
    if ENABLE_FORY_CYTHON_SERIALIZATION:
        with pytest.raises(Exception):
            fory.write_context.prepare(readonly)
        assert fory.write_context.buffer is None
    else:
        fory.write_context.prepare(readonly)
        with pytest.raises(Exception):
            fory.write_context.write_int8(1)
    assert readonly.to_bytes() == b"\0"

    fory.write_context.reset()
    backing = bytearray(b"\0")
    writable = Buffer(backing)
    fory.write_context.prepare(writable)
    fory.write_context.write_int8(1)
    assert backing == bytearray(b"\1")
    fory.write_context.reset()


def test_bulk_pointer_ranges():
    target = Buffer.allocate(1)

    np = pytest.importorskip("numpy")
    huge = np.lib.stride_tricks.as_strided(np.zeros(1, dtype=np.uint8), shape=(2**31,), strides=(1,))
    assert memoryview(huge).nbytes == 2**31
    with pytest.raises(Exception):
        Buffer(huge)
    with pytest.raises(Exception):
        target.write_buffer(huge)
    assert target.get_writer_index() == 0
    with pytest.raises(Exception):
        pyfory.mmh3.hash_buffer(huge)


def test_bit_helper_ranges():
    buffer = Buffer.allocate(1)
    set_bit(buffer, 0, 7)
    assert get_bit(buffer, 0, 7)
    clear_bit(buffer, 0, 7)
    assert not get_bit(buffer, 0, 7)
    set_bit_to(buffer, 0, 0, True)
    assert get_bit(buffer, 0, 0)

    for operation in (get_bit, set_bit, clear_bit):
        with pytest.raises(Exception):
            operation(buffer, 0, 8)
        with pytest.raises(Exception):
            operation(buffer, 1, 0)
    with pytest.raises(Exception):
        set_bit_to(buffer, 2**32 - 1, 2**32 - 1, True)


def test_hash_buffer_empty_input():
    assert pyfory.mmh3.hash_buffer(b"") == (0, 0)


def test_readline_without_newline_does_not_read_out_of_bounds():
    assert Buffer(b"abc").readline() == b"abc"


def test_write_varint32():
    buf = Buffer.allocate(32)
    for i in range(1):
        for j in range(i):
            buf.write_int8(1)
            buf.read_int8()
        check_varuint32(buf, 1, 1)
        check_varuint32(buf, 1 << 6, 1)
        check_varuint32(buf, 1 << 7, 2)
        check_varuint32(buf, 1 << 13, 2)
        check_varuint32(buf, 1 << 14, 3)
        check_varuint32(buf, 1 << 20, 3)
        check_varuint32(buf, 1 << 21, 4)
        check_varuint32(buf, 1 << 27, 4)
        check_varuint32(buf, 1 << 28, 5)
        check_varuint32(buf, 1 << 30, 5)

        check_varint32(buf, -1)
        check_varint32(buf, -1 << 6)
        check_varint32(buf, -1 << 7)
        check_varint32(buf, -1 << 13)
        check_varint32(buf, -1 << 14)
        check_varint32(buf, -1 << 20)
        check_varint32(buf, -1 << 21)
        check_varint32(buf, -1 << 27)
        check_varint32(buf, -1 << 28)
        check_varint32(buf, -1 << 30)


def check_varuint32(buf: Buffer, value: int, bytes_written: int):
    assert buf.get_writer_index() == buf.get_reader_index()
    actual_bytes_written = buf.write_var_uint32(value)
    assert actual_bytes_written == bytes_written
    varint = buf.read_var_uint32()
    assert buf.get_writer_index() == buf.get_reader_index()
    assert value == varint


def check_varint32(buf: Buffer, value: int):
    assert buf.get_writer_index() == buf.get_reader_index()
    buf.write_varint32(value)
    varint = buf.read_varint32()
    assert buf.get_writer_index() == buf.get_reader_index()
    assert value == varint


@require_pyarrow
def test_buffer_protocol():
    # test buffer protocol compatibility with pyarrow
    buffer = Buffer.allocate(32)
    binary = b"b" * 100
    buffer.write_bytes_and_size(binary)
    assert bytes(buffer) == bytes(pa.py_buffer(buffer))
    assert buffer.to_bytes() == bytes(pa.py_buffer(buffer))


def test_grow():
    binary = b"a" * 10
    buffer = Buffer(bytearray(binary))
    assert not buffer.own_data()
    buffer.write_bytes(binary)
    assert not buffer.own_data()
    buffer.write_bytes(binary)
    assert buffer.own_data()


def test_write_var_uint64():
    buf = Buffer.allocate(32)
    cases = (
        (1, 1),
        (1 << 6, 1),
        (1 << 7, 2),
        (1 << 13, 2),
        (1 << 14, 3),
        (1 << 20, 3),
        (1 << 21, 4),
        (1 << 27, 4),
        (1 << 28, 5),
        (1 << 35, 6),
        (1 << 42, 7),
        (1 << 49, 8),
        (1 << 56, 9),
        ((1 << 63) - 1, 9),
        (1 << 63, 9),
        ((1 << 64) - 1, 9),
    )
    for i in range(32):
        for j in range(i):
            buf.write_int8(1)
            buf.read_int8()
        for value, bytes_written in cases:
            check_varuint64(buf, value, bytes_written)
    with pytest.raises(Exception):
        buf.write_var_uint64(-1)


def test_int24_declared_range():
    buffer = Buffer.allocate(16)
    values = (-(1 << 23), -32769, 32768, (1 << 23) - 1)
    for value in values:
        buffer.write_int24(value)
    for value in values:
        assert buffer.read_int24() == value
    for index, value in enumerate(values):
        buffer.put_int24(index * 3, value)
        assert buffer.get_int24(index * 3) == value


@pyfory.dataclass
class UInt64Value:
    value: pyfory.UInt64 = 0


def test_var_uint64_fory_round_trip():
    fory = pyfory.Fory(xlang=True, compatible=False, ref=False)
    fory.register_type(UInt64Value)
    value = UInt64Value((1 << 64) - 1)
    assert fory.deserialize(fory.serialize(value)) == value
    with pytest.raises(Exception):
        fory.serialize(UInt64Value(-1))


def check_varuint64(buf: Buffer, value: int, bytes_written: int):
    assert buf.get_writer_index() == buf.get_reader_index()
    actual_bytes_written = buf.write_var_uint64(value)
    assert actual_bytes_written == bytes_written
    varint = buf.read_var_uint64()
    assert buf.get_writer_index() == buf.get_reader_index()
    assert value == varint


def test_buffer_flush_stream():
    stream = PartialWriteStream()
    buffer = Buffer.allocate(16)
    output_stream = Buffer.wrap_output_stream(stream)
    buffer.bind_output_stream(output_stream)
    payload = b"stream-flush-buffer"
    buffer.write_bytes(payload)
    output_stream.force_flush()
    assert stream.to_bytes() == payload
    assert buffer.get_writer_index() == 0


def test_wrap_output_stream_invalid_target_raises():
    with pytest.raises(ValueError):
        Buffer.wrap_output_stream(object())


def test_output_stream_try_flush_preserves_bound_buffer_when_barrier_active():
    stream = PartialWriteStream()
    output_stream = Buffer.wrap_output_stream(stream)
    buffer = Buffer.allocate(32)
    buffer.bind_output_stream(output_stream)
    payload = b"x" * 5000

    output_stream.enter_flush_barrier()
    buffer.write_bytes(payload)
    output_stream.try_flush()
    output_stream.try_flush()
    assert buffer.get_writer_index() == len(payload)
    assert stream.to_bytes() == b""

    output_stream.exit_flush_barrier()
    output_stream.try_flush()
    assert buffer.get_writer_index() == 0

    output_stream.force_flush()
    assert stream.to_bytes() == payload


def test_output_stream_try_flush_small_payload_needs_force_flush():
    stream = PartialWriteStream()
    output_stream = Buffer.wrap_output_stream(stream)
    buffer = Buffer.allocate(32)
    buffer.bind_output_stream(output_stream)
    payload = b"small-payload"
    buffer.write_bytes(payload)

    output_stream.try_flush()
    assert buffer.get_writer_index() == len(payload)
    assert stream.to_bytes() == b""

    output_stream.force_flush()
    assert buffer.get_writer_index() == 0
    assert stream.to_bytes() == payload


def test_write_buffer():
    buf = Buffer.allocate(32)
    buf.write(b"")
    buf.write(b"123")
    buf.write(Buffer.allocate(32))
    assert buf.get_writer_index() == 35
    assert buf.read(0) == b""
    assert buf.read(3) == b"123"


def test_read_bytes_as_int64():
    # test small buffer whose length < 8
    buf = Buffer(b"1234")
    assert buf.read_bytes_as_int64(0) == 0
    assert buf.read_bytes_as_int64(1) == 49

    # test big buffer whose length > 8
    buf = Buffer(b"12345678901234")
    assert buf.read_bytes_as_int64(0) == 0
    assert buf.read_bytes_as_int64(1) == 49
    assert buf.read_bytes_as_int64(8) == 4123106164818064178

    # test fix for `OverflowError: Python int too large to convert to C long`
    buf = Buffer(b"\xa6IOr\x9ch)\x80\x12\x02")
    buf.read_bytes_as_int64(8)


def test_stream_buffer_read():
    writer = Buffer.allocate(32)
    writer.write_uint32(0x01020304)
    writer.write_int64(-1234567890)
    writer.write_var_uint32(300)
    writer.write_varint64(-4567890123)
    writer.write_tagged_uint64(0x123456789)
    writer.write_var_uint64(0x1FFFF)
    writer.write_bytes_and_size(b"stream-data")
    writer.write_string("hello-stream")

    data = writer.get_bytes(0, writer.get_writer_index())
    stream = OneByteStream(data)
    reader = Buffer.from_stream(stream)

    assert reader.read_uint32() == 0x01020304
    assert reader.read_int64() == -1234567890
    assert reader.read_var_uint32() == 300
    assert reader.read_varint64() == -4567890123
    assert reader.read_tagged_uint64() == 0x123456789
    assert reader.read_var_uint64() == 0x1FFFF
    assert reader.read_bytes_and_size() == b"stream-data"
    assert reader.read_string() == "hello-stream"


def test_stream_buffer_read_with_recv_into():
    reader = Buffer.from_stream(RecvIntoOnlyStream(bytes([0x11, 0x22, 0x33, 0x44])))
    assert reader.read_uint32() == 0x44332211


def test_stream_buffer_read_with_legacy_recvinto():
    reader = Buffer.from_stream(LegacyRecvIntoOnlyStream(bytes([0x11, 0x22, 0x33, 0x44])))
    assert reader.read_uint32() == 0x44332211


def test_stream_buffer_geometric_growth():
    stream = RecordingOneByteStream(bytes(range(32)))
    reader = Buffer.from_stream(stream, buffer_size=1)

    assert [reader.read_uint8() for _ in range(32)] == list(range(32))
    assert max(stream.offered_sizes) >= 8


def test_stream_buffer_set_reader_index():
    reader = Buffer.from_stream(OneByteStream(bytes([0x11, 0x22, 0x33, 0x44, 0x55])))
    reader.set_reader_index(4)
    assert reader.read_uint8() == 0x55


def test_stream_buffer_set_reader_index_out_of_bound():
    reader = Buffer.from_stream(OneByteStream(b"\x11\x22\x33"))
    with pytest.raises(Exception, match="Buffer out of bound"):
        reader.set_reader_index(10)


def test_stream_buffer_read_bytes_and_skip_update_reader_index():
    reader = Buffer.from_stream(OneByteStream(bytes(range(20))), buffer_size=2)
    assert reader.read_bytes(5) == bytes([0, 1, 2, 3, 4])
    assert reader.get_reader_index() == 5
    reader.skip(5)
    assert reader.get_reader_index() == 10


def test_stream_buffer_short_read_error():
    reader = Buffer.from_stream(OneByteStream(b"\x01\x02\x03"))
    with pytest.raises(Exception, match="Buffer out of bound"):
        reader.read_uint32()
