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

#include <cstdint>
#include <limits>

#include "fory/util/string_util.h"

static PyObject **py_sequence_get_items(PyObject *collection) {
  if (PyList_CheckExact(collection)) {
    return ((PyListObject *)collection)->ob_item;
  } else if (PyTuple_CheckExact(collection)) {
    return ((PyTupleObject *)collection)->ob_item;
  }
  return nullptr;
}

static bool ensure_readable_size(fory::Buffer *buffer, Py_ssize_t size,
                                 Py_ssize_t element_size) {
  if (size < 0 || element_size < 0) {
    return false;
  }
  uint32_t buffer_size = buffer->size();
  uint32_t reader_index = buffer->reader_index();
  if (FORY_PREDICT_FALSE(reader_index > buffer_size)) {
    return false;
  }
  uint64_t element_size_u64 = static_cast<uint64_t>(element_size);
  uint64_t size_u64 = static_cast<uint64_t>(size);
  if (FORY_PREDICT_FALSE(element_size_u64 != 0 &&
                         size_u64 > std::numeric_limits<uint64_t>::max() /
                                        element_size_u64)) {
    return false;
  }
  uint64_t readable = static_cast<uint64_t>(buffer_size - reader_index);
  uint64_t expected = size_u64 * element_size_u64;
  return readable >= expected;
}

static bool py_parse_int64(PyObject *obj, int64_t *out) {
  if (!PyLong_CheckExact(obj)) {
    return false;
  }

  auto *long_obj = reinterpret_cast<PyLongObject *>(obj);
  Py_ssize_t size = Py_SIZE(long_obj);
  if (size == 0) {
    *out = 0;
    return true;
  }
  if (size == 1) {
    *out = static_cast<int64_t>(long_obj->ob_digit[0]);
    return true;
  }
  if (size == -1) {
    *out = -static_cast<int64_t>(long_obj->ob_digit[0]);
    return true;
  }
  if (size == 2 || size == -2) {
    int64_t value = static_cast<int64_t>(long_obj->ob_digit[0]);
    value |= static_cast<int64_t>(long_obj->ob_digit[1]) << PyLong_SHIFT;
    if (size < 0) {
      value = -value;
    }
    *out = value;
    return true;
  }

  int overflow = 0;
  long long value = PyLong_AsLongLongAndOverflow(obj, &overflow);
  if (overflow != 0) {
    PyErr_Clear();
    return false;
  }
  if (value == -1 && PyErr_Occurred()) {
    PyErr_Clear();
    return false;
  }
  *out = static_cast<int64_t>(value);
  return true;
}

static bool py_write_string_to_buffer(PyObject *obj, fory::Buffer *buffer) {
  if (!PyUnicode_CheckExact(obj)) {
    return false;
  }
  Py_ssize_t length = PyUnicode_GET_LENGTH(obj);
  int kind = PyUnicode_KIND(obj);
  const void *str_data = PyUnicode_DATA(obj);
  uint64_t header = 0;
  Py_ssize_t byte_size = 0;
  if (kind == PyUnicode_1BYTE_KIND) {
    byte_size = length;
    header = static_cast<uint64_t>(length) << 2;
  } else if (kind == PyUnicode_2BYTE_KIND) {
    byte_size = length << 1;
    header = (static_cast<uint64_t>(length) << 3) | 1;
  } else {
    str_data = PyUnicode_AsUTF8AndSize(obj, &length);
    if (str_data == nullptr) {
      PyErr_Clear();
      return false;
    }
    byte_size = length;
    header = (static_cast<uint64_t>(byte_size) << 2) | 2;
  }
  if (byte_size < 0 ||
      static_cast<uint64_t>(byte_size) > std::numeric_limits<uint32_t>::max()) {
    return false;
  }
  uint32_t payload_size = static_cast<uint32_t>(byte_size);
  buffer->write_var_uint64(header);
  if (payload_size > 0) {
    buffer->write_bytes(str_data, payload_size);
  }
  return true;
}

