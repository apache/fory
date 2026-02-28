---
title: Protobuf IDL Support
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

This page explains how Apache Fory works with Protocol Buffers (`.proto`) schemas,
how protobuf concepts map to Fory, and how to use protobuf-only Fory extension options.

## What This Page Covers

- Choosing protobuf vs Fory for your use case
- Syntax and semantic differences that matter during migration
- Supported Fory extension options in protobuf files
- Practical migration patterns from protobuf to Fory

## Quick Decision Guide

| Situation                                                     | Recommended Format |
| ------------------------------------------------------------- | ------------------ |
| You are building gRPC APIs and rely on protobuf tooling       | Protocol Buffers   |
| You need maximum object-graph performance and ref tracking    | Fory               |
| You need circular/shared references in serialized data        | Fory               |
| You need strong unknown-field behavior for wire compatibility | Protocol Buffers   |
| You need native structs/classes instead of protobuf wrappers  | Fory               |

## Protobuf vs Fory at a Glance

| Aspect             | Protocol Buffers              | Fory                                  |
| ------------------ | ----------------------------- | ------------------------------------- |
| Primary purpose    | RPC/message contracts         | High-performance object serialization |
| Encoding model     | Tag-length-value              | Fory binary protocol                  |
| Reference tracking | Not built-in                  | First-class (`ref`)                   |
| Circular refs      | Not supported                 | Supported                             |
| Unknown fields     | Preserved                     | Not preserved                         |
| Generated types    | Protobuf-specific model types | Native language constructs            |
| gRPC ecosystem     | Native                        | In progress (active development)      |

Fory gRPC support is under active development. For production gRPC
workflows today, protobuf remains the mature/default choice.

## Why Use Apache Fory

- Idiomatic generated code: Fory IDL generates language-idiomatic classes and
  structs that can be used directly as domain objects.
- Faster serialization: In Fory benchmarks, Fory can be around 10x faster than
  protobuf for object serialization workloads.
- Better graph modeling: Shared and circular references are first-class features
  instead of application-level ID-link workarounds.

