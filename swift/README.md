# Apache Fory™ Swift

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

**Apache Fory™** is a blazing-fast multi-language serialization framework.

The Swift implementation provides high-performance object graph serialization with macro-based code generation, schema evolution support, and xlang interoperability.

## 🚀 Why Apache Fory™ Swift?

- **🔥 Fast Binary Serialization**: Efficient encoding for Swift value and reference types
- **🧩 Macro-Driven Models**: Use `@ForyObject` to generate serializers for structs, classes, and enums
- **🌍 Cross-Language**: Exchange payloads with Java, Rust, Go, Python, and other Fory runtimes via xlang
- **🔄 Shared/Circular References**: Preserve object identity with `trackRef` for reference graphs
- **🧬 Dynamic Values**: Serialize `Any`, `AnyObject`, `any Serializer`, `AnyHashable`, and dynamic containers
- **📦 Schema Evolution**: Enable compatible mode for add/remove/reorder field evolution
- **⚠️ Clear Scope**: Swift currently focuses on object graph serialization (row-format APIs are not exposed yet)

## 📦 Package Layout

| Target           | Description                                                 |
| ---------------- | ----------------------------------------------------------- |
| `Fory`           | Core Swift runtime and macro declarations                   |
| `ForyMacro`      | Macro implementation used by `@ForyObject` and `@ForyField` |
| `ForyXlangTests` | Executable used by Java-driven xlang integration tests      |
| `ForyTests`      | Swift unit tests                                            |

## 🏃 Quick Start

### 1. Add dependency

`Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/apache/fory.git", branch: "main")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "Fory", package: "fory")
        ]
    )
]
```

### 2. Basic serialization

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

### 3. Buffer-oriented APIs

```swift
var out = Data()
try fory.serialize(input, to: &out)

let buffer = ByteBuffer(data: out)
let output2: User = try fory.deserialize(from: buffer)
assert(output2 == input)
```

## 📚 Core Features

### 1. Object Graph Serialization

Use `@ForyObject`, register user types, then serialize/deserialize.

```swift
import Fory

@ForyObject
struct Address: Equatable {
    var street: String = ""
    var zip: Int32 = 0
}

@ForyObject
struct Person: Equatable {
    var id: Int64 = 0
    var name: String = ""
    var nickname: String? = nil
    var tags: Set<String> = []
    var scores: [Int32] = []
    var addresses: [Address] = []
    var metadata: [Int8: Int32?] = [:]
}

let fory = Fory()
fory.register(Address.self, id: 100)
fory.register(Person.self, id: 101)

let person = Person(
    id: 42,
    name: "Alice",
    nickname: nil,
    tags: ["swift", "xlang"],
    scores: [10, 20, 30],
    addresses: [Address(street: "Main", zip: 94107)],
    metadata: [1: 100, 2: nil]
)

let bytes = try fory.serialize(person)
let decoded: Person = try fory.deserialize(bytes)
assert(decoded == person)
```

### 2. Shared and Circular References

Enable reference tracking for class/reference graphs:

```swift
let fory = Fory(xlang: true, trackRef: true, compatible: false)
```

Shared reference identity is preserved:

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

let fory = Fory(xlang: true, trackRef: true)
fory.register(Animal.self, id: 200)
fory.register(AnimalPair.self, id: 201)

let shared = Animal(name: "cat")
let input = AnimalPair(first: shared, second: shared)

let data = try fory.serialize(input)
let decoded: AnimalPair = try fory.deserialize(data)
assert(decoded.first === decoded.second)
```

For cyclic graphs, use `weak` on at least one edge to avoid ARC leaks:

```swift
@ForyObject
final class Node {
    var value: Int32 = 0
    weak var next: Node? = nil

    required init() {}
}
```

### 3. Dynamic and Polymorphic Values

Top-level and field-level dynamic serialization is supported for:

- `Any`
- `AnyObject`
- `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

If dynamic payloads contain user-defined concrete types, register those types before serialization/deserialization.

```swift
import Fory

@ForyObject
struct DynamicAddress {
    var street: String = ""
    var zip: Int32 = 0
}

let fory = Fory()
fory.register(DynamicAddress.self, id: 410)

let payload: [String: Any] = [
    "id": Int32(7),
    "name": "alice",
    "addr": DynamicAddress(street: "main", zip: 94107),
]

let data = try fory.serialize(payload)
let decoded: [String: Any] = try fory.deserialize(data)
assert(decoded["id"] as? Int32 == 7)
```

