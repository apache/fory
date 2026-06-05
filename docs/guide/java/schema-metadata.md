---
title: Schema Metadata
sidebar_position: 7
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

This page explains how to configure field-level metadata for serialization in Java.

## Overview

Apache Fory™ provides field-level configuration through annotations:

- **`@ForyField`**: Configure field metadata (id, dynamic)
- **`@Nullable`**: Mark a field type or nested type position as nullable
- **`@Ref`**: Enable field or nested-element reference tracking
- **`@Ignore`**: Exclude fields from serialization
- **Integer type annotations**: Control integer encoding (varint, fixed, tagged, unsigned)

This enables:

- **Tag IDs**: Assign compact numeric IDs to reduce struct field meta size overhead for compatible mode
- **Nullability**: Control whether fields can be null
- **Reference Tracking**: Enable reference tracking for shared objects
- **Field Skipping**: Exclude fields from serialization
- **Encoding Control**: Specify how integers are encoded
- **Polymorphism Control**: Control type info writing for struct fields

## Basic Syntax

Use annotations on fields:

```java
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Nullable;

public class Person {
    @ForyField(id = 0)
    private String name;

    @ForyField(id = 1)
    private int age;

    @Nullable
    @ForyField(id = 2)
    private String nickname;
}
```

## The `@ForyField` Annotation

Use `@ForyField` to configure field-level metadata:

```java
public class User {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;

    @Nullable
    @ForyField(id = 2)
    private String email;

    @ForyField(id = 3)
    private List<@Ref User> friends;

    @ForyField(id = 4, dynamic = ForyField.Dynamic.TRUE)
    private Object data;
}
```

### Parameters

| Parameter | Type      | Default | Description                            |
| --------- | --------- | ------- | -------------------------------------- |
| `id`      | `int`     | `-1`    | Non-negative field tag ID, or no ID    |
| `dynamic` | `Dynamic` | `AUTO`  | Control polymorphism for struct fields |

Use `@Nullable` on the field type or nested type position for nullable schema
metadata and `@Ref` for reference tracking. `@ForyField` does not carry either
setting.

## Field ID (`id`)

Assigns a numeric ID to a field to minimize struct field meta size overhead for compatible mode:

```java
public class User {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;

    @ForyField(id = 2)
    private int age;
}
```

**Benefits**:

- Smaller serialized size (numeric IDs vs field names in metadata)
- Reduced struct field meta overhead
- Allows renaming fields without breaking binary compatibility

**Recommendation**: It is recommended to configure field IDs for compatible mode since it reduces serialization cost.

**Notes**:

- IDs must be unique within a class
- IDs must be >= 0 when configured
- If not specified, the annotation default `-1` is ignored and field name is used in metadata
  (larger overhead)

**Without field IDs** (field names used in metadata):

```java
public class User {
    private long id;
    private String name;
}
```

## Nullable Fields (`@Nullable`)

Use `@Nullable` for fields that can be `null`:

```java
public class Record {
    // Nullable string field
    @Nullable
    @ForyField(id = 0)
    private String optionalName;

    // Nullable Integer field (boxed type)
    @Nullable
    @ForyField(id = 1)
    private Integer optionalCount;

    // Non-nullable field (default)
    @ForyField(id = 2)
    private String requiredName;
}
```

**Notes**:

- Xlang fields are non-nullable by default.
- When a field is non-nullable, Fory skips writing the null flag.
- Boxed types (`Integer`, `Long`, etc.) that can be null should use `@Nullable`.

## Reference Tracking (`@Ref`)

Enable reference tracking for fields that may be shared or circular:

```java
public class RefOuter {
    // Both fields may point to the same inner object
    @Nullable
    @ForyField(id = 0)
    @Ref
    private RefInner inner1;

    @Nullable
    @ForyField(id = 1)
    @Ref
    private RefInner inner2;
}

public class CircularRef {
    @ForyField(id = 0)
    private String name;

    // Self-referencing field for circular references
    @Nullable
    @ForyField(id = 1)
    @Ref
    private CircularRef selfRef;
}
```

**Use Cases**:

- Enable for fields that may be circular or shared
- When the same object is referenced from multiple fields

**Notes**:

- Fields without `@Ref` do not use field-wrapper reference tracking
- Avoid `@Ref` when values are not shared or circular, so Fory can skip the reference flag
- Reference tracking only takes effect when global ref tracking is enabled

## Dynamic (Polymorphism Control)

Controls polymorphism behavior for struct fields in cross-language serialization:

