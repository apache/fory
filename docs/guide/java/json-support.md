---
title: JSON Support
sidebar_position: 19
id: json_support
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

Fory JSON is Apache Fory's thread-safe Java JSON codec. It supports Java objects, records,
immutable creator-based classes, common JDK types, generic containers, custom complete-value
codecs, and finite annotation-declared polymorphism through interpreted and runtime-generated
codecs.

Fory JSON is separate from Fory's binary native and xlang protocols. Use JSON for interoperable
text payloads such as HTTP APIs, browser traffic, logs, and configuration. Use the binary protocol
when you need reference identity, circular graphs, cross-language schema metadata, or Fory's
binary-only features.

## Requirements and installation

The module targets Java 8 bytecode. Record mapping requires Java 17 or later.

Fory JSON is currently available from the source tree as `1.4.0-SNAPSHOT`. Until a published Fory
release contains the module, install it locally from the repository root:

```bash
cd java
mvn -pl fory-json -am -DskipTests install
```

Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-json</artifactId>
  <version>1.4.0-SNAPSHOT</version>
</dependency>
```

Gradle, using `mavenLocal()` for the snapshot:

```kotlin
implementation("org.apache.fory:fory-json:1.4.0-SNAPSHOT")
```

Keep all Fory modules on the same version. Replace the snapshot with the released version that
contains `fory-json` after publication.

### JDK 25 and later

Open `java.lang.invoke` to Fory core. On the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

On the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

Fory JSON's JPMS module name is `org.apache.fory.json`.

## Quick start

Create one `ForyJson` instance and reuse it. It is thread-safe and has no close lifecycle.

```java
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.ForyJson;

public final class JsonExample {
  private static final ForyJson JSON = ForyJson.builder().build();

  public static final class User {
    public long id;
    public String name;

    public User() {}

