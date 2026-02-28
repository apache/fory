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

// @Skip()
library;

import 'dart:collection';
import 'dart:io';
import 'dart:typed_data';
import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/entity/complex_obj_1.dart';
import 'package:fory_test/entity/complex_obj_2.dart';
import 'package:fory_test/entity/complex_obj_3.dart';
import 'package:fory_test/entity/simple_struct1.dart';
import 'package:fory_test/util/cross_lang_util.dart';
import 'package:fory_test/util/test_file_util.dart';
import 'package:test/test.dart';

void main() {
  group('Cross-language struct serialization', () {
    test('round-trips SimpleStruct1', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(SimpleStruct1, typename: "SimpleStruct1");
      SimpleStruct1 obj = SimpleStruct1();
      obj.a = Int32.maxValue;
      Uint8List lis = fory.serialize(obj);
      Object? obj2 = fory.deserialize(lis);
      check(obj2).equals(obj);
    });

    test('round-trips ComplexObject2', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(ComplexObject2, typename: "test.ComplexObject2");
      ComplexObject2 o = ComplexObject2(true, {Int8(-1): Int32(2)});
      CrossLangUtil.structRoundBack(fory, o, "test_serialize_simple_struct");
    },
        skip:
            'Cross-language serialization with Python needs protocol alignment');

    test('round-trips ComplexObject1 with nested data', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(ComplexObject2, typename: "test.ComplexObject2");
      fory.register(ComplexObject1, typename: "test.ComplexObject1");
      ComplexObject2 obj2 = ComplexObject2(true, {Int8(-1): Int32(2)});
      ComplexObject1 obj = ComplexObject1();
      obj.f1 = obj2;
      obj.f2 = "abc";
      obj.f3 = ["abc", "abc"];
      obj.f4 = {Int8(1): Int32(2)};
      obj.f5 = Int8.maxValue;
      obj.f6 = Int16.maxValue;
      obj.f7 = Int32.maxValue;
      obj.f8 = 0x7FFFFFFFFFFFFFFF;
      obj.f9 = Float32(1.0 / 2);
      obj.f10 = 1 / 3.0;
      obj.f11 = Int16List.fromList([1, 2]);
      obj.f12 = [Int16(-1), Int16(4)];
      CrossLangUtil.structRoundBack(fory, obj, "test_serialize_complex_struct");
    },
        skip:
            'Cross-language serialization with Python needs protocol alignment');

    test('preserves cross-language references', () {
      Fory fory = Fory(
        ref: true,
      );
      List<Object> list = [];
      Map<Object, Object> map = {};
      list.add(list);
      list.add(map);
      map['k1'] = map;
      map['k2'] = list;

      Uint8List bytes = fory.serialize(list);

      File file = TestFileUtil.getWriteFile(
          'test_cross_language_reference.data', bytes);
      bool exeRes = CrossLangUtil.executeWithPython(
          'test_cross_language_reference', file.path);
      check(exeRes).isTrue();

      // deserialize
      bytes = file.readAsBytesSync();
      Object? obj = fory.deserialize(bytes);
      check(obj).isNotNull();
      List list1 = obj as List<Object?>;
      check(list1).identicalTo(list1[0]);
      Map<Object?, Object?> map1 = list1[1] as Map<Object?, Object?>;
      check(map1['k1']).identicalTo(map1['k1']);
      check(map1['k2']).identicalTo(list1);
    },
        skip:
            'Cross-language serialization with Python needs protocol alignment');

    test('round-trips ComplexObject3 with nested collections', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(ComplexObject3, typename: "test.ComplexObject3");
      ComplexObject3 obj = ComplexObject3();

      Map<int, Float32> map1 = {
        1: Float32(1.0),
        2: Float32(2.0),
      };
      Map<int, Float32> map2 = {
        3: Float32(3.0),
        4: Float32(4.0),
      };
      obj.f1 = [map1, map2];

      SplayTreeMap<int, Float32> splayTreeMap1 =
          SplayTreeMap<int, Float32>.of(map1);
      SplayTreeMap<int, Float32> splayTreeMap2 =
          SplayTreeMap<int, Float32>.of(map2);
      List<SplayTreeMap<int, Float32>> list1 = [splayTreeMap1, splayTreeMap2];
      HashMap<String, List<SplayTreeMap<int, Float32>>> map3 = HashMap();
      map3['key1'] = list1;
      obj.f2 = map3;

      HashSet<Int8> set1 = HashSet<Int8>.of([Int8(1), Int8(2)]);
      LinkedHashMap<String, HashSet<Int8>> map4 = LinkedHashMap();
      map4['key2'] = set1;
      obj.f3 = map4;

      Uint8List bytes = fory.serialize(obj);
      Object? obj2 = fory.deserialize(bytes);
      check(obj2).isNotNull();
      check(obj2).equals(obj);
    });
  });
}
