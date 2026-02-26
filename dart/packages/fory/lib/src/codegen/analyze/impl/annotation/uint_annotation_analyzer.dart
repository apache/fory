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
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/exception/annotation_exception.dart';
import 'package:fory/src/const/types.dart';

/// Result of uint annotation analysis
class UintAnnotationResult {
  final ObjType? objType;
  final bool hasAnnotation;

  const UintAnnotationResult(this.objType, this.hasAnnotation);

  static const none = UintAnnotationResult(null, false);
}

class UintAnnotationAnalyzer {
  const UintAnnotationAnalyzer();

  /// Analyzes uint type annotations on a field.
  /// Returns the appropriate ObjType if a uint annotation is found, null otherwise.
  UintAnnotationResult analyze(
    List<ElementAnnotation> metadata,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureFieldLevel);

    DartObject? uintAnno;
    String? annotationType;

    for (ElementAnnotation annoElement in metadata) {
      final anno = annoElement.computeConstantValue()!;
      final annoClsElement = anno.type!.element as ClassElement;

      if (AnalysisTypeIdentifier.isUint8Type(annoClsElement)) {
        if (uintAnno != null) {
          throw DuplicatedAnnotationException(
            'Uint type annotation',
            locationMark.fieldName!,
            locationMark.clsLocation,
          );
        }
        uintAnno = anno;
        annotationType = 'Uint8Type';
      } else if (AnalysisTypeIdentifier.isUint16Type(annoClsElement)) {
        if (uintAnno != null) {
          throw DuplicatedAnnotationException(
            'Uint type annotation',
            locationMark.fieldName!,
            locationMark.clsLocation,
          );
        }
        uintAnno = anno;
        annotationType = 'Uint16Type';
      } else if (AnalysisTypeIdentifier.isUint32Type(annoClsElement)) {
        if (uintAnno != null) {
          throw DuplicatedAnnotationException(
            'Uint type annotation',
            locationMark.fieldName!,
            locationMark.clsLocation,
          );
        }
        uintAnno = anno;
        annotationType = 'Uint32Type';
      } else if (AnalysisTypeIdentifier.isUint64Type(annoClsElement)) {
        if (uintAnno != null) {
          throw DuplicatedAnnotationException(
            'Uint type annotation',
            locationMark.fieldName!,
            locationMark.clsLocation,
          );
        }
        uintAnno = anno;
        annotationType = 'Uint64Type';
      }
    }

    if (uintAnno == null) {
      return UintAnnotationResult.none;
    }

    // Determine the ObjType based on the annotation
    ObjType objType;
    switch (annotationType) {
      case 'Uint8Type':
        objType = ObjType.UINT8;
        break;
      case 'Uint16Type':
        objType = ObjType.UINT16;
        break;
      case 'Uint32Type':
        // Check encoding parameter
        final encodingField = uintAnno.getField('encoding');
        if (encodingField != null && !encodingField.isNull) {
          final encodingValue =
              encodingField.getField('_name')?.toStringValue();
          if (encodingValue == 'varint') {
            objType = ObjType.VAR_UINT32;
          } else {
            objType = ObjType.UINT32;
          }
        } else {
          objType = ObjType.UINT32;
        }
        break;
      case 'Uint64Type':
        // Check encoding parameter
        final encodingField = uintAnno.getField('encoding');
        if (encodingField != null && !encodingField.isNull) {
          final encodingValue =
              encodingField.getField('_name')?.toStringValue();
          if (encodingValue == 'varint') {
            objType = ObjType.VAR_UINT64;
          } else if (encodingValue == 'tagged') {
            objType = ObjType.TAGGED_UINT64;
          } else {
            objType = ObjType.UINT64;
          }
        } else {
          objType = ObjType.UINT64;
        }
        break;
      default:
        return UintAnnotationResult.none;
    }

    return UintAnnotationResult(objType, true);
  }
}
