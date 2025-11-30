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
        BOOL = 1
        INT8 = 2
        INT16 = 3
        INT32 = 4
        VAR_INT32 = 5
        INT64 = 6
        VAR_INT64 = 7
        SLI_INT64 = 8
        FLOAT16 = 9
        FLOAT32 = 10
        FLOAT64 = 11
        STRING = 12
        ENUM = 13
        NAMED_ENUM = 14
        STRUCT = 15
        COMPATIBLE_STRUCT = 16
        NAMED_STRUCT = 17
        NAMED_COMPATIBLE_STRUCT = 18
        EXT = 19
        NAMED_EXT = 20
        LIST = 21
        SET = 22
        MAP = 23
        DURATION = 24
        TIMESTAMP = 25
        LOCAL_DATE = 26
        DECIMAL = 27
        BINARY = 28
        ARRAY = 29
        BOOL_ARRAY = 30
        INT8_ARRAY = 31
        INT16_ARRAY = 32
        INT32_ARRAY = 33
        INT64_ARRAY = 34
        FLOAT16_ARRAY = 35
        FLOAT32_ARRAY = 36
        FLOAT64_ARRAY = 37
        BOUND = 64

    cdef c_bool IsNamespacedType(int32_t type_id)
    cdef c_bool IsTypeShareMeta(int32_t type_id)

cdef extern from "fory/python/pyfory.h" namespace "fory":
    int Fory_PyBooleanSequenceWriteToBuffer(object collection, CBuffer *buffer, Py_ssize_t start_index)
    int Fory_PyFloatSequenceWriteToBuffer(object collection, CBuffer *buffer, Py_ssize_t start_index)
