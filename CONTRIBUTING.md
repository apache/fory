# How to contribute to Apache Fory™

## Finding good first issues

See [Good First Issues](https://github.com/apache/fory/contribute).

## How to create an issue

Create an issue with [this form](https://github.com/apache/fory/issues/new/choose).

## How to title your PR

Generally we follow the [Conventional Commits](https://www.conventionalcommits.org/) for pull request titles,
since we will squash and merge the PR and use the PR title as the first line of commit message.

For example, here are good PR titles:

- feat(java): support xxx feature
- fix(c++): blablabla
- chore(python): remove useless yyy file

If the submitted PR affects the performance of Apache Fory™, we strongly recommend using the perf type,
and need to provide benchmark data in the PR description. For how to run the benchmark,
please check [Apache Fory™ Java Benchmark](https://github.com/apache/fory/blob/main/benchmarks/java/README.md).

For more details, please check [pr-lint.yml](./.github/workflows/pr-lint.yml).

## AI-assisted contributions

For full requirements, see [AI Contribution Policy](./AI_CONTRIBUTION_POLICY.md).

Key points:

- AI tools are allowed as assistants, but contributors remain fully responsible for all submitted changes.
- AI-assisted code must be reviewed carefully line by line before submission, and contributors must be able to explain and defend it during review.
- For substantial AI assistance, provide privacy-safe disclosure in the PR using the standard template in `.github/pull_request_template.md`. Minor/narrow AI assistance does not require full disclosure.
- Include adequate human verification evidence (for example exact build/lint/test commands and pass/fail outcomes), and add/update tests and specs where required.
- For protocol/type-mapping/wire-format or performance-sensitive changes, provide the required compatibility/performance validation evidence.
- Ensure licensing and provenance compliance with [ASF Generative Tooling Guidance](https://www.apache.org/legal/generative-tooling.html) and do not submit content with uncertain provenance.

## Testing

For environmental requirements, please check [DEVELOPMENT.md](./docs/guide/DEVELOPMENT.md).

### Python

```bash
cd python
pytest -v -s .
```

### Java

```bash
cd java
mvn -T10 clean test
```

### C++

```bash
bazel test $(bazel query //...)
```

### GoLang

```bash
cd go/fory
go test -v ./...
go test -v fory_xlang_test.go
```

### Rust

```bash
cd rust
cargo test
# run test with specific test file and method
cargo test -p tests  --test $test_file $test_method
# run specific test under subdirectory
cargo test --test mod $dir$::$test_file::$test_method
# debug specific test under subdirectory and get backtrace
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 cargo test --test mod $dir$::$test_file::$test_method
```

### JavaScript

```bash
cd javascript
npm run test
```

## Code Style

Run all checks: `bash ci/format.sh --all`.

### License headers

```bash
docker run --rm -v $(pwd):/github/workspace ghcr.io/korandoru/hawkeye-native:v3 format
```

### Java

```bash
cd java
# code format
mvn spotless:apply
# code format check
mvn spotless:check
mvn checkstyle:check
```

### Python

```bash
cd python
# install dependencies for formatting
pip install ruff
# format python code
ruff format
```

### C++

```bash
pip install clang-format==18.1.8
git ls-files -- '*.cc' '*.h' | xargs -P 5 clang-format -i
```

### GoLang

```bash
cd go/fory
gofmt -s -w .
```

### Rust

```bash
cd rust
cargo fmt --all
# lint
cargo clippy --workspace --all-features --all-targets -- -D warnings
```

### JavaScript

```bash
cd javascript
npm run lint
```

## Debug

### Java

Apache Fory™ supports dump jit generated code into local file for better debug by configuring environment variables:

- `FORY_CODE_DIR`：The directory for fory to dump generated code. Set to empty by default to skip dump code.
- `ENABLE_FORY_GENERATED_CLASS_UNIQUE_ID`: Append an unique id for dynamically generated files by default to avoid serializer collision for different classes with same name. Set this to `false` to keep serializer name same for multiple execution or `AOT` codegen.

By using those environment variables, we can generate code to source directory and debug the generated code in next run.

### Python

```bash
cd python
python setup.py develop
```

- Use `cython --cplus -a  pyfory/serialization.pyx` to produce an annotated HTML file of the source code. Then you can analyze interaction between Python objects and Python's C API.
- Read more: https://cython.readthedocs.io/en/latest/src/userguide/debugging.html

```bash
FORY_DEBUG=true python setup.py build_ext --inplace
# For linux
cygdb build
```

### C++

See the [Debugging C++](docs/cpp_debug.md) doc.

### Debug Crash

Enable core dump on Macos Monterey 12.1:

```bash
/usr/libexec/PlistBuddy -c "Add :com.apple.security.get-task-allow bool true" tmp.entitlements
codesign -s - -f --entitlements tmp.entitlements /Users/chaokunyang/anaconda3/envs/py3.8/bin/python
ulimit -c unlimited
```

then run the code:

```bash
python fory_serializer.py
ls -al /cores
```

## Profiling

### C++

```bash
# Dtrace
sudo dtrace -x ustackframes=100 -n 'profile-99 /pid == 73485 && arg1/ { @[ustack()] = count(); } tick-60s { exit(0); }' -o out.stack
sudo stackcollapse.pl out.stack > out.folded
sudo flamegraph.pl out.folded > out.svg
```

## Extracts compile_commands.json

```bash
bazel run :refresh_compile_commands
```

## How to use Jetbrains IDEA IDE for Java Development

Apache Fory™ Java development is based on Java 11+, and different modules are built with different Java versions.

For example, the `fory-core` module is built with Java 8, and the `fory-format` module is built with Java 11.

To use Jetbrains IDEA IDE for Java Development, you need to configure the project SDK and module SDK to using JDK 11+.

And due to the usage of `sun.misc.Unsafe` API, which is not visible in Java 11+, you need to configure java compiler with `--releaese` option disabled.

<div align="center">
  <img width="65%" alt="" src="docs/images/idea_jdk11.png"><br>
</div>

## Website

Apache Fory™'s website consists of static pages hosted at https://github.com/apache/fory-site.

All updates about docs under [guide](docs/guide) and [benchmarks](docs/benchmarks) will be synced to the site repo automatically.

If you want write a blog, or update other contents about the website, please submit PR to the site repo.

## Development

For more information, please refer to [Development Guide](https://fory.apache.org/docs/docs/guide/development).
