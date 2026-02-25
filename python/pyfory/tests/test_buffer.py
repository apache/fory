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

from pyfory.buffer import Buffer
from pyfory.tests.core import require_pyarrow
from pyfory.utils import lazy_import

pa = lazy_import("pyarrow")


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
    print(f"buffer size {buffer.size()}, writer_index {buffer.get_writer_index()}")
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
    buffer = Buffer(binary)
    assert not buffer.own_data()
    buffer.write_bytes(binary)
    assert not buffer.own_data()
    buffer.write_bytes(binary)
    assert buffer.own_data()


def test_write_var_uint64():
    buf = Buffer.allocate(32)
    check_varuint64(buf, -1, 9)
    for i in range(32):
        for j in range(i):
            buf.write_int8(1)
            buf.read_int8()
        check_varuint64(buf, -1, 9)
        check_varuint64(buf, 1, 1)
        check_varuint64(buf, 1 << 6, 1)
        check_varuint64(buf, 1 << 7, 2)
        check_varuint64(buf, -(2**6), 9)
        check_varuint64(buf, -(2**7), 9)
        check_varuint64(buf, 1 << 13, 2)
        check_varuint64(buf, 1 << 14, 3)
        check_varuint64(buf, -(2**13), 9)
        check_varuint64(buf, -(2**14), 9)
        check_varuint64(buf, 1 << 20, 3)
        check_varuint64(buf, 1 << 21, 4)
        check_varuint64(buf, -(2**20), 9)
        check_varuint64(buf, -(2**21), 9)
        check_varuint64(buf, 1 << 27, 4)
        check_varuint64(buf, 1 << 28, 5)
        check_varuint64(buf, -(2**27), 9)
        check_varuint64(buf, -(2**28), 9)
        check_varuint64(buf, 1 << 30, 5)
        check_varuint64(buf, -(2**30), 9)
        check_varuint64(buf, 1 << 31, 5)
        check_varuint64(buf, -(2**31), 9)
        check_varuint64(buf, 1 << 32, 5)
        check_varuint64(buf, -(2**32), 9)
        check_varuint64(buf, 1 << 34, 5)
        check_varuint64(buf, -(2**34), 9)
        check_varuint64(buf, 1 << 35, 6)
        check_varuint64(buf, -(2**35), 9)
        check_varuint64(buf, 1 << 41, 6)
        check_varuint64(buf, -(2**41), 9)
        check_varuint64(buf, 1 << 42, 7)
        check_varuint64(buf, -(2**42), 9)
        check_varuint64(buf, 1 << 48, 7)
        check_varuint64(buf, -(2**48), 9)
        check_varuint64(buf, 1 << 49, 8)
        check_varuint64(buf, -(2**49), 9)
        check_varuint64(buf, 1 << 55, 8)
        check_varuint64(buf, -(2**55), 9)
        check_varuint64(buf, 1 << 56, 9)
        check_varuint64(buf, -(2**56), 9)
        check_varuint64(buf, 1 << 62, 9)
        check_varuint64(buf, -(2**62), 9)
        check_varuint64(buf, 1 << 63 - 1, 9)
        check_varuint64(buf, -(2**63), 9)


def check_varuint64(buf: Buffer, value: int, bytes_written: int):
    reader_index = buf.get_reader_index()
    assert buf.get_writer_index() == buf.get_reader_index()
    actual_bytes_written = buf.write_var_uint64(value)
    assert actual_bytes_written == bytes_written
    varint = buf.read_var_uint64()
    assert buf.get_writer_index() == buf.get_reader_index()
    assert value == varint
    # test slow read branch in `read_varint64`
    assert buf.slice(reader_index, buf.get_reader_index() - reader_index).read_var_uint64() == value


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


def test_buffer_from_stream():
    import io
    from pyfory.buffer import Buffer
    # This specifically tests the fix for the __init__ len() error
    stream = io.BytesIO(b"fury_test_data")
    buf = Buffer(stream)
    assert buf is not None
    # If you've implemented the read logic, you can add:
    # assert buf.to_bytes() == b"fury_test_data"


if __name__ == "__main__":
    test_grow()
