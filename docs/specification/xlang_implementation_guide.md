---
title: Xlang Implementation Guide
sidebar_position: 10
id: xlang_implementation_guide
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

## Overview

This guide describes the current xlang ownership model used by Fory implementations.

The wire format is defined by
[Xlang Serialization Spec](xlang_serialization_spec.md). This document is about
service boundaries, operation flow, and internal ownership. New Fory implementations do not
need the same class names, but they should preserve the same control flow:

- root operations stay on the `Fory` facade
- nested payload work stays on explicit read and write contexts
- type metadata stays in the type resolver layer
- serializers stay payload-focused

When this guide conflicts with the wire-format specification, follow
`docs/specification/xlang_serialization_spec.md`. When it conflicts with a
language-specific implementation detail, follow the current implementation code for
that language.

## Source Of Truth

Use these sources in this order:

1. `docs/specification/xlang_serialization_spec.md`
2. the current implementation for the language
3. cross-language tests under `integration_tests/`

For Dart, the implementation shape is centered on:

- `Fory`
- `WriteContext`
- `ReadContext`
- `RefWriter`
- `RefReader`
- `TypeResolver`
- `StructSerializer`

## Implementation Ownership Model

### `Fory` is the root-operation facade

`Fory` owns the reusable services for one Fory instance.

In Dart, `Fory` owns exactly four reusable members:

- `Buffer`
- `WriteContext`
- `ReadContext`
- `TypeResolver`

In Java, `Fory` also owns instance-local services such as `JITContext` and
`CopyContext`, but the ownership rule is the same: `Fory` is the root facade,
not the place where nested serializers do their work.

`Fory` is responsible for:

- preparing the shared buffer for root operations
- writing and reading the root xlang header bitmap
- delegating nested value encoding to `WriteContext`
- delegating nested value decoding to `ReadContext`
- owning registration through `TypeResolver`
- resetting operation-local context state in a top-level `finally`

Nested serializers must not call back into root `serialize(...)` or
`deserialize(...)` entry points.

### `WriteContext` and `ReadContext` hold operation-local state

`WriteContext` and `ReadContext` are prepared by `Fory` for one root operation
and reset by `Fory` in a `finally` block before reuse.

`prepare(...)` should only bind the active buffer and root-operation inputs.
`reset()` should clear operation-local mutable state.

That operation-local state includes:

- the current buffer
- the active `RefWriter` or `RefReader`
- meta-string state
- shared type-definition state
- operation-local scratch state keyed by identity
- logical object-graph depth

Generated and hand-written serializers should treat these contexts as the only
source of operation-local services. Serializers must not keep ambient instance
state in thread locals, globals, or serializer instance fields.

### `WriteContext`

`WriteContext` owns all write-side per-operation state:

- current `Buffer`
- `RefWriter`
- `MetaStringWriter`
- shared TypeDef write state
- root `trackRef` mode
- recursion depth and limits

It exposes one-shot primitive helpers such as:

- `writeBool`
- `writeInt32`
- `writeVarUInt32`

These helpers are convenience methods. Serializers that perform repeated
primitive IO should cache `final buffer = context.buffer;` and call buffer
methods directly.

### `ReadContext`

`ReadContext` owns all read-side per-operation state:

- current `Buffer`
- `RefReader`
- `MetaStringReader`
- shared TypeDef read state
- recursion depth and limits

It exposes matching one-shot primitive helpers such as:

- `readBool`
- `readInt32`
- `readVarUInt32`

Generated struct serializers call `context.reference(value)` immediately after
constructing the target instance so back-references can resolve to that object.

## Reference Tracking

Reference handling is split behind two explicit services:

- `RefWriter` writes null, ref, and new-value markers and remembers previously
  written objects by identity.
- `RefReader` decodes those markers, reserves read reference IDs, and resolves
  previously materialized objects.

The xlang ref markers are:

- `NULL_FLAG (-3)`
- `REF_FLAG (-2)`
- `NOT_NULL_VALUE_FLAG (-1)`
- `REF_VALUE_FLAG (0)`

Key behavior:

- basic values never use ref tracking
- field metadata controls ref behavior inside generated structs
- root `trackRef` is only for top-level graphs and container roots with no
  field metadata
- serializers that allocate an object before all nested reads complete must bind
  that object early with `context.reference(...)`

## Type Resolution

`TypeResolver` owns:

- built-in type resolution
- registration by numeric id or by `namespace + typeName`
- serializer lookup
- struct metadata lookup
- type metadata encoding and decoding
- canonical encoded meta strings for package names, type names, and field names
- encoded-name lookup for named type resolution
- wire type decisions for struct, compatible struct, enum, ext, and union forms

In Java xlang mode the concrete implementation is `XtypeResolver`. In Dart the
same ownership stays behind the internal `TypeResolver`.

Serializers do not resolve class metadata themselves. They ask the current
context to read or write nested values, and the context delegates type work to
`TypeResolver`.

### External-type serialization ownership

A language binding that cannot attach Fory behavior directly to every
application value type may separate a serializer provider from its target
value.

The ownership split is:

- the serializer provider owns static serialization behavior and, for an
  external structural serializer, the local schema declaration
- the target owns values of the target type, host type identity, storage size, and
  dynamic downcasts
- the value serializer owns target bodies, complete root values, defaults,
  value type identity, root/dynamic type information, and value-level
  polymorphism
- the internal field codec extends the value serializer for the same target
  and owns `FieldType`, field null/reference framing, field-capacity hints,
  remote field metadata, compatible-field composition, and recursive carrier
  field schemas
- one carrier implementation owns the body, allocation, insertion, and
  reference algorithms reused by root serializers and field codecs
- a custom serializer owns allocations inside its opaque body and must perform the
  corresponding readable-byte, policy, and graph-memory checks before
  allocation
- `Fory` owns root framing and operation setup/reset
- `TypeResolver` owns registration and dynamic lookup

#### C# generated structural serializers

C# uses one target-keyed source-generation path for ordinary and external
structural serializers. `ForyStructAttribute` is non-inherited: every
first-party class that participates in a serializable hierarchy carries a
direct annotation. An external declaration supplies the equivalent contract
for an unmodifiable target.

For one concrete ordinary class, compilation-level hierarchy discovery
produces two independent immutable outputs:

- one flattened wire-member set, used by field ordering, schema hashes,
  `TypeMeta`, and generated reads and writes; and
- one shallow-storage model, used only by graph-memory accounting.

The wire set is assembled base-first from declaration-owned generated
descriptors. Property override chains collapse to one logical slot before the
complete set is validated and sorted by the protocol comparator. Hidden
members retain their exact declaring type. A generated child never calls a
parent serializer body and never encodes a base object as a nested value.

Each inheritable ordinary class publishes a target-keyed compiler contract
containing:

- its exact target;
- its exact directly declared wire-member count;
- one descriptor on each deterministic accessor for a directly declared wire
  member: fields use a writable `ref` accessor, while properties use a getter
  plus a matching setter; and
- a public static readonly cumulative `HierarchyShallowBytes` value.

The provider's shallow value is the immediate parent provider value plus only
the current class's directly declared physical instance fields. A sealed
concrete serializer uses the same cumulative expression privately and does not
publish a provider marker, descriptors, or hierarchy value. The concrete
serializer adds object self storage once. Properties never directly add
storage, while private, readonly, compiler-generated, and non-wire instance
fields do. Referenced child compilations consume only an accessible provider
contract; they do not import, enumerate, or reconstruct private parent fields.
Provider-only targets emit a static provider; a concrete non-sealed
serializer carries the same contract without a second type or forwarding path.
An internal provider is available only to assemblies granted normal C#
accessibility, such as through `InternalsVisibleTo`; an inaccessible or
extern-alias-only contract does not own another compilation's hierarchy.

The generator emits ordinary direct member access whenever C# accessibility
allows it. A provider publishes an accessor when a referenced child must reach
declaration-owned state. The accessor signature preserves the member's CLR
type and nullability metadata. Missing or ambiguous providers fail generation.

An abstract ordinary class emits only its generated hierarchy provider and does not
create a serializer instance or registration. Its property descriptors may
publish unresolved abstract override slots; a concrete descendant must supply
the callable implementation. Concrete classes require legal parameterless
construction and retain the existing allocate-before-children and reference
publication order.

`ForyStructAttribute` and `ForyEnumAttribute` carry an optional `Target` type.
A local non-generic abstract class supplies an external structural declaration,
while an empty non-generic static class selects an external enum target:

```csharp
[ForyStruct(Target = typeof(ThirdParty.User))]
internal abstract class UserSerializer
{
    [ForyField(
        1,
        TargetDeclaringType = typeof(ThirdParty.User),
        TargetMemberName = "<Name>k__BackingField")]
    public abstract string Name { get; }
}

[ForyEnum(Target = typeof(ThirdParty.Status))]
internal static class StatusSerializer
{
}
```

External declarations are compile-time generator input only. They are never
instantiated, reflected over at runtime, registered, reference-published,
or used as wire identities. All serializer type positions, construction, `TypeInfo`,
metadata, reference publication, generated factory keys, roots, fields,
dynamic values, and carriers use the target type.

