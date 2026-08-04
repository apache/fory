# C\#

Load this file when changing `csharp/` or C# xlang behavior.

## Rules

- Run all `dotnet` commands from within `csharp/`.
- Changes under `csharp/` must pass formatting and tests.
- C# code must build without compiler or analyzer warnings. Treat warnings as blockers in project, test, and generated code.
- Fory C# requires .NET SDK `8.0+` and C# `12+`.
- Use `dotnet format` to keep C# code style consistent.
- Generated C# gRPC service companions are compiler-owned files that depend on application-provided gRPC packages, not `csharp/src/Fory`. Keep gRPC package references out of the Fory runtime package.
- C# generated schema modules are source-file owners. Service companions must use that module's `ThreadSafeFory` and must not introduce namespace-owned aliases or duplicate serializer registration paths.
- C# external-type serialization is target-keyed. A local
  `ForyStruct(Target = typeof(...))` abstract serializer declaration owns only
  compile-time schema metadata; an empty static
  `ForyEnum(Target = typeof(...))` declaration selects the canonical target
  enum serializer. Generated code, `Serializer<T>`, `TypeInfo`, registration,
  roots, fields, dynamic values, references, graph memory, and carrier children
  use the actual target type. Never instantiate, register, reflect over, or
  reference-publish the declaration.
- Keep one generator model/emitter and one resolver registration path for
  ordinary and external C# targets. Registration uses only the
  `Register<Target>` and `Register<Target, TSerializer>` APIs. Do not add
  external registration, field/root serializer selectors, provider objects,
  callbacks, runtime schema trees, carrier-provider types, per-element
  external dispatch, or declaration-to-target conversion objects.
- Every generated struct uses
  `RegisterGeneratedStruct<Target, TSerializer>(Evolving)` so generator-owned
  schema evolution reaches target `TypeInfo` directly. Generated enum and
  union factories use `RegisterGenerated<Target, TSerializer>()`. Do not
  recover structural metadata through target reflection, hide structural
  registration behind a boolean overload of the non-structural method, or add
  a second metadata owner. Reject duplicate generated owners by target at
  compile time and deterministically on the cold cross-assembly
  factory-registration path. Last-writer-wins generated factories are
  forbidden; explicit custom serializer replacement uses normal resolver
  semantics.
- One concrete ordinary class serializer owns one flattened wire-member set.
  Every first-party class in the hierarchy must carry a direct `[ForyStruct]`
  annotation. Each inheritable generated base provider publishes only its exact
  declared wire-descriptor count, descriptors/accessors, and cumulative
  `HierarchyShallowBytes`; a child consumes the immediate accessible provider
  and adds only its directly declared physical fields. A sealed serializer
  keeps its final cumulative expression private. Do not scan referenced private
  metadata, reconstruct parent storage, call parent serializer bodies, or derive
  storage from wire members.
- External structural serializers use direct target construction and member
  access under the mutable parameterless-construction contract. External class
  targets may use exact `TargetDeclaringType` and `TargetMemberName` mappings
  for renamed and inaccessible fields. External struct targets support visible
  member mappings only. Constructor-only, factory-only, readonly, init-only,
  converted, or custom-wire targets use a custom serializer. Derive allocation,
  reference publication, default value, and graph-memory behavior from the
  target class/struct kind, never the declaration kind.
- An external class declaration owns the exact third-party wire and physical
  fields it lists. An exact wire mapping contributes storage; an ignored
  mapping contributes only storage. A visible property mapping contributes no
  storage, so declare its backing field separately when it owns storage. Add
  unmapped visible public instance fields once, but never infer or scan private
  target layout. A `BaseOnly` declaration may own the complete third-party
  hierarchy prefix and publishes no standalone factory or registration.
- Keep external private ABI access exact and fallback-free. On .NET 8, reject
  a private wire accessor whose declaring type or signature is generic.
  Visible generic members and exact storage-only field mappings remain
  supported for class targets. Reject inaccessible pointer storage because
  referenced metadata cannot distinguish a pointer field from fixed-buffer
  storage without scanning private layout.
- External children compose through the concrete carrier serializers already
  owned by `TypeResolver`: nullable structs, one-dimensional arrays, List,
  LinkedList, Queue, Stack, HashSet, SortedSet, ImmutableHashSet, Dictionary,
  SortedDictionary, SortedList, ConcurrentDictionary, and
  NullableKeyDictionary. Do not add or claim unsupported collection-interface,
  tuple, fixed-array, multidimensional-array, memory, or reference-wrapper
  carriers.
- External and equivalent ordinary generated hot bodies must have the same
  work and allocation shape apart from target/member metadata tokens. External
  target selection is compile-time only. Keep duplicate-target and validation
  errors on no-inline cold entrances, but do not mark successful serializer
  dispatch cold.
- Compatible scalar, list-array, and binary/uint8-array adaptations are immediate-field-only. Recursive matched-field comparison for collection elements, array elements, map keys, and map values must require exact nullability, ref tracking, generic arity, and type shape except documented user-type family normalization.
- Root deserialization graph memory budget state belongs to `ReadContext`. C# public roots are
  memory-backed today, but the graph budget uses the same fixed default for every root shape.
  Root APIs reset the budget only; they must not pre-reserve root type or root self bytes.
  Do not mirror the configured max into a second active-limit field; use config plus mutable
  remaining budget.
  `ReadContext` may expose only raw byte reservation; concrete serializers and generated
  serializers must compute list, array, map, struct, and object byte formulas before calling it.
  Treat the option as an approximate collection/map/array/struct/object gate, not an exact heap
  cap. Leaf values skipped by graph budgeting remain gated by unread input bytes.
- `ReadContext` must not expose ref-publication pause/resume APIs or any non-budget owner
  controls. Concrete serializers and generated serializers own ref publication timing directly,
  and must not publish temporary owners.
- For C# graph budget formulas, distinguish inline value storage from reference storage: use cheap
  value-type size for `List<T>`/`T[]` value paths and the 4-byte reference fallback for reference
  paths. Class/reference serializers reserve their own shallow self cost plus field storage when
  materialized; struct/value serializers do not reserve their own self storage, including generic
  root reads and generated struct read paths. Fields, lists, arrays, maps, sets, and boxed/dynamic
  materialization paths reserve the inline or boxed storage they own. Maps reserve key plus value storage, linked/hash/tree
  conversions must not add guessed node or entry overhead, and independently materialized
  collection/map/array owners reserve nonzero shallow self cost.
  Dedicated string, binary, primitive scalar, and primitive dense-array serializers stay skipped and
  rely on byte availability checks.
- When extending C# tests from Java references, prioritize xlang spec behavior and the public C# contract before adding complex Java-specific parity cases.

## Commands

```bash
# Restore
dotnet restore Fory.sln

# Build
dotnet build Fory.sln -c Release --no-restore

# Run tests
dotnet test Fory.sln -c Release

# Run a specific test
dotnet test tests/Fory.Tests/Fory.Tests.csproj -c Release --filter "FullyQualifiedName~ForyRuntimeTests.DynamicObjectReadDepthExceededThrows"

# Format
dotnet format Fory.sln

# Format check
dotnet format Fory.sln --verify-no-changes
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_CSHARP_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.CSharpXlangTest
```
