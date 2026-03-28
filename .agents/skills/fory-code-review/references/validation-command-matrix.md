# Validation Command Matrix

Use the smallest command set that proves the changed behavior. If protocol or xlang behavior changed, call out the cross-language tests that should run even if the author did not run them yet.

## Repo-Wide Anchors

- Format/lint sweep: `bash ci/format.sh --all`
- Refresh remote main before main-branch comparison: `git fetch apache main`

## Java

- Build/test from `java/`
- Typical checks:
  - `mvn -T16 spotless:check`
  - `mvn -T16 checkstyle:check`
  - `mvn -T16 test`
  - targeted: `mvn -T16 test -Dtest=<Class>#<method>`

## C#

- Build/test from `csharp/`
- Typical checks:
  - `dotnet format Fory.sln --verify-no-changes`
  - `dotnet build Fory.sln -c Release --no-restore`
  - `dotnet test Fory.sln -c Release`

## C++

- Build/test from repo root or `cpp/`
- Architecture note: only add `--config=x86_64` on `x86_64` or `amd64`, not on arm64.
- Typical checks:
  - `bazel build //cpp/...`
  - `bazel test $(bazel query //cpp/...)`

## Python

- Work from `python/`
- Typical checks:
  - `ruff format .`
  - `ruff check .`
  - `ENABLE_FORY_CYTHON_SERIALIZATION=0 pytest -v -s .`
  - `ENABLE_FORY_CYTHON_SERIALIZATION=1 pytest -v -s .`

## Rust

- Work from `rust/`
- Typical checks:
  - `cargo fmt --check`
  - `cargo clippy --all-targets --all-features -- -D warnings`
  - `cargo test --features tests`

## Swift

- Work from `swift/`
- Typical checks:
  - `swiftlint lint --config .swiftlint.yml`
  - `swift build`
  - `swift test`

## Go

- Work from `go/fory/`
- Typical checks:
  - `go fmt ./...`
  - `go test -v ./...`

## Xlang Matrix Triggers

When the patch touches xlang behavior, type mapping, protocol bytes, compatible mode, `TypeMeta`, or cross-language container semantics, require the relevant Java-driven xlang tests:

- `org.apache.fory.xlang.CPPXlangTest`
- `org.apache.fory.xlang.CSharpXlangTest`
- `org.apache.fory.xlang.RustXlangTest`
- `org.apache.fory.xlang.GoXlangTest`
- `org.apache.fory.xlang.PythonXlangTest`

If the touched language is Swift and xlang behavior changed, include `org.apache.fory.xlang.SwiftXlangTest` too.
