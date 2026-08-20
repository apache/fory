---
title: Scala
sidebar_position: 12
id: scala
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

The Scala target emits Scala 3 source only. The `fory-scala` artifact still
supports Scala 2.13 and Scala 3, but generated IDL source and macro derivation
require Scala 3.

## Output Layout

For `package addressbook`, Scala output is generated under:

- `<scala_out>/addressbook/`
- Type files: `AddressBook.scala`, `Person.scala`, `Dog.scala`, `Cat.scala`, `Animal.scala`
- Schema module: `AddressbookForyModule.scala`

For schemas without a Scala package, the schema module name is derived from the
source file stem, for example `main.fdl` generates `MainForyModule.scala`.
Scala import graphs cannot mix default-package schemas with named Scala
packages.

## Type Generation

Messages outside compiler-detected construction cycles generate case classes:

```scala
import org.apache.fory.annotation.{ForyField, ForyStruct}
import org.apache.fory.scala.ForySerializer

@ForyStruct
final case class Person(
  @ForyField(id = 1) name: String,
  @ForyField(id = 3) email: Option[String],
  @ForyField(id = 7) phones: List[Person.PhoneNumber],
  @ForyField(id = 8) pet: Animal
) derives ForySerializer {
  def toBytes(): Array[Byte] =
    AddressbookForyModule.getFory.serialize(this)
}

object Person {
  def fromBytes(bytes: Array[Byte]): Person =
    AddressbookForyModule.getFory.deserialize(bytes).asInstanceOf[Person]
}
```

Messages in circular construction cycles generate normal classes with mutable
serialized fields so reads can register the object before reading back-references:

```scala
import org.apache.fory.annotation.{ForyField, ForyStruct, Ref}
import org.apache.fory.scala.ForySerializer

@ForyStruct
final class Node() derives ForySerializer {
  @ForyField(id = 1)
  var id: String = ""

  @Ref
  @ForyField(id = 2)
  var parent: Option[Node] = None
}
```

Enums generate Scala 3 enums with stable Fory IDs:

```scala
import org.apache.fory.annotation.ForyEnumId

enum PhoneType {
  @ForyEnumId(0)
  case Mobile

  @ForyEnumId(1)
  case Home

  @ForyEnumId(2)
  case Work
}
```

Unions generate Scala 3 ADT enums. `Unknown(UnknownCase)` is the Fory-provided
forward-compatibility carrier marked with `@ForyUnknownCase`. It is omitted
from the schema case table because the marker only selects the carrier and does
not add a schema entry. Schema-defined cases use non-negative `@ForyCase` IDs.
A typed union must have at least one
non-`Unknown` case.

```scala
package addressbook

import org.apache.fory.annotation.{ForyCase, ForyUnion, ForyUnknownCase}
import org.apache.fory.scala.ForySerializer
import org.apache.fory.`type`.union.UnknownCase

@ForyUnion
enum Animal derives ForySerializer {
  @ForyUnknownCase
  case Unknown(value: UnknownCase)

  @ForyCase(id = 0)
  case Dog(value: _root_.addressbook.Dog)

  @ForyCase(id = 1)
  case Cat(value: _root_.addressbook.Cat)
}
```

Packaged Scala output keeps the schema case name and qualifies the payload type
when both have the same simple name. If a target output mode cannot express a
legal qualifier for a conflict, the compiler appends `Case` to the generated
case name.

`optional T` fields generate `Option[T]`. Top-level message references use
`@Ref` on the field or constructor parameter. Nested element/value references
use type-use annotations such as `List[Node @Ref]`.

## Schema Module

Generated schema modules register schema serializers, enums, structs, and
unions. The package-owned helper Fory instance uses
`ForyScala.builder().withXlang(true)` with the schema module installed, so
message `toBytes`/`fromBytes` helpers work without caller-managed Fory setup:

```scala
object AddressbookForyModule extends org.apache.fory.ForyModule {
  private lazy val fory: ThreadSafeFory =
    ForyScala.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withModule(this)
      .buildThreadSafeFory()

  private[addressbook] def getFory: ThreadSafeFory = fory

  override def install(fory: Fory): Unit = {
    ScalaSerializers.registerEnum(fory, classOf[Person.PhoneType], 101L)
    ForySerializer.register(fory, classOf[Person.PhoneNumber], 102L)
    ForySerializer.register(fory, classOf[Person], 100L)
    ForySerializer.register(fory, classOf[Animal], 106L)
  }
}
```

## gRPC Service Companions

With `--grpc`, Scala emits one `<ServiceName>Grpc.scala` object per local service in the generated models' package. It exposes `SERVICE_NAME`, service and method descriptors, `<ServiceName>ImplBase`, and `<ServiceName>Client`. See [Scala gRPC](../../grpc/scala.md) for `RpcFuture`, `RpcIterator`, grpc-java variants, and lifecycle guidance.
