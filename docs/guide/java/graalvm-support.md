---
title: GraalVM Support
sidebar_position: 14
id: graalvm_support
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

GraalVM Native Image compiles Java applications ahead of time. Because a native image cannot
discover every reflective access or generate serializers at runtime, Fory prepares serializers and
the required metadata while the image is built.

`fory-core` contains Fory's GraalVM Feature and activates it automatically. Applications do not need
an additional Fory artifact or a `--features` option.

## How It Works

Prepare each Fory instance during build-time class initialization:

1. Store the Fory instance in a static field.
2. Register every application class that the native executable will serialize.
3. Call `fory.ensureSerializersCompiled()` after registration is complete.
4. Configure the owning class for build-time initialization.

The Feature uses those registrations to provide the Native Image metadata required by Fory,
including metadata for private constructors, records, serializer constructors, and registered proxy
shapes. Application classes still need to be registered with Fory before serializers are compiled.

Fory disables asynchronous serializer compilation in a native image because runtime just-in-time
compilation is unavailable.

## Fory JSON

Fory JSON uses a separate Native Image workflow. Add the Fory annotation processor to the
application compiler path:

```xml
<annotationProcessorPaths>
  <path>
    <groupId>org.apache.fory</groupId>
    <artifactId>fory-annotation-processor</artifactId>
    <version>${fory.version}</version>
  </path>
</annotationProcessorPaths>
```

Then add `@JsonType` to each concrete object model that the native executable reads or writes:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonType;

@JsonType
public final class User {
  public long id;
  public String name;
}

public class JsonExample {
  public static void main(String[] args) {
    ForyJson json = ForyJson.builder().build();
    User user = json.fromJson("{\"id\":1,\"name\":\"Ada\"}", User.class);
    System.out.println(json.toJson(user));
  }
}
```

The processor generates direct property and creator operations. The `fory-json` artifact activates
its Native Image Feature automatically and retains the generated factories and required model
metadata. `@JsonType` is not inherited, so annotate every concrete runtime model. An annotated base
with a class-literal `@JsonSubTypes` table registers those listed subtypes automatically, but each
concrete object subtype needs its own direct `@JsonType` to receive generated operations. Reachable
concrete `Collection` and `Map` root types are also supported when they
have the public no-argument constructor required by Fory JSON. Reachable `@JsonCodec` declarations
register their codec constructor even when the declaration target is not an object model. A class
referenced only by a runtime string is not reachable; `JsonSubTypes.Type.className` is therefore
unsupported in a native image.

Native execution uses Fory JSON's interpreted readers and writers with the generated property and
creator operations. `ForyJson.builder()` automatically
disables runtime code generation and asynchronous compilation in the native executable, while all
other builder options retain their normal behavior. Applications can create differently configured
`ForyJson` instances at runtime and do not need build-time initialization or reflection
configuration.

Type, field, effective ordinary getter, setter value parameter, and `JsonCreator` parameter
`@JsonCodec` annotations are supported. The Feature registers every selected complete-value,
element, content, Map-key, and Map-value codec constructor. This is the same annotation model used
on the JVM and Android.

`JsonValue` fields and effective public zero-argument methods are supported, including matching
one-String `JsonCreator` constructors and public static factories. Fixed `JsonRawValue` fields and
getters support trusted raw String values, and fixed `JsonBase64` fields and getters support Base64
`byte[]` values as on the JVM. Annotate each reachable owning model with `JsonType` so Native Image
retains these members and the Base64 codec constructor. A directly annotated `JsonValue` Record
uses its generated component accessor and canonical constructor operations.

`JsonAnyProperty` and `JsonAnyGetter` flatten their Map into the enclosing object. Use
`@JsonCodec(valueCodec = ...)` on that field or getter to customize each dynamic value. A second
`JsonAnySetter` parameter may use the normal configuration for its own value shape.

`JsonUnwrapped` uses the same interpreted behavior as on the JVM. Annotate the containing model and
every unwrapped child or intermediate object with `JsonType` so each model receives its generated
property and creator operations.

Child codecs act on one direct level. `elementCodec` supports `Collection`, Java arrays, and
`AtomicReferenceArray`; `contentCodec` supports `Optional` and `AtomicReference`; `keyCodec` and
`valueCodec` support Map keys and values. A complete `value` codec cannot be combined with a child
codec.

An annotation codec must have the same public no-argument constructor required on the JVM. In a
named module, export or open its package to `org.apache.fory.json`. A codec instance supplied
through `registerCodec` is constructed by the application and needs no annotation-constructor
metadata.

## Basic Usage

### Create Fory and Register Classes

```java
import org.apache.fory.Fory;

