# Integration tests for fory:

- [jdk_compatibility_tests](jdk_compatibility_tests): test fory compatibility across multiple jdk versions.
- [graalvm_tests](graalvm_tests): test graalvm native image support.
- [jpms_tests](jpms_tests): test JPMS module names.
- [idl_tests](idl_tests): test Fory IDL cross-language generation and round trips.
- [grpc_tests](grpc_tests): test Fory gRPC companion interoperability across languages.
- [cpython_benchmark](cpython_benchmark): fory CPython microbenchmark.

> Note that this integration_tests is not designed as a maven multi-module project on purpose, so we can introduce features of higher jdk version without breaking compilation for lower jdk, and add integration tests for other languages.
