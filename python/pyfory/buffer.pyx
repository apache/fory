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

# distutils: language = c++
# cython: embedsignature = True
# cython: language_level = 3
# cython: annotate = True

cimport cython
from cpython cimport *
from cpython.unicode cimport *
from cpython.bytes cimport PyBytes_AsString, PyBytes_FromStringAndSize, PyBytes_AS_STRING
from libcpp.memory cimport shared_ptr
from libcpp.utility cimport move
from cython.operator cimport dereference as deref
from libcpp.string cimport string as c_string
from libc.stdint cimport *
from libcpp cimport bool as c_bool
from pyfory.includes.libutil cimport(
    CBuffer, allocate_buffer, get_bit as c_get_bit, set_bit as c_set_bit, clear_bit as c_clear_bit,
    set_bit_to as c_set_bit_to, CError, CErrorCode, CResultVoidError, utf16_has_surrogate_pairs
)
from pyfory.includes.libpyfory cimport Fory_PyCreateBufferFromStream
import os
from pyfory.error import raise_fory_error

cdef int32_t max_buffer_size = 2 ** 31 - 1
cdef int UTF16_LE = -1

cdef c_bool _WINDOWS = os.name == 'nt'


@cython.final
cdef class _SharedBufferOwner:
    cdef shared_ptr[CBuffer] buffer


