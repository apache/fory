---
title: Annotations
sidebar_position: 4
id: annotations
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

Fory JSON provides these mapping and validation annotations in
`org.apache.fory.json.annotation`:
`JsonAnyGetter`, `JsonAnyProperty`, `JsonAnySetter`, `JsonBase64`, `JsonCodec`, `JsonCreator`, `JsonFormat`,
`JsonIgnore`, `JsonProperty`, `JsonPropertyOrder`, `JsonRawValue`, `JsonSubTypes`, `JsonUnwrapped`,
`JsonValidator`, and `JsonValue`. `JsonType` is a separate build-time model marker. They are
Fory JSON APIs, not Jackson, Gson, or Fory binary-protocol compatibility annotations.

`JsonType` is not inherited, so mark each eligible concrete model that must participate in a
platform build workflow. For Java source, the Fory annotation processor generates direct property
and creator operations plus exact retention rules on the JVM and Android. A directly annotated
`JsonValue` Record also receives generated value-access and canonical-constructor operations.
Ordinary unannotated Java classes may still use reflection; on Android they need
application-authored exact R8 rules. Android-desugared Records require processor-generated
operations from either a direct `JsonType` declaration or a compiled exact `JsonMixin` pair.
Outside Native Image, a directly annotated Java model that uses the default object codec fails
during codec creation if its generated Java operations are missing.

Kotlin/JVM models are mapped from validated Kotlin metadata and do not require generated
construction operations. In Android builds that use R8 or ProGuard, Kotlin KSP emits exact
retention rules for Kotlin `@JsonType` models. It also processes an exact Mixin declared in
application source when either the Mixin or its exact target is Kotlin. KSP does not generate codecs
or construction operations. GraalVM Native Image discovers reachable Java and Kotlin `JsonType`
declarations directly. It generates codecs with the default configuration and each reachable
provider configuration; models without a matching generated codec use interpreted codecs. See the
[GraalVM guide](graalvm.md) and [Android guide](android.md) for the platform workflows.

## Kotlin use-site targets

Kotlin annotations merge into the same logical property as their Java field, accessor, or selected
constructor parameter. Use explicit targets so behavior does not depend on Kotlin's default-target
policy:

| Kotlin site  | Logical declaration                                        |
| ------------ | ---------------------------------------------------------- |
| `@field:`    | backing field                                              |
| `@get:`      | getter                                                     |
| `@set:`      | setter                                                     |
| `@param:`    | selected constructor parameter                             |
| `@setparam:` | setter value parameter for supported parameter annotations |

`@property:` is unsupported because Fory JSON annotations do not target Kotlin-only property
metadata. `@setparam:JsonProperty` is rejected because setter-parameter naming is not a JSON
property-name contract. `@setparam:JsonIgnore`, `@setparam:JsonCodec`, and
`@setparam:JsonUnwrapped` apply to the exact one-argument setter property. An effective
`@set:JsonCodec` is also supported directly.

