# Apache Fory™ Java

[![Maven Version](https://img.shields.io/maven-central/v/org.apache.fory/fory-core?style=for-the-badge)](https://search.maven.org/#search|gav|1|g:"org.apache.fory"%20AND%20a:"fory-core")
[![Java Version](https://img.shields.io/badge/Java-8%2B-blue?style=for-the-badge)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](https://opensource.org/licenses/Apache-2.0)

Apache Fory™ Java provides blazingly-fast serialization for the Java ecosystem, delivering up to **170x performance improvement** over traditional frameworks through JIT compilation and zero-copy techniques.

## Features

### High Performance

- **JIT Code Generation**: Highly-extensible JIT framework generates serializer code at runtime using async multi-threaded compilation, delivering 20-170x speedup through:
  - Inlining variables to reduce memory access
  - Inlining method calls to eliminate virtual dispatch overhead
  - Minimizing conditional branching
  - Eliminating hash lookups
- **Zero-Copy**: Direct memory access without intermediate buffer copies; row format supports random access and partial serialization
- **Variable-Length Encoding**: Optimized compression for integers, longs
- **Meta Sharing**: Cached class metadata reduces redundant type information
- **SIMD Acceleration**: Java Vector API support for array operations (Java 16+)

### Drop-in Replacement

- **100% JDK Serialization Compatible**: Supports `writeObject`/`readObject`/`writeReplace`/`readResolve`/`readObjectNoData`/`Externalizable`
- **Java 8+ Support**: Works across all modern Java versions including Java 17+ records
- **GraalVM Native Image**: AOT compilation support without reflection configuration

### Advanced Features

- **Reference Tracking**: Automatic handling of shared and circular references
- **Schema Evolution**: Forward/backward compatibility for class schema changes
- **Polymorphism**: Full support for inheritance hierarchies and interfaces
- **Deep Copy**: Efficient deep cloning of complex object graphs with reference preservation
- **Security**: Class registration and configurable deserialization policies

## Documentation

| Topic                       | Description                      | Source Doc Link                                                                | Website Doc Link                                                                              |
| --------------------------- | -------------------------------- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| **Java Guide**              | Java xlang and native mode usage | [docs/guide/java](../docs/guide/java)                                          | [Java Guide](https://fory.apache.org/docs/guide/java/)                                        |
| **GraalVM Native Image**    | Native image support             | [graalvm-support.md](../docs/guide/java/graalvm-support.md)                    | [GraalVM Support](https://fory.apache.org/docs/guide/java/graalvm_support)                    |
| **Java Serialization Spec** | Protocol specification           | [java_serialization_spec.md](../docs/specification/java_serialization_spec.md) | [Java Serialization Spec](https://fory.apache.org/docs/specification/java_serialization_spec) |
| **Java Benchmarks**         | Performance data and plots       | [java/README.md](../docs/benchmarks/java/README.md)                            | [Java Benchmarks](https://fory.apache.org/docs/benchmarks/java)                               |

## Modules

| Module              | Description                           | Maven Artifact                    |
| ------------------- | ------------------------------------- | --------------------------------- |
| **fory-core**       | Core serialization engine             | `org.apache.fory:fory-core`       |
| **fory-format**     | Row format and Apache Arrow support   | `org.apache.fory:fory-format`     |
| **fory-extensions** | Protobuf support and meta compression | `org.apache.fory:fory-extensions` |
| **fory-test-core**  | Testing utilities and data generators | `org.apache.fory:fory-test-core`  |

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.3.0</version>
</dependency>

<!-- Optional: Row format support -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-format</artifactId>
  <version>1.3.0</version>
</dependency>

<!-- Optional: Serializers for Protobuf data -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-extensions</artifactId>
  <version>1.3.0</version>
</dependency>

```

### Gradle

```gradle
dependencies {
    implementation 'org.apache.fory:fory-core:1.3.0'
    // Optional modules
    implementation 'org.apache.fory:fory-format:1.3.0'
    implementation 'org.apache.fory:fory-extensions:1.3.0'
}
```

### JDK25+

On JDK25+, open `java.lang.invoke` to Fory. Use `ALL-UNNAMED` when Fory is on
the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

Use the Fory core module name when Fory is on the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

## Quick Start

### Basic Usage

Create a Fory instance, register your classes, and start serializing objects. Remember to reuse the Fory instance for optimal performance:

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

// Create Fory instance (should be reused). Java defaults to xlang mode with
// compatible schema evolution.
Fory fory = Fory.builder()
  .withXlang(true)
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

// Create thread-safe xlang Fory instance
private static final ThreadSafeFory fory = Fory.builder().withXlang(true).buildThreadSafeFory();

static {
    fory.register(MyClass.class);
}

// Use in multiple threads
byte[] bytes = fory.serialize(object);
Object result = fory.deserialize(bytes);
```

### Native Mode

Use native mode for Java-only payloads when you need JVM-specific object behavior such as JDK
serialization hooks, `Externalizable`, broader object graph support, or a replacement for JDK
serialization, Kryo, FST, Hessian, or Java-only Protocol Buffers payloads:

```java
Fory fory = Fory.builder()
  .withXlang(false)
  .requireClassRegistration(true)
  .build();
```

### Schema Evolution

Compatible mode is the default for both xlang and native mode. Keep that default when your class
definitions change over time:

```java
Fory fory = Fory.builder().withXlang(false)
  .build();

// Serialization and deserialization can use different class versions
// New fields will be ignored, missing fields will use default values
```

### Reference Tracking

Enable reference tracking to properly handle shared references and circular dependencies in your object graphs:

```java
// Enable reference tracking for circular/shared references
Fory fory = Fory.builder().withXlang(false)
  .withRefTracking(true)
  .build();

// Serialize complex object graphs
GraphNode node = new GraphNode();
node.next = node;  // Circular reference
byte[] bytes = fory.serialize(node);
```

### Cross-Language Serialization

Use xlang mode, the Java default, to serialize data that can be deserialized by other languages
(Python, Rust, Go, etc.):

```java
Fory fory = Fory.builder()
  .withXlang(true)
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
  // Native mode for Java-only payloads. Omit this for xlang payloads.
  .withXlang(false)
  // Reference tracking for circular/shared references
  .withRefTracking(true)
  // Compatible schema evolution is enabled by default.
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

See the [Java guide](../docs/guide/java/) for detailed configuration options.

## Advanced Features

### JDK Serialization Compatibility

In native mode, Fory supports JDK serialization APIs with much better performance. Use native mode
when replacing Java-only JDK serialization, Kryo, FST, Hessian, or Protocol Buffers payloads:

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
  .withXlang(false)
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

See the [Java row-format guide](../docs/guide/java/row-format.md) for more details.

### Array Compression (Java 16+)

Use SIMD-accelerated compression for integer and long arrays to reduce memory usage when array elements have small values:

```java
import org.apache.fory.simd.*;

Fory fory = Fory.builder().withXlang(false)
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

See [GraalVM Support](../docs/guide/java/graalvm-support.md) for details.

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
4. **Use native mode**: For Java-only payloads, use `withXlang(false)`. Native mode reduces type metadata overhead and supports more Java-native types not available in xlang mode
5. **Warm Up**: Allow JIT compilation to complete before benchmarking
6. **Register Classes**: Class registration reduces metadata overhead
7. **Use SIMD**: Enable array compression on Java 16+ for numeric arrays

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Licensed under the [Apache License 2.0](../LICENSE).
