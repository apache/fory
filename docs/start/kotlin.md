---
title: Kotlin Setup
sidebar_position: 11
id: kotlin
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

Fory Kotlin provides binary Object Serialization, generated models, Fory gRPC,
and Android support. It runs on Fory Java and supports Java 8 and later.

## Verify the Toolchain

```bash
java -version
./gradlew --version
# or: mvn -version
```

## Object Serialization

Add the Fory Kotlin library to the application module:

```kotlin title="build.gradle.kts"
dependencies {
  implementation("org.apache.fory:fory-kotlin:1.6.1")
}
```

Create `src/main/kotlin/KotlinExample.kt`:

```kotlin
import org.apache.fory.ThreadSafeFory
import org.apache.fory.kotlin.ForyKotlin

data class User(val id: Long, val name: String)

fun main() {
    val fory: ThreadSafeFory = ForyKotlin.builder()
        .withXlang(true)
        .requireClassRegistration(true)
        .buildThreadSafeFory()
    fory.register(User::class.java, 1)

    val bytes = fory.serialize(User(1, "Alice"))
    val decoded = fory.deserialize(bytes) as User
    println(decoded.name)
}
```

If the project applies Gradle's `application` plugin, run its application task:

```bash
./gradlew run
```

Use xlang mode when a peer uses a different Fory implementation family; use native mode for data
within the JVM Fory implementation family. Continue with
[Kotlin Object Serialization](../object-serialization/kotlin/index.md),
[xlang](../object-serialization/kotlin/basic-serialization.md#cross-language-interoperability), or
[native mode](../object-serialization/kotlin/native.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Kotlin models and registration helpers through KSP. See [Compiler Getting Started](../compiler/getting-started.md) and the [Kotlin generated-code guide](../compiler/generated-code/kotlin.md).
- **Fory gRPC** uses grpc-kotlin and grpc-java transports with Fory-encoded messages. See [Kotlin gRPC](../grpc/kotlin.md).
- **Android** uses generated serializers with the same Fory Kotlin library. See [Android Object Serialization](../object-serialization/java/android.md).
