## 1.8.0-dev

- Start the next development cycle after the 1.7.1 release.

## 1.7.1

- Release Apache Fory Dart 1.7.1.

## 1.7.0

- Release Apache Fory Dart 1.7.0.

## 1.6.0

- Release Apache Fory Dart 1.6.0.

## 1.5.0

- Release Apache Fory Dart 1.5.0.
- Add generated external-type serialization with direct target construction
  and existing registration, carrier, reference, and schema-evolution support.
- Replace `ForyField.skip` with `ForyField.ignore`, remove `skip` from container
  field annotations, and include ignored and discoverable external target
  fields in graph-memory accounting.
- Flatten ordinary `ForyStruct` superclass and applied-mixin storage into the
  concrete child's generated schema. Public and same-library private fields use
  direct access; cross-library private fields require
  `exposePrivateFields: true` on a public boundary in each declaring library.
- Reject hidden, inaccessible, unsupported, or unconstructable included
  inherited storage instead of silently omitting it. Included `final` and
  `late final` fields now require a proven identity-preserving constructor
  path.
- Add `ForyStruct.ignoreInheritedPrivateFields`, defaulting to `false`, so a
  concrete ordinary child can explicitly omit all private superclass and
  applied-mixin storage from its own flattened schema. Child-declared private
  fields and inherited public fields remain included; setting the option to
  `true` on external and provider-only declarations is rejected.
- This inheritance correction is a breaking generated-schema change. Regenerate
  all affected `.fory.dart` files; fixed-schema payloads written when inherited
  fields were omitted are not supported by a compatibility reader. External
  target declarations remain explicit, and inheritance does not change the
  runtime reference protocol.

## 1.4.0

- Release Apache Fory Dart 1.4.0.

## 1.3.0

- Release Apache Fory Dart 1.3.0.

## 1.2.0

- Release Apache Fory Dart 1.2.0.

## 1.1.0

- Refresh pub.dev package metadata and documentation links.
- Update code generation dependencies for current stable Dart tooling.

## 1.0.0

- First stable Apache Fory Dart package release.
- Provide generated serializers, schema evolution support, and xlang object serialization for Dart applications.

## 0.17.0-dev

- Dart runtime implementation around `Fory`, `Buffer`, `WriteContext`, `ReadContext`, and `TypeResolver`.
