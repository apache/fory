---
title: Kotlin Xlang Serialization
sidebar_position: 1
id: xlang
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

Kotlin xlang serialization uses the JVM Fory implementation through `ForyKotlin`. Use it when
Kotlin payloads must be read by another supported Fory runtime. Register portable model types with
the same identity and field schema on every peer.

Kotlin data classes, enums, and sealed-class models use the Kotlin integration and generated
serializers where applicable. Exact portable carrier mappings remain defined by the
[xlang type mapping](../../specification/xlang_type_mapping.md).

## Create an xlang instance

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder()
    .withXlang(true)
    .build()
```

## First round trip

```kotlin
import org.apache.fory.ThreadSafeFory
import org.apache.fory.kotlin.ForyKotlin

data class Person(val name: String, val age: Int)

fun main() {
    val fory: ThreadSafeFory = ForyKotlin.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .buildThreadSafeFory()
    fory.register(Person::class.java)

    val bytes = fory.serialize(Person("chaokunyang", 28))
    val result = fory.deserialize(bytes) as Person
    println("${result.name} ${result.age}")
}
```
