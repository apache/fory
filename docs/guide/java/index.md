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

Apache Fory™ provides blazingly fast Java object serialization with JIT compilation and zero-copy techniques. Java supports both xlang mode and native mode. Xlang mode is the default cross-language wire format and uses compatible schema evolution. Native mode is the Java-only wire format for same-language object serialization, JDK serialization replacement behavior, framework replacement, and Java-native object graph features.

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
- **Android API 26+ Support**: Core object serialization works on Android without runtime code generation.

### Advanced Features

- **Reference Tracking**: Automatic handling of shared and circular references
- **Schema Evolution**: Forward/backward compatibility for class schema changes
- **Polymorphism**: Full support for inheritance hierarchies and interfaces
- **Deep Copy**: Efficient deep cloning of complex object graphs with reference preservation
- **Security**: Class registration and configurable deserialization policies

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.3.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.apache.fory:fory-core:1.3.0")
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

Note that Fory creation is not cheap, the **Fory instances should be reused between serializations** instead of creating it every time. You should keep Fory as a static global variable, or instance variable of some singleton object or limited objects.

### Single-Thread Usage

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

### Multi-Thread Usage

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

### Fory Instance Reuse Pattern

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

## Xlang Mode And Native Mode

Use xlang mode for cross-language payloads and schemas shared with non-Java implementations. It is the default Java wire mode, and Java examples that use it set `.withXlang(true)` explicitly so the mode choice is visible.

Use native mode for Java-only traffic. Native mode is selected with `.withXlang(false)` and owns Java-specific object behavior such as JDK serialization hooks, `Externalizable`, dynamic object graphs, object copy, and Java native-mode zero-copy buffers. It is optimized for the JVM type system and supports a broader Java object surface than xlang mode. Compatible mode is enabled by default. Set `.withCompatible(false)` only when every reader and writer uses the same class schema and you want faster serialization and smaller size. If you are replacing JDK serialization, Kryo, FST, Hessian, or Java-only Protocol Buffers payloads, start with native mode.

See [Native Serialization](native-serialization.md) for Java-only serialization details and [Xlang Serialization](xlang-serialization.md) for Java xlang registration and interoperability rules.

## Thread Safety

Fory provides two thread-safe Fory instance styles:

### `buildThreadSafeFory`

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

### ThreadLocalFory

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

### `buildThreadSafeForyPool`

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

### Builder Methods

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
- [Static Generated Serializers](static-generated-serializers.md) - Annotation-processor static generated serializers for `@ForyStruct`
- [GraalVM Support](graalvm-support.md) - Build-time serializer compilation for native images
