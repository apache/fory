---
title: GraalVM Native Image
sidebar_position: 7
id: graalvm
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

## Reachable Models

Fory JSON has its own Native Image Feature and does not use the Fory annotation processor. Add
`@JsonType` to each reachable concrete object model that the native executable reads or writes:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonValidator;

@JsonType
public final class User {
  public long id;
  public String name;

  @JsonValidator
  public void validate() {
    if (id < 0) {
      throw new IllegalArgumentException("id must not be negative");
    }
  }
}

public class JsonExample {
  public static void main(String[] args) {
    ForyJson json = ForyJson.builder().build();
    User user = json.fromJson("{\"id\":1,\"name\":\"Ada\"}", User.class);
    System.out.println(json.toJson(user));
  }
}
```

This is sufficient for correct native execution. During image construction, Fory JSON retains the
model metadata and prepares its field, property, creator, record, and `JsonAnySetter` access. At
runtime, `ForyJson.builder().build()` can therefore use interpreted codecs without application
reflection configuration, package exports or opens, or build-time initialization.

## Generated Codecs

To include generated codecs for a configuration, return that completed configuration from a
reachable `@ForyJsonProvider`:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.ForyJsonProvider;

@ForyJsonProvider
public final class JsonConfigs {
  private final ForyJson api =
      ForyJson.builder()
          .writeNullFields(true)
          .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
          .registerCodec(Money.class, new MoneyCodec())
          .build();

  public JsonConfigs() {}

  public ForyJson api() {
    return api;
  }
}
```

The provider class must be public and concrete and have a public no-argument constructor. Provider
members are public, non-static, zero-argument instance methods whose exact return type is
`ForyJson`. Inherited superclass methods and public interface default methods are included. A
provider may return multiple configurations, and multiple providers may be reachable. Equivalent
configurations are generated once.

Provider objects exist only while the image is built. Prefer a dedicated configuration class with
instance fields and methods as shown above; no application `native-image.properties` entry is
needed, and the provider package does not need to be exported or opened to Fory. Static provider
methods and fields are not supported.

Only configurations returned by a provider receive generated codecs. The default configuration is
not generated implicitly. If a codegen-enabled `ForyJson` configuration was not included, Fory JSON
uses its prepared interpreted codecs and logs one process-wide warning recommending a reachable
`@ForyJsonProvider`. `withCodegen(false)` explicitly selects interpreted codecs and does not request
generated-codec lookup. Asynchronous compilation is disabled in a native executable.

## Mixins

Use Fory JSON Mixins for models that cannot be modified:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;

@JsonMixin(target = ThirdPartyUser.class)
public abstract class ThirdPartyUserMixin {
  @JsonProperty("user_id")
  long id;
}

public class JsonExample {
  public static void main(String[] args) {
    ForyJson json =
        ForyJson.builder().registerMixin(ThirdPartyUserMixin.class).build();
    ThirdPartyUser user = json.fromJson("{\"user_id\":1}", ThirdPartyUser.class);
    System.out.println(json.toJson(user));
  }
}
```

`JsonMixin` is a build-time entry point for its exact declared target, so the target does not need
`JsonType` solely to use the Mixin. The registered Mixin class literal must be reachable from the
application. The Native Image Feature retains the target metadata and prepares the same access as
it does for a direct `JsonType` model. A provider configuration generates the Mixin
target only when that exact Mixin is registered in the returned `ForyJson`.

Only one source is enabled for an exact target in a built `ForyJson`. Later registration replaces
an earlier source for subsequent `build()` calls; each built `ForyJson` instance keeps the immutable snapshot it was
built with.

## Type Discovery and Construction

The `fory-json` artifact activates its Native Image Feature automatically. `@JsonType` is not
inherited, so annotate every concrete application model. An annotated base with a class-literal
`@JsonSubTypes` table registers its listed subtypes automatically. Dedicated supported containers,
including `EnumMap` and `EnumSet`, use their built-in factories. Other reachable concrete
`Collection` and `Map` root types require a public no-argument constructor. A class referenced only
by a class name resolved at runtime is not reachable;
`JsonSubTypes.Type.className` is therefore unsupported in a native image.

Do not add application reflection configuration as a replacement for the generated configuration.
The native executable resolves the same effective annotations as the JVM.

## Annotations and Custom Codecs

Effective `JsonValidator` methods must be public instance methods with no arguments and a `void`
return type. A model with a directly declared validator must use `JsonType`. A validator contributed
by a registered Mixin uses that exact Mixin-target pair, so the target does not also need
`JsonType`. The Native Image Feature prepares validator access for interpreted configurations and
provider-generated codecs invoke the same effective validators. Do not add reflection configuration
for validators. Complete custom codecs, complete `JsonValue` representations, and creators that
enforce validation themselves perform their own validation.

Type, field, effective ordinary getter, setter value parameter, and `JsonCreator` parameter
`@JsonCodec` annotations are supported. The Feature retains every selected complete-value, element,
content, Map-key, and Map-value codec constructor. This is the same annotation model used on the
JVM and Android.

`JsonValue` fields and effective public zero-argument methods are supported, including matching
one-String `JsonCreator` constructors and public static factories. Fixed `JsonRawValue` fields and
getters support trusted raw String values, and fixed `JsonBase64` fields and getters support Base64
`byte[]` values as on the JVM. `JsonFormat` date/time fields use the same direct-field,
one-wrapper-level, and `timezone` behavior as on the JVM. For direct target annotations, annotate
each reachable owning model with `JsonType` so Native Image retains these members and the Base64
codec constructor.
A directly annotated `JsonValue` Record uses its generated component accessor and canonical
constructor operations. An effective declaration supplied by a Mixin uses the Mixin workflow above
instead.

`JsonAnyProperty` and `JsonAnyGetter` flatten their Map into the enclosing object. Use
`@JsonCodec(valueCodec = ...)` on that field or getter to customize each dynamic value. A second
`JsonAnySetter` parameter may use the normal configuration for its own value shape.

`JsonUnwrapped` uses the same behavior as on the JVM. For direct target annotations, annotate the
containing model and every unwrapped child or intermediate object with `JsonType`. A Mixin retains
the unwrapped models reached by its effective schema; register a separate exact Mixin for a child
only when that child's annotations also need an overlay.

Child codecs act on one direct level. `elementCodec` supports `Collection`, Java arrays, and
`AtomicReferenceArray`; `contentCodec` supports `Optional` and `AtomicReference`; `keyCodec` and
`valueCodec` support Map keys and values. A complete `value` codec cannot be combined with a child
codec.

An annotation codec must have a public no-argument constructor. Fory prepares that constructor
during Native Image construction, so application modules do not need to export or open the codec
package. A codec instance supplied through `registerCodec` is constructed by the application and
needs no annotation-constructor metadata.
