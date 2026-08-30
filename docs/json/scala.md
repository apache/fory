---
title: Scala
sidebar_position: 7
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

Fory JSON supports Scala 2.13 and Scala 3 through the optional `fory-json-scala` artifact. The
module works on the ordinary JVM and GraalVM Native Image. Android is not supported.

## Setup

```sbt
libraryDependencies += "org.apache.fory" %% "fory-json-scala" % "1.7.0"
```

`ForyJsonScala.builder()` installs the Scala module and returns the standard Fory JSON builder:

```scala
import org.apache.fory.json.scala.ForyJsonScala

case class Person(name: String, age: Int = 18, aliases: List[String] = Nil)

val json = ForyJsonScala.builder().build()
val text = json.toJson(Person("Ada"))
val person = json.fromJson(text, classOf[Person])
```

Reuse the resulting `ForyJson` instance. It is immutable and thread-safe after construction.

## Case classes and annotations

Case classes are decoded by calling their full primary constructor. Fory invokes Scala's generated
constructor-default methods for missing defaulted parameters; it does not parse default expressions
or mutate constructor `val` fields. Defaults in later parameter lists receive the preceding
constructor arguments exactly as Scala defines them. A missing parameter without a default is an
error. Mutable body properties are applied after construction.

A case class may be declared at the top level, or inside an `object` at any nesting depth, as long
as every enclosing scope is itself an `object`. A case class enclosed by a `class`, a trait, or a
method is rejected for both reading and writing, because Fory cannot reach the enclosing instance
or the companion it needs to rebuild the value.

Fory JSON annotations can be placed directly on Scala constructor properties:

```scala
import org.apache.fory.json.annotation.{JsonCodec, JsonIgnore, JsonProperty}

case class Media(
    @JsonProperty("media_uri") uri: String,
    @JsonIgnore internalId: String = "hidden",
    @JsonCodec(elementCodec = classOf[TagCodec]) tags: List[Tag] = Nil,
    @JsonProperty(include = JsonProperty.Include.NON_NULL) title: String = null
)
```

`JsonIgnore` applies to fields, property methods, setter parameters, and selected constructor
parameters. `JsonCodec` child slots bind direct collection elements, `Option` content, and map keys
or values. All other Fory JSON annotations retain the behavior described in
[Annotations](annotations.md).

If a required non-defaulted reference parameter uses an inclusion rule that would omit `null`,
serialization rejects a null value. This guarantees that JSON written by Fory remains readable by
the same case-class schema.

## Supported Scala types

| Scala type                                                  | JSON representation                             |
| ----------------------------------------------------------- | ----------------------------------------------- |
| `Unit`                                                      | `null`                                          |
| case class                                                  | object                                          |
| singleton object                                            | empty object                                    |
| value class                                                 | underlying value                                |
| `Option[A]`, `Some[A]`, `None`                              | contained value or `null`                       |
| `Either[L, R]`                                              | object containing exactly one `l` or `r` member |
| `List`, `Seq`, `Vector`, `Queue`, `ArraySeq`, buffers, sets | array                                           |
| Scala maps, `IntMap`, `LongMap`                             | object                                          |
| immutable and mutable `BitSet`                              | ascending integer array                         |
| `Tuple1` through `Tuple22`                                  | fixed-length array                              |
| Scala 3 `EmptyTuple`                                        | empty array                                     |
| `BigInt`, `BigDecimal`                                      | JSON number                                     |
| Scala `StringBuilder`                                       | string                                          |
| `Range`, supported `NumericRange`                           | realized value array                            |
| `FiniteDuration`, `Duration`                                | fixed `length`/`unit` or `special` object       |
| parameterless Scala 3 enum                                  | string case name                                |
| Scala 2 `Enumeration`                                       | string through an owner-bound codec             |

Strict standard-library collections are reconstructed through their standard Scala builders.
`Either` writes compact `l` and `r` member names. Readers also accept the legacy `left` and
`right` member names.
Fory does not add a Scala-specific collection-size limit; the codecs use the same input-length,
depth, graph-memory, and read-progress limits as Fory JSON core. A sparse `BitSet` whose highest
index would require backing storage disproportionate to the available JSON input is rejected.

Lazy or process-local values are intentionally unsupported by the default module, including
`LazyList`, `Stream`, views, iterators, collection builders, `Try`, `Throwable`, `Future`, `Promise`,
`ExecutionContext`, `Deadline`, functions, reflection/compiler metadata, and regex values. Sorted
or custom collections need an exact application codec because their ordering or construction is
application configuration.

## Parameterized types

Use a complete `TypeRef` when reading a parameterized Scala type:

```scala
import org.apache.fory.reflect.TypeRef

val typeRef = new TypeRef[Map[String, Option[Int]]]() {}
val value = json.fromJson("""{"count":1}""", typeRef)
```

