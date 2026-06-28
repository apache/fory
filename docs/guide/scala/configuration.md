---
title: Configuration
sidebar_position: 1
id: configuration
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

This page covers Scala-specific Fory instance configuration and creation.

## Xlang Setup

Fory Scala follows the Java builder default: xlang mode with compatible schema
evolution. Use this path for cross-language Scala payloads, schema IDL generated
Scala models, and macro-derived xlang serializers.

```scala
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder()
  .withXlang(true)
  .build()
```

Register application classes before serialization:

```scala
fory.register(classOf[Person])
fory.register(classOf[Point])
```

## Native Mode Setup

For same-language Scala/JVM payloads that need native JVM object behavior, you
must:

1. Create the Fory instance with `ForyScala.builder().withXlang(false)`, or install
   `ForyScala` with `Fory.builder().withXlang(false).withModule(ForyScala)`.
2. Register application classes before serialization.

```scala
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder().withXlang(false)
  .build()
```

### Registering Scala Internal Types

Depending on the object types you serialize, you may need to register some Scala internal types:

```scala
fory.register(Class.forName("scala.Enumeration.Val"))
```

To avoid such registration, you can disable class registration:

```scala
val fory = ForyScala.builder().withXlang(false)
  .requireClassRegistration(false)
  .build()
```

> **Note**: Disabling class registration allows deserialization of unknown types. This is more flexible but may be insecure if the classes contain malicious code.

### Reference Tracking

Circular references are common in Scala. Reference tracking should be enabled with `withRefTracking(true)`:

```scala
val fory = ForyScala.builder()
  .withRefTracking(true)
  .build()
```

> **Note**: If you don't enable reference tracking, [StackOverflowError](https://github.com/apache/fory/issues/1032) may occur for some Scala versions when serializing Scala Enumeration.

## Thread Safety

Fory instance creation is not cheap. Instances should be shared between multiple serializations.

### Single-Thread Usage

```scala
import org.apache.fory.Fory
import org.apache.fory.scala.ForyScala

object ForyHolder {
  val fory: Fory = ForyScala.builder()
    .withXlang(true)
    .build()
}
```

### Multi-Thread Usage

For multi-threaded applications, use `ThreadSafeFory`:

```scala
import org.apache.fory.ThreadSafeFory
import org.apache.fory.scala.ForyScala

object ForyHolder {
  val fory: ThreadSafeFory = ForyScala.builder()
    .withXlang(true)
    .buildThreadSafeFory()
}
```

## Configuration

All configuration options from Fory Java are available. See [Java Configuration](../java/configuration.md) for the complete list.

Common options for Scala native-mode payloads:

```scala
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder().withXlang(false)
  // Enable reference tracking for circular references
  .withRefTracking(true)
  // Same-schema optimization. Use only when every reader and writer
  // always uses the same Scala/JVM schema.
  .withCompatible(false)
  // Enable async compilation for better startup performance
  .withAsyncCompilation(true)
  .build()
```

## Xlang Mode

For Scala xlang or schema IDL generated code, use the default xlang mode and
register the generated schema module:

```scala
import org.apache.fory.scala.ForyScala
import example.ExampleForyModule

val fory = ForyScala.builder()
  .withXlang(true)
  .withRefTracking(true)
  .withModule(ExampleForyModule)
  .build()
```

In xlang mode, Scala collections use canonical `list`, `set`, and `map`
payloads instead of Scala factory payloads. Generated optional fields use
`Option[T]`.

## Compatible Mode

Compatible mode is enabled by default through the Java builder in both xlang and native mode. Keep
this default when models may evolve independently, when services deploy separately, or when xlang
schemas are written by hand in different languages.

Use `withCompatible(false)` only when the class schema used to deserialize every payload is always
the same as the class schema used to serialize it and you want faster serialization and smaller size.
For xlang payloads, call `withCompatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

## Security

Scala uses the Java configuration surface. Keep class registration enabled for production
and any untrusted payload source:

```scala
val fory = ForyScala.builder()
  .requireClassRegistration(true)
  .withMaxDepth(50)
  .withMaxTypeFields(512)
  .withMaxTypeMetaBytes(4096)
  .build()
```

Security-related configuration:

- Keep `requireClassRegistration(true)` and register application classes or generated modules.
- Use `withMaxDepth(...)` to reject unexpectedly deep object graphs.
- Keep `withMaxTypeFields(...)`, `withMaxTypeMetaBytes(...)`, and the remote schema-version limits
  at their defaults unless the data is not malicious and a trusted peer sends larger metadata or
  many schema versions.
- Follow [Java Configuration](../java/configuration.md#security) for allow-listing and unknown-class
  controls.
