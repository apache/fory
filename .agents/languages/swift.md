# Swift

Load this file when changing `swift/` or Swift xlang behavior.

## Rules

- Run Swift commands from within `swift/`.
- Changes under `swift/` must pass lint and tests.
- Swift code must compile without compiler warnings. Treat warnings as blockers, including warnings in generated Swift code.
- Swift lint uses `swift/.swiftlint.yml`.
- Swift formatting uses `swift/.swift-format`; do not rely on SwiftLint for indentation or source
  formatting.
- Use `ENABLE_FORY_DEBUG_OUTPUT=1` when debugging Swift tests.
- Prefer the user-requested or existing Foundation public value type when it is the intended Swift surface; do not invent Fory-prefixed wrappers only to avoid import ambiguity.
- Preserve distinct temporal semantics. Timestamp values and day-only local dates should have protocol-accurate helper names and no stale aliases after a refactor.
- When temporal or public-type refactors touch generated Swift code, sweep message fields, union payloads, macros, xlang harnesses, and integration fixtures together.
- Compatible scalar, list-array, and binary/uint8-array adaptations are immediate-field-only. Recursive matched-field comparison for collection elements, array elements, map keys, and map values must require exact nullability, ref tracking, generic arity, and type shape except documented user-type family normalization.
- Root deserialization container memory budget state belongs to `ReadContext`. Swift public roots are `Data` and `ByteBuffer`, so auto uses known root bytes; do not add stream bytes-read accounting or serializer-local budget state.
- For Swift container budget formulas, distinguish inline/value storage from reference storage: use `MemoryLayout<T>.stride` for value arrays/lists/maps and the 4-byte reference fallback for `Serializer.isRefType` / `FieldCodec.isRefType` paths. Dedicated `String`, `Data`/binary, and primitive packed-array owners stay skipped, except compatible packed-array-to-list reads must charge the target list materialization before allocation.

## Commands

```bash
# Build package
swift build

# Run tests
swift test

# Run tests with debug output
ENABLE_FORY_DEBUG_OUTPUT=1 swift test

# Lint check
swiftlint lint --config .swiftlint.yml

# Format verification
swift-format lint --configuration .swift-format --recursive --strict Sources Tests Package.swift

# Auto-fix where supported
swiftlint --fix --config .swiftlint.yml

# Auto-format Swift source
swift-format format --configuration .swift-format --recursive --in-place Sources Tests Package.swift
```

## Java-Driven Xlang Test

```bash
cd swift
swift build -c release --disable-automatic-resolution --product ForyXlangTests
cd ../java
mvn -T16 install -DskipTests
cd fory-core
FORY_SWIFT_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```