`JsonProperty` members merge individually when their explicit values agree; conflicting names,
indexes, or inclusion policies fail. `JsonIgnore` read/write directions merge monotonically, and
repeated `JsonCodec` declarations must be identical. Mixin replacement or removal happens before
this merge. See [Kotlin](kotlin.md#annotations-and-use-site-targets) for an idiomatic example.

## Mixins

Use a JSON Mixin to apply Fory JSON mapping and validation annotations to a class without modifying
that class:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonUnwrapped;

@JsonMixin(target = ThirdPartyUser.class)
abstract class ThirdPartyUserMixin {
  @JsonProperty("user_id")
  long id;

  @JsonUnwrapped(prefix = "address_")
  Address address;
}

ForyJson json = ForyJson.builder().registerMixin(ThirdPartyUserMixin.class).build();
```

A Mixin source is a named abstract class or interface, must not be local or anonymous, must not
extend or implement another type, and is never instantiated. Its annotated fields, methods,
constructors, and parameters select existing declarations on the exact target. The target continues
to own all Java types, values, access, and construction. A registration for a base class does not
affect a subclass, and an interface registration does not affect an implementation.

The source may apply any mapping or validation annotation listed above. Declaring an annotation on
a matched source declaration replaces the target annotation of the same type as a whole; it does
not merge individual annotation members. `JsonType` cannot be added or removed by a Mixin.

Use `JsonMixinRemove` when the target's annotation should not be effective in this configuration:

```java
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonMixinRemove;
import org.apache.fory.json.annotation.JsonRawValue;

@JsonMixin(target = ThirdPartyMessage.class)
abstract class QuotedMessageMixin {
  @JsonMixinRemove(JsonRawValue.class)
  String body;
}
```

The source selector must match exactly one target declaration even when it only removes an
annotation. Registering a different Mixin for the same target on one builder replaces the earlier
registration. Re-registering the same source is harmless. Each `build()` snapshots the current
last-registration-wins mapping, so later builder changes do not mutate an existing `ForyJson`. An
empty source is a no-op and clears an earlier source for the same target when registered later.

A `JsonCodec` supplied by a Mixin is the target's effective annotation. An exact
`registerCodec` registration still wins, while the effective type annotation wins over a built-in
mapping.

On Android, compile a Java-source Mixin for a Java target with the Fory annotation processor. If
either the Mixin source or its exact target is Kotlin, apply the Kotlin KSP processor instead so the
pair's exact retention rules are packaged. GraalVM Native Image discovers reachable Mixins
directly. See the platform guides linked above.

## `JsonProperty`

`JsonProperty` configures the canonical name, serialization index, and null inclusion of one
complete logical property. An annotation on a field, getter, or setter applies to the merged
field/getter/setter group.

```java
import org.apache.fory.json.annotation.JsonProperty;

public final class User {
  @JsonProperty("user_id")
  private long id;

  @JsonProperty(include = JsonProperty.Include.ALWAYS)
  private String displayName;

  @JsonProperty(index = 10)
  private String email;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }
}
```

The supported inclusion values are:

- `DEFAULT`: use `ForyJsonBuilder.writeNullFields`.
- `ALWAYS`: write the property even when its selected value is null.
- `NON_NULL`: omit a null value.

Inclusion affects writing only. A non-default inclusion is invalid for a creator-only property with
no write source. Repeating the same declaration is allowed; conflicting explicit names, indexes, or
non-default inclusion policies within one logical property are rejected. Two properties that
normalize to the same final JSON name are also rejected.

`index` controls relative serialization order. Indexed properties are written in ascending index
order before unindexed properties. Indexes must be non-negative, may contain gaps, and must be
unique among writable properties. `-1` means unspecified; lower values are invalid. An index on a
setter-only, creator-only, or write-ignored property is invalid.

`NON_EMPTY`, aliases, formatting, and independent read/write names are not supported.
`JsonProperty` cannot be combined with an Any logical property or declared on a `JsonAnySetter`.

## `JsonPropertyOrder`

`JsonPropertyOrder` combines a named serialization prefix, property indexes, and final-name
alphabetic ordering:

```java
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;

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

The output order is `id`, `display_name`, `name`, `address`, then `age`. The named prefix is written
first, remaining indexed properties follow in ascending index order, and `alphabetic = true` sorts
the remaining unindexed properties by final JSON name. Without `alphabetic`, those properties keep
their existing relative order. Use `@JsonPropertyOrder(alphabetic = true)` when no named prefix is
needed. Alphabetic comparison uses Java's natural, case-sensitive String order and is
locale-independent.

Order entries match the final JSON name first and the Java logical property name second. The list
may be empty only when `alphabetic` is true. Its entries must be non-empty, unique writable
properties; unknown and duplicate entries fail when object metadata is built.

A subclass declaration replaces both settings from its superclass as a whole. If the subclass has
no declaration, the nearest superclass declaration is used and resolved against the subclass
properties. Interface declarations are not considered. Ordering affects serialization only;
deserialization remains name-based, and subtype discriminators remain before user properties.

An unwrapped group also occupies one position, selected by the group's Java logical property name.
Its child members remain adjacent and retain the child's own order.

A write-enabled `JsonAnyProperty` or `JsonAnyGetter` participates as one position identified by its
Java logical property name. The position emits all dynamic entries in Map iteration order:

