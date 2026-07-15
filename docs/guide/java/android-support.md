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

Add Fory JSON to the application and put the annotation processor on the module's processor path:

```kotlin
dependencies {
  implementation("org.apache.fory:fory-json:${foryVersion}")
  annotationProcessor("org.apache.fory:fory-annotation-processor:${foryVersion}")
}
```

`@JsonType` is optional. Add it to application models when the processor should emit exact R8 rules
and metadata for `@JsonCodec` type uses, including qualified roots, parameter types, generic
arguments, and array components:

```java
import java.util.List;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonType;

@JsonType
public final class GeneratedInvoice {
  public List<@JsonCodec(MoneyCodec.class) Money> items;

  public GeneratedInvoice() {}
}
```

Without `@JsonType`, ordinary reflection mapping and `@JsonCodec` declarations on types, fields, and
effective ordinary getters still work. A release-minified application must supply equivalent exact
R8 rules for every reflected class, constructor, field, method, generic signature, and runtime
annotation. For example:

```java
public final class ManualInvoice {
  @JsonCodec(MoneyCodec.class)
  public Money total;

  public ManualInvoice() {}
}
```

```proguard
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,AnnotationDefault,MethodParameters
-keep,allowoptimization class com.example.ManualInvoice {
  public <init>();
  public com.example.Money total;
}
-keep,allowoptimization,allowobfuscation class com.example.MoneyCodec {
  public <init>();
}
```

Application-authored rules cannot supply codec type-use metadata that Android reflection does not
expose. For example, this nested annotation is not applied on Android when the class omits
`@JsonType`, even if exact rules retain the class, field, generic signature, and type annotation:

```java
public final class UnsupportedNestedInvoice {
  public List<@JsonCodec(MoneyCodec.class) Money> items;

  public UnsupportedNestedInvoice() {}
}
```

```proguard
-keepattributes Signature,RuntimeVisibleTypeAnnotations
-keep,allowoptimization class com.example.UnsupportedNestedInvoice {
  public <init>();
  public java.util.List items;
}
```

On Android, a pure type-use `@JsonCodec` requires `@JsonType`. This includes a qualified root type
use, a parameter type, a generic argument, and an array component. Type declarations, field
declarations, and effective ordinary getter declarations remain available through direct
reflection without `@JsonType`.

On the JVM and in a native image, a field or effective ordinary getter declaration takes precedence
over a codec on the root annotated type. Android reads the declaration directly and obtains pure
type-use facts from the generated companion. A setter, creator factory, unrelated method, or void
method cannot declare `@JsonCodec`. `JsonAnyProperty` and `JsonAnyGetter` flatten their Map rather
than exposing a complete root value, so their root declaration and root type-use codec forms are
invalid; annotate the Map value type when a nested codec is needed.

Android Fory JSON requires a retained no-argument constructor for an ordinary mutable class; it may
be non-public when Android reflection can make it accessible. `JsonCreator` constructor-backed
classes follow the normal creator rules instead. Retain every field and method used for reflection,
or use an application codec when a model cannot satisfy those requirements. Fory JSON Record
mapping is not supported on Android.

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
- Fory JSON Record mapping.

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
