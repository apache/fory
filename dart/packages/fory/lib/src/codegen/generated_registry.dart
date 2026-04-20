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

import 'package:meta/meta.dart';

import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/struct_serializer.dart';

enum GeneratedRegistrationKind {
  enumType,
  struct,
}

@internal
final class GeneratedRegistration {
  final GeneratedRegistrationKind kind;
  final Serializer<Object?> Function() serializerFactory;
  final bool evolving;
  final List<FieldInfo> fields;
  final GeneratedStructCompatibleFactory<Object>? compatibleFactory;
  final List<GeneratedStructCompatibleFieldReader<Object>>?
      compatibleReadersBySlot;

  const GeneratedRegistration({
    required this.kind,
    required this.serializerFactory,
    this.evolving = true,
    this.fields = const <FieldInfo>[],
    this.compatibleFactory,
    this.compatibleReadersBySlot,
  });
}

@internal
abstract final class GeneratedRegistrationCatalog {
  static final Map<Type, GeneratedRegistration> _byType =
      <Type, GeneratedRegistration>{};

  static void remember(Type type, GeneratedRegistration registration) {
    _byType[type] = registration;
  }

  static GeneratedRegistration? lookup(Type type) {
    return _byType[type];
  }
}
