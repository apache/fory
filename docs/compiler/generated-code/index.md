---
title: Generated Code
sidebar_position: 1
id: index
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

This document explains generated code for each target language.

Fory IDL generated types are idiomatic in host languages and can be used directly as domain objects. Generated types also include `to/from bytes` helpers and schema modules or registration helpers, depending on the target language.

Generated schema modules are named from the schema source file, not from the
package or namespace. In targets that expose the module directly in a language
package or namespace, names such as `AddressbookForyModule` or
`ComplexPbForyModule` let multiple IDL files target the same package or
namespace without producing colliding `ForyModule` types.

## Reference Schemas

The examples below use two real schemas:

1. `addressbook.fdl` (explicit type IDs)
2. `auto_id.fdl` (no explicit type IDs)

### `addressbook.fdl` Excerpt

```protobuf
package addressbook;

option go_package = "github.com/myorg/myrepo/gen/addressbook;addressbook";

message Person [id=100] {
    string name = 1;
    int32 id = 2;

    enum PhoneType [id=101] {
        PHONE_TYPE_MOBILE = 0;
        PHONE_TYPE_HOME = 1;
        PHONE_TYPE_WORK = 2;
    }

    message PhoneNumber [id=102] {
        string number = 1;
        PhoneType phone_type = 2;
    }

    list<PhoneNumber> phones = 7;
    Animal pet = 8;
}

message Dog [id=104] {
    string name = 1;
    int32 bark_volume = 2;
}

message Cat [id=105] {
    string name = 1;
    int32 lives = 2;
}

union Animal [id=106] {
    Dog dog = 1;
    Cat cat = 2;
}

message AddressBook [id=103] {
    list<Person> people = 1;
    map<string, Person> people_by_name = 2;
}
```

### `auto_id.fdl` Excerpt

```protobuf
package auto_id;

enum Status {
    UNKNOWN = 0;
    OK = 1;
}

message Envelope {
    string id = 1;

    message Payload {
        int32 value = 1;
    }

    union Detail {
        Payload payload = 1;
        string note = 2;
    }

    Payload payload = 2;
    Detail detail = 3;
    Status status = 4;
}

union Wrapper {
    Envelope envelope = 1;
    string raw = 2;
}
```

## Cross-Language Notes

### Type ID Behavior

- Explicit `[id=...]` values are used directly by generated module installation or registration helpers.
- When type IDs are omitted, generated code uses computed numeric IDs (see `auto_id.*` outputs).
- If `option enable_auto_type_id = false;` is set, generated module installation or registration helpers use name-based APIs instead of numeric IDs.

### Nested Type Shape

| Language              | Nested type form               |
| --------------------- | ------------------------------ |
| Java                  | `Person.PhoneNumber`           |
| Python                | `Person.PhoneNumber`           |
| Rust                  | `person::PhoneNumber`          |
| C++                   | `Person::PhoneNumber`          |
| Go                    | `Person_PhoneNumber` (default) |
| C#                    | `Person.PhoneNumber`           |
| JavaScript/TypeScript | `Person.PhoneNumber`           |
| Swift                 | `Person.PhoneNumber`           |
| Dart                  | `Person_PhoneNumber`           |
| Kotlin                | `PersonPhoneNumber`            |
| Scala                 | `Person.PhoneNumber`           |

### Byte Helper Naming

| Language              | Helpers                   |
| --------------------- | ------------------------- |
| Java                  | `toBytes` / `fromBytes`   |
| Kotlin                | `toBytes` / `fromBytes`   |
| Scala                 | `toBytes` / `fromBytes`   |
| Python                | `to_bytes` / `from_bytes` |
| Rust                  | `to_bytes` / `from_bytes` |
| C++                   | `to_bytes` / `from_bytes` |
| Go                    | `ToBytes` / `FromBytes`   |
| C#                    | `ToBytes` / `FromBytes`   |
| JavaScript/TypeScript | (via `fory.serialize()`)  |
| Swift                 | `toBytes` / `fromBytes`   |
| Dart                  | (via `fory.serialize()`)  |

## Runtime References

Choose the generated-code reference for the output runtime. Generated models remain ordinary
runtime-owned types and use that runtime's supported Fory serialization APIs.

| Runtime               | Generated-code reference               |
| --------------------- | -------------------------------------- |
| Java                  | [Java](java.md)                        |
| Python                | [Python](python.md)                    |
| C++                   | [C++](cpp.md)                          |
| Go                    | [Go](go.md)                            |
| Rust                  | [Rust](rust.md)                        |
| JavaScript/TypeScript | [JavaScript/TypeScript](javascript.md) |
| C#                    | [C#](csharp.md)                        |
| Swift                 | [Swift](swift.md)                      |
| Dart                  | [Dart](dart.md)                        |
| Scala                 | [Scala](scala.md)                      |
| Kotlin                | [Kotlin](kotlin.md)                    |
