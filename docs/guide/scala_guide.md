---
title: Scala Serialization Guide
sidebar_position: 4
id: scala_guide
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

Apache Fory™ supports all scala object serialization:

- `case` class serialization supported
- `pojo/bean` class serialization supported
- `object` singleton serialization supported
- `collection` serialization supported
- other types such as `tuple/either` and basic types are all supported too.

Scala 2 and 3 are both supported.

## Install

To add a dependency on Apache Fory™ scala for with sbt, use the following:

```sbt
libraryDependencies += "org.apache.fory" %% "fory-scala" % "0.12.2"
```

## Quick Start

```scala
case class Person(name: String, id: Long, github: String)
case class Point(x : Int, y : Int, z : Int)

object ScalaExample {
  val fory: Fory = Fory.builder().withScalaOptimizationEnabled(true).build()
  // Register optimized fory serializers for scala
  ScalaSerializers.registerSerializers(fory)
  fory.register(classOf[Person])
  fory.register(classOf[Point])

  def main(args: Array[String]): Unit = {
    val p = Person("Shawn Yang", 1, "https://github.com/chaokunyang")
    println(fory.deserialize(fory.serialize(p)))
    println(fory.deserialize(fory.serialize(Point(1, 2, 3))))
  }
}
```

## Fory creation

When using fory for scala serialization, you should create fory at least with following options:

```scala
import org.apache.fory.Fory
import org.apache.fory.serializer.scala.ScalaSerializers

val fory = Fory.builder().withScalaOptimizationEnabled(true).build()

// Register optimized fory serializers for scala
ScalaSerializers.registerSerializers(fory)
```

Depending on the object types you serialize, you may need to register some scala internal types:

```scala
fory.register(Class.forName("scala.Enumeration.Val"))
```

If you want to avoid such registration, you can disable class registration by `ForyBuilder#requireClassRegistration(false)`.
Note that this option allow to deserialize objects unknown types, more flexible but may be insecure if the classes contains malicious code.

And circular references are common in scala, `Reference tracking` should be enabled by `ForyBuilder#withRefTracking(true)`. If you don't enable reference tracking, [StackOverflowError](https://github.com/apache/fory/issues/1032) may happen for some scala versions when serializing scala Enumeration.

Note that fory instance should be shared between multiple serialization, the creation of fory instance is not cheap.

If you use shared fory instance across multiple threads, you should create `ThreadSafeFory` instead by `ForyBuilder#buildThreadSafeFory()` instead.

## Serialize case class

```scala
case class Person(github: String, age: Int, id: Long)
val p = Person("https://github.com/chaokunyang", 18, 1)
println(fory.deserialize(fory.serialize(p)))
println(fory.deserializeJavaObject(fory.serializeJavaObject(p)))
```

## Serialize pojo

```scala
class Foo(f1: Int, f2: String) {
  override def toString: String = s"Foo($f1, $f2)"
}
println(fory.deserialize(fory.serialize(Foo(1, "chaokunyang"))))
```

## Serialize object singleton

```scala
object singleton {
}
val o1 = fory.deserialize(fory.serialize(singleton))
val o2 = fory.deserialize(fory.serialize(singleton))
println(o1 == o2)
```

## Serialize collection

```scala
val seq = Seq(1,2)
val list = List("a", "b")
val map = Map("a" -> 1, "b" -> 2)
println(fory.deserialize(fory.serialize(seq)))
println(fory.deserialize(fory.serialize(list)))
println(fory.deserialize(fory.serialize(map)))
```

## Serialize Tuple

```scala
val tuple = (100, 10000L) //Tuple2
println(fory.deserialize(fory.serialize(tuple)))
val tuple = (100, 10000L, 10000L, "str") //Tuple4
println(fory.deserialize(fory.serialize(tuple)))
```

## Serialize Enum

### Scala3 Enum

```scala
enum Color { case Red, Green, Blue }
println(fory.deserialize(fory.serialize(Color.Green)))
```

### Scala2 Enum

```scala
object ColorEnum extends Enumeration {
  type ColorEnum = Value
  val Red, Green, Blue = Value
}
println(fory.deserialize(fory.serialize(ColorEnum.Green)))
```

## Serialize Option

```scala
val opt: Option[Long] = Some(100)
println(fory.deserialize(fory.serialize(opt)))
val opt1: Option[Long] = None
println(fory.deserialize(fory.serialize(opt1)))
```

## Scala Class Default Values Support

Fory supports Scala class default values during deserialization when using compatible mode. This feature enables forward/backward compatibility when case classes or regular Scala classes have default parameters.

