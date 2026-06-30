# JavaScript And TypeScript

Load this file when changing `javascript/`.

## Rules

- Run JavaScript and TypeScript commands from within `javascript/`.
- This implementation uses npm or yarn for package management.
- IDL Jest tests should stay local to generated-code construction and local `Fory` serialize/deserialize assertions. Java-driven peer orchestration belongs in the existing integration harness.
- Language peer entrypoints under `integration_tests/idl_tests` should mirror existing peers and validate semantic equality, not just deserialize and reserialize bytes.
- Preserve generated serializer hot paths that bind writer, reader, ref, resolver, and metadata locals in outer closures; do not replace them with per-call context lookups without a measured reason.
- Do not add parallel header-low/header-high slot caches in TypeMeta hot paths to chase benchmark gaps. Header-cache hits must use the concrete checked cache owner directly; if a small hit hint is needed, cache TypeMeta objects themselves and compare `TypeMeta.headerHash`, not separate low/high header fields or benchmark-pattern state.
- JavaScript TypeMeta header cache hits should compare the 52-bit TypeMeta header hash directly. The hash is precise in JS `Number` and already includes the low header bits as hash input; do not add extra low-bit fields, sentinel state, nullable accepted headers, or parallel slot arrays around it.
- Runtime value carriers such as decimal or reduced-precision numeric types belong under the core `types/` ownership boundary, with imports, exports, and codegen externals updated together.
- Keep `TypeInfo` as schema metadata. Compatibility-sensitive decisions belong on `TypeResolver` or explicit operations, not as retained resolver state on metadata objects.
- Normalize optional boolean config values at config construction; do not carry `null` through runtime paths when it means `false`.
- JavaScript root deserialization container memory budgeting belongs to `ReadContext`.
  `maxContainerMemoryBytes` uses `-1` auto, positive explicit limits, and known
  `Uint8Array` root length as `inputBytes * 8 + 64 KiB`. `ReadContext` may expose only raw
  byte reservation and generic counted-byte arithmetic; generated and dynamic
  list/set/map readers must reserve before allocation while preserving existing
  byte checks. Lists/sets/object arrays charge 4-byte reference slots, maps charge
  two 4-byte references per entry, and empty containers with no backing storage
  normally charge zero. Keep dedicated string, binary, and dense typed-array
  owners out of this budget; compatible list-to-typed-array reads must charge
  typed inline storage.
- Regenerated compatible read serializers are remote-schema-specific. After classification marks a field as direct, compatible scalar, or skip, generated JavaScript should emit straight-line remote-field-order code. Do not add an outer matched-id switch unless the current regenerated shape cannot preserve those semantics.
- Compatible scalar codegen must decide the exact remote/local scalar pair before emitting source. Generate the concrete `reader.readXxx()` call plus inline trivial conversions such as boolean-to-string or numeric widening, and keep helpers only for semantic validation such as range checks, exactness checks, decimal parsing/formatting, and string-to-bool. Do not call a generic hot-path converter that redispatches on `remoteTypeId`, `localTypeId`, field descriptors, or field names.
- Compatible scalar conversion is immediate-field-only. Recursive schema comparison for collection elements, array elements, map keys, and map values must reject scalar mismatches instead of applying the top-level scalar conversion matrix.

## Commands

```bash
# Install dependencies
npm install

# Run tests
node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit

# Check TypeScript formatting and lint rules
npm run format-check

# Format TypeScript, then apply ESLint fixes
npm run format
```
