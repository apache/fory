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
  implementation("org.apache.fory:fory-json-kotlin:1.7.0-SNAPSHOT")
  ksp("org.apache.fory:fory-json-kotlin-ksp:1.7.0-SNAPSHOT")
}
```

The runtime reads Kotlin/JVM metadata directly. This processor does not generate application code,
codecs, or construction operations, and it is not required for an unminified JVM build. Keep the
generated rule resources in the Android application and do not replace them with package-wide keep
rules.

See the [Kotlin JSON guide](../../docs/json/kotlin.md) and
[Android guide](../../docs/json/android.md) for model and release-build setup.
