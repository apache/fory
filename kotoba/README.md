# Kotoba v1 Fory binding

Honest v1 only. This tree is a first-class sibling of `java/`, `python/`,
`go/`, and the other runtimes **on this fork**. It is not a replacement for
those implementations and it is not robotics-ready.

The module compiles with [Kotoba](https://github.com/kotoba-lang/kotoba) CLI
**0.7.2** to `wasm32-kotoba-v1` under the `i64-v1` value profile: no FFI, no
IEEE floats, no vector or externref ABI.

## What this implements

One vendored xlang record and the Fory header bitmap that precedes it.

| Byte | Value  | Meaning                                                |
| ---- | ------ | ------------------------------------------------------ |
| 0    | `0x01` | xlang bit set; oob clear; reserved bits 2-7 zero       |
| 1    | `0xFF` | `NOT_NULL_VALUE_FLAG` (root ref tracking is null-only) |
| 2    | `0x07` | `VARINT64` type id                                     |
| 3    | `0x54` | zigzag(`42`) as a one-byte unsigned varint             |

The fixture is `fixtures/varint64-42.hex`. `fory.kotoba` encodes that same
integer and decodes those same four bytes. It does not implement the rest of
the xlang serializer: no structs, collections, strings, metadata, references
beyond this null-only flag, or a general varint loop.

Layout source: `docs/specification/xlang_serialization_spec.md` (Fory header,
reference flags, type id table, signed varint64 / zigzag).

## What this is not

- Not a Java, Python, Go, Rust, or other Fory runtime.
- Not cross-language integration coverage.
- Not schema evolution, codegen, or row format.
- Not a claim that Kotoba can exchange arbitrary Fory payloads.

## Checks

`checks.sh` downloads Kotoba 0.7.2 (or uses `KOTOBA_BIN`), compiles
`fory.kotoba` to wasm, and requires a real compiler receipt:

- `value-profile` is `i64-v1`
- target is `wasm32-kotoba-v1`
- `value-abi` is `direct-v1`
- `wasm-features` is empty
- the artifact starts with wasm magic and carries `wasm32-kotoba-v1`

It then runs the module and requires runtime value `1`. The script fails if
the fixture, module comment, or byte literals drift. It does not invent a
pass.

```sh
bash kotoba/checks.sh
```

## Upstream

This binding lives on `kotoba-lang/fory`. It is not an Apache Fory release
surface. An eventual pull request to `apache/fory` may require an ASF ICLA.
This tree does not file that ICLA.

Fork operator: [awai.network](https://awai.network) / Ryo Awai.
