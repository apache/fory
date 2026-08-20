---
title: Python Row Format
sidebar_position: 4
id: python
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

Apache Fory™ provides a random-access row format that enables reading nested fields from binary data without full deserialization.

## Overview

Row format drastically reduces overhead when working with large objects where only partial data access is needed. It also supports memory-mapped files for ultra-low memory footprint.

**Key Benefits:**

| Feature                 | Description                                              |
| ----------------------- | -------------------------------------------------------- |
| Zero-Copy Access        | Read nested fields without deserializing entire object   |
| Memory Efficiency       | Memory-map large datasets directly from disk             |
| Cross-Language          | Binary format compatible between Python, Java, C++, Rust |
| Partial Deserialization | Deserialize only specific elements you need              |
| High Performance        | Skip unnecessary data parsing for analytics workloads    |

## Basic Usage

```python
from dataclasses import dataclass
from typing import Dict, List

import pyfory

@dataclass
class Bar:
    f1: str
    f2: List[pyfory.Int64]

@dataclass
class Foo:
    f1: pyfory.Int32
    f2: List[pyfory.Int32]
    f3: Dict[str, pyfory.Int32]
    f4: List[Bar]

# Create encoder for row format
encoder = pyfory.encoder(Foo)

# Create large dataset
foo = Foo(
    f1=10,
    f2=list(range(1_000_000)),
    f3={f"k{i}": i for i in range(1_000_000)},
    f4=[Bar(f1=f"s{i}", f2=list(range(10))) for i in range(1_000_000)]
)

# Encode to row format
binary: bytes = encoder.to_row(foo).to_bytes()

# Zero-copy access - no full deserialization needed!
foo_row = pyfory.RowData(encoder.schema, binary)
print(foo_row.f2[100000])              # Access 100,000th element directly
print(foo_row.f4[100000].f1)           # Access nested field directly
print(foo_row.f4[200000].f2[5])        # Access deeply nested field directly
```

## PyArrow Schema Conversion

Row format can convert PyArrow schemas through `pyfory.format` when the
`format` optional dependency is installed:

```python
import pyarrow as pa
from pyfory.format import from_arrow_schema, to_arrow_schema

arrow_schema = pa.schema(
    [
        pa.field("id", pa.int32(), nullable=False),
        pa.field("scores", pa.list_(pa.float64())),
    ]
)

fory_schema = from_arrow_schema(arrow_schema)
roundtrip_arrow_schema = to_arrow_schema(fory_schema)
```

Convert Python values directly to Arrow batches or tables through the row
encoder:

```python
encoder = pyfory.encoder(Foo)
record_batch = encoder.to_arrow_record_batch([foo] * 10_000)
table = encoder.to_arrow_table([foo] * 10_000)
```

This PyArrow conversion surface is separate from cross-language dense-array
field annotations. In object serialization, `pyfory.PyArray[T]` means the
standard-library Python `array.array` carrier, not PyArrow.

## Cross-Language Compatibility

Row format works seamlessly across languages. The same binary data can be accessed from Java, C++, and Rust.

### Java

```java
public class Bar {
  String f1;
  List<Long> f2;
}

public class Foo {
  int f1;
  List<Integer> f2;
  Map<String, Integer> f3;
  List<Bar> f4;
}

RowEncoder<Foo> encoder = Encoders.bean(Foo.class);

// Encode to row format (cross-language compatible with Python)
BinaryRow binaryRow = encoder.toRow(foo);

// Zero-copy random access without full deserialization
BinaryArray f2Array = binaryRow.getArray(1);              // Access f2 list
BinaryArray f4Array = binaryRow.getArray(3);              // Access f4 list
BinaryRow bar10 = f4Array.getStruct(10);                  // Access 11th Bar
long value = bar10.getArray(1).getInt64(5);               // Access 6th element of bar.f2

// Partial deserialization - only deserialize what you need
RowEncoder<Bar> barEncoder = Encoders.bean(Bar.class);
Bar bar1 = barEncoder.fromRow(f4Array.getStruct(10));     // Deserialize 11th Bar only
Bar bar2 = barEncoder.fromRow(f4Array.getStruct(20));     // Deserialize 21st Bar only
```

### C++

```cpp
#include "fory/encoder/row_encoder.h"
#include "fory/row/writer.h"

struct Bar {
  std::string f1;
  std::vector<int64_t> f2;
  FORY_STRUCT(Bar, f1, f2);
};

struct Foo {
  int32_t f1;
  std::vector<int32_t> f2;
  std::map<std::string, int32_t> f3;
  std::vector<Bar> f4;
  FORY_STRUCT(Foo, f1, f2, f3, f4);
};

fory::row::encoder::RowEncoder<Foo> encoder;
encoder.encode(foo);
auto row = encoder.get_writer().to_row();

// Zero-copy random access without full deserialization
auto f2_array = row->get_array(1);                   // Access f2 list
auto f4_array = row->get_array(3);                   // Access f4 list
auto bar10 = f4_array->get_struct(10);               // Access 11th Bar
int64_t value = bar10->get_array(1)->get_int64(5);   // Access 6th element of bar.f2
std::string str = bar10->get_string(0);              // Access bar.f1
```

## Installation

Row format requires Apache Arrow:

```bash
pip install pyfory[format]
```

## When to Use Row Format

- **Analytics workloads**: When you only need to access specific fields
- **Large datasets**: When full deserialization is too expensive
- **Memory-mapped files**: Working with data larger than RAM
- **Data pipelines**: Processing data without full object reconstruction
- **Cross-language data sharing**: When data needs to be accessed from multiple languages

## Related Topics

- [Cross-Language Interoperability](../object-serialization/python/basic-serialization.md#cross-language-interoperability) - xlang mode
- [Basic Serialization](../object-serialization/python/basic-serialization.md) - Object serialization
- [Row Format Specification](https://fory.apache.org/docs/specification/row_format_spec) - Protocol details
