---
title: Xlang Serialization
sidebar_position: 2
id: xlang_serialization
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

Apache Fory™ xlang serialization is the Java wire mode for payloads that must be read by Python,
Rust, Go, JavaScript/TypeScript, C++, C#, Swift, Dart, Scala, Kotlin, or another non-Java Fory implementation. Java defaults to
xlang mode with compatible schema evolution, but examples set the mode explicitly so the payload
contract is visible in code.

## Create an Xlang Fory Instance

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

Use [Native Serialization](native-serialization.md) instead when every writer and reader is Java
and the payload should preserve Java-specific object behavior.

## Register Types

Types must be registered with consistent IDs or names across all languages. Fory supports two
registration methods.

### Register by ID (Recommended for Performance)

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

### Register by Name (Recommended for Flexibility)

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

## Java To Python Example

### Java (Serializer)

```java
import org.apache.fory.Fory;

public record Person(String name, int age) {}

public class Example {
  public static void main(String[] args) {
    Fory fory = Fory.builder()
        .withXlang(true)
        .withRefTracking(true)
        .build();

    // Register with the same logical name used by Python.
    fory.register(Person.class, "example.Person");

    Person person = new Person("Bob", 25);
    byte[] bytes = fory.serialize(person);

    // Send bytes to Python by your service transport.
  }
}
```

### Python (Deserializer)

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

person = fory.deserialize(bytes_from_java)
print(f"{person.name}, {person.age}")  # Output: Bob, 25
```

## Handling Circular and Shared References

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

## Type Mapping Considerations

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

### Lists and Dense Arrays

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

### Compatible Types

```java
public record UserData(
    String name,           // compatible
    int age,               // compatible
    List<String> tags,     // compatible
    Map<String, Integer> scores  // compatible
) {}
```

### Problematic Types

```java
public record UserData(
    Optional<String> name,    // not cross-language compatible
    BigDecimal balance,       // limited support
    EnumSet<Status> statuses  // Java-specific collection
) {}
```

## Performance Considerations

Xlang mode has additional overhead compared to Java native mode:

- **Type metadata encoding**: Adds extra bytes per type
- **Type resolution**: Requires name/ID lookup during deserialization

**For best performance**:

- Use **ID-based registration** when possible (smaller encoding)
- **Disable reference tracking** if you don't need circular references (`withRefTracking(false)`)
- **Use native mode** (`withXlang(false)`) when only Java serialization is needed

## Best Practices

1. Use explicit type IDs or namespace/type names for every user type.
2. Keep compatible mode for independently deployed services.
3. Test payloads through every peer before relying on a schema in production.
4. Use native serialization for Java-only traffic that needs Java-specific object behavior.

## Troubleshooting

### "Type not registered" errors

- Verify type is registered with same ID/name on both sides
- Check if type name has typos or case differences

### "Type mismatch" errors

- Ensure field types are compatible across languages
- Review [Type Mapping Guide](../../specification/xlang_type_mapping.md)

### Data corruption or unexpected values

- Verify both sides use xlang payloads
- Ensure both sides have compatible Fory versions

## See Also

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Python Xlang Serialization Guide](../python/xlang-serialization.md)
- [Rust Xlang Serialization Guide](../rust/xlang-serialization.md)

## Related Topics

- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration methods
- [Native Serialization](native-serialization.md) - Java-only serialization features
- [Row Format](row-format.md) - Cross-language row format
