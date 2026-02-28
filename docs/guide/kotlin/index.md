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

Apache Fory™ Kotlin provides optimized serializers for Kotlin types, built on top of Fory Java. Most standard Kotlin types work out of the box with the default Fory Java implementation, while Fory Kotlin adds additional support for Kotlin-specific types.

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
- **Schema Evolution**: Forward/backward compatibility for class schema changes

See [Java Features](../java/index.md#features) for complete feature list.

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-kotlin</artifactId>
  <version>0.15.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.apache.fory:fory-kotlin:0.15.0")
```

## Quick Start

```kotlin
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.serializer.kotlin.KotlinSerializers

data class Person(val name: String, val id: Long, val github: String)
data class Point(val x: Int, val y: Int, val z: Int)

fun main() {
    // Create Fory instance (should be reused)
    val fory: ThreadSafeFory = Fory.builder()
        .requireClassRegistration(true)
        .buildThreadSafeFory()

    // Register Kotlin serializers
    KotlinSerializers.registerSerializers(fory)

    // Register your classes
    fory.register(Person::class.java)
    fory.register(Point::class.java)

    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
}
```

## Built on Fory Java

Fory Kotlin is built on top of Fory Java. Most configuration options, features, and concepts from Fory Java apply directly to Kotlin. Refer to the Java documentation for:

- [Configuration Options](../java/configuration.md) - All ForyBuilder options
- [Basic Serialization](../java/basic-serialization.md) - Serialization patterns and APIs
- [Type Registration](../java/type-registration.md) - Class registration and security
- [Schema Evolution](../java/schema-evolution.md) - Forward/backward compatibility
- [Custom Serializers](../java/custom-serializers.md) - Implement custom serializers
- [Compression](../java/compression.md) - Int, long, and string compression
- [Troubleshooting](../java/troubleshooting.md) - Common issues and solutions

## Kotlin-Specific Documentation

- [Fory Creation](fory-creation.md) - Kotlin-specific Fory setup requirements
- [Type Serialization](type-serialization.md) - Serializing Kotlin types
- [Default Values](default-values.md) - Kotlin data class default values support
