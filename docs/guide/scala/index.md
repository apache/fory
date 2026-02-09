---
title: Scala Serialization Guide
sidebar_position: 0
id: serialization_index
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

Apache Fory™ Scala provides optimized serializers for Scala types, built on top of Fory Java. It supports all Scala object serialization:

- `case` class serialization
- `pojo/bean` class serialization
- `object` singleton serialization
- `collection` serialization (Seq, List, Map, etc.)
- `tuple` and `either` types
- `Option` types
- Scala 2 and 3 enumerations

Both Scala 2 and Scala 3 are supported.

## Features

Fory Scala inherits all features from Fory Java, plus Scala-specific optimizations:

- **High Performance**: JIT code generation, zero-copy, 20-170x faster than traditional serialization
- **Scala Type Support**: Optimized serializers for case classes, singletons, collections, tuples, Option, Either
- **Default Value Support**: Automatic handling of Scala class default parameters during schema evolution
- **Singleton Preservation**: `object` singletons maintain referential equality after deserialization
- **Schema Evolution**: Forward/backward compatibility for class schema changes

See [Java Features](../java/index.md#features) for complete feature list.

## Installation

Add the dependency with sbt:

```sbt
libraryDependencies += "org.apache.fory" %% "fory-scala" % "0.15.0"
```

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

## Built on Fory Java

Fory Scala is built on top of Fory Java. Most configuration options, features, and concepts from Fory Java apply directly to Scala. Refer to the Java documentation for:

- [Configuration Options](../java/configuration.md) - All ForyBuilder options
- [Basic Serialization](../java/basic-serialization.md) - Serialization patterns and APIs
- [Type Registration](../java/type-registration.md) - Class registration and security
- [Schema Evolution](../java/schema-evolution.md) - Forward/backward compatibility
- [Custom Serializers](../java/custom-serializers.md) - Implement custom serializers
- [Compression](../java/compression.md) - Int, long, and string compression
- [Troubleshooting](../java/troubleshooting.md) - Common issues and solutions

## Scala-Specific Documentation

- [Fory Creation](fory-creation.md) - Scala-specific Fory setup requirements
- [Type Serialization](type-serialization.md) - Serializing Scala types
- [Default Values](default-values.md) - Scala class default values support
