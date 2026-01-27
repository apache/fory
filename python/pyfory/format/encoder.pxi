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

from libcpp.memory cimport shared_ptr, dynamic_pointer_cast
from datetime import datetime, date
from libc.stdint cimport *
from libcpp cimport bool as c_bool
from libcpp.vector cimport vector
import cython
from cpython cimport *
from pyfory.includes.libformat cimport (
    CWriter, CRowWriter, CArrayWriter, CBuffer, CTypeId,
    CSchema, CField, CListType, CMapType, fory_schema, fory_list
)
from pyfory.includes.libutil cimport AllocateBuffer

cimport pyfory.includes.libformat as libformat


def create_row_encoder(Schema schema):
    return RowEncoder.create(schema)


cdef class Encoder:
    cdef:
        CWriter* writer

    cdef write(self, int i, value):
        pass
    cdef read(self, Getter data, int i):
        pass


cdef class RowEncoder(Encoder):
    cdef:
        readonly Schema schema_
        int initial_buffer_size
        shared_ptr[CSchema] c_schema
        CWriter* parent_writer
        CRowWriter* row_writer
        list encoders
        c_bool is_root

    def __init__(self):
        raise TypeError("Do not call constructor directly, use "
                        "factory function instead.")

    @staticmethod
    cdef create(Schema schema, CWriter* parent_writer=NULL, initial_buffer_size=16):
        cdef RowEncoder encoder = RowEncoder.__new__(RowEncoder)
        encoder.schema_ = schema
        encoder.c_schema = schema.c_schema
        encoder.initial_buffer_size = initial_buffer_size
        encoder.parent_writer = parent_writer

        if parent_writer == NULL:
            encoder.row_writer = new CRowWriter(encoder.c_schema)
        else:
            encoder.row_writer = new CRowWriter(encoder.c_schema, parent_writer)

        encoder.encoders = []
        cdef:
            Field field_
        for i in range(schema.num_fields):
            field_ = schema.field(i)
            encoder.encoders.append(create_converter(field_, encoder.row_writer))
        return encoder

    @property
    def schema(self):
        return self.schema_

    # Special methods of extension types must be declared with def, not cdef.
    def __dealloc__(self):
        del self.row_writer

    cpdef RowData to_row(self, value):
        if value is None:
            raise ValueError("value shouldn't be None")

        cdef shared_ptr[CBuffer] buf
        if not AllocateBuffer(self.initial_buffer_size, &buf):
            raise MemoryError("out of memory")
        self.row_writer.SetBuffer(buf)
        self.row_writer.Reset()
        return self.write_row(value)

    cpdef from_row(self, RowData row):
        return self.decode(row)

    cdef RowData write_row(self, value):
        cdef:
            Field field_
            int num_fields = self.schema_.num_fields
            int i
        # we don't use __dict__, because if user implements __getattr__/__setattr__
        # or use descriptor, the key in __dict__ may be not same as in schema's
        # field name, and __slot__ also needs extra check.
        # We don't support Mapping subclass, because isinstance cost too much time.
        if type(value) is not dict:
            for i in range(num_fields):
                field_ = self.schema_.field(i)
                field_value = getattr(value, field_.name, None)
                if field_value is None:
                    self.row_writer.SetNullAt(i)
                else:
                    self.row_writer.SetNotNullAt(i)
                    (<Encoder>self.encoders[i]).write(i, field_value)
        else:
            for i in range(num_fields):
                field_ = self.schema_.field(i)
                field_value = value.get(field_.name)
                if field_value is None:
                    self.row_writer.SetNullAt(i)
                else:
                    self.row_writer.SetNotNullAt(i)
                    (<Encoder>self.encoders[i]).write(i, field_value)
        cdef shared_ptr[libformat.CRow] row = self.row_writer.ToRow()
        return RowData.wrap(row, self.schema_)

    cdef decode(self, RowData row):
        cdef:
            int num_fields = self.schema_.num_fields
            int i
        from pyfory.format import get_cls_by_schema
        cls = get_cls_by_schema(self.schema_)
        obj = cls.__new__(cls)
        for i in range(num_fields):
            field_ = self.schema_.field(i)
            field_name = field_.name
            if not row.is_null_at(i):
                setattr(obj, field_name, (<Encoder>self.encoders[i]).read(row, i))
            else:
                setattr(obj, field_name, None)
        return obj

    cdef write(self, int i, value):
        cdef int offset = self.parent_writer.cursor()
        self.row_writer.Reset()
        self.write_row(value)
        cdef int size = self.parent_writer.cursor() - offset
        self.parent_writer.SetOffsetAndSize(i, offset, size)

    cdef read(self, Getter data, int i):
        struct_data = data.get_struct(i)
        if struct_data is not None:
            return self.decode(struct_data)
        else:
            return None


