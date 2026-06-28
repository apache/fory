---
title: Static Generated Serializers
sidebar_position: 5
id: static_generated_serializers
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

Use `fory-kotlin-ksp` when Kotlin classes must participate in Fory
cross-language schema serialization. The processor generates Kotlin source
serializers at build time. Those serializers call the existing Fory Java
implementation, including `WriteContext`, `ReadContext`, and `MemoryBuffer`; there is
no Kotlin-only protocol.

Static generated Kotlin serializers are for Kotlin/JVM and Android xlang/schema
mode. They are not Java native object serializers and do not preserve JVM object
graph implementation details such as the exact concrete collection class.

## Add KSP

Add `fory-kotlin` to the application classpath and run `fory-kotlin-ksp` as a KSP processor in
the module that compiles your `@ForyStruct` Kotlin classes.

```kotlin
plugins {
  id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
  implementation("org.apache.fory:fory-kotlin:<fory-version>")
  ksp("org.apache.fory:fory-kotlin-ksp:<fory-version>")
}
```

For Android, configure KSP in the Android module or library module that owns
the Kotlin model classes.

## Define A Struct

Reuse the Java Fory annotations for schema concepts. Use Kotlin type-use
annotations only when you need to override integer encoding.

```kotlin
import org.apache.fory.annotation.ForyField
import org.apache.fory.annotation.ForyStruct
import org.apache.fory.kotlin.Fixed
import org.apache.fory.kotlin.VarInt

@ForyStruct
data class User(
  @ForyField(id = 1)
  val id: @Fixed UInt,

  @ForyField(id = 2)
  val score: @VarInt Long,

  @ForyField(id = 3)
  val tags: List<String>,
)
```

Use `@ForyField(id = 1)` on constructor properties. `@field:ForyField(id = 1)`
is also accepted for field-backed properties. Do not use `@get:ForyField` or
`@set:ForyField`; accessors are not schema fields and the processor rejects
them.

## Supported Structs

The processor generates serializers for public or internal, concrete,
non-generic classes in named packages. A supported class must have a primary
constructor whose serialized parameters are `val` or `var` properties. `data
class` is the common case, but it is not required.

Internal Kotlin struct classes are supported when KSP runs in the same Kotlin
module that owns the struct. The generated Kotlin serializer is also internal,
so it can call the internal constructor and expose the internal type in
overrides while still producing a JVM class that Fory Java can load.
Application code outside that Kotlin module still cannot refer to the internal
struct directly, so registration must happen from code that can see the class.

The processor rejects these declarations:

- `private` struct classes.
- local, anonymous, or nested `@ForyStruct` classes.
- Kotlin `object` declarations.
- interfaces, abstract classes, and sealed classes as serializer targets.
- generic `@ForyStruct` classes.
- private constructor properties.
- private or protected primary constructors.

Kotlin default constructor arguments are supported for compatible reads. A
struct can have up to 12 defaulted constructor fields.

Constructor-based generated serializers support wide primary constructors.
Compatible reads track remote field presence in generated side state instead of
using constructor bit masks.

## Nullability

Use Kotlin `?` to describe nullable schema positions. Nullability is preserved
inside collections and maps.

```kotlin
@ForyStruct
data class NullabilityExample(
  @ForyField(id = 1)
  val a: List<String>,

  @ForyField(id = 2)
  val b: List<String?>,

  @ForyField(id = 3)
  val c: List<String>?,

  @ForyField(id = 4)
  val d: List<String?>?,
)
```

Do not use Fory `@Nullable` in hand-written constructor-based Kotlin structs.
The KSP processor rejects it so the schema is always read from Kotlin source
nullability. Compiler-generated Kotlin IDL sources follow the same rule and use
Kotlin `?` for nullable fields.

## References

Kotlin generated serializers preserve `@Ref` metadata for fields, list
elements, and map values. Constructor-owned reads construct Kotlin values
through primary constructors. Schema IDL classes that need reference
publication are emitted as mutable no-arg classes, and their KSP-generated
serializers publish the instance before reading fields. In both shapes, KSP owns
field descriptors, nested nullability, and `@Ref` metadata.

## Collections

Collection declarations carry schema shape, not JVM implementation identity.
For example, `List<String>` is encoded as `list<string>` and
`Map<String, Int>` is encoded as `map<string, int32>`.

Deserialization only guarantees that the result is assignable to the declared
field type. Fory does not preserve whether the original concrete value was an
`ArrayList`, `LinkedList`, `Collections.unmodifiableList`, synchronized
collection wrapper, or another JVM-specific collection implementation.

Supported collection declarations include Kotlin and Java list, set, and map
types. Mutable collection interface fields are deserialized to mutable
implementations assignable to the declared type. Sorted collections without an
explicit comparator, such as `TreeSet` and `ConcurrentSkipListSet`, are accepted
only for non-null scalar or string elements. Concurrent map declarations are
accepted only with non-null values because JVM concurrent map implementations
reject null entries.

`Set<*>`, `Map<*, T>`, `Map<*, *>`, and raw Java collections are rejected.
`List<*>` and `Map<K, *>` are accepted and use dynamic nullable values.

## Dense Arrays

Kotlin dense primitive and unsigned array fields are supported:

