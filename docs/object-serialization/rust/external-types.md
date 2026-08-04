---
title: External-Type Serialization
sidebar_position: 10
id: external-types
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

Rust external-type serialization lets an application serialize a type owned by
another crate without a wrapper value. Use a derived external structural
serializer for an accessible public schema, or a custom serializer for an
opaque or invariant-bearing type.

## One serializer model

A serializer implementation declares the Rust value type it handles:

```rust
pub trait Serializer {
    type Target;

    fn write_data(
        value: &Self::Target,
        context: &mut WriteContext,
    ) -> Result<(), Error>;

    fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error>;
}
```

For an ordinary local type, the type provides itself. For an external type, a
local serializer supplies the behavior without wrapping or converting the
application value. These methods handle body data; Fory's complete-value
`write` and `read` operations add the requested reference and type-information
envelopes.

When a root is a container such as `Vec<third_party::User>`, compose
Fory-owned carrier serializers such as `VecSerializer<UserSerializer>`. These are
compile-time types and are never instantiated.

There is no separate public field serializer, adapter, encoder, or decoder
trait.

## External structural serializers

Use an external structural serializer when every target field or enum variant is publicly
accessible and the target can be constructed directly with the same shape:

```rust
use fory::ForyStruct;

#[derive(ForyStruct)]
#[fory(target = third_party::User)]
struct UserSerializer {
    name: String,
    age: u32,
}
```

The external structural serializer is a schema and code-generation declaration.
Fory never constructs a `UserSerializer` value. Generated code reads fields
from `third_party::User` and constructs `third_party::User` directly.

For an external enum or union, the external structural serializer declaration
defines the wire schema. In xlang mode, explicit Fory IDs and declaration-order
fallbacks define ENUM or UNION tags. Native data enums retain the existing
local `ForyUnion` tag rules; external-type serialization does not give explicit
IDs a new native meaning. The external enum's source declaration order does
not define the schema. Generated exhaustive matching still verifies the target
variant names and shapes.

External structural serializers for structs and pure enums retain the existing
STRUCT and ENUM formats. Xlang-compatible data enums retain UNION, while
native struct-style enums retain the existing `xlang = false` Rust enum format.
The serializer declaration does not appear on the wire.

Private, invariant-bearing, or `#[non_exhaustive]` targets are not eligible for
an external structural serializer.

An xlang-compatible data enum uses `ForyUnion` and the existing union case
rules:

```rust
use fory::{ForyUnion, UnknownCase};

#[derive(ForyUnion)]
#[fory(target = third_party::Value<UnknownCase>)]
enum ValueSerializer {
    #[fory(default)]
    Null,
    Text(String),
    Count(i64),
    #[fory(unknown)]
    Unknown(UnknownCase),
}
```

The target must represent the same runtime unknown carrier for lossless xlang
union reads. A third-party crate can remain independent of Fory by declaring a
generic enum such as `Value<U>`; the application sets the serializer target to
`Value<UnknownCase>`. A target that cannot carry unknown cases is not eligible
for lossless external xlang-union serialization.

### Native struct-style enums

With `xlang = false`, an external structural serializer supports the complete
Rust data-enum surface: unit variants, tuple variants with one or more fields,
named variants with one or more fields, and any mixture of those shapes.

```rust
use fory::ForyUnion;

#[derive(ForyUnion)]
#[fory(target = third_party::Command)]
enum CommandSerializer {
    #[fory(default)]
    Idle,
    Create {
        #[fory(with = UuidSerializer)]
        id: uuid::Uuid,
        label: String,
    },
    Move(i32, i32),
}
```

`ForyUnion` remains the one derive for data-carrying Rust enums; there is no
separate native-enum macro. Direct, carrier, list-element, map key/value, and
heterogeneous tuple-position serializer annotations also work inside tuple and
named variant payloads.
Native struct-style enums work with both compatible and schema-consistent
native serialization.