cdef class ArrayWriter(Encoder):
    cdef:
        ListType list_type
        CWriter* parent_writer
        CArrayWriter* array_writer
        object elem_encoder

    def __init__(self):
        raise TypeError("Do not call ArrayWriter's constructor directly, use "
                        "factory function instead.")

    @staticmethod
    cdef ArrayWriter create(ListType list_type, CWriter* parent_writer):
        cdef:
            ArrayWriter encoder = ArrayWriter.__new__(ArrayWriter)
        cdef shared_ptr[CListType] c_list_type = \
            dynamic_pointer_cast[CListType, libformat.CDataType](list_type.c_type)

        encoder.parent_writer = parent_writer
        encoder.list_type = list_type
        encoder.array_writer = new CArrayWriter(c_list_type, parent_writer)
        # Create encoder for value type
        cdef Field value_field = list_type.value_field
        encoder.elem_encoder = create_converter(value_field, encoder.array_writer)
        return encoder

    def __dealloc__(self):
        del self.array_writer

    cdef void write_array(self, value):
        """If value don't have __iter__/__len__, raise TypeError"""
        if value is None:
            raise ValueError("value shouldn't be None")
        # only support max to 32-bit int, so we don't use Py_ssize_t,
        # use int and let cython check overflow instead.
        cdef:
            int length = len(value)
            int i
        self.array_writer.Reset(length)
        it = iter(value)
        for i in range(length):
            elem = next(it)
            if elem is None:
                self.array_writer.SetNullAt(i)
            else:
                self.array_writer.SetNotNullAt(i)
                (<Encoder>self.elem_encoder).write(i, elem)

    cdef decode(self, ArrayData array_data):
        cdef:
            int num_elements = array_data.data.get().num_elements()
            int i
        arr = []
        for i in range(num_elements):
            if not array_data.is_null_at(i):
                arr.append((<Encoder>self.elem_encoder).read(array_data, i))
            else:
                arr.append(None)
        return arr

    cdef write(self, int i, value):
        cdef int offset = self.parent_writer.cursor()
        self.write_array(value)
        cdef int size = self.parent_writer.cursor() - offset
        self.parent_writer.SetOffsetAndSize(i, offset, size)

    cdef read(self, Getter data, int i):
        array_data = data.get_array_data(i)
        if array_data is not None:
            return self.decode(array_data)
        else:
            return None

cdef class MapWriter(Encoder):
    cdef:
        MapType map_type
        CWriter* parent_writer
        ArrayWriter keys_encoder
        ArrayWriter values_encoder

    def __init__(self):
        raise TypeError("Do not call MapWriter's constructor directly, use "
                        "factory function instead.")

    @staticmethod
    cdef MapWriter create(MapType map_type, CWriter* parent_writer):
        cdef MapWriter encoder = MapWriter.__new__(MapWriter)
        encoder.map_type = map_type
        encoder.parent_writer = parent_writer
        # Create list types for keys and values
        cdef ListType keys_list_type = list_(map_type.key_type)
        cdef ListType values_list_type = list_(map_type.item_type)
        encoder.keys_encoder = ArrayWriter.create(keys_list_type, parent_writer)
        encoder.values_encoder = ArrayWriter.create(values_list_type, parent_writer)
        return encoder

    cdef void write_map(self, value):
        """if value has keys/values methods, we take it as a dict,
        else raise TypeError"""
        if value is None:
            raise ValueError("value shouldn't be None")
        cdef int offset = self.parent_writer.cursor()
        self.parent_writer.WriteDirectly(-1)  # increase cursor by 8
        self.keys_encoder.write_array(value.keys())
        cdef int keys_size_bytes = self.parent_writer.cursor() - offset - 8
        self.parent_writer.WriteDirectly(offset, keys_size_bytes)
        self.values_encoder.write_array(value.values())

    cdef decode(self, MapData map_data):
        cdef:
            int num_elements = map_data.num_elements
            int i
        dict_obj = {}
        key_arr = self.keys_encoder.decode(
            map_data.keys_array_(self.keys_encoder.list_type))
        value_arr = self.values_encoder.decode(
            map_data.values_array_(self.values_encoder.list_type))
        return dict(zip(key_arr, value_arr))

    cdef write(self, int i, value):
        cdef int offset = self.parent_writer.cursor()
        self.write_map(value)
        cdef int size = self.parent_writer.cursor() - offset
        self.parent_writer.SetOffsetAndSize(i, offset, size)

    cdef read(self, Getter data, int i):
        map_data = data.get_map_data(i)
        if map_data is not None:
            return self.decode(map_data)
        else:
            return None


# no need to check numeric overflow, cython will check it
# cython will check type for automatic cast
# cython: checked-type-casts

# When a parameter of a Python function is declared to have a C data type,
# it is passed in as a Python object and automatically converted to a C value,
# if possible. Automatic conversion is currently only possible for numeric types,
# string types and structs (composed recursively of any of these types).
# So you can declare parameter with extension_types, because it's a python object
@cython.internal
cdef class BooleanWriter(Encoder):
    cdef write(self, int i, value):
        cdef c_bool v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_boolean(i)


