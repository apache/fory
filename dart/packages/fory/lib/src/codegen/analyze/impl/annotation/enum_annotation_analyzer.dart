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
import 'package:fory/src/annotation/fory_class.dart';
import 'package:fory/src/codegen/analyze/analysis_type_identifier.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/annotation/fory_enum.dart';
import 'package:fory/src/codegen/exception/annotation_exception.dart';

class EnumAnnotationAnalyzer {
  const EnumAnnotationAnalyzer();

  ForyEnum analyze(
    List<ElementAnnotation> metadata,
    @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureClassLevel);
    late DartObject anno;
    late ClassElement annoClsElement;
    bool getForyEnum = false;
    for (ElementAnnotation annoElement in metadata) {
      anno = annoElement.computeConstantValue()!;
      annoClsElement = anno.type!.element as ClassElement;
      if (AnalysisTypeIdentifier.isForyEnum(annoClsElement)) {
        if (getForyEnum) {
          throw DuplicatedAnnotationException(
              ForyClass.name, locationMark.clsName, locationMark.libPath);
        }
        getForyEnum = true;
      }
    }
    assert(
        getForyEnum); // There will definitely be a ForyMeta annotation, otherwise this class wouldn't be analyzed.
    // TODO: This is still under modification, no fields yet, but maintaining the code structure.
    return ForyEnum();
  }

  // This method does not perform annotation validity checks, nor does it perform caching operations.
  bool hasForyEnumAnnotation(List<ElementAnnotation> metadata) {
    DartObject? anno;
    for (ElementAnnotation annoElement in metadata) {
      anno = annoElement.computeConstantValue()!;
      ClassElement annoClsElement = anno.type!.element as ClassElement;
      if (AnalysisTypeIdentifier.isForyEnum(annoClsElement)) {
        return true;
      }
    }
    return false;
  }
}
