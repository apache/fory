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

/// Single-precision floating-point wrapper used by the xlang type system.
final class Float32 implements Comparable<Float32> {
  /// The normalized 32-bit floating-point value as a Dart [double].
  final double value;

  /// Creates a value rounded to IEEE 754 binary32 precision.
  Float32(num value) : value = (Float32List(1)..[0] = value.toDouble())[0];

  @override
  int compareTo(Float32 other) => value.compareTo(other.value);

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Float32 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
