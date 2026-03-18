# Language Command Matrix

Use this as the default verification matrix after performance changes. Run commands from the language directory unless noted.

## Swift

- Build: `swift build`
- Tests: `swift test`
- Lint: `swiftlint lint --config .swiftlint.yml`
- Benchmark: `cd benchmarks/swift && swift build -c release && ./.build/release/swift-benchmark --duration <N>`
- Profile (macOS sample): run benchmark with long duration, then `sample <pid> 10 1 -mayDie -file /tmp/<name>.sample.txt`

## C++

- Build: `bazel build //cpp/...`
- Tests: `bazel test $(bazel query //cpp/...)`
- Perf tests: `bazel test $(bazel query //cpp/fory/serialization/...)`
- Profile: use repository-approved sampling tooling from `CONTRIBUTING.md` and `docs/cpp_debug.md`

## Java

- Build: `mvn -T16 package`
- Tests: `mvn -T16 test`
- Format/style checks as needed: `spotless:check`, `checkstyle:check`
- Profile: JFR or async-profiler on the exact benchmark/test workload

## Python/Cython

- Install: `pip install -v -e .`
- Tests (python mode): `ENABLE_FORY_CYTHON_SERIALIZATION=0 pytest -v -s .`
- Tests (cython mode): `ENABLE_FORY_CYTHON_SERIALIZATION=1 pytest -v -s .`
- Format/lint: `ruff format . && ruff check --fix .`
- Profile: `py-spy`, `cProfile`, and Cython annotations as needed

## Rust

- Build: `cargo build`
- Check: `cargo check`
- Lint: `cargo clippy --all-targets --all-features -- -D warnings`
- Tests: `cargo test --features tests`
- Profile: flamegraph/perf tooling on benchmark or targeted test

## Go

- Build: `go build`
- Tests: `go test -v ./...`
- Format: `go fmt ./...`
- Profile: `pprof` (`go test -bench` + cpu/mem profiles)

## C#

- Build: `dotnet build Fory.sln -c Release --no-restore`
- Tests: `dotnet test Fory.sln -c Release`
- Format check: `dotnet format Fory.sln --verify-no-changes`
- Profile: `dotnet-trace` / `dotnet-counters` on benchmark/test runs

## JavaScript/TypeScript

- Install: `npm install`
- Tests: `node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit`
- Lint: `git ls-files -- '*.ts' | xargs -P 5 node ./node_modules/.bin/eslint`

## Dart

- Generate: `dart run build_runner build`
- Tests: `dart test`
- Analyze/fix: `dart analyze && dart fix --dry-run`

## Kotlin

- Build: `mvn clean package`
- Tests: `mvn test`

## Scala

- Build: `sbt compile`
- Tests: `sbt test`
- Format: `sbt scalafmt`

## Cross-Language Xlang Verification

When changing xlang/runtime semantics, run relevant Java-driven xlang tests from `java/fory-core` with debug output enabled, for impacted languages:

- `CPPXlangTest`
- `CSharpXlangTest`
- `RustXlangTest`
- `GoXlangTest`
- `PythonXlangTest`
- `SwiftXlangTest`
