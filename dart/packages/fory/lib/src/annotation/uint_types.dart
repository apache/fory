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

library;

/// Annotation to mark a field as unsigned 8-bit integer (0-255).
///
/// Use this annotation on `int` fields to serialize them as UINT8 type.
///
/// Example:
/// ```dart
/// class MyStruct {
///   @Uint8Type()
///   int age;  // Serialized as UINT8
/// }
/// ```
class Uint8Type {
  const Uint8Type();
}

/// Annotation to mark a field as unsigned 16-bit integer (0-65535).
///
/// Use this annotation on `int` fields to serialize them as UINT16 type.
///
/// Example:
/// ```dart
/// class MyStruct {
///   @Uint16Type()
///   int port;  // Serialized as UINT16
/// }
/// ```
class Uint16Type {
  const Uint16Type();
}

/// Encoding options for 32-bit and 64-bit unsigned integers.
enum UintEncoding {
  /// Fixed-length encoding (4 bytes for uint32, 8 bytes for uint64)
  fixed,

  /// Variable-length encoding (VAR_UINT32 or VAR_UINT64)
  varint,

  /// Tagged variable-length encoding (only for uint64)
  tagged,
}

/// Annotation to mark a field as unsigned 32-bit integer (0-4294967295).
///
/// Use this annotation on `int` fields to serialize them as UINT32 or VAR_UINT32 type.
///
/// Example:
/// ```dart
/// class MyStruct {
///   @Uint32Type()
///   int count;  // Serialized as UINT32 (fixed)
///
///   @Uint32Type(encoding: UintEncoding.varint)
///   int varCount;  // Serialized as VAR_UINT32
/// }
/// ```
class Uint32Type {
  final UintEncoding encoding;

  const Uint32Type({this.encoding = UintEncoding.fixed});
}

/// Annotation to mark a field as unsigned 64-bit integer.
///
/// Use this annotation on `int` fields to serialize them as UINT64, VAR_UINT64, or TAGGED_UINT64 type.
///
/// Example:
/// ```dart
/// class MyStruct {
///   @Uint64Type()
///   int id;  // Serialized as UINT64 (fixed)
///
///   @Uint64Type(encoding: UintEncoding.varint)
///   int varId;  // Serialized as VAR_UINT64
///
///   @Uint64Type(encoding: UintEncoding.tagged)
///   int taggedId;  // Serialized as TAGGED_UINT64
/// }
/// ```
class Uint64Type {
  final UintEncoding encoding;

  const Uint64Type({this.encoding = UintEncoding.fixed});
}
