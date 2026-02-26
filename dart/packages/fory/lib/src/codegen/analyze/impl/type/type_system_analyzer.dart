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

import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:fory/src/codegen/analyze/analysis_type_identifier.dart';
import 'package:fory/src/codegen/analyze/type_analysis_models.dart';
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/either.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/exception/constraint_violation_exception.dart';
import 'package:fory/src/const/dart_type.dart';

class TypeSystemAnalyzer {
  const TypeSystemAnalyzer();

  ObjectTypeAnalysis resolveObjectType(
    InterfaceElement element,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureFieldLevel);
    // Confirm the current ObjType
    Either<ObjectTypeAnalysis, DartTypeEnum> res =
        Analyzer.customTypeAnalyzer.resolveType(element);
    if (res.isRight) {
      throw UnsupportedTypeException(
        locationMark.libPath,
        locationMark.clsName,
        locationMark.fieldName!,
        res.right!.scheme,
        res.right!.path,
        res.right!.typeName,
      );
    }
    return res.left!;
  }

  TypeAnalysisDecision decideInterfaceType(DartType inputType) {
    InterfaceType? type;
    DartType? dartType;
    if (inputType is InterfaceType) {
      type = inputType;
    } else if (inputType.element is TypeParameterElement) {
      dartType = (inputType.element as TypeParameterElement).bound;
      if (dartType is InterfaceType) {
        type = dartType;
      } else if (dartType == null) {
        // do nothing
      } else {
        throw ArgumentError(
          'Field type is not InterfaceType or DynamicType: $inputType',
        );
      }
    } else if (inputType is DynamicType) {
      type = null;
    } else {
      throw ArgumentError(
        'Field type is not InterfaceType or TypeParameterElement: $inputType',
      );
    }
    return (type == null)
        ? (type: AnalysisTypeIdentifier.objectType, forceNullable: true)
        : (type: type, forceNullable: false);
  }
}
