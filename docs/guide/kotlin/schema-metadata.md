---
title: Schema Metadata
sidebar_position: 3
id: schema_metadata
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

Kotlin schema metadata is used by the KSP-generated xlang serializers. Reuse the Java Fory
annotations for schema concepts, and use Kotlin type-use annotations only when you need Kotlin
specific integer encoding metadata.

## Struct Fields

Annotate Kotlin schema classes with `@ForyStruct` and constructor properties with
`@ForyField(id = N)`:

```kotlin
import org.apache.fory.annotation.ForyField
import org.apache.fory.annotation.ForyStruct
import org.apache.fory.kotlin.Fixed
import org.apache.fory.kotlin.VarInt

@ForyStruct
data class User(
  @ForyField(id = 1)
  val id: @Fixed UInt,

  @ForyField(id = 2)
  val score: @VarInt Long,

  @ForyField(id = 3)
  val tags: List<String>,
)
```

Use `@ForyField(id = 1)` on constructor properties. `@field:ForyField(id = 1)` is also accepted
for field-backed properties. Do not use `@get:ForyField` or `@set:ForyField`; accessors are not
schema fields and the processor rejects them.

## Nullability

Use Kotlin `?` to describe nullable schema positions. Nullability is preserved inside collections
and maps:

```kotlin
@ForyStruct
data class NullabilityExample(
  @ForyField(id = 1)
  val names: List<String>,

  @ForyField(id = 2)
  val optionalNames: List<String?>,

  @ForyField(id = 3)
  val nullableList: List<String>?,
)
```

Do not use Fory `@Nullable` in hand-written constructor-based Kotlin structs. The KSP processor
reads nullability from Kotlin source and rejects conflicting nullable annotations.

## Reference Tracking

Kotlin generated serializers preserve `@Ref` metadata for fields, list elements, and map values:

```kotlin
import org.apache.fory.annotation.Ref

@ForyStruct
data class Node(
  @ForyField(id = 1)
  val children: List<@Ref Node>,

  @ForyField(id = 2)
  @Ref
  val parent: Node?,
)
```

Global reference tracking still comes from Fory configuration. See
[Configuration](configuration.md).

## Integer Encoding

Kotlin type-use encoding annotations map to Fory xlang integer encodings:

| Annotation | Valid Kotlin types             |
| ---------- | ------------------------------ |
| `@Fixed`   | `Int`, `Long`, `UInt`, `ULong` |
| `@VarInt`  | `Int`, `Long`, `UInt`, `ULong` |
| `@Tagged`  | `Long`, `ULong`                |

Without an annotation, xlang `Int`, `Long`, `UInt`, and `ULong` use varint encoding.

## Collections And Dense Arrays

Collection declarations carry schema shape, not JVM implementation identity. `List<String>` is
encoded as `list<string>` and `Map<String, Int>` is encoded as `map<string, int32>`.

Dense primitive and unsigned array fields are supported, including `BooleanArray`, `ByteArray`,
`IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, `UByteArray`, `UShortArray`, `UIntArray`, and
`ULongArray`. `ByteArray` is encoded as Fory `binary` unless the type use is annotated with Java
`@ArrayType`.

## Related Topics

- [Static Generated Serializers](static-generated-serializers.md)
- [Configuration](configuration.md)
- [Default Values](default-values.md)
- [Android Support](android-support.md)
