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
import 'package:test/test.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/entity/complex_obj_1.dart';
import 'package:fory_test/entity/complex_obj_2.dart';

void _testPerfSerialize(Fory fory, Object? obj, int times, String testName) {
  //warm up
  for (int i = 0; i < 10000; i++) {
    fory.serialize(obj);
  }
  // measure
  final stopwatch = Stopwatch()..start();
  for (int i = 0; i < times; ++i) {
    fory.serialize(obj);
  }
  stopwatch.stop();
  print(
      '$testName\nserialize simple struct test $times times: ${stopwatch.elapsedMilliseconds} ms');
}

void _testPerfDeserialize(Fory fory, Object? obj, int times, String testName) {
  Uint8List bytes = fory.serialize(obj);
  // warm up
  for (int i = 0; i < 10000; i++) {
    fory.deserialize(bytes);
  }
  // measure
  final stopwatch = Stopwatch()..start();
  for (int i = 0; i < times; i++) {
    fory.deserialize(bytes);
  }
  stopwatch.stop();
  print(
      '$testName\ndeserialize simple struct test $times times: ${stopwatch.elapsedMilliseconds} ms');
}

void main() {
  group('Serialization & Deserialization Performance', () {
    test('Serialize simple struct', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(ComplexObject2, typename: "test.ComplexObject2");
      ComplexObject2 o = ComplexObject2(true, {Int8(-1): Int32(2)});
      _testPerfSerialize(fory, o, 1000000, 'Serialize simple struct');
    });

    test('Deserialize simple struct', () {
      Fory fory = Fory(
        ref: true,
      );
      fory.register(ComplexObject2, typename: "test.ComplexObject2");
      ComplexObject2 o = ComplexObject2(true, {Int8(-1): Int32(2)});
      _testPerfDeserialize(fory, o, 1000000, 'Deserialize simple struct');
    });

    test('Serialize medium complex struct', () {
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
      _testPerfSerialize(fory, obj, 1000000, 'Serialize medium complex struct');
    });

    test('Deserialize medium complex struct', () {
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
      _testPerfDeserialize(
          fory, obj, 1000000, 'Deserialize medium complex struct');
    });

    // test('test json serialize medium complex struct perf', () {
    //   Fory fory = Fory(
    //     ref: true,
    //   );
    //   fory.register(ComplexObject2, typename: "test.ComplexObject2");
    //   fory.register(ComplexObject1, typename: "test.ComplexObject1");
    //   ComplexObject2 obj2 = ComplexObject2(true,{Int8(-1):Int32(2)});
    //   ComplexObject1 obj = ComplexObject1();
    //   obj.f1 = obj2;
    //   obj.f2 = "abc";
    //   obj.f3 = ["abc", "abc"];
    //   obj.f4 = {Int8(1): Int32(2)};
    //   obj.f5 = Int8.maxValue;
    //   obj.f6 = Int16.maxValue;
    //   obj.f7 = Int32.maxValue;
    //   obj.f8 = 0x7FFFFFFFFFFFFFFF;
    //   obj.f9 = Float32(1.0 / 2);
    //   obj.f10 = 1 / 3.0;
    //   obj.f11 = Int16List.fromList([1, 2]);
    //   obj.f12 = [Int16(-1),Int16(4)];
    //   _testPerfSerialize(fory, obj, 1000000, 'test deserialize medium complex struct perf');
    // });
    //
    // test('test json deserialize medium complex struct perf', () {
    //   Fory fory = Fory(
    //     ref: true,
    //   );
    //   fory.register(ComplexObject2, typename: "test.ComplexObject2");
    //   fory.register(ComplexObject1, typename: "test.ComplexObject1");
    //   ComplexObject2 obj2 = ComplexObject2(true,{Int8(-1):Int32(2)});
    //   ComplexObject1 obj = ComplexObject1();
    //   obj.f1 = obj2;
    //   obj.f2 = "abc";
    //   obj.f3 = ["abc", "abc"];
    //   obj.f4 = {Int8(1): Int32(2)};
    //   obj.f5 = Int8.maxValue;
    //   obj.f6 = Int16.maxValue;
    //   obj.f7 = Int32.maxValue;
    //   obj.f8 = 0x7FFFFFFFFFFFFFFF;
    //   obj.f9 = Float32(1.0 / 2);
    //   obj.f10 = 1 / 3.0;
    //   obj.f11 = Int16List.fromList([1, 2]);
    //   obj.f12 = [Int16(-1),Int16(4)];
    //   _testPerfDeserialize(fory, obj, 1000000, 'test serialize medium complex struct perf');
    // });
  });
}
