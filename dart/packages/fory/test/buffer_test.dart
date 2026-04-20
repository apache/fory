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

import 'package:fory/fory.dart';
import 'package:fory/src/codegen/generated_support.dart';
import 'package:test/test.dart';

void main() {
  group('Buffer', () {
    test('round-trips primitive values', () {
      final buffer = Buffer();
      buffer.writeBool(true);
      buffer.writeInt16(-7);
      buffer.writeInt32(42);
      buffer.writeInt64(123456789);
      buffer.writeFloat32(1.5);
      buffer.writeFloat64(2.5);
      buffer.writeVarInt32(-9);
      buffer.writeVarUint32(300);
      buffer.writeVarInt64(-17);
      buffer.writeVarUint64(9000);

      expect(buffer.readBool(), isTrue);
      expect(buffer.readInt16(), equals(-7));
      expect(buffer.readInt32(), equals(42));
      expect(buffer.readInt64(), equals(123456789));
      expect(buffer.readFloat32(), closeTo(1.5, 0.0001));
      expect(buffer.readFloat64(), equals(2.5));
      expect(buffer.readVarInt32(), equals(-9));
      expect(buffer.readVarUint32(), equals(300));
      expect(buffer.readVarInt64(), equals(-17));
      expect(buffer.readVarUint64(), equals(9000));
    });

    test('tagged uint64 sign extension regression', () {
      final buffer = Buffer();
      final testValue = 0x7FFFFFFF;

      buffer.writeTaggedUint64(testValue);
      buffer.wrap(buffer.toBytes());

      final result = buffer.readTaggedUint64();

      expect(result, equals(testValue));
    });

    test('GeneratedReadCursor tagged uint64 sign extension regression', () {
      final buffer = Buffer();
      final testValue = 0x7FFFFFFF;

      buffer.writeTaggedUint64(testValue);
      buffer.wrap(buffer.toBytes());

      final cursor = GeneratedReadCursor.start(buffer);
      final result = cursor.readTaggedUint64();

      expect(result, equals(testValue));
    });
  });
}
