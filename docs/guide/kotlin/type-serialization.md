---
title: Type Serialization
sidebar_position: 2
id: type_serialization
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

This page covers serialization of Kotlin-specific JVM types in native mode. For
cross-language Kotlin models, use the xlang path described in
[Static Generated Serializers](static-generated-serializers.md).

When compatible mode is enabled, Kotlin readers use the JVM compatible-read rules for selected
scalar field type changes. A matched field can read between `Boolean`, `String`, numeric scalars,
and `java.math.BigDecimal` when the converted value has the same logical value. For example,
`"true"` and `"false"` can be read as booleans, `"123"` can be read as a numeric field that can hold
`123`, numbers and decimals can be read as canonical strings, and numeric widening or narrowing
succeeds only when no precision or range is lost. Numeric strings use finite ASCII decimal syntax.
Invalid strings and lossy conversions fail during deserialization. Nullable and boxed fields still
compose with these conversions, but reference-tracked scalar type changes are incompatible.

## Setup

All examples assume the following setup:

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder().withXlang(false)
    .requireClassRegistration(false)
    .build()
```

## Data Class

```kotlin
data class Person(val name: String, val age: Int, val id: Long)

fory.register(Person::class.java)

val p = Person("John", 30, 1L)
println(fory.deserialize(fory.serialize(p)))
```

## Unsigned Primitives

Kotlin unsigned types are fully supported:

```kotlin
val uByte: UByte = 255u
val uShort: UShort = 65535u
val uInt: UInt = 4294967295u
val uLong: ULong = 18446744073709551615u

println(fory.deserialize(fory.serialize(uByte)))
println(fory.deserialize(fory.serialize(uShort)))
println(fory.deserialize(fory.serialize(uInt)))
println(fory.deserialize(fory.serialize(uLong)))
```

## Unsigned Arrays

```kotlin
val uByteArray = ubyteArrayOf(1u, 2u, 255u)
val uShortArray = ushortArrayOf(1u, 2u, 65535u)
val uIntArray = uintArrayOf(1u, 2u, 4294967295u)
val uLongArray = ulongArrayOf(1u, 2u, 18446744073709551615u)

println(fory.deserialize(fory.serialize(uByteArray)).contentToString())
println(fory.deserialize(fory.serialize(uShortArray)).contentToString())
println(fory.deserialize(fory.serialize(uIntArray)).contentToString())
println(fory.deserialize(fory.serialize(uLongArray)).contentToString())
```

## Stdlib Types

### Pair and Triple

```kotlin
val pair = Pair("key", 42)
val triple = Triple("a", "b", "c")

println(fory.deserialize(fory.serialize(pair)))
println(fory.deserialize(fory.serialize(triple)))
```

### Result

```kotlin
val success: Result<Int> = Result.success(42)
val failure: Result<Int> = Result.failure(Exception("error"))

println(fory.deserialize(fory.serialize(success)))
println(fory.deserialize(fory.serialize(failure)))
```

## Ranges and Progressions

```kotlin
val intRange = 1..10
val longRange = 1L..100L
val charRange = 'a'..'z'

println(fory.deserialize(fory.serialize(intRange)))
println(fory.deserialize(fory.serialize(longRange)))
println(fory.deserialize(fory.serialize(charRange)))

// Progressions
val intProgression = 1..10 step 2
val longProgression = 1L..100L step 10

println(fory.deserialize(fory.serialize(intProgression)))
println(fory.deserialize(fory.serialize(longProgression)))
```

## Collections

### ArrayDeque

```kotlin
val deque = ArrayDeque<String>()
deque.addFirst("first")
deque.addLast("last")

println(fory.deserialize(fory.serialize(deque)))
```

### Empty Collections

```kotlin
val emptyList = emptyList<String>()
val emptySet = emptySet<Int>()
val emptyMap = emptyMap<String, Int>()

println(fory.deserialize(fory.serialize(emptyList)))
println(fory.deserialize(fory.serialize(emptySet)))
println(fory.deserialize(fory.serialize(emptyMap)))
```

## Duration

```kotlin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

val duration: Duration = 2.hours + 30.minutes

println(fory.deserialize(fory.serialize(duration)))
```

## Regex

```kotlin
val regex = Regex("[a-zA-Z]+")

println(fory.deserialize(fory.serialize(regex)))
```

## UUID (Kotlin 2.0+)

```kotlin
import kotlin.uuid.Uuid

val uuid = Uuid.random()

println(fory.deserialize(fory.serialize(uuid)))
```

## Types Working Out of the Box

The following types work with the default Fory Java implementation:

- **Primitives**: `Byte`, `Boolean`, `Int`, `Short`, `Long`, `Char`, `Float`, `Double`
- **String**: `String`
- **Collections**: `ArrayList`, `HashMap`, `HashSet`, `LinkedHashSet`, `LinkedHashMap`
- **Arrays**: `Array`, `BooleanArray`, `ByteArray`, `CharArray`, `DoubleArray`, `FloatArray`, `IntArray`, `LongArray`, `ShortArray`

Use `ForyKotlin.builder()` for Kotlin-specific types such as unsigned values, ranges, and `Duration`.
