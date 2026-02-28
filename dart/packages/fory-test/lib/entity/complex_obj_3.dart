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
import 'package:fory/fory.dart';
import 'package:fory_test/extensions/map_ext.dart';

part '../generated/complex_obj_3.g.dart';

@foryClass
class ComplexObject3 {
  late final List<Map<int, Float32>> f1;
  late final HashMap<String, List<SplayTreeMap<int, Float32>>> f2;
  late final LinkedHashMap<String, HashSet<Int8>> f3;

  @override
  int get hashCode => Object.hash(f1, f2, f3);

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    if (other is! ComplexObject3) return false;
    if (other.runtimeType != runtimeType) return false;

    for (int i = 0; i < f1.length; i++) {
      if (!f1[i].equals(other.f1[i])) return false;
    }

    if (f2.length != other.f2.length) return false;
    for (var key in f2.keys) {
      var value1 = other.f2[key];
      if (value1 == null) return false;
      var value = f2[key];
      if (value!.length != value1.length) return false;
      for (int i = 0; i < value.length; i++) {
        if (!value[i].equals(value1[i])) return false;
      }
    }

    if (f3.length != other.f3.length) return false;
    for (var key in f3.keys) {
      var value1 = other.f3[key];
      if (value1 == null) return false;
      var value = f3[key];
      if (value!.length != value1.length) return false;
      for (int i = 0; i < value.length; i++) {
        if (value.elementAt(i) != value1.elementAt(i)) return false;
      }
    }
    return true;
  }
}
