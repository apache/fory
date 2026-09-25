---
title: Kotlin
sidebar_position: 8
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

Fory JSON Kotlin maps Kotlin/JVM types to ordinary JSON while preserving Kotlin constructor
defaults, nullability, value types, and generic arguments. It is an optional module layered on
Fory JSON; it does not change Fory's binary protocols.

## Installation

The runtime accepts model metadata supported by Kotlin's strict metadata reader and is built with
Kotlin 2.3.20. Use the same Fory version for every module:

```kotlin title="build.gradle.kts"
plugins {
  kotlin("jvm") version "2.3.20"
}

repositories {
  maven("https://repository.apache.org/snapshots/") {
    mavenContent { snapshotsOnly() }
  }
  mavenCentral()
}

dependencies {
  implementation("org.apache.fory:fory-json-kotlin:1.7.5")
}
```

The Kotlin module does not require `kotlin-reflect`. On Android, add KSP when R8 or ProGuard is
enabled or when a Kotlin-source Mixin adds inferred `JsonSubTypes` to a Java sealed target:

```kotlin title="build.gradle.kts"
plugins {
  id("com.google.devtools.ksp") version "2.3.8"
}

dependencies {
  ksp("org.apache.fory:fory-json-kotlin-ksp:1.7.5")
}
```

Annotate every Kotlin source model that needs exact minification retention with `@JsonType`. For a
third-party target, declare an exact `@JsonMixin` in application source instead. Use Kotlin KSP for
a Mixin when either the Mixin or its exact target is Kotlin. If a Kotlin-source Mixin adds inferred
`JsonSubTypes` to a Java sealed target, also enable `fory-annotation-processor` and compile on JDK 17
or newer.

## Quick start

Use `ForyJsonKotlin.builder()` to install the Kotlin module. Retain a `jsonTypeRef<T>()` for every
declared Kotlin root whose nullability, unsigned identity, value-class identity, or generic
arguments matter:

```kotlin
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef

data class Account(
  val id: ULong,
  val name: String,
  val nickname: String? = null,
)

val json = ForyJsonKotlin.builder().build()
val accountType = jsonTypeRef<Account>()

val text = json.toJson(Account(7u, "Alice"), accountType)
val decoded = json.fromJson(text, accountType)
```

