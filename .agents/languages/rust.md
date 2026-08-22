# Rust

Load this file when changing `rust/` or Rust xlang behavior.

## Rules

- Run all cargo commands from within `rust/`.
- Changes under `rust/` must pass `clippy` and tests.
- Rust code must compile without compiler or Clippy warnings. Treat warnings as blockers and keep `cargo clippy --all-targets --all-features -- -D warnings` passing.
- `check_string_read(false)` is a trusted-input-only performance option. It deliberately skips UTF-8 validation, so callers that enable it own the guarantee that serialized string bytes are valid UTF-8. Keep the default checked mode for untrusted input, and do not treat misuse of the unchecked option as a default-path deserialization vulnerability.
- Use `RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1` when debugging failing Rust tests.
- Add `-- --nocapture` when you need test output during debugging.
- Do not set `FORY_PANIC_ON_ERROR=1` when running the full Rust test suite, because some tests assert on error contents.
- Avoid cosmetic filesystem or module churn when logical module names and call sites are already stable.
- Operation contexts such as `ReadContext` and `WriteContext` should sit beside the runtime facade and aggregate resolver, buffer, and config state; they are not resolver-owned submodules.
- Runtime carriers belong in `types/`, and schema or type-hash helpers belong with metadata hashing rather than generic wire/type-id modules.
- Rust derive-generated runtime paths are owned by the selected runtime crate: normal downstream crates depend on `fory`, and `fory-derive` must resolve that facade with `proc-macro-crate` and emit through `fory::__private`; direct lower-level crates may resolve `fory-core`. Do not add raw crate-path string attributes such as `#[fory(crate = "...")]`.
- `fory-core` `macro_rules!` exports, including `register_trait_type!` and helpers, must use `$crate` for runtime paths so facade re-exports stay hygienic.
- `register_trait_type!` owns the visibility of its generated named serializers. With no
  visibility token they are private to the invocation module; explicit `$vis:vis` forms such as
  `register_trait_type!(pub Animal, ...)` and `register_trait_type!(pub sync Animal, ...)` export
  them. Thread that visibility through the existing generated serializer and codec items. Do not
  emit unconditional `pub` or `pub(crate)` items, add a second macro, or introduce a runtime
  registration path to solve private-trait visibility.
- Use "external-type serialization" for this feature, "external structural serializer" for a
  schema-derived serializer targeting another crate's type, "custom serializer" for custom
  behavior, and "carrier serializer" for Fory's recursive generic serializers. "Serializer
  provider" is acceptable only for the internal type-level role. Do not add a separate public marker,
  derive, attribute, registration concept, example suffix, heading, test concept, alternate feature
  name, or replacement abstraction for the same ownership role.
- Rust external-type serialization uses one serializer-provider model: the serializer provider owns
  value-level behavior and structural schema; `Serializer::Target` owns runtime identity, values,
  storage size, downcasts, and dynamic registration. `Serializer` must not expose `FieldType`,
  field compatibility, remote field metadata, field null/reference policy, declared-field generic
  state, or field encoding selection. The internal `Codec<T>` extends
  `Serializer<Target = T>` and owns those field-node concerns. Serializer providers and external
  structural serializers are never materialized. Changes to this surface must remain atomic rather
  than adding a partial or parallel path.
- Keep capacity hints on the same ownership boundary. `Serializer::reserved_space` estimates a
  value and must be forwarded unchanged by leaf adapters. The internal codec owns the field
  estimate, including field framing; generated fields and field-mode carrier bodies use that
  codec estimate, while roots and value-mode carrier bodies use the serializer estimate.
