# Rust

Load this file when changing `rust/` or Rust xlang behavior.

## Rules

- Run all cargo commands from within `rust/`.
- Changes under `rust/` must pass `clippy` and tests.
- Use `RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1` when debugging failing Rust tests.
- Add `-- --nocapture` when you need test output during debugging.
- Do not set `FORY_PANIC_ON_ERROR=1` when running the full Rust test suite, because some tests assert on error contents.

## Key Paths

- `fory/src/lib.rs`
- `fory-core/src/fory.rs`
- `fory-core/src/resolver/type_resolver.rs`
- `fory-core/src/resolver/metastring_resolver.rs`
- `fory-core/src/resolver/context.rs`
- `fory-core/src/buffer.rs`
- `fory-core/src/meta`
- `fory-core/src/serializer`
- `fory-core/src/row`
- `fory-derive/src/object`
- `fory-derive/src/fory_row`

## Commands

```bash
# Check code
cargo check

# Build
cargo build

# Lint
cargo clippy --all-targets --all-features -- -D warnings

# Run tests
cargo test --features tests

# Run a specific test
cargo test -p tests --test <test_file> <test_method>

# Run a specific test under a subdirectory
cargo test --test mod <dir>::<test_file>::<test_method>

# Debug a specific test
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 ENABLE_FORY_DEBUG_OUTPUT=1 cargo test --test mod <dir>::<test_file>::<test_method> -- --nocapture

# Inspect generated code by the derive macro
cargo expand --test mod <mod>::<file> > expanded.rs

# Format
cargo fmt

# Check formatting
cargo fmt --check

# Build docs
cargo doc --lib --no-deps --all-features

# Run benchmarks
cd <project_dir>/benchmarks/rust
cargo bench
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 FORY_RUST_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.RustXlangTest
```