An external member may bind a visible same-name field or property. For external
class targets, setting `TargetDeclaringType` and `TargetMemberName` instead
declares one exact field on the target or a non-`object` ancestor. An exact wire mapping
also supplies physical storage; `Ignore = true` supplies only shallow storage.
External struct targets support visible member mappings only. Unmapped visible
public instance fields are added to external class shallow storage once. The
generator never discovers private fields from a referenced assembly.

`BaseOnly = true` makes an external class declaration the terminal provider for
a complete third-party hierarchy prefix. It may list exact target and
target-ancestor fields and publishes no standalone factory or registration. An
ordinary child consumes this provider exactly as it consumes an ordinary
parent provider. A `BaseOnly` target may be abstract or nonconstructible
because only the concrete ordinary child is materialized.

Exact private mappings are version-pinned package ABI declarations. Wire
accessors fail with the CLR missing-field error if the target ABI changes;
there is no reflection or alternate-member fallback. On .NET 8, the generator
rejects private wire access whose declaring owner or signature is generic.
Visible closed-generic members and explicit storage-only field mappings remain
supported for class targets. An inaccessible pointer field cannot be
distinguished from fixed-buffer storage without importing private layout, so an
exact private pointer mapping is rejected.

Standalone external structural targets require an accessible concrete class
or struct, legal parameterless construction, and writable declared wire state.
Constructor-only, factory-only, readonly, init-only, converted, and
custom-wire shapes use a custom `Serializer<T>`. Explicit nullability must
match when target metadata is annotated; otherwise the declaration supplies
schema nullability.

Every generated ordinary or external struct uses
`TypeResolver.RegisterGeneratedStruct<T, TSerializer>(bool evolving)` to carry
generator-owned `Evolving` into target `TypeInfo`. Generated enums and unions
use `TypeResolver.RegisterGenerated<T, TSerializer>()`. Abstract ordinary and
`BaseOnly` providers do not register. Multiple generated owners for one target
are rejected during generation or deterministically on the cold
cross-assembly factory-registration path. Custom serializer replacement keeps
the resolver's normal target rules.

C# carrier composition remains target-based. The resolver recursively binds
`Nullable<T>`, one-dimensional `T[]`, `List<T>`, `LinkedList<T>`, `Queue<T>`,
`Stack<T>`, `HashSet<T>`, `SortedSet<T>`, `ImmutableHashSet<T>`,
`Dictionary<TKey, TValue>`, `SortedDictionary<TKey, TValue>`,
`SortedList<TKey, TValue>`, `ConcurrentDictionary<TKey, TValue>`, and
`NullableKeyDictionary<TKey, TValue>`. Ordinary, external, and custom
serializers use the same carrier bodies. There is no hierarchy lookup at runtime,
provider object, callback, schema tree, per-element dispatch, or additional
value allocation.

Dynamic `object` values and unions resolve concrete target types through
`TypeResolver`. Arbitrary statically typed interface or base-class
polymorphism remains unsupported. Flat ordinary and external generated hot
bodies retain the same work and allocation shape apart from target/member
metadata tokens; hierarchy composition is static initialization and
compile-time metadata work.

Rust names these serializer operation boundaries explicitly:

- `Serializer::write` and `Serializer::read` process a complete value,
  including the requested reference and type-information envelopes.
- `Serializer::write_data` and `Serializer::read_data` process only the target
  body.
- `write_with_type_info` and `read_with_type_info` remain complete-value
  operations over already-resolved value metadata.

Rust represents immutable value-serializer properties with five associated
constants on `Serializer`:

- `IS_OPTIONAL` means the selected value shape carries Option semantics;
- `IS_POLYMORPHIC` means its concrete target is selected from the application
  value;
- `IS_SHARED_REF` means it uses the existing shared-reference wire behavior;
- `IS_WRAPPER` means it is a Fory-owned wrapper serializer without an
  independent registration identity; and
- `REQUIRES_SCOPED_ACCESS` means inspecting or using the contained dynamic
  value requires a borrow, lock, or weak upgrade.

These are type-level value properties and must fold through monomorphization.
`Codec<T>` inherits them through `Serializer<Target = T>` and does not
redeclare them. `SerializerCodec<S>` forwards them from `S`. The
value-dependent `is_none(value)` and `dynamic_type_id(value)` operations
remain functions. `is_none` means only `Option::None`, including transparent
Option propagation; weak-target expiry remains a separate weak-access result.

`Serializer` has no field API or field-schema argument. In particular, it does
not expose `FieldType`, field-compatible reads, declared-field generic state,
field null/reference policy, or field encoding selection. Rust's internal
`Codec<T>` extends `Serializer<Target = T>` and owns those field operations.
The leaf `SerializerCodec<S>` implements value behavior by forwarding to `S`
and implements field behavior itself; it never calls a field hook on `S`.
Its value-capacity hint is forwarded unchanged. Generated fields and
field-mode carrier bodies use the codec-owned field-capacity hint, which adds
or forwards field framing without changing root or value composition.

Serializer-provider identity is a host implementation detail and is never
encoded. External structural serializers use the same STRUCT, ENUM, or UNION
metadata and value format as an equivalent directly supported target. Custom
serializers that are not the Fory implementation's canonical serializer for an existing
built-in use EXT or NAMED_EXT. Serializer-provider separation does not replace
implementation-owned built-in mappings.

Static generated fields and serializer-selected roots should dispatch directly
to the serializer selected by their schema. A Rust field `with = S` selects the
exact field node and requires `S::Target` to equal the declared field type.
This accepts an ordinary, external structural, custom, or carrier serializer.
For example, `with = VecSerializer<UserSerializer>` selects the structural
`Vec<User>` field node, while `list(element(with = UserSerializer))` selects
the child node recursively. Transparent fields select their exact carrier
serializer, such as `OptionSerializer<UserSerializer>`. Field codegen lowers
both forms recursively to carrier codecs.

Rust procedural macros cannot resolve type aliases or renamed imports. Because
the design has no associated codec mapping trait or runtime fallback, every
carrier constructor in a schema-bearing derived field's declared Rust type and
every Fory-owned carrier constructor in its `with` tree must use its canonical
terminal name, optionally qualified. Leaf serializer aliases, root carrier
aliases, and carrier aliases used only by a skipped field's value-level default
remain valid. Derive recognizes the complete audited carrier syntax and lowers
unknown types only as leaf serializers. The leaf adapter rejects an aliased
non-wrapper carrier during cold field-schema construction using its existing
wire category, before resolver publication. An aliased Fory-owned wrapper
cannot be registered independently and therefore fails the ordinary
required-provider lookup. The leaf adapter does not consume `IS_WRAPPER`,
inspect runtime type names, or add a value-path branch.

When a root is a transparent,
collection, fixed-array, map, or heterogeneous tuple composition containing
selected external children, the binding should expose binding-owned,
carrier-specific static serializers parameterized recursively by child
serializers. For example, a vector carrier serializer over `S` has the target
vector of `S::Target`, a map carrier serializer over `KS` and `VS` has the
target map of `KS::Target` to `VS::Target`, and an arity-N tuple carrier
serializer has the target tuple formed
positionally from every `Si::Target`.

Root carrier composition recursively composes value serializers. Field
composition recursively composes codecs. They are intentionally different
compile-time type trees: a root carrier must not construct a field-codec tree
or request or synthesize `FieldType`. Root carrier reads may use only direct or
value-`TypeInfo` child state; remote `FieldType` belongs exclusively to the
field-codec entrance. Each carrier implementation provides `Serializer`
behavior when its children implement `Serializer`, and field `Codec` behavior
only when its children implement `Codec`. Both layers call one carrier body,
allocation, insertion, and reference implementation. They must not copy
collection, map, reference, holder, fixed-array, tuple, or compatible-read
algorithms. A metadata-only adapter that still calls a whole carrier
serializer is not external-child support.

Rust keeps an external structural serializer's generated `write_data` body
non-inlined. Recursive carrier composition would otherwise duplicate that body
into each list, map, tuple, and wrapper monomorph. This is a direct
successful-path function boundary, not a cold path, and it adds no runtime
selection, callback, or allocation. Self-owned generated serializers retain
the ordinary compiler inlining heuristic.

A transparent reference carrier consumes only its own null or reference
envelope. If compatible mode places user-type metadata before the selected
child body, the child must still consume that metadata and use its remote
schema. If the containing schema instead declares a recursive carrier child,
the child receives that declared field metadata directly. Implementations must
not discard either metadata form or read the carrier envelope twice.

Carrier serializers are not registered user types: they preserve the carrier's
standard wire kind, have no resolver entry or dynamic harness, and any
registration belongs to a selected user-type child when that child is reached.
Carrier delegation must include every existing canonical specialization rather
than forcing a generic collection shape. For example, a Rust vector carrier
serializer over the canonical `i32` serializer retains `INT32_ARRAY`, one over
the canonical `u8` serializer retains BINARY, and one over an external
structural or custom serializer uses LIST. A nested vector retains the selected
child representation in root bytes; the equivalent field-codec tree retains it
in recursive `FieldType`.

For Rust, the audited carrier serializer surface is exhaustive:

