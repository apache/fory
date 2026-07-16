# Fory JSON

Fory JSON is Apache Fory's thread-safe Java JSON codec. It provides interpreted and
runtime-generated codecs for Java objects, records, immutable creator-based classes, common JDK
types, generic containers, custom complete-value codecs, and finite annotation-declared
polymorphism.

Fory JSON is a separate data format from Fory's binary native and xlang protocols. Use it when a
system must exchange ordinary JSON with browsers, APIs, logs, configuration, or another JSON
implementation. Use the Fory binary protocol when you need cross-language schema metadata,
reference identity, circular graphs, or Fory's binary-only features.

## Requirements and installation

The module targets Java 8 bytecode. Record mapping requires Java 17 or later.

Fory JSON is currently available from the source tree as `1.4.0-SNAPSHOT`. Until a published Fory
release contains the module, install it into the local Maven repository from the repository root:

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

Gradle, using `mavenLocal()` while consuming the snapshot:

```kotlin
implementation("org.apache.fory:fory-json:1.4.0-SNAPSHOT")
```

Use the same version for every Fory module in one application. After `fory-json` is published,
replace the snapshot with the released version that contains it.

### JDK 25 and later

On JDK 25 and later, open `java.lang.invoke` to Fory core. For a classpath application:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

For a module-path application:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

The JPMS module name of Fory JSON is `org.apache.fory.json`.

## Quick start

Create one `ForyJson` instance and reuse it. The instance is thread-safe and has no close lifecycle.

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
Null object properties are omitted by default. Default JSON property discovery order is not a
compatibility contract; use `JsonPropertyOrder` or `JsonProperty.index` when emitted property order
must be explicit.

## Reading and writing APIs

Fory JSON supports String input/output and UTF-8 byte input/output. It does not currently provide an
`InputStream` parsing API.

| Operation            | Runtime type              | Declared `Class`                | Declared `TypeRef`                 |
| -------------------- | ------------------------- | ------------------------------- | ---------------------------------- |
| String output        | `toJson(value)`           | `toJson(value, type)`           | `toJson(value, typeRef)`           |
| UTF-8 bytes          | `toJsonBytes(value)`      | `toJsonBytes(value, type)`      | `toJsonBytes(value, typeRef)`      |
| UTF-8 `OutputStream` | `writeJsonTo(value, out)` | `writeJsonTo(value, type, out)` | `writeJsonTo(value, typeRef, out)` |
| String input         | -                         | `fromJson(text, type)`          | `fromJson(text, typeRef)`          |
| UTF-8 input          | -                         | `fromJson(bytes, type)`         | `fromJson(bytes, typeRef)`         |

Every `fromJson` call consumes exactly one JSON value and rejects trailing non-whitespace content.
Returned Strings and byte arrays are detached from internal reusable buffers.

`writeJsonTo` buffers the complete UTF-8 document, performs one `OutputStream.write`, and neither
flushes nor closes the caller-owned stream. It is an output convenience API, not incremental JSON
streaming. I/O failures are wrapped in `ForyJsonException`.

### Generic types

Use `TypeRef` whenever a root type contains generic arguments:

```java
import java.util.List;
import org.apache.fory.json.ForyJson;
import org.apache.fory.reflect.TypeRef;

ForyJson json = ForyJson.builder().build();
TypeRef<List<User>> usersType = new TypeRef<List<User>>() {};

List<User> users = json.fromJson("[{\"id\":7,\"name\":\"Alice\"}]", usersType);
String encoded = json.toJson(users, usersType);
```

Declared writes require a fully bound type. Wildcards and type variables are rejected. A non-null
value must be assignable to the declared raw type.

The declared schema controls serialization. For example, a property declared as a concrete parent
class uses the parent's mapped properties rather than automatically adding subclass-only fields. A
declared `Object` value uses runtime dispatch when writing and natural JSON mapping when reading.

### Declared types and polymorphism

The no-type write overloads dispatch from the runtime class. Use a declared-type overload when a
base type owns `JsonSubTypes` metadata:

