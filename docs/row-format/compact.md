---
title: Compact Row
sidebar_position: 2
id: compact
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

Compact Row is a Java-only row encoding that reduces fixed-slot and null-bitmap overhead. It is not
wire-compatible with Standard Row.

## Create a compact encoder

```java
RowEncoder<MyBean> encoder =
    Encoders.buildBeanCodec(MyBean.class)
        .compactEncoding()
        .build()
        .get();

BinaryRow row = encoder.toRow(value);
MyBean decoded = encoder.fromRow(row);
```

Reuse the encoder within one thread. Create separate encoders for concurrent threads.

## Layout tradeoffs

- Fixed-size fields use their natural widths instead of eight-byte Standard Row slots.
- Fields are sorted by alignment to reduce padding.
- The null bitmap is omitted when no field is nullable.
- Fixed-size nested structs can be stored inline.

Choose Compact Row only when every reader is Java and the space reduction justifies the
Java-specific layout. Use [Standard Row](standard.md) for Java/Python/C++/Rust interchange.

See the [Row Format specification](../specification/row_format_spec.md) for the exact binary layout.