```java
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "properties", "timestamp"})
public final class Event {
  public String id;

  @JsonAnyProperty
  public Map<String, Object> properties;

  public long timestamp;
}
```

If `properties` contains `x` and `y`, output order is `id`, `x`, `y`, then `timestamp`; no member
named `properties` is written. Naming strategies do not transform the Any ordering name. An
input-only Any field and `JsonAnySetter` have no write position. Dynamic keys cannot be listed in
`JsonPropertyOrder`, and alphabetic ordering never sorts entries inside the Map.

## Property Naming Strategy

Configure the naming style for logical properties without an explicit non-empty `JsonProperty`
name:

```java
import org.apache.fory.json.PropertyNamingStrategy;

ForyJson json =
    ForyJson.builder()
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .build();
```

The default `LOWER_CAMEL_CASE` preserves the discovered Java logical property name. `SNAKE_CASE`
handles acronym and digit boundaries, for example:

- `userName` becomes `user_name`;
- `URLValue` becomes `url_value`;
- `version2FA` becomes `version2_fa`.

A non-empty `@JsonProperty("...")` value, a parameter-local creator name, a subtype discriminator
property, and dynamic Any keys are already JSON names and are never transformed.

## `JsonIgnore`

`JsonIgnore` is field-targeted and controls the read and write directions of the complete logical
property:

```java
import org.apache.fory.json.annotation.JsonIgnore;

@JsonIgnore(ignoreRead = false, ignoreWrite = true)
private String serverManagedValue;
```

Both flags default to true. A same-named getter or setter cannot restore an ignored direction, and
`JsonProperty` cannot override it. Fory core's `Expose` annotation has no effect in Fory JSON.

## `JsonValue`

`JsonValue` selects one exact `String` field or public zero-argument method as the complete JSON
representation of its owning type. Fory writes the selected value as an ordinary JSON string, with
quotes and normal escaping, instead of writing the owning object's properties:

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

`json.toJson(new UserId("user-1"))` returns `"user-1"`. The method need not use a JavaBean getter
name. It must be public, non-static, zero-argument, and return exactly `String`; a field must be an
eligible non-static instance field. One type may have only one effective value member. An
unannotated method override suppresses an inherited declaration.

`JsonValue` controls serialization by itself. Deserialization additionally requires a
`JsonCreator` constructor or public static factory with exactly one `String` parameter, an empty
`JsonCreator.value()`, and no `JsonProperty` on that parameter. Fory recognizes that shape as the
reverse String constructor; no creator mode is needed. Existing property-list and parameter-local
creator forms are unchanged. Without the matching creator, writing still works and reading the
owning type fails clearly.

A null owner is written and read as JSON `null` without invoking either member or creator. A
non-null owner whose value member returns null is also written as JSON `null`. `JsonValue` does not
change Map key encoding.

## `JsonRawValue`

`JsonRawValue` marks one fixed ordinary `String` property. Fory writes the String directly at the
value position without quotes, escaping, parsing, or validation:

```java
import org.apache.fory.json.annotation.JsonRawValue;

public final class Response {
  public int status;

  @JsonRawValue
  public String body;
}
```

With `status = 200` and `body = "{\"id\":1}"`, the output contains
`{"status":200,"body":{"id":1}}`. The raw String may be any complete JSON value, including an
object, array, number, boolean, quoted JSON string, or `null` token.

This annotation is a trusted write-only escape hatch. Invalid or attacker-controlled content can
make the enclosing output invalid or change its structure. Java null still follows the property's
normal inclusion policy and, when included, is written as JSON `null`.

Reading remains ordinary String-property reading. For example, `{"body":"text"}` can populate the
field, but an object such as `{"body":{"id":1}}` cannot be read back into it. `JsonRawValue` is not
a type-use annotation and does not apply to container elements or Map values. It cannot be placed
on a setter, creator parameter, Any declaration, or the same property occurrence as `JsonCodec`.
As an occurrence-local representation, it keeps the raw String shape even when the value type has
an exact builder-registered codec.