- Keep external selection node-local and explicit for static values: `with` selects a serializer at a
  direct struct or enum-variant field node, recursive list elements, map key/value nodes, or sparse
  zero-based heterogeneous tuple positions, while serializer-selected roots use the dedicated
  `serialize_with`, `serialize_to_with`, `deserialize_with`, and `deserialize_from_with` APIs. A
  root composition such as `Vec<third_party::User>` must use the Fory-owned
  `VecSerializer<UserSerializer>` carrier serializer; never infer a child serializer from the
  target type or registration. Provide semantic, recursively composable, zero-sized carrier
  serializer families for
  Option, Box/Rc/Arc/weak, RefCell, Mutex, Vec, VecDeque, LinkedList, HashSet, BTreeSet, BinaryHeap,
  fixed arrays, HashMap, BTreeMap, and tuple arities 1 through 22. Each serializer's target is its
  carrier over its child serializer targets. Do not add a user-declared structural root-container
  serializer, derive, or unit declaration.
- The audited static tuple family is `Tuple1Serializer<S0>` through
  `Tuple22Serializer<S0, ..., S21>`. Generate those carrier serializers and their matching
  arity-specific codecs from the tuple owner; do not add `Tuple0Serializer`, a tuple-serializer
  trait, serializer list,
  or runtime tuple descriptor. Unit `()` and `PhantomData<T>` have no serialized child and need no
  serializer composition. `Cell<T>` is not currently serialized; derive's Send/Sync name recognition
  is not codec support, so do not add `CellSerializer` or treat `Cell` as `RefCell`. Only Fory's
  `RcWeak`/`ArcWeak`, not standard-library weak pointers, are supported.
- Root carrier composition recursively composes serializers; field composition recursively
  composes codecs. A carrier implementation provides `Serializer` behavior when its children
  implement `Serializer` and `Codec` behavior only when its children implement `Codec`. Both
  layers reuse one body, allocation, insertion, and reference owner without requiring the same
  concrete generic tree; field codecs retain compatible-read dispatch in that same carrier
  implementation. Root carriers must not wrap children in
  `SerializerCodec`, request or synthesize `FieldType`, or construct field-codec trees. Root reads
  may carry only direct/value-`TypeInfo` state; remote `FieldType` state exists only in the field
  codec entrance. `SerializerCodec<S>` is
  only the leaf field adapter: it forwards value methods to `S` and implements leaf field metadata,
  envelopes, and compatible reads itself without calling a field hook on `S`. Do not add field
  metadata, field-compatible body reads, or declared-field generic state to `Serializer`, including
  through an equivalent bridge or serializer write parameter. Do not add a metadata-only carrier
  adapter that delegates its body to a whole-target serializer; every carrier-specific
  implementation owns both value and field behavior.
- Represent immutable value-serializer properties as the associated constants `IS_OPTIONAL`,
  `IS_POLYMORPHIC`, `IS_SHARED_REF`, `IS_WRAPPER`, and `REQUIRES_SCOPED_ACCESS`. `Codec` inherits
  them through `Serializer` and must not redeclare them. Keep `is_none(value)` and
  `dynamic_type_id(value)` as functions because they inspect a concrete value. Weak-target expiry
  is not Option absence. Transparent carriers propagate child constants only where their value
  semantics require it; `RefCell`, `Mutex`, and weak serializers require scoped access, while
  direct `Any` and application-trait serializers do not.
- Preserve one canonical primitive Vec/fixed-array selection for type ID, body, reserved space,
  derive metadata, and compatible reads. The private primitive-array/carrier owner derives the
  parent kind from the child's scalar `static_type_id()`, the exact Rust child target, and the
  carrier mode. Scalar serializers declare only scalar behavior and scalar wire IDs; neither
  `Serializer` nor `Codec` exposes a parent-array kind. Reuse the same private mapping to validate
  every unsafe bulk copy and to adapt compatible LIST/array fields. Canonical primitive children
  retain dense-array/BINARY encodings, including `u8` BINARY and the existing `isize`/`usize` array
  kinds; an external structural or custom child serializer targeting a primitive remains LIST
  because it does not expose that scalar wire ID. Rust 1.70 cannot select a codec type from an
  associated const, so one unified `VecCodec` and one unified `ArrayCodec` own both primitive and
  object bodies. The Vec carrier implementation has two compile-time schema consts.
  `STRUCTURAL_LIST` preserves
  unannotated and explicit `list(...)` generated Vec fields as LIST, including canonical primitive
  children; ordinary roots, `VecSerializer<S>`, `bytes`, and `array` disable it so they can consume
  the validated canonical child kind. `DENSE_ARRAY` represents only existing explicit
  `#[fory(array)]` syntax and maps canonical `u8` BINARY to `UINT8_ARRAY`; roots, carrier
  serializers, LIST fields, and `bytes` pass false. Thus unannotated `Vec<i32>` remains
  `LIST<VARINT32>`, an explicit fixed element remains `LIST<INT32>`, ordinary/serializer-selected
  primitive roots remain dense/BINARY, and `bytes` remains BINARY. Derive may validate the explicit
  primitive category, but it must not map Rust types to wire IDs or maintain an
  ordinary-composition classifier. Inline target and scalar-ID checks must fold after
  monomorphization; optimized hot paths may not retain a dynamic lookup or Rust `TypeId`
  comparison.
