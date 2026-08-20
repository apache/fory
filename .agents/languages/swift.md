# Swift

Load this file when changing `swift/` or Swift xlang behavior.

## Rules

- Run Swift commands from within `swift/`.
- Changes under `swift/` must pass lint and tests.
- SwiftLint is mandatory but not sufficient for readability; manually clean touched Swift source
  formatting before committing because this repo does not run a Swift formatter in CI.
- Swift code must compile without compiler warnings. Treat warnings as blockers, including warnings in generated Swift code.
- Swift lint uses `swift/.swiftlint.yml`.
- Swift formatting uses `swift/.swift-format`; do not rely on SwiftLint for indentation or source
  formatting.
- Use `ENABLE_FORY_DEBUG_OUTPUT=1` when debugging Swift tests.
- Prefer the user-requested or existing Foundation public value type when it is the intended Swift surface; do not invent Fory-prefixed wrappers only to avoid import ambiguity.
- Preserve distinct temporal semantics. Timestamp values and day-only local dates should have protocol-accurate helper names and no stale aliases after a refactor.
- When temporal or public-type refactors touch generated Swift code, sweep message fields, union payloads, macros, xlang harnesses, and integration fixtures together.
- Keep `Serializer` as static value-level behavior for one exact `Target`. `FieldCodec` layers
  field-only schema and compatibility behavior over that value behavior; `Serializer` must not
  depend on `FieldCodec`.
- Custom serialization is not external-only. Static selection follows provider ownership at every
  root, field, and carrier node. A target with `Target == Self`, including an intentional
  retroactive conformance on an external type, composes implicitly everywhere. A separate
  serializer whose `Target` is another type composes explicitly everywhere; registration must not
  infer it. Retroactive conformances are process-global, and `@retroactive` does not make duplicate
  `(Target, Protocol)` conformances safe.
- Swift carrier serializers are exactly `OptionalSerializer`, `ArraySerializer`, `SetSerializer`,
  and `DictionarySerializer`. Root and field composition use different static type trees but share
  one carrier body and allocation owner.
- External structural serializers extend `@ForyStruct`, `@ForyEnum`, and `@ForyUnion` through
  `target:`. Because those declarations are separate from their targets, static nodes select them
  with `with`, and registration uses the existing serializer registration API.
- Swift macros cannot inspect an external target's stored layout. External class
  graph-memory formulas therefore count only external declaration fields;
  ignored declaration fields are budget-only and must not enter target access,
  construction, metadata, or wire code. Omitted large value storage must be
  declared explicitly and ignored.
- Direct `Any` and `AnyObject` root overloads remain disfavored forwarding facades over
  `DynamicSerializer<Any>` and `DynamicSerializer<AnyObject>`, including their Data-buffer forms.
  Arbitrary protocol roots explicitly select `DynamicSerializer<T>`. Do not add an unconstrained
  generic dynamic root overload, runtime serializer value, provider instance, or wrapper
  collection. Static carriers perform no target lookup; a homogeneous dynamic chunk resolves once,
  while a truly heterogeneous chunk retains its required per-value lookup.
- A non-null MAP chunk size is 1 through 255; reject zero so decoding always advances. For a
  one-null entry, encode and decode the non-null side in complete-field order: reference envelope
  when present, undeclared `TypeInfo`, then body. Keep the writer, reader, compatible-field
  skipper, and static/dynamic MAP branches aligned.
- Preserve Swift's xlang-only union rule of zero or one associated value per known case. Use a
  struct payload when a case has multiple logical fields.
- Keep declared-child collection-header state in `FieldCodec`, not `Serializer`. Mark cold error
  and validation entrances `@inline(never)`; successful work is not cold, although a measured
  composite external structural success body may remain deliberately out of line.
- `OptionalSerializer` has `isWrapper == true` because it has no independent registration
  identity. A custom serializer targeting the same Swift shape remains false because it owns an
  independent opaque EXT body.
- A dynamic existential uses conservative reference-envelope capability, but graph slot accounting
  uses its declared `MemoryLayout<T>.stride`; concrete reference ownership comes from resolved
  `TypeInfo`.
- Compatible scalar, list-array, and binary/uint8-array adaptations are immediate-field-only. Recursive matched-field comparison for collection elements, array elements, map keys, and map values must require exact nullability, ref tracking, generic arity, and type shape except documented user-type family normalization.
- Root deserialization graph memory budget state belongs to `ReadContext`. Swift public roots are
  `Data` and `ByteBuffer`, and both use the same fixed default graph budget; do not add stream
  bytes-read accounting or serializer-local budget state. Root APIs reset the budget only; they must
  not pre-reserve root type or root self bytes. `ReadContext` may expose only raw byte reservation;
  array, set, map, struct, and object formulas belong in serializer and field-codec owners.
  Treat the option as an approximate array/dictionary/set/struct/class/object gate, not an exact
  heap cap. Leaf values skipped by graph budgeting remain gated by unread input bytes.
- For Swift graph budget formulas, distinguish inline/value storage from reference storage: use
  `MemoryLayout<T>.stride` for value arrays/lists/sets/maps and the 4-byte reference fallback for
  `Serializer.isRefType` / `FieldCodec.isRefType` paths. Class/reference paths reserve their own
  shallow self cost plus field storage when materialized; value serializers do not reserve their own
  self storage, including standalone, generated, and root read paths. Field, array, set, map, box,
  and dynamic materialization owners reserve inline or boxed storage exactly once. Independently materialized
  collection/map/array owners reserve nonzero shallow self cost plus backing/reference/inline
  storage. Dedicated `String`, `Data`/binary,
  primitive scalar, and primitive packed-array owners stay skipped, except compatible
  packed-array-to-list reads must reserve the target list materialization before allocation.

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
