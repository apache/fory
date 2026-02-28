---
title: Type Registration
sidebar_position: 3
id: type_registration
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

This page covers type registration methods in Apache Fory™ Rust.

## Register by ID

Register types with a numeric ID for fast, compact serialization:

```rust
use fory::Fory;
use fory::ForyObject;

#[derive(ForyObject)]
struct User {
    name: String,
    age: i32,
}

let mut fory = Fory::default();
fory.register::<User>(1)?;

let user = User {
    name: "Alice".to_string(),
    age: 30,
};

let bytes = fory.serialize(&user)?;
let decoded: User = fory.deserialize(&bytes)?;
```

## Register by Namespace

For cross-language compatibility, register with namespace and type name:

```rust
let mut fory = Fory::default()
    .compatible(true)
    .xlang(true);

// Register with namespace-based naming
fory.register_by_namespace::<MyStruct>("com.example", "MyStruct")?;
```

## Register Custom Serializer

For types that need custom serialization logic:

```rust
let mut fory = Fory::default();
fory.register_serializer::<CustomType>(100)?;
```

## Registration Consistency

Rust registration APIs use explicit IDs or explicit namespace/type names. Keep the same registration mapping on serializer and deserializer peers:

```rust
// Serializer side
let mut fory = Fory::default();
fory.register::<TypeA>(1)?;
fory.register::<TypeB>(2)?;
fory.register::<TypeC>(3)?;

// Deserializer side - MUST use the same ID mapping
let mut fory = Fory::default();
fory.register::<TypeA>(1)?;
fory.register::<TypeB>(2)?;
fory.register::<TypeC>(3)?;
```

## Thread-Safe Registration

Perform all registrations before spawning threads:

```rust
use std::sync::Arc;
use std::thread;

let mut fory = Fory::default();
fory.register::<User>(1)?;
fory.register::<Order>(2)?;

// Now share across threads
let fory = Arc::new(fory);

let handles: Vec<_> = (0..4)
    .map(|_| {
        let shared = Arc::clone(&fory);
        thread::spawn(move || {
            // Use fory for serialization
        })
    })
    .collect();
```

## Best Practices

1. **Use consistent IDs**: Same type ID across all languages for cross-language compatibility
2. **Register before threading**: Complete all registrations before spawning threads
3. **Use namespace for xlang**: Makes type names consistent across languages
4. **Explicit IDs for stability**: Avoid auto-generated IDs in production

## Related Topics

- [Configuration](configuration.md) - Fory builder options
- [Cross-Language](cross-language.md) - XLANG mode registration
- [Custom Serializers](custom-serializers.md) - Custom serialization
