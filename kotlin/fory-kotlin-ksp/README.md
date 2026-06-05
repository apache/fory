# Apache Fory Kotlin KSP

`fory-kotlin-ksp` is the KSP processor for Apache Fory Kotlin xlang/schema
serializers.

The processor depends on `fory-kotlin` and generates Kotlin source serializers
for Kotlin classes annotated with Fory schema annotations such as
`@ForyStruct` and `@ForyField`. Generated serializers call the existing Fory
Java runtime; they do not define a Kotlin-only protocol or buffer abstraction.

## Usage

Add the processor to the module that compiles Kotlin `@ForyStruct` classes:

```kotlin
plugins {
  id("com.google.devtools.ksp")
}

dependencies {
  implementation("org.apache.fory:fory-kotlin:<fory-version>")
  ksp("org.apache.fory:fory-kotlin-ksp:<fory-version>")
}
```

Register target classes with the Kotlin `register<T>` extension:

```kotlin
import org.apache.fory.kotlin.ForyKotlin
import org.apache.fory.kotlin.register

val fory = ForyKotlin.builder()
  .withXlang(true)
  .requireClassRegistration(true)
  .build()

fory.register<User>("example.User")
```

Application code should not reference generated serializer classes directly.
The runtime resolves generated serializers from the registered target class.

## Scope

`fory-kotlin-ksp` generates serializers only for Fory xlang/schema mode. It
does not generate Java native object serializers and does not preserve concrete
JVM collection implementation identity.

Supported targets are Kotlin/JVM and Android. Kotlin/Native and Kotlin/JS are
not supported.

## Android

Android projects should validate minified release builds. KSP emits generated
consumer R8/ProGuard rules for generated serializer constructors and Kotlin
metadata required by Fory. Fix packaging if those generated resources are not
included in the Android artifact.

See [Kotlin Android Support](../../docs/guide/kotlin/android-support.md) and
[Kotlin Static Generated Serializers](../../docs/guide/kotlin/static-generated-serializers.md)
for user-facing documentation.

## Development

Build from the Kotlin parent directory:

```bash
cd ..
mvn -pl fory-kotlin-ksp -am test
```

If Java artifacts have changed, install them first:

```bash
cd ../../java
mvn -T16 install -DskipTests
```
