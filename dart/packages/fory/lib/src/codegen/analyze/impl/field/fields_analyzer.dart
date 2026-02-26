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
import 'package:fory/src/codegen/analyze/analysis_cache.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/analyze/impl/field/non_static_field_visitor.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/fields_cache_unit.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/meta/impl/field_spec_immutable.dart';
import 'package:fory/src/codegen/meta/public_accessor_descriptor.dart';

class FieldsAnalyzer {
  const FieldsAnalyzer();

  FieldsCacheUnit analyzeFields(
    ClassElement element,
    @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureClassLevel);
    assert(
        element.supertype != null); // At the very least, it is also an Object
    FieldsCacheUnit res = _analyzeFieldsInner(element, locationMark)!;
    return res;
  }

  // For efficiency, a key can be provided here (because the key may have already been established, reuse it if possible)
  FieldsCacheUnit? _analyzeFieldsInner(
    ClassElement element,
    @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureClassLevel);
    if (element.supertype == null) {
      return null; // No need to analyze further if the inheritance chain reaches Object
    }

    FieldsCacheUnit? cacheUnit = AnalysisCache.getFields(element.id);
    if (cacheUnit != null) {
      return cacheUnit;
    }

    FieldsCacheUnit? superCacheUnit = _analyzeFieldsInner(
        element.supertype!.element as ClassElement, locationMark);

    List<FieldSpecImmutable>? superFields = superCacheUnit?.fieldImmutables;
    Set<String>? superParamNames = superCacheUnit?.fieldNames;

    // Start analyzing the fields of the current class
    NonStaticFieldVisitor visitor =
        NonStaticFieldVisitor(superParamNames, locationMark);
    element.visitChildren(visitor);
    // analyse setter and getter
    _analyzeFieldSetAndGet(visitor.fields, visitor.accessors);
    // put this and super together
    List<FieldSpecImmutable> fields = visitor.fields;
    Set<String> fieldNames = fields.map((e) => e.name).toSet();
    if (superCacheUnit != null) {
      fields.addAll(superFields!);
      fieldNames.addAll(superParamNames!);
    }

    bool superAllFieldsIndependent =
        (superCacheUnit == null) || (superCacheUnit.allFieldsIndependent);
    late bool allFieldsIndependent;
    if (!superAllFieldsIndependent) {
      allFieldsIndependent = false;
    } else {
      allFieldsIndependent = true;
      for (var field in fields) {
        if (!field.typeSpec.independent) {
          allFieldsIndependent = false;
          break;
        }
      }
    }
    cacheUnit = FieldsCacheUnit(fields, allFieldsIndependent, fieldNames);
    // cache
    AnalysisCache.putFields(element.id, cacheUnit);
    return cacheUnit;
  }

  // This method only analyzes field readability and writability through setters and getters.
  // However, for fields like finalAndHasInitializer, the setter cannot change the fact that canSet=false
  void _analyzeFieldSetAndGet(List<FieldSpecImmutable> fields,
      List<PublicAccessorDescriptor> accessors) {
    accessors.sort((a, b) => a.name.compareTo(b.name));
    for (var field in fields) {
      if (field.isPublic) {
        assert(field.canGet);
        continue;
      }
      if (field.accessUnchangeable) {
        continue;
      }
      final accessor =
          _searchAccessorByName(field.name.substring(1), accessors);
      if (accessor != null) {
        field.notifyHasSetter(accessor.hasSetter);
        field.notifyHasGetter(accessor.hasGetter);
      } else {
        field.notifyHasSetter(false);
        field.notifyHasGetter(false);
      }
    }
  }

  PublicAccessorDescriptor? _searchAccessorByName(
      String name, List<PublicAccessorDescriptor> accessors) {
    int low = 0;
    int high = accessors.length - 1;
    while (low <= high) {
      int mid = (low + high) >> 1;
      int cmp = accessors[mid].name.compareTo(name);
      if (cmp == 0) {
        return accessors[mid];
      } else if (cmp < 0) {
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return null;
  }
}
