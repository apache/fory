---
title: Configuration
sidebar_position: 4
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

This page documents all configuration options available through `ForyBuilder`.

## ForyBuilder Options

| Option Name                         | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Default Value                                                        |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `timeRefIgnored`                    | Whether to ignore reference tracking of all time types registered in `TimeSerializers` and subclasses of those types when ref tracking is enabled. If ignored, ref tracking of every time type can be enabled by invoking `Fory#registerSerializer(Class, Serializer)`. For example, `fory.registerSerializer(Date.class, new DateSerializer(fory.getConfig(), true))`. Note that enabling ref tracking should happen before serializer codegen of any types which contain time fields. Otherwise, those fields will still skip ref tracking. | `true`                                                               |
| `compressInt`                       | Enables or disables int compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | `true`                                                               |
| `compressLong`                      | Enables or disables long compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `true`                                                               |
| `compressIntArray`                  | Enables or disables SIMD-accelerated compression for int arrays when values can fit in smaller data types. Requires Java 16+.                                                                                                                                                                                                                                                                                                                                                                                                                 | `false`                                                              |
| `compressLongArray`                 | Enables or disables SIMD-accelerated compression for long arrays when values can fit in smaller data types. Requires Java 16+.                                                                                                                                                                                                                                                                                                                                                                                                                | `false`                                                              |
| `compressString`                    | Enables or disables string compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `false`                                                              |
| `classLoader`                       | The classloader is fixed per `Fory` instance because Fory caches class metadata. To use a different loader, create a new `Fory` or `ThreadSafeFory` configured with that loader, or rely on the thread context classloader before first class resolution.                                                                                                                                                                                                                                                                                     | `Thread.currentThread().getContextClassLoader()`                     |
| `compatible`                        | Schema evolution mode. `true`: readers can tolerate compatible field additions, removals, and reordering. `false`: use only when every reader and writer always uses the same class schema and you want faster serialization and smaller size. When unset, compatible mode is enabled in both xlang and native mode. [See more](schema-evolution.md).                                                                                                                                                                                         | `true`                                                               |
| `checkClassVersion`                 | Checks the class-version hash for intentional same-schema payloads. Fory disables this check when compatible mode is enabled because compatible mode carries schema metadata for evolution.                                                                                                                                                                                                                                                                                                                                                   | `false`                                                              |
| `checkJdkClassSerializable`         | Enables or disables checking of `Serializable` interface for classes under `java.*`. If a class under `java.*` is not `Serializable`, Fory will throw an `UnsupportedOperationException`.                                                                                                                                                                                                                                                                                                                                                     | `true`                                                               |
| `registerGuavaTypes`                | Whether to pre-register Guava types such as `RegularImmutableMap`/`RegularImmutableList`. These types are not public API, but seem pretty stable.                                                                                                                                                                                                                                                                                                                                                                                             | `true`                                                               |
| `requireClassRegistration`          | Disabling may allow unknown classes to be deserialized, potentially causing security risks.                                                                                                                                                                                                                                                                                                                                                                                                                                                   | `true`                                                               |
| `maxDepth`                          | Set max depth for deserialization, when depth exceeds, an exception will be thrown. This can be used to refuse deserialization DDOS attack.                                                                                                                                                                                                                                                                                                                                                                                                   | `50`                                                                 |
| `maxTypeFields`                     | Maximum fields accepted in one received remote struct metadata body.                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `512`                                                                |
| `maxTypeMetaBytes`                  | Maximum encoded body bytes accepted for one received TypeDef or TypeMeta body, excluding the 8-byte header and any extended-size varint.                                                                                                                                                                                                                                                                                                                                                                                                      | `4096`                                                               |
| `maxSchemaVersionsPerType`          | Maximum accepted remote metadata versions for one logical type.                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `10`                                                                 |
| `maxAverageSchemaVersionsPerType`   | Average accepted remote metadata versions across all accepted remote types. The effective global floor is `8192` metadata entries.                                                                                                                                                                                                                                                                                                                                                                                                            | `3`                                                                  |
| `suppressClassRegistrationWarnings` | Whether to suppress class registration warnings. The warnings can be used for security audit, but may be annoying, this suppression will be enabled by default.                                                                                                                                                                                                                                                                                                                                                                               | `true`                                                               |
| `metaShareEnabled`                  | Enables or disables meta share mode.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `true` if compatible mode is enabled, otherwise false.               |
| `scopedMetaShareEnabled`            | Scoped meta share focuses on a single serialization process. Metadata created or identified during this process is exclusive to it and is not shared with by other serializations.                                                                                                                                                                                                                                                                                                                                                            | `true` if compatible mode is enabled, otherwise false.               |
| `metaCompressor`                    | Set a compressor for meta compression. Note that the passed MetaCompressor should be thread-safe. By default, a `Deflater` based compressor `DeflaterMetaCompressor` will be used. Users can pass other compressor such as `zstd` for better compression rate.                                                                                                                                                                                                                                                                                | `DeflaterMetaCompressor`                                             |
| `deserializeUnknownClass`           | Enables or disables deserialization/skipping of data for non-existent or unknown classes.                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `true` if compatible mode is enabled, otherwise false.               |
| `codeGenEnabled`                    | Disabling may result in faster initial serialization but slower subsequent serializations. When unset, codegen defaults to enabled on ordinary JVMs and disabled on Android and GraalVM native image. Explicit `withCodegen(true)` on Android or GraalVM native image is accepted, but final build configuration forces interpreter serializers and emits a warning. If a build-time `@ForyStruct` static serializer is available, ordinary JVM `withCodegen(false)` and Android use it instead of the interpreter object serializer.         | `true` on ordinary JVMs; `false` on Android and GraalVM native image |
| `asyncCompilationEnabled`           | If enabled, serialization uses interpreter mode first and switches to JIT serialization after async serializer JIT for a class is finished. This option is forced off on Android and GraalVM native image because runtime code generation is unavailable there.                                                                                                                                                                                                                                                                               | `false`                                                              |
| `copyRef`                           | When disabled, the copy performance will be better. But fory deep copy will ignore circular and shared reference. Same reference of an object graph will be copied into different objects in one `Fory#copy`.                                                                                                                                                                                                                                                                                                                                 | `false`                                                              |
| `serializeEnumByName`               | When enabled, Fory serializes enum names instead of numeric enum tags. Without this option, Fory writes declaration ordinals by default, or explicit stable ids when the enum is configured with `@ForyEnumId`.                                                                                                                                                                                                                                                                                                                               | `false`                                                              |

