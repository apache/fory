---
title: Swift
sidebar_position: 9
id: swift
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

## Output Layout

Swift output is one `.swift` file per schema, for example:

- `<swift_out>/addressbook/addressbook.swift`

## Type Generation

The generator creates Swift models with split model macros and stable field/case IDs.
A typed union must include `@ForyUnknownCase case unknown(UnknownCase)` and at
least one non-`unknown` case; `unknown(UnknownCase)` is only the
Fory-provided forward-compatibility carrier. The marker only selects the carrier
and does not add an entry to the schema case table.

When package/namespace is non-empty, namespace shaping is controlled by `swift_namespace_style`:

- `enum` (default): nested enum namespace wrappers.
- `flatten`: package-derived prefix on top-level type names (for example `Demo_Foo_User`).

When package/namespace is empty, no enum wrapper or flatten prefix is applied.

For non-empty package with default `enum` style:

```swift
public enum Addressbook {
    @ForyUnion
    public enum Animal {
        @ForyUnknownCase
        case unknown(UnknownCase)
        @ForyCase(id: 0)
        case dog(Addressbook.Dog)
        @ForyCase(id: 1)
        case cat(Addressbook.Cat)
    }

    @ForyStruct
    public struct Person: Equatable {
        @ForyField(id: 1)
        public var name: String = ""
        @ForyField(id: 8)
        public var pet: Addressbook.Animal =
            Addressbook.Animal.dog(Addressbook.Dog())
    }
}
```

For non-empty package with `flatten` style:

```swift
@ForyStruct
public struct Addressbook_Person: Equatable { ... }
```

The CLI flag `--swift_namespace_style` overrides schema option `swift_namespace_style` when both are set.

Unions are generated as tagged Swift enums with associated payload values.
Recursive unions are emitted as `indirect` enums. The first known union case
must have a finite recursively constructible default; the compiler rejects a
first-case default cycle instead of emitting a non-terminating initializer.
Messages with `ref`/`weak_ref` fields are generated as `final class` models to preserve reference semantics.
A directly stored message cycle must mark at least one cycle edge `ref`; otherwise
the compiler rejects the schema because Swift value types cannot represent it.
Fixed or tagged integer encodings inside list/map fields are emitted as Swift
field type hints, for example `@ListField(element: .encoding(.fixed))` or
`@MapField(value: .encoding(.tagged))`.
For non-null fixed-width integer list elements, Swift classifies the field as
the corresponding Fory primitive packed-array type; fixed-width integer sets
remain Fory sets.

## Module Installation

Each schema includes a `ForyModule` owner with transitive import installation:

```swift
public enum ForyModule {
    public static func install(_ fory: Fory) throws {
        try ComplexPb.ForyModule.install(fory)
        try fory.register(Addressbook.Person.self, id: 100)
        try fory.register(Addressbook.Animal.self, id: 106)
    }
}
```

With non-empty package and `flatten` style, the helper is prefixed too (for example `Addressbook_ForyModule`).

For schemas without explicit `[id=...]`, installation uses computed numeric IDs.
If `option enable_auto_type_id = false;` is set, generated code uses name-based registration APIs.

Generated models declare `Equatable` where every field supports it, but they do
not declare `Sendable`. Compile them in Swift 5 language mode; Swift 6 strict
concurrency rejects passing them across an isolation boundary.

## gRPC Service Companions

With `--grpc`, Swift emits one `<Service>Grpc.swift` per service containing `<Base>Provider`, `<Base>AsyncProvider`, `<Base>AsyncClient`, and `<Base>Metadata`, where `<Base>` carries the package prefix. See [Swift gRPC](../../grpc/swift.md) for dependencies, streaming shapes, and usage.
