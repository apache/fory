---
title: Object Copy
sidebar_position: 9
id: object_copy
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

This page covers in-memory Java object graph copying with `Fory#copy(Object)`.

`Fory.copy` is a deep-copy operation for Java object graphs. It does not serialize to bytes first.
Instead, it uses the same runtime type system and serializers to create a copied object graph in
memory.

## When to Use Object Copy

Use object copy when you want a detached in-memory clone of an existing Java object graph.

Typical use cases:

- Clone request or response models before mutation
- Duplicate cached state for optimistic updates
- Copy graphs that contain collections, maps, arrays, or nested beans
- Preserve shared references and circular references during cloning

Use serialization instead when you need bytes for transport, storage, or cross-process exchange.

| Operation                | `Fory.copy`         | `serialize` / `deserialize`               |
| ------------------------ | ------------------- | ----------------------------------------- |
| Result                   | Java object graph   | Binary payload plus reconstructed objects |
| Main use                 | In-memory deep copy | Transport, persistence, interoperability  |
| Copy ref option          | `withRefCopy(...)`  | `withRefTracking(...)`                    |
| Cross-language payload   | No                  | Yes, in xlang mode                        |
| Intermediate byte buffer | No                  | Yes                                       |

## Quick Start

For general-purpose object graphs, enable `withRefCopy(true)` so shared references and cycles are
handled correctly:

```java
import org.apache.fory.Fory;
import org.apache.fory.config.Language;

public class Example {
  public static void main(String[] args) {
    Fory fory = Fory.builder()
      .withLanguage(Language.JAVA)
      .withRefCopy(true)
      .build();

    Order original = new Order();
    Order copied = fory.copy(original);
  }
}
```

`copy(null)` returns `null`.

## Reference Semantics

The most important copy option is `ForyBuilder#withRefCopy(boolean)`.

### `withRefCopy(true)`

This is the safe default for general object graphs. Shared references remain shared in the copied
graph, and circular references can be copied correctly.

```java
import org.apache.fory.Fory;
import org.apache.fory.config.Language;

public class Example {
  static final class Address {
    String city;
  }

  static final class Pair {
    Address left;
    Address right;
  }

  public static void main(String[] args) {
    Fory fory = Fory.builder()
      .withLanguage(Language.JAVA)
      .withRefCopy(true)
      .build();

    Address address = new Address();
    address.city = "Shanghai";

    Pair pair = new Pair();
    pair.left = address;
    pair.right = address;

    Pair copied = fory.copy(pair);
    System.out.println(copied.left == copied.right); // true
  }
}
```

### `withRefCopy(false)`

Disable copy ref tracking only when you know the graph is tree-like and does not rely on shared or
cyclic references. This can be faster, but repeated references are copied into different objects.

```java
import org.apache.fory.Fory;
import org.apache.fory.config.Language;

public class Example {
  static final class Address {
    String city;
  }

  static final class Pair {
    Address left;
    Address right;
  }

  public static void main(String[] args) {
    Fory fory = Fory.builder()
      .withLanguage(Language.JAVA)
      .withRefCopy(false)
      .build();

    Address address = new Address();
    Pair pair = new Pair();
    pair.left = address;
    pair.right = address;

    Pair copied = fory.copy(pair);
    System.out.println(copied.left == copied.right); // false
  }
}
```

If you disable `withRefCopy` and the graph contains a cycle, copy can fail with stack overflow.

## `withRefCopy` vs `withRefTracking`

These two options control different operations:

- `withRefCopy(true)` affects `Fory.copy(...)`
- `withRefTracking(true)` affects serialization and deserialization

Enabling one does not automatically enable the other. If your application both serializes and
copies graphs with shared or circular references, configure both options explicitly.

```java
Fory fory = Fory.builder()
  .withRefTracking(true)
  .withRefCopy(true)
  .build();
```

## Immutable vs Mutable Values

Fory may reuse the original instance for immutable values. For mutable values, it creates a new
object graph.

In practice, this means:

- `String`, boxed primitives, enums, and many immutable JDK value types may be returned as-is
- Primitive arrays, string arrays, collections, maps, beans, dates, and other mutable structures
  are copied into distinct objects

Do not use object identity alone to decide whether copy succeeded. Use the mutability contract of
the value you are copying.

## Class Registration

If class registration is required, register copied classes before calling `copy`.

```java
import org.apache.fory.Fory;

public class Example {
  public static void main(String[] args) {
    Fory fory = Fory.builder()
      .requireClassRegistration(true)
      .withRefCopy(true)
      .build();

    fory.register(Order.class);
    Order copied = fory.copy(new Order());
  }
}
```

