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

from pyfory.serialization import Buffer  # noqa: F401 # pylint: disable=unused-import

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
    Float16Serializer,
    BoolArraySerializer,
    Int8ArraySerializer,
    Int16ArraySerializer,
    Int32ArraySerializer,
    Int64ArraySerializer,
    UInt8ArraySerializer,
    UInt16ArraySerializer,
    UInt32ArraySerializer,
    UInt64ArraySerializer,
    Float16ArraySerializer,
    Float32ArraySerializer,
    Float64ArraySerializer,
    Float32Serializer,
    Float64Serializer,
    StringSerializer,
    DateSerializer,
    TimestampSerializer,
    DurationSerializer,
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
    BFloat16Serializer,
    BFloat16ArraySerializer,
)
from pyfory.struct import DataClassSerializer
from pyfory.field import dataclass, field  # noqa: F401 # pylint: disable=unused-import
from pyfory.annotation import (  # noqa: F401 # pylint: disable=unused-import
    Array,
    BFloat16,
    BFloat16Array,
    Bool,
    BoolArray,
    Float16,
    Float16Array,
    Float32,
    Float32Array,
    Float64,
    Float64Array,
    FixedInt32,
    FixedInt64,
    FixedUInt32,
    FixedUInt64,
    Int16,
    Int16Array,
    Int32,
    Int32Array,
    Int64,
    Int64Array,
    Int8,
    Int8Array,
    NDArray,
    Ref,
    PyArray,
    TaggedInt64,
    TaggedUInt64,
    UInt16,
    UInt16Array,
    UInt32,
    UInt32Array,
    UInt64,
    UInt64Array,
    UInt8,
    UInt8Array,
)
from pyfory.types import (  # noqa: F401 # pylint: disable=unused-import
    TypeId,
)
from pyfory.type_util import (  # noqa: F401 # pylint: disable=unused-import
    record_class_factory,
    get_qualified_classname,
    dataslots,
)
from pyfory.policy import DeserializationPolicy  # noqa: F401 # pylint: disable=unused-import

__version__ = "0.18.0.dev0"

__all__ = [
    # Core classes
    "Fory",
    "ThreadSafeFory",
    "TypeInfo",
    "Buffer",
    "Float16Serializer",
    "BoolArraySerializer",
    "Int8ArraySerializer",
    "Int16ArraySerializer",
    "Int32ArraySerializer",
    "Int64ArraySerializer",
    "UInt8ArraySerializer",
    "UInt16ArraySerializer",
    "UInt32ArraySerializer",
    "UInt64ArraySerializer",
    "Float16ArraySerializer",
    "Float32ArraySerializer",
    "Float64ArraySerializer",
    "BFloat16Serializer",
    "BFloat16ArraySerializer",
    "DeserializationPolicy",
    # Field metadata
    "field",
    "dataclass",
    # Type utilities
    "record_class_factory",
    "get_qualified_classname",
    "TypeId",
    "Ref",
    "Array",
    "NDArray",
    "PyArray",
    "BoolArray",
    "Int8Array",
    "Int16Array",
    "Int32Array",
    "Int64Array",
    "UInt8Array",
    "UInt16Array",
    "UInt32Array",
    "UInt64Array",
    "Float16Array",
    "BFloat16Array",
    "Float32Array",
    "Float64Array",
    "Bool",
    "Int8",
    "Int16",
    "Int32",
    "Int64",
    "UInt8",
    "UInt16",
    "UInt32",
    "UInt64",
    "Float16",
    "BFloat16",
    "Float32",
    "Float64",
    "FixedInt32",
    "FixedInt64",
    "FixedUInt32",
    "FixedUInt64",
    "TaggedInt64",
    "TaggedUInt64",
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
    "StringSerializer",
    "DateSerializer",
    "TimestampSerializer",
    "DurationSerializer",
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
