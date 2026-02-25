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

"""
Cython wrapper for Fory schema types.

This module exposes the C++ schema API to Python, providing DataType, Field,
and Schema classes for defining row format schemas.
"""

from libcpp.memory cimport shared_ptr, make_shared, dynamic_pointer_cast
from libcpp.string cimport string as c_string
from libcpp.vector cimport vector
from libcpp cimport bool as c_bool
from libc.stdint cimport int64_t
from cython.operator cimport dereference as deref

from pyfory.includes.libformat cimport (
    CDataType, CDataTypePtr, CField, CFieldPtr, CSchema, CSchemaPtr,
    CListType, CListTypePtr, CMapType, CMapTypePtr, CStructType, CStructTypePtr,
    CFixedWidthType, CDecimalType, CTypeId,
    boolean as c_boolean, int8 as c_int8, int16 as c_int16, int32 as c_int32,
    int64 as c_int64, float16 as c_float16, float32 as c_float32,
    float64 as c_float64, utf8 as c_utf8, binary as c_binary,
    duration as c_duration, timestamp as c_timestamp, date32 as c_date32,
    decimal as c_decimal, fory_list, struct_, fory_map, fory_field, fory_schema,
    get_byte_width as c_get_byte_width
)



# Create Python-accessible TypeId enum
# The CTypeId enum from libformat.pxd is only accessible from Cython
class TypeId:
    """Type identifiers for Fory data types."""
    BOOL = 1
    INT8 = 2
    INT16 = 3
    INT32 = 4
    INT64 = 6
    UINT8 = 9
    UINT16 = 10
    UINT32 = 11
    UINT64 = 13
    FLOAT8 = 16
    FLOAT16 = 17
    BFLOAT16 = 18
    FLOAT32 = 19
    FLOAT64 = 20
    STRING = 21
    LIST = 22
    SET = 23
    MAP = 24
    STRUCT = 27
    UNION = 33
    TYPED_UNION = 34
    NAMED_UNION = 35
    NONE = 36
    DURATION = 37
    TIMESTAMP = 38
    DATE = 39
    DECIMAL = 40
    BINARY = 41


cdef class DataType:
    """Base class for all Fory data types."""
    cdef shared_ptr[CDataType] c_type

    def __init__(self):
        raise TypeError("DataType cannot be instantiated directly. Use factory functions.")

    @staticmethod
    cdef DataType wrap(shared_ptr[CDataType] c_type):
        cdef DataType dtype = DataType.__new__(DataType)
        dtype.c_type = c_type
        return dtype

    @property
    def id(self):
        """Returns the TypeId of this data type."""
        return self.c_type.get().id()

    @property
    def name(self) -> str:
        """Returns the name of this data type."""
        return self.c_type.get().name().decode('utf-8')

    @property
    def bit_width(self) -> int:
        """Returns the bit width for fixed-width types, or -1 for variable-width."""
        return self.c_type.get().bit_width()

    @property
    def num_fields(self) -> int:
        """Returns the number of child fields for nested types."""
        return self.c_type.get().num_fields()

    def field(self, int i):
        """Returns the i-th child field for nested types."""
        cdef shared_ptr[CField] c_field = self.c_type.get().field(i)
        if c_field.get() == NULL:
            return None
        return Field.wrap(c_field)

    def fields(self) -> list:
        """Returns all child fields for nested types."""
        cdef vector[shared_ptr[CField]] c_fields = self.c_type.get().fields()
        return [Field.wrap(f) for f in c_fields]

    def equals(self, other) -> bool:
        """Check if this type equals another type."""
        if not isinstance(other, DataType):
            return False
        cdef DataType other_type = <DataType>other
        return self.c_type.get().equals(other_type.c_type)

    def __eq__(self, other):
        return self.equals(other)

    def __str__(self) -> str:
        return self.c_type.get().to_string().decode('utf-8')

    def __repr__(self) -> str:
        return f"DataType({self})"