- `OptionSerializer<S>`, `BoxSerializer<S>`, `RcSerializer<S>`,
  `ArcSerializer<S>`, Fory `RcWeakSerializer<S>`/`ArcWeakSerializer<S>`,
  `RefCellSerializer<S>`, and `MutexSerializer<S>`;
- `VecSerializer<S>`, `VecDequeSerializer<S>`,
  `LinkedListSerializer<S>`, `HashSetSerializer<S>`,
  `BTreeSetSerializer<S>`, `BinaryHeapSerializer<S>`, and
  `ArraySerializer<S, N>`;
- `HashMapSerializer<KS, VS>` and `BTreeMapSerializer<KS, VS>`;
- `Tuple1Serializer<S0>` through
  `Tuple22Serializer<S0, ..., S21>`.

Every carrier serializer target is formed recursively from child serializer
targets. Each child can be an ordinary serializer targeting itself, an external
structural serializer, a custom serializer, or another carrier serializer, and
all four forms enter the same carrier body implementation. `Tuple1Serializer` through
`Tuple22Serializer` and matching
arity-specific codecs are macro-generated because Rust has no variadic
generics; no public serializer-list trait or runtime tuple descriptor is
introduced. Unit `()` and `PhantomData<T>` have no serialized child and require
no serializer composition. Erased Any/application-trait carriers retain their
dynamic registered-target owner rather than masquerading as static child
serializers.

`Cell<T>` is not in the current Rust serializer surface: derive only recognizes
its Send/Sync properties. There is no `Cell` serializer or codec, so this
feature must not invent `CellSerializer<S>` or treat `Cell` as `RefCell`.
Likewise, standard-library weak pointers are not aliases for Fory's weak
carriers.

For Swift, `Serializer` follows the same exact-target ownership boundary with
an associated `Target`. A self-provided structural or custom serializer uses
`Target == Self`; a separately provided structural or custom serializer names
another target type. Serializer operations are static and accept or return
`Target`. Fory never instantiates a serializer object, and generated external
structural code reads target properties and constructs the target directly.

Static selection follows provider ownership uniformly. A target that itself
conforms to `Serializer` with `Target == Self` is selected implicitly at roots,
generated fields, optionals, arrays, sets, and dictionaries. This includes an
external type with an intentional retroactive conformance. A serializer type
whose `Target` is another type must be selected explicitly at every static node
where it is required. Registration does not infer that serializer, even if it
is the only registered provider for the target, because Swift cannot
reverse-infer a unique `S: Serializer` from `S.Target == T`.

A retroactive conformance is process-global. `@retroactive` acknowledges
Swift's ownership warning but does not make duplicate `(Target, Protocol)`
conformances safe. Applications may use it when they intentionally own the
single global binding; public libraries should generally use a separate
serializer so applications can choose the implementation explicitly.

Swift `StructSerializer` covers every structural registration category.
Ordinary and external `@ForyStruct`, `@ForyEnum`, and `@ForyUnion` expansions
all conform; custom EXT serializers do not.

Swift's doc-hidden `FieldCodec` extends value serialization for the same exact
target. It owns `FieldType`, recursive field generics, field null/reference
policy, compatible-field reads, scalar conversion, and annotation-selected
field encodings. `Serializer` has no `FieldType`, compatible-field hook, or
declared-generics argument. `FieldCodec` alone carries the
`hasDeclaredChildren` mode that preserves canonical collection and map header
decisions when enclosing field metadata owns the recursive child schema.
`SerializerCodec<S>` is the generated leaf adapter and has target `S.Target`.
Because `FieldCodec` extends `Serializer`, its value-level half must remain a
valid root serializer, but root code cannot observe any field-only operation.
In non-compatible mode, exact nonoptional selected leaves with no
type-information envelope call `S.readData` when no reference envelope is
required; tracked reference targets continue through `S.read`. Compatible or
recursively retained metadata remains owned by `SerializerCodec<S>` and enters
the selected serializer with that scope intact.
Numeric field codecs delegate that value-level half to the canonical numeric
serializer, and packed-array field codecs delegate it to the canonical LIST
carrier. Only their field operations use fixed, tagged, or packed field
representations. Root eligibility therefore adds no alternate numeric or
packed-array wire mapping.

Swift `Serializer.isWrapper` is a doc-hidden value-level property used only to
reject Fory-owned transparent wrappers as independent EXT registrations.
`OptionalSerializer` sets it; collection carriers do not. A custom serializer
does not acquire wrapper status from target spelling and may own an independent
opaque carrier body. Target-identity conflicts prevent it from replacing a
seeded canonical dynamic builtin.

The exhaustive Swift recursive carrier serializer surface is:

- `OptionalSerializer<S>` with target `S.Target?`;
- `ArraySerializer<S>` with target `[S.Target]` and LIST wire shape;
- `SetSerializer<S>` with target `Set<S.Target>` where `S.Target: Hashable`;
- `DictionarySerializer<KS, VS>` with target
  `[KS.Target: VS.Target]` where `KS.Target: Hashable`.

Each carrier serializer conditionally provides field-codec behavior when its
children are field codecs. Root composition therefore contains serializers,
while field composition contains recursively lowered field codecs, and both
call the same carrier body, allocation, insertion, reference, and compatible
implementation. Ordinary `Optional`, `Array`, `Set`, and `Dictionary`
conformances delegate to the same owners under exact self-target constraints.
Those constraints apply equally to user-declared and retroactively conforming
external children. Carrier serializers are zero-state and unregistered.

The transparent Swift `OptionalSerializer` has no independent field-metadata
identity. Its field-codec metadata scope delegates recursively to the wrapped
field codec, including nonnull reads, so accepted remote child metadata remains
owned by the selected leaf or nested carrier.

Swift `Array` is LIST for statically selected roots and carrier roots, including
`[Int32]`. `@ArrayField` is a separate field-only dense bool/numeric selection,
and exact primitive arrays hidden in dynamic `Any` use their canonical dynamic
packed-array mapping. Only a canonical primitive field codec can select
the packed form; a noncanonical selected child is rejected rather than gaining
a mapping from its target syntax. `Data` remains the BINARY leaf.

Swift has no supported generic tuple, fixed-length array, cell, box, weak,
reference-holder, `ContiguousArray`, `ArraySlice`, result, range, deque,
`NSSet`, or `NSDictionary` serializer. External-child composition must not
invent those carriers. `AnyHashable`, `UnknownCase`, and `ByteBuffer` are a
dynamic key holder, union value holder, and transport owner respectively, not
recursive static carriers.

Swift external structural declarations use the structural macros:

```swift
@ForyStruct(target: ThirdParty.User.self)
struct UserSerializer {
    var name: String
    var age: UInt32
}
```

Generated ordinary value declarations use `Target = Self`. Because Swift
rejects a nested `Target = Self` type alias in a class, an ordinary generated
class names the declaring class explicitly; both forms express the same exact
self-target relationship.

Equivalent target selection applies to `@ForyEnum` and `@ForyUnion`. A value
schema declaration targets a value type and uses direct labeled construction.
A class schema declaration targets a class, allocates the final target through
an accessible zero-argument initializer, publishes it before reading child
fields, and assigns accessible mutable properties. Generated Swift enforces
the class target's `AnyObject` constraint. Swift has no negative generic
constraint for the inverse case, so cold registration validation rejects a
value-schema declaration that targets a class before publishing metadata. An
inaccessible, immutable, invariant-bearing, or non-exhaustive target requires a
custom serializer; the implementation must not use reflection, unsafe layout
access, unavailable-overload tricks, schema mirror values, conversion wrappers,
or builders as a fallback.

Swift macros cannot inspect another type's stored layout. An external class's
shallow graph-memory formula therefore uses its declaration fields only.
`@ForyField(ignore: true)` adds a budget-only declaration field without schema,
target access, construction, or wire code. Applications must use this form for
substantial omitted storage.

An external structural union requires the target to expose a lossless
`unknown(UnknownCase)` case. A dependency-free target module may expose a
generic unknown payload and let the application select its `UnknownCase`
specialization; a target may also use Fory's carrier directly. Fory does not
convert another module's unknown representation. A third-party union without
this shape requires a custom serializer and does not claim structural-union
wire equivalence.

An ordinary Swift generated field recursively selects a self-provided declared
type without annotation. Swift field selection uses
`@ForyField(with: S.self)` for one exact declared node and `.with(S.self)`
inside `ForyFieldType` list, set, map, and union payload nodes when selecting a
separate serializer or another deliberate override. A selected optional or
whole collection node names its exact carrier serializer. Canonical
whole-carrier syntax is recursively lowered to the same field-codec tree as the
structural field DSL. The compiler enforces that the selected serializer target
equals the declared field node.

Swift root selection uses a serializer metatype:

```swift
fory.serialize(user, with: UserSerializer.self)
fory.deserialize(bytes, with: UserSerializer.self)
fory.serialize(users, with: ArraySerializer<UserSerializer>.self)
```

