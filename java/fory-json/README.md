# Fory JSON

Fory JSON is Apache Fory's thread-safe Java JSON codec. It provides interpreted and
runtime-generated codecs for Java objects, records, immutable creator-based classes, common JDK
types, generic containers, exact custom codecs, and finite annotation-declared polymorphism.

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

Unknown input properties are skipped. Null object properties are omitted by default. Default JSON
property discovery order is not a compatibility contract; use `JsonPropertyOrder` or
`JsonProperty.index` when emitted property order must be explicit.

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
builder and runtime for every operation. A registered `JsonCodec` and `JsonTypeChecker` may be
called concurrently and must also be thread-safe.

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

## JSON annotations

Fory JSON defines five annotations in `org.apache.fory.json.annotation`. They are Fory JSON APIs,
not Jackson, Gson, or Fory binary-protocol compatibility annotations.

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

`NON_EMPTY`, aliases, formatting, annotation-selected codecs, and independent read/write names are
not supported.

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

A non-empty `@JsonProperty("...")` value, a parameter-local creator name, and a subtype
discriminator property are already JSON names and are never transformed.

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

A creator must have at least one parameter and cannot be varargs or generic. A constructor must be
public. A factory must be public and static, declare the target class as its exact return type, and
return a non-null value whose runtime class is exactly the target. Missing reference parameters use
null, missing primitives use Java zero values, duplicate members use the last value, and explicit
null for a primitive parameter is rejected. Records use their canonical constructor and cannot
declare `JsonCreator`.

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
plain JSON null unless an exact custom codec registered for the declared base replaces the
annotation.

The base must be an interface or abstract class. Every entry must resolve to a unique concrete,
assignable class, and serialization accepts only an exact listed runtime class. Listing a parent
does not implicitly admit its descendants. The annotation is read from the declared base itself and
is not inherited from another annotated interface or abstract class. Readers accept only the
configured inclusion; changing inclusion is a wire-format change and there is no dual-read
fallback.

At GraalVM native-image runtime, use class-literal entries rather than `className` entries.

## Custom codecs

Register a custom `JsonCodec<T>` when a class needs a representation that is not provided by the
built-in mapping. Registration is exact-class only and replaces the built-in, object, or subtype
annotation codec for that class.

A codec implements both output representations and all three input representations. It owns one
complete value, including its null behavior:

```java
import java.math.BigDecimal;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public final class MoneyCodec implements JsonCodec<Money> {
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

The parent object's property name, ignore direction, and inclusion policy are applied before the
selected value codec runs. An omitted null property does not call its codec; a selected property
does. The same codec instance is shared across concurrent operations, so implementations must be
thread-safe.

Registering a custom codec for a `JsonSubTypes` base replaces that base's subtype annotation.
Registering one for a listed subtype is supported by the two wrapper inclusions but not by inline
property inclusion.

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
thread-safe. Built-in scalar types normally do not invoke the custom checker. A custom codec does
not bypass Fory's fixed disallow list.

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
  root wrapping, format annotations, or annotation-driven raw JSON values;
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
| Ordinary object cannot be constructed     | Add a usable no-argument constructor, use a record or `JsonCreator`, or register a custom codec; Android and GraalVM native image are stricter      |
| Getter/setter annotation fails            | The method is not an eligible public JavaBean accessor, or field mode is enabled                                                                    |
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
