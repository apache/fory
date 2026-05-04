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
  TypeResolver? _declaredTypeInfoResolver;
  bool _hasDeclaredTypeInfo = false;
  bool? _usesDeclaredType;
  TypeResolver? _usesDeclaredTypeResolver;

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
    if (_hasDeclaredTypeInfo &&
        identical(_declaredTypeInfoResolver, resolver)) {
      return _declaredTypeInfo;
    }
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      _declaredTypeInfo = null;
      _declaredTypeInfoResolver = resolver;
      _hasDeclaredTypeInfo = true;
      return null;
    }
    final resolved = resolver.tryResolveFieldType(fieldType);
    _declaredTypeInfo = resolved;
    _declaredTypeInfoResolver = resolver;
    _hasDeclaredTypeInfo = true;
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
    if (cached != null && identical(_usesDeclaredTypeResolver, resolver)) {
      return cached;
    }
    final uses = usesDeclaredTypeInfo(
      resolver.config.compatible,
      fieldType,
      resolved,
    );
    _usesDeclaredType = uses;
    _usesDeclaredTypeResolver = resolver;
    return uses;
  }
}