The `Data`, append-to-`Data`, and `ByteBuffer` forms share one root framing and
the reusable contexts. Ordinary roots require `T.Target == T` and
delegate to the same selected-serializer helper. This admits every
self-provider, including an intentional retroactive external conformance, but
does not infer a separate serializer from registration. There are no parallel
serializer-selection aliases or application-declared structural container
schemas. The root facade retains its existing module boundary and inlining
policy; static specialization belongs to serializer, generated-code, and
carrier owners below it. Do not expose resolver or reusable-context state as
`@usableFromInline` merely to force the complete root flow into clients.
Selected Swift read roots carry an inferred result generic constrained by
`S.Target == T`. The call remains `deserialize(..., with: S.self)`, but the
same-type generic lets the caller provide concrete target metadata and avoids
an associated-target lookup and dynamic result-storage setup in every
unspecialized root call.

Generated value-struct `readData` owns target construction directly. When an
external structural serializer has recursively selected carrier fields, that
owner may be out of line to control code size, but a private large-value
returning helper must not sit between `readData` and the final target result
buffer. Generated class readers retain a private helper because the class
allocation owner must publish a reserved reference before reading children.

Swift `TypeResolver` indexes one immutable `TypeInfo` by both serializer
identity and concrete target identity. Static schema and explicit selection use
serializer identity; dynamic writes use target identity; wire reads use the
numeric ID or name. All directions share one writer, exact reader, compatible
reader, metadata, and registration owner. Public registration accepts the
selected structural or custom serializer through the ID or name API. It rejects
carrier, dynamic, builtin, and field codec identities before publication.

Swift arbitrary application protocol existentials use a zero-state
`DynamicSerializer<T>`. Dynamic writes resolve the registered concrete target,
downcast once to its serializer's exact target, and invoke the shared
`TypeInfo` harness. Dynamic reads materialize the final concrete target once
and cast it to the requested existential. Normal target registration is the
allowlist, so Swift needs no marker protocol, protocol-specific registry,
closed target-list macro, protocol wire identity, serializer value, or wrapper
collection. Protocol roots and root carriers select the dynamic serializer
explicitly, for example `DynamicSerializer<any Animal>` and
`ArraySerializer<DynamicSerializer<any Animal>>`. Swift retains direct `Any`
and `AnyObject` root overloads for source compatibility. Those overloads
forward to the same selected-serializer roots with `DynamicSerializer<Any>` or
`DynamicSerializer<AnyObject>` and add no parallel codec, framing, lookup, or
allocation path. Their append-to-Data and ByteBuffer forms follow the same
rule. Because Swift permits every value to convert to `Any`, a concrete
non-self-serializing value may enter registered dynamic-target lookup through
the `Any` overload. Explicit `with:` selection names a separate static
serializer when required. Swift exposes no unconstrained generic dynamic root,
`any Serializer` root, or specialized heterogeneous-container root overload.

`DynamicSerializer<T>` overrides complete-value reference handling. It resolves
the concrete `TypeInfo` before deciding whether the target is a reference and
uses conservative `isRefType == true` only for the outer dynamic field shape.
The outer dynamic serializer owns nullability and type information. Its
`TypeInfo` harness invokes body serialization for a value target and the
concrete complete-value reference envelope for a class target. Dynamic slot
accounting uses the declared existential layout rather than treating every
value as a reference. `AnyObject` reads reject value-typed metadata before the
cast so Swift cannot allocate a bridge object. An arbitrary nonoptional
protocol has no synthetic default; optional protocol values compose through
`OptionalSerializer`.

An exact generated field whose selected codec has UNKNOWN static identity
still writes and reads the concrete `TypeInfo`; carrier headers own this
information for dynamic children and do not duplicate it. Dynamic map chunks
retain the established key-type/value-type/key-body/value-body order and reuse
the two resolved `TypeInfo` entries without another target lookup. A retained
dynamic `TypeInfo` is scoped by the serializer that selected it, so
`AnyHashable` and `DynamicSerializer<T>` share one implementation without
substituting each other's scope identity.

Swift rejects zero-sized non-null MAP chunks because they cannot advance the
decoded entry count. A one-null entry encodes its non-null side as a complete
field value: its reference envelope when present, then undeclared `TypeInfo`,
then its body. The MAP writer, reader, and compatible-field skipper use that
complete-field order rather than the shared type-prefix order of non-null
chunks. Static and dynamic MAP branches use the same ordering and validation.

Dynamic Any and protocol paths operate directly on final target values and
containers. Static composition never enters target lookup; dynamic lookup
occurs only at an explicit dynamic boundary.

Swift heterogeneous dynamic collections use their exact supported target
shapes: `[Any]`, `[String: Any]`, `[Int32: Any]`, and `[AnyHashable: Any]`.
Assigning a homogeneous list or map to `Any` does not erase its concrete target
identity and must not trigger a converted collection or a second collection
codec. Homogeneous lists and maps use their ordinary or explicitly selected
carrier serializer. Preseeded exact primitive arrays retain their packed
dynamic mapping. A null dynamic key in `[AnyHashable: Any]` materializes as
`AnyHashable(ForyAnyNullValue())`.

When a carrier retains remote child metadata for a user-type field codec, the
leaf codec enters the selected serializer without another envelope so its exact
or compatible reader sees that retained `TypeInfo`. Builtin leaf codecs read
their field bodies directly.

Swift supports only the xlang wire mode. A known `@ForyUnion` case has zero or
one associated value, and multiple logical fields use an explicit struct
payload. There is no Swift-native multi-field enum encoding to preserve.

Rust tuple field metadata selects sparse zero-based positions, while
unmentioned positions use ordinary serializers. It lowers to the
arity-specific tuple codec, while the root carrier recursively composes tuple
serializers; both use the same arity-specific tuple body. The existing tuple wire
contract remains unchanged: native non-compatible mode uses direct
heterogeneous position bodies, while compatible native and xlang modes use
the existing heterogeneous LIST form. Its existing UNKNOWN generic
`FieldType` shape remains unchanged, and no serializer type or position index is
encoded.

Rust's primitive collection selection must have one owner shared by ordinary
types, carrier serializers, and derive. Type ID, body encoding, reserved space,
field metadata, and compatible reads cannot use independent tables. This
includes canonical `u8` BINARY and the existing `isize`/`usize` dense-array
types. The private primitive-array/carrier owner derives the parent kind from
the child's scalar `static_type_id()`, the exact Rust child target, and
the carrier mode. Scalar serializers and codecs declare only scalar behavior
and scalar wire IDs; neither public serializer contracts nor the generic codec
contract exposes a parent-array kind. The same private mapping validates unsafe
bulk copies and supplies compatible LIST/array element metadata.

Rust 1.70 cannot select a codec type from an associated const, so the existing
Vec and fixed-array carrier implementation types are the single owners of both
primitive and object bodies. The Vec implementation carries two established schema choices as compile-time
consts. `STRUCTURAL_LIST` preserves unannotated and explicit `list(...)`
generated fields as LIST even for canonical primitive children. Ordinary roots,
Vec carrier serializers, `#[fory(bytes)]`, and `#[fory(array)]` disable it so
the carrier can consume the validated canonical child kind. `DENSE_ARRAY` is true
only for explicit `#[fory(array)]`; it maps canonical `u8` BINARY to
`UINT8_ARRAY`. It is false for roots, carrier serializers, LIST fields, and
`#[fory(bytes)]`. Consequently, an unannotated `Vec<i32>` field remains
`LIST<VARINT32>`, an explicit fixed-element list remains `LIST<INT32>`, and
ordinary or serializer-selected primitive Vec roots retain their dense-array or
BINARY representation. An external structural or custom serializer targeting a
primitive remains an object LIST child because its serializer does not expose the
canonical scalar wire ID. Derive may validate the explicit primitive category
but does not map Rust types to wire IDs. Inline scalar-ID and exact-target
checks must fold after monomorphization. Derive always uses the same unified
core carrier implementation and maintains no ordinary-composition AST
primitive table.

Registration is access-driven by the owning serializer or field codec. A
selected user serializer must be registered before schema construction or value
processing accesses its registered identity or registration-backed metadata.
An absent Option, empty collection or map, empty weak value, zero-length fixed
array, or equivalent recursive branch may complete without child registration
when its owning path makes no such access. A declared-type body path invokes
its statically selected serializer without a registration lookup; if a
containing schema supplies that declaration, its metadata construction already
owns the one required registration/mismatch check. A heterogeneous tuple whose
existing outer `FieldType` does not declare positions instead reaches any
required child registration through its ordinary per-position type-metadata
operation. A binding must not add an eager recursive root validation pass,
reached-body check, selector tree, composed-target lookup, per-element
serializer dispatch, allocation, callback, or hot-path branch to change that
behavior. An exact custom serializer for a whole container remains a separate
opaque EXT/NAMED_EXT choice. It owns registered dynamic target identity, while
unregistered carrier serializers continue to own explicit static structural
composition.

Carrier serializers have no independent registered identity. Structural
registration requires the existing structural serializer contract and matching
STRUCT/ENUM/UNION category. Custom registration requires an independent
EXT/NAMED_EXT serializer and rejects `IS_WRAPPER`. Option, Box, Rc, Arc,
RcWeak, ArcWeak, RefCell, and Mutex carrier serializers set this property
independently of their child's category. Lists, sets, maps, fixed arrays, and
tuples keep it false and are rejected through their own wire category. A
custom serializer targeting one of the same Rust shapes also keeps it false
because it owns an independent opaque EXT body. Private built-in registration
validates only its expected internal type ID. These semantic checks use the
existing serializer contracts and wire categories. Custom EXT registration is
the only consumer of `IS_WRAPPER`.