static bool py_read_string_from_buffer(fory::Buffer *buffer, PyObject **out) {
  if (out == nullptr) {
    return false;
  }
  fory::Error error;
  uint64_t header = buffer->read_var_uint64(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    return false;
  }
  uint32_t payload_size = static_cast<uint32_t>(header >> 2);
  if (FORY_PREDICT_FALSE(payload_size > buffer->remaining_size())) {
    return false;
  }

  uint32_t reader_index = buffer->reader_index();
  const char *bytes =
      reinterpret_cast<const char *>(buffer->data() + reader_index);
  uint32_t encoding = static_cast<uint32_t>(header & 0b11);
  PyObject *value = nullptr;
  if (encoding == 0) {
    value = PyUnicode_DecodeLatin1(bytes, payload_size, "strict");
  } else if (encoding == 1) {
    if (FORY_PREDICT_FALSE((payload_size & 1) != 0)) {
      return false;
    }
    if (fory::utf16_has_surrogate_pairs(
            reinterpret_cast<const uint16_t *>(bytes), payload_size >> 1)) {
      int utf16_le = -1;
      value = PyUnicode_DecodeUTF16(bytes, payload_size, nullptr, &utf16_le);
    } else {
      value = PyUnicode_FromKindAndData(PyUnicode_2BYTE_KIND, bytes,
                                        payload_size >> 1);
    }
  } else {
    value = PyUnicode_DecodeUTF8(bytes, payload_size, "strict");
  }
  if (value == nullptr) {
    return false;
  }
  buffer->reader_index(reader_index + payload_size);
  *out = value;
  return true;
}

static bool read_var_int64_from_buffer(fory::Buffer *buffer, fory::Error *error,
                                       int64_t *out) {
  if (out == nullptr) {
    return false;
  }
  if (error == nullptr) {
    return false;
  }
  int64_t value = buffer->read_var_int64(*error);
  if (FORY_PREDICT_FALSE(!error->ok())) {
    return false;
  }
  *out = value;
  return true;
}

static void write_var_int64_to_buffer(fory::Buffer *buffer, int64_t value) {
  buffer->write_var_int64(value);
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

int Fory_PyInt64SequenceWriteVarintToBuffer(PyObject *collection,
                                            Buffer *buffer) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  if (size < 0) {
    return -1;
  }
  uint64_t max_write_bytes = static_cast<uint64_t>(size) * 9;
  if (max_write_bytes > std::numeric_limits<uint32_t>::max()) {
    return -1;
  }
  buffer->grow(static_cast<uint32_t>(max_write_bytes));
  uint32_t writer_index = buffer->writer_index();
  for (Py_ssize_t i = 0; i < size; i++) {
    int64_t value;
    if (!py_parse_int64(items[i], &value)) {
      return -1;
    }
    uint64_t zigzag = (static_cast<uint64_t>(value) << 1) ^
                      static_cast<uint64_t>(value >> 63);
    if (FORY_PREDICT_TRUE(zigzag < 0x80)) {
      buffer->unsafe_put_byte(writer_index, static_cast<uint8_t>(zigzag));
      writer_index += 1;
    } else {
      writer_index += buffer->put_var_uint64(writer_index, zigzag);
    }
  }
  buffer->writer_index(writer_index);
  return 0;
}

int Fory_PyStringSequenceWriteToBuffer(PyObject *collection, Buffer *buffer) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  for (Py_ssize_t i = 0; i < size; i++) {
    if (!py_write_string_to_buffer(items[i], buffer)) {
      return -1;
    }
  }
  return 0;
}

int Fory_PyDetectSequenceNoNullExactTypeKind(PyObject *collection) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return kForyPySequenceValueNone;
  }
  Py_ssize_t size = Py_SIZE(collection);
  if (size == 0) {
    return kForyPySequenceValueNone;
  }

  PyObject *first = items[0];
  int kind = kForyPySequenceValueNone;
  if (PyUnicode_CheckExact(first)) {
    kind = kForyPySequenceValueString;
  } else if (PyLong_CheckExact(first)) {
    if (first == Py_True || first == Py_False) {
      kind = kForyPySequenceValueBool;
    } else {
      kind = kForyPySequenceValueInt64;
    }
  } else if (PyFloat_CheckExact(first)) {
    kind = kForyPySequenceValueFloat64;
  } else {
    return kForyPySequenceValueNone;
  }

  for (Py_ssize_t i = 1; i < size; i++) {
    PyObject *item = items[i];
    if (kind == kForyPySequenceValueString) {
      if (!PyUnicode_CheckExact(item)) {
        return kForyPySequenceValueNone;
      }
      continue;
    }
    if (kind == kForyPySequenceValueBool) {
      if (item != Py_True && item != Py_False) {
        return kForyPySequenceValueNone;
      }
      continue;
    }
    if (kind == kForyPySequenceValueFloat64) {
      if (!PyFloat_CheckExact(item)) {
        return kForyPySequenceValueNone;
      }
      continue;
    }
    if (!PyLong_CheckExact(item) || item == Py_True || item == Py_False) {
      return kForyPySequenceValueNone;
    }
  }
  return kind;
}