- `tuple(element(index = ..., ...))` is sparse and zero-based; unmentioned positions use ordinary
  serializers. Reject duplicate/out-of-range indexes and `index` outside tuple metadata. Root
  tuple serializers and annotated tuple field codecs use the same arity-specific tuple body
  implementation with serializer and codec child bounds respectively.
  Preserve native non-compatible direct position bodies, compatible/xlang heterogeneous LIST
  bodies, the existing UNKNOWN-generic tuple `FieldType`, missing-position defaults, extra-position
  skipping, and wire behavior. Tuples are not transparent wrappers: keep `IS_WRAPPER = false` and
  reject tuple serializers from custom EXT registration through their LIST category. No serializer
  type or tuple index appears on the wire.
- Keep shared LIST/SET loops in `serializer/collection.rs`, MAP chunk behavior in
  `serializer/map.rs`, and arity-generated heterogeneous tuple behavior in
  `serializer/tuple.rs`, parameterized by child serializers for value bodies and by the additional
  codec bounds only for field behavior, without provider objects, callbacks, function pointers, or
  erased builders. Carrier implementations own concrete allocation and insertion. `BTreeMap`
  requires `Ord` on the target key; remove its current unnecessary `Hash`
  bound rather than exposing that implementation accident through the serializer API.
- Fory-owned carrier serializers are not registered and have no resolver entry, dynamic harness, or
  wire identity. A field `with` selects one serializer whose `Target` is exactly the declared field
  node and accepts ordinary, external structural, custom, and carrier serializers. Transparent
  fields therefore name their exact carrier serializer, such as
  `OptionSerializer<UserSerializer>`, while recursive list/map/tuple annotations select child
  nodes. Field codegen must recognize every canonical carrier serializer and ordinary carrier type
  recursively, lower it to the matching codec tree, and use `SerializerCodec<S>` only for a leaf.
  Because procedural macros cannot resolve type aliases or renamed imports and no associated codec
  mapping abstraction is allowed, every carrier constructor in a schema-bearing derived field's
  declared type and every carrier serializer constructor in its `with` tree must use its canonical
  terminal name, optionally qualified. Leaf serializer aliases, root carrier aliases, and carrier
  aliases used only by a skipped field's value-level default remain valid. The leaf adapter must
  reject an aliased non-wrapper carrier during cold field-schema construction using its existing
  wire category so it cannot silently emit a leaf schema. An aliased Fory-owned wrapper cannot be
  registered independently and therefore fails the ordinary required-provider lookup. This check
  must not use `type_name` or enter the value hot path. Compile-time target equality comes from
  `Codec<T>: Serializer<Target = T>`.
  Registration rejects carrier serializers through the existing API semantics: structural APIs
  require `StructSerializer` and the matching structural category, while custom registration
  requires an independent EXT/NAMED_EXT serializer and rejects transparent wrappers. Require a
  selected serializer's registration only when the owning carrier or field path accesses its
  registered identity or registration-backed metadata. `Serializer::write_data/read_data` are
  body-only and must not perform a reached-body registration check. `Serializer::write/read` are
  complete-value operations and contain no field-schema argument. Field-declared generic state
  stays inside `Codec`. Do not eagerly walk a root serializer tree: absent Options, empty
  collections/maps, empty weak values,
  zero-length arrays, and equivalent recursive no-child branches may complete without leaf
  registration when they make no identity/metadata access. A declared-type body path calls the
  selected serializer directly; its containing schema, when present and declaring that child, owns
  the one prior registration/mismatch check. The existing UNKNOWN-generic tuple `FieldType` does
  not declare positions, so its ordinary per-position type metadata owns any required child
  registration access. Do not add a recursive serializer field hook, preflight lookup,
  reached-body/per-element check, or a second tuple-position selector.
