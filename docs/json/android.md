---
title: Android
sidebar_position: 6
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

## Installation and Runtime Model

Add Fory JSON to the application:

```kotlin
dependencies {
  implementation("org.apache.fory:fory-json:${foryVersion}")
}
```

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

## Generated Access and R8 Rules

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
on the runtime that should use it:

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

Compile every non-empty Mixin source with `fory-annotation-processor`. The processor emits exact
R8 rules and any pair-specific target operations that the runtime can use. Registered codecs,
effective type codecs, and built-in mappings keep their normal runtime precedence. An empty Mixin
produces no generated output.

A Mixin may place `JsonValidator` on a public abstract zero-argument `void` method that exactly
matches a public target method. The generated pair calls that target method directly. The target
does not need `JsonType` solely for a Mixin validator.

The target does not need `JsonType` merely because it has a Mixin. `JsonMixin` is itself the
processor entry point for the pair. If a target also uses `JsonType`, the runtime selects the
pair-specific companion for a non-empty registered Mixin instead of combining the overlay with the
target's direct companion.

Only one source is enabled for an exact target in one built runtime. A later registration for that
target replaces an earlier registration on the builder, and `build()` snapshots the selected
mapping. The processor may generate artifacts for multiple source alternatives; the runtime uses
only the last registered source.

Use the processor-generated R8 rules for non-empty Mixins instead of broad package keep rules.

## Reflection-Based Models

Ordinary non-Record classes that omit `JsonType` can supply equivalent exact rules themselves unless
they declare `JsonValidator`. Direct validators require the processor-generated calls from
`JsonType`; do not replace them with reflection rules. Retain every model constructor, field,
method, generic signature, declaration annotation, and parameter annotation used by Fory JSON,
plus the public no-argument constructor of every annotation-selected codec. For a model without a
validator:

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

For `@JsonType` models, generated operations and R8 rules also cover effective `JsonValidator`
methods, `JsonValue` fields and effective methods, fixed `JsonRawValue` and `JsonBase64` fields and
getters, `JsonFormat` date/time fields, their runtime annotations, and the Base64 codec constructor.
Without `@JsonType`, the value, raw, Base64, format, and codec annotations still work through
reflection, but a release-minified application must keep the exact annotated members, annotation
attributes, and codec constructor itself. A `JsonValue` method may use a non-JavaBean name, so its
manual rule must name that method explicitly. `JsonFormat` keeps the same direct-field and
one-wrapper-level behavior as on the JVM, including `timezone` for `Instant`, `ZonedDateTime`, and
`OffsetDateTime`.

Android Fory JSON requires a retained no-argument constructor for an ordinary mutable class; it may
be non-public when Android reflection can make it accessible. `JsonCreator` constructor-backed
classes follow the normal creator rules instead. Retain every field and method used for reflection,
or use an application codec when a model cannot satisfy those requirements. `JsonUnwrapped`
supports mutable classes, creator-backed classes, and Records through their normal property and
construction paths. When the containing model and its unwrapped children use `JsonType`, their
generated companions supply those operations.

## Records

Android-desugared Records require processor-generated operations from either a direct `@JsonType`
declaration or a compiled exact `@JsonMixin` pair. Manual R8 rules alone cannot reconstruct Record
component order because Android does not provide the Java Record reflection APIs. This also applies
to a Record whose complete representation is a `JsonValue` String: the generated companion
identifies the propagated component accessor and calls an annotated one-String canonical
constructor directly. Generated child codecs act on one level exactly as they do on the JVM. Every
Record in a `JsonUnwrapped` path needs its own direct `JsonType` declaration or compiled exact
`JsonMixin` pair. Use a complete value codec for deeper nested behavior.
