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

import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:test/test.dart';

void main() {
  test('readMetaString rejects a negative dynamic reference index', () {
    final reader = MetaStringReader(TypeResolver(const Config()));
    final buffer = Buffer();

    buffer.writeVarUint32Small7(1);
    bufferSetReaderIndex(buffer, 0);

    expect(
      () => reader.readMetaString(buffer),
      throwsA(isA<StateError>()),
    );
  });

  test('readMetaString still resolves a valid dynamic reference', () {
    final reader = MetaStringReader(TypeResolver(const Config()));
    final buffer = Buffer();

    buffer.writeVarUint32Small7(2);
    buffer.writeByte(metaStringUtf8Encoding);
    buffer.writeBytes([0x61]);
    buffer.writeVarUint32Small7(3);
    bufferSetReaderIndex(buffer, 0);

    final value = reader.readMetaString(buffer);
    expect(value.bytes, orderedEquals(<int>[0x61]));
    expect(reader.readMetaString(buffer).bytes, orderedEquals(<int>[0x61]));
  });
}