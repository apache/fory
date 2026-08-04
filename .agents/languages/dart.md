# Dart

Load this file when changing `dart/`.

## Rules

- Run Dart commands from within `dart/`.
- Use `pub`-based tooling and generate code before testing when the build runner outputs are involved.
- Dart code must analyze and compile without warnings. Treat analyzer warnings and compiler warnings as blockers, including warnings in generated Dart code.
- Do not design different user-facing generated-registration behavior for Dart VM and Flutter/no-mirrors. Cross-platform registration flow must stay consistent.
- Users must never be required to call private generated helpers such as `_ensure...` or `_install...`.
- If `Fory.register(...)` cannot be made self-sufficient across Dart platforms, use an explicit public wrapper API rather than splitting VM and Flutter behavior.
- Generated registration is ownership-based: generated types register through `Fory.register(...)`, custom serializers use `Fory.registerSerializer(...)`, and generated descriptors/support helpers stay internal.
- Dart external-type serialization uses `ForyStruct.target` on an `abstract final`
  schema declaration. The declaration is compile-time input only; generated
  serializers, schemas, and module dispatch are parameterized and registered
  by the target type. Keep ordinary and external structs in one analyzed model
  and emitter, with direct target getter/constructor/setter code and the
  existing resolver, field, carrier, reference, and compatible-read paths. Do
  not add runtime declaration instances, callbacks, member lookup, target-copy
  objects, a second registration API, or a second carrier/root flow.
- Ordinary `ForyStruct` generation must discover every real instance storage
  field on the concrete superclass and applied-mixin chain before considering
  Dart accessibility. Enumerate each instantiated hierarchy layer's declared
  fields; do not use only the child `element.fields`, `allSupertypes`, member
  lookup, import visibility, or privacy as field discovery.
- Process each discovered ordinary field through one owner path: apply its
  declaration-owned `ForyField(ignore: true)`, then the concrete child's
  `ignoreInheritedPrivateFields` policy, substitute the concrete generic type,
  resolve direct or companion access, prove construction, then build the
  concrete child's one globally sorted flattened schema. The child policy
  omits every ancestor- or applied-mixin-declared private field, including
  same-library fields, but never child-declared private or inherited public
  fields. Never omit a remaining field because it is inaccessible, final,
  hidden, or unsupported; report a generation error.
- `ignoreInheritedPrivateFields` defaults to false, belongs only to an ordinary
  concrete schema owner, and is not inherited from ancestor annotations.
  Reject true on external and provider-only abstract, open-generic, or mixin
  declarations. Filtering must happen before substitution and access
  resolution, without changing complete hierarchy discovery.
- Public inherited fields and private inherited fields declared in the child's
  library use direct generated access without a parent annotation.
  Included cross-library private storage requires
  `ForyStruct(exposePrivateFields: true)` on a public hierarchy boundary in
  that field's declaring library. The companion emits only public typed static
  getter/setter access for its own library's private state and owns no schema,
  serializer, construction, registration, reference, or graph-memory state.
  Companion generation is independent of a consumer's omission policy. Each
  declaring library in a multi-library hierarchy must provide its own
  boundary. A consumer exposure annotation cannot authorize another library's
  field.
- An included ordinary `final` or `late final` field must receive its decoded
  value unchanged from the selected concrete-child constructor through
  element-proven field formals, super formals, redirects, or explicit
  initializer/super arguments. Names are not bindings. Do not post-set final
  state, invent a required constructor source after filtering, use reflection,
  or fall back when identity flow cannot be proven.
- The concrete ordinary child owns one flattened schema, serializer,
  constructor reconstruction, reference publication path, and graph-memory
  object charge. Included inherited reference metadata enters the existing
  recursive reference analysis once; omitted fields do not. Do not add
  reference state, change `ReadContext` or serializer reference-call contracts,
  or invoke a parent serializer for inheritance.
- Keep external target fields explicit. Ordinary hierarchy discovery and
  `exposePrivateFields` must never scan or expose an external target hierarchy,
  and `ignoreInheritedPrivateFields` is invalid with `target`.