Dynamic values should resolve by the concrete target identity. When a Fory implementation
needs both directions, its serializer-provider-to-type-info and target-to-type-info
indexes must point to the same immutable registration metadata and serializer
harness rather than creating parallel metadata or serialization paths.
The internal context and resolver entry points must name the direction; Rust
uses `write_provider_type_info`/`get_provider_type_info` for static schema
identity and `write_target_type_info`/`get_target_type_info` for dynamic value
identity. An ambiguous lookup must not probe one map and fall back to the other.
Wire reads still resolve their encoded ID or name to that same metadata.

When an existing homogeneous LIST/SET or MAP header emits one dynamically
selected concrete type, that header owner resolves and validates the target
once and retains the resolved registration metadata for the wire chunk. Each
body borrows that exact metadata to invoke the existing dynamic harness; it
must not repeat target lookup or clone a reference-counted metadata handle per
element or entry. Heterogeneous chunks retain their existing per-value metadata
path. This handoff adds no wire field, runtime serializer instance, callback,
schema tree, cache, or static-path branch.

Dynamic target inspection is fallible and represents an absent value without a
sentinel target identity. A LIST/SET owner may pre-inspect a child when
`!C::IS_POLYMORPHIC || !C::REQUIRES_SCOPED_ACCESS`; this caller-local decision
must not become another serializer capability. A polymorphic child that needs
a `RefCell` borrow, `Mutex` lock, or weak upgrade skips target/null
pre-inspection and writes each value through the existing heterogeneous path
under one holder access. A non-polymorphic nullable holder preserves the
existing LIST/SET null-header scan, then performs one body access without
repeating null inspection. MAP metadata and null flags for both sides precede
either body, so a nullable or access-constrained polymorphic MAP holder
performs one short null/target inspection, releases the borrow, guard, or
upgraded owner, and later performs its normal body access. Implementations must
not retain that access across the other map side, stage body bytes, allocate a
prepared value, invoke a callback, or change MAP wire order. A weak wrapper
does not keep its target alive; an operation requiring one coherent
observation must retain its upgraded strong owner for that owned operation.

If closed polymorphic membership needs the host target identity after a wire
ID/name lookup, that shared harness or registration metadata must retain an
optional target identity. Registered local behavior supplies it; a remote-only
stub supplies none and must enter the missing-registration error before
membership checks, serializer calls, or allocation. Do not scan a reverse map,
invent a sentinel identity, or add a second metadata cache.

Serializer code must not materialize a serializer provider value or a
structural mirror value. Generated structural reads construct the target directly. Dynamic
materializers allocate the requested final owner once and use target size for
memory accounting.

Fallible serializer defaults that can materialize values must receive the
active read state or context. A field codec uses its inherited serializer
default rather than defining a second default API. The concrete owner reserves
graph memory before allocating; null, missing-compatible-field, and
skipped-field paths must not bypass the normal allocation budget merely because
they consume no body bytes.

Compatible structural reads remain in the normal struct-compatibility owner.
The checked metadata cache remains the sole owner of accepted remote metadata;
serializer dispatch must not add another validation marker, metadata cache, or
exact-schema decision.

## Root Frame Responsibilities

Every root payload starts with a one-byte bitmap written and read by `Fory`
itself, not by serializers.

Current xlang root bits:

| Bit | Meaning                    |
| --- | -------------------------- |
| `0` | null root payload          |
| `1` | xlang payload              |
| `2` | out-of-band buffers in use |

Keep the root bitmap separate from per-object ref markers:

- the root bitmap describes the whole payload
- ref flags describe one nested value at a time

## Serialization Flow

### Root write path

The current root write flow is:

1. `Fory.serialize(...)` or `serializeTo(...)` prepares the target buffer.
2. `Fory` calls `writeContext.prepare(...)`.
3. `Fory` writes the root bitmap.
4. `Fory` delegates the root object to `WriteContext`.
5. `writeContext.reset()` runs in `finally`.

For a non-null root value, `WriteContext.writeRootValue(...)` performs:

1. ref/null framing
2. type metadata write
3. payload write

Payload serializers are responsible only for the payload of their type. They do
not write the root bitmap and they do not own registration or type-header
encoding.

### Nested writes use `WriteContext`

Important rules:

- nested serializers must use `WriteContext` helpers such as `writeRef(...)`,
  `writeNonRef(...)`, and container helpers when they need ref handling or type
  metadata
- repeated primitive writes should go directly through the buffer
- nested serializer flow should stay straight-line; do not add internal
  `try/finally` blocks just to clean per-operation state
- top-level `Fory.serialize(...)` owns the operation reset `finally`

## Deserialization Flow

### Root read path

The current root read flow mirrors the write flow:

1. `Fory.deserialize(...)` or `deserializeFrom(...)` reads the root bitmap.
2. null roots return immediately.
3. `Fory` validates xlang mode and other root framing requirements.
4. `Fory` calls `readContext.prepare(...)`.
5. `Fory` delegates to `ReadContext`.
6. `readContext.reset()` runs in `finally`.

### `ReadContext` owns ref reservation and payload materialization

`ReadContext.readRef()` performs the normal xlang read sequence:

1. consume the next ref marker
2. return `null` or a back-reference immediately when appropriate
3. reserve a fresh read ref id for new reference-tracked values
4. read type metadata
5. read the payload
6. bind the reserved read ref id to the completed object

Primitive and string-like hot paths should read directly from the buffer;
complex payloads delegate to the resolved serializer.

### Stream And Buffer Byte Reads

Implementations must keep byte availability in the byte owner layer while
keeping string, binary, primitive-array, compression, and collection semantics in
serializers.

The required byte-owner primitive for allocation-before-read checks is a
readability check such as `checkReadableBytes(byteCount)`. Implementations do
not need additional generic read-context methods for this design. After the
readability check succeeds, serializers use their existing local buffer read,
copy, or decode paths.

The readability check is a byte operation only. It must not decode strings,
primitive-array element counts, compression modes, or collection capacity
policy.

For large byte-counted values, every implementation should call the byte-owner
readability check before allocating a variable-length result. This applies to
binary values, strings, decimal or metadata bodies, and primitive wire arrays
whose encoded body is measured in bytes. For multi-byte primitive wire arrays,
compare the encoded byte count, not only the logical element count, with the
readable bytes.

1. Validate the encoded byte count in the serializer. For fixed-width primitive
   arrays, check overflow and element alignment before allocation, such as
   `wireByteCount % elementByteWidth == 0`, then derive the logical element
   count from the encoded byte count.
2. Call `checkReadableBytes(wireByteCount)` unconditionally before allocating
   the variable-length result. Buffer-backed inputs normally return from this
   check with only a bounds comparison. Stream-backed inputs use the same call;
   the byte owner handles the fast path when enough bytes are already buffered
   and otherwise fills the read buffer until the requested encoded body is
   readable or an input error is recorded.
3. After readability is proven, allocate the final value once and copy or decode
   from the current readable buffer into the final result.

`checkReadableBytes` is not an `ensureCapacity(wireByteCount)` operation. In
stream mode it may end with the byte owner holding the full encoded body in its
read buffer, but it must grow that buffer as bytes are successfully read from
the stream. It should grow from current proven buffer capacity, such as by
doubling current capacity, and cap only when that bounded growth step reaches
the immediate target. A byte owner may use an owner-local availability signal as
a one-shot growth hint when the stream implementation itself is caller-owned
trusted code; if that hint is absent or insufficient, it must fall back to
bounded growth from already buffered bytes. It must not reserve the
attacker-declared length before input bytes or an owner-local growth hint
justify that intermediate buffer capacity. The stream slow path may pay one
extra intermediate buffer copy; this is preferable to serializer-local chunk
accumulation and repeated final-container growth.

For byte-counted values, the serializer should not duplicate the byte owner's
fast-path branch by testing `availableBytes()` before calling
`checkReadableBytes`. Keeping that branch in the byte owner gives every language
the same correctness rule and keeps serializer hot paths focused on their own
wire semantics.

For primitive wire arrays:

- Compare and prove the encoded wire byte count, not only the logical element
  count.
- Keep compression, bit-packing, byte-order conversion, and other primitive
  array encoding semantics in the serializer. `checkReadableBytes` only proves
  that the encoded bytes are present.
- For compressed or transformed bodies, the serializer must still validate the
  decoded length and encoding-specific metadata before allocating or returning
  the final value.

The common serializer shape is:

```text
wireByteCount = readVarUint32()
elementWidth = primitiveWireElementWidth(kind)
validate wireByteCount and element alignment
elementCount = wireByteCount / elementWidth

ctx.checkReadableBytes(wireByteCount)
result = allocatePrimitiveResult(elementCount)
copy or decode wireByteCount bytes from the current readable buffer into result
advance the reader index by wireByteCount
return result
```

Byte values are the `elementWidth == 1` specialization of the same policy. In
that case the serializer shape is:

