---
title: Schema Evolution
sidebar_position: 8
id: schema_evolution
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

Fory supports schema evolution through compatible mode.

## Enable Compatible Mode

```swift
let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
```

## Example: Evolving a Struct

```swift
import Fory

@ForyObject
struct PersonV1 {
    var name: String = ""
    var age: Int32 = 0
    var address: String = ""
}

@ForyObject
struct PersonV2 {
    var name: String = ""
    var age: Int32 = 0
    var phone: String? = nil // added field
}

let writer = Fory(config: .init(xlang: true, compatible: true))
writer.register(PersonV1.self, id: 1)

let reader = Fory(config: .init(xlang: true, compatible: true))
reader.register(PersonV2.self, id: 1)

let v1 = PersonV1(name: "alice", age: 30, address: "main st")
let bytes = try writer.serialize(v1)
let v2: PersonV2 = try reader.deserialize(bytes)

assert(v2.name == "alice")
assert(v2.age == 30)
assert(v2.phone == nil)
```

## What Is Safe in Compatible Mode

- Add new fields
- Remove old fields
- Reorder fields

## What Is Not Safe

- Arbitrary type changes for an existing field (for example `Int32` to `String`)
- Inconsistent registration mapping across peers

## Schema-consistent Mode Behavior

With `compatible=false`, Fory validates schema hash and fails fast on mismatch.
