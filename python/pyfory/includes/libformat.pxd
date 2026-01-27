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
from libcpp.vector cimport vector
from cpython cimport PyObject
from pyfory.includes.libutil cimport CBuffer

cimport cpython

cdef inline object PyObject_to_object(PyObject* o):
    # Cast to "object" increments reference count
    cdef object result = <object> o
    cpython.Py_DECREF(result)
    return result


# Fory type system declarations
cdef extern from "fory/type/type.h" namespace "fory" nogil:
    cpdef enum class CTypeId" fory::TypeId" (int32_t):
        UNKNOWN = 0
        BOOL = 1
        INT8 = 2
        INT16 = 3
        INT32 = 4
        VARINT32 = 5
        INT64 = 6
        VARINT64 = 7
        TAGGED_INT64 = 8
        UINT8 = 9
        UINT16 = 10
        UINT32 = 11
        VAR_UINT32 = 12
        UINT64 = 13
        VAR_UINT64 = 14
        TAGGED_UINT64 = 15
        FLOAT16 = 16
        FLOAT32 = 17
        FLOAT64 = 18
        STRING = 19
        LIST = 20
        SET = 21
        MAP = 22
        ENUM = 23
        NAMED_ENUM = 24
        STRUCT = 25
        COMPATIBLE_STRUCT = 26
        NAMED_STRUCT = 27
        NAMED_COMPATIBLE_STRUCT = 28
        EXT = 29
        NAMED_EXT = 30
        UNION = 31
        TYPED_UNION = 32
        NAMED_UNION = 33
        NONE = 34
        DURATION = 35
        TIMESTAMP = 36
        DATE = 37
        DECIMAL = 38
        BINARY = 39
        ARRAY = 40
        BOOL_ARRAY = 41
        INT8_ARRAY = 42
        INT16_ARRAY = 43
        INT32_ARRAY = 44
        INT64_ARRAY = 45
        UINT8_ARRAY = 46
        UINT16_ARRAY = 47
        UINT32_ARRAY = 48
        UINT64_ARRAY = 49
        FLOAT16_ARRAY = 50
        FLOAT32_ARRAY = 51
        FLOAT64_ARRAY = 52
        BOUND = 64


