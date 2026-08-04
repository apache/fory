---
title: Custom Serializers
sidebar_position: 11
id: custom-serializers
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

Use a custom serializer when derive cannot express the type's serialized
representation or when an opaque encoding is intentional. A serializer is a
type-level implementation and names the value it handles through `Target`.

For a public third-party struct or enum whose schema should remain structural,
prefer [External-Type Serialization](external-types.md).

## Implement a Custom Serializer

This example uses a local type as its own serializer:

```rust
use fory::{Error, Fory, ReadContext, Serializer, WriteContext};

#[derive(Debug, PartialEq)]
struct Point {
    value: i32,
}

impl Serializer for Point {
    type Target = Self;

    fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_i32(value.value);
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self {
            value: context.reader.read_i32()?,
        })
    }

    fn default_value(_context: &mut ReadContext) -> Result<Self, Error> {
        Ok(Self { value: 0 })
    }
}

let mut fory = Fory::builder().xlang(false).build();
fory.register_serializer::<Point>(100)?;

let value = Point { value: 42 };
let bytes = fory.serialize(&value)?;
let decoded: Point = fory.deserialize(&bytes)?;
assert_eq!(decoded, value);
# Ok::<(), Error>(())
```

`write_data` and `read_data` handle the EXT body. Fory's complete-value
`write` and `read` operations supply the root or field reference and
type-information framing.

`default_value` is optional. Implement it only when a null or missing
compatible field has a meaningful value. It receives the active `ReadContext`,
so a default that allocates can apply the same deserialization limits as a
normal read.

## Serialize a Third-Party Opaque Type

A separate serializer can target a type from another crate:

```rust
use fory::{Error, ReadContext, Serializer, WriteContext};

struct UuidSerializer;

#[cold]
#[inline(never)]
fn invalid_uuid(error: uuid::Error) -> Error {
    Error::invalid_data(error.to_string())
}

impl Serializer for UuidSerializer {
    type Target = uuid::Uuid;

    fn write_data(
        value: &uuid::Uuid,
        context: &mut WriteContext,
    ) -> Result<(), Error> {
        context.writer.write_bytes(value.as_bytes());
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<uuid::Uuid, Error> {
        let bytes = context.reader.read_bytes(16)?;
        uuid::Uuid::from_slice(bytes).map_err(invalid_uuid)
    }
}
```

Register the serializer, then select it at the root or field:

```rust
fory.register_serializer::<UuidSerializer>(101)?;

let bytes = fory.serialize_with::<UuidSerializer>(&uuid)?;
let decoded =
    fory.deserialize_with::<UuidSerializer>(&bytes)?;
```

```rust
#[derive(ForyStruct)]
struct Request {
    #[fory(with = UuidSerializer)]
    id: uuid::Uuid,
}
```

Custom serializer bodies are opaque. Compatible mode does not map fields
inside them.

## Support `Arc<dyn Any + Send + Sync>`

If a custom serializer's target must be materialized behind
`Arc<dyn Any + Send + Sync>` or a synchronized application trait, implement
`read_arc_any`:

```rust
use std::any::Any;
use std::sync::Arc;

impl Serializer for Point {
    type Target = Self;

    // Implement write_data, read_data, and any desired default as above.

    fn read_arc_any(
        context: &mut ReadContext,
    ) -> Result<Arc<dyn Any + Send + Sync>, Error> {
        Ok(Arc::new(Self::read_data(context)?))
    }
}
```

The target must implement `Send + Sync`. If this method is omitted, typed,
`Box`, and `Rc` operations remain available, while synchronized `Arc`
materialization returns an error.

## Registration by Name

Use name registration when the serialized identity is a qualified name:

```rust
fory.register_serializer_by_name::<UuidSerializer>(
    "example.Uuid",
)?;
```

One `Fory` instance can register at most one serializer for a target.

## Context Access

`WriteContext` and `ReadContext` expose the binary writer and reader:

```rust
context.writer.write_i8(value);
context.writer.write_i32(value);
context.writer.write_var_u32(value);
context.writer.write_f64(value);

let value = context.reader.read_i8()?;
let value = context.reader.read_i32()?;
let value = context.reader.read_var_u32()?;
let value = context.reader.read_f64()?;
```

For variable-size bodies, validate readable bytes and graph-memory limits
before allocating from an encoded length.

When a custom serializer is selected as a child of a variable-size carrier,
the carrier must emit at least one aggregate byte per declared element or map
entry after its count. Fory rejects serialization when the carrier's complete
header, metadata, framing, and child bodies are shorter than that count, so it
never emits bytes that the paired allocation-safety check cannot read. Fixed
arrays are exempt because their validated count does not control an
allocation; zero-sized elements in `Vec`, `VecDeque`, and `BinaryHeap` are also
exempt because those carriers allocate no backing storage for them.

## Related Topics

- [External-Type Serialization](external-types.md)
- [Type Registration](type-registration.md)
- [Schema Evolution](schema-evolution.md)
