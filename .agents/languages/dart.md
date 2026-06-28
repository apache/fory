# Dart

Load this file when changing `dart/`.

## Rules

- Run Dart commands from within `dart/`.
- Use `pub`-based tooling and generate code before testing when the build runner outputs are involved.
- Dart code must analyze and compile without warnings. Treat analyzer warnings and compiler warnings as blockers, including warnings in generated Dart code.
- Do not design different user-facing generated-registration behavior for Dart VM and Flutter/no-mirrors. Cross-platform registration flow must stay consistent.
- Users must never be required to call private generated helpers such as `_ensure...` or `_install...`.
- If `Fory.register(...)` cannot be made self-sufficient across Dart platforms, use an explicit public wrapper API rather than splitting VM and Flutter behavior.
- Generated registration is ownership-based: generated types register through `Fory.register(...)`, manual or custom serializers use `Fory.registerSerializer(...)`, and generated descriptors/support helpers stay internal.
- Keep root numeric wrapper defaults separate from generated field metadata. Root wrapper resolution belongs in the builtin resolver, while annotations and generated metadata choose fixed, tagged, or declared-field encodings.
- Dart 64-bit carriers are optimized for each platform. Do not replace native extension-type wrappers with allocation-heavy classes or route web/native hot paths through `BigInt` unless the user approves a representation change.
- In `Buffer`, cursor, serializer, and generated-code hot paths, prefer direct byte/local integer operations and conditional import/export files over callbacks, records, holder objects, wrapper round-trips, or runtime platform branches.
- Do not add parallel header-low/header-high slot caches or multi-slot recent caches in TypeMeta hot paths to chase benchmark gaps. Header-cache hits must use the concrete checked cache owner directly; if a hit hint is needed, cache one TypeInfo/TypeMeta object and compare the validated header identity on that object, not separate low/high header fields or benchmark-pattern state.
- If Dart TypeMeta cache ownership changes, keep the invariant in a source comment near the hit path: a checked metadata-cache hit skips the body and must not grow low-bit sentinels, accepted-header fields, parallel header slots, or benchmark-pattern state.
- Dart expected-type TypeDef reads should compare the expected `TypeInfo` object's cached local TypeDef header before consulting the parsed-metadata map. A match is a direct local-schema hit: skip the remote body, add the expected type to the per-read shared type table, and do not publish to `ParsedTypeMetaCache`, record a remote schema version, or parse/hash the body.
- Dart local TypeDef construction is a registration-time two-stage process: first record the current `TypeInfo`, then eagerly rebuild local TypeDefs and struct serializers for all registered user types from the complete current registry. Do not move local TypeDef construction back into read/write hot paths or cache-miss workarounds; late registrations must refresh earlier TypeDefs on the registration cold path.
- Codegen must support private fields through same-library `part` generation. If generated file naming changes from `*.fory.dart`, update builder config, source `part` directives, analysis exclusions, docs, CI snippets, and stale artifacts together.
- Keep generated Dart outputs (`*.fory.dart`) and Dart `pubspec.lock` files untracked in this repo.
- For generated numeric or xlang changes, test root values and generated required/nullable fields across schema-consistent and compatible serializers, metadata type IDs, rejection paths, and every affected encoding mode.
- Compatible scalar conversion is immediate-field-only. Recursive compatible schema comparison for list elements, typed-array elements, map keys, and map values must reject scalar mismatches instead of applying top-level scalar conversion.
- Generated compatible struct reads must consume per-remote-field read descriptors built before field dispatch. Exact doubled cases read directly from local field metadata and must not receive remote compatible metadata; compatible scalar cases use preclassified scalar read descriptors instead of layout-wide scalar source arrays or hot schema/type-pair eligibility helpers.
- Generated struct serializers should use serializer-owned field descriptors for runtime resolver decisions and emit direct field-specific write/read code for static schemas. Do not route generated hot writes through generic field-info value helpers such as `writeGeneratedStructFieldInfoValue`.
- Dart xlang or runtime ownership changes need local Dart package tests plus the Java-driven `DartXlangTest`; package-only smoke tests are not enough.
- When claiming non-VM Dart support, prove a relevant non-VM compile path such as `dart compile js` against active runtime or example code.
- Generated Dart gRPC service companions (`<stem>_grpc.dart`) are compiler-owned files that depend on the application-provided `grpc` package, not `dart/packages/fory`. Keep gRPC dependencies out of the Fory Dart runtime package.
- Dart generated schema modules (`<Stem>ForyModule`) are the source-file owners and own a ready `Fory` runtime: `getFory()` initializes a default runtime and registers the schema's types on first use, so generated gRPC companions never require a manual `install(...)`; `install(customFory)` stays optional injection. Keep `getFory()` ready by construction, and do not introduce package-derived aliases or duplicate serializer registration paths.

## Commands

```bash
# Generate code
dart run build_runner build

# Run tests
dart test

# Analyze and apply fixes
dart analyze
dart fix --dry-run
dart fix --apply
```