Null decoding semantics:

- `Any` null is represented as `ForyAnyNullValue`
- `AnyObject` null is represented as `NSNull`

### 4. Schema Evolution (Compatible Mode)

Use compatible mode to evolve schemas between peers.

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
    var phone: String? = nil
}

let writer = Fory(xlang: true, compatible: true)
writer.register(PersonV1.self, id: 1)

let reader = Fory(xlang: true, compatible: true)
reader.register(PersonV2.self, id: 1)

let v1 = PersonV1(name: "alice", age: 30, address: "main st")
let bytes = try writer.serialize(v1)
let v2: PersonV2 = try reader.deserialize(bytes)

assert(v2.name == "alice")
assert(v2.age == 30)
assert(v2.phone == nil)
```

Compatible mode supports:

- Add fields
- Remove fields
- Reorder fields

Not supported:

- Arbitrary field type changes (for example `Int32` to `String`)

### 5. Field Encoding Overrides

Use `@ForyField(encoding:)` to control integer wire encoding.

```swift
import Fory

@ForyObject
struct Metrics {
    @ForyField(encoding: .fixed)
    var u32Fixed: UInt32 = 0

    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64 = 0
}
```

Supported combinations:

| Swift type                       | Supported encodings            |
| -------------------------------- | ------------------------------ |
| `Int32`, `UInt32`                | `.varint`, `.fixed`            |
| `Int64`, `UInt64`, `Int`, `UInt` | `.varint`, `.fixed`, `.tagged` |

### 6. Enum and Tagged Union Support

`@ForyObject` supports C-style enums and associated-value enums.

```swift
import Fory

@ForyObject
enum Color: Equatable {
    case red
    case green
    case blue
}

@ForyObject
enum StringOrLong: Equatable {
    case text(String)
    case number(Int64)
}

let fory = Fory(xlang: true, compatible: false)
fory.register(Color.self, id: 300)
fory.register(StringOrLong.self, id: 301)

let a = try fory.serialize(Color.green)
let b = try fory.serialize(StringOrLong.text("hello"))

let color: Color = try fory.deserialize(a)
let value: StringOrLong = try fory.deserialize(b)

assert(color == .green)
assert(value == .text("hello"))
```

### 7. Custom Serializers

For types that should not use `@ForyObject`, implement `Serializer` manually and register the type.
See `../docs/guide/swift/custom-serializers.md` for a complete example.

## 🌍 Cross-Language Serialization

Recommended xlang preset:

```swift
let fory = Fory(xlang: true, trackRef: false, compatible: true)
```

Type registration can be ID-based or name-based:

```swift
fory.register(MyType.self, id: 100)
try fory.register(MyType.self, namespace: "com.example", name: "MyType")
```

Cross-language rules:

- Keep registration mappings consistent across peers
- Use compatible mode for independently evolving schemas
- Register all user-defined concrete types used inside dynamic payloads

## ⚠️ Row Format Status

Swift runtime currently exposes object graph serialization APIs (`Fory.serialize` / `Fory.deserialize`).
Row-format APIs are not exposed yet in Swift.

## ⚡ Performance Notes

- Prefer `trackRef=false` for value-only payloads to avoid reference-table overhead
- Reuse the same `Fory` instance and register types once per process/service lifecycle
- Use schema-consistent mode (`compatible=false`) when strict schema parity is guaranteed

## 🛠️ Development

Run Swift tests:

```bash
cd swift
ENABLE_FORY_DEBUG_OUTPUT=1 swift test
```

Run Java-driven Swift xlang tests:

```bash
cd java/fory-core
ENABLE_FORY_DEBUG_OUTPUT=1 FORY_SWIFT_JAVA_CI=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```

## 📖 Documentation

- [Swift Guide](../docs/guide/swift/index.md)
- [Configuration](../docs/guide/swift/configuration.md)
- [Type Registration](../docs/guide/swift/type-registration.md)
- [Schema Evolution](../docs/guide/swift/schema-evolution.md)
- [Cross-Language Guide](../docs/guide/swift/cross-language.md)
- [Xlang Specification](../docs/specification/xlang_serialization_spec.md)
- [Xlang Type Mapping](../docs/specification/xlang_type_mapping.md)

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](https://github.com/apache/fory/blob/main/LICENSE).

## 🤝 Contributing

Contributions are welcome. See [CONTRIBUTING.md](https://github.com/apache/fory/blob/main/CONTRIBUTING.md).
