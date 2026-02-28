# Apache Fory™ C++

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and type safety.

The C++ implementation provides high-performance serialization with compile-time type safety through template metaprogramming and zero-copy row format for analytics workloads.

## Why Apache Fory™ C++?

- **Blazingly Fast**: Fast serialization and optimized binary protocols
- **Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
- **Type-Safe**: Compile-time type checking with template specialization
- **Shared References**: Automatic tracking of shared and circular references
- **Schema Evolution**: Compatible mode for independent schema changes
- **Two Formats**: Object graph serialization and zero-copy row-based format
- **Modern C++17**: Clean API using modern C++ features

## Benchmark

Fory C++ demonstrates competitive performance compared to protobuf c++ serialization framework.

<p align="center">
<img src="../docs/benchmarks/cpp/throughput.png" width="95%">
</p>

For more detailed benchmarks and methodology, see [C++ Benchmarks](../benchmarks/cpp).

## Quick Start

### Basic Example

```cpp
#include "fory/serialization/fory.h"

// Define your class with FORY_STRUCT macro (struct works the same way).
// Place it after all fields.
// When used inside a class, it must be placed in a public: section.
struct Person {
  std::string name;
  int32_t age;
  std::string email;
  FORY_STRUCT(Person, name, age, email);
};

int main() {
  // Create Fory instance
  auto fory = apache::fory::ForyBuilder()
      .xlang(true)
      .track_ref(true)
      .build();

  // Register type
  fory->register_struct<Person>(1);

  // Create object
  Person person{"Alice", 30, "alice@example.com"};

  // Serialize
  auto result = fory->serialize(person);
  if (!result.ok()) {
    std::cerr << "Serialization failed: " << result.error().message() << std::endl;
    return 1;
  }
  std::vector<uint8_t> bytes = std::move(result.value());

  // Deserialize
  auto decoded = fory->deserialize<Person>(bytes);
  if (!decoded.ok()) {
    std::cerr << "Deserialization failed: " << decoded.error().message() << std::endl;
    return 1;
  }
  Person restored = decoded.value();

  assert(restored.name == "Alice");
  assert(restored.age == 30);
  return 0;
}
```

## Core Features

### 1. Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `FORY_STRUCT` macro enables compile-time reflection without runtime overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (vector, set, unordered_set)
- Map types (map, unordered_map)
- Optional fields with `std::optional<T>`
- Smart pointers (shared_ptr, unique_ptr, weak_ptr)
- Efficient binary encoding with variable-length integers

```cpp
#include "fory/serialization/fory.h"

class Address {
public:
  std::string street;
  std::string city;
  std::string country;
  FORY_STRUCT(Address, street, city, country);
};

class Person {
public:
  std::string name;
  int32_t age;
  Address address;
  std::vector<std::string> hobbies;
  std::map<std::string, std::string> metadata;
  FORY_STRUCT(Person, name, age, address, hobbies, metadata);
};

auto fory = apache::fory::ForyBuilder().build();
fory->register_struct<Address>(100);
fory->register_struct<Person>(200);

Person person{
    "John Doe",
    30,
    Address{"123 Main St", "New York", "USA"},
    {"reading", "coding"},
    {{"role", "developer"}}
};

auto bytes = fory->serialize(person).value();
auto decoded = fory->deserialize<Person>(bytes).value();
```

### 1.1 External/Third-Party Types

For third-party types where you cannot modify the class definition, use
`FORY_STRUCT` at namespace scope. This works for public fields only.

```cpp
namespace thirdparty {
struct Foo {
  int32_t id;
  std::string name;
};

FORY_STRUCT(Foo, id, name);
} // namespace thirdparty

auto fory = apache::fory::ForyBuilder().build();
fory->register_struct<thirdparty::Foo>(1);
```

### 1.2 Inherited Fields

To include base-class fields in a derived type, use `FORY_BASE(Base)` inside
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

### 2. Shared References

Apache Fory™ automatically tracks and preserves reference identity for shared objects using `std::shared_ptr<T>`. When the same object is referenced multiple times, Fory serializes it only once and uses reference IDs for subsequent occurrences.

```cpp
auto fory = apache::fory::ForyBuilder()
    .track_ref(true)
    .build();

// Create a shared value
auto shared = std::make_shared<std::string>("shared_value");

// Reference it multiple times
std::vector<std::shared_ptr<std::string>> data = {shared, shared, shared};

// The shared value is serialized only once
auto bytes = fory->serialize(data).value();
auto decoded = fory->deserialize<std::vector<std::shared_ptr<std::string>>>(bytes).value();

// Verify reference identity is preserved
assert(decoded[0].get() == decoded[1].get());
assert(decoded[1].get() == decoded[2].get());
```

### 3. Schema Evolution

Apache Fory™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions.

```cpp
// Version 1
struct PersonV1 {
  std::string name;
  int32_t age;
  std::string address;
  FORY_STRUCT(PersonV1, name, age, address);
};

// Version 2 - address removed, phone added
struct PersonV2 {
  std::string name;
  int32_t age;
  std::optional<std::string> phone;
  FORY_STRUCT(PersonV2, name, age, phone);
};

auto fory1 = apache::fory::ForyBuilder()
    .compatible(true)
    .build();
fory1->register_struct<PersonV1>(1);

auto fory2 = apache::fory::ForyBuilder()
    .compatible(true)
    .build();
fory2->register_struct<PersonV2>(1);

PersonV1 v1{"Alice", 30, "123 Main St"};
auto bytes = fory1->serialize(v1).value();

// Deserialize with V2 - missing fields get default values
auto v2 = fory2->deserialize<PersonV2>(bytes).value();
assert(v2.name == "Alice");
assert(v2.phone == std::nullopt);
```

