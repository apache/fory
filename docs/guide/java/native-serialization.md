---
title: Native Serialization
sidebar_position: 3
id: native_serialization
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

Java native serialization is the Java-only wire format selected with `withXlang(false)`. Use it
when every writer and reader is a Java/JVM process and the payload should follow the JVM type system
instead of the portable xlang type system. Native serialization is the right starting point for
Java/JVM-only replacements of JDK serialization, Kryo, FST, Hessian, or Java-only Protocol Buffers
payloads.

Native serialization in this page means Fory's `xlang=false` wire mode. It is separate from GraalVM
native image support, which is covered in [GraalVM Support](graalvm-support.md).

Use [Xlang Serialization](xlang-serialization.md), the default Java mode, when bytes must be read by
non-Java Fory runtimes.

## When To Use Native Serialization

Use native serialization when:

- A payload is produced and consumed only by Java/JVM applications.
- The object model uses Java-specific types, JDK collections, wrapper types, inheritance,
  interfaces, or polymorphism that do not need a cross-language schema.
- Existing classes rely on JDK serialization hooks such as `writeObject`, `readObject`,
  `writeReplace`, `readResolve`, `readObjectNoData`, or `Externalizable`.
- You need Java object copy through `Fory.copy(...)`.
- Large primitive arrays or binary payloads should use native-mode out-of-band buffers.
- You are replacing Java-only serialization frameworks and want the broadest Java object surface.

Use xlang serialization instead when the payload must be read by Python, C++, Go,
Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin, or another
non-Java runtime.

## Create a Native Runtime

```java
import org.apache.fory.Fory;

Fory fory = Fory.builder()
    .withXlang(false)
    .requireClassRegistration(true)
    .withRefTracking(true)
    .build();

byte[] bytes = fory.serialize(object);
Object decoded = fory.deserialize(bytes);
```

Create and reuse a `Fory` or `ThreadSafeFory` instance for each configuration. Fory creation is not
cheap because the runtime caches class metadata, serializers, and generated code.

```java
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

ThreadSafeFory fory = Fory.builder()
    .withXlang(false)
    .requireClassRegistration(true)
    .withRefTracking(true)
    .buildThreadSafeFory();

fory.register(Order.class, 100);
```

Register classes and serializers during startup before concurrent serialization starts. Use a
separate runtime when class loader, registration, security, schema evolution, or reference-tracking
settings differ.

## Schema Evolution

Native serialization defaults to schema-consistent mode. In schema-consistent mode, writer and
reader classes are expected to have the same schema. This is the most direct native-mode path and is
the right default for lockstep deployments.

Enable compatible mode when Java classes can evolve independently across writer and reader
deployments:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .withCompatible(true)
    .build();
```

Compatible mode lets readers tolerate added, removed, or reordered fields when the schema metadata
remains compatible. It also enables metadata sharing by default. See [Schema Evolution](schema-evolution.md)
for field IDs, class version checks, meta sharing, and unknown-class handling.

## Registration And Security

Class registration is enabled by default. Keep it enabled for service boundaries and register
application classes explicitly:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .requireClassRegistration(true)
    .build();

fory.register(Order.class, 100);
fory.register(LineItem.class, 101);
```

Explicit numeric IDs avoid registration-order drift. If you use `fory.register(MyClass.class)`
without an ID, every writer and reader must register classes in the same order. Name-based
registration is also available when type ID coordination is harder:

```java
fory.register(Order.class, "com.example", "Order");
```

Disable class registration only in trusted environments. If you need dynamic class loading, install
a `TypeChecker` or `AllowListChecker` so deserialization can reject unexpected classes:

```java
import org.apache.fory.Fory;
import org.apache.fory.resolver.AllowListChecker;

AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
checker.allowClass("com.example.*");

Fory fory = Fory.builder()
    .withXlang(false)
    .requireClassRegistration(false)
    .withTypeChecker(checker)
    .withMaxDepth(100)
    .build();
```

Use `withMaxDepth(...)` to cap object graph depth for untrusted or externally supplied payloads.
See [Type Registration](type-registration.md) for the full security configuration.

## Java Object Surface

Native serialization owns the Java-specific object surface:

