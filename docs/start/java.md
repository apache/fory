---
title: Java Setup
sidebar_position: 1
id: java
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

Fory Java provides binary Object Serialization, Fory JSON, Row Format, generated
models, and Fory gRPC. Artifacts are published to Maven Central. Fory core and
Fory JSON support Java 8 and later, Java Records require Java 17 or later, and
Row Format requires Java 11 or later. Keep every Fory artifact in one application
on the same version.

## Verify the Toolchain

```bash
java -version
mvn -version
# or: ./gradlew --version
```

## Object Serialization

Use Object Serialization for object graphs. Xlang mode produces data that other
Fory implementations in other languages can read; native mode supports a broader JVM object surface.

Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.7.1</version>
</dependency>
```

Gradle:

```kotlin
implementation("org.apache.fory:fory-core:1.7.1")
```

Run this complete xlang round trip:

```java
import org.apache.fory.Fory;

public final class ForyExample {
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
    Fory fory = Fory.builder().withXlang(true).build();
    fory.register(User.class, 1);

    byte[] bytes = fory.serialize(new User(1, "Alice"));
    User decoded = (User) fory.deserialize(bytes);
    System.out.println(decoded.name);
  }
}
```

Reuse a `Fory` instance within one thread instead of rebuilding it for every
value. `Fory` is not thread-safe; use `ThreadSafeFory` for shared concurrent
access. Continue with
[Java Object Serialization](../object-serialization/java/index.md),
[xlang mode](../object-serialization/java/basic-serialization.md#cross-language-interoperability),
[native mode](../object-serialization/java/native.md), or
[configuration](../object-serialization/java/configuration.md).

## Fory JSON

Fory JSON maps Java objects to standard JSON text and UTF-8 bytes. Add
`fory-json` instead of `fory-core` when the application only needs JSON:

```kotlin
implementation("org.apache.fory:fory-json:1.7.1")
```

Add the import to `ForyExample.java`:

```java
import org.apache.fory.json.ForyJson;
```

Then place the JSON round trip inside `ForyExample.main`:

```java
ForyJson json = ForyJson.builder().build();
String text = json.toJson(new User(1, "Alice"));
User jsonDecoded = json.fromJson(text, User.class);
System.out.println(jsonDecoded.name);
```

See [Fory JSON Getting Started](../json/getting-started.md) for Maven setup,
object mapping, annotations, Android, GraalVM, and security.

## Other Capabilities

- **Row Format** provides random and partial field access for trusted analytical data. See [Java Row Format](../row-format/java.md).
- **Fory IDL and Compiler** generates Java models and registration helpers from Fory IDL, protobuf IDL, or FlatBuffers IDL. See [Compiler Getting Started](../compiler/getting-started.md) and the [Java generated-code guide](../compiler/generated-code/java.md).
- **Fory gRPC** uses normal grpc-java transports with Fory-encoded request and response objects. See [Java gRPC](../grpc/java.md).

## Platform Notes

- On JDK 25 and later, follow the setup in
  [Java Object Serialization](../object-serialization/java/index.md).
- For Android, see [Java Android support](../object-serialization/java/android.md).
- For native images, see [Java GraalVM support](../object-serialization/java/graalvm.md).