int Fory_PyDetectSequenceTypeAndNull(PyObject *collection, int *has_null,
                                     int *has_same_type,
                                     int64_t *element_type_addr) {
  if (has_null == nullptr || has_same_type == nullptr ||
      element_type_addr == nullptr) {
    return -1;
  }
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  *has_null = 0;
  *has_same_type = 1;
  *element_type_addr = 0;
  PyTypeObject *element_type = nullptr;
  for (Py_ssize_t i = 0; i < size; i++) {
    PyObject *item = items[i];
    if (item == Py_None) {
      *has_null = 1;
      continue;
    }
    PyTypeObject *current_type = Py_TYPE(item);
    if (element_type == nullptr) {
      element_type = current_type;
    } else if (*has_same_type != 0 && current_type != element_type) {
      *has_same_type = 0;
    }
  }
  if (element_type != nullptr) {
    *element_type_addr =
        static_cast<int64_t>(reinterpret_cast<intptr_t>(element_type));
  }
  return 0;
}

int Fory_PySequenceHasNull(PyObject *collection) {
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  Py_ssize_t size = Py_SIZE(collection);
  for (Py_ssize_t i = 0; i < size; i++) {
    if (items[i] == Py_None) {
      return 1;
    }
  }
  return 0;
}

int Fory_PyDetectStringKeyMapValueKind(PyObject *map) {
  if (!PyDict_CheckExact(map)) {
    return kForyPyStringMapValueNone;
  }
  Py_ssize_t pos = 0;
  PyObject *key = nullptr;
  PyObject *value = nullptr;
  int value_kind = kForyPyStringMapValueNone;
  while (PyDict_Next(map, &pos, &key, &value)) {
    if (!PyUnicode_CheckExact(key)) {
      return kForyPyStringMapValueNone;
    }
    int current_kind = kForyPyStringMapValueNone;
    if (PyLong_CheckExact(value)) {
      int64_t int64_value;
      if (!py_parse_int64(value, &int64_value)) {
        return kForyPyStringMapValueNone;
      }
      current_kind = kForyPyStringMapValueInt64;
    } else if (PyUnicode_CheckExact(value)) {
      current_kind = kForyPyStringMapValueString;
    } else {
      return kForyPyStringMapValueNone;
    }
    if (value_kind == kForyPyStringMapValueNone) {
      value_kind = current_kind;
    } else if (value_kind != current_kind) {
      return kForyPyStringMapValueNone;
    }
  }
  return value_kind;
}

int Fory_PyStringInt64MapWriteChunkToBuffer(PyObject *map, Py_ssize_t *pos,
                                            Py_ssize_t chunk_size,
                                            Buffer *buffer) {
  if (!PyDict_CheckExact(map) || pos == nullptr || chunk_size < 0) {
    return -1;
  }
  PyObject *key = nullptr;
  PyObject *value = nullptr;
  for (Py_ssize_t i = 0; i < chunk_size; i++) {
    if (!PyDict_Next(map, pos, &key, &value)) {
      return -1;
    }
    if (!py_write_string_to_buffer(key, buffer)) {
      return -1;
    }
    int64_t int64_value;
    if (!py_parse_int64(value, &int64_value)) {
      return -1;
    }
    write_var_int64_to_buffer(buffer, int64_value);
  }
  return 0;
}

int Fory_PyStringStringMapWriteChunkToBuffer(PyObject *map, Py_ssize_t *pos,
                                             Py_ssize_t chunk_size,
                                             Buffer *buffer) {
  if (!PyDict_CheckExact(map) || pos == nullptr || chunk_size < 0) {
    return -1;
  }
  PyObject *key = nullptr;
  PyObject *value = nullptr;
  for (Py_ssize_t i = 0; i < chunk_size; i++) {
    if (!PyDict_Next(map, pos, &key, &value)) {
      return -1;
    }
    if (!py_write_string_to_buffer(key, buffer) ||
        !py_write_string_to_buffer(value, buffer)) {
      return -1;
    }
  }
  return 0;
}

static void set_map_contiguous_chunk_write_result(
    Py_ssize_t chunk_size, int has_next, PyObject *next_key,
    PyObject *next_value, Py_ssize_t *written_chunk_size, int *has_next_out,
    int64_t *next_key_addr, int64_t *next_value_addr) {
  if (written_chunk_size != nullptr) {
    *written_chunk_size = chunk_size;
  }
  if (has_next_out != nullptr) {
    *has_next_out = has_next;
  }
  if (next_key_addr != nullptr) {
    *next_key_addr =
        has_next == 0
            ? 0
            : static_cast<int64_t>(reinterpret_cast<intptr_t>(next_key));
  }
  if (next_value_addr != nullptr) {
    *next_value_addr =
        has_next == 0
            ? 0
            : static_cast<int64_t>(reinterpret_cast<intptr_t>(next_value));
  }
}

