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
  <version>1.7.7</version>
</dependency>
```

For Gradle:

```kotlin
implementation("org.apache.fory:fory-format:1.7.7")
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

## Schema Evolution

Enable `.withSchemaEvolution()` on a row, array, or map codec builder to read payloads written
by older versions of the same bean. Writing always uses the current version; reading detects
the payload's version from a strict hash at the head of the payload. Java is the only
implementation today. The protocol is language-neutral and defined in the
[row format specification](../specification/row_format_spec.md#schema-evolution).

Annotate fields added after v1 with `@ForyRowVersion(since = N)`:

```java
@Data
public class Person {
  String name;
  int age;

  @ForyRowVersion(since = 2)
  String email;
}
```

Build the codec with schema evolution enabled. It writes the current version and reads the
current and older versions:

```java
RowEncoder<Person> codec =
    Encoders.buildBeanCodec(Person.class).withSchemaEvolution().build().get();
byte[] bytes = codec.encode(person);
Person current = codec.decode(bytes);
// v1Bytes was written with schema evolution, before Person had the email field.
Person older = codec.decode(v1Bytes);
```

A v1 payload (with `name` and `age` only) decodes to a `Person` whose `email` is `null`.
Primitive fields added later default to `0`, `0.0`, or `false`, and `Optional` fields to
`Optional.empty()`. A field initializer does not apply to a field that the payload lacks.
Unannotated fields are treated as present from the first version, so a class can adopt versioning
by annotating only the fields added after v1.

Put the annotation on the field of a class, on a record component, or on an accessor method of an
interface bean. The codec does not read it from other methods, so building the codec fails when
another method carries it, such as the getter of a class field. A class bean does not read the
annotations of an interface that it implements, because they belong to the codec of that
interface bean. Annotate the fields of the class.

For a record, the absent component's default is passed to the canonical constructor, so a
constructor that rejects `null` for a reference component added in a later version throws when
decoding an older payload. Let the constructor tolerate the missing value, for example by
normalizing `null` to a default:

```java
public record Person(String name, @ForyRowVersion(since = 2) String email) {
  public Person {
    if (email == null) {
      email = "";
    }
  }
}
```

Remove a field by deleting the Java member and declaring it on a nested history interface as a
method with a `@ForyRowVersion(until = N)`. The method's return type carries any parameterized
type information from the original field. `removedFields` must name an interface; building the
codec fails for a class.

```java
@Data
@ForyRowSchema(removedFields = Person.History.class)
public class Person {
  String name;

  @ForyRowVersion(since = 2)
  String email;

  interface History {
    @ForyRowVersion(until = 3)
    int age();

    @ForyRowVersion(until = 5)
    List<String> tags();
  }
}
```

The history method name matches the name of the original live member. For a class or a record,
including a Lombok `@Data` class, that is the field name (`age`, `tags`). For an interface bean,
it is the accessor method name (`getAge`). The codec cannot detect a history method with the wrong
name: a class that declares `getTags()` instead of `tags()` gets the wire name `get_tags`, so
every historical layout that holds that field hashes differently from the payloads that the old
version wrote, and those payloads fail to decode with `ClassNotCompatibleException`. Test that an
old payload still decodes before you retire a live field.

A subclass inherits the removed fields that its superclasses declare with `@ForyRowSchema`, and
an interface bean inherits those of its superinterfaces, the same as it inherits their live fields.

A field that is removed and later added again with the same name and the same Java type is one
field: older payloads that hold it decode with its value. If the Java type changes and the row
type or the nullability changes too (for example `String` to `int`, or `Integer` to `int`), older
values are discarded. If the Java type changes but the row type and the nullability stay the same
(for example `String` to an enum), or either type reaches a versioned bean (directly, or through a
list or map), building the codec fails, because an old layout could be identical to the new one
and old values would decode as the new type. To change the type of a field, give the new member a
new name.

A field handled by a custom codec evolves as its encoded representation. When `getForyField`
returns null, the column follows `encodedType()`, and a versioned bean in that type evolves as
usual. When `getForyField` returns a field, that field is the whole column: the members of the
declared Java type do not change the layout, and their `@ForyRowVersion` annotations have no
effect.

### Wire Format and Limitations

Producers and consumers must agree on the `withSchemaEvolution()` flag — they are not
wire-compatible otherwise. Row payloads always carry an 8-byte hash slot; under evolution its
value is the strict hash (which includes field name and nullability), so a flag-mismatched
peer fails loudly with `ClassNotCompatibleException`. Arrays and maps prepend an 8-byte prefix
under evolution (the strict hash for an array, the combined key and value hash for a map) and no
prefix otherwise. An evolution-on consumer rejects evolution-off array and map bytes. An
evolution-off consumer cannot detect evolution-on array and map bytes, so the result of that read
is undefined. With evolution on, the hash also identifies the row format, so a compact consumer
rejects standard payloads with `ClassNotCompatibleException`, and the reverse.

Only `encode` and `decode` write and check the hash. `toRow`, `fromRow`, `toArray`, `fromArray`,
`toMap`, and `fromMap` always use the current schema, and the bytes of a `BinaryRow` hold no hash.
To read older versions, store the output of `encode` and read it with `decode`.

Turning the flag on changes the wire format of every payload, so do it as a cutover, not as a
rolling change. During a rolling deploy, flag-on and flag-off peers reject each other's rows, and
the result of a flag-off reader on flag-on arrays and maps is undefined. Rows written before the
cutover are not readable with the flag on. Stop or drain the producers, rewrite or stop reading
the stored payloads, and then start every peer with the flag on, in a release that changes no
schema.

After that, deploy every reader of a schema change before any writer. A reader decodes payloads
whose layout it knows, from its own version and older versions, and it rejects a payload whose
layout it does not know with `ClassNotCompatibleException`.

In a GraalVM native image, an evolution codec cannot decode a payload with an older layout: the
decode fails. This is a known limitation.

The hash algorithm, wire names, field order, and framing are specified language-neutrally in
the [row format specification](../specification/row_format_spec.md#schema-evolution). Java is the
only implementation today, so Python, C++, and other runtimes cannot read evolution-enabled
payloads until they implement that protocol.

Field identity on the wire is the snake_case form of the member name (`createdAt` becomes
`created_at`), and a versioned layout orders fields by that wire name. The `snake_case` conversion
inserts an underscore before each ASCII capital letter and lowercases it, and changes no other
character.

With schema evolution, a member name or a history method name with an underscore, such as
`created_at` or `count_`, fails the codec build. The build also fails when the member-name order
and the wire-name order differ, which happens only for a character outside the Basic Multilingual
Plane. The error names the members to rename. Other Fory runtimes do not all convert acronyms, a
leading capital letter, or non-ASCII capital letters the same way, so use ordinary lowerCamelCase
member names (`createdAt`, not `fooURL` or `URLValue`) for portability.

Because field names are wire identity, a rename is a removal plus an addition, not an in-place
mapping: declare the old name on the history interface with `until = N` and annotate the renamed
member with `since = N`. Older payloads then decode with the renamed field at its default, not
carrying over the old field's value; renaming without those declarations changes the payload hash
and older payloads stop decoding. The wire name derives from the field name for a class or a
record, and from the accessor method name for an interface bean, so converting a bean between a
class and an interface also changes every wire name and is a rename of every field.

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
be used as a map key as well as a map value. When the key and value are different classes, they
evolve independently; when one class backs both, a payload holds one version of it. This
holds wherever the map appears: as the codec's top-level type, nested inside a bean field, or
reached through a top-level array or map (such as `List<Map<KeyBean, ValueBean>>`), and a single
map may evolve more than one distinct bean class across its key and value. A top-level map carries
its own hash identifying both layouts together; a map nested inside an array, another map, or a
bean field has its layouts folded into the enclosing payload's hash.

When a versioned bean contains other versioned beans, the reader can read one layout per
combination of versions across the composition, with each bean class at one version. Building the
codec computes the layout and hash of every combination, so the build time and memory grow with
the product of the version counts of the distinct versioned bean classes, and the codec sets no
limit. A map whose key and value both evolve combines their versions the same way. The first
`decode` of a payload with each older layout takes longer than later ones, because the codec
prepares its reader for that layout inside that call.

To bound the growth, stop reading the oldest layouts: remove an entry from a bean's `History`
interface, or remove or lower the `since` of a live field so that no layout without that field
remains. Either change removes every layout that differs only by that field, so the reader then
rejects every payload written with one of those layouts. This is a read-side decision only; the
writer always uses the current schema.

## Related Topics

- [Cross-Language Interoperability](../object-serialization/java/basic-serialization.md#cross-language-interoperability) - xlang mode
- [Java Advanced Features](../object-serialization/java/advanced-features.md) - Zero-copy object serialization
- [Row Format Specification](https://fory.apache.org/docs/specification/row_format_spec) - Protocol details
