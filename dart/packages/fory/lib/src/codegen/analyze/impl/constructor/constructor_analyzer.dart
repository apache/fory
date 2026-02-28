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

// TODO: The current analysis function does not support field override in the inheritance chain (not recommended by Dart official)
// TODO: It only focuses on UnnamedConstructor
// TODO: And for the parameters of UnnamedConstructor, it only cares about parameters prefixed with super and this
import 'package:analyzer/dart/constant/value.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:fory/src/codegen/analyze/analysis_cache.dart';
import 'package:fory/src/codegen/analyze/analysis_type_identifier.dart';
import 'package:fory/src/codegen/analyze/annotation/require_location_level.dart';
import 'package:fory/src/codegen/const/location_level.dart';
import 'package:fory/src/codegen/entity/constructor_param.dart';
import 'package:fory/src/codegen/entity/constructor_params.dart';
import 'package:fory/src/codegen/entity/location_mark.dart';
import 'package:fory/src/codegen/exception/constraint_violation_exception.dart'
    show
        CircularIncapableRisk,
        InformalConstructorParamException,
        NoUsableConstructorException;
import 'package:fory/src/codegen/meta/impl/constructor_info.dart'
    show ConstructorInfo;

class ConstructorAnalyzer {
  const ConstructorAnalyzer();

  ConstructorParams? _analyzeInner(ConstructorElement element,
      @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
      [int? classElementId, int depth = 0]) {
    classElementId ??= element.enclosingElement3.id;
    // Each annotated class will only be analyzed once, and a class has only one UnnamedConstructor.
    // Therefore, for depth = 0, it must be the first time the class is being analyzed,
    // so it is definitely not in the cache and there is no need to check.
    if (depth != 0) {
      assert(depth > 0);
      if (element.superConstructor == null) {
        return null; // There's nothing to analyze once the inheritance chain reaches Object
      }
      // now got key, check cache
      ConstructorParams? cParams =
          AnalysisCache.getUnnamedConstructorParams(classElementId);
      if (cParams != null) return cParams;
    }

    ConstructorParams? superConsParams =
        _analyzeInner(element.superConstructor!, locationMark, null, depth + 1);
    final List<ConstructorParam>? superPositional = superConsParams?.positional;

    final params = element.parameters;
    final List<ConstructorParam> positional = [];
    final List<ConstructorParam> named = [];

    int superPositionalCount = 0;
    for (int i = 0; i < params.length; ++i) {
      final param = params[i];
      if (param.isInitializingFormal) {
        if (param.isPositional) {
          positional
              .add(ConstructorParam.withName(param.name, param.isOptional));
        } else {
          // is named
          assert(param.isNamed);
          named.add(ConstructorParam.withName(param.name, param.isOptional));
        }
      } else if (param.isSuperFormal) {
        // If it indicates super, then we can be sure that superConsParams is not null
        if (param.isPositional) {
          positional.add(superPositional![superPositionalCount++]
              .copyWithOptional(param
                  .isOptional)); // Must copy, otherwise it will lead to inconsistency
        } else {
          assert(param.isNamed);
          named.add(ConstructorParam.withName(param.name, param.isOptional));
        }
      } else {
        // Indicates a regular parameter
        if (param.isOptional) {
          // TODO: Maybe we can enforce stricter limitations here
          // This indicates that it is neither an initialization parameter nor a super parameter, which means it is a regular parameter.
          // However, for optional parameters, we will not throw an exception here, but this does not mean it passes the check,
          // because later we will analyze it with the fields to see if all required fields have a chance to be assigned a value.
          // TODO: The handling of non-exceptional cases (e.g., WARNING) is relatively simple here, we can try to establish a dedicated logging component later.
          print(
              "[WARNING] constructor param ${param.name} isn't initializing formal or super formal, but optional, please check");
        } else {
          throw InformalConstructorParamException(
              locationMark.libPath, locationMark.clsName, [
            param.name,
          ]);
        }
      }
    }
    // now got all params
    ConstructorParams cParams = ConstructorParams(
      positional,
      named,
    );
    AnalysisCache.putUnnamedConstructorParams(classElementId, cParams);
    return cParams;
  }

  ConstructorElement? _findUnnamedCons(
    List<ConstructorElement> cons,
  ) {
    for (var consEle in cons) {
      if (consEle.name.isEmpty) {
        return consEle;
      }
    }
    return null;
  }

  ConstructorElement? _findSpecifiedCons(
    List<ConstructorElement> cons,
  ) {
    late DartObject anno;
    late ClassElement annoClsElement;
    for (var consEle in cons) {
      for (var annoEle in consEle.metadata) {
        anno = annoEle.computeConstantValue()!;
        annoClsElement = anno.type!.element as ClassElement;
        if (AnalysisTypeIdentifier.isForyConstructorAnnotation(
            annoClsElement)) {
          return consEle;
        }
      }
    }
    return null;
  }

  /// looking for flexible constructor
  ConstructorElement? _findFlexibleCons(
    List<ConstructorElement> cons,
  ) {
    for (var consEle in cons) {
      if (_isFlexible(consEle)) {
        return consEle;
      }
    }
    return null;
  }

  bool _isFlexible(
    ConstructorElement cons,
  ) {
    for (final param in cons.parameters) {
      if (param.isOptional) continue;
      return false;
    }
    return true;
  }

  void _checkCircularRisk(
    bool isFlex,
    bool allFieldsPrimitive,
    bool promiseAcyclic,
    @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
  ) {
    if (isFlex || allFieldsPrimitive || promiseAcyclic) return;
    throw CircularIncapableRisk(
      locationMark.libPath,
      locationMark.clsName,
    );
  }

  ConstructorInfo analyze(
    List<ConstructorElement> cons,
    int classElementId,
    bool promiseAcyclic,
    bool allFieldsPrimitive,
    @RequireLocationLevel(LocationLevel.clsLevel) LocationMark locationMark,
  ) {
    assert(locationMark.ensureClassLevel);
    // Look for whether the user has specified a constructor
    late bool isFlexible;
    ConstructorElement? consEle = _findSpecifiedCons(cons);
    if (consEle != null) {
      isFlexible = _isFlexible(consEle);
      _checkCircularRisk(
        isFlexible,
        allFieldsPrimitive,
        promiseAcyclic,
        locationMark,
      );
    } else {
      consEle = _findFlexibleCons(cons);
      isFlexible = consEle != null;
      if (!isFlexible) {
        // Indicates no specified constructor, nor a flexible constructor
        // So we will use the UnnamedConstructor
        consEle = _findUnnamedCons(cons);
        if (consEle == null) {
          throw NoUsableConstructorException(
            locationMark.libPath,
            locationMark.clsName,
            "You didn't specify a constructor using the @ForyCons annotation, "
            "but this class itself also doesn't have an Unnamed constructor "
            "or a constructor that takes no parameters, "
            "to the point that it can't continue analyzing it",
          );
        } else {
          // Found the UnnamedConstructor, so this is the only one that can be used
          _checkCircularRisk(
            false,
            allFieldsPrimitive,
            promiseAcyclic,
            locationMark,
          );
        }
        isFlexible = false;
      }
    }
    if (isFlexible) {
      return ConstructorInfo.flexibleConstructor(consEle.name);
    }
    // Indicates the specified constructor
    assert(consEle.superConstructor !=
        null); // Currently analyzing a class, it cannot be Object. In Dart, only the Object class does not have a super class.
    return ConstructorInfo.unnamedConstructor(
      _analyzeInner(consEle, locationMark, classElementId, 0),
    );
  }
}
