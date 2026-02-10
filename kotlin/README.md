# Apache Fory™ Kotlin

Apache Fory™ Kotlin provides optimized serializers for Kotlin types, built on top of Fory Java. It delivers high-performance serialization for the Kotlin ecosystem with full support for Kotlin-specific types and idioms.

Most standard Kotlin types work out of the box with the default Fory Java implementation, while Fory Kotlin adds additional support for Kotlin-specific types.

## Features

### Supported Types

Apache Fory™ Kotlin is tested and works with the following types:

- **Data classes**: Full support for data class serialization with all field types
- **Primitives**: `Byte`, `Boolean`, `Int`, `Short`, `Long`, `Char`, `Float`, `Double` (works out of the box)
- **Unsigned primitives**: `UByte`, `UShort`, `UInt`, `ULong`
- **Unsigned arrays**: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- **Collections**: `ArrayList`, `HashMap`, `HashSet`, `LinkedHashSet`, `LinkedHashMap` (works out of the box), `ArrayDeque`
- **Empty collections**: `emptyList`, `emptyMap`, `emptySet`
- **Arrays**: All standard array types (works out of the box)
- **Stdlib types**: `Pair`, `Triple`, `Result`
- **Ranges**: `IntRange`, `LongRange`, `CharRange`, `IntProgression`, `LongProgression`, `CharProgression`, `UIntRange`, `ULongRange`
- **Other**: `kotlin.text.Regex`, `kotlin.time.Duration`, `kotlin.uuid.Uuid`, `kotlin.random.Random`

### Kotlin-Specific Features

- **Default Value Support**: Automatic handling of Kotlin data class default parameters during schema evolution
- **Unsigned Type Support**: Full support for Kotlin unsigned primitives and arrays
- **Range Serialization**: Optimized serializers for Kotlin ranges and progressions

## Quick Start

```kotlin
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.serializer.kotlin.KotlinSerializers

data class Person(val name: String, val id: Long, val github: String)
data class Point(val x: Int, val y: Int, val z: Int)

fun main() {
    // Create Fory instance (should be reused)
    val fory: ThreadSafeFory = Fory.builder()
        .requireClassRegistration(true)
        .buildThreadSafeFory()

    // Register Kotlin serializers
    KotlinSerializers.registerSerializers(fory)

    // Register your classes
    fory.register(Person::class.java)
    fory.register(Point::class.java)

    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
}
```

## Default Value Support

Apache Fory™ Kotlin provides support for Kotlin data class default values during deserialization. This feature enables forward/backward compatibility when data class schemas evolve.

### How It Works

When a Kotlin data class has parameters with default values, Fory can:

1. **Detect default values** using Kotlin reflection
2. **Apply default values** during deserialization when fields are missing from serialized data
3. **Support schema evolution** by allowing new fields with defaults to be added without breaking existing serialized data

### Example Usage

```kotlin
import org.apache.fory.Fory
import org.apache.fory.config.CompatibleMode
import org.apache.fory.serializer.kotlin.KotlinSerializers

// Original data class
data class User(val name: String, val age: Int)

// Evolved data class with new field and default value
data class UserV2(val name: String, val age: Int, val email: String = "default@example.com")

fun main() {
    val fory = Fory.builder()
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .build()
    KotlinSerializers.registerSerializers(fory)
    fory.register(User::class.java)
    fory.register(UserV2::class.java)

    // Serialize with old schema
    val oldUser = User("John", 30)
    val serialized = fory.serialize(oldUser)

    // Deserialize with new schema - missing field gets default value
    val newUser = fory.deserialize(serialized) as UserV2
    println(newUser) // UserV2(name=John, age=30, email=default@example.com)
}
```

## Thread-Safe Usage

For multi-threaded applications, use `ThreadSafeFory`:

```kotlin
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.ThreadLocalFory
import org.apache.fory.serializer.kotlin.KotlinSerializers

object ForyHolder {
    val fory: ThreadSafeFory = ThreadLocalFory { classLoader ->
        Fory.builder()
            .withClassLoader(classLoader)
            .requireClassRegistration(true)
            .build().also {
                KotlinSerializers.registerSerializers(it)
                it.register(Person::class.java)
            }
    }
}

// Use in multiple threads
val bytes = ForyHolder.fory.serialize(person)
val result = ForyHolder.fory.deserialize(bytes)
```

## Configuration

Fory Kotlin is built on Fory Java, so all Java configuration options are available:

```kotlin
import org.apache.fory.Fory
import org.apache.fory.config.CompatibleMode
import org.apache.fory.serializer.kotlin.KotlinSerializers

val fory = Fory.builder()
    // Enable reference tracking for circular references
    .withRefTracking(true)
    // Enable schema evolution support
    .withCompatibleMode(CompatibleMode.COMPATIBLE)
    // Enable async compilation for better startup performance
    .withAsyncCompilation(true)
    // Compression options
    .withIntCompressed(true)
    .withLongCompressed(true)
    .build()

KotlinSerializers.registerSerializers(fory)
```

## Documentation

| Resource          | Link                                               |
| ----------------- | -------------------------------------------------- |
| **Website**       | https://fory.apache.org/docs/guide/kotlin          |
| **Source Docs**   | [docs/guide/kotlin](../docs/guide/kotlin/index.md) |
| **Java Guide**    | [docs/guide/java](../docs/guide/java/index.md)     |
| **API Reference** | [Fory Java API](../java/README.md)                 |

## Installation

### Maven

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-kotlin</artifactId>
  <version>0.15.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.apache.fory:fory-kotlin:0.15.0")
```

## Building

Fory Kotlin requires Fory Java to be installed first:

```bash
# Install Fory Java
cd ../java && mvn -T16 install -DskipTests

# Build Fory Kotlin
cd ../kotlin
mvn clean package
```

## Testing

```bash
mvn test
```

## Code Format

```bash
mvn spotless:apply
```

## Additional Notes

- **Fory Reuse**: Always reuse Fory instances; creation is expensive
- **withDefault Collections**: Wrapper classes created from `withDefault` method are currently not supported

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Licensed under the [Apache License 2.0](../LICENSE).
