---
title: Xlang Implementation Guide
sidebar_position: 10
id: xlang_implementation_guide
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

## Implementation guidelines

### How to reduce memory read/write code

- Try to merge multiple bytes into an int/long write before writing to reduce memory IO and bound check cost.
- Read multiple bytes as an int/long, then split into multiple bytes to reduce memory IO and bound check cost.
- Try to use one varint/long to write flags and length together to save one byte cost and reduce memory io.
- Condition branches are less expensive compared to memory IO cost unless there are too many branches.

### Fast deserialization for static languages without runtime codegen support

For type evolution, the serializer will encode the type meta into the serialized data. The deserializer will compare
this meta with class meta in the current process, and use the diff to determine how to deserialize the data.

For java/javascript/python, we can use the diff to generate serializer code at runtime and load it as class/function for
deserialization. In this way, the type evolution will be as fast as type consist mode.

For C++/Rust, we can't generate the serializer code at runtime. So we need to generate the code at compile-time using
meta programming. But at that time, we don't know the type schema in other processes, so we can't generate the
serializer code for such inconsistent types. We may need to generate the code which has a loop and compare field name
one by one to decide whether to deserialize and assign the field or skip the field value.

One fast way is that we can optimize the string comparison into `jump` instructions:

- Assume the current type has `n` fields, and the peer type has `n1` fields.
- Generate an auto growing `field id` from `0` for every sorted field in the current type at the compile time.
- Compare the received type meta with current type, generate same id if the field name is same, otherwise generate an
  auto growing id starting from `n`, cache this meta at runtime.
- Iterate the fields of received type meta, use a `switch` to compare the `field id` to deserialize data
  and `assign/skip` field value. **Continuous** field id will be optimized into `jump` in `switch` block, so it will
  very fast.

Here is an example, suppose process A has a class `Foo` with version 1 defined as `Foo1`, process B has a class `Foo`
with version 2 defined as `Foo2`:

```c++
// class Foo with version 1
class Foo1 {
  int32_t v1; // id 0
  std::string v2; // id 1
};
// class Foo with version 2
class Foo2 {
  // id 0, but will have id 2 in process A
  bool v0;
  // id 1, but will have id 0 in process A
  int32_t v1;
  // id 2, but will have id 3 in process A
  int64_t long_value;
  // id 3, but will have id 1 in process A
  std::string v2;
  // id 4, but will have id 4 in process A
  std::vector<std::string> list;
};
```

When process A received serialized `Foo2` from process B, here is how it deserialize the data:

```c++
Foo1 foo1 = ...;
const std::vector<fory::FieldInfo> &field_infos = type_meta.field_infos;
for (const auto &field_info : field_infos) {
  switch (field_info.field_id) {
    case 0:
      foo1.v1 = buffer.read_varint32();
      break;
    case 1:
      foo1.v2 = fory.read_string();
      break;
    default:
      fory.skip_data(field_info);
  }
}
```

## Implementation Checklist for New Languages

This section provides a step-by-step guide for implementing Fory xlang serialization in a new language.

### Phase 1: Core Infrastructure

1. **Buffer Implementation**
   - [ ] Create a byte buffer with read/write cursor tracking
   - [ ] Implement little-endian byte order for all multi-byte writes
   - [ ] Implement `write_int8`, `write_int16`, `write_int32`, `write_int64`
   - [ ] Implement `write_float32`, `write_float64`
   - [ ] Implement `read_*` counterparts for all write methods
   - [ ] Implement buffer growth strategy (e.g., doubling)

2. **Varint Encoding**
   - [ ] Implement `write_varuint32` / `read_varuint32`
   - [ ] Implement `write_varint32` / `read_varint32` (with ZigZag)
   - [ ] Implement `write_varuint64` / `read_varuint64`
   - [ ] Implement `write_varint64` / `read_varint64` (with ZigZag)
   - [ ] Implement `write_varuint36_small` / `read_varuint36_small` (for strings)
   - [ ] Optionally implement Hybrid encoding (TAGGED_INT64/TAGGED_UINT64) for int64

3. **Header Handling**
   - [ ] Write/read bitmap flags (null, xlang, oob)

### Phase 2: Basic Type Serializers

4. **Primitive Types**
   - [ ] bool (1 byte: 0 or 1)
   - [ ] int8, int16, int32, int64 (little endian)
   - [ ] float32, float64 (IEEE 754, little endian)

5. **String Serialization**
   - [ ] Implement string header: `(byte_length << 2) | encoding`
   - [ ] Support UTF-8 encoding (required for xlang)
   - [ ] Optionally support LATIN1 and UTF-16

