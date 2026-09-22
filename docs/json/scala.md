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
libraryDependencies += "org.apache.fory" %% "fory-json-scala" % "1.7.5"
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
Use `ForyJsonScala.builder().escapeNonAscii(true)` to escape non-ASCII string contents and names
in compact and pretty output. The setting is fixed per instance; raw JSON remains verbatim.
See [Non-ASCII escaping](object-mapping.md#non-ascii-escaping).

Use `ForyJsonScala.builder().writeLongAsString(true)` to emit Scala `Long` values, including
declared collection and map values, `Option[Long]`, `Long`-backed value classes, and Java Long-like
wrappers as quoted decimal strings. Readers accept both quoted and unquoted integer tokens.
Use `ScalaTypeRef` when a parameterized declaration contains `Long` because normal JVM signatures
can erase Scala value-type arguments to `Object`.

## Byte-array formats

Scala `Array[Byte]` uses Base64 strings by default. Use the standard builder's
`byteArrayFormat` to select numeric arrays or hexadecimal strings, including arrays inside
`Option`, Scala collections, and maps:

```scala
import org.apache.fory.json.annotation.JsonByteArray
import org.apache.fory.json.scala.{ForyJsonScala, ScalaTypeRef}

val hexJson = ForyJsonScala.builder().byteArrayFormat(JsonByteArray.Format.BASE16).build()
val bytesType = ScalaTypeRef[Array[Byte]]
val text = hexJson.toJson(Array[Byte](1, -2, 3), bytesType) // "\"01fe03\""
val bytes = hexJson.fromJson(text, bytesType)
```

`JsonByteArray` on a field or getter, including a Mixin, overrides the default for that property.
See [byte-array formats](object-mapping.md#builder-configuration) for the read/write contract.

## Case classes and annotations

Case classes are decoded by calling their full primary constructor. Fory invokes Scala's generated
constructor-default methods for missing defaulted parameters; it does not parse default expressions
or mutate constructor `val` fields. Defaults in later parameter lists receive the preceding
constructor arguments exactly as Scala defines them. When a constructor parameter has no explicit
default, an omitted property uses its type's default:

- Numeric values use zero, and Boolean values use `false`.
- Collections, maps, and arrays use empty values. Mutable defaults are fresh for each object.
- `Option[A]` uses `None`.
- Other reference values, including strings and nested objects, use `null`.

Mutable body properties retain their initializers when omitted and are applied after construction
when present.

```scala
case class Options(value: Option[Int], selected: Option[Int] = Some(7))
case class Profile(age: Int, enabled: Boolean, tags: List[String], name: String)

json.fromJson("{}", classOf[Options]) // Options(None, Some(7))
json.fromJson("""{"selected":null}""", classOf[Options]) // Options(None, None)
json.fromJson("{}", classOf[Profile]) // Profile(0, false, List(), null)
```

Explicit constructor defaults take precedence for omitted properties. An explicit JSON `null`
decodes as `None` for `Option[A]`, even when its constructor default is `Some(...)`.

A case class may be declared at the top level, or inside an `object` at any nesting depth, as long
as every enclosing scope is itself an `object`. A case class enclosed by a `class`, a trait, or a
method is rejected for both reading and writing, because Fory cannot reach the enclosing instance
or the companion it needs to rebuild the value. Construction requires a public JVM constructor
with a matching public companion `apply`; unsupported private constructor shapes are rejected.

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

Property inclusion controls which values are written. Omitted properties use constructor or type
defaults when read, so omitting an empty string can restore `null`. Use `ALWAYS` when those values
must remain distinct. An explicit JSON `null` keeps the declared type's normal null behavior; it
does not request a constructor default.

`NON_EMPTY` recognizes `None` and supported empty strict Scala sequences, sets, and maps, including
mutable collections and ranges. Filtering is shallow: `Some("")`, `Some(Nil)`, `Some(null)`, and
nonempty containers remain included. It does not traverse lazy collections to determine emptiness.
Root values, array elements, map entries, and tuple positions are not removed.

These Scala codecs implement `isEmpty(writer, value)`. If you replace one with a custom codec,
override that method to preserve the desired omission behavior; its default returns `false`.
For example, a custom Option codec without that override retains `None` fields under `NON_EMPTY`.
Dynamic `Any` properties use the codec selected for the actual value, while a field-specific custom
codec controls its own empty check. See [Custom empty values](custom-codecs.md#custom-empty-values).

Explicitly authorize stable declared defaults with `NON_DEFAULT`:

```scala
import org.apache.fory.json.annotation.JsonProperty.Include
import org.apache.fory.json.annotation.{JsonInclude, JsonProperty}
import org.apache.fory.json.scala.ForyJsonScala

@JsonInclude(Include.NON_DEFAULT)
case class Request(
  @JsonProperty(include = Include.ALWAYS) id: Int,
  retries: Int = 3,
  tags: List[String] = Nil
)

val json = ForyJsonScala.builder().build()
json.toJson(Request(0)) // {"id":0}
json.fromJson("""{"id":0}""", classOf[Request]) // Request(0, 3, Nil)
json.toJson(Request(0, retries = 0)) // {"id":0,"retries":0}
```

`id` is explicitly excluded because it has no declared default; otherwise class-level authorization
would fail. A reader's implicit zero, empty collection, or `None` fallback is not a declared default.
Alternatively, omit the class annotation and place `@JsonProperty(include = Include.NON_DEFAULT)`
only on selected defaulted properties. Mixins support both forms. Global `NON_DEFAULT` is rejected.

Default expressions run during writing and must be deterministic and free of externally visible
side effects. For `case class Limits(low: Int)(val high: Int = low + 1)`, the comparison for `high`
uses the object's actual `low`. For `low=5, high=2`, high is retained because its default is 6.
Missing default methods or unavailable write-schema dependencies cause a model-initialization error.
`Unit` defaults whose JVM methods return `void` are not supported comparison sources; retain those
properties with `ALWAYS` when authorizing the class.
Authorization confirms that missing input restores the same context; Fory does not prove this or
expression purity. Use `ALWAYS` for time-, random-, or state-dependent defaults. Arrays compare by contents, and
floating-point comparisons distinguish positive and negative zero. Class-body initializers are
not inferred as Scala constructor defaults. Values differing from a default, including null and
empty collections, remain written. Reading stays independent and creates fresh mutable defaults.
Class authorization covers future added fields too; see [Default omission](annotations.md#jsoninclude-and-default-omission).

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

Generic case classes preserve their type arguments, including finite nesting of the same class:

```scala
case class Box[A](value: A)

val boxType = ScalaTypeRef[Box[Box[Int]]]
val box = json.fromJson("""{"value":{"value":1}}""", boxType)
json.toJson(box, boxType) // {"value":{"value":1}}
```

Recursive declarations that continually expand their type arguments, such as `Node[A]` containing
`Node[List[A]]`, need a custom codec.

Use `ScalaTypeRef[Unit]` for a `Unit` root value:

```scala
val unitType = ScalaTypeRef[Unit]
json.toJson((), unitType)       // "null"
json.fromJson("null", unitType) // ()
```

`Unit` also works in case-class fields and nested types such as `List[Unit]`, `Array[Unit]`,
and `Option[Unit]`. Each `Unit` value is encoded as JSON `null`. Under the value-or-null
representation of `Option`, `Some(())` writes `null` and reads back as `None`.
Do not pass `classOf[Unit]` to the Java `Class` overload: it denotes JVM `void`, which is
rejected when writing a root value.

`Some[Int]` is a valid declared type when supplied with its complete type argument. A non-null JSON
value decodes to `Some(value)`; JSON `null` is rejected for `Some[Int]` but decodes to `None` for
`Option[Int]`.

## Scala 2 Enumeration

Use `ScalaTypeRef` on Scala 2.13 or Scala 3 to preserve a statically known enumeration owner,
including inside arrays, collections, options, and maps:

```scala
import org.apache.fory.json.scala.{ForyJsonScala, ScalaTypeRef}

object Suit extends Enumeration {
  val Hearts, Clubs = Value
}

val json = ForyJsonScala.builder().build()
val suits = ScalaTypeRef[Array[Suit.Value]]
val values = json.fromJson("""["Hearts","Clubs"]""", suits)
val text = json.toJson(values, suits)
```

The owner is selected for each type occurrence. Different enumerations can coexist in one runtime;
no registration for the shared `Enumeration.Value` class is needed. Type aliases that retain the
owner also work. An erased `Enumeration#Value`, `Class`, or ordinary JVM `TypeRef` cannot recover
the owner. In particular, case-class properties discovered through JVM reflection need
`JsonEnumeration` when their signature has erased the owner. Use this annotation on a direct value,
collection or array element, `Option` content, or map key/value:

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

## Singleton sealed ADTs

On Scala 2.13 and Scala 3, explicitly select `ScalaJsonCodec.stringEnum[T]` to encode a closed
sealed hierarchy of singleton cases as JSON strings:

```scala
import org.apache.fory.json.scala.{ForyJsonScala, ScalaJsonCodec, ScalaTypeRef}

sealed trait Color
case object Red extends Color
case object Blue extends Color

val json = ForyJsonScala.builder()
  .registerCodec(classOf[Color], ScalaJsonCodec.stringEnum[Color])
  .build()
val colors = ScalaTypeRef[Array[Color]]
val text = json.toJson(Array[Color](Red, Blue), colors) // ["Red","Blue"]
val values = json.fromJson(text, colors)
```

The compiler discovers the cases and their names, including cases beneath sealed intermediate
branches. No handwritten name-to-member mapping is required. An open abstract branch or a case
with constructor parameters is rejected at compilation. Unknown input names are rejected; names
never identify classes to load. Case names do not depend on an overridden `toString`.

This representation is opt-in. `ScalaJsonCodec.derived[T]` retains the wrapper-object representation
and is also available for explicitly registered Scala 2 sealed hierarchies. Scala 3 `derives` and
parameterless Scala 3 enum defaults are unchanged. `null` remains JSON `null`.

## Scalar strings

Use `JsonFormat(shape = JsonFormat.Shape.STRING)` on a Boolean or numeric property to write its
scalar token as a JSON string. Reading accepts both strings and native scalar tokens:

```scala
import org.apache.fory.json.annotation.{JsonFormat, JsonMixin}
import org.apache.fory.json.scala.ForyJsonScala

case class Artifact(expired: Boolean, size: Long)

@JsonMixin(target = classOf[Artifact])
abstract class ArtifactMixin {
  @JsonFormat(shape = JsonFormat.Shape.STRING) var expired: Boolean = false
}

val json = ForyJsonScala.builder().registerMixin(classOf[ArtifactMixin]).build()
val text = json.toJson(Artifact(false, 7L)) // {"expired":"false","size":7}
```

The annotation may instead be placed directly on a constructor property. A Mixin keeps the model
unchanged and uses only Fory annotations. Scala `BigInt` and `BigDecimal` are supported along with
primitive and boxed Boolean/numeric types. See [Annotations](annotations.md#jsonformat) for null,
non-finite number, and supported direct-wrapper behavior.

## Scala 3 closed enums and sealed hierarchies

A parameterless Scala 3 enum uses its case name as a JSON string, including as the key of a typed
Scala map such as `Map[Color, String]`. Add `derives ScalaJsonCodec` to an
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