int Fory_PyStringInt64MapWriteContiguousChunkToBuffer(
    PyObject *map, Py_ssize_t *pos, PyObject *first_key, PyObject *first_value,
    Py_ssize_t max_chunk_size, Buffer *buffer, Py_ssize_t *written_chunk_size,
    int *has_next, int64_t *next_key_addr, int64_t *next_value_addr) {
  if (!PyDict_CheckExact(map) || pos == nullptr || max_chunk_size <= 0 ||
      buffer == nullptr || first_key == nullptr || first_value == nullptr) {
    return -1;
  }
  if (!PyUnicode_CheckExact(first_key) || !PyLong_CheckExact(first_value) ||
      first_value == Py_True || first_value == Py_False) {
    return -1;
  }
  PyObject *current_key = first_key;
  PyObject *current_value = first_value;
  Py_ssize_t chunk_size = 0;
  while (true) {
    if (!py_write_string_to_buffer(current_key, buffer)) {
      return -1;
    }
    int64_t int64_value;
    if (!py_parse_int64(current_value, &int64_value)) {
      return -1;
    }
    write_var_int64_to_buffer(buffer, int64_value);
    chunk_size += 1;
    if (chunk_size >= max_chunk_size) {
      int iter_has_next =
          PyDict_Next(map, pos, &current_key, &current_value) ? 1 : 0;
      set_map_contiguous_chunk_write_result(
          chunk_size, iter_has_next, current_key, current_value,
          written_chunk_size, has_next, next_key_addr, next_value_addr);
      return 0;
    }
    if (!PyDict_Next(map, pos, &current_key, &current_value)) {
      set_map_contiguous_chunk_write_result(chunk_size, 0, nullptr, nullptr,
                                            written_chunk_size, has_next,
                                            next_key_addr, next_value_addr);
      return 0;
    }
    if (!PyUnicode_CheckExact(current_key) ||
        !PyLong_CheckExact(current_value) || current_value == Py_True ||
        current_value == Py_False) {
      set_map_contiguous_chunk_write_result(
          chunk_size, 1, current_key, current_value, written_chunk_size,
          has_next, next_key_addr, next_value_addr);
      return 0;
    }
  }
}

int Fory_PyStringStringMapWriteContiguousChunkToBuffer(
    PyObject *map, Py_ssize_t *pos, PyObject *first_key, PyObject *first_value,
    Py_ssize_t max_chunk_size, Buffer *buffer, Py_ssize_t *written_chunk_size,
    int *has_next, int64_t *next_key_addr, int64_t *next_value_addr) {
  if (!PyDict_CheckExact(map) || pos == nullptr || max_chunk_size <= 0 ||
      buffer == nullptr || first_key == nullptr || first_value == nullptr) {
    return -1;
  }
  if (!PyUnicode_CheckExact(first_key) || !PyUnicode_CheckExact(first_value)) {
    return -1;
  }
  PyObject *current_key = first_key;
  PyObject *current_value = first_value;
  Py_ssize_t chunk_size = 0;
  while (true) {
    if (!py_write_string_to_buffer(current_key, buffer) ||
        !py_write_string_to_buffer(current_value, buffer)) {
      return -1;
    }
    chunk_size += 1;
    if (chunk_size >= max_chunk_size) {
      int iter_has_next =
          PyDict_Next(map, pos, &current_key, &current_value) ? 1 : 0;
      set_map_contiguous_chunk_write_result(
          chunk_size, iter_has_next, current_key, current_value,
          written_chunk_size, has_next, next_key_addr, next_value_addr);
      return 0;
    }
    if (!PyDict_Next(map, pos, &current_key, &current_value)) {
      set_map_contiguous_chunk_write_result(chunk_size, 0, nullptr, nullptr,
                                            written_chunk_size, has_next,
                                            next_key_addr, next_value_addr);
      return 0;
    }
    if (!PyUnicode_CheckExact(current_key) ||
        !PyUnicode_CheckExact(current_value)) {
      set_map_contiguous_chunk_write_result(
          chunk_size, 1, current_key, current_value, written_chunk_size,
          has_next, next_key_addr, next_value_addr);
      return 0;
    }
  }
}

