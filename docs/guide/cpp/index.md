---
title: C++ Serialization Guide
sidebar_position: 0
id: serialization_index
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

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

The C++ implementation provides high-performance serialization with compile-time type safety using modern C++17 features and template metaprogramming. It supports both xlang mode for cross-language payloads and native mode for C++-only payloads.

## Why Apache Fory™ C++?

- **Fast binary encoding**: Fast serialization and optimized binary protocols
- **Xlang**: Seamlessly serialize/deserialize data across Java, Python, C++, Go,
  Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin
- **Type-safe**: Compile-time type checking with macro-based struct registration
- **Reference tracking**: Automatic tracking of shared and circular references
- **Schema evolution**: Compatible mode for independent schema changes
- **Two formats**: Object graph serialization and zero-copy row-based format
- **Thread safety**: Both single-threaded and thread-safe variants

## Installation

The C++ implementation supports both CMake and Bazel build systems.

### Prerequisites

- CMake 3.16+ (for CMake build) or Bazel 8+ (for Bazel build)
- C++17 compatible compiler (GCC 7+, Clang 5+, MSVC 2017+)

When building with MSVC, configure your build system to pass
`/Zc:preprocessor`.

### Using CMake (Recommended)

The easiest way to use Fory is with CMake's `FetchContent` module:

```cmake
cmake_minimum_required(VERSION 3.16)
project(my_project LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
    add_compile_options(/Zc:preprocessor)
endif()
include(FetchContent)
FetchContent_Declare(
    fory
    GIT_REPOSITORY https://github.com/apache/fory.git
    GIT_TAG        v1.0.0
    SOURCE_SUBDIR  cpp
)
FetchContent_MakeAvailable(fory)

add_executable(my_app main.cc)
target_link_libraries(my_app PRIVATE fory::serialization)
```

Then build and run:

```bash
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --parallel
./my_app
```

### Using Bazel

Create a `MODULE.bazel` file in your project root:

```bazel
module(
    name = "my_project",
    version = "1.0.0",
)

bazel_dep(name = "rules_cc", version = "0.1.1")

bazel_dep(name = "fory", version = "1.0.0")
git_override(
    module_name = "fory",
    remote = "https://github.com/apache/fory.git",
    commit = "v1.0.0",  # Or use a specific commit hash for reproducibility
)
```

Create a `BUILD` file for your application:

```bazel
cc_binary(
    name = "my_app",
    srcs = ["main.cc"],
    deps = ["@fory//cpp/fory/serialization:fory_serialization"],
)
```

When building with MSVC, add the conforming preprocessor option to your Bazel
configuration:

```bazel
# .bazelrc
build --cxxopt=/Zc:preprocessor
```

Then build and run:

```bash
bazel build //:my_app
bazel run //:my_app
```

For local development, you can use `local_path_override` instead:

```bazel
bazel_dep(name = "fory", version = "1.0.0")
local_path_override(
    module_name = "fory",
    path = "/path/to/fory",
)
```

### Examples

See the [examples/cpp](https://github.com/apache/fory/tree/main/examples/cpp) directory for complete working examples:

- [hello_world](https://github.com/apache/fory/tree/main/examples/cpp/hello_world) - Object graph serialization
- [hello_row](https://github.com/apache/fory/tree/main/examples/cpp/hello_row) - Row format encoding

## Quick Start

### Basic Example

```cpp
#include "fory/serialization/fory.h"
#include <string>
#include <vector>

using namespace fory::serialization;

// Define a struct
struct Person {
  std::string name;
  int32_t age;
  std::vector<std::string> hobbies;

  bool operator==(const Person &other) const {
    return name == other.name && age == other.age && hobbies == other.hobbies;
  }
};
FORY_STRUCT(Person, name, age, hobbies);

int main() {
  // Create an xlang Fory instance
  auto fory = Fory::builder()
      .xlang(true)
      .track_ref(false)     // Disable reference tracking for simple types
      .build();

  // Register the type with a unique ID
  fory.register_struct<Person>(1);

  // Create an object
  Person person{"Alice", 30, {"reading", "coding"}};

  // Serialize
  auto result = fory.serialize(person);
  if (!result.ok()) {
    // Handle error
    return 1;
  }
  std::vector<uint8_t> bytes = std::move(result).value();

  // Deserialize
  auto deser_result = fory.deserialize<Person>(bytes);
  if (!deser_result.ok()) {
    // Handle error
    return 1;
  }
  Person decoded = std::move(deser_result).value();

  assert(person == decoded);
  return 0;
}
```

### Inherited Fields

To include base-class fields in a derived type, list `FORY_BASE(Base)` inside
`FORY_STRUCT`. The base must define its own `FORY_STRUCT` so its fields can be
referenced.

```cpp
struct Base {
  int32_t id;
  FORY_STRUCT(Base, id);
};

struct Derived : Base {
  std::string name;
  FORY_STRUCT(Derived, FORY_BASE(Base), name);
};
```

## Xlang Mode And Native Mode

Use xlang mode for cross-language payloads and schemas shared with other Fory runtimes. Xlang mode is the default C++ wire mode, and C++ examples that use it set `.xlang(true)` explicitly so the mode choice is visible.

Use native mode for C++-only traffic. Native mode is selected with `.xlang(false)`, uses schema-consistent payloads unless compatible mode is enabled, and keeps C++ object serialization on the C++ runtime path. It is optimized for C++ types and avoids portable xlang type-mapping constraints when the payload never leaves C++.

See [Xlang Serialization](xlang-serialization.md) for C++ xlang registration and interoperability rules, and [Native Serialization](native-serialization.md) for C++-only payloads.

## Thread Safety

Apache Fory™ C++ provides two variants for different threading needs:

### Single-Threaded (Fastest)

```cpp
// Single-threaded Fory - fastest, NOT thread-safe
auto fory = Fory::builder().xlang(true).build();
```

### Thread-Safe

```cpp
// Thread-safe Fory - uses context pools
auto fory = Fory::builder().xlang(true).build_thread_safe();

// Can be used from multiple threads safely
std::thread t1([&]() {
  auto result = fory.serialize(obj1);
});
std::thread t2([&]() {
  auto result = fory.serialize(obj2);
});
```

**Tip:** Perform type registrations before spawning threads so every worker sees the same metadata.

## Use Cases

### Object Serialization

- Complex data structures with nested objects and references
- Cross-language communication in microservices
- General-purpose serialization with full type safety
- Schema evolution with compatible mode

### Row-Based Serialization

- High-throughput data processing
- Analytics workloads requiring fast field access
- Memory-constrained environments
- Zero-copy scenarios

## Next Steps

- [Configuration](configuration.md) - Builder options and modes
- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Xlang Serialization](xlang-serialization.md) - xlang mode and interoperability
- [Native Serialization](native-serialization.md) - C++-only serialization
- [Schema Metadata](schema-metadata.md) - Field-level metadata (nullable, ref tracking)
- [Schema Evolution](schema-evolution.md) - Compatible mode and schema changes
- [Type Registration](type-registration.md) - Registering types
- [Supported Types](supported-types.md) - All supported types
- [Custom Serializers](custom-serializers.md) - Extend serialization behavior
- [Row Format](row-format.md) - Zero-copy row-based format
