---
title: Xlang Serialization
sidebar_position: 2
id: xlang
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

Xlang is Fory's default object serialization mode. It uses one portable binary format across Java,
Python, C++, Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. Each
language-specific [Basic Serialization](#language-guides) page documents the relevant API and model
examples; this page explains the rules that peers must share.

Read [Core Concepts](core-concepts.md) first for the object graph, schema, reference, and
polymorphism concepts shared by xlang and native modes.

## Overview

Use xlang serialization when bytes cross language boundaries, including polyglot services, data
pipelines, and frontend/backend communication. It provides:

- Direct serialization of native language models without requiring an IDL.
- Coordinated numeric or named identities for application types.
- Compatible schema evolution for independently deployed peers.
- Optional shared-reference and circular-reference preservation.
- Polymorphic values when every concrete type has a portable mapping.
- Out-of-band buffers for large binary and numeric data where the Fory implementation supports them.

Use [Native Serialization](native.md) instead when every writer and reader uses one supported Fory
implementation family and the object graph needs language-specific behavior such as Java
serialization hooks or Python pickle-compatible objects.

### Supported Languages

| Language              | Package or target                          | Modes        |
| --------------------- | ------------------------------------------ | ------------ |
| Java                  | `org.apache.fory:fory-core`                | xlang/native |
| Python                | `pyfory`                                   | xlang/native |
| C++                   | Fory C++ CMake or Bazel target             | xlang/native |
| Go                    | `github.com/apache/fory/go/fory`           | xlang/native |
| Rust                  | `fory` crate                               | xlang/native |
| JavaScript/TypeScript | `@apache-fory/core`                        | xlang        |
| C#                    | `Apache.Fory`                              | xlang        |
| Swift                 | `Fory` Swift Package Manager target        | xlang        |
| Dart                  | `fory` package                             | xlang        |
| Scala                 | `org.apache.fory:fory-scala`               | xlang/native |
| Kotlin                | `org.apache.fory:fory-kotlin` and Java API | xlang/native |

### First Cross-Language Round Trip

Register the same logical type identity and compatible fields on every peer. The following example
uses a shared type name.

Rust producer:

```rust
use fory::{Fory, ForyStruct};

#[derive(ForyStruct)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder().xlang(true).build();
fory.register_by_name::<Person>("example.Person").unwrap();

let person = Person {
    name: "Alice".to_string(),
    age: 30,
};
let bytes = fory.serialize(&person).unwrap();
```

Python consumer:

```python
from dataclasses import dataclass
import pyfory

@dataclass
class Person:
    name: str
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, name="example.Person")
person = fory.deserialize(bytes_from_rust)
```

Although xlang is the default, examples often select it explicitly so the transport contract is
visible in application code.

### Native Models or Fory IDL

| Approach                          | Use when                                                               |
| --------------------------------- | ---------------------------------------------------------------------- |
| Native language models            | The contract is small and teams want to start without a compiler step  |
| [Fory IDL](../compiler/index.md)  | Many messages or teams need one schema and generated models            |
| Native first, then migrate to IDL | A small contract is becoming a long-lived, multi-team service boundary |

A minimal Fory IDL message looks like this:

```protobuf
package example;

message Person {
  string name = 1;
  int32 age = 2;
  optional string email = 3;
}
```

Generate the required language targets with `foryc`; generated models use consistent field and type
metadata across those targets.

## Type System and Type Identity

### Built-in and Custom Types

Primitive numeric values, strings, binary values, temporal values, lists, sets, maps, dense numeric
arrays, enums, structs, and unions have shared xlang schemas. Built-in values do not require user
registration. Application structs, enums, unions, and extension types require a coordinated
identity.

The normative [type mapping](../specification/xlang_type_mapping.md) defines the exact host-language
carrier for every xlang type. Important cases include:

- Python uses markers such as `pyfory.Int32`, `pyfory.Float16`, and `pyfory.BFloat16` when the native
  Python type does not express the required width.
- Java, Dart, and other languages use annotations or schema metadata where one host type can represent
  multiple xlang types.
- Reduced-precision `float16` and `bfloat16` values and dense arrays use language-specific carriers.
- `list<T>` and dense `array<T>` are distinct schemas. In compatible mode, a direct struct field may
  adapt between a list and dense bool/numeric array when the element domain is compatible and the
  actual list contains no unrepresentable null or reference-tracked element.

Use the mapping specification instead of inferring compatibility from similar host-language names.

### Coordinate Type Identity

Every peer must register a custom type with either the same numeric ID or the same namespace and type
name. Numeric IDs produce smaller metadata; names are easier to coordinate across independently
owned services. Do not register one peer by ID and another by name for the same contract.

Registration must happen before the first root serialization or deserialization operation. Keep a
small contract registry or use generated Fory IDL modules when multiple teams own the peers.

### Static and Dynamic Fields

A statically known field uses its declared serializer without writing a concrete type. A
dynamic field carries enough type information to select the concrete registered type. Dynamic
metadata is needed for interfaces, abstract types, trait objects, and other polymorphic positions;
it is unnecessary for primitives and exact final types.

| Language | Dynamic field model                                               |
| -------- | ----------------------------------------------------------------- |
| Java     | `@ForyField(dynamic = ...)` controls automatic or forced metadata |
| Python   | `pyfory.field(dynamic=...)` controls object-field metadata        |
| C++      | `fory::F(...).dynamic(...)` overrides automatic detection         |
| Go       | Interface fields express dynamic values                           |
| Rust     | Trait-object carriers express dynamic values                      |

Writing dynamic metadata costs space and type-resolution work. Disable it only when the field can
never contain another concrete type. Exact annotation and registration examples belong to each
language's schema metadata, type registration, and polymorphism pages.

## Nullability and Reference Tracking

Nullability and reference tracking solve different problems:

| Concern            | Purpose                                                       |
| ------------------ | ------------------------------------------------------------- |
| Nullability        | Allows a field or value position to contain no value          |
| Reference tracking | Preserves repeated object identity and supports object cycles |

The wire framing is defined by the
[xlang serialization specification](../specification/xlang_serialization_spec.md). Applications
should configure the semantic behavior through their language-specific Fory APIs rather than depend
on flag values.

### Nullability

Xlang struct fields are non-nullable by default. Non-nullable fields are smaller and make required
data explicit. Nullable or optional carriers include Java boxed/annotated values, Python
`Optional[T]`, C++ `std::optional<T>`, Go pointers, Rust `Option<T>`, and Scala `Option[T]`.

Keep corresponding fields consistently nullable across peers. In same-schema mode, changing
nullability changes the schema and is incompatible. Compatible mode supports the documented
nullable and scalar adaptations, but a remote null still cannot be materialized into a local type
that has no valid null or missing-value behavior.

### Shared and Circular References

Enable reference tracking when the graph contains the same object more than once or contains a
cycle. Leave it disabled for value-shaped data to avoid identity-table overhead.

```java
import org.apache.fory.Fory;
import org.apache.fory.annotation.Ref;

public class Node {
  public String value;
  @Ref public Node next;
}

Node first = new Node();
Node second = new Node();
first.next = second;
second.next = first;

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)
    .build();
```

Global reference tracking enables the reference-tracking mechanism; field metadata selects which positions
participate. Common field-level controls are Java and Scala `@Ref`, Go `fory:"ref"` tags, Rust
`#[fory(ref = true)]`, and C++ smart-pointer or `fory::F().ref()` metadata. Consult the language guide
because default tracking differs by carrier and language.

Reference support also follows the host ownership model. For example, Rust can preserve supported
shared-reference carriers, while cycles require representable ownership and weak-reference shapes.

## Polymorphism

Xlang polymorphism preserves a value's concrete registered type when its declared field, collection
element, or root type is broader. Every receiving peer must:

1. Register the same concrete type identity.
2. Provide a compatible field schema for that concrete type.
3. Mark or model the position as dynamic when the Fory implementation cannot infer it.
4. Use a concrete type that has a portable xlang mapping.

Host-language inheritance alone does not make a type portable. If a shape has no xlang mapping, use
native mode for traffic within one Fory implementation family or define a portable model. See the
language polymorphism pages for interfaces, trait objects, unions, and generated-code syntax.

## Schema Evolution

Compatible mode is the xlang default. It carries schema metadata so independently deployed readers
can tolerate supported field additions, removals, reordering, and documented compatible type
adaptations.

Keep compatible mode when peers may deploy separately. Select same-schema mode only when every
reader and writer uses the same:

- Type identity and field IDs or names.
- Field types and nested generic shapes.
- Nullability and reference metadata.
- Polymorphic alternatives.

Same-schema mode reduces metadata and payload size, but any mismatch can produce a schema hash or
type error. Generated Fory IDL models make exact coordination easier when all peers are released
together. Normative compatibility behavior lives in the
[xlang serialization specification](../specification/xlang_serialization_spec.md).

## Zero-Copy Serialization

Some Fory implementations can move large binary or numeric buffers out of the main serialized byte stream. This
avoids copying those buffers into one contiguous payload.

The transport flow is:

1. Serialize the object graph and collect selected buffer objects through a callback.
2. Send the main metadata bytes and the collected buffers separately.
3. Provide the buffers in the same order during deserialization.

Java:

```java
Collection<BufferObject> objects = new ArrayList<>();
byte[] metadata = fory.serialize(value, object -> !objects.add(object));
List<MemoryBuffer> buffers = objects.stream()
    .map(BufferObject::toBuffer)
    .toList();
Object decoded = fory.deserialize(metadata, buffers);
```

Python:

```python
objects = []
metadata = fory.serialize(value, buffer_callback=objects.append)
buffers = [obj.to_buffer() for obj in objects]
decoded = fory.deserialize(metadata, buffers=buffers)
```

Go exposes the equivalent callback-buffer flow through its serialization and buffer APIs. Use the
language guide for the current method names and supported buffer carriers.

Out-of-band serialization helps when buffers are large and the transport can send them without an
additional copy. For small arrays, callback and multi-buffer transport overhead may cost more than
copying. The application owns buffer ordering, lifetime, and transport framing. See
[Python Out-of-Band Serialization](python/out-of-band.md) for Python and NumPy details.

## Troubleshooting

| Symptom                                  | Likely cause                                        | Resolution                                                                |
| ---------------------------------------- | --------------------------------------------------- | ------------------------------------------------------------------------- |
| Type is not registered                   | Registration missing or performed too late          | Register every custom type before the first root operation                |
| Type ID or name mismatch                 | Peers use different identities                      | Use the same numeric ID or the same namespace and type name               |
| Integer overflow or float precision loss | Host carriers use different numeric widths          | Follow the type mapping and use explicit width metadata                   |
| Fields decode incorrectly                | Field IDs, names, or types differ                   | Align field metadata or regenerate all peers from the same IDL            |
| Stack overflow on a cyclic graph         | Reference tracking is disabled                      | Enable global and field-level reference tracking                          |
| Shared objects become duplicates         | The value position does not track references        | Enable reference tracking for that carrier or field                       |
| Unsupported host type                    | The type has no portable xlang representation       | Use a portable model or native mode within one Fory implementation family |
| Schema/hash mismatch                     | Same-schema peers have different schemas            | Align every peer or restore compatible mode                               |
| Failure after an upgrade                 | Peers run incompatible protocol versions            | Align supported Fory versions and review release notes                    |
| Payload rejected immediately             | One peer wrote native bytes and another reads xlang | Keep all peers on xlang for a cross-language contract                     |

### Diagnostic Checklist

1. Confirm that every peer uses xlang mode and a mutually supported Fory version.
2. Compare the registered type identity, field IDs or names, numeric widths, nullability, and
   reference metadata.
3. Reproduce a local round trip in each Fory implementation before testing the cross-language direction.
4. Test both directions for every language pair used in production.
5. Reduce the value to one type and field, then add fields back until the mismatch appears.
6. Inspect the language-specific troubleshooting page for generated-code, platform, or API errors.

When diagnosing binary layout, use the specifications and implementation-specific debug facilities. Do not treat a
hex dump or internal flag value as a stable application API.

## Language Guides

- [Java](java/basic-serialization.md#cross-language-interoperability)
- [Python](python/basic-serialization.md#cross-language-interoperability)
- [C++](cpp/basic-serialization.md#cross-language-interoperability)
- [Go](go/basic-serialization.md#cross-language-interoperability)
- [Rust](rust/basic-serialization.md#cross-language-interoperability)
- [JavaScript/TypeScript](javascript/basic-serialization.md#cross-language-interoperability)
- [C#](csharp/basic-serialization.md#cross-language-interoperability)
- [Swift](swift/basic-serialization.md#cross-language-interoperability)
- [Dart](dart/basic-serialization.md#cross-language-interoperability)
- [Scala](scala/basic-serialization.md#cross-language-interoperability)
- [Kotlin](kotlin/basic-serialization.md#cross-language-interoperability)

## Related Documentation

- [Xlang Serialization Format](../specification/xlang_serialization_spec.md) — normative wire format
- [Xlang Type Mapping](../specification/xlang_type_mapping.md) — exact host-language carrier mappings
- [Fory IDL and Compiler](../compiler/index.md) — schema-first models and code generation
- [Getting Started](../start/index.md) — installation and first serialization for each language
- [Row Format](../row-format/index.md) — random-access analytical rows for trusted data

## Operational Best Practices

1. Coordinate one type identity and field contract across every peer.
2. Keep compatible mode unless all readers and writers deploy the same schema together.
3. Enable reference tracking only for identity-bearing or cyclic graphs.
4. Reuse configured Fory instances instead of rebuilding them for each operation.
5. Validate every production language pair in both directions before deployment.
6. Prefer Fory IDL once a contract spans many messages, services, or independently owned teams.
