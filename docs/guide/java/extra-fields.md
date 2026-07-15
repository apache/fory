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
- Supported across the runtime interpreter, and the runtime JIT
  (`withCodegen(true)`).
  Cross-language (xlang) mode is not supported. Using `ForyExtraFields` under xlang mode will cause errors.

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

## Future Work

Adiding Extra-field capture for
the annotation processor, Scala macros, and KSP is planned for a follow-up change.
