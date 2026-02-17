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

library;

import 'package:fory/fory.dart';
import 'package:fory_test/entity/uint_struct.dart';
import 'package:test/test.dart';

void main() {
  group('UInt struct serialization', () {
    test('serializes and deserializes UInt fields correctly', () {
      // Create test instance
      var original = UIntStruct(
        UInt8(25),
        UInt16(8080),
        UInt32(1000000),
      );

      // Serialize
      var fory = Fory();
      fory.register(UIntStruct);
      var bytes = fory.serialize(original);

      expect(bytes.isNotEmpty, isTrue);

      // Deserialize
      var decoded = fory.deserialize(bytes) as UIntStruct;

      // Verify values
      expect(decoded.age.value, 25);
      expect(decoded.port.value, 8080);
      expect(decoded.count.value, 1000000);
    });

    test('handles max values correctly', () {
      var original = UIntStruct(
        UInt8(255), // Max UInt8
        UInt16(65535), // Max UInt16
        UInt32(4294967295), // Max UInt32
      );

      var fory = Fory();
      fory.register(UIntStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntStruct;

      expect(decoded.age.value, 255);
      expect(decoded.port.value, 65535);
      expect(decoded.count.value, 4294967295);
    });

    test('handles min values correctly', () {
      var original = UIntStruct(
        UInt8(0),
        UInt16(0),
        UInt32(0),
      );

      var fory = Fory();
      fory.register(UIntStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntStruct;

      expect(decoded.age.value, 0);
      expect(decoded.port.value, 0);
      expect(decoded.count.value, 0);
    });
  });
}
