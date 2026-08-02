---
title: Extra Fields
sidebar_position: 7
id: extra_fields
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

This page describes `ForyExtraFields` [Java], an opt-in mechanism for capturing and preserving fields
that exist in a writer's schema but are unknown to the reader's class.

## The Problem

In [compatible mode](schema-evolution.md#compatible-mode), a reader can deserialize a payload whose
writer schema differs from its own. When the writer includes a field that the reader's class does
not declare, that field is **silently dropped**.

## The Solution

A class opts in by declaring **a field** of type `ForyExtraFields`:

```java
import org.apache.fory.serializer.ForyExtraFields;

public class PersonView {
  public int age;                      // known field
  public ForyExtraFields extraFields;  // sink for everything else
}
```

When Fory deserializes a payload into `PersonView`:

- Fields that match the local class (`age`) are read normally.
- Every unmatched remote field is **captured** into `extraFields` instead of being discarded.
- If no fields are unmatched, `extraFields` stays `null` — the sink is only allocated on demand.

On re-serialization, Fory emits the **original (remote) schema** and replays every field — matched
fields from the object, unmatched fields from the sink — so a downstream peer with the full schema
recovers them exactly.

The field can be named anything; detection is by type, not name, and Fory walks the class hierarchy,
so the sink may also be inherited from a superclass.

A class uses a **single** sink. All of an object's unmatched fields are captured into one
name-keyed map, so a second `ForyExtraFields` field would have no distinct role to play — Fory uses
the first one it finds. This is a limit **per class, not per object
graph**: every object in a nested structure resolves its own sink from its own class, so a parent and
its nested children can each declare one and capture independently — see
[Nested and Collection Fields](#nested-and-collection-fields).

## Requirements

- **Compatible mode.** Extra-field capture depends on schema metadata, so it works only when
  compatible mode (the default).
  [same-schema mode](schema-evolution.md#same-schema-optimization).
- **A sink field** of type `ForyExtraFields` per class.
- **A mutable owner — not a record.** The sink is populated field-by-field while the object is being
  read, so the owner must exist before its fields are set. A `record` is built from its constructor
  arguments and is immutable, so there is no instance to capture into mid-read. See
  [Records Are Not Supported](#records-are-not-supported).
- Supported across the runtime interpreter, and the runtime JIT
  (`withCodegen(true)`).
  Cross-language (xlang) mode is not supported. Using `ForyExtraFields` under xlang mode will cause errors.

### Records Are Not Supported

Declaring a `ForyExtraFields` component on a `record` is **rejected** — Fory throws an
`UnsupportedOperationException` when it builds the serializer for that type (for both serialization
and deserialization, under the interpreter and the JIT), rather than silently dropping the extra
fields:

```java
// Rejected: ForyExtraFields on a record.
public record PersonView(int age, ForyExtraFields extraFields) {}
// -> UnsupportedOperationException: ForyExtraFields is not supported on records: ...
//    Extra-field capture requires a mutable owner; declare the ForyExtraFields sink
//    on a regular class instead.
```

Capture requires a mutable owner: the framework fills the sink as it reads each unmatched field, but
a record's fields can only be supplied all at once to its canonical constructor, and the instance
does not exist until then. Use a regular class instead.

## Constraints

Replay needs a Fory that has deserialized a payload carrying that schema and still
has it cached, ie the instance that produced this object.

## Reading Captured Fields

`ForyExtraFields` exposes read-only, name-keyed lookup of the captured values. The sink is populated by
Fory during deserialization; application code reads from it by field name:

```java
byte[] bytes = /* payload written by a full-schema peer */;
PersonView view = fory.deserialize(bytes, PersonView.class);

if (view.extraFields != null) {
  // Field values are keyed by their original field name.
  String name = (String) view.extraFields.get("name");

  // getOrDefault avoids a null value for absent keys.
  int score = (Integer) view.extraFields.getOrDefault("score", 0);

  // Presence check — distinguishes "captured as null" from "not captured".
  boolean hasName = view.extraFields.containsKey("name");

  // Print the captured values.
  System.out.println(name);
  System.out.println(score);
}
```

| Method                    | Description                                                       |
| ------------------------- | ----------------------------------------------------------------- |
| `get(name)`               | Captured value for `name`, or `null` if absent (or captured null) |
| `getOrDefault(name, def)` | Captured value, or `def` if the key is absent                     |
| `containsKey(name)`       | Whether a value was captured for `name` (true even if it is null) |
| `isEmpty()`               | Whether no fields were captured                                   |
| `getTypeDef()`            | The remote schema (`TypeDef`) the fields were captured from       |

Notes:

- **Primitives are boxed.** A captured `int` reads back as an `Integer`, a `long` as a `Long`, and
  so on, because the map holds `Object` values.
- **`null` values are preserved.** A field that was written as `null` is captured as a `null` entry
  (not omitted), so `containsKey` returns `true` while `get` returns `null`. On re-serialization it
  is replayed as `null`.
- The map is **read-only** to application code; there is no public way to add or mutate captured
  entries.

## Forwarding: Round-Trip Recovery

When a partial-schema peer re-serializes an object
whose sink is populated, Fory writes the object under the **original remote schema** so the next peer
sees byte layout identical to what the original writer would have produced:

```java
// --- A: full-schema writer ---
Fory foryA = Fory.builder().withXlang(false).withCompatible(true).build();
Upstream original = new Upstream(/* f0..f9 */);
byte[] aToB = foryA.serialize(original);

// --- B: partial-schema intermediary with a sink ---
Fory foryB = Fory.builder().withXlang(false).withCompatible(true).build();
DownstreamWithSink b = foryB.deserialize(aToB, DownstreamWithSink.class);
// b knows f0..f8; f9 was captured into b.extraFields.

byte[] bToC = foryB.serialize(b);   // replays the full A-schema, including f9

// --- C: full-schema receiver recovers everything ---
Fory foryC = Fory.builder().withXlang(false).withCompatible(true).build();
Upstream recovered = foryC.deserialize(bToC, Upstream.class);
// recovered.f9 equals original.f9
```

This works across **multi-hop chains**. Each hop matches the fields it knows and
captures the rest, and every hop re-stamps the original schema, so a peer several hops downstream
still recovers the full object.

### Reference Sharing

If the original writer had two fields aliasing the same object, that identity is preserved through
capture and replay

```java
DownstreamRefSink result = fory.deserialize(bytes, DownstreamRefSink.class);
Object shared = result.extraFields.get("shared");
Object alias  = result.extraFields.get("alias");
assert shared == alias;   // same instance, identity preserved
```

### Nested and Collection Fields

Capture works with Fory's existing type system. Unknown fields may themselves be objects, collections, arrays, or nested structures; they are captured and replayed using Fory's normal serialization mechanisms.

The "one sink per class" limit is per class rather than per object graph, **each object in a
nested structure captures into its own sink**, resolved from its own class. A parent with no sink can
hold a child that has one, siblings can each capture independently, and this works to any depth:

```java
// Full graph:  Outer → Middle → Inner{f0, f1}
// Partial graph the reader knows:  Outer → Middle → Inner{f0, ForyExtraFields}
OuterPartial b = fory.deserialize(bytes, OuterPartial.class);

// The unknown Inner.f1 lands in the innermost object's own sink:
Object f1 = b.middle.inner.extraFields.get("f1");
```

Each sink preserves the remote schema of the object it belongs to, so re-serializing the whole graph
replays every level correctly. This is exercised directly in `ForyExtraFieldsTest` — see
`roundTripTripleNestedObjectWithInnerSink` (three levels deep), and
`roundTripMultipleNestedObjectsWithOwnSinks` (two sibling children capturing independently).

### Converted (Type-Mismatched) Fields

[Compatible mode](schema-evolution.md#compatible-mode) can bridge some scalar type mismatches
between the writer and reader — for example a remote `int score` deserializing into a local `long
score`. Such a field is **matched** (the reader does declare `score`), but reached through a scalar
converter rather than a direct field write, because the reader's field type differs from the wire
type.

When a converter field is combined with a sink, Fory captures the field **twice**, for two
different purposes:

- The local field (`score`) holds the converted value (`95` as a `long`), for application code to
  read normally.
- The sink **also** preserves the untouched, remote-typed value (`95` as the wire's original
  `int`), keyed by the field's identity like any other captured field.

The sink copy exists purely for replay. Re-deriving the remote value from the local one at
re-serialization time is not always exact — a remote `BigDecimal("1.0")` converted to a local `int`
loses the original scale, and reconverting `1` back to `BigDecimal` would produce `"1"`, not
`"1.0"`. Capturing the pre-conversion value sidesteps that: replay always emits the exact original
bytes, never a value re-derived from a lossy local conversion.

```java
// Remote: age, score (int), bonus (int, unknown to the reader)
Fory foryA = Fory.builder().withXlang(false).withCompatible(true).build();
byte[] aToB = foryA.serialize(new UpstreamConverted(30, 95, 7));

// Local: age matches, score converts int -> long, bonus is captured
public class DownstreamConvertedSink {
  public int age;
  public long score;              // converter field: reads the remote int, converts to long
  public ForyExtraFields extraFields;
}

Fory foryB = Fory.builder().withXlang(false).withCompatible(true).build();
DownstreamConvertedSink b = foryB.deserialize(aToB, DownstreamConvertedSink.class);
b.score;   // 95L -- the converted value, for application code

// The sink holds the untouched remote value alongside it:
ForyExtraFields.FieldIdentity scoreId =
    ForyExtraFields.FieldIdentity.of(UpstreamConverted.class.getName(), "score");
b.extraFields.get(scoreId);   // 95 (an Integer) -- the original remote-typed value

byte[] bToC = foryB.serialize(b);   // replays the exact original int, plus bonus
```

Replaying a converter field this way needs the target object at read time to capture the
pre-conversion value into the sink. Both the interpreter and the JIT-compiled (codegen) reader
provide it, for every converter field remote type — non-nullable primitives, nullable/boxed
scalars, `BigDecimal`, `String`, the unsigned wrapper types, and `Float16`/`BFloat16` alike.

One narrower constraint remains, independent of codegen vs. the interpreter: a converter field
combined with a sink is schema-incompatible when the remote type is reference-tracked (e.g.
`BigDecimal` under `withRefTracking(true)`) but the local converted type is a primitive-family type
(`int`, `long`, `String`, etc.) that can never carry ref-tracking itself. That combination fails.

## Future Work

Adiding Extra-field capture for
the annotation processor, Scala macros, and KSP is planned for a follow-up change.

AI Usage Disclosure

- substantial_ai_assistance: yes
- scope: docs, code drafting, tests, design drafting
- affected_files_or_subsystems:
  - docs/guide/java/extra-fields.md
  - builder/BaseObjectCodecBuilder
  - builder/CompatibleCodecBuilder
  - builder/ObjectCodecBuilder
  - builder/StaticCompatibleCodecBuilder
  - context/MetaWriteContext
  - context/WriteContext
  - resolver/TypeInfo
  - resolver/TypeResolver
  - serializer/CompatibleLayerSerializerBase
  - serializer/CompatibleSerializer
  - serializer/ForyExtraFields
  - serializer/ForyExtraFieldsSupport
  - serializer/UnknownClassSerializers
  - serializer/converter/FieldConverters
  - serializer/CompatibleFieldConvertTest
  - serializer/ForyExtraFieldsTest
  - serializer/GraphMemoryBudgetTest
  - builder/StaticCompatibleCodecBuilderTest
  - native-image.properties
- ai_review: Line-by-line review of serializer logic and field capture/replay mechanics completed. Architecture validated against compatible-mode constraints and reference-tracking edge cases. Documentation reviewed for clarity and completeness across nested structures, converter fields, and round-trip scenarios.
- ai_review_artifacts:https://claude.ai/code/session_01JAyGgABzsVxarBkJ6zq9Tk , https://claude.ai/code/session_01VA4BrfmpXTVmGKiyu3Sv1t
- human_verification: mvn -T10 clean test
- performance_verification: N/A (feature addition with no performance regression expected; benchmarks validated in test suite)
- provenance_license_confirmation: Apache-2.0-compatible provenance confirmed; no incompatible third-party code introduced