## Example Configuration

```java
Fory fory = Fory.builder()
  .withXlang(false)
  // enable reference tracking for shared/circular reference.
  // Disable it will have better performance if no duplicate reference.
  .withRefTracking(false)
  // compress int for smaller size
  .withIntCompressed(true)
  // compress long for smaller size
  .withLongCompressed(true)
  // Optional: use `compatible=false` only when
  // every reader and writer always uses the same class schema.
  // .withCompatible(false)
  // enable async multi-threaded compilation.
  .withAsyncCompilation(true)
  .build();
```

## Compatible Mode

Compatible mode is enabled by default for both xlang and native mode. Keep this default when classes
may evolve independently, when services deploy separately, or when xlang schemas are written by hand
in different languages.

Use `withCompatible(false)` only when the class schema used to deserialize every payload is always
the same as the class schema used to serialize it and you want faster serialization and smaller size.
For xlang payloads, call `withCompatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

## Security

Keep class registration enabled for production and any untrusted payload source:

```java
Fory fory = Fory.builder()
    .requireClassRegistration(true)
    .withMaxDepth(50)
    .build();
```

Security-related options:

- `requireClassRegistration(true)` restricts deserialization to registered classes.
- `withMaxDepth(...)` rejects unexpectedly deep object graphs.
- `withMaxTypeFields(...)` and `withMaxTypeMetaBytes(...)` bound the field count
  and encoded body size of one received remote metadata body.
- `withMaxSchemaVersionsPerType(...)` and
  `withMaxAverageSchemaVersionsPerType(...)` bound accepted remote metadata versions without
  changing registration, dynamic loading, or schema-evolution semantics.
- `withDeserializeUnknownClass(false)` avoids materializing unknown classes from metadata.
- `checkJdkClassSerializable(true)` keeps the JDK serializability check for `java.*` classes.
- Class registration warnings can be useful during security audits; use
  `suppressClassRegistrationWarnings(false)` when you need to surface unexpected types.

Use `requireClassRegistration(false)` only for trusted payloads, and pair it with a `TypeChecker`
allow list when dynamic class loading is required.

## Related Topics

- [Schema Metadata](schema-metadata.md) - `@ForyField`, `@Ignore`, integer encoding annotations, `serializeEnumByName`, and `@ForyEnumId`
- [Schema Evolution](schema-evolution.md) - Compatible mode and meta sharing
- [Compression](compression.md) - Int, long, and array compression details
- [Type Registration](type-registration.md) - Class registration options
- [Static Generated Serializers](static-generated-serializers.md) - Annotation-processor static generated serializers for `@ForyStruct`, `codegen=false`, and Android