```java
public class Container {
    // AUTO: Interface/abstract types are dynamic, concrete types are not
    @ForyField(id = 0, dynamic = ForyField.Dynamic.AUTO)
    private Animal animal;  // Interface - type info written

    // FALSE: No type info written, uses declared type's serializer
    @ForyField(id = 1, dynamic = ForyField.Dynamic.FALSE)
    private Dog dog;  // Concrete - no type info

    // TRUE: Type info written to support subtypes
    @ForyField(id = 2, dynamic = ForyField.Dynamic.TRUE)
    private Object data;  // Force polymorphic
}
```

**Options**:

| Value   | Description                                                         |
| ------- | ------------------------------------------------------------------- |
| `AUTO`  | Auto-detect: interface/abstract are dynamic, concrete types are not |
| `FALSE` | No type info written, uses declared type's serializer directly      |
| `TRUE`  | Type info written to support subtypes                               |

## Skipping Fields

### Using `@Ignore`

Exclude fields from serialization:

```java
import org.apache.fory.annotation.Ignore;

public class User {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;

    @Ignore
    private String password;  // Not serialized

    @Ignore
    private Object internalState;  // Not serialized
}
```

### Using `transient`

Java's `transient` keyword also excludes fields:

```java
public class User {
    @ForyField(id = 0)
    private long id;

    private transient String password;  // Not serialized
    private transient Object cache;     // Not serialized
}
```

## Integer Type Annotations

Fory provides annotations to control integer encoding for cross-language compatibility.
Integer schema annotations are Java type-use annotations. Put them on the field type, after any
field modifiers and alongside `@ForyField` when both are present.

### Signed 32-bit Integer (`@Int32Type`)

```java
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.config.Int32Encoding;

public class MyStruct {
    // Variable-length encoding (default) - compact for small values
    private @Int32Type(encoding = Int32Encoding.VARINT) int compactId;

    // Fixed 4-byte encoding - consistent size
    private @Int32Type(encoding = Int32Encoding.FIXED) int fixedId;
}
```

### Signed 64-bit Integer (`@Int64Type`)

```java
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.config.Int64Encoding;

public class MyStruct {
    // Variable-length encoding (default)
    private @Int64Type(encoding = Int64Encoding.VARINT) long compactId;

    // Fixed 8-byte encoding
    private @Int64Type(encoding = Int64Encoding.FIXED) long fixedTimestamp;

    // Tagged encoding (4 bytes for small values, 9 bytes otherwise)
    private @Int64Type(encoding = Int64Encoding.TAGGED) long taggedValue;
}
```

### Unsigned Integers

```java
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;

public class UnsignedStruct {
    // Unsigned 8-bit [0, 255]
    private @UInt8Type int flags;

    // Unsigned 16-bit [0, 65535]
    private @UInt16Type int port;

    // Unsigned 32-bit with varint encoding (default)
    private @UInt32Type(encoding = Int32Encoding.VARINT) long compactCount;

    // Unsigned 32-bit with fixed encoding
    private @UInt32Type(encoding = Int32Encoding.FIXED) long fixedCount;

    // Unsigned 64-bit with various encodings
    private @UInt64Type(encoding = Int64Encoding.VARINT) long varintU64;

    private @UInt64Type(encoding = Int64Encoding.FIXED) long fixedU64;

    private @UInt64Type(encoding = Int64Encoding.TAGGED) long taggedU64;
}
```

### Encoding Summary

| Annotation                       | Type ID | Encoding | Size         |
| -------------------------------- | ------- | -------- | ------------ |
| `@Int32Type(encoding = VARINT)`  | 5       | varint   | 1-5 bytes    |
| `@Int32Type(encoding = FIXED)`   | 4       | fixed    | 4 bytes      |
| `@Int64Type(encoding = VARINT)`  | 7       | varint   | 1-10 bytes   |
| `@Int64Type(encoding = FIXED)`   | 6       | fixed    | 8 bytes      |
| `@Int64Type(encoding = TAGGED)`  | 8       | tagged   | 4 or 9 bytes |
| `@UInt8Type`                     | 9       | fixed    | 1 byte       |
| `@UInt16Type`                    | 10      | fixed    | 2 bytes      |
| `@UInt32Type(encoding = VARINT)` | 12      | varint   | 1-5 bytes    |
| `@UInt32Type(encoding = FIXED)`  | 11      | fixed    | 4 bytes      |
| `@UInt64Type(encoding = VARINT)` | 14      | varint   | 1-10 bytes   |
| `@UInt64Type(encoding = FIXED)`  | 13      | fixed    | 8 bytes      |
| `@UInt64Type(encoding = TAGGED)` | 15      | tagged   | 4 or 9 bytes |

**When to Use**:

- `varint`: Best for values that are often small (default)
- `fixed`: Best for values that use full range (e.g., timestamps, hashes)
- `tagged`: Good balance between size and performance
- Unsigned types: For cross-language compatibility with Rust, Go, C++

