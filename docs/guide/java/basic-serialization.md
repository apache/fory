---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page covers the Java xlang quickstart. Xlang mode is the default Java wire format and is the
right first choice for cross-language payloads.

## Create a Fory Instance

For a single-threaded xlang Fory instance, set the mode explicitly:

```java
import org.apache.fory.Fory;

Fory fory = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .build();
```

For a thread-safe Fory instance, build `ThreadSafeFory` from the same builder:

```java
import org.apache.fory.ThreadSafeFory;

ThreadSafeFory fory = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory();
```

Default Java xlang mode also defaults to compatible schema mode, so independently deployed services
can add and remove fields when their schema metadata remains compatible. Use
`withCompatible(false)` only when every reader and writer always uses the same schema and you want
faster serialization and smaller size. Use the `compatible=false` opt-out only after verifying that every language uses the same xlang schema, or when native types are generated from Fory schema IDL.

## Register Custom Types

Register application classes with the same type identity on every peer. Numeric IDs are compact and
fast, while name registration is easier to coordinate across independently owned services.

```java
import org.apache.fory.annotation.ForyField;

public class User {
  @ForyField(id = 0)
  public String name;

  @ForyField(id = 1)
  public int age;
}

Fory fory = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .build();

fory.register(User.class, "example", "User");
```

Use field IDs for long-lived schemas so field identity is stable even if Java field names change.
See [Schema Metadata](schema-metadata.md) for Java annotations, nullability, reference tracking, and
enum metadata.

## Serialize And Deserialize

```java
User user = new User();
user.name = "Alice";
user.age = 30;

byte[] bytes = fory.serialize(user);
User decoded = fory.deserialize(bytes, User.class);
```

When xlang bytes cross languages, every peer must register the same type identity and compatible
field metadata. The shared rules live in [Xlang](../xlang/index.md), while Java-specific API calls
are in [Xlang Serialization](xlang-serialization.md).

## Use Native Serialization For Java-Only Traffic

For same-language Java/JVM traffic, native mode is usually the better fit:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .build();
```

Native mode supports the broad Java object serialization surface, including JDK serialization hooks,
object copy, and native-mode zero-copy buffers. See [Native Serialization](native-serialization.md).

## Common Options

- `withRefTracking(true)` preserves shared references and circular references.
- `requireClassRegistration(true)` keeps the default registered-type policy.
- Compatible mode is enabled by default for native-mode and xlang payloads. Use
  `withCompatible(false)` only when every reader and writer uses the same schema and you want faster
  serialization and smaller size. For xlang payloads, use the `compatible=false` opt-out only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.
- `withAsyncCompilation(true)` enables asynchronous serializer compilation where supported.

## Best Practices

1. **Reuse Fory instances**: Creating Fory is expensive, always reuse instances
2. **Use appropriate thread safety**: Choose between single-thread and thread-safe based on your needs
3. **Register classes**: Keep type identity stable across every xlang peer
4. **Configure reference tracking**: Enable it only when the object graph needs identity or cycles

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Native Serialization](native-serialization.md) - Java-only serialization features
- [Schema Metadata](schema-metadata.md) - Field IDs, nullability, reference tracking, and enum IDs
- [Xlang Serialization](xlang-serialization.md) - Java xlang interoperability
- [Troubleshooting](troubleshooting.md) - Common API usage issues
