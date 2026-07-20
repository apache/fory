---
title: Java Serialization Guide
sidebar_position: 0
id: serialization_index
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Apache Fory™ Java provides high-performance binary object serialization, JSON
serialization, and a random-access row format. Binary serialization supports
xlang mode for cross-language payloads and native mode for Java-only object
graphs. [Fory JSON](json-support.md) is a separate, performance-first standard
JSON codec with its own object-mapping model.

## Choose a Format

| Format                          | Use it when                                                                      | Artifact                      | Start here                                    |
| ------------------------------- | -------------------------------------------------------------------------------- | ----------------------------- | --------------------------------------------- |
| **Binary Object Serialization** | You need compact object graphs in Java native mode or across supported languages | `org.apache.fory:fory-core`   | [Basic Serialization](basic-serialization.md) |
| **Fory JSON**                   | You need high-throughput standard JSON for Java applications                     | `org.apache.fory:fory-json`   | [JSON Support](json-support.md)               |
| **Row Format**                  | You need zero-copy random access, partial reads, or Arrow integration            | `org.apache.fory:fory-format` | [Row Format](row-format.md)                   |

## Binary Object Serialization

### Features

- **Generated Codecs**: JIT-generated serializers reduce virtual dispatch,
  branching, and metadata lookups on hot paths.
- **Native and Xlang Modes**: Choose Java-native object semantics or a portable
  wire format shared with other Fory implementations.
- **Compact Encoding**: Variable-length integers, metadata sharing, string
  compression, and optional numeric-array compression reduce payload size.
- **Object Graph Semantics**: Preserve shared and circular references,
  polymorphism, schema evolution, and deep-copy identity.

### Native Mode Features

- **Framework Replacement**: Replace JDK serialization, Kryo, FST, Hessian, or
  Java-only Protocol Buffers payloads in Java-only systems.
- **JDK Semantics**: Supports JDK custom serialization behavior and
  `Externalizable` in native mode.
- **Security Controls**: Class registration, type checking, depth limits, and
  configurable deserialization policies protect decoding boundaries.

### Installation

Add `fory-core` for binary object serialization. Keep all Fory modules in one
application on the same version.

#### Maven

```xml
<!-- Binary object serialization -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.4.0</version>
</dependency>
```

#### Gradle

```kotlin
// Binary object serialization
implementation("org.apache.fory:fory-core:1.4.0")
```

#### JDK 25 and Later

On JDK 25 and later, open `java.lang.invoke` to Fory. Use `ALL-UNNAMED` when Fory is on
the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

Use the Fory core module name when Fory is on the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

### Quick Start

Note that Fory creation is not cheap, the **Fory instances should be reused between serializations** instead of creating it every time. You should keep Fory as a static global variable, or instance variable of some singleton object or limited objects.

#### Single-Thread Usage

```java
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    // Note that Fory instances should be reused between
    // multiple serializations of different objects.
    Fory fory = Fory.builder()
      .withXlang(true)
      .requireClassRegistration(true)
      .build();
    // Registering types can reduce class name serialization overhead, but not mandatory.
    // If class registration enabled, all custom types must be registered.
    // Registration order must be consistent if id is not specified
    fory.register(SomeClass.class);
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

#### Multi-Thread Usage

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    ThreadSafeFory fory = Fory.builder()
      .withXlang(true)
      .buildThreadSafeFory();
    fory.register(SomeClass.class, 1);
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

#### Fory Instance Reuse Pattern

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  private static final ThreadSafeFory fory = Fory.builder()
    .withXlang(true)
    .buildThreadSafeFory();

  static {
    fory.register(SomeClass.class, 1);
  }

  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

### Xlang Mode And Native Mode

Use xlang mode for cross-language payloads and schemas shared with non-Java implementations. It is the default Java wire mode, and Java examples that use it set `.withXlang(true)` explicitly so the mode choice is visible.

Use native mode for Java-only traffic. Native mode is selected with `.withXlang(false)` and owns Java-specific object behavior such as JDK serialization hooks, `Externalizable`, dynamic object graphs, object copy, and Java native-mode zero-copy buffers. It is optimized for the JVM type system and supports a broader Java object surface than xlang mode. Compatible mode is enabled by default. Set `.withCompatible(false)` only when every reader and writer uses the same class schema and you want faster serialization and smaller size. If you are replacing JDK serialization, Kryo, FST, Hessian, or Java-only Protocol Buffers payloads, start with native mode.

See [Native Serialization](native-serialization.md) for Java-only serialization details and [Xlang Serialization](xlang-serialization.md) for Java xlang registration and interoperability rules.

### Thread Safety

Fory provides two thread-safe Fory instance styles:

#### `buildThreadSafeFory`

This is the default choice. It uses a fixed-size shared `ThreadPoolFory` sized to
`4 * availableProcessors()` and is the preferred instance form for virtual-thread workloads:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(true)
  .withRefTracking(false)
  .withAsyncCompilation(true)
  .buildThreadSafeFory();
```

See more details in [Virtual Threads](virtual-threads.md).

