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
import 'package:fory_test/util/cross_lang_util.dart';
import 'package:fory_test/util/test_file_util.dart';
import 'package:test/test.dart';
import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/entity/enum_foo.dart';
import 'package:fory_test/extensions/array_ext.dart';
import 'package:fory_test/extensions/collection_ext.dart';
import 'package:fory_test/extensions/map_ext.dart';
import 'package:fory_test/extensions/obj_ext.dart';

T Function<T>(Fory, Fory, T) serDe = CrossLangUtil.serDe;

void _testTypedDataArray(Fory fory1, Fory fory2) {
  BoolList blist = BoolList.generate(10, (i) => i % 2 == 0);
  check(blist.equals(serDe(fory1, fory2, blist))).isTrue();

  Int8List i8list = Int8List.fromList(List<int>.generate(100, (i) => i * 100));
  check(i8list.memEquals(serDe(fory1, fory2, i8list))).isTrue();

  Int16List i16list =
      Int16List.fromList(List<int>.generate(100, (i) => i * 100));
  check(i16list.memEquals(serDe(fory1, fory2, i16list))).isTrue();

  Int32List i32list =
      Int32List.fromList(List<int>.generate(100, (i) => i * -1000));
  check(i32list.memEquals(serDe(fory1, fory2, i32list))).isTrue();

  Int64List i64list =
      Int64List.fromList(List<int>.generate(100, (i) => i * -10000));
  check(i64list.memEquals(serDe(fory1, fory2, i64list))).isTrue();

  Float32List f32list =
      Float32List.fromList(List<double>.generate(100, (i) => i * -31415.0));
  check(f32list.memEquals(serDe(fory1, fory2, f32list))).isTrue();

  Float64List f64list =
      Float64List.fromList(List<double>.generate(100, (i) => i * -314150.0));
  check(f64list.memEquals(serDe(fory1, fory2, f64list))).isTrue();
}

void _testCollectionType(Fory fory1, Fory fory2) {
  List<int> list = List<int>.generate(10, (i) => i * 100);
  List list1 = serDe(fory1, fory2, list);
  check(list1.equals(list)).isTrue();

  List<String> strList = List<String>.generate(10, (i) => 'str$i');
  List strList1 = serDe(fory1, fory2, strList);
  check(strList1.equals(strList)).isTrue();

  SplayTreeSet<Float32> set = SplayTreeSet<Float32>();
  for (int i = 0; i < 10; ++i) {
    set.add(Float32(i * -10.137));
  }
  Object? obj = serDe(fory1, fory2, set);
  check(obj).isA<Set>();
  check((obj as Set).equals(set)).isTrue();
}

void _testArrayCollection(bool ref) {
  Fory fory1 = Fory(
    ref: ref,
  );
  Fory fory2 = Fory(
    ref: ref,
  );
  _testTypedDataArray(fory1, fory2);
  _testCollectionType(fory1, fory2);
}

void _basicTypeTest(bool ref) {
  Fory fory1 = Fory(
    ref: ref,
  );
  Fory fory2 = Fory(
    ref: ref,
  );
  check('str').equals(serDe(fory1, fory2, 'str'));
  // with non-latin char
  check('2023年10月23日').equals(serDe(fory1, fory2, '2023年10月23日'));
  check(true).equals(CrossLangUtil.serDe(fory1, fory2, true));

  fory1.register(EnumFoo);
  fory2.register(EnumFoo);
  fory1.register(EnumSubClass);
  fory2.register(EnumSubClass);

  check(EnumFoo.A).equals(serDe(fory1, fory2, EnumFoo.A));
  check(EnumFoo.B).equals(serDe(fory1, fory2, EnumFoo.B));

  check(EnumSubClass.A).equals(serDe(fory1, fory2, EnumSubClass.A));
  check(EnumSubClass.B).equals(serDe(fory1, fory2, EnumSubClass.B));

  LocalDate day = LocalDate.now();
  check(day).equals(serDe(fory1, fory2, day));

  TimeStamp ts = TimeStamp.now();
  check(ts).equals(serDe(fory1, fory2, ts));
}

