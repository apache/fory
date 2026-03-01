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

#include <algorithm>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <vector>

#include "fory/type/type.h"
#include "fory/util/stream.h"
#include "fory/util/string_util.h"

static PyObject **py_sequence_get_items(PyObject *collection) {
  if (PyList_CheckExact(collection)) {
    return ((PyListObject *)collection)->ob_item;
  } else if (PyTuple_CheckExact(collection)) {
    return ((PyTupleObject *)collection)->ob_item;
  }
  return nullptr;
}

namespace fory {

static std::string fetch_python_error_message() {
  PyObject *type = nullptr;
  PyObject *value = nullptr;
  PyObject *traceback = nullptr;
  PyErr_Fetch(&type, &value, &traceback);
  PyErr_NormalizeException(&type, &value, &traceback);
  std::string message = "python stream read failed";
  if (value != nullptr) {
    PyObject *value_str = PyObject_Str(value);
    if (value_str != nullptr) {
      const char *c_str = PyUnicode_AsUTF8(value_str);
      if (c_str != nullptr) {
        message = c_str;
      }
      Py_DECREF(value_str);
    } else {
      PyErr_Clear();
    }
  }
  Py_XDECREF(type);
  Py_XDECREF(value);
  Py_XDECREF(traceback);
  return message;
}

enum class PythonStreamReadMethod {
  ReadInto,
  RecvInto,
  RecvIntoUnderscore,
};

static const char *
python_stream_read_method_name(PythonStreamReadMethod method) {
  switch (method) {
  case PythonStreamReadMethod::ReadInto:
    return "readinto";
  case PythonStreamReadMethod::RecvInto:
    return "recvinto";
  case PythonStreamReadMethod::RecvIntoUnderscore:
    return "recv_into";
  }
  return "readinto";
}

static bool resolve_python_stream_read_method(PyObject *stream,
                                              PythonStreamReadMethod *method,
                                              std::string *error_message) {
  struct MethodCandidate {
    const char *name;
    PythonStreamReadMethod method;
  };
  constexpr MethodCandidate k_candidates[] = {
      {"readinto", PythonStreamReadMethod::ReadInto},
      {"recv_into", PythonStreamReadMethod::RecvIntoUnderscore},
      {"recvinto", PythonStreamReadMethod::RecvInto},
  };
  for (const auto &candidate : k_candidates) {
    const int has_method = PyObject_HasAttrString(stream, candidate.name);
    if (has_method < 0) {
      *error_message = fetch_python_error_message();
      return false;
    }
    if (has_method == 0) {
      continue;
    }
    PyObject *method_obj = PyObject_GetAttrString(stream, candidate.name);
    if (method_obj == nullptr) {
      *error_message = fetch_python_error_message();
      return false;
    }
    const bool is_callable = PyCallable_Check(method_obj) != 0;
    Py_DECREF(method_obj);
    if (is_callable) {
      *method = candidate.method;
      return true;
    }
  }
  *error_message = "stream object must provide readinto(buffer), "
                   "recv_into(buffer, size) or recvinto(buffer, size) method";
  return false;
}

class PythonStreamReader final : public StreamReader {
public:
  explicit PythonStreamReader(PyObject *stream, uint32_t buffer_size,
                              PythonStreamReadMethod read_method)
      : stream_(stream), read_method_(read_method),
        read_method_name_(python_stream_read_method_name(read_method)),
        data_(std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
        initial_buffer_size_(
            std::max<uint32_t>(buffer_size, static_cast<uint32_t>(1))),
        owned_buffer_(std::make_unique<Buffer>()) {
    FORY_CHECK(stream_ != nullptr) << "stream must not be null";
    Py_INCREF(stream_);
    bind_buffer(owned_buffer_.get());
  }

  ~PythonStreamReader() override {
    if (stream_ != nullptr) {
      PyGILState_STATE gil_state = PyGILState_Ensure();
      Py_DECREF(stream_);
      PyGILState_Release(gil_state);
      stream_ = nullptr;
    }
  }

  Result<void, Error> fill_buffer(uint32_t min_fill_size) override {
    if (min_fill_size == 0 || remaining_size() >= min_fill_size) {
      return Result<void, Error>();
    }

    const uint32_t read_pos = buffer_->reader_index_;
    const uint32_t deficit = min_fill_size - remaining_size();
    constexpr uint64_t k_max_u32 = std::numeric_limits<uint32_t>::max();
    const uint64_t required = static_cast<uint64_t>(buffer_->size_) + deficit;
    if (required > k_max_u32) {
      return Unexpected(
          Error::out_of_bound("stream buffer size exceeds uint32 range"));
    }
    if (required > data_.size()) {
      uint64_t new_size =
          std::max<uint64_t>(required, static_cast<uint64_t>(data_.size()) * 2);
      if (new_size > k_max_u32) {
        new_size = k_max_u32;
      }
      reserve(static_cast<uint32_t>(new_size));
    }

    uint32_t write_pos = buffer_->size_;
    while (remaining_size() < min_fill_size) {
      uint32_t writable = static_cast<uint32_t>(data_.size()) - write_pos;
      auto read_result = recv_into(data_.data() + write_pos, writable);
      if (FORY_PREDICT_FALSE(!read_result.ok())) {
        return Unexpected(std::move(read_result).error());
      }
      uint32_t read_bytes = std::move(read_result).value();
      if (read_bytes == 0) {
        return Unexpected(Error::buffer_out_of_bound(read_pos, min_fill_size,
                                                     remaining_size()));
      }
      write_pos += read_bytes;
      buffer_->size_ = write_pos;
    }
    return Result<void, Error>();
  }

  Result<void, Error> read_to(uint8_t *dst, uint32_t length) override {
    if (length == 0) {
      return Result<void, Error>();
    }
    Error error;
    if (FORY_PREDICT_FALSE(!buffer_->ensure_readable(length, error))) {
      return Unexpected(std::move(error));
    }
    std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
                static_cast<size_t>(length));
    buffer_->reader_index_ += length;
    return Result<void, Error>();
  }

