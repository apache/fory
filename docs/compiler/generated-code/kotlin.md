---
title: Kotlin
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

The Kotlin target emits Kotlin source only. The compiler does not generate Java
files.

## Output Layout

For source file `addressbook.fdl` with `package addressbook`, Kotlin output is
generated under:

- `<kotlin_out>/addressbook/`
- Type files: `AddressBook.kt`, `Person.kt`, `Dog.kt`, `Cat.kt`, `Animal.kt`
- Schema module: `AddressbookForyModule.kt`

The schema module name is derived from the source file stem. Schemas in the same
Kotlin package need distinct generated file names; duplicate generated Kotlin
file paths are rejected before files are written.

If `option kotlin_package = "...";` is present, the output path and Kotlin
package use that option. Otherwise Kotlin uses the FDL package. A Kotlin import
graph cannot mix default-package schemas with named Kotlin packages.
Registration still uses the FDL package so cross-language type names stay
stable.

## Type Generation

Messages generate Kotlin `data class` declarations by default:

```kotlin
@ForyStruct
public data class Person(
  @field:ForyField(id = 1)
  public val name: String,

  @field:ForyField(id = 7)
  public val phones: List<PersonPhoneNumber>,

  @field:ForyField(id = 8)
  public val pet: Animal,
) {
  public fun toBytes(): ByteArray = AddressbookForyModule.getFory().serialize(this)

  public companion object {
    public fun fromBytes(bytes: ByteArray): Person =
      AddressbookForyModule.getFory().deserialize(bytes, Person::class.java)
  }
}
```

Messages that participate in compiler-detected construction cycles generate
normal mutable classes so the generated serializer can publish the instance
before reading back-references:

```kotlin
@ForyStruct
public class Node() {
  @ForyField(id = 1)
  public var id: String = ""

  @Ref
  @ForyField(id = 2)
  public var parent: Node? = null
}
```

Generated Kotlin IDL sources express nullability with Kotlin `?`, not Fory
`@Nullable`, including mutable classes emitted for compiler-detected
construction cycles.

Enums generate Kotlin enum classes with stable Fory enum IDs. Unions generate
sealed classes with `@ForyUnion`; the Fory-provided `Unknown(UnknownCase)`
carrier is marked with `@ForyUnknownCase`. The marker only selects the carrier
and does not add an entry to the schema case table. Schema-defined cases may use
case IDs `0..N` and hold a single `value` property. A typed union must have at
least one non-`Unknown` case.

```kotlin
package addressbook

import org.apache.fory.annotation.ForyCase
import org.apache.fory.annotation.ForyUnion
import org.apache.fory.annotation.ForyUnknownCase
import org.apache.fory.type.union.UnknownCase

@ForyUnion
public sealed class Animal {
  @ForyUnknownCase
  public data class Unknown(public val value: UnknownCase) : Animal()

  @ForyCase(id = 0)
  public data class Dog(public val value: addressbook.Dog) : Animal()
}
```

Packaged Kotlin output keeps the schema case name and qualifies the payload
type when both have the same simple name. If a target output mode cannot express
a legal qualifier for a conflict, the compiler appends `Case` to the generated
case class name.

Kotlin `int32`, `int64`, `uint32`, and `uint64` fields use xlang varint
encoding by default, so generated Kotlin does not emit `@VarInt` for the
default case. It emits `@Fixed` or `@Tagged` only when the schema requests that
non-default encoding. `duration` maps to `kotlin.time.Duration`, and infinite
durations are rejected when encoded. Dense `array<float16>` and
`array<bfloat16>` use the Java core `Float16Array` and `BFloat16Array`
carriers. Generated Kotlin IDL uses `@ArrayType ByteArray` for `array<int8>`,
including nested positions.

## Schema Module

Generated schema modules register schema types and resolve KSP-generated
serializers from the target class name. The package-owned helper Fory instance uses
`ForyKotlin.builder().withXlang(true)` with the schema module installed, so message
`toBytes`/`fromBytes` helpers work without caller-managed Fory setup. For
`addressbook.fdl`:

```kotlin
public object AddressbookForyModule : ForyModule {
  private val fory: ThreadSafeFory by lazy {
    ForyKotlin.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withModule(this)
      .buildThreadSafeFory()
  }

  internal fun getFory(): ThreadSafeFory = fory

  override fun install(fory: Fory) {
    KotlinSerializers.registerType(fory, Person::class.java, 100L)
    KotlinSerializers.registerSerializer(fory, Person::class.java)
    KotlinSerializers.registerUnion(fory, Animal::class.java, 106L)
  }
}
```

`registerUnion` discovers the generated `<Target>_ForySerializer`; callers do
not pass a serializer instance.

## gRPC Service Companions

With `--grpc`, Kotlin emits one `<ServiceName>GrpcKt.kt` per service, not a Java companion. The generated object exposes `SERVICE_NAME`, service and method descriptors, `<ServiceName>CoroutineImplBase`, and `<ServiceName>CoroutineStub`. See [Kotlin gRPC](../../grpc/kotlin.md) for dependencies, KSP setup, and coroutine and `Flow` usage.
