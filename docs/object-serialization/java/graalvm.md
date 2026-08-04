---
title: GraalVM Native Image
sidebar_position: 15
id: graalvm
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
