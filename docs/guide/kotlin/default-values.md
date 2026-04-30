---
title: Default Values
sidebar_position: 3
id: default_values
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

Fory supports Kotlin data class default values during deserialization when using compatible mode. This feature enables forward/backward compatibility when data class schemas evolve.

## Overview

When a Kotlin data class has parameters with default values, Fory can:

1. **Detect default values** using Kotlin reflection
2. **Apply default values** during deserialization when fields are missing from serialized data
3. **Support schema evolution** by allowing new fields with defaults to be added without breaking existing serialized data

## Usage

This feature is automatically enabled when:

- Compatible mode is enabled (`withCompatible(true)`)
- Kotlin serializers are registered (`KotlinSerializers.registerSerializers(fory)`)
- A field is missing from the serialized data but exists in the target class with a default value

## Example

```kotlin
import org.apache.fory.Fory
import org.apache.fory.serializer.kotlin.KotlinSerializers

// Original data class
data class User(val name: String, val age: Int)

// Evolved data class with new field and default value
data class UserV2(val name: String, val age: Int, val email: String = "default@example.com")

fun main() {
    val fory = Fory.builder()
        .withCompatible(true)
        .build()
    KotlinSerializers.registerSerializers(fory)
    fory.register(User::class.java)
    fory.register(UserV2::class.java)

    // Serialize with old schema
    val oldUser = User("John", 30)
    val serialized = fory.serialize(oldUser)

    // Deserialize with new schema - missing field gets default value
    val newUser = fory.deserialize(serialized) as UserV2
    println(newUser) // UserV2(name=John, age=30, email=default@example.com)
}
```

## Supported Default Value Types

The following types are supported for default values:

- **Primitive types**: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `Char`
- **Unsigned types**: `UInt`, `ULong`, `UByte`, `UShort`
- **String**: `String`
- **Collections**: `List`, `Set`, `Map` (with default instances)
- **Custom objects**: Any object that can be instantiated via reflection

## Complex Default Values

Default values can be complex expressions:

```kotlin
data class ConfigV1(val name: String)

data class ConfigV2(
    val name: String,
    val settings: Map<String, String> = mapOf("default" to "value"),
    val tags: List<String> = listOf("default"),
    val enabled: Boolean = true,
    val retryCount: Int = 3
)

val fory = Fory.builder()
    .withCompatible(true)
    .build()
KotlinSerializers.registerSerializers(fory)

val original = ConfigV1("myConfig")
val serialized = fory.serialize(original)

val deserialized = fory.deserialize(serialized) as ConfigV2
// deserialized.name == "myConfig"
// deserialized.settings == mapOf("default" to "value")
// deserialized.tags == listOf("default")
// deserialized.enabled == true
// deserialized.retryCount == 3
```

## Nullable Fields with Defaults

Nullable fields with default values are also supported:

```kotlin
data class PersonV1(val name: String)

data class PersonV2(
    val name: String,
    val nickname: String? = null,
    val age: Int? = null
)

val original = PersonV1("John")
val serialized = fory.serialize(original)

val deserialized = fory.deserialize(serialized) as PersonV2
// deserialized.name == "John"
// deserialized.nickname == null (default)
// deserialized.age == null (default)
```

## Related Topics

- [Schema Evolution](../java/schema-evolution.md) - Forward/backward compatibility in Java
- [Fory Creation](fory-creation.md) - Setting up Fory with compatible mode
