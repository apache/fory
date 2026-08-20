---
title: Modules
sidebar_position: 6
id: modules
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

`ForyJsonModule` packages a coherent set of Fory JSON registrations into one reusable extension.
A module can be provided by a language integration, a third-party library, a framework, or an
application. Use a module when consumers should install the whole extension with one builder call;
use direct builder registration for an application-specific codec that does not need separate
distribution.

## Creating and Installing a Module

After implementing a codec such as `MoneyCodec` from the
[Custom Codecs guide](custom-codecs.md), a library can distribute its registration as a module:

```java
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonModule;
import org.apache.fory.json.ModuleContext;

public final class MoneyJsonModule implements ForyJsonModule {
  public static final MoneyJsonModule INSTANCE = new MoneyJsonModule();

  private MoneyJsonModule() {}

  @Override
  public void install(ModuleContext context) {
    context.registerCodec(Money.class, new MoneyCodec());
  }
}

ForyJson json =
    ForyJson.builder()
        .withModule(MoneyJsonModule.INSTANCE)
        .build();
```

Installation runs while `build()` creates the immutable runtime configuration. Treat a module
configuration as immutable after adding it to a builder. Registered codec instances are shared by
concurrent operations and must be thread-safe.

## Module Registrations

`ModuleContext` exposes the registrations needed by reusable integrations:

| Registration                             | Use                                                      |
| ---------------------------------------- | -------------------------------------------------------- |
| `registerCodec(Class, JsonValueCodec)`   | One shared complete codec for an exact class             |
| `registerCodec(Class, JsonCodecFactory)` | A resolver-owned complete codec for an exact class       |
| `registerMixin(Class)`                   | An annotated Mixin for its declared target               |
| `registerCodecFactory(JsonCodecFactory)` | A codec family selected from a parameterized target type |

Codec implementations and `JsonCodecFactory` behavior are documented in
[Custom Codecs](custom-codecs.md). Modules only package those registrations for installation.

Application registrations made directly on `ForyJsonBuilder` take precedence over module exact
registrations. Conflicting module registrations fail during `build()` instead of depending on
installation order.

## Kotlin Module

`ForyJsonKotlin` is the optional module for Kotlin/JVM models. Prefer its builder when an
application uses Kotlin models:

```kotlin
import org.apache.fory.json.kotlin.ForyJsonKotlin

val json = ForyJsonKotlin.builder().build()
```

This is equivalent to `ForyJson.builder().withModule(ForyJsonKotlin)`. It does not scan the
classpath or register application models. Exact application codec registrations retain normal
precedence. See [Kotlin](kotlin.md) for type tokens and optional Android minification setup.

## Module Identity

`moduleKey()` identifies the module configuration for generated-code reuse and conflict checking.
The default key is the module class name and is sufficient for a configuration-free module.

A configurable module must return a deterministic key that includes every option affecting codec
selection or generated code. Do not include secrets, mutable process state, or values unrelated to
the installed JSON behavior.

```java
public final class ConfiguredJsonModule implements ForyJsonModule {
  private final boolean compactNames;

  public ConfiguredJsonModule(boolean compactNames) {
    this.compactNames = compactNames;
  }

  @Override
  public String moduleKey() {
    return getClass().getName() + ":compactNames=" + compactNames;
  }

  @Override
  public void install(ModuleContext context) {
    // Register the codecs, factories, or Mixins selected by this configuration.
  }
}
```

For a Scala example that packages derived third-party enum codecs in a module, see
[Packaging Derived Codecs in a Module](scala.md#packaging-derived-codecs-in-a-module).
