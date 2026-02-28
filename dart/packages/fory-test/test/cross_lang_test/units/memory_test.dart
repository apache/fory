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

import 'dart:io';
import 'dart:typed_data';
import 'package:checks/checks.dart';
import 'package:fory_test/util/cross_lang_util.dart';
import 'package:fory_test/util/test_file_util.dart';
import 'package:test/test.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/extensions/array_ext.dart';

void main() {
  group('Cross-language buffer serialization', () {
    test('serialize/deserialize buffer matches Python implementation', () {
      ByteWriter bw = ByteWriter();
      bw.writeBool(true);
      bw.writeInt8(Int8.maxValue.value);
      bw.writeInt16(Int16.maxValue.value);
      bw.writeInt32(Int32.maxValue.value);
      bw.writeInt64(0x7FFFFFFFFFFFFFFF);
      bw.writeFloat32(Float32(-1.1).value);
      bw.writeFloat64(-1.1);
      bw.writeVarUint32(100);
      Uint8List bytes2 = Uint8List.fromList([97, 98]);
      bw.writeInt32(bytes2.lengthInBytes);
      bw.writeBytes(bytes2);

      File file = TestFileUtil.getWriteFile('test_buffer.data', bw.toBytes());
      bool exeRes = CrossLangUtil.executeWithPython('test_buffer', file.path);
      check(exeRes).isTrue();

      Uint8List readBytes = file.readAsBytesSync();
      ByteReader br = ByteReader.forBytes(readBytes);
      check(br.readBool()).isTrue();
      check(br.readInt8()).equals(Int8.maxValue.value);
      check(br.readInt16()).equals(Int16.maxValue.value);
      check(br.readInt32()).equals(Int32.maxValue.value);
      check(br.readInt64()).equals(0x7FFFFFFFFFFFFFFF);
      check(br.readFloat32()).equals(Float32(-1.1).value);
      check(br.readFloat64()).equals(-1.1);
      check(br.readVarUint32()).equals(100);
      Uint8List byteLis = br.copyBytes(br.readInt32());
      check(byteLis.memEquals(bytes2)).isTrue();
    }, skip: 'Cross-language test requires Python installation');
  });
}
