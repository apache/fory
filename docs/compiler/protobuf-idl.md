---
title: Protocol Buffers IDL
sidebar_position: 10
id: protobuf_idl_support
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

This document compares Google's Protocol Buffers (protobuf) with Fory Definition Language (FDL), helping you understand when to use each and how to migrate between them.

## Overview

| Aspect                 | Protocol Buffers                  | FDL                                 |
| ---------------------- | --------------------------------- | ----------------------------------- |
| **Primary Purpose**    | RPC and message interchange       | Cross-language object serialization |
| **Design Philosophy**  | Schema evolution, backward compat | Performance, native integration     |
| **Reference Tracking** | Not supported                     | First-class support (`ref`)         |
| **Generated Code**     | Custom message types              | Native language constructs          |
| **Serialization**      | Tag-length-value encoding         | Fory binary protocol                |
| **Performance**        | Good                              | Excellent (up to 170x faster)       |

## Syntax Comparison

### Package Declaration

**Protocol Buffers:**

```protobuf
syntax = "proto3";
package example.models;
option java_package = "com.example.models";
option go_package = "example.com/models";
```

**FDL:**

```protobuf
package example.models;
```

FDL uses a single package declaration that maps to all languages automatically.

### Enum Definition

**Protocol Buffers:**

```protobuf
enum Status {
  STATUS_UNSPECIFIED = 0;
  STATUS_PENDING = 1;
  STATUS_ACTIVE = 2;
  STATUS_COMPLETED = 3;
}
```

**FDL:**

```protobuf
enum Status [id=100] {
    PENDING = 0;
    ACTIVE = 1;
    COMPLETED = 2;
}
```

Key differences:

- FDL supports optional type IDs (`[id=100]`) for efficient serialization
- Protobuf requires `_UNSPECIFIED = 0` by convention; FDL uses explicit values
- FDL enum values don't require prefixes

### Message Definition

**Protocol Buffers:**

```protobuf
message User {
  string id = 1;
  string name = 2;
  optional string email = 3;
  int32 age = 4;
  repeated string tags = 5;
  map<string, string> metadata = 6;
}
```

**FDL:**

```protobuf
message User [id=101] {
    string id = 1;
    string name = 2;
    optional string email = 3;
    int32 age = 4;
    repeated string tags = 5;
    map<string, string> metadata = 6;
}
```

Syntax is nearly identical, but FDL adds:

- Type IDs (`[id=101]`) for cross-language registration
- `ref` modifier for reference tracking

### Nested Types

**Protocol Buffers:**

```protobuf
message Order {
  message Item {
    string product_id = 1;
    int32 quantity = 2;
  }
  repeated Item items = 1;
}
```

**FDL:**

```protobuf
message OrderItem [id=200] {
    string product_id = 1;
    int32 quantity = 2;
}

message Order [id=201] {
    repeated OrderItem items = 1;
}
```

FDL supports nested types, but generators may flatten them for languages where nested types are not idiomatic.

### Imports

**Protocol Buffers:**

```protobuf
import "other.proto";
import "google/protobuf/timestamp.proto";
```

**FDL:**

FDL currently requires all types in a single file or uses forward references within the same file.

## Feature Comparison

### Reference Tracking

FDL's killer feature is first-class reference tracking:

**FDL:**

```protobuf
message TreeNode [id=300] {
    string value = 1;
    ref TreeNode parent = 2;
    repeated ref TreeNode children = 3; // Element refs
    ref repeated TreeNode path = 4;     // Collection ref
}

message Graph [id=301] {
    repeated ref Node nodes = 1;  // Shared references preserved (elements)
}
```

**Protocol Buffers:**

Protobuf cannot represent circular or shared references. You must use workarounds:

```protobuf
// Workaround: Use IDs instead of references
message TreeNode {
  string id = 1;
  string value = 2;
  string parent_id = 3;        // Manual ID reference
  repeated string child_ids = 4;
}
```

### Type System

