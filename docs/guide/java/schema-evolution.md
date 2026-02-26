---
title: Schema Evolution
sidebar_position: 5
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

### Default Mode: SCHEMA_CONSISTENT

By default, Fory serializes objects using the `CompatibleMode.SCHEMA_CONSISTENT` mode. This mode assumes that the deserialization process uses the same class schema as the serialization process, minimizing payload overhead. However, if there is a schema inconsistency, deserialization will fail.

### Compatible Mode

If the schema is expected to change, to make deserialization succeed (i.e., schema forward/backward compatibility), users must configure Fory to use `CompatibleMode.COMPATIBLE`. This can be done using the `ForyBuilder#withCompatibleMode(CompatibleMode.COMPATIBLE)` method.

In this compatible mode, deserialization can handle schema changes such as missing or extra fields, allowing it to succeed even when the serialization and deserialization processes have different class schemas.

```java
Fory fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .build();

byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

This compatible mode involves serializing class metadata into the serialized output. Despite Fory's use of sophisticated compression techniques to minimize overhead, there is still some additional space cost associated with class metadata.

### Disable Evolution for Stable Classes

If a class schema is stable and will not change, you can opt out of schema evolution on a per-class basis to avoid compatible metadata overhead. Annotate the class with `@ForyObject(evolving = false)` to force `STRUCT/NAMED_STRUCT` type IDs even when Compatible mode is enabled.

```java
import org.apache.fory.annotation.ForyObject;

@ForyObject(evolving = false)
public class StableMessage {
  public int id;
  public String name;
}
```

## Meta Sharing

To further reduce metadata costs, Fory introduces a class metadata sharing mechanism, which allows the metadata to be sent to the deserialization process only once.

Fory supports sharing type metadata (class name, field name, final field type information, etc.) between multiple serializations in a context (ex. TCP connection). This information will be sent to the peer during the first serialization in the context. Based on this metadata, the peer can rebuild the same deserializer, which avoids transmitting metadata for subsequent serializations and reduces network traffic pressure while supporting type forward/backward compatibility automatically.

### Using Meta Sharing

```java
// Fory.builder()
//   .withLanguage(Language.JAVA)
//   .withRefTracking(false)
//   // share meta across serialization.
//   .withMetaShare(true)

// Not thread-safe fory.
MetaContext context = xxx;
fory.getSerializationContext().setMetaContext(context);
byte[] bytes = fory.serialize(o);

// Not thread-safe fory.
MetaContext context = xxx;
fory.getSerializationContext().setMetaContext(context);
fory.deserialize(bytes);
```

### Thread-Safe Meta Sharing

```java
// Thread-safe fory
fory.setClassLoader(beanA.getClass().getClassLoader());
byte[] serialized = fory.execute(
  f -> {
    f.getSerializationContext().setMetaContext(context);
    return f.serialize(beanA);
  }
);

// Thread-safe fory
fory.setClassLoader(beanA.getClass().getClassLoader());
Object newObj = fory.execute(
  f -> {
    f.getSerializationContext().setMetaContext(context);
    return f.deserialize(serialized);
  }
);
```

**Note**: `MetaContext` is not thread-safe and cannot be reused across instances of Fory or multiple threads. In cases of multi-threading, a separate `MetaContext` must be created for each Fory instance.

For more details, please refer to the [Meta Sharing specification](https://fory.apache.org/docs/specification/fory_java_serialization_spec#meta-share).

## Deserialize Unknown Classes

Fory supports deserializing non-existent or unknown classes. This feature can be enabled by `ForyBuilder#deserializeUnknownClass(true)`.

When enabled and metadata sharing is enabled, Fory will store the deserialized data of this type in a lazy subclass of Map. By using the lazy map implemented by Fory, the rebalance cost of filling map during deserialization can be avoided, which further improves performance.

If this data is sent to another process and the class exists in this process, the data will be deserialized into the object of this type without losing any information.

If metadata sharing is not enabled, the new class data will be skipped and a `UnknownSkipClass` stub object will be returned.

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

  static ThreadSafeFory fory1 = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();
  static ThreadSafeFory fory2 = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();

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

Fory allows you to serialize one POJO and deserialize it into a different POJO. The different POJO means schema inconsistency. Users must configure Fory with `CompatibleMode` set to `org.apache.fory.config.CompatibleMode.COMPATIBLE`.

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

  static ThreadSafeFory fory = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    byte[] data = fory.serializeJavaObject(struct1);
    Struct2 struct2 = (Struct2) fory.deserializeJavaObject(bytes, Struct2.class);
  }
}
```

## Configuration Options

| Option                    | Description                            | Default                   |
| ------------------------- | -------------------------------------- | ------------------------- |
| `compatibleMode`          | `SCHEMA_CONSISTENT` or `COMPATIBLE`    | `SCHEMA_CONSISTENT`       |
| `checkClassVersion`       | Check class schema consistency         | `false`                   |
| `metaShareEnabled`        | Enable meta sharing                    | `true` if Compatible mode |
| `scopedMetaShareEnabled`  | Scoped meta share per serialization    | `true` if Compatible mode |
| `deserializeUnknownClass` | Handle non-existent or unknown classes | `true` if Compatible mode |
| `metaCompressor`          | Compressor for meta compression        | `DeflaterMetaCompressor`  |

## Best Practices

1. **Use COMPATIBLE mode for evolving schemas**: When classes may change between versions
2. **Enable meta sharing for network communication**: Reduces bandwidth for repeated serializations
3. **Use consistent type IDs for struct mapping**: Ensure same registration order or explicit IDs
4. **Consider space overhead**: Compatible mode adds metadata, balance with your requirements

## Related Topics

- [Configuration Options](configuration.md) - All ForyBuilder options
- [Cross-Language Serialization](cross-language.md) - XLANG mode
- [Troubleshooting](troubleshooting.md) - Common schema issues