- Keep root serializer selection in `serialize_with`, `serialize_to_with`, `deserialize_with`, and
  `deserialize_from_with`. Do not add a runtime provider tree, public codec, provider object,
  container-specific root method, or per-element lookup. `Option`, Box/Rc/Arc/weak references,
  `RefCell`, and `Mutex` remain transparent around the selected child serializer and must not add an
  allocation.
  Direct/static and access-constrained polymorphic LIST/SET body writes use one borrow, lock, or
  weak upgrade. A non-polymorphic nullable holder preserves the existing LIST/SET null-header scan
  and then uses one body access without inspecting null again. MAP metadata and null flags precede
  both bodies, so a nullable or access-constrained polymorphic MAP holder performs one short
  null/target inspection and later its normal body access; never retain a borrow/guard across the
  other side, stage body bytes, allocate a prepared value, invoke a callback, or change MAP wire
  order. A
  node-local custom serializer may target one exact whole container or tuple as opaque EXT;
  recursive list/map/tuple annotations or Fory-owned carrier serializer roots instead preserve the
  structural carrier and select child serializers. Never combine both meanings at one node.
  Registration must not silently replace static selection.
- Static serializer paths must stay monomorphized and allocation-free. Dynamic dispatch looks up the
  concrete target TypeId. When a registered provider has both provider and target resolver
  indexes, they must point to that provider's one immutable `TypeInfo` and harness, not parallel
  metadata or serializer paths.
- Name provider-identity and target-identity lookups explicitly. Static schema paths use
  `write_provider_type_info`/`get_provider_type_info`; dynamic `Any` and application-trait paths use
  `write_target_type_info`/`get_target_type_info`. Remove ambiguous single-map lookup APIs instead
  of probing one map and falling back to the other.
- Preserve the existing homogeneous LIST/SET and MAP metadata owner for dynamic children. Its
  doc-hidden dynamic handoff resolves and validates the concrete target once, retains the returned
  `Rc<TypeInfo>` for that wire chunk, and borrows it for each body. Field-declared state stays in
  the dynamic field codec; the registered target harness invokes value-level serializer methods
  only. Do not resolve again per element/entry, clone the `Rc` per body, move
  membership/category validation into the carrier, or add a callback, cache, provider instance, or
  runtime schema object. This handoff is not a field-selection path and does not justify a field
  hook on `Serializer`.
  Dynamic target inspection is fallible and returns an optional concrete TypeId, where absence
  means a null/expired value rather than a sentinel type. LIST/SET must skip target/null
  pre-inspection for an access-constrained polymorphic child and use its existing per-value
  metadata path. Its non-polymorphic nullable holder path must preserve the existing null-header
  scan and avoid a second null inspection in the body loop. MAP must inspect a nullable or
  access-constrained polymorphic holder once before its fixed-order metadata and access the body
  later.
- Provider registration conflicts use exact target-index identity and must be order-independent.
  Private built-in registration validates the expected internal type ID before publication, and
  later user insertions use the same target-index conflict checks. Do not classify generic target
  families through `type_name` or add a target-kind marker hierarchy; an
  otherwise unowned exact container/tuple target may use one custom EXT serializer.
- External structural serializers must access and construct their target directly. Do not create a temporary
  external structural serializer, mirror value, conversion hook, private-field workaround, or unsafe access path.
