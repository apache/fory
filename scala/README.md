# Apache Fory™ Scala

Apache Fory™ Scala provides optimized serializers for Scala types, built on top of Fory Java. It delivers **high-performance serialization** for the Scala ecosystem with full support for Scala-specific types and idioms.

Both Scala 2 and Scala 3 are supported.

## Features

### Supported Types

Apache Fory™ Scala is tested and works with the following types:

- **Case classes**: Full support for case class serialization with all field types
- **Regular classes**: POJO/bean style classes with constructor parameters
- **Object singletons**: Scala `object` singletons preserve identity after deserialization
- **Collections**: `Seq`, `List`, `Vector`, `Set`, `Map`, `ArrayBuffer`, and other Scala collections
- **Tuples**: All tuple types from `Tuple1` to `Tuple22`
- **Option types**: `Some` and `None`
- **Either types**: `Left` and `Right`
- **Enumerations**: Both Scala 2 `Enumeration` and Scala 3 `enum`

### Scala-Specific Features

- **Default Value Support**: Automatic handling of Scala class default parameters during schema evolution
- **Singleton Preservation**: `object` singletons maintain referential equality after deserialization
- **Scala Collection Optimization**: Optimized serializers for Scala's immutable and mutable collections

## Quick Start

```scala
import org.apache.fory.Fory
import org.apache.fory.serializer.scala.ScalaSerializers

case class Person(name: String, id: Long, github: String)
case class Point(x: Int, y: Int, z: Int)

object ScalaExample {
  // Create Fory with Scala optimization enabled
  val fory: Fory = Fory.builder()
    .withScalaOptimizationEnabled(true)
    .build()

  // Register optimized Fory serializers for Scala
  ScalaSerializers.registerSerializers(fory)

  // Register your classes
  fory.register(classOf[Person])
  fory.register(classOf[Point])

  def main(args: Array[String]): Unit = {
    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
  }
}
```

## Default Value Support

Apache Fory™ Scala provides support for Scala class default values during deserialization. This feature enables forward/backward compatibility when case classes or regular Scala classes have default parameters.

### How It Works

When a Scala class has default parameters, the Scala compiler generates methods in the companion object (for case classes) or in the class itself that return default values. Fory detects these methods and uses them when deserializing objects where certain fields are missing from the serialized data.

### Example Usage

```scala
import org.apache.fory.Fory
import org.apache.fory.config.CompatibleMode
import org.apache.fory.serializer.scala.ScalaSerializers

// Original case class
case class User(name: String, age: Int)

// Evolved case class with new fields and default values
case class UserV2(name: String, age: Int, email: String = "unknown", active: Boolean = true)

object DefaultValueExample {
  val fory: Fory = Fory.builder()
    .withScalaOptimizationEnabled(true)
    .withCompatibleMode(CompatibleMode.COMPATIBLE)
    .build()

  ScalaSerializers.registerSerializers(fory)

  def main(args: Array[String]): Unit = {
    // Serialize with old schema
    val oldUser = User("John", 30)
    val serialized = fory.serialize(oldUser)

    // Deserialize with new schema - missing fields get default values
    val newUser = fory.deserialize(serialized).asInstanceOf[UserV2]
    println(newUser) // UserV2(John, 30, unknown, true)
  }
}
```

### Supported Default Value Types

- **Primitive types**: `Int`, `Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `Char`
- **String**: `String`
- **Collections**: `List`, `Set`, `Map`, `Seq`, etc.
- **Option types**: `Option[T]`
- **Custom objects**: Any serializable object

## Thread-Safe Usage

For multi-threaded applications, use `ThreadSafeFory`:

```scala
import org.apache.fory.{Fory, ThreadSafeFory, ThreadLocalFory}
import org.apache.fory.serializer.scala.ScalaSerializers

object ForyHolder {
  val fory: ThreadSafeFory = new ThreadLocalFory(classLoader => {
    val f = Fory.builder()
      .withScalaOptimizationEnabled(true)
      .withClassLoader(classLoader)
      .build()
    ScalaSerializers.registerSerializers(f)
    f.register(classOf[Person])
    f
  })
}

// Use in multiple threads
val bytes = ForyHolder.fory.serialize(person)
val result = ForyHolder.fory.deserialize(bytes)
```

## Configuration

Fory Scala is built on Fory Java, so all Java configuration options are available:

```scala
import org.apache.fory.Fory
import org.apache.fory.config.CompatibleMode
import org.apache.fory.serializer.scala.ScalaSerializers

val fory = Fory.builder()
  // Required for Scala
  .withScalaOptimizationEnabled(true)
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

ScalaSerializers.registerSerializers(fory)
```

## Documentation

| Resource          | Link                                             |
| ----------------- | ------------------------------------------------ |
| **Website**       | https://fory.apache.org/docs/guide/scala         |
| **Source Docs**   | [docs/guide/scala](../docs/guide/scala/index.md) |
| **Java Guide**    | [docs/guide/java](../docs/guide/java/index.md)   |
| **API Reference** | [Fory Java API](../java/README.md)               |

## Installation

Add the dependency with sbt:

```sbt
libraryDependencies += "org.apache.fory" %% "fory-scala" % "0.15.0"
```

## Building

Fory Scala requires Fory Java to be installed first:

```bash
# Install Fory Java
cd ../java && mvn -T16 install -DskipTests

# Build Fory Scala
cd ../scala
sbt compile
```

## Testing

```bash
sbt test
```

## Code Format

```bash
sbt scalafmt
```

## Additional Notes

- **Fory Reuse**: Always reuse Fory instances; creation is expensive
- **Scala Internal Types**: You may need to register some Scala internal types like `scala.Enumeration.Val`

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## License

Licensed under the [Apache License 2.0](../LICENSE).