Unsigned Java scalar carriers are `int`/`Integer` for `@UInt8Type` and
`@UInt16Type`, and `long`/`Long` for `@UInt32Type` and `@UInt64Type`.
Annotating `byte` with `@UInt8Type` is invalid because Java `byte` cannot
represent the unsigned range.

Integer annotations can also be applied to nested generic type arguments:

```java
import java.util.List;
import java.util.Map;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Int64Encoding;

public class NestedStruct {
    private Map<
            @UInt32Type(encoding = Int32Encoding.FIXED) Long,
            List<@Int64Type(encoding = Int64Encoding.TAGGED) Long>>
        values;
}
```

Specialized unsigned list carriers use the `list<T>` schema by default, so
their element annotations are preserved in list metadata. Add `@ArrayType` only
when the field should use dense `array<T>` schema.

Primitive unsigned arrays can use scalar element annotations for dense
`array<T>` metadata:

```java
import org.apache.fory.annotation.UInt32Type;

public class IdBatch {
    private @UInt32Type int[] ids;
}
```

## Complete Example

```java
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Ignore;
import org.apache.fory.annotation.Int64Type;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.config.Int64Encoding;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Document {
    // Fields with tag IDs (recommended for compatible mode)
    @ForyField(id = 0)
    private String title;

    @ForyField(id = 1)
    private int version;

    // Nullable field
    @Nullable
    @ForyField(id = 2)
    private String description;

    // Collection fields
    @ForyField(id = 3)
    private List<String> tags;

    @ForyField(id = 4)
    private Map<String, String> metadata;

    @ForyField(id = 5)
    private Set<String> categories;

    // Integer with different encodings
    @ForyField(id = 6)
    private @UInt64Type(encoding = Int64Encoding.VARINT) long viewCount;  // varint encoding

    @ForyField(id = 7)
    private @UInt64Type(encoding = Int64Encoding.FIXED) long fileSize;   // fixed encoding

    @ForyField(id = 8)
    private @UInt64Type(encoding = Int64Encoding.TAGGED) long checksum;   // tagged encoding

    // Reference-tracked field for shared/circular references
    @Nullable
    @ForyField(id = 9)
    @Ref
    private Document parent;

    // Ignored field (not serialized)
    private transient Object cache;

    // Getters and setters...
}

// Usage
public class Main {
    public static void main(String[] args) {
        Fory fory = Fory.builder()
            .withXlang(true)
            .withRefTracking(true)
            .build();

        fory.register(Document.class, 100);

        Document doc = new Document();
        doc.setTitle("My Document");
        doc.setVersion(1);
        doc.setDescription("A sample document");

        // Serialize
        byte[] data = fory.serialize(doc);

        // Deserialize
        Document decoded = (Document) fory.deserialize(data);
    }
}
```

## Cross-Language Compatibility

When serializing data to be read by other languages (Python, Rust, C++, Go), use field IDs and matching type annotations:

```java
public class CrossLangData {
    // Use field IDs for cross-language compatibility
    @ForyField(id = 0)
    private @Int32Type(encoding = Int32Encoding.VARINT) int intVar;

    @ForyField(id = 1)
    private @UInt64Type(encoding = Int64Encoding.FIXED) long longFixed;

    @ForyField(id = 2)
    private @UInt64Type(encoding = Int64Encoding.TAGGED) long longTagged;

    @Nullable
    @ForyField(id = 3)
    private String optionalValue;
}
```

## Schema Evolution

Compatible mode supports schema evolution. It is recommended to configure field IDs to reduce serialization cost:

```java
// Version 1
public class DataV1 {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;
}

// Version 2: Added new field
public class DataV2 {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;

    @Nullable
    @ForyField(id = 2)
    private String email;  // New field
}
```

Data serialized with V1 can be deserialized with V2 (new field will be `null`).

Alternatively, field IDs can be omitted (field names will be used in metadata with larger overhead):

```java
public class Data {
    private long id;
    private String name;
}
```

## Enum Metadata

Java enums are serialized by numeric tag in xlang mode. The default tag is the declaration ordinal.
When an enum needs stable ids that do not depend on declaration order, annotate exactly one id
source with `@ForyEnumId`, or annotate every enum constant with explicit tag values.

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
constant directly, such as `@ForyEnumId(10) Unknown`.

`@ForyEnumId` supports exactly three configuration styles:

1. Annotate one enum instance field and store the numeric id there.
2. Annotate one zero-argument public instance method such as `getId()`.
3. Annotate every enum constant directly with an explicit value such as `@ForyEnumId(10) Unknown`.

Validation rules:

