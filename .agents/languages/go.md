# Go

Load this file when changing `go/fory/` or Go xlang behavior.

## Rules

- Run Go commands from within `go/fory/`.
- Changes under `go/` must pass formatting and tests.
- The Go implementation focuses on fast serializers.
- Root deserialization graph memory budgets are owned by `ReadContext`.
  `WithMaxGraphMemoryBytes` uses a fixed `128 MiB` default; positive explicit
  values override it, and explicit non-positive values are invalid at config creation.
  Byte-slice and stream roots use the same configured/default budget behavior.
  Root APIs reset the budget only; they must not pre-reserve root type or root self bytes. Do not
  mirror the configured max into a second active-limit field; root setup should update only the
  mutable remaining budget. `ReadContext` may expose only raw byte reservation; slice, map, array,
  struct, and object formulas belong in handwritten serializer owners. Reserve Go slices, maps,
  map-backed sets, and LIST-encoded inline/value slices with a real shallow owner byte lower bound
  plus direct element/key/value storage in the owner that allocates that storage. Struct pointer allocations reserve
  shallow value storage in the pointer materialization path; root struct reads do not reserve root
  object memory in `Fory` or `ReadContext`, and nested inline struct serializers do not charge
  their own self storage again. Fixed arrays are caller-owned unless a read path materializes a
  temporary owner. Skip dedicated string, binary, BufferObject, primitive scalar, primitive ARRAY
  slice, and primitive array owners with byte checks.
  Treat the option as an approximate slice/map/array/struct/object gate, not an exact heap cap. Leaf
  values skipped by graph budgeting remain gated by unread input bytes.
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
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_GO_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```