cdef class ListType(DataType):
    """List type: variable-length array of elements with uniform type."""

    @staticmethod
    cdef ListType wrap_list(shared_ptr[CListType] c_type):
        cdef ListType dtype = ListType.__new__(ListType)
        dtype.c_type = <shared_ptr[CDataType]>c_type
        return dtype

    @property
    def value_type(self):
        """Returns the element type of the list."""
        cdef CListType* list_type = <CListType*>self.c_type.get()
        return DataType.wrap(list_type.value_type())

    @property
    def value_field(self):
        """Returns the value field of the list."""
        cdef CListType* list_type = <CListType*>self.c_type.get()
        return Field.wrap(list_type.value_field())


cdef class MapType(DataType):
    """Map type: collection of key-value pairs."""

    @staticmethod
    cdef MapType wrap_map(shared_ptr[CMapType] c_type):
        cdef MapType dtype = MapType.__new__(MapType)
        dtype.c_type = <shared_ptr[CDataType]>c_type
        return dtype

    @property
    def key_type(self):
        """Returns the key type of the map."""
        cdef CMapType* map_type = <CMapType*>self.c_type.get()
        return DataType.wrap(map_type.key_type())

    @property
    def item_type(self):
        """Returns the value type of the map."""
        cdef CMapType* map_type = <CMapType*>self.c_type.get()
        return DataType.wrap(map_type.item_type())

    @property
    def keys_sorted(self) -> bool:
        """Returns whether map keys are sorted."""
        cdef CMapType* map_type = <CMapType*>self.c_type.get()
        return map_type.keys_sorted()


cdef class StructType(DataType):
    """Struct type: sequence of named fields."""

    @staticmethod
    cdef StructType wrap_struct(shared_ptr[CStructType] c_type):
        cdef StructType dtype = StructType.__new__(StructType)
        dtype.c_type = <shared_ptr[CDataType]>c_type
        return dtype

    def get_field_by_name(self, str name):
        """Returns the field with the given name, or None if not found."""
        cdef CStructType* struct_type = <CStructType*>self.c_type.get()
        cdef c_string c_name = name.encode('utf-8')
        cdef shared_ptr[CField] c_field = struct_type.get_field_by_name(c_name)
        if c_field.get() == NULL:
            return None
        return Field.wrap(c_field)

    def get_field_index(self, str name) -> int:
        """Returns the index of the field with the given name, or -1 if not found."""
        cdef CStructType* struct_type = <CStructType*>self.c_type.get()
        cdef c_string c_name = name.encode('utf-8')
        return struct_type.get_field_index(c_name)


cdef class Field:
    """A named field with type, nullability, and optional metadata."""
    cdef shared_ptr[CField] c_field

    def __init__(self, str name, DataType dtype, bint nullable=True):
        """Create a new field.

        Args:
            name: Field name.
            dtype: Data type of the field.
            nullable: Whether the field can contain null values.
        """
        cdef c_string c_name = name.encode('utf-8')
        self.c_field = fory_field(c_name, (<DataType>dtype).c_type, nullable)

    @staticmethod
    cdef Field wrap(shared_ptr[CField] c_field):
        cdef Field field = Field.__new__(Field)
        field.c_field = c_field
        return field

    @property
    def name(self) -> str:
        """Returns the field name."""
        return self.c_field.get().name().decode('utf-8')

    @property
    def type(self):
        """Returns the data type of the field."""
        cdef shared_ptr[CDataType] c_type = self.c_field.get().type()
        cdef CTypeId type_id = c_type.get().id()
        if type_id == CTypeId.LIST:
            return ListType.wrap_list(dynamic_pointer_cast[CListType, CDataType](c_type))
        elif type_id == CTypeId.MAP:
            return MapType.wrap_map(dynamic_pointer_cast[CMapType, CDataType](c_type))
        elif type_id == CTypeId.STRUCT:
            return StructType.wrap_struct(dynamic_pointer_cast[CStructType, CDataType](c_type))
        return DataType.wrap(c_type)

    @property
    def nullable(self) -> bool:
        """Returns whether the field can contain null values."""
        return self.c_field.get().nullable()

    def equals(self, other) -> bool:
        """Check if this field equals another field."""
        if not isinstance(other, Field):
            return False
        cdef Field other_field = <Field>other
        return self.c_field.get().equals(other_field.c_field)

    def __eq__(self, other):
        return self.equals(other)

    def __str__(self) -> str:
        return self.c_field.get().to_string().decode('utf-8')

    def __repr__(self) -> str:
        return f"Field({self})"


