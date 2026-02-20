---
title: Shared and Circular References
sidebar_position: 6
id: references
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

Swift reference tracking is controlled by `ForyConfig.trackRef`.

## Enable Reference Tracking

```swift
let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: false))
```

When enabled, reference-trackable types preserve identity and cycles.

## Shared Reference Example

```swift
import Fory

@ForyObject
final class Animal {
    var name: String = ""

    required init() {}

    init(name: String) {
        self.name = name
    }
}

@ForyObject
final class AnimalPair {
    var first: Animal? = nil
    var second: Animal? = nil

    required init() {}

    init(first: Animal? = nil, second: Animal? = nil) {
        self.first = first
        self.second = second
    }
}

let fory = Fory(config: .init(xlang: true, trackRef: true))
fory.register(Animal.self, id: 200)
fory.register(AnimalPair.self, id: 201)

let shared = Animal(name: "cat")
let input = AnimalPair(first: shared, second: shared)

let data = try fory.serialize(input)
let decoded: AnimalPair = try fory.deserialize(data)

assert(decoded.first === decoded.second)
```

## Circular Reference Example (Use `weak`)

`trackRef` preserves the reference graph, but it does not change ARC ownership.
Use `weak` on at least one edge in a cycle to avoid leaks.

```swift
import Fory

@ForyObject
final class Node {
    var value: Int32 = 0
    weak var next: Node? = nil

    required init() {}

    init(value: Int32, next: Node? = nil) {
        self.value = value
        self.next = next
    }
}

let fory = Fory(config: .init(xlang: true, trackRef: true))
fory.register(Node.self, id: 300)

let node = Node(value: 7)
node.next = node

let data = try fory.serialize(node)
let decoded: Node = try fory.deserialize(data)

assert(decoded.next === decoded)
```

## Notes

- Value types (`struct`, primitive values) do not carry identity semantics
- `trackRef` controls serialization graph identity, not ARC memory ownership
- Use `trackRef=false` for purely value-based payloads to reduce overhead
