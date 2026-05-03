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

final class ForyField {
  final bool skip;
  final int? id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? type;

  const ForyField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.type,
  });
}

final class ListField {
  final bool skip;
  final int? id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? element;

  const ListField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.element,
  });
}

final class ArrayField {
  final bool skip;
  final int? id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec element;

  const ArrayField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
    required this.element,
  });
}

final class SetField {
  final bool skip;
  final int? id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? element;

  const SetField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.element,
  });
}

final class MapField {
  final bool skip;
  final int? id;
  final bool? nullable;
  final bool ref;
  final bool? dynamic;
  final TypeSpec? key;
  final TypeSpec? value;

  const MapField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
    this.key,
    this.value,
  });
}
