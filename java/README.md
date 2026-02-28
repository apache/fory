# Apache Fory™ Java

[![Maven Version](https://img.shields.io/maven-central/v/org.apache.fory/fory-core?style=for-the-badge)](https://search.maven.org/#search|gav|1|g:"org.apache.fory"%20AND%20a:"fory-core")
[![Java Version](https://img.shields.io/badge/Java-8%20to%2025-blue?style=for-the-badge)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](https://opensource.org/licenses/Apache-2.0)

Apache Fory™ Java provides blazingly-fast serialization for the Java ecosystem, delivering up to **170x performance improvement** over traditional frameworks through JIT compilation and zero-copy techniques.

## Features

### 🚀 High Performance

- **JIT Code Generation**: Highly-extensible JIT framework generates serializer code at runtime using async multi-threaded compilation, delivering 20-170x speedup through:
  - Inlining variables to reduce memory access
  - Inlining method calls to eliminate virtual dispatch overhead
  - Minimizing conditional branching
  - Eliminating hash lookups
- **Zero-Copy**: Direct memory access without intermediate buffer copies; row format supports random access and partial serialization
- **Variable-Length Encoding**: Optimized compression for integers, longs
- **Meta Sharing**: Cached class metadata reduces redundant type information
- **SIMD Acceleration**: Java Vector API support for array operations (Java 16+)

### 🔧 Drop-in Replacement

- **100% JDK Serialization Compatible**: Supports `writeObject`/`readObject`/`writeReplace`/`readResolve`/`readObjectNoData`/`Externalizable`
- **Java 8-24 Support**: Works across all modern Java versions including Java 17+ records
- **GraalVM Native Image**: AOT compilation support without reflection configuration

### 🔄 Advanced Features

- **Reference Tracking**: Automatic handling of shared and circular references
- **Schema Evolution**: Forward/backward compatibility for class schema changes
- **Polymorphism**: Full support for inheritance hierarchies and interfaces
- **Deep Copy**: Efficient deep cloning of complex object graphs with reference preservation
- **Security**: Class registration and configurable deserialization policies

## Documentation

| Topic                       | Description                | Source Doc Link                                                                | Website Doc Link                                                                                   |
| --------------------------- | -------------------------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| **Java Serialization**      | Comprehensive usage guide  | [java_serialization_guide.md](../docs/guide/java_serialization_guide.md)       | [Java Serialization Guide](https://fory.apache.org/docs/docs/guide/java_serialization)             |
| **GraalVM Native Image**    | Native image support       | [graalvm_guide.md](../docs/guide/graalvm_guide.md)                             | [GraalVM Guide](https://fory.apache.org/docs/docs/guide/graalvm_serialization)                     |
| **Java Serialization Spec** | Protocol specification     | [java_serialization_spec.md](../docs/specification/java_serialization_spec.md) | [Java Serialization Spec](https://fory.apache.org/docs/specification/fory_java_serialization_spec) |
| **Java Benchmarks**         | Performance data and plots | [java/README.md](../docs/benchmarks/java/README.md)                            | [Java Benchmarks](https://fory.apache.org/docs/benchmarks/java)                                    |

## Modules

| Module              | Description                           | Maven Artifact                    |
| ------------------- | ------------------------------------- | --------------------------------- |
| **fory-core**       | Core serialization engine             | `org.apache.fory:fory-core`       |
| **fory-format**     | Row format and Apache Arrow support   | `org.apache.fory:fory-format`     |
| **fory-simd**       | SIMD-accelerated array compression    | `org.apache.fory:fory-simd`       |
| **fory-extensions** | Protobuf support and meta compression | `org.apache.fory:fory-extensions` |
| **fory-test-core**  | Testing utilities and data generators | `org.apache.fory:fory-test-core`  |

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>0.15.0</version>
</dependency>

<!-- Optional: Row format support -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-format</artifactId>
  <version>0.15.0</version>
</dependency>

<!-- Optional: Serializers for Protobuf data -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-extensions</artifactId>
  <version>0.15.0</version>
</dependency>

<!-- Optional: SIMD acceleration (Java 16+) -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-simd</artifactId>
  <version>0.15.0</version>
</dependency>
```

### Gradle

```gradle
dependencies {
    implementation 'org.apache.fory:fory-core:0.15.0'
    // Optional modules
    implementation 'org.apache.fory:fory-format:0.15.0'
    implementation 'org.apache.fory:fory-simd:0.15.0'
    implementation 'org.apache.fory:fory-extensions:0.15.0'
}
```

## Quick Start

### Basic Usage

Create a Fory instance, register your classes, and start serializing objects. Remember to reuse the Fory instance for optimal performance:

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

// Create Fory instance (should be reused)
Fory fory = Fory.builder()
  .requireClassRegistration(true)
  .build();

// Register your classes
fory.register(MyClass.class);

// Serialize
MyClass object = new MyClass();
byte[] bytes = fory.serialize(object);

// Deserialize
MyClass result = (MyClass) fory.deserialize(bytes);
```

### Thread-Safe Usage

For multi-threaded environments, use `ThreadSafeFory` which maintains a pool of Fory instances:

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

// Create thread-safe Fory instance
private static final ThreadSafeFory fory = Fory.builder().buildThreadSafeFory();

static {
    fory.register(MyClass.class);
}

// Use in multiple threads
byte[] bytes = fory.serialize(object);
Object result = fory.deserialize(bytes);
```

### Schema Evolution

Enable schema compatibility mode to support forward and backward compatibility when your class definitions change over time:

```java
// Enable forward/backward compatibility
Fory fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .build();

// Serialization and deserialization can use different class versions
// New fields will be ignored, missing fields will use default values
```

### Reference Tracking

Enable reference tracking to properly handle shared references and circular dependencies in your object graphs:

```java
// Enable reference tracking for circular/shared references
Fory fory = Fory.builder()
  .withRefTracking(true)
  .build();

// Serialize complex object graphs
GraphNode node = new GraphNode();
node.next = node;  // Circular reference
byte[] bytes = fory.serialize(node);
```

### Cross-Language Serialization

Use XLANG mode to serialize data that can be deserialized by other languages (Python, Rust, Go, etc.):

```java
// Use XLANG mode for cross-language compatibility
Fory fory = Fory.builder()
  .withLanguage(Language.XLANG)
  .withRefTracking(true)
  .build();

// Register with cross-language type id/name
fory.register(MyClass.class, 1);
// fory.register(MyClass.class, "com.example.MyClass");

// Bytes can be deserialized by Python, Go, etc.
byte[] bytes = fory.serialize(object);
```

## Configuration Options

### ForyBuilder Options

Configure Fory with various options to suit your specific use case:

```java
Fory fory = Fory.builder()
  // Language mode: JAVA (fastest) or XLANG (cross-language)
  .withLanguage(Language.JAVA)
  // Reference tracking for circular/shared references
  .withRefTracking(true)
  // Schema evolution mode
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  // Compression options
  .withIntCompressed(true)
  .withLongCompressed(true)
  .withStringCompressed(false)
  // Security options
  .requireClassRegistration(true)
  .withMaxDepth(50)
  // Performance options
  .withCodeGen(true)
  .withAsyncCompilation(true)
  // Class loader
  .withClassLoader(classLoader)
  .build();
```

See [Java Serialization Guide](../docs/guide/java_serialization_guide.md) for detailed configuration options.

## Advanced Features

### JDK Serialization Compatibility

Fory fully supports JDK serialization APIs with 100x better performance. You can use standard Java serialization methods and Fory will handle them automatically:

```java
public class MyClass implements Serializable {
  private void writeObject(ObjectOutputStream out) throws IOException {
    // Custom serialization logic
  }

  private void readObject(ObjectInputStream in) throws IOException {
    // Custom deserialization logic
  }

  private Object writeReplace() {
    // Return replacement object
  }

  private Object readResolve() {
    // Return resolved object
  }
}
```

### Deep Copy

Enable reference tracking during deep copy to preserve object identity and handle circular references correctly:

```java
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withRefCopy(true)
  .build();

MyClass original = new MyClass();
MyClass copy = fory.copy(original);
```

### Row Format

Fory provides a cache-friendly binary row format optimized for random access and analytics:

- **Zero-Copy Random Access**: Read individual fields without deserializing entire objects
- **Partial Serialization**: Skip unnecessary fields during serialization
- **Cross-Language Compatible**: Row format data can be read by Python, C++
- **Apache Arrow Integration**: Convert row format to/from Arrow RecordBatch for analytics

```java
import org.apache.fory.format.encoder.*;
import org.apache.fory.format.row.*;

public class Bar {
  String f1;
  List<Long> f2;
}

public class Foo {
  int f1;
  List<Integer> f2;
  Map<String, Integer> f3;
  List<Bar> f4;
}

// Create row encoder
RowEncoder<Foo> encoder = Encoders.bean(Foo.class);

// Serialize to row format
Foo foo = new Foo();
foo.f1 = 10;
foo.f2 = IntStream.range(0, 1000).boxed().collect(Collectors.toList());
BinaryRow binaryRow = encoder.toRow(foo);

// Zero-copy random access to fields
BinaryArray f2Array = binaryRow.getArray(1);  // Access f2 without deserializing entire object
BinaryArray f4Array = binaryRow.getArray(3);  // Access f4

// Zero-copy access nested fields
BinaryRow barStruct = f4Array.getStruct(10);  // Get 11th element
long value = barStruct.getArray(1).getInt64(5);  // Access nested field

// Partial deserialization
RowEncoder<Bar> barEncoder = Encoders.bean(Bar.class);
Bar partialBar = barEncoder.fromRow(barStruct);  // Deserialize only one Bar object

// Full deserialization
Foo deserializedFoo = encoder.fromRow(binaryRow);
```

See [Row Format Guide](../docs/guide/row_format_guide.md) for more details.

### Array Compression (Java 16+)

Use SIMD-accelerated compression for integer and long arrays to reduce memory usage when array elements have small values:

```java
import org.apache.fory.simd.*;

Fory fory = Fory.builder()
  .withIntArrayCompressed(true)
  .withLongArrayCompressed(true)
  .build();

// Register compressed array serializers
CompressedArraySerializers.registerSerializers(fory);

// Arrays with small values are automatically compressed
int[] data = new int[1000000];
byte[] bytes = fory.serialize(data);
```

### GraalVM Native Image

Fory supports GraalVM native image through code generation, eliminating the need for reflection configuration. Build your native image as follows:

```bash
# Generate serializers at build time
mvn package -Pnative

# Run native image
./target/my-app
```

See [GraalVM Guide](../docs/guide/graalvm_guide.md) for details.

## Development

### Building

All commands must be executed in the `java` directory:

```bash
# Build
mvn -T16 clean package

# Run tests
mvn -T16 test

# Install locally
mvn -T16 install -DskipTests

# Code formatting
mvn -T16 spotless:apply

# Code style check
mvn -T16 checkstyle:check
```

### Testing

```bash
# Run all tests
mvn -T16 test

# Run specific test
mvn -T16 test -Dtest=MyTestClass#testMethod

# Run with specific JDK
JAVA_HOME=/path/to/jdk mvn test
```

### Code Quality

```bash
# Format code
mvn -T16 spotless:apply

# Check code style
mvn -T16 checkstyle:check
```

## Performance Tips

1. **Reuse Fory Instances**: Creating Fory is expensive; reuse instances across serializations
2. **Enable Compression**: For numeric-heavy data, enable int/long compression
3. **Disable Reference Tracking**: If no circular references exist, disable tracking for better performance
4. **Use Java Mode**: Use `Language.JAVA` instead of `Language.XLANG` when cross-language support isn't needed. Java mode reduces type metadata overhead and supports more Java-native types not available in xlang mode
5. **Warm Up**: Allow JIT compilation to complete before benchmarking
6. **Register Classes**: Class registration reduces metadata overhead
7. **Use SIMD**: Enable array compression on Java 16+ for numeric arrays

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Licensed under the [Apache License 2.0](../LICENSE).
