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

import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/memory/buffer.dart';
import 'package:test/test.dart';

void main() {
  test('readRefOrNull rejects out-of-range ref ids', () {
    final reader = RefReader();
    final buffer = Buffer();

    buffer.writeByte(RefWriter.refFlag);
    buffer.writeVarUint32(9999);
    bufferSetReaderIndex(buffer, 0);

    expect(
      () => reader.readRefOrNull(buffer),
      throwsA(isA<StateError>()),
    );
  });

  test('getReadRef rejects out-of-range ref ids', () {
    final reader = RefReader();

    expect(
      () => reader.getReadRef(9999),
      throwsA(isA<StateError>()),
    );
  });
}