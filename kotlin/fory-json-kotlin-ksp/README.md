<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Fory JSON Kotlin KSP

`fory-json-kotlin-ksp` generates exact R8 and ProGuard retention rules for Kotlin classes annotated
with `@JsonType`. It also owns a source `@JsonMixin` request when either the Mixin or its exact
target is Kotlin.

Use it in Android applications that enable shrinking or obfuscation:

```kotlin
plugins {
  id("com.google.devtools.ksp") version "2.3.8"
}

repositories {
  maven("https://repository.apache.org/snapshots/") {
    mavenContent { snapshotsOnly() }
  }
  mavenCentral()
}

dependencies {
  implementation("org.apache.fory:fory-json-kotlin:1.7.7")
  ksp("org.apache.fory:fory-json-kotlin-ksp:1.7.7")
}
```

The runtime reads Kotlin/JVM metadata directly. This processor does not generate application code,
codecs, or construction operations, and it is not required for an unminified JVM build. Keep the
generated rule resources in the Android application and do not replace them with package-wide keep
rules.

See the [Kotlin JSON guide](../../docs/json/kotlin.md) and
[Android guide](../../docs/json/android.md) for model and release-build setup.
