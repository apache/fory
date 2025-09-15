# Apache Fory™ Kotlin

This provides additional Apache Fory™ support for Kotlin Serialization on JVM:

Most standard kotlin types are already supported out of the box with the default Apache Fory™ java implementation.

Apache Fory™ Kotlin provides additional tests and implementation support for Kotlin types.

Apache Fory™ Kotlin is tested and works with the following types:

- primitives: `Byte`, `Boolean`, `Int`, `Short`, `Long`, `Char`, `Float`, `Double`, `UByte`, `UShort`, `UInt`, `ULong`.
- `Byte`, `Boolean`, `Int`, `Short`, `Long`, `Char`, `Float`, `Double` works out of the box with the default fory java implementation.
- stdlib `collection`: `ArrayDeque`, `ArrayList`, `HashMap`,`HashSet`, `LinkedHashSet`, `LinkedHashMap`.
- `ArrayList`, `HashMap`,`HashSet`, `LinkedHashSet`, `LinkedHashMap` works out of the box with the default fory java implementation.
- `String` works out of the box with the default fory java implementation.
- arrays: `Array`, `BooleanArray`, `ByteArray`, `CharArray`, `DoubleArray`, `FloatArray`, `IntArray`, `LongArray`, `ShortArray`
- all standard array types work out of the box with the default fory java implementation.
- unsigned arrays: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- from stdlib: `Pair`, `Triple`, `Result`
- kotlin.random: `Random`
- kotlin.ranges: `CharRange`, `CharProgression`, `IntRange`, `IntProgression`, `LongRange`, `LongProgression`, `UintRange`, `UintProgression`, `ULongRange`, `ULongProgression`
- kotlin.text: `Regex`
- kotlin.time: `Duration`
- kotlin.uuid: `Uuid`

Additional support is added for the following classes in kotlin:

- Unsigned primitives: `UByte`, `UShort`, `UInt`, `ULong`
- Unsigned array types: `UByteArray`, `UShortArray`, `UIntArray`, `ULongArray`
- Empty collections: `emptyList`, `emptyMap`, `emptySet`
- Collections: `ArrayDeque`
- kotlin.time: `Duration`
- kotlin.uuid: `Uuid`

Additional Notes:

- wrappers classes created from `withDefault` method is currently not supported.

## Quick Start

```kotlin
import org.apache.fory.Fory
import org.apache.fory.ThreadSafeFory
import org.apache.fory.serializer.kotlin.KotlinSerializers

data class Person(val name: String, val id: Long, val github: String)
data class Point(val x : Int, val y : Int, val z : Int)

fun main(args: Array<String>) {
    // Note: following fory init code should be executed only once in a global scope instead
    // of initializing it everytime when serialization.
    val fory: ThreadSafeFory = Fory.builder().requireClassRegistration(true).buildThreadSafeFory()
    KotlinSerializers.registerSerializers(fory)
    fory.register(Person::class.java)
    fory.register(Point::class.java)

    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
}
```

## Default Value Support

Apache Fory™ Kotlin provides support for Kotlin data class default values during serialization and deserialization. This feature allows for backward and forward compatibility when data class schemas evolve.

### How It Works

When a Kotlin data class has parameters with default values, Apache Fory™ can:

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
    val newUser = fory.deserialize(serialized, UserV2::class.java)
    println(newUser) // UserV2(name=John, age=30, email=default@example.com)
}
```

### Supported Default Value Types

The following types are supported for default values:

- **Primitive types**: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `Char`
- **String**: `String`
- **Collections**: `List`, `Set`, `Map` (with default instances)
- **Custom objects**: Any object that can be instantiated via reflection

### Configuration

To enable default value support:

1. **Enable compatible mode** (recommended for schema evolution):

   ```kotlin
   val fory = Fory.builder()
       .withCompatibleMode(CompatibleMode.COMPATIBLE)
       .build()
   ```

2. **Register Kotlin serializers**:

   ```kotlin
   KotlinSerializers.registerSerializers(fory)
   ```

## Building Apache Fory™ Kotlin

```bash
mvn clean
mvn -T10 compile
```

## Code Format

```bash
mvn -T10 spotless:apply
```

## Testing

```bash
mvn -T10 test
```
