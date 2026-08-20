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

import 'package:fory/src/meta/field_type.dart';

final class FieldInfo {
  /// The local source name used in diagnostics.
  ///
  /// Remote fields use their textual wire identity because no Dart source
  /// declaration exists.
  final String name;

  /// The protocol's textual id-or-name form used for encoding and hashing.
  ///
  /// Compatible matching uses [id] for tagged fields and this value only for
  /// untagged fields, so the two identity domains remain distinct.
  final String identifier;

  /// The numeric wire tag for a tagged field.
  final int? id;

  final FieldType fieldType;

  const FieldInfo({
    required this.name,
    required this.identifier,
    required this.id,
    required this.fieldType,
  });
}
