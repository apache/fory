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
import 'package:fory_test/entity/uint_annotated_struct.dart';
import 'package:test/test.dart';

void main() {
  group('UInt annotated struct serialization', () {
    test('serializes and deserializes annotated uint fields correctly', () {
      // Create test instance with native int types
      var original = UIntAnnotatedStruct(
        age: 25,
        port: 8080,
        count: 1000000,
        varCount: 500000,
        id: 9223372036854775807,
        varId: 4611686018427387903,
        taggedId: 1152921504606846975,
      );

      // Serialize
      var fory = Fory();
      fory.register(UIntAnnotatedStruct);
      var bytes = fory.serialize(original);

      expect(bytes.isNotEmpty, isTrue);

      // Deserialize
      var decoded = fory.deserialize(bytes) as UIntAnnotatedStruct;

      // Verify values
      expect(decoded.age, 25);
      expect(decoded.port, 8080);
      expect(decoded.count, 1000000);
      expect(decoded.varCount, 500000);
      expect(decoded.id, 9223372036854775807);
      expect(decoded.varId, 4611686018427387903);
      expect(decoded.taggedId, 1152921504606846975);
    });

    test('handles uint8 max value correctly', () {
      var original = UIntAnnotatedStruct(
        age: 255, // Max UInt8
        port: 100,
        count: 100,
        varCount: 100,
        id: 100,
        varId: 100,
        taggedId: 100,
      );

      var fory = Fory();
      fory.register(UIntAnnotatedStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntAnnotatedStruct;

      expect(decoded.age, 255);
    });

    test('handles uint16 max value correctly', () {
      var original = UIntAnnotatedStruct(
        age: 100,
        port: 65535, // Max UInt16
        count: 100,
        varCount: 100,
        id: 100,
        varId: 100,
        taggedId: 100,
      );

      var fory = Fory();
      fory.register(UIntAnnotatedStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntAnnotatedStruct;

      expect(decoded.port, 65535);
    });

    test('handles uint32 max value correctly', () {
      var original = UIntAnnotatedStruct(
        age: 100,
        port: 100,
        count: 4294967295, // Max UInt32
        varCount: 4294967295, // Max UInt32
        id: 100,
        varId: 100,
        taggedId: 100,
      );

      var fory = Fory();
      fory.register(UIntAnnotatedStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntAnnotatedStruct;

      expect(decoded.count, 4294967295);
      expect(decoded.varCount, 4294967295);
    });

    test('handles min values correctly', () {
      var original = UIntAnnotatedStruct(
        age: 0,
        port: 0,
        count: 0,
        varCount: 0,
        id: 0,
        varId: 0,
        taggedId: 0,
      );

      var fory = Fory();
      fory.register(UIntAnnotatedStruct);
      var bytes = fory.serialize(original);
      var decoded = fory.deserialize(bytes) as UIntAnnotatedStruct;

      expect(decoded.age, 0);
      expect(decoded.port, 0);
      expect(decoded.count, 0);
      expect(decoded.varCount, 0);
      expect(decoded.id, 0);
      expect(decoded.varId, 0);
      expect(decoded.taggedId, 0);
    });

    test('varint encoding uses less space for small values', () {
      var smallValues = UIntAnnotatedStruct(
        age: 1,
        port: 1,
        count: 1,
        varCount: 1,
        id: 1,
        varId: 1,
        taggedId: 1,
      );

      var largeValues = UIntAnnotatedStruct(
        age: 1,
        port: 1,
        count: 4294967295,
        varCount: 4294967295,
        id: 1,
        varId: 1,
        taggedId: 1,
      );

      var fory = Fory();
      fory.register(UIntAnnotatedStruct);

      var smallBytes = fory.serialize(smallValues);
      var largeBytes = fory.serialize(largeValues);

      // Varint should use less space for small values
      // Fixed-length uint32 always uses 4 bytes regardless of value
      expect(smallBytes.length, lessThan(largeBytes.length));
    });
  });
}
