---
title: Object Mapping
sidebar_position: 3
id: object-mapping
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

## Thread safety, reuse, and code generation

`ForyJson` is immutable and thread-safe after `build()`. Reuse one instance instead of creating a
builder and `ForyJson` instance for every operation. Registered and annotation-selected `JsonValueCodec`
instances and the `JsonTypeChecker` may be called concurrently and must also be thread-safe.

Code generation and asynchronous compilation are enabled by default. Disabling code generation is
useful for diagnostics or environments that prohibit runtime compilation:

```java
ForyJson json =
    ForyJson.builder()
        .withCodegen(false)
        .withAsyncCompilation(false)
        .build();
```

`withConcurrencyLevel` sets the maximum number of root operations that execute concurrently.
Additional callers wait until one of those fixed execution states is available. Root APIs on one
`ForyJson` instance are not reentrant: a custom codec must continue through the concrete reader or
writer passed to it instead of calling `toJson`, `toJsonBytes`, `writeJsonTo`, or `fromJson` on that
instance.

## Java object mapping

### Default property discovery

By default, Fory JSON builds one logical property from members with the same Java property name:

- eligible instance fields across the class hierarchy, including private, protected,
  package-private, and public fields;
- public non-static JavaBean getters named `getX()`;
- public non-static boolean getters named `isX()`;
- public non-static void setters named `setX(value)`.

Static, transient, synthetic, and `Class<?>` fields are excluded. `getClass()` and accessors whose
value type is `Class<?>` are also excluded. An annotation placed on an ineligible member is rejected
instead of being silently ignored.

An ordinary final field can be written but is not used as a mutable read sink. Use a record,
`JsonCreator`, or a custom codec for immutable construction.

### Field mode

Field mode disables getter and setter discovery while retaining eligible fields:

```java
ForyJson json = ForyJson.builder().withFieldMode(true).build();
```

Annotations on methods are invalid in field mode because those methods are not part of the JSON
property model.

### Construction and input behavior

Fory JSON supports ordinary concrete classes, Java records, and classes with an explicit
`JsonCreator` constructor or factory.

- Records use their canonical constructor.
- Creator-based classes use only the declared creator read schema and do not run setters afterward.
- Unknown object members are skipped.
- An ordinary class with a no-argument constructor runs that constructor before readable
  properties are assigned. Missing properties therefore retain values established by field
  initializers or that constructor.
- On an ordinary JVM, a class without a no-argument constructor is allocated without running its
  constructors or field initializers. Its missing properties retain JVM zero or null values.
- Creator reference parameters default to null and creator primitive parameters default to zero.
- Duplicate ordinary properties use the last value. A polymorphic discriminator is stricter and
  must appear exactly once.
- JSON null is rejected for primitive targets. Most reference targets return null, but a selected
  built-in or custom codec may define another result; for example, declared `Optional` targets
  return `Optional.empty()`.

Android cannot construct an ordinary class without a usable no-argument constructor. GraalVM
native image on JDK 25 and later also requires one for most ordinary classes; the supported
exception is a `Serializable` class whose first non-serializable superclass is `Object`. For a
portable construction contract, use a record, `JsonCreator`, or a no-argument constructor. Do not
use ordinary-constructor side effects as a deserialization completion hook: when a no-argument
constructor runs, property assignment happens afterward, and constructor-bypassing paths do not run
it at all.

## Kotlin object mapping

Install `fory-json-kotlin` and use `ForyJsonKotlin.builder()` for Kotlin/JVM classes. Kotlin
ordinary and data classes use their selected constructor, exact property types, compiler defaults,
and declared nullability; they do not use Java's constructor-bypassing fallback. A default applies
only when the member is missing. An explicit JSON null remains a present value and is rejected for
a non-null parameter.

Use `jsonTypeRef<T>()` for generic, nullable, unsigned, and value-class roots. Standard arrays,
collections, and maps continue to use their normal Fory JSON representation. The complete language
type table, singleton/value-class behavior, and omission rules are in the
[Kotlin guide](kotlin.md).

