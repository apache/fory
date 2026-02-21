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

#include "fory/python/pyfory.h"

static PyObject **py_sequence_get_items(PyObject *collection) {
  if (PyList_CheckExact(collection)) {
    return ((PyListObject *)collection)->ob_item;
  } else if (PyTuple_CheckExact(collection)) {
    return ((PyTupleObject *)collection)->ob_item;
  }
  return nullptr;
}

namespace fory {
int Fory_PyBooleanSequenceWriteToBuffer(PyObject *collection, Buffer *buffer,
                                        Py_ssize_t start_index) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  for (Py_ssize_t i = 0; i < size; i++) {
    bool b = items[i] == Py_True;
    buffer->unsafe_put(start_index, b);
    start_index += sizeof(bool);
  }
  return 0;
}

int Fory_PyFloatSequenceWriteToBuffer(PyObject *collection, Buffer *buffer,
                                      Py_ssize_t start_index) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  for (Py_ssize_t i = 0; i < size; i++) {
    auto *f = (PyFloatObject *)items[i];
    buffer->unsafe_put(start_index, f->ob_fval);
    start_index += sizeof(double);
  }
  return 0;
}

// Write varint64 with ZigZag encoding inline
// Returns number of bytes written
static inline uint32_t WriteVarint64ZigZag(uint8_t *arr, int64_t value) {
  // ZigZag encoding: (value << 1) ^ (value >> 63)
  uint64_t v = (static_cast<uint64_t>(value) << 1) ^
               (static_cast<uint64_t>(value >> 63));

  if (v < 0x80) {
    arr[0] = static_cast<uint8_t>(v);
    return 1;
  }
  arr[0] = static_cast<uint8_t>((v & 0x7F) | 0x80);
  if (v < 0x4000) {
    arr[1] = static_cast<uint8_t>(v >> 7);
    return 2;
  }
  arr[1] = static_cast<uint8_t>((v >> 7) | 0x80);
  if (v < 0x200000) {
    arr[2] = static_cast<uint8_t>(v >> 14);
    return 3;
  }
  arr[2] = static_cast<uint8_t>((v >> 14) | 0x80);
  if (v < 0x10000000) {
    arr[3] = static_cast<uint8_t>(v >> 21);
    return 4;
  }
  arr[3] = static_cast<uint8_t>((v >> 21) | 0x80);
  if (v < 0x800000000ULL) {
    arr[4] = static_cast<uint8_t>(v >> 28);
    return 5;
  }
  arr[4] = static_cast<uint8_t>((v >> 28) | 0x80);
  if (v < 0x40000000000ULL) {
    arr[5] = static_cast<uint8_t>(v >> 35);
    return 6;
  }
  arr[5] = static_cast<uint8_t>((v >> 35) | 0x80);
  if (v < 0x2000000000000ULL) {
    arr[6] = static_cast<uint8_t>(v >> 42);
    return 7;
  }
  arr[6] = static_cast<uint8_t>((v >> 42) | 0x80);
  if (v < 0x100000000000000ULL) {
    arr[7] = static_cast<uint8_t>(v >> 49);
    return 8;
  }
  arr[7] = static_cast<uint8_t>((v >> 49) | 0x80);
  arr[8] = static_cast<uint8_t>(v >> 56);
  return 9;
}

Py_ssize_t Fory_PyInt64SequenceWriteToBuffer(PyObject *collection,
                                             Buffer *buffer) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  uint32_t start_index = buffer->writer_index();
  uint8_t *data = buffer->data() + start_index;
  Py_ssize_t total_bytes = 0;

  for (Py_ssize_t i = 0; i < size; i++) {
    PyObject *item = items[i];
    int64_t value = PyLong_AsLongLong(item);
    if (value == -1 && PyErr_Occurred()) {
      return -1;
    }
    uint32_t bytes_written = WriteVarint64ZigZag(data + total_bytes, value);
    total_bytes += bytes_written;
  }

  buffer->increase_writer_index(total_bytes);
  return total_bytes;
}

// Read varint64 with ZigZag decoding inline
// Returns the number of bytes read, or 0 on buffer overflow
static inline uint32_t
ReadVarint64ZigZag(const uint8_t *arr, Py_ssize_t remaining, int64_t *result) {
  if (remaining <= 0) {
    return 0;
  }

  uint64_t v = 0;
  uint32_t shift = 0;
  uint32_t bytes_read = 0;

  // Read up to 8 bytes with continuation bit
  for (int i = 0; i < 8; i++) {
    if (bytes_read >= static_cast<uint32_t>(remaining)) {
      return 0; // Buffer overflow
    }
    uint8_t b = arr[bytes_read++];
    v |= static_cast<uint64_t>(b & 0x7F) << shift;
    if ((b & 0x80) == 0) {
      *result = static_cast<int64_t>((v >> 1) ^ (~(v & 1) + 1));
      return bytes_read;
    }
    shift += 7;
  }
  // 9th byte: use all 8 bits (no continuation bit masking)
  if (bytes_read >= static_cast<uint32_t>(remaining)) {
    return 0; // Buffer overflow
  }
  uint8_t b = arr[bytes_read++];
  v |= static_cast<uint64_t>(b) << 56;

  *result = static_cast<int64_t>((v >> 1) ^ (~(v & 1) + 1));
  return bytes_read;
}

Py_ssize_t Fory_PyInt64SequenceReadFromBuffer(PyObject *list, Buffer *buffer,
                                              Py_ssize_t count) {
  if (!PyList_CheckExact(list)) {
    return -1;
  }

  uint32_t start_index = buffer->reader_index();
  const uint8_t *data = buffer->data() + start_index;
  Py_ssize_t remaining = buffer->size() - start_index;
  Py_ssize_t total_bytes = 0;

  for (Py_ssize_t i = 0; i < count; i++) {
    int64_t value;
    uint32_t bytes_read =
        ReadVarint64ZigZag(data + total_bytes, remaining - total_bytes, &value);
    if (bytes_read == 0) {
      PyErr_SetString(PyExc_ValueError, "Buffer overflow reading varint64");
      return -1;
    }
    total_bytes += bytes_read;

    PyObject *py_int = PyLong_FromLongLong(value);
    if (py_int == nullptr) {
      return -1;
    }
    PyList_SET_ITEM(list, i, py_int);
  }

  buffer->increase_reader_index(total_bytes);
  return total_bytes;
}
} // namespace fory
