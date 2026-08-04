---
title: Basic Serialization
sidebar_position: 1
id: basic-serialization
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
field metadata. The shared rules live in [Cross-Language Interoperability](../xlang.md), and the Java-specific
interoperability requirements are covered below.

## Use Native Serialization For Java-Only Traffic

For same-language Java/JVM traffic, native mode is usually the better fit:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .build();
```

Native mode supports the broad Java object serialization surface, including JDK serialization hooks,
object copy, and native-mode zero-copy buffers. See [Native Serialization](native.md).

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

## Cross-Language Interoperability

The default xlang format is shared by all supported Fory implementations. The following sections cover its cross-language type mapping, type identity, and interoperability requirements.

Apache Fory™ xlang serialization is the Java wire mode for payloads that must be read by Python,
Rust, Go, JavaScript/TypeScript, C++, C#, Swift, Dart, Scala, Kotlin, or another non-Java Fory implementation. Java defaults to
xlang mode with compatible schema evolution, but examples set the mode explicitly so the payload
contract is visible in code.

### Xlang Configuration

Use one long-lived `Fory` or `ThreadSafeFory` instance per configuration. Creating a Fory instance is
expensive because Fory caches type metadata and generated serializers.

```java
import org.apache.fory.Fory;

Fory fory = Fory.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .withRefTracking(true)
    .build();
```

`withRefTracking(true)` is required only when the cross-language data model includes shared object
identity or cycles. Disable it for value-shaped schemas.

Use [Native Serialization](native.md) instead when every writer and reader is Java
and the payload should preserve Java-specific object behavior.

### Register Types

Types must be registered with consistent IDs or names across all languages. Fory supports two
registration methods.

#### Register by ID (Recommended for Performance)

```java
public record Person(String name, int age) {}

// Numeric ID registration is compact and fast.
fory.register(Person.class, 1);

Person person = new Person("Alice", 30);
byte[] bytes = fory.serialize(person);
// bytes can be deserialized by Python, Rust, Go, etc.
```

Benefits: faster serialization and smaller binary size.

Trade-off: every service must coordinate IDs so the same logical type uses the same number.

#### Register by Name (Recommended for Flexibility)

```java
public record Person(String name, int age) {}

// Namespace/type-name registration is easier to coordinate across teams.
fory.register(Person.class, "example", "Person");

Person person = new Person("Alice", 30);
byte[] bytes = fory.serialize(person);
// bytes can be deserialized by Python, Rust, Go, etc.
```

Benefits: less risk of numeric ID conflicts and easier management across independently owned
services.

Trade-off: the payload includes string identity, so it is larger than ID-based registration.

The Java API also supports a single string type name, such as
`fory.register(Person.class, "example.Person")`. Use the same logical identity on every peer.

### Java To Python Example

#### Java (Serializer)

```java
import org.apache.fory.Fory;
import java.nio.file.Files;
import java.nio.file.Path;

public record Person(String name, int age) {}

public class Example {
  public static void main(String[] args) throws Exception {
    Fory fory = Fory.builder()
        .withXlang(true)
        .withRefTracking(true)
        .build();

    // Register with the same logical name used by Python.
    fory.register(Person.class, "example.Person");

    Person person = new Person("Bob", 25);
    byte[] bytes = fory.serialize(person);
    Files.write(Path.of("person.bin"), bytes);
  }
}
```

#### Python (Deserializer)

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True, ref=True)

# Register with the same name as Java.
fory.register_type(Person, name="example.Person")

with open("person.bin", "rb") as input_file:
    person = fory.deserialize(input_file.read())
print(f"{person.name}, {person.age}")  # Output: Bob, 25
```

### Handling Circular and Shared References

Xlang mode supports circular and shared references when reference tracking is enabled:

```java
public class Node {
  public String value;
  public Node next;
  public Node parent;
}

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)
    .build();

fory.register(Node.class, "example.Node");

Node node1 = new Node();
node1.value = "A";
Node node2 = new Node();
node2.value = "B";
node1.next = node2;
node2.parent = node1;

byte[] bytes = fory.serialize(node1);
// Python/Rust/Go can correctly deserialize this with circular references preserved
```

### Type Mapping Considerations

Not all Java types have equivalents in other languages. When using xlang mode:

- Use primitive types (`int`, `long`, `double`, `String`) for maximum compatibility.
- Use standard collections (`List`, `Map`, `Set`) instead of language-specific collections.
- Use reduced-precision carriers (`Float16`, `BFloat16`, `Float16List`, `BFloat16List`) for
  16-bit float payloads.
- Treat `Float16[]`, `BFloat16[]`, `Float16List`, and `BFloat16List` as `list<T>` carriers by
  default; use `@ArrayType` when the schema must be `array<float16>` or `array<bfloat16>`.
- Avoid Java-specific types like `Optional`, `BigDecimal`, and `EnumSet` unless every target language
  has an agreed mapping.
- See [Type Mapping Guide](../../specification/xlang_type_mapping.md) for the complete
  compatibility matrix.

#### Lists and Dense Arrays

Java primitive arrays are dense `array<T>` carriers, except plain `byte[]`,
which defaults to `bytes`. General Java collections and Fory primitive-list
carriers such as `Int32List`, `Float16List`, and `BFloat16List` use
`list<T>` unless the field has explicit `@ArrayType` metadata.

