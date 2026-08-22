---
title: Configuration
sidebar_position: 3
id: configuration
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

This page covers Kotlin-specific Fory instance configuration and creation.

## Xlang Setup

Fory Kotlin follows the Java builder default: xlang mode with compatible schema
evolution. Use this path for cross-language Kotlin payloads, schema IDL
generated Kotlin models, and KSP-generated xlang serializers.

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .build()
```

## Native Mode Setup

For same-language Kotlin/JVM payloads that need native JVM object behavior, use
native mode explicitly:

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder().withXlang(false)
    .requireClassRegistration(true)
    .build()
```

## Thread Safety

Fory instance creation is not cheap. Instances should be shared between multiple serializations.

### Single-Thread Usage

```kotlin
import org.apache.fory.Fory
import org.apache.fory.kotlin.ForyKotlin

object ForyHolder {
    val fory: Fory = ForyKotlin.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .build()
}
```

### Multi-Thread Usage

For multi-threaded applications, use `ThreadSafeFory`:

```kotlin
import org.apache.fory.ThreadSafeFory
import org.apache.fory.kotlin.ForyKotlin

object ForyHolder {
    val fory: ThreadSafeFory = ForyKotlin.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .buildThreadSafeFory()
}
```

### Using Builder Methods

```kotlin
// Thread-safe Fory
val fory: ThreadSafeFory = ForyKotlin.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .buildThreadSafeFory()
```

## Configuration

All configuration options from Fory Java are available. See [Java Configuration](../java/configuration.md) for the complete list.

Common options for Kotlin native-mode payloads:

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder().withXlang(false)
    // Enable reference tracking for circular references
    .withRefTracking(true)
    // Same-schema optimization. Use only when every reader and writer
    // always uses the same Kotlin/JVM schema.
    .withCompatible(false)
    // Enable async compilation for better startup performance
    .withAsyncCompilation(true)
    // Compression options
    .withIntCompressed(true)
    .withLongCompressed(true)
    .build()
```

### Graph Memory Budget

Kotlin uses the Java `withMaxGraphMemoryBytes(...)` option. It sets an approximate graph-memory gate
for one root deserialization, mainly for materialized collections, maps, arrays, structs, and
objects. It skips leaf values such as strings, binary data, primitive scalars, and dense primitive
arrays, so actual process memory can be higher than this value. Leaf values are still gated by
remaining input bytes: if the unread input does not contain enough bytes, Fory will not read or
create that leaf value.

```kotlin
val fory = ForyKotlin.builder()
    .withMaxGraphMemoryBytes(128L * 1024 * 1024)
    .withMaxUnbackedContainerItems(8192)
    .build()
```

`withMaxUnbackedContainerItems(...)` limits collection elements and map entries whose repeated
read bodies do not consume proportional input during one root deserialization. The default is
`8192`; zero is a strict limit.

## Compatible Mode

Compatible mode is enabled by default through the Java builder in both xlang and native mode. Keep
this default when models may evolve independently, when services deploy separately, or when xlang
schemas are written by hand in different languages.

Use `withCompatible(false)` only when the class schema used to deserialize every payload is always
the same as the class schema used to serialize it and you want faster serialization and smaller size.
For xlang payloads, call `withCompatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

## Security

See [Kotlin Security](security.md) for trust boundaries, safe reader configuration, and verification.