Use `ForyJsonKotlin.builder().escapeNonAscii(true)` to escape non-ASCII string contents and names
in compact and pretty output. The setting is fixed per instance; raw JSON remains verbatim.
See [Non-ASCII escaping](object-mapping.md#non-ascii-escaping).

Use `ForyJsonKotlin.builder().writeLongAsString(true)` when signed `Long` and unsigned `ULong`
values must be emitted as quoted decimal strings. The setting also applies to their declared
collection and map values, nullable values, Kotlin value classes backed by them, `ULongArray`, and
the Java Long-like wrappers supported by the core JSON runtime. Readers accept both quoted and
unquoted integer tokens.

`ByteArray` uses Base64 strings by default. The standard builder's `byteArrayFormat` also controls
root arrays and arrays nested in properties, collections, and maps:

```kotlin
import org.apache.fory.json.annotation.JsonByteArray
import org.apache.fory.json.kotlin.ForyJsonKotlin
import org.apache.fory.json.kotlin.jsonTypeRef

val hexJson = ForyJsonKotlin.builder().byteArrayFormat(JsonByteArray.Format.BASE16).build()
val bytesType = jsonTypeRef<ByteArray>()
val text = hexJson.toJson(byteArrayOf(1, -2, 3), bytesType) // "\"01fe03\""
val bytes = hexJson.fromJson(text, bytesType)
```

`@field:JsonByteArray` or `@get:JsonByteArray`, including a Mixin declaration, overrides the default
for that property. `UByteArray` retains its unsigned numeric-array representation. See
[byte-array formats](object-mapping.md#builder-configuration) for accepted input and null behavior.

`jsonTypeRef<T>()` is a type token, not a codec lookup. Construct it once and reuse it. A Java
`Class` or ordinary Java `TypeRef` cannot express distinctions such as `List<Account?>`, `UInt`, or
a logical value class lowered to a primitive carrier.

The builder can also install the module explicitly:

```kotlin
import org.apache.fory.json.ForyJson
import org.apache.fory.json.kotlin.ForyJsonKotlin

val json = ForyJson.builder().withModule(ForyJsonKotlin).build()
```

There is no automatic classpath installation or Kotlin-specific encode/decode alias.

For framework callbacks with a runtime Kotlin `KType`, use `jsonTypeRef<Any?>(kType)`.
See [Kotlin integration](integration.md#kotlin-integration) for controller type discovery,
request and response conversion, and Spring MVC adapter requirements.

## Immutable classes and compiler defaults

An ordinary or data class is mapped as a named JSON object. Fory selects one valid public Kotlin
constructor and reconstructible properties; `copy` and `componentN` functions do not define the
schema. A compiler-generated default is used only when its JSON member is absent:

```kotlin
data class Request(
  val id: Long,
  val label: String? = "new",
  val retries: Int = 3,
)
```

The primary constructor is automatic. A public secondary constructor or target-owned public static
factory is used only when explicitly selected by `JsonCreator`; a companion factory qualifies only
through its real outer-class `@JvmStatic` bridge. `@JvmOverloads` artifacts are not separate
creator candidates, and a selected static factory cannot have compiler-default parameters.
Private/protected, vararg, executable-generic, context-parameter, local,
anonymous, `inner`, or synthetic construction requires an exact application codec.

For this model:

- `{"id":1}` invokes both compiler defaults.
- `{"id":1,"label":null}` passes an explicit null and does not invoke the `label` default.
- a missing `id` uses `0`.
- `{"id":1,"retries":null}` fails; null never asks Kotlin to use a default.

Enable `ForyJsonKotlin.builder().failOnMissingRequiredProperties(true).build()` to require ordinary
constructor properties without declared defaults, including nullable properties and numeric or
Boolean properties. Compiler defaults still apply when declared. For example, a missing `id` in
the model above is then an error, while missing `label` and `retries` still use their defaults.
Nullability alone does not make a property optional in this mode: a nullable property without a
default must appear, but its value may be explicit null. This option defaults to `false` and does
not change writing, body-property behavior, or existing non-null constraints.

Normal body `var` properties preserve their initializer when absent and are assigned after
construction when present. A `lateinit` property is required. Automatic creator and deferred
properties must be reconstructible in both read and write directions.

A body `val`, computed or delegated property, getter-only property, or delegated `var` must be
ignored or handled by an exact custom codec. Present deferred setters run in fixed property order
after construction, followed by validators; input member order does not choose application call
order. Kotlin class instances are always constructed normally, so primary-constructor initialization and
validation are not bypassed.

Kotlin properties follow the configured [property inclusion](annotations.md#jsonproperty),
including constructor parameters and body properties. The default `NON_NULL` omits null values;
`NON_EMPTY` also omits empty strings, arrays, collections, and maps, and absent JDK Optional values.
A property's `@JsonProperty(include = ...)` overrides the class's `@JsonInclude` policy, which
overrides the builder default. Use `ALWAYS` or
`writeNullFields(true)` to retain null values.

```kotlin
import org.apache.fory.json.annotation.JsonProperty.Include
import org.apache.fory.json.kotlin.ForyJsonKotlin

data class Response(
    val id: Int,
    val name: String? = null,
    val items: List<String>? = null,
)

val json = ForyJsonKotlin.builder().defaultPropertyInclusion(Include.NON_EMPTY).build()
val text = json.toJson(Response(1, items = emptyList())) // {"id":1}
```

Inclusion affects writing only. Reading the example's output uses the declared `items` default
of null, so the original empty list is not preserved. A missing constructor parameter uses its
declared default first. Otherwise, non-null numeric and Boolean parameters use zero and `false`,
nullable parameters use `null`, and other non-null parameters remain required. A missing body
property keeps its initializer.
Explicit null remains distinct from a missing field and is rejected for a non-nullable property.
Choose an inclusion rule that retains values when exact round trips are required.

`NON_DEFAULT` requires explicit field authorization with `@JsonProperty`, or class authorization
with `@JsonInclude`. Comparing against a reference object is supported when **every parameter of the
selected constructor has a language default**, or the model uses an ordinary no-argument constructor. Fory constructs one
reference object per initialized model metadata and captures authorized property values. This
executes the complete constructor, all initializers, and `init` blocks. It is not repeated on each
write, and models without authorization do not construct a reference. Dynamic and declared model
occurrences can initialize separately.

Both field-level and class-level authorization retain constructor properties without defaults and
`lateinit` properties, including null, zero, false, and empty values. A defaulted property that needs
a reference object is rejected when the selected constructor also has required parameters, even if
another constructor has no arguments.
For example, class-level `NON_DEFAULT` on `data class Key(val id: Int)` writes `{"id":0}` for `Key(0)`.
For `data class Entry(val id: Int, val count: Int = 1)`, the same class policy cannot compare `count`
without inventing `id`; give `count` an `ALWAYS` override to retain it.
Fory does not fabricate parameters, borrow them from the first object, change creator
selection, bypass a constructor, or analyze bytecode. Failed construction reports the model and
property. Global `defaultPropertyInclusion(NON_DEFAULT)` is rejected.

Kotlin metadata indicates default presence but does not reveal expressions or dependencies. The
caller must confirm stable defaults, no externally visible side effects from reference construction,
and matching recovery for missing fields. A fixed reference cannot represent defaults that depend
on the current input, time, randomness, or external state. Exclude those properties:

```kotlin
import org.apache.fory.json.annotation.JsonInclude
import org.apache.fory.json.annotation.JsonProperty
import org.apache.fory.json.annotation.JsonProperty.Include
import org.apache.fory.json.kotlin.ForyJsonKotlin

@JsonInclude(Include.NON_DEFAULT)
data class Limits(
  val low: Int = 1,
  @param:JsonProperty(include = Include.ALWAYS) val high: Int = low + 1
)

val json = ForyJsonKotlin.builder().build()
json.toJson(Limits()) // {"high":2}
json.toJson(Limits(5, 2)) // {"low":5,"high":2}
json.fromJson("""{"low":5}""", Limits::class.java) // Limits(5, 6)
```

Here `high=2` must remain present when `low=5`: omitting it would restore `high=6`. Parameter
dependencies are not universally unsafe, but this fixed reference cannot supply their changing
context. To authorize only individual fields, omit `JsonInclude` and put
`@param:JsonProperty(include = Include.NON_DEFAULT)` on those fields. A registered Mixin may provide
the class or field policy without editing the model. Class authorization also covers future fields;
maintainers must verify each new default or exclude it with `ALWAYS`.

Reading still invokes the normal Kotlin defaults for missing fields. Explicit null is not missing.
The reference object and its mutable collections are never shared with decoded objects. Comparison
uses primitive values, reference equality contracts (`equals`), and array contents; see
[Default omission](annotations.md#jsoninclude-and-default-omission). When minifying, use the existing
JSON KSP setup. Default evaluation takes place at runtime, not during KSP processing.

An empty list is omitted by `NON_EMPTY` regardless of whether its default is null, `emptyList()`, or
a non-empty list. Empty underlying string or collection carriers do not make non-null value-class
properties empty. An explicit `NON_EMPTY` is unsupported when an unboxed value class itself
implements `CharSequence`, `Collection`, or `Map`; use a containing custom codec for that shape.

## Nullability

Kotlin occurrence nullability is enforced at roots, properties, container elements, map values,
and generic children:

| Declaration                       | Missing member        | Explicit JSON `null` |
| --------------------------------- | --------------------- | -------------------- |
| `val value: String`               | Fails                 | Fails                |
| `val value: String?`              | Passes null           | Passes null          |
| `val value: Int`                  | Uses `0`              | Fails                |
| `val value: Boolean`              | Uses `false`          | Fails                |
| `val value: Int?`                 | Passes null           | Passes null          |
| `val value: String = expression`  | Evaluates the default | Fails                |
| `val value: String? = expression` | Evaluates the default | Passes null          |

`List<String?>` accepts null elements; `List<String>` rejects them. Map keys must be non-null.
Platform/unknown nullability is not guessed for automatic Kotlin construction.

Transparent wrappers must have one unambiguous null meaning. For example, non-null
`Optional<T>` uses JSON null for `Optional.empty()`, so `Optional<T>?` and `Optional<T?>` are
rejected. `AtomicReference<T>` and value classes follow the same injective-shape rule. Use an exact
custom tagged codec when two logical states would otherwise share JSON null.

An application codec selected by registration or `@JsonCodec` remains trusted application code.
For a non-null JSON token, it must return the exact declared type and honor Kotlin occurrence
nullability.

## Annotations and use-site targets

Kotlin annotations feed the same logical-property merge used by Java. Prefer explicit use-site
targets:

```kotlin
import org.apache.fory.json.annotation.JsonCodec
import org.apache.fory.json.annotation.JsonIgnore
import org.apache.fory.json.annotation.JsonProperty
import org.apache.fory.json.annotation.JsonType

@JsonType
data class Profile(
  @param:JsonProperty("user_id")
  val id: Long,

  @field:JsonIgnore
  val localCacheKey: String? = null,

  @field:JsonProperty(include = JsonProperty.Include.ALWAYS)
  val nickname: String? = null,

  @field:JsonCodec(elementCodec = AccountIdCodec::class)
  val accountIds: List<AccountId>,
)
```

Use explicit JVM use-site targets as shown so behavior does not depend on Kotlin's default-target
policy. See [Annotations](annotations.md#kotlin-use-site-targets) for the complete supported-target
and conflict rules, and [Custom Codecs](custom-codecs.md) for complete-value, element, content, key,
and map-value codecs.

## Generics and collections

Use complete declared types:

```kotlin
import org.apache.fory.json.kotlin.jsonTypeRef

val accountsType = jsonTypeRef<List<Account?>>()
val accounts = json.fromJson("""[null,{"id":7,"name":"Alice"}]""", accountsType)
```

Kotlin raw strings can be passed directly to `fromJson`; JSON double quotes do not need backslash
escaping.

Raw generic types, `in` projections, and star projections are rejected for typed restoration. An
`out X` projection is accepted only when it normalizes to one exact final or closed readable
schema. Recursive generic models are supported when recursion returns to the same exact binding;
an active raw declaration that expands to a different binding is rejected.

Kotlin read-only and mutable collection interfaces use the normal JSON collection/map behavior.
Read-only does not mean immutable: standard interfaces materialize as `ArrayList`,
`LinkedHashSet`, or `LinkedHashMap`. Declare public interfaces instead of JDK/Kotlin private empty,
singleton, unmodifiable, or builder implementation classes. `Iterable`, `Sequence`, iterators,
`EnumEntries`, and a directly declared `Map.Entry` are not automatic value schemas.

Unsigned and eligible non-null value-class map keys use JSON object member names through the
normal map codec. A value-class key chain must terminate in String, enum, signed integer, or
unsigned integer semantics. Floating, Boolean, nullable, and arbitrary object keys require an
explicit key or whole-map codec.

## Value classes, objects, and closed hierarchies

A user value class has the transparent JSON shape of its underlying value:

```kotlin
import org.apache.fory.json.kotlin.jsonTypeRef

@JvmInline
value class AccountId(val value: ULong)

val idType = jsonTypeRef<AccountId>()
val id = json.fromJson("18446744073709551615", idType)
```

Fory executes the compiler's validated constructor operation, so value-class initialization checks
still run. If both the outer value and its underlying value can be null, transparent JSON is
ambiguous and automatic mapping is rejected. Use a tagged exact codec for that case.

A stateless `object` or `data object` uses strict `{}` and returns the canonical singleton. A
stateful object and a companion object require an exact custom codec. `Unit` also uses `{}`;
`Nothing` is rejected, while `Nothing?` accepts only JSON null through its explicit Kotlin type
token.

Annotate a sealed class or interface with `JsonSubTypes` and leave `value` empty to infer its closed
hierarchy. Fory recursively includes concrete sealed descendants and stops at each concrete open
class, admitting that exact class but not its descendants. An open abstract branch is rejected.
Inferred logical names are source simple names, including object names without a trailing `$`. A
non-empty `value` remains an exact explicit subset. Input contains a logical subtype name, never a
JVM class name. See [Annotations](annotations.md#jsonsubtypes) for the property and wrapper shapes.

## Supported Kotlin types

Java/JDK scalar, temporal, Optional, atomic, array, collection, map, enum, and JSON tree types keep
their normal Fory JSON representation when used from Kotlin:

| Core family                        | Kotlin-visible behavior                                                                                                                                                                                                                                                                    |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Any` / `Any?`                     | natural JSON Boolean, number, String, array, object, or null; dynamic runtime dispatch on writes                                                                                                                                                                                           |
| signed scalars and boxes           | `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `Char`, and `Number` use the core scalar codecs; non-finite floating values use quoted core forms                                                                                                                            |
| text                               | `String`, exact `CharSequence`, `StringBuilder`, and `StringBuffer` use String shapes                                                                                                                                                                                                      |
| arbitrary/reduced-precision number | `BigInteger`, `BigDecimal`, Fory `Float16`, and `BFloat16` use their core numeric shapes and limits                                                                                                                                                                                        |
| enum                               | quoted enum constant name                                                                                                                                                                                                                                                                  |
| Java/Kotlin arrays                 | normal JSON arrays except `ByteArray`, which uses Base64 strings by default; `@field:JsonByteArray(JsonByteArray.Format.ARRAY)` selects numeric arrays; unsigned semantic arrays are listed below                                                                                          |
| Optional and atomic                | `Optional<T>`, primitive Optionals, atomic scalars/references, and atomic arrays keep their transparent core shapes subject to the nullability rules above                                                                                                                                 |
| quoted JDK values                  | `Currency`, `File`, `URI`, `Path`, `Pattern`, `UUID`, `Locale`, `Charset`, and `TimeZone` keep their core String shapes                                                                                                                                                                    |
| legacy date/time                   | `Date`, `Calendar`, and available `java.sql.Date`, `Time`, and `Timestamp` keep their epoch-millisecond shapes                                                                                                                                                                             |
| Java time                          | `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `java.time.Duration`, `ZoneOffset`, `ZoneId`, `ZonedDateTime`, `Year`, `YearMonth`, `MonthDay`, `Period`, `OffsetTime`, `OffsetDateTime`, and supported chronology dates keep their exact core text grammars                         |
| other core values                  | `BitSet`, `ByteBuffer`, `JsonArray`, and `JsonObject` keep their normal core shapes                                                                                                                                                                                                        |
| collections/maps                   | supported Java/Kotlin `Collection` and `Map` interfaces and implementations use core array/object shapes; supported Guava immutable carriers remain optional                                                                                                                               |
| map keys                           | String, enum, signed `Byte`/`Short`/`Int`/`Long`, and the Kotlin unsigned/value-class additions below; Boolean, floating, nullable, and arbitrary object keys need an explicit key or whole-map codec                                                                                      |
| fixed rejections                   | `Class`, URL/network/socket/address families, unsupported JDK internal collection implementations, and unregistered `Number`/`CharSequence` subclasses remain rejected; an application may explicitly own a permitted custom representation subject to the normal type and security checks |

See [Object Mapping](object-mapping.md#supported-java-types) for the detailed core shapes.
Kotlin-specific behavior is:

| Type family                                                                                | Automatic JSON shape or decision                                                   |
| ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| ordinary, data, and nested classes                                                         | named JSON object                                                                  |
| `inner` class, ordinary abstract class/interface                                           | closed `JsonSubTypes` or exact custom codec only                                   |
| sealed class/interface                                                                     | inferred or explicit closed `JsonSubTypes` table                                   |
| enum                                                                                       | quoted enum name                                                                   |
| stateless `object`, `data object`, `Unit`                                                  | strict `{}`                                                                        |
| stateful object, companion object                                                          | exact custom codec only                                                            |
| value class                                                                                | transparent underlying value; exact binding required                               |
| `Nothing?` / `Nothing`                                                                     | null-only / rejected                                                               |
| `Pair`, `Triple`                                                                           | named objects with `first`, `second`, and `third`                                  |
| `Result`, `Lazy`, Kotlin standard property delegates                                       | exact custom codec only                                                            |
| signed primitives, primitive arrays, `Array<T>`                                            | normal core number/Boolean/character and array shapes                              |
| `UByte`, `UShort`, `UInt`, `ULong` and their arrays                                        | unsigned decimal numbers and arrays                                                |
| read-only and mutable collections/maps, Kotlin `ArrayDeque`                                | normal core arrays/maps                                                            |
| `Map.Entry`, private collection carriers, `Iterable`, `Sequence`, iterators, `EnumEntries` | rejected automatically                                                             |
| `CharRange`, signed/unsigned integer ranges                                                | `{"start":...,"endInclusive":...}`                                                 |
| corresponding progressions                                                                 | `{"first":...,"last":...,"step":...}`                                              |
| `ClosedRange`, `OpenEndRange`, abstract/open/floating ranges                               | rejected automatically                                                             |
| `kotlin.time.Duration`                                                                     | quoted canonical Kotlin ISO duration                                               |
| `kotlin.time.Instant`                                                                      | quoted canonical Kotlin ISO instant                                                |
| `TimedValue<T>`                                                                            | `{"value":...,"duration":...}`                                                     |
| `DurationUnit`, `RegexOption`                                                              | quoted enum name                                                                   |
| clocks, time sources/marks, Regex/match state, Random                                      | exact custom codec only                                                            |
| `kotlin.uuid.Uuid`                                                                         | quoted canonical dashed UUID                                                       |
| complete generic class / declaration-site variance                                         | exact substituted schema                                                           |
| `out X` / `in X` / star projection                                                         | exact final-or-closed `X` only / rejected / rejected                               |
| recursive generic                                                                          | same exact recursive binding only; active expansion to another binding is rejected |
| typealias                                                                                  | its fully expanded type                                                            |
| function/suspend function, reflection types, coroutine/flow/channel state                  | rejected                                                                           |
| eligible third-party immutable Kotlin model                                                | automatic on the JVM; register an exact Mixin when applying annotation overlays    |

Kotlin experimental opt-in requirements for time and UUID APIs still apply to application source.
The Fory artifact's supported compiler and metadata boundary does not turn an experimental Kotlin
API into a cross-version Kotlin guarantee.

## Security

`jsonTypeRef<T>()`, annotations, Mixins, and codec registrations are application-declared schema.
JSON input cannot select an arbitrary class, constructor, compiler default, object, companion,
module, codec, or callable. A sealed hierarchy accepts only the logical subtype names in its
validated inferred or explicit `@JsonSubTypes` table.

Kotlin arrays, collections, maps, and objects use the same `maxDepth`, graph-memory, input-buffer,
field-name-cache, and type-checker controls as the core JSON runtime. There are no Kotlin-specific
collection or workspace limits to configure. Model constructors, compiler defaults, validators,
and custom codecs remain trusted application code and may have application-defined allocation or
side effects.

See [Security](security.md) before decoding untrusted input.

## GraalVM and Android

On GraalVM Native Image, use the existing `@ForyJsonProvider` workflow, install
`ForyJsonKotlin`, and enable code generation in the returned configuration. Annotate each reachable
concrete Kotlin model with `@JsonType`, or register an exact reachable Mixin for a third-party
target. Models not selected for code generation continue to use interpreted mapping. Only exact
generic bindings reached through concrete roots are available. Do not add reflection configuration
or package-wide opens.

On Android, use API 26 or later. Runtime JSON code generation remains disabled. Kotlin sealed
inference needs no additional setup in an unminified build. Enable KSP when R8 or ProGuard is used
or when a Kotlin-source Mixin adds inferred `JsonSubTypes` to a Java sealed target. The Mixin case
also requires `fory-annotation-processor` and JDK 17 or newer. Follow the
[installation](#installation) above and the [Android guide](android.md) when shrinking is enabled.

Kotlin/Native, Kotlin/JS, and Kotlin/Wasm are not supported by this JVM module.

See [GraalVM Native Image](graalvm.md) and [Android](android.md) for complete platform setup.

## Troubleshooting

See [Troubleshooting](troubleshooting.md) for Kotlin metadata, nullability, generic binding,
Android shrinking, Native Image, syntax, limits, custom codecs, subtypes, and root-operation
failures.

The source-aligned four-library benchmark methodology and publication status are in the
[Kotlin JSON benchmark report](../benchmarks/json/kotlin/README.md). No Kotlin result is inferred
from the Java or Scala benchmark.