cdef class Schema:
    """A collection of named fields defining a row structure."""
    cdef shared_ptr[CSchema] c_schema

    def __init__(self, fields):
        """Create a new schema from a list of fields.

        Args:
            fields: List of Field objects.
        """
        cdef vector[shared_ptr[CField]] c_fields
        for f in fields:
            c_fields.push_back((<Field>f).c_field)
        self.c_schema = fory_schema(c_fields)

    @staticmethod
    cdef Schema wrap(shared_ptr[CSchema] c_schema):
        cdef Schema schema = Schema.__new__(Schema)
        schema.c_schema = c_schema
        return schema

    @property
    def num_fields(self) -> int:
        """Returns the number of fields in the schema."""
        return self.c_schema.get().num_fields()

    def field(self, int i):
        """Returns the i-th field in the schema."""
        cdef shared_ptr[CField] c_field = self.c_schema.get().field(i)
        if c_field.get() == NULL:
            return None
        return Field.wrap(c_field)

    @property
    def fields(self) -> list:
        """Returns all fields in the schema."""
        cdef vector[shared_ptr[CField]] c_fields = self.c_schema.get().fields()
        return [Field.wrap(f) for f in c_fields]

    @property
    def names(self) -> list:
        """Returns the names of all fields."""
        cdef vector[c_string] c_names = self.c_schema.get().field_names()
        return [n.decode('utf-8') for n in c_names]

    def get_field_by_name(self, str name):
        """Returns the field with the given name, or None if not found."""
        cdef c_string c_name = name.encode('utf-8')
        cdef shared_ptr[CField] c_field = self.c_schema.get().get_field_by_name(c_name)
        if c_field.get() == NULL:
            return None
        return Field.wrap(c_field)

    def get_field_index(self, str name) -> int:
        """Returns the index of the field with the given name, or -1 if not found."""
        cdef c_string c_name = name.encode('utf-8')
        return self.c_schema.get().get_field_index(c_name)

    def equals(self, other) -> bool:
        """Check if this schema equals another schema."""
        if not isinstance(other, Schema):
            return False
        cdef Schema other_schema = <Schema>other
        return self.c_schema.get().equals(other_schema.c_schema)

    def __eq__(self, other):
        return self.equals(other)

    def __len__(self):
        return self.num_fields

    def __getitem__(self, key):
        if isinstance(key, int):
            return self.field(key)
        elif isinstance(key, str):
            return self.get_field_by_name(key)
        raise TypeError(f"Schema indices must be int or str, not {type(key).__name__}")

    def __str__(self) -> str:
        return self.c_schema.get().to_string().decode('utf-8')

    def __repr__(self) -> str:
        return f"Schema({self})"

    def to_bytes(self) -> bytes:
        """Serialize this schema to bytes.

        Returns:
            bytes: The serialized schema.
        """
        cdef vector[uint8_t] c_bytes = self.c_schema.get().to_bytes()
        return bytes(c_bytes)

    @staticmethod
    def from_bytes(data) -> Schema:
        """Deserialize a schema from bytes.

        Args:
            data: bytes containing serialized schema.

        Returns:
            Schema: The deserialized schema.
        """
        cdef const uint8_t* data_ptr
        cdef Py_ssize_t data_len
        cdef vector[uint8_t] c_bytes
        if isinstance(data, bytes):
            data_ptr = <const uint8_t*>(<bytes>data)
            data_len = len(<bytes>data)
        else:
            py_bytes = bytes(data)
            data_ptr = <const uint8_t*>py_bytes
            data_len = len(py_bytes)
        c_bytes.assign(data_ptr, data_ptr + data_len)
        return Schema.wrap(CSchema.from_bytes(c_bytes))


