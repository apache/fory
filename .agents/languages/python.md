# Python

Load this file when changing `python/`, Cython serialization, or Python xlang behavior.

## Rules

- Run Python commands from within `python/`.
- Changes under `python/` must pass formatting and tests.
- Fory Python requires CPython `3.8+`.
- Use `ENABLE_FORY_CYTHON_SERIALIZATION=0` first when debugging protocol behavior.
- Python mode is the pure-Python xlang implementation and is mainly for debugging and testing.
- Cython mode is the default high-performance implementation.
- Cython mode owns the hot runtime path. Do not duplicate core runtime types between Python and Cython, tunnel Python facade methods into hidden Cython internals, or keep dead shims unless the user explicitly needs a compatibility module path.
- Use explicit Cython fields and methods for fixed hot-path shapes. Avoid `__getattr__`, generic `object` fields, public bridge internals, or `Fory` backreferences where ownership can stay explicit.
- Keep Python and Cython context/ref-tracking branch conditions and stack mutations semantically aligned unless a documented intentional difference exists.
- Public value constructors should accept normal Python values. Raw-bit, raw-buffer, and memoryview entry points should be explicit low-level APIs, and packed carriers should expose the buffer protocol from the actual storage owner when appropriate.
- When debugging runtime or benchmark behavior, install the local package into the exact interpreter under test instead of relying on mixed `PYTHONPATH` state.
- For wheel or extension pipeline changes, derive extension-module paths from current build targets, packaging config, or wheel payload discovery rather than historical module names.
- Keep new Python test names compact and behavior-focused; avoid sentence-length names that restate setup details already obvious from the test body.
- `ENABLE_FORY_DEBUG_OUTPUT=1` enables detailed struct serialization and deserialization logs.

## Key Paths

- `pyfory/serialization.pyx`
- `pyfory/_fory.py`
- `pyfory/registry.py`
- `pyfory/serializer.py`
- `pyfory/includes`
- `pyfory/resolver.py`
- `pyfory/format`
- `pyfory/buffer.pyx`

## Commands

```bash
# Clean build outputs
rm -rf build dist .pytest_cache
bazel clean --expunge

# Format and lint
ruff format .
ruff check --fix .

# Install
pip install -v -e .

# Build the native extension on x86_64
bazel build //:cp_fory_so --@rules_python//python/config_settings:python_version=X.Y --config=x86_64

# Build the native extension on arm64 / aarch64
bazel build //:cp_fory_so --@rules_python//python/config_settings:python_version=X.Y --copt=-fsigned-char

# Run tests without Cython
ENABLE_FORY_CYTHON_SERIALIZATION=0 pytest -v -s .

# Run tests with Cython
ENABLE_FORY_CYTHON_SERIALIZATION=1 pytest -v -s .
```

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_PYTHON_JAVA_CI=1 ENABLE_FORY_CYTHON_SERIALIZATION=0 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.PythonXlangTest
FORY_PYTHON_JAVA_CI=1 ENABLE_FORY_CYTHON_SERIALIZATION=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.PythonXlangTest
```

## Debugging

- Generate annotated Cython output with `cython --cplus -a pyfory/serialization.pyx`.
- Build a debug extension with `FORY_DEBUG=true python setup.py build_ext --inplace`.
