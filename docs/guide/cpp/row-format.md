---
title: Row Format
sidebar_position: 20
id: row_format
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

This page covers the row-based serialization format for high-performance, cache-friendly data access.

## Overview

Apache Fory™ Row Format is a binary format optimized for:

- **Random Access**: Read any field without deserializing the entire object
- **Zero-Copy**: Direct memory access without data copying
- **Cache-Friendly**: Contiguous memory layout for CPU cache efficiency
- **Columnar Conversion**: Easy conversion to Apache Arrow format
- **Partial Serialization**: Serialize only needed fields

## When to Use Row Format

| Use Case                      | Row Format | Object Graph |
| ----------------------------- | ---------- | ------------ |
| Analytics/OLAP                | ✅         | ❌           |
| Random field access           | ✅         | ❌           |
| Full object serialization     | ❌         | ✅           |
| Complex object graphs         | ❌         | ✅           |
| Reference tracking            | ❌         | ✅           |
| Cross-language (simple types) | ✅         | ✅           |

## Quick Start

```cpp
#include "fory/encoder/row_encoder.h"
#include "fory/row/writer.h"

using namespace fory::row;
using namespace fory::row::encoder;

// Define a struct
struct Person {
  int32_t id;
  std::string name;
  float score;
};

// Register field metadata (required for row encoding)
FORY_FIELD_INFO(Person, id, name, score);

int main() {
  // Create encoder
  RowEncoder<Person> encoder;

  // Encode a person
  Person person{1, "Alice", 95.5f};
  encoder.Encode(person);

  // Get the encoded row
  auto row = encoder.GetWriter().ToRow();

  // Random access to fields
  int32_t id = row->GetInt32(0);
  std::string name = row->GetString(1);
  float score = row->GetFloat(2);

  assert(id == 1);
  assert(name == "Alice");
  assert(score == 95.5f);

  return 0;
}
```

## Row Encoder

### Basic Usage

The `RowEncoder<T>` template class provides type-safe encoding:

```cpp
#include "fory/encoder/row_encoder.h"

// Define struct with FORY_FIELD_INFO
struct Point {
  double x;
  double y;
};
FORY_FIELD_INFO(Point, x, y);

// Create encoder
RowEncoder<Point> encoder;

// Access schema (for inspection)
const Schema& schema = encoder.GetSchema();
std::cout << "Fields: " << schema.field_names().size() << std::endl;

// Encode value
Point p{1.0, 2.0};
encoder.Encode(p);

// Get result as Row
auto row = encoder.GetWriter().ToRow();
```

### Nested Structs

```cpp
struct Address {
  std::string city;
  std::string country;
};
FORY_FIELD_INFO(Address, city, country);

struct Person {
  std::string name;
  Address address;
};
FORY_FIELD_INFO(Person, name, address);

// Encode nested struct
RowEncoder<Person> encoder;
Person person{"Alice", {"New York", "USA"}};
encoder.Encode(person);

auto row = encoder.GetWriter().ToRow();
std::string name = row->GetString(0);

// Access nested struct
auto address_row = row->GetStruct(1);
std::string city = address_row->GetString(0);
std::string country = address_row->GetString(1);
```

### Arrays / Lists

```cpp
struct Record {
  std::vector<int32_t> values;
  std::string label;
};
FORY_FIELD_INFO(Record, values, label);

RowEncoder<Record> encoder;
Record record{{1, 2, 3, 4, 5}, "test"};
encoder.Encode(record);

auto row = encoder.GetWriter().ToRow();
auto array = row->GetArray(0);

int count = array->num_elements();
for (int i = 0; i < count; i++) {
  int32_t value = array->GetInt32(i);
}
```

### Encoding Arrays Directly

```cpp
// Encode a vector directly (not inside a struct)
std::vector<Person> people{
    {"Alice", {"NYC", "USA"}},
    {"Bob", {"London", "UK"}}
};

RowEncoder<decltype(people)> encoder;
encoder.Encode(people);

// Get array data
auto array = encoder.GetWriter().CopyToArrayData();
auto first_person = array->GetStruct(0);
std::string first_name = first_person->GetString(0);
```