`JsonRawValue` does not collect unknown sibling fields. Unknown fields are skipped unless an
existing `JsonAnyProperty` or `JsonAnyGetter`/`JsonAnySetter` owner captures them. The raw-value and
Any-property features are independent.

`JsonValue` and `JsonRawValue` may be combined on the same String member to write an owning object
as a trusted raw root value. That combination is serialization-only: the ordinary one-String
`JsonCreator` cannot turn an input object or array into a String.

## `JsonBase64`

`JsonBase64` selects a quoted standard Base64 JSON string for one exact `byte[]` field or getter:

```java
import org.apache.fory.json.annotation.JsonBase64;

public final class Attachment {
  @JsonBase64
  public byte[] content;
}
```

Bytes `{1, 2, 3}` are written as `{"content":"AQID"}` and decoded back to the original array.
Fory writes the Base64 characters directly to the JSON output and decodes directly from the JSON
input without creating an intermediate String. Standard Base64 padding is preserved. Java null
follows the property's normal inclusion rule and reads from JSON null as null.

The annotation is not a type-use annotation and does not change ordinary unannotated `byte[]`
properties, container elements, or Map values. It cannot share a logical property with
`JsonRawValue`, an occurrence `JsonCodec`, `JsonFormat`, or an Any declaration. The equivalent explicit codec is
`@JsonCodec(Base64ByteArrayCodec.class)`.

## `JsonFormat`

Use `JsonFormat` on a date/time field to select its JSON text pattern in both directions. Patterns
use `DateTimeFormatter` syntax and the root locale:

```java
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fory.json.annotation.JsonFormat;

public final class Schedule {
  @JsonFormat(pattern = "dd/MM/uuuu")
  public LocalDate day;

  @JsonFormat(pattern = "dd/MM/uuuu")
  public Optional<LocalDate> optionalDay;

  @JsonFormat(pattern = "dd/MM/uuuu")
  public List<LocalDate> days;

  @JsonFormat(pattern = "dd/MM/uuuu")
  public Map<String, LocalDate> daysByName;

  @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss XXX", timezone = "Asia/Shanghai")
  public Instant timestamp;
}
```

For `day = LocalDate.of(2024, 1, 2)`, the property is written as `"day":"02/01/2024"` and
the same text reads back to that date. The annotation applies to the field value when it is a
supported date/time type. For one direct wrapper, it applies to an array or collection element, an
`AtomicReferenceArray` element, an `Optional` or `AtomicReference` content value, or a Map value.
This includes `List`, `Set`, and their concrete `Collection` implementations. Null handling still
follows the property's ordinary inclusion rule.

