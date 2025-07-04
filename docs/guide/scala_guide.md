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

Fory supports all scala object serialization:

- `case` class serialization supported
- `pojo/bean` class serialization supported
- `object` singleton serialization supported
- `collection` serialization supported
- other types such as `tuple/either` and basic types are all supported too.

Scala 2 and 3 are both supported.

## Install

To add a dependency on Fory scala for with sbt, use the following:

```sbt
libraryDependencies += "org.apache.fory" %% "fory-scala" % "0.11.1"
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
