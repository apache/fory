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

        inline void WriterIndex(uint32_t writer_index)

        inline void IncreaseWriterIndex(uint32_t diff)

        inline void ReaderIndex(uint32_t reader_index)

        inline void IncreaseReaderIndex(uint32_t diff)

        void Grow(uint32_t min_capacity)

        void Reserve(uint32_t new_size)

        inline void UnsafePutByte(uint32_t offset, c_bool)

        inline void UnsafePutByte(uint32_t offset, uint8_t)

        inline void UnsafePutByte(uint32_t offset, int8_t)

        inline void UnsafePut(uint32_t offset, int16_t)

        inline void UnsafePut(uint32_t offset, int32_t)

        inline void UnsafePut(uint32_t offset, int64_t)

        inline void UnsafePut(uint32_t offset, float)

        inline void UnsafePut(uint32_t offset, double)

        void CopyFrom(uint32_t offset, const uint8_t *src, uint32_t src_offset,
                      uint32_t nbytes)

        inline c_bool GetBool(uint32_t offset)

        inline int8_t GetInt8(uint32_t offset)

        inline int16_t GetInt16(uint32_t offset)

        inline int32_t GetInt24(uint32_t offset)

        inline int32_t GetInt32(uint32_t offset)

        inline int64_t GetInt64(uint32_t offset)

        inline float GetFloat(uint32_t offset)

        inline double GetDouble(uint32_t offset)

        inline CResultVoidError GetBytesAsInt64(uint32_t offset, uint32_t length, int64_t* target)

        inline uint32_t PutVarUint32(uint32_t offset, int32_t value)

        inline int32_t GetVarUint32(uint32_t offset, uint32_t *readBytesLength)

        inline void PutInt24(uint32_t offset, int32_t value)

        void WriteUint8(uint8_t value)

        void WriteInt8(int8_t value)

        void WriteUint16(uint16_t value)

        void WriteInt16(int16_t value)

        void WriteInt24(int32_t value)

        void WriteUint32(uint32_t value)

        void WriteInt32(int32_t value)

        void WriteInt64(int64_t value)

        void WriteFloat(float value)

        void WriteDouble(double value)

        void WriteVarUint32(uint32_t value)

        void WriteVarInt32(int32_t value)

        void WriteVarUint64(uint64_t value)

        void WriteVarInt64(int64_t value)

        void WriteTaggedInt64(int64_t value)

        void WriteTaggedUint64(uint64_t value)

        void WriteBytes(const void* data, uint32_t length)

        uint8_t ReadUint8(CError& error)

        int8_t ReadInt8(CError& error)

        uint16_t ReadUint16(CError& error)

        int16_t ReadInt16(CError& error)

        int32_t ReadInt24(CError& error)

        uint32_t ReadUint32(CError& error)

        int32_t ReadInt32(CError& error)

        uint64_t ReadUint64(CError& error)

        int64_t ReadInt64(CError& error)

        float ReadFloat(CError& error)

        double ReadDouble(CError& error)

        uint32_t ReadVarUint32(CError& error)

        int32_t ReadVarInt32(CError& error)

        uint64_t ReadVarUint64(CError& error)

        int64_t ReadVarInt64(CError& error)

        int64_t ReadTaggedInt64(CError& error)

        uint64_t ReadTaggedUint64(CError& error)

        uint64_t ReadVarUint36Small(CError& error)

        void ReadBytes(void* data, uint32_t length, CError& error)

        void Skip(uint32_t length, CError& error)

        void Copy(uint32_t start, uint32_t nbytes,
                  uint8_t* out, uint32_t offset) const

        c_string Hex()

    CBuffer* AllocateBuffer(uint32_t size)
    c_bool AllocateBuffer(uint32_t size, shared_ptr[CBuffer]* out)


cdef extern from "fory/util/bit_util.h" namespace "fory::util" nogil:
    c_bool GetBit(const uint8_t *bits, uint32_t i)

    void SetBit(uint8_t *bits, int64_t i)

    void ClearBit(uint8_t *bits, int64_t i)

    void SetBitTo(uint8_t *bits, int64_t i, c_bool bit_is_set)

    c_string hex(uint8_t *data, int32_t length)


cdef extern from "fory/util/string_util.h" namespace "fory" nogil:
    c_bool utf16HasSurrogatePairs(uint16_t* data, size_t size)
