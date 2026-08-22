---
title: Rust Standard Row Format
sidebar_position: 6
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

Apache Fory™ Rust implements the Standard Row Format used by Java, C++, and Python. It provides zero-copy borrowed views and random field access without reconstructing the complete value.

## Overview

Use Row Format when readers need selected fields or collection elements rather than an owned copy of the complete value. The view borrows the input bytes, so the bytes must remain alive while the view is in use.

Row Format is schema-driven: the Rust type supplied to `from_row` determines the field types and declaration order. Cross-language readers and writers must use the same schema.

## When to Use Row Format

- Analytics workloads with selective field access
- Large datasets where only a subset of fields is needed
- Memory-constrained environments
- High-throughput data pipelines
- Sharing Standard Row Format bytes with Java, C++, or Python

## Basic Usage

```rust
use fory::{from_row, to_row, Error, ForyRow, RowView};
use std::collections::BTreeMap;

#[derive(ForyRow)]
struct UserProfile {
    id: i64,
    username: String,
    email: Option<String>,
    scores: Vec<i32>,
    preferences: BTreeMap<String, String>,
    is_active: bool,
}

fn main() -> Result<(), Error> {
    let profile = UserProfile {
        id: 12345,
        username: "alice".to_string(),
        email: Some("alice@example.com".to_string()),
        scores: vec![95, 87, 92, 88],
        preferences: BTreeMap::from([
            ("theme".to_string(), "dark".to_string()),
            ("language".to_string(), "en".to_string()),
        ]),
        is_active: true,
    };

    let row_data = to_row(&profile)?;
    let row = from_row::<UserProfile>(&row_data)?;

    // Field methods return Result and validate the referenced bytes.
    assert_eq!(row.id()?, 12345);
    assert_eq!(row.username()?, "alice");
    assert_eq!(row.email()?, Some("alice@example.com"));
    assert!(row.is_active()?);

    let scores = row.scores()?;
    assert_eq!(scores.len(), 4);
    assert_eq!(scores.get(0)?, 95);
    assert_eq!(scores.get(1)?, 87);
    assert_eq!(
        scores.iter().collect::<Result<Vec<_>, _>>()?,
        [95, 87, 92, 88]
    );

    let preferences = row.preferences()?;
    assert_eq!(preferences.len(), 2);
    assert_eq!(preferences.key(0)?, "language");
    assert_eq!(preferences.value(0)?, "en");
    assert_eq!(row.as_bytes(), row_data);
    Ok(())
}
```

`to_row` accepts Row Format roots: derived structs, supported arrays, and `BTreeMap` values. Scalar, string, binary, and `Option<T>` values are field or element values rather than standalone roots.

## View Traversal and Buffer Reuse

`ArrayView::iter` and `IntoIterator for &ArrayView` read elements on demand through the same checked path as `get`. Each item is a `Result`, so malformed data is reported when that element is visited.

`MapView` exposes `len`, `is_empty`, `key(index)`, and `value(index)`. Its `keys()` and `values()` array views remain available for independent iteration.
Call `to_btree_map()` only when an owned lookup structure is more useful than indexed access; it materializes the map from the borrowed key and value views.

Struct, array, and map views are cheap `Copy` and `Clone` values. The `RowView` trait provides `as_bytes()`, which returns the exact encoded slice bound to the view, and `encoded_len()`, which returns its length. A nested view returns only its size-delimited child bytes.

Use `to_row_into` to replace a caller-owned buffer while retaining its capacity:

```rust
use fory::to_row_into;

let mut row_data = Vec::with_capacity(4096);
to_row_into(&vec![1i32, 2, 3], &mut row_data).unwrap();
```

Repeated calls discard the previous logical contents. If encoding returns an error, the buffer is left empty. Row framing remains the application's responsibility.

## Nullability and Field Order

`Option<T>` declares a nullable field or array element. `None` sets the corresponding null bit, and the field method returns `None` without reading a value body. `Some(value)` uses the same fixed slot width as `T`.

