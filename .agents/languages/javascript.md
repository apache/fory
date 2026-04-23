# JavaScript And TypeScript

Load this file when changing `javascript/`.

## Rules

- Run JavaScript and TypeScript commands from within `javascript/`.
- This implementation uses npm or yarn for package management.
- IDL Jest tests should stay local to generated-code construction and local `Fory` serialize/deserialize assertions. Java-driven peer orchestration belongs in the existing integration harness.
- Language peer entrypoints under `integration_tests/idl_tests` should mirror existing peers and validate semantic equality, not just deserialize and reserialize bytes.
- Preserve generated serializer hot paths that bind writer, reader, ref, resolver, and metadata locals in outer closures; do not replace them with per-call context lookups without a measured reason.
- Runtime value carriers such as decimal or reduced-precision numeric types belong under the core `types/` ownership boundary, with imports, exports, and codegen externals updated together.
- Keep `TypeInfo` as schema metadata. Compatibility-sensitive decisions belong on `TypeResolver` or explicit operations, not as retained resolver state on metadata objects.
- Normalize optional boolean config values at config construction; do not carry `null` through runtime paths when it means `false`.

## Commands

```bash
# Install dependencies
npm install

# Run tests
node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit

# Lint TypeScript
git ls-files -- '*.ts' | xargs -P 5 node ./node_modules/.bin/eslint
```
