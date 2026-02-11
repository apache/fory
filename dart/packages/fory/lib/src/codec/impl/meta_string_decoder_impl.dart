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
import 'package:fory/src/codec/meta_string_decoder.dart';
import 'package:fory/src/codec/meta_string_encoding.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/meta/meta_string_byte.dart';

class ForyMetaStringDecoder extends MetaStringDecoder {
  const ForyMetaStringDecoder(super.specialChar1, super.specialChar2);

  // Decoding special char for LOWER_SPECIAL based on encoding mapping.
  @inline
  int _decodeLowerSpecialChar(int charValue) {
    if (charValue >= 0 && charValue <= 25) {
      // 'a' to 'z'
      return 97 + charValue;
    } else if (charValue == 26) {
      // '.'
      return 46;
    } else if (charValue == 27) {
      // '_'
      return 95;
    } else if (charValue == 28) {
      // '$'
      return 36;
    } else if (charValue == 29) {
      // '|'
      return 124;
    } else {
      throw ArgumentError(
          'Unsupported character for LOWER_SPECIAL encoding: $charValue');
    }
  }

  // Decoding special char for LOWER_UPPER_DIGIT_SPECIAL based on encoding mapping.
  @inline
  int _decodeLowerUpperDigitSpecialChar(int charValue) {
    if (charValue >= 0 && charValue <= 25) {
      // 'a' to 'z'
      return 97 + charValue;
    } else if (charValue >= 26 && charValue <= 51) {
      // 'A' to 'Z'
      return 65 + (charValue - 26);
    } else if (charValue >= 52 && charValue <= 61) {
      // '0' to '9'
      return 48 + (charValue - 52);
    } else if (charValue == 62) {
      return specialChar1;
    } else if (charValue == 63) {
      return specialChar2;
    } else {
      throw ArgumentError(
          'Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: $charValue');
    }
  }

  String _decodeLowerSpecial(Uint8List data) {
    assert(data.isNotEmpty);
    var decoded = StringBuffer();
    int totalBits = data.length * 8;
    bool stripLastChar =
        (data[0] & 0x80) != 0; // the first bit of the first byte
    int bitMask = 0x1f; // 1 1111
    int bitIndex = 1;
    while (bitIndex + 5 <= totalBits &&
        !(stripLastChar && (bitIndex + 2 * 5 > totalBits))) {
      int byteIndex = bitIndex ~/ 8;
      int bitOffset = bitIndex % 8;
      int charValue = 0; // codeUnit
      // 01234567
      if (bitOffset > 3) {
        // need to read from two bytes
        charValue = ((data[byteIndex] & 0xFF) << 8) |
            (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = ((charValue >> (11 - bitOffset)) & bitMask);
      } else {
        // read from one byte
        charValue = (data[byteIndex] >> (3 - bitOffset)) & bitMask;
      }
      bitIndex += 5;
      decoded.writeCharCode(_decodeLowerSpecialChar(charValue));
    }
    return decoded.toString();
  }

  String _decodeLowerUpperDigitSpecial(Uint8List data) {
    StringBuffer buf = StringBuffer();
    int bitIndex = 1;
    bool stripLastChar =
        (data[0] & 0x80) != 0; // the first bit of the first byte
    int bitMask = 0x3f; // 0011 1111
    int numBits = data.length * 8;
    while (bitIndex + 6 <= numBits &&
        !(stripLastChar && (bitIndex + 2 * 6 > numBits))) {
      int byteIndex = bitIndex ~/ 8;
      int intraByteIndex = bitIndex % 8;
      int charValue = 0; // codeUnit
      // 01234567
      if (intraByteIndex > 2) {
        // need to read from two bytes
        charValue = ((data[byteIndex] & 0xFF) << 8) |
            (byteIndex + 1 < data.length ? (data[byteIndex + 1] & 0xFF) : 0);
        charValue = (charValue >> (10 - intraByteIndex)) & bitMask;
      } else {
        // read from one byte
        charValue = data[byteIndex] >> (2 - intraByteIndex) & bitMask;
      }
      bitIndex += 6;
      buf.writeCharCode(_decodeLowerUpperDigitSpecialChar(charValue));
    }
    return buf.toString();
  }

  String _decodeRepFirstLowerSpecial(Uint8List data) {
    String decoded = _decodeLowerSpecial(data);
    Uint16List chars = Uint16List.fromList(decoded.codeUnits);
    chars[0] -= 32; // 'a' to 'A'
    return String.fromCharCodes(chars);
  }

  String _decodeRepAllToLowerSpecial(Uint8List data) {
    String decoded = _decodeLowerSpecial(data);
    StringBuffer buf = StringBuffer();
    List<int> chars = decoded.codeUnits;
    int c;
    for (int i = 0; i < chars.length; ++i) {
      if (chars[i] == 0x7c) {
        c = chars[++i];
        buf.writeCharCode(c - 32); // 'A' to 'a'
      } else {
        buf.writeCharCode(chars[i]);
      }
    }
    return buf.toString();
  }

  @override
  String decode(Uint8List data, MetaStringEncoding encoding) {
    if (data.isEmpty) return '';
    switch (encoding) {
      case MetaStringEncoding.ls:
        return _decodeLowerSpecial(data);
      case MetaStringEncoding.luds:
        return _decodeLowerUpperDigitSpecial(data);
      case MetaStringEncoding.ftls:
        return _decodeRepFirstLowerSpecial(data);
      case MetaStringEncoding.atls:
        return _decodeRepAllToLowerSpecial(data);
      case MetaStringEncoding.utf8:
        return utf8.decode(data);
      // default:
      //   throw ArgumentError('Unsupported encoding: $encoding');
    }
  }

  @override
  @inline
  String decodeMetaString(MetaStringBytes data) {
    return decode(data.bytes, data.encoding);
  }
}