    User(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static void main(String[] args) {
    User input = new User(7, "Alice");
    String text = JSON.toJson(input);
    byte[] utf8 = JSON.toJsonBytes(input);

    User fromText = JSON.fromJson(text, User.class);
    User fromUtf8 = JSON.fromJson(utf8, User.class);

    System.out.println(text);
    System.out.println(new String(utf8, StandardCharsets.UTF_8));
    System.out.println(fromText.name + " / " + fromUtf8.name);
  }
}
```

Unknown input properties are skipped unless a read-enabled Any field or any-setter receives them.
Null object properties are omitted by default. Default JSON member discovery order is not a
compatibility contract; use `JsonPropertyOrder` or `JsonProperty.index` when emitted member order
must be explicit.

## Reading and writing

Fory JSON supports String and UTF-8 byte input/output. There is no `InputStream` parsing API.

| Operation            | Runtime type              | Declared `Class`                | Declared `TypeRef`                 |
| -------------------- | ------------------------- | ------------------------------- | ---------------------------------- |
| String output        | `toJson(value)`           | `toJson(value, type)`           | `toJson(value, typeRef)`           |
| UTF-8 bytes          | `toJsonBytes(value)`      | `toJsonBytes(value, type)`      | `toJsonBytes(value, typeRef)`      |
| UTF-8 `OutputStream` | `writeJsonTo(value, out)` | `writeJsonTo(value, type, out)` | `writeJsonTo(value, typeRef, out)` |
| String input         | -                         | `fromJson(text, type)`          | `fromJson(text, typeRef)`          |
| UTF-8 input          | -                         | `fromJson(bytes, type)`         | `fromJson(bytes, typeRef)`         |

Parsing consumes exactly one value and rejects trailing non-whitespace. String and byte-array
outputs are detached from internal buffers.

`writeJsonTo` buffers the complete document, performs one `OutputStream.write`, and neither flushes
nor closes the stream. It is not incremental streaming. I/O failures are wrapped in
`ForyJsonException`.

### Generic and declared types

Use `TypeRef` for generic roots:

```java
import java.util.List;
import org.apache.fory.reflect.TypeRef;

TypeRef<List<User>> usersType = new TypeRef<List<User>>() {};
List<User> users = json.fromJson("[{\"id\":7,\"name\":\"Alice\"}]", usersType);
String encoded = json.toJson(users, usersType);
```

Typed writes require fully bound types and reject wildcards and type variables. Values must be
assignable to the declared raw type. The declared schema controls serialization, including nested
generic element types and closed subtype metadata.

Use a declared base type when it owns `JsonSubTypes`:

```java
Shape shape = new Circle(2);

json.toJson(shape);              // Concrete runtime representation
json.toJson(shape, Shape.class); // Configured Shape subtype representation
```

For `List<Shape>`, use `new TypeRef<List<Shape>>() {}` so each element retains the declared subtype
schema.

## Thread safety and code generation

`ForyJson` is immutable and thread-safe after `build()`. Registered and annotation-selected
`JsonValueCodec` instances and type checkers are shared and must also be thread-safe.

Code generation and asynchronous compilation are enabled by default. Disable them for diagnostics
or environments that prohibit runtime compilation:

```java
ForyJson json =
    ForyJson.builder()
        .withCodegen(false)
        .withAsyncCompilation(false)
        .build();
```

`withConcurrencyLevel` controls reusable operation states, not a caller limit. Extra concurrent
operations use temporary state rather than one global lock.

## Object mapping

Default discovery merges members with the same Java logical property name:

- eligible instance fields across the hierarchy, regardless of Java visibility;
- public non-static `getX()` and boolean `isX()` getters;
- public non-static void `setX(value)` setters.

Static, transient, synthetic, and `Class<?>` fields are excluded. Class-valued accessors and
`getClass()` are excluded. An annotation on an ineligible member fails rather than becoming a
silent no-op.

An ordinary final field is a write source but not a mutable read sink. Use a record, `JsonCreator`,
or custom codec for immutable construction. Records use their canonical constructor.

Enable field-only discovery with:

```java
ForyJson json = ForyJson.builder().withFieldMode(true).build();
```

In field mode, getter/setter annotations are invalid. For ordinary properties, unknown members are
skipped and duplicate members use the last value. Polymorphic discriminator members are stricter
and must occur exactly once. JSON null is rejected for primitive targets. Most reference targets
return null, but a selected built-in or custom codec may define another result; declared Optional
targets return the corresponding empty Optional.

An ordinary class with a no-argument constructor runs it before readable properties are assigned,
so missing properties retain values established by field initializers or the constructor. On an
ordinary JVM, a class without such a constructor is allocated without running its constructors or
field initializers, and missing properties retain JVM zero or null values. Android cannot construct
an ordinary class without a usable no-argument constructor. GraalVM native image on JDK 25 and
later also requires one for most ordinary classes; the supported exception is a `Serializable`
class whose first non-serializable superclass is `Object`.

For portable construction, use a record, `JsonCreator`, or a no-argument constructor. Do not use an
ordinary constructor as a deserialization completion hook: property assignment follows a
no-argument constructor, while constructor-bypassing paths do not run it.

## Supported Java types

| Group              | Types and behavior                                                                                                                                                                                                                                                                     |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Scalars            | Primitive and boxed booleans/numbers/chars, strings and string builders, `BigInteger`, `BigDecimal`, Fory half-precision numbers, enums                                                                                                                                                |
| Containers         | Primitive/boxed/object arrays; collection, list, set, queue, deque, blocking, sorted, and navigable interfaces; map, sorted-map, navigable-map, and concurrent-map interfaces; supported concrete implementations; `EnumSet`; `EnumMap`; `Optional` variants; atomic values and arrays |
| Time               | `Date`, `Calendar`, `TimeZone`, Java time types, supported chronology dates, optional `java.sql.Date`, `Time`, and `Timestamp`                                                                                                                                                         |
| Other JDK          | `UUID`, `URI`, `File`, `Path`, `Locale`, `Charset`, `Currency`, `Pattern`, `BitSet`, `ByteBuffer`                                                                                                                                                                                      |
| Optional libraries | Guava `ImmutableList`, `ImmutableSet`, `ImmutableSortedSet`, `ImmutableMap`, `ImmutableBiMap`, `ImmutableSortedMap`, and `ImmutableIntArray` when Guava is present                                                                                                                     |
| Objects            | Mutable concrete classes, records, creator classes, `JsonObject`, `JsonArray`                                                                                                                                                                                                          |

Interfaces are reconstructed with appropriate standard mutable implementations. `ArrayBlockingQueue`,
`Arrays.asList` results, JDK immutable collections, empty/singleton/unmodifiable wrappers,
constructor-constrained implementations, and unlisted Guava immutable implementations cannot be
reconstructed. Guava remains optional.

Non-finite float/double values use quoted `"NaN"`, `"Infinity"`, and `"-Infinity"` tokens.

### Built-in representations

| Java type                                                                 | JSON representation                                                                                                 |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Enum                                                                      | Constant name string                                                                                                |
| `Date`, `Calendar`, `java.sql.Date`, `Time`, `Timestamp`                  | Epoch-millisecond number                                                                                            |
| `TimeZone`                                                                | Time-zone ID string                                                                                                 |
| Java time and supported chronology date types                             | Standard textual string                                                                                             |
| `UUID`, `URI`, `File`, `Path`, `Locale`, `Charset`, `Currency`, `Pattern` | Type-specific text string; `File` and `Path` use path text, `Locale` uses a language tag, and `Pattern` loses flags |
| `BitSet`                                                                  | Array of signed `long` words                                                                                        |
| `ByteBuffer`                                                              | Array of signed byte values from position to limit                                                                  |
| Optional and atomic wrappers                                              | Contained scalar, array, or value directly                                                                          |

`Calendar` is reconstructed as a new `GregorianCalendar`, so its original subtype, time zone, and
other configuration are not retained. A null Optional reference and an empty Optional both write
JSON null; reading JSON null as a declared Optional type returns the corresponding empty Optional.

### Dynamic JSON trees

Reading `Object.class` produces natural JSON values:

| JSON                | Java                                       |
| ------------------- | ------------------------------------------ |
| Object              | `JsonObject`                               |
| Array               | `JsonArray`                                |
| String/boolean/null | `String`, `Boolean`, null                  |
| Integer             | `Long`, or `BigInteger` outside long range |
| Fraction/exponent   | `Double`                                   |

`JsonObject` preserves insertion order and `JsonArray` is mutable.

### Map keys

Declared keys support String, byte, short, int, long, their boxed types, and enums. `Object` keys
can write String, number, boolean, character, and enum values, but read back as strings. Null keys
are rejected.

## Builder configuration

| Method                       | Default                                      | Effect                                                 |
| ---------------------------- | -------------------------------------------- | ------------------------------------------------------ |
| `writeNullFields`            | `false`                                      | Default null-property inclusion                        |
| `withCodegen`                | `true`                                       | Generated object codecs                                |
| `withAsyncCompilation`       | `true`                                       | Asynchronous generated-code compilation                |
| `withFieldMode`              | `false`                                      | Field-only discovery when true                         |
| `withPropertyNamingStrategy` | `LOWER_CAMEL_CASE`                           | Naming of properties without explicit names            |
| `withClassLoader`            | Snapshotted context loader, then Fory loader | Resolve annotation subtype class names                 |
| `maxDepth`                   | `20`                                         | Maximum nested object/array depth                      |
| `withConcurrencyLevel`       | `max(1, 2 * processors)`                     | Reusable operation-state count                         |
| `withBufferSizeLimitBytes`   | 2 MiB                                        | Reusable capacity retained by each pooled writer       |
| `registerCodec`              | None                                         | Exact-class complete-value codec                       |
| `withTypeChecker`            | None                                         | Application policy in addition to Fory's disallow list |

Depth, concurrency, and retained buffer limits must be positive. The buffer setting does not limit
output size. Builder changes after `build()` do not mutate an existing runtime.

In a GraalVM native image, runtime code generation and asynchronous compilation are automatically
disabled. Every other builder option keeps the behavior described above.

## Annotations

Fory JSON provides `JsonProperty`, `JsonPropertyOrder`, `JsonIgnore`, `JsonAnyProperty`,
`JsonAnyGetter`, `JsonAnySetter`, `JsonCreator`, `JsonCodec`, `JsonSubTypes`, and `JsonType` under
`org.apache.fory.json.annotation`. They are not Jackson, Gson, or Fory binary-protocol annotations.

```java
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
```

`JsonType` marks a reachable object model for GraalVM Native Image metadata. On Android it also
enables processor-generated R8 rules and `JsonCodec` type-use metadata. It has no effect on ordinary
JVM JSON behavior and is not inherited. See [GraalVM Support](graalvm-support.md) and
[Android Support](android-support.md) for the platform workflows.

### `JsonProperty`

An annotation on a field, getter, or setter configures the complete merged logical property:

```java
@JsonProperty("user_id")
private long id;

@JsonProperty(include = JsonProperty.Include.ALWAYS)
private String displayName;

@JsonProperty(index = 10)
private String email;
```

Supported inclusion values are:

- `DEFAULT`: inherit `writeNullFields`;
- `ALWAYS`: include null;
- `NON_NULL`: omit null.

`index` controls relative serialization order. Indexed properties are written in ascending index
order before unindexed properties. Indexes must be non-negative, may contain gaps, and must be
unique among writable properties. `-1` means unspecified; lower values are invalid. An index on a
setter-only, creator-only, or write-ignored property is invalid.

Inclusion affects writing only. Identical repeated declarations are allowed; conflicting explicit
names, indexes, or non-default policies fail. Two properties cannot normalize to the same JSON
name. `JsonProperty` cannot be combined with an Any logical property or declared on a
`JsonAnySetter`. `NON_EMPTY`, aliases, and formatting are unsupported.

### `JsonPropertyOrder`

Use `JsonPropertyOrder` to combine a named prefix, property indexes, and final-name alphabetic
ordering:

```java
@JsonPropertyOrder(value = {"id", "display_name"}, alphabetic = true)
public final class User {
  @JsonProperty(index = 20)
  public String name;

