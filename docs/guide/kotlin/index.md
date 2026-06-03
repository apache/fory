---
title: Kotlin Serialization Guide
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

Apache Fory™ Kotlin provides optimized serializers for Kotlin types, built on top of Fory Java. It supports xlang mode for cross-language payloads and native mode for Kotlin/JVM-only object serialization. Most standard Kotlin types work out of the box with the default Fory Java implementation, while Fory Kotlin adds additional support for Kotlin-specific types.

Supported types include:

- `data class` serialization
- Unsigned primitives: `UByte`, `UShort`, `UInt`, `ULong`
- Unsigned arrays: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- Stdlib types: `Pair`, `Triple`, `Result`
- Ranges: `IntRange`, `LongRange`, `CharRange`, and progressions
- Collections: `ArrayDeque`, empty collections (`emptyList`, `emptyMap`, `emptySet`)
- `kotlin.time.Duration`, `kotlin.text.Regex`, `kotlin.uuid.Uuid`

## Features

Fory Kotlin inherits all features from Fory Java, plus Kotlin-specific optimizations:

- **High Performance**: JIT code generation, zero-copy, 20-170x faster than traditional serialization
- **Kotlin Type Support**: Optimized serializers for data classes, unsigned types, ranges, and stdlib types
- **Default Value Support**: Automatic handling of Kotlin data class default parameters during schema evolution
- **Static Xlang Serializers**: KSP-generated schema serializers for Kotlin/JVM and Android xlang mode
- **Schema IDL Generation**: Fory compiler output for Kotlin models, sealed unions, and schema modules
- **Schema Evolution**: Forward/backward compatibility for class schema changes

See [Java Features](../java/index.md#features) for complete feature list.

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-kotlin</artifactId>
  <version>1.1.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.apache.fory:fory-kotlin:1.1.0")
```

### JDK25+

Kotlin uses the Fory Java core at runtime. On JDK25+, open `java.lang.invoke`
to Fory. Use `ALL-UNNAMED` when Fory is on the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

Use the Fory core module name when Fory is on the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

## Quick Start

```kotlin
import org.apache.fory.ThreadSafeFory
import org.apache.fory.kotlin.ForyKotlin

data class Person(val name: String, val id: Long, val github: String)
data class Point(val x: Int, val y: Int, val z: Int)

fun main() {
    // Create Fory instance (should be reused). Kotlin follows the Java default:
    // xlang mode with compatible schema evolution.
    val fory: ThreadSafeFory = ForyKotlin.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .buildThreadSafeFory()

    fory.register(Person::class.java)
    fory.register(Point::class.java)

    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
}
```

## Xlang Mode And Native Mode

Use xlang mode for cross-language payloads and schemas shared with other Fory runtimes. Xlang mode is the default Kotlin wire mode through the JVM builder, and Kotlin examples that use it set `.withXlang(true)` explicitly so the mode choice is visible.

Use native mode for Kotlin/JVM-only traffic. Native mode is selected with `.withXlang(false)`, uses schema-consistent payloads unless compatible mode is enabled, and inherits the JVM native-mode object serialization path from Fory Java while adding Kotlin-specific serializers for data classes, unsigned values, ranges, stdlib types, and generated serializers. It is optimized for JVM and Kotlin type systems and is the right path for same-language Kotlin/JVM framework replacement payloads.

See [Configuration](configuration.md) for Kotlin builder setup and [Java Native Serialization](../java/native-serialization.md) for the full JVM native-mode behavior.

## Built on Fory Java

Fory Kotlin is built on top of Fory Java. Most configuration options, features, and concepts from Fory Java apply directly to Kotlin. Refer to the Java documentation for:

- [Configuration](../java/configuration.md) - All ForyBuilder options
- [Basic Serialization](../java/basic-serialization.md) - Serialization patterns and APIs
- [Type Registration](../java/type-registration.md) - Class registration and security
- [Schema Evolution](../java/schema-evolution.md) - Forward/backward compatibility
- [Custom Serializers](../java/custom-serializers.md) - Implement custom serializers
- [Compression](../java/compression.md) - Int, long, and string compression
- [Troubleshooting](../java/troubleshooting.md) - Common issues and solutions

## Kotlin-Specific Documentation

- [Configuration](configuration.md) - Kotlin-specific Fory setup requirements
- [Type Serialization](type-serialization.md) - Serializing Kotlin types
- [Schema Metadata](schema-metadata.md) - Kotlin annotations, nullability, references, and integer metadata
- [Default Values](default-values.md) - Kotlin data class default values support
- [Static Generated Serializers](static-generated-serializers.md) - KSP xlang/schema serializer generation
- [Android Support](android-support.md) - Android setup, R8 behavior, and release-build validation
