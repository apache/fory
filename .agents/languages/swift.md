# Swift

Load this file when changing `swift/` or Swift xlang behavior.

## Rules

- Run Swift commands from within `swift/`.
- Changes under `swift/` must pass lint and tests.
- Swift lint uses `swift/.swiftlint.yml`.
- Use `ENABLE_FORY_DEBUG_OUTPUT=1` when debugging Swift tests.

## Commands

```bash
# Build package
swift build

# Run tests
swift test

# Run tests with debug output
ENABLE_FORY_DEBUG_OUTPUT=1 swift test

# Lint check
swiftlint lint --config .swiftlint.yml

# Auto-fix where supported
swiftlint --fix --config .swiftlint.yml
```

## Java-Driven Xlang Test

```bash
cd swift
swift build -c release --disable-automatic-resolution --product ForyXlangTests
cd ../java
mvn -T16 install -DskipTests
cd fory-core
FORY_SWIFT_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn -T16 test -Dtest=org.apache.fory.xlang.SwiftXlangTest
```