  Result<void, Error> skip(uint32_t size) override {
    if (size == 0) {
      return Result<void, Error>();
    }
    Error error;
    buffer_->increase_reader_index(size, error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      return Unexpected(std::move(error));
    }
    return Result<void, Error>();
  }

  void shrink_buffer() override {
    if (buffer_ == nullptr) {
      return;
    }

    const uint32_t read_pos = buffer_->reader_index_;
    const uint32_t remaining = remaining_size();
    if (read_pos > 0) {
      if (remaining > 0) {
        std::memmove(data_.data(), data_.data() + read_pos,
                     static_cast<size_t>(remaining));
      }
      buffer_->reader_index_ = 0;
      buffer_->size_ = remaining;
      buffer_->writer_index_ = remaining;
    }

    const uint32_t current_capacity = static_cast<uint32_t>(data_.size());
    uint32_t target_capacity = current_capacity;
    if (current_capacity > initial_buffer_size_) {
      if (remaining == 0) {
        target_capacity = initial_buffer_size_;
      } else if (remaining <= current_capacity / 4) {
        const uint32_t doubled =
            remaining > std::numeric_limits<uint32_t>::max() / 2
                ? std::numeric_limits<uint32_t>::max()
                : remaining * 2;
        target_capacity = std::max<uint32_t>(
            initial_buffer_size_,
            std::max<uint32_t>(doubled, static_cast<uint32_t>(1)));
      }
    }
    if (target_capacity < current_capacity) {
      data_.resize(target_capacity);
      data_.shrink_to_fit();
      buffer_->data_ = data_.data();
    }
  }

  Result<void, Error> unread(uint32_t size) override {
    if (FORY_PREDICT_FALSE(size > buffer_->reader_index_)) {
      return Unexpected(Error::buffer_out_of_bound(buffer_->reader_index_, size,
                                                   buffer_->size_));
    }
    buffer_->reader_index_ -= size;
    return Result<void, Error>();
  }

  Buffer &get_buffer() override { return *buffer_; }

  void bind_buffer(Buffer *buffer) override {
    Buffer *target = buffer == nullptr ? owned_buffer_.get() : buffer;
    if (target == nullptr) {
      if (buffer_ != nullptr) {
        buffer_->stream_reader_ = nullptr;
      }
      buffer_ = nullptr;
      return;
    }

    if (buffer_ == target) {
      buffer_->data_ = data_.data();
      buffer_->own_data_ = false;
      buffer_->wrapped_vector_ = nullptr;
      buffer_->stream_reader_ = this;
      return;
    }

    Buffer *source = buffer_;
    if (source != nullptr) {
      target->size_ = source->size_;
      target->writer_index_ = source->writer_index_;
      target->reader_index_ = source->reader_index_;
      source->stream_reader_ = nullptr;
    } else {
      target->size_ = 0;
      target->writer_index_ = 0;
      target->reader_index_ = 0;
    }

    buffer_ = target;
    buffer_->data_ = data_.data();
    buffer_->own_data_ = false;
    buffer_->wrapped_vector_ = nullptr;
    buffer_->stream_reader_ = this;
  }

private:
  Result<uint32_t, Error> recv_into(void *dst, uint32_t length) {
    if (length == 0) {
      return 0U;
    }
    PyGILState_STATE gil_state = PyGILState_Ensure();
    PyObject *memory_view =
        PyMemoryView_FromMemory(reinterpret_cast<char *>(dst),
                                static_cast<Py_ssize_t>(length), PyBUF_WRITE);
    if (memory_view == nullptr) {
      std::string message = fetch_python_error_message();
      PyGILState_Release(gil_state);
      return Unexpected(Error::io_error(message));
    }
    PyObject *read_bytes_obj = nullptr;
    switch (read_method_) {
    case PythonStreamReadMethod::ReadInto:
      read_bytes_obj =
          PyObject_CallMethod(stream_, read_method_name_, "O", memory_view);
      break;
    case PythonStreamReadMethod::RecvInto:
    case PythonStreamReadMethod::RecvIntoUnderscore:
      read_bytes_obj =
          PyObject_CallMethod(stream_, read_method_name_, "On", memory_view,
                              static_cast<Py_ssize_t>(length));
      break;
    }
    Py_DECREF(memory_view);
    if (read_bytes_obj == nullptr) {
      std::string message = fetch_python_error_message();
      PyGILState_Release(gil_state);
      return Unexpected(Error::io_error(message));
    }

    Py_ssize_t read_bytes = PyLong_AsSsize_t(read_bytes_obj);
    Py_DECREF(read_bytes_obj);
    if (read_bytes == -1 && PyErr_Occurred()) {
      std::string message = fetch_python_error_message();
      PyGILState_Release(gil_state);
      return Unexpected(Error::io_error(message));
    }
    PyGILState_Release(gil_state);
    if (read_bytes < 0 ||
        static_cast<uint64_t>(read_bytes) > static_cast<uint64_t>(length)) {
      return Unexpected(Error::io_error("python stream " +
                                        std::string(read_method_name_) +
                                        " returned invalid length"));
    }
    return static_cast<uint32_t>(read_bytes);
  }

