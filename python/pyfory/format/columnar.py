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
Pure Python implementation for converting Fory row format to Apache Arrow
columnar format. This module provides ArrowWriter class that accumulates
row data and converts it to Arrow RecordBatch.
"""

import pyarrow as pa
from pyarrow import types


def _create_array_appender(data_type):
    """Factory function to create appropriate array appender for a given Arrow type."""
    if types.is_boolean(data_type):
        return BooleanArrayAppender()
    elif types.is_int8(data_type):
        return Int8ArrayAppender()
    elif types.is_int16(data_type):
        return Int16ArrayAppender()
    elif types.is_int32(data_type):
        return Int32ArrayAppender()
    elif types.is_int64(data_type):
        return Int64ArrayAppender()
    elif types.is_float32(data_type):
        return FloatArrayAppender()
    elif types.is_float64(data_type):
        return DoubleArrayAppender()
    elif types.is_date32(data_type):
        return DateArrayAppender()
    elif types.is_time32(data_type):
        return Time32ArrayAppender(data_type)
    elif types.is_time64(data_type):
        return Time64ArrayAppender(data_type)
    elif types.is_timestamp(data_type):
        return TimestampArrayAppender(data_type)
    elif types.is_binary(data_type):
        return BinaryArrayAppender()
    elif types.is_string(data_type) or types.is_large_string(data_type):
        return StringArrayAppender()
    elif types.is_list(data_type):
        elem_appender = _create_array_appender(data_type.value_type)
        return ListArrayAppender(data_type, elem_appender)
    elif types.is_struct(data_type):
        field_appenders = [_create_array_appender(data_type.field(i).type) for i in range(data_type.num_fields)]
        return StructArrayAppender(data_type, field_appenders)
    elif types.is_map(data_type):
        key_appender = _create_array_appender(data_type.key_type)
        item_appender = _create_array_appender(data_type.item_type)
        return MapArrayAppender(data_type, key_appender, item_appender)
    else:
        raise NotImplementedError(f"Unsupported type: {data_type}")


def _get_value_reader(data_type):
    """Return the appropriate getter method name for the data type."""
    if types.is_boolean(data_type):
        return "get_boolean"
    elif types.is_int8(data_type):
        return "get_int8"
    elif types.is_int16(data_type):
        return "get_int16"
    elif types.is_int32(data_type):
        return "get_int32"
    elif types.is_int64(data_type):
        return "get_int64"
    elif types.is_float32(data_type):
        return "get_float"
    elif types.is_float64(data_type):
        return "get_double"
    elif types.is_date32(data_type):
        return "get_date"
    elif types.is_time32(data_type):
        return "get_int32"
    elif types.is_time64(data_type):
        return "get_int64"
    elif types.is_timestamp(data_type):
        return "get_datetime"
    elif types.is_binary(data_type):
        return "get_binary"
    elif types.is_string(data_type) or types.is_large_string(data_type):
        return "get_str"
    elif types.is_list(data_type):
        return "get_array_data"
    elif types.is_struct(data_type):
        return "get_struct"
    elif types.is_map(data_type):
        return "get_map_data"
    else:
        raise NotImplementedError(f"Unsupported type: {data_type}")


class ArrayAppender:
    """Base class for array appenders."""

    def append(self, value):
        """Append a value (can be None for null)."""
        raise NotImplementedError

    def finish(self):
        """Finish building and return the Arrow array."""
        raise NotImplementedError

    def reset(self):
        """Reset the builder for reuse."""
        raise NotImplementedError


class BooleanArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.bool_())

    def reset(self):
        self._values = []


class Int8ArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.int8())

    def reset(self):
        self._values = []


class Int16ArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.int16())

    def reset(self):
        self._values = []


class Int32ArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.int32())

    def reset(self):
        self._values = []


class Int64ArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.int64())

    def reset(self):
        self._values = []


class FloatArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.float32())

    def reset(self):
        self._values = []


class DoubleArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.float64())

    def reset(self):
        self._values = []


class DateArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.date32())

    def reset(self):
        self._values = []


class Time32ArrayAppender(ArrayAppender):
    def __init__(self, data_type):
        self._type = data_type
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=self._type)

    def reset(self):
        self._values = []


class Time64ArrayAppender(ArrayAppender):
    def __init__(self, data_type):
        self._type = data_type
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=self._type)

    def reset(self):
        self._values = []


class TimestampArrayAppender(ArrayAppender):
    def __init__(self, data_type):
        self._type = data_type
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=self._type)

    def reset(self):
        self._values = []


class BinaryArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.binary())

    def reset(self):
        self._values = []


class StringArrayAppender(ArrayAppender):
    def __init__(self):
        self._values = []

    def append(self, value):
        self._values.append(value)

    def finish(self):
        return pa.array(self._values, type=pa.string())

    def reset(self):
        self._values = []


class ListArrayAppender(ArrayAppender):
    def __init__(self, data_type, elem_appender):
        self._type = data_type
        self._elem_appender = elem_appender
        self._elem_reader = _get_value_reader(data_type.value_type)
        self._offsets = [0]
        self._null_bitmap = []

    def append(self, array_data):
        if array_data is None:
            self._offsets.append(self._offsets[-1])
            self._null_bitmap.append(False)
        else:
            num_elements = array_data.num_elements
            reader = getattr(array_data, self._elem_reader)
            for j in range(num_elements):
                value = reader(j)
                self._elem_appender.append(value)
            self._offsets.append(self._offsets[-1] + num_elements)
            self._null_bitmap.append(True)

    def finish(self):
        values = self._elem_appender.finish()
        offsets = pa.array(self._offsets, type=pa.int32())
        null_mask = pa.array([not v for v in self._null_bitmap], type=pa.bool_())
        return pa.ListArray.from_arrays(offsets, values, mask=null_mask)

    def reset(self):
        self._offsets = [0]
        self._null_bitmap = []
        self._elem_appender.reset()


class StructArrayAppender(ArrayAppender):
    def __init__(self, data_type, field_appenders):
        self._type = data_type
        self._field_appenders = field_appenders
        self._field_readers = [_get_value_reader(data_type.field(i).type) for i in range(data_type.num_fields)]
        self._null_bitmap = []

    def append(self, struct_data):
        if struct_data is None:
            self._null_bitmap.append(False)
            for appender in self._field_appenders:
                appender.append(None)
        else:
            num_fields = struct_data.num_fields
            for j in range(num_fields):
                reader = getattr(struct_data, self._field_readers[j])
                value = reader(j)
                self._field_appenders[j].append(value)
            self._null_bitmap.append(True)

    def finish(self):
        arrays = [appender.finish() for appender in self._field_appenders]
        names = [self._type.field(i).name for i in range(self._type.num_fields)]
        null_mask = pa.array([not v for v in self._null_bitmap], type=pa.bool_())
        return pa.StructArray.from_arrays(arrays, names=names, mask=null_mask)

    def reset(self):
        self._null_bitmap = []
        for appender in self._field_appenders:
            appender.reset()


class MapArrayAppender(ArrayAppender):
    def __init__(self, data_type, key_appender, item_appender):
        self._type = data_type
        self._key_appender = key_appender
        self._item_appender = item_appender
        self._key_reader = _get_value_reader(data_type.key_type)
        self._item_reader = _get_value_reader(data_type.item_type)
        self._offsets = [0]
        self._null_bitmap = []

    def append(self, map_data):
        if map_data is None:
            self._offsets.append(self._offsets[-1])
            self._null_bitmap.append(False)
        else:
            num_elements = map_data.num_elements
            keys_array = map_data.keys_array()
            values_array = map_data.values_array()
            key_reader = getattr(keys_array, self._key_reader)
            item_reader = getattr(values_array, self._item_reader)
            for j in range(num_elements):
                self._key_appender.append(key_reader(j))
                self._item_appender.append(item_reader(j))
            self._offsets.append(self._offsets[-1] + num_elements)
            self._null_bitmap.append(True)

    def finish(self):
        keys = self._key_appender.finish()
        items = self._item_appender.finish()
        offsets = pa.array(self._offsets, type=pa.int32())
        return pa.MapArray.from_arrays(offsets, keys, items)

    def reset(self):
        self._offsets = [0]
        self._null_bitmap = []
        self._key_appender.reset()
        self._item_appender.reset()


class ArrowWriter:
    """
    Converts Fory row format data to Apache Arrow columnar format.

    This class accumulates rows and produces an Arrow RecordBatch.

    Example:
        >>> schema = pa.schema([("f1", pa.int64()), ("f2", pa.string())])
        >>> writer = ArrowWriter(schema)
        >>> encoder = create_row_encoder(schema)
        >>> for obj in objects:
        ...     row = encoder.to_row(obj)
        ...     writer.write(row)
        >>> record_batch = writer.finish()
    """

    def __init__(self, schema, pool=None):
        """
        Initialize ArrowWriter with the given schema.

        Args:
            schema: PyArrow Schema defining the structure of the data.
            pool: Memory pool (unused, kept for API compatibility).
        """
        self._schema = schema
        self._column_appenders = []
        self._column_readers = []
        self._num_rows = 0

        for i in range(len(schema)):
            field_type = schema.field(i).type
            self._column_appenders.append(_create_array_appender(field_type))
            self._column_readers.append(_get_value_reader(field_type))

    def write(self, row):
        """
        Write a row to the writer.

        Args:
            row: A RowData instance containing the row data.
        """
        num_fields = row.num_fields
        for i in range(num_fields):
            reader_method = getattr(row, self._column_readers[i])
            value = reader_method(i)
            self._column_appenders[i].append(value)
        self._num_rows += 1

    def finish(self):
        """
        Finish writing and return the RecordBatch.

        Returns:
            An Arrow RecordBatch containing all written rows.
        """
        columns = [appender.finish() for appender in self._column_appenders]
        return pa.RecordBatch.from_arrays(columns, schema=self._schema)

    def reset(self):
        """Reset the writer for reuse."""
        self._num_rows = 0
        for appender in self._column_appenders:
            appender.reset()