int Fory_PyStringInt64MapReadChunkFromBuffer(PyObject *map,
                                             Py_ssize_t chunk_size,
                                             Buffer *buffer) {
  if (!PyDict_CheckExact(map) || chunk_size < 0) {
    return -1;
  }
  Error error;
  for (Py_ssize_t i = 0; i < chunk_size; i++) {
    PyObject *key = nullptr;
    if (!py_read_string_from_buffer(buffer, &key)) {
      return -1;
    }
    int64_t value;
    if (!read_var_int64_from_buffer(buffer, &error, &value)) {
      Py_DECREF(key);
      return -1;
    }
    PyObject *py_value = PyLong_FromLongLong(value);
    if (py_value == nullptr) {
      Py_DECREF(key);
      return -1;
    }
    int status = PyDict_SetItem(map, key, py_value);
    Py_DECREF(key);
    Py_DECREF(py_value);
    if (status != 0) {
      return -1;
    }
  }
  return 0;
}

int Fory_PyStringStringMapReadChunkFromBuffer(PyObject *map,
                                              Py_ssize_t chunk_size,
                                              Buffer *buffer) {
  if (!PyDict_CheckExact(map) || chunk_size < 0) {
    return -1;
  }
  for (Py_ssize_t i = 0; i < chunk_size; i++) {
    PyObject *key = nullptr;
    PyObject *value = nullptr;
    if (!py_read_string_from_buffer(buffer, &key)) {
      return -1;
    }
    if (!py_read_string_from_buffer(buffer, &value)) {
      Py_DECREF(key);
      return -1;
    }
    int status = PyDict_SetItem(map, key, value);
    Py_DECREF(key);
    Py_DECREF(value);
    if (status != 0) {
      return -1;
    }
  }
  return 0;
}

int Fory_PyBooleanSequenceReadFromBuffer(PyObject *collection, Buffer *buffer,
                                         Py_ssize_t size) {
  if (Py_SIZE(collection) != size) {
    return -1;
  }
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  if (!ensure_readable_size(buffer, size, sizeof(bool))) {
    return -1;
  }
  uint32_t reader_index = buffer->reader_index();
  uint8_t *data = buffer->data();
  for (Py_ssize_t i = 0; i < size; i++) {
    PyObject *value = data[reader_index + i] == 0 ? Py_False : Py_True;
    Py_INCREF(value);
    items[i] = value;
  }
  buffer->increase_reader_index(static_cast<uint32_t>(size));
  return 0;
}

int Fory_PyFloatSequenceReadFromBuffer(PyObject *collection, Buffer *buffer,
                                       Py_ssize_t size) {
  if (Py_SIZE(collection) != size) {
    return -1;
  }
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  if (!ensure_readable_size(buffer, size, sizeof(double))) {
    return -1;
  }
  Error error;
  for (Py_ssize_t i = 0; i < size; i++) {
    double value = buffer->read_double(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return -1;
    }
    PyObject *obj = PyFloat_FromDouble(value);
    if (obj == nullptr) {
      return -1;
    }
    items[i] = obj;
  }
  return 0;
}

int Fory_PyInt64SequenceReadVarintFromBuffer(PyObject *collection,
                                             Buffer *buffer, Py_ssize_t size) {
  if (Py_SIZE(collection) != size) {
    return -1;
  }
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  if (size < 0) {
    return -1;
  }
  uint32_t reader_index = buffer->reader_index();
  uint8_t *data = buffer->data();
  uint32_t buffer_size = buffer->size();
  Error error;
  for (Py_ssize_t i = 0; i < size; i++) {
    if (FORY_PREDICT_FALSE(reader_index >= buffer_size)) {
      return -1;
    }
    int64_t value;
    uint8_t first = data[reader_index];
    if (FORY_PREDICT_TRUE((first & 0x80) == 0)) {
      uint64_t zigzag = first;
      value = static_cast<int64_t>((zigzag >> 1) ^
                                   -static_cast<int64_t>(zigzag & 1));
      reader_index += 1;
    } else {
      buffer->reader_index(reader_index);
      value = buffer->read_var_int64(error);
      if (FORY_PREDICT_FALSE(!error.ok())) {
        return -1;
      }
      reader_index = buffer->reader_index();
    }
    PyObject *obj = PyLong_FromLongLong(value);
    if (obj == nullptr) {
      return -1;
    }
    items[i] = obj;
  }
  buffer->reader_index(reader_index);
  return 0;
}

int Fory_PyStringSequenceReadFromBuffer(PyObject *collection, Buffer *buffer,
                                        Py_ssize_t size) {
  if (Py_SIZE(collection) != size) {
    return -1;
  }
  PyObject **items = py_sequence_get_items(collection);
  if (items == nullptr) {
    return -1;
  }
  for (Py_ssize_t i = 0; i < size; i++) {
    PyObject *value = nullptr;
    if (!py_read_string_from_buffer(buffer, &value)) {
      return -1;
    }
    items[i] = value;
  }
  return 0;
}
} // namespace fory
