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

from libcpp.memory cimport make_shared
from libc.stdint cimport *
from cython.operator cimport dereference as deref
from datetime import timedelta
from pyfory.includes.libformat cimport (
    CGetter, CArrayData, CMapData, CRow, CTypeId,
    CSchema, CListType, CMapType, fory_schema
)
from pyfory.includes.libutil cimport CBuffer
from libcpp.memory cimport shared_ptr
from libcpp.vector cimport vector
from datetime import datetime, date
from libc.stdint cimport *
from libcpp cimport bool as c_bool
from cpython cimport *

cdef dict reader_map = {}


cdef class Getter:
    cdef:
        CGetter* getter

    cdef inline c_bool is_null_at(self, int i):
        return self.getter.is_null_at(i)

    cpdef get_boolean(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_boolean(i)

    cpdef get_int8(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_int8(i)

    cpdef get_int16(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_int16(i)

    cpdef get_int32(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_int32(i)

    cpdef get_int64(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_int64(i)

    cpdef get_float(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_float(i)

    cpdef get_double(self, int i):
        if self.is_null_at(i):
            return None
        return self.getter.get_double(i)

    cpdef get_date(self, int i):
        if self.is_null_at(i):
            return None
        cdef int32_t days = self.getter.get_int32(i)
        return date(1970, 1, 1) + timedelta(days=days)

    cpdef get_datetime(self, int i):
        if self.is_null_at(i):
            return None
        cdef int64_t timestamp = self.getter.get_int64(i)
        # TimestampType represent micro seconds
        return datetime.fromtimestamp(float(timestamp) / 1000000)

    cpdef get_binary(self, int i):
        if self.is_null_at(i):
            return None
        cdef unsigned char* binary_data
        cdef int32_t size = self.getter.get_binary(i, &binary_data)
        return binary_data[:size]

    cpdef get_str(self, int i):
        if self.is_null_at(i):
            return None
        cdef unsigned char* binary_data
        cdef int32_t size = self.getter.get_binary(i, &binary_data)
        return binary_data[:size].decode("UTF-8")

    cpdef RowData get_struct(self, int i):
        pass

    cpdef ArrayData get_array_data(self, int i):
        pass

    cpdef MapData get_map_data(self, int i):
        pass


cdef class ArrayData(Getter):
    cdef:
        ListType type_
        shared_ptr[CArrayData] data

    def __init__(self):
        raise TypeError("Do not call constructor directly, use "
                        "factory function instead.")

    @staticmethod
    cdef ArrayData wrap(shared_ptr[CArrayData] data, ListType array_type):
        cdef ArrayData array_data = ArrayData.__new__(ArrayData)
        array_data.getter = data.get()
        array_data.data = data
        array_data.type_ = array_type
        return array_data

    @property
    def num_elements(self) -> int:
        return self.data.get().num_elements()

    def buffer(self) -> Buffer:
        return Buffer.wrap(self.data.get().buffer())

    def base_offset(self) -> int:
        return self.data.get().base_offset()

    def size_bytes(self) -> int:
        return self.data.get().size_bytes()

    cpdef RowData get_struct(self, int i):
        cdef DataType data_type = self.type_.value_type
        if self.is_null_at(i):
            return None
        # Create schema from struct type fields
        cdef Schema struct_schema = _struct_type_to_schema(data_type)
        return RowData.wrap(self.data.get().get_struct(i), struct_schema)

    cpdef ArrayData get_array_data(self, int i):
        cdef DataType data_type = self.type_.value_type
        if self.is_null_at(i):
            return None
        # data_type should be a ListType
        cdef ListType list_type = <ListType>data_type
        return ArrayData.wrap(self.data.get().get_array(i), list_type)

    cpdef MapData get_map_data(self, int i):
        cdef DataType data_type = self.type_.value_type
        if self.is_null_at(i):
            return None
        cdef shared_ptr[CMapData] v = self.data.get().get_map(i)
        cdef MapType map_type = <MapType>data_type
        return MapData.wrap(v, map_type)

    def __getitem__(self, i):
        if i > self.num_elements or i < 0:
            raise IndexError("length is {}, but index is {}"
                             .format(self.num_elements, i))
        return self.get(i)

    def get(self, int i):
        cdef DataType value_type = self.type_.value_type
        key = id(value_type)
        reader = reader_map.get(key)
        if reader is None:
            reader = get_reader(value_type, type(self))
            reader_map[key] = reader
        if self.is_null_at(i):
            return None
        else:
            # cdef methods don't bind self.
            return reader(self, i)

    def __dealloc__(self):
        reader_map.pop(id(self.type_.value_type), None)

    def __str__(self) -> str:
        cdef:
            int length = self.num_elements
            int i
            str result = "["
        cdef DataType value_type = self.type_.value_type
        getter = get_reader(value_type, type(self))
        for i in range(length):
            if i != 0:
                result += ','
            if self.is_null_at(i):
                result += "null"
            else:
                result += str(getter(self, i))
        result += ']'
        return result


cdef class MapData:
    cdef:
        shared_ptr[CMapData] data
        MapType map_type

    def __init__(self):
        raise TypeError("Do not call constructor directly, use "
                        "factory function instead.")

    @staticmethod
    cdef MapData wrap(shared_ptr[CMapData] data, MapType map_type):
        cdef MapData map_data = MapData.__new__(MapData)
        map_data.data = data
        map_data.map_type = map_type
        return map_data

    @property
    def num_elements(self) -> int:
        return self.data.get().num_elements()

    def buffer(self) -> Buffer:
        return Buffer.wrap(self.data.get().buffer())

    def base_offset(self) -> int:
        return self.data.get().base_offset()

    def size_bytes(self) -> int:
        return self.data.get().size_bytes()

    def keys_array(self):
        cdef ListType array_type = list_(self.map_type.key_type)
        return ArrayData.wrap(self.data.get().keys_array(), array_type)

    def values_array(self):
        cdef ListType array_type = list_(self.map_type.item_type)
        return ArrayData.wrap(self.data.get().values_array(), array_type)

    cdef keys_array_(self, ListType array_type):
        return ArrayData.wrap(self.data.get().keys_array(), array_type)

    cdef values_array_(self, ListType array_type):
        return ArrayData.wrap(self.data.get().values_array(), array_type)

    def __str__(self):
        return 'Map{' + str(self.keys_array()) + ', ' + str(self.values_array()) + '}'


cdef Schema _struct_type_to_schema(DataType struct_type):
    """Convert a struct type to a schema."""
    cdef vector[shared_ptr[CField]] c_fields
    cdef int num = struct_type.num_fields
    for i in range(num):
        c_fields.push_back((<Field>struct_type.field(i)).c_field)
    cdef Schema schema = Schema.__new__(Schema)
    schema.c_schema = fory_schema(c_fields)
    return schema


cdef class RowData(Getter):
    cdef:
        shared_ptr[CRow] data
        Schema schema_
        Buffer _buf  # hold buffer reference

    def __init__(self, schema, buffer, offset=0, size_in_bytes=None):
        if size_in_bytes is None:
            size_in_bytes = len(buffer)
        if type(buffer) is not Buffer:
            buffer = Buffer(buffer, offset=offset, length=size_in_bytes)
        self._buf = buffer
        cdef:
            Buffer buf = <Buffer>buffer
            shared_ptr[CRow] row = make_shared[CRow]((<Schema>schema).c_schema)
            shared_ptr[CBuffer] shared_buf = make_shared[CBuffer](
                buf.c_buffer.data(),
                buf.c_buffer.size(),
                False,
            )
        deref(row).point_to(shared_buf, offset, size_in_bytes)
        self.data = row
        self.getter = row.get()
        self.schema_ = schema

    @staticmethod
    cdef RowData wrap(shared_ptr[CRow] data, Schema schema):
        cdef RowData row_data = RowData.__new__(RowData)
        row_data.data = data
        row_data.getter = data.get()
        row_data.schema_ = schema
        return row_data

    @property
    def schema(self):
        return self.schema_

    @property
    def num_fields(self) -> int:
        return self.data.get().num_fields()

    def buffer(self) -> Buffer:
        return Buffer.wrap(self.data.get().buffer())

    cpdef base_offset(self):
        return self.data.get().base_offset()

    cpdef size_bytes(self):
        return self.data.get().size_bytes()

    def to_bytes(self) -> bytes:
        end_offset = self.base_offset() + self.size_bytes()
        return self.buffer().to_bytes()[self.base_offset():end_offset]

    cpdef RowData get_struct(self, int i):
        if self.is_null_at(i):
            return None
        cdef DataType data_type = self.schema_.field(i).type
        cdef Schema struct_schema = _struct_type_to_schema(data_type)
        return RowData.wrap(self.data.get().get_struct(i), struct_schema)

    cpdef ArrayData get_array_data(self, int i):
        if self.is_null_at(i):
            return None
        cdef DataType data_type = self.schema_.field(i).type
        cdef ListType list_type = <ListType>data_type
        return ArrayData.wrap(self.data.get().get_array(i), list_type)

    cpdef MapData get_map_data(self, int i):
        if self.is_null_at(i):
            return None
        cdef DataType data_type = self.schema_.field(i).type
        cdef shared_ptr[CMapData] v = self.data.get().get_map(i)
        cdef MapType map_type = <MapType>data_type
        return MapData.wrap(v, map_type)

    def __getitem__(self, i):
        if not isinstance(i, int):
            assert type(i) is str
            i = self.schema_.names.index(i)
        if i > self.num_fields or i < 0:
            raise IndexError("num_fields is {}, but index is {}"
                             .format(self.num_fields, i))
        return self.get(i)

    def __getattr__(self, item):
        return self.__getitem__(item)

    def get(self, i):
        key = id(self.schema_)
        readers = reader_map.get(key)
        if readers is None:
            readers = []
            for field_index in range(self.schema_.num_fields):
                readers.append(get_reader(
                    self.schema_.field(field_index).type, type(self)))
            reader_map[key] = readers

        if self.is_null_at(i):
            return None
        else:
            return readers[i](self, i)

    def __dealloc__(self):
        reader_map.pop(id(self.schema_), None)

    def __str__(self) -> str:
        cdef:
            Field field_
            int num_fields = self.schema_.num_fields
            int i
            str result = "{"
        for i in range(num_fields):
            if i != 0:
                result += ','
            field_ = self.schema_.field(i)
            getter = get_reader(field_.type, type(self))
            result += field_.name
            result += '='
            if self.is_null_at(i):
                result += "null"
            else:
                result += str(getter(self, i))
        result += "}"
        return result


def assert_type(i, data_type, type_cls):
    if not isinstance(data_type, type_cls):
        raise TypeError("type for {0} is {1}, isn't {2}".
                        format(i, data_type, type_cls))


def get_reader(data_type, type_):
    """get the appropriate reader method based on the Fory data type."""
    cdef CTypeId type_id = (<DataType>data_type).id
    if type_id == CTypeId.BOOL:
        return type_.get_boolean
    elif type_id == CTypeId.INT8:
        return type_.get_int8
    elif type_id == CTypeId.INT16:
        return type_.get_int16
    elif type_id == CTypeId.INT32:
        return type_.get_int32
    elif type_id == CTypeId.INT64:
        return type_.get_int64
    elif type_id == CTypeId.FLOAT32:
        return type_.get_float
    elif type_id == CTypeId.FLOAT64:
        return type_.get_double
    elif type_id == CTypeId.DATE:
        return type_.get_date
    elif type_id == CTypeId.TIMESTAMP:
        return type_.get_datetime
    elif type_id == CTypeId.BINARY:
        return type_.get_binary
    elif type_id == CTypeId.STRING:
        return type_.get_str
    elif type_id == CTypeId.STRUCT:
        return type_.get_struct
    elif type_id == CTypeId.LIST:
        return type_.get_array_data
    elif type_id == CTypeId.MAP:
        return type_.get_map_data
    raise TypeError("Unsupported type: " + str(data_type))