### Overview

When a Scala class has default parameters, the Scala compiler generates methods in the companion object (for case classes) or in the class itself (for regular Scala classes) like `apply$default$1`, `apply$default$2`, etc. that return the default values. Fory can detect these methods and use them when deserializing objects where certain fields are missing from the serialized data.

### Supported Class Types

Fory supports default values for:

- **Case classes** with default parameters
- **Regular Scala classes** with default parameters in their primary constructor
- **Nested case classes** with default parameters
- **Deeply nested case classes** with default parameters

### How It Works

1. **Detection**: Fory detects if a class is a Scala class by checking for the presence of default value methods (`apply$default$N` or `$default$N`).

2. **Default Value Discovery**:
   - For case classes: Fory scans the companion object for methods named `apply$default$1`, `apply$default$2`, etc.
   - For regular Scala classes: Fory scans the class itself for methods named `$default$1`, `$default$2`, etc.

3. **Field Mapping**: During deserialization, Fory identifies fields that exist in the target class but are missing from the serialized data.

4. **Value Application**: After reading all available fields from the serialized data, Fory applies default values to any missing fields using direct field access for optimal performance.

### Usage

This feature is automatically enabled when:

- Compatible mode is enabled (`withCompatibleMode(CompatibleMode.COMPATIBLE)`)
- The target class is detected as a Scala class with default values
- A field is missing from the serialized data but exists in the target class

No additional configuration is required.

### Examples

#### Case Class with Default Values

```scala
// Class WITHOUT default values (for serialization)
case class PersonNoDefaults(name: String)

// Class WITH default values (for deserialization)
case class PersonWithDefaults(name: String, age: Int = 25, city: String = "Unknown")

val fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .withScalaOptimizationEnabled(true)
  .build()

// Serialize using class without default values
val original = PersonNoDefaults("John")
val serialized = fory.serialize(original)

// Deserialize into class with default values - missing fields will use defaults
val deserialized = fory.deserialize(serialized, classOf[PersonWithDefaults])
// deserialized.name will be "John"
// deserialized.age will be 25 (default)
// deserialized.city will be "Unknown" (default)
```

#### Regular Scala Class with Default Values

```scala
// Class WITHOUT default values (for serialization)
class EmployeeNoDefaults(val name: String)

// Class WITH default values (for deserialization)
class EmployeeWithDefaults(val name: String, val age: Int = 30, val department: String = "Engineering")

val fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .withScalaOptimizationEnabled(true)
  .build()

// Serialize using class without default values
val original = new EmployeeNoDefaults("Jane")
val serialized = fory.serialize(original)

// Deserialize into class with default values - missing fields will use defaults
val deserialized = fory.deserialize(serialized, classOf[EmployeeWithDefaults])
// deserialized.name will be "Jane"
// deserialized.age will be 30 (default)
// deserialized.department will be "Engineering" (default)
```

#### Complex Default Values

```scala
// Class WITHOUT default values (for serialization)
case class ConfigurationNoDefaults(name: String)

// Class WITH default values (for deserialization)
case class ConfigurationWithDefaults(
  name: String,
  settings: Map[String, String] = Map("default" -> "value"),
  tags: List[String] = List("default"),
  enabled: Boolean = true
)

val fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .withScalaOptimizationEnabled(true)
  .build()

// Serialize using class without default values
val original = ConfigurationNoDefaults("myConfig")
val serialized = fory.serialize(original)

// Deserialize into class with default values - missing fields will use defaults
val deserialized = fory.deserialize(serialized, classOf[ConfigurationWithDefaults])
// deserialized.name will be "myConfig"
// deserialized.settings will be Map("default" -> "value")
// deserialized.tags will be List("default")
// deserialized.enabled will be true
```

#### Nested Case Classes

```scala
object NestedClasses {
  // Class WITHOUT default values (for serialization)
  case class SimplePerson(name: String)

  // Class WITH default values (for deserialization)
  case class Address(street: String, city: String = "DefaultCity")
  case class PersonWithDefaults(name: String, address: Address = Address("DefaultStreet"))
}

val fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .withScalaOptimizationEnabled(true)
  .build()

// Serialize using class without default values
val original = NestedClasses.SimplePerson("Alice")
val serialized = fory.serialize(original)

// Deserialize into class with default values - missing address field will use default
val deserialized = fory.deserialize(serialized, classOf[NestedClasses.PersonWithDefaults])
// deserialized.name will be "Alice"
// deserialized.address will be Address("DefaultStreet", "DefaultCity")
```
