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

import 'package:fory/src/dev_annotation/optimize.dart';

final class CharUtil {
  @inline
  static bool isLUD(int c) {
    // Lower or Upper or Digit
    return (c >= 0x30 && c <= 0x39) || // 0-9
        (c >= 0x41 && c <= 0x5A) || // A-Z
        (c >= 0x61 && c <= 0x7A); // a-z
  }

  @inline
  static bool isLS(int c) {
    // Lower or Special
    return (c >= 0x61 && c <= 0x7A) || // a-z
        (c == 0x24) || // $
        (c == 0x5F) || // _
        (c == 0x2E) || // .
        (c == 0x7C); // |
  }

  @inline
  static bool digit(int c) {
    // Digit
    return (c >= 0x30 && c <= 0x39); // 0-9
  }

  @inline
  static bool upper(int c) {
    // Upper
    return (c >= 0x41 && c <= 0x5A); // A-Z
  }

  @inline
  static int toLowerChar(int charCode) {
    return charCode + 32;
  }
}