An xlang union case can carry at most one alternative value. Consequently, a
multi-field tuple or named Rust variant is native-only. Registering a serializer
for such an enum while `xlang = true` returns an error before registration is
published. Fory does not discard fields, synthesize hidden variant structs, or
silently encode the type as EXT.

## Custom serializers

Use a custom serializer for opaque types such as UUID or Jiff values:

```rust
use fory::{Error, ReadContext, Serializer, WriteContext};
use std::any::Any;
use std::sync::Arc;

struct UuidSerializer;

#[cold]
#[inline(never)]
fn invalid_uuid(error: uuid::Error) -> Error {
    Error::invalid_data(error.to_string())
}

impl Serializer for UuidSerializer {
    type Target = uuid::Uuid;

    fn write_data(
        value: &uuid::Uuid,
        context: &mut WriteContext,
    ) -> Result<(), Error> {
        context.writer.write_bytes(value.as_bytes());
        Ok(())
    }

    fn read_data(context: &mut ReadContext) -> Result<uuid::Uuid, Error> {
        let bytes = context.reader.read_bytes(16)?;
        uuid::Uuid::from_slice(bytes).map_err(invalid_uuid)
    }

    // Required only for sync Arc dynamic carriers.
    fn read_arc_any(
        context: &mut ReadContext,
    ) -> Result<Arc<dyn Any + Send + Sync>, Error> {
        let value: Arc<dyn Any + Send + Sync> =
            Arc::new(Self::read_data(context)?);
        Ok(value)
    }
}
```

Custom serializers use the existing EXT or NAMED_EXT wire kinds. Their internal
body evolution is application-owned. If a body length controls an allocation,
the custom serializer must use the read context's byte-availability and graph-memory
checks before reserving or allocating that storage.

When a custom serializer is composed under a variable-size carrier, the
carrier's complete bytes after its count must average at least one byte per
declared element or map entry. Fory checks this once after writing the carrier
and returns an error for a body that is too compact for the paired
allocation-safety check. Fixed arrays, and zero-sized elements in `Vec`,
`VecDeque`, or `BinaryHeap`, do not allocate storage from that count and are
exempt.

Fory invokes the optional `read_arc_any` method only after reserving the final
Arc owner; the method shown above performs that allocation once and must not
reserve the same outer owner again. A custom serializer that does not need sync
Arc dynamic carriers omits it and remains valid for typed, Box, and Rc uses;
attempting sync Arc materialization then returns an unsupported error.

## Fields and nested values

A static external value names its serializer explicitly:

```rust
use fory::{ForyStruct, MutexSerializer, RefCellSerializer};
use std::cell::RefCell;
use std::sync::Mutex;

#[derive(ForyStruct)]
struct Request {
    #[fory(with = UuidSerializer)]
    request_id: uuid::Uuid,

    #[fory(with = UserSerializer)]
    user: third_party::User,

    #[fory(with = RefCellSerializer<UserSerializer>)]
    mutable_user: RefCell<third_party::User>,

    #[fory(with = MutexSerializer<UserSerializer>)]
    locked_user: Mutex<third_party::User>,
}
```

`with` selects one serializer whose target is the exact declared field type.
For `Option`, `Box`, `Rc`, `Arc`, Fory weak-reference carriers, `RefCell`, and
`Mutex`, select the corresponding carrier serializer around the child
serializer. For example, use `OptionSerializer<UserSerializer>` for
`Option<third_party::User>` and `ArcSerializer<UserSerializer>` for
`Arc<third_party::User>`. `RefCell` and `Mutex` retain the child wire shape;
serialization of these direct fields uses one borrow or lock, and
deserialization constructs the holder directly without an additional heap
owner. Compatible schema evolution remains active for an external structural
serializer nested behind any of these wrappers.

