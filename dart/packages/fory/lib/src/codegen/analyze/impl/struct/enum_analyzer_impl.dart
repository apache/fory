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

import 'package:analyzer/dart/constant/value.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:fory/src/codegen/analyze/analysis_type_identifier.dart';
import 'package:fory/src/codegen/analyze/interface/enum_analyzer.dart';
import 'package:fory/src/codegen/meta/impl/enum_spec_generator.dart';
import 'package:fory/src/fory_exception.dart';

class EnumAnalyzerImpl implements EnumAnalyzer {
  const EnumAnalyzerImpl();

  static const int _minEnumId = 0;
  static const int _maxEnumId = (1024 * 1024 * 1024 * 4) - 1; // 2^32 - 1

  /// Finds a non-constant field annotated with @ForyEnumId().
  String? _findIdField(EnumElement enumElement) {
    for (final FieldElement field in enumElement.fields) {
      if (field.isEnumConstant || field.isSynthetic) continue;
      for (final ElementAnnotation annotation in field.metadata) {
        final DartObject? annotationValue = annotation.computeConstantValue();
        final Element? typeElement = annotationValue?.type?.element;
        if (typeElement is ClassElement &&
            AnalysisTypeIdentifier.isForyEnumId(typeElement)) {
          return field.name;
        }
      }
    }
    return null;
  }

  /// Reads @ForyEnumId(id) from per-value annotation.
  int? _readEnumId(FieldElement enumField) {
    for (final ElementAnnotation annotation in enumField.metadata) {
      final DartObject? annotationValue = annotation.computeConstantValue();
      final Element? typeElement = annotationValue?.type?.element;
      if (typeElement is ClassElement &&
          AnalysisTypeIdentifier.isForyEnumId(typeElement)) {
        return annotationValue?.getField('id')?.toIntValue();
      }
    }
    return null;
  }

  @override
  EnumSpecGenerator analyze(EnumElement enumElement) {
    String packageName = enumElement.location!.components[0];
    final String enumName = enumElement.name;
    final List<FieldElement> enumFields =
        enumElement.fields.where((FieldElement e) => e.isEnumConstant).toList();
    final List<String> enumValues =
        enumFields.map((FieldElement e) => e.name).toList();

    final String? idFieldName = _findIdField(enumElement);
    final Map<String, int> enumIds = <String, int>{};
    final Map<int, String> usedIds = <int, String>{};
    final List<String> duplicateIds = <String>[];
    final List<String> missingIdValues = <String>[];
    final List<String> outOfRangeIds = <String>[];

    for (final FieldElement enumField in enumFields) {
      final int? id = idFieldName != null
          ? enumField.computeConstantValue()?.getField(idFieldName)?.toIntValue()
          : _readEnumId(enumField);

      if (id == null) {
        missingIdValues.add(enumField.name);
        continue;
      }

      if (id < _minEnumId || id > _maxEnumId) {
        outOfRangeIds.add('$id for ${enumField.name}');
        continue;
      }

      final String? firstValueWithId = usedIds[id];
      if (firstValueWithId != null) {
        duplicateIds.add('$id for $firstValueWithId and ${enumField.name}');
        continue;
      }
      usedIds[id] = enumField.name;
      enumIds[enumField.name] = id;
    }

    // Any @ForyEnumId present means the whole configuration must be valid.
    final bool anyAnnotationPresent = idFieldName != null ||
      enumIds.isNotEmpty ||
      outOfRangeIds.isNotEmpty ||
      duplicateIds.isNotEmpty;

    if (anyAnnotationPresent) {
      if (outOfRangeIds.isNotEmpty) {
        throw InvalidDataException(
          'Enum $enumName in $packageName has @ForyEnumId values outside the '
          'unsigned 32-bit range (0 to 4294967295). '
          'Offending values: ${outOfRangeIds.join('; ')}.',
        );
      }
      if (duplicateIds.isNotEmpty) {
        throw InvalidDataException(
          'Enum $enumName in $packageName has duplicate @ForyEnumId values '
          '(${duplicateIds.join('; ')}). '
          'All ids must be unique.',
        );
      }
      if (missingIdValues.isNotEmpty) {
        throw InvalidDataException(
          'Enum $enumName in $packageName has partial @ForyEnumId annotations. '
          'Missing values: ${missingIdValues.join(', ')}. '
          'All values must have an id when @ForyEnumId is used.',
        );
      }
    }

    return EnumSpecGenerator(
      enumName,
      packageName,
      enumValues,
      anyAnnotationPresent ? enumIds : null,
    );
  }
}
