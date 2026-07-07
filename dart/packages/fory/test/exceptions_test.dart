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
import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:test/test.dart';

void main() {
  group('Fory exceptions', () {
    test('invalid data exception is catchable as ForyException', () {
      const exception = InvalidDataException('invalid scalar conversion');

      expect(exception, isA<ForyException>());
      expect(exception.message, equals('invalid scalar conversion'));
      expect(exception.cause, isNull);
      expect(
        exception.toString(),
        equals('InvalidDataException: invalid scalar conversion'),
      );
    });

    test('includes cause in string output', () {
      const cause = FormatException('bad payload');
      const exception = InvalidDataException('invalid payload', cause);

      expect(exception.cause, same(cause));
      expect(
        exception.toString(),
        equals(
          'InvalidDataException: invalid payload '
          '(caused by FormatException: bad payload)',
        ),
      );
    });

    test('invalid read ref id is rejected', () {
      final refReader = RefReader();
      final buffer = Buffer();
      buffer.writeByte(RefWriter.refFlag);
      buffer.writeVarUint32(0);

      expect(
        () => refReader.tryPreserveRefId(Buffer.wrap(buffer.toBytes())),
        throwsA(
          isA<InvalidDataException>().having(
            (error) => error.message,
            'message',
            'Invalid ref id 0, current size 0.',
          ),
        ),
      );
    });

    test('unresolved read ref slot is rejected', () {
      final refReader = RefReader()..preserveRefId();
      final buffer = Buffer();
      buffer.writeByte(RefWriter.refFlag);
      buffer.writeVarUint32(0);

      expect(
        () => refReader.tryPreserveRefId(Buffer.wrap(buffer.toBytes())),
        throwsA(
          isA<InvalidDataException>().having(
            (error) => error.message,
            'message',
            'Invalid ref id 0, current size 1.',
          ),
        ),
      );
    });
  });
}
