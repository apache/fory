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

from libc.stdint cimport *
from libcpp cimport bool as c_bool
from libcpp.memory cimport shared_ptr
from libcpp.string cimport string as c_string

cdef extern from "fory/util/error.h" namespace "fory" nogil:
    ctypedef enum class CErrorCode "fory::ErrorCode"(char):
        OK = 0,
        OutOfMemory = 1,
        OutOfBound = 2,
        KeyError = 3,
        TypeError = 4,
        Invalid = 5,
        IOError = 6,
        UnknownError = 7,
        EncodeError = 8,
        InvalidData = 9,
        InvalidRef = 10,
        UnknownEnum = 11,
        EncodingError = 12,
        DepthExceed = 13,
        Unsupported = 14,
        NotAllowed = 15,
        StructVersionMismatch = 16,
        TypeMismatch = 17,
        BufferOutOfBound = 18

    cdef cppclass CError "fory::Error":
        c_bool ok() const
        CErrorCode code() const
        const c_string& message() const
        c_string to_string() const
        c_string code_as_string() const
        void reset()

cdef extern from "fory/util/result.h" namespace "fory" nogil:
    cdef cppclass CResultVoidError "fory::Result<void, fory::Error>":
        c_bool has_value() const
        c_bool ok() const
        CError& error()

cdef extern from "fory/util/buffer.h" namespace "fory" nogil:
    cdef cppclass CBuffer "fory::Buffer":
        CBuffer()
        CBuffer(uint8_t* data, uint32_t size, c_bool own_data)

        inline uint8_t* data()

        inline uint32_t size()

        inline c_bool own_data()

        inline uint32_t writer_index()

        inline uint32_t reader_index()

        inline c_bool ensure_readable(uint32_t length, CError& error)

        inline void writer_index(uint32_t writer_index)

        inline void increase_writer_index(uint32_t diff)

        inline void reader_index(uint32_t reader_index)

        inline void increase_reader_index(uint32_t diff, CError& error)

        void grow(uint32_t min_capacity)

        void reserve(uint32_t new_size)

        inline void unsafe_put_byte(uint32_t offset, c_bool)

        inline void unsafe_put_byte(uint32_t offset, uint8_t)

        inline void unsafe_put_byte(uint32_t offset, int8_t)

        inline void unsafe_put(uint32_t offset, int16_t)

        inline void unsafe_put(uint32_t offset, int32_t)

        inline void unsafe_put(uint32_t offset, int64_t)

        inline void unsafe_put(uint32_t offset, float)

        inline void unsafe_put(uint32_t offset, double)

        void copy_from(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                      uint32_t nbytes)

        inline c_bool get_bool(uint32_t offset)

        inline int8_t get_int8(uint32_t offset)

        inline int16_t get_int16(uint32_t offset)

        inline int32_t get_int24(uint32_t offset)

        inline int32_t get_int32(uint32_t offset)

        inline int64_t get_int64(uint32_t offset)

        inline float get_float(uint32_t offset)

        inline double get_double(uint32_t offset)

        inline CResultVoidError get_bytes_as_int64(uint32_t offset, uint32_t length, int64_t* target)

        inline uint32_t put_var_uint32(uint32_t offset, int32_t value)

        inline int32_t get_var_uint32(uint32_t offset, uint32_t *read_bytes_length)

        inline void put_int24(uint32_t offset, int32_t value)

        void write_uint8(uint8_t value)

        void write_int8(int8_t value)

        void write_uint16(uint16_t value)

        void write_int16(int16_t value)

        void write_int24(int32_t value)

        void write_uint32(uint32_t value)

        void write_int32(int32_t value)

        void write_int64(int64_t value)

        void write_float(float value)

        void write_double(double value)

        void write_var_uint32(uint32_t value)

        void write_var_int32(int32_t value)

        void write_var_uint64(uint64_t value)

        void write_var_int64(int64_t value)

        void write_tagged_int64(int64_t value)

        void write_tagged_uint64(uint64_t value)

        void write_bytes(const void* data, uint32_t length)

        uint8_t read_uint8(CError& error)

        int8_t read_int8(CError& error)

        uint16_t read_uint16(CError& error)

        int16_t read_int16(CError& error)

        int32_t read_int24(CError& error)

        uint32_t read_uint32(CError& error)

        int32_t read_int32(CError& error)

        uint64_t read_uint64(CError& error)

        int64_t read_int64(CError& error)

        float read_float(CError& error)

        double read_double(CError& error)

        uint32_t read_var_uint32(CError& error)

        int32_t read_var_int32(CError& error)

        uint64_t read_var_uint64(CError& error)

        int64_t read_var_int64(CError& error)

        int64_t read_tagged_int64(CError& error)

        uint64_t read_tagged_uint64(CError& error)

        uint64_t read_var_uint36_small(CError& error)

        void read_bytes(void* data, uint32_t length, CError& error)

        void skip(uint32_t length, CError& error)

        void copy(uint32_t start, uint32_t nbytes,
                  uint8_t* out, uint32_t offset) const

        c_string hex()

    CBuffer* allocate_buffer(uint32_t size)
    c_bool allocate_buffer(uint32_t size, shared_ptr[CBuffer]* out)


cdef extern from "fory/util/bit_util.h" namespace "fory::util" nogil:
    c_bool get_bit(const uint8_t *bits, uint32_t i)

    void set_bit(uint8_t *bits, int64_t i)

    void clear_bit(uint8_t *bits, int64_t i)

    void set_bit_to(uint8_t *bits, int64_t i, c_bool bit_is_set)

    c_string hex(uint8_t *data, int32_t length)


cdef extern from "fory/util/string_util.h" namespace "fory" nogil:
    c_bool utf16_has_surrogate_pairs(uint16_t* data, size_t size)