- POJOs, records, enums, primitive arrays, object arrays, and common JDK collections.
- Inheritance, interfaces, polymorphic fields, shared references, and circular object graphs.
- Java wrapper and collection behavior that does not have to map to a portable xlang type.
- JDK serialization hooks for classes that require Java serialization compatibility.
- Custom serializers registered with `registerSerializer(...)` or `registerSerializerAndType(...)`.

For ordinary application classes, Fory can use generated serializers and avoid JDK
`ObjectOutputStream` semantics. Classes that require JDK serialization hooks may use the Java
serialization-compatible path; prefer a Fory custom serializer for hot classes when the hook-based
path is too expensive.

## JDK Serialization Hooks

Java native mode supports the JDK serialization hooks that are part of many existing Java object
models:

- `writeObject` and `readObject`
- `writeReplace` and `readResolve`
- `readObjectNoData`
- `Externalizable`

```java
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class MyClass implements Serializable {
  private void writeObject(ObjectOutputStream out) throws IOException {
    // Custom serialization logic.
  }

  private void readObject(ObjectInputStream in) throws IOException {
    // Custom deserialization logic.
  }

  private Object writeReplace() {
    return this;
  }

  private Object readResolve() {
    return this;
  }
}
```

Fory native payloads are not JDK `ObjectOutputStream` payloads. The hooks are honored for
Java-object compatibility, but new payloads should be written and read by Fory.

## Migrating From Java Serialization Frameworks

When replacing JDK serialization, Kryo, FST, Hessian, or a Java-only Protocol Buffers pipeline:

1. Start with `.withXlang(false)` because the data is Java-only.
2. Keep `requireClassRegistration(true)` and register application classes with explicit IDs.
3. Use `.withCompatible(true)` if writer and reader deployments roll independently.
4. Enable `.withRefTracking(true)` only when identity or circular references matter.
5. Add custom serializers for hot classes that would otherwise use expensive JDK serialization hooks.
6. Keep old and new byte streams separated when possible.

When an application must read data that may be either JDK `ObjectOutputStream` bytes or Fory
native-mode bytes, `JavaSerializer.serializedByJDK` can identify the JDK payload before falling
back to Fory:

```java
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import org.apache.fory.serializer.JavaSerializer;

if (JavaSerializer.serializedByJDK(bytes)) {
  ObjectInputStream objectInputStream = new ObjectInputStream(new ByteArrayInputStream(bytes));
  return objectInputStream.readObject();
}
return fory.deserialize(bytes);
```

Use this bridge only at boundaries that actually accept both formats. Native-mode Fory payloads
should otherwise be written and read by Fory directly.

## Object Graphs And Reference Tracking

Native mode supports shared references and circular references when reference tracking is enabled:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .withRefTracking(true)
    .build();
```

Disable reference tracking only for value-shaped graphs where identity and cycles are not part of
the data model:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .withRefTracking(false)
    .build();
```

Reference tracking is a semantic choice. Turning it off can improve performance and reduce payload
size, but repeated references deserialize as distinct objects and cycles are unsupported.

## Object Copy

Fory can deep-copy Java objects without materializing a byte array. For full copy semantics, custom
copy hooks, and troubleshooting, see [Object Copy](object-copy.md).

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .withRefCopy(true)
    .build();

MyClass copy = fory.copy(original);
```

`withRefCopy(true)` controls reference preservation for copy operations. It is separate from
`withRefTracking(...)`, which controls serialization and deserialization.

## Zero-Copy Serialization

Native mode supports out-of-band `BufferObject` payloads for large binary values and primitive
arrays:

```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.BufferObject;

Fory fory = Fory.builder()
    .withXlang(false)
    .build();

List<Object> value = Arrays.asList("str", new byte[1000], new int[100], new double[100]);
Collection<BufferObject> bufferObjects = new ArrayList<>();
byte[] bytes = fory.serialize(value, bufferObject -> !bufferObjects.add(bufferObject));
List<MemoryBuffer> buffers = bufferObjects.stream()
    .map(BufferObject::toBuffer)
    .collect(Collectors.toList());

