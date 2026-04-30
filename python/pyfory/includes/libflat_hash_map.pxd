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
#

from libcpp.utility cimport pair

cdef extern from "fory/thirdparty/flat_hash_map.h" namespace "fory" nogil:
    cdef cppclass flat_hash_map[T, U]:
        ctypedef T key_type
        ctypedef U mapped_type
        ctypedef pair[T, U] value_type
        flat_hash_map() except +
        flat_hash_map(flat_hash_map&) except +
        U& operator[](const T&)
        void clear()
        bint empty()
        pair[T, U]* find(const T&)
        size_t size()
        void swap(flat_hash_map&)
        void max_load_factor(float)
        float max_load_factor()
        void rehash(size_t)
        void reserve(size_t)
        size_t bucket_count()