@cython.final
cdef class Buffer:
    def __init__(self,  data not None, int32_t offset=0, length=None):
        self.data = data
        cdef int32_t buffer_len = len(data)
        cdef int length_
        if length is None:
            length_ = buffer_len - offset
        else:
            length_ = length
        if offset < 0 or offset + length_ > buffer_len:
            raise ValueError(f'Wrong offset {offset} or length {length} for buffer with size {buffer_len}')
        cdef uint8_t* address
        if length_ > 0:
            address = get_address(data) + offset
        else:
            address = NULL
        self.c_buffer = CBuffer(address, length_, False)
        self.c_buffer.reader_index(0)
        self.c_buffer.writer_index(0)

    @classmethod
    def from_stream(cls, stream not None, uint32_t buffer_size=4096):
        cdef CBuffer* stream_buffer
        cdef c_string stream_error
        if Fory_PyCreateBufferFromStream(
            <PyObject*>stream, buffer_size, &stream_buffer, &stream_error
        ) != 0:
            raise ValueError(stream_error.decode("UTF-8"))
        if stream_buffer == NULL:
            raise ValueError("failed to create stream buffer")
        cdef Buffer buffer = Buffer.__new__(Buffer)
        buffer.c_buffer = move(deref(stream_buffer))
        del stream_buffer
        buffer.data = stream
        buffer.c_buffer.reader_index(0)
        buffer.c_buffer.writer_index(0)
        return buffer

    @staticmethod
    cdef Buffer wrap(shared_ptr[CBuffer] c_buffer):
        cdef Buffer buffer = Buffer.__new__(Buffer)
        cdef CBuffer* ptr = c_buffer.get()
        buffer.c_buffer = CBuffer(ptr.data(), ptr.size(), False)
        cdef _SharedBufferOwner owner = _SharedBufferOwner.__new__(_SharedBufferOwner)
        owner.buffer = c_buffer
        buffer.data = owner
        buffer.c_buffer.reader_index(0)
        buffer.c_buffer.writer_index(0)
        return buffer

    @classmethod
    def allocate(cls, int32_t size):
        cdef CBuffer* buf = allocate_buffer(size)
        if buf == NULL:
            raise MemoryError("out of memory")
        cdef Buffer buffer = Buffer.__new__(Buffer)
        buffer.c_buffer = move(deref(buf))
        del buf
        buffer.data = None
        buffer.c_buffer.reader_index(0)
        buffer.c_buffer.writer_index(0)
        return buffer

    cdef inline void _raise_if_error(self):
        cdef CErrorCode code
        cdef c_string message
        if not self._error.ok():
            code = self._error.code()
            message = self._error.message()
            self._error.reset()
            raise_fory_error(code, message)

    cpdef inline int32_t get_reader_index(self):
        return <int32_t>self.c_buffer.reader_index()

    cpdef inline void set_reader_index(self, int32_t value):
        if value < 0:
            raise ValueError("reader_index must be >= 0")
        if not self.c_buffer.reader_index(<uint32_t>value, self._error):
            self._raise_if_error()

    cpdef inline int32_t get_writer_index(self):
        return <int32_t>self.c_buffer.writer_index()

    cpdef inline void set_writer_index(self, int32_t value):
        if value < 0:
            raise ValueError("writer_index must be >= 0")
        self.c_buffer.writer_index(<uint32_t>value)

    cpdef c_bool own_data(self):
        return self.c_buffer.own_data()

    cpdef inline reserve(self, int32_t new_size):
        assert 0 < new_size < max_buffer_size
        self.c_buffer.reserve(new_size)

    cpdef inline put_bool(self, uint32_t offset, c_bool v):
        self.check_bound(offset, <int32_t>1)
        self.c_buffer.unsafe_put_byte(offset, v)

    cpdef inline put_uint8(self, uint32_t offset, uint8_t v):
        self.check_bound(offset, <int32_t>1)
        self.c_buffer.unsafe_put_byte(offset, v)

    cpdef inline put_int8(self, uint32_t offset, int8_t v):
        self.check_bound(offset, <int32_t>1)
        self.c_buffer.unsafe_put_byte(offset, v)

    cpdef inline put_int16(self, uint32_t offset, int16_t v):
        self.check_bound(offset, <int32_t>2)
        self.c_buffer.unsafe_put(offset, v)

    cpdef inline put_int24(self, uint32_t offset, int32_t v):
        self.check_bound(offset, <int32_t>3)
        self.c_buffer.put_int24(offset, v)

    cpdef inline put_int32(self, uint32_t offset, int32_t v):
        self.check_bound(offset, <int32_t>4)
        self.c_buffer.unsafe_put(offset, v)

    cpdef inline put_int64(self, uint32_t offset, int64_t v):
        self.check_bound(offset, <int32_t>8)
        self.c_buffer.unsafe_put(offset, v)

    cpdef inline put_float(self, uint32_t offset, float v):
        self.check_bound(offset, <int32_t>4)
        self.c_buffer.unsafe_put(offset, v)

    cpdef inline put_double(self, uint32_t offset, double v):
        self.check_bound(offset, <int32_t>8)
        self.c_buffer.unsafe_put(offset, v)

    cpdef inline c_bool get_bool(self, uint32_t offset):
        self.check_bound(offset, <int32_t>1)
        return self.c_buffer.get_bool(offset)

    cpdef inline int8_t get_int8(self, uint32_t offset):
        self.check_bound(offset, <int32_t>1)
        return self.c_buffer.get_int8(offset)

    cpdef inline int16_t get_int16(self, uint32_t offset):
        self.check_bound(offset, <int32_t>2)
        return self.c_buffer.get_int16(offset)

    cpdef inline int32_t get_int24(self, uint32_t offset):
        self.check_bound(offset, <int32_t>3)
        return self.c_buffer.get_int24(offset)

    cpdef inline int32_t get_int32(self, uint32_t offset):
        self.check_bound(offset, <int32_t>4)
        return self.c_buffer.get_int32(offset)

    cpdef inline int64_t get_int64(self, uint32_t offset):
        self.check_bound(offset, <int32_t>8)
        return self.c_buffer.get_int64(offset)

    cpdef inline float get_float(self, uint32_t offset):
        self.check_bound(offset, <int32_t>4)
        return self.c_buffer.get_float(offset)

    cpdef inline double get_double(self, uint32_t offset):
        self.check_bound(offset, <int32_t>8)
        return self.c_buffer.get_double(offset)

    cpdef inline check_bound(self, int32_t offset, int32_t length):
        cdef int32_t size_ = self.c_buffer.size()
        if offset | length | (offset + length) | (size_- (offset + length)) < 0:
            raise_fory_error(
                CErrorCode.BufferOutOfBound,
                f"Address range {offset, offset + length} out of bound {0, size_}",
            )

    cpdef inline write_bool(self, c_bool value):
        self.c_buffer.write_uint8(<uint8_t>value)
    
    cpdef inline write_uint8(self, uint8_t value):
        self.c_buffer.write_uint8(value)

    cpdef inline write_int8(self, int8_t value):
        self.c_buffer.write_int8(value)

    cpdef inline write_int16(self, int16_t value):
        self.c_buffer.write_int16(value)

    cpdef inline write_int24(self, int32_t value):
        self.c_buffer.write_int24(value)

    cpdef inline write_int32(self, int32_t value):
        self.c_buffer.write_int32(value)

    cpdef inline write_int64(self, int64_t value):
        self.c_buffer.write_int64(value)

    cpdef inline write_uint16(self, uint16_t value):
        self.c_buffer.write_uint16(value)

    cpdef inline write_uint32(self, uint32_t value):
        self.c_buffer.write_uint32(value)

    cpdef inline write_uint64(self, uint64_t value):
        self.c_buffer.write_int64(<int64_t>value)

    cpdef inline write_float(self, float value):
        self.c_buffer.write_float(value)

    cpdef inline write_float32(self, float value):
        self.c_buffer.write_float(value)

    cpdef inline write_double(self, double value):
        self.c_buffer.write_double(value)

    cpdef inline write_float64(self, double value):
        self.c_buffer.write_double(value)

    cpdef put_buffer(self, uint32_t offset, v, int32_t src_index, int32_t length):
        if length == 0:  # access an emtpy buffer may raise out-of-bound exception.
            return
        view = memoryview(v)
        assert view.c_contiguous
        itemsize = view.itemsize
        size = (length - src_index) * itemsize
        self.check_bound(offset, size)
        src_offset = src_index * itemsize
        cdef uint8_t* ptr = get_address(v)
        self.c_buffer.copy_from(offset, ptr, src_offset, size)

    cpdef inline write_bytes_and_size(self, bytes value):
        cdef const unsigned char[:] data = value
        cdef int32_t length = data.nbytes
        self.write_var_uint32(length)
        if length > 0:
            self.c_buffer.write_bytes(&data[0], length)

    cpdef inline bytes read_bytes_and_size(self):
        cdef int32_t length = self.read_var_uint32()
        return self.read_bytes(length)

    cpdef inline write_bytes(self, bytes value):
        cdef const unsigned char[:] data = value
        cdef int32_t length = data.nbytes
        if length > 0:
            self.c_buffer.write_bytes(&data[0], length)

    cpdef inline bytes read_bytes(self, int32_t length):
        if length == 0:
            return b""
        cdef bytes py_bytes = PyBytes_FromStringAndSize(NULL, length)
        if py_bytes is None:
            raise MemoryError("out of memory")
        cdef char* buf = PyBytes_AS_STRING(py_bytes)
        self.c_buffer.read_bytes(buf, length, self._error)
        if not self._error.ok():
            self._raise_if_error()
        return py_bytes

    cpdef inline int64_t read_bytes_as_int64(self, int32_t length):
        cdef int64_t result = 0
        cdef uint32_t offset
        cdef CResultVoidError res
        if length == 0:
            return 0
        if length < 0 or length > 8:
            raise_fory_error(
                CErrorCode.InvalidData,
                f"get_bytes_as_int64 length should be in range [0, 8], but got {length}",
            )
        if not self.c_buffer.ensure_readable(<uint32_t>length, self._error):
            self._raise_if_error()
        offset = self.c_buffer.reader_index()
        res = self.c_buffer.get_bytes_as_int64(offset, <uint32_t>length, &result)
        if not res.ok():
            raise_fory_error(res.error().code(), res.error().message())
        self.c_buffer.increase_reader_index(<uint32_t>length, self._error)
        self._raise_if_error()
        return result

    cpdef inline put_bytes(self, uint32_t offset, bytes value):
        cdef const unsigned char[:] data = value
        cdef int32_t length = data.nbytes
        if length > 0:
            self.grow(length)
            self.c_buffer.copy_from(offset, &data[0], 0, length)

    cpdef inline bytes get_bytes(self, uint32_t offset, uint32_t nbytes):
        if nbytes == 0:
            return b""
        self.check_bound(offset, nbytes)
        cdef unsigned char* binary_data = self.c_buffer.data() + offset
        return binary_data[:nbytes]

    cpdef inline write_buffer(self, value, src_index=0, length_=None):
        view = memoryview(value)
        dtype = view.format
        cdef int32_t itemsize = view.itemsize
        cdef int32_t length = 0
        if length_ is None:
            length = len(value) - src_index
        else:
            length = length_
        if length <= 0:
            return
        cdef uint32_t offset = self.c_buffer.writer_index()
        self.c_buffer.grow(length * itemsize)
        self.put_buffer(offset, value, src_index, length)
        self.c_buffer.increase_writer_index(length * itemsize)

    cpdef inline write(self, value):
        cdef const unsigned char[:] data = value
        cdef int32_t length = data.nbytes
        if length > 0:
            self.c_buffer.write_bytes(&data[0], length)

    cpdef inline grow(self, int32_t needed_size):
        self.c_buffer.grow(needed_size)

    cpdef inline ensure(self, int32_t length):
        if length > self.c_buffer.size():
            self.reserve(length * 2)

    cpdef inline skip(self, int32_t length):
        self.c_buffer.skip(length, self._error)
        self._raise_if_error()

    cpdef inline c_bool read_bool(self):
        cdef uint8_t value = self.c_buffer.read_uint8(self._error)
        self._raise_if_error()
        return value != 0

    cpdef inline uint8_t read_uint8(self):
        cdef uint8_t value = self.c_buffer.read_uint8(self._error)
        self._raise_if_error()
        return value

    cpdef inline int8_t read_int8(self):
        cdef int8_t value = self.c_buffer.read_int8(self._error)
        self._raise_if_error()
        return value

    cpdef inline int16_t read_int16(self):
        cdef int16_t value = self.c_buffer.read_int16(self._error)
        self._raise_if_error()
        return value

    cpdef inline int16_t read_int24(self):
        cdef int32_t value = self.c_buffer.read_int24(self._error)
        self._raise_if_error()
        return value

    cpdef inline int32_t read_int32(self):
        cdef int32_t value = self.c_buffer.read_int32(self._error)
        self._raise_if_error()
        return value

    cpdef inline int64_t read_int64(self):
        cdef int64_t value = self.c_buffer.read_int64(self._error)
        self._raise_if_error()
        return value

    cpdef inline uint16_t read_uint16(self):
        cdef uint16_t value = self.c_buffer.read_uint16(self._error)
        self._raise_if_error()
        return value

    cpdef inline uint32_t read_uint32(self):
        cdef uint32_t value = self.c_buffer.read_uint32(self._error)
        self._raise_if_error()
        return value

    cpdef inline uint64_t read_uint64(self):
        cdef uint64_t value = self.c_buffer.read_uint64(self._error)
        self._raise_if_error()
        return value

    cpdef inline float read_float(self):
        cdef float value = self.c_buffer.read_float(self._error)
        self._raise_if_error()
        return value

    cpdef inline float read_float32(self):
        cdef float value = self.c_buffer.read_float(self._error)
        self._raise_if_error()
        return value

    cpdef inline double read_double(self):
        cdef double value = self.c_buffer.read_double(self._error)
        self._raise_if_error()
        return value

    cpdef inline double read_float64(self):
        cdef double value = self.c_buffer.read_double(self._error)
        self._raise_if_error()
        return value

    cpdef inline bytes read(self, int32_t length):
        return self.read_bytes(length)

    cpdef inline bytes readline(self, int32_t size=-1):
        if size != <int32_t>-1:
            raise ValueError(f"Specify size {size} is unsupported")
        cdef uint8_t* arr = self.c_buffer.data()
        cdef uint32_t start_index = self.c_buffer.reader_index()
        cdef uint32_t target_index = start_index
        cdef uint8_t sep = 10  # '\n'
        cdef int32_t buffer_size = self.c_buffer.size()
        while arr[target_index] != sep and target_index < buffer_size:
            target_index += <int32_t>1
        cdef bytes data = arr[start_index:target_index]
        self.c_buffer.reader_index(target_index)
        return data

    cpdef inline write_varint32(self, int32_t value):
        cdef uint32_t before = self.c_buffer.writer_index()
        self.c_buffer.write_var_int32(value)
        cdef uint32_t after = self.c_buffer.writer_index()
        return after - before

    cpdef inline write_var_uint32(self, uint32_t value):
        cdef uint32_t before = self.c_buffer.writer_index()
        self.c_buffer.write_var_uint32(value)
        cdef uint32_t after = self.c_buffer.writer_index()
        return after - before

    cpdef inline int32_t read_varint32(self):
        cdef int32_t value = self.c_buffer.read_var_int32(self._error)
        self._raise_if_error()
        return value

    cpdef inline uint32_t read_var_uint32(self):
        cdef uint32_t value = self.c_buffer.read_var_uint32(self._error)
        self._raise_if_error()
        return value

    cpdef inline write_varint64(self, int64_t value):
        cdef uint32_t before = self.c_buffer.writer_index()
        self.c_buffer.write_var_int64(value)
        cdef uint32_t after = self.c_buffer.writer_index()
        return after - before

    cpdef inline write_var_uint64(self, int64_t v):
        cdef uint32_t before = self.c_buffer.writer_index()
        self.c_buffer.write_var_uint64(<uint64_t>v)
        cdef uint32_t after = self.c_buffer.writer_index()
        return after - before

    cpdef inline int64_t read_varint64(self):
        cdef int64_t value = self.c_buffer.read_var_int64(self._error)
        self._raise_if_error()
        return value

    cpdef inline int64_t read_var_uint64(self):
        cdef uint64_t value = self.c_buffer.read_var_uint64(self._error)
        self._raise_if_error()
        return <int64_t>value

    cpdef inline write_tagged_int64(self, int64_t value):
        """write signed int64 using fory Tagged(Small long as int) encoding."""
        self.c_buffer.write_tagged_int64(value)

    cpdef inline int64_t read_tagged_int64(self):
        """Read signed fory Tagged(Small long as int) encoded int64."""
        cdef int64_t value = self.c_buffer.read_tagged_int64(self._error)
        self._raise_if_error()
        return value

    cpdef inline write_tagged_uint64(self, uint64_t value):
        """write unsigned uint64 using fory Tagged(Small long as int) encoding."""
        self.c_buffer.write_tagged_uint64(value)

    cpdef inline uint64_t read_tagged_uint64(self):
        """Read unsigned fory Tagged(Small long as int) encoded uint64."""
        cdef uint64_t value = self.c_buffer.read_tagged_uint64(self._error)
        self._raise_if_error()
        return value

    cdef inline write_c_buffer(self, const uint8_t* value, int32_t length):
        self.write_var_uint32(length)
        if length <= 0:  # access an emtpy buffer may raise out-of-bound exception.
            return
        cdef uint32_t offset = self.c_buffer.writer_index()
        self.c_buffer.grow(length)
        self.check_bound(offset, length)
        self.c_buffer.copy_from(offset, value, 0, length)
        self.c_buffer.increase_writer_index(length)

    cpdef inline write_string(self, str value):
        cdef Py_ssize_t length = PyUnicode_GET_LENGTH(value)
        cdef int32_t kind = PyUnicode_KIND(value)
        # Note: buffer will be native endian for PyUnicode_2BYTE_KIND
        cdef void* buffer = PyUnicode_DATA(value)
        cdef uint64_t header = 0
        cdef int32_t buffer_size
        if kind == PyUnicode_1BYTE_KIND:
            buffer_size = length
            header = (length << 2) | 0
        elif kind == PyUnicode_2BYTE_KIND:
            buffer_size = length << 1
            header = (length << 3) | 1
        else:
            buffer = <void *>(PyUnicode_AsUTF8AndSize(value, &length))
            buffer_size = length
            header = (buffer_size << 2) | 2
        self.write_var_uint64(header)
        if buffer_size == 0:  # access an emtpy buffer may raise out-of-bound exception.
            return
        cdef uint32_t offset = self.c_buffer.writer_index()
        self.c_buffer.grow(buffer_size)
        self.check_bound(offset, buffer_size)
        self.c_buffer.copy_from(offset, <const uint8_t *>buffer, 0, buffer_size)
        self.c_buffer.increase_writer_index(buffer_size)

    cpdef inline str read_string(self, int32_t max_string_bytes_length=-1):
        cdef uint64_t header = self.read_var_uint64()
        cdef uint32_t size = header >> 2
        if max_string_bytes_length >= 0 and size > <uint32_t>max_string_bytes_length:
            raise ValueError(f"String size {size} exceeds the configured limit of {max_string_bytes_length}")
        cdef uint32_t encoding = header & <uint32_t>0b11
        if size == 0:
            return ""
        cdef uint32_t offset = self.c_buffer.reader_index()
        cdef uint32_t available = self.c_buffer.size() - offset
        cdef const char * buf
        cdef bytes py_bytes
        if available >= size:
            buf = <const char *>(self.c_buffer.data() + offset)
            self.c_buffer.reader_index(offset + size)
        else:
            py_bytes = self.read_bytes(<int32_t>size)
            buf = <const char *>PyBytes_AS_STRING(py_bytes)
        if encoding == 0:
            # PyUnicode_FromASCII
            return PyUnicode_DecodeLatin1(buf, size, "strict")
        elif encoding == 1:
            if utf16_has_surrogate_pairs(<const uint16_t *>buf, size >> 1):
                return PyUnicode_DecodeUTF16(
                    buf,
                    size,  # len of string in bytes
                    NULL,  # special error handling options, we don't need any
                    &UTF16_LE,  # fory use little-endian
                )
            else:
                return PyUnicode_FromKindAndData(PyUnicode_2BYTE_KIND, buf, size >> 1)
        else:
            return PyUnicode_DecodeUTF8(buf, size, "strict")

    def __len__(self):
        return self.c_buffer.size()

    cpdef inline int32_t size(self):
        return self.c_buffer.size()

    def to_bytes(self, int32_t offset=0, int32_t length=0) -> bytes:
        if length != 0:
            assert 0 < length <= self.c_buffer.size(),\
                f"length {length} size {self.c_buffer.size()}"
        else:
            length = self.c_buffer.size()
        cdef:
            uint8_t* data = self.c_buffer.data() + offset
        return data[:length]

    def to_pybytes(self) -> bytes:
        return self.to_bytes()

    def slice(self, offset=0, length=None):
        return type(self)(self, offset, length)

    def __getitem__(self, key):
        if isinstance(key, slice):
            if (key.step or 1) != 1:
                raise IndexError('only slices with step 1 supported')
            return _normalize_slice(self, key)
        return self.getitem(_normalize_index(key, self.c_buffer.size()))

    cdef getitem(self, int64_t i):
        return self.c_buffer.data()[i]

    def hex(self):
        """
        Compute hexadecimal representation of the buffer.

        Returns
        -------
        : bytes
        """
        return self.c_buffer.hex().decode("UTF-8")

    def __getbuffer__(self, Py_buffer *buffer, int flags):
        cdef Py_ssize_t itemsize = 1
        self.shape[0] = self.c_buffer.size()
        self.stride[0] = itemsize
        buffer.buf = <char *>(self.c_buffer.data())
        buffer.format = 'B'
        buffer.internal = NULL                  # see References
        buffer.itemsize = itemsize
        buffer.len = self.c_buffer.size()  # product(shape) * itemsize
        buffer.ndim = 1
        buffer.obj = self
        buffer.readonly = 0
        buffer.shape = self.shape
        buffer.strides = self.stride
        buffer.suboffsets = NULL                # for pointer arrays only

    def __releasebuffer__(self, Py_buffer *buffer):
        pass

    def __repr__(self):
        return "Buffer(reader_index={}, writer_index={}, size={})".format(
            self.get_reader_index(), self.get_writer_index(), self.size()
        )


