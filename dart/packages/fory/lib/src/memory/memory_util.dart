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

import 'dart:typed_data';

class MemoryUtil {
  static int getInt64LittleEndian(Uint8List buffer, int offset) {
    return buffer[offset] & 0xFF |
        (buffer[offset + 1] & 0xFF) << 8 |
        (buffer[offset + 2] & 0xFF) << 16 |
        (buffer[offset + 3] & 0xFF) << 24 |
        (buffer[offset + 4] & 0xFF) << 32 |
        (buffer[offset + 5] & 0xFF) << 40 |
        (buffer[offset + 6] & 0xFF) << 48 |
        (buffer[offset + 7] & 0xFF) << 56;
  }
}
