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

from pyfory.lib import mmh3
from pyfory._fory import (
    Fory,
    ThreadSafeFory,
)

try:
    from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
except ImportError:
    ENABLE_FORY_CYTHON_SERIALIZATION = False

from pyfory.registry import TypeInfo

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import Fory, TypeInfo  # noqa: F401,F811

from pyfory.serializer import (  # noqa: F401 # pylint: disable=unused-import
    Serializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    Varint32Serializer,
    Varint64Serializer,
    TaggedInt64Serializer,
    Uint8Serializer,
    Uint16Serializer,
    Uint32Serializer,
    VarUint32Serializer,
    Uint64Serializer,
    VarUint64Serializer,
    TaggedUint64Serializer,
    Float32Serializer,
    Float64Serializer,
    BFloat16Serializer,
    StringSerializer,
    DateSerializer,
    TimestampSerializer,
    CollectionSerializer,
    ListSerializer,
    TupleSerializer,
    StringArraySerializer,
    SetSerializer,
    MapSerializer,
    EnumSerializer,
    SliceSerializer,
    FunctionSerializer,
    TypeSerializer,
    MethodSerializer,
    ReduceSerializer,
    StatefulSerializer,
)
from pyfory.struct import DataClassSerializer
from pyfory.field import dataclass, field  # noqa: F401 # pylint: disable=unused-import
from pyfory.types import (  # noqa: F401 # pylint: disable=unused-import
    TypeId,
    Ref,
    int8,
    int16,
    int32,
    int64,
    fixed_int32,
    fixed_int64,
    tagged_int64,
    uint8,
    uint16,
    uint32,
    fixed_uint32,
    uint64,
    fixed_uint64,
    tagged_uint64,
    float32,
    float64,
    bfloat16,
    bfloat16_array,
    int8_array,
    uint8_array,
    int16_array,
    int32_array,
    int64_array,
    uint16_array,
    uint32_array,
    uint64_array,
    float32_array,
    float64_array,
    bool_ndarray,
    int8_ndarray,
    uint8_ndarray,
    int16_ndarray,
    int32_ndarray,
    int64_ndarray,
    uint16_ndarray,
    uint32_ndarray,
    uint64_ndarray,
    float32_ndarray,
    float64_ndarray,
)
from pyfory.type_util import (  # noqa: F401 # pylint: disable=unused-import
    record_class_factory,
    get_qualified_classname,
    dataslots,
)
from pyfory.policy import DeserializationPolicy  # noqa: F401 # pylint: disable=unused-import
from pyfory.buffer import Buffer  # noqa: F401 # pylint: disable=unused-import

# BFloat16 support
from pyfory.bfloat16 import bfloat16  # noqa: F401
from pyfory.bfloat16_array import BFloat16Array  # noqa: F401
# Keep compatibility with existing API naming.
BFloat16 = bfloat16

__version__ = "0.16.0.dev0"

__all__ = [
    # Core classes
    "Fory",
    "ThreadSafeFory",
    "TypeInfo",
    "Buffer",
    "DeserializationPolicy",
    # Field metadata
    "field",
    "dataclass",
    # Type utilities
    "record_class_factory",
    "get_qualified_classname",
    "TypeId",
    "Ref",
    "int8",
    "int16",
    "int32",
    "int64",
    "fixed_int32",
    "fixed_int64",
    "tagged_int64",
    "uint8",
    "uint16",
    "uint32",
    "fixed_uint32",
    "uint64",
    "fixed_uint64",
    "tagged_uint64",
    "float32",
    "float64",
    "BFloat16",
    "BFloat16Array",
    "bfloat16",
    "bfloat16_array",
    "int8_array",
    "uint8_array",
    "int16_array",
    "int32_array",
    "int64_array",
    "uint16_array",
    "uint32_array",
    "uint64_array",
    "float32_array",
    "float64_array",
    "bool_ndarray",
    "int8_ndarray",
    "uint8_ndarray",
    "int16_ndarray",
    "int32_ndarray",
    "int64_ndarray",
    "uint16_ndarray",
    "uint32_ndarray",
    "uint64_ndarray",
    "float32_ndarray",
    "float64_ndarray",
    "dataslots",
    # Serializers
    "Serializer",
    "BooleanSerializer",
    "ByteSerializer",
    "Int16Serializer",
    "Int32Serializer",
    "Int64Serializer",
    "Varint32Serializer",
    "Varint64Serializer",
    "TaggedInt64Serializer",
    "Uint8Serializer",
    "Uint16Serializer",
    "Uint32Serializer",
    "VarUint32Serializer",
    "Uint64Serializer",
    "VarUint64Serializer",
    "TaggedUint64Serializer",
    "Float32Serializer",
    "Float64Serializer",
    "BFloat16Serializer",
    "StringSerializer",
    "DateSerializer",
    "TimestampSerializer",
    "CollectionSerializer",
    "ListSerializer",
    "TupleSerializer",
    "StringArraySerializer",
    "SetSerializer",
    "MapSerializer",
    "EnumSerializer",
    "SliceSerializer",
    "DataClassSerializer",
    "FunctionSerializer",
    "TypeSerializer",
    "MethodSerializer",
    "ReduceSerializer",
    "StatefulSerializer",
    "mmh3",
    # Version
    "__version__",
]

# Try to import format utilities (requires pyarrow)
import warnings

try:
    with warnings.catch_warnings():
        warnings.filterwarnings("ignore", category=RuntimeWarning)
        from pyfory.format import (  # noqa: F401 # pylint: disable=unused-import
            create_row_encoder,
            RowData,
            encoder,
            Encoder,
        )

        __all__.extend(
            [
                "format",
                "create_row_encoder",
                "RowData",
                "encoder",
                "Encoder",
            ]
        )
except (AttributeError, ImportError):
    pass
