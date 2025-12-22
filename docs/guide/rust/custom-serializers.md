---
title: Custom Serializers
sidebar_position: 4
id: rust_custom_serializers
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

For types that don't support `#[derive(ForyObject)]`, implement the `Serializer` trait manually.

## When to Use Custom Serializers

- External types from other crates
- Types with special serialization requirements
- Legacy data format compatibility
- Performance-critical custom encoding

## Implementing the Serializer Trait

```rust
use fory::{Fory, ReadContext, WriteContext, Serializer, ForyDefault, Error};
use std::any::Any;

#[derive(Debug, PartialEq, Default)]
struct CustomType {
    value: i32,
    name: String,
}

impl Serializer for CustomType {
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        context.writer.write_i32(self.value);
        context.writer.write_varuint32(self.name.len() as u32);
        context.writer.write_utf8_string(&self.name);
    }

    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        let value = context.reader.read_i32();
        let len = context.reader.read_varuint32() as usize;
        let name = context.reader.read_utf8_string(len);
        Ok(Self { value, name })
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> u32 {
        Self::fory_get_type_id(type_resolver)
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

// ForyDefault delegates to Default
impl ForyDefault for CustomType {
    fn fory_default() -> Self {
        Self::default()
    }
}
```

> **Note**: When implementing `ForyDefault` manually, ensure your type also implements `Default` if you use `Self::default()`.
> Alternatively, you can construct a default instance directly in `fory_default()`.
>
> **Tip**: If your type supports `#[derive(ForyObject)]`, you can use `#[fory(generate_default)]` to automatically generate both `ForyDefault` and `Default` implementations.

## Registering Custom Serializers

```rust
let mut fory = Fory::default();
fory.register_serializer::<CustomType>(100);

let custom = CustomType {
    value: 42,
    name: "test".to_string(),
};
let bytes = fory.serialize(&custom);
let decoded: CustomType = fory.deserialize(&bytes)?;
assert_eq!(custom, decoded);
```

## WriteContext and ReadContext

The `WriteContext` and `ReadContext` provide access to:

- **writer/reader**: Binary buffer operations
- **type_resolver**: Type registration information
- **ref_resolver**: Reference tracking (for shared/circular references)

### Common Writer Methods

```rust
// Primitive types
context.writer.write_i8(value);
context.writer.write_i16(value);
context.writer.write_i32(value);
context.writer.write_i64(value);
context.writer.write_f32(value);
context.writer.write_f64(value);
context.writer.write_bool(value);

// Variable-length integers
context.writer.write_varint32(value);
context.writer.write_varuint32(value);

// Strings
context.writer.write_utf8_string(&string);
```

### Common Reader Methods

```rust
// Primitive types
let value = context.reader.read_i8();
let value = context.reader.read_i16();
let value = context.reader.read_i32();
let value = context.reader.read_i64();
let value = context.reader.read_f32();
let value = context.reader.read_f64();
let value = context.reader.read_bool();

// Variable-length integers
let value = context.reader.read_varint32();
let value = context.reader.read_varuint32();

// Strings
let string = context.reader.read_utf8_string(len);
```

## Best Practices

1. **Use variable-length encoding** for integers that may be small
2. **Write length first** for variable-length data
3. **Handle errors properly** in read methods
4. **Implement ForyDefault** for schema evolution support

## Related Topics

- [Type Registration](type-registration.md) - Registering serializers
- [Basic Serialization](basic-serialization.md) - Using ForyObject derive
- [Schema Evolution](schema-evolution.md) - Compatible mode
