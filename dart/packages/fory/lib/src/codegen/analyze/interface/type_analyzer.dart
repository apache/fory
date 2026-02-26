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

import 'package:fory/src/codegen/analyze/type_analysis_models.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/type_spec_generator.dart';
import 'package:fory/src/const/types.dart';

abstract class TypeAnalyzer {
  TypeSpecGenerator resolveTypeSpec(
    TypeAnalysisDecision typeDecision,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
  );

  /// Version of resolveTypeSpec that supports annotation-based ObjType override.
  /// Used when uint annotations are present on int fields.
  TypeSpecGenerator resolveTypeSpecWithOverride(
    TypeAnalysisDecision typeDecision,
    @RequireLocationLevel(LocationLevel.fieldLevel) LocationMark locationMark,
    ObjType objTypeOverride,
  );
}
