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

from libc.stdint cimport int32_t, int64_t, uint8_t
from libcpp cimport bool as c_bool
from cpython.object cimport PyObject
from pyfory.includes.libutil cimport CBuffer

cdef extern from "fory/type/type.h" namespace "fory" nogil:

    # Declare the C++ TypeId enum
    cdef enum class TypeId(uint8_t):
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
        FLOAT8 = 16
        FLOAT16 = 17
        BFLOAT16 = 18
        FLOAT32 = 19
        FLOAT64 = 20
        STRING = 21
        LIST = 22
        SET = 23
        MAP = 24
        ENUM = 25
        NAMED_ENUM = 26
        STRUCT = 27
        COMPATIBLE_STRUCT = 28
        NAMED_STRUCT = 29
        NAMED_COMPATIBLE_STRUCT = 30
        EXT = 31
        NAMED_EXT = 32
        UNION = 33
        TYPED_UNION = 34
        NAMED_UNION = 35
        NONE = 36
        DURATION = 37
        TIMESTAMP = 38
        DATE = 39
        DECIMAL = 40
        BINARY = 41
        ARRAY = 42
        BOOL_ARRAY = 43
        INT8_ARRAY = 44
        INT16_ARRAY = 45
        INT32_ARRAY = 46
        INT64_ARRAY = 47
        UINT8_ARRAY = 48
        UINT16_ARRAY = 49
        UINT32_ARRAY = 50
        UINT64_ARRAY = 51
        FLOAT8_ARRAY = 52
        FLOAT16_ARRAY = 53
        BFLOAT16_ARRAY = 54
        FLOAT32_ARRAY = 55
        FLOAT64_ARRAY = 56
        BOUND = 64

    cdef enum class TypeRegistrationKind(int32_t):
        INTERNAL = 0
        BY_ID = 1
        BY_NAME = 2

    cdef TypeRegistrationKind get_type_registration_kind(TypeId type_id)
    cdef c_bool is_namespaced_type(TypeId type_id)
    cdef c_bool is_type_share_meta(TypeId type_id)

cdef extern from "fory/python/pyfory.h" namespace "fory":
    int Fory_PyPrimitiveCollectionWriteToBuffer(object collection, CBuffer *buffer, uint8_t type_id) except -1
    int Fory_PyPrimitiveSequenceWriteToBuffer(PyObject **items, int64_t size, CBuffer *buffer, uint8_t type_id) except -1
    int Fory_PyPrimitiveCollectionReadFromBuffer(object collection, CBuffer *buffer, int64_t size, uint8_t type_id) except -1
