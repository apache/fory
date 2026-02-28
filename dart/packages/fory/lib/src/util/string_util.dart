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
import 'package:fory/src/dev_annotation/optimize.dart';

class StringUtil {
  static final RegExp _nonLatinRegex = RegExp(r'[^\x00-\xFF]');

  @inline
  static bool hasNonLatin(String input) {
    return _nonLatinRegex.hasMatch(input);
  }

  static int upperCount(String input) {
    int count = 0;
    for (final codeUnit in input.codeUnits) {
      if (codeUnit >= 0x41 && codeUnit <= 0x5A) {
        ++count;
      }
    }
    return count;
  }

  static String addingTypeNameAndNs(String ns, String tn) {
    if (ns.isEmpty) return tn;
    StringBuffer sb = StringBuffer();
    sb.write(ns);
    sb.write('.');
    sb.write(tn);
    return sb.toString();
  }

  // The guarantee of executing this function is that str is a lowerCamelCase style string, so do not consider non-compliant cases of str
  static String lowerCamelToLowerUnderscore(String str) {
    // return str.replaceAllMapped(
    //   RegExp(r'([a-z])([A-Z])'),
    //   (Match m) => '${m[1]}_${m[2]!.toLowerCase()}',
    // ).toLowerCase();
    StringBuffer buf = StringBuffer();
    int len = str.length;
    int fromIndex = 0;
    for (int i = 0; i < len; ++i) {
      int codeUnit = str.codeUnitAt(i);
      if (codeUnit >= 65 && codeUnit <= 90) {
        // Uppercase letter
        buf.write(str.substring(fromIndex, i));
        buf.write("_");
        buf.write(str[i].toLowerCase());
        fromIndex = i + 1;
      }
    }
    if (fromIndex < len) {
      buf.write(str.substring(fromIndex, len));
    }
    return buf.toString();
  }

  static int computeUtf8StringHash(String str) {
    int hash = 17;
    Uint8List utf8Bytes = utf8.encode(str);
    for (int byte in utf8Bytes) {
      hash = hash * 31 + byte;
      while (hash > 0x7FFFFFFF) {
        hash ~/= 7;
      }
    }
    return hash;
  }

  /// The vectorized method is actually not faster than regex, so don't use it for now
  @Deprecated('perf reason')
  bool isLatin5(String input) {
    final codeUnits = input.codeUnits;
    if (codeUnits.isEmpty) return true;
    // Get the current platform's byte order
    Endian endian = Endian.little;

    // Adjust the mask according to the byte order
    const highByteMaskLittle = 0xFF00FF00FF00FF00; // Little-endian mask
    const highByteMaskBig = 0x00FF00FF00FF00FF; // Big-endian mask
    final highByteMask =
        endian == Endian.little ? highByteMaskLittle : highByteMaskBig;

    // Convert codeUnits to byte data (directly shared memory)
    final buffer = Uint16List.fromList(codeUnits).buffer;
    final byteData = ByteData.view(buffer);
    final totalBytes = codeUnits.length * 2;

    // Process 8 bytes (4 characters) in batches
    final batchSize = 8;
    final batchCount = totalBytes ~/ batchSize;
    final uint64List = byteData.buffer.asUint64List();

    for (int i = 0; i < batchCount; i++) {
      final value = uint64List[i];
      if (value & highByteMask != 0) return false;
    }

    // Process remaining bytes (read in correct byte order)
    final remainderOffset = batchCount * batchSize;
    for (int offset = remainderOffset; offset < totalBytes; offset += 2) {
      final codeUnit = byteData.getUint16(offset, endian);
      if (codeUnit > 0xFF) return false;
    }
    return true;
  }
}