- Because external structural serializer declarations are compile-time schemas rather than runtime
  values, generated target-owned code must keep their declared fields and variants valid under
  `deny(dead_code)` with one private, allowed, type-check-only schema-use item. It may reference
  constructors only inside uncalled closures, must never be invoked, and must not add a runtime
  mirror, conversion, provider value, branch, or allocation.
- An external structural enum serializer declaration owns its schema; derive cannot inspect or validate an external enum's
  source declaration order. Explicit IDs and declaration-order fallback own xlang ENUM/UNION tags.
  Native data enums retain the existing local `ForyUnion` ordinal/tag behavior; do not assign
  explicit IDs a new native meaning or change existing native bytes.
- The complete external-type implementation includes external structural serializers for native
  Rust struct-style enums under
  `xlang = false`: `ForyUnion` must support mixed unit, multi-field tuple, and multi-field named
  variants in both compatibility modes, with recursive serializers on variant payload fields and
  with the same native bytes as an equivalent local derive. Do not add another derive macro. A
  multi-field variant has no xlang ENUM/UNION representation; reject its serializer on the cold,
  fallible actual-type-ID registration path before publishing resolver state when `xlang = true`.
  Never discard fields, synthesize hidden variant structs, or fall back to EXT.
- Keep registration-family selection on the existing static category: STRUCT and ENUM use normal
  structural registration, UNION uses union registration, and each family rejects the other
  categories before publication. A native-only multi-field `ForyUnion` reports ENUM and uses normal
  registration; an xlang-compatible `ForyUnion` reports UNION. Do not add a capability registry or
  schema-kind hierarchy for this distinction.
- A lossless external xlang-union target must represent the same runtime unknown carrier returned
  by `ForyUnion`; the external structural serializer cannot invent a serializer-only target
  variant. Use a dependency-free
  generic external target such as `Value<U>` instantiated with `fory::UnknownCase` in tests and
  examples.
- Rust application trait objects use the object-safe `ForyObject` downcast surface, not
  `Serializer`. Do not restore `Box<dyn Serializer>`, generated Rc/Arc value wrappers,
  clone-on-write wrapper codecs, or first-concrete-type defaults. Dynamic Box/Rc/Arc reads must
  allocate the requested final owner exactly once. Wire-resolved membership checks use a
  zero-allocation optional target-TypeId accessor on the shared provider harness before
  materialization; remote-compatible metadata must preserve that same local harness, while a
  remote-only stub returns no target identity and enters a cold missing-registration error.
  `Any` and application traits work in native and xlang modes only through mode-eligible concrete
  serializers; their Rust carrier/trait identity never appears on the wire.
- Custom serializers used through sync Arc dynamic carriers must implement the direct final-Arc
  materializer hook. Do not allocate a Box first, and do not make non-Send/Sync serializers pretend
  Arc materialization is supported.
- `Rc`, `Arc`, `RcWeak`, and `ArcWeak` own only their reference envelope. After consuming it, a
  compatible metadata-bearing structural child must still consume its inline `TypeInfo`, while a
  declared recursive carrier child must receive the remote `FieldType` directly. Do not discard
  either metadata form, read a second reference envelope, or add a lookup.
- Custom serializers own allocation and proportional readable-byte/graph-memory checks inside their
  opaque body. Serializer codecs own the outer envelope and only the carriers they materialize;
  dynamic Box/Rc harnesses reserve before serializer reading and allocate the outer owner once
  afterward. Dynamic Arc harnesses reserve first, then the serializer/structural Arc hook performs
  the single Arc allocation. Neither path may duplicate body/backing allocation.
- Fallible `Serializer::default_value` is the only Fory default owner. A field codec inherits and
  invokes that serializer operation; it does not define a parallel default API. Do not retain or reintroduce
  `ForyDefault` bounds through scalar conversion, skips, carriers, collections, compatible reads,
  registration, or root APIs. Every default receives the active `ReadContext` so its allocation
  owner can reserve graph memory. Generated serializer defaults must not delegate to standard
  `Default`; construct through selected codecs and context. `skip + with` selects the serializer
  codec only for construction default and needs no registration when it emits no metadata/body. Do
  not mark the default method itself universally cold: skipped-field construction is a normal read
  path. A complete read's null branch calls `Self::default_value(context)` directly; do not
  interpose a cold forwarding helper. Keep the default unsupported-error constructor and other
  genuinely cold mismatch failures cold and non-inlined.
