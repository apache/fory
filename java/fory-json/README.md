# Fory JSON

Fory JSON is a thread-safe Java JSON codec with interpreted and runtime-generated object codecs.
It supports ordinary mutable classes, Java records, immutable creator-based classes, exact custom
codecs, and finite annotation-declared polymorphism.

## Installation

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-json</artifactId>
  <version>1.3.0</version>
</dependency>
```

## Basic usage

Reuse one `ForyJson` instance across threads:

```java
import org.apache.fory.json.ForyJson;

ForyJson json = ForyJson.builder().build();

String text = json.toJson(value);
byte[] utf8 = json.toJsonBytes(value);
User user = json.fromJson(text, User.class);
```

Unknown object members are skipped. By default, null object properties are omitted. Root null values
are always written as JSON `null`.

## JSON annotations

Fory JSON defines four annotations in `org.apache.fory.json.annotation`. They are Fory APIs, not
Jackson compatibility annotations.

### `JsonProperty`

`JsonProperty` configures one logical property. Fory first groups a field, getter, and setter by
their Java property name; an annotation on one member applies to the entire group.

```java
public final class User {
  @JsonProperty("user_id")
  private long id;

  @JsonProperty(include = JsonProperty.Include.ALWAYS)
  private String displayName;

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }
}
```

Version 1 supports these inclusion values:

- `DEFAULT`: use `ForyJsonBuilder.writeNullFields`.
- `ALWAYS`: write the property even when its value is null.
- `NON_NULL`: omit a null value.

Repeated identical declarations are allowed. Conflicting explicit names or inclusion policies on a
field/getter/setter group are rejected when metadata is built. `NON_EMPTY`, aliases, formatting,
views, and annotation-selected codecs are not supported.

### Property naming

Configure a global naming style for properties without an explicit `JsonProperty` name:

```java
ForyJson json =
    ForyJson.builder()
        .withPropertyNamingStrategy(PropertyNamingStrategy.LOWER_SNAKE_CASE)
        .build();
```

The default is `IDENTITY`. `LOWER_SNAKE_CASE` handles acronym and digit boundaries, for example
`userName -> user_name`, `URLValue -> url_value`, and `version2FA -> version2_fa`. A non-empty
`@JsonProperty("...")` value is already a JSON name and is never transformed.

### `JsonIgnore`

`JsonIgnore` is field-targeted and controls read and write directions for the complete logical
property:

```java
@JsonIgnore(ignoreRead = false, ignoreWrite = true)
private String serverManagedValue;
```

A same-named getter or setter cannot restore an ignored direction. Fory core's `Expose` annotation
has no effect in Fory JSON.

### `JsonCreator`

Use `JsonCreator` for an immutable class with one public constructor or public static factory. The
creator is the complete read schema; Fory does not invoke setters after construction.

The compact form lists existing Java logical property names in parameter order and reuses their
normalized JSON metadata:

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

The parameter-local form gives each input parameter an explicit JSON name and may define
creator-only input properties:

```java
@JsonCreator
public static User create(
    @JsonProperty("user_id") long id,
    @JsonProperty("display_name") String name) {
  return new User(id, name);
}
```

Parameter-local names are not transformed by the naming strategy. Missing reference parameters use
null, missing primitives use Java zero values, duplicate JSON members use the last value, and an
explicit null for a primitive parameter is rejected. Records keep their canonical constructor and
cannot declare `JsonCreator`.

### `JsonSubTypes`

`JsonSubTypes` declares a closed, finite subtype table. The default wire shape stores an inline
discriminator as the first object member:

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

`className` is useful when an API JAR must not depend on an implementation JAR. Configure a fixed
loader when necessary:

```java
ForyJson json = ForyJson.builder().withClassLoader(pluginClassLoader).build();
```

If no loader is configured, `build()` snapshots the current thread context class loader and falls
back to the loader that defined `ForyJson`. JSON input never supplies a class name and cannot expand
the declared table.

Wrapper-object mode is explicit:

```java
@JsonSubTypes(
    wrapperObject = true,
    value = {@JsonSubTypes.Type(value = Circle.class, name = "circle")})
public interface Shape {}
```

```json
{ "circle": { "radius": 2 } }
```

Inline mode requires ordinary object subtypes because it writes their members inside one object.
Wrapper mode permits any subtype representation, including an exact custom codec.

## Declared-type writes

Existing write methods use the runtime class and therefore do not add polymorphic metadata. Use a
declared-type overload when a base type owns `JsonSubTypes`:

```java
Shape shape = new Circle(2);

json.toJson(shape);              // Concrete Circle representation
json.toJson(shape, Shape.class); // Closed Shape representation
json.toJsonBytes(shape, Shape.class);
json.writeJsonTo(shape, Shape.class, outputStream);
```

Generic declared types use `TypeRef`:

```java
String text = json.toJson(shapes, new TypeRef<List<Shape>>() {});
```

Typed writes require a fully bound type and reject wildcards and type variables. The value must be
assignable to the declared raw type.

## Custom codecs

An exact custom codec owns the complete JSON representation of its registered class:

```java
ForyJson json =
    ForyJson.builder().registerCodec(Money.class, moneyCodec).build();
```

Parent `JsonProperty` naming and inclusion still decide whether and under which name a property is
written. The custom codec decides how the selected value itself is represented. An exact custom
codec registered for a `JsonSubTypes` base replaces that subtype annotation.

## Object graph boundary

Fory JSON does not preserve shared-reference identity or circular references. Nesting is bounded by
`maxDepth`, and circular graphs eventually fail that limit. Use Fory core's binary protocol when
reference identity or cycles must be preserved.

Fory JSON also intentionally omits open polymorphism, class-name IDs in JSON, aliases, views,
filters, injection, managed/back references, object identity annotations, raw values, root wrapping,
format annotations, and runtime subtype registration.
