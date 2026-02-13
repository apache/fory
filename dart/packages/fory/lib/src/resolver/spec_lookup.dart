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

import 'dart:collection';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/resolver/spec_lookup_stub.dart'
    if (dart.library.mirrors) 'package:fory/src/resolver/spec_lookup_mirrors.dart'
    as impl;

final class SpecLookup {
  static final Map<Type, CustomTypeSpec?> _resolvedCache =
      HashMap<Type, CustomTypeSpec?>();
  static final Map<Type, CustomTypeSpec> _manualSpecs =
      HashMap<Type, CustomTypeSpec>();

  static CustomTypeSpec? resolve(Type type) {
    if (_resolvedCache.containsKey(type)) {
      return _resolvedCache[type];
    }
    final CustomTypeSpec? spec =
        _manualSpecs[type] ?? impl.resolveByMirrors(type);
    _resolvedCache[type] = spec;
    return spec;
  }

  static void register(CustomTypeSpec spec) {
    _manualSpecs[spec.dartType] = spec;
    _resolvedCache[spec.dartType] = spec;
  }

  static void registerAll(Iterable<CustomTypeSpec> specs) {
    for (final CustomTypeSpec spec in specs) {
      register(spec);
    }
  }
}