See benchmark details under [Performance References](#performance-references).

## Syntax and Semantic Mapping

### Package and File Options

**Protocol Buffers**

```protobuf
syntax = "proto3";
package example.models;
option java_package = "com.example.models";
option go_package = "example.com/models";
```

**Fory**

```protobuf
package example.models;
```

Fory uses one package namespace for cross-language registration. Language-specific
package placement is still configurable in code generation.

### Message and Enum Definitions

**Protocol Buffers**

```protobuf
message User {
  string id = 1;
  string name = 2;
  optional string email = 3;
  int32 age = 4;
  repeated string tags = 5;
  map<string, string> metadata = 6;
}

enum Status {
  STATUS_UNSPECIFIED = 0;
  STATUS_ACTIVE = 1;
}
```

**Fory**

```protobuf
message User [id=101] {
    string id = 1;
    string name = 2;
    optional string email = 3;
    int32 age = 4;
    list<string> tags = 5;
    map<string, string> metadata = 6;
}

enum Status [id=102] {
    UNKNOWN = 0;
    ACTIVE = 1;
}
```

Key differences:

- Fory can assign stable type IDs directly (`[id=...]`).
- Fory uses `list<T>` (with `repeated T` as alias).
- Enum naming conventions are language-driven instead of protobuf prefix style.

### `oneof` to `union`

Protobuf `oneof` is translated to a nested Fory `union` plus an optional field
referencing that union.

**Protocol Buffers**

```protobuf
message Event {
  oneof payload {
    string text = 1;
    int32 number = 2;
  }
}
```

**Fory-style shape after translation**

```protobuf
message Event {
    union payload {
        string text = 1;
        int32 number = 2;
    }
    optional payload payload = 1;
}
```

Notes:

- Union case IDs are derived from the original `oneof` field numbers.
- The synthetic union field uses the smallest `oneof` case number.

### Imports and Well-Known Types

Protobuf imports are supported. Common well-known types map directly:

- `google.protobuf.Timestamp` -> `timestamp`
- `google.protobuf.Duration` -> `duration`
- `google.protobuf.Any` -> `any`

## Type Mapping Highlights

| Protobuf Type                            | Fory Mapping                             |
| ---------------------------------------- | ---------------------------------------- |
| `bool`                                   | `bool`                                   |
| `int32`, `uint32`                        | variable-length 32-bit integer kinds     |
| `sint32`                                 | zigzag 32-bit integer                    |
| `int64`, `uint64`                        | variable-length 64-bit integer kinds     |
| `sint64`                                 | zigzag 64-bit integer                    |
| `fixed32`, `fixed64`                     | fixed-width unsigned integer kinds       |
| `sfixed32`, `sfixed64`                   | fixed-width signed integer kinds         |
| `float`, `double`                        | `float32`, `float64`                     |
| `string`, `bytes`                        | `string`, `bytes`                        |
| `repeated T`                             | `list<T>`                                |
| `map<K, V>`                              | `map<K, V>`                              |
| `optional T`                             | `optional T`                             |
| `oneof`                                  | `union` + optional union reference field |
| `int64 [(fory).type = "tagged_int64"]`   | `tagged_int64` encoding                  |
| `uint64 [(fory).type = "tagged_uint64"]` | `tagged_uint64` encoding                 |

## Fory Extension Options (Protobuf)

Fory-specific options in `.proto` use the `(fory).` prefix.

```protobuf
option (fory).enable_auto_type_id = true;

message TreeNode {
  TreeNode parent = 1 [(fory).weak_ref = true];
  repeated TreeNode children = 2 [(fory).ref = true];
}
```

### File-Level Options

| Option                               | Type   | Description                                                                                  |
| ------------------------------------ | ------ | -------------------------------------------------------------------------------------------- |
| `(fory).use_record_for_java_message` | bool   | Generate Java records for all messages in this file                                          |
| `(fory).polymorphism`                | bool   | Enable polymorphic serialization metadata by default                                         |
| `(fory).enable_auto_type_id`         | bool   | Auto-generate type IDs when omitted (compiler default is true)                               |
| `(fory).evolving`                    | bool   | Default schema-evolution behavior for messages                                               |
| `(fory).go_nested_type_style`        | string | Go nested naming style: `underscore` (default) or `camelcase`                                |
| `(fory).swift_namespace_style`       | string | Swift namespace style: `enum` (default) or `flatten`; applies only when package is non-empty |

### Message and Enum Options

| Option                       | Applies To    | Type   | Description                              |
| ---------------------------- | ------------- | ------ | ---------------------------------------- |
| `(fory).id`                  | message, enum | int    | Explicit type ID for registration        |
| `(fory).alias`               | message, enum | string | Alternate name used for auto-ID hashing  |
| `(fory).evolving`            | message       | bool   | Override file-level evolution setting    |
| `(fory).use_record_for_java` | message       | bool   | Generate Java record for this message    |
| `(fory).deprecated`          | message, enum | bool   | Mark type as deprecated                  |
| `(fory).namespace`           | message       | string | Override default package-based namespace |

### Field-Level Options

| Option                       | Type   | Description                                                  |
| ---------------------------- | ------ | ------------------------------------------------------------ |
| `(fory).ref`                 | bool   | Enable reference tracking for this field                     |
| `(fory).nullable`            | bool   | Treat field as nullable (`optional`)                         |
| `(fory).weak_ref`            | bool   | Generate weak pointer semantics (C++/Rust codegen)           |
| `(fory).thread_safe_pointer` | bool   | Rust pointer flavor for ref fields (`Arc` vs `Rc`)           |
| `(fory).deprecated`          | bool   | Mark field as deprecated                                     |
| `(fory).type`                | string | Primitive override, currently `tagged_int64`/`tagged_uint64` |

Reference option behavior:

- `weak_ref = true` implies ref tracking.
- For `repeated` fields, `(fory).ref = true` applies to list elements.
- For `map<K, V>` fields, `(fory).ref = true` applies to map values.
- `weak_ref` and `thread_safe_pointer` are codegen hints for C++/Rust.

### Option Examples by Shape

```protobuf
message Graph {
  Node root = 1 [(fory).ref = true, (fory).thread_safe_pointer = false];
  repeated Node nodes = 2 [(fory).ref = true];
  map<string, Node> cache = 3 [(fory).ref = true];
  Node parent = 4 [(fory).weak_ref = true];
}
```

## Reference Tracking vs Protobuf IDs

Protobuf itself does not preserve shared/cyclic object graphs. With Fory
protobuf extensions, you can opt into graph semantics.

**Without Fory ref options (protobuf-style IDs):**

```protobuf
message TreeNode {
  string id = 1;
  string parent_id = 2;
  repeated string child_ids = 3;
}
```

**With Fory ref options (object graph):**

```protobuf
message TreeNode {
  TreeNode parent = 1 [(fory).weak_ref = true];
  repeated TreeNode children = 2 [(fory).ref = true];
}
```

## Migration Guide: Protobuf to Fory

### Step 1: Translate Schema Syntax

- Keep package names stable.
- Replace `repeated T` with `list<T>` (or keep `repeated` alias).
- Add explicit `[id=...]` where you need stable numeric registration.

### Step 2: Convert `oneof` and Special Types

- `oneof` -> `union` + optional union field.
- Map protobuf well-known types to Fory primitives (`timestamp`, `duration`, `any`).

### Step 3: Replace Protobuf Workarounds with `ref`

Where protobuf used manual ID links for object graphs, switch to Fory `ref`
modifiers (and optional `ref(weak=true)` where needed).

### Step 4: Update Build/Codegen

Replace protobuf generation steps with the Fory compiler invocation for target
languages.

### Step 5: Run Compatibility Checks

For staged migrations, keep both formats in parallel and verify payload-level
parity with integration tests.

## Coexistence Strategy

You can run protobuf and Fory in parallel during migration:

```java
public byte[] serialize(Object obj, Format format) {
    if (format == Format.PROTOBUF) {
        return ((MessageLite) obj).toByteArray();
    }
    return fory.serialize(obj);
}
```

Use translators at service boundaries while internal object-graph heavy paths
migrate first.

## Performance References

- Benchmarks: https://fory.apache.org/docs/introduction/benchmark
- Benchmark code: https://github.com/apache/fory/tree/main/benchmarks

## Summary

Use protobuf when your primary concern is API contracts and gRPC ecosystem
integration. Use Fory when object-graph performance, native models, and
reference semantics are the primary concern.
