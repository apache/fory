# Go

Load this file when changing `go/fory/` or Go xlang behavior.

## Rules

- Run Go commands from within `go/fory/`.
- Changes under `go/` must pass formatting and tests.
- The Go implementation focuses on reflection-based and codegen-based serialization.
- Root deserialization graph memory budgets are owned by `ReadContext`.
  `WithMaxGraphMemoryBytes` uses a fixed `128 MiB` default; positive explicit
  values override it, and explicit non-positive values intentionally disable
  graph-memory enforcement. Byte-slice and stream roots use the same
  configured/default budget behavior. `ReadContext` may expose only raw byte
  reservation; slice, map, array, struct, and object
  formulas belong in handwritten or generated serializer owners. Reserve Go
  slices as `len * elemBytes`, maps as `len * (keyBytes + valueBytes)`,
  map-backed sets, and LIST-encoded inline/value slices in the owner that
  allocates that storage. Root struct owners, pointer allocations, and generated
  allocation entries reserve shallow value storage exactly once; nested inline
  struct serializers do not charge their own self storage again. Fixed arrays
  are caller-owned unless a read path materializes a temporary owner. Skip
  dedicated string, binary, BufferObject, primitive scalar, primitive ARRAY
  slice, and primitive array owners with byte checks.
- Set `FORY_PANIC_ON_ERROR=1` when debugging a failing Go test so you get the full call stack.
- Do not set `FORY_PANIC_ON_ERROR=1` when running the full Go test suite, because some tests assert on error contents.

## Key Paths

- `fory.go`
- `resolver.go`
- `type.go`
- `slice.go`
- `map.go`
- `set.go`
- `struct.go`
- `string.go`
- `buffer.go`
- `codegen/`
- `meta/`

## Commands

```bash
# Format code
go fmt ./...

# Run tests
go test -v ./...

# Run tests with race detection
go test -race -v ./...

# Build
go build

# Generate code
go generate ./...
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_GO_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```
