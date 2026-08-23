---
title: Scala Setup
sidebar_position: 10
id: scala
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

Fory Scala provides binary Object Serialization, generated models, and Fory
gRPC. The Fory Scala artifact supports Scala 2.13 and Scala 3; generated Scala
models require Scala 3.

## Verify the Toolchain

```bash
java -version
scala -version
sbt --version
```

## Object Serialization

Add the Fory Scala library to `build.sbt`:

```sbt
ThisBuild / scalaVersion := "3.3.1"
libraryDependencies += "org.apache.fory" %% "fory-scala" % "1.7.0"
```

Create `src/main/scala/ScalaExample.scala`:

```scala
import org.apache.fory.Fory
import org.apache.fory.scala.ForyScala

case class User(id: Long, name: String)

object ScalaExample {
  def main(args: Array[String]): Unit = {
    val fory: Fory = ForyScala.builder()
      .withXlang(true)
      .build()
    fory.register(classOf[User], 1)

    val bytes = fory.serialize(User(1, "Alice"))
    val decoded = fory.deserialize(bytes).asInstanceOf[User]
    println(decoded.name)
  }
}
```

```bash
sbt run
```

Use xlang mode when a peer uses a different Fory implementation family; use native mode for data
within the JVM Fory implementation family. Continue with
[Scala Object Serialization](../object-serialization/scala/index.md),
[xlang](../object-serialization/scala/basic-serialization.md#cross-language-interoperability), or
[native mode](../object-serialization/scala/native.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Scala 3 models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Scala generated-code guide](../compiler/generated-code/scala.md).
- **Fory gRPC** uses grpc-java transports with Fory-encoded messages. See [Scala gRPC](../grpc/scala.md).
