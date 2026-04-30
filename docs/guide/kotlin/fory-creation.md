---
title: Fory Creation
sidebar_position: 1
id: fory_creation
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

This page covers Kotlin-specific requirements for creating Fory instances.

## Basic Setup

When using Fory for Kotlin serialization, register Kotlin serializers via `KotlinSerializers.registerSerializers(fory)`:

```kotlin
import org.apache.fory.Fory
import org.apache.fory.serializer.kotlin.KotlinSerializers

val fory = Fory.builder()
    .requireClassRegistration(true)
    .build()

// Register Kotlin serializers
KotlinSerializers.registerSerializers(fory)
```

## Thread Safety

Fory instance creation is not cheap. Instances should be shared between multiple serializations.

### Single-Thread Usage

```kotlin
import org.apache.fory.Fory
import org.apache.fory.serializer.kotlin.KotlinSerializers

object ForyHolder {
    val fory: Fory = Fory.builder()
        .requireClassRegistration(true)
        .build().also {
            KotlinSerializers.registerSerializers(it)
        }
}
```

### Multi-Thread Usage

For multi-threaded applications, use `ThreadSafeFory`:

```kotlin
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.serializer.kotlin.KotlinSerializers

object ForyHolder {
    val fory: ThreadSafeFory = Fory.builder()
        .requireClassRegistration(true)
        .buildThreadSafeFory().also {
            KotlinSerializers.registerSerializers(it)
        }
}
```

### Using Builder Methods

```kotlin
// Thread-safe Fory
val fory: ThreadSafeFory = Fory.builder()
    .requireClassRegistration(true)
    .buildThreadSafeFory()

KotlinSerializers.registerSerializers(fory)
```

## Configuration

All configuration options from Fory Java are available. See [Java Configuration](../java/configuration.md) for the complete list.

Common options for Kotlin:

```kotlin
import org.apache.fory.Fory
import org.apache.fory.serializer.kotlin.KotlinSerializers

val fory = Fory.builder()
    // Enable reference tracking for circular references
    .withRefTracking(true)
    // Enable schema evolution support
    .withCompatible(true)
    // Enable async compilation for better startup performance
    .withAsyncCompilation(true)
    // Compression options
    .withIntCompressed(true)
    .withLongCompressed(true)
    .build()

KotlinSerializers.registerSerializers(fory)
```
