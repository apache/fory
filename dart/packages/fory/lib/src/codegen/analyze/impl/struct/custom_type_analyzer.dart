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
import 'package:fory/src/codegen/analyze/type_analysis_models.dart';
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/entity/either.dart';
import 'package:fory/src/codegen/exception/annotation_exception.dart';
import 'package:fory/src/const/dart_type.dart';

class CustomTypeAnalyzer {
  const CustomTypeAnalyzer();
  /*
   Here is an explanation
   Why, when this class is a custom type,
   it simply retrieves without checking validity or caching the analysis results for possible future operations.
   First, if this class has annotations, it will naturally trigger a dedicated analysis. If there is an error in the writing, it will naturally report it, so why not let it handle it there.
   Additionally, for now, annotations are simple and it seems possible to analyze everything at once, but in the future, as fields may gradually increase, handling it here would be overstepping, so it is not cached now.
   */
  // Here, the left of either is the result found without prohibition, and the right is the prohibited type.
  Either<ObjectTypeAnalysis, DartTypeEnum> resolveType(
    InterfaceElement element,
  ) {
    String name = element.name;
    Uri libLoc = element.library.source.uri;
    String scheme = libLoc.scheme;
    String path = libLoc.path;

    DartTypeEnum? dartTypeEnum = DartTypeEnum.find(name, scheme, path);

    if (dartTypeEnum != null) {
      if (dartTypeEnum.objType == null) {
        return Either.right(dartTypeEnum);
      }
      return Either.left(ObjectTypeAnalysis(
        dartTypeEnum.objType!,
        dartTypeEnum.serializationCertain,
      ));
    }
    // Not found in built-in types, considered as a custom type
    if (element is EnumElement) {
      if (!Analyzer.enumAnnotationAnalyzer
          .hasForyEnumAnnotation(element.metadata)) {
        throw CodegenUnregisteredTypeException(path, name, 'ForyEnum');
      }
      return Either.left(ObjectTypeAnalysis.namedEnumType);
    }
    assert(element is ClassElement);
    if (Analyzer.classAnnotationAnalyzer
        .hasForyClassAnnotation(element.metadata)) {
      return Either.left(ObjectTypeAnalysis.namedStructType);
    }
    return Either.left(ObjectTypeAnalysis.unknownStructType);
  }
}
