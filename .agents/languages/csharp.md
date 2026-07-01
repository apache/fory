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
- Compatible scalar, list-array, and binary/uint8-array adaptations are immediate-field-only. Recursive matched-field comparison for collection elements, array elements, map keys, and map values must require exact nullability, ref tracking, generic arity, and type shape except documented user-type family normalization.
- Root deserialization graph memory budget state belongs to `ReadContext`. C# public roots are
  memory-backed today, but the graph budget uses the same fixed default for every root shape.
  `ReadContext` may expose only raw byte reservation; concrete serializers and generated
  serializers must compute list, array, map, struct, and object byte formulas before calling it.
- For C# graph budget formulas, distinguish inline value storage from reference storage: use cheap
  value-type size for `List<T>`/`T[]` value paths and the 4-byte reference fallback for reference
  paths. Class/reference serializers reserve their own shallow self cost plus field storage when
  materialized; struct/value serializers do not unconditionally charge self storage because root,
  field, list, array, map, set, or box owners reserve the inline storage they own. Maps reserve key
  plus value storage, linked/hash/tree conversions must not add guessed node or entry overhead, and
  independently materialized collection/map/array owners reserve nonzero shallow self cost.
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