Scala raw strings can be passed directly to `fromJson`; JSON double quotes do not need backslash
escaping.

Scala value-type arguments can erase to `Object` in a normal JVM signature. `ScalaTypeRef` is a
compile-time type-token constructor that preserves those arguments on Scala 2.13 and Scala 3:

```scala
import org.apache.fory.json.scala.ScalaTypeRef

val rangeType = ScalaTypeRef[scala.collection.immutable.NumericRange[Int]]
val range = json.fromJson("[1,3,5,7]", rangeType)
```

`Some[Int]` is a valid declared type when supplied with its complete type argument. A non-null JSON
value decodes to `Some(value)`; JSON `null` is rejected for `Some[Int]` but decodes to `None` for
`Option[Int]`.

## Scala 2 Enumeration

Scala 2 erases the owning `Enumeration` from `Enumeration#Value`. Use `JsonEnumeration` to retain
the owner on a direct value, collection or array element, `Option` content, or map key/value:

```scala
import org.apache.fory.json.scala.JsonEnumeration

object Weekday extends Enumeration {
  val Monday, Tuesday = Value
}

object Month extends Enumeration {
  val January, February = Value
}

case class Schedule(
    @JsonEnumeration(classOf[Weekday.type]) day: Weekday.Value,
    @JsonEnumeration(element = classOf[Weekday.type]) days: List[Weekday.Value],
    @JsonEnumeration(content = classOf[Month.type]) month: Option[Month.Value],
    @JsonEnumeration(
      mapKey = classOf[Weekday.type],
      mapValue = classOf[Month.type]
    ) labels: Map[Weekday.Value, Month.Value]
)
```

Each slot describes one direct `Enumeration.Value` occurrence. `value` cannot be combined with a
child slot, and `element`, `content`, and map slots must match the annotated property's immediate
type shape. Invalid or conflicting declarations fail when the case-class metadata is created.

For a custom wire representation, extend `ScalaEnumerationCodec` and select the codec through
`@JsonCodec`. The codec also implements the map-key contract, so its class can be used in
`keyCodec`.

## Scala 3 closed enums and sealed hierarchies

A parameterless Scala 3 enum uses its case name as a JSON string. Add `derives ScalaJsonCodec` to an
enum with parameterized cases to define one closed wrapper-object representation for every case:

```scala
import org.apache.fory.json.scala.*

enum Result derives ScalaJsonCodec {
  case Ok(value: String)
  case Error(code: Int)
  case Pending
}

val json = ForyJsonScala.builder().build()
```

The values above use `{"Ok":{"value":"ready"}}`, `{"Error":{"code":7}}`, and
`{"Pending":{}}`. The reader never accepts a class name or chooses a subtype from runtime
reflection. For a third-party enum that cannot add `derives`, derive and register its schema at the
builder call site:

```scala
val json = ForyJsonScala.builder().register[thirdparty.Result].build()
```

For a Scala 3 sealed trait or class, add an empty `JsonSubTypes` annotation and derive
`ScalaJsonCodec`:

```scala
import org.apache.fory.json.annotation.JsonSubTypes
import org.apache.fory.json.scala.*

@JsonSubTypes(property = "kind")
sealed trait Event derives ScalaJsonCodec

final case class Message(value: String) extends Event
case object Idle extends Event
```

This example uses `Message` and `Idle` as logical subtype names. Derivation recursively traverses
sealed branches. A concrete open class is one exact member and its descendants are not admitted; an
open abstract branch is rejected. A non-empty annotation value remains an explicit subset. Scala 2
sealed traits and classes are not supported by this inference feature.

### Packaging Derived Codecs in a Module

A library that supports several third-party Scala 3 enums can package their derived codecs in a
reusable module:

```scala
import org.apache.fory.json.{ForyJsonModule, ModuleContext}
import org.apache.fory.json.scala.*

object ThirdPartyJsonModule extends ForyJsonModule:
  override def install(context: ModuleContext): Unit =
    context.registerCodec(
      classOf[thirdparty.Result],
      ScalaJsonCodec.derived[thirdparty.Result]
    )

val json =
  ForyJsonScala.builder()
    .withModule(ThirdPartyJsonModule)
    .build()
```

The derivation is compiled as part of the module, so consumers only install the compiled module.
This is the reusable equivalent of calling `register[thirdparty.Result]` on one builder.

Modules are installed explicitly with `withModule`. Fory JSON does not scan the classpath or invoke
modules through `ServiceLoader`; explicit installation keeps the enabled codecs deterministic and
prevents an unrelated dependency from changing deserialization behavior. See
[Modules](modules.md) for the general module API and registration rules.

## GraalVM Native Image

The Scala module uses the same registration on the JVM and in a native image. Application models,
custom codecs, and derived enum or sealed schemas must be reachable when the native image is built.
Generate Fory codecs as part of the native-image build rather than adding general reflection
configuration.