```java
Shape shape = new Circle(2);

json.toJson(shape);              // Circle's concrete representation
json.toJson(shape, Shape.class); // Shape's configured subtype representation
json.toJsonBytes(shape, Shape.class);
json.writeJsonTo(shape, Shape.class, outputStream);
```

For containers of polymorphic values, carry the declared base type in `TypeRef`:

```java
TypeRef<List<Shape>> shapesType = new TypeRef<List<Shape>>() {};
String encoded = json.toJson(shapes, shapesType);
```

## Thread safety, reuse, and code generation

`ForyJson` is immutable and thread-safe after `build()`. Reuse one instance instead of creating a
builder and runtime for every operation. Registered and annotation-selected `JsonValueCodec`
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

`withConcurrencyLevel` configures the number of reusable operation states, not a maximum number of
concurrent callers. When all reusable states are busy, Fory JSON creates a temporary state rather
than serializing callers through one global lock.

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
support is optional and does not make Guava a required runtime dependency.

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

| Builder method                         | Default                                                  | User-visible effect                                                  |
| -------------------------------------- | -------------------------------------------------------- | -------------------------------------------------------------------- |
| `writeNullFields(boolean)`             | `false`                                                  | Default inclusion of null object properties                          |
| `withCodegen(boolean)`                 | `true`                                                   | Enable generated object codecs                                       |
| `withAsyncCompilation(boolean)`        | `true`                                                   | Compile generated codecs asynchronously                              |
| `withFieldMode(boolean)`               | `false`                                                  | When true, discover fields without getters/setters                   |
| `withPropertyNamingStrategy(strategy)` | `LOWER_CAMEL_CASE`                                       | Name properties without an explicit `JsonProperty` name              |
| `withClassLoader(loader)`              | Snapshotted thread context loader, then Fory JSON loader | Resolve annotation-declared subtype class names                      |
| `maxDepth(int)`                        | `20`                                                     | Maximum nested object/array depth for reads and writes               |
| `withConcurrencyLevel(int)`            | `max(1, 2 * processors)`                                 | Number of reusable concurrent operation states                       |
| `withBufferSizeLimitBytes(int)`        | 2 MiB                                                    | Maximum reusable capacity retained by each pooled writer             |
| `registerCodec(type, codec)`           | None                                                     | Replace the exact class's complete JSON codec                        |
| `withTypeChecker(checker)`             | No custom checker                                        | Apply an application type policy in addition to Fory's disallow list |

Depth, concurrency level, and buffer retention limit must be positive. The buffer retention setting
does not limit JSON input or output size; it only limits reusable writer storage retained after an
operation. Apply request/body size limits at the transport boundary when parsing untrusted input.

Builder mutation after `build()` does not modify an existing `ForyJson` runtime.

On Android and in a GraalVM native image, runtime code generation and asynchronous compilation are
automatically disabled. Every other builder option keeps the behavior described above.

## JSON annotations

Fory JSON defines fourteen annotations in `org.apache.fory.json.annotation`, including `JsonCodec` for
complete-value codec selection and `JsonType` for generated model execution and retention.
They are Fory JSON APIs, not Jackson, Gson, or Fory binary-protocol compatibility annotations.

`JsonType` asks the annotation processor to generate direct property and creator operations plus
exact retention rules. It is not inherited, so annotate each eligible concrete model that needs a
generated companion. A directly annotated `JsonValue` Record also receives a companion for its
value accessor and canonical constructor. Ordinary unannotated classes may still use reflection; on
Android they need application-authored exact R8 rules. Android-desugared Records require `JsonType`
and the processor. A directly annotated model that uses the default object codec fails during codec
creation if its generated companion is missing.
See the [GraalVM guide](../../docs/guide/java/graalvm-support.md) and
[Android guide](../../docs/guide/java/android-support.md) for the platform workflows.

### `JsonProperty`

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

### `JsonPropertyOrder`

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

### Property naming strategy

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

### `JsonIgnore`

`JsonIgnore` is field-targeted and controls the read and write directions of the complete logical
property:

```java
import org.apache.fory.json.annotation.JsonIgnore;

@JsonIgnore(ignoreRead = false, ignoreWrite = true)
private String serverManagedValue;
```