## Supported Java types

The following groups have built-in mappings. Exact wire representations are stable JSON values, but
application schemas should still declare the intended Java type when precision or construction
matters.

| Group               | Supported types and behavior                                                                                                                                                                                                                         |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Core scalars        | `boolean`, numeric primitives, `char`, their boxed types, `String`, `CharSequence`, `StringBuilder`, `StringBuffer`                                                                                                                                  |
| Numbers             | `Number`, `BigInteger`, `BigDecimal`, Fory `Float16` and `BFloat16`, `AtomicInteger`, `AtomicLong`                                                                                                                                                   |
| Enums               | Enum constant names as JSON strings                                                                                                                                                                                                                  |
| Arrays              | Primitive arrays, boxed arrays, String arrays, object arrays, and multidimensional arrays                                                                                                                                                            |
| Collections         | `Collection`, `List`, `Set`, `Queue`, deque, blocking, sorted, and navigable interfaces; their abstract bases; `EnumSet`; and concrete implementations with an accessible no-argument constructor                                                    |
| Maps                | `Map`, sorted, navigable, and concurrent interfaces; `AbstractMap`; `EnumMap`; and concrete implementations with an accessible no-argument constructor                                                                                               |
| Optional and atomic | `Optional`, `OptionalInt`, `OptionalLong`, `OptionalDouble`, `AtomicBoolean`, `AtomicReference`, and atomic arrays                                                                                                                                   |
| Time                | `Date`, `Calendar`, `TimeZone`, `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `Duration`, `ZoneOffset`, `ZoneId`, `ZonedDateTime`, `Year`, `YearMonth`, `MonthDay`, `Period`, `OffsetTime`, `OffsetDateTime`, and supported chronology dates |
| Other JDK types     | `UUID`, `URI`, `File`, `Path`, `Locale`, `Charset`, `Currency`, `Pattern`, `BitSet`, `ByteBuffer`                                                                                                                                                    |
| Optional modules    | `java.sql.Date`, `Time`, and `Timestamp`; Guava `ImmutableList`, `ImmutableSet`, `ImmutableSortedSet`, `ImmutableMap`, `ImmutableBiMap`, `ImmutableSortedMap`, and `ImmutableIntArray` when Guava is present                                         |
| Objects             | Mutable concrete classes, records, creator-based classes, `JsonObject`, and `JsonArray`                                                                                                                                                              |

Collection interfaces are reconstructed with standard mutable implementations, such as
`ArrayList`, `LinkedHashSet`, `ArrayDeque`, `LinkedBlockingQueue`, `LinkedBlockingDeque`, or
`TreeSet`, according to the declared interface. Map interfaces similarly use `LinkedHashMap`,
`TreeMap`, `ConcurrentHashMap`, or `ConcurrentSkipListMap`. `ArrayBlockingQueue`, `Arrays.asList`
results, JDK immutable collections, empty/singleton/unmodifiable wrappers, constructor-constrained
implementations, and unlisted Guava immutable implementations cannot be reconstructed. Guava
support is optional and does not make Guava a required application dependency.

Non-finite float and double values use the quoted strings `"NaN"`, `"Infinity"`, and
`"-Infinity"`. Use explicit `BigInteger` or `BigDecimal` targets when arbitrary precision must be
preserved.

### Built-in representations

These built-in values use the following ordinary JSON shapes:

| Java type                                                                 | JSON representation                                                                                                                |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Enum                                                                      | Constant name as a string                                                                                                          |
| `Date`, `Calendar`, `java.sql.Date`, `Time`, `Timestamp`                  | Epoch milliseconds as a number                                                                                                     |
| `TimeZone`                                                                | Time-zone ID as a string                                                                                                           |
| Java time and supported chronology date types                             | Their standard textual form as a string                                                                                            |
| `UUID`, `URI`, `File`, `Path`, `Locale`, `Charset`, `Currency`, `Pattern` | Type-specific text as a string; `File` and `Path` use path text, `Locale` uses a language tag, and `Pattern` does not retain flags |
| `BitSet`                                                                  | Array of signed `long` words from `BitSet.toLongArray()`                                                                           |
| `ByteBuffer`                                                              | Array of signed byte values for the remaining range from position to limit                                                         |
| Optional and atomic wrappers                                              | Their contained scalar, array, or value directly                                                                                   |

`Calendar` reads epoch milliseconds into a new `GregorianCalendar`; its original calendar subtype,
time zone, and other configuration are not retained. A null `Optional` reference and an empty
`Optional` both write JSON null, and JSON null read as a declared Optional type becomes the
corresponding empty Optional.

### Dynamic JSON trees

Reading as `Object` uses natural JSON values:

| JSON value                  | Java value   |
| --------------------------- | ------------ |
| Object                      | `JsonObject` |
| Array                       | `JsonArray`  |
| String                      | `String`     |
| Boolean                     | `Boolean`    |
| Integer within `long` range | `Long`       |
| Larger integer              | `BigInteger` |
| Fraction or exponent        | `Double`     |
| Null                        | `null`       |

`JsonObject` preserves member insertion order and `JsonArray` is mutable. They can also be created
and written directly.

```java
import org.apache.fory.json.JsonArray;
import org.apache.fory.json.JsonObject;

