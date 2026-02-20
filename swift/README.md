# Apache Fory™ Swift

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

Apache Fory™ Swift is the Swift implementation of Apache Fory's high-performance serialization framework.

It provides:

- Macro-driven serialization for structs, classes, and enums (`@ForyObject`)
- Cross-language interoperability via xlang protocol
- Compatible mode for schema evolution
- Reference tracking for shared/circular object graphs
- Dynamic serialization for `Any`, `AnyObject`, and `any Serializer`

## Package Layout

- `Fory`: core Swift runtime and macro declarations
- `ForyMacro`: macro implementation target used by `@ForyObject` and `@ForyField`
- `ForyXlangTests`: executable used by Java xlang integration tests
- `ForyTests`: Swift unit tests

## Quick Start

### Add dependency (local development)

`Package.swift`:

```swift
dependencies: [
    .package(path: "../fory/swift")
],
targets: [
    .target(
        name: "MyApp",
        dependencies: ["Fory"]
    )
]
```

### Serialize and deserialize

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

### Buffer-oriented APIs

```swift
var out = Data()
try fory.serialize(input, to: &out)

let buffer = ByteBuffer(data: out)
let output2: User = try fory.deserialize(from: buffer)
assert(output2 == input)
```

## Configuration

```swift
let fory = Fory(config: .init(
    xlang: true,
    trackRef: false,
    compatible: true
))
```

- `xlang`: enable cross-language wire compatibility
- `trackRef`: preserve shared/circular reference identity
- `compatible`: enable schema evolution mode

## Type Registration

Register user types before use.

```swift
fory.register(User.self, id: 1)
try fory.register(User.self, namespace: "com.example", name: "User")
try fory.register(User.self, name: "com.example.User")
```

## Field Encoding Overrides

Use `@ForyField` for integer encoding control.

```swift
@ForyObject
struct Metrics {
    @ForyField(encoding: .fixed)
    var u32Fixed: UInt32 = 0

    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64 = 0
}
```

Supported type/encoding combinations:

- `Int32`, `UInt32`: `.varint`, `.fixed`
- `Int64`, `UInt64`, `Int`, `UInt`: `.varint`, `.fixed`, `.tagged`

## Dynamic and Polymorphic Values

Top-level and field-level support exists for:

- `Any`
- `AnyObject`
- `any Serializer`
- `AnyHashable`
- `[Any]`
- `[String: Any]`
- `[Int32: Any]`
- `[AnyHashable: Any]`

Register concrete user types used inside dynamic payloads.

## Development

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

## Documentation

- Swift guide: `../docs/guide/swift`
- Xlang specification: `../docs/specification/xlang_serialization_spec.md`
- Type mapping: `../docs/specification/xlang_type_mapping.md`
