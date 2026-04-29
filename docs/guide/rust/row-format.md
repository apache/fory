---
title: Row Format
sidebar_position: 9
id: row_format
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

Apache Fory™ provides a high-performance **row format** for zero-copy deserialization.

## Overview

Unlike traditional object serialization that reconstructs entire objects in memory, row format enables **random access** to fields directly from binary data without full deserialization.

**Key benefits:**

- **Zero-copy access**: Read fields without allocating or copying data
- **Partial deserialization**: Access only the fields you need
- **Memory-mapped files**: Work with data larger than RAM
- **Cache-friendly**: Sequential memory layout for better CPU cache utilization
- **Lazy evaluation**: Defer expensive operations until field access

## When to Use Row Format

- Analytics workloads with selective field access
- Large datasets where only a subset of fields is needed
- Memory-constrained environments
- High-throughput data pipelines
- Reading from memory-mapped files or shared memory

## Basic Usage

```rust
use fory::{to_row, from_row};
use fory::ForyRow;
use std::collections::BTreeMap;

#[derive(ForyRow)]
struct UserProfile {
    id: i64,
    username: String,
    email: String,
    scores: Vec<i32>,
    preferences: BTreeMap<String, String>,
    is_active: bool,
}

let profile = UserProfile {
    id: 12345,
    username: "alice".to_string(),
    email: "alice@example.com".to_string(),
    scores: vec![95, 87, 92, 88],
    preferences: BTreeMap::from([
        ("theme".to_string(), "dark".to_string()),
        ("language".to_string(), "en".to_string()),
    ]),
    is_active: true,
};

// Serialize to row format
let row_data = to_row(&profile);

// Zero-copy deserialization - no object allocation!
let row = from_row::<UserProfile>(&row_data);

// Access fields directly from binary data
assert_eq!(row.id(), 12345);
assert_eq!(row.username(), "alice");
assert_eq!(row.email(), "alice@example.com");
assert_eq!(row.is_active(), true);

// Access collections efficiently
let scores = row.scores();
assert_eq!(scores.size(), 4);
assert_eq!(scores.get(0), 95);
assert_eq!(scores.get(1), 87);

let prefs = row.preferences();
assert_eq!(prefs.keys().size(), 2);
assert_eq!(prefs.keys().get(0), "language");
assert_eq!(prefs.values().get(0), "en");
```

## How It Works

- Fields are encoded in a binary row with fixed offsets for primitives
- Variable-length data (strings, collections) stored with offset pointers
- Null bitmap tracks which fields are present
- Nested structures supported through recursive row encoding

## Performance Comparison

| Operation            | Object Format                 | Row Format                      |
| -------------------- | ----------------------------- | ------------------------------- |
| Full deserialization | Allocates all objects         | Zero allocation                 |
| Single field access  | Full deserialization required | Direct offset read              |
| Memory usage         | Full object graph in memory   | Only accessed fields in memory  |
| Suitable for         | Small objects, full access    | Large objects, selective access |

## ForyRow vs ForyStruct

| Feature         | `#[derive(ForyRow)]`  | `#[derive(ForyStruct)]`    |
| --------------- | --------------------- | -------------------------- |
| Deserialization | Zero-copy, lazy       | Full object reconstruction |
| Field access    | Direct from binary    | Normal struct access       |
| Memory usage    | Minimal               | Full object                |
| Best for        | Analytics, large data | General serialization      |

## Related Topics

- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Cross-Language](cross-language.md) - Row format across languages
- [Row Format Specification](https://fory.apache.org/docs/specification/row_format_spec) - Protocol details