  uint32_t remaining_size() const {
    return buffer_->size_ - buffer_->reader_index_;
  }

  void reserve(uint32_t new_size) {
    data_.resize(new_size);
    buffer_->data_ = data_.data();
  }

  PyObject *stream_ = nullptr;
  PythonStreamReadMethod read_method_;
  const char *read_method_name_ = nullptr;
  std::vector<uint8_t> data_;
  uint32_t initial_buffer_size_ = 1;
  Buffer *buffer_ = nullptr;
  std::unique_ptr<Buffer> owned_buffer_;
};

enum class PythonCollectionKind : uint8_t {
  List = 0,
  Tuple = 1,
  Set = 2,
};

static PythonCollectionKind
resolve_python_collection_kind(PyObject *collection) {
  if (PyList_CheckExact(collection)) {
    return PythonCollectionKind::List;
  }
  if (PyTuple_CheckExact(collection)) {
    return PythonCollectionKind::Tuple;
  }
  if (PySet_CheckExact(collection)) {
    return PythonCollectionKind::Set;
  }
  PyErr_Format(PyExc_TypeError,
               "fastpath only supports list/tuple/set collections, got %.200s",
               Py_TYPE(collection)->tp_name);
  return PythonCollectionKind::List;
}

static void set_buffer_error(const Error &error) {
  PyErr_SetString(PyExc_BufferError, error.to_string().c_str());
}

static bool py_long_to_int64(PyObject *value, int64_t *out) {
  int overflow = 0;
  long long converted = PyLong_AsLongLongAndOverflow(value, &overflow);
  if (converted == -1 && PyErr_Occurred()) {
    return false;
  }
  if (overflow != 0) {
    PyErr_SetString(PyExc_OverflowError,
                    "integer out of range for int64 fastpath");
    return false;
  }
  *out = static_cast<int64_t>(converted);
  return true;
}

static bool can_use_list_sequence_fastpath(PyObject **items, Py_ssize_t size,
                                           uint8_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::STRING:
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (!PyUnicode_CheckExact(items[i])) {
        return false;
      }
    }
    return true;
  case TypeId::VARINT64:
  case TypeId::VARINT32:
  case TypeId::INT8:
  case TypeId::INT16:
  case TypeId::INT32:
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (!PyLong_CheckExact(items[i])) {
        return false;
      }
    }
    return true;
  case TypeId::BOOL:
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (items[i] != Py_True && items[i] != Py_False) {
        return false;
      }
    }
    return true;
  case TypeId::FLOAT64:
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (!PyFloat_CheckExact(items[i])) {
        return false;
      }
    }
    return true;
  default:
    return false;
  }
}

template <typename T>
static bool py_long_to_integral_range(PyObject *value, const char *type_name,
                                      T *out) {
  int64_t converted = 0;
  if (!py_long_to_int64(value, &converted)) {
    return false;
  }
  constexpr int64_t k_min = static_cast<int64_t>(std::numeric_limits<T>::min());
  constexpr int64_t k_max = static_cast<int64_t>(std::numeric_limits<T>::max());
  if (converted < k_min || converted > k_max) {
    PyErr_Format(PyExc_OverflowError, "integer out of range for %s", type_name);
    return false;
  }
  *out = static_cast<T>(converted);
  return true;
}

static int write_python_string(Buffer *buffer, PyObject *value) {
  if (FORY_PREDICT_FALSE(!PyUnicode_Check(value))) {
    PyErr_Format(PyExc_TypeError, "expected str, got %.200s",
                 Py_TYPE(value)->tp_name);
    return -1;
  }
  if (FORY_PREDICT_FALSE(PyUnicode_READY(value) < 0)) {
    return -1;
  }
  const Py_ssize_t length = PyUnicode_GET_LENGTH(value);
  const int kind = PyUnicode_KIND(value);
  const void *data = PyUnicode_DATA(value);
  uint64_t header = 0;
  uint32_t buffer_size = 0;

  if (kind == PyUnicode_1BYTE_KIND) {
    if (FORY_PREDICT_FALSE(length > std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError,
                      "string too large for fastpath encoding");
      return -1;
    }
    buffer_size = static_cast<uint32_t>(length);
    header = (static_cast<uint64_t>(length) << 2U) | 0ULL;
  } else if (kind == PyUnicode_2BYTE_KIND) {
    const uint64_t bytes = static_cast<uint64_t>(length) << 1U;
    if (FORY_PREDICT_FALSE(bytes > std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError,
                      "string too large for fastpath encoding");
      return -1;
    }
    buffer_size = static_cast<uint32_t>(bytes);
    // Keep wire format exactly aligned with Buffer.write_string in buffer.pyx.
    header = (static_cast<uint64_t>(length) << 3U) | 1ULL;
  } else {
    Py_ssize_t utf8_size = 0;
    const char *utf8 = PyUnicode_AsUTF8AndSize(value, &utf8_size);
    if (FORY_PREDICT_FALSE(utf8 == nullptr)) {
      return -1;
    }
    if (FORY_PREDICT_FALSE(utf8_size > std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError,
                      "string too large for fastpath encoding");
      return -1;
    }
    data = utf8;
    buffer_size = static_cast<uint32_t>(utf8_size);
    header = (static_cast<uint64_t>(buffer_size) << 2U) | 2ULL;
  }

  buffer->write_var_uint64(header);
  if (buffer_size == 0) {
    return 0;
  }
  const uint32_t writer_index = buffer->writer_index();
  buffer->grow(buffer_size);
  buffer->unsafe_put(writer_index, data, buffer_size);
  buffer->increase_writer_index(buffer_size);
  return 0;
}

