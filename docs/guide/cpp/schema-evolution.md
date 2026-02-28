---
title: Schema Evolution
sidebar_position: 3
id: schema_evolution
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

Apache Fory™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions.

## Compatible Mode

Enable schema evolution with `compatible(true)`:

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Version 1: Original schema
struct PersonV1 {
  std::string name;
  int32_t age;
};
FORY_STRUCT(PersonV1, name, age);

// Version 2: Added email field
struct PersonV2 {
  std::string name;
  int32_t age;
  std::string email;  // NEW FIELD
};
FORY_STRUCT(PersonV2, name, age, email);

int main() {
  // Create separate Fory instances for each schema version
  auto fory_v1 = Fory::builder()
      .compatible(true)  // Enable schema evolution
      .xlang(true)
      .build();

  auto fory_v2 = Fory::builder()
      .compatible(true)
      .xlang(true)
      .build();

  // Register with the SAME type ID for schema evolution
  constexpr uint32_t PERSON_TYPE_ID = 100;
  fory_v1.register_struct<PersonV1>(PERSON_TYPE_ID);
  fory_v2.register_struct<PersonV2>(PERSON_TYPE_ID);

  // Serialize with V1
  PersonV1 v1{"Alice", 30};
  auto bytes = fory_v1.serialize(v1).value();

  // Deserialize as V2 - email gets default value (empty string)
  auto v2 = fory_v2.deserialize<PersonV2>(bytes).value();
  assert(v2.name == "Alice");
  assert(v2.age == 30);
  assert(v2.email == "");  // Default value for missing field

  return 0;
}
```

### Disable Evolution for Stable Structs

If a struct schema is stable and will not change, you can disable evolution for that struct to avoid compatible metadata overhead. Use `FORY_STRUCT_EVOLVING` after `FORY_STRUCT`:

```cpp
struct StableMessage {
  int32_t id;
};
FORY_STRUCT(StableMessage, id);
FORY_STRUCT_EVOLVING(StableMessage, false);
```

## Schema Evolution Features

Compatible mode supports the following schema changes:

| Change Type        | Support | Behavior                                |
| ------------------ | ------- | --------------------------------------- |
| Add new fields     | ✅      | Missing fields use default values       |
| Remove fields      | ✅      | Extra fields are skipped                |
| Reorder fields     | ✅      | Fields matched by name, not position    |
| Change nullability | ✅      | `T` ↔ `std::optional<T>`                |
| Change field types | ❌      | Types must be compatible                |
| Rename fields      | ❌      | Field names must match (case-sensitive) |

## Adding Fields (Backward Compatibility)

When deserializing old data with a new schema that has additional fields:

```cpp
// Old schema (V1)
struct ProductV1 {
  std::string name;
  double price;
};
FORY_STRUCT(ProductV1, name, price);

// New schema (V2) with additional fields
struct ProductV2 {
  std::string name;
  double price;
  std::vector<std::string> tags;       // NEW
  std::map<std::string, std::string> attributes;  // NEW
};
FORY_STRUCT(ProductV2, name, price, tags, attributes);

// Serialize V1
ProductV1 v1{"Laptop", 999.99};
auto bytes = fory_v1.serialize(v1).value();

// Deserialize as V2
auto v2 = fory_v2.deserialize<ProductV2>(bytes).value();
assert(v2.name == "Laptop");
assert(v2.price == 999.99);
assert(v2.tags.empty());        // Default: empty vector
assert(v2.attributes.empty());  // Default: empty map
```

## Removing Fields (Forward Compatibility)

When deserializing new data with an old schema that has fewer fields:

```cpp
// Full schema
struct UserFull {
  int64_t id;
  std::string username;
  std::string email;
  std::string password_hash;
  int32_t login_count;
};
FORY_STRUCT(UserFull, id, username, email, password_hash, login_count);

// Minimal schema (removed 3 fields)
struct UserMinimal {
  int64_t id;
  std::string username;
};
FORY_STRUCT(UserMinimal, id, username);

// Serialize full version
UserFull full{12345, "johndoe", "john@example.com", "hash123", 42};
auto bytes = fory_full.serialize(full).value();

// Deserialize as minimal - extra fields are skipped
auto minimal = fory_minimal.deserialize<UserMinimal>(bytes).value();
assert(minimal.id == 12345);
assert(minimal.username == "johndoe");
// email, password_hash, login_count are skipped
```

## Field Reordering

In compatible mode, fields are matched by name, not by position:

```cpp
// Original field order
struct ConfigOriginal {
  std::string host;
  int32_t port;
  bool enable_ssl;
  std::string protocol;
};
FORY_STRUCT(ConfigOriginal, host, port, enable_ssl, protocol);

// Reordered fields
struct ConfigReordered {
  bool enable_ssl;      // Moved to first
  std::string protocol; // Moved to second
  std::string host;     // Moved to third
  int32_t port;         // Moved to last
};
FORY_STRUCT(ConfigReordered, enable_ssl, protocol, host, port);

// Serialize with original order
ConfigOriginal orig{"localhost", 8080, true, "https"};
auto bytes = fory_orig.serialize(orig).value();

// Deserialize with different field order - works correctly
auto reordered = fory_reord.deserialize<ConfigReordered>(bytes).value();
assert(reordered.host == "localhost");
assert(reordered.port == 8080);
assert(reordered.enable_ssl == true);
assert(reordered.protocol == "https");
```

## Nested Struct Evolution

Schema evolution works recursively for nested structs:

```cpp
// V1 Address
struct AddressV1 {
  std::string street;
  std::string city;
};
FORY_STRUCT(AddressV1, street, city);

