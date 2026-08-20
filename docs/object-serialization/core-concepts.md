---
title: Core Concepts
sidebar_position: 1
id: core-concepts
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

Fory object serialization turns an object graph into bytes and reconstructs that graph later. The
same concepts apply to the default [xlang mode](xlang.md) and to supported
[native modes](native.md); the selected mode determines which types and wire rules are available.

## Object graphs

A root value may contain scalar fields, collections, maps, nested objects, repeated references, and
cycles. Serialization walks that graph from the root. Deserialization creates a new graph from the
encoded type and field data.

This is different from serializing a row or a JSON document. Object serialization can preserve
concrete object types and object identity so the reader can reconstruct application objects rather than
only values. Use [Row Format](../row-format/index.md) for trusted analytical rows and
[Fory JSON](../json/index.md) for JSON interchange.

## Fory instances and registration

A Fory instance owns its mode, schema behavior, reference settings, registered types, custom
serializers, and read limits. Configure and register the instance before its first root
serialization or deserialization operation, then reuse it. Registration is frozen after the first
root operation so the same instance always resolves a type in the same way.

Thread-safety differs by Fory implementation. Some implementations provide a thread-safe wrapper or pool; others use
one instance per thread or task. Follow the selected language guide instead of sharing an ordinary
instance without checking its concurrency contract.

## Types and type identity

Built-in types have identities owned by Fory. Application structs, classes, enums, unions, and
extension types use a registered numeric ID or name. Type identity answers _which serializer and
model should read this value_; a field schema describes _what data that model contains_.

A statically known field can use its declared type directly. A dynamic field also carries the
concrete type needed for interfaces, abstract classes, trait objects, broad object types,
or heterogeneous values. Dynamic typing is more flexible but requires every possible concrete type
to be registered and supported by the selected mode.

In xlang mode, peers must coordinate the same portable type identity and mapping. Native mode may
use implementation-specific identities and language-specific types. See
[Xlang Serialization](xlang.md) for the portable rules and each language's Type Registration page
for its exact API.

## Schemas and evolution

A schema describes the fields and nested types of a structured value. Compatible mode carries
metadata that lets a reader handle supported additions, removals, reordering, and type adaptations.
Use it when readers and writers may deploy independently.

Same-schema mode assumes both sides use the same type identity, fields, nested types, nullability,
and reference metadata. It reduces metadata and payload size, but a schema mismatch is an error.
Use it only when one release process keeps every reader and writer aligned.

Field IDs or names should remain stable after a contract is published. Renaming or reusing an
identity can turn an intended evolution into a different field or type.

## Nullability

Nullability determines whether a value position may contain no value. Languages express it through
nullable references, option types, pointers, annotations, or schema metadata. A nullable field is
not the same as a field whose value happens to use a default.

Keep nullability consistent across readers and writers. Compatible mode can handle documented
nullable and missing-field cases, but it cannot place a remote null into a local carrier that has
no valid null or missing-value representation.

## Reference tracking

Reference tracking preserves object identity. Enable it when a graph contains the same object more
than once or contains a cycle. Without reference tracking, repeated values may become separate
objects and cycles may recurse until the operation fails.

Leave reference tracking disabled for value-shaped, acyclic data when identity does not matter; it
adds per-object metadata and lookup work. Some Fory implementations combine a global setting with field-level
metadata, so use the language-specific References or Basic Serialization page for exact behavior.

## Polymorphism

Polymorphism stores the concrete type of a value whose declared position is broader. The reader
must know and accept that concrete type, and the type must be representable in the selected mode.

Host-language inheritance alone does not create a portable contract. For cross-language data,
model only alternatives that have xlang mappings on every peer. For data within one Fory
implementation family, native mode may support additional language-specific class, trait, or hook
behavior.

## Custom serializers

Use a custom serializer when a type needs a representation that built-in schema inference cannot
provide. Registration connects the custom serializer to the application type. A custom serializer
must follow the selected mode's rules: xlang serializers need a portable representation, while
native serializers may use language-specific data and hooks.

Prefer built-in serializers and generated models when they already describe the type. They keep
schema evolution, reference handling, and cross-language behavior easier to reason about.

## Continue with a mode

- [Xlang Serialization](xlang.md) is the default and is required when peers use different Fory
  implementation families or need a portable contract.
- [Native Serialization](native.md) is for supported use cases within one Fory implementation
  family that need native types or behavior.
- Choose a language section after selecting a mode to find installation, API, configuration,
  registration, platform, security, and troubleshooting guidance.
