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

/// Exact decimal value represented as `unscaledValue * 10^-scale`.
final class Decimal {
  /// The exact integer significand.
  final BigInt unscaledValue;

  /// The decimal scale.
  final int scale;

  /// Creates a decimal from its unscaled integer value and decimal [scale].
  const Decimal(this.unscaledValue, this.scale);

  /// Creates a zero decimal with the given [scale].
  factory Decimal.zero([int scale = 0]) {
    return Decimal(BigInt.zero, scale);
  }

  /// Creates a decimal from a small integer value and optional [scale].
  factory Decimal.fromInt(int value, {int scale = 0}) {
    return Decimal(BigInt.from(value), scale);
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Decimal &&
          other.scale == scale &&
          other.unscaledValue == unscaledValue;

  @override
  int get hashCode => Object.hash(unscaledValue, scale);

  @override
  String toString() => '${unscaledValue}e${-scale}';
}