`#[derive(ForyRow)]` supports named structs, including generic structs. Fields are encoded in source declaration order. The derive generates a borrowed `StructNameRowView` type whose visibility matches the source struct. Each generated field method preserves the corresponding field's visibility and returns `Result<_, Error>`.

Changing field order or field types changes the Row Format schema. Coordinate such changes across all producers and consumers.

## Supported Types

| Rust type                                      | Standard Row Format encoding     |
| ---------------------------------------------- | -------------------------------- |
| `bool`, `i8`, `i16`, `i32`, `i64`              | Fixed-width scalar               |
| `f32`, `f64`                                   | Fixed-width IEEE 754 scalar      |
| `Date`                                         | Fixed-width date32 in epoch days |
| `Timestamp`                                    | Fixed-width epoch microseconds   |
| `Duration`                                     | Fixed-width microseconds         |
| `String`, `&str`                               | Variable-width UTF-8             |
| `Vec<u8>`, `&[u8]`                             | Variable-width binary            |
| `Vec<T>`, `[T; N]` for supported element types | Standard array                   |
| `BTreeMap<K, V>`                               | Standard map                     |
| Named structs with `#[derive(ForyRow)]`        | Nested Standard Row              |
| `Option<T>`                                    | Nullable field or array element  |

`Float16` and `Decimal` are not supported because the Standard Row Format specification does not define complete interoperable encodings for them.

Fixed arrays require the encoded element count to equal `N`. `BTreeMap` keys must implement `Ord`; map values do not need to implement `Ord`.

`Vec<u8>` is encoded as binary rather than as a Standard Array. Use another supported element type when an array representation is required.

## Standard Binary Layout

- A row starts with an 8-byte-aligned null bitmap followed by one 8-byte slot per field.
- Fixed-width values are stored little-endian at the low address of their slot. Unused slot bytes are zero.
- A variable-width slot is the little-endian `u64` value `(relative_offset << 32) | size`. The value body and its zero padding follow the fixed region.
- An array starts with a `u64` element count and an 8-byte-aligned null bitmap. Fixed-width elements use contiguous natural-width slots; variable-width elements use 8-byte offset-size slots.
- A map contains the key-array byte size followed by complete key and value arrays. Nested structs, arrays, and maps are complete child structures.
- Variable bodies and array slot regions are padded with zeroes to an 8-byte boundary. Offsets are relative to the immediate containing row or array.

For the normative layout and size formulas, see the [Row Format Specification](../specification/row_format_spec.md).

## Validation and Errors

`from_row`, generated field methods, array `get`/iteration, and map indexed access return `Result`. They reject truncated fixed regions, invalid counts, out-of-range offsets and sizes, invalid UTF-8, fixed-array length mismatches, and mismatched map key/value counts.

Array access is also bounds-checked:

```rust
let scores = row.scores()?;
assert!(scores.get(scores.len()).is_err());
```

## Performance Comparison

| Operation         | Object Format                       | Row Format                               |
| ----------------- | ----------------------------------- | ---------------------------------------- |
| Open encoded data | Reconstructs an owned value         | Creates a borrowed view                  |
| Read one field    | Accesses the reconstructed object   | Validates and reads the field directly   |
| Collection access | Uses an owned collection            | Uses a borrowed array or map view        |
| Suitable for      | Full object use and graph semantics | Selective access and cross-language rows |

## ForyRow vs ForyStruct

| Feature      | `#[derive(ForyRow)]`                   | `#[derive(ForyStruct)]`      |
| ------------ | -------------------------------------- | ---------------------------- |
| Read result  | Borrowed view                          | Owned Rust value             |
| Field access | Field method returning `Result`        | Normal struct access         |
| Schema order | Source declaration order               | Object-format schema rules   |
| Best for     | Selective access to Standard Row bytes | General object serialization |

## Related Topics

- [Basic Serialization](../object-serialization/rust/basic-serialization.md) - Object graph serialization
- [Standard Row Format](index.md#standard-row) - Shared layout for Java, Python, C++, and Rust
- [Row Format Specification](../specification/row_format_spec.md) - Protocol details