static PyObject *read_python_string(Buffer *buffer) {
  Error error;
  const uint64_t header = buffer->read_var_uint64(error);
  if (FORY_PREDICT_FALSE(!error.ok())) {
    set_buffer_error(error);
    return nullptr;
  }
  const uint64_t size64 = header >> 2U;
  if (FORY_PREDICT_FALSE(size64 > std::numeric_limits<uint32_t>::max())) {
    PyErr_SetString(PyExc_OverflowError,
                    "string length too large for fastpath decoding");
    return nullptr;
  }
  const uint32_t size = static_cast<uint32_t>(size64);
  const uint32_t encoding = static_cast<uint32_t>(header & 0b11ULL);
  if (size == 0) {
    return PyUnicode_FromStringAndSize("", 0);
  }

  uint32_t reader_index = buffer->reader_index();
  if (FORY_PREDICT_FALSE(size > buffer->size() - reader_index)) {
    if (FORY_PREDICT_FALSE(!buffer->ensure_readable(size, error))) {
      set_buffer_error(error);
      return nullptr;
    }
    reader_index = buffer->reader_index();
  }
  const char *data =
      reinterpret_cast<const char *>(buffer->data() + reader_index);
  buffer->reader_index(reader_index + size);

  if (encoding == 0) {
    return PyUnicode_DecodeLatin1(data, static_cast<Py_ssize_t>(size),
                                  "strict");
  }
  if (encoding == 1) {
    if (FORY_PREDICT_FALSE((size & 1U) != 0U)) {
      PyErr_SetString(PyExc_ValueError, "invalid utf16 string length");
      return nullptr;
    }
    const auto *utf16_data = reinterpret_cast<const uint16_t *>(data);
    if (utf16_has_surrogate_pairs(utf16_data,
                                  static_cast<size_t>(size >> 1U))) {
      int byteorder = -1; // little-endian
      return PyUnicode_DecodeUTF16(data, static_cast<Py_ssize_t>(size),
                                   "strict", &byteorder);
    }
    return PyUnicode_FromKindAndData(PyUnicode_2BYTE_KIND, data,
                                     static_cast<Py_ssize_t>(size >> 1U));
  }
  if (encoding == 2) {
    return PyUnicode_DecodeUTF8(data, static_cast<Py_ssize_t>(size), "strict");
  }
  PyErr_Format(PyExc_ValueError, "unsupported string encoding tag: %u",
               encoding);
  return nullptr;
}

static int write_primitive_item(Buffer *buffer, PyObject *value,
                                uint8_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::STRING:
    return write_python_string(buffer, value);
  case TypeId::VARINT64: {
    int64_t v = 0;
    if (FORY_PREDICT_FALSE(!py_long_to_int64(value, &v))) {
      return -1;
    }
    buffer->write_var_int64(v);
    return 0;
  }
  case TypeId::VARINT32: {
    int32_t v = 0;
    if (FORY_PREDICT_FALSE(
            !py_long_to_integral_range<int32_t>(value, "int32", &v))) {
      return -1;
    }
    buffer->write_var_int32(v);
    return 0;
  }
  case TypeId::BOOL:
    buffer->write_int8(value == Py_True ? 1 : 0);
    return 0;
  case TypeId::FLOAT64: {
    double v;
    if (PyFloat_CheckExact(value)) {
      v = reinterpret_cast<PyFloatObject *>(value)->ob_fval;
    } else {
      v = PyFloat_AsDouble(value);
      if (FORY_PREDICT_FALSE(v == -1.0 && PyErr_Occurred())) {
        return -1;
      }
    }
    buffer->write_double(v);
    return 0;
  }
  case TypeId::INT8: {
    int8_t v = 0;
    if (FORY_PREDICT_FALSE(
            !py_long_to_integral_range<int8_t>(value, "int8", &v))) {
      return -1;
    }
    buffer->write_int8(v);
    return 0;
  }
  case TypeId::INT16: {
    int16_t v = 0;
    if (FORY_PREDICT_FALSE(
            !py_long_to_integral_range<int16_t>(value, "int16", &v))) {
      return -1;
    }
    buffer->write_int16(v);
    return 0;
  }
  case TypeId::INT32: {
    int32_t v = 0;
    if (FORY_PREDICT_FALSE(
            !py_long_to_integral_range<int32_t>(value, "int32", &v))) {
      return -1;
    }
    buffer->write_int32(v);
    return 0;
  }
  default:
    PyErr_Format(PyExc_ValueError, "unsupported primitive fastpath type id: %u",
                 static_cast<unsigned>(type_id));
    return -1;
  }
}

