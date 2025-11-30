# Fory C++ CMake Example

This example demonstrates how to integrate Fory C++ into your CMake-based project.

## Prerequisites

- CMake 3.16 or higher
- C++17 compatible compiler (GCC 7+, Clang 5+, MSVC 2017+)

## Using Fory in Your CMake Project

### Option 1: FetchContent (Recommended)

The easiest way to use Fory is with CMake's `FetchContent` module, similar to how you might use Abseil or GoogleTest:

```cmake
include(FetchContent)
FetchContent_Declare(
    fory
    GIT_REPOSITORY https://github.com/apache/fory.git
    GIT_TAG        main  # or a specific version tag
    SOURCE_SUBDIR  cpp
)
FetchContent_MakeAvailable(fory)

# Link to your target
target_link_libraries(your_app PRIVATE fory::serialization)
```

### Option 2: find_package (System Installation)

If Fory is installed system-wide:

```cmake
find_package(Fory REQUIRED)
target_link_libraries(your_app PRIVATE fory::serialization)
```

To install Fory system-wide:

```bash
cd fory/cpp
mkdir build && cd build
cmake .. -DCMAKE_INSTALL_PREFIX=/usr/local
cmake --build .
cmake --install .
```

### Option 3: add_subdirectory (Local Source)

For development or when you have Fory source locally:

```cmake
add_subdirectory(/path/to/fory/cpp ${CMAKE_BINARY_DIR}/fory)
target_link_libraries(your_app PRIVATE fory::serialization)
```

## Building This Example

```bash
cd examples/cpp/cmake_example
mkdir build && cd build
cmake ..
cmake --build .
```

Or use the provided script:

```bash
./run.sh
```

## Running the Examples

After building:

```bash
# Run serialization example
./serialization_example

# Run row format example
./row_format_example
```

## Example Overview

### serialization_example.cpp

Demonstrates Fory's high-performance serialization:

- Primitive types (int, float, string)
- Collections (vector, map)
- Custom structs with `FORY_STRUCT` macro
- Nested structs
- Enums

Key concepts:

- Use `FORY_STRUCT(StructName, field1, field2, ...)` to register struct fields
- Create Fory instance with `Fory::builder().xlang(true).build()`
- Register struct types with `fory.register_struct<Type>(type_id)`
- Serialize with `fory.serialize(obj)`
- Deserialize with `fory.deserialize<Type>(data, size)`

### row_format_example.cpp

Demonstrates Fory's row format for random access:

- Manual row writing with schema
- Automatic encoding with `RowEncoder`
- Nested struct encoding
- Array encoding
- Direct array creation from vectors

Key concepts:

- Use `FORY_FIELD_INFO(StructName, field1, field2, ...)` for row encoding
- Create schema with `schema({field("name", type()), ...})`
- Write rows with `RowWriter`
- Encode structs automatically with `encoder::RowEncoder<T>`
- Read fields with `row->GetString(index)`, `row->GetInt32(index)`, etc.

## Using Fory in Your Project

### Linking to Fory Libraries

Fory provides three main CMake targets:

```cmake
# For serialization only
target_link_libraries(your_app PRIVATE fory::serialization)

# For row format (includes serialization)
target_link_libraries(your_app PRIVATE fory::row_format)

# For everything
target_link_libraries(your_app PRIVATE fory::fory)
```

### Including Headers

```cpp
// For serialization
#include "fory/serialization/fory.h"

// For row format
#include "fory/row/row.h"
#include "fory/row/schema.h"
#include "fory/row/writer.h"
#include "fory/encoder/row_encoder.h"
#include "fory/encoder/row_encode_trait.h"
```

## Build Options

When building Fory, you can configure these options:

| Option              | Default | Description            |
| ------------------- | ------- | ---------------------- |
| `FORY_BUILD_TESTS`  | OFF     | Build Fory tests       |
| `FORY_BUILD_SHARED` | ON      | Build shared libraries |
| `FORY_BUILD_STATIC` | ON      | Build static libraries |

Example:

```bash
cmake -DFORY_BUILD_TESTS=ON ..
```
