---
title: Security
sidebar_position: 11
id: security
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

Use Fory JSON with untrusted input only after defining which JVM types may be
materialized and which resource limits the endpoint will enforce. Fory JSON
does not derive arbitrary Java class names from JSON input, but annotations,
declared target types, and custom codecs still define an application-controlled
object surface.

Kotlin type tokens and metadata are trusted schema declarations, not input authority. The Kotlin
module validates the logical type, physical JVM carrier, and constructor/default operations before
parsing. JSON input cannot select a class, constructor, compiler default target, object, companion,
module, codec, or callable. A closed `JsonSubTypes` value selects only a logical name from the
application-declared finite table.

## Type Policy And Class Loading

Fory JSON always applies its fixed disallow list. Add an application allow-list
with `withTypeChecker` when only selected model packages should be mapped:

```java
ForyJson json =
    ForyJson.builder()
        .withTypeChecker(
            (className, context) ->
                className.startsWith("com.example.model.")
                    || className.equals("java.util.List")
                    || className.equals("java.util.Map"))
        .build();
```

Allow every application model and non-built-in container type used by the
declared schema. The checker runs while application types are prepared for
both serialization and parsing, so it must be thread-safe. Built-in scalar
types normally do not invoke the custom checker, but an application codec for
a built-in target makes that target subject to the checker. A custom codec
never bypasses the fixed disallow list.

`withClassLoader` sets the loader for annotation-declared subtype `className`
entries. Without it, `build()` snapshots the current thread context class
loader and falls back to the loader that defined `ForyJson`. Later changes to
the thread context class loader do not affect that `ForyJson` instance.

The following types are rejected by default because their natural JSON mapping
would be unsafe or ambiguous: `Class`, `URL`, `InetAddress`, and
`InetSocketAddress`. An application may support `URL` with an exact custom
codec that it owns. Arbitrary `Number` and `CharSequence` subclasses also need
an exact built-in or custom codec.

## Depth And Graph Memory Limits

`maxDepth` limits nested arrays and objects; its default is `20`, and configured
values must be positive. It is not an input-byte or memory quota.
`ForyJsonBuilder.withMaxGraphMemoryBytes` independently limits the
approximate retained graph created by each root read. The default is the fixed
`ForyJson.DEFAULT_MAX_GRAPH_MEMORY_BYTES` value of 128 MiB, and explicit values
must be positive. String and UTF-8 byte-array roots use the same configured
limit. Each root read starts with the complete budget, and neither success nor
failure reduces the next operation's budget. The limit is not derived from
input length.

Built-in accounting includes shallow POJO and record storage, collections and
sets plus candidate element-reference slots, maps plus candidate key/value
reference slots, reference arrays plus their slots, and primitive arrays plus
their primitive storage. Natural `JsonObject` and `JsonArray` values follow the
same map and collection rules. Unknown-length collection, map, and array
storage is reserved in 1024-item batches before each batch's final child and at
the tail. Repeated set elements and duplicate or overwritten map members are
therefore charged for every input occurrence. A reference array is charged
even when every element is a leaf, and an object is charged when all its
properties are leaves. `AtomicReference`, `AtomicReferenceArray`, and generic `Optional<T>` values
include wrapper and reference storage. An allocated primitive Optional or atomic primitive wrapper
is also charged once; a cached empty Optional singleton is not a new graph owner.

Dedicated leaf codecs are excluded from graph accounting: null, strings,
characters, booleans, numeric values including arbitrary-precision numbers,
enums, temporal and other scalar values, and binary values. A `byte[]` handled
by a binary or Base64 codec remains a binary leaf; the same Java carrier read
from a JSON numeric array is a primitive-array owner. Byte-availability and
grammar checks still apply independently of graph accounting.

A custom codec that materializes composite graph owners must call
`JsonReader.reserveGraphMemory` with an application-defined byte estimate for
each composite application object, collection, map, or reference array.
Unknown-length retained storage should be reserved in bounded batches before
each batch's final child and at the tail; a codec may reserve earlier. A custom
scalar or other dedicated leaf representation makes no reservation.

The graph budget is a portable approximation, not exact JVM heap accounting.
It cannot include application constructor or validator internals, temporary
parsing storage, custom-codec allocations that the codec does not reserve, or
unrelated process memory. Actual memory use can therefore exceed the configured
budget.

Kotlin does not add separate collection, input, or workspace limits. Arrays, collections, maps,
and ordinary objects use the same core depth and graph-memory accounting as Java and Scala.
Interpreted constructor argument arrays are fixed from trusted model metadata, not an
input-declared count, and are not retained in the decoded graph. Singleton and `Unit` reads return
existing instances. A boxed value-class result is charged once when that wrapper is materialized.

Compiler defaults, model constructors, validators, and application codecs are trusted application
code. Their internal allocation and side effects are not sandboxed or charged by the graph budget.
Their exceptions still fail the root operation and clear root parsing state, but a later trailing-
input failure cannot undo code that already ran. Validate side effects accordingly when decoding
untrusted input.

## External Controls And Verification

Fory JSON does not authenticate, authorize, encrypt, sign, or impose an HTTP
request-size limit. Combine its type, depth, and graph limits with transport
body limits, authentication, authorization, timeouts, and domain validation
appropriate to the endpoint.

Use negative tests to verify rejection of an unexpected target type, excessive
nesting, an oversized retained graph, and application-invalid values.
