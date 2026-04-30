---
title: Enum Configuration
sidebar_position: 6
id: enum_configuration
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

This page explains how Java enum serialization is configured in Apache Fory.

## Default Enum Behavior

Java enums can be serialized in two modes:

1. **By numeric tag**: the default behavior
2. **By enum name**: enabled with `serializeEnumByName(true)`

Numeric tags are always used in xlang mode. In native Java mode, `serializeEnumByName(true)`
switches enum serialization to names instead of numeric tags.

## Serialize Enums by Name

Use `serializeEnumByName(true)` when native Java peers should match enum constants by name instead
of numeric tag.

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .serializeEnumByName(true)
    .build();
```

This mode is useful when declaration order is unstable but enum names remain fixed. It only affects
native Java mode. Xlang still uses numeric tags.

## Stable Numeric Enum IDs

Without `serializeEnumByName(true)`, Java enums are serialized by numeric tag. The default tag is
the declaration ordinal. When an enum needs stable ids that do not depend on declaration order,
annotate exactly one id source with `@ForyEnumId`, or annotate every enum constant with explicit
tag values.

```java
import org.apache.fory.annotation.ForyEnumId;

enum Status {
    Unknown(10),
    Running(20),
    Finished(30);

    private final int id;

    Status(int id) {
        this.id = id;
    }

    @ForyEnumId
    public int getId() {
        return id;
    }
}
```

Java also supports annotating one enum instance field with `@ForyEnumId`, or annotating every enum
constant directly such as `@ForyEnumId(10) Unknown`.

### `@ForyEnumId` Styles

`@ForyEnumId` supports exactly three configuration styles:

1. Annotate one enum instance field and store the numeric id there.
2. Annotate one zero-argument public instance method such as `getId()`.
3. Annotate every enum constant directly with an explicit value such as `@ForyEnumId(10) Unknown`.

### Validation Rules

1. Use exactly one of those three styles for a given enum.
2. Field and method annotations must leave `value()` at its default `-1`.
3. Enum-constant annotations must appear on every constant once any constant uses `@ForyEnumId`.
4. All ids must be non-negative, unique, and fit in Java `int`.

### Lookup Behavior

1. Without `@ForyEnumId`, Fory writes the declaration ordinal.
2. With `@ForyEnumId`, Fory writes the configured stable numeric tag instead.
3. Small dense tags use an array lookup internally; sparse larger tags fall back to a map.

## Choosing Between Name and Numeric Modes

- Use **enum names** when the enum is Java-only and constant names are the intended compatibility key.
- Use **numeric tags** when cross-language payloads or stable explicit ids matter.
- Use **`@ForyEnumId`** when declaration order may change but the numeric wire ids must stay stable.

## Related Topics

- [Configuration](configuration.md) - `serializeEnumByName` and other runtime options
- [Field Configuration](field-configuration.md) - `@ForyField`, `@Ignore`, and integer encoding annotations
- [Cross-Language](cross-language.md) - Xlang enum interoperability
