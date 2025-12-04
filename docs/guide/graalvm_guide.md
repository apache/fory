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

GraalVM `native image` compiles Java code into native executables ahead-of-time, resulting in faster startup and lower memory usage. However, native images don't support runtime JIT compilation or reflection without explicit configuration.

Apache Fory™ works excellently with GraalVM native image by using **codegen instead of reflection**. All serializer code is generated at build time, eliminating the need for reflection configuration files in most cases.

## How It Works

Fory generates serialization code at GraalVM build time when you:

1. Create Fory as a **static** field
2. **Register** all classes in a static initializer
3. Call `fory.ensureSerializersCompiled()` to compile serializers
4. Configure the class to initialize at build time via `native-image.properties`

**The main benefit**: You don't need to configure [reflection json](https://www.graalvm.org/latest/reference-manual/native-image/metadata/#specifying-reflection-metadata-in-json) or [serialization json](https://www.graalvm.org/latest/reference-manual/native-image/metadata/#serialization) for most serializable classes.

Note: Fory's `asyncCompilationEnabled` option is automatically disabled for GraalVM native image since runtime JIT is not supported.

## Basic Usage

### Step 1: Create Fory and Register Classes

```java
import org.apache.fory.Fory;

public class Example {
  // Must be static field
  static Fory fory;

  static {
    fory = Fory.builder().build();
    fory.register(MyClass.class);
    fory.register(AnotherClass.class);
    // Compile all serializers at build time
    fory.ensureSerializersCompiled();
  }

  public static void main(String[] args) {
    byte[] bytes = fory.serialize(new MyClass());
    MyClass obj = (MyClass) fory.deserialize(bytes);
  }
}
```

### Step 2: Configure Build-Time Initialization

Create `resources/META-INF/native-image/your-group/your-artifact/native-image.properties`:

```properties
Args = --initialize-at-build-time=com.example.Example
```

## ForyGraalVMFeature (Optional)

For most types with public constructors, the basic setup above is sufficient. However, some advanced cases require reflection registration:

- **Private constructors** (classes without accessible no-arg constructor)
- **Private inner classes/records**
- **Dynamic proxy serialization**

The `fory-graalvm-feature` module automatically handles these cases, eliminating the need for manual `reflect-config.json` configuration.

### Adding the Dependency

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-graalvm-feature</artifactId>
  <version>${fory.version}</version>
</dependency>
```

### Enabling the Feature

Add to your `native-image.properties`:

```properties
Args = --initialize-at-build-time=com.example.Example \
       --features=org.apache.fory.graalvm.feature.ForyGraalVMFeature
```

### What ForyGraalVMFeature Handles

| Scenario                        | Without Feature              | With Feature       |
| ------------------------------- | ---------------------------- | ------------------ |
| Public classes with no-arg ctor | ✅ Works                     | ✅ Works           |
| Private constructors            | ❌ Needs reflect-config.json | ✅ Auto-registered |
| Private inner records           | ❌ Needs reflect-config.json | ✅ Auto-registered |
| Dynamic proxies                 | ❌ Needs manual config       | ✅ Auto-registered |

### Example with Private Record

```java
public class Example {
  // Private inner record - requires ForyGraalVMFeature
  private record PrivateRecord(int id, String name) {}

  static Fory fory;

  static {
    fory = Fory.builder().build();
    fory.register(PrivateRecord.class);
    fory.ensureSerializersCompiled();
  }
}
```

### Example with Dynamic Proxy

```java
import org.apache.fory.util.GraalvmSupport;

public class ProxyExample {
  public interface MyService {
    String execute();
  }

  static Fory fory;

  static {
    fory = Fory.builder().build();
    // Register proxy interface for serialization
    GraalvmSupport.registerProxySupport(MyService.class);
    fory.ensureSerializersCompiled();
  }
}
```

## Thread-Safe Fory

For multi-threaded applications, use `ThreadLocalFory`:

```java
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;

public class ThreadSafeExample {
  public record Foo(int f1, String f2, List<String> f3) {}

  static ThreadSafeFory fory;

  static {
    fory = new ThreadLocalFory(classLoader -> {
      Fory f = Fory.builder().build();
      f.register(Foo.class);
      f.ensureSerializersCompiled();
      return f;
    });
  }

  public static void main(String[] args) {
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"));
    byte[] bytes = fory.serialize(foo);
    Foo result = (Foo) fory.deserialize(bytes);
  }
}
```

## Troubleshooting

### "Type is instantiated reflectively but was never registered"

If you see this error:

```
Type com.example.MyClass is instantiated reflectively but was never registered
```

**Solution**: Register the class with Fory (don't add to reflect-config.json):

```java
fory.register(MyClass.class);
fory.ensureSerializersCompiled();
```

If the class has a private constructor, either:

1. Add `fory-graalvm-feature` dependency, or
2. Create a `reflect-config.json` for that specific class

## Framework Integration

For framework developers integrating Fory:

1. Provide a configuration file for users to list serializable classes
2. Load those classes and call `fory.register(Class<?>)` for each
3. Call `fory.ensureSerializersCompiled()` after all registrations
4. Configure your integration class for build-time initialization

## Benchmark

Performance comparison between Fory and GraalVM JDK Serialization:

| Type   | Compression | Speed      | Size |
| ------ | ----------- | ---------- | ---- |
| Struct | Off         | 46x faster | 43%  |
| Struct | On          | 24x faster | 31%  |
| Pojo   | Off         | 12x faster | 56%  |
| Pojo   | On          | 12x faster | 48%  |

See [Benchmark.java](https://github.com/apache/fory/blob/main/integration_tests/graalvm_tests/src/main/java/org/apache/fory/graalvm/Benchmark.java) for benchmark code.

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
