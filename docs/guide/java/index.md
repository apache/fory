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

Apache Fory™ provides blazingly fast Java object serialization with JIT compilation and zero-copy techniques. When only Java object serialization is needed, this mode delivers better performance compared to cross-language object graph serialization.

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
- **Java 8-24 Support**: Works across all modern Java versions including Java 17+ records
- **GraalVM Native Image**: AOT compilation support without reflection configuration

### Advanced Features

- **Reference Tracking**: Automatic handling of shared and circular references
- **Schema Evolution**: Forward/backward compatibility for class schema changes
- **Polymorphism**: Full support for inheritance hierarchies and interfaces
- **Deep Copy**: Efficient deep cloning of complex object graphs with reference preservation
- **Security**: Class registration and configurable deserialization policies

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
    Fory fory = Fory.builder().withXlang(false)
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
      .withXlang(false)
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
      .withXlang(false)
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

## Thread Safety

Fory provides two thread-safe runtime styles:

### `buildThreadSafeFory`

This is the default choice. It uses a fixed-size shared `ThreadPoolFory` sized to
`4 * availableProcessors()` and is the preferred runtime for virtual-thread workloads:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(false)
  .withRefTracking(false)
  .withCompatible(false)
  .withAsyncCompilation(true)
  .buildThreadSafeFory();
```

See more details in [Virtual Threads](virtual-threads.md).

### ThreadLocalFory

Use `buildThreadLocalFory()` only when you explicitly want one `Fory` instance per long-lived
platform thread, or when you want to pin that choice regardless of JDK version:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(false)
  .buildThreadLocalFory();
fory.register(SomeClass.class, 1);
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

### `buildThreadSafeForyPool`

Use `buildThreadSafeForyPool(poolSize)` when you want to set that fixed shared pool size
explicitly. It eagerly creates `poolSize` `Fory` instances, keeps them in shared fixed slots, and
then lets any caller borrow one through a thread-agnostic fast path. Calls only block when every
pooled instance is already in use; the runtime does not key cached instances by thread identity:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(false)
  .withRefTracking(false)
  .withCompatible(false)
  .withAsyncCompilation(true)
  .buildThreadSafeForyPool(poolSize);
```

### Builder Methods

```java
// Single-thread Fory
Fory fory = Fory.builder()
  .withXlang(false)
  .withRefTracking(false)
  .withCompatible(false)
  .withAsyncCompilation(true)
  .build();

// Thread-safe Fory (thread-safe Fory backed by a pool of Fory instances)
ThreadSafeFory fory = Fory.builder()
  .withXlang(false)
  .withRefTracking(false)
  .withCompatible(false)
  .withAsyncCompilation(true)
  .buildThreadSafeFory();

// Explicit thread-local runtime
ThreadSafeFory threadLocalFory = Fory.builder()
  .withXlang(false)
  .buildThreadLocalFory();
```

## Next Steps

- [Configuration](configuration.md) - Learn about ForyBuilder options
- [Field Configuration](field-configuration.md) - `@ForyField`, `@Ignore`, and integer encoding annotations
- [Enum Configuration](enum-configuration.md) - `serializeEnumByName` and `@ForyEnumId`
- [Basic Serialization](basic-serialization.md) - Detailed serialization patterns
- [Object Copy](object-copy.md) - Deep-copy Java object graphs in memory
- [Compression](compression.md) - Integer, long, and array compression options
- [Virtual Threads](virtual-threads.md) - Virtual-thread usage and pool sizing guidance
- [Type Registration](type-registration.md) - Class registration and security
- [Custom Serializers](custom-serializers.md) - Implement custom serializers
- [Cross-Language Serialization](cross-language.md) - Serialize data for other languages
- [GraalVM Support](graalvm-support.md) - Build-time serializer compilation for native images
