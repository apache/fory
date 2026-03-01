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
#include <cstdint>
#include <string>

#include "Python.h"
#include "fory/util/buffer.h"

namespace fory {
int Fory_PyPrimitiveCollectionWriteToBuffer(PyObject *collection,
                                            Buffer *buffer, uint8_t type_id);
int Fory_PyPrimitiveSequenceWriteToBuffer(PyObject **items, Py_ssize_t size,
                                          Buffer *buffer, uint8_t type_id);
int Fory_PyPrimitiveCollectionReadFromBuffer(PyObject *collection,
                                             Buffer *buffer, Py_ssize_t size,
                                             uint8_t type_id);
int Fory_PyCreateBufferFromStream(PyObject *stream, uint32_t buffer_size,
                                  Buffer **out, std::string *error_message);
} // namespace fory
