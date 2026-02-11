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
import 'package:analyzer/dart/element/nullability_suffix.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:fory/src/codegen/analyze/analysis_cache.dart';
import 'package:fory/src/codegen/analyze/type_analysis_models.dart';
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/analyze/interface/type_analyzer.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/type_immutable.dart';
import 'package:fory/src/codegen/meta/impl/type_spec_generator.dart';
import 'package:fory/src/const/types.dart';

class TypeAnalyzerImpl extends TypeAnalyzer {
  TypeAnalyzerImpl();

  TypeSpecGenerator _analyze(
    TypeAnalysisDecision typeDecision,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureFieldLevel);
    InterfaceType type = typeDecision.type;
    bool nullable = (typeDecision.forceNullable)
        ? true
        : type.nullabilitySuffix == NullabilitySuffix.question;
    TypeImmutable typeImmutable =
        _resolveTypeImmutable(type.element, locationMark);

    List<TypeSpecGenerator> typeArgsTypes = [];
    for (DartType arg in type.typeArguments) {
      typeArgsTypes.add(
        _analyze(
            Analyzer.typeSystemAnalyzer.decideInterfaceType(arg), locationMark),
      );
    }
    TypeSpecGenerator spec = TypeSpecGenerator(
      typeImmutable,
      nullable,
      typeArgsTypes,
    );
    return spec;
  }

  @override
  TypeSpecGenerator resolveTypeSpec(
    TypeAnalysisDecision typeDecision,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    InterfaceType type = typeDecision.type;

    InterfaceElement element = type.element;
    int libId = element.library.id;
    bool nullable = (typeDecision.forceNullable)
        ? true
        : type.nullabilitySuffix == NullabilitySuffix.question;

    ObjectTypeAnalysis objTypeRes =
        Analyzer.typeSystemAnalyzer.resolveObjectType(element, locationMark);
    List<TypeSpecGenerator> typeArgsTypes = [];
    for (DartType arg in type.typeArguments) {
      typeArgsTypes.add(
        _analyze(
          Analyzer.typeSystemAnalyzer.decideInterfaceType(arg),
          locationMark,
        ),
      );
    }
    return TypeSpecGenerator(
      TypeImmutable(
        element.name,
        libId,
        objTypeRes.objType,
        objTypeRes.objType.independent,
        objTypeRes.serializationCertain,
      ),
      nullable,
      typeArgsTypes,
    );
  }

  /// Version of resolveTypeSpec that supports annotation-based ObjType override.
  /// Used when uint annotations are present on int fields.
  @override
  TypeSpecGenerator resolveTypeSpecWithOverride(
    TypeAnalysisDecision typeDecision,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
    ObjType objTypeOverride,
  ) {
    InterfaceType type = typeDecision.type;

    InterfaceElement element = type.element;
    int libId = element.library.id;
    bool nullable = (typeDecision.forceNullable)
        ? true
        : type.nullabilitySuffix == NullabilitySuffix.question;

    List<TypeSpecGenerator> typeArgsTypes = [];
    for (DartType arg in type.typeArguments) {
      typeArgsTypes.add(
        _analyze(
          Analyzer.typeSystemAnalyzer.decideInterfaceType(arg),
          locationMark,
        ),
      );
    }
    return TypeSpecGenerator(
      TypeImmutable(
        element.name,
        libId,
        objTypeOverride,
        objTypeOverride.independent,
        true, // serializationCertain is true for annotation-based types
      ),
      nullable,
      typeArgsTypes,
    );
  }

  TypeImmutable _resolveTypeImmutable(
    InterfaceElement element,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  ) {
    // loc
    assert(locationMark.ensureFieldLevel);
    // check cache
    TypeImmutable? typeImmutable = AnalysisCache.getTypeImmutable(element.id);
    if (typeImmutable != null) {
      return typeImmutable;
    }
    // step by step
    String name = element.name;

    ObjectTypeAnalysis objTypeRes =
        Analyzer.typeSystemAnalyzer.resolveObjectType(element, locationMark);
    int libId = element.library.id;
    // cache
    typeImmutable = TypeImmutable(
      name,
      libId,
      objTypeRes.objType,
      objTypeRes.objType.independent,
      objTypeRes.serializationCertain,
    );
    AnalysisCache.putTypeImmutable(element.id, typeImmutable);
    return typeImmutable;
  }
}
