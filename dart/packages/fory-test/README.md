## Testing Approach

The test suite is inspired by Apache Fory™ Java's testing approach and includes:

- **Datatype Tests**: Validates custom data types implemented for Dart
- **Code Generation Tests**: Ensures correctness of the generated static code
- **Buffer Tests**: Validates correct memory handling for primitive types
- **Cross-Language Tests**: Tests functionality against other Apache Fory™ implementations
- **Performance Tests**: Simple benchmarks for serialization/deserialization performance

### Running Tests

Tests use the standard [dart test](https://pub.dev/packages/test) framework.

To run tests:

```bash
# First, generate necessary code
cd fory-test
dart run build_runner build

# Run all tests
dart test

# For more options (skipping tests, platform-specific tests, etc.)
# See: https://github.com/dart-lang/test/blob/master/pkgs/test/README.md
```
