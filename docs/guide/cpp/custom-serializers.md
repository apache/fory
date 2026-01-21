---
title: Custom Serializers
sidebar_position: 6
id: custom_serializers
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

For types that don't support `FORY_STRUCT`, implement a `Serializer` template specialization manually.

## When to Use Custom Serializers

- External types from third-party libraries
- Types with special serialization requirements
- Legacy data format compatibility
- Performance-critical custom encoding
- Cross-language interoperability with custom protocols

## Implementing the Serializer Template

To create a custom serializer, specialize the `Serializer` template for your type within the `fory::serialization` namespace:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Define your custom type
struct MyExt {
  int32_t id;
  bool operator==(const MyExt &other) const { return id == other.id; }
};

namespace fory {
namespace serialization {

template <>
struct Serializer<MyExt> {
  // Declare as extension type for custom serialization
  static constexpr TypeId type_id = TypeId::EXT;

  // Main write method - handles null checking and type info
  static void write(const MyExt &value, WriteContext &ctx, RefMode ref_mode,
                    bool write_type, bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      auto result = ctx.write_any_typeinfo(
          static_cast<uint32_t>(TypeId::UNKNOWN),
          std::type_index(typeid(MyExt)));
      if (!result.ok()) {
        ctx.set_error(std::move(result).error());
        return;
      }
    }
    write_data(value, ctx);
  }

  // Write only the data (no type info)
  static void write_data(const MyExt &value, WriteContext &ctx) {
    Serializer<int32_t>::write_data(value.id, ctx);
  }

  // Write data with generics support
  static void write_data_generic(const MyExt &value, WriteContext &ctx,
                                 bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  // Main read method - handles null checking and type info
  static MyExt read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return MyExt{};
    }
    if (read_type) {
      const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
      if (ctx.has_error()) {
        return MyExt{};
      }
      if (!type_info) {
        ctx.set_error(Error::type_error("TypeInfo for MyExt not found"));
        return MyExt{};
      }
    }
    return read_data(ctx);
  }

  // Read only the data (no type info)
  static MyExt read_data(ReadContext &ctx) {
    MyExt value;
    value.id = Serializer<int32_t>::read_data(ctx);
    return value;
  }

  // Read data with generics support
  static MyExt read_data_generic(ReadContext &ctx, bool has_generics) {
    (void)has_generics;
    return read_data(ctx);
  }

  // Read with pre-resolved type info
  static MyExt read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                   const TypeInfo &type_info) {
    (void)type_info;
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory
```

## Required Methods

A custom serializer must implement these static methods:

| Method                | Purpose                                         |
| --------------------- | ----------------------------------------------- |
| `write`               | Main serialization entry point with type info   |
| `write_data`          | Serialize data only (no type info)              |
| `write_data_generic`  | Serialize data with generics support            |
| `read`                | Main deserialization entry point with type info |
| `read_data`           | Deserialize data only (no type info)            |
| `read_data_generic`   | Deserialize data with generics support          |
| `read_with_type_info` | Deserialize with pre-resolved TypeInfo          |

The `type_id` constant should be set to `TypeId::EXT` for custom extension types.

## Registering Custom Serializers

Register your custom serializer with Fory before use:

```cpp
auto fory = Fory::builder().xlang(true).build();

// Register with numeric type ID (must match across languages)
auto result = fory.register_extension_type<MyExt>(103);
if (!result.ok()) {
  std::cerr << "Failed to register: " << result.error().to_string() << std::endl;
}

// Or register with type name for named type systems
fory.register_extension_type<MyExt>("my_ext");

// Or with namespace and type name
fory.register_extension_type<MyExt>("com.example", "MyExt");
```

## Complete Example

```cpp
#include "fory/serialization/fory.h"
#include <iostream>

using namespace fory::serialization;

struct CustomType {
  int32_t value;
  std::string name;

  bool operator==(const CustomType &other) const {
    return value == other.value && name == other.name;
  }
};

namespace fory {
namespace serialization {

template <>
struct Serializer<CustomType> {
  static constexpr TypeId type_id = TypeId::EXT;

  static void write(const CustomType &value, WriteContext &ctx,
                    RefMode ref_mode, bool write_type, bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      auto result = ctx.write_any_typeinfo(
          static_cast<uint32_t>(TypeId::UNKNOWN),
          std::type_index(typeid(CustomType)));
      if (!result.ok()) {
        ctx.set_error(std::move(result).error());
        return;
      }
    }
    write_data(value, ctx);
  }

  static void write_data(const CustomType &value, WriteContext &ctx) {
    // Write value as varint for compact encoding
    Serializer<int32_t>::write_data(value.value, ctx);
    // Delegate string serialization to built-in serializer
    Serializer<std::string>::write_data(value.name, ctx);
  }

  static void write_data_generic(const CustomType &value, WriteContext &ctx,
                                 bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  static CustomType read(ReadContext &ctx, RefMode ref_mode, bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return CustomType{};
    }
    if (read_type) {
      const TypeInfo *type_info = ctx.read_any_typeinfo(ctx.error());
      if (ctx.has_error()) {
        return CustomType{};
      }
      if (!type_info) {
        ctx.set_error(Error::type_error("TypeInfo for CustomType not found"));
        return CustomType{};
      }
    }
    return read_data(ctx);
  }

  static CustomType read_data(ReadContext &ctx) {
    CustomType value;
    value.value = Serializer<int32_t>::read_data(ctx);
    value.name = Serializer<std::string>::read_data(ctx);
    return value;
  }

  static CustomType read_data_generic(ReadContext &ctx, bool has_generics) {
    (void)has_generics;
    return read_data(ctx);
  }

  static CustomType read_with_type_info(ReadContext &ctx, RefMode ref_mode,
                                        const TypeInfo &type_info) {
    (void)type_info;
    return read(ctx, ref_mode, false);
  }
};

} // namespace serialization
} // namespace fory

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_extension_type<CustomType>(100);

  CustomType original{42, "test"};

  auto serialized = fory.serialize(original);
  if (!serialized.ok()) {
    std::cerr << "Serialization failed" << std::endl;
    return 1;
  }

  auto deserialized = fory.deserialize<CustomType>(serialized.value());
  if (!deserialized.ok()) {
    std::cerr << "Deserialization failed" << std::endl;
    return 1;
  }

  assert(original == deserialized.value());
  std::cout << "Custom serializer works!" << std::endl;
  return 0;
}
```

## WriteContext Methods

The `WriteContext` provides methods for writing data:

```cpp
// Primitive types
ctx.write_uint8(value);
ctx.write_int8(value);
ctx.write_uint16(value);

