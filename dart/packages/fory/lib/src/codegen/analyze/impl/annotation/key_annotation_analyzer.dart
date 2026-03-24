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
import 'package:fory/src/fory_exception.dart';
import 'package:fory/src/annotation/fory_key.dart';

class KeyAnnotationAnalyzer {
  const KeyAnnotationAnalyzer();

  // Currently, ForyKey will analyze only once at a run, so there is no need to use a caching mechanism.
  ForyKey analyze(
    List<ElementAnnotation> metadata,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureFieldLevel);
    DartObject? anno;
    late ClassElement annoClsElement;
    bool getMeta = false;
    for (ElementAnnotation annoElement in metadata) {
      anno = annoElement.computeConstantValue()!;
      annoClsElement = anno.type!.element as ClassElement;
      if (AnalysisTypeIdentifier.isForyKey(annoClsElement)) {
        if (getMeta) {
          throw DuplicatedAnnotationException(
            ForyKey.name,
            locationMark.fieldName!,
            locationMark.clsLocation,
          );
        }
        getMeta = true;
        // Currently only handles ForyKey
      }
    }
    // If there is no annotation, both includeFromFory and includeToFory default to true.
    bool includeFromFory = true;
    bool includeToFory = true;
    bool ref = false;
    if (getMeta && anno != null) {
      includeFromFory = anno.getField("includeFromFory")!.toBoolValue()!;
      includeToFory = anno.getField("includeToFory")!.toBoolValue()!;
      ref = anno.getField("ref")!.toBoolValue()!;
      // serializeToVar = anno.getField("serializeTo")?.variable;
      // deserializeFromVar = anno.getField("deserializeFrom")?.variable;
      // if (serializeToVar != null){
      //   serializeTo = ForyType.fromString(serializeToVar.name)!;
      // }
      // if (deserializeFromVar != null){
      //   deserializeFrom = ForyType.fromString(deserializeFromVar.name)!;
      // }
      // targetType = (anno.getField("targetType")!.variable.enclosingElement3 as EnumElement).fields[1].;
    }
    ForyKey foryKey = ForyKey(
      // serializeTo: serializeTo,
      // deserializeFrom: deserializeFrom,
      includeFromFory: includeFromFory,
      includeToFory: includeToFory,
      ref: ref,
    );
    return foryKey;
  }
}
