# C++

Load this file when changing `cpp/`, Cython build plumbing, or C++ xlang behavior.

## Rules

- All commands must be executed within the `cpp/` directory.
- Use C++17 in `cpp/`; do not introduce newer language features.
- Bazel uses bzlmod via `MODULE.bazel`; prefer Bazel 8+.
- For Bazel C++ tests, add `--config=x86_64` only on `x86_64` or `amd64`. Do not use it on `arm64` or `aarch64`.
- Run `clang-format` on updated C++ files.
- When invoking a method that returns `Result`, use `FORY_TRY` unless you are in control-flow logic that cannot use it cleanly.
- Wrap error checks with `FORY_PREDICT_FALSE` for branch prediction.
- Continue on trivial errors; return early only for critical errors such as buffer overflow.
- Put private methods last in class definitions, immediately before private fields.
- Do not redesign alias-based or low-level public type shapes to add convenience methods unless the user explicitly asks for that API change.
- For cross-language feature ports, match protocol behavior but use idiomatic C++ ownership and layering instead of mirroring Java structure literally.

## Key Paths

- `cpp/fory/row`
- `cpp/fory/meta`
- `cpp/fory/encoder`
- `cpp/fory/util`

## Commands

```bash
# Build the C++ library
bazel build //cpp/...

# Build the Cython extension (replace X.Y with the Python version)
bazel build //:cp_fory_so --@rules_python//python/config_settings:python_version=X.Y

# Run all C++ tests
bazel test $(bazel query //cpp/...)

# Run serialization tests
bazel test $(bazel query //cpp/fory/serialization/...)

# Run a specific test
bazel test //cpp/fory/util:buffer_test

# Format a file
clang-format -i <file>
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_CPP_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.CPPXlangTest
```

## Debugging And Profiling

- See `docs/cpp_debug.md` for C++ debugging guidance.
- Generate `compile_commands.json` with `bazel run :refresh_compile_commands`.
- DTrace-based stack sampling is documented in `CONTRIBUTING.md`.
