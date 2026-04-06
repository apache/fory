# Go

Load this file when changing `go/fory/` or Go xlang behavior.

## Rules

- Run Go commands from within `go/fory/`.
- Changes under `go/` must pass formatting and tests.
- The Go implementation focuses on reflection-based and codegen-based serialization.
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
