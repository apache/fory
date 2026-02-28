---
title: Custom Serializers
sidebar_position: 4
id: custom_serializers
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

For types that cannot or should not use `@ForyObject`, implement `Serializer` manually.

## When to Use Custom Serializers

- External types with strict wire compatibility requirements
- Specialized compact encodings
- Legacy payload migration paths
- Highly tuned hot-path serialization

## Implementing `Serializer`

```swift
import Foundation
import Fory

struct UUIDBox: Serializer, Equatable {
    var value: UUID = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!

    static func foryDefault() -> UUIDBox {
        UUIDBox()
    }

    static var staticTypeId: ForyTypeId {
        .ext
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        try value.uuidString.foryWriteData(context, hasGenerics: false)
    }

    static func foryReadData(_ context: ReadContext) throws -> UUIDBox {
        let raw = try String.foryReadData(context)
        guard let uuid = UUID(uuidString: raw) else {
            throw ForyError.invalidData("invalid UUID string: \(raw)")
        }
        return UUIDBox(value: uuid)
    }
}
```

## Register and Use

```swift
let fory = Fory()
fory.register(UUIDBox.self, id: 300)

let input = UUIDBox(value: UUID())
let data = try fory.serialize(input)
let output: UUIDBox = try fory.deserialize(data)

assert(input == output)
```

## Choosing `staticTypeId`

For manually implemented custom types, use `staticTypeId` that matches the wire kind you are implementing.

Typical choices:

- `.structType`: regular structured object
- `.enumType` / `.typedUnion`: enum-like values
- `.ext`: extension/custom kind
