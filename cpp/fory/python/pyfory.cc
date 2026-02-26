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

#include "fory/util/stream.h"

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
    const uint32_t read_pos = buffer_->reader_index_;
    const uint32_t total_length = length;
    uint32_t available = remaining_size();
    if (available >= length) {
      std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
                  static_cast<size_t>(length));
      buffer_->reader_index_ += length;
      return Result<void, Error>();
    }

    if (available > 0) {
      std::memcpy(dst, buffer_->data_ + buffer_->reader_index_,
                  static_cast<size_t>(available));
      buffer_->reader_index_ += available;
      dst += available;
      length -= available;
    }

    uint32_t copied = 0;
    while (copied < length) {
      auto read_result = recv_into(dst + copied, length - copied);
      if (FORY_PREDICT_FALSE(!read_result.ok())) {
        return Unexpected(std::move(read_result).error());
      }
      uint32_t read_bytes = std::move(read_result).value();
      if (read_bytes == 0) {
        return Unexpected(
            Error::buffer_out_of_bound(read_pos, total_length, buffer_->size_));
      }
      copied += read_bytes;
    }
    return Result<void, Error>();
  }

  Result<void, Error> skip(uint32_t size) override {
    const uint32_t read_pos = buffer_->reader_index_;
    const uint32_t total_size = size;
    uint32_t available = remaining_size();
    if (available >= size) {
      buffer_->reader_index_ += size;
      return Result<void, Error>();
    }

    buffer_->reader_index_ += available;
    size -= available;

    char discard[4096];
    uint32_t skipped = 0;
    while (skipped < size) {
      const uint32_t chunk = std::min<uint32_t>(
          size - skipped, static_cast<uint32_t>(sizeof(discard)));
      auto read_result = recv_into(discard, chunk);
      if (FORY_PREDICT_FALSE(!read_result.ok())) {
        return Unexpected(std::move(read_result).error());
      }
      uint32_t read_bytes = std::move(read_result).value();
      if (read_bytes == 0) {
        return Unexpected(
            Error::buffer_out_of_bound(read_pos, total_size, buffer_->size_));
      }
      skipped += read_bytes;
    }
    return Result<void, Error>();
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
  Buffer *buffer_ = nullptr;
  std::unique_ptr<Buffer> owned_buffer_;
};

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
