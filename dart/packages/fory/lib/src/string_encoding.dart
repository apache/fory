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

import 'package:fory/src/buffer.dart';

const int stringLatin1Encoding = 0;
const int stringUtf16Encoding = 1;
const int stringUtf8Encoding = 2;
const Utf8Decoder _utf8Decoder = Utf8Decoder();
const int _utf8ReplacementLength = 3;

void writeString(Buffer buffer, String value) {
  if (_firstNonLatin1Index(value) < 0) {
    _writeLatin1(buffer, value);
  } else {
    _writeUtf8(buffer, value);
  }
}

String decodeString(Uint8List bytes, int encoding) {
  if (bytes.isEmpty) {
    return '';
  }
  switch (encoding) {
    case stringLatin1Encoding:
      return String.fromCharCodes(bytes);
    case stringUtf16Encoding:
      if (bytes.length.isOdd) {
        throw StateError(
          'Invalid UTF-16 string payload length ${bytes.length}.',
        );
      }
      final codeUnitCount = bytes.length ~/ 2;
      if (Endian.host == Endian.little && bytes.offsetInBytes.isEven) {
        return String.fromCharCodes(
          Uint16List.view(bytes.buffer, bytes.offsetInBytes, codeUnitCount),
        );
      }
      final codeUnits = Uint16List(codeUnitCount);
      final view = ByteData.sublistView(bytes);
      for (var index = 0; index < codeUnitCount; index += 1) {
        codeUnits[index] = view.getUint16(index * 2, Endian.little);
      }
      return String.fromCharCodes(codeUnits);
    case stringUtf8Encoding:
      return _utf8Decoder.convert(bytes);
    default:
      throw StateError('Unsupported string encoding $encoding.');
  }
}

String readStringFromBuffer(
  Buffer buffer,
  int byteLength,
  int encoding,
) {
  if (byteLength == 0) {
    return '';
  }
  final start = bufferReaderIndex(buffer);
  buffer.skip(byteLength);
  final bytes = bufferBytes(buffer);
  switch (encoding) {
    case stringLatin1Encoding:
      return String.fromCharCodes(bytes, start, start + byteLength);
    case stringUtf16Encoding:
      if (byteLength.isOdd) {
        throw StateError(
          'Invalid UTF-16 string payload length $byteLength.',
        );
      }
      final codeUnitCount = byteLength ~/ 2;
      if (Endian.host == Endian.little && start.isEven) {
        return String.fromCharCodes(
          Uint16List.view(bytes.buffer, start, codeUnitCount),
        );
      }
      final codeUnits = Uint16List(codeUnitCount);
      final view = bufferByteData(buffer);
      for (var index = 0; index < codeUnitCount; index += 1) {
        codeUnits[index] = view.getUint16(start + (index * 2), Endian.little);
      }
      return String.fromCharCodes(codeUnits);
    case stringUtf8Encoding:
      return _utf8Decoder.convert(bytes, start, start + byteLength);
    default:
      throw StateError('Unsupported string encoding $encoding.');
  }
}

void _writeLatin1(Buffer buffer, String value) {
  final length = value.length;
  final header = (length << 2) | stringLatin1Encoding;
  final headerLength = _varUint36SmallLength(header);
  buffer.ensureWritable(headerLength + length);
  final bytes = bufferBytes(buffer);
  final start = bufferWriterIndex(buffer);
  var offset = start + _writeVarUint36Small(bytes, start, header);
  for (var index = 0; index < length; index += 1) {
    bytes[offset] = value.codeUnitAt(index);
    offset += 1;
  }
  bufferSetWriterIndex(buffer, offset);
}

