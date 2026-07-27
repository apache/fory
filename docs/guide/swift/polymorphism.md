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

Fory Swift supports dynamic serialization for `Any`, `AnyObject`, arbitrary
application protocol existentials, and supported heterogeneous collections.
`Any` and `AnyObject` roots use direct convenience APIs. Application protocols
and recursively composed carriers select `DynamicSerializer` explicitly.

## Dynamic Roots

```swift
let fory = Fory()

let dynamic: Any = Int32(7)
let data = try fory.serialize(dynamic)
let decoded: Any = try fory.deserialize(data)
```

The equivalent explicit form is
`with: DynamicSerializer<Any>.self`. It is useful when composing serializers,
but is unnecessary for an `Any` root. `AnyObject` has the same direct root
APIs.

A concrete external value passed as `Any` may use its registered serializer.
For typed roots and fields, select a separate serializer explicitly with
`with:`.

Heterogeneous containers compose carrier serializers:

```swift
typealias AnyArraySerializer =
    ArraySerializer<DynamicSerializer<Any>>

typealias StringAnyMapSerializer = DictionarySerializer<
    String,
    DynamicSerializer<Any>
>
```

Use `DynamicSerializer<AnyHashable>` for an erased dynamic dictionary key.

Use the exact heterogeneous shape for dynamic lists and maps: `[Any]`,
`[String: Any]`, `[Int32: Any]`, or `[AnyHashable: Any]`. For example, write
`["a", "b"] as [Any]` before storing that heterogeneous list in `Any`.
Keep homogeneous lists and maps in their ordinary concrete types.

## Application Protocols

Register each concrete target that may appear. No Fory-specific marker
protocol is required:

```swift
protocol Animal {
    var name: String { get }
}

@ForyStruct
struct Dog: Animal {
    var name: String = ""
}

@ForyStruct(target: ThirdParty.Cat.self)
struct CatSerializer {
    var name: String
}

let fory = Fory()
try fory.register(Dog.self, id: 100)
try fory.register(CatSerializer.self, id: 101)

let input: any Animal = Dog(name: "Rex")
let data = try fory.serialize(
    input,
    with: DynamicSerializer<any Animal>.self
)
let output = try fory.deserialize(
    data,
    with: DynamicSerializer<any Animal>.self
)
```

Every concrete target must conform to the requested application protocol. A
target that is unregistered or does not conform fails deserialization.

## Protocol Fields

Application protocol fields are dynamic:

```swift
@ForyStruct
struct Zoo {
    var featured: any Animal
    var animals: [any Animal]
}
```

Register `Zoo` and every concrete target that may appear.

Use an optional when the field needs a nil default:

```swift
@ForyStruct
struct OptionalZoo {
    var featured: (any Animal)? = nil
}
```

## Protocol Root Carriers

Use `DynamicSerializer<T>` as the child of a root carrier:

```swift
typealias AnimalArraySerializer =
    ArraySerializer<DynamicSerializer<any Animal>>

let data = try fory.serialize(
    animals,
    with: AnimalArraySerializer.self
)

let decoded = try fory.deserialize(
    data,
    with: AnimalArraySerializer.self
)
```

Optional and map roots compose the same way:

```swift
typealias FeaturedSerializer =
    OptionalSerializer<DynamicSerializer<any Animal>>

typealias AnimalMapSerializer = DictionarySerializer<
    String,
    DynamicSerializer<any Animal>
>
```

Swift's normal `Hashable` rules still apply to set elements and dictionary
keys. Use `AnyHashable` for erased dynamic keys.

## Explicitly Polymorphic Class Nodes

Select `DynamicSerializer<Base>` when a class-typed field must preserve its
concrete subclass:

```swift
@ForyField(with: DynamicSerializer<AnimalBase>.self)
var animal: AnimalBase
```

Register every concrete subclass that may appear.

## Dynamic `Any` Fields

```swift
@ForyStruct
struct DynamicHolder {
    var value: Any = ForyAnyNullValue()
    var list: [Any] = []
    var byName: [String: Any] = [:]
    var byId: [Int32: Any] = [:]
    var byDynamicKey: [AnyHashable: Any] = [:]
}
```

## Concrete Type Registration Still Applies

If dynamic values contain user-defined types, register those concrete types.

```swift
@ForyStruct
struct Address {
    var street: String = ""
    var zip: Int32 = 0
}

let fory = Fory()
try fory.register(Address.self, id: 100)
```

## Null Semantics

- `Any` null representation: `ForyAnyNullValue`
- `AnyObject` null representation: `NSNull`
- `AnyHashable` dynamic null-key representation:
  `AnyHashable(ForyAnyNullValue())`
- Optional dynamic values map to the corresponding null representation on decode

## `AnyHashable` Keys

`AnyHashable` keys must wrap values that are both `Hashable` and supported by
Fory dynamic serialization.
