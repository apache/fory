---
title: Struct Inheritance
sidebar_position: 5
id: inheritance
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

Ordinary Dart `@ForyStruct()` classes serialize one flattened view of their
concrete superclass and applied-mixin storage. The generated child schema does
not nest or invoke a parent serializer.

## Public Fields

Public inherited fields need no annotation on the parent:

```dart
class MessageBase {
  int sequence = 0;
}

@ForyStruct()
class TextMessage extends MessageBase {
  TextMessage();

  String text = '';
}
```

The generated `TextMessage` schema contains both `sequence` and `text`.
Interfaces, abstract getters, static fields, and mixin `on` constraints do not
add storage.

## Field Inclusion

Fory first discovers the complete concrete superclass and applied-mixin
storage chain. It applies declaration-owned field omission first, followed by
the concrete-child option:

| Configuration                                     | Scope                                                                                                |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `@ForyField(ignore: true)`                        | Omits that declaring field from every child schema                                                   |
| `@ForyStruct(ignoreInheritedPrivateFields: true)` | Omits every private field declared by an ancestor or applied mixin from this concrete child's schema |

Every remaining field must have a supported type, an unambiguous access path,
and a valid reconstruction path. Fory reports a generation error instead of
silently dropping unresolved state.

Both omission options are applied during code generation. They do not add a
runtime flag, branch, or parent serializer.

## Ignoring Inherited Private Fields

`ignoreInheritedPrivateFields` defaults to `false`. Set it on a concrete child
when that child's wire schema intentionally excludes all private ancestor
state:

```dart
class FrameworkBase {
  String _cache = '';
  String publicLabel = '';
}

@ForyStruct(ignoreInheritedPrivateFields: true)
class PublicMessage extends FrameworkBase {
  PublicMessage();

  String _localState = '';
  String text = '';
}
```

The `PublicMessage` schema contains `_localState`, `text`, and `publicLabel`.
It does not contain `_cache`.

The option has the following exact scope:

- It applies to private fields declared by direct or transitive superclasses
  and applied mixins.
- It treats same-library and cross-library private fields identically.
- It never omits a private field declared directly by the annotated child.
- It never omits an inherited public field.
- It belongs only to the annotated concrete child. A parent annotation does
  not change a child's setting.
- It filters matching fields before private access is resolved. An omitted
  field does not require an access companion, even when one exists.
- It is invalid on `ForyStruct.target` declarations and provider-only abstract,
  open-generic, or mixin declarations.

A concrete class may set both `exposePrivateFields: true` and
`ignoreInheritedPrivateFields: true`. Its own serializer omits its private
ancestor fields, while its generated access companion remains available to
other child libraries.

## Including Cross-Library Private Fields

With the default `ignoreInheritedPrivateFields: false`, private fields declared
in another Dart library remain part of the child schema. The declaring library
must expose them through a public hierarchy boundary:

```dart
// package:model_owner/base.dart
import 'package:fory/fory.dart';

part 'base.fory.dart';

@ForyStruct(exposePrivateFields: true)
abstract class AccountBase {
  String _tenant = '';

  String get tenant => _tenant;
}
```

The consumer uses the normal annotation:

```dart
// lib/account.dart
import 'package:fory/fory.dart';
import 'package:model_owner/base.dart';

part 'account.fory.dart';

@ForyStruct()
class Account extends AccountBase {
  Account();
}
```

Generate and publish `base.fory.dart` before generating a dependent consumer.
A direct import or barrel export must expose both the public boundary and its
generated `$AccountBaseForyFieldAccess` companion. If private fields come from
several libraries, each declaring library needs its own public boundary and
companion.

Private inherited fields declared in the same Dart library as the child need
no parent annotation or companion.

## Constructors and Final Fields

Every included `final` or `late final` field must receive its decoded value
unchanged through the selected concrete-child generative constructor. Fory can
follow initializing formals, super formals, redirects, and direct constructor
initializers. A parameter with the same name is not sufficient proof if the
value is ignored or transformed.

Filtering a private field does not let Fory invent constructor inputs. If a
required child constructor parameter no longer has a serialized field source,
generation fails. Use a reconstructing constructor, declaration-owned
`@ForyField(ignore: true)` where appropriate, or a custom serializer.

## Mixins, Generics, and Field IDs

Applied mixin storage is flattened like superclass storage. Generic field types
are specialized for the concrete child before schema metadata is generated.
All included child, superclass, and mixin fields share one field-ID namespace
and one canonical ordering.

Field hiding is rejected when it prevents generated access from reaching an
included physical storage slot. Omitting private ancestor storage does not
relax validation for retained public or child-declared fields.

## References and Graph Memory

Included inherited `ref: true` fields and nested container reference metadata
use the same reference behavior as equivalent fields declared directly on the
child. Omitted fields do not enter generated reference analysis. Inheritance
does not add a reference mode or parent-level reference state.

Physical inherited storage still contributes to shallow graph-memory
accounting when omitted, matching `@ForyField(ignore: true)` behavior.

## External Types

`ForyStruct.target` declarations retain their explicit field list. Fory does
not scan an external target hierarchy automatically, and neither
`exposePrivateFields` nor `ignoreInheritedPrivateFields` is valid with
`target`.

An external declaration may explicitly list an accessible property inherited
by its target. See [External-Type Serialization](external-types.md).

## Regeneration and Compatibility

Regenerate affected `.fory.dart` files after changing hierarchy storage,
`exposePrivateFields`, or `ignoreInheritedPrivateFields`.

Changing `ignoreInheritedPrivateFields` changes the generated field set.
Compatible mode uses its normal missing-field and unknown-field behavior.
Fixed-schema peers must update together.

## Related Topics

- [Code Generation](code-generation.md)
- [Schema Metadata](schema-metadata.md)
- [Schema Evolution](schema-evolution.md)
- [Type Registration](type-registration.md)
- [Troubleshooting](troubleshooting.md)
