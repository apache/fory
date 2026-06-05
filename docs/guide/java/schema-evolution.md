---
title: Schema Evolution
sidebar_position: 6
id: schema_evolution
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

This page covers schema evolution, meta sharing, and handling non-existent/unknown classes.

## Handling Class Schema Evolution

In many systems, the schema of a class used for serialization may change over time. For instance, fields within a class may be added or removed. When serialization and deserialization processes use different versions of jars, the schema of the class being deserialized may differ from the one used during serialization.

### Default Mode

Fory defaults to compatible mode in both Java native mode (`xlang=false`) and xlang mode. This default is safer for independently deployed services because writer and reader schemas can diverge during rolling upgrades or across language implementations.

For payloads whose reader and writer schemas never differ, see
[Same-Schema Optimization](#same-schema-optimization).

### Compatible Mode

Compatible mode is enabled by default, so deserialization can tolerate added, removed, or reordered
fields when metadata remains compatible.

In this compatible mode, deserialization can handle schema changes such as missing or extra fields, allowing it to succeed even when the serialization and deserialization processes have different class schemas.

Compatible readers also tolerate selected scalar field type changes when the value is lossless. A
matched field can read between `boolean`, `String`, numeric scalars, and `BigDecimal` when the value
has the same logical value after conversion. For example, `"true"` and `"false"` can be read as
booleans, `"123"` can be read as a numeric field that can hold `123`, numbers and decimals can be
read as canonical strings, and numeric widening or narrowing succeeds only when no precision or range
is lost. Numeric strings use finite ASCII decimal syntax. Nullable and boxed fields still compose with
these conversions, but reference-tracked scalar type changes are incompatible. Invalid strings and
lossy conversions fail during deserialization.

```java
Fory fory = Fory.builder().withXlang(false)
  .build();

byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

This compatible mode involves serializing class metadata into the serialized output. Despite Fory's use of sophisticated compression techniques to minimize overhead, there is still some additional space cost associated with class metadata.

## Meta Sharing

To further reduce metadata costs, Fory introduces a class metadata sharing mechanism, which allows the metadata to be sent to the deserialization process only once.

Fory supports sharing type metadata (class name, field name, final field type information, etc.) between multiple serializations in a context (ex. TCP connection). This information will be sent to the peer during the first serialization in the context. Based on this metadata, the peer can rebuild the same deserializer, which avoids transmitting metadata for subsequent serializations and reduces network traffic pressure while supporting type forward/backward compatibility automatically.

### Using Meta Sharing

```java
// Fory.builder()
//   .withXlang(false)
//   .withRefTracking(false)
//   // share meta across serialization.
//   .withMetaShare(true)

// Not thread-safe fory.
MetaWriteContext writeContext = xxx;
fory.setMetaWriteContext(writeContext);
byte[] bytes = fory.serialize(o);

// Not thread-safe fory.
MetaReadContext readContext = xxx;
fory.setMetaReadContext(readContext);
fory.deserialize(bytes);
```

### Thread-Safe Meta Sharing

```java
// Thread-safe fory
byte[] serialized = fory.execute(
  f -> {
    f.setMetaWriteContext(writeContext);
    return f.serialize(beanA);
  }
);

// Thread-safe fory
Object newObj = fory.execute(
  f -> {
    f.setMetaReadContext(readContext);
    return f.deserialize(serialized);
  }
);
```

**Note**: `MetaWriteContext` and `MetaReadContext` are not thread-safe and cannot be reused across
instances of Fory or multiple threads. In cases of multi-threading, a separate pair of meta
contexts must be created for each Fory instance. If you need a different classloader, create a
separate `Fory` or `ThreadSafeFory` configured with that loader instead of switching loaders on an
existing instance.

For more details, please refer to the [Meta Sharing specification](https://fory.apache.org/docs/specification/java_serialization_spec#meta-share).

## Deserialize Unknown Classes

Fory supports deserializing non-existent or unknown classes. This feature can be enabled by `ForyBuilder#deserializeUnknownClass(true)`.

When enabled and metadata sharing is enabled, Fory will store the deserialized data of this type in a lazy subclass of Map. By using the lazy map implemented by Fory, the rebalance cost of filling map during deserialization can be avoided, which further improves performance.

If this data is sent to another process and the class exists in this process, the data will be deserialized into the object of this type without losing any information.

If metadata sharing is not enabled, the new class data is skipped and Fory returns an `UnknownEmptyStruct` marker object.

## Copy/Map Object from One Type to Another

Fory supports mapping objects from one type to another type.

**Notes:**

1. This mapping will execute a deep copy. All mapped fields are serialized into binary and deserialized from that binary to map into another type.
2. All struct types must be registered with the same ID, otherwise Fory cannot map to the correct struct type. Be careful when you use `Fory#register(Class)`, because Fory will allocate an auto-grown ID which might be inconsistent if you register classes with different order between Fory instances.

```java
public class StructMappingExample {
  static class Struct1 {
    int f1;
    String f2;

    public Struct1(int f1, String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  static class Struct2 {
    int f1;
    String f2;
    double f3;
  }

  static ThreadSafeFory fory1 = Fory.builder().withXlang(false)
    .buildThreadSafeFory();
  static ThreadSafeFory fory2 = Fory.builder().withXlang(false)
    .buildThreadSafeFory();

  static {
    fory1.register(Struct1.class);
    fory2.register(Struct2.class);
  }

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    Struct2 struct2 = (Struct2) fory2.deserialize(fory1.serialize(struct1));
    Assert.assertEquals(struct2.f1, struct1.f1);
    Assert.assertEquals(struct2.f2, struct1.f2);
    struct1 = (Struct1) fory1.deserialize(fory2.serialize(struct2));
    Assert.assertEquals(struct1.f1, struct2.f1);
    Assert.assertEquals(struct1.f2, struct2.f2);
  }
}
```

## Deserialize POJO into Another Type

Fory allows you to serialize one POJO and deserialize it into a different POJO. The different POJO means schema inconsistency, so use compatible mode.

```java
public class DeserializeIntoType {
  static class Struct1 {
    int f1;
    String f2;

    public Struct1(int f1, String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  static class Struct2 {
    int f1;
    String f2;
    double f3;
  }

  static ThreadSafeFory fory = Fory.builder().withXlang(false)
    .buildThreadSafeFory();

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    byte[] data = fory.serialize(struct1);
    Struct2 struct2 = fory.deserialize(data, Struct2.class);
  }
}
```

## Same-Schema Optimization

Use `ForyBuilder#withCompatible(false)` only when the class schema used to deserialize every payload
is always the same as the class schema used to serialize it, and you want faster serialization and smaller size.
For xlang payloads, call `withCompatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .withCompatible(false)
    .build();
```

### Per-Class Opt-Out

`@ForyStruct` can set a per-class evolution policy:

- `Evolution.INHERIT`: follow the Fory instance's compatible/meta-share configuration. This is the
  default.
- `Evolution.ENABLED`: require schema evolution metadata for this class. Registration or type
  resolution fails if the Fory instance cannot emit that metadata.
- `Evolution.DISABLED`: force fixed-schema `STRUCT/NAMED_STRUCT` encoding even when compatible
  metadata is otherwise enabled.

Use `@ForyStruct(evolution = Evolution.DISABLED)` only for same-schema classes. The boolean shorthand
`@ForyStruct(evolving = false)` is also supported as a same-schema opt-out.

```java
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.ForyStruct.Evolution;

@ForyStruct(evolution = Evolution.DISABLED)
public class SameSchemaMessage {
  public int id;
  public String name;
}
```

## Configuration

| Option                    | Description                                                                                        | Default                   |
| ------------------------- | -------------------------------------------------------------------------------------------------- | ------------------------- |
| `compatibleMode`          | Controls whether Fory writes schema evolution metadata; same-schema mode requires matching schemas | `COMPATIBLE`              |
| `checkClassVersion`       | Check schema hashes for same-schema payloads                                                       | `false`                   |
| `metaShareEnabled`        | Enable meta sharing                                                                                | `true` if Compatible mode |
| `scopedMetaShareEnabled`  | Scoped meta share per serialization                                                                | `true` if Compatible mode |
| `deserializeUnknownClass` | Handle non-existent or unknown classes                                                             | `true` if Compatible mode |
| `metaCompressor`          | Compressor for meta compression                                                                    | `DeflaterMetaCompressor`  |

## Best Practices

1. **Use COMPATIBLE mode for evolving schemas**: When classes may change between versions
2. **Enable meta sharing for network communication**: Reduces bandwidth for repeated serializations
3. **Use consistent type IDs for struct mapping**: Ensure same registration order or explicit IDs
4. **Consider space overhead**: Compatible mode adds metadata, balance with your requirements

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Xlang Serialization](xlang-serialization.md) - xlang mode
- [Troubleshooting](troubleshooting.md) - Common schema issues
