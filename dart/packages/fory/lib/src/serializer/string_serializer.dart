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

import 'dart:convert';
import 'dart:typed_data';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/exception/deserialization_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/util/string_util.dart';

enum _StrCode {
  latin1(0),
  utf16(1),
  utf8(2);

  final int id;
  const _StrCode(this.id);
}

final class _StringSerializerCache extends SerializerCache {
  static StringSerializer? serializerWithRef;
  static StringSerializer? serializerWithoutRef;

  const _StringSerializerCache();

  @override
  Serializer getSerializer(
    ForyConfig conf,
  ) {
    // Currently, there are only two types of serializer for primitive types:
    // one that writes a reference and one that does not, so only these two are cached here.
    bool writeRef = conf.ref && !conf.stringRefIgnored;
    return getSerializerWithRef(writeRef);
  }

  Serializer getSerializerWithRef(bool writeRef) {
    if (writeRef) {
      serializerWithRef ??= StringSerializer(true);
      return serializerWithRef!;
    } else {
      serializerWithoutRef ??= StringSerializer(false);
      return serializerWithoutRef!;
    }
  }
}

final class StringSerializer extends Serializer<String> {
  static const SerializerCache cache = _StringSerializerCache();

  StringSerializer(bool writeRef) : super(ObjType.STRING, writeRef);

  @override
  String read(ByteReader br, int refId, DeserializationContext pack) {
    int header = br.readVarUint36Small();
    int coder = header & 3;
    int byteNum = header >>> 2;
    if (coder == _StrCode.latin1.id) {
      return _readLatin1(br, byteNum);
    } else if (coder == _StrCode.utf16.id) {
      return _readUtf16(br, byteNum);
    } else if (coder == _StrCode.utf8.id) {
      return _readUtf8(br, byteNum);
    }
    throw UnsupportedFeatureException(coder, _StrCode.values, 'String Coder');
  }

  @override
  void write(ByteWriter bw, String v, SerializationContext pack) {
    if (StringUtil.hasNonLatin(v)) {
      _writeUtf16(bw, v);
      return;
    }
    _writeLatin1(bw, v);
  }

  String _readLatin1(ByteReader br, int byteNum) {
    Uint8List bytesView = br.readBytesView(byteNum);
    return String.fromCharCodes(bytesView);
  }

  String _readUtf16(ByteReader br, int byteNum) {
    Uint16List bytes = br.readCopyUint16List(byteNum);
    return String.fromCharCodes(bytes);
  }

  String _readUtf8(ByteReader br, int byteNum) {
    Uint8List bytes = br.readBytesView(byteNum);
    return utf8.decode(bytes);
  }

  void _writeLatin1(ByteWriter bw, String v) {
    bw.writeVarUint36Small((v.length << 2) | _StrCode.latin1.id);
    bw.writeBytes(v.codeUnits);
  }

  void _writeUtf16(ByteWriter bw, String v) {
    // here (v.length * 2)  << 2 == (v.length  << 3);
    bw.writeVarUint36Small((v.length << 3) | _StrCode.utf16.id);
    bw.writeBytes(
      Uint16List.fromList(v.codeUnits).buffer.asUint8List(),
    );
  }
}