@cython.internal
cdef class Int8Writer(Encoder):
    cdef write(self, int i, value):
        cdef int8_t v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_int8(i)


@cython.internal
cdef class Int16Writer(Encoder):
    cdef write(self, int i, value):
        cdef int16_t v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_int16(i)


@cython.internal
cdef class Int32Writer(Encoder):
    cdef write(self, int i, value):
        cdef int32_t v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_int32(i)


@cython.internal
cdef class Int64Writer(Encoder):
    cdef write(self, int i, value):
        cdef int64_t v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_int64(i)


@cython.internal
cdef class FloatWriter(Encoder):
    cdef write(self, int i, value):
        cdef float v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_float(i)


@cython.internal
cdef class DoubleWriter(Encoder):
    cdef write(self, int i, value):
        cdef double v = value
        self.writer.Write(i, v)

    cdef read(self, Getter data, int i):
        return data.get_double(i)


@cython.internal
cdef class DateWriter(Encoder):
    cdef write(self, int i, value):
        if not isinstance(value, date):
            raise TypeError("{} should be {} instead of {}".format(
                value, date, type(value)))
        cdef int32_t days = (value - date(1970, 1, 1)).days
        self.writer.Write(i, days)

    cdef read(self, Getter data, int i):
        return data.get_date(i)


@cython.internal
cdef class TimestampWriter(Encoder):
    cdef write(self, int i, value):
        if not isinstance(value, datetime):
            raise TypeError("{} should be {} instead of {}".format(
                value, datetime, type(value)))
        # TimestampType represent micro seconds
        cdef int64_t timestamp = int(value.timestamp() * 1000000)
        self.writer.Write(i, timestamp)

    cdef read(self, Getter data, int i):
        return data.get_datetime(i)


@cython.internal
cdef class BinaryWriter(Encoder):
    cdef write(self, int i, value):
        # support bytes, bytearray, array of unsigned char
        cdef const unsigned char[:] data = value
        cdef int32_t length = data.nbytes
        self.writer.WriteBytes(i, &data[0], length)

    cdef read(self, Getter data, int i):
        return data.get_binary(i)


@cython.internal
cdef class StrWriter(Encoder):
    cdef write(self, int i, value):
        cdef unsigned char* data
        if PyUnicode_Check(value):
            encoded = PyUnicode_AsEncodedString(value, "UTF-8", "encode to utf-8 error")
            data = encoded
            self.writer.WriteBytes(i, data, len(encoded))
        else:
            raise TypeError("value should be unicode, but get type of {}"
                            .format(type(value)))

    cdef read(self, Getter data, int i):
        return data.get_str(i)


cdef create_converter(Field field_, CWriter* writer):
    """Create an encoder for the given field type."""
    cdef:
        RowEncoder row_encoder
        ArrayWriter array_encoder
        MapWriter map_encoder
        DataType data_type = field_.type
        CTypeId type_id = data_type.id
        ListType list_type
        MapType map_type

    if type_id == CTypeId.BOOL:
        return create_atomic_encoder(BooleanWriter, writer)
    elif type_id == CTypeId.INT8:
        return create_atomic_encoder(Int8Writer, writer)
    elif type_id == CTypeId.INT16:
        return create_atomic_encoder(Int16Writer, writer)
    elif type_id == CTypeId.INT32:
        return create_atomic_encoder(Int32Writer, writer)
    elif type_id == CTypeId.INT64:
        return create_atomic_encoder(Int64Writer, writer)
    elif type_id == CTypeId.FLOAT32:
        return create_atomic_encoder(FloatWriter, writer)
    elif type_id == CTypeId.FLOAT64:
        return create_atomic_encoder(DoubleWriter, writer)
    elif type_id == CTypeId.DATE:
        return create_atomic_encoder(DateWriter, writer)
    elif type_id == CTypeId.TIMESTAMP:
        return create_atomic_encoder(TimestampWriter, writer)
    elif type_id == CTypeId.BINARY:
        return create_atomic_encoder(BinaryWriter, writer)
    elif type_id == CTypeId.STRING:
        return create_atomic_encoder(StrWriter, writer)
    elif type_id == CTypeId.STRUCT:
        # Create schema from struct type fields
        struct_schema = _struct_type_to_schema(data_type)
        row_encoder = RowEncoder.create(struct_schema, writer)
        return row_encoder
    elif type_id == CTypeId.LIST:
        list_type = <ListType>data_type
        array_encoder = ArrayWriter.create(list_type, writer)
        return array_encoder
    elif type_id == CTypeId.MAP:
        map_type = <MapType>data_type
        map_encoder = MapWriter.create(map_type, writer)
        return map_encoder
    raise TypeError("Unsupported type: " + str(data_type))


cdef create_atomic_encoder(cls, CWriter* writer):
    cdef Encoder encoder = cls.__new__(cls)
    encoder.writer = writer
    return encoder
