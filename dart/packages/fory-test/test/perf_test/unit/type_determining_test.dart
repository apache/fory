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
import 'dart:typed_data';
import 'package:fory/fory.dart';
import 'package:fory/fory_test.dart';
import 'package:test/test.dart';

class TestForyTypeProvider implements ForyTypeProvider {
  @override
  Type get foryType => String;
}

void testDeterminingDartType() {
  // prepare test data
  final testObjects = [
    'string',
    123,
    3.14,
    true,
    <String, dynamic>{'key': 'value'},
    HashMap<String, dynamic>.from({'a': 1}),
    LinkedHashMap<String, dynamic>.from({'b': 2}),
    <dynamic>[1, 2, 3],
    Uint8List(10),
    Int8List(10),
    Float32List(10),
    HashSet<int>.from([1, 2, 3]),
    [4, 5, 6],
    TestForyTypeProvider(),
  ];

  final resolver = DartTypeResolver.I;
  final iterations = 1000000; // 1 million iterations
  final stopwatch = Stopwatch();

  print('run each test $iterations times...\n');

  // Test getForyType method
  stopwatch.start();
  for (int i = 0; i < iterations; i++) {
    resolver.getForyType(testObjects[i % testObjects.length]);
  }
  stopwatch.stop();
  print('DartTypeResolver.getForyType: ${stopwatch.elapsedMilliseconds} ms');

  // Reset the stopwatch
  stopwatch.reset();

  // Test runtimeType direct access
  stopwatch.start();
  for (int i = 0; i < iterations; i++) {
    testObjects[i % testObjects.length].runtimeType;
  }
  stopwatch.stop();
  print('directly obj.runtimeType: ${stopwatch.elapsedMilliseconds} ms');

  // Reset the stopwatch
  stopwatch.reset();

  // Test type check chain
  stopwatch.start();
  for (int i = 0; i < iterations; i++) {
    var obj = testObjects[i % testObjects.length];
    if (obj is Map) {
      if (obj is LinkedHashMap) {
      } else if (obj is HashMap) {}
    } else if (obj is List) {
      if (obj is Uint8List) {
      } else if (obj is Int8List) {}
    } else if (obj is Set) {
      if (obj is HashSet) {}
    }
  }
  stopwatch.stop();
  print('type chain: ${stopwatch.elapsedMilliseconds} ms');
}

void main() {
  test('test determining dartType', () {
    testDeterminingDartType();
  });
}
