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
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/analyze/interface/class_analyzer.dart';
import 'package:fory/src/codegen/entity/fields_cache_unit.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/class_spec_generator.dart';
import 'package:fory/src/annotation/fory_class.dart';
import 'package:fory/src/codegen/meta/impl/constructor_info.dart';
import 'package:fory/src/codegen/meta/impl/fields_spec_generator.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';

class ClassAnalyzerImpl implements ClassAnalyzer {
  const ClassAnalyzerImpl();

  void _setObjectTypeIfNeed(ClassElement clsElement) {
    if (AnalysisTypeIdentifier.hasObjectType) return;
    InterfaceType? type = clsElement.thisType;
    while (type!.superclass != null) {
      type = type.superclass;
    }
    AnalysisTypeIdentifier.cacheObjectType(type);
  }

  @override
  ClassSpecGenerator analyze(ClassElement clsElement) {
    _setObjectTypeIfNeed(clsElement);
    // here clsElement is ClassElement
    assert(
        clsElement.location != null &&
            clsElement.location!.components.isNotEmpty,
        'Class location is null or empty');
    /*------------------analyze package and class name-----------------------------*/
    String packageName = clsElement.location!.components[0];
    String className = clsElement.name;
    LocationMark locationMark = LocationMark.clsLevel(packageName, className);
    /*------------------analyze ForyMeta-----------------------------*/
    ForyClass foryClass = Analyzer.classAnnotationAnalyzer.analyze(
      clsElement.metadata,
      clsElement.id,
      locationMark,
    );
    /*--------------analyze imports--------------------------------*/
    LibraryImportPack libImportPack =
        Analyzer.importsAnalyzer.analyze(clsElement.library);

    /*-----------------analyze fields------------------------------*/
    FieldsCacheUnit fieldsUnit =
        Analyzer.fieldsAnalyzer.analyzeFields(clsElement, locationMark);

    /*--------------analyze constructor----------------------------*/
    ConstructorInfo consInfo = Analyzer.constructorAnalyzer.analyze(
      clsElement.constructors,
      clsElement.id,
      foryClass.promiseAcyclic,
      fieldsUnit.allFieldsIndependent,
      locationMark,
    );

    FieldsSpecGenerator fieldsSpecGen = Analyzer.accessInfoAnalyzer
        .validateAndAssignAccessInfo(consInfo.unnamedConstructorParams,
            fieldsUnit.fieldImmutables, false, locationMark);

    if (!fieldsSpecGen.fieldSorted) {
      Analyzer.fieldsSorter.sortFieldsByName(fieldsSpecGen.fields);
      fieldsSpecGen.fieldSorted = true;
    }

    return ClassSpecGenerator(
      className,
      packageName,
      foryClass.promiseAcyclic,
      fieldsUnit.allFieldsIndependent,
      fieldsSpecGen,
      libImportPack,
      consInfo,
    );
  }
}
