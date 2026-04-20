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

import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/resolver/type_resolver.dart';

final class SerializationFieldInfo {
  final FieldInfo field;
  final int slot;
  TypeInfo? _declaredTypeInfo;
  bool? _usesDeclaredType;

  SerializationFieldInfo({
    required this.field,
    required this.slot,
    TypeInfo? declaredTypeInfo,
    bool? usesDeclaredType,
  })  : _declaredTypeInfo = declaredTypeInfo,
        _usesDeclaredType = usesDeclaredType;

  String get name => field.name;

  String get identifier => field.identifier;

  int? get id => field.id;

  FieldType get fieldType => field.fieldType;

  TypeInfo? declaredTypeInfo(TypeResolver resolver) {
    final cached = _declaredTypeInfo;
    if (cached != null) {
      return cached;
    }
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return null;
    }
    final resolved = resolver.tryResolveFieldType(fieldType);
    if (resolved != null) {
      _declaredTypeInfo = resolved;
    }
    return resolved;
  }

  bool usesDeclaredType(TypeResolver resolver) {
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return false;
    }
    final resolved = declaredTypeInfo(resolver);
    if (resolved == null) {
      return false;
    }
    final cached = _usesDeclaredType;
    if (cached != null) {
      return cached;
    }
    final uses = usesDeclaredTypeInfo(
      resolver.config.compatible,
      fieldType,
      resolved,
    );
    _usesDeclaredType = uses;
    return uses;
  }
}