### 4. Enum Support

Apache Fory™ supports enum serialization with automatic ordinal mapping:

```cpp
// Continuous enum - works automatically
enum class Color { Red, Green, Blue };

// Non-continuous enum - needs FORY_ENUM
enum class LegacyStatus { Active = 1, Inactive = 5, Pending = 10 };
FORY_ENUM(LegacyStatus, Active, Inactive, Pending);
// FORY_ENUM must be defined at namespace scope.

struct Item {
  std::string name;
  Color color;
  LegacyStatus status;
  FORY_STRUCT(Item, name, color, status);
};
```

### 5. Variant Support

Apache Fory™ supports `std::variant` for type-safe union types:

```cpp
using Value = std::variant<std::monostate, bool, int32_t, std::string>;

struct Config {
  std::string key;
  Value value;
  FORY_STRUCT(Config, key, value);
};

Config config{"timeout", 30};
auto bytes = fory->serialize(config).value();
```

### 6. Row-Based Serialization

Apache Fory™ provides a high-performance **row format** for zero-copy deserialization, enabling random access to fields directly from binary data.

```cpp
#include "fory/encoder/row_encoder.h"

struct UserProfile {
  int64_t id;
  std::string username;
  std::string email;
  std::vector<int32_t> scores;
  bool is_active;
  FORY_STRUCT(UserProfile, id, username, email, scores, is_active);
};

apache::fory::RowEncoder<UserProfile> encoder;

UserProfile profile{12345, "alice", "alice@example.com", {95, 87, 92}, true};

// encode to row format
encoder.encode(profile);
auto& writer = encoder.get_writer();

// Access fields directly without full deserialization
auto row = writer.to_row();
assert(row.get_int64(0) == 12345);           // id
assert(row.get_string(1) == "alice");         // username
assert(row.get_bool(4) == true);              // is_active
```

## Cross-Language Serialization

Apache Fory™ supports seamless data exchange across multiple languages:

```cpp
// Enable cross-language mode
auto fory = apache::fory::ForyBuilder()
    .xlang(true)
    .compatible(true)
    .build();

// Register types with consistent IDs across languages
fory->register_struct<MyStruct>(1);
```

See [xlang_type_mapping.md](https://fory.apache.org/docs/specification/xlang_type_mapping) for type mapping across languages.

## Thread Safety

```cpp
// Single-threaded (fastest performance)
auto fory = apache::fory::ForyBuilder().build();

// Thread-safe with internal Fory pool
auto fory = apache::fory::ForyBuilder().build_thread_safe();
```

## Architecture

The C++ implementation consists of these main components:

```
cpp/fory/
├── serialization/           # Object graph serialization
│   ├── fory.h              # Main entry point (Fory, ThreadSafeFory)
│   ├── config.h            # Configuration options
│   ├── serializer.h        # Core serializer API
│   ├── basic_serializer.h  # Primitive type serializers
│   ├── string_serializer.h # String type serializers
│   ├── struct_serializer.h # Struct serialization with FORY_STRUCT
│   ├── collection_serializer.h  # vector, set serializers
│   ├── map_serializer.h    # map serializers
│   ├── smart_ptr_serializers.h  # optional, shared_ptr, unique_ptr
│   ├── temporal_serializers.h   # Duration, Timestamp, Date
│   ├── variant_serializer.h     # std::variant support
│   ├── type_resolver.h     # Type resolution and registration
│   └── context.h           # Read/write context
├── encoder/                 # Row format encoding
│   ├── row_encoder.h       # Row format encoder
│   └── row_encode_trait.h  # Encoding traits
├── row/                     # Row format data structures
│   ├── row.h               # Row, ArrayData, MapData
│   ├── writer.h            # RowWriter, ArrayWriter
│   ├── schema.h            # Schema definitions
│   └── type.h              # Type definitions
├── meta/                    # Compile-time reflection
│   ├── field_info.h        # Field metadata extraction
│   └── type_traits.h       # Type traits utilities
└── util/                    # Common utilities
    ├── buffer.h            # Binary buffer management
    ├── error.h             # Error handling
    └── status.h            # Status codes
```

## Environment

- **C++ Standard**: C++17 or later
- **Build System**: Bazel 8.2.1+ or CMake 3.16+

## Building

### With Bazel

```bash
# Build all projects
bazel build //cpp/...

# Run all tests
bazel test $(bazel query //cpp/...)

# Run serialization tests
bazel test $(bazel query //cpp/fory/serialization/...)
```

### With CMake

```bash
mkdir build && cd build
cmake ..
make -j$(nproc)
```

## Code Quality

```bash
# Format code
clang-format -i <file>

# Or use the CI script
bash ci/format.sh --cpp
```

## Documentation

- **[User Guide](https://fory.apache.org/docs/guide/cpp)** - Comprehensive user documentation
- **[Protocol Specification](https://fory.apache.org/docs/specification/fory_xlang_serialization_spec)** - Serialization protocol details
- **[Type Mapping](https://fory.apache.org/docs/specification/xlang_type_mapping)** - Cross-language type mappings
- **[Source](https://github.com/apache/fory/tree/main/docs/guide/cpp)** - Documentation source

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

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.

## Contributing

We welcome contributions! Please see our [Contributing Guide](https://github.com/apache/fory/blob/main/CONTRIBUTING.md) for details.

## Support

- **Issues**: [GitHub Issues](https://github.com/apache/fory/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/fory/discussions)
- **Slack**: [Apache Fory Slack](https://join.slack.com/t/fory-project/shared_invite/zt-1u8soj4qc-ieYEu7ciHOqA2mo47llS8A)

---

**Apache Fory™** - Blazingly fast multi-language serialization framework.
