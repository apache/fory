---
title: Schema IDL And Xlang
sidebar_position: 5
id: schema_idl
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

The Fory schema IDL Scala target generates Scala 3 source for xlang payloads.
The Fory Scala artifact remains cross-built for Scala 2.13 and Scala 3; only the
schema IDL output and quoted macro derivation require Scala 3.

## Setup

Generated Scala code uses the public macro API in `org.apache.fory.scala` and
the shared JVM annotations in `org.apache.fory.annotation`. Macro internals live
under `org.apache.fory.scala.internal`.

```scala
import org.apache.fory.scala.{ForyScala, ForySerializer}
import example.ExampleForyModule

val fory = ForyScala.builder()
  .withXlang(true)
  .withRefTracking(true)
  .withModule(ExampleForyModule)
  .build()
```

Generated schema modules are also Fory modules. Use `.withModule(...)` when
creating a custom Fory instance, or use the generated no-argument `toBytes` and
`fromBytes` helpers when the generated default Fory instance is sufficient.

Generated helpers register message type identities before installing message
serializers. This two-phase order lets mutually recursive message graphs build
descriptor metadata through the normal `TypeResolver` path without temporary
serializers or Scala-specific registration state in Java core. Enums and unions
are registered with their serializers directly because their derived serializers
own case dispatch.

## Generated Messages

Acyclic messages generate case classes:

```scala
import org.apache.fory.annotation.{ForyField, ForyStruct}
import org.apache.fory.scala.ForySerializer

@ForyStruct
final case class Person(
  @ForyField(id = 1) name: String,
  @ForyField(id = 2) email: Option[String]
) derives ForySerializer
```

Schema `optional T` fields are stored as `Option[T]`.

Messages in compiler-detected construction cycles generate normal classes with
mutable serialized fields so the deserializer can allocate and register the
object before reading fields that can point back to it. A top-level `ref Foo`,
nested `list<ref Foo>`, or `any` field does not by itself force this shape.
The compiler analyzes message and union dependencies together, so
message-to-union-to-message cycles also make the participating messages normal
classes. Acyclic owner messages that only contain a cyclic nested type remain
case classes.

Reference tracking is expressed with the shared `@Ref` annotation, including
type-use positions:

```scala
@ForyStruct
final class Node() derives ForySerializer {
  @ForyField(id = 1)
  var children: List[Node @Ref] = List.empty

  @Ref
  @ForyField(id = 2)
  var parent: Option[Node] = None
}
```

`@Ref` is the JVM reference-tracking annotation for Scala macro and IDL APIs.
Use field or constructor-parameter `@Ref` for a top-level `ref T` field. Use
type-use `T @Ref` only for nested element/value/payload refs, such as
`list<ref T>`.

Generated xlang collection fields use immutable Scala collection types:
`List[T]`, `Set[T]`, and `Map[K, V]`. Fory's xlang serializers can also rebuild
supported mutable collection interfaces such as `scala.collection.Seq`
and `scala.collection.Map`, but concrete mutable collection classes are outside
the schema IDL surface unless explicitly generated.

## Generated Enums

IDL enums generate Scala 3 enums only. The compiler does not emit Java enum
files.

```scala
import org.apache.fory.annotation.ForyEnumId

enum Status {
  @ForyEnumId(0)
  case Unknown

  @ForyEnumId(1)
  case Ok
}
```

Generated registration uses `ScalaSerializers.registerEnum(...)` so the stable
Fory enum IDs from case-level `@ForyEnumId` metadata are used in xlang mode.

## Generated Unions

IDL unions generate Scala 3 ADT enums with macro-derived serializers:

```scala
package example

import org.apache.fory.annotation.{ForyCase, ForyUnion, ForyUnknownCase, UInt32Type}
import org.apache.fory.config.Int32Encoding
import org.apache.fory.scala.ForySerializer
import org.apache.fory.`type`.union.UnknownCase

@ForyUnion
enum SearchTarget derives ForySerializer {
  @ForyUnknownCase
  case Unknown(value: UnknownCase)

  @ForyCase(id = 0)
  case User(value: _root_.example.User)

  @ForyCase(id = 1)
  case FixedId(value: Long @UInt32Type(encoding = Int32Encoding.FIXED))
}
```

When a generated Scala union case name matches the payload type simple name,
packaged output keeps the case name and qualifies the payload type. If a target
output mode cannot express a legal qualifier for a conflict, the IDL compiler
appends `Case` to the generated case name.

Schema-defined union cases use non-negative IDs, and a typed union must declare
at least one non-`Unknown` case. The Scala unknown-case carrier is selected by
`@ForyUnknownCase`, not by a schema case ID. Its payload stores the original case
ID and the deserialized value. When a reader sees a newer case ID, it returns
`Unknown(UnknownCase)` instead of failing solely because the case ID is not known
locally.

The macro writes the existing xlang union envelope directly. It does not
allocate temporary Java `Union` carriers.

## Manual Scala 3 Derivation

Manual Scala 3 models can derive the same serializer typeclass:

```scala
@ForyStruct
final class Record(@ForyField(id = 1) val id: Int) derives ForySerializer {
  @ForyField(id = 2)
  var name: String = ""
}
```

The macro generates direct constructor calls for constructor-owned fields and
direct assignments for mutable post-construction fields. It builds descriptor
metadata from Scala compile-time types, including nested generics, `Option`,
arrays, scalar encoding annotations, nullability, and `@Ref` metadata. Java
reflection is not the source of truth for generated Scala metadata.

During copy, cyclic graphs are supported when the copied root can be allocated
and registered before cyclic fields are copied, which is the normal-class shape
used by schema IDL for construction cycles. If a copy starts at an immutable
constructor-owned value that participates in the cycle, such as a Scala enum
case or case class, the serializer fails with a clear error because no copied
identity can be published until construction has completed.
