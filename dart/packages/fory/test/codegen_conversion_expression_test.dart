// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

@TestOn('vm')
library;

import 'package:analyzer/dart/element/nullability_suffix.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:fory/fory.dart';
import 'package:fory/src/codegen/fory_generator.dart';
import 'package:test/test.dart';

class _FakeDartType implements DartType {
  _FakeDartType({required this.nullable});

  final bool nullable;

  @override
  bool get isDartCoreObject => true;

  @override
  NullabilitySuffix get nullabilitySuffix =>
      nullable ? NullabilitySuffix.question : NullabilitySuffix.none;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  test('object nullable conversion keeps raw value expression', () {
    final generator = ForyGenerator();
    final expression = generator.debugConversionExpressionForType(
      _FakeDartType(nullable: true),
      DebugGeneratedFieldTypeSpec(
        typeLiteral: 'Object',
        typeId: TypeIds.unknown,
        nullable: true,
        ref: false,
        dynamic: true,
        arguments: const <DebugGeneratedFieldTypeSpec>[],
      ),
      'rawValue',
      nullExpression: 'throw StateError()',
    );
    expect(expression, 'rawValue');
  });

  test('object non-nullable conversion guards null and keeps typed value', () {
    final generator = ForyGenerator();
    final expression = generator.debugConversionExpressionForType(
      _FakeDartType(nullable: false),
      DebugGeneratedFieldTypeSpec(
        typeLiteral: 'Object',
        typeId: TypeIds.unknown,
        nullable: false,
        ref: false,
        dynamic: true,
        arguments: const <DebugGeneratedFieldTypeSpec>[],
      ),
      'rawValue',
      nullExpression: 'fallback()',
    );
    expect(expression, 'rawValue == null ? fallback() : rawValue');
  });
}