  @JsonProperty(value = "display_name", index = 10)
  public String displayName;

  public long id;
  public int age;
  public String address;
}
```

The output order is `id`, `display_name`, `name`, `address`, then `age`:

```json
{ "id": 1, "display_name": "Alice", "name": "alice", "address": "x", "age": 30 }
```

The named prefix is written first. Remaining indexed properties follow in ascending index order.
When `alphabetic = true`, remaining unindexed properties are sorted by final JSON name; otherwise
they keep their existing relative order. Use `@JsonPropertyOrder(alphabetic = true)` when no named
prefix is needed. Alphabetic comparison uses Java's natural, case-sensitive String order and does
not depend on the locale.

Order entries match the final JSON name first and the Java logical property name second. This lets
`display_name` match an explicit `JsonProperty` name while an unannotated `displayName` can still be
addressed by either `display_name` under `SNAKE_CASE` or its Java name `displayName`.

The list may be empty only when `alphabetic` is true. Its entries must be non-empty, unique writable
properties; unknown and duplicate entries fail when the object metadata is built. A subclass
declaration replaces both settings from its superclass as a whole; declarations are not merged. If
the subclass has no declaration, the nearest superclass declaration is used and resolved against the
subclass properties. Interface declarations are not considered.

Property order affects serialization only. Deserialization remains name-based and accepts members
in any order. Subtype discriminators remain before user properties.

A write-enabled `JsonAnyProperty` or `JsonAnyGetter` participates as one position identified by its
Java logical property name:

```java
@JsonPropertyOrder({"id", "properties", "timestamp"})
public final class Event {
  public String id;

