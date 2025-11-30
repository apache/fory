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

#include "fory/encoder/row_encode_trait.h"
#include "fory/row/writer.h"
#include <memory>
#include <type_traits>

namespace fory {
namespace row {

namespace encoder {

namespace details {

template <typename T, typename Enabled = void> struct GetWriterTypeImpl;

template <typename T>
struct GetWriterTypeImpl<T,
                         std::enable_if_t<details::IsClassButNotBuiltin<T>>> {
  using type = RowWriter;
};

template <typename T>
struct GetWriterTypeImpl<T, std::enable_if_t<details::IsArray<T>>> {
  using type = ArrayWriter;
};

template <typename T> using GetWriterType = typename GetWriterTypeImpl<T>::type;

template <typename T, std::enable_if_t<
                          std::is_same_v<GetWriterType<T>, RowWriter>, int> = 0>
auto GetSchemaOrType() {
  return RowEncodeTrait<T>::Schema();
}

template <
    typename T,
    std::enable_if_t<std::is_same_v<GetWriterType<T>, ArrayWriter>, int> = 0>
auto GetSchemaOrType() {
  return std::dynamic_pointer_cast<ListType>(RowEncodeTrait<T>::Type());
}

} // namespace details

template <typename T> struct RowEncoder {
  static_assert(details::IsClassButNotBuiltin<T> || details::IsArray<T>,
                "only class types and iterable types are supported");

  using WriterType = details::GetWriterType<T>;

  RowEncoder()
      : writer_(std::make_unique<WriterType>(details::GetSchemaOrType<T>())) {}

  template <typename U = WriterType,
            std::enable_if_t<std::is_same_v<U, RowWriter>, int> = 0>
  void Encode(const T &value) {
    writer_->Reset();
    RowEncodeTrait<T>::Write(DefaultWriteVisitor{children_}, value,
                             GetWriter());
  }

  template <typename U = WriterType,
            std::enable_if_t<std::is_same_v<U, ArrayWriter>, int> = 0>
  void Encode(const T &value) {
    writer_->Reset(value.size());
    RowEncodeTrait<T>::Write(DefaultWriteVisitor{children_}, value,
                             GetWriter());
  }

  WriterType &GetWriter() const { return *writer_.get(); }
  const std::vector<std::unique_ptr<Writer>> &GetChildren() const {
    return children_;
  }

  template <typename U = WriterType,
            std::enable_if_t<std::is_same_v<U, RowWriter>, int> = 0>
  const Schema &GetSchema() const {
    return *writer_->schema().get();
  }

  template <typename U = WriterType,
            std::enable_if_t<std::is_same_v<U, ArrayWriter>, int> = 0>
  const ListType &GetType() const {
    return *writer_->type().get();
  }

  void ResetChildren() { children_.clear(); }

private:
  std::unique_ptr<WriterType> writer_;
  std::vector<std::unique_ptr<Writer>> children_;
};

} // namespace encoder

} // namespace row
} // namespace fory
