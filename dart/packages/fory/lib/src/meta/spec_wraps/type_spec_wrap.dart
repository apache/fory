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

import 'package:fory/src/const/types.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/meta/specs/field_type_spec.dart';
import 'package:fory/src/serializer/serializer.dart';

class TypeSpecWrap {
  final Type type;
  final ObjType objType;
  final bool serializationCertain;
  final bool nullable;
  final List<TypeSpecWrap> genericsArgs;
  Serializer? serializer;

  TypeSpecWrap._(
    this.type,
    this.objType,
    this.serializationCertain,
    this.nullable,
    this.genericsArgs,
    this.serializer,
  );

  factory TypeSpecWrap.of(FieldTypeSpec typeSpec) {
    List<TypeSpecWrap> genericsWraps = [];
    var genericsArgs = typeSpec.genericsArgs;
    for (int i = 0; i < genericsArgs.length; ++i) {
      TypeSpecWrap argWrap = TypeSpecWrap.of(typeSpec.genericsArgs[i]);
      genericsWraps.add(argWrap);
    }
    return TypeSpecWrap._(
      typeSpec.type,
      typeSpec.objType,
      typeSpec.serializationCertain,
      typeSpec.nullable,
      genericsWraps,
      null,
    );
  }

  static List<TypeSpecWrap> ofList(List<FieldSpec> fieldSpecs) {
    List<TypeSpecWrap> typeSpecWraps = [];
    for (int i = 0; i < fieldSpecs.length; ++i) {
      TypeSpecWrap typeSpecWrap = TypeSpecWrap.of(fieldSpecs[i].typeSpec);
      typeSpecWraps.add(typeSpecWrap);
    }
    return typeSpecWraps;
  }

  bool get hasGenericsParam => genericsArgs.isNotEmpty;

  TypeSpecWrap? get param0 => genericsArgs.isNotEmpty ? genericsArgs[0] : null;
  TypeSpecWrap? get param1 => genericsArgs.length > 1 ? genericsArgs[1] : null;

  int get paramCount => genericsArgs.length;
}