  @JsonAnyProperty
  public Map<String, Object> properties;

  public long timestamp;
}
```

The position emits every `properties` entry in Map iteration order between `id` and `timestamp`; it
does not emit a member named `properties`. Naming strategies do not transform the Any ordering
name. Input-only Any fields and `JsonAnySetter` have no write position. Dynamic keys cannot appear
in `JsonPropertyOrder`, and alphabetic ordering never sorts entries inside the Map.

### Naming strategy

```java
ForyJson json =
    ForyJson.builder()
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .build();
```

The default `LOWER_CAMEL_CASE` preserves the discovered Java logical property name. `SNAKE_CASE`
maps `userName` to `user_name`, `URLValue` to `url_value`, and `version2FA` to `version2_fa`.
Explicit `JsonProperty` names, parameter-local creator names, and subtype discriminator properties
bypass the strategy. Dynamic Any keys also bypass it.

### `JsonIgnore`

`JsonIgnore` is field-targeted and controls both directions of the complete logical property:

```java
@JsonIgnore(ignoreRead = false, ignoreWrite = true)
private String serverManagedValue;
```

Both flags default to true. Accessors cannot restore an ignored direction, and `JsonProperty`
cannot override it. Fory core's `Expose` has no effect in Fory JSON.

### Dynamic object members

Use `JsonAnyProperty` to flatten a `Map<String, V>` field into the containing JSON object and store
otherwise unknown input members:

```java
public final class Event {
  public String id;

  @JsonAnyProperty
  public Map<String, Object> properties = new LinkedHashMap<>();
}
```

For `properties` containing `"source" -> "mobile"`, the result contains `"source":"mobile"`
beside `id`; no nested `properties` member is written. The field reads and writes by default.
`JsonIgnore` may select one direction, but it cannot disable both. During reading, Fory reuses an
existing Map or initializes a null non-final field on the first unknown member. A readable final
field on an ordinary mutable object must already contain a mutable Map. Records and property-list
`JsonCreator` types instead receive the accumulated Map through their construction argument.

Use `JsonAnyGetter` and `JsonAnySetter` for method-backed writing and reading:

```java
public final class Event {
  private final Map<String, Object> properties = new LinkedHashMap<>();

  @JsonAnyGetter
  public Map<String, Object> getProperties() {
    return properties;
  }

  @JsonAnySetter
  public void putProperty(String name, Object value) {
    properties.put(name, value);
  }
}
```

An any-getter must be a public instance method with no arguments and a `Map<String, V>` return type.
An any-setter must be a public instance method with signature `void method(String, V)`. Either may
be used independently. When paired, their resolved value types must match after primitive types are
boxed. A primitive any-setter value rejects JSON null. Any-setters are not supported on records or
types using `JsonCreator`.

A read-enabled `JsonAnyProperty` on a record component supplies that component from unknown input.
In property-list creator mode, a read-enabled Any field must correspond to one listed creator
argument; parameter-local creator mode cannot bind it. A write-only Any field or any-getter cannot
occupy a creator argument. If one claims a record component, that component receives its normal
Java default during reading.

An any-getter claims its complete Java logical property: both `getProperties()` and `properties()`
claim `properties`, so same-named ordinary fields and accessors are not mapped again as a fixed
member. Fory does not infer a differently named backing field; use `JsonIgnore` if that field must
not be mapped separately. An any-setter has no logical property name and does not claim a field.

The logical name is used only for grouping and `JsonPropertyOrder`; it is not a fixed JSON member.
An input member with that name is a dynamic entry rather than a nested aggregate. The same output
key remains valid unless another fixed property conflicts with it.

One effective type hierarchy may use either one Any field or up to one any-getter and one
any-setter. Field-backed and method-backed forms cannot be mixed, and method annotations are invalid
in field mode. An unannotated override disables an inherited method annotation. `JsonProperty` is
invalid on an any-setter and on every member claimed by an Any field or getter. A same-named field
cannot use `JsonIgnore` to suppress an any-getter's write direction, and its `ignoreRead` flag does
not disable a separate any-setter.

Dynamic keys are emitted unchanged in Map iteration order. A null Map emits nothing, while a null
Map value emits JSON null regardless of fixed-property null settings. Null and non-String output
keys are rejected. Raw Maps, wildcard or unresolved keys, and non-String key types are invalid.
Declared fixed members, including members excluded from reading, are not delivered to an Any
input. Output keys whose Fory field-name hash conflicts with a fixed property are rejected,
including differently spelled hash collisions. Fory does not inspect an Any Map for a key whose
name or Fory field-name hash conflicts with an inline subtype discriminator. An exact-name output
key emits a duplicate JSON member; on input, a differently spelled hash collision is classified as
the discriminator by the child field table. Applications must keep dynamic keys distinct from the
active discriminator by both name and hash. Repeated unknown names replace the Map value; an
any-setter is called for every occurrence. Fixed input lookup is also hash-based, so a differently
spelled colliding name follows the fixed member instead of Any handling. Escaped input names are
decoded before delivery.

### `JsonCreator`

The compact mode lists existing Java logical property names in parameter order and reuses their
normalized metadata:

```java
public final class User {
  public final long id;
  public final String name;

