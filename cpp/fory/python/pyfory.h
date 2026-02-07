/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#pragma once
#include "Python.h"
#include "fory/util/buffer.h"

namespace fory {
int Fory_PyBooleanSequenceWriteToBuffer(PyObject *collection, Buffer *buffer,
                                        Py_ssize_t start_index);
int Fory_PyFloatSequenceWriteToBuffer(PyObject *collection, Buffer *buffer,
                                      Py_ssize_t start_index);
int Fory_PyInt64SequenceWriteVarintToBuffer(PyObject *collection,
                                            Buffer *buffer);
int Fory_PyStringSequenceWriteToBuffer(PyObject *collection, Buffer *buffer);

enum ForyPyStringMapValueKind : int {
  kForyPyStringMapValueNone = 0,
  kForyPyStringMapValueInt64 = 1,
  kForyPyStringMapValueString = 2,
};

enum ForyPySequenceValueKind : int {
  kForyPySequenceValueNone = 0,
  kForyPySequenceValueString = 1,
  kForyPySequenceValueInt64 = 2,
  kForyPySequenceValueBool = 3,
  kForyPySequenceValueFloat64 = 4,
};

int Fory_PyDetectSequenceNoNullExactTypeKind(PyObject *collection);

int Fory_PyDetectStringKeyMapValueKind(PyObject *map);
int Fory_PyStringInt64MapWriteChunkToBuffer(PyObject *map, Py_ssize_t *pos,
                                            Py_ssize_t chunk_size,
                                            Buffer *buffer);
int Fory_PyStringStringMapWriteChunkToBuffer(PyObject *map, Py_ssize_t *pos,
                                             Py_ssize_t chunk_size,
                                             Buffer *buffer);
int Fory_PyStringInt64MapWriteContiguousChunkToBuffer(
    PyObject *map, Py_ssize_t *pos, PyObject *first_key, PyObject *first_value,
    Py_ssize_t max_chunk_size, Buffer *buffer, Py_ssize_t *written_chunk_size,
    int *has_next, int64_t *next_key_addr, int64_t *next_value_addr);
int Fory_PyStringStringMapWriteContiguousChunkToBuffer(
    PyObject *map, Py_ssize_t *pos, PyObject *first_key, PyObject *first_value,
    Py_ssize_t max_chunk_size, Buffer *buffer, Py_ssize_t *written_chunk_size,
    int *has_next, int64_t *next_key_addr, int64_t *next_value_addr);
int Fory_PyStringInt64MapReadChunkFromBuffer(PyObject *map,
                                             Py_ssize_t chunk_size,
                                             Buffer *buffer);
int Fory_PyStringStringMapReadChunkFromBuffer(PyObject *map,
                                              Py_ssize_t chunk_size,
                                              Buffer *buffer);

int Fory_PyBooleanSequenceReadFromBuffer(PyObject *collection, Buffer *buffer,
                                         Py_ssize_t size);
int Fory_PyFloatSequenceReadFromBuffer(PyObject *collection, Buffer *buffer,
                                       Py_ssize_t size);
int Fory_PyInt64SequenceReadVarintFromBuffer(PyObject *collection,
                                             Buffer *buffer, Py_ssize_t size);
} // namespace fory
