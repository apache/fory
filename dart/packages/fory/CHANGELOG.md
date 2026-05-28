## 1.1.0

- Rename generated registration owner classes from `*Fory` to `*ForyModule`.
- Update Dart IDL compiler integration to use schema-file-scoped module owners and install imported modules transitively.
- Fix generated metadata for static union fields so Dart compiler output uses the union field type correctly.
- Refresh pub.dev package metadata and documentation links.
- Update code generation dependencies for current stable Dart tooling.

## 1.0.0

- First stable Apache Fory Dart package release.
- Provide generated serializers, schema evolution support, and xlang object serialization for Dart applications.

## 0.17.0-dev

- Dart runtime implementation around `Fory`, `Buffer`, `WriteContext`, `ReadContext`, and `TypeResolver`.