- `IS_WRAPPER` identifies only Fory-owned wrapper serializers without an independent registration
  identity: Option, Box, Rc, Arc, RcWeak, ArcWeak, RefCell, and Mutex. It is intrinsic to the outer
  serializer and remains true over an EXT child. Lists, sets, maps, fixed arrays, and tuples are
  false. A custom serializer targeting a wrapper-shaped Rust type also remains false because it
  owns an independent opaque EXT body. Use this constant with the existing wire category for
  custom EXT registration validation, its only runtime consumer; do not replace it with reflection,
  `type_name` classification, target syntax inspection, or a second registration marker.
- Mark cold failure and slow-path entrances reachable from Rust serialization hot paths with both
  `#[cold]` and `#[inline(never)]`. Keep successful dynamic dispatch and normal non-null/matched
  paths hot. A generated structural compatible read is the normal path whenever compatible mode is
  enabled, so keep it out of cold sections even when it remains non-inlined.
- Self-owned generated structural `Serializer::write_data` and all generated structural
  `Serializer::read_data` bodies use ordinary `#[inline]`, not `#[inline(always)]`. Small bodies
  remain compiler-inlineable, while forcing large bodies into root context closures can inflate
  code and stack frames and regress carrier result handling. External structural
  `Serializer::write_data` bodies use `#[inline(never)]` so recursive carrier composition does not
  duplicate the same generated body into every child monomorph; this is a stable successful-path
  boundary, not a cold path. Reserve `#[inline(always)]` for small complete-value forwarding or
  compile-time selection hooks whose bodies must disappear after monomorphization.
- If breakage is explicitly acceptable during a Rust module refactor, rewire macros, tests, and sibling crates directly to the new boundaries instead of adding compatibility re-exports.
- For panic-safety in hot paths, preserve TLS context reuse. Add scoped guards or owned fallbacks rather than per-call context allocation, and reset reused contexts at entry and successful exit.
- Read depth and per-root generic/reference state use root reset as their only failure-cleanup
  owner. Nested readers and skippers increment depth before reading children and decrement only
  after every child succeeds; an error must retain the failed path's depth and transient state until
  root reset. Do not use `Drop`, RAII, scope guards, or match-error cleanup to decrement or pop that
  read-side state on failure. This rule does not change write-side cleanup.
- Compatible scalar, list-array, and binary/uint8-array adaptations are immediate-field-only. Keep recursive matched-field shape classification owned by `fory-core/src/meta/type_meta.rs`; collection elements, array elements, map keys, and map values must require exact nullability, ref tracking, generic arity, and type shape except documented user-type family normalization.
- Root deserialization graph memory budget state belongs to `ReadContext` and is initialized by the
  root `Fory` read methods before the header is consumed. Use the fixed `128 MiB` default unless a
  positive explicit value overrides it; zero is invalid at config
  creation. Root `Fory` read methods reset the budget only; they must not pre-reserve root type or
  root self bytes. Do not derive the budget from root input size or add dynamic bytes-read
  accounting.
  Do not mirror the configured max into a second active-limit field; keep one configured max plus
  mutable remaining budget.
  `ReadContext` may expose only raw byte reservation; `Vec`,
  collection, map, array, struct, object, and derive codec formulas belong in their serializer
  owners.
  Treat the option as an approximate collection/map/array/struct/object gate, not an exact heap
  cap. Leaf values skipped by graph budgeting remain gated by unread input bytes.