cdef inline uint8_t* get_address(v):
    if type(v) is bytes:
        return <uint8_t*>(PyBytes_AsString(v))
    view = memoryview(v)
    cdef str dtype = view.format
    # Handle little-endian format codes (e.g., "<h", "<i", etc.)
    # Strip the endian prefix since we only care about the type
    if len(dtype) > 1 and dtype[0] in ('<', '>', '=', '@', '!'):
        dtype = dtype[1:]
    cdef:
        const char[:] signed_char_data
        const unsigned char[:] unsigned_data
        const int16_t[:] signed_short_data
        const int32_t[:] signed_int_data
        const int64_t[:] signed_long_data
        const uint16_t[:] unsigned_short_data
        const uint32_t[:] unsigned_int_data
        const uint64_t[:] unsigned_long_data
        const uint64_t[:] unsigned_long_long_data
        const float[:] signed_float_data
        const double[:] signed_double_data
        uint8_t* ptr
    if dtype == "b":
        signed_char_data = v
        ptr = <uint8_t*>(&signed_char_data[0])
    elif dtype == "B":
        unsigned_data = v
        ptr = <uint8_t*>(&unsigned_data[0])
    elif dtype == "h":
        signed_short_data = v
        ptr = <uint8_t*>(&signed_short_data[0])
    elif dtype == "H":
        unsigned_short_data = v
        ptr = <uint8_t*>(&unsigned_short_data[0])
    elif dtype == "i":
        signed_int_data = v
        ptr = <uint8_t*>(&signed_int_data[0])
    elif dtype == "I":
        unsigned_int_data = v
        ptr = <uint8_t*>(&unsigned_int_data[0])
    elif dtype == "l":
        if _WINDOWS:
            signed_int_data = v
            ptr = <uint8_t*>(&signed_int_data[0])
        else:
            signed_long_data = v
            ptr = <uint8_t*>(&signed_long_data[0])
    elif dtype == "L":
        if _WINDOWS:
            unsigned_int_data = v
            ptr = <uint8_t*>(&unsigned_int_data[0])
        else:
            unsigned_long_data = v
            ptr = <uint8_t*>(&unsigned_long_data[0])
    elif dtype == "q":
        signed_long_data = v
        ptr = <uint8_t*>(&signed_long_data[0])
    elif dtype == "Q":
        unsigned_long_long_data = v
        ptr = <uint8_t*>(&unsigned_long_long_data[0])
    elif dtype == "f":
        signed_float_data = v
        ptr = <uint8_t*>(&signed_float_data[0])
    elif dtype == "d":
        signed_double_data = v
        ptr = <uint8_t*>(&signed_double_data[0])
    else:
        raise Exception(f"Unsupported buffer of type {type(v)} and format {dtype}")
    return ptr