```text
byteCount = readVarUint32()

ctx.checkReadableBytes(byteCount)
result = allocateBytes(byteCount)
copy byteCount bytes from the current readable buffer into result
advance the reader index by byteCount
return result
```

This policy avoids three inefficient implementation shapes:

- allocating the complete final contiguous value before the encoded body is
  readable
- growing or repeatedly copying the final result container on stream slow paths
- adding serializer-local chunk buffers when the byte owner can prove
  readability once and expose a normal buffered read

Scratch buffers remain appropriate when the target representation is not a
direct byte target, such as string transcoding, compression, byte-order
conversion that is not performed in place, bit-packed values, or implementations whose
stream API cannot read into a caller-provided target.

For fixed-width primitive arrays, the final result must not become visible to
callers until the exact encoded byte count has been read successfully.

For list, set, map, and other container readers, the declared logical element
count is not an encoded byte count, so serializers must still own all element,
chunk, nullability, reference, and type-dispatch semantics. It is still the
right allocation proof for count-based preallocation: after validating a
non-empty count and reading any serializer-owned header or type metadata that
precedes allocation, call `checkReadableBytes(logicalCount)` before allocating,
reserving backing capacity, or size-hinting from that count. The byte owner
handles buffer versus stream readiness; the container serializer then allocates
with the declared count and reads elements through its normal owner path.

This check is not a full container-body validation. It only prevents a small or
truncated input from causing a large count-based preallocation. Chunk sizes,
duplicate keys, element value semantics, and protocol strictness remain owned by
the container/map serializer and should be validated only when they protect a
real owner invariant.

Materializing readers should also reserve a root-operation estimated graph
memory budget before allocation or size hinting. The budget state belongs to
`ReadContext` or the equivalent root read state, not to ambient thread-local
state. Root facades set or reset the per-operation budget only; they must not
pre-reserve root type or root self bytes. `maxGraphMemoryBytes` defaults to a
fixed `128 MiB`; positive configuration overrides the default; explicit
non-positive configuration is invalid and must be rejected during configuration or Fory instance
creation. Do not derive this budget from root input size, and do not add dynamic
stream bytes-read accounting for this budget.
Because the budget is fixed per root, read state should not mirror the
configured maximum into a second active-limit field. Use the existing
configuration, or one configured maximum field when the config is not otherwise
available, plus the mutable remaining budget.

Read context or equivalent read state owns only raw byte reservation. It must
not expose counted arithmetic helpers or collection, map, array, struct, or
object semantic reservation APIs. Concrete serializers and generated serializer
owners compute the storage constants and formulas for the owner path they
allocate, including counted-byte overflow checks.
Read state must not grow non-memory-budget APIs for this feature, including
ref-publication controls, temporary-owner controls, serializer-owner controls,
conversion helpers, or APIs that encode the kind of value being materialized.
Concrete serializers and generated serializers own those decisions.

The budget is an approximate gate for materialized graph owners, mainly
collections, maps, arrays, structs, and objects. It does not measure exact heap
bytes, and actual process memory can be higher. Reserve self storage exactly
once at the owner that stores or allocates the value. Root facades reset the
budget only and must not reserve root value storage. Reference-backed
containers, maps, sets, and
object/reference arrays reserve nonzero owner self cost plus reference slots;
each referenced heap owner then reserves its own shallow self cost when
materialized. Inline/value containers reserve element storage; inline/value maps
reserve key plus value storage; pointer, box, and dynamic materialization owners
reserve the heap or boxed storage they allocate. Value serializers, including
root and generated struct/product read paths, do not reserve their own self
storage. Struct/record/POJO/tuple, compatible, generated, and dynamic object
owners reserve a nonzero shallow self cost plus shallow field storage only in
reference-object implementations or dynamic/boxed materialization paths.
Parents must not recursively include child object, collection, map, string,
binary, or primitive dense-array contents. Skip enum/union as separate owners and
skip dedicated string, binary, primitive scalar, primitive array, and primitive
dense-array leaf owners, but do not skip general inline-value containers such as
vectors or lists of value objects. If reference slot size is not cheap or
reliable to query, use a 4-byte reference slot. Native-code implementations may use
conservative lower-bound estimates instead of guessing non-portable object,
container, allocator, table, node, entry, or debug-layout details. Reject
arithmetic overflow before budget comparison or allocation, and keep the
existing `checkReadableBytes` proof before backing
allocation or capacity reservation.
Skipped leaf owners must still be gated by remaining input bytes. If unread
bytes are insufficient for a string, binary value, primitive scalar, primitive
array, or primitive dense array, the reader must not read or create that leaf
value.

For TypeDef or TypeMeta bodies, first prove that the encoded metadata body bytes
are readable through the byte owner. Field-list allocation should happen after
that body readability check and should not use a separate small initial-capacity
cap as a security rule.

Implementations should also bound received metadata bodies and struct field
lists on the cold metadata parse path. `maxTypeMetaBytes` limits one encoded
TypeDef or TypeMeta body, excluding the 8-byte header and any extended-size
varint, and is checked before copying or decompressing that body.
`maxTypeFields` limits the number of fields declared by one received struct
metadata body and is checked before reserving or allocating the field list.
These limits are runtime resource controls; they do not change wire encoding,
type identity, dynamic loading, unknown-type behavior, deserialization policy,
or schema-evolution semantics. Metadata cache hits and generated field readers
remain hot paths and must not add work for these limits.

Remote schema-version limits belong to the same cold metadata owner path.
Header cache hits must skip the remaining metadata body and return cached
metadata without schema-limit checks, hash revalidation, allocation, or policy
work. The protocol-defined 52-bit TypeDef/TypeMeta header hash is the unique
schema identity. When the selected local type already owns the received header,
that is a local-schema hit: skip the body and use the local metadata without
comparing field arrays or encoded metadata bytes, publishing to the persistent
remote cache, or consuming a schema-version count. This applies to struct and
named enum, ext, and union metadata when those metadata bodies are present.
The low 12 header bits belong only to the current frame. A hit reads its current
size and optional extension for bounds and skip but does not validate current
reserved or compression flags; low-flag validation belongs to the cold miss.

A header miss enters the parse owner: prove and read the metadata body bytes,
validate the body against its header, validate field counts, and resolve the
type through the existing registration and deserialization-policy checks. When
no local header was available before parsing, this miss-only path may lazily
build local metadata and compare its 52-bit header hash with the validated
received hash. Hash equality selects local metadata without a byte or field
comparison and without consuming a remote schema-version count.
Otherwise, check schema-version limits, build the required read state, publish
to the persistent metadata cache, and then record the schema count. Failed or
incompatible metadata must not publish to the persistent cache and must not
consume schema-version counts. Pure id-based enum, ext, and typed-union values
do not carry TypeDef or TypeMeta bodies and must stay on the normal type-id plus
user-type-id path. Compatible named enum, ext, and union metadata normally has
one version, but it still counts against accepted remote metadata totals when it
is sent as shared metadata and is a non-local metadata miss. `maxTypeFields`
applies only to struct field lists.

The miss-only local candidate must be derived inside the metadata owner from
the decoded identity, after the existing class, registration, and policy
checks. Compare only its 52-bit header hash with the validated received hash.
Do not retain or compare metadata bytes or fields, thread extra expected-type
parameters through callers for revalidation, or add parallel accepted-header
state. Cache hits never repeat miss-time work.

In Java, the header hash identifies the wire schema, while a requested target class can require a
different `TypeInfo` for that same schema. Hash-only metadata caches and depth hints retain the
source `TypeInfo`. The existing target-conversion cache retains the result for each target class,
source class, and header hash. Local-schema selection occurs only on a metadata or target-conversion
cache miss; subsequent hits reuse the selected result without querying local TypeDef metadata.

When a statically declared compatible named enum, ext, or union field reads
shared metadata, the decoded metadata must match the declared type id,
namespace, and type name before the metadata owner publishes it to the
persistent cache or records a schema count. Already accepted header or reference
cache hits still skip the body and must not rerun body-hash, schema-limit, or
registration checks, but the field reader must not treat metadata for a
different declared named type as the current field's metadata. Route such hits
by the concrete metadata owner identity bound on the miss; do not inspect the
metadata body or repeat its namespace, type-name, user-id, or wire-type checks.

Skip paths do not need to materialize skipped values. Existing byte-skip
operations should consume any available buffered prefix first, then skip or drop
remaining stream bytes in bounded steps.

### Nested reads use `ReadContext`

Important rules:

- serializers that allocate the result object early must call
  `context.reference(obj)` before reading nested children that may refer back to
  it
- nested serializer flow should stay straight-line; do not add internal
  `try/finally` blocks just to restore operation-local state
- top-level `Fory.deserialize(...)` owns the operation reset `finally`

## Depth Tracking

`WriteContext` and `ReadContext` track logical object depth explicitly.
`increaseDepth()` enforces `Config.maxDepth`.

Depth should stay explicit on the contexts rather than relying on the native
call stack alone. At the same time, depth cleanup should not depend on nested
`try/finally` blocks throughout serializer code. Top-level context reset must be
able to recover operation-local state after failures.

## Struct Compatibility

Struct-specific schema/version framing and compatible-field layout belong in the
struct serializer layer, not on `Fory` and not on the public serializer API.

