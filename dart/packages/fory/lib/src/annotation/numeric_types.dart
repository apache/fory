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

/// Encodings available for 64-bit signed and unsigned integer fields.
enum LongEncoding {
  /// Always use the fixed-width 64-bit wire form.
  fixed,

  /// Use variable-length varint encoding.
  varint,

  /// Use the tagged compact-or-wide 64-bit encoding.
  tagged,
}

/// Overrides the generated wire encoding for a 32-bit signed integer field.
final class Int32Type {
  /// Whether to use the compressed varint representation instead of fixed-width
  /// `int32`.
  final bool compress;

  /// Creates an `int32` encoding override.
  const Int32Type({this.compress = true});
}

/// Overrides the generated wire encoding for a 64-bit signed integer field.
final class Int64Type {
  /// Selected encoding for the field.
  final LongEncoding encoding;

  /// Creates an `int64` encoding override.
  const Int64Type({this.encoding = LongEncoding.varint});
}

/// Marks a field as xlang `uint8`.
final class Uint8Type {
  /// Creates a `uint8` encoding override.
  const Uint8Type();
}

/// Marks a field as xlang `uint16`.
final class Uint16Type {
  /// Creates a `uint16` encoding override.
  const Uint16Type();
}

/// Overrides the generated wire encoding for a 32-bit unsigned integer field.
final class Uint32Type {
  /// Whether to use the compressed varuint representation instead of fixed-width
  /// `uint32`.
  final bool compress;

  /// Creates a `uint32` encoding override.
  const Uint32Type({this.compress = true});
}

/// Overrides the generated wire encoding for a 64-bit unsigned integer field.
final class Uint64Type {
  /// Selected encoding for the field.
  final LongEncoding encoding;

  /// Creates a `uint64` encoding override.
  const Uint64Type({this.encoding = LongEncoding.varint});
}
