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

"""Primitive type system for Fory IDL."""

from enum import Enum as PyEnum


class PrimitiveKind(PyEnum):
    """Primitive type kinds."""

    BOOL = "bool"
    INT8 = "int8"
    INT16 = "int16"
    INT32 = "int32"
    INT64 = "int64"
    VARINT32 = "varint32"
    VARINT64 = "varint64"
    TAGGED_INT64 = "tagged_int64"
    UINT8 = "uint8"
    UINT16 = "uint16"
    UINT32 = "uint32"
    UINT64 = "uint64"
    VAR_UINT32 = "var_uint32"
    VAR_UINT64 = "var_uint64"
    TAGGED_UINT64 = "tagged_uint64"
    FLOAT16 = "float16"
    BFLOAT16 = "bfloat16"
    FLOAT32 = "float32"
    FLOAT64 = "float64"
    STRING = "string"
    BYTES = "bytes"
    DATE = "date"
    TIMESTAMP = "timestamp"
    DURATION = "duration"
    DECIMAL = "decimal"
    ANY = "any"


PRIMITIVE_TYPES = {
    "bool": PrimitiveKind.BOOL,
    "int8": PrimitiveKind.INT8,
    "int16": PrimitiveKind.INT16,
    "int32": PrimitiveKind.VARINT32,
    "int64": PrimitiveKind.VARINT64,
    "fixed_int32": PrimitiveKind.INT32,
    "fixed_int64": PrimitiveKind.INT64,
    "tagged_int64": PrimitiveKind.TAGGED_INT64,
    "uint8": PrimitiveKind.UINT8,
    "uint16": PrimitiveKind.UINT16,
    "uint32": PrimitiveKind.VAR_UINT32,
    "uint64": PrimitiveKind.VAR_UINT64,
    "fixed_uint32": PrimitiveKind.UINT32,
    "fixed_uint64": PrimitiveKind.UINT64,
    "tagged_uint64": PrimitiveKind.TAGGED_UINT64,
    "float16": PrimitiveKind.FLOAT16,
    "bfloat16": PrimitiveKind.BFLOAT16,
    "float32": PrimitiveKind.FLOAT32,
    "float64": PrimitiveKind.FLOAT64,
    "string": PrimitiveKind.STRING,
    "bytes": PrimitiveKind.BYTES,
    "date": PrimitiveKind.DATE,
    "timestamp": PrimitiveKind.TIMESTAMP,
    "duration": PrimitiveKind.DURATION,
    "decimal": PrimitiveKind.DECIMAL,
    "any": PrimitiveKind.ANY,
}


__all__ = ["PrimitiveKind", "PRIMITIVE_TYPES"]
