---
title: Android
sidebar_position: 9
id: android
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

Fory JSON supports ordinary classes on Android API level 26 and later through the regular
`fory-json` artifact. Runtime JSON code generation and asynchronous compilation are disabled
automatically, so `ForyJson.builder().build()` uses the interpreted object mapper.

Kotlin applications use the ordinary `fory-json-kotlin` runtime. KSP is needed when R8 or ProGuard
can rename or remove Kotlin model members or when a Kotlin-source Mixin adds inferred
`JsonSubTypes` to a Java sealed target.

## Installation and Codec Model

Add Fory JSON to the application:

```kotlin
dependencies {
  implementation("org.apache.fory:fory-json:${foryVersion}")
}
```

For Kotlin, apply Kotlin 2.3.20 and add the Kotlin JSON runtime:

```kotlin
plugins {
  kotlin("android") version "2.3.20"
}

dependencies {
  implementation("org.apache.fory:fory-json-kotlin:${foryVersion}")
}
```

If the application enables R8 or ProGuard, or if a Kotlin-source Mixin adds inferred `JsonSubTypes`
to a Java sealed target, also apply KSP 2.3.8:

```kotlin
plugins {
  id("com.google.devtools.ksp") version "2.3.8"
}

dependencies {
  ksp("org.apache.fory:fory-json-kotlin-ksp:${foryVersion}")
}
```

Create the runtime with `ForyJsonKotlin.builder()`. For a minified build, annotate every required
Kotlin source model with `@JsonType`. For a third-party Kotlin target, declare a source-owned exact
`@JsonMixin` instead. If a Kotlin-source Mixin adds inferred `JsonSubTypes` to a Java sealed target,
also apply `fory-annotation-processor` and compile on JDK 17 or newer.

## Custom Codecs

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

## Java Generated Access and R8 Rules

Add the annotation processor and mark application object models with `JsonType` to generate direct
field, getter, setter, Record constructor, `JsonCreator`, and `JsonValidator` operations together
with exact R8 rules:

```kotlin
dependencies {
  annotationProcessor("org.apache.fory:fory-annotation-processor:${foryVersion}")
}
```

```java
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonValidator;

@JsonType
public final class Invoice {
  public long total;

  @JsonValidator
  public void validate() {
    if (total < 0) {
      throw new IllegalArgumentException("total must not be negative");
    }
  }
}
```

## Mixins

The same processor supports Fory JSON Mixins. A Mixin declares one exact target and is registered
on the `ForyJson` builder that should use it:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonMixin;

@JsonMixin(target = ThirdPartyInvoice.class)
public abstract class ThirdPartyInvoiceMixin {
  @JsonBase64 byte[] signature;
}

ForyJson json =
    ForyJson.builder().registerMixin(ThirdPartyInvoiceMixin.class).build();
```

Compile a non-empty Java-source Mixin for a Java target with `fory-annotation-processor`. The
registered Mixin remains scoped to its exact target. Registered codecs, effective type codecs, and
built-in mappings keep their normal codec-selection precedence.

A Mixin may place `JsonValidator` on a public abstract zero-argument `void` method that exactly
matches a public target method. The target does not need `JsonType` solely for a Mixin validator.

The target does not need `JsonType` merely because it has a Mixin. If the target also uses
`JsonType`, the registered Mixin still defines the effective annotations for that `ForyJson`
instance.

Only one source is enabled for an exact target in one built `ForyJson` instance. A later
registration for that target replaces an earlier registration on the builder, and `build()`
snapshots the selected mapping.

Use the Fory processors instead of broad package keep rules. A Java-only Mixin pair requires
`fory-annotation-processor`; a pair involving Kotlin requires `fory-json-kotlin-ksp`. A
Kotlin-source Mixin that adds inferred `JsonSubTypes` to a Java sealed target requires both and must
be compiled on JDK 17 or newer.

## Reflection-Based Models

Ordinary non-Record classes that omit `JsonType` can supply equivalent exact rules themselves unless
they declare `JsonValidator`. Direct validators require `JsonType` and
`fory-annotation-processor`; manual reflection rules are not sufficient. Retain every model
constructor, field, method, generic signature, declaration annotation, and parameter annotation
used by Fory JSON, plus the public no-argument constructor of every annotation-selected codec. For
a model without a validator:

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

This reflection-based section applies to Java models. Kotlin models use the Kotlin JSON module. For
a minified Android build, apply KSP instead of writing broad package keep rules.

Java `@JsonType` models support effective `JsonValidator`, `JsonValue`, `JsonRawValue`,
`JsonBase64`, and `JsonFormat` annotations. Without `@JsonType`, those annotations still work
through reflection, but a release-minified application must keep the exact annotated members,
annotation attributes, and codec constructor itself. A `JsonValue` method may use a non-JavaBean
name, so its manual rule must name that method explicitly.

Kotlin models use the same effective annotations. Enable KSP for minified builds. `JsonFormat` keeps
the same direct-field and one-wrapper-level behavior as on the JVM, including `timezone` for
`Instant`, `ZonedDateTime`, and `OffsetDateTime`.

Android supports inferred `JsonSubTypes` for Java and Kotlin sealed hierarchies. Java sealed
inference requires `fory-annotation-processor` and JDK 17 or newer. Kotlin models in minified builds
require `fory-json-kotlin-ksp`. For a Kotlin-source Mixin that adds inferred `JsonSubTypes` to a Java
sealed target, apply both processors even when shrinking is disabled. Only the sealed hierarchy is
considered; package and classpath subtype scanning are not supported.

Android Fory JSON requires a retained no-argument constructor for an ordinary mutable class; it may
be non-public when Android reflection can make it accessible. `JsonCreator` constructor-backed
classes follow the normal creator rules instead. Retain every field and method used for reflection,
or use an application codec when a model cannot satisfy those requirements. `JsonUnwrapped`
supports mutable classes, creator-backed classes, and Records through their normal property and
construction paths. Apply the normal `JsonType` requirements to the containing model and every
unwrapped child. In a minified Kotlin build, annotate each required model and enable KSP.

## Records

Android-desugared Records require either a direct `@JsonType` declaration or an exact `@JsonMixin`
processed by `fory-annotation-processor`. Manual R8 rules alone are insufficient because Android
does not provide the Java Record reflection APIs. This also applies to a Record whose complete
representation is a `JsonValue` String. Every Record in a `JsonUnwrapped` path needs its own direct
`JsonType` declaration or processed exact `JsonMixin`. Child codecs act on one level exactly as they
do on the JVM; use a complete value codec for deeper nested behavior.
