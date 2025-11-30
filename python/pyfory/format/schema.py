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
Schema conversion utilities between Apache Arrow and Fory schema types.

This module provides bidirectional conversion between Arrow schema types
and Fory's internal schema representation for the row format.
"""

import pyarrow as pa
from pyarrow import types as pa_types


def arrow_type_to_fory_type_id(arrow_type):
    """
    Convert an Arrow data type to a Fory TypeId value.

    Args:
        arrow_type: A PyArrow DataType instance.

    Returns:
        int: The corresponding Fory TypeId value.

    Raises:
        NotImplementedError: If the Arrow type is not supported.
    """
    # Boolean
    if pa_types.is_boolean(arrow_type):
        return 1  # BOOL

    # Integer types
    if pa_types.is_int8(arrow_type):
        return 2  # INT8
    if pa_types.is_int16(arrow_type):
        return 3  # INT16
    if pa_types.is_int32(arrow_type):
        return 4  # INT32
    if pa_types.is_int64(arrow_type):
        return 6  # INT64

    # Floating point types
    if pa_types.is_float16(arrow_type):
        return 9  # FLOAT16
    if pa_types.is_float32(arrow_type):
        return 10  # FLOAT32
    if pa_types.is_float64(arrow_type):
        return 11  # FLOAT64

    # String and binary
    if pa_types.is_string(arrow_type) or pa_types.is_large_string(arrow_type):
        return 12  # STRING
    if pa_types.is_binary(arrow_type) or pa_types.is_large_binary(arrow_type):
        return 28  # BINARY

    # Date/time types
    if pa_types.is_date32(arrow_type):
        return 26  # LOCAL_DATE
    if pa_types.is_timestamp(arrow_type):
        return 25  # TIMESTAMP
    if pa_types.is_duration(arrow_type):
        return 24  # DURATION

    # Decimal
    if pa_types.is_decimal(arrow_type):
        return 27  # DECIMAL

    # Complex types
    if pa_types.is_list(arrow_type) or pa_types.is_large_list(arrow_type):
        return 21  # LIST
    if pa_types.is_map(arrow_type):
        return 23  # MAP
    if pa_types.is_struct(arrow_type):
        return 15  # STRUCT

    raise NotImplementedError(f"Unsupported Arrow type: {arrow_type}")


def fory_type_id_to_arrow_type(type_id, precision=None, scale=None, list_type=None, map_key_type=None, map_value_type=None, struct_fields=None):
    """
    Convert a Fory TypeId value to an Arrow data type.

    Args:
        type_id: The Fory TypeId value (int).
        precision: Precision for decimal types.
        scale: Scale for decimal types.
        list_type: Value type for list types (Arrow type).
        map_key_type: Key type for map types (Arrow type).
        map_value_type: Value type for map types (Arrow type).
        struct_fields: List of (name, type) tuples for struct types.

    Returns:
        A PyArrow DataType instance.

    Raises:
        NotImplementedError: If the Fory type is not supported.
    """
    type_map = {
        1: pa.bool_(),  # BOOL
        2: pa.int8(),  # INT8
        3: pa.int16(),  # INT16
        4: pa.int32(),  # INT32
        6: pa.int64(),  # INT64
        9: pa.float16(),  # FLOAT16
        10: pa.float32(),  # FLOAT32
        11: pa.float64(),  # FLOAT64
        12: pa.utf8(),  # STRING
        24: pa.duration("ns"),  # DURATION
        25: pa.timestamp("us"),  # TIMESTAMP
        26: pa.date32(),  # LOCAL_DATE
        28: pa.binary(),  # BINARY
    }

    if type_id in type_map:
        return type_map[type_id]

    # Decimal
    if type_id == 27:  # DECIMAL
        return pa.decimal128(precision or 38, scale or 18)

    # List
    if type_id == 21:  # LIST
        if list_type is None:
            raise ValueError("list_type must be provided for LIST type")
        return pa.list_(list_type)

    # Map
    if type_id == 23:  # MAP
        if map_key_type is None or map_value_type is None:
            raise ValueError("map_key_type and map_value_type must be provided for MAP type")
        return pa.map_(map_key_type, map_value_type)

    # Struct
    if type_id == 15:  # STRUCT
        if struct_fields is None:
            raise ValueError("struct_fields must be provided for STRUCT type")
        return pa.struct(struct_fields)

    raise NotImplementedError(f"Unsupported Fory type ID: {type_id}")


def arrow_schema_to_fory_field_list(arrow_schema):
    """
    Convert an Arrow schema to a list of field specifications for Fory.

    Args:
        arrow_schema: A PyArrow Schema instance.

    Returns:
        list: A list of dictionaries with field specifications.
    """
    fields = []
    for i in range(len(arrow_schema)):
        field = arrow_schema.field(i)
        field_spec = {
            "name": field.name,
            "type_id": arrow_type_to_fory_type_id(field.type),
            "nullable": field.nullable,
            "arrow_type": field.type,
        }
        # Handle nested types
        if pa_types.is_list(field.type):
            field_spec["value_type"] = field.type.value_type
        elif pa_types.is_map(field.type):
            field_spec["key_type"] = field.type.key_type
            field_spec["item_type"] = field.type.item_type
        elif pa_types.is_struct(field.type):
            field_spec["struct_fields"] = [(field.type.field(j).name, field.type.field(j).type) for j in range(field.type.num_fields)]
        elif pa_types.is_decimal(field.type):
            field_spec["precision"] = field.type.precision
            field_spec["scale"] = field.type.scale
        fields.append(field_spec)
    return fields


def fory_field_list_to_arrow_schema(field_list):
    """
    Convert a list of Fory field specifications to an Arrow schema.

    Args:
        field_list: A list of dictionaries with field specifications.

    Returns:
        A PyArrow Schema instance.
    """
    arrow_fields = []
    for field_spec in field_list:
        name = field_spec["name"]
        type_id = field_spec["type_id"]
        nullable = field_spec.get("nullable", True)

        # Handle nested types
        if type_id == 21:  # LIST
            value_type = field_spec.get("value_type")
            arrow_type = pa.list_(value_type)
        elif type_id == 23:  # MAP
            key_type = field_spec.get("key_type")
            item_type = field_spec.get("item_type")
            arrow_type = pa.map_(key_type, item_type)
        elif type_id == 15:  # STRUCT
            struct_fields = field_spec.get("struct_fields", [])
            arrow_type = pa.struct(struct_fields)
        elif type_id == 27:  # DECIMAL
            precision = field_spec.get("precision", 38)
            scale = field_spec.get("scale", 18)
            arrow_type = pa.decimal128(precision, scale)
        else:
            arrow_type = fory_type_id_to_arrow_type(type_id)

        arrow_fields.append(pa.field(name, arrow_type, nullable=nullable))

    return pa.schema(arrow_fields)


def convert_arrow_type_recursive(arrow_type):
    """
    Recursively convert an Arrow type to a serializable specification.

    Args:
        arrow_type: A PyArrow DataType instance.

    Returns:
        dict: A dictionary specification for the type.
    """
    type_id = arrow_type_to_fory_type_id(arrow_type)
    spec = {"type_id": type_id}

    if pa_types.is_list(arrow_type):
        spec["value_type"] = convert_arrow_type_recursive(arrow_type.value_type)
    elif pa_types.is_map(arrow_type):
        spec["key_type"] = convert_arrow_type_recursive(arrow_type.key_type)
        spec["item_type"] = convert_arrow_type_recursive(arrow_type.item_type)
    elif pa_types.is_struct(arrow_type):
        spec["fields"] = []
        for i in range(arrow_type.num_fields):
            field = arrow_type.field(i)
            spec["fields"].append(
                {
                    "name": field.name,
                    "type": convert_arrow_type_recursive(field.type),
                    "nullable": field.nullable,
                }
            )
    elif pa_types.is_decimal(arrow_type):
        spec["precision"] = arrow_type.precision
        spec["scale"] = arrow_type.scale

    return spec


def reconstruct_arrow_type(spec):
    """
    Reconstruct an Arrow type from a type specification.

    Args:
        spec: A dictionary specification for the type.

    Returns:
        A PyArrow DataType instance.
    """
    type_id = spec["type_id"]

    if type_id == 21:  # LIST
        value_type = reconstruct_arrow_type(spec["value_type"])
        return pa.list_(value_type)
    elif type_id == 23:  # MAP
        key_type = reconstruct_arrow_type(spec["key_type"])
        item_type = reconstruct_arrow_type(spec["item_type"])
        return pa.map_(key_type, item_type)
    elif type_id == 15:  # STRUCT
        fields = []
        for field_spec in spec["fields"]:
            field_type = reconstruct_arrow_type(field_spec["type"])
            fields.append(pa.field(field_spec["name"], field_type, nullable=field_spec.get("nullable", True)))
        return pa.struct(fields)
    elif type_id == 27:  # DECIMAL
        return pa.decimal128(spec.get("precision", 38), spec.get("scale", 18))
    else:
        return fory_type_id_to_arrow_type(type_id)