## Row Data Access

### Row Class

The `Row` class provides random access to struct fields:

```cpp
class Row {
public:
  // Null check
  bool IsNullAt(int i) const;

  // Primitive getters
  bool GetBoolean(int i) const;
  int8_t GetInt8(int i) const;
  int16_t GetInt16(int i) const;
  int32_t GetInt32(int i) const;
  int64_t GetInt64(int i) const;
  float GetFloat(int i) const;
  double GetDouble(int i) const;

  // String/binary getter
  std::string GetString(int i) const;
  std::vector<uint8_t> GetBinary(int i) const;

  // Nested types
  std::shared_ptr<Row> GetStruct(int i) const;
  std::shared_ptr<ArrayData> GetArray(int i) const;
  std::shared_ptr<MapData> GetMap(int i) const;

  // Metadata
  int num_fields() const;
  SchemaPtr schema() const;

  // Debug
  std::string ToString() const;
};
```

### ArrayData Class

The `ArrayData` class provides access to list/array elements:

```cpp
class ArrayData {
public:
  // Null check
  bool IsNullAt(int i) const;

  // Element count
  int num_elements() const;

  // Primitive getters (same as Row)
  int32_t GetInt32(int i) const;
  // ... other primitives

  // String getter
  std::string GetString(int i) const;

  // Nested types
  std::shared_ptr<Row> GetStruct(int i) const;
  std::shared_ptr<ArrayData> GetArray(int i) const;
  std::shared_ptr<MapData> GetMap(int i) const;

  // Type info
  ListTypePtr type() const;
};
```

### MapData Class

The `MapData` class provides access to map key-value pairs:

```cpp
class MapData {
public:
  // Element count
  int num_elements();

  // Access keys and values as arrays
  std::shared_ptr<ArrayData> keys_array();
  std::shared_ptr<ArrayData> values_array();

  // Type info
  MapTypePtr type();
};
```

## Schema and Types

### Schema Definition

Schemas define the structure of row data:

```cpp
#include "fory/row/schema.h"

using namespace fory::row;

// Create schema programmatically
auto person_schema = schema({
    field("id", int32()),
    field("name", utf8()),
    field("score", float32()),
    field("active", boolean())
});

// Access schema info
for (const auto& f : person_schema->fields()) {
  std::cout << f->name() << ": " << f->type()->name() << std::endl;
}
```

### Type System

Available types for row format:

```cpp
// Primitive types
DataTypePtr boolean();    // bool
DataTypePtr int8();       // int8_t
DataTypePtr int16();      // int16_t
DataTypePtr int32();      // int32_t
DataTypePtr int64();      // int64_t
DataTypePtr float32();    // float
DataTypePtr float64();    // double

// String and binary
DataTypePtr utf8();       // std::string
DataTypePtr binary();     // std::vector<uint8_t>

// Complex types
DataTypePtr list(DataTypePtr element_type);
DataTypePtr map(DataTypePtr key_type, DataTypePtr value_type);
DataTypePtr struct_(std::vector<FieldPtr> fields);
```

### Type Inference

The `RowEncodeTrait` template automatically infers types:

```cpp
// Type inference for primitives
RowEncodeTrait<int32_t>::Type();  // Returns int32()
RowEncodeTrait<float>::Type();    // Returns float32()
RowEncodeTrait<std::string>::Type();  // Returns utf8()

// Type inference for collections
RowEncodeTrait<std::vector<int32_t>>::Type();  // Returns list(int32())

// Type inference for maps
RowEncodeTrait<std::map<std::string, int32_t>>::Type();
// Returns map(utf8(), int32())

// Type inference for structs (requires FORY_FIELD_INFO)
RowEncodeTrait<Person>::Type();  // Returns struct_({...})
RowEncodeTrait<Person>::Schema();  // Returns schema({...})
```

## Row Writer

### RowWriter

For manual row construction:

```cpp
#include "fory/row/writer.h"

// Create schema
auto my_schema = schema({
    field("x", int32()),
    field("y", float64()),
    field("name", utf8())
});

// Create writer
RowWriter writer(my_schema);
writer.Reset();

// Write fields
writer.Write(0, 42);          // x = 42
writer.Write(1, 3.14);        // y = 3.14
writer.WriteString(2, "test"); // name = "test"

// Get result
auto row = writer.ToRow();
```

### ArrayWriter

For manual array construction:

```cpp
// Create array type
auto array_type = list(int32());

// Create writer
ArrayWriter writer(array_type);
writer.Reset(5);  // 5 elements

// Write elements
for (int i = 0; i < 5; i++) {
  writer.Write(i, i * 10);
}

// Get result
auto array = writer.CopyToArrayData();
```

### Null Values

```cpp
// Set null at specific index
writer.SetNullAt(2);  // Field 2 is null

// Check null when reading
if (!row->IsNullAt(2)) {
  std::string value = row->GetString(2);
}
```

## Memory Layout

### Row Layout

```
+------------------+--------------------+--------------------+
|   Null Bitmap    |  Fixed-Size Data   | Variable-Size Data |
+------------------+--------------------+--------------------+
|   ceil(n/8) B    |     8 * n bytes    |      variable      |
+------------------+--------------------+--------------------+
```

- **Null Bitmap**: One bit per field, indicates null values
- **Fixed-Size Data**: 8 bytes per field (primitives stored directly, offset+size for variable)
- **Variable-Size Data**: Strings, arrays, nested structs

### Array Layout

```
+------------+------------------+--------------------+--------------------+
| Num Elems  |   Null Bitmap    |  Fixed-Size Data   | Variable-Size Data |
+------------+------------------+--------------------+--------------------+
|   8 bytes  |  ceil(n/8) bytes |   elem_size * n    |      variable      |
+------------+------------------+--------------------+--------------------+
```

### Map Layout

```
+------------------+------------------+
|    Keys Array    |   Values Array   |
+------------------+------------------+
```

## Performance Tips

### 1. Reuse Encoders

```cpp
RowEncoder<Person> encoder;

// Encode multiple records
for (const auto& person : people) {
  encoder.Encode(person);
  auto row = encoder.GetWriter().ToRow();
  // Process row...
}
```

### 2. Pre-allocate Buffer

```cpp
// Get buffer reference for pre-allocation
auto& buffer = encoder.GetWriter().buffer();
buffer->Reserve(expected_size);
```

### 3. Batch Processing

```cpp
// Process in batches for better cache utilization
std::vector<Person> batch;
batch.reserve(BATCH_SIZE);

while (hasMore()) {
  batch.clear();
  fillBatch(batch);

  for (const auto& person : batch) {
    encoder.Encode(person);
    process(encoder.GetWriter().ToRow());
  }
}
```

### 4. Zero-Copy Reading

```cpp
// Point to existing buffer (zero-copy)
Row row(schema);
row.PointTo(buffer, offset, size);

// Access fields directly from buffer
int32_t id = row.GetInt32(0);
```

## Supported Types Summary

| C++ Type                 | Row Type         | Fixed Size |
| ------------------------ | ---------------- | ---------- |
| `bool`                   | `boolean()`      | 1 byte     |
| `int8_t`                 | `int8()`         | 1 byte     |
| `int16_t`                | `int16()`        | 2 bytes    |
| `int32_t`                | `int32()`        | 4 bytes    |
| `int64_t`                | `int64()`        | 8 bytes    |
| `float`                  | `float32()`      | 4 bytes    |
| `double`                 | `float64()`      | 8 bytes    |
| `std::string`            | `utf8()`         | Variable   |
| `std::vector<T>`         | `list(T)`        | Variable   |
| `std::map<K,V>`          | `map(K,V)`       | Variable   |
| `std::optional<T>`       | Inner type       | Nullable   |
| Struct (FORY_FIELD_INFO) | `struct_({...})` | Variable   |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Configuration](configuration.md) - Builder options
- [Supported Types](supported-types.md) - All supported types
