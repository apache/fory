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

import 'dart:typed_data';
import 'package:fory/fory.dart';
import 'package:fory_test/extensions/map_ext.dart';

part '../generated/complex_obj_1.g.dart';

@foryClass
class ComplexObject1 {
  late Object f1;
  late String f2;
  late List<Object> f3;
  late Map<Int8, Int32> f4;
  late Int8 f5;
  late Int16 f6;
  late Int32 f7;
  late int f8;
  late Float32 f9;
  late double f10;
  late Int16List f11;
  late List<Int16> f12;

  @override
  int get hashCode =>
      Object.hash(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12);

  // define ==
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is ComplexObject1 &&
            runtimeType == other.runtimeType &&
            f1 == other.f1 &&
            f2 == other.f2 &&
            f3.equals(other.f3) &&
            f4.equals(other.f4) &&
            f5 == other.f5 &&
            f6 == other.f6 &&
            f7 == other.f7 &&
            f8 == other.f8 &&
            f9 == other.f9 &&
            f10 == other.f10 &&
            f11.equals(other.f11) &&
            f12.equals(other.f12));
  }
}
