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
- **Zero-copy**: Direct memory access without data copying
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

struct Person {
  int32_t id;
  std::string name;
  float score;
  FORY_STRUCT(Person, id, name, score);
};

int main() {
  // Create encoder
  RowEncoder<Person> encoder;

  // encode a person
  Person person{1, "Alice", 95.5f};
  encoder.encode(person);

  // get the encoded row
  auto row = encoder.get_writer().to_row();

  // Random access to fields
  int32_t id = row->get_int32(0);
  std::string name = row->get_string(1);
  float score = row->get_float(2);

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

struct Point {
  double x;
  double y;
  FORY_STRUCT(Point, x, y);
};

// Create encoder
RowEncoder<Point> encoder;

// Access schema (for inspection)
const Schema& schema = encoder.get_schema();
std::cout << "Fields: " << schema.field_names().size() << std::endl;

// encode value
Point p{1.0, 2.0};
encoder.encode(p);

// get result as Row
auto row = encoder.get_writer().to_row();
```

### Nested Structs

```cpp
struct Address {
  std::string city;
  std::string country;
  FORY_STRUCT(Address, city, country);
};

struct Person {
  std::string name;
  Address address;
  FORY_STRUCT(Person, name, address);
};

// encode nested struct
RowEncoder<Person> encoder;
Person person{"Alice", {"New York", "USA"}};
encoder.encode(person);

auto row = encoder.get_writer().to_row();
std::string name = row->get_string(0);

// Access nested struct
auto address_row = row->get_struct(1);
std::string city = address_row->get_string(0);
std::string country = address_row->get_string(1);
```

### Arrays / Lists

```cpp
struct Record {
  std::vector<int32_t> values;
  std::string label;
  FORY_STRUCT(Record, values, label);
};

RowEncoder<Record> encoder;
Record record{{1, 2, 3, 4, 5}, "test"};
encoder.encode(record);

auto row = encoder.get_writer().to_row();
auto array = row->get_array(0);

int count = array->num_elements();
for (int i = 0; i < count; i++) {
  int32_t value = array->get_int32(i);
}
```

### Encoding Arrays Directly

```cpp
// encode a vector directly (not inside a struct)
std::vector<Person> people{
    {"Alice", {"NYC", "USA"}},
    {"Bob", {"London", "UK"}}
};

RowEncoder<decltype(people)> encoder;
encoder.encode(people);

// get array data
auto array = encoder.get_writer().copy_to_array_data();
auto first_person = array->get_struct(0);
std::string first_name = first_person->get_string(0);
```

## Row Data Access

### Row Class

The `Row` class provides random access to struct fields:

```cpp
class Row {
public:
  // Null check
  bool is_null_at(int i) const;

  // Primitive getters
  bool get_boolean(int i) const;
  int8_t get_int8(int i) const;
  int16_t get_int16(int i) const;
  int32_t get_int32(int i) const;
  int64_t get_int64(int i) const;
  float get_float(int i) const;
  double get_double(int i) const;

  // String/binary getter
  std::string get_string(int i) const;
  std::vector<uint8_t> get_binary(int i) const;

  // Nested types
  std::shared_ptr<Row> get_struct(int i) const;
  std::shared_ptr<ArrayData> get_array(int i) const;
  std::shared_ptr<MapData> get_map(int i) const;

  // Metadata
  int num_fields() const;
  SchemaPtr schema() const;

  // Debug
  std::string to_string() const;
};
```

### ArrayData Class

The `ArrayData` class provides access to list/array elements:

```cpp
class ArrayData {
public:
  // Null check
  bool is_null_at(int i) const;

  // Element count
  int num_elements() const;

  // Primitive getters (same as Row)
  int32_t get_int32(int i) const;
  // ... other primitives

  // String getter
  std::string get_string(int i) const;

  // Nested types
  std::shared_ptr<Row> get_struct(int i) const;
  std::shared_ptr<ArrayData> get_array(int i) const;
  std::shared_ptr<MapData> get_map(int i) const;

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

// Type inference for structs (requires FORY_STRUCT)
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
writer.reset();

// write fields
writer.write(0, 42);          // x = 42
writer.write(1, 3.14);        // y = 3.14
writer.write_string(2, "test"); // name = "test"

// get result
auto row = writer.to_row();
```

### ArrayWriter

For manual array construction:

```cpp
// Create array type
auto array_type = list(int32());

// Create writer
ArrayWriter writer(array_type);
writer.reset(5);  // 5 elements

// write elements
for (int i = 0; i < 5; i++) {
  writer.write(i, i * 10);
}

// get result
auto array = writer.copy_to_array_data();
```

### Null Values

```cpp
// Set null at specific index
writer.set_null_at(2);  // Field 2 is null

// Check null when reading
if (!row->is_null_at(2)) {
  std::string value = row->get_string(2);
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

// encode multiple records
for (const auto& person : people) {
  encoder.encode(person);
  auto row = encoder.get_writer().to_row();
  // Process row...
}
```

### 2. Pre-allocate Buffer

```cpp
// get buffer reference for pre-allocation
auto& buffer = encoder.get_writer().buffer();
buffer->reserve(expected_size);
```

### 3. Batch Processing

```cpp
// Process in batches for better cache utilization
std::vector<Person> batch;
batch.reserve(BATCH_SIZE);

while (has_more()) {
  batch.clear();
  fill_batch(batch);

  for (const auto& person : batch) {
    encoder.encode(person);
    process(encoder.get_writer().to_row());
  }
}
```

### 4. Zero-copy Reading

```cpp
// Point to existing buffer (zero-copy)
Row row(schema);
row.point_to(buffer, offset, size);

// Access fields directly from buffer
int32_t id = row.get_int32(0);
```

## Supported Types Summary

| C++ Type             | Row Type         | Fixed Size |
| -------------------- | ---------------- | ---------- |
| `bool`               | `boolean()`      | 1 byte     |
| `int8_t`             | `int8()`         | 1 byte     |
| `int16_t`            | `int16()`        | 2 bytes    |
| `int32_t`            | `int32()`        | 4 bytes    |
| `int64_t`            | `int64()`        | 8 bytes    |
| `float`              | `float32()`      | 4 bytes    |
| `double`             | `float64()`      | 8 bytes    |
| `std::string`        | `utf8()`         | Variable   |
| `std::vector<T>`     | `list(T)`        | Variable   |
| `std::map<K,V>`      | `map(K,V)`       | Variable   |
| `std::optional<T>`   | Inner type       | Nullable   |
| Struct (FORY_STRUCT) | `struct_({...})` | Variable   |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Configuration](configuration.md) - Builder options
- [Supported Types](supported-types.md) - All supported types