public class Example {
  private static final Fory FORY;

  static {
    FORY = Fory.builder().withXlang(false).build();
    FORY.register(MyClass.class);
    FORY.register(AnotherClass.class);
    FORY.ensureSerializersCompiled();
  }

  public static void main(String[] args) {
    byte[] bytes = FORY.serialize(new MyClass());
    MyClass obj = (MyClass) FORY.deserialize(bytes);
  }
}
```

### Configure Build-Time Initialization

Create `resources/META-INF/native-image/your-group/your-artifact/native-image.properties`:

```properties
Args = --initialize-at-build-time=com.example.Example
```

## Registered Classes

During the native-image build, Fory automatically registers the metadata needed for registered
classes, including:

- Classes with private constructors
- Private nested classes and records
- Serializer constructors
- Dynamic proxy shapes registered through `GraalvmSupport`

For Fory, your application metadata only needs to configure its build-time initialized bootstrap
class, for example:

```properties
Args = --initialize-at-build-time=com.example.Example
```

### Example with Private Record

```java
import org.apache.fory.Fory;

public class Example {
  private record PrivateRecord(int id, String name) {}

  private static final Fory FORY;

  static {
    FORY = Fory.builder().withXlang(false).build();
    FORY.register(PrivateRecord.class);
    FORY.ensureSerializersCompiled();
  }
}
```

### Example with Dynamic Proxy

```java
import org.apache.fory.Fory;
import org.apache.fory.platform.GraalvmSupport;

public class ProxyExample {
  public interface MyService {
    String execute();
  }

  public interface Audited {
    String traceId();
  }

  private static final Fory FORY;

  static {
    FORY = Fory.builder().withXlang(false).build();
    GraalvmSupport.registerProxySupport(MyService.class, Audited.class);
    FORY.ensureSerializersCompiled();
  }
}
```

Use `registerProxySupport(MyService.class)` for a single-interface proxy. For proxies that implement
multiple interfaces, pass the full interface list in the same order used to create the proxy. Call
this method before `ensureSerializersCompiled()`.

## Thread-Safe Fory

For multi-threaded applications, use `ThreadLocalFory`:

```java
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;

public class ThreadSafeExample {
  public record Foo(int f1, String f2, List<String> f3) {}

  private static final ThreadSafeFory FORY;

  static {
    FORY =
        new ThreadLocalFory(
            builder -> {
              Fory f = builder.build();
              f.register(Foo.class);
              f.ensureSerializersCompiled();
              return f;
            });
  }

  public static void main(String[] args) {
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"));
    byte[] bytes = FORY.serialize(foo);
    Foo result = (Foo) FORY.deserialize(bytes);
  }
}
```

## Troubleshooting

### "Type is instantiated reflectively but was never registered"

If you see this error:

```
Type com.example.MyClass is instantiated reflectively but was never registered
```

Register the class before compiling serializers:

```java
fory.register(MyClass.class);
fory.ensureSerializersCompiled();
```

If registration is conditional, make sure the same branch runs during build-time initialization.

## Framework Integration

For framework developers integrating Fory:

1. Provide a configuration file for users to list serializable classes.
2. Load those classes and call `fory.register(Class<?>)` for each.
3. Call `fory.ensureSerializersCompiled()` after all registrations.
4. Configure your integration class for build-time initialization.

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