void main() {
  group('Cross-language data type serialization', () {
    test('serializes various datatypes via ByteWriter', () {
      Fory fory = Fory(
        ref: true,
      );
      ByteWriter bw = ByteWriter();
      fory.serializeTo(true, bw);
      fory.serializeTo(false, bw);
      fory.serializeTo(Int32(-1), bw);
      fory.serializeTo(Int8.maxValue, bw);
      fory.serializeTo(Int8.minValue, bw);
      fory.serializeTo(Int16.maxValue, bw);
      fory.serializeTo(Int16.minValue, bw);
      fory.serializeTo(Int32.maxValue, bw);
      fory.serializeTo(Int32.minValue, bw);
      fory.serializeTo(0x7fffffffffffffff, bw);
      fory.serializeTo(0x8000000000000000, bw);
      fory.serializeTo(Float32(-1.0), bw);
      fory.serializeTo(-1.0, bw);
      fory.serializeTo('str', bw);

      LocalDate day = LocalDate(2021, 11, 23);
      fory.serializeTo(day, bw);

      TimeStamp ts = TimeStamp.fromSecondsSinceEpoch(100);
      fory.serializeTo(ts, bw);

      List<Object> list = ['a', Int32(1), -1.0, ts, day];
      fory.serializeTo(list, bw);

      Map<Object, Object> map = HashMap();
      for (int i = 0; i < list.length; ++i) {
        map['k$i'] = list[i];
        map[list[i]] = list[i];
      }
      fory.serializeTo(map, bw);

      Set<Object> set = HashSet.of(list);
      fory.serializeTo(set, bw);

      BoolList blist = BoolList.of([true, false]);
      fory.serializeTo(blist, bw);

      Int16List i16list = Int16List.fromList([1, 32767]);
      fory.serializeTo(i16list, bw);

      Int32List i32list = Int32List.fromList([1, 0x7fffffff]);
      fory.serializeTo(i32list, bw);

      Int64List i64list = Int64List.fromList([1, 0x7fffffffffffffff]);
      fory.serializeTo(i64list, bw);

      Float32List f32list = Float32List.fromList([1.0, 2.0]);
      fory.serializeTo(f32list, bw);

      Float64List f64list = Float64List.fromList([1.0, 2.0]);
      fory.serializeTo(f64list, bw);

      Uint8List bytes1 = bw.takeBytes();

      testFunc(Uint8List bytes) {
        ByteReader br = ByteReader.forBytes(bytes);
        check(fory.deserialize(bytes, br) as bool).isTrue();
        check(fory.deserialize(bytes, br) as bool).isFalse();
        check(Int32(-1) == fory.deserialize(bytes, br)).isTrue();
        check(Int8.maxValue == fory.deserialize(bytes, br)).isTrue();
        check(Int8.minValue == fory.deserialize(bytes, br)).isTrue();
        check(Int16.maxValue == fory.deserialize(bytes, br)).isTrue();
        check(Int16.minValue == fory.deserialize(bytes, br)).isTrue();
        check(Int32.maxValue == fory.deserialize(bytes, br)).isTrue();
        check(Int32.minValue == fory.deserialize(bytes, br)).isTrue();
        check(fory.deserialize(bytes, br) as int).equals(0x7fffffffffffffff);
        check(fory.deserialize(bytes, br) as int).equals(0x8000000000000000);
        check(Float32(-1.0) == fory.deserialize(bytes, br)).isTrue();
        check(fory.deserialize(bytes, br) as double).equals(-1.0);
        check(fory.deserialize(bytes, br) as String).equals('str');

        check(fory.deserialize(bytes, br) as LocalDate).equals(day);
        check(fory.deserialize(bytes, br) as TimeStamp).equals(ts);
        check((fory.deserialize(bytes, br) as List).strEquals(list)).isTrue();
        check((fory.deserialize(bytes, br) as Map).equals(map)).isTrue();
        check((fory.deserialize(bytes, br) as Set).equals(set)).isTrue();
        check((fory.deserialize(bytes, br) as BoolList).equals(blist)).isTrue();
        check((fory.deserialize(bytes, br) as Int16List).equals(i16list))
            .isTrue();
        check((fory.deserialize(bytes, br) as Int32List).equals(i32list))
            .isTrue();
        check((fory.deserialize(bytes, br) as Int64List).equals(i64list))
            .isTrue();
        check((fory.deserialize(bytes, br) as Float32List).equals(f32list))
            .isTrue();
        check((fory.deserialize(bytes, br) as Float64List).equals(f64list))
            .isTrue();
      }

      testFunc(bytes1);

      File file =
          TestFileUtil.getWriteFile('test_cross_language_serializer', bytes1);
      bool exeRes = CrossLangUtil.executeWithPython(
          'test_cross_language_serializer', file.path);
      check(exeRes).isTrue();
      Uint8List bytes2 = file.readAsBytesSync();
      testFunc(bytes2);
    },
        skip:
            'Cross-language serialization with Python needs protocol alignment');

    test('round-trips basic types with/without ref', () {
      _basicTypeTest(true);
      _basicTypeTest(false);
    });

    test('round-trips arrays & collections with/without ref', () {
      _testArrayCollection(true);
      _testArrayCollection(false);
    });
  });
}
