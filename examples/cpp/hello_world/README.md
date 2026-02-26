# Fory C++ Hello World (Serialization)

This example demonstrates how to use Fory's high-performance serialization for C++ objects.

## Prerequisites

- CMake 3.16 or higher (for CMake build)
- Bazel 8+ (for Bazel build)
- C++17 compatible compiler (GCC 7+, Clang 5+, MSVC 2017+)

## Building with CMake

### Option 1: Using FetchContent (Recommended)

The easiest way to use Fory is with CMake's `FetchContent` module:

```cmake
include(FetchContent)
FetchContent_Declare(
    fory
    GIT_REPOSITORY https://github.com/apache/fory.git
    GIT_TAG        main
    SOURCE_SUBDIR  cpp
)
FetchContent_MakeAvailable(fory)

target_link_libraries(your_app PRIVATE fory::serialization)
```

### Build and Run

```bash
cd examples/cpp/hello_world
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --parallel
./hello_world
```

Or use the provided script:

```bash
./run.sh
```

## Building with Bazel

### In-repo build (for testing within Fory repository)

From the repository root:

```bash
bazel build //examples/cpp/hello_world:hello_world
bazel run //examples/cpp/hello_world:hello_world
```

Or use the provided script:

```bash
./run_bazel.sh
```

### Standalone project (using Fory as external dependency)

For your own project using Fory as a dependency:

1. Copy `MODULE.bazel.example` to `MODULE.bazel` in your project root
2. Copy `BUILD.standalone` to `BUILD` in your source directory
3. Adjust the git commit or use `local_path_override` for local development

```bazel
# In your MODULE.bazel
bazel_dep(name = "fory", version = "0.15.0")
git_override(
    module_name = "fory",
    remote = "https://github.com/apache/fory.git",
    commit = "main",  # Use specific commit for reproducibility
)

# In your BUILD file
cc_binary(
    name = "my_app",
    srcs = ["main.cc"],
    deps = ["@fory//cpp/fory/serialization:fory_serialization"],
)
```

## Example Overview

This example demonstrates:

- **Primitive types**: Serializing integers, floats, and basic types
- **Strings**: High-performance string serialization
- **Collections**: Vectors and maps
- **Custom structs**: Using `FORY_STRUCT` macro to register struct fields
- **Nested structs**: Structs containing other structs
- **Enums**: Enum serialization

## Key Concepts

### Registering Structs

Use the `FORY_STRUCT` macro to register struct fields for serialization:

```cpp
struct Point {
  int32_t x;
  int32_t y;
  FORY_STRUCT(Point, x, y);
};
```

### Creating a Fory Instance

```cpp
auto fory = fory::serialization::Fory::builder()
                .xlang(true)      // Enable cross-language serialization
                .track_ref(false) // Disable reference tracking
                .build();

// Register struct types with unique IDs
fory.register_struct<Point>(1);
```

### Serialization and Deserialization

```cpp
// Serialize
Point point{10, 20};
auto bytes_result = fory.serialize(point);
if (bytes_result.ok()) {
    auto bytes = bytes_result.value();
    // Use bytes...
}

// Deserialize
auto result = fory.deserialize<Point>(bytes.data(), bytes.size());
if (result.ok()) {
    Point deserialized = result.value();
}
```

## Using Fory in Your Project

### CMake Integration

```cmake
# Link to serialization library
target_link_libraries(your_app PRIVATE fory::serialization)
```

### Including Headers

```cpp
#include "fory/serialization/fory.h"
```