This follows the same registration rules as the rest of the runtime: if the runtime requires class
registration, copied runtime types must be registered first.

## Thread-Safe Copy

`ThreadSafeFory` also supports `copy(...)`.

For general multi-threaded usage:

```java
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.Language;

public class Example {
  public static void main(String[] args) {
    ThreadSafeFory fory = Fory.builder()
      .withLanguage(Language.JAVA)
      .withRefCopy(true)
      .buildThreadSafeFory();

    Order copied = fory.copy(new Order());
  }
}
```

The same API also works for `buildThreadLocalFory()` and `buildThreadSafeForyPool(poolSize)`.

## Built-In Coverage

Fory already provides copy support for many common Java runtime types, including:

- Primitive values and boxed primitives
- Strings and primitive arrays
- Common JDK collections and maps
- Java time and date/time values
- Beans, records, and nested object graphs

If the runtime already knows how to serialize a mutable type, it may still need an explicit copy
implementation in that serializer. For mutable serializers, the default `Serializer.copy(...)`
throws `UnsupportedOperationException` unless the serializer overrides it.

## Custom Copy with `ForyCopyable`

If a type needs custom copy logic, implement `ForyCopyable<T>`.

This is the simplest approach when the class itself should control how nested fields are copied:

```java
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.ForyCopyable;
import org.apache.fory.context.CopyContext;

public final class Node implements ForyCopyable<Node> {
  private String name;
  private final List<Node> neighbors = new ArrayList<>();

  @Override
  public Node copy(CopyContext copyContext) {
    Node copied = new Node();
    copyContext.reference(this, copied);
    copied.name = name;
    for (Node neighbor : neighbors) {
      copied.neighbors.add(copyContext.copyObject(neighbor));
    }
    return copied;
  }
}
```

Guidelines:

- Call `copyContext.reference(origin, copy)` immediately after creating a composite mutable object
  if the type can participate in cycles or shared-reference graphs
- Use `copyContext.copyObject(...)` for nested values instead of manually duplicating nested copy
  logic
- Keep copy logic consistent with the normal runtime semantics of the type

## Custom Copy in a Serializer

When a type already uses a custom serializer, override `Serializer.copy(...)` for mutable values.

```java
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.serializer.Serializer;

public final class EnvelopeSerializer extends Serializer<Envelope> {
  public EnvelopeSerializer(Config config) {
    super(config, Envelope.class);
  }

  @Override
  public Envelope copy(CopyContext copyContext, Envelope value) {
    Envelope copied = new Envelope();
    copyContext.reference(value, copied);
    copied.header = copyContext.copyObject(value.header);
    copied.payload = copyContext.copyObject(value.payload);
    return copied;
  }

  @Override
  public void write(WriteContext writeContext, Envelope value) {
    throw new UnsupportedOperationException("omitted");
  }

  @Override
  public Envelope read(ReadContext readContext) {
    throw new UnsupportedOperationException("omitted");
  }
}
```

Use this approach when copy behavior belongs with a serializer rather than the domain class.

## Best Practices

- Reuse `Fory` or `ThreadSafeFory` instances instead of rebuilding them for each copy
- Enable `withRefCopy(true)` unless you are certain the graph is acyclic and does not rely on
  shared references
- Treat `withRefCopy(false)` as a performance optimization for tree-like data, not as a default
- Test custom copy implementations with both shared-reference and cyclic graphs
- Keep mutable custom serializer copy paths explicit and do not rely on fallback behavior

## Troubleshooting

### Stack Overflow or Copy Failure on Cyclic Graphs

If copy fails on a cyclic object graph, enable `withRefCopy(true)`:

```java
Fory fory = Fory.builder()
  .withRefCopy(true)
  .build();
```

Disabling copy ref tracking is only safe for acyclic graphs.

### Shared References Are Not Preserved

If the same source object is copied into multiple distinct target objects, `withRefCopy` is
disabled. Turn it on:

```java
Fory fory = Fory.builder()
  .withRefCopy(true)
  .build();
```

`withRefTracking(true)` alone does not change `Fory.copy(...)` behavior.

### `Copy for ... is not supported`

This means the mutable serializer for that type does not implement `copy(...)`.

Fix it by either:

- Implementing `ForyCopyable<T>` on the class, or
- Overriding `Serializer.copy(CopyContext, T)` in the registered serializer

### Registration Errors

If your runtime uses `requireClassRegistration(true)`, make sure the copied runtime types are
registered before calling `copy(...)`.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Runtime creation and core APIs
- [Configuration](configuration.md) - Builder options including `withRefCopy`
- [Custom Serializers](custom-serializers.md) - Serializer design and registration
- [Virtual Threads](virtual-threads.md) - Thread-safe runtime guidance