Both flags default to true. A same-named getter or setter cannot restore an ignored direction, and
`JsonProperty` cannot override it. Fory core's `Expose` annotation has no effect in Fory JSON.

### `JsonValue`

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

### `JsonRawValue`

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

### `JsonBase64`

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
`JsonRawValue`, an occurrence `JsonCodec`, or an Any declaration. The equivalent explicit codec is
`@JsonCodec(Base64ByteArrayCodec.class)`.

### `JsonUnwrapped`

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

Fory rejects duplicate or hash-colliding final names, recursive chains made only of unwrapped
properties, parameterized children, JSON Any children, polymorphic or custom-codec child roots,
and scalar, array, collection, or Map children. Use `JsonAnyProperty`, `JsonAnyGetter`, or
`JsonAnySetter` to flatten a Map. `JsonProperty.value`, non-default `JsonProperty.include`, and
`JsonCodec` are not valid on an unwrapped property; ordinary child leaf properties may still use
them.

### Dynamic object members

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
delivered to an Any input. An output key is rejected when its Fory field-name hash conflicts with a
fixed property; this also covers differently spelled hash collisions. Fory does not inspect an Any
Map for a key whose name or Fory field-name hash conflicts with an inline subtype discriminator. An
exact-name output key writes a duplicate JSON member; on input, a differently spelled hash
collision is classified as the discriminator by the child field table. Applications must keep
dynamic keys distinct from the active discriminator by both name and hash. Fixed input lookup is
also hash-based, so a differently spelled colliding name follows the fixed member instead of Any
handling. Repeated unknown input names replace the prior Map value, while an any-setter is invoked
for every occurrence. Escaped input names are decoded before delivery.

### `JsonCreator`

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

### `JsonSubTypes`

`JsonSubTypes` declares the complete finite subtype table for an interface or abstract class. Each
entry has a case-sensitive logical JSON name and exactly one trusted Java type source:

- `value = Circle.class`; or
- `className = "com.example.shape.Circle"` using the exact Java binary name.

`className` is useful when an API JAR must not depend on an implementation JAR. It is resolved by
the fixed builder class loader when the table is built. JSON input never supplies a Java class name
and cannot add entries. Runtime registration and open subtype discovery are not supported.

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

## Custom codecs

`JsonValueCodec<T>` is Fory JSON's streaming codec SPI for one complete JSON value. It writes
directly to Fory's String or UTF-8 writer and reads directly from Fory's Latin-1, UTF-16, or UTF-8
reader. It is not a JSON abstract syntax tree (AST) or `JsonNode` codec. It owns the complete value,
including JSON null, but never handles a Map key; `MapKeyCodec` remains responsible for JSON object
member names.

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

```java
import org.apache.fory.json.ForyJson;

ForyJson json =
    ForyJson.builder()
        .registerCodec(Money.class, new MoneyCodec())
        .build();
```

The containing property still controls its name, ignore direction, and null-inclusion policy. If a
null property is omitted, the value codec is not called. If the property is emitted, or the value
is an array element, collection element, map value, Optional value, or atomic-reference value, the
codec receives and owns null. The registered instance is shared across concurrent operations and
must be thread-safe.

Registering a custom codec for a `JsonSubTypes` base replaces that base's subtype annotation.
Registering one for a listed subtype is supported by the two wrapper inclusions but not by inline
property inclusion.

### Selecting codecs with `JsonCodec`

Use `@JsonCodec` on a class, record, enum, or interface to declare its default complete-value
codec. The positional form is shorthand for `value`:

```java
import org.apache.fory.json.annotation.JsonCodec;

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
GraalVM object models follow the `JsonType` workflow in the
[GraalVM guide](../../docs/guide/java/graalvm-support.md).

## Type validation and untrusted input

Fory JSON never derives a Java class name from JSON input. It always applies Fory's fixed disallow
list. Add an application allow-list with `withTypeChecker` when only selected model packages should
be mapped:

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
checker is used while application types are prepared for both serialization and parsing and must be
thread-safe. Built-in scalar types normally do not invoke the custom checker, but selecting an
application codec for a built-in target makes that target subject to the checker. A custom codec
does not bypass Fory's fixed disallow list.

`withClassLoader` sets the fixed loader for annotation-declared subtype `className` entries. If it
is not configured, `build()` snapshots the current thread context class loader and falls back to the
loader that defined `ForyJson`. Later thread context loader changes do not affect the runtime.

`maxDepth` limits nested arrays and objects but is not an input-byte or memory quota. Apply external
request size, timeout, and resource controls appropriate to the application's trust boundary.

The following types are rejected by default because their natural JSON mapping would create unsafe
or ambiguous behavior: `Class`, `URL`, `InetAddress`, and `InetSocketAddress`. `URL` may be supported
with an application-owned exact custom codec. Arbitrary `Number` and `CharSequence` subclasses also
require an exact supported or custom codec.

## Limits and unsupported features

Fory JSON intentionally has a smaller semantic surface than the Fory binary protocol and general
Jackson object mapping:

- no shared-reference identity or circular-reference protocol;
- no open polymorphism, JSON class-name IDs, runtime subtype discovery, or runtime subtype table
  extension;
- no `InputStream` parser or incremental `OutputStream` writer on the `ForyJson` root API;
- no pretty-print configuration;
- no Jackson/Gson annotation compatibility layer;
- no aliases, views, filters, injection, managed/back references, object identity annotations,
  root wrapping, or format annotations;
- no Fory core `Expose` processing.

Circular graphs eventually fail `maxDepth`; they are not reconstructed. Use Fory core's binary
native or xlang protocol when reference identity or cycles are required.

## Errors and troubleshooting

| Symptom                                   | Likely cause and action                                                                                                                             |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ForyJsonException` while parsing         | Invalid JSON grammar, type mismatch, unsupported mapping, depth violation, or trailing content; inspect the message and target type                 |
| `InsecureException`                       | Fory's disallow list or the configured `JsonTypeChecker` rejected a class                                                                           |
| `IllegalArgumentException` from a builder | Depth, concurrency level, or retained buffer limit is not positive                                                                                  |
| Declared write is rejected                | The value is not assignable to the declared type, the type contains a wildcard/type variable, or null was supplied for a primitive                  |
| Immutable value is not populated          | Use a record, a valid `JsonCreator`, or an exact custom codec                                                                                       |
| `JsonValue` read fails                    | Add one plain `String` `JsonCreator`, or register an exact custom codec                                                                             |
| Raw JSON output is invalid                | Supply exactly one trusted, complete JSON value to the `JsonRawValue` property                                                                      |
| Ordinary object cannot be constructed     | Add a usable no-argument constructor, use a record or `JsonCreator`, or register a custom codec; Android and GraalVM native image are stricter      |
| Ordinary accessor annotation fails        | The method is not an eligible public JavaBean accessor, or field mode is enabled                                                                    |
| Any annotation fails                      | Use exactly one field-backed form or one valid method-backed pair with resolved `Map<String, V>` types; method annotations require non-field mode   |
| Codec annotation fails                    | Resolve same-node or hierarchy conflicts, remove a hidden nested override, or use a public no-argument codec class                                  |
| Subtype is rejected                       | The base is not declared on the write, the runtime class is not an exact table entry, or the input wire shape differs from the configured inclusion |
| Collection cannot be read                 | Target a supported interface/common implementation or register a custom codec                                                                       |
| OutputStream write fails                  | The underlying `IOException` is wrapped as the cause of `ForyJsonException`                                                                         |

Fory JSON mapping, syntax, codec, depth, and output failures use `ForyJsonException`. User code may
still throw its own runtime exception. Creator exceptions other than `Error` are wrapped with their
original cause.

## Related documentation

- [Fory JSON website guide](https://fory.apache.org/docs/guide/java/json_support)
- [Java native serialization](https://fory.apache.org/docs/guide/java/native_serialization)
- [Java xlang serialization](https://fory.apache.org/docs/guide/java/xlang_serialization)
- [Java configuration](https://fory.apache.org/docs/guide/java/configuration)