static int write_primitive_sequence(PyObject **items, Py_ssize_t size,
                                    Buffer *buffer, uint8_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::STRING:
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(write_python_string(buffer, items[i]) != 0)) {
        return -1;
      }
    }
    return 0;
  case TypeId::VARINT64:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           std::numeric_limits<uint32_t>::max() / 9ULL)) {
      PyErr_SetString(PyExc_OverflowError, "varint64 collection too large");
      return -1;
    }
    {
      const uint32_t max_byte_size = static_cast<uint32_t>(size) * 9U;
      const uint32_t writer_index = buffer->writer_index();
      buffer->grow(max_byte_size);
      uint32_t offset = writer_index;
      for (Py_ssize_t i = 0; i < size; ++i) {
        int64_t v = 0;
        if (FORY_PREDICT_FALSE(!py_long_to_int64(items[i], &v))) {
          return -1;
        }
        const uint64_t zigzag =
            (static_cast<uint64_t>(v) << 1) ^ static_cast<uint64_t>(v >> 63);
        offset += buffer->put_var_uint64(offset, zigzag);
      }
      buffer->increase_writer_index(offset - writer_index);
    }
    return 0;
  case TypeId::VARINT32:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           std::numeric_limits<uint32_t>::max() / 5ULL)) {
      PyErr_SetString(PyExc_OverflowError, "varint32 collection too large");
      return -1;
    }
    {
      const uint32_t max_byte_size = static_cast<uint32_t>(size) * 5U;
      const uint32_t writer_index = buffer->writer_index();
      buffer->grow(max_byte_size);
      uint32_t offset = writer_index;
      for (Py_ssize_t i = 0; i < size; ++i) {
        int32_t v = 0;
        if (FORY_PREDICT_FALSE(
                !py_long_to_integral_range<int32_t>(items[i], "int32", &v))) {
          return -1;
        }
        const uint32_t zigzag =
            (static_cast<uint32_t>(v) << 1) ^ static_cast<uint32_t>(v >> 31);
        offset += buffer->put_var_uint32(offset, zigzag);
      }
      buffer->increase_writer_index(offset - writer_index);
    }
    return 0;
  case TypeId::BOOL: {
    const uint64_t byte_size64 = static_cast<uint64_t>(size) * sizeof(bool);
    if (FORY_PREDICT_FALSE(byte_size64 >
                           std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError, "bool collection too large");
      return -1;
    }
    const uint32_t byte_size = static_cast<uint32_t>(byte_size64);
    const uint32_t writer_index = buffer->writer_index();
    buffer->grow(byte_size);
    uint32_t offset = writer_index;
    for (Py_ssize_t i = 0; i < size; ++i) {
      buffer->unsafe_put_byte(offset++,
                              static_cast<uint8_t>(items[i] == Py_True));
    }
    buffer->increase_writer_index(byte_size);
    return 0;
  }
  case TypeId::FLOAT64: {
    const uint64_t byte_size64 = static_cast<uint64_t>(size) * sizeof(double);
    if (FORY_PREDICT_FALSE(byte_size64 >
                           std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError, "float collection too large");
      return -1;
    }
    const uint32_t byte_size = static_cast<uint32_t>(byte_size64);
    const uint32_t writer_index = buffer->writer_index();
    buffer->grow(byte_size);
    uint32_t offset = writer_index;
    for (Py_ssize_t i = 0; i < size; ++i) {
      PyObject *value = items[i];
      double v;
      if (PyFloat_CheckExact(value)) {
        v = reinterpret_cast<PyFloatObject *>(value)->ob_fval;
      } else {
        v = PyFloat_AsDouble(value);
        if (FORY_PREDICT_FALSE(v == -1.0 && PyErr_Occurred())) {
          return -1;
        }
      }
      buffer->unsafe_put(offset, v);
      offset += sizeof(double);
    }
    buffer->increase_writer_index(byte_size);
    return 0;
  }
  case TypeId::INT8: {
    const uint64_t byte_size64 = static_cast<uint64_t>(size) * sizeof(int8_t);
    if (FORY_PREDICT_FALSE(byte_size64 >
                           std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError, "int8 collection too large");
      return -1;
    }
    const uint32_t byte_size = static_cast<uint32_t>(byte_size64);
    const uint32_t writer_index = buffer->writer_index();
    buffer->grow(byte_size);
    uint32_t offset = writer_index;
    for (Py_ssize_t i = 0; i < size; ++i) {
      int8_t v = 0;
      if (FORY_PREDICT_FALSE(
              !py_long_to_integral_range<int8_t>(items[i], "int8", &v))) {
        return -1;
      }
      buffer->unsafe_put_byte(offset++, v);
    }
    buffer->increase_writer_index(byte_size);
    return 0;
  }
  case TypeId::INT16: {
    const uint64_t byte_size64 = static_cast<uint64_t>(size) * sizeof(int16_t);
    if (FORY_PREDICT_FALSE(byte_size64 >
                           std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError, "int16 collection too large");
      return -1;
    }
    const uint32_t byte_size = static_cast<uint32_t>(byte_size64);
    const uint32_t writer_index = buffer->writer_index();
    buffer->grow(byte_size);
    uint32_t offset = writer_index;
    for (Py_ssize_t i = 0; i < size; ++i) {
      int16_t v = 0;
      if (FORY_PREDICT_FALSE(
              !py_long_to_integral_range<int16_t>(items[i], "int16", &v))) {
        return -1;
      }
      buffer->unsafe_put(offset, v);
      offset += sizeof(int16_t);
    }
    buffer->increase_writer_index(byte_size);
    return 0;
  }
  case TypeId::INT32: {
    const uint64_t byte_size64 = static_cast<uint64_t>(size) * sizeof(int32_t);
    if (FORY_PREDICT_FALSE(byte_size64 >
                           std::numeric_limits<uint32_t>::max())) {
      PyErr_SetString(PyExc_OverflowError, "int32 collection too large");
      return -1;
    }
    const uint32_t byte_size = static_cast<uint32_t>(byte_size64);
    const uint32_t writer_index = buffer->writer_index();
    buffer->grow(byte_size);
    uint32_t offset = writer_index;
    for (Py_ssize_t i = 0; i < size; ++i) {
      int32_t v = 0;
      if (FORY_PREDICT_FALSE(
              !py_long_to_integral_range<int32_t>(items[i], "int32", &v))) {
        return -1;
      }
      buffer->unsafe_put(offset, v);
      offset += sizeof(int32_t);
    }
    buffer->increase_writer_index(byte_size);
    return 0;
  }
  default:
    PyErr_Format(PyExc_ValueError, "unsupported primitive fastpath type id: %u",
                 static_cast<unsigned>(type_id));
    return -1;
  }
}