| Type       | Protocol Buffers                                                                                       | FDL                               |
| ---------- | ------------------------------------------------------------------------------------------------------ | --------------------------------- |
| Boolean    | `bool`                                                                                                 | `bool`                            |
| Integers   | `int32`, `int64`, `sint32`, `sint64`, `uint32`, `uint64`, `fixed32`, `fixed64`, `sfixed32`, `sfixed64` | `int8`, `int16`, `int32`, `int64` |
| Floats     | `float`, `double`                                                                                      | `float32`, `float64`              |
| String     | `string`                                                                                               | `string`                          |
| Binary     | `bytes`                                                                                                | `bytes`                           |
| Timestamp  | `google.protobuf.Timestamp`                                                                            | `timestamp`                       |
| Date       | Not built-in                                                                                           | `date`                            |
| Duration   | `google.protobuf.Duration`                                                                             | Not built-in                      |
| List       | `repeated T`                                                                                           | `repeated T`                      |
| Map        | `map<K, V>`                                                                                            | `map<K, V>`                       |
| Nullable   | `optional T` (proto3)                                                                                  | `optional T`                      |
| Oneof      | `oneof`                                                                                                | Not supported                     |
| Any        | `google.protobuf.Any`                                                                                  | Not supported                     |
| Extensions | `extend`                                                                                               | Not supported                     |

### Wire Format

**Protocol Buffers:**

- Tag-length-value encoding
- Variable-length integers (varints)
- Field numbers encoded in wire format
- Unknown fields preserved

**FDL/Fory:**

- Optimized binary format
- Schema-aware encoding
- Type IDs for fast lookup
- Reference tracking support
- Zero-copy deserialization where possible

### Generated Code Style

**Protocol Buffers** generates custom types with builders and accessors:

```java
// Protobuf generated Java
User user = User.newBuilder()
    .setId("u123")
    .setName("Alice")
    .setAge(30)
    .build();
```

**FDL** generates native POJOs:

```java
// FDL generated Java
User user = new User();
user.setId("u123");
user.setName("Alice");
user.setAge(30);
```

### Comparison Table

| Feature                    | Protocol Buffers  | FDL       |
| -------------------------- | ----------------- | --------- |
| Schema evolution           | Excellent         | Good      |
| Backward compatibility     | Excellent         | Good      |
| Reference tracking         | No                | Yes       |
| Circular references        | No                | Yes       |
| Native code generation     | No (custom types) | Yes       |
| Unknown field preservation | Yes               | No        |
| Schema-less mode           | No                | Yes\*     |
| RPC integration (gRPC)     | Yes               | No        |
| Zero-copy deserialization  | Limited           | Yes       |
| Human-readable format      | JSON, TextFormat  | No        |
| Performance                | Good              | Excellent |

\*Fory supports schema-less serialization without FDL

## When to Use Each

### Use Protocol Buffers When:

1. **Building gRPC services**: Protobuf is the native format for gRPC
2. **Maximum backward compatibility**: Protobuf's unknown field handling is robust
3. **Schema evolution is critical**: Adding/removing fields across versions
4. **You need oneof/Any types**: Complex polymorphism requirements
5. **Human-readable debugging**: TextFormat and JSON transcoding available
6. **Ecosystem integration**: Wide tooling support (linting, documentation)

### Use FDL/Fory When:

1. **Performance is critical**: Up to 170x faster than protobuf
2. **Cross-language object graphs**: Serialize Java objects, deserialize in Python
3. **Circular/shared references**: Object graphs with cycles
4. **Native code preferred**: Standard POJOs, dataclasses, structs
5. **Memory efficiency**: Zero-copy deserialization
6. **Existing object models**: Minimal changes to existing code

## Performance Comparison

Benchmarks show Fory significantly outperforms Protocol Buffers, see more details from below links:

- Benchmark result: https://fory.apache.org/docs/introduction/benchmark
- Benchmark code: https://github.com/apache/fory/tree/main/benchmarks
- Benchmark docs: https://github.com/apache/fory/tree/main/benchmarks

## Migration Guide

### From Protocol Buffers to FDL

#### Step 1: Convert Syntax

