# Java

Load this file when changing anything under `java/` or when Java drives a cross-language validation flow.

## Rules

- Run all Maven commands from within `java/`.
- Changes under `java/` must pass code style checks and tests.
- Fory Java requires JDK `17+`.
- Run Java `spotless` with JDK `21+`. If the current runtime is lower than 21, export `JAVA_HOME` to a JDK 21 installation before running `mvn spotless:check` or `mvn spotless:apply`.
- `fory-core` targets Java 8 bytecode and `fory-format` targets Java 11 bytecode. Do not use newer APIs in those modules.
- Do not use wildcard imports.
- Import config and annotation types instead of fully qualifying enum constants or annotation
  values; use qualified names only when a real name conflict requires it.
- If you run temporary tests with `java -cp`, run `mvn -T16 install -DskipTests` first so local Fory jars are current.
- `WriteContext`, `ReadContext`, and `CopyContext` must stay explicit. Do not reintroduce `ThreadLocal` or ambient runtime-context patterns.
- Generated serializers must not retain runtime context fields. `Fory` should stay a root-operation facade rather than accumulating serializer or convenience state.
- When the serializer class and constructor shape are known at the call site, prefer direct constructor lambdas or direct instantiation over reflective `Serializers.newSerializer(...)`.
- For GraalVM, use `fory codegen` to generate serializers when building native images. Do not add reflection configuration except for JDK `proxy`.
- In Java native mode (`xlang=false`), only `Types.BOOL` through `Types.STRING` share type IDs with xlang mode. Other native-mode type IDs differ.
- Choose one serializer ownership location per logical Java type family. Add native/xlang serializer variants only when the wire format or constructor contract truly differs.
- Do not add normal-JVM process-global caches keyed by user classes, generated classes, serializer classes, classloaders, or class-bound method handles. Prefer per-runtime state, immutable shared metadata, or build-time-only template data.
- Concrete serializers may opt into sharing only after auditing retained fields. Treat serializers retaining `TypeResolver`, `RefResolver`, mutable scratch buffers, runtime state, or classloader-sensitive state as non-shareable unless that state is externalized.
- Resolver and serializer hot paths should keep the fast-path/null-slow-path shape obvious. Hoist repeated buffer or cache-state access into locals for multi-step operations and keep rebuild/restoration logic cold.
- Keep GraalVM feature code as a thin metadata/registration layer. Build time should publish metadata needed for runtime reconstruction, not retain concrete generated or user serializer instances in the image heap.
- If changes touch GraalVM bootstrap, serializer retention, native-image metadata, or `ObjectStreamSerializer` GraalVM behavior, verify the native-image build and run the produced binary; a plain Java compile is insufficient.
- Put latest-JDK or virtual-thread tests in the latest-JDK test modules with the matching compiler/profile floor, and centralize runtime-version probing in existing compatibility utilities.

## Key Modules

- `fory-core`: core object-graph serialization runtime
- `fory-format`: row-format encoding and decoding
- `fory-extensions`: protobuf serializers and other extensions
- `fory-simd`: SIMD-accelerated serializers and utilities
- `fory-test-core`: shared Java test utilities
- `testsuite`: issue-driven and complex regression coverage
- `benchmark`: JMH benchmark suite

## Commands

```bash
# Clean the build
mvn -T16 clean

# Build
mvn -T16 package

# Install
mvn -T16 install -DskipTests

# Format check
mvn -T16 spotless:check

# Apply formatting
mvn -T16 spotless:apply

# Spotless with JDK 21 when the current runtime is older
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -T16 spotless:check

# Style check
mvn -T16 checkstyle:check

# Run tests
mvn -T16 test

# Run a specific test
mvn -T16 test -Dtest=org.apache.fory.TestClass#testMethod
```

## Debugging And IDE Notes

- Set `FORY_CODE_DIR` to dump generated code.
- Set `ENABLE_FORY_GENERATED_CLASS_UNIQUE_ID=false` when you need stable generated class names.
- In IntelliJ IDEA, use a JDK 11+ project SDK and disable `--release` if it blocks `sun.misc.Unsafe` access.
