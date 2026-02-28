---
title: Cross-Language Serialization
sidebar_position: 8
id: cross_language
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

Apache Fory™ supports seamless data exchange between Java and other languages (Python, Rust, Go, JavaScript, etc.) through the xlang serialization format. This enables multi-language microservices, polyglot data pipelines, and cross-platform data sharing.

## Enable Cross-Language Mode

To serialize data for consumption by other languages, use `Language.XLANG` mode:

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

// Create Fory instance with XLANG mode
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)  // Enable reference tracking for complex graphs
    .build();
```

## Register Types for Cross-Language Compatibility

Types must be registered with **consistent IDs or names** across all languages. Fory supports two registration methods:

### Register by ID (Recommended for Performance)

```java
public record Person(String name, int age) {}

// Register with numeric ID - faster and more compact
fory.register(Person.class, 1);

Person person = new Person("Alice", 30);
byte[] bytes = fory.serialize(person);
// bytes can be deserialized by Python, Rust, Go, etc.
```

**Benefits**: Faster serialization, smaller binary size
**Trade-offs**: Requires coordination to avoid ID conflicts across teams/services

### Register by Name (Recommended for Flexibility)

```java
public record Person(String name, int age) {}

// Register with string name - more flexible
fory.register(Person.class, "example.Person");

Person person = new Person("Alice", 30);
byte[] bytes = fory.serialize(person);
// bytes can be deserialized by Python, Rust, Go, etc.
```

**Benefits**: Less prone to conflicts, easier management across teams, no coordination needed
**Trade-offs**: Slightly larger binary size due to string encoding

## Cross-Language Example: Java ↔ Python

### Java (Serializer)

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public record Person(String name, int age) {}

public class Example {
    public static void main(String[] args) {
        Fory fory = Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .build();

        // Register with consistent name
        fory.register(Person.class, "example.Person");

        Person person = new Person("Bob", 25);
        byte[] bytes = fory.serialize(person);

        // Send bytes to Python service via network/file/queue
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
    age: pyfory.int32

# Create Fory in xlang mode
fory = pyfory.Fory(xlang=True, ref=True)

# Register with the SAME name as Java
fory.register_type(Person, typename="example.Person")

# Deserialize bytes from Java
person = fory.deserialize(bytes_from_java)
print(f"{person.name}, {person.age}")  # Output: Bob, 25
```

## Handling Circular and Shared References

Cross-language mode supports circular and shared references when reference tracking is enabled:

```java
public class Node {
    public String value;
    public Node next;
    public Node parent;
}

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)  // Required for circular references
    .build();

fory.register(Node.class, "example.Node");

// Create circular reference
Node node1 = new Node();
node1.value = "A";
Node node2 = new Node();
node2.value = "B";
node1.next = node2;
node2.parent = node1;  // Circular reference

byte[] bytes = fory.serialize(node1);
// Python/Rust/Go can correctly deserialize this with circular references preserved
```

## Type Mapping Considerations

Not all Java types have equivalents in other languages. When using xlang mode:

- Use **primitive types** (`int`, `long`, `double`, `String`) for maximum compatibility
- Use **standard collections** (`List`, `Map`, `Set`) instead of language-specific ones
- Avoid **Java-specific types** like `Optional`, `BigDecimal` (unless the target language supports them)
- See [Type Mapping Guide](../../specification/xlang_type_mapping.md) for complete compatibility matrix

### Compatible Types

```java
public record UserData(
    String name,           // ✅ Compatible
    int age,               // ✅ Compatible
    List<String> tags,     // ✅ Compatible
    Map<String, Integer> scores  // ✅ Compatible
) {}
```

### Problematic Types

```java
public record UserData(
    Optional<String> name,    // ❌ Not cross-language compatible
    BigDecimal balance,       // ❌ Limited support
    EnumSet<Status> statuses  // ❌ Java-specific collection
) {}
```

## Performance Considerations

Cross-language mode has additional overhead compared to Java-only mode:

- **Type metadata encoding**: Adds extra bytes per type
- **Type resolution**: Requires name/ID lookup during deserialization

**For best performance**:

- Use **ID-based registration** when possible (smaller encoding)
- **Disable reference tracking** if you don't need circular references (`withRefTracking(false)`)
- **Use Java mode** (`Language.JAVA`) when only Java serialization is needed

## Cross-Language Best Practices

1. **Consistent Registration**: Ensure all services register types with identical IDs/names
2. **Version Compatibility**: Use compatible mode for schema evolution across services

## Troubleshooting Cross-Language Serialization

### "Type not registered" errors

- Verify type is registered with same ID/name on both sides
- Check if type name has typos or case differences

### "Type mismatch" errors

- Ensure field types are compatible across languages
- Review [Type Mapping Guide](../../specification/xlang_type_mapping.md)

### Data corruption or unexpected values

- Verify both sides use `Language.XLANG` mode
- Ensure both sides have compatible Fory versions

## See Also

- [Cross-Language Serialization Specification](../../specification/xlang_serialization_spec.md)
- [Type Mapping Reference](../../specification/xlang_type_mapping.md)
- [Python Cross-Language Guide](../python/cross-language.md)
- [Rust Cross-Language Guide](../rust/cross-language.md)

## Related Topics

- [Schema Evolution](schema-evolution.md) - Compatible mode
- [Type Registration](type-registration.md) - Registration methods
- [Row Format](row-format.md) - Cross-language row format