  @JsonCreator({"id", "name"})
  public User(long id, String name) {
    this.id = id;
    this.name = name;
  }
}
```

Parameter-local mode gives every parameter an explicit JSON name and permits creator-only inputs:

```java
@JsonCreator
public static User create(
    @JsonProperty("user_id") long id,
    @JsonProperty("display_name") String name) {
  return new User(id, name);
}
```

The modes cannot be mixed. Compact names must be non-empty and unique, their count must match the
parameter count, and compact parameters cannot also declare `JsonProperty`. Parameter-local mode
requires a non-empty, unique `JsonProperty` name on every parameter. The creator is the complete
read schema and setters do not run after it.

Exactly one creator is allowed. It must be public, have at least one parameter, and be neither
varargs nor generic. A factory is also static, declares the target class as its exact return type,
and returns a non-null value whose runtime class is exactly the target. Missing references use null,
missing primitives use zero, duplicate members use the last value, and explicit primitive null
fails. Records cannot declare `JsonCreator`.

### `JsonSubTypes`

`JsonSubTypes` defines a complete finite table on an interface or abstract base. Each entry has a
case-sensitive logical name and exactly one Java source: a class literal or trusted binary
`className`. JSON never supplies class names or expands the table.

Default property inclusion:

```java
@JsonSubTypes(
    property = "kind",
    value = {
      @JsonSubTypes.Type(value = Circle.class, name = "circle"),
      @JsonSubTypes.Type(
          className = "com.example.shape.Rectangle",
          name = "rectangle")
    })
public interface Shape {}
```

```json
{ "kind": "circle", "radius": 2 }
```

The discriminator is emitted first but may appear at any direct input member position. It must
occur exactly once, contain a known String name, and not collide with a subtype property.

Wrapper object:

```java
@JsonSubTypes(
    inclusion = JsonSubTypes.Inclusion.WRAPPER_OBJECT,
    value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
public interface Shape {}
```

```json
{ "circle": { "radius": 2 } }
```

Wrapper array:

```java
@JsonSubTypes(
    inclusion = JsonSubTypes.Inclusion.WRAPPER_ARRAY,
    value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
public interface Shape {}
```

```json
["circle", { "radius": 2 }]
```

| Inclusion        | `property`             | Value rule                          |
| ---------------- | ---------------------- | ----------------------------------- |
| `PROPERTY`       | Required and non-empty | Ordinary subtype object members     |
| `WRAPPER_OBJECT` | Must be empty          | Complete subtype value              |
| `WRAPPER_ARRAY`  | Must be empty          | Complete subtype value as element 1 |

Wrapper inclusions support exact custom subtype codecs. Logical names and resolved concrete,
assignable classes must each be unique. Only exact listed runtime classes are accepted; listing a
parent does not admit descendants. The annotation is read from the declared base itself and is not
inherited from another annotated base. Null is plain JSON null unless codec precedence selects a
custom complete-value codec for the declared base, replacing the annotation. Readers accept only
the configured shape, so changing inclusion is a wire-format change. At GraalVM native-image
runtime, annotate the base with `JsonType` and use class literals instead of `className`. Listed
class-literal subtypes are registered automatically.

## Custom codecs

`JsonValueCodec<T>` reads and writes one complete JSON value, including null, through Fory's
concrete String/UTF-8 writers and Latin-1/UTF-16/UTF-8 readers. It is a direct streaming-value SPI,
not a JSON abstract syntax tree (AST) codec. It never handles Map keys; `MapKeyCodec` owns JSON
object member names.

Implement all five representations with the same JSON shape:

```java
import java.math.BigDecimal;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public final class MoneyCodec implements JsonValueCodec<Money> {
  @Override
  public void writeString(StringJsonWriter writer, Money value) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeBigDecimal(value.amount);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Money value) {
    if (value == null) {
      writer.writeNull();
    } else {
      writer.writeBigDecimal(value.amount);
    }
  }

  @Override
  public Money readLatin1(Latin1JsonReader reader) {
    return reader.tryReadNullToken() ? null : new Money(reader.readBigDecimal());
  }

  @Override
  public Money readUtf16(Utf16JsonReader reader) {
    return reader.tryReadNullToken() ? null : new Money(reader.readBigDecimal());
  }

  @Override
  public Money readUtf8(Utf8JsonReader reader) {
    return reader.tryReadNullToken() ? null : new Money(reader.readBigDecimal());
  }
}

final class Money {
  final BigDecimal amount;

  Money(BigDecimal amount) {
    this.amount = amount;
  }
}
```

Register it once:

```java
import org.apache.fory.json.ForyJson;

ForyJson json =
    ForyJson.builder()
        .registerCodec(Money.class, new MoneyCodec())
        .build();
```

The parent property still controls its name, ignore direction, and null inclusion before the codec
runs. An emitted property and every array, collection, map-value, Optional, or atomic-reference
position delegate the complete value, including null. The registered codec instance is shared
concurrently and must be thread-safe. A custom codec on a subtype is compatible with wrapper
inclusion, not inline property inclusion. A codec on the base replaces its `JsonSubTypes`
annotation.

### `JsonCodec` declarations and type uses

Use `@JsonCodec` on a class, record, enum, or interface to make a codec the default representation
for that declaration and its descendants:

```java
@JsonCodec(MoneyCodec.class)
public final class Money {}

@JsonCodec(AccountCodec.class)
public interface Account {}

public final class RetailAccount implements Account {}
```

The default applies at root `Class` and raw `TypeRef` targets and at unannotated nested value
positions. Fory explicitly inherits declarations through superclasses and interfaces; it does not
use Java `@Inherited`.

On a field or effective ordinary getter, a declaration annotation selects the codec for that
member's root value:

```java
public final class Invoice {
  @JsonCodec(MoneyCodec.class)
  public Money total;

  @JsonCodec(MoneyCodec.class)
  public Money getTax() {
    return tax;
  }
}
```

The method form is invalid on record accessors, setters, creator factories, unrelated methods, and
void methods. An ordinary getter declaration is invalid when field mode disables getter discovery.
Existing record-component `@JsonCodec` behavior is unchanged; declaring `@JsonCodec` only on a
record accessor is unsupported. For a selected field or getter, Fory checks the declaration first
and uses the annotation on the root annotated type only when the declaration is absent.

Use the same annotation at a type-use position to select a codec for exactly that occurrence:

```java
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class Invoice {
  // Qualified placement selects the root type-use fallback rather than the field declaration.
  public com.example.@JsonCodec(MoneyCodec.class) Money qualifiedTotal;

  public List<@JsonCodec(MoneyCodec.class) Money> items;
  public Set<@JsonCodec(MoneyCodec.class) Money> uniqueItems;
  public Collection<@JsonCodec(MoneyCodec.class) Money> allItems;
  public Map<String, @JsonCodec(MoneyCodec.class) Money> byName;
  public Optional<@JsonCodec(MoneyCodec.class) Money> optional;
  public AtomicReference<@JsonCodec(MoneyCodec.class) Money> current;

  // Codec for each element.
  public com.example.@JsonCodec(MoneyCodec.class) Money[] itemArray;

  // Codec for the complete array value.
  public Money @JsonCodec(MoneyArrayCodec.class) [] encodedArray;

  // Codec for each complete inner Set value.
  public List<@JsonCodec(MoneySetCodec.class) Set<Money>> groups;

  // Normal containers with a codec only at the leaf.
  public List<Set<@JsonCodec(MoneyCodec.class) Money>> nestedItems;
}
```

Every array dimension is a distinct type-use node. This recursive model also covers
multidimensional arrays, `AtomicReferenceArray`, concrete containers, and generic container
subclasses. Fory follows the inherited `Collection<E>` or `Map<K,V>` binding instead of assuming a
fixed type-argument index.

One logical property's field, effective getter, effective setter, record component, and matching
creator parameter contribute to one resolved codec tree. Missing annotations are not conflicts;
equal codec classes at one node merge, while different classes fail with both sources and the
nested path. Type-use metadata survives generic substitution, including a codec attached to a
bound type argument used by a field declared as a type variable. Supported positions include field
and record-component types, getter return types, setter value parameters, and `JsonCreator`
constructor or factory value parameters.

`TypeRef` preserves ordinary Java generic types but not arbitrary occurrence annotations. A root
`TypeRef<List<@JsonCodec(...) Money>>` therefore cannot carry that local override. Put it on a
discovered model property, declare a default on `Money`, or use exact builder registration.

### Codec precedence

Fory chooses the complete codec for each current resolved value node using this exact order. An
invalid higher-priority source fails rather than falling back.

| Priority | Source                                  | Matching rule                                                                                             |
| -------: | --------------------------------------- | --------------------------------------------------------------------------------------------------------- |
|        1 | Current member or `TYPE_USE @JsonCodec` | Exact resolved occurrence after declaration-first acquisition, property merging, and generic substitution |
|        2 | `registerCodec(Target.class, instance)` | Exact target `Class`; never inherited                                                                     |
|        3 | Direct type `@JsonCodec` declaration    | Current class, record, enum, or interface                                                                 |
|        4 | Inherited type declaration              | Most-specific superclass/interface result                                                                 |
|        5 | Existing mapping                        | `JsonSubTypes`, built-in/container mapping, then default object mapping                                   |

For each field or getter, its declaration takes precedence only over that same member's root
annotated type. The selected field, getter, setter, record component, and creator occurrences are
then merged as one logical property; different codecs at the same node remain a conflict.

Rows 1 through 4 replace the whole current value mapping. They may therefore replace a scalar,
enum, container, object, or `JsonSubTypes` representation. A current occurrence, exact
registration, or direct declaration also resolves any lower-priority inherited ambiguity.

### Deterministic inheritance and conflicts

For inherited declarations, Fory collects every annotated proper superclass and interface, then
removes a candidate when another declaring type is its Java subtype. The remaining most-specific
declarations resolve as follows:

- one declaration wins;
- unrelated declarations naming the same codec class are consistent;
- unrelated declarations naming different codec classes conflict.

This is independent of `implements` order and reflection order. A child interface declaration
overrides its parent declaration, and a child class declaration overrides its parent:

```java
@JsonCodec(ParentCodec.class)
interface Parent {}

@JsonCodec(ChildCodec.class)
interface Child extends Parent {}

final class Value implements Child {} // ChildCodec

@JsonCodec(BaseCodec.class)
class Base {}

@JsonCodec(ValueCodec.class)
final class Concrete extends Base {} // ValueCodec
```

Unrelated interfaces using a common codec are valid; different codecs are ambiguous:

```java
@JsonCodec(CommonCodec.class)
interface A {}

@JsonCodec(CommonCodec.class)
interface B {}

final class Consistent implements A, B {}

@JsonCodec(ACodec.class)
interface Left {}

@JsonCodec(BCodec.class)
interface Right {}

final class Ambiguous implements Left, Right {} // Metadata error.
```

Superclass/interface candidates follow the same subtype rule:

| Declaring-type relationship                     | Codec classes     | Result                                                    |
| ----------------------------------------------- | ----------------- | --------------------------------------------------------- |
| Superclass implements or inherits the interface | Same or different | Superclass declaration wins because it is more specific   |
| Superclass and interface are unrelated          | Same              | Common codec is accepted                                  |
| Superclass and interface are unrelated          | Different         | Conflict; class does not automatically override interface |

Disambiguate by annotating the concrete class, annotating only the current type use, or registering
an exact codec instance:

```java
@JsonCodec(ConcreteCodec.class)
final class DeclaredValue implements Left, Right {}

final class Holder {
  public @JsonCodec(LocalCodec.class) Ambiguous value;
}

ForyJson json =
    ForyJson.builder()
        .registerCodec(Ambiguous.class, new ConcreteCodec())
        .build();
```

Fory retains Jackson's useful idea of explicitly inheriting annotations through class and
interface hierarchies, but rejects traversal-order first-wins behavior. Java subtype specificity
and codec-class identity decide the result; incomparable different codecs produce an error instead
of depending on hierarchy declaration order.

### Nested composition and Map keys

A custom codec owns its complete current value and cannot delegate annotated children. An explicit
nested type-use under an outer custom codec is therefore rejected as hidden configuration:

```java
// Invalid: LocalCodec cannot run because CustomListCodec owns the whole list.
public @JsonCodec(CustomListCodec.class)
    List<@JsonCodec(LocalCodec.class) Money> values;
```

A child class's declaration codec is only a lazy default. The outer codec prevents child lookup,
so that default is not a hidden explicit instruction and is valid:

```java
@JsonCodec(MoneyCodec.class)
final class DeclaredMoney {}

public @JsonCodec(CustomListCodec.class) List<DeclaredMoney> values;
```

The hidden-explicit-descendant rule also applies when the outer complete codec comes from builder
registration or a class/interface declaration. Without an outer custom codec, arrays, collections,
map values, Optional, and atomic references resolve their annotated children normally.

Map keys are JSON object member names, not JSON values. An explicit `@JsonCodec` anywhere in a Map
key subtree is rejected. Builder or declaration value codecs on the key's raw type are ignored in
key position and still apply when that type is used as a value. `JsonAnyProperty` and
`JsonAnyGetter` flatten their Map and cannot select a whole-value codec; only the nested Map value
node can. A field or method declaration codec and a root type-use codec are therefore invalid on
those two forms. A `JsonAnySetter` value parameter is already one flattened value and may carry a
root type-use codec. Wildcards, wildcard bounds, and type variables still unresolved after
substitution cannot select a codec. A type-use on an `extends` or `implements` clause is not a JSON
value occurrence and is not used; annotate the type declaration instead. Annotation type
declarations are not supported JSON model targets.

### Construction, inherited results, and platform support

An annotation codec class must be public, concrete, top-level or static nested, and have a public
no-argument constructor. One successfully constructed instance is shared by all annotated sites
and concurrent operations of the built `ForyJson`; it must be thread-safe. Use
`registerCodec(Target.class, instance)` when the codec needs configuration.

In a named Java module, export or open the codec's package to `org.apache.fory.json`. Fory does not
bypass a closed module boundary and reports an actionable access error.

When a parent or interface declaration supplies the codec for a more specific target, every reader
result must be null or assignable to that target. A parent codec may return the requested child and
succeed. Returning a plain parent while decoding the child fails with `ForyJsonException` that
names the target type, codec class, declaring type, and actual returned type. The actual result,
not the parent's `final` status or the codec's generic signature, determines validity.

`@JsonCodec` is supported on ordinary JVMs and GraalVM native images, including member
declarations, inherited type declarations, and nested type uses. Native models must follow the
`JsonType` workflow described in [GraalVM Support](graalvm-support.md), which registers the
annotation codec's public no-argument constructor.

Android directly reads type, field, and effective getter declarations. Pure type-use locations,
including qualified roots, parameters, generic arguments, and array components, require `JsonType`
processor metadata. `JsonType` also supplies automatic R8 rules; applications that omit it can
supply exact rules manually and use declaration syntax, but type-use codecs remain unavailable. See
[Android Support](android-support.md).

## Type validation and untrusted input

Fory JSON always applies its fixed disallow list. Add an application policy with:

```java
ForyJson json =
    ForyJson.builder()
        .withTypeChecker(
            (className, context) ->
                className.startsWith("com.example.model.")
                    || className.equals("java.util.List")
                    || className.equals("java.util.Map"))
        .build();
```

Allow every application model and non-built-in container type that the declared schema uses. The
checker applies while application types are prepared for serialization and parsing and must be
thread-safe. Built-in scalars normally skip the custom checker, but selecting an application codec
for a built-in target makes that target subject to the checker. Custom codecs cannot bypass the
fixed disallow list.

`withClassLoader` fixes subtype `className` resolution. Otherwise `build()` snapshots the thread
context class loader and falls back to the Fory JSON loader.

`maxDepth` is not an input-size or memory quota. Enforce request size, timeout, and resource limits
at the transport boundary. `Class`, `URL`, `InetAddress`, and `InetSocketAddress` are unsupported by
default. URL and arbitrary unsupported Number/CharSequence subclasses require exact custom codecs.

## Limits and unsupported features

- No shared-reference identity or circular-reference protocol. Use Fory binary when needed.
- No open polymorphism, JSON class-name IDs, subtype discovery, or runtime subtype-table extension.
- No InputStream parser, incremental `OutputStream` writer on the `ForyJson` root API, or
  pretty-print configuration.
- No Jackson/Gson annotation compatibility.
- No aliases, views, filters, injection, managed/back references, object identity annotations, root
  wrapping, format annotations, or annotation-driven raw JSON values.
- Fory core's `Expose` is ignored.

Circular graphs eventually fail `maxDepth`; they are not reconstructed.

## Errors and troubleshooting

| Symptom                            | Action                                                                                          |
| ---------------------------------- | ----------------------------------------------------------------------------------------------- |
| `ForyJsonException`                | Check JSON grammar, target type, mapping support, depth, trailing content, or output cause      |
| `InsecureException`                | Check Fory's disallow list and the configured type checker                                      |
| Builder `IllegalArgumentException` | Use positive depth, concurrency, and retained-buffer values                                     |
| Declared write fails               | Remove wildcard/type variables and pass an assignable value; primitive declarations reject null |
| Immutable value is empty           | Use a record, valid creator, or custom codec                                                    |
| Ordinary object cannot be created  | Add a usable no-argument constructor, use a record or creator, or register a codec              |
| Ordinary accessor annotation fails | Use an eligible public JavaBean accessor and disable field mode                                 |
| Any annotation fails               | Use one field form or one valid method pair with resolved `Map<String, V>` types                |
| Codec annotation fails             | Resolve same-node or hierarchy conflicts, hidden nested overrides, or codec constructor access  |
| Subtype fails                      | Write with the declared base, list the exact runtime class, and use the configured wire shape   |
| Collection fails                   | Target a supported interface/common implementation or register a codec                          |

Creator failures other than `Error` are wrapped with their original cause. User codec code may
still throw its own runtime exceptions.

## Related Java guides

- [Java serialization overview](index.md)
- [Native binary serialization](native-serialization.md)
- [Xlang binary serialization](xlang-serialization.md)
- [Java configuration](configuration.md)
- [Troubleshooting](troubleshooting.md)