In a non-skipped derived field, keep every carrier constructor's canonical name
visible, optionally with its module path. This applies both to the field's Rust
type and to a `with` serializer tree. For example, write
`Vec<third_party::User>` and `VecSerializer<UserSerializer>` directly instead
of hiding either carrier tree behind a type alias or renamed import. Leaf
serializer aliases remain valid. Carrier aliases also remain valid at roots,
where Rust resolves the serializer type without field code generation. A
skipped field may also use a carrier alias because it emits no field schema and
uses only the selected serializer's default.

A skipped external field can use `#[fory(skip, with = UserSerializer)]`. In that
case the serializer is used only for its fallible construction default; the
field contributes no schema or body bytes. Registration is unnecessary when
that skipped default is the serializer's only use. The default receives the
active read context and must reserve graph memory before any owned allocation.
Skipped list/map/tuple fields can retain recursive child serializer annotations,
although an empty container default does not invoke its children.

List elements, map children, and tuple positions select serializers at their own
recursive schema nodes:

```rust
use fory::{ForyStruct, VecSerializer};
use std::collections::HashMap;

#[derive(ForyStruct)]
struct Directory {
    #[fory(with = VecSerializer<UserSerializer>)]
    direct_users: Vec<third_party::User>,

    #[fory(list(element(with = UserSerializer)))]
    users: Vec<third_party::User>,

    #[fory(map(
        key(with = ExternalKeySerializer),
        value(with = UserSerializer)
    ))]
    by_key: HashMap<third_party::Key, third_party::User>,

    #[fory(tuple(
        element(index = 0, with = ExternalKeySerializer),
        element(index = 1, list(element(with = UserSerializer)))
    ))]
    entry: (third_party::Key, Vec<third_party::User>),
}
```

Tuple indexes are zero-based. Unmentioned tuple positions use their ordinary
serializer, and tuple elements can contain the same recursive list, map, or
tuple metadata. Tuples are supported from arity 1 through 22.

Recursive field selection covers `Vec`, `VecDeque`, `LinkedList`, `HashSet`,
`BTreeSet`, `BinaryHeap`, fixed arrays, `HashMap`, `BTreeMap`, and tuple
arities 1 through 22. `direct_users` selects a carrier serializer for the exact
Vec node; `users` selects the element serializer recursively. Both forms use the
same built-in Vec implementation and structural LIST representation. A serializer
annotation applies only to its declared node; it never silently propagates
through a composite.

A node-local `#[fory(with = PackedUsersSerializer)]` may instead select one
custom serializer whose exact target is the whole `Vec`, map, set, array, or
tuple. That field uses opaque EXT/NAMED_EXT encoding and has no child schema.
It cannot also use `list`, `map`, `tuple`, `array`, `bytes`, or `encoding`.
Recursive list/map/tuple annotations are the distinct choice when the composite
should retain its structural wire kind and only its children need selected
serializers. This opaque customization is not a user-declared carrier
serializer and cannot compose child serializers.

If that exact whole-container custom serializer is registered, erased polymorphic values
of the exact target use it. Ordinary unannotated typed fields and roots whose
children self-provide keep using the built-in structural container format;
explicit carrier serializer roots also keep their declared structural format.
Registration does not redirect those static paths.

## Root values

Ordinary local roots retain `serialize`, `serialize_to`, `deserialize`, and
`deserialize_from`.

An external root selects its serializer:

```rust
let bytes = fory.serialize_with::<UserSerializer>(&user)?;
let decoded =
    fory.deserialize_with::<UserSerializer>(&bytes)?;
```

The complete serializer-selected root family is:

- `serialize_with`;
- `serialize_to_with`;
- `deserialize_with`;
- `deserialize_from_with`.

The serializer is a compile-time type parameter. No serializer instance, wrapper,
or mirror value is passed at runtime.

For a container root, compose the corresponding Fory-owned carrier serializer:

```rust
use fory::VecSerializer;

fory.register::<UserSerializer>(100)?;

let users: Vec<third_party::User> = load_users();
let bytes =
    fory.serialize_with::<VecSerializer<UserSerializer>>(&users)?;
let decoded: Vec<third_party::User> =
    fory.deserialize_with::<VecSerializer<UserSerializer>>(&bytes)?;
```