def _normalize_slice(Buffer buf, slice key):
    """
    Only support step with 1
    """
    cdef:
        Py_ssize_t start, stop, step
        Py_ssize_t n = len(buf)

    start = key.start or 0
    if start < 0:
        start += n
        if start < 0:
            start = 0
    elif start >= n:
        start = n

    stop = key.stop if key.stop is not None else n
    if stop < 0:
        stop += n
        if stop < 0:
            stop = 0
    elif stop >= n:
        stop = n
    if key.step is not None:
        assert key.step == 1, f"Step should be 1 but got {key.step}"
    length = max(stop - start, 0)
    return buf.slice(start, length)


cdef Py_ssize_t _normalize_index(Py_ssize_t index,
                                 Py_ssize_t length) except -1:
    if index < 0:
        index += length
        if index < 0:
            raise IndexError("index out of bounds")
    elif index >= length:
        raise IndexError("index out of bounds")
    return index


def get_bit(Buffer buffer, uint32_t base_offset, uint32_t index) -> bool:
    return c_get_bit(buffer.c_buffer.data() + base_offset, index)


def set_bit(Buffer buffer, uint32_t base_offset, uint32_t index):
    return c_set_bit(buffer.c_buffer.data() + base_offset, index)


def clear_bit(Buffer buffer, uint32_t base_offset, uint32_t index):
    return c_clear_bit(buffer.c_buffer.data() + base_offset, index)


def set_bit_to(Buffer buffer,
               uint32_t base_offset,
               uint32_t index,
               c_bool bit_is_set):
    return c_set_bit_to(
        buffer.c_buffer.data() + base_offset, index, bit_is_set)
