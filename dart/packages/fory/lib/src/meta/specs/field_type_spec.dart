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

import 'package:collection/collection.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/meta/specs/enum_spec.dart';

class FieldTypeSpec {
  final Type type;
  final ObjType objType;
  final bool nullable;
  final bool serializationCertain;
  final EnumSpec? enumSpec;
  final List<FieldTypeSpec> genericsArgs;

  const FieldTypeSpec(
    this.type,
    this.objType,
    this.nullable,
    this.serializationCertain,
    this.enumSpec,
    this.genericsArgs,
  );

  @override
  int get hashCode => Object.hash(
      type, objType, nullable, serializationCertain, enumSpec, genericsArgs);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is FieldTypeSpec &&
            runtimeType == other.runtimeType &&
            type == other.type &&
            objType == other.objType &&
            nullable == other.nullable &&
            serializationCertain == other.serializationCertain &&
            enumSpec == other.enumSpec &&
            genericsArgs.equals(other.genericsArgs));
  }
}