Object decoded = fory.deserialize(bytes, buffers);
```

The callback returns `false` for buffers that should be sent out-of-band. The main byte array still
contains the root object graph and references the buffers in callback order.

Use this when the transport can carry the main payload and buffers separately. If the stream is
stored or sent as one byte array, omit the callback and let Fory keep buffer contents in-band.

Native serialization also supports byte arrays, `MemoryBuffer`, `ByteBuffer`, `OutputStream`,
`ForyInputStream`, and `ForyReadableChannel` APIs. Choose the API that matches the boundary you
already own; avoid copying through `byte[]` when a buffer or stream is already available.

## Class Loaders

```java
ClassLoader loader = Thread.currentThread().getContextClassLoader();

Fory fory = Fory.builder()
    .withXlang(false)
    .withClassLoader(loader)
    .build();
```

Each `Fory` instance is tied to one class loader because class metadata and serializers are cached.
Build a separate runtime for each application, plugin, or tenant class loader instead of switching
loaders on an existing runtime.

## Performance Guidelines

- Reuse `Fory` or `ThreadSafeFory` instances instead of rebuilding them per request.
- Register classes with explicit numeric IDs for compact type metadata and stable deployments.
- Keep schema-consistent mode for lockstep Java services; enable compatible mode only when schema
  evolution requires it.
- Disable reference tracking for value-shaped graphs with no identity or cycles.
- Use async compilation on ordinary JVMs when startup latency can tolerate interpreter-first
  serialization:

  ```java
  Fory fory = Fory.builder()
      .withXlang(false)
      .withAsyncCompilation(true)
      .build();
  ```

- Keep runtime code generation enabled on ordinary JVMs. Use static generated serializers for
  GraalVM native image and Android flows.
- Use zero-copy out-of-band buffers for large primitive arrays or binary fields when the transport
  supports split payloads.
- Replace expensive JDK serialization hooks with Fory custom serializers for hot classes when the
  object contract allows it.

## Native And Xlang Comparison

| Requirement                                 | Use native serialization | Use xlang serialization |
| ------------------------------------------- | ------------------------ | ----------------------- |
| Java/JVM-only payloads                      | Yes                      | Optional                |
| Non-Java readers or writers                 | No                       | Yes                     |
| Broad Java object surface                   | Yes                      | Limited to xlang types  |
| JDK serialization hooks                     | Yes                      | No                      |
| Java object copy                            | Yes                      | No                      |
| Portable type mapping across runtimes       | No                       | Yes                     |
| Compatible schema evolution by default      | No                       | Yes                     |
| Schema-consistent same-language performance | Yes                      | No                      |

## Troubleshooting

### A non-Java runtime cannot read the payload

The writer is using native serialization. Rebuild the writer with `.withXlang(true)` and align type
registration with every peer runtime.

### A class is rejected during deserialization

Keep class registration enabled and register the class on both writer and reader. If dynamic class
loading is intentional, use `requireClassRegistration(false)` only with an allow-listing
`TypeChecker`.

### A rolling deployment fails after a field change

Native serialization defaults to schema-consistent mode. Use `.withCompatible(true)` when writer and
reader versions can differ, and add stable field metadata for long-lived schemas.

### Object identity is not preserved

Enable `.withRefTracking(true)` for serialization and deserialization. For `Fory.copy(...)`, enable
`.withRefCopy(true)`.

### A migrated boundary receives both JDK and Fory bytes

Use `JavaSerializer.serializedByJDK(...)` only at the mixed-format boundary, then route JDK bytes to
`ObjectInputStream` and Fory native bytes to `fory.deserialize(...)`.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Xlang-first Java quickstart
- [Xlang Serialization](xlang-serialization.md) - Cross-runtime Java payloads
- [Configuration](configuration.md) - Java builder options
- [Schema Evolution](schema-evolution.md) - Compatible and schema-consistent mode
- [Type Registration](type-registration.md) - Registration and security
- [Object Copy](object-copy.md) - Deep-copy semantics
- [Custom Serializers](custom-serializers.md) - Custom Java serializers
- [Static Generated Serializers](static-generated-serializers.md) - Build-time generated serializers
- [GraalVM Support](graalvm-support.md) - Native-image platform support
