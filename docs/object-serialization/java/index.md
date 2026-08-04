---
title: Java Object Serialization
sidebar_position: 0
id: index
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

Apache Fory Java provides high-performance binary object serialization. Use xlang mode for payloads
shared across Fory implementation families and native mode for JVM-family object graphs.

This Java guide is scoped to Binary Object Serialization. For other Java capabilities, use
[Row Format](../../row-format/java.md), [Fory JSON](../../json/index.md),
[Fory IDL and compiler](../../compiler/index.md), or [Fory gRPC](../../grpc/java.md).

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

Fory core supports Java 8 and later. Java Record serialization requires Java 17
or later.

#### Maven

```xml
<!-- Binary object serialization -->
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.5.0</version>
</dependency>
```

#### Gradle

```kotlin
// Binary object serialization
implementation("org.apache.fory:fory-core:1.5.0")
```

#### JDK 25 and Later

On JDK 25 and later, opening `java.lang.invoke` to Fory core is not required, but is recommended. It avoids
the current-JDK Unsafe fallback and is required when Unsafe access is disabled or unavailable,
including with `--sun-misc-unsafe-memory-access=deny`. Use `ALL-UNNAMED` when Fory is on the
classpath:

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

See [Native Serialization](native.md) for Java-only serialization details and [Cross-Language Interoperability](basic-serialization.md#cross-language-interoperability) for Java xlang registration and interoperability rules.

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

## Platform support

- [Android](android.md) covers Fory Core, static generated serializers, Kotlin integration, R8,
  `ByteBuffer`, and Android object-model constraints.
- [GraalVM Native Image](graalvm.md) covers build-time serializer generation, registration,
  initialization, proxies, framework integration, and diagnostics.

Fory JSON has separate [Android](../../json/android.md) and
[GraalVM Native Image](../../json/graalvm.md) deployment guides because its generated-code and
model-discovery workflows differ from Fory Core.

## Documentation map

| Group                  | Pages                                                                                                                                                                                                                                                             |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Serialization modes    | [Basic Serialization](basic-serialization.md), [Native Serialization](native.md)                                                                                                                                                                                  |
| Common                 | [Configuration](configuration.md), [Type Registration](type-registration.md), [Schema Evolution](schema-evolution.md), [Schema Metadata](schema-metadata.md), [Custom Serializers](custom-serializers.md)                                                         |
| Java-specific features | [Advanced Features](advanced-features.md), [Compression](compression.md), [Object Copy](object-copy.md), [JDK Custom Serialization](jdk-serialization.md), [Static Generated Serializers](static-generated-serializers.md), [Virtual Threads](virtual-threads.md) |
| Platform and operate   | [Android](android.md), [GraalVM Native Image](graalvm.md), [Troubleshooting](troubleshooting.md)                                                                                                                                                                  |

Before decoding externally supplied binary payloads, read
[Java Security](security.md).