static PyObject *read_primitive_item(Buffer *buffer, uint8_t type_id) {
  Error error;
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::STRING:
    return read_python_string(buffer);
  case TypeId::VARINT64: {
    const int64_t v = buffer->read_var_int64(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyLong_FromLongLong(v);
  }
  case TypeId::VARINT32: {
    const int32_t v = buffer->read_var_int32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyLong_FromLong(v);
  }
  case TypeId::BOOL: {
    const uint8_t v = buffer->read_uint8(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyBool_FromLong(v != 0);
  }
  case TypeId::FLOAT64: {
    const double v = buffer->read_double(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyFloat_FromDouble(v);
  }
  case TypeId::INT8: {
    const int8_t v = buffer->read_int8(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyLong_FromLong(v);
  }
  case TypeId::INT16: {
    const int16_t v = buffer->read_int16(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyLong_FromLong(v);
  }
  case TypeId::INT32: {
    const int32_t v = buffer->read_int32(error);
    if (FORY_PREDICT_FALSE(!error.ok())) {
      set_buffer_error(error);
      return nullptr;
    }
    return PyLong_FromLong(v);
  }
  default:
    PyErr_Format(PyExc_ValueError, "unsupported primitive fastpath type id: %u",
                 static_cast<unsigned>(type_id));
    return nullptr;
  }
}

template <typename SetItemFn>
static int read_primitive_sequence_indexed(Buffer *buffer, Py_ssize_t size,
                                           uint8_t type_id,
                                           SetItemFn set_item) {
  Error error;
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::STRING:
    for (Py_ssize_t i = 0; i < size; ++i) {
      PyObject *item = read_python_string(buffer);
      if (FORY_PREDICT_FALSE(item == nullptr)) {
        return -1;
      }
      set_item(i, item);
    }
    return 0;
  case TypeId::VARINT64: {
    const uint8_t *data = buffer->data();
    const uint8_t *ptr = data + buffer->reader_index();
    const uint8_t *end = data + buffer->size();
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ptr >= end)) {
        PyErr_SetString(PyExc_BufferError,
                        "buffer out of bound while reading varint64");
        return -1;
      }
      uint64_t raw = 0;
      const uint8_t b0 = *ptr++;
      if ((b0 & 0x80) == 0) {
        raw = b0;
      } else {
        if (FORY_PREDICT_FALSE(ptr >= end)) {
          PyErr_SetString(PyExc_BufferError,
                          "buffer out of bound while reading varint64");
          return -1;
        }
        const uint8_t b1 = *ptr++;
        raw = static_cast<uint64_t>(b0 & 0x7F) |
              (static_cast<uint64_t>(b1 & 0x7F) << 7);
        if (FORY_PREDICT_FALSE((b1 & 0x80) != 0)) {
          const uint32_t offset = static_cast<uint32_t>((ptr - data) - 2);
          uint32_t read_bytes = 0;
          raw = buffer->get_var_uint64(offset, &read_bytes);
          if (FORY_PREDICT_FALSE(read_bytes == 0)) {
            PyErr_SetString(PyExc_BufferError,
                            "buffer out of bound while reading varint64");
            return -1;
          }
          ptr = data + offset + read_bytes;
        }
      }
      const int64_t v =
          static_cast<int64_t>((raw >> 1) ^ -static_cast<int64_t>(raw & 1ULL));
      PyObject *item = PyLong_FromLongLong(v);
      if (FORY_PREDICT_FALSE(item == nullptr)) {
        return -1;
      }
      set_item(i, item);
    }
    buffer->reader_index(static_cast<uint32_t>(ptr - data));
  }
    return 0;
  case TypeId::VARINT32: {
    const uint8_t *data = buffer->data();
    const uint8_t *ptr = data + buffer->reader_index();
    const uint8_t *end = data + buffer->size();
    for (Py_ssize_t i = 0; i < size; ++i) {
      if (FORY_PREDICT_FALSE(ptr >= end)) {
        PyErr_SetString(PyExc_BufferError,
                        "buffer out of bound while reading varint32");
        return -1;
      }
      uint32_t raw = 0;
      const uint8_t b0 = *ptr++;
      if ((b0 & 0x80) == 0) {
        raw = b0;
      } else {
        if (FORY_PREDICT_FALSE(ptr >= end)) {
          PyErr_SetString(PyExc_BufferError,
                          "buffer out of bound while reading varint32");
          return -1;
        }
        const uint8_t b1 = *ptr++;
        raw = static_cast<uint32_t>(b0 & 0x7F) |
              (static_cast<uint32_t>(b1 & 0x7F) << 7);
        if (FORY_PREDICT_FALSE((b1 & 0x80) != 0)) {
          const uint32_t offset = static_cast<uint32_t>((ptr - data) - 2);
          uint32_t read_bytes = 0;
          raw = buffer->get_var_uint32(offset, &read_bytes);
          if (FORY_PREDICT_FALSE(read_bytes == 0)) {
            PyErr_SetString(PyExc_BufferError,
                            "buffer out of bound while reading varint32");
            return -1;
          }
          ptr = data + offset + read_bytes;
        }
      }
      const int32_t v =
          static_cast<int32_t>((raw >> 1) ^ -static_cast<int32_t>(raw & 1U));
      PyObject *item = PyLong_FromLong(v);
      if (FORY_PREDICT_FALSE(item == nullptr)) {
        return -1;
      }
      set_item(i, item);
    }
    buffer->reader_index(static_cast<uint32_t>(ptr - data));
  }
    return 0;
  case TypeId::BOOL:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           buffer->remaining_size())) {
      PyErr_SetString(PyExc_BufferError,
                      "buffer out of bound while reading bool");
      return -1;
    }
    {
      uint32_t offset = buffer->reader_index();
      const uint8_t *data = buffer->data() + offset;
      for (Py_ssize_t i = 0; i < size; ++i) {
        PyObject *item =
            data[i] != 0 ? Py_NewRef(Py_True) : Py_NewRef(Py_False);
        set_item(i, item);
      }
      buffer->reader_index(offset + static_cast<uint32_t>(size));
    }
    return 0;
  case TypeId::FLOAT64:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           buffer->remaining_size() / sizeof(double))) {
      PyErr_SetString(PyExc_BufferError,
                      "buffer out of bound while reading float64");
      return -1;
    }
    {
      uint32_t offset = buffer->reader_index();
      const uint8_t *data = buffer->data() + offset;
      for (Py_ssize_t i = 0; i < size; ++i) {
        double v;
        std::memcpy(&v, data + i * sizeof(double), sizeof(double));
        PyObject *item = PyFloat_FromDouble(v);
        if (FORY_PREDICT_FALSE(item == nullptr)) {
          return -1;
        }
        set_item(i, item);
      }
      buffer->reader_index(offset +
                           static_cast<uint32_t>(size * sizeof(double)));
    }
    return 0;
  case TypeId::INT8:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           buffer->remaining_size())) {
      PyErr_SetString(PyExc_BufferError,
                      "buffer out of bound while reading int8");
      return -1;
    }
    {
      uint32_t offset = buffer->reader_index();
      const int8_t *data =
          reinterpret_cast<const int8_t *>(buffer->data() + offset);
      for (Py_ssize_t i = 0; i < size; ++i) {
        PyObject *item = PyLong_FromLong(data[i]);
        if (FORY_PREDICT_FALSE(item == nullptr)) {
          return -1;
        }
        set_item(i, item);
      }
      buffer->reader_index(offset + static_cast<uint32_t>(size));
    }
    return 0;
  case TypeId::INT16:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           buffer->remaining_size() / sizeof(int16_t))) {
      PyErr_SetString(PyExc_BufferError,
                      "buffer out of bound while reading int16");
      return -1;
    }
    {
      uint32_t offset = buffer->reader_index();
      const uint8_t *data = buffer->data() + offset;
      for (Py_ssize_t i = 0; i < size; ++i) {
        int16_t v;
        std::memcpy(&v, data + i * sizeof(int16_t), sizeof(int16_t));
        PyObject *item = PyLong_FromLong(v);
        if (FORY_PREDICT_FALSE(item == nullptr)) {
          return -1;
        }
        set_item(i, item);
      }
      buffer->reader_index(offset +
                           static_cast<uint32_t>(size * sizeof(int16_t)));
    }
    return 0;
  case TypeId::INT32:
    if (FORY_PREDICT_FALSE(static_cast<uint64_t>(size) >
                           buffer->remaining_size() / sizeof(int32_t))) {
      PyErr_SetString(PyExc_BufferError,
                      "buffer out of bound while reading int32");
      return -1;
    }
    {
      uint32_t offset = buffer->reader_index();
      const uint8_t *data = buffer->data() + offset;
      for (Py_ssize_t i = 0; i < size; ++i) {
        int32_t v;
        std::memcpy(&v, data + i * sizeof(int32_t), sizeof(int32_t));
        PyObject *item = PyLong_FromLong(v);
        if (FORY_PREDICT_FALSE(item == nullptr)) {
          return -1;
        }
        set_item(i, item);
      }
      buffer->reader_index(offset +
                           static_cast<uint32_t>(size * sizeof(int32_t)));
    }
    return 0;
  default:
    PyErr_Format(PyExc_ValueError, "unsupported primitive fastpath type id: %u",
                 static_cast<unsigned>(type_id));
    return -1;
  }
}

