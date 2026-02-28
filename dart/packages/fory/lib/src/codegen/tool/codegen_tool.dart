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

class CodegenTool {
  static const String _2Spaces = '  ';
  static const String _4Spaces = _2Spaces + _2Spaces;
  static const String _6Spaces = _4Spaces + _2Spaces;
  static const String _8Spaces = _6Spaces + _2Spaces;
  static const String _10Spaces = _8Spaces + _2Spaces;
  static const String _12Spaces = _10Spaces + _2Spaces;
  static const String _14Spaces = _12Spaces + _2Spaces;
  static const String _16Spaces = _14Spaces + _2Spaces;
  static const String _18Spaces = _16Spaces + _2Spaces;

  static const List<String> _spaces = [
    _2Spaces,
    _4Spaces,
    _6Spaces,
    _8Spaces,
    _10Spaces,
    _12Spaces,
    _14Spaces,
    _16Spaces,
    _18Spaces,
  ];

  static const List<int> _spacesNum = [2, 4, 6, 8, 10, 12, 14, 16, 18];

  /// Instead of directly looping to write spaces, use binary search to minimize the number of writes
  static void writeIndent(StringBuffer buf, int indent) {
    int low = 0;
    int high = _spacesNum.length - 1;
    int result = -1;
    while (low <= high) {
      int mid = low + ((high - low) >> 1); // Avoid overflow
      if (_spacesNum[mid] < indent) {
        // Current element is less than the target, record candidate position, continue to the right for a closer match
        result = mid;
        low = mid + 1;
      } else if (_spacesNum[mid] > indent) {
        // Current element >= target, narrow the range to the left
        high = mid - 1;
      } else {
        // Current element == target, return directly
        buf.write(_spaces[mid]);
        return;
      }
    }
    if (result != -1) {
      buf.write(_spaces[result]);
    }
    indent -= (result == -1 ? 0 : _spacesNum[result]);
    for (int i = 0; i < indent; ++i) {
      buf.write(' ');
    }
  }
}
