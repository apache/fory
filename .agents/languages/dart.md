# Dart

Load this file when changing `dart/`.

## Rules

- Run Dart commands from within `dart/`.
- Use `pub`-based tooling and generate code before testing when the build runner outputs are involved.
- Do not design different user-facing generated-registration behavior for Dart VM and Flutter/no-mirrors. Cross-platform registration flow must stay consistent.
- Users must never be required to call private generated helpers such as `_ensure...` or `_install...`.
- If `Fory.register(...)` cannot be made self-sufficient across Dart platforms, use an explicit public wrapper API rather than splitting VM and Flutter behavior.
- Generated registration is ownership-based: generated types register through `Fory.register(...)`, manual or custom serializers use `Fory.registerSerializer(...)`, and generated descriptors/support helpers stay internal.
- Keep root numeric wrapper defaults separate from generated field metadata. Root wrapper resolution belongs in the builtin resolver, while annotations and generated metadata choose fixed, tagged, or declared-field encodings.
- Dart 64-bit carriers are optimized for each platform. Do not replace native extension-type wrappers with allocation-heavy classes or route web/native hot paths through `BigInt` unless the user approves a representation change.
- In `Buffer`, cursor, serializer, and generated-code hot paths, prefer direct byte/local integer operations and conditional import/export files over callbacks, records, holder objects, wrapper round-trips, or runtime platform branches.
- Codegen must support private fields through same-library `part` generation. If generated file naming changes from `*.fory.dart`, update builder config, source `part` directives, analysis exclusions, docs, CI snippets, and stale artifacts together.
- Keep generated Dart outputs (`*.fory.dart`) and Dart `pubspec.lock` files untracked in this repo.
- For generated numeric or xlang changes, test root values and generated required/nullable fields across schema-consistent and compatible serializers, metadata type IDs, rejection paths, and every affected encoding mode.
- Dart xlang or runtime ownership changes need local Dart package tests plus the Java-driven `DartXlangTest`; package-only smoke tests are not enough.
- When claiming non-VM Dart support, prove a relevant non-VM compile path such as `dart compile js` against active runtime or example code.

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
