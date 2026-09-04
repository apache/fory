---
title: C++ Setup
sidebar_position: 3
id: cpp
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Fory C++ provides binary Object Serialization, Row Format, generated models,
and Fory gRPC. It requires a C++17 compiler and supports CMake 3.16 or later
and Bazel 8 or later.

## Verify the Toolchain

```bash
c++ --version
cmake --version
# or: bazel --version
```

## Object Serialization

Use xlang mode for data shared with Fory implementations in other languages or native mode for
C++-only data. Fetch a released source tree and link the serialization target:

```cmake title="CMakeLists.txt"
cmake_minimum_required(VERSION 3.16)
project(fory_example LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
include(FetchContent)
FetchContent_Declare(
  fory
  GIT_REPOSITORY https://github.com/apache/fory.git
  GIT_TAG v1.7.1
  SOURCE_SUBDIR cpp
)
FetchContent_MakeAvailable(fory)

add_executable(fory_example main.cc)
target_link_libraries(fory_example PRIVATE fory::serialization)
```

```cpp title="main.cc"
#include <cassert>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "fory/serialization/fory.h"

struct User {
  int64_t id;
  std::string name;

  bool operator==(const User &other) const {
    return id == other.id && name == other.name;
  }
};
FORY_STRUCT(User, id, name);

int main() {
  auto fory = fory::serialization::Fory::builder().xlang(true).build();
  fory.register_struct<User>(1);

  auto bytes = fory.serialize(User{1, "Alice"});
  assert(bytes.ok());
  std::vector<uint8_t> data = std::move(bytes).value();
  auto decoded = fory.deserialize<User>(data);
  assert(decoded.ok());
  User user = std::move(decoded).value();
  assert(user.id == 1 && user.name == "Alice");
}
```

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
./build/fory_example
```

See [C++ Object Serialization](../object-serialization/cpp/index.md) for Bazel,
Windows, error handling, and thread-safe instances; then continue to
[xlang](../object-serialization/cpp/basic-serialization.md#cross-language-interoperability) or
[native mode](../object-serialization/cpp/native.md).

## Other Capabilities

- **Row Format** provides random and partial field access for trusted analytical data. See [C++ Row Format](../row-format/cpp.md).
- **Fory IDL and Compiler** generates C++ models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [C++ generated-code guide](../compiler/generated-code/cpp.md).
- **Fory gRPC** uses gRPC C++ transports with Fory-encoded messages. See [C++ gRPC](../grpc/cpp.md).