`VecSerializer<UserSerializer>` targets `Vec<third_party::User>` and keeps the
normal LIST format. Register selected external structural serializers such as
`UserSerializer`; do not register the carrier serializer itself.

Map keys, map values, and nested containers compose recursively:

```rust
use fory::{HashMapSerializer, VecSerializer};

type DirectorySerializer =
    HashMapSerializer<ExternalKeySerializer, VecSerializer<UserSerializer>>;

let bytes =
    fory.serialize_with::<DirectorySerializer>(&directory)?;
```

For nested lists, `VecSerializer<VecSerializer<UserSerializer>>` targets
`Vec<Vec<third_party::User>>`.

Heterogeneous tuple roots compose by position:

```rust
use fory::Tuple2Serializer;

type EntrySerializer =
    Tuple2Serializer<String, UserSerializer>;

let bytes =
    fory.serialize_with::<EntrySerializer>(&entry)?;
```

`EntrySerializer` targets `(String, third_party::User)`. The tuple carrier serializer
keeps the same native or heterogeneous LIST representation as an ordinary
two-position tuple.

The complete carrier serializer family is:

- `OptionSerializer`, `BoxSerializer`, `RcSerializer`, `ArcSerializer`,
  `RcWeakSerializer`, `ArcWeakSerializer`, `RefCellSerializer`, and
  `MutexSerializer`;
- `VecSerializer`, `VecDequeSerializer`, `LinkedListSerializer`,
  `HashSetSerializer`, `BTreeSetSerializer`, `BinaryHeapSerializer`, and
  `ArraySerializer`;
- `HashMapSerializer` and `BTreeMapSerializer`;
- `Tuple1Serializer` through `Tuple22Serializer`.

There is no `Tuple0Serializer`: `()` has no child and remains self-provided.
`PhantomData<T>` also has no serialized child and needs no serializer
composition.

`Cell<T>` is not a currently supported Rust serialization carrier and does not
have a `CellSerializer`. `RefCell<T>` is the supported interior-mutability
carrier. Standard-library `Weak<T>` is also not an alias for Fory's
`RcWeak<T>` or `ArcWeak<T>`.

Every child argument is another serializer type. It can be an ordinary serializer targeting itself,
an external structural serializer, a custom leaf serializer, or another Fory-owned carrier
serializer. All four forms preserve the carrier's built-in encoding. An ordinary
local type is its own serializer, so
`HashMapSerializer<String, UserSerializer>` targets
`HashMap<String, third_party::User>`.
`ArraySerializer<S, N>` uses the same carrier selection as the chosen child
serializer. A canonical primitive serializer such as `i32` retains its
dense-array format, while an external structural or custom child serializer
uses LIST even if its target is a primitive Rust type.

`VecSerializer<S>` also preserves the ordinary Vec format selected by its
child. `VecSerializer<i32>` uses `INT32_ARRAY`,
`VecSerializer<u8>` uses `BINARY`, and
`VecSerializer<UserSerializer>` uses LIST. A nested
`VecSerializer<VecSerializer<i32>>` uses an outer LIST whose element type is
the ordinary `INT32_ARRAY` representation. Serializer composition never turns a
canonical primitive vector into an object LIST.

Generated fields keep their declared field schema. An unannotated `Vec<i32>`
field is `LIST<VARINT32>`, and
`#[fory(list(element(encoding = fixed)))] Vec<i32>` is `LIST<INT32>`.
`#[fory(array)]` selects a dense array and `#[fory(bytes)]` selects BINARY. All
of these forms, including `VecSerializer<S>` roots, preserve the built-in Vec
encoding selected by their declared schema.

