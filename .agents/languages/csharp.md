# C#

Load this file when changing `csharp/` or C# xlang behavior.

## Rules

- Run all `dotnet` commands from within `csharp/`.
- Changes under `csharp/` must pass formatting and tests.
- Fory C# requires .NET SDK `8.0+` and C# `12+`.
- Use `dotnet format` to keep C# code style consistent.

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
