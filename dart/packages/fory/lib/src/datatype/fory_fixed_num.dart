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

import 'float16.dart';
import 'float32.dart';
import 'int16.dart';
import 'int32.dart';
import 'int8.dart';

enum NumType { int8, int16, int32, float16, float32 }

/// Base abstract class for fixed-size numeric types
abstract base class FixedNum implements Comparable<FixedNum> {
  const FixedNum();

  num get value;

  // Factory constructor to create the appropriate type
  static FixedNum from(num value, [NumType type = NumType.int32]) {
    switch (type) {
      case NumType.int8:
        return Int8(value);
      case NumType.int16:
        return Int16(value);
      case NumType.int32:
        return Int32(value);
      case NumType.float16:
        return Float16(value);
      case NumType.float32:
        return Float32(value);
    }
  }

  @override
  int compareTo(FixedNum other) => value.compareTo(other.value);
}
