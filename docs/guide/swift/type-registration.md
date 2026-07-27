---
title: Type Registration
sidebar_position: 5
id: type_registration
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

This page covers registration APIs for user-defined types.

## Why Registration Is Required

Register user-defined structs, classes, enums, unions, and external targets
before serialization or deserialization.

If a type is missing, deserialization fails with:

- `Type not registered: ...`

## Register by Numeric ID

Use a stable ID shared by serializer and deserializer peers.

```swift
@ForyStruct
struct User {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
try fory.register(User.self, id: 1)
```

For an external structural serializer, register the separate serializer
declaration:

```swift
@ForyStruct(target: ThirdParty.User.self)
struct UserSerializer {
    var name: String
    var age: UInt32
}

try fory.register(UserSerializer.self, id: 1)
```

If an application intentionally gives an external type one retroactive
`Serializer` conformance with `Target == Self`, register the target itself:

```swift
try fory.register(UUID.self, id: 2)
```

After registering a separate serializer, select it explicitly at each root,
field, or carrier child where it is required.

## Register by Name

### Fully-qualified name

```swift
try fory.register(User.self, name: "com.example.User")
```

`name` is split by the last `.`:

- namespace: `com.example`
- type name: `User`

Simple names such as `User` use an empty namespace. Empty names and names ending in `.` are invalid.

## Consistency Rules

Keep registration mapping consistent across peers:

- ID mode: same type uses same numeric ID on all peers
- Name mode: same type uses same namespace and type name on all peers
- Do not mix ID and name mapping for the same logical type across services
- Register only one serializer for each target type on a `Fory` instance

Registration closes after the first root serialization or deserialization.
Complete all registrations before the first root operation.

## Dynamic Types and Registration

When serializing `Any`, `AnyObject`, or application protocol values, register
each concrete target through its ordinary, external structural, or manual
serializer. `Any` and `AnyObject` use direct root APIs; application protocols
select `DynamicSerializer<T>` explicitly.
