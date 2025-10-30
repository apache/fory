---
title: GraalVM Guide
sidebar_position: 6
id: graalvm_serialization
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

## GraalVM Native Image

GraalVM `native image` can compile java code into native code ahead to build faster, smaller, leaner applications.
The native image doesn't have a JIT compiler to compile bytecode into machine code, and doesn't support
reflection unless configure reflection file.

Apache Fory™ runs on GraalVM native image pretty well. Apache Fory™ generates all serializer code for `Fory JIT framework` and `MethodHandle/LambdaMetafactory` at graalvm build time. Then use those generated code for serialization at runtime without
any extra cost, the performance is great.

In order to use Apache Fory™ on graalvm native image, you must create Fory as an **static** field of a class, and **register** all classes at
the enclosing class initialize time. Then configure `native-image.properties` under
`resources/META-INF/native-image/$xxx/native-image.properties` to tell graalvm to init the class at native image
build time. For example, here we configure `org.apache.fory.graalvm.Example` class be init at build time:

```properties
Args = --initialize-at-build-time=org.apache.fory.graalvm.Example
```

**The main benefit of using Fory with GraalVM is that you don't need to configure [reflection json](https://www.graalvm.org/latest/reference-manual/native-image/metadata/#specifying-reflection-metadata-in-json) or
[serialization json](https://www.graalvm.org/latest/reference-manual/native-image/metadata/#serialization) for your serializable classes!** This eliminates the tedious, cumbersome and error-prone configuration process.

Fory achieves this by using **codegen instead of reflection** - all serialization code is generated at build time when you call:

- `fory.register(YourClass.class)` to register your classes
- `fory.ensureSerializersCompiled()` to compile serializers at build time

Note that Fory `asyncCompilationEnabled` option will be disabled automatically for graalvm native image since graalvm
native image doesn't support JIT at the image run time.

## Registering Your Classes

If you encounter a GraalVM error like:

```
Type com.example.MyClass is instantiated reflectively but was never registered
```

This means you need to register your class with Fory. **Do NOT add it to reflect-config.json** - instead, register it with Fory's codegen system:

```java
static {
  fory = Fory.builder().build();
  // register class
  fory.register(MyClass.class);
  // ensure all serializers for registered classes being compiled by fory at graalvm native image build time.
  fory.ensureSerializersCompiled();
}
```

And make sure the class executing static registering is initialized at build time in `native-image.properties`:

```properties
Args = --initialize-at-build-time=com.example.MyForySerializer
```

This approach is much more efficient than reflection-based serialization and eliminates the need for complex GraalVM configuration files.

## Not thread-safe Fory

Example:

```java
import org.apache.fory.Fory;
import org.apache.fory.util.Preconditions;

import java.util.List;
import java.util.Map;

public class Example {
  public record Record (
    int f1,
    String f2,
    List<String> f3,
    Map<String, Long> f4) {
  }

  static Fory fory;

  static {
    fory = Fory.builder().build();
    // register class
    fory.register(Record.class);
    // ensure all serializers for registered classes being compiled by fory at graalvm native image build time.
    fory.ensureSerializersCompiled();
  }

  public static void main(String[] args) {
    Record record = new Record(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    System.out.println(record);
    byte[] bytes = fory.serialize(record);
    Object o = fory.deserialize(bytes);
    System.out.println(o);
    Preconditions.checkArgument(record.equals(o));
  }
}
```

Then add `org.apache.fory.graalvm.Example` build time init to `native-image.properties` configuration:

```properties
Args = --initialize-at-build-time=org.apache.fory.graalvm.Example
```

## Thread-safe Fory

```java
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.util.Preconditions;

import java.util.List;
import java.util.Map;

public class ThreadSafeExample {
  public record Foo (
    int f1,
    String f2,
    List<String> f3,
    Map<String, Long> f4) {
  }

  static ThreadSafeFory fory;

  static {
    fory = new ThreadLocalFory(classLoader -> {
      Fory f = Fory.builder().build();
      // register class
      f.register(Foo.class);
      // ensure all serializers for registered classes being compiled by fory at graalvm native image build time.
      fory.ensureSerializersCompiled();
      return f;
    });
  }

  public static void main(String[] args) {
    System.out.println(fory.deserialize(fory.serialize("abc")));
    System.out.println(fory.deserialize(fory.serialize(List.of(1,2,3))));
    System.out.println(fory.deserialize(fory.serialize(Map.of("k1", 1, "k2", 2))));
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    System.out.println(foo);
    byte[] bytes = fory.serialize(foo);
    Object o = fory.deserialize(bytes);
    System.out.println(o);
  }
}
```

Then add `org.apache.fory.graalvm.ThreadSafeExample` build time init to `native-image.properties` configuration:

```properties
Args = --initialize-at-build-time=org.apache.fory.graalvm.ThreadSafeExample
```

## Framework Integration

For framework developers, if you want to integrate fory for serialization, you can provided a configuration file to let
the users to list all the classes they want to serialize, then you can load those classes and invoke
`org.apache.fory.Fory.register(Class<?>)` to register those classes in your Fory integration class. After all classes are registered, you need to invoke `org.apache.fory.Fory.ensureSerializersCompiled()` to compile serializers at build time, and configure that class be initialized at graalvm native image build time.

## Benchmark

Here we give two class benchmarks between Fory and Graalvm Serialization.

When Fory compression is disabled:

- Struct: Fory is `46x speed, 43% size` compared to JDK.
- Pojo: Fory is `12x speed, 56% size` compared to JDK.

When Fory compression is enabled:

- Struct: Fory is `24x speed, 31% size` compared to JDK.
- Pojo: Fory is `12x speed, 48% size` compared to JDK.

See [[Benchmark.java](https://github.com/apache/fory/blob/main/integration_tests/graalvm_tests/src/main/java/org/apache/fory/graalvm/Benchmark.java)] for benchmark code.

### Struct Benchmark

#### Class Fields

```java
public class Struct implements Serializable {
  public int f1;
  public long f2;
  public float f3;
  public double f4;
  public int f5;
  public long f6;
  public float f7;
  public double f8;
  public int f9;
  public long f10;
  public float f11;
  public double f12;
}
```

#### Benchmark Results

No compression:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Struct
Compress number: false
Fory size: 76.0
JDK size: 178.0
Fory serialization took mills: 49
JDK serialization took mills: 2254
Compare speed: Fory is 45.70x speed of JDK
Compare size: Fory is 0.43x size of JDK
```

Compress number:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Struct
Compress number: true
Fory size: 55.0
JDK size: 178.0
Fory serialization took mills: 130
JDK serialization took mills: 3161
Compare speed: Fory is 24.16x speed of JDK
Compare size: Fory is 0.31x size of JDK
```

### Pojo Benchmark

#### Class Fields

```java
public class Foo implements Serializable {
  int f1;
  String f2;
  List<String> f3;
  Map<String, Long> f4;
}
```

#### Benchmark Results

No compression:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Foo
Compress number: false
Fory size: 541.0
JDK size: 964.0
Fory serialization took mills: 1663
JDK serialization took mills: 16266
Compare speed: Fory is 12.19x speed of JDK
Compare size: Fory is 0.56x size of JDK
```

Compress number:

```
Benchmark repeat number: 400000
Object type: class org.apache.fory.graalvm.Foo
Compress number: true
Fory size: 459.0
JDK size: 964.0
Fory serialization took mills: 1289
JDK serialization took mills: 15069
Compare speed: Fory is 12.11x speed of JDK
Compare size: Fory is 0.48x size of JDK
```
