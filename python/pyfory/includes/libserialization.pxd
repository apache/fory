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

from libc.stdint cimport int32_t
from libcpp cimport bool as c_bool
from pyfory.includes.libutil cimport CBuffer

cdef extern from "fory/type/type.h" namespace "fory" nogil:

    # Declare the C++ TypeId enum
    cdef enum class TypeId(int32_t):
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

    cdef c_bool IsNamespacedType(int32_t type_id)
    cdef c_bool IsTypeShareMeta(int32_t type_id)

cdef extern from "fory/python/pyfory.h" namespace "fory":
    int Fory_PyBooleanSequenceWriteToBuffer(object collection, CBuffer *buffer, Py_ssize_t start_index)
    int Fory_PyFloatSequenceWriteToBuffer(object collection, CBuffer *buffer, Py_ssize_t start_index)
