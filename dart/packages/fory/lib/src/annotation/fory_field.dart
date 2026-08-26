/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import 'package:fory/src/annotation/type_spec.dart';

/// Configures serialization for the storage field on which it is declared.
///
/// Field metadata remains attached to its declaration when an ordinary
/// `ForyStruct` inherits the field. [ignore] is the declaration-owned,
/// per-field omission option. A concrete child may separately omit all
/// ancestor-declared private storage through
/// `ForyStruct.ignoreInheritedPrivateFields`.
final class ForyField {
  /// Whether to omit this field from the serialized schema.
  ///
  /// This is the only declaration-owned way to omit an ordinary instance
  /// storage field. Ignored storage still contributes to shallow graph-memory
  /// accounting.
  final bool ignore;

  /// The stable numeric field identity used for schema evolution.
  ///
  /// Values must be in the range `0 <= id < 2^29`. The default `-1` means
  /// that the field uses its name as its wire identity.
  final int id;

  /// An optional override for the field's inferred nullability.
  final bool? nullable;

  /// Whether references through this field preserve object identity.
  final bool ref;

  /// Whether values carry their concrete runtime type.
  final bool? dynamic;

  /// An optional exact wire-type description.
  final TypeSpec? type;

  /// Creates field-level serialization metadata.
  const ForyField({
    this.ignore = false,
    this.id = -1,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.type,
  }) : assert(
         !ignore ||
             (id == -1 &&
                 nullable == null &&
                 !ref &&
                 dynamic == null &&
                 type == null),
         'Ignored fields cannot define wire metadata.',
       );
}

final class ListField {
  final int id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? element;

  const ListField({
    this.id = -1,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.element,
  });
}

final class ArrayField {
  final int id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec element;

  const ArrayField({
    this.id = -1,
    this.nullable,
    this.ref = false,
    this.dynamic,
    required this.element,
  });
}

final class SetField {
  final int id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? element;

  const SetField({
    this.id = -1,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.element,
  });
}

final class MapField {
  final int id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? key;
  final TypeSpec? value;

  const MapField({
    this.id = -1,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.key,
    this.value,
  });
}
