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
    Fory fory = Fory.builder().withLanguage(Language.JAVA)
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
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    // Note that Fory instances should be reused between
    // multiple serializations of different objects.
    ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
      Fory f = Fory.builder().withLanguage(Language.JAVA)
        .withClassLoader(classLoader).build();
      f.register(SomeClass.class, 1);
      return f;
    });
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

### Fory Instance Reuse Pattern

```java
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  // reuse fory.
  private static final ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
    Fory f = Fory.builder().withLanguage(Language.JAVA)
      .withClassLoader(classLoader).build();
    f.register(SomeClass.class, 1);
    return f;
  });

  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

## Thread Safety

Fory provides multiple options for thread-safe serialization:

### ThreadLocalFory

Uses thread-local storage to maintain separate Fory instances per thread:

```java
ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
  Fory f = Fory.builder().withLanguage(Language.JAVA)
    .withClassLoader(classLoader).build();
  f.register(SomeClass.class, 1);
  return f;
});
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

### ThreadSafeForyPool

For virtual threads or environments where thread-local storage is not appropriate, use `buildThreadSafeForyPool`:

```java
ThreadSafeFory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withRefTracking(false)
  .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
  .withAsyncCompilation(true)
  .buildThreadSafeForyPool(minPoolSize, maxPoolSize);
```

Note that calling `buildThreadSafeFory()` on `ForyBuilder` will create an instance of `ThreadLocalFory`. This may not be appropriate in environments where virtual threads are used, as each thread will create its own Fory instance, a relatively expensive operation. An alternative for virtual threads is to use `buildThreadSafeForyPool`.

### Builder Methods

```java
// Single-thread Fory
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withRefTracking(false)
  .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
  .withAsyncCompilation(true)
  .build();

// Thread-safe Fory (ThreadLocalFory)
ThreadSafeFory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withRefTracking(false)
  .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
  .withAsyncCompilation(true)
  .buildThreadSafeFory();
```

## Next Steps

- [Configuration](configuration.md) - Learn about ForyBuilder options
- [Basic Serialization](basic-serialization.md) - Detailed serialization patterns
- [Type Registration](type-registration.md) - Class registration and security
- [Custom Serializers](custom-serializers.md) - Implement custom serializers
- [Cross-Language Serialization](cross-language.md) - Serialize data for other languages
