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
`JsonAnyGetter`, `JsonAnySetter`, `JsonCreator`, `JsonCodec`, `JsonValue`, `JsonRawValue`,
`JsonBase64`, `JsonUnwrapped`, `JsonSubTypes`, and `JsonType` under
`org.apache.fory.json.annotation`. They are not Jackson, Gson, or Fory binary-protocol annotations.

```java
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
```

`JsonType` asks the annotation processor to generate direct property and creator operations plus
the exact retention rules for an eligible concrete object model. A directly annotated
`JsonValue` Record also receives a companion so its value accessor and canonical constructor work
after Android desugaring. The same generated companion is used on the JVM, Android, and GraalVM
Native Image. The annotation is not inherited; a concrete subtype needs its own direct annotation
to receive a companion. See
[GraalVM Support](graalvm-support.md) and [Android Support](android-support.md) for setup.
A directly annotated model that uses the default object codec requires that generated companion;
the runtime reports a configuration error if the processor output is missing.

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

An unwrapped group also occupies one position, selected by the group's Java logical property name.
Its child members remain adjacent and retain the child's own order.

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

### `JsonValue`

Use `JsonValue` when one exact `String` member is the complete JSON representation of its owning
type. Fory writes it as an ordinary quoted and escaped JSON string instead of an object:

```java
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonValue;

public final class UserId {
  private final String value;

  @JsonCreator
  public UserId(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }
}
```

The method may use any name but must be public, non-static, zero-argument, and return exactly
`String`. A field must be an eligible non-static instance field. Only one effective member is
allowed, and an unannotated override suppresses an inherited method annotation.

The annotation controls writing on its own. Reading requires a `JsonCreator` constructor or public
static factory with one exact `String` parameter, empty `JsonCreator.value()`, and no `JsonProperty`
on the parameter. This shape is inferred as the reverse String constructor; no creator mode is
needed. Existing property-based creator forms are unchanged. JSON null maps directly to Java null
without invoking the value member or creator.

### `JsonRawValue`

Use `JsonRawValue` on a fixed ordinary `String` property whose contents are already one complete
JSON value:

```java
import org.apache.fory.json.annotation.JsonRawValue;

public final class Response {
  public int status;

  @JsonRawValue
  public String body;
}
```

For `body = "{\"id\":1}"`, Fory emits the object directly at the `body` value position. It does not
quote, escape, parse, validate, or normalize the String. This is a trusted write-only escape hatch;
invalid or attacker-controlled content can make the entire output invalid or change its structure.
Java null follows the property's existing inclusion rule and is written as JSON null when included.

Reading is unchanged and still expects a JSON string. A raw object or array written through the
property cannot be read back into that `String`. The annotation does not apply to setters, creator
parameters, Any declarations, container elements, or Map values, and it cannot share an occurrence
with `JsonCodec`.
As an occurrence-local representation, it keeps the raw String shape even when the value type has
an exact builder-registered codec.

Neither annotation collects unknown sibling fields. Unknown fields are skipped unless an existing
`JsonAnyProperty` or `JsonAnyGetter`/`JsonAnySetter` mapping owns them. Combining `JsonValue` and
`JsonRawValue` on the same String member writes the owning object as a trusted raw root value, but
does not add a raw-fragment read contract.

### `JsonBase64`

Use `JsonBase64` on one exact `byte[]` field or getter to represent it as a quoted standard Base64
JSON string:

```java
import org.apache.fory.json.annotation.JsonBase64;

public final class Attachment {
  @JsonBase64
  public byte[] content;
}
```

Bytes `{1, 2, 3}` are written as `{"content":"AQID"}` and decoded back to the original array.
Encoding and decoding do not create an intermediate String. Null handling follows the property's
normal inclusion rule.

The annotation is not a type-use annotation and does not affect ordinary `byte[]` properties,
container elements, or Map values. It cannot share a logical property with `JsonRawValue`, an
occurrence `JsonCodec`, or an Any declaration. The equivalent explicit codec is
`@JsonCodec(Base64ByteArrayCodec.class)`.

### `JsonUnwrapped`

Use `JsonUnwrapped` when an object-valued property should keep its Java object boundary but place
its members in the containing JSON object:

```java
import org.apache.fory.json.annotation.JsonUnwrapped;

public final class Person {
  public int age;

  @JsonUnwrapped(prefix = "name_")
  public Name name;
}

public final class Name {
  public String first;
  public String last;
}
```

