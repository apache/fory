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

# cython: profile=False
# distutils: language = c++
# cython: embedsignature = True
# cython: language_level = 3

from libc.stdint cimport *
from libcpp cimport bool as c_bool
from libcpp.memory cimport shared_ptr
from libcpp.string cimport string as c_string
from cpython cimport PyObject
from pyarrow.lib cimport CSchema, CField, CListType, CMapType
from pyarrow.lib cimport CStatus, CMemoryPool, CRecordBatch
from pyfory.includes.libutil cimport CBuffer

cimport cpython

cdef inline object PyObject_to_object(PyObject* o):
    # Cast to "object" increments reference count
    cdef object result = <object> o
    cpython.Py_DECREF(result)
    return result


cdef extern from "fory/row/row.h" namespace "fory" nogil:
    cdef cppclass CGetter" fory::Getter":
        shared_ptr[CBuffer] buffer() const

        int base_offset() const

        int size_bytes() const

        c_bool IsNullAt(int i)

        int8_t GetInt8(int i)

        int8_t GetUInt8(int i)

        c_bool GetBoolean(int i)

        int16_t GetInt16(int i)

        int32_t GetInt32(int i)

        int64_t GetInt64(int i)

        float GetFloat(int i)

        double GetDouble(int i)

        c_string GetString(int i)

        int GetBinary(int i, uint8_t** out)

        shared_ptr[CRow] GetStruct(int i)

        shared_ptr[CArrayData] GetArray(int i)

        shared_ptr[CMapData] GetMap(int i)

        c_string ToString()

    cdef cppclass CArrayData" fory::ArrayData"(CGetter):
        CArrayData(shared_ptr[CListType] type)

        int num_elements()

        shared_ptr[CListType] type()

    cdef cppclass CMapData" fory::MapData":
        CMapData(shared_ptr[CMapType] type)

        void PointTo(shared_ptr[CBuffer] buffer,
                     uint32_t offset, uint32_t size_in_bytes)

        int num_elements()

        shared_ptr[CBuffer] buffer() const

        int base_offset() const

        int size_bytes() const

        shared_ptr[CListType] type()

        shared_ptr[CArrayData] keys_array()

        shared_ptr[CArrayData] values_array()

        c_string ToString()

    cdef cppclass CRow" fory::Row"(CGetter):
        Row(shared_ptr[CSchema] schema)

        shared_ptr[CSchema] schema()

        int num_fields()

        void PointTo(shared_ptr[CBuffer] buffer,
                     uint32_t offset, uint32_t size_in_bytes)


cdef extern from "fory/row/writer.h" namespace "fory" nogil:
    cdef cppclass CWriter" fory::Writer":

        shared_ptr[CBuffer]& buffer()

        uint32_t cursor()

        uint32_t size()

        uint32_t starting_offset()

        void IncreaseCursor(uint32_t val)

        void Grow(uint32_t needed_size)

        void SetOffsetAndSize(int i, uint32_t size)

        void SetOffsetAndSize(int i, uint32_t absolute_offset, uint32_t size)

        void ZeroOutPaddingBytes(uint32_t num_bytes)

        void SetNullAt(int i)

        void SetNotNullAt(int i)

        c_bool IsNullAt(int i) const

        void Write(int i, int8_t value)
        void Write(int i, c_bool value)
        void Write(int i, int16_t value)
        void Write(int i, int32_t value)
        void Write(int i, int64_t value)
        void Write(int i, float value)
        void Write(int i, double value)

        void WriteString(int i, c_string &value)

        void WriteBytes(int i, const uint8_t *input, uint32_t length)

        void WriteUnaligned(int i, const uint8_t *input,
                            uint32_t offset, uint32_t num_bytes)

        void WriteDirectly(int64_t value)

        void WriteDirectly(uint32_t offset, int64_t value)

    cdef cppclass CRowWriter" fory::RowWriter"(CWriter):
        CRowWriter(shared_ptr[CSchema] schema)

        CRowWriter(shared_ptr[CSchema] schema, CWriter *writer)

        shared_ptr[CSchema] schema()

        void SetBuffer(shared_ptr[CBuffer]& buffer)

        void Reset()

        shared_ptr[CRow] ToRow()

    cdef cppclass CArrayWriter" fory::ArrayWriter"(CWriter):
        CArrayWriter(shared_ptr[CListType] type_, CWriter *writer)

        void Reset(int num_elements)

        int size()

        shared_ptr[CArrayData] CopyToArrayData()