# Factory functions for creating types

def boolean():
    """Create a boolean type."""
    return DataType.wrap(c_boolean())

def int8():
    """Create a signed 8-bit integer type."""
    return DataType.wrap(c_int8())

def int16():
    """Create a signed 16-bit integer type."""
    return DataType.wrap(c_int16())

def int32():
    """Create a signed 32-bit integer type."""
    return DataType.wrap(c_int32())

def int64():
    """Create a signed 64-bit integer type."""
    return DataType.wrap(c_int64())

def float16():
    """Create a 16-bit floating point type."""
    return DataType.wrap(c_float16())

def bfloat16():
    """Create a 16-bit brain floating point type."""
    # TODO: Use c_bfloat16() when C++ row format supports bfloat16
    # For now, use float16 as a temporary workaround since C++ doesn't have bfloat16() yet
    return DataType.wrap(c_float16())

def float32():
    """Create a 32-bit floating point type."""
    return DataType.wrap(c_float32())

def float64():
    """Create a 64-bit floating point type."""
    return DataType.wrap(c_float64())

def utf8():
    """Create a UTF-8 string type."""
    return DataType.wrap(c_utf8())

def string():
    """Create a UTF-8 string type (alias for utf8)."""
    return DataType.wrap(c_utf8())

def binary():
    """Create a binary data type."""
    return DataType.wrap(c_binary())

def duration():
    """Create a duration type."""
    return DataType.wrap(c_duration())

def timestamp():
    """Create a timestamp type."""
    return DataType.wrap(c_timestamp())

def date32():
    """Create a date type (days since epoch)."""
    return DataType.wrap(c_date32())

def decimal(int precision=38, int scale=18):
    """Create a decimal type with given precision and scale."""
    return DataType.wrap(c_decimal(precision, scale))

def list_(value_type):
    """Create a list type with the given element type.

    Args:
        value_type: DataType for list elements.
    """
    cdef DataType vtype = <DataType>value_type
    return ListType.wrap_list(fory_list(vtype.c_type))

def map_(key_type, item_type, bint keys_sorted=False):
    """Create a map type with the given key and value types.

    Args:
        key_type: DataType for map keys.
        item_type: DataType for map values.
        keys_sorted: Whether map keys are sorted.
    """
    cdef DataType ktype = <DataType>key_type
    cdef DataType itype = <DataType>item_type
    return MapType.wrap_map(fory_map(ktype.c_type, itype.c_type, keys_sorted))

def struct(fields):
    """Create a struct type with the given fields.

    Args:
        fields: List of Field objects.
    """
    cdef vector[shared_ptr[CField]] c_fields
    for f in fields:
        c_fields.push_back((<Field>f).c_field)
    cdef shared_ptr[CDataType] c_struct = struct_(c_fields)
    # Need to cast to StructType
    cdef StructType dtype = StructType.__new__(StructType)
    dtype.c_type = c_struct
    return dtype

def field(str name, dtype, bint nullable=True):
    """Create a field with the given name and type.

    Args:
        name: Field name.
        dtype: Data type of the field.
        nullable: Whether the field can contain null values.
    """
    return Field(name, dtype, nullable)

def schema(fields):
    """Create a schema from a list of fields.

    Args:
        fields: List of Field objects.
    """
    return Schema(fields)

def get_byte_width(dtype) -> int:
    """Returns the byte width of a fixed-width type, or -1 for variable-width."""
    cdef DataType dt = <DataType>dtype
    return c_get_byte_width(dt.c_type)
