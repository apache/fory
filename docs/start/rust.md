---
title: Rust Setup
sidebar_position: 5
id: rust
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

Fory Rust provides xlang and native Object Serialization, standard Row Format,
generated models, and Fory gRPC. The public `fory` crate is published on
crates.io, supports Rust 1.70 or later, and uses the Rust 2021 edition.

## Verify the Toolchain

```bash
rustc --version
cargo --version
```

## Object Serialization

Add the public crate:

```toml title="Cargo.toml"
[dependencies]
fory = "1.7.0"
```

```rust
use fory::{Error, Fory, ForyStruct};

#[derive(ForyStruct, Debug, PartialEq)]
struct User {
    id: i64,
    name: String,
}

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(true).build();
    fory.register::<User>(1)?;

    let user = User {
        id: 1,
        name: "Alice".to_string(),
    };
    let bytes = fory.serialize(&user)?;
    let decoded: User = fory.deserialize(&bytes)?;
    assert_eq!(user, decoded);
    Ok(())
}
```

Use [xlang mode](../object-serialization/rust/basic-serialization.md#cross-language-interoperability) for cross-language data
and [native mode](../object-serialization/rust/native.md) for Rust-only data.
Continue with [Rust Object Serialization](../object-serialization/rust/index.md),
[configuration](../object-serialization/rust/configuration.md), and
[type registration](../object-serialization/rust/type-registration.md).

## Other Capabilities

- **Row Format** provides zero-copy views over trusted analytical data using the standard Fory Row layout. See [Rust Row Format](../row-format/rust.md).
- **Fory IDL and Compiler** generates Rust models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Rust generated-code guide](../compiler/generated-code/rust.md).
- **Fory gRPC** uses tonic transports with Fory-encoded messages. See [Rust gRPC](../grpc/rust.md).
