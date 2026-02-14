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
import 'package:fory_test/entity/simple_struct1.dart';
import 'package:test/test.dart';

const int _listTypeId = 22;
const int _namedCompatibleStructTypeId = 30;

void main() {
  group('Schema evolution (TypeDef meta share)', () {
    test('round-trip with compatible mode and meta share', () {
      final fory = Fory(compatible: true, ref: true);
      fory.register($SimpleStruct1, typename: 'SimpleStruct1');

      final a = SimpleStruct1()..a = Int32(100);
      final b = SimpleStruct1()..a = Int32(200);

      final bytes = fory.serialize([a, b]);
      expect(bytes.isNotEmpty, isTrue);

      final decoded = fory.deserialize(bytes) as List<Object?>;
      expect(decoded.length, 2);
      expect(decoded[0], isA<SimpleStruct1>());
      expect(decoded[1], isA<SimpleStruct1>());
      expect((decoded[0] as SimpleStruct1).a.value, 100);
      expect((decoded[1] as SimpleStruct1).a.value, 200);
    });

    test('meta share cache: first occurrence has TypeDef, later use reference', () {
      final fory = Fory(compatible: true, ref: true);
      fory.register($SimpleStruct1, typename: 'SimpleStruct1');

      final list = [
        SimpleStruct1()..a = Int32(1),
        SimpleStruct1()..a = Int32(2),
        SimpleStruct1()..a = Int32(3),
      ];
      final bytes = fory.serialize(list);

      final markers = _collectSharedTypeMarkers(bytes);
      expect(markers.length, 1, reason: 'List writes element type once');
      expect(markers[0] & 1, 0, reason: 'Only occurrence should be new TypeDef (even marker)');

      final decoded = fory.deserialize(bytes) as List<Object?>;
      expect(decoded.length, 3);
      expect((decoded[0] as SimpleStruct1).a.value, 1);
      expect((decoded[1] as SimpleStruct1).a.value, 2);
      expect((decoded[2] as SimpleStruct1).a.value, 3);
    });

    test('TypeDef format: type ID and field list round-trip', () {
      final fory = Fory(compatible: true, ref: true);
      fory.register($SimpleStruct1, typename: 'test.SimpleStruct1');

      final original = SimpleStruct1()..a = Int32(42);
      final bytes = fory.serialize(original);
      final decoded = fory.deserialize(bytes) as SimpleStruct1;

      expect(decoded.a.value, 42);
    });

    test('named compatible struct (typename) round-trip', () {
      final fory = Fory(compatible: true, ref: true);
      fory.register($SimpleStruct1, typename: 'myns.SimpleStruct1');

      final original = SimpleStruct1()..a = Int32(123);
      final bytes = fory.serialize(original);
      final decoded = fory.deserialize(bytes) as SimpleStruct1;
      expect(decoded.a.value, 123);
    });

    test('compatible struct by user type id round-trip', () {
      final fory = Fory(compatible: true, ref: true);
      fory.register($SimpleStruct1, typeId: 1000);

      final original = SimpleStruct1()..a = Int32(456);
      final bytes = fory.serialize(original);
      final decoded = fory.deserialize(bytes) as SimpleStruct1;
      expect(decoded.a.value, 456);
    });

    test(
      'cross-language golden: decode bytes from Java/Go/Python with different field order',
      () {
        expect(true, isTrue, reason: 'Placeholder: add golden file and decode assertion');
      },
      skip: 'Cross-language golden bytes not yet added; use integration test with Java/Go/Python',
    );
  });
}

List<int> _collectSharedTypeMarkers(Uint8List bytes) {
  const int metaSizeMask = 0xFF;
  final markers = <int>[];
  final br = ByteReader.forBytes(bytes);
  br.skip(1); // global header
  final refFlag = br.readInt8();
  if (refFlag < 0) return markers;
  final typeId = br.readVarUint32Small7();
  if (typeId != _listTypeId) return markers;
  final length = br.readVarUint32Small7();
  if (length == 0) return markers;
  br.readUint8();
  final elemTypeId = br.readVarUint32Small7();
  if (elemTypeId == _namedCompatibleStructTypeId) {
    final marker = br.readVarUint32();
    markers.add(marker);
    if ((marker & 1) == 0) {
      final id = br.readInt64();
      int size = id & metaSizeMask;
      if (size == metaSizeMask) size += br.readVarUint32();
      br.skip(size);
    }
  }
  return markers;
}