Supported values are exact `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `ZonedDateTime`,
`Year`, `YearMonth`, `MonthDay`, `OffsetTime`, `OffsetDateTime`, `HijrahDate`, `JapaneseDate`,
`MinguoDate`, and `ThaiBuddhistDate` types. `Instant` uses UTC; zoned and offset types use the zone or
offset carried by the value. The pattern must contain enough information to reconstruct the
declared type.

Set `timezone` to a valid `ZoneId` identifier to format and parse `Instant`, `ZonedDateTime`, or
`OffsetDateTime` in that zone. For example, the `timestamp` field above writes
`Instant.parse("2024-01-02T03:04:05Z")` as `"2024-01-02 11:04:05 +08:00"`. The parsed value keeps
the same instant for matching timezone text. The configured zone supplies missing zone or offset
information during parsing; an explicit zone or offset in the JSON text participates in the usual
`DateTimeFormatter` resolution. Include an offset in the pattern when an exact instant must survive
a daylight saving time overlap. Omitting `timezone` preserves the default behavior described
above. Invalid zone identifiers and a non-empty `timezone` on other supported date/time types are
rejected.

`JsonFormat` is a field annotation, not a type-use annotation. A record component works through its
generated field. Nested wrappers, Map keys, raw or wildcard direct children, JSON Any values, and
unwrapped values are intentionally rejected. Types with ambiguous formatting semantics, including
legacy and SQL date types, `Duration`, `Period`, `TimeZone`, `ZoneId`, and `ZoneOffset`, are not
supported. A wrapper with a complete registered, annotation-selected, polymorphic, or `JsonValue`
representation is also rejected because that representation owns the whole wrapper.
`JsonFormat` cannot share a field with `JsonCodec`, `JsonBase64`, `JsonRawValue`, `JsonAnyProperty`,
`JsonUnwrapped`, or `JsonValue`.

## `JsonUnwrapped`

Use `JsonUnwrapped` to place an object-valued property's members directly in the containing JSON
object:

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

This maps `Person` to `{"age":18,"name_first":"Ada","name_last":"Lovelace"}`. The
optional prefix and suffix apply to each child's final JSON name after `JsonProperty` and the
configured naming strategy. Nested unwrapped properties compose their transformations from the
inside out.

A null child writes no members. On input, Fory creates and assigns the child only after seeing one
of its flattened members. A completely missing group therefore preserves a mutable parent's
initializer value and leaves a record or creator argument at its normal missing-property default.
Partial input constructs the child with the ordinary defaults for its other properties.

Mutable classes, records, and `JsonCreator` classes can be parents or children. A parameter-local
creator parameter may declare a read-only unwrapped group; its required `JsonProperty` value names
the Java creator argument and is not accepted as a JSON wrapper. A parameterized parent is allowed,
but every unwrapped child and intermediate must be an exact raw, non-generic class using Fory's
standard object mapping.

The flattened group occupies one position in the parent's write order. `JsonProperty.index` may
position it, and `JsonPropertyOrder` selects it by Java logical property name. The child's own
property order remains intact. Parent fields are matched before flattened fields, which are matched
before dynamic Any handling.

Fory rejects duplicate final names, recursive chains made only of unwrapped properties,
parameterized children, JSON Any children, polymorphic or custom-codec child roots, and scalar,
array, collection, or Map children. Use `JsonAnyProperty`, `JsonAnyGetter`, or `JsonAnySetter` to
flatten a Map. `JsonProperty.value`, non-default `JsonProperty.include`, `JsonCodec`, and `JsonFormat` are not
valid on an unwrapped property; ordinary child leaf properties may still use them.

## Dynamic Object Members

Use `JsonAnyProperty` when one `Map<String, V>` field should hold otherwise unknown JSON members.
The Map is flattened into the containing object instead of appearing under the field name:

```java
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyProperty;

public final class Event {
  public String id;

  @JsonAnyProperty
  public Map<String, Object> properties = new LinkedHashMap<>();
}
```

For `properties` containing `"source" -> "mobile"`, Fory writes
`{"id":"7","source":"mobile"}`, not a nested `properties` member. Unknown input members are
inserted into the Map. The field reads and writes by default; `JsonIgnore` may select one direction:

```java
import org.apache.fory.json.annotation.JsonIgnore;

@JsonAnyProperty
@JsonIgnore(ignoreRead = true, ignoreWrite = false)
public Map<String, Object> outputOnly;
```

During reading, an existing Map is reused. A null non-final field is initialized when the first
unknown member is encountered. A readable final field on an ordinary mutable object must already
contain a mutable Map. Records and property-list `JsonCreator` types instead receive the accumulated
Map through their construction argument. If no unknown member is present, Fory does not initialize
a null field.

Use `JsonAnyGetter` and `JsonAnySetter` for method-backed writing and reading:

```java
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnySetter;

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

An any-getter is a public instance method with no arguments and a `Map<String, V>` return type. An
any-setter is a public instance method with signature `void method(String, V)`. Either method may be
used alone. When paired, their resolved value types must match after primitive types are boxed. A
primitive any-setter value parameter rejects JSON null. An any-setter is not supported on records or
types that use `JsonCreator`.

A read-enabled `JsonAnyProperty` on a record component supplies that component from unknown input
members. In property-list `JsonCreator` mode, a read-enabled Any field must correspond to one listed
creator argument; parameter-local creator mode cannot bind a field annotation. A write-only Any
field or any-getter cannot occupy a creator argument. If a write-only Any field or any-getter claims
a record component, that component receives its normal Java default during reading.

