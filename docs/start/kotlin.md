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

Fory Kotlin provides binary Object Serialization, standard JSON mapping, generated models, Fory
gRPC, and Android support. It runs on Fory Java and supports Java 8 and later.

## Verify the Toolchain

```bash
java -version
./gradlew --version
# or: mvn -version
```

## Object Serialization

The repositories below resolve release coordinates from Maven Central and `-SNAPSHOT` coordinates
from the Apache snapshot repository. Keep every Fory module on the same version:

```kotlin title="build.gradle.kts"
repositories {
  maven("https://repository.apache.org/snapshots/") {
    mavenContent { snapshotsOnly() }
  }
  mavenCentral()
}

dependencies {
  implementation("org.apache.fory:fory-kotlin:1.7.1")
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

## Standard JSON

Fory JSON is a separate text format from binary Object Serialization. Add its optional Kotlin
module when interoperating with ordinary JSON APIs, browsers, logs, or other JSON libraries:

```kotlin title="build.gradle.kts"
dependencies {
  implementation("org.apache.fory:fory-json-kotlin:1.7.1")
}
```

```kotlin
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef

data class User(val id: Long, val name: String)

val json = ForyJsonKotlin.builder().build()
val userType = jsonTypeRef<User>()
val text = json.toJson(User(1, "Alice"), userType)
val decoded = json.fromJson(text, userType)
```

The runtime reads Kotlin/JVM metadata directly. Add `fory-json-kotlin-ksp` only to Android builds
that use R8 or ProGuard; it emits exact retention rules for Kotlin `@JsonType` models and
source-owned exact Mixins. Native Image uses the normal `@ForyJsonProvider` workflow. Continue with
[Kotlin JSON](../json/kotlin.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Kotlin models and registration helpers through KSP. See [Compiler Getting Started](../compiler/getting-started.md) and the [Kotlin generated-code guide](../compiler/generated-code/kotlin.md).
- **Fory gRPC** uses grpc-kotlin and grpc-java transports with Fory-encoded messages. See [Kotlin gRPC](../grpc/kotlin.md).
- **Android** uses generated serializers with the same Fory Kotlin library. See [Android Object Serialization](../object-serialization/java/android.md).
