# Dart

Load this file when changing `dart/`.

## Rules

- Run Dart commands from within `dart/`.
- Use `pub`-based tooling and generate code before testing when the build runner outputs are involved.

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
