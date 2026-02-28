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

import 'dart:typed_data';
import 'package:fory/fory.dart';
import 'package:fory/src/fory_exception.dart';
import 'package:test/test.dart';

void main() {
  group('maxCollectionSize guard check', () {
    test('list within limit deserializes successfully', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize([1, 2, 3]);

      final foryRead = Fory(maxCollectionSize: 10);
      final result = foryRead.deserialize(bytes);
      expect(result, equals([1, 2, 3]));
    });

    test('list exceeding limit throws InvalidDataException', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize([1, 2, 3, 4, 5]);

      final foryRead = Fory(maxCollectionSize: 3);
      expect(
        () => foryRead.deserialize(bytes),
        throwsA(isA<InvalidDataException>()),
      );
    });

    test('list at exact limit deserializes successfully', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize([1, 2, 3]);

      final foryRead = Fory(maxCollectionSize: 3);
      final result = foryRead.deserialize(bytes);
      expect(result, equals([1, 2, 3]));
    });

    test('empty list always deserializes successfully', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize(<int>[]);

      final foryRead = Fory(maxCollectionSize: 0);
      final result = foryRead.deserialize(bytes);
      expect(result, equals([]));
    });

    test('map exceeding limit throws InvalidDataException', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize({'a': 1, 'b': 2, 'c': 3});

      final foryRead = Fory(maxCollectionSize: 2);
      expect(
        () => foryRead.deserialize(bytes),
        throwsA(isA<InvalidDataException>()),
      );
    });

    test('map within limit deserializes successfully', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize({'a': 1, 'b': 2});

      final foryRead = Fory(maxCollectionSize: 10);
      final result = foryRead.deserialize(bytes);
      expect(result, equals({'a': 1, 'b': 2}));
    });

    test('set exceeding limit throws InvalidDataException', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize({1, 2, 3, 4, 5});

      final foryRead = Fory(maxCollectionSize: 2);
      expect(
        () => foryRead.deserialize(bytes),
        throwsA(isA<InvalidDataException>()),
      );
    });

    test('set within limit deserializes successfully', () {
      final foryWrite = Fory();
      final bytes = foryWrite.serialize({1, 2, 3});

      final foryRead = Fory(maxCollectionSize: 10);
      final result = foryRead.deserialize(bytes);
      expect(result, equals({1, 2, 3}));
    });

    test('default maxCollectionSize allows normal sizes', () {
      final foryWrite = Fory();
      final largeList = List.generate(1000, (i) => i);
      final bytes = foryWrite.serialize(largeList);

      final foryRead = Fory();
      final result = foryRead.deserialize(bytes) as List;
      expect(result.length, 1000);
    });
  });

  group('maxBinarySize guard check', () {
    test('binary within limit deserializes successfully', () {
      final foryWrite = Fory();
      final data = Uint8List.fromList([1, 2, 3, 4, 5]);
      final bytes = foryWrite.serialize(data);

      final foryRead = Fory(maxBinarySize: 10);
      final result = foryRead.deserialize(bytes) as Uint8List;
      expect(result, equals(data));
    });

    test('binary exceeding limit throws InvalidDataException', () {
      final foryWrite = Fory();
      final data = Uint8List.fromList([1, 2, 3, 4, 5]);
      final bytes = foryWrite.serialize(data);

      final foryRead = Fory(maxBinarySize: 3);
      expect(
        () => foryRead.deserialize(bytes),
        throwsA(isA<InvalidDataException>()),
      );
    });

    test('binary at exact limit deserializes successfully', () {
      final foryWrite = Fory();
      final data = Uint8List.fromList([1, 2, 3]);
      final bytes = foryWrite.serialize(data);

      final foryRead = Fory(maxBinarySize: 3);
      final result = foryRead.deserialize(bytes) as Uint8List;
      expect(result, equals(data));
    });

    test('empty binary always deserializes successfully', () {
      final foryWrite = Fory();
      final data = Uint8List(0);
      final bytes = foryWrite.serialize(data);

      final foryRead = Fory(maxBinarySize: 0);
      final result = foryRead.deserialize(bytes) as Uint8List;
      expect(result, equals(data));
    });

    test('default maxBinarySize allows normal sizes', () {
      final foryWrite = Fory();
      final data = Uint8List.fromList(List.generate(1000, (i) => i % 256));
      final bytes = foryWrite.serialize(data);

      final foryRead = Fory();
      final result = foryRead.deserialize(bytes) as Uint8List;
      expect(result.length, 1000);
    });
  });

  group('combined guard check', () {
    test('both limits enforced independently', () {
      final foryWrite = Fory();

      final listBytes = foryWrite.serialize([1, 2, 3, 4, 5]);
      final binaryBytes =
          foryWrite.serialize(Uint8List.fromList([1, 2, 3, 4, 5]));

      final foryRead = Fory(maxCollectionSize: 3, maxBinarySize: 10);

      // Collection exceeds limit
      expect(
        () => foryRead.deserialize(listBytes),
        throwsA(isA<InvalidDataException>()),
      );

      // Binary within limit
      final result = foryRead.deserialize(binaryBytes) as Uint8List;
      expect(result.length, 5);
    });

    test('default values are applied', () {
      final config = ForyConfig();
      expect(config.maxCollectionSize, ForyConfig.defaultMaxCollectionSize);
      expect(config.maxBinarySize, ForyConfig.defaultMaxBinarySize);
    });
  });
}
