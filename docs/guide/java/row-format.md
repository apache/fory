---
title: Row Format
sidebar_position: 12
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

Apache Fory™ provides a random-access row format that enables reading nested fields from binary data without full deserialization. This drastically reduces overhead when working with large objects where only partial data access is needed.

## Overview

Row format is a cache-friendly binary random access format that supports:

- **Zero-copy access**: Read fields directly from binary without allocating objects
- **Partial deserialization**: Access only the fields you need
- **Skipping serialization**: Skip serialization of fields you don't need
- **Cross-language compatibility**: Works across Python, Java, C++, and other languages
- **Column format conversion**: Can convert to Apache Arrow columnar format automatically

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

// Encode to row format (cross-language compatible with Python/C++)
BinaryRow binaryRow = encoder.toRow(foo);

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

| Feature                 | Description                                            |
| ----------------------- | ------------------------------------------------------ |
| Zero-Copy Access        | Read nested fields without deserializing entire object |
| Memory Efficiency       | Memory-map large datasets directly from disk           |
| Cross-Language          | Binary format compatible between Java, Python, C++     |
| Partial Deserialization | Deserialize only specific elements you need            |
| High Performance        | Skip unnecessary data parsing for analytics workloads  |

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
import pyarrow as pa
from dataclasses import dataclass
from typing import List, Dict

@dataclass
class Bar:
    f1: str
    f2: List[pa.int64]

@dataclass
class Foo:
    f1: pa.int32
    f2: List[pa.int32]
    f3: Dict[str, pa.int32]
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

## Schema evolution

Enable `.withSchemaEvolution()` on a row, array, or map codec builder to read payloads written
by older versions of the same bean. Writing always uses the current version; reading detects
the payload's version from a strict hash at the head of the payload. Java only.

Annotate fields added after v1 with `@ForyVersion(since = N)`:

```java
@Data
public class Person {
  String name;
  int age;

  @ForyVersion(since = 2)
  String email;
}
```

A v1 payload (with `name` and `age` only) decodes to a `Person` whose `email` is `null`.
Primitive fields added later default to `0`, `0.0`, or `false`. Unannotated fields are treated
as present from the first version, so a class can adopt versioning by annotating only the fields
added after v1.

For a record, the absent component's default is passed to the canonical constructor, so a
constructor that rejects `null` for a reference component added in a later version throws when
decoding an older payload. Let the constructor tolerate the missing value, for example by
normalizing `null` to a default:

```java
public record Person(String name, @ForyVersion(since = 2) String email) {
  public Person {
    if (email == null) {
      email = "";
    }
  }
}
```

Remove a field by deleting the Java member and declaring it on a nested history interface as a
method with a `@ForyVersion(until = N)`. The method's return type carries any parameterized
type information from the original field.

```java
@Data
@ForySchema(removedFields = Person.History.class)
public class Person {
  String name;

  @ForyVersion(since = 2)
  String email;

  interface History {
    @ForyVersion(until = 3)
    int age();

    @ForyVersion(until = 5)
    List<String> tags();
  }
}
```

The history method name matches the original live descriptor name. For field-backed beans
(Lombok `@Data`, records, or plain classes with a backing field) that is the field name
(`age`, `tags`). For interface beans, where the live member is a getter with no backing field,
it is the method name (`getAge`).

### Wire format and limitations

Producers and consumers must agree on the `withSchemaEvolution()` flag — they are not
wire-compatible otherwise. Row payloads always carry an 8-byte hash slot; under evolution its
value is the strict hash (which includes field name and nullability), so a flag-mismatched
peer fails loudly with `ClassNotCompatibleException`. Arrays and maps of bean elements prepend
an 8-byte strict-hash prefix under evolution and no prefix otherwise; an evolution-on consumer
reading evolution-off bytes also fails with `ClassNotCompatibleException`, but the reverse
direction (evolution-off consumer, evolution-on bytes) is undefined.

To adopt the flag on an existing deployment, enable `withSchemaEvolution()` on both sides in a
release that changes no schema, then start evolving schemas only once every peer is on the
evolution-enabled build. Turning the flag on and changing a schema in the same release strands
any peer that has not yet upgraded.

Cross-language consumers (Python, C++) cannot read evolution-enabled payloads.

A reader selects the matching layout from the 8-byte strict hash on the payload. The hash includes
field names and nullability and is checked for collisions across a bean's own versions when the
codec is built, but it is still a 64-bit value: a payload whose hash coincides with one of the
reader's historical layouts is decoded against that layout. This is the same hash-based dispatch
the row format has always used, so feeding a codec bytes it was not built for has undefined results
whether or not evolution is enabled. Only hand a codec payloads produced for the same bean.

Nested evolution works to arbitrary depth and places no restriction on shape: a versioned bean
may contain versioned beans that themselves contain versioned beans, the same versioned bean
class may back more than one field, and fields typed as a non-evolving bean, a list, or a map are
unrestricted. Each nesting level is routed to the correct historical layout. A versioned bean may
be used as a map key as well as a map value, and the key and value evolve independently. This
holds wherever the map appears: as the codec's top-level type, nested inside a bean field, or
reached through a top-level array or map (such as `List<Map<KeyBean, ValueBean>>`), and a single
map may evolve more than one distinct bean class across its key and value. A top-level map carries
its own hash identifying both layouts together; a map nested inside an array, another map, or a
bean field has its layouts folded into the enclosing payload's hash.

When a versioned bean contains other versioned beans, the reader generates one projection codec
class per combination of versions across the composition. The count grows as the product of the
version counts of the distinct nested versioned bean classes, not the number of fields, so
reusing a class across several fields adds no combinations. A map whose key and value both evolve
multiplies their version counts the same way. If the product across distinct classes becomes a
concern, drop entries from each bean's `History` interface once you no longer need to read payloads
from that range. Retiring a history entry is purely a read-side decision; the writer always uses the
current schema.

## Related Topics

- [Xlang Serialization](xlang-serialization.md) - xlang mode
- [Advanced Features](advanced-features.md) - Zero-copy serialization
- [Row Format Specification](https://fory.apache.org/docs/specification/row_format_spec) - Protocol details