In Dart that internal owner is `StructSerializer`.

`StructSerializer` is responsible for:

- schema-hash framing when compatibility mode is off and version checks are on
- compatible-struct field remapping when compatibility mode is on
- caching compatible read layouts
- skipping unknown compatible fields
- passing compatible read layouts explicitly to generated serializers
- classifying matched compatible fields as exact direct reads, compatible
  conversions, or remote-only skips before generated dispatch

When `Config.compatible` is enabled and the struct is marked evolving:

- the wire type uses the compatible struct form
- the writer emits shared TypeDef metadata
- reads map incoming fields by identifier and skip unknown fields
- generated serializers apply matched fields directly while preserving their own
  object construction and default-value rules
- exact matched field schemas use the same direct read shape as same-schema
  reads and must not receive remote compatible metadata
- matched scalar fields may use compatible scalar conversion only when the
  layout has classified a remote/local top-level scalar pair as lossless
  convertible and both field schemas have `trackingRef = false`
- compatible scalar conversion applies only to the immediate matched field.
  Nested collection, array, map key, and map value schemas must not be accepted
  by recursively applying scalar conversion to child schemas.
- direct top-level `list<T?>` to dense `array<T>` matched fields must be
  classified as compatible when element domains match; the nullable element
  schema bit alone is not a schema-pair rejection. Actual null element payloads
  fail in the dense-array reader. Ref-tracked list-element framing is separate
  and may remain rejected when the implementation cannot materialize it without
  generic/reference paths.

When `compatible` is disabled and `checkStructVersion` is enabled:

- the writer emits the schema hash for struct payloads
- the read side checks that hash before reading fields

Compatible scalar conversion is owned by the compatible struct field reader or
the generated compatible layout action. Root facades, read/write contexts, type
resolvers, class resolvers, xlang type resolvers, and raw buffer utilities must
not expose public conversion APIs or carry conversion state. Resolvers may
provide field schema metadata for layout classification, but the conversion
decision and value adaptation stay with the serializer-owned compatible field
layout. Layout classification must reject top-level scalar conversions when
either matched schema has `trackingRef = true` and must reject same scalar type
pairs whose top-level `trackingRef` framing differs; converters must not add a
reference-table path for scalar mismatches. Recursive schema comparison inside
containers must reject scalar mismatches instead of reusing the top-level scalar
conversion matrix. Generated serializers should consume the classified layout
decision directly:

- source-generated serializers use the layout's matched-field dispatch key to
  select exact direct field code, compatible conversion code, or skip code
- regenerated serializers may instead compile a remote-schema-specific
  straight-line reader after classification, without a second outer matched-id
  switch, when the generated source still has pure direct, pure conversion, and
  explicit skip operations
- compatible scalar conversion cases must read the concrete remote wire scalar
  selected by classification and compose only the required lossless conversion;
  they must not call a generic runtime converter that redispatches by remote and
  local scalar type IDs, field descriptors, field names, or schema eligibility
  helpers

Same-schema readers with matching reference and null/optional framing must keep
direct scalar read paths without conversion branches or per-field conversion
objects. Same raw scalar types with different null/optional framing may still
use the compatible nullable/optional composition path when both fields are not
reference-tracked.

## Meta Strings And Shared Type Metadata

Two explicit pieces of state back xlang type metadata:

- `MetaStringWriter` and `MetaStringReader` deduplicate and decode namespace
  and type-name strings
- shared TypeDef write/read state tracks announced TypeDef metadata

Ownership rules:

- canonical encoded names live in `TypeResolver`
- per-operation dynamic meta-string ids live on `MetaStringWriter` and
  `MetaStringReader`
- shared type-definition tables are operation-local context state

For a large MetaString that carries a wire hash, that hash alone is its checked
cache identity. The body length belongs to the current frame: a hash cache hit
checks that many bytes are readable and skips them, but does not compare the
length or body with the cached value. Only a cache miss reads the body, verifies
the hash, and publishes the checked value.

## Enums In Xlang Mode

In xlang mode, enums are serialized by numeric tag, not by name.

In Java:

- the default tag is the declaration ordinal
- `@ForyEnumId` can override that with a stable explicit tag
- `serializeEnumByName(true)` affects native Java mode, not xlang mode

In C#, the enum's underlying numeric value is the xlang tag. Java peers for
sparse C# enums must declare matching `@ForyEnumId` values instead of relying
on declaration ordinals.

Other Fory implementations should preserve the same wire rule even if the configuration or
annotation surface differs.

A Rust data-carrying enum is an xlang union only when each known variant is unit
or carries exactly one alternative value and satisfies the union case rules.
Rust native mode (`xlang = false`) may additionally encode tuple or named
variants with multiple fields through its native enum format. Those
struct-style enum shapes have no implicit xlang mapping. Registration in xlang
mode must reject them on the cold schema/type-selection path before publishing
resolver state; generated code must not drop fields, synthesize an undeclared
variant struct, or fall back to EXT.

## Out-Of-Band Buffer Objects

Buffer-object handling follows the same split:

- one root bit advertises whether out-of-band buffers are in play
- nested buffer-object payloads still decide in-band vs out-of-band one value at
  a time
- serializers use read/write context helpers rather than bypassing the context layer

## Code Generation

The normal Dart integration path is:

1. annotate structs with `@ForyStruct`
2. annotate field overrides with `@ForyField`
3. run `build_runner`
4. call the generated per-library helper, such as
   `<InputFile>ForyModule.register(...)`, to bind private generated metadata and
   register generated types

Generated code should emit:

- private serializer classes
- private metadata constants
- a public per-library registration helper that users call from application code
- private generated installation helpers that keep serializer factories private

The public helper should be a thin generated wrapper around the Fory
registration API, not a public global registry or a second unrelated
registration API family.

### Dart Ordinary Struct Inheritance

Ordinary Dart `ForyStruct` inheritance is a code-generation-time field
discovery, normalization, access, construction, and flattening change. It does
not redesign the reference protocol.

For a concrete annotated child, the generator walks the instantiated
superclass and applied-mixin storage chain rather than only the child's direct
`element.fields`. Each layer's `InterfaceType.element.fields` exposes its
declared elements, including private declarations in another library. Dart
privacy controls which generated expression can access an element; it does not
control whether the element is discovered.

The storage collector:

1. visits the instantiated superclass;
2. visits actually applied mixins in application order;
3. visits the current class;
4. collects concrete instance storage declared by each layer exactly once.

It excludes `Object`, interfaces, mixin `on` constraints, abstract accessors,
static fields, external fields, and synthetic members that do not own storage.
A mixin slot is identified by its application site and declaring field, so
multiple applications cannot be collapsed by spelling or `baseElement`.

Every discovered storage field follows one pipeline:

```text
complete hierarchy discovery
  -> declaration-owned @ForyField(ignore: true)
  -> concrete-child ignoreInheritedPrivateFields policy
  -> concrete generic substitution
  -> direct or companion access resolution
  -> constructor validation
  -> one globally sorted child schema
```

`@ForyField(ignore: true)` is the declaration-owned, per-field omission.
After that check, a concrete child with
`ignoreInheritedPrivateFields: true` removes every private field declared by a
superclass or applied mixin, including same-library, cross-library, direct, and
transitive ancestors. It does not remove child-declared private fields or
inherited public fields. Both omission forms bypass substitution, access,
construction, wire identity, reference analysis, and codec work while their
physical slots remain in the concrete object's shallow graph-memory field
count. Any unresolved field that remains included is a generation error.

Ownership is fixed:

| Concern                          | Owner                                            |
| -------------------------------- | ------------------------------------------------ |
| Field identity and annotations   | Declaring storage field                          |
| Hierarchy discovery/substitution | Concrete-child generator                         |
| Inherited-private omission       | Concrete annotated child                         |
| Cross-library private permission | Public boundary in the field's declaring library |
| Schema, sort, and codec          | Concrete annotated child                         |
| Construction                     | Selected concrete-child generative constructor   |
| Reference analysis/publication   | Existing concrete-child serializer path          |
| Graph-memory self charge         | One concrete child object                        |
| External target field list       | Explicit external serializer declaration         |

The concrete-child omission option defaults to `false`, is not inherited from
ancestor annotations, and is valid only on an ordinary concrete declaration
that owns a flattened schema. It is invalid on external target declarations
and provider-only abstract, open-generic, or mixin boundaries. It is applied
after complete storage discovery and before generic substitution or access
resolution, so a matching field needs no direct access or companion even when
a companion exists.

For fields that remain included, public inherited fields and private inherited
fields declared in the child's library use direct generated access and require
no parent annotation. A private field declared in another library requires
`ForyStruct(exposePrivateFields: true)` on a public hierarchy boundary in that
field's declaring library. `exposePrivateFields` defaults to `false`, is
invalid with `ForyStruct.target`, and authorizes only provider-library access
generation. It does not enable discovery, alter same-library access, change
field inclusion, or let a consumer authorize another library's private state.

A public boundary may expose same-library private storage inherited from a
private class or mixin. If private fields come from several Dart libraries,
each declaring library must independently provide an opted-in public boundary
and visible companion. The nearest qualifying child-visible boundary in the
declaring library is selected.