// V2 Address with new fields
struct AddressV2 {
  std::string street;
  std::string city;
  std::string country;  // NEW
  std::string zipcode;  // NEW
};
FORY_STRUCT(AddressV2, street, city, country, zipcode);

// V1 Employee with V1 Address
struct EmployeeV1 {
  std::string name;
  AddressV1 home_address;
};
FORY_STRUCT(EmployeeV1, name, home_address);

// V2 Employee with V2 Address and new field
struct EmployeeV2 {
  std::string name;
  AddressV2 home_address;  // Nested struct evolved
  std::string employee_id; // NEW
};
FORY_STRUCT(EmployeeV2, name, home_address, employee_id);

// Register types with same IDs
constexpr uint32_t ADDRESS_TYPE_ID = 100;
constexpr uint32_t EMPLOYEE_TYPE_ID = 101;

fory_v1.register_struct<AddressV1>(ADDRESS_TYPE_ID);
fory_v1.register_struct<EmployeeV1>(EMPLOYEE_TYPE_ID);
fory_v2.register_struct<AddressV2>(ADDRESS_TYPE_ID);
fory_v2.register_struct<EmployeeV2>(EMPLOYEE_TYPE_ID);

// Serialize V1
EmployeeV1 emp_v1{"Jane Doe", {"123 Main St", "NYC"}};
auto bytes = fory_v1.serialize(emp_v1).value();

// Deserialize as V2
auto emp_v2 = fory_v2.deserialize<EmployeeV2>(bytes).value();
assert(emp_v2.name == "Jane Doe");
assert(emp_v2.home_address.street == "123 Main St");
assert(emp_v2.home_address.city == "NYC");
assert(emp_v2.home_address.country == "");  // Default
assert(emp_v2.home_address.zipcode == "");  // Default
assert(emp_v2.employee_id == "");           // Default
```

## Bidirectional Evolution

Schema evolution works in both directions:

```cpp
// V2 -> V1 (downgrade)
PersonV2 v2{"Charlie", 35, "charlie@example.com"};
auto bytes = fory_v2.serialize(v2).value();

auto v1 = fory_v1.deserialize<PersonV1>(bytes).value();
assert(v1.name == "Charlie");
assert(v1.age == 35);
// email field is discarded during deserialization
```

## Default Values

When fields are missing, C++ default initialization is used:

| Type                   | Default Value       |
| ---------------------- | ------------------- |
| `int8_t`, `int16_t`... | `0`                 |
| `float`, `double`      | `0.0`               |
| `bool`                 | `false`             |
| `std::string`          | `""`                |
| `std::vector<T>`       | Empty vector        |
| `std::map<K,V>`        | Empty map           |
| `std::set<T>`          | Empty set           |
| `std::optional<T>`     | `std::nullopt`      |
| Struct types           | Default-constructed |

## Schema Consistent Mode (Default)

Without compatible mode, schemas must match exactly:

```cpp
// Strict mode (default)
auto fory = Fory::builder()
    .compatible(false)  // Default: schema must match
    .xlang(true)
    .build();

// Serialization/deserialization requires identical schemas
// Schema mismatches may cause errors or undefined behavior
```

**Use SchemaConsistent mode when:**

- Schemas are guaranteed to match (same binary version)
- Maximum performance is required (less metadata overhead)
- You control both serialization and deserialization

**Use Compatible mode when:**

- Schemas may evolve independently
- Cross-version compatibility is required
- Different services may have different schema versions

## Type ID Requirements

For schema evolution to work:

1. **Same Type ID**: Different versions of the same struct must use the same type ID
2. **Consistent IDs**: Type IDs must be consistent across all Fory instances
3. **Register All Versions**: Each Fory instance registers its own struct version

```cpp
constexpr uint32_t PERSON_TYPE_ID = 100;

// Instance 1 uses PersonV1
fory_v1.register_struct<PersonV1>(PERSON_TYPE_ID);

// Instance 2 uses PersonV2
fory_v2.register_struct<PersonV2>(PERSON_TYPE_ID);

// Same type ID enables schema evolution
```

## Best Practices

### 1. Plan for Evolution

Design schemas with future changes in mind:

```cpp
// Good: Use optional for fields that might be removed
struct Config {
  std::string host;
  int32_t port;
  std::optional<std::string> deprecated_field;  // Can be removed later
};
```

### 2. Use Meaningful Default Values

Consider what default values make sense for new fields:

```cpp
struct Settings {
  int32_t timeout_ms;      // Default: 0 (might want a sensible default)
  bool enabled;            // Default: false
  std::string mode;        // Default: "" (might want "default")
};
```

### 3. Document Schema Versions

Track schema changes for debugging:

```cpp
// V1: Initial schema (2024-01-01)
// V2: Added email field (2024-02-01)
// V3: Added phone, address fields (2024-03-01)
```

### 4. Test Evolution Paths

Test both upgrade and downgrade scenarios:

```cpp
// Test V1 -> V2
// Test V2 -> V1
// Test V1 -> V3
// Test V3 -> V1
```

## Cross-Language Schema Evolution

Schema evolution works across languages when using xlang mode:

```cpp
// C++ with compatible mode
auto fory = Fory::builder()
    .compatible(true)
    .xlang(true)
    .build();
```

```java
// Java with compatible mode
Fory fory = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE)
    .withLanguage(Language.XLANG)
    .build();
```

Both instances can exchange data even with different schema versions.

## Related Topics

- [Configuration](configuration.md) - Enabling compatible mode
- [Type Registration](type-registration.md) - Type ID management
- [Cross-Language](cross-language.md) - Cross-language considerations
