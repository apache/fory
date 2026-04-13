# Dart

Load this file when changing `dart/`.

## Rules

- Run Dart commands from within `dart/`.
- Use `pub`-based tooling and generate code before testing when the build runner outputs are involved.
- Do not design different user-facing generated-registration behavior for Dart VM and Flutter/no-mirrors. Cross-platform registration flow must stay consistent.
- Users must never be required to call private generated helpers such as `_ensure...` or `_install...`.
- If `Fory.register(...)` cannot be made self-sufficient across Dart platforms, use an explicit public wrapper API rather than splitting VM and Flutter behavior.

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