An any-getter claims its Java logical property: `getProperties()` and `properties()` both claim
`properties`. A same-named field, ordinary getter, or ordinary setter is not also mapped as a fixed
member. Fory does not infer a differently named backing field, so annotate that field with
`JsonIgnore` if it must not be mapped separately. `JsonAnySetter` has no logical property name and
does not claim a backing field.

The Any logical name is used only for property grouping and `JsonPropertyOrder`; it is not itself a
fixed JSON member. An input member with that name is an ordinary dynamic entry rather than a nested
aggregate, and the same dynamic output key remains valid unless another fixed property conflicts
with it.

One effective type hierarchy may use either one `JsonAnyProperty` field or at most one effective
`JsonAnyGetter` and one effective `JsonAnySetter`; the forms cannot be mixed. An unannotated method
override disables an inherited method annotation. Method-backed Any annotations are invalid in
field mode. `JsonProperty` is invalid on an Any setter and on every member of a logical property
claimed by an Any field or getter. A same-named field cannot use `JsonIgnore` to suppress an
any-getter's write direction. Its `ignoreRead` flag also does not disable a separate any-setter.

Dynamic keys are exact JSON member names and retain Map iteration order. A null Map writes no
members, and a null Map value writes JSON null regardless of fixed-property null settings. Null and
non-String output keys are rejected. Raw Maps, wildcard or unresolved keys, and non-String key
types are invalid. Declared fixed members, including members excluded from reading, are not
delivered to an Any input. An output key that conflicts with a fixed property is rejected. Fory
does not inspect an Any Map for a key that duplicates an inline subtype discriminator; such a key
writes a duplicate JSON member. Applications must keep dynamic keys distinct from the active
discriminator. Repeated unknown input names replace the prior Map value, while an any-setter is
invoked for every occurrence. Escaped input names are decoded before delivery.

## `JsonCreator`

Use `JsonCreator` for an immutable class with one public constructor or public static factory. The
creator is the complete read schema; ordinary properties not selected by it are write-only, and
setters are not invoked after construction.

The compact form lists existing Java logical property names in parameter order and reuses their
normalized JSON metadata:

```java
import org.apache.fory.json.annotation.JsonCreator;

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

The parameter-local form gives every parameter an explicit JSON name. It may introduce
creator-only input properties:

```java
@JsonCreator
public static User create(
    @JsonProperty("user_id") long id,
    @JsonProperty("display_name") String name) {
  return new User(id, name);
}
```

Parameter-local names bypass the naming strategy. The two modes cannot be mixed. In compact mode,
names must be non-empty and unique, the name count must equal the parameter count, and parameters
must not also declare `JsonProperty`. In parameter-local mode, every parameter requires a
non-empty, unique `JsonProperty` name.

For a type with `JsonValue`, the empty form also accepts exactly one `String` parameter without
`JsonProperty` and reconstructs the owning value from its JSON string. This value form is distinct
from both property-based forms and is inferred only because the target has `JsonValue`.

A creator must have at least one parameter and cannot be varargs or generic. A constructor must be
public. A factory must be public and static, declare the target class as its exact return type, and
return a non-null value whose runtime class is exactly the target. Missing reference parameters use
null, missing primitives use Java zero values, duplicate members use the last value, and explicit
null for a primitive parameter is rejected. Records cannot declare a property-based `JsonCreator`;
a record with `JsonValue` may annotate its one-String canonical constructor for the value form.

## `JsonValidator`

Use `JsonValidator` for application validation that must run after an object has been completely
constructed and populated:

```java
import org.apache.fory.json.annotation.JsonValidator;

public final class Account {
  public String id;
  public long balance;

  @JsonValidator
  public void validate() {
    if (id == null || id.isEmpty()) {
      throw new IllegalArgumentException("id must not be empty");
    }
    if (balance < 0) {
      throw new IllegalArgumentException("balance must not be negative");
    }
  }
}
```

A validator must be a public instance method with no arguments and a `void` return type. The method
may declare exceptions. Every effective validator runs exactly once after its object is complete,
including objects created by a `JsonCreator`, records, nested objects, unwrapped objects, and
selected subtypes. A JSON null value does not invoke a validator. If a class has multiple
validators, their relative order is unspecified and validation stops at the first failure.
`JsonValidator` has no index or ordering member.

An invalid validator declaration is rejected when Fory JSON prepares the type. `Error` is
propagated directly; every other validator invocation failure is reported as `ForyJsonException`
with the original cause. A `JsonCreator` constructor or factory may validate during construction
instead; omit `JsonValidator` when the creator already enforces the complete invariant.

A Mixin can add validation to a matching public method on an exact target:

```java
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonValidator;

