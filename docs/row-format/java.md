---
title: Java Row Format
sidebar_position: 3
id: java
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

Apache Fory™ provides a random-access row format that enables reading nested fields from binary data without full deserialization. This drastically reduces overhead when working with large objects where only partial data access is needed.

## Overview

Row format is a cache-friendly binary random access format that supports:

- **Zero-copy access**: Read fields directly from binary without allocating objects
- **Partial deserialization**: Access only the fields you need
- **Skipping serialization**: Skip serialization of fields you don't need
- **Cross-language compatibility**: Standard rows work across Python, Java, C++, and Rust
- **Column format conversion**: Can convert to Apache Arrow columnar format automatically

## Installation

Java Row Format requires Java 11 or later and is not supported on Android. Add
the `fory-format` artifact at the same version as the other Fory modules in the
application.

For Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-format</artifactId>
  <version>1.5.0</version>
</dependency>
```

For Gradle:

```kotlin
implementation("org.apache.fory:fory-format:1.5.0")
```

## Basic Usage

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

// Create large dataset
Foo foo = new Foo();
foo.f1 = 10;
foo.f2 = IntStream.range(0, 1_000_000).boxed().collect(Collectors.toList());
foo.f3 = IntStream.range(0, 1_000_000).boxed().collect(Collectors.toMap(i -> "k" + i, i -> i));
List<Bar> bars = new ArrayList<>(1_000_000);
for (int i = 0; i < 1_000_000; i++) {
  Bar bar = new Bar();
  bar.f1 = "s" + i;
  bar.f2 = LongStream.range(0, 10).boxed().collect(Collectors.toList());
  bars.add(bar);
}
foo.f4 = bars;

// Encode to row format (cross-language compatible with Python/C++/Rust)
BinaryRow binaryRow = encoder.toRow(foo);

// Reconstruct the complete object only when the application needs it.
Foo decoded = encoder.fromRow(binaryRow);

// Zero-copy random access without full deserialization
BinaryArray f2Array = binaryRow.getArray(1);              // Access f2 list
BinaryArray f4Array = binaryRow.getArray(3);              // Access f4 list
BinaryRow bar10 = f4Array.getStruct(10);                  // Access 11th Bar
long value = bar10.getArray(1).getInt64(5);               // Access 6th element of bar.f2

// Name-based access without repeated schema lookups
Schema schema = encoder.schema();
Schema.Int32Field f1 = schema.int32Field("f1");
Schema.ArrayField f4 = schema.arrayField("f4");
int f1Value = f1.get(binaryRow);
ArrayData f4ByName = f4.get(binaryRow);

// Partial deserialization - only deserialize what you need
RowEncoder<Bar> barEncoder = Encoders.bean(Bar.class);
Bar bar1 = barEncoder.fromRow(f4Array.getStruct(10));     // Deserialize 11th Bar only
Bar bar2 = barEncoder.fromRow(f4Array.getStruct(20));     // Deserialize 21st Bar only

// Full deserialization when needed
Foo newFoo = encoder.fromRow(binaryRow);
```

Cache the returned `Schema.*Field` handles in user code and reuse them for all rows with the same
schema. Calling `schema.int32Field("f1")` creates a typed handle by resolving the field name to an
ordinal, accepting Java lower-camel field names for bean-derived schemas, validating the expected
row-format type, and storing the resolved ordinal. Later calls such as `f1.get(binaryRow)` go
straight to the ordinal row getter without another schema map lookup or typed handle construction.

## Key Benefits

| Feature                 | Description                                              |
| ----------------------- | -------------------------------------------------------- |
| Zero-Copy Access        | Read nested fields without deserializing entire object   |
| Memory Efficiency       | Memory-map large datasets directly from disk             |
| Cross-Language          | Binary format compatible between Java, Python, C++, Rust |
| Partial Deserialization | Deserialize only specific elements you need              |
| High Performance        | Skip unnecessary data parsing for analytics workloads    |

## When to Use Row Format

Row format is ideal for:

- **Analytics workloads**: When you only need to access specific fields
- **Large datasets**: When full deserialization is too expensive
- **Memory-mapped files**: Working with data larger than RAM
- **Data pipelines**: Processing data without full object reconstruction
- **Cross-language data sharing**: When data needs to be accessed from multiple languages

## Cross-Language Compatibility

Row format works seamlessly across languages. The same binary data can be accessed from:

### Python

```python
import pyfory
from dataclasses import dataclass
from typing import List, Dict

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

encoder = pyfory.encoder(Foo)
binary: bytes = encoder.to_row(foo).to_bytes()

# Zero-copy access
foo_row = pyfory.RowData(encoder.schema, binary)
print(foo_row.f2[100000])
print(foo_row.f4[100000].f1)
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

// Zero-copy random access
auto f2_array = row->get_array(1);
auto f4_array = row->get_array(3);
auto bar10 = f4_array->get_struct(10);
int64_t value = bar10->get_array(1)->get_int64(5);
std::string str = bar10->get_string(0);
```

## Performance Comparison

| Operation            | Object Format                 | Row Format                      |
| -------------------- | ----------------------------- | ------------------------------- |
| Full deserialization | Allocates all objects         | Zero allocation                 |
| Single field access  | Full deserialization required | Direct offset read              |
| Memory usage         | Full object graph in memory   | Only accessed fields            |
| Suitable for         | Small objects, full access    | Large objects, selective access |

## Apache Arrow Conversion

Convert Java rows to an Arrow `RecordBatch` for analytical processing:

```java
Schema schema = TypeInference.inferSchema(BeanA.class);
ArrowWriter arrowWriter = ArrowUtils.createArrowWriter(schema);
Encoder<BeanA> encoder = Encoders.rowEncoder(BeanA.class);
for (int i = 0; i < 10; i++) {
  BeanA beanA = BeanA.createBeanA(2);
  arrowWriter.write(encoder.toRow(beanA));
}
return arrowWriter.finishAsRecordBatch();
```

## Interface and Extension Types

Java Row Format can map an interface or superclass schema to a concrete value.
This support was introduced in
[#2243](https://github.com/apache/fory/pull/2243),
[#2250](https://github.com/apache/fory/pull/2250), and
[#2256](https://github.com/apache/fory/pull/2256).

### Interface Mapping

```java
public interface Animal {
  String speak();
}

public class Dog implements Animal {
  public String name;

  @Override
  public String speak() {
    return "Woof";
  }
}

RowEncoder<Animal> encoder = Encoders.bean(Animal.class);
Dog dog = new Dog();
dog.name = "Bingo";
BinaryRow row = encoder.toRow(dog);
Animal decoded = encoder.fromRow(row);
System.out.println(decoded.speak()); // Woof
```

### Extension-Type Mapping

```java
public class Parent {
  public String parentField;
}

public class Child extends Parent {
  public String childField;
}

RowEncoder<Parent> encoder = Encoders.bean(Parent.class);
Child child = new Child();
child.parentField = "Hello";
child.childField = "World";
BinaryRow row = encoder.toRow(child);
Parent decoded = encoder.fromRow(row);
```

## Related Topics

- [Xlang Serialization](../object-serialization/java/xlang.md) - xlang mode
- [Java Advanced Features](../object-serialization/java/advanced-features.md) - Zero-copy object serialization
- [Row Format Specification](https://fory.apache.org/docs/specification/row_format_spec) - Protocol details
