# Contributing to Apache Fory™ Python

This document provides instructions for building and testing the `pyfory` package.

## Building

```bash
cd python
# Uninstall numpy first so that when we install pyarrow, it will install the correct numpy version automatically.
# For Python versions less than 3.13, numpy 2 is not currently supported.
pip uninstall -y numpy
# Install development environment for Python
pip install -v -e ".[dev,format]"
```

If the last steps fails with an error like `libarrow_python.dylib: No such file or directory`,
you are probably suffering from bazel's aggressive caching; the sought library is longer at the
temporary directory it was the last time bazel ran. To remedy this run

> bazel clean --expunge

### Environment Requirements

- python 3.8+

## Testing

```bash
cd python
pytest -v -s .

# Run specific test
pytest -v -s pyfory/tests/test_serializer.py
```

## Formatting

```bash
cd python
pip install ruff
ruff format .
ruff check --fix .
```

## Debugging

```bash
cd python
python setup.py develop
```

- Use `cython --cplus -a  pyfory/serialization.pyx` to produce an annotated HTML file of the source code. Then you can
  analyze interaction between Python objects and Python's C API.
- Read more: <https://cython.readthedocs.io/en/latest/src/userguide/debugging.html>

```bash
FORY_DEBUG=true python setup.py build_ext --inplace
# For linux
cygdb build
```

### Debugging with lldb

```bash
lldb
(lldb) target create -- python
(lldb) settings set -- target.run-args "-c" "from pyfory.tests.test_serializer import test_enum; test_enum()"
(lldb) run
(lldb) bt
```
