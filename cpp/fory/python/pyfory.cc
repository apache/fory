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
#include <istream>
#include <streambuf>

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

class PythonInputStreamBuf final : public std::streambuf {
public:
  explicit PythonInputStreamBuf(PyObject *stream) : stream_(stream) {
    Py_INCREF(stream_);
  }

  ~PythonInputStreamBuf() override {
    if (stream_ != nullptr) {
      PyGILState_STATE gil_state = PyGILState_Ensure();
      Py_DECREF(stream_);
      PyGILState_Release(gil_state);
      stream_ = nullptr;
    }
  }

  bool has_error() const { return has_error_; }

  const std::string &error_message() const { return error_message_; }

protected:
  std::streamsize xsgetn(char *s, std::streamsize count) override {
    if (count <= 0 || has_error_) {
      return 0;
    }
    PyGILState_STATE gil_state = PyGILState_Ensure();
    const Py_ssize_t requested = static_cast<Py_ssize_t>(
        std::min<std::streamsize>(count, PY_SSIZE_T_MAX));
    PyObject *chunk = PyObject_CallMethod(stream_, "read", "n", requested);
    if (chunk == nullptr) {
      has_error_ = true;
      error_message_ = fetch_python_error_message();
      PyGILState_Release(gil_state);
      return 0;
    }
    Py_buffer view;
    if (PyObject_GetBuffer(chunk, &view, PyBUF_CONTIG_RO) != 0) {
      has_error_ = true;
      error_message_ = fetch_python_error_message();
      Py_DECREF(chunk);
      PyGILState_Release(gil_state);
      return 0;
    }
    const std::streamsize read_bytes = std::min<std::streamsize>(
        count, static_cast<std::streamsize>(view.len));
    if (read_bytes > 0) {
      std::memcpy(s, view.buf, static_cast<size_t>(read_bytes));
    }
    PyBuffer_Release(&view);
    Py_DECREF(chunk);
    PyGILState_Release(gil_state);
    return read_bytes;
  }

  int_type underflow() override {
    if (gptr() < egptr()) {
      return traits_type::to_int_type(*gptr());
    }
    if (xsgetn(&current_, 1) != 1) {
      return traits_type::eof();
    }
    setg(&current_, &current_, &current_ + 1);
    return traits_type::to_int_type(current_);
  }

private:
  PyObject *stream_ = nullptr;
  bool has_error_ = false;
  std::string error_message_;
  char current_ = 0;
};

class PythonInputStream final : public std::istream {
public:
  explicit PythonInputStream(PyObject *stream)
      : std::istream(nullptr), buf_(stream) {
    rdbuf(&buf_);
  }

  bool has_error() const { return buf_.has_error(); }

  const std::string &error_message() const { return buf_.error_message(); }

private:
  PythonInputStreamBuf buf_;
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
  const int has_read = PyObject_HasAttrString(stream, "read");
  if (has_read < 0) {
    *error_message = fetch_python_error_message();
    return -1;
  }
  if (has_read == 0) {
    *error_message = "stream object must provide read(size) method";
    return -1;
  }
  try {
    auto py_stream = std::make_shared<PythonInputStream>(stream);
    auto source_stream = std::static_pointer_cast<std::istream>(py_stream);
    auto fory_stream = std::make_shared<ForyInputStream>(
        std::move(source_stream), buffer_size);
    *out = new Buffer(*fory_stream);
    return 0;
  } catch (const std::exception &e) {
    *error_message = e.what();
    return -1;
  }
}
} // namespace fory