// Variable-length integers (compact encoding)
ctx.write_varuint32(value);   // Unsigned varint
ctx.write_varint32(value);    // Signed zigzag varint
ctx.write_varuint64(value);   // Unsigned varint
ctx.write_varint64(value);    // Signed zigzag varint

// Tagged integers (for mixed-size encoding)
ctx.write_tagged_uint64(value);
ctx.write_tagged_int64(value);

// Raw bytes
ctx.write_bytes(data_ptr, length);

// Access underlying buffer for advanced operations
ctx.buffer().WriteInt32(value);
ctx.buffer().WriteFloat(value);
ctx.buffer().WriteDouble(value);
```

## ReadContext Methods

The `ReadContext` provides methods for reading data:

```cpp
// Primitive types (use error reference pattern)
uint8_t u8 = ctx.read_uint8(ctx.error());
int8_t i8 = ctx.read_int8(ctx.error());

// Variable-length integers
uint32_t u32 = ctx.read_varuint32(ctx.error());
int32_t i32 = ctx.read_varint32(ctx.error());
uint64_t u64 = ctx.read_varuint64(ctx.error());
int64_t i64 = ctx.read_varint64(ctx.error());

// Check for errors after read operations
if (ctx.has_error()) {
  return MyType{};  // Return default on error
}

// Access underlying buffer for advanced operations
int32_t value = ctx.buffer().ReadInt32(ctx.error());
float f = ctx.buffer().ReadFloat(ctx.error());
double d = ctx.buffer().ReadDouble(ctx.error());
```

## Delegating to Built-in Serializers

Reuse existing serializers for nested types:

```cpp
static void write_data(const MyType &value, WriteContext &ctx) {
  // Delegate to built-in serializers
  Serializer<int32_t>::write_data(value.int_field, ctx);
  Serializer<std::string>::write_data(value.string_field, ctx);
  Serializer<std::vector<int32_t>>::write_data(value.vec_field, ctx);
}

static MyType read_data(ReadContext &ctx) {
  MyType value;
  value.int_field = Serializer<int32_t>::read_data(ctx);
  value.string_field = Serializer<std::string>::read_data(ctx);
  value.vec_field = Serializer<std::vector<int32_t>>::read_data(ctx);
  return value;
}
```

## Best Practices

1. **Use variable-length encoding** for integers that may be small
2. **Check errors after read operations** using `ctx.has_error()`
3. **Return default values on error** to maintain consistent behavior
4. **Delegate to built-in serializers** for standard types
5. **Match type IDs across languages** for cross-language compatibility
6. **Use `(void)param`** to suppress unused parameter warnings

## Related Topics

- [Type Registration](type-registration.md) - Registering serializers
- [Basic Serialization](basic-serialization.md) - Using FORY_STRUCT macro
- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Cross-Language](cross-language.md) - Cross-language serialization
