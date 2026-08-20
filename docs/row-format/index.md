---
title: Introduction
sidebar_position: 0
id: index
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

Row Format is a cache-friendly binary format for efficient random access and partial
deserialization. Unlike object graph serialization, it lets readers access individual fields
without reconstructing the complete object.

Row Format is intended only for trusted analytical data, including memory-mapped data, selective
field access, and data pipelines. Use [Object Serialization](../object-serialization/index.md) when
the application needs general object graphs, shared or circular references, or complete object
reconstruction as its primary access pattern.

## Choose a Layout

| Layout       | Language support        | Compatibility                    |
| ------------ | ----------------------- | -------------------------------- |
| Standard Row | Java, Python, C++, Rust | Shared Standard Row layout       |
| Compact Row  | Java                    | Java-only, space-oriented layout |

## Standard Row

Standard Row is the interoperable layout for Java, Python, C++, and Rust.

### Features

- **Zero-copy random access**: Read selected fields directly from encoded data.
- **Partial deserialization**: Reconstruct only the values an application needs.
- **Cross-language compatibility**: Share Standard Row bytes between Java, Python, C++, and Rust.
- **Apache Arrow integration**: Convert rows to Arrow data in Java and Python.

### Layout

Standard Row stores fixed-width values inline and variable-width values by offset and size. Rows,
arrays, and maps use a schema to resolve field positions and element types. The normative byte
layout, alignment rules, type table, and endianness are defined by the
[Row Format specification](../specification/row_format_spec.md).

### Language Support

| Language | Standard Row compatibility | Language guide      | Additional integration                                 |
| -------- | -------------------------- | ------------------- | ------------------------------------------------------ |
| Java     | Compatible                 | [Java](java.md)     | Arrow conversion; interface and extension-type mapping |
| Python   | Compatible                 | [Python](python.md) | PyArrow schema and table conversion                    |
| C++      | Compatible                 | [C++](cpp.md)       | Native row readers and writers                         |
| Rust     | Compatible                 | [Rust](rust.md)     | Borrowed struct, array, and map views                  |

Use the language guides for installation, schema construction, encoding, random access, partial
reads, and language-specific integrations.

## Compact Row

Compact Row is a Java-only row encoding that reduces fixed-slot and null-bitmap overhead. It is not
wire-compatible with Standard Row.

### Create a Compact Encoder

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

### Layout Tradeoffs

- Fixed-size fields use their natural widths instead of eight-byte Standard Row slots.
- Fields are sorted by alignment to reduce padding.
- The null bitmap is omitted when no field is nullable.
- Fixed-size nested structs can be stored inline.

Choose Compact Row only when every reader is Java and the space reduction justifies the
Java-specific layout. Use Standard Row for Java, Python, C++, and Rust interchange.

See the [Row Format specification](../specification/row_format_spec.md) for the exact Standard and
Compact binary layouts.
