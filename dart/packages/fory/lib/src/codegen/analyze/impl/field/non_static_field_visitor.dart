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
import 'package:analyzer/dart/element/visitor.dart';
import 'package:fory/src/codegen/analyze/analyzer.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/either.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/field_spec_immutable.dart';
import 'package:fory/src/codegen/meta/public_accessor_descriptor.dart';

class NonStaticFieldVisitor extends SimpleElementVisitor {
  final LocationMark _locationMark;
  final Set<String>? _superParamNames;

  final List<FieldSpecImmutable> fields = [];
  final List<PublicAccessorDescriptor> accessors = [];

  NonStaticFieldVisitor(
    this._superParamNames,
    @RequireLocationLevel(LocationLevel.clsLevel) this._locationMark,
  ) {
    assert(_locationMark.ensureClassLevel);
  }

  bool _checkFieldOverride(String fieldName) {
    return (_superParamNames != null && _superParamNames.contains(fieldName));
  }

  @override
  visitFieldElement(FieldElement element) {
    element.type.element?.library;
    if (element.isStatic) return;
    Either<FieldSpecImmutable, PublicAccessorDescriptor>? field =
        Analyzer.fieldAnalyzer.analyze(
      element,
      _checkFieldOverride,
      _locationMark,
    );
    if (field == null) return;
    if (field.isLeft) {
      fields.add(field.left!);
    } else {
      accessors.add(field.right!);
    }
  }
}
