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

import 'dart:typed_data';

import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/entity/time_obj.dart';
import 'package:fory_test/extensions/array_ext.dart';
import 'package:fory_test/extensions/map_ext.dart';
import 'package:test/test.dart';

Object? _roundTrip(Fory fory, Object? obj) {
  Uint8List bytes = fory.serialize(obj);
  Object? obj2 = fory.deserialize(bytes);
  return obj2;
}

void main() {
  group('Type preservation', () {
    test('primitives preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      Object? obj0 = _roundTrip(fory, true);
      check(obj0).isNotNull().isA<bool>();
      check(obj0).equals(true);

      Object? obj1 = _roundTrip(fory, Int8.maxValue);
      check(obj1).isNotNull().isA<Int8>();
      check(obj1).equals(Int8.maxValue);

      Object? obj2 = _roundTrip(fory, Int16.maxValue);
      check(obj2).isNotNull().isA<Int16>();
      check(obj2).equals(Int16.maxValue);

      Object? obj3 = _roundTrip(fory, Int32.maxValue);
      check(obj3).isNotNull().isA<Int32>();
      check(obj3).equals(Int32.maxValue);

      Object? obj4 = _roundTrip(fory, 0x7FFFFFFFFFFFFFFF);
      check(obj4).isNotNull().isA<int>();
      check(obj4).equals(0x7FFFFFFFFFFFFFFF);

      Object? obj5 = _roundTrip(fory, Float32(-1.1));
      check(obj5).isNotNull().isA<Float32>();
      check(obj5).equals(Float32(-1.1));

      Object? obj6 = _roundTrip(fory, -1.1);
      check(obj6).isNotNull().isA<double>();
      check(obj6).equals(-1.1);
    });

    test('strings and codeUnits preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      Object? obj1 = _roundTrip(fory, "str");
      check(obj1).isNotNull().isA<String>();
      check(obj1).equals("str");

      Object? obj2 = _roundTrip(fory, "软件测试".codeUnits);
      check(obj2).isNotNull().isA<List>();
      check((obj2 as List).equals("软件测试".codeUnits)).isTrue();

      Object? obj3 = _roundTrip(fory, '软件测试');
      check(obj3).isNotNull().isA<String>();
      check(obj3).equals("软件测试");
    });

    test('BoolList preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      Object? obj1 =
          _roundTrip(fory, BoolList.of([true, false, true, true, false, true]));
      check(obj1).isNotNull().isA<BoolList>();
      BoolList boolList = obj1 as BoolList;
      BoolList boolList1 = BoolList.of([true, false, true, true, false, true]);
      check(boolList.equals(boolList1)).isTrue();
    });

    test('typed arrays preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      Int32List int32List = Int32List.fromList([1, 2]);
      Object? obj4 = _roundTrip(fory, int32List);
      check(obj4).isNotNull().isA<Int32List>();
      Int32List int32List1 = obj4 as Int32List;
      check(int32List1.memEquals(int32List)).isTrue();

      Int64List int64List = Int64List.fromList([1, 2]);
      Object? obj5 = _roundTrip(fory, int64List);
      check(obj5).isNotNull().isA<Int64List>();
      Int64List int64List1 = obj5 as Int64List;
      check(int64List1.memEquals(int64List)).isTrue();

      Uint8List byteList = Uint8List.fromList([1, 2]);
      Object? obj6 = _roundTrip(fory, byteList);
      check(obj6).isNotNull().isA<Uint8List>();
      Uint8List byteList1 = obj6 as Uint8List;
      check(byteList1.memEquals(byteList)).isTrue();

      Int16List int16List = Int16List.fromList([1, 2]);
      Object? obj7 = _roundTrip(fory, int16List);
      check(obj7).isNotNull().isA<Int16List>();
      Int16List int16List1 = obj7 as Int16List;
      check(int16List1.memEquals(int16List)).isTrue();
    });

    test('Lists preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      List<String> strList = ["str", "str"];
      Object? obj1 = _roundTrip(fory, strList);
      check(obj1).isNotNull().isA<List>();
      List strList1 = obj1 as List;
      check(strList1.equals(strList)).isTrue();

      List<Object> objList = ['str', 1];
      Object? obj2 = _roundTrip(fory, objList);
      check(obj2).isNotNull().isA<List>();
      List objList1 = obj2 as List;
      check(objList1.equals(objList)).isTrue();

      List<List<int>> intList = [
        [1, 2],
        [1, 2]
      ];
      Object? obj3 = _roundTrip(fory, intList);
      check(obj3).isNotNull().isA<List>();
      List intList1 = obj3 as List;
      check(intList1.equals(intList, const ListEquality())).isTrue();
    });

    test('Maps preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      Map<String, Int16> strMap = {"key": Int16(1), "key2": Int16(2)};
      Object? obj1 = _roundTrip(fory, strMap);
      check(obj1).isNotNull().isA<Map>();
      Map strMap1 = obj1 as Map;
      check(strMap1.equals(strMap)).isTrue();
    });

    test('TimeObj preserved', () {
      Fory fory = Fory(
        ref: true,
      );
      TimeObj timeObj = TimeObj(
        LocalDate.epoch,
        LocalDate(2025, 3, 19),
        LocalDate(2023, 3, 20),
        LocalDate(2023, 1, 4),
        TimeStamp(0),
        TimeStamp(1714490301000000),
        TimeStamp(1742373844000000),
        TimeStamp(-1714490301000000),
      );

      fory.register(TimeObj, typename: "test.TimeObj");
      Object? obj1 = _roundTrip(fory, timeObj);
      check(obj1).isNotNull().isA<TimeObj>();
      TimeObj timeObj1 = obj1 as TimeObj;
      check(timeObj1).equals(timeObj);
    });
  });
}
