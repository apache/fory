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
import 'package:fory/src/codegen/analyze/analysis_wrappers.dart';
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/analyze/annotation/location_level_ensure.dart';
import 'package:fory/src/codegen/analyze/interface/type_analyzer.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/type_immutable.dart';
import 'package:fory/src/codegen/meta/impl/type_spec_gen.dart';
import 'package:fory/src/const/obj_type.dart';

class TypeAnalyzerImpl extends TypeAnalyzer{

  TypeAnalyzerImpl();

  TypeSpecGen _analyze(
    TypeDecision typeDecision,
    @LocationEnsure(LocationLevel.fieldLevel) LocationMark locationMark,
  ){
    assert (locationMark.ensureFieldLevel);
    InterfaceType type = typeDecision.type;
    bool nullable = (typeDecision.forceNullable) ? true:  type.nullabilitySuffix == NullabilitySuffix.question;
    TypeImmutable typeImmutable = _analyzeTypeImmutable(type.element, locationMark);

    List<TypeSpecGen> typeArgsTypes = [];
    for (DartType arg in type.typeArguments){
      typeArgsTypes.add(
        _analyze(Analyzer.typeSystemAnalyzer.decideInterfaceType(arg), locationMark),
      );
    }
    TypeSpecGen spec = TypeSpecGen(
      typeImmutable,
      nullable,
      typeArgsTypes,
    );
    return spec;
  }


  @override
  TypeSpecGen getTypeImmutableAndTag(
    TypeDecision typeDecision,
    @LocationEnsure(LocationLevel.fieldLevel) LocationMark locationMark,
  ){
    InterfaceType type = typeDecision.type;

    InterfaceElement element = type.element;
    int libId = element.library.id;
    bool nullable = (typeDecision.forceNullable) ? true:  type.nullabilitySuffix == NullabilitySuffix.question;

    ObjTypeWrapper objTypeRes = Analyzer.typeSystemAnalyzer.analyzeObjType(element, locationMark);
    List<TypeSpecGen> typeArgsTypes = [];
    for (DartType arg in type.typeArguments){
      typeArgsTypes.add(
        _analyze(
          Analyzer.typeSystemAnalyzer.decideInterfaceType(arg),
          locationMark,
        ),
      );
    }
    return TypeSpecGen(
      TypeImmutable(
        element.name,
        libId,
        objTypeRes.objType,
        objTypeRes.objType.independent,
        objTypeRes.certainForSer,
      ),
      nullable,
      typeArgsTypes,
    );
  }

  /// Version of getTypeImmutableAndTag that supports annotation-based ObjType override.
  /// Used when uint annotations are present on int fields.
  TypeSpecGen getTypeImmutableAndTagWithOverride(
    TypeDecision typeDecision,
    @LocationEnsure(LocationLevel.fieldLevel) LocationMark locationMark,
    ObjType objTypeOverride,
  ){
    InterfaceType type = typeDecision.type;

    InterfaceElement element = type.element;
    int libId = element.library.id;
    bool nullable = (typeDecision.forceNullable) ? true:  type.nullabilitySuffix == NullabilitySuffix.question;

    List<TypeSpecGen> typeArgsTypes = [];
    for (DartType arg in type.typeArguments){
      typeArgsTypes.add(
        _analyze(
          Analyzer.typeSystemAnalyzer.decideInterfaceType(arg),
          locationMark,
        ),
      );
    }
    return TypeSpecGen(
      TypeImmutable(
        element.name,
        libId,
        objTypeOverride,
        objTypeOverride.independent,
        true, // certainForSer is true for annotation-based types
      ),
      nullable,
      typeArgsTypes,
    );
  }

  TypeImmutable _analyzeTypeImmutable(
    InterfaceElement element,
    @LocationEnsure(LocationLevel.fieldLevel) LocationMark locationMark,
  ){
    // loc
    assert (locationMark.ensureFieldLevel);
    // check cache
    TypeImmutable? typeImmutable = AnalysisCache.getTypeImmutable(element.id);
    if (typeImmutable != null) {
      return typeImmutable;
    }
    // step by step
    String name = element.name;

    ObjTypeWrapper objTypeRes = Analyzer.typeSystemAnalyzer.analyzeObjType(element, locationMark);
    int libId = element.library.id;
    // cache
    typeImmutable = TypeImmutable(
      name,
      libId,
      objTypeRes.objType,
      objTypeRes.objType.independent,
      objTypeRes.certainForSer,
    );
    AnalysisCache.putTypeImmutable(element.id, typeImmutable);
    return typeImmutable;
  }
}