- Rust `Vec<T>` stores inline element storage, so general LIST paths reserve
  `len * size_of::<T>()`, including `Vec<String>` and `Vec<struct>`. Maps reserve
  `len * (size_of::<K>() + size_of::<V>())`. Rust struct serializers do not reserve their own
  memory because structs are inline values; parent fields, collection/map backing storage, and
  Box/Rc/Arc/dynamic-box owners account for storage they own with direct `size_of::<T>()` formulas.
  Root deserialization does not reserve root object memory.
  Dedicated primitive dense ARRAY `Vec<T>` readers, strings, binary, primitive scalars, and
  primitive fixed-array owners stay skipped and keep their byte checks.
- Ordinary, external, custom, carrier-root, and derive field paths converge on the same carrier
  body/allocation implementation. Root paths compose child serializers and field paths compose
  child codecs. Keep exactly one reservation before `Vec::with_capacity`,
  `HashMap::with_capacity`, or collection materialization at that concrete owner. Do not restore a
  duplicate collection/map body route or duplicate reservation. Empty non-leaf owners that
  allocate an independent owner object or storage reserve nonzero shallow self cost.
- Count-derived collection and map owners whose exact repeated body is compile-time proven to
  consume a byte must retain the direct readable-count gate with no unbacked-budget access. An
  uncertain owner may exclude the root operation's remaining unbacked-container allowance from
  that preallocation byte check, then must settle actual item/body byte progress in its one
  existing loop: every 1024 collection items plus the tail, and at existing map chunk boundaries.
  The allowance is root-shared, defaults to `8192`, and zero is strict. Writers must keep compact
  empty bodies valid; do not reject, pad, or change their wire encoding for this reader policy.
- Fixed arrays do not allocate from their validated wire count and bypass the preallocation gate.
  Zero-sized children also bypass that gate for `Vec`, `VecDeque`, and `BinaryHeap`, whose
  representation has no count-derived backing for those elements, but their repeated read work
  still uses the root unbacked-container budget when the body is not proven to advance.
  `LinkedList`, `HashSet`, `BTreeSet`, `HashMap`, and `BTreeMap` retain the allocation gate for node,
  bucket, entry, or duplicate-processing work. Compile proven-positive budget access out after
  monomorphization. Do not add guessed node charges, padding bytes, a second container loop, or
  another collection or map codec.

## Key Paths

- `fory/src/lib.rs`
- `fory-core/src/fory.rs`
- `fory-core/src/resolver/type_resolver.rs`
- `fory-core/src/resolver/metastring_resolver.rs`
- `fory-core/src/resolver/context.rs`
- `fory-core/src/buffer.rs`
- `fory-core/src/meta`
- `fory-core/src/serializer`
- `fory-core/src/row`
- `fory-derive/src/object`
- `fory-derive/src/fory_row`

## Commands

```bash
# Check code
cargo check

# Build
cargo build

# Lint
cargo clippy --all-targets --all-features -- -D warnings

# Run tests
cargo test --features tests

# Run a specific test
cargo test -p tests --test <test_file> <test_method>

# Run a specific test under a subdirectory
cargo test --test mod <dir>::<test_file>::<test_method>

# Debug a specific test
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 ENABLE_FORY_DEBUG_OUTPUT=1 cargo test --test mod <dir>::<test_file>::<test_method> -- --nocapture

# Inspect generated code by the derive macro
cargo expand --test mod <mod>::<file> > expanded.rs

# Format
cargo fmt

# Check formatting
cargo fmt --check

# Build docs
cargo doc --lib --no-deps --all-features

# Run benchmarks
cd <project_dir>/benchmarks/rust
./run.sh
```

The Rust benchmark workspace has two independent package owners:
`benchmarks/rust/xlang` owns shared xlang models, adapters, generated Protobuf
code, `serialization_bench`, and `fory_profiler`;
`benchmarks/rust/local` owns buffer, threaded, and external-type benchmarks.
Neither package may depend on the other or contain the other package's models
or monomorphizations. Use the member manifest explicitly when building or
running one suite so the other package is not compiled.

## Java-Driven Xlang Test

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
RUST_BACKTRACE=1 FORY_RUST_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.RustXlangTest
```
