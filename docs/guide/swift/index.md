---
title: Swift Serialization Guide
sidebar_position: 0
id: serialization_index
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

Apache Fory Swift provides high-performance object graph serialization with strong type safety, macro-based code generation, schema evolution, and cross-language compatibility.

## Why Fory Swift?

- Fast binary serialization for Swift value and reference types
- `@ForyObject` macro for zero-boilerplate model serialization
- Cross-language protocol compatibility (`xlang`) with Java, Rust, Go, Python, and more
- Compatible mode for schema evolution across versions
- Built-in support for dynamic values (`Any`, `AnyObject`, `any Serializer`, `AnyHashable`)
- Reference tracking for shared/circular graphs, including weak references on classes

## Guide Contents

- [Configuration](configuration.md)
- [Basic Serialization](basic-serialization.md)
- [Type Registration](type-registration.md)
- [Custom Serializers](custom-serializers.md)
- [Field Configuration](field-configuration.md)
- [Shared and Circular References](references.md)
- [Polymorphism and Dynamic Types](polymorphism.md)
- [Schema Evolution](schema-evolution.md)
- [Cross-Language Serialization](cross-language.md)
- [Row Format Status](row-format.md)
- [Troubleshooting](troubleshooting.md)

## Quick Example

```swift
import Fory

@ForyObject
struct User: Equatable {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
fory.register(User.self, id: 1)

let input = User(name: "alice", age: 30)
let data = try fory.serialize(input)
let output: User = try fory.deserialize(data)

assert(input == output)
```