| Fory schema       | Java field shape                           |
| ----------------- | ------------------------------------------ |
| `list<int32>`     | `List<Integer>` or `Int32List`             |
| `array<bool>`     | `boolean[]`                                |
| `array<int8>`     | `@Int8Type byte[]` type-use                |
| `array<int16>`    | `short[]`                                  |
| `array<int32>`    | `int[]`                                    |
| `array<int64>`    | `long[]`                                   |
| `array<uint8>`    | `@UInt8Type byte[]` type-use               |
| `array<uint16>`   | `@UInt16Type short[]` type-use             |
| `array<uint32>`   | `@UInt32Type int[]` type-use               |
| `array<uint64>`   | `@UInt64Type long[]` type-use              |
| `array<float16>`  | `Float16Array` or `@Float16Type short[]`   |
| `array<bfloat16>` | `BFloat16Array` or `@BFloat16Type short[]` |
| `array<float32>`  | `float[]`                                  |
| `array<float64>`  | `double[]`                                 |

Prefer type-use syntax for primitive-array annotations:

```java
private @UInt32Type int[] ids;
private @BFloat16Type short[] values;
```

#### Compatible Types

```java
public record UserData(
    String name,           // compatible
    int age,               // compatible
    List<String> tags,     // compatible
    Map<String, Integer> scores  // compatible
) {}
```

#### Problematic Types

```java
public record UserData(
    Optional<String> name,    // not cross-language compatible
    BigDecimal balance,       // limited support
    EnumSet<Status> statuses  // Java-specific collection
) {}
```

### Performance Considerations

Xlang mode has additional overhead compared to Java native mode:

- **Type metadata encoding**: Adds extra bytes per type
- **Type resolution**: Requires name/ID lookup during deserialization

**For best performance**:

- Use **ID-based registration** when possible (smaller encoding)
- **Disable reference tracking** if you don't need circular references (`withRefTracking(false)`)
- **Use native mode** (`withXlang(false)`) when only Java serialization is needed

### Interoperability Best Practices

1. Use explicit type IDs or namespace/type names for every user type.
2. Keep compatible mode for independently deployed services.
3. Test payloads through every peer before relying on a schema in production.
4. Use native serialization for Java-only traffic that needs Java-specific object behavior.

### Interoperability Troubleshooting

#### "Type not registered" errors

- Verify type is registered with same ID/name on both sides
- Check if type name has typos or case differences

#### "Type mismatch" errors

- Ensure field types are compatible across languages
- Review [Type Mapping Guide](../../specification/xlang_type_mapping.md)

#### Data corruption or unexpected values

- Verify both sides use xlang payloads
- Ensure both sides have compatible Fory versions

### Specifications and References

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Python Interoperability Guide](../python/basic-serialization.md#cross-language-interoperability)
- [Rust Interoperability Guide](../rust/basic-serialization.md#cross-language-interoperability)

### Related Guides

- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration methods
- [Native Serialization](native.md) - Java-only serialization features
- [Row Format](../../row-format/java.md) - Cross-language row format

### Built-in values

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

import java.util.*;

public class Example1 {
  public static void main(String[] args) {
    Fory fory = Fory.builder().withXlang(true).build();
    List<Object> list = ofArrayList(true, false, "str", -1.1, 1, new int[100], new double[20]);
    byte[] bytes = fory.serialize(list);
    // bytes can be deserialized by other languages
    fory.deserialize(bytes);
    Map<Object, Object> map = new HashMap<>();
    map.put("k1", "v1");
    map.put("k2", list);
    map.put("k3", -1);
    bytes = fory.serialize(map);
    // bytes can be deserialized by other languages
    fory.deserialize(bytes);
  }
}
```

### Custom values

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import java.util.*;

public class Example2 {
  public static class SomeClass1 {
    Object f1;
    Map<Byte, Integer> f2;
  }

  public static class SomeClass2 {
    Object f1;
    String f2;
    List<Object> f3;
    Map<Byte, Integer> f4;
    Byte f5;
    Short f6;
    Integer f7;
    Long f8;
    Float f9;
    Double f10;
    short[] f11;
    List<Short> f12;
  }

  public static Object createObject() {
    SomeClass1 obj1 = new SomeClass1();
    obj1.f1 = true;
    obj1.f2 = ofHashMap((byte) -1, 2);
    SomeClass2 obj = new SomeClass2();
    obj.f1 = obj1;
    obj.f2 = "abc";
    obj.f3 = ofArrayList("abc", "abc");
    obj.f4 = ofHashMap((byte) 1, 2);
    obj.f5 = Byte.MAX_VALUE;
    obj.f6 = Short.MAX_VALUE;
    obj.f7 = Integer.MAX_VALUE;
    obj.f8 = Long.MAX_VALUE;
    obj.f9 = 1.0f / 2;
    obj.f10 = 1 / 3.0;
    obj.f11 = new short[]{(short) 1, (short) 2};
    obj.f12 = ofArrayList((short) -1, (short) 4);
    return obj;
  }

  // mvn exec:java -Dexec.mainClass="org.apache.fory.examples.Example2"
  public static void main(String[] args) {
    Fory fory = Fory.builder().withXlang(true).build();
    fory.register(SomeClass1.class, "example.SomeClass1");
    fory.register(SomeClass2.class, "example.SomeClass2");
    byte[] bytes = fory.serialize(createObject());
    // bytes can be deserialized by other languages
    System.out.println(fory.deserialize(bytes));
  }
}
```

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Native Serialization](native.md) - Java-only serialization features
- [Schema Metadata](schema-metadata.md) - Field IDs, nullability, reference tracking, and enum IDs
- [Troubleshooting](troubleshooting.md) - Common API usage issues
