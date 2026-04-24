# Apache Fory Ruby (prototype)

This folder is a small, work-in-progress ruby prototype for Apache Fory’s xlang wire format.

It’s not a usable Ruby runtime yet. The goal is simply to show early progress on the lowest-level building blocks (buffer + integer/string header encodings) and to keep those pieces covered by tests.

## What’s included

- A growable byte `Buffer` with separate read/write cursors
- Encoding helpers needed by the xlang spec:
  - little-endian fixed-width ints
  - `varuint64` / `varint64` (ZigZag)
  - `TAGGED_INT64` (the xlang “signed hybrid int64”)
  - `VarUint36Small` for string headers
  - UTF-8 string header: `(byte_length << 2) | encoding_type`
- Tests that cover:
  - round-trips
  - truncated/malformed inputs
  - a few golden-byte fixtures (assert exact encoded bytes for selected values)

## Spec notes

This prototype follows the in-repo reference spec:
- `docs/specification/xlang_serialization_spec.md`

A couple of details that are easy to get wrong (and are explicitly tested here):

- **VarUint36Small**: if `< 0x80`, it’s a single byte; otherwise it falls back to standard `varuint64`.
- **TAGGED_INT64**:
  - 4-byte form for values in `[-2^30, 2^30-1]`: write `int32_le(value << 1)`
  - 9-byte form otherwise: write `0x01` followed by `int64_le(value)`
- **String encoding ids** used in headers: LATIN1=0, UTF16=1, UTF8=2.

If another runtime turns out to be the canonical reference for a particular corner case, this prototype should be adjusted to match it.

## What’s intentionally not included

- type system / serializers
- reference tracking
- TypeDef / meta share
- collections, structs, enums
- cross-language interop fixtures (likely a later step)

## Run tests

From the repo root:

- `cd ruby && ruby -Ilib:test test/**/*_test.rb`

This keeps the prototype lightweight (no gem packaging step needed yet).