This writes `{"age":18,"name_first":"Ada","name_last":"Lovelace"}` instead of a
nested `name` object. `prefix` and `suffix` apply to every final child name after `JsonProperty` and
the configured naming strategy. Nested groups compose these transformations from the inside out.

A null child emits no members. During reading, the child is created only when at least one
flattened member is present. A missing group preserves a mutable parent's initialized value and
leaves a record or creator argument at its normal missing-property default. Partial input creates
the child and uses ordinary defaults for its other members.

Mutable classes, records, and `JsonCreator` classes are supported as parents and children. A
parameter-local creator parameter can define a read-only group; its required `JsonProperty` value
identifies the Java argument and is not a wrapper name. The containing parent may be parameterized,
but each unwrapped child and intermediate must be an exact raw, non-generic class using the standard
Fory object mapping.

The complete group occupies one position in parent serialization order. Position it with
`JsonProperty.index`, or list its Java logical property name in `JsonPropertyOrder`. Child ordering
is preserved inside the group. Input matches parent fixed properties first, flattened properties
second, and dynamic Any members last.

Fory rejects final-name or name-hash collisions, recursive chains made only of unwrapped
properties, parameterized children, JSON Any children, polymorphic or custom-codec child roots,
and scalar, array, collection, or Map children. Flatten Maps with `JsonAnyProperty`,
`JsonAnyGetter`, or `JsonAnySetter`. An unwrapped property cannot use `JsonProperty.value`, a
non-default `JsonProperty.include`, or `JsonCodec`; ordinary leaf properties inside the child keep
their normal annotations.

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

For a type with `JsonValue`, the empty form also accepts exactly one `String` parameter without
`JsonProperty` and reconstructs the owning value from its ordinary JSON string representation.

Exactly one creator is allowed. It must be public, have at least one parameter, and be neither
varargs nor generic. A factory is also static, declares the target class as its exact return type,
and returns a non-null value whose runtime class is exactly the target. Missing references use null,
missing primitives use zero, duplicate members use the last value, and explicit primitive null
fails. Records cannot declare a property-based `JsonCreator`; a record with `JsonValue` may annotate
its one-String canonical constructor for the value form.

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

### Selecting codecs with `JsonCodec`

Use `@JsonCodec` on a class, record, enum, or interface to declare its default complete-value
codec. The positional form is shorthand for `value`:

```java
@JsonCodec(MoneyCodec.class)
public final class Money {}

@JsonCodec(AccountCodec.class)
public interface Account {}

public final class RetailAccount implements Account {}
```

Type declarations are inherited through both superclasses and interfaces. The most-specific
declaration wins. Unrelated declarations using the same codec are consistent; unrelated
declarations using different codecs fail instead of depending on reflection order.

On a field or effective ordinary getter, `value` replaces the complete property value. The same
annotation is supported on an effective setter value parameter, a `JsonCreator` constructor or
factory parameter, and a record component through Java's field, accessor, and constructor-parameter
propagation:

```java
public final class Invoice {
  @JsonCodec(MoneyCodec.class)
  public Money total;
  private Money tax;
  private Money discount;

  @JsonCodec(MoneyCodec.class)
  public Money getTax() {
    return tax;
  }

  public void setDiscount(@JsonCodec(MoneyCodec.class) Money discount) {
    this.discount = discount;
  }

  @JsonCreator
  public Invoice(@JsonProperty("total") @JsonCodec(MoneyCodec.class) Money total) {
    this.total = total;
  }
}
```

Use a child member when the standard container should remain in control and only its direct child
needs a custom codec:

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class InvoiceGroup {
  @JsonCodec(elementCodec = MoneyCodec.class)
  public List<Money> items;

  @JsonCodec(elementCodec = MoneyCodec.class)
  public Money[] itemArray;

  @JsonCodec(elementCodec = MoneyCodec.class)
  public AtomicReferenceArray<Money> atomicItems;

  @JsonCodec(contentCodec = MoneyCodec.class)
  public Optional<Money> optional;

  @JsonCodec(contentCodec = MoneyCodec.class)
  public AtomicReference<Money> current;

  @JsonCodec(keyCodec = CurrencyKeyCodec.class, valueCodec = MoneyCodec.class)
  public Map<Currency, Money> byCurrency;
}
```

The child members have these meanings:

| Member         | Supported current value                           | Direct child handled by the codec |
| -------------- | ------------------------------------------------- | --------------------------------- |
| `elementCodec` | `Collection<E>`, `E[]`, `AtomicReferenceArray<E>` | `E`                               |
| `contentCodec` | `Optional<T>`, `AtomicReference<T>`               | `T`                               |
| `keyCodec`     | `Map<K, V>`                                       | JSON member name for `K`          |
| `valueCodec`   | `Map<K, V>`                                       | direct `V` value                  |

A custom Map-key codec converts between the declared key and a JSON member name:

```java
import java.util.Locale;
import org.apache.fory.json.codec.MapKeyCodec;

