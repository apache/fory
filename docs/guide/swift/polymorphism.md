---
title: Polymorphism and Dynamic Types
sidebar_position: 7
id: polymorphism
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

Fory Swift supports dynamic serialization for `Any`, `AnyObject`, and `any Serializer`.

## Top-level Dynamic APIs

```swift
let fory = Fory()

let dynamic: Any = Int32(7)
let data = try fory.serialize(dynamic)
let decoded: Any = try fory.deserialize(data)
```

Equivalent overloads exist for:

- `AnyObject`
- `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

## Dynamic Fields in `@ForyObject` Types

```swift
@ForyObject
struct DynamicHolder {
    var value: Any = ForyAnyNullValue()
    var list: [Any] = []
    var byName: [String: Any] = [:]
    var byId: [Int32: Any] = [:]
    var byDynamicKey: [AnyHashable: Any] = [:]
}
```

## Concrete Type Registration Still Applies

If dynamic values contain user-defined runtime types, register those concrete types.

```swift
@ForyObject
struct Address {
    var street: String = ""
    var zip: Int32 = 0
}

let fory = Fory()
fory.register(Address.self, id: 100)
```

## Null Semantics

- `Any` null representation: `ForyAnyNullValue`
- `AnyObject` null representation: `NSNull`
- Optional dynamic values map to the corresponding null representation on decode

## Current Limitations

- `AnyHashable` keys must wrap runtime values that are both `Hashable` and supported by Fory dynamic serialization
