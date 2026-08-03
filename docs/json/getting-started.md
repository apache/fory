---
title: Getting Started
sidebar_position: 2
id: getting-started
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

## Requirements and installation

Fory JSON supports Java 8 and later on standard JDKs, GraalVM native images, and Android. Java
records are supported on Java 17 and later.

Fory JSON is available from Maven Central.

Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-json</artifactId>
  <version>1.5.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("org.apache.fory:fory-json:1.5.0")
```

Use the same version for every Fory module in one application.

### JDK 25 and later

On JDK 25 and later, open `java.lang.invoke` to Fory core. For a classpath application:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

For a module-path application:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

The JPMS module name of Fory JSON is `org.apache.fory.json`.

## Quick start

Create one `ForyJson` instance and reuse it. The instance is thread-safe and has no close lifecycle.

```java
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.ForyJson;

public final class JsonExample {
  private static final ForyJson JSON = ForyJson.builder().build();

  public static final class User {
    public long id;
    public String name;

    public User() {}

    User(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static void main(String[] args) {
    User input = new User(7, "Alice");

    String text = JSON.toJson(input);
    byte[] utf8 = JSON.toJsonBytes(input);

    User fromText = JSON.fromJson(text, User.class);
    User fromUtf8 = JSON.fromJson(utf8, User.class);

    System.out.println(text);
    System.out.println(new String(utf8, StandardCharsets.UTF_8));
    System.out.println(fromText.name + " / " + fromUtf8.name);
  }
}
```

Unknown input properties are skipped unless a read-enabled Any field or any-setter receives them.
Null object properties are omitted by default. Default JSON property discovery order is not a
compatibility contract; use `JsonPropertyOrder` or `JsonProperty.index` when emitted property order
must be explicit.

## Reading and writing APIs

Fory JSON supports String input/output and UTF-8 byte input/output. It does not currently provide an
`InputStream` parsing API.

| Operation            | Runtime type              | Declared `Class`                | Declared `TypeRef`                 |
| -------------------- | ------------------------- | ------------------------------- | ---------------------------------- |
| String output        | `toJson(value)`           | `toJson(value, type)`           | `toJson(value, typeRef)`           |
| UTF-8 bytes          | `toJsonBytes(value)`      | `toJsonBytes(value, type)`      | `toJsonBytes(value, typeRef)`      |
| UTF-8 `OutputStream` | `writeJsonTo(value, out)` | `writeJsonTo(value, type, out)` | `writeJsonTo(value, typeRef, out)` |
| String input         | -                         | `fromJson(text, type)`          | `fromJson(text, typeRef)`          |
| UTF-8 input          | -                         | `fromJson(bytes, type)`         | `fromJson(bytes, typeRef)`         |

Every `fromJson` call consumes exactly one JSON value and rejects trailing non-whitespace content.
Returned Strings and byte arrays are detached from internal reusable buffers.

`writeJsonTo` buffers the complete UTF-8 document, performs one `OutputStream.write`, and neither
flushes nor closes the caller-owned stream. It is an output convenience API, not incremental JSON
streaming. I/O failures are wrapped in `ForyJsonException`.

### Generic types

Use `TypeRef` whenever a root type contains generic arguments:

```java
import java.util.List;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;

ForyJson json = ForyJson.builder().build();
TypeRef<List<User>> usersType = new TypeRef<List<User>>() {};

List<User> users = json.fromJson("[{\"id\":7,\"name\":\"Alice\"}]", usersType);
String encoded = json.toJson(users, usersType);
```

Declared writes require a fully bound type. Wildcards and type variables are rejected. A non-null
value must be assignable to the declared raw type.

The declared schema controls serialization. For example, a property declared as a concrete parent
class uses the parent's mapped properties rather than automatically adding subclass-only fields. A
declared `Object` value uses runtime dispatch when writing and natural JSON mapping when reading.

### Declared types and polymorphism

The no-type write overloads dispatch from the runtime class. Use a declared-type overload when a
base type owns `JsonSubTypes` metadata:

```java
Shape shape = new Circle(2);

json.toJson(shape);              // Circle's concrete representation
json.toJson(shape, Shape.class); // Shape's configured subtype representation
json.toJsonBytes(shape, Shape.class);
json.writeJsonTo(shape, Shape.class, outputStream);
```

For containers of polymorphic values, carry the declared base type in `TypeRef`:

```java
TypeRef<List<Shape>> shapesType = new TypeRef<List<Shape>>() {};
String encoded = json.toJson(shapes, shapesType);
```
