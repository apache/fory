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

Fory supports schema evolution through compatible mode, which is enabled by default in Swift.

Compatible readers also tolerate selected scalar field type changes when the value is lossless. A
matched field can read between `Bool`, `String`, numeric scalars, and `Decimal` when the converted
value has the same logical value. For example, `"true"` and `"false"` can be read as booleans,
`"123"` can be read as a numeric field that can hold `123`, numbers and decimals can be read as
canonical strings, and numeric widening or narrowing succeeds only when no precision or range is
lost. Numeric strings use finite ASCII decimal syntax. Invalid strings and lossy conversions fail
during deserialization.

Scalar conversion also composes with optional fields. A present optional value is converted by the
same rules, while a missing optional value keeps Swift's normal compatible-mode default for the
local field. Reference-tracked scalar type changes are incompatible.

## Default Compatible Mode

```swift
let fory = Fory()
```

## Example: Evolving a Struct

```swift
import Fory

@ForyStruct
struct PersonV1 {
    var name: String = ""
    var age: Int32 = 0
    var address: String = ""
}

@ForyStruct
struct PersonV2 {
    var name: String = ""
    var age: Int32 = 0
    var phone: String? = nil // added field
}

let writer = Fory()
writer.register(PersonV1.self, id: 1)

let reader = Fory()
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
- Change a matched scalar field between `Bool`, `String`, numeric scalars, or `Decimal` when every
  value you write is lossless for the reader

## What Is Not Safe

- Arbitrary type changes for an existing field, including scalar values that are out of range,
  rounded, non-finite, or not accepted by the compatible numeric string grammar
- Inconsistent registration mapping across peers

## Same-Schema Optimization

Set `compatible: false` only when every reader and writer always uses the same schema and you want
faster serialization and smaller size. For xlang payloads, set `compatible: false` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.