Fory never infers a serializer composition from a target type or from
registration. Name every external child serializer explicitly in the carrier serializer
type. A field can select an exact carrier serializer with `with`, such as
`#[fory(with = VecSerializer<UserSerializer>)]`. Use the inline
`list(element(...))` or `map(key(...), value(...))` grammar, or indexed
`tuple(element(...))` grammar, when serializers are selected at recursive child
nodes.

An exact whole-container or whole-tuple custom serializer remains a valid opaque
root, but it uses EXT/NAMED_EXT instead of the built-in structural
representation and does not select child serializers.

The `_with` method suffix means its generic argument selects the serializer,
matching `#[fory(with = UserSerializer)]`.

## Registration

Structural and custom serializers use the existing registration categories.
Choose one identity style for each serializer.

Numeric registration:

```rust
fory.register::<UserSerializer>(100)?;
fory.register::<CommandSerializer>(101)?; // Native struct-style enum.
fory.register_union::<ValueSerializer>(102)?; // Xlang-compatible union.
fory.register_serializer::<UuidSerializer>(103)?;
```

Name registration:

```rust
fory.register_by_name::<UserSerializer>("example.User")?;
fory.register_by_name::<CommandSerializer>("example.Command")?;
fory.register_union_by_name::<ValueSerializer>("example.Value")?;
fory.register_serializer_by_name::<UuidSerializer>("example.Uuid")?;
```

Registration associates the wire identity with the serializer's target. One
`Fory` instance accepts only one registered dynamic serializer for a given
target. Registering it does not replace an ordinary static serializer or
implicitly select it for an unannotated field or root. Complete registration
before the first serialization operation. Register each structural or custom
external child; never register a carrier serializer.

Carrier composition does not make the entire container a dynamic `Any` target.
If the exact whole container must participate in erased dynamic dispatch,
register a custom exact-target serializer and use its EXT/NAMED_EXT
representation.

## Polymorphism

Registered external targets participate in `Box<dyn Any>` and `Rc<dyn Any>`
through their target identity. A `Send + Sync` target also participates in
`Arc<dyn Any + Send + Sync>` when its structural derive generated the Arc
materializer or its custom serializer implemented `read_arc_any`.

These erased carriers work in native and xlang modes when the selected concrete
serializer supports that mode. The Rust carrier and application trait name are
not written; xlang mode still requires each concrete target to have an
xlang-representable structural or EXT identity.

Application traits extend a small object-safe `ForyObject` trait instead of
`Serializer`:

```rust
use fory::{register_trait_type, ForyObject};

trait Animal: ForyObject {
    fn name(&self) -> &str;
}

register_trait_type!(Animal, Dog, third_party::Cat);
```

The concrete target list is closed and explicit. Each target can use an
ordinary serializer, an external structural serializer, or a custom serializer.

For `Arc<dyn Trait>`, the sync form requires the trait and listed targets to
implement `Send + Sync`:

```rust
use fory::{register_trait_type, ForyObject};

trait Animal: ForyObject + Send + Sync {
    fn name(&self) -> &str;
}

register_trait_type!(sync Animal, Dog, third_party::Cat);
```

Use either the non-sync or sync declaration and macro invocation for a given
trait, not both.

Generated trait-root serializer names are private to the macro's module by
default. A library can export them with
`register_trait_type!(pub Animal, ...)` or
`register_trait_type!(pub sync Animal, ...)`; the trait and listed targets must
be visible at least as broadly.

`Box<dyn Animal>` remains a direct root. The macro generates zero-sized
serializer types for `Rc<dyn Animal>` and, for the sync macro form,
`Arc<dyn Animal>` roots. It does not generate Rc/Arc value wrappers or perform
wrapper conversions.

The trait carrier serializers are not registered by ID or name. Applications
register each allowed concrete target through its ordinary serializer,
external structural serializer, or custom serializer.

## Related Topics

- [Custom Serializers](custom-serializers.md)
- [Polymorphism](polymorphism.md)
- [Type Registration](type-registration.md)
- [Schema Evolution](schema-evolution.md)