**Before (proto):**

```protobuf
syntax = "proto3";
package myapp;

message Person {
  string name = 1;
  int32 age = 2;
  repeated string emails = 3;
  Address address = 4;
}

message Address {
  string street = 1;
  string city = 2;
}
```

**After (FDL):**

```protobuf
package myapp;

message Address [id=100] {
    string street = 1;
    string city = 2;
}

message Person [id=101] {
    string name = 1;
    int32 age = 2;
    repeated string emails = 3;
    Address address = 4;
}
```

#### Step 2: Handle Special Cases

**oneof fields:**

```protobuf
// Proto
message Result {
  oneof result {
    Success success = 1;
    Error error = 2;
  }
}
```

```protobuf
// FDL - Use separate optional fields
message Result [id=102] {
    optional Success success = 1;
    optional Error error = 2;
}
// Or model as sealed class hierarchy in generated code
```

**Well-known types:**

```protobuf
// Proto
import "google/protobuf/timestamp.proto";
message Event {
  google.protobuf.Timestamp created_at = 1;
}
```

```protobuf
// FDL
message Event [id=103] {
    timestamp created_at = 1;
}
```

#### Step 3: Add Type IDs

Assign unique type IDs for cross-language compatibility:

```protobuf
// Reserve ranges for different domains
// 100-199: Common types
// 200-299: User domain
// 300-399: Order domain

message Address [id=100] { ... }
message Person [id=200] { ... }
message Order [id=300] { ... }
```

#### Step 4: Update Build Configuration

**Before (Maven with protobuf):**

```xml
<plugin>
  <groupId>org.xolstice.maven.plugins</groupId>
  <artifactId>protobuf-maven-plugin</artifactId>
  <!-- ... -->
</plugin>
```

**After (Maven with FDL):**

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>generate-fory-types</id>
      <phase>generate-sources</phase>
      <goals><goal>exec</goal></goals>
      <configuration>
        <executable>fory</executable>
        <arguments>
          <argument>compile</argument>
          <argument>${project.basedir}/src/main/fdl/schema.fdl</argument>
          <argument>--lang</argument>
          <argument>java</argument>
          <argument>--output</argument>
          <argument>${project.build.directory}/generated-sources/fdl</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
```

#### Step 5: Update Application Code

**Before (Protobuf Java):**

```java
// Protobuf style
Person.Builder builder = Person.newBuilder();
builder.setName("Alice");
builder.setAge(30);
Person person = builder.build();

byte[] data = person.toByteArray();
Person restored = Person.parseFrom(data);
```

**After (Fory Java):**

```java
// Fory style
Person person = new Person();
person.setName("Alice");
person.setAge(30);

Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
MyappForyRegistration.register(fory);

byte[] data = fory.serialize(person);
Person restored = (Person) fory.deserialize(data);
```

### Coexistence Strategy

For gradual migration, you can run both systems in parallel:

```java
// Dual serialization during migration
public byte[] serialize(Object obj, Format format) {
    if (format == Format.PROTOBUF) {
        return ((MessageLite) obj).toByteArray();
    } else {
        return fory.serialize(obj);
    }
}

// Convert between formats
public ForyPerson fromProto(ProtoPerson proto) {
    ForyPerson person = new ForyPerson();
    person.setName(proto.getName());
    person.setAge(proto.getAge());
    return person;
}
```

## Summary

| Aspect           | Choose Protocol Buffers | Choose FDL/Fory        |
| ---------------- | ----------------------- | ---------------------- |
| Use case         | RPC, API contracts      | Object serialization   |
| Performance      | Acceptable              | Critical               |
| References       | Not needed              | Circular/shared needed |
| Code style       | Builder pattern OK      | Native POJOs preferred |
| Schema evolution | Complex requirements    | Simpler requirements   |
| Ecosystem        | Need gRPC, tooling      | Need raw performance   |

Both tools excel in their domains. Protocol Buffers shines for RPC and API contracts with strong schema evolution guarantees. FDL/Fory excels at high-performance object serialization with native language integration and reference tracking support.
