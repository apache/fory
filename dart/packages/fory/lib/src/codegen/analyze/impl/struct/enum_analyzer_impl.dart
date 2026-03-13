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

class EnumAnalyzerImpl implements EnumAnalyzer {
  const EnumAnalyzerImpl();

  int? _readEnumId(FieldElement enumField) {
    for (final ElementAnnotation annotation in enumField.metadata) {
      final DartObject? annotationValue = annotation.computeConstantValue();
      final Element? typeElement = annotationValue?.type?.element;
      if (typeElement is! ClassElement) {
        continue;
      }
      if (!AnalysisTypeIdentifier.isForyEnumId(typeElement)) {
        continue;
      }
      return annotationValue?.getField('id')?.toIntValue();
    }
    return null;
  }

  @override
  EnumSpecGenerator analyze(EnumElement enumElement) {
    String packageName = enumElement.location!.components[0];
    final List<FieldElement> enumFields =
        enumElement.fields.where((FieldElement e) => e.isEnumConstant).toList();

    final List<String> enumValues =
        enumFields.map((FieldElement e) => e.name).toList();

    final Map<String, int> enumIds = <String, int>{};
    final Set<int> usedIds = <int>{};
    bool useAnnotatedIds = true;
    for (final FieldElement enumField in enumFields) {
      final int? id = _readEnumId(enumField);
      if (id == null || !usedIds.add(id)) {
        useAnnotatedIds = false;
        break;
      }
      enumIds[enumField.name] = id;
    }

    return EnumSpecGenerator(
      enumElement.name,
      packageName,
      enumValues,
      useAnnotatedIds ? enumIds : null,
    );
  }
}