void _writeUtf8(Buffer buffer, String value) {
  final maxUtf8Length = value.length * 3;
  final provisionalHeader = (maxUtf8Length << 2) | stringUtf8Encoding;
  final provisionalHeaderLength = _varUint36SmallLength(provisionalHeader);
  buffer.ensureWritable(provisionalHeaderLength + maxUtf8Length);
  final bytes = bufferBytes(buffer);
  final start = bufferWriterIndex(buffer);
  final payloadStart = start + provisionalHeaderLength;
  final payloadEnd = _writeUtf8Bytes(bytes, payloadStart, value);
  final utf8Length = payloadEnd - payloadStart;
  final header = (utf8Length << 2) | stringUtf8Encoding;
  final headerLength = _writeVarUint36Small(bytes, start, header);
  final headerShift = provisionalHeaderLength - headerLength;
  if (headerShift > 0) {
    bytes.setRange(
      start + headerLength,
      start + headerLength + utf8Length,
      bytes,
      payloadStart,
    );
  }
  bufferSetWriterIndex(buffer, start + headerLength + utf8Length);
}

int _firstNonLatin1Index(String value) {
  final length = value.length;
  for (var index = 0; index < length; index += 1) {
    if (value.codeUnitAt(index) > 0xff) {
      return index;
    }
  }
  return -1;
}

int _writeUtf8Bytes(Uint8List target, int start, String value) {
  var offset = start;
  final codeUnitLength = value.length;
  for (var index = 0; index < codeUnitLength; index += 1) {
    final codeUnit = value.codeUnitAt(index);
    if (codeUnit <= 0x7f) {
      target[offset] = codeUnit;
      offset += 1;
      continue;
    }
    if (codeUnit <= 0x7ff) {
      target[offset] = 0xc0 | (codeUnit >> 6);
      target[offset + 1] = 0x80 | (codeUnit & 0x3f);
      offset += 2;
      continue;
    }
    if (_isLeadSurrogate(codeUnit)) {
      if (index + 1 < codeUnitLength) {
        final next = value.codeUnitAt(index + 1);
        if (_isTrailSurrogate(next)) {
          final scalar =
              0x10000 + (((codeUnit & 0x03ff) << 10) | (next & 0x03ff));
          target[offset] = 0xf0 | (scalar >> 18);
          target[offset + 1] = 0x80 | ((scalar >> 12) & 0x3f);
          target[offset + 2] = 0x80 | ((scalar >> 6) & 0x3f);
          target[offset + 3] = 0x80 | (scalar & 0x3f);
          offset += 4;
          index += 1;
          continue;
        }
      }
      offset = _writeUtf8Replacement(target, offset);
      continue;
    }
    if (_isTrailSurrogate(codeUnit)) {
      offset = _writeUtf8Replacement(target, offset);
      continue;
    }
    target[offset] = 0xe0 | (codeUnit >> 12);
    target[offset + 1] = 0x80 | ((codeUnit >> 6) & 0x3f);
    target[offset + 2] = 0x80 | (codeUnit & 0x3f);
    offset += 3;
  }
  return offset;
}

int _writeUtf8Replacement(Uint8List target, int offset) {
  target[offset] = 0xef;
  target[offset + 1] = 0xbf;
  target[offset + 2] = 0xbd;
  return offset + _utf8ReplacementLength;
}

bool _isLeadSurrogate(int codeUnit) => codeUnit >= 0xd800 && codeUnit <= 0xdbff;

bool _isTrailSurrogate(int codeUnit) =>
    codeUnit >= 0xdc00 && codeUnit <= 0xdfff;

int _writeVarUint36Small(Uint8List target, int offset, int value) {
  final start = offset;
  var remaining = value;
  while (remaining >= 0x80) {
    target[offset] = (remaining & 0x7f) | 0x80;
    offset += 1;
    remaining >>>= 7;
  }
  target[offset] = remaining;
  return offset - start + 1;
}

int _varUint36SmallLength(int value) {
  var length = 1;
  var remaining = value;
  while (remaining >= 0x80) {
    remaining >>>= 7;
    length += 1;
  }
  return length;
}