public final class CurrencyKeyCodec implements MapKeyCodec {
  @Override
  public String toName(Object key) {
    return ((Currency) key).name().toLowerCase(Locale.ROOT);
  }

  @Override
  public Object fromName(String name) {
    return Currency.valueOf(name.toUpperCase(Locale.ROOT));
  }
}
```

Code that used the removed type-use form should move the codec to the owning declaration:

```java
// Before
List<@JsonCodec(MoneyCodec.class) Money> items;

// Now
@JsonCodec(elementCodec = MoneyCodec.class)
List<Money> items;
```

Use `contentCodec` for an `Optional` or `AtomicReference`, `valueCodec` for a Map value, and
`elementCodec` for an array or `AtomicReferenceArray` element.

`Iterable<E>` values that are not `Collection<E>` do not support `elementCodec`. Use `value` when a
complete codec should own such a value.

Child configuration is intentionally one level deep. For `List<List<Money>>`, `elementCodec`
handles each complete `List<Money>`. For `Money[][]`, it handles each `Money[]`. To customize a
deeper descendant, implement a codec for the complete current value and select it with `value`.

`value` is mutually exclusive with every child member because it already owns the complete current
value. An empty annotation, an unsupported child member, or an outer complete codec combined with
a child member fails during model construction. A configured direct child must resolve to a
concrete type; raw containers, direct wildcards, and unresolved direct type variables are rejected.

`JsonAnyProperty` and `JsonAnyGetter` flatten their Map into the enclosing object. Configure their
dynamic values with `valueCodec`:

```java
@JsonAnyProperty
@JsonCodec(valueCodec = MoneyCodec.class)
public Map<String, Money> extra;
```

The first `JsonAnySetter` parameter is the String property name. Its second parameter may use
`@JsonCodec(value = ...)` or another configuration valid for that parameter's own shape.

### Codec precedence and repeated declarations

Fory resolves each current value in this order:

| Priority | Source                                    |
| -------: | ----------------------------------------- |
|        1 | Current property or parameter `JsonCodec` |
|        2 | Exact `registerCodec` registration        |
|        3 | Direct type `JsonCodec` declaration       |
|        4 | Inherited type declaration                |
|        5 | Built-in or default JSON mapping          |

One logical property may expose the annotation from its field, getter, setter parameter, creator
parameter, or record propagation. Repeated configurations must be identical; Fory does not merge
partial configurations from different declarations. An unannotated effective override suppresses
an inherited method annotation.

A child member replaces only that direct child. Unconfigured Map siblings continue through the
normal precedence. If an exact registration or type declaration supplies a complete codec for the
outer container, a property child member is unreachable and therefore rejected.

Map keys are JSON object member names and use `MapKeyCodec`, not `JsonValueCodec`. A custom key
codec class follows the same construction rules as a value codec. Null Map keys are rejected, and
decoded keys must match the declared key type.

### Codec construction and platform support

An annotation codec class must be public, concrete, top-level or static nested, and have a public
no-argument constructor. One instance is shared by all annotated sites and concurrent operations of
the built `ForyJson`, so it must be thread-safe. Use `registerCodec(Target.class, instance)` when a
complete-value codec needs configuration.

In a named Java module, export or open the codec package to `org.apache.fory.json`. When an inherited
type-declaration codec is used for a more specific target, every decoded value must be null or
assignable to that target.

The annotation has the same FIELD, METHOD, and PARAMETER behavior on the JVM, Android, and GraalVM
Native Image. Ordinary Android classes may omit `JsonType` and provide equivalent exact rules;
Android-desugared Records, including `JsonValue` Records, require `JsonType` and the processor.
GraalVM object models follow the `JsonType` workflow in
[GraalVM Support](graalvm-support.md).

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
  wrapping, or format annotations.
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
| `JsonValue` read fails             | Add one plain `String` creator, or register an exact custom codec                               |
| Raw JSON output is invalid         | Supply one trusted, complete JSON value to the `JsonRawValue` property                          |
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