cdef extern from "fory/row/schema.h" namespace "fory::row" nogil:
    cdef cppclass CDataType" fory::row::DataType":
        CTypeId id()
        c_string name()
        c_string ToString()
        c_bool Equals(const CDataType& other)
        c_bool Equals(shared_ptr[CDataType] other)
        int num_fields()
        shared_ptr[CField] field(int i)
        vector[shared_ptr[CField]] fields()
        int bit_width()

    ctypedef shared_ptr[CDataType] CDataTypePtr" fory::row::DataTypePtr"

    cdef cppclass CFixedWidthType" fory::row::FixedWidthType"(CDataType):
        int bit_width()
        int byte_width()

    cdef cppclass CBooleanType" fory::row::BooleanType"(CFixedWidthType):
        pass

    cdef cppclass CInt8Type" fory::row::Int8Type"(CFixedWidthType):
        pass

    cdef cppclass CInt16Type" fory::row::Int16Type"(CFixedWidthType):
        pass

    cdef cppclass CInt32Type" fory::row::Int32Type"(CFixedWidthType):
        pass

    cdef cppclass CInt64Type" fory::row::Int64Type"(CFixedWidthType):
        pass

    cdef cppclass CFloat16Type" fory::row::Float16Type"(CFixedWidthType):
        pass

    cdef cppclass CFloat32Type" fory::row::Float32Type"(CFixedWidthType):
        pass

    cdef cppclass CFloat64Type" fory::row::Float64Type"(CFixedWidthType):
        pass

    cdef cppclass CStringType" fory::row::StringType"(CDataType):
        pass

    cdef cppclass CBinaryType" fory::row::BinaryType"(CDataType):
        pass

    cdef cppclass CDurationType" fory::row::DurationType"(CFixedWidthType):
        pass

    cdef cppclass CTimestampType" fory::row::TimestampType"(CFixedWidthType):
        pass

    cdef cppclass CLocalDateType" fory::row::LocalDateType"(CFixedWidthType):
        pass

    cdef cppclass CDecimalType" fory::row::DecimalType"(CDataType):
        CDecimalType(int precision, int scale)
        int precision()
        int scale()

    cdef cppclass CField" fory::row::Field":
        CField(c_string name, shared_ptr[CDataType] type, c_bool nullable)
        const c_string& name()
        const shared_ptr[CDataType]& type()
        c_bool nullable()
        c_string ToString()
        c_bool Equals(const CField& other)
        c_bool Equals(shared_ptr[CField] other)

    ctypedef shared_ptr[CField] CFieldPtr" fory::row::FieldPtr"

    cdef cppclass CListType" fory::row::ListType"(CDataType):
        CListType(shared_ptr[CDataType] value_type)
        CListType(shared_ptr[CField] value_field)
        const shared_ptr[CDataType]& value_type()
        const shared_ptr[CField]& value_field()

    ctypedef shared_ptr[CListType] CListTypePtr" fory::row::ListTypePtr"

    cdef cppclass CStructType" fory::row::StructType"(CDataType):
        CStructType(vector[shared_ptr[CField]] fields)
        shared_ptr[CField] GetFieldByName(const c_string& name)
        int GetFieldIndex(const c_string& name)

    ctypedef shared_ptr[CStructType] CStructTypePtr" fory::row::StructTypePtr"

    cdef cppclass CMapType" fory::row::MapType"(CDataType):
        CMapType(shared_ptr[CDataType] key_type, shared_ptr[CDataType] item_type, c_bool keys_sorted)
        const shared_ptr[CDataType]& key_type()
        const shared_ptr[CDataType]& item_type()
        const shared_ptr[CField]& key_field()
        const shared_ptr[CField]& item_field()
        c_bool keys_sorted()

    ctypedef shared_ptr[CMapType] CMapTypePtr" fory::row::MapTypePtr"

    cdef cppclass CSchema" fory::row::Schema":
        CSchema(vector[shared_ptr[CField]] fields)
        int num_fields()
        shared_ptr[CField] field(int i)
        const vector[shared_ptr[CField]]& fields()
        vector[c_string] field_names()
        shared_ptr[CField] GetFieldByName(const c_string& name)
        int GetFieldIndex(const c_string& name)
        c_string ToString()
        c_bool Equals(const CSchema& other)
        c_bool Equals(shared_ptr[CSchema] other)
        # Schema serialization methods
        vector[uint8_t] ToBytes() const
        @staticmethod
        shared_ptr[CSchema] FromBytes(const vector[uint8_t]& bytes)

    ctypedef shared_ptr[CSchema] CSchemaPtr" fory::row::SchemaPtr"

    # Factory functions
    shared_ptr[CDataType] boolean" fory::row::boolean"()
    shared_ptr[CDataType] int8" fory::row::int8"()
    shared_ptr[CDataType] int16" fory::row::int16"()
    shared_ptr[CDataType] int32" fory::row::int32"()
    shared_ptr[CDataType] int64" fory::row::int64"()
    shared_ptr[CDataType] float16" fory::row::float16"()
    shared_ptr[CDataType] float32" fory::row::float32"()
    shared_ptr[CDataType] float64" fory::row::float64"()
    shared_ptr[CDataType] utf8" fory::row::utf8"()
    shared_ptr[CDataType] binary" fory::row::binary"()
    shared_ptr[CDataType] duration" fory::row::duration"()
    shared_ptr[CDataType] timestamp" fory::row::timestamp"()
    shared_ptr[CDataType] date32" fory::row::date32"()
    shared_ptr[CDataType] decimal" fory::row::decimal"(int precision, int scale)

    shared_ptr[CListType] fory_list" fory::row::list"(shared_ptr[CDataType] value_type)
    shared_ptr[CDataType] struct_" fory::row::struct_"(vector[shared_ptr[CField]] fields)
    shared_ptr[CMapType] fory_map" fory::row::map"(shared_ptr[CDataType] key_type, shared_ptr[CDataType] item_type, c_bool keys_sorted)
    shared_ptr[CField] fory_field" fory::row::field"(c_string name, shared_ptr[CDataType] type, c_bool nullable)
    shared_ptr[CSchema] fory_schema" fory::row::schema"(vector[shared_ptr[CField]] fields)

    int64_t get_byte_width" fory::row::get_byte_width"(shared_ptr[CDataType] dtype)


cdef extern from "fory/row/row.h" namespace "fory::row" nogil:
    cdef cppclass CGetter" fory::row::Getter":
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

    cdef cppclass CArrayData" fory::row::ArrayData"(CGetter):
        CArrayData(shared_ptr[CListType] type)

        int num_elements()

        shared_ptr[CListType] type()

    cdef cppclass CMapData" fory::row::MapData":
        CMapData(shared_ptr[CMapType] type)

        void PointTo(shared_ptr[CBuffer] buffer,
                     uint32_t offset, uint32_t size_in_bytes)

        int num_elements()

        shared_ptr[CBuffer] buffer() const

        int base_offset() const

        int size_bytes() const

        shared_ptr[CMapType] type()

        shared_ptr[CArrayData] keys_array()

        shared_ptr[CArrayData] values_array()

        c_string ToString()

    cdef cppclass CRow" fory::row::Row"(CGetter):
        CRow(shared_ptr[CSchema] schema)

        shared_ptr[CSchema] schema()

        int num_fields()

        void PointTo(shared_ptr[CBuffer] buffer,
                     uint32_t offset, uint32_t size_in_bytes)


cdef extern from "fory/row/writer.h" namespace "fory::row" nogil:
    cdef cppclass CWriter" fory::row::Writer":

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

    cdef cppclass CRowWriter" fory::row::RowWriter"(CWriter):
        CRowWriter(shared_ptr[CSchema] schema)

        CRowWriter(shared_ptr[CSchema] schema, CWriter *writer)

        shared_ptr[CSchema] schema()

        void SetBuffer(shared_ptr[CBuffer]& buffer)

        void Reset()

        shared_ptr[CRow] ToRow()

    cdef cppclass CArrayWriter" fory::row::ArrayWriter"(CWriter):
        CArrayWriter(shared_ptr[CListType] type_, CWriter *writer)

        void Reset(int num_elements)

        int size()

        shared_ptr[CArrayData] CopyToArrayData()
