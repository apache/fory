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

library;

import 'package:analyzer/dart/element/element.dart';
import 'package:build_test/build_test.dart';
import 'package:fory/src/codegen/analyze/impl/annotation/uint_annotation_analyzer.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/const/types.dart';
import 'package:test/test.dart';

void main() {
  group('UintAnnotationAnalyzer integration tests', () {
    const analyzer = UintAnnotationAnalyzer();

    Future<UintAnnotationResult> analyzeField(
        String source, String fieldName) async {
      final library = await resolveSource(
          source, (resolver) => resolver.findLibraryByName('test_lib'));
      final classElement = library!.topLevelElements
          .firstWhere((e) => e is ClassElement) as ClassElement;
      final fieldElement =
          classElement.fields.firstWhere((f) => f.name == fieldName);

      final locationMark = LocationMark.fieldLevel(
        'test_lib',
        'TestClass',
        fieldName,
      );

      return analyzer.analyze(fieldElement.metadata, locationMark);
    }

    const String commonSource = '''
library test_lib;
import 'package:fory/fory.dart';

class TestClass {
  @Uint8Type()
  int f8;

  @Uint16Type()
  int f16;

  @Uint32Type()
  int f32;

  @Uint32Type(encoding: UintEncoding.varint)
  int f32v;

  @Uint64Type()
  int f64;

  @Uint64Type(encoding: UintEncoding.varint)
  int f64v;

  @Uint64Type(encoding: UintEncoding.tagged)
  int f64t;

  int fNone;

  @Uint8Type()
  @Uint16Type()
  int fDup;
}
''';

    test('analyzes @Uint8Type correctly', () async {
      final result = await analyzeField(commonSource, 'f8');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.UINT8));
    });

    test('analyzes @Uint16Type correctly', () async {
      final result = await analyzeField(commonSource, 'f16');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.UINT16));
    });

    test('analyzes @Uint32Type (default) correctly', () async {
      final result = await analyzeField(commonSource, 'f32');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.UINT32));
    });

    test('analyzes @Uint32Type (varint) correctly', () async {
      final result = await analyzeField(commonSource, 'f32v');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.VAR_UINT32));
    });

    test('analyzes @Uint64Type (default) correctly', () async {
      final result = await analyzeField(commonSource, 'f64');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.UINT64));
    });

    test('analyzes @Uint64Type (varint) correctly', () async {
      final result = await analyzeField(commonSource, 'f64v');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.VAR_UINT64));
    });

    test('analyzes @Uint64Type (tagged) correctly', () async {
      final result = await analyzeField(commonSource, 'f64t');
      expect(result.hasAnnotation, isTrue);
      expect(result.objType, equals(ObjType.TAGGED_UINT64));
    });

    test('returns none for unannotated field', () async {
      final result = await analyzeField(commonSource, 'fNone');
      expect(result.hasAnnotation, isFalse);
      expect(result.objType, isNull);
    });

    test('throws exception for duplicated uint annotations', () async {
      expect(
        () => analyzeField(commonSource, 'fDup'),
        throwsA(anything), // Should throw DuplicatedAnnotationException
      );
    });
  });
}