int Fory_PyPrimitiveCollectionWriteToBuffer(PyObject *collection,
                                            Buffer *buffer, uint8_t type_id) {
  PyObject **items = py_sequence_get_items(collection);
  if (items != nullptr) {
    const Py_ssize_t size = Py_SIZE(collection);
    // For list, keep raw ob_item fastpath only when element conversions are
    // guaranteed not to execute Python callbacks that might mutate the list.
    if (!PyList_CheckExact(collection) ||
        can_use_list_sequence_fastpath(items, size, type_id)) {
      return write_primitive_sequence(items, size, buffer, type_id);
    }
  }
  PyObject *iterator = PyObject_GetIter(collection);
  if (FORY_PREDICT_FALSE(iterator == nullptr)) {
    return -1;
  }
  int rc = 0;
  for (PyObject *item = PyIter_Next(iterator); item != nullptr;
       item = PyIter_Next(iterator)) {
    if (FORY_PREDICT_FALSE(write_primitive_item(buffer, item, type_id) != 0)) {
      Py_DECREF(item);
      rc = -1;
      break;
    }
    Py_DECREF(item);
  }
  if (FORY_PREDICT_FALSE(rc == 0 && PyErr_Occurred() != nullptr)) {
    rc = -1;
  }
  Py_DECREF(iterator);
  return rc;
}