JsonObject object = new JsonObject();
JsonArray items = new JsonArray();
items.add(1);
items.add("two");
object.put("items", items);

String encoded = json.toJson(object);
```

### Map keys

JSON object member names are strings. Declared map keys support `String`, `byte`, `short`, `int`,
`long`, their boxed forms, and enums. A map declared with `Object` keys can write String, number,
boolean, character, and enum keys, but reads them back as strings because JSON does not retain the
original key type. Null map keys are rejected.

## Builder configuration

| Builder method                         | Default                                   | User-visible effect                                        |
| -------------------------------------- | ----------------------------------------- | ---------------------------------------------------------- |
| `writeNullFields(boolean)`             | `false`                                   | Default inclusion of null object properties                |
| `withCodegen(boolean)`                 | `true`                                    | Enable generated object codecs                             |
| `withAsyncCompilation(boolean)`        | `true`                                    | Compile generated codecs asynchronously                    |
| `withFieldMode(boolean)`               | `false`                                   | When true, discover fields without getters/setters         |
| `withPropertyNamingStrategy(strategy)` | `LOWER_CAMEL_CASE`                        | Name properties without an explicit `JsonProperty` name    |
| `withMaxCachedFieldNames(int)`         | `DEFAULT_MAX_CACHED_FIELD_NAMES` (`8192`) | Field-name cache entries per reader; zero disables caching |
| `withConcurrencyLevel(int)`            | `max(1, 2 * processors)`                  | Maximum concurrent root operations                         |
| `withBufferSizeLimitBytes(int)`        | 2 MiB                                     | Maximum reusable capacity retained by each pooled writer   |
| `registerCodec(type, codec)`           | None                                      | Replace an eligible exact class's complete JSON codec      |
| `registerMixin(mixinType)`             | None                                      | Apply one annotation Mixin to its exact declared target    |

Concurrency-level and buffer-retention limits must be positive. The cached-field-name limit
applies independently to each reader; zero disables this cache. It bounds only cached field names,
not names accepted from the input. The buffer-retention setting does not limit JSON input or output
size, only reusable writer storage retained after an operation.

For class loading, type policy, nesting depth, graph-memory limits, and external input controls,
see [Fory JSON Security](security.md).

Builder mutation after `build()` does not modify an existing `ForyJson` instance.

On Android, runtime code generation and asynchronous compilation are disabled. In a GraalVM native
image, runtime compilation is unavailable. Fory JSON generates codecs for reachable models with the
default configuration and each reachable `ForyJsonProvider` configuration. A model without a
matching generated codec uses an interpreted codec. Every other builder option keeps the behavior
described above.
