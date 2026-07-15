---
title: Android Support
sidebar_position: 15
id: android_support
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

## Android Runtime

Fory Java supports Android 8.0+ (API level 26+) through the regular `fory-core` artifact. No separate
Android artifact is required for core object serialization.

Use core object serialization on Android:

- `Fory#serialize(Object)` and `Fory#deserialize(byte[])`.
- `BaseFory#deserialize(ByteBuffer)` for heap, direct, and read-only `ByteBuffer` inputs.
- Stream, channel, and out-of-band buffer APIs through byte-array, heap-buffer, or `ByteBuffer` copy
  paths.
- Java collections/maps and xlang collections/maps.

`java/fory-format` row-format APIs are JVM-only and are not supported on Android.

## Runtime Codegen

Runtime serializer code generation is disabled on Android. If `withCodegen(true)` is set, Fory keeps
Android serialization on the non-codegen path and logs a warning.

Android apps that need generated serializers should use build-time static generated serializers
instead.

## Fory JSON

Fory JSON supports ordinary classes on Android API level 26 and later through the regular
`fory-json` artifact. Runtime JSON code generation and asynchronous compilation are disabled
automatically, so `ForyJson.builder().build()` uses the interpreted object mapper.

Add Fory JSON to the application:

```kotlin
dependencies {
  implementation("org.apache.fory:fory-json:${foryVersion}")
}
```

`@JsonCodec` has the same declaration behavior on Android and the JVM. It supports complete values,
direct collection and array elements, `Optional` and `AtomicReference` contents, Map keys and
values, ordinary getters, setter value parameters, and `JsonCreator` parameters:

```java
import java.util.List;
import org.apache.fory.json.annotation.JsonCodec;

public final class Invoice {
  @JsonCodec(elementCodec = MoneyCodec.class)
  public List<Money> items;
  private Money primary;

  public void setPrimary(@JsonCodec(MoneyCodec.class) Money primary) {
    this.primary = primary;
  }

  public Invoice() {}
}
```

Child codecs act on one direct level only. For example, `elementCodec` on `Money[][]` handles each
`Money[]`, and `elementCodec` on `AtomicReferenceArray<Money>` handles each `Money`. Use a complete
`value` codec when deeper custom behavior is required.

Add the annotation processor and mark application object models with `JsonType` to generate direct
field, getter, setter, Record constructor, and `JsonCreator` operations together with exact R8
rules:

```kotlin
dependencies {
  annotationProcessor("org.apache.fory:fory-annotation-processor:${foryVersion}")
}
```

```java
import org.apache.fory.json.annotation.JsonType;

@JsonType
public final class Invoice {
  // ...
}
```

Ordinary non-Record classes that omit `JsonType` can supply equivalent exact rules themselves.
Retain every model
constructor, field, method, generic signature, declaration annotation, and parameter annotation used
by Fory JSON, plus the public no-argument constructor of every annotation-selected codec. For the
previous `Invoice` example:

```proguard
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault,MethodParameters,InnerClasses,EnclosingMethod
-keep,allowoptimization class com.example.Invoice {
  public <init>();
  public java.util.List items;
  public void setPrimary(com.example.Money);
}
-keep,allowoptimization,allowobfuscation class com.example.MoneyCodec {
  public <init>();
}
```

The same exact-rule approach supports every `JsonCodec` member; it is not limited to complete-value
codecs. `JsonType` is not required for codec selection on an ordinary class.

For `@JsonType` models, the generated R8 rules also retain `JsonValue` fields and effective methods,
fixed `JsonRawValue` and `JsonBase64` fields and getters, their runtime annotations, and the Base64
codec constructor. Without `@JsonType`, these annotations still work through reflection, but a
release-minified application must keep the exact annotated members, annotation attributes, and
codec constructor itself. A `JsonValue` method may use a non-JavaBean name, so its manual rule must
name that method explicitly.

Android Fory JSON requires a retained no-argument constructor for an ordinary mutable class; it may
be non-public when Android reflection can make it accessible. `JsonCreator` constructor-backed
classes follow the normal creator rules instead. Retain every field and method used for reflection,
or use an application codec when a model cannot satisfy those requirements. `JsonUnwrapped`
supports mutable classes, creator-backed classes, and Records through their normal property and
construction paths. When the containing model and its unwrapped children use `JsonType`, their
generated companions supply those operations.

Android-desugared Records require a direct `@JsonType` annotation and the annotation processor.
Manual R8 rules alone cannot reconstruct Record component order because Android does not provide
the Java Record reflection APIs. This also applies to a Record whose complete representation is a
`JsonValue` String: the generated companion identifies the propagated component accessor and calls
an annotated one-String canonical constructor directly. Generated child codecs act on one level
exactly as they do on the JVM. Every Record in a `JsonUnwrapped` path needs its own direct
`JsonType`. Use a complete value codec for deeper nested behavior.

## Static Generated Serializers

Use `@ForyStruct` static generated serializers for Android application classes. They are generated by
javac during the app build and work without runtime bytecode generation.

### Install The Annotation Processor

Add `fory-annotation-processor` to the annotation processor path of the module that compiles your
Android model classes:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path>
            <groupId>org.apache.fory</groupId>
            <artifactId>fory-annotation-processor</artifactId>
            <version>${fory.version}</version>
          </path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

Then annotate Android model classes with `@ForyStruct`.

Static generated serializers are required on Android when a serialized class uses Fory type-use
annotations, for example:

```java
import java.util.List;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.UInt8Type;

@ForyStruct
public class ImageBlock {
  public List<@UInt8Type Integer> pixels;
}
```

Without the generated static descriptors, Android reflection may not expose the nested type-use
metadata needed for annotations such as `@Ref`, `@Int8Type`, `@UInt8Type`, `@Float16Type`, or
`@BFloat16Type`. Serialization for those classes will not have the schema information Fory needs.

See [Static Generated Serializers](static-generated-serializers.md) for setup instructions.

## Object Model Requirements

Android serializers use public Android APIs. For application classes, prefer:

- accessible no-argument constructors, or records with supported constructors.
- public, protected, or package-private serialized fields.
- non-private getters and setters for private serialized fields.
- `@ForyStruct` static generated serializers for Android model classes.

Final fields in ordinary classes are not suitable for generated read/copy methods. Use records for
constructor-based immutable values.

## Unsupported Features

The following JVM features are not supported on Android:

- Runtime serializer code generation and async compilation.
- Lambda and `SerializedLambda` serialization.
- Native-address serialization APIs and native-address `MemoryBuffer` wrapping.
- Raw unsafe memory copy APIs.
- `java/fory-format` row-format APIs.

## ByteBuffer

`BaseFory#deserialize(ByteBuffer)` supports heap, direct, and read-only buffers on Android by copying
the remaining bytes into a Fory-owned heap buffer. The caller buffer position and limit are not
changed.

Raw direct-buffer address wrapping is a JVM-only fast path and is not used on Android.

## Collections, Maps, And Proxies

Common JDK collection and map implementations are supported on Android. In xlang mode, collection and
map serialization uses the xlang protocol and does not encode Java wrapper/view internals.

`java.lang.reflect.Proxy` serialization is supported for normal proxy usage. Do not invoke, log, or
use a proxy as a map/set key while it is still being deserialized; the invocation handler may not be
ready yet.
