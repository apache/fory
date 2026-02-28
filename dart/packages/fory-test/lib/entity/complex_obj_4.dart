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

import 'package:fory/fory.dart';
import 'package:fory_test/extensions/map_ext.dart';

part '../generated/complex_obj_4.g.dart';

@foryClass
class ComplexObject4 {
  late String f1;
  late String f2;
  late List<String> f3;
  late Map<int, double> f4;
  late int f5;
  late int f6;
  late int f7;
  late int f8;
  late double f9;
  late double f10;
  late List<double> f11;
  late List<int> f12;

  ComplexObject4();

  @override
  int get hashCode =>
      Object.hash(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12);

  // define ==
  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is ComplexObject4 &&
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

  @override
  ComplexObject4.fromJson(Map<String, dynamic> json)
      : f1 = json['f1'] as String,
        f2 = json['f2'] as String,
        f3 = (json['f3'] as List<dynamic>).map((e) => e as String).toList(),
        f4 = (json['f4'] as Map<String, dynamic>).map(
          (k, e) => MapEntry(int.parse(k), (e as num).toDouble()),
        ),
        f5 = json['f5'] as int,
        f6 = json['f6'] as int,
        f7 = json['f7'] as int,
        f8 = json['f8'] as int,
        f9 = (json['f9'] as num).toDouble(),
        f10 = (json['f10'] as num).toDouble(),
        f11 = (json['f11'] as List<dynamic>)
            .map((e) => (e as num).toDouble())
            .toList(),
        f12 = (json['f12'] as List<dynamic>).map((e) => e as int).toList();

  Map<String, dynamic> toJson() {
    return {
      'f1': f1,
      'f2': f2,
      'f3': f3,
      'f4': f4.map((k, e) => MapEntry(k.toString(), e)),
      'f5': f5,
      'f6': f6,
      'f7': f7,
      'f8': f8,
      'f9': f9,
      'f10': f10,
      'f11': f11,
      'f12': f12,
    };
  }
}
