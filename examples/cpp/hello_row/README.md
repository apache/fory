# Fory C++ Hello Row (Row Format)

This example demonstrates how to use Fory's row format for cache-friendly binary random access.

## When to Use Row Format

Row format is ideal for scenarios where you need:

- **Partial serialization/deserialization**: Read specific fields without deserializing entire objects
- **Random field access**: Access fields by index without full deserialization
- **Interoperability with columnar formats**: Convert between row and column formats
- **Data processing pipelines**: Efficient data transformation and filtering

## Prerequisites

- CMake 3.16 or higher (for CMake build)
- Bazel 8+ (for Bazel build)
- C++17 compatible compiler (GCC 7+, Clang 5+, MSVC 2017+)

## Building with CMake

### Option 1: Using FetchContent (Recommended)

```cmake
include(FetchContent)
FetchContent_Declare(
    fory
    GIT_REPOSITORY https://github.com/apache/fory.git
    GIT_TAG        main
    SOURCE_SUBDIR  cpp
)
FetchContent_MakeAvailable(fory)

target_link_libraries(your_app PRIVATE fory::row_format)
```

### Build and Run

```bash
cd examples/cpp/hello_row
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --parallel
./hello_row
```

Or use the provided script:

```bash
./run.sh
```

## Building with Bazel

### In-repo build (for testing within Fory repository)

From the repository root:

```bash
bazel build //examples/cpp/hello_row:hello_row
bazel run //examples/cpp/hello_row:hello_row
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
    deps = [
        "@fory//cpp/fory/encoder:fory_encoder",
        "@fory//cpp/fory/row:fory_row_format",
    ],
)
```

## Example Overview

This example demonstrates:

- **Manual row writing**: Creating rows with schema definition
- **Automatic encoding**: Using `RowEncoder` for struct encoding
- **Nested structs**: Encoding structs containing other structs
- **Array encoding**: Encoding vectors of structs
- **Direct array creation**: Creating arrays from vectors

## Key Concepts

### Registering Struct Metadata

Use the `FORY_STRUCT` macro to enable automatic encoding:

```cpp
struct Employee {
  std::string name;
  int32_t id;
  float salary;
  FORY_STRUCT(Employee, name, id, salary);
};
```

### Manual Row Writing

```cpp
using namespace fory::row;

// Define schema
auto name_field = field("name", utf8());
auto age_field = field("age", int32());
std::vector<FieldPtr> fields = {name_field, age_field};
auto row_schema = schema(fields);

// Create and write row
RowWriter writer(row_schema);
writer.reset();
writer.write_string(0, "Alice");
writer.write(1, static_cast<int32_t>(25));

// Read back
auto row = writer.to_row();
std::cout << row->get_string(0) << std::endl;  // "Alice"
std::cout << row->get_int32(1) << std::endl;   // 25
```

### Automatic Encoding with RowEncoder

```cpp
using namespace fory::row;

Employee emp{"Bob", 1001, 75000.0f};
encoder::RowEncoder<Employee> enc;
enc.encode(emp);

auto row = enc.get_writer().to_row();
std::cout << row->get_string(0) << std::endl;  // "Bob"
std::cout << row->get_int32(1) << std::endl;   // 1001
std::cout << row->get_float(2) << std::endl;   // 75000.0
```

### Supported Types

Row format supports these field types:

| Function     | Type          |
| ------------ | ------------- |
| `int8()`     | 8-bit integer |
| `int16()`    | 16-bit int    |
| `int32()`    | 32-bit int    |
| `int64()`    | 64-bit int    |
| `float32()`  | 32-bit float  |
| `float64()`  | 64-bit float  |
| `utf8()`     | UTF-8 string  |
| `binary()`   | Binary data   |
| `list(T)`    | List of T     |
| `struct(Fs)` | Struct fields |

## Using Fory in Your Project

### CMake Integration

```cmake
# Link to row format library (includes serialization)
target_link_libraries(your_app PRIVATE fory::row_format)
```

### Including Headers

```cpp
#include "fory/row/row.h"
#include "fory/row/schema.h"
#include "fory/row/writer.h"
#include "fory/encoder/row_encoder.h"
#include "fory/encoder/row_encode_trait.h"
```
