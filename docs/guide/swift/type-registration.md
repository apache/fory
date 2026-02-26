---
title: Type Registration
sidebar_position: 3
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

User types (`struct`, `class`, enum/union, ext types) must be registered before serialization/deserialization.

If a type is missing, deserialization fails with:

- `Type not registered: ...`

## Register by Numeric ID

Use a stable ID shared by serializer and deserializer peers.

```swift
@ForyObject
struct User {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
fory.register(User.self, id: 1)
```

## Register by Name

### Fully-qualified name

```swift
try fory.register(User.self, name: "com.example.User")
```

`name` is split by `.`:

- namespace: `com.example`
- type name: `User`

### Explicit namespace + name

```swift
try fory.register(User.self, namespace: "com.example", name: "User")
```

## Consistency Rules

Keep registration mapping consistent across peers:

- ID mode: same type uses same numeric ID on all peers
- Name mode: same type uses same namespace and type name on all peers
- Do not mix ID and name mapping for the same logical type across services

## Dynamic Types and Registration

When serializing dynamic values (`Any`, `AnyObject`, `any Serializer`) that contain user-defined types, the concrete runtime types must still be registered.