- `BooleanArray`
- `ByteArray`
- `ShortArray`
- `IntArray`
- `LongArray`
- `FloatArray`
- `DoubleArray`
- `UByteArray`
- `UShortArray`
- `UIntArray`
- `ULongArray`

Dense arrays with unambiguous Kotlin carriers are supported in fields,
collection elements, map values, and union cases. `array<float16>` and
`array<bfloat16>` use the Java core `Float16Array` and `BFloat16Array` carriers.

`ByteArray` is encoded as Fory `binary` unless the `ByteArray` type use is
annotated with Java `@ArrayType`. Generated Kotlin IDL uses
`@ArrayType ByteArray` for `array<int8>`, including nested collection and map
positions.

`@ArrayType` is also supported on top-level `List<T>` fields when `T` is a
non-null boolean or numeric dense-array element type. In that case the field is
encoded as dense `array<T>` schema, and generated reads convert decoded JVM list
elements back to the declared Kotlin element carrier.

## Integer Encoding

Kotlin type-use encoding annotations map to Fory xlang integer encodings:

| Annotation | Valid Kotlin types             |
| ---------- | ------------------------------ |
| `@Fixed`   | `Int`, `Long`, `UInt`, `ULong` |
| `@VarInt`  | `Int`, `Long`, `UInt`, `ULong` |
| `@Tagged`  | `Long`, `ULong`                |

Without an annotation, xlang `Int`, `Long`, `UInt`, and `ULong` use varint
encoding. This is required by xlang mode and is not controlled by Java native
mode numeric compression options.

## Duration

Xlang `duration` maps to `kotlin.time.Duration`. Infinite Kotlin durations
cannot be represented by the xlang duration payload and fail during
serialization.

## Sealed Unions

KSP generates serializers for top-level sealed classes annotated with
`@ForyUnion`. Each schema case is a nested class annotated with `@ForyCase` and
one constructor property named `value`. `Unknown(UnknownCase)` is marked with
`@ForyUnknownCase` as the Fory-owned forward-compatibility carrier. It is
omitted from the schema case table because the marker only selects the carrier
and does not add a schema entry. A typed union must declare at least one
non-`Unknown` case:

```kotlin
package example

import org.apache.fory.annotation.ForyCase
import org.apache.fory.annotation.ForyUnion
import org.apache.fory.annotation.ForyUnknownCase
import org.apache.fory.type.union.UnknownCase

@ForyUnion
sealed class Animal {
  @ForyUnknownCase
  data class Unknown(val value: UnknownCase) : Animal()

  @ForyCase(id = 0)
  data class Dog(val value: example.Dog) : Animal()
}
```

When a generated Kotlin union case name matches the payload type simple name,
packaged output keeps the case name and qualifies the payload type. If a target
output mode cannot express a legal qualifier for a conflict, the IDL compiler
appends `Case` to the generated case class name.

Generated schema modules register sealed unions through `KotlinSerializers.registerUnion`.
Fory discovers the generated `<Target>_ForySerializer` automatically, so
callers do not pass a serializer instance.

## Register Classes

Register Kotlin struct classes with the Kotlin `register<T>` extension. You
choose the xlang namespace and type name; generated serializers do not choose
IDs or names for you.

```kotlin
import org.apache.fory.kotlin.ForyKotlin
import org.apache.fory.kotlin.register

val fory = ForyKotlin.builder()
    .withXlang(true)
    .requireClassRegistration(true)
    .build()

fory.register<User>("example.User")
```

`ForyKotlin.builder()` installs the Kotlin serializer bootstrap for the Fory
instance. The `fory.register<T>(...)` extension registers your xlang schema type
name and resolves the generated serializer from the target class.

Do not register or reference generated serializer classes in application code.
Fory resolves them from the registered target class.

Generated Schema IDL modules use the same path. They call
`KotlinSerializers.registerType`, `registerSerializer`, `registerEnum`, and
`registerUnion` as appropriate and never emit Java files.

## Generated Names

The generated serializer is emitted in the same package as the target class.
Its name is `<target>_ForySerializer`. For nested binary names, `$` is encoded
as `_`; source underscores are encoded as `_u_`.

These names are an implementation detail. They matter for diagnostics and
Android shrinking, but user code should only register target classes.

If a constructor-owned Kotlin xlang struct is registered but its KSP generated
serializer is missing, Fory fails with a configuration error. Compile generated
IDL sources with KSP before registering generated Kotlin classes.

## Android And R8

Android apps should not need user-written keep rules for generated Kotlin
serializers. KSP emits generated consumer R8/ProGuard rules under
`META-INF/proguard/` for the generated serializer constructors used by Fory and
the Kotlin metadata needed to detect required Kotlin generated serializers.

For library modules, package the generated `META-INF/proguard/` resources into
the produced artifact. For Android application modules, make sure your KSP
setup includes generated resources in the minified variant.

See [Android Support](android-support.md) for Android Gradle setup and
release-minified validation guidance.

## Native Object Mode

Kotlin KSP generated serializers are only for xlang/schema mode. They do not
replace Fory Java native object serializers and do not preserve JVM object graph
identity. If you use Fory with `withXlang(false)`, Fory uses the normal Java and
Kotlin serializers instead.

Kotlin/Native and Kotlin/JS are not supported by this module.
