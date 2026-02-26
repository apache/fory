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
import 'dart:typed_data';
import 'package:collection/collection.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/fory_type_provider.dart';

/// Type i = Uint8List;
/// Uint8List lis = Uint8List(10);
/// print(lis.runtimeType == i);
/// false
final class DartTypeResolver {
  static const DartTypeResolver I = DartTypeResolver._internal();
  const DartTypeResolver._internal();

  @inline
  Type getForyType(Object obj) {
    if (obj is ForyTypeProvider) {
      return obj.foryType;
    }
    if (obj is Enum) {
      return obj.runtimeType;
    }
    if (obj is Map) {
      if (obj is LinkedHashMap) return LinkedHashMap;
      if (obj is HashMap) return HashMap;
      if (obj is SplayTreeMap) return SplayTreeMap;
      return Map;
    }
    if (obj is List) {
      if (obj is TypedDataList) {
        switch (obj.elementSizeInBytes) {
          case 1:
            if (obj is Uint8List) return Uint8List;
            if (obj is Uint8ClampedList) return Uint8ClampedList;
            if (obj is Int8List) return Int8List;
          case 2:
            if (obj is Uint16List) return Uint16List;
            if (obj is Int16List) return Int16List;
          case 4:
            if (obj is Uint32List) return Uint32List;
            if (obj is Int32List) return Int32List;
            if (obj is Float32List) return Float32List;
          case 8:
            if (obj is Uint64List) return Uint64List;
            if (obj is Int64List) return Int64List;
            if (obj is Float64List) return Float64List;
          default:
            return TypedDataList;
        }
      }
      if (obj is BoolList) return BoolList;
      return List;
    }
    if (obj is Set) {
      if (obj is LinkedHashSet) return LinkedHashSet;
      if (obj is HashSet) return HashSet;
      if (obj is SplayTreeSet) return SplayTreeSet;
      return Set;
    }
    return obj.runtimeType;
  }
}