The provider's `.fory.dart` part emits a public `@nodoc` typed static access
companion. It emits an exact getter for each exposed non-ignored private field,
a setter only for mutable storage, and no setter for `final` or `late final`
storage. The receiver is the public boundary type. Generic bounds and every
type nested in a public signature must be nameable outside the provider
library. The companion must not use `dynamic`, `Object?` bridge casts,
reflection, callbacks, runtime lookup, a parent serializer, or stored runtime
state. Companion generation is independent of every consumer child's
`ignoreInheritedPrivateFields` value. A concrete boundary may enable both
options: its own serializer applies the omission, while its provider companion
continues to expose the declaring library's eligible private storage.

The child source must have a direct import or re-export namespace that exposes
the public boundary and companion. Provider output must be generated and
published before a dependent package is built. Missing permission, a hidden or
ambiguous companion namespace, an unnameable signature, or dispatch that no
longer reaches the exact storage slot is a generation error. A child must also
validate the complete concrete hierarchy so field hiding below the boundary
cannot redirect a generated getter or setter to another slot.

Every included `final` or `late final` field must be initialized by a
statically proven identity flow:

```text
selected concrete-child constructor parameter
  -> redirect or super parameter
  -> exact storage field
```

Accepted edges are exact field formals, super formals, direct parameter
references in constructor field initializers, and direct parameter references
in redirecting or super-constructor arguments. Types must remain identical
after concrete generic substitution, including nullability. Calls, operators,
casts, null assertions, constants, constructor-body assignment, and matching
names without element identity are not proof. A declaration initializer on a
included final field is unsupported because the decoded value cannot own that
slot. There is no post-construction final write, reflection, or fallback.

Mutable fields connected to selected constructor parameters are initialized
once through the same exact identity flow. Remaining mutable fields require an
exact setter and are restored after construction. Required constructor
parameters must have one unambiguous field source. Optional named parameters
may be omitted; an omitted optional positional parameter cannot be followed by
a passed positional parameter. Constructor arguments and assignments are
matched by resolved storage-field identity rather than field-name strings. If
omission removes the only serialized source for a required parameter,
generation fails; the generator must not invent a value or relax identity
proof.

The concrete child owns one `GeneratedStructSchema`, one canonical field sort,
one serializer and descriptor cache, one reconstruction, one reference
publication path, and one graph-memory owner. Parent serializers are neither
nested nor invoked, and parent type registration is not required. A
separately annotated concrete parent has its own independently flattened
schema only for values of that exact type.

Included direct and inherited fields feed one normalized list into the
existing recursive reference analysis and `needsRootRef` calculation.
Included inherited `ref: true` and nested container metadata behave like
equivalent direct child fields; omitted fields do not enter that list.
Inheritance adds no reference state, `ReadContext` API, serializer signature,
call-contract change, runtime branch, slot, sentinel, callback, wrapper,
compatible-layout state, or parent reference owner. A failure shared by the
equivalent flat model is a separate reference-subsystem issue.

Java provides only the flattened-model comparison for this feature: its object
serializer extends one field-descriptor list across inheritance and adds no
inheritance-specific reference state. Java's existing reference contract uses
the `readRefIds` stack, real reference IDs, and its `-1` sentinel to associate
`reference(obj)` with the current serializer invocation. That contract differs
from Dart's current reference subsystem and is not a design to transplant as
part of Dart hierarchy support. Dart inheritance is required only to match its
own equivalent flat model.

Hierarchy traversal, substitution, access validation, and constructor proofs
run during generation. Existing flat serializers gain no runtime work, public
and same-library inherited fields emit the same direct operations as equivalent
flat fields, policy filtering emits no runtime check, and included
cross-library private fields add only an inlineable typed static companion
call. Generation must not introduce runtime hierarchy traversal, allocation,
callbacks, reflection, or parent dispatch.

The child's shallow graph-memory formula is:

```text
24 + 4 * actualConcreteStorageFieldCount
```

It counts every real inherited and ignored storage slot once and adds no parent
self charge.

Generated diagnostics must identify the concrete child, declaring field,
declaring library, and failed access or constructor path, then give an
actionable remedy. Typical remedies are adding
`exposePrivateFields: true` to a public owner-library boundary, importing its
generated companion, forwarding a constructor value unchanged, placing
`@ForyField(ignore: true)` on the field declaration, setting
`ignoreInheritedPrivateFields: true` on the concrete child when all private
ancestor state should be omitted, or using a custom serializer. Diagnostics
must state that hierarchy discovery is independent of cross-library access.

Changes to hierarchy storage, exposure boundaries, or
`ignoreInheritedPrivateFields` require regeneration of every affected
`.fory.dart` file. Compatible mode uses normal missing/unknown-field handling;
fixed-schema peers must change together. Implementations must not add a
special legacy reader, retain an alternative path limited to child
declarations, bind constructors only by name, use a late-final setter path,
fall back to another access model, or delegate to a parent serializer.

Acceptance requires that every hierarchy storage slot is either omitted by its
declaration, omitted by the concrete-child inherited-private policy, or
represented exactly once with a valid type, access path, wire identity, and
reconstruction path. Equivalent included flat and inherited models must
produce the same canonical schema, wire bytes, reference IDs, and round-trip
behavior. External target declarations remain explicit and unaffected.

### Dart External Structural Serializers

Dart external-type serialization extends `ForyStruct` with an optional
compile-time `target` and named generative `constructor`. The annotated
`abstract final` class is a schema declaration only. It has no runtime value or
registration identity.

The generator must analyze ordinary and external structs through one struct
model and one emitter. Private generated symbol names come from the declaration
name. Every generated serializer type position uses the target type: `Serializer<Target>`,
`GeneratedStructSchema<Target>`, read and write signatures, constructor calls,
schema `type`, and generated-module dispatch.

For each serialized declaration field, resolve an accessible target getter
with the same name and exact instantiated Dart type. Constructor parameters
and any post-construction setter must also match exactly. A public named
generative constructor is selected only when the annotation names it. Factory
constructors, abstract targets, open target types, and constructor-based
reference-tracked paths back to the target are rejected during generation.
The recursive check includes target elements, keys, and values nested in the
supported list, set, and map field metadata.

The external declaration's fields are the complete schema. It may explicitly
name an accessible property inherited by the target, but the generator does
not automatically scan the external target hierarchy for schema fields.
`exposePrivateFields` and `ignoreInheritedPrivateFields` are invalid on an
external declaration.

An external object's shallow graph-memory formula is the union of declaration
fields and public instance fields discovered on the target, its superclasses,
and applied mixins. A public target field represented by a declaration is
counted once. `@ForyField(ignore: true)` adds budget-only declaration storage
without target access, construction, metadata, or wire code.

Generated code reads getters and invokes target constructors or setters
directly. It must not allocate the declaration, copy values through an
intermediate object, invoke runtime callbacks, perform member-name lookup, or
branch on whether the struct is external. The existing generated struct,
registration, resolver, field, collection, map, compatible-read, and reference
paths remain the only runtime paths.

Registration is keyed by `Target` through the generated module and the existing
generated registration API. Direct roots, generated fields, dynamic values,
and recursive collection/map children resolve the same target registration.
Dart root collections retain their existing untyped outer shapes.

## Directory Layout

Under each Dart package `lib/` tree, only one nested source layer is allowed.

Allowed:

- `lib/fory.dart`
- `lib/src/<file>.dart`
- `lib/src/<area>/<file>.dart`

Not allowed:

- `lib/src/<area>/<subarea>/<file>.dart`

## Serializer Design Rules For New Implementations

Any new xlang implementation should follow these rules even if its surface API looks
different:

1. Keep root operations on the `Fory` facade and nested payload work on
   explicit read and write contexts.
2. Keep reference tracking behind dedicated read-side and write-side services
   so the disabled path stays cheap.
3. Make serializers payload-only. Type metadata, registration, and root
   framing belong to the `Fory` and type resolver layers.
4. Track per-operation state explicitly. Do not rely on ambient thread-local
   instance state.
5. Reserve read reference IDs before materializing new objects, and bind
   partially built objects as soon as a nested child may refer back to them.
6. Keep operation setup and operation cleanup separate. `prepare(...)` binds
   the current operation inputs, and `reset()` clears operation-local state.
7. Preserve the separation between the root bitmap, per-object ref flags, type
   headers, and payload bytes.
8. Keep internal naming in the serialization domain. Prefer words like
   `serializer`, `binding`, and `layout`; avoid RPC-style terms such as
   `session` or vague control-flow terms such as `plan`.
9. When the implementation language supports cold and no-inline annotations,
   keep error, cache-miss, schema-mismatch, unsupported-capability, and other
   cold entrances reachable from serializer hot paths out of hot-path
   inlining. Do not mark successful dynamic dispatch cold.
10. After any xlang protocol or ownership change, run the cross-language test
    matrix and update both this guide and
    [Xlang Serialization Spec](xlang_serialization_spec.md).

## Validation

For Dart implementation changes, run at minimum:

```bash
cd dart
dart run build_runner build
dart analyze
dart test
```

For generated consumer coverage, also run:

```bash
cd dart/packages/fory-test
dart run build_runner build
dart test
```
