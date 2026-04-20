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

abstract class _fixedInt implements Comparable<_fixedInt> {
  final int value;

  const _fixedInt(this.value);

  @override
  int compareTo(_fixedInt other) => value.compareTo(other.value);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other.runtimeType == runtimeType &&
          other is _fixedInt &&
          other.value == value;

  @override
  int get hashCode => Object.hash(runtimeType, value);

  @override
  String toString() => value.toString();
}

/// Signed 8-bit integer wrapper used by the xlang type system.
///
/// Values are normalized to the `[-128, 127]` range at construction time.
final class Int8 extends _fixedInt {
  /// Creates a normalized signed 8-bit value.
  Int8(int value) : super(_normalize(value));

  static int _normalize(int value) => value.toSigned(8);
}

/// Signed 16-bit integer wrapper used by the xlang type system.
///
/// Values are normalized to the `[-32768, 32767]` range at construction time.
final class Int16 extends _fixedInt {
  /// Creates a normalized signed 16-bit value.
  Int16(int value) : super(_normalize(value));

  static int _normalize(int value) => value.toSigned(16);
}

/// Signed 32-bit integer wrapper used by the xlang type system.
///
/// Values are normalized to the signed 32-bit range at construction time.
final class Int32 extends _fixedInt {
  /// Creates a normalized signed 32-bit value.
  Int32(int value) : super(_normalize(value));

  static int _normalize(int value) => value.toSigned(32);
}

/// Unsigned 8-bit integer wrapper used by the xlang type system.
final class UInt8 extends _fixedInt {
  /// Creates a normalized unsigned 8-bit value.
  UInt8(int value) : super(value.toUnsigned(8));
}

/// Unsigned 16-bit integer wrapper used by the xlang type system.
final class UInt16 extends _fixedInt {
  /// Creates a normalized unsigned 16-bit value.
  UInt16(int value) : super(value.toUnsigned(16));
}

/// Unsigned 32-bit integer wrapper used by the xlang type system.
final class UInt32 extends _fixedInt {
  /// Creates a normalized unsigned 32-bit value.
  UInt32(int value) : super(value.toUnsigned(32));
}