@JsonMixin(target = ThirdPartyAccount.class)
abstract class ThirdPartyAccountMixin {
  @JsonValidator
  public abstract void checkValid();
}
```

The Mixin method uses the same exact method-signature matching as other Mixin methods. Remove a
target validator for one configuration by placing
`@JsonMixinRemove(JsonValidator.class)` on the matching Mixin method. An unannotated override is the
effective declaration and does not inherit the overridden method's validator annotation.

`JsonValidator` applies to Fory JSON's default object mapping. An exact registered codec, a
complete type-level `JsonCodec`, or a complete `JsonValue` representation must perform any required
validation itself.

On Android, compile a directly annotated validator model with `JsonType` and the Fory annotation
processor. A validator supplied by a Mixin uses the processor output for that exact Mixin-target
pair. GraalVM Native Image discovers a direct `JsonType` or registered Mixin and prepares its
effective validators without annotation-processor output. Neither platform requires application
reflection configuration for validators.

## `JsonSubTypes`

`JsonSubTypes` declares the complete finite subtype table for an interface or abstract class. Each
entry has a case-sensitive logical JSON name and exactly one trusted Java type source:

- `value = Circle.class`; or
- `className = "com.example.shape.Circle"` using the exact Java binary name.

`className` is useful when an API JAR must not depend on an implementation JAR. It is resolved by
the fixed builder class loader when the table is built. JSON input never supplies a Java class name
and cannot add entries. Post-build subtype registration and open subtype discovery are not supported.

The default `PROPERTY` inclusion writes an inline discriminator as the first output member:

```java
import org.apache.fory.json.annotation.JsonSubTypes;

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

Property input accepts the discriminator at any direct object-member position, but it must appear
exactly once, be a string, and name a configured subtype. The discriminator property bypasses the
naming strategy and must not collide with a subtype's ordinary JSON property. Property inclusion
requires the subtype's ordinary object representation.

`WRAPPER_OBJECT` uses one outer member:

```java
@JsonSubTypes(
    inclusion = JsonSubTypes.Inclusion.WRAPPER_OBJECT,
    value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
public interface Shape {}
```

```json
{ "circle": { "radius": 2 } }
```

`WRAPPER_ARRAY` uses exactly two array elements:

```java
@JsonSubTypes(
    inclusion = JsonSubTypes.Inclusion.WRAPPER_ARRAY,
    value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
public interface Shape {}
```

```json
["circle", { "radius": 2 }]
```

The configuration rules are strict:

| Inclusion        | `property`             | Subtype representation                                |
| ---------------- | ---------------------- | ----------------------------------------------------- |
| `PROPERTY`       | Required and non-empty | Ordinary object members inline with the discriminator |
| `WRAPPER_OBJECT` | Must be empty          | Complete subtype value inside one-member object       |
| `WRAPPER_ARRAY`  | Must be empty          | Complete subtype value as array element 1             |

Both wrappers may delegate to an exact custom subtype codec. All three inclusions write null as
plain JSON null unless codec precedence selects a custom complete-value codec for the declared
base, replacing the annotation.

The base must be an interface or abstract class. Every entry must resolve to a unique concrete,
assignable class, and serialization accepts only an exact listed runtime class. Listing a parent
does not implicitly admit its descendants. The annotation is read from the declared base itself and
is not inherited from another annotated interface or abstract class. Readers accept only the
configured inclusion; changing inclusion is a wire-format change and there is no dual-read
fallback.

At GraalVM native-image runtime, annotate the base with `JsonType` and use class-literal entries
rather than `className` entries. Listed class-literal subtypes are registered automatically.
