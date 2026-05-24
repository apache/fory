---
title: Xlang Serialization Guide
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

Apache Fory™ xlang serialization is the default wire format for cross-language payloads. Serialize
data in one language and deserialize it in another without manual conversion. You can use direct
language model types for small contracts, or use Fory IDL and code generation when a schema-first
workflow is a better fit.

## Features

- **No IDL required**: Serialize objects directly with language model types.
- **Multi-language support**: Java, Python, C++, Go, Rust,
  JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin interoperate through
  the same xlang format.
- **Reference support**: Shared and circular references work across language boundaries when reference tracking is enabled in each runtime.
- **Schema evolution**: Compatible mode is the xlang default so readers can tolerate added, removed, or reordered fields.
- **Out-of-band buffers**: Language runtimes can expose zero-copy buffer paths for large binary data.
- **High performance**: Runtimes use generated serializers, JIT serializers, or optimized code paths where available.

## Supported Languages

| Language              | Status    | Package or target                |
| --------------------- | --------- | -------------------------------- |
| Java                  | Supported | `org.apache.fory:fory-core`      |
| Python                | Supported | `pyfory`                         |
| C++                   | Supported | Bazel/CMake build                |
| Go                    | Supported | `github.com/apache/fory/go/fory` |
| Rust                  | Supported | `fory` crate                     |
| JavaScript/TypeScript | Supported | `@apache-fory/core`              |
| C#                    | Supported | `Apache.Fory`                    |
| Swift                 | Supported | Swift Package Manager target     |
| Dart                  | Supported | `fory` package                   |
| Scala                 | Supported | `org.apache.fory:fory-scala`     |
| Kotlin                | Supported | `org.apache.fory:fory-kotlin`    |

## When to Use Xlang Mode

Use xlang mode when:

- Building multi-language microservices
- Creating polyglot data pipelines
- Sharing data between frontend JavaScript/TypeScript and backend runtimes such
  as Java, Python, Go, C#, Scala, or Kotlin

Use native mode for same-language traffic in Java, Scala, Kotlin, Python, C++,
Go, or Rust:

- All serialization/deserialization happens in the same language
- You need language-specific features such as Python pickle-style objects or Java serialization hooks
- You want native-mode schema-consistent payloads for same-language services

## Quick Example

### Java (Producer)

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

public class Person {
    public String name;
    public int age;
}

Fory fory = Fory.builder().withXlang(true).build();
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
    age: pyfory.Int32

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
foryc person.fdl --lang java,python,cpp,go,rust,javascript,csharp,swift,dart,scala,kotlin --output ./generated
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
| [Type Mapping](../../specification/xlang_type_mapping.md) | Xlang type mapping reference                     |
| [Serialization](serialization.md)                         | Built-in types, custom types, reference handling |
| [Zero-Copy](zero-copy.md)                                 | Out-of-band serialization for large data         |
| [Row Format](row_format.md)                               | Cache-friendly binary format with random access  |
| [Troubleshooting](troubleshooting.md)                     | Common issues and solutions                      |

## Language-Specific Guides

For language-specific details and API reference:

- [Java Xlang Serialization Guide](../java/xlang-serialization.md)
- [Python Xlang Serialization Guide](../python/xlang-serialization.md)
- [C++ Xlang Serialization Guide](../cpp/xlang-serialization.md)
- [Go Xlang Serialization Guide](../go/xlang-serialization.md)
- [Rust Xlang Serialization Guide](../rust/xlang-serialization.md)
- [JavaScript/TypeScript Xlang Serialization Guide](../javascript/xlang-serialization.md)
- [C# Xlang Serialization Guide](../csharp/xlang-serialization.md)
- [Swift Xlang Serialization Guide](../swift/xlang-serialization.md)
- [Dart Xlang Serialization Guide](../dart/xlang-serialization.md)
- [Scala Schema IDL And Xlang Guide](../scala/schema-idl.md)
- [Kotlin Static Generated Serializers Guide](../kotlin/static-generated-serializers.md)

## Specifications

- [Xlang Serialization Specification](../../specification/xlang_serialization_spec.md) - Binary protocol details
- [Type Mapping Specification](../../specification/xlang_type_mapping.md) - Complete type mapping reference