1. Use exactly one of those three styles for a given enum.
2. Field and method annotations must leave `value()` at its default `-1`.
3. Enum-constant annotations must appear on every constant once any constant uses `@ForyEnumId`.
4. All ids must be non-negative, unique, and fit in Java `int`.

Lookup behavior:

1. Without `@ForyEnumId`, Fory writes the declaration ordinal.
2. With `@ForyEnumId`, Fory writes the configured stable numeric tag instead.
3. Small dense tags use an array lookup internally; sparse larger tags fall back to a map.

Use `serializeEnumByName(true)` only for Java native-mode peers that should match enum constants by
name instead of numeric tag:

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .serializeEnumByName(true)
    .build();
```

This Java native-mode option does not change xlang enum encoding; xlang uses numeric enum tags. Prefer
`@ForyEnumId` for cross-language payloads or any schema where numeric wire ids must stay stable.

## Native Mode vs Xlang Mode

Field configuration behaves differently depending on the serialization mode:

### Native Mode (Java-only)

Native mode has **relaxed default values** for maximum compatibility:

- **Nullable**: Reference types are nullable by default
- **Ref tracking**: Enabled by default for object references (except `String`, boxed types, and time types)
- **Polymorphism**: All non-final classes support polymorphism by default

In native mode, you typically **don't need to configure field annotations** unless you want to:

- Reduce serialized size by using field IDs
- Optimize performance by disabling unnecessary ref tracking
- Control integer encoding for specific fields

```java
// Native mode: works without any annotations
public class User {
    private long id;
    private String name;
    private List<String> tags;  // Nullable and ref-tracked by default
}
```

### Xlang Mode (Cross-language)

Xlang mode has **stricter default values** due to type system differences between languages:

- **Nullable**: Fields are non-nullable by default
- **Ref tracking**: Disabled by default unless the field type uses `@Ref`
- **Polymorphism**: Concrete types are non-polymorphic by default

In xlang mode, you **need to configure fields** when:

- A field can be null (use `@Nullable`)
- A field needs reference tracking for shared/circular objects (use `@Ref`)
- Integer types need specific encoding for cross-language compatibility
- You want to reduce metadata size (use field IDs)

```java
// Xlang mode: explicit configuration required for nullable/ref fields
public class User {
    @ForyField(id = 0)
    private long id;

    @ForyField(id = 1)
    private String name;

    @Nullable
    @ForyField(id = 2) // Must declare @Nullable
    private String email;

    @Nullable
    @ForyField(id = 3)
    @Ref // Must declare @Ref for shared objects
    private User friend;
}
```

### Default Values Summary

| Option     | Native Mode Default      | Xlang Mode Default                |
| ---------- | ------------------------ | --------------------------------- |
| `nullable` | `true` (reference types) | `false`                           |
| `ref`      | `true`                   | `false`                           |
| `dynamic`  | `true` (non-final)       | `AUTO` (concrete types are final) |

## Best Practices

1. **Configure field IDs**: Recommended for compatible mode to reduce serialization cost
2. **Use `@Nullable` for nullable fields**: Required for fields that can be null
3. **Enable ref tracking for shared objects**: Use `@Ref` when objects are shared or circular
4. **Use `@Ignore` or `transient` for sensitive data**: Passwords, tokens, internal state
5. **Choose appropriate encoding**: `varint` for small values, `fixed` for full-range values
6. **Keep IDs stable**: Once assigned, don't change field IDs
7. **Configure unsigned types for cross-language compatibility**: When interoperating with unsigned numbers in Rust, Go, C++

## Annotations Reference

| Annotation                    | Description                            |
| ----------------------------- | -------------------------------------- |
| `@ForyField(id = N)`          | Field tag ID to reduce metadata size   |
| `@Nullable`                   | Mark field or nested type as nullable  |
| `@Ref`                        | Enable reference tracking              |
| `@ForyField(dynamic = ...)`   | Control polymorphism for struct fields |
| `@Ignore`                     | Exclude field from serialization       |
| `@Int32Type(encoding = ...)`  | 32-bit signed integer encoding         |
| `@Int64Type(encoding = ...)`  | 64-bit signed integer encoding         |
| `@UInt8Type`                  | Unsigned 8-bit integer                 |
| `@UInt16Type`                 | Unsigned 16-bit integer                |
| `@UInt32Type(encoding = ...)` | Unsigned 32-bit integer encoding       |
| `@UInt64Type(encoding = ...)` | Unsigned 64-bit integer encoding       |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Getting started with Fory serialization
- [Configuration](configuration.md) - `ForyBuilder` options
- [Schema Evolution](schema-evolution.md) - Compatible mode and schema evolution
- [Xlang Serialization](xlang-serialization.md) - Interoperability with Python, Rust, C++, Go