#### ThreadLocalFory

Use `buildThreadLocalFory()` only when you explicitly want one `Fory` instance per long-lived
platform thread, or when you want to pin that choice regardless of JDK version:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(true)
  .buildThreadLocalFory();
fory.register(SomeClass.class, 1);
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

#### `buildThreadSafeForyPool`

Use `buildThreadSafeForyPool(poolSize)` when you want to set that fixed shared pool size
explicitly. It eagerly creates `poolSize` `Fory` instances, keeps them in shared fixed slots, and
then lets any caller borrow one through a thread-agnostic fast path. Calls only block when every
pooled instance is already in use; the pool does not key cached instances by thread identity:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(true)
  .withRefTracking(false)
  .withAsyncCompilation(true)
  .buildThreadSafeForyPool(poolSize);
```

#### Builder Methods

```java
// Single-thread Fory
Fory fory = Fory.builder()
  .withXlang(true)
  .withRefTracking(false)
  .withAsyncCompilation(true)
  .build();

// Thread-safe Fory (thread-safe Fory backed by a pool of Fory instances)
ThreadSafeFory fory = Fory.builder()
  .withXlang(true)
  .withRefTracking(false)
  .withAsyncCompilation(true)
  .buildThreadSafeFory();

// Explicit thread-local Fory instance
ThreadSafeFory threadLocalFory = Fory.builder()
  .withXlang(true)
  .buildThreadLocalFory();
```

## Fory JSON

Fory JSON is a separate, performance-first standard JSON codec for Java. It has
its own object-mapping annotations and limits and does not use Fory's native or
xlang binary wire format.

### Features

- **Performance-First Codec**: Optimized readers and writers plus interpreted
  and runtime-generated codecs keep JSON encoding and decoding fast.
- **Java Object Mapping**: Supports ordinary objects, Java 17 records, immutable
  creator-based classes, common JDK types, generic containers, custom codecs,
  and annotation-declared polymorphism.
- **String and UTF-8 APIs**: Read and write `String` and UTF-8 `byte[]` with one
  immutable, thread-safe `ForyJson` instance.

### Installation

`fory-json` includes `fory-core` transitively. Keep both modules on the same
version when another dependency also brings `fory-core` into the application.

#### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-json</artifactId>
  <version>1.4.0</version>
</dependency>
```

#### Gradle

```kotlin
implementation("org.apache.fory:fory-json:1.4.0")
```

On JDK 25 and later, use the same `java.lang.invoke` module open described in
the binary serialization installation section.

### Quick Start

`ForyJson` is immutable and thread-safe after construction. Reuse one instance
for both `String` and UTF-8 `byte[]` operations:

```java
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

    System.out.println(fromText.name + " / " + fromUtf8.name);
  }
}
```

See [JSON Support](json-support.md) for supported types, annotations, custom
codecs, security controls, and platform setup.

## Row Format

Fory row format is a separate cache-friendly binary format for random access,
partial reads, and analytics workloads.

### Features

- **Zero-Copy Random Access**: Read fields and nested values without rebuilding
  complete objects.
- **Partial Reads**: Decode only the data required by an analytics or query path.
- **Apache Arrow Integration**: Convert between Fory row data and Arrow data for
  columnar processing.

### Installation

#### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-format</artifactId>
  <version>1.4.0</version>
</dependency>
```

#### Gradle

```kotlin
implementation("org.apache.fory:fory-format:1.4.0")
```

See [Row Format](row-format.md) for encoding, typed field access, partial
deserialization, nested values, and Arrow integration.

## Platform Support

- `fory-core` and `fory-json` support Java 8 and later; Java records require
  Java 17 or later.
- `fory-format` targets Java 11 and later and is not supported on Android.
- `fory-core` and `fory-json` run on standard JDKs, GraalVM native images, and
  Android API level 26 and later.

## Next Steps

- [Configuration](configuration.md) - Learn about ForyBuilder options
- [Schema Metadata](schema-metadata.md) - `@ForyField`, `@Ignore`, integer encoding annotations, `serializeEnumByName`, and `@ForyEnumId`
- [Basic Serialization](basic-serialization.md) - Detailed serialization patterns
- [Object Copy](object-copy.md) - Deep-copy Java object graphs in memory
- [Compression](compression.md) - Integer, long, and array compression options
- [Virtual Threads](virtual-threads.md) - Virtual-thread usage and pool sizing guidance
- [gRPC Support](grpc-support.md) - Fory payloads over grpc-java
- [Type Registration](type-registration.md) - Class registration and security
- [Custom Serializers](custom-serializers.md) - Implement custom serializers
- [Xlang Serialization](xlang-serialization.md) - Serialize data for other languages
- [Native Serialization](native-serialization.md) - Java-only serialization features
- [JSON Support](json-support.md) - Complete Fory JSON user guide
- [Static Generated Serializers](static-generated-serializers.md) - Annotation-processor static generated serializers for `@ForyStruct`
- [GraalVM Support](graalvm-support.md) - Native Image support for binary serialization and JSON