6. **Temporal Types**
   - [ ] Duration (seconds + nanoseconds)
   - [ ] Timestamp (seconds + nanoseconds since epoch)
   - [ ] Date (days since epoch)

7. **Reference Tracking**
   - [ ] Implement write-side object tracking (object → ref_id map)
   - [ ] Implement read-side object tracking (ref_id → object list)
   - [ ] Handle all four reference flags: NULL(-3), REF(-2), NOT_NULL(-1), REF_VALUE(0)
   - [ ] Support disabling reference tracking per-type or globally

### Phase 3: Collection Types

8. **List/Array Serialization**
   - [ ] Write length as varuint32
   - [ ] Write elements header byte
   - [ ] Handle homogeneous vs heterogeneous elements
   - [ ] Handle null elements

9. **Map Serialization**
   - [ ] Write total size as varuint32
   - [ ] Implement chunk-based format (max 255 pairs per chunk)
   - [ ] Write KV header byte per chunk
   - [ ] Handle key and value type variations

10. **Set Serialization**
    - [ ] Same format as List (reuse implementation)

### Phase 4: Meta String Encoding

Meta strings are required for enum and struct serialization (encoding field names, type names, namespaces).

11. **Meta String Compression**
    - [ ] Implement LOWER_SPECIAL encoding (5 bits/char)
    - [ ] Implement LOWER_UPPER_DIGIT_SPECIAL encoding (6 bits/char)
    - [ ] Implement FIRST_TO_LOWER_SPECIAL encoding
    - [ ] Implement ALL_TO_LOWER_SPECIAL encoding
    - [ ] Implement encoding selection algorithm
    - [ ] Implement meta string deduplication

### Phase 5: Enum Serialization

12. **Enum Serialization**
    - [ ] Write ordinal as varuint32
    - [ ] Support named enum (namespace + type name)

### Phase 6: Struct Serialization

13. **Type Registration**
    - [ ] Support registration by numeric ID
    - [ ] Support registration by namespace + type name
    - [ ] Maintain type → serializer mapping
    - [ ] Generate type IDs: write internal type ID, then `user_type_id` as varuint32

14. **Field Ordering**
    - [ ] Implement the spec-defined grouping and ordering (primitive/boxed/built-in, collections/maps, other)
    - [ ] Use a stable comparator within each group (type ID and name)
    - [ ] Use tag ID or snake_case field name as field identifier for fingerprints

15. **Schema Consistent Mode**
    - [ ] If class-version check is enabled, compute schema hash from field identifiers
    - [ ] Write 4-byte schema hash before fields
    - [ ] Serialize fields in Fory order

16. **Compatible/Meta Share Mode**
    - [ ] Implement shared TypeDef stream (inline new TypeDefs, index references)
    - [ ] Map fields by name or tag ID, skip unknown fields
    - [ ] Apply nullable/ref flags from TypeDef metadata

### Phase 7: Other types

17. **Binary/Array Types**

- [ ] Primitive arrays (direct buffer copy)
- [ ] Multi-dimensional arrays as nested lists (no tensor encoding)

### Testing Strategy

18. **Cross-Language Compatibility Tests**
    - [ ] Serialize in new language, deserialize in Java/Python
    - [ ] Serialize in Java/Python, deserialize in new language
    - [ ] Test all primitive types
    - [ ] Test strings with various encodings
    - [ ] Test collections (empty, single, multiple elements)
    - [ ] Test maps with various key/value types
    - [ ] Test nested structs
    - [ ] Test circular references (if supported)

## Language-Specific Implementation Notes

### Java

- Uses runtime code generation (JIT) for maximum performance
- Supports all reference tracking modes
- Uses internal String coder for encoding selection
- Thread-safe via `ThreadSafeFory` wrapper

### Python

- Two modes: Pure Python (debugging) and Cython (performance)
- Uses `id(obj)` for reference tracking
- Latin1/UTF-16/UTF-8 encoding for all strings in xlang mode
- `dataclass` support via code generation

### C++

- Compile-time reflection via macros (`FORY_STRUCT`)
- Template meta programming for type dispatch and serializer selection
- Uses `std::shared_ptr` for reference tracking
- Compile-time field ordering
- No runtime code generation

### Rust

- Derive macros for automatic serialization (`#[derive(ForyObject)]`)
- Uses `Rc<T>` / `Arc<T>` for reference tracking
- Thread-local context caching for performance
- Compile-time field ordering

### Go

- Reflection-based and codegen-based modes
- Struct tags for field annotations
- Interface types for polymorphism
