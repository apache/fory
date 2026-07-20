# Apache Fory™ Java

[![Maven Version](https://img.shields.io/maven-central/v/org.apache.fory/fory-core?style=for-the-badge)](https://search.maven.org/#search|gav|1|g:"org.apache.fory"%20AND%20a:"fory-core")
[![Java Version](https://img.shields.io/badge/Java-8%2B-blue?style=for-the-badge)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](https://opensource.org/licenses/Apache-2.0)

Apache Fory™ Java provides high-performance binary object serialization, JSON
serialization, and a random-access row format for the Java ecosystem.

## Choose a Format

| Format                          | Use it when                                                                      | Module        | Guide                                                 |
| ------------------------------- | -------------------------------------------------------------------------------- | ------------- | ----------------------------------------------------- |
| **Binary Object Serialization** | You need compact object graphs in Java native mode or across supported languages | `fory-core`   | [Java guide](../docs/guide/java/)                     |
| **JSON Serialization**          | You need high-throughput standard JSON for Java applications                     | `fory-json`   | [Fory JSON guide](../docs/guide/java/json-support.md) |
| **Row Format**                  | You need zero-copy random access, partial reads, or Arrow integration            | `fory-format` | [Row-format guide](../docs/guide/java/row-format.md)  |

Keep all Fory modules in one application on the same version.

## Features

### Binary Object Serialization

- **Generated Codecs**: JIT-generated serializers inline data access and reduce
  virtual dispatch, branching, and metadata lookups on hot paths.
- **Native and Cross-Language Modes**: Use Java-native object semantics for
  Java-only traffic or a portable wire format across supported languages.
- **Object Graph Semantics**: Preserve shared and circular references,
  polymorphism, and schema evolution.
- **Compact Encoding**: Variable-length integer encoding, metadata sharing,
  string compression, and optional numeric-array compression reduce payload size.
- **Java Object Model**: Native mode supports ordinary Java classes, records,
  JDK custom serialization semantics, `Externalizable`, and deep copy.
- **Security Controls**: Class registration, type checking, depth limits, and
  configurable deserialization policies protect decoding boundaries.

### JSON Serialization

- **Performance-First Codec**: Optimized readers and writers plus interpreted
  and runtime-generated codecs keep JSON encoding and decoding fast.
- **Java Object Mapping**: Supports ordinary objects, Java 17 records, immutable
  creator-based classes, common JDK types, generic containers, custom codecs,
  and annotation-declared polymorphism.
- **String and UTF-8 APIs**: Read and write both `String` and UTF-8 `byte[]`
  without requiring separate mapper instances.
- **Thread-Safe Runtime**: Build one immutable `ForyJson` instance and reuse it
  across threads.

### Row Format

- **Zero-Copy Random Access**: Read fields and nested values without rebuilding
  complete objects.
- **Partial Reads**: Decode only the data required by an analytics or query path.
- **Apache Arrow Integration**: Convert between Fory row data and Arrow data for
  columnar processing.

### Platforms

- `fory-core` and `fory-json` support Java 8 and later; Java records require
  Java 17 or later.
- `fory-format` targets Java 11 and later.
- `fory-core` and `fory-json` run on standard JDKs, GraalVM native images, and
  Android. Optional SIMD array acceleration in `fory-core` uses the Java Vector
  API on Java 16 and later.

## Documentation

| Topic                       | Description                         | Source Doc Link                                                                | Website Doc Link                                                                              |
| --------------------------- | ----------------------------------- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| **Java Guide**              | Binary xlang and native mode usage  | [docs/guide/java](../docs/guide/java)                                          | [Java Guide](https://fory.apache.org/docs/guide/java/)                                        |
| **Fory JSON**               | JSON installation, APIs, and types  | [json-support.md](../docs/guide/java/json-support.md)                          | [Fory JSON](https://fory.apache.org/docs/guide/java/json_support)                             |
| **Row Format**              | Random access and Arrow integration | [row-format.md](../docs/guide/java/row-format.md)                              | [Row Format](https://fory.apache.org/docs/guide/java/row_format)                              |
| **GraalVM Native Image**    | Native image support                | [graalvm-support.md](../docs/guide/java/graalvm-support.md)                    | [GraalVM Support](https://fory.apache.org/docs/guide/java/graalvm_support)                    |
| **Java Serialization Spec** | Binary protocol specification       | [java_serialization_spec.md](../docs/specification/java_serialization_spec.md) | [Java Serialization Spec](https://fory.apache.org/docs/specification/java_serialization_spec) |
| **Java Benchmarks**         | Performance data and plots          | [java/README.md](../docs/benchmarks/java/README.md)                            | [Java Benchmarks](https://fory.apache.org/docs/benchmarks/java)                               |

## Modules

| Module                               | Description                               | Maven Artifact                    |
| ------------------------------------ | ----------------------------------------- | --------------------------------- |
| **fory-core**                        | Binary native and xlang serialization     | `org.apache.fory:fory-core`       |
| [**fory-json**](fory-json/README.md) | High-performance JSON codec               | `org.apache.fory:fory-json`       |
| **fory-format**                      | Row format and Apache Arrow support       | `org.apache.fory:fory-format`     |
| **fory-extensions**                  | Protobuf support and metadata compression | `org.apache.fory:fory-extensions` |
| **fory-test-core**                   | Testing utilities and data generators     | `org.apache.fory:fory-test-core`  |

## Installation

Add only the artifacts required by your chosen formats and keep their versions
aligned. `fory-json` includes `fory-core` transitively.

### Maven

```xml
<!-- Binary object serialization -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.4.0</version>
</dependency>

<!-- JSON serialization -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-json</artifactId>
  <version>1.4.0</version>
</dependency>

<!-- Optional: Row format support -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-format</artifactId>
  <version>1.4.0</version>
</dependency>

<!-- Optional: Serializers for Protobuf data -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-extensions</artifactId>
  <version>1.4.0</version>
</dependency>
```

### Gradle

```gradle
dependencies {
    // Binary object serialization
    implementation 'org.apache.fory:fory-core:1.4.0'
    // Add only the optional formats and extensions you use
    implementation 'org.apache.fory:fory-json:1.4.0'
    implementation 'org.apache.fory:fory-format:1.4.0'
    implementation 'org.apache.fory:fory-extensions:1.4.0'
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

## Binary Object Serialization

### Quick Start

Create a Fory instance, register your classes, and start serializing objects. Remember to reuse the Fory instance for optimal performance:

```java
import org.apache.fory.Fory;

// Create Fory instance (should be reused). Java defaults to xlang mode with
// compatible schema evolution.
Fory fory = Fory.builder()
  .withXlang(true)
  .requireClassRegistration(true)
  .build();

// Register the same type identity on every xlang peer
fory.register(MyClass.class, "example.MyClass");

// Serialize
MyClass object = new MyClass();
byte[] bytes = fory.serialize(object);

// Deserialize
MyClass result = fory.deserialize(bytes, MyClass.class);
```

### Thread-Safe Usage

For multi-threaded environments, use `ThreadSafeFory` which maintains a pool of Fory instances:

```java
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

// Create thread-safe xlang Fory instance
private static final ThreadSafeFory fory = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();

static {
    fory.register(MyClass.class, "example.MyClass");
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

### Configuration

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

### JDK Custom Serialization Semantics

In native mode, Fory supports JDK serialization APIs with much better performance. Use native mode
when replacing Java-only JDK serialization, Kryo, FST, Hessian, or Protocol Buffers payloads:

```java
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

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

### Array Compression

Use width compression for integer and long arrays to reduce serialized size when array elements have small values. JDK 8 through 15 use scalar range analysis; JDK 16 and later automatically select the Vector API implementation from the multi-release JAR.

```java
import org.apache.fory.serializer.CompressedArraySerializers;

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

On JDK 16 or later, resolve the incubator Vector API module when starting the application:

```bash
java --add-modules=jdk.incubator.vector ...
```

### Performance Guidelines

1. Reuse `Fory` or `ThreadSafeFory` instances instead of rebuilding runtime
   state for each operation.
2. Register application classes to avoid repeated type metadata and keep type
   identity explicit.
3. Use native mode for Java-only payloads; use xlang mode only when payloads
   must cross language boundaries.
4. Keep compatible mode enabled when schemas may differ. Disable it only when
   every reader and writer always uses the same schema.
5. Disable reference tracking only when shared identity and circular references
   are not part of the data model.
6. Enable string, integer, long, or numeric-array compression only after
   measuring the payload distribution and throughput tradeoff.
7. Warm up generated serializers before measuring steady-state performance.

## JSON Serialization

Fory JSON is a performance-first, thread-safe JSON codec for Java. Optimized
readers and writers handle `String` and UTF-8 `byte[]` directly, while
runtime-generated codecs accelerate object mapping where runtime code generation
is available.

Build one `ForyJson` instance and reuse it across threads:

```java
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.ForyJson;

public final class JsonExample {
  private static final ForyJson JSON = ForyJson.builder().build();

  public static final class User {
    public long id;
    public String name;

    public User() {}

    public User(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static void main(String[] args) {
    User input = new User(7, "Alice");

    String text = JSON.toJson(input);
    User fromText = JSON.fromJson(text, User.class);

    byte[] utf8 = JSON.toJsonBytes(input);
    User fromUtf8 = JSON.fromJson(utf8, User.class);

    System.out.println(text);
    System.out.println(new String(utf8, StandardCharsets.UTF_8));
    System.out.println(fromText.name + " / " + fromUtf8.name);
  }
}
```

Fory JSON supports Java 8 and later on standard JDKs, GraalVM native images,
and Android. Java records are supported on Java 17 and later. See the
[Fory JSON guide](../docs/guide/java/json-support.md) for supported types,
annotations, custom codecs, security controls, and platform setup.

## Row Format

Fory row format is a cache-friendly binary format for random access and
analytics. It can read fields, arrays, and nested values without rebuilding the
complete object.

```java
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.type.Schema;

public final class RowExample {
  public static final class User {
    public int id;
    public String name;
    public int[] scores;
  }

  public static void main(String[] args) {
    RowEncoder<User> encoder = Encoders.bean(User.class);

    User user = new User();
    user.id = 1;
    user.name = "Alice";
    user.scores = new int[] {98, 100, 95};

    BinaryRow row = encoder.toRow(user);

    Schema schema = encoder.schema();
    Schema.StringField nameField = schema.stringField("name");
    Schema.ArrayField scoresField = schema.arrayField("scores");

    String name = nameField.get(row);
    ArrayData scores = scoresField.get(row);
    int secondScore = scores.getInt32(1);

    System.out.println(name + ": " + secondScore);
  }
}
```

See the [Java row-format guide](../docs/guide/java/row-format.md) for nested
structs, arrays, maps, partial deserialization, and Arrow integration.

## GraalVM Native Image

Fory supports GraalVM Native Image without application reflection configuration. Binary
serialization generates serializers while the image is built; the Fory annotation processor
generates type-owned execution companions for Fory JSON `@JsonType` models. Build your native image
as follows:

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

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Licensed under the [Apache License 2.0](../LICENSE).