- Across packages, generate and publish the provider library's `.fory.dart`
  part before building a consumer that uses its private-field access companion.
  A direct import or re-export must expose both the public boundary and
  companion.
- Constructor-based external structural serializers must reject every
  statically provable reference-tracked path back to the target, including
  paths through supported list, set, and map type arguments. Do not simulate
  early publication with placeholders or final-field patching.
- External object graph-memory formulas count all declaration fields plus
  concrete public instance fields visible on the target, superclass, and mixin
  storage paths. Ignored declaration fields are budget-only and must not enter
  target access, construction, metadata, or wire code. Compute this union during
  generation and emit one constant reservation.
- Keep root numeric wrapper defaults separate from generated field metadata. Root wrapper resolution belongs in the builtin resolver, while annotations and generated metadata choose fixed, tagged, or declared-field encodings.
- Dart 64-bit carriers are optimized for each platform. Do not replace native extension-type wrappers with allocation-heavy classes or route web/native hot paths through `BigInt` unless the user approves a representation change.
- In `Buffer`, cursor, serializer, and generated-code hot paths, prefer direct byte/local integer operations and conditional import/export files over callbacks, records, holder objects, wrapper round-trips, or runtime platform branches.
- Root deserialization graph memory budget state belongs to `ReadContext`;
  `maxGraphMemoryBytes` defaults to fixed `128 MiB`, positive explicit values override it, and
  explicit non-positive values are invalid at config creation. Do not derive the budget from
  `buffer.readableBytes`. `ReadContext` may expose only raw byte reservation; list, set, map, array,
  struct, and object formulas belong in serializer owners. Reserve Dart list/set/object-array reference
  slots plus nonzero owner self cost, map key/value slots plus nonzero owner
  self cost, compatible array-to-list materialization, and generated object reads before
  allocation. Compatible list-to-typed-array reads skip the dense primitive-array leaf owner while
  preserving byte checks. Object/struct owners reserve nonzero shallow self memory plus shallow field storage. Skip
  only dedicated string, binary, primitive scalar, `BoolList`, and typed-array
  dense owner paths with byte checks. Do not add stream bytes-read accounting,
  per-element accounting, extra hot-path allocations, or stale narrower-scope
  formulas.
  Treat the option as an approximate collection/map/array/struct/object gate, not an exact heap
  cap. Leaf values skipped by graph budgeting remain gated by unread input bytes.
- Do not add parallel header-low/header-high slot caches or multi-slot recent caches in TypeMeta hot paths to chase benchmark gaps. Header-cache hits must use the concrete checked cache owner directly; if a hit hint is needed, cache one TypeInfo/TypeMeta object and compare the validated header identity on that object, not separate low/high header fields or benchmark-pattern state.
- If Dart TypeMeta cache ownership changes, keep the invariant in a source comment near the hit path: a checked metadata-cache hit skips the body and must not grow low-bit sentinels, accepted-header fields, parallel header slots, or benchmark-pattern state.
- Dart expected-type TypeDef reads should compare the expected `TypeInfo` object's cached local TypeDef header before consulting the parsed-metadata map. A match is a direct local-schema hit: skip the remote body, add the expected type to the per-read shared type table, and do not publish to `ParsedTypeMetaCache`, record a remote schema version, or parse/hash the body.
- Dart local TypeDef construction is registration-owned: record registrations
  and finalize their dependent TypeDefs and struct serializers before the first
  root read or write. The first `serialize`, `serializeTo`, `serializeBuiltin`,
  `serializeBuiltinTo`, `deserialize`, or `deserializeFrom` call permanently
  freezes that `Fory` instance's resolver;
  later type or serializer registration must fail before mutation. Do not keep
  late-registration cache invalidation, generated-field refresh, parsed-metadata
  eviction, schema-counter rollback, or serializer rebinding paths. Do not move
  local TypeDef construction into read/write hot paths or cache-miss workarounds.
  Generated registration must enforce this boundary before updating
  `GeneratedTypeCatalog`; route catalog publication and resolver registration
  through one Fory-owned internal operation instead of exposing a check-only
  registry-state method.
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