int Fory_PyPrimitiveSequenceWriteToBuffer(PyObject **items, Py_ssize_t size,
                                          Buffer *buffer, uint8_t type_id) {
  if (FORY_PREDICT_FALSE(items == nullptr)) {
    PyErr_SetString(PyExc_ValueError, "items must not be null");
    return -1;
  }
  if (FORY_PREDICT_FALSE(size < 0)) {
    PyErr_SetString(PyExc_ValueError, "negative collection size");
    return -1;
  }
  return write_primitive_sequence(items, size, buffer, type_id);
}

int Fory_PyPrimitiveCollectionReadFromBuffer(PyObject *collection,
                                             Buffer *buffer, Py_ssize_t size,
                                             uint8_t type_id) {
  if (FORY_PREDICT_FALSE(size < 0)) {
    PyErr_SetString(PyExc_ValueError, "negative collection size");
    return -1;
  }

  const PythonCollectionKind kind = resolve_python_collection_kind(collection);
  if (FORY_PREDICT_FALSE(PyErr_Occurred() != nullptr)) {
    return -1;
  }
  if (kind == PythonCollectionKind::List && Py_SIZE(collection) < size) {
    PyErr_SetString(PyExc_ValueError,
                    "list collection size is smaller than requested read size");
    return -1;
  }
  if (kind == PythonCollectionKind::Tuple && Py_SIZE(collection) < size) {
    PyErr_SetString(
        PyExc_ValueError,
        "tuple collection size is smaller than requested read size");
    return -1;
  }
  if (!buffer->is_stream_backed() && kind == PythonCollectionKind::List) {
    return read_primitive_sequence_indexed(
        buffer, size, type_id, [collection](Py_ssize_t i, PyObject *item) {
          PyList_SET_ITEM(collection, i, item);
        });
  }
  if (!buffer->is_stream_backed() && kind == PythonCollectionKind::Tuple) {
    return read_primitive_sequence_indexed(
        buffer, size, type_id, [collection](Py_ssize_t i, PyObject *item) {
          PyTuple_SET_ITEM(collection, i, item);
        });
  }

  for (Py_ssize_t i = 0; i < size; ++i) {
    PyObject *item = read_primitive_item(buffer, type_id);
    if (FORY_PREDICT_FALSE(item == nullptr)) {
      return -1;
    }
    if (kind == PythonCollectionKind::List) {
      PyList_SET_ITEM(collection, i, item);
    } else if (kind == PythonCollectionKind::Tuple) {
      PyTuple_SET_ITEM(collection, i, item);
    } else {
      if (FORY_PREDICT_FALSE(PySet_Add(collection, item) < 0)) {
        Py_DECREF(item);
        return -1;
      }
      Py_DECREF(item);
    }
  }
  return 0;
}

int Fory_PyCreateBufferFromStream(PyObject *stream, uint32_t buffer_size,
                                  Buffer **out, std::string *error_message) {
  if (stream == nullptr) {
    *error_message = "stream must not be null";
    return -1;
  }
  PythonStreamReadMethod read_method = PythonStreamReadMethod::ReadInto;
  if (!resolve_python_stream_read_method(stream, &read_method, error_message)) {
    return -1;
  }
  try {
    auto stream_reader =
        std::make_shared<PythonStreamReader>(stream, buffer_size, read_method);
    *out = new Buffer(*stream_reader);
    return 0;
  } catch (const std::exception &e) {
    *error_message = e.what();
    return -1;
  }
}
} // namespace fory
