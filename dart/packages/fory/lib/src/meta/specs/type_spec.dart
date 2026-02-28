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
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/meta/specs/field_spec.dart';

typedef HasArgsCons = Object Function(List<Object?>);
typedef NoArgsCons = Object Function();

class TypeSpec extends CustomTypeSpec {
  final List<FieldSpec> fields;
  final bool promiseAcyclic;
  final bool
      noCyclicRisk; // No risk of cyclic references obtained by analyzing the code, for example, all fields are
  final HasArgsCons? construct;
  // Think, is noArgs really necessary? If a constructor with only basic type parameters is provided, is it acceptable?
  // No, during the deserialization phase, the order of fields is not arbitrary, it follows the field order (basic types are not necessarily at the beginning of the order)
  final NoArgsCons? noArgConstruct;

  TypeSpec(
    Type dartType,
    this.promiseAcyclic,
    this.noCyclicRisk,
    this.fields,
    this.construct,
    this.noArgConstruct,
  ) : super(
            dartType,
            ObjType
                .NAMED_STRUCT) // Currently, a class can only be a named structure
  {
    assert(construct != null || noArgConstruct != null,
        'construct and noArgConstruct can not be both non-null');
    // If noArgConstruct is null, then promiseAcyclic is required, A->B is equivalent to !A || B
    assert(noArgConstruct != null || promiseAcyclic || noCyclicRisk);
  }

  @override
  int get hashCode => Object.hash(
      fields, promiseAcyclic, noCyclicRisk, construct, noArgConstruct);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is TypeSpec &&
            runtimeType == other.runtimeType &&
            fields.equals(other.fields) &&
            promiseAcyclic == other.promiseAcyclic &&
            noCyclicRisk == other.noCyclicRisk &&
            ((identical(construct, other.construct)) ||
                (construct == null) == (construct == null)) &&
            ((identical(noArgConstruct, other.noArgConstruct)) ||
                (noArgConstruct == null) == (other.noArgConstruct == null)));
  }
}
