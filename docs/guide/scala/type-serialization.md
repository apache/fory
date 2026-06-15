---
title: Type Serialization
sidebar_position: 2
id: type_serialization
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

This page covers serialization of Scala-specific JVM types in native mode. For
cross-language Scala models, use the xlang path described in
[Schema IDL And Xlang](schema-idl.md).

When compatible mode is enabled, Scala readers use the JVM compatible-read rules for selected
scalar field type changes. A matched field can read between `Boolean`, `String`, numeric scalars,
and `java.math.BigDecimal` when the converted value has the same logical value. For example,
`"true"` and `"false"` can be read as booleans, `"123"` can be read as a numeric field that can hold
`123`, numbers and decimals can be read as canonical strings, and numeric widening or narrowing
succeeds only when no precision or range is lost. Numeric strings use finite ASCII decimal syntax.
Invalid strings and lossy conversions fail during deserialization. Optional and boxed fields still
compose with these conversions, but reference-tracked scalar type changes are incompatible.

## Setup

All examples assume the following setup:

```scala
import org.apache.fory.Fory
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder().withXlang(false)
  .build()
```

## Case Class

```scala
case class Person(github: String, age: Int, id: Long)

fory.register(classOf[Person])

val p = Person("https://github.com/chaokunyang", 18, 1)
println(fory.deserialize(fory.serialize(p)))
```

## POJO Class

```scala
class Foo(f1: Int, f2: String) {
  override def toString: String = s"Foo($f1, $f2)"
}

fory.register(classOf[Foo])

println(fory.deserialize(fory.serialize(new Foo(1, "chaokunyang"))))
```

## Object Singleton

Scala `object` singletons are serialized and deserialized to the same instance:

```scala
object MySingleton {
  val value = 42
}

fory.register(MySingleton.getClass)

val o1 = fory.deserialize(fory.serialize(MySingleton))
val o2 = fory.deserialize(fory.serialize(MySingleton))
println(o1 == o2) // true
```

## Collection

Scala collections are fully supported:

```scala
val seq = Seq(1, 2)
val list = List("a", "b")
val map = Map("a" -> 1, "b" -> 2)

println(fory.deserialize(fory.serialize(seq)))
println(fory.deserialize(fory.serialize(list)))
println(fory.deserialize(fory.serialize(map)))
```

## Tuple

All Scala tuple types (Tuple1 through Tuple22) are supported:

```scala
val tuple2 = (100, 10000L)
println(fory.deserialize(fory.serialize(tuple2)))

val tuple4 = (100, 10000L, 10000L, "str")
println(fory.deserialize(fory.serialize(tuple4)))
```

## Enum

### Scala 3 Enum

```scala
enum Color { case Red, Green, Blue }

fory.register(classOf[Color])

println(fory.deserialize(fory.serialize(Color.Green)))
```

### Scala 2 Enumeration

```scala
object ColorEnum extends Enumeration {
  type ColorEnum = Value
  val Red, Green, Blue = Value
}

fory.register(Class.forName("scala.Enumeration.Val"))

println(fory.deserialize(fory.serialize(ColorEnum.Green)))
```

> **Note**: For Scala 2 Enumeration, you may need to register `scala.Enumeration.Val` or enable reference tracking to avoid `StackOverflowError`.

## Option

```scala
val some: Option[Long] = Some(100)
println(fory.deserialize(fory.serialize(some)))

val none: Option[Long] = None
println(fory.deserialize(fory.serialize(none)))
```

## Either

```scala
val right: Either[String, Int] = Right(42)
println(fory.deserialize(fory.serialize(right)))

val left: Either[String, Int] = Left("error")
println(fory.deserialize(fory.serialize(left)))
```

## Nested Types

Complex nested structures are fully supported:

```scala
case class Address(street: String, city: String)
case class Company(name: String, address: Address)
case class Employee(name: String, company: Company, tags: List[String])

fory.register(classOf[Address])
fory.register(classOf[Company])
fory.register(classOf[Employee])

val employee = Employee(
  "John",
  Company("Acme", Address("123 Main St", "Springfield")),
  List("developer", "scala")
)

println(fory.deserialize(fory.serialize(employee)))
```
