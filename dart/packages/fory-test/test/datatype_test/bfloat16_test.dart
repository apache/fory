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

import 'package:test/test.dart';
import 'package:fory/fory.dart';

void main() {
  group('BFloat16', () {
    test('converts from and to bits', () {
      var bf = BFloat16.fromBits(0x3f80); // 1.0 in bfloat16
      expect(bf.toBits(), 0x3f80);
      expect(bf.toFloat32(), 1.0);
      expect(bf.value, 1.0);
    });

    test('converts from float32', () {
      var bf = BFloat16.fromFloat32(1.0);
      expect(bf.toBits(), 0x3f80);
      expect(bf.toFloat32(), 1.0);
      
      var bf2 = BFloat16.fromFloat32(-1.0);
      expect(bf2.toFloat32(), -1.0);
      
      var bf3 = BFloat16.fromFloat32(0.0);
      expect(bf3.toFloat32(), 0.0);
    });
    
    test('equality and hashcode', () {
      var bf1 = BFloat16.fromBits(0x3f80);
      var bf2 = BFloat16.fromFloat32(1.0);
      var bf3 = BFloat16.fromFloat32(2.0);
      
      expect(bf1 == bf2, isTrue);
      expect(bf1 == bf3, isFalse);
      expect(bf1.hashCode == bf2.hashCode, isTrue);
    });
  });

  group('BFloat16Array', () {
    test('creates from length', () {
      var arr = BFloat16Array.fromLength(5);
      expect(arr.length, 5);
      expect(arr.raw.length, 5);
    });

    test('creates from list and sets values', () {
      var arr = BFloat16Array.fromList([1.0, 2.0, BFloat16.fromFloat32(3.0)]);
      expect(arr.length, 3);
      expect(arr.get(0).toFloat32(), 1.0);
      expect(arr.get(1).toFloat32(), 2.0);
      expect(arr.get(2).toFloat32(), 3.0);
      
      arr.set(0, 4.0);
      expect(arr.get(0).toFloat32(), 4.0);
      
      arr.set(1, BFloat16.fromFloat32(5.0));
      expect(arr.get(1).toFloat32(), 5.0);
    });
  });
}
