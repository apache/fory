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
    Language,
    ThreadSafeFory,
)

PYTHON = Language.PYTHON
XLANG = Language.XLANG

try:
    from pyfory.serialization import ENABLE_FORY_CYTHON_SERIALIZATION
except ImportError:
    ENABLE_FORY_CYTHON_SERIALIZATION = False

from pyfory._registry import TypeInfo

if ENABLE_FORY_CYTHON_SERIALIZATION:
    from pyfory.serialization import Fory, TypeInfo  # noqa: F401,F811

from pyfory.serializer import (  # noqa: F401 # pylint: disable=unused-import
    Serializer,
    XlangCompatibleSerializer,
    BooleanSerializer,
    ByteSerializer,
    Int16Serializer,
    Int32Serializer,
    Int64Serializer,
    Float32Serializer,
    Float64Serializer,
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
    DataClassSerializer,
    FunctionSerializer,
    TypeSerializer,
    MethodSerializer,
    ReduceSerializer,
    StatefulSerializer,
)
from pyfory.type import (  # noqa: F401 # pylint: disable=unused-import
    record_class_factory,
    get_qualified_classname,
    TypeId,
    int8,
    int16,
    int32,
    int64,
    float32,
    float64,
    # Int8ArrayType,
    int16_array,
    int32_array,
    int64_array,
    float32_array,
    float64_array,
    dataslots,
)
from pyfory.policy import DeserializationPolicy  # noqa: F401 # pylint: disable=unused-import
from pyfory._util import Buffer  # noqa: F401 # pylint: disable=unused-import

__version__ = "0.14.1"

__all__ = [
    # Core classes
    "Fory",
    "Language",
    "ThreadSafeFory",
    "TypeInfo",
    "Buffer",
    "DeserializationPolicy",
    # Language constants
    "PYTHON",
    "XLANG",
    # Type utilities
    "record_class_factory",
    "get_qualified_classname",
    "TypeId",
    "int8",
    "int16",
    "int32",
    "int64",
    "float32",
    "float64",
    "int16_array",
    "int32_array",
    "int64_array",
    "float32_array",
    "float64_array",
    "dataslots",
    # Serializers
    "Serializer",
    "XlangCompatibleSerializer",
    "BooleanSerializer",
    "ByteSerializer",
    "Int16Serializer",
    "Int32Serializer",
    "Int64Serializer",
    "Float32Serializer",
    "Float64Serializer",
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
