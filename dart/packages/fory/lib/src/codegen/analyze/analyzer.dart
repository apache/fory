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

import 'package:fory/src/codegen/analyze/impl/annotation/class_annotation_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/annotation/enum_annotation_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/annotation/key_annotation_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/annotation/uint_annotation_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/constructor/constructor_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/field/access_info_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/field/field_analyzer_impl.dart';
import 'package:fory/src/codegen/analyze/impl/field/field_spec_sorter.dart';
import 'package:fory/src/codegen/analyze/impl/field/fields_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/imports/imports_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/struct/class_analyzer_impl.dart';
import 'package:fory/src/codegen/analyze/impl/struct/custom_type_analyzer.dart';
import 'package:fory/src/codegen/analyze/impl/struct/enum_analyzer_impl.dart';
import 'package:fory/src/codegen/analyze/impl/type/type_analyzer_impl.dart';
import 'package:fory/src/codegen/analyze/impl/type/type_system_analyzer.dart';
import 'package:fory/src/codegen/analyze/interface/class_analyzer.dart';
import 'package:fory/src/codegen/analyze/interface/enum_analyzer.dart';
import 'package:fory/src/codegen/analyze/interface/field_analyzer.dart';
import 'package:fory/src/codegen/analyze/interface/type_analyzer.dart';
import 'package:fory/src/codegen/meta/generated_code_part.dart';

abstract class Analyzer {
  GeneratedCodePart analyze(Object input);

  // Annotation analyzers
  static const ClassAnnotationAnalyzer classAnnotationAnalyzer =
      ClassAnnotationAnalyzer();
  static const KeyAnnotationAnalyzer keyAnnotationAnalyzer =
      KeyAnnotationAnalyzer();
  static const EnumAnnotationAnalyzer enumAnnotationAnalyzer =
      EnumAnnotationAnalyzer();
  static const UintAnnotationAnalyzer uintAnnotationAnalyzer =
      UintAnnotationAnalyzer();

  // Enum analyzers
  static const EnumAnalyzer enumAnalyzer = EnumAnalyzerImpl();
  static const ClassAnalyzer classAnalyzer = ClassAnalyzerImpl();

  // Class analyzers
  static const ConstructorAnalyzer constructorAnalyzer = ConstructorAnalyzer();
  static const ImportsAnalyzer importsAnalyzer = ImportsAnalyzer();

  // Field analyzers
  static const AccessInfoAnalyzer accessInfoAnalyzer = AccessInfoAnalyzer();
  static const FieldsAnalyzer fieldsAnalyzer = FieldsAnalyzer();
  static const FieldAnalyzer fieldAnalyzer = FieldAnalyzerImpl();
  static const FieldSpecSorter fieldsSorter = FieldSpecSorter();

  // Type analyzers
  static final TypeAnalyzer typeAnalyzer = TypeAnalyzerImpl();
  static const TypeSystemAnalyzer typeSystemAnalyzer = TypeSystemAnalyzer();

  // Custom type analyzer
  static const CustomTypeAnalyzer customTypeAnalyzer = CustomTypeAnalyzer();
}
