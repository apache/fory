---
title: Cross-Language Serialization Guide
sidebar_position: 0
id: serialization_index
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

Apache Fory™ xlang (cross-language) serialization enables seamless data exchange between different programming languages. Serialize data in one language and deserialize it in another without manual data conversion. You can use either direct native types (no IDL) or a schema-first Fory IDL workflow.

## Features

- **No IDL Required**: Serialize objects directly with native language types
- **Multi-Language Support**: Java, Python, C++, Go, Rust, JavaScript all interoperate seamlessly
- **Reference Support**: Shared and circular references work across language boundaries
- **Schema Evolution**: Forward/backward compatibility when class definitions change
- **Zero-Copy**: Out-of-band serialization for large binary data
- **High Performance**: JIT compilation and optimized binary protocol

## Supported Languages

| Language   | Status | Package                          |
| ---------- | ------ | -------------------------------- |
| Java       | ✅     | `org.apache.fory:fory-core`      |
| Python     | ✅     | `pyfory`                         |
| C++        | ✅     | Bazel/CMake build                |
| Go         | ✅     | `github.com/apache/fory/go/fory` |
| Rust       | ✅     | `fory` crate                     |
| JavaScript | ✅     | `@apache-fory/fory`              |

## When to Use Xlang Mode

**Use xlang mode when:**

- Building multi-language microservices
- Creating polyglot data pipelines
- Sharing data between frontend (JavaScript) and backend (Java/Python/Go)

**Use language-native mode when:**

- All serialization/deserialization happens in the same language
- Maximum performance is required (native mode is faster)
- You need language-specific features (Python pickle compatibility, Java serialization hooks)

## Quick Example

### Java (Producer)

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public class Person {
    public String name;
    public int age;
}

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .build();
fory.register(Person.class, "example.Person");

Person person = new Person();
person.name = "Alice";
person.age = 30;
byte[] bytes = fory.serialize(person);
// Send bytes to Python, Go, Rust, etc.
```

### Python (Consumer)

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: pyfory.Int32Type

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, typename="example.Person")

# Receive bytes from Java
person = fory.deserialize(bytes_from_java)
print(f"{person.name}, {person.age}")  # Alice, 30
```

## Fory IDL

For schema-first projects, Fory also provides **Fory IDL** and code generation.

- Compiler docs: [Fory IDL Overview](../../compiler/index.md)
- Best for large multi-language message contracts and long-lived schemas

### Minimal IDL Example

Create `person.fdl`:

```protobuf
package example;

message Person {
    string name = 1;
    int32 age = 2;
    optional string email = 3;
}
```

Generate code:

```bash
foryc person.fdl --lang java,python,go,rust,cpp --output ./generated
```

This generates native language types with consistent field/type mappings across all targets.

## When to Fory IDL

| Option                             | Use When                                                               | Why                                                                              |
| ---------------------------------- | ---------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Native xlang types (no IDL)        | You only have a few message types and want to move quickly             | Avoids the integration/setup cost of introducing and operating the compiler      |
| Fory IDL (schema-first + codegen)  | You have many messages across multiple languages/teams/services        | Provides a single contract, stronger consistency, and easier long-term evolution |
| Hybrid (start native, move to IDL) | Project starts small but message count and cross-team dependency grows | Lets you keep early velocity, then standardize once schema complexity increases  |

## Documentation

| Topic                                                     | Description                                      |
| --------------------------------------------------------- | ------------------------------------------------ |
| [Getting Started](getting-started.md)                     | Installation and basic setup for all languages   |
| [Type Mapping](../../specification/xlang_type_mapping.md) | Cross-language type mapping reference            |
| [Serialization](serialization.md)                         | Built-in types, custom types, reference handling |
| [Zero-Copy](zero-copy.md)                                 | Out-of-band serialization for large data         |
| [Row Format](row_format.md)                               | Cache-friendly binary format with random access  |
| [Troubleshooting](troubleshooting.md)                     | Common issues and solutions                      |

## Language-Specific Guides

For language-specific details and API reference:

- [Java Cross-Language Guide](../java/cross-language.md)
- [Python Cross-Language Guide](../python/cross-language.md)
- [C++ Cross-Language Guide](../cpp/cross-language.md)
- [Rust Cross-Language Guide](../rust/cross-language.md)

## Specifications

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md) - Binary protocol details
- [Type Mapping Specification](../../specification/xlang_type_mapping.md) - Complete type mapping reference
