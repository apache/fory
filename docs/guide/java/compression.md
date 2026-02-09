---
title: Compression
sidebar_position: 6
id: compression
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

This page covers compression options for reducing serialized data size.

## Integer Compression

`ForyBuilder#withIntCompressed`/`ForyBuilder#withLongCompressed` can be used to compress int/long for smaller size. Normally compressing int is enough.

Both compression options are enabled by default. If the serialized size is not important (for example, you use FlatBuffers for serialization before, which doesn't compress anything), then you should disable compression. If your data are all numbers, the compression may bring 80% performance regression.

### Int Compression

For int compression, Fory uses 1~5 bytes for encoding. The first bit in every byte indicates whether there is a next byte. If the first bit is set, then the next byte will be read until the first bit of the next byte is unset.

### Long Compression

For long compression, Fory supports two encodings:

#### SLI (Small Long as Int) Encoding (Default)

- If long is in `[-1073741824, 1073741823]`, encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`
- Otherwise write as 9 bytes: `| 0b1 | little-endian 8bytes long |`

#### PVL (Progressive Variable-length Long) Encoding

- First bit in every byte indicates whether there is a next byte. If first bit is set, then next byte will be read until first bit of next byte is unset.
- Negative numbers will be converted to positive numbers by `(v << 1) ^ (v >> 63)` to reduce cost of small negative numbers.

If a number is of `long` type but can't be represented by smaller bytes mostly, the compression won't get good enough results—not worthy compared to performance cost. Maybe you should try to disable long compression if you find it didn't bring much space savings.

## Array Compression

Fory supports SIMD-accelerated compression for primitive arrays (`int[]` and `long[]`) when array values can fit in smaller data types. This feature is available on Java 16+ and uses the Vector API for optimal performance.

### How Array Compression Works

Array compression analyzes arrays to determine if values can be stored using fewer bytes:

- **`int[]` → `byte[]`**: When all values are in range [-128, 127] (75% size reduction)
- **`int[]` → `short[]`**: When all values are in range [-32768, 32767] (50% size reduction)
- **`long[]` → `int[]`**: When all values fit in integer range (50% size reduction)

### Configuration and Registration

To enable array compression, you must explicitly register the serializers:

```java
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  // Enable int array compression
  .withIntArrayCompressed(true)
  // Enable long array compression
  .withLongArrayCompressed(true)
  .build();

// You must explicitly register compressed array serializers
CompressedArraySerializers.registerSerializers(fory);
```

**Note**: The `fory-simd` module must be included in your dependencies for compressed array serializers to be available.

### Maven Dependency

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-simd</artifactId>
  <version>0.15.0</version>
</dependency>
```

## String Compression

String compression can be enabled via `ForyBuilder#withStringCompressed(true)`. This is disabled by default.

## Configuration Summary

| Option              | Description                                   | Default |
| ------------------- | --------------------------------------------- | ------- |
| `compressInt`       | Enable int compression                        | `true`  |
| `compressLong`      | Enable long compression                       | `true`  |
| `compressIntArray`  | Enable SIMD int array compression (Java 16+)  | `false` |
| `compressLongArray` | Enable SIMD long array compression (Java 16+) | `false` |
| `compressString`    | Enable string compression                     | `false` |

## Performance Considerations

1. **Disable compression for numeric-heavy data**: If your data is mostly numbers, compression overhead may not be worth it
2. **Array compression requires Java 16+**: Uses Vector API for SIMD acceleration
3. **Long compression may not help large values**: If most longs can't fit in smaller representations, disable it
4. **String compression has overhead**: Only enable if strings are highly compressible

## Example Configuration

```java
// For mostly numeric data - disable compression
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withIntCompressed(false)
  .withLongCompressed(false)
  .build();

// For mixed data with arrays - enable array compression
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withIntCompressed(true)
  .withLongCompressed(true)
  .withIntArrayCompressed(true)
  .withLongArrayCompressed(true)
  .build();
CompressedArraySerializers.registerSerializers(fory);
```

## Related Topics

- [Configuration Options](configuration.md) - All ForyBuilder options
- [Advanced Features](advanced-features.md) - Memory management
