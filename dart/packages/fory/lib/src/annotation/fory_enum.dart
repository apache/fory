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

import 'package:meta/meta_meta.dart';
import 'fory_object.dart';

/// A class representing an enumeration type in the Fory framework.
///
/// This class extends [ForyObject] and is used to annotate enum types
/// within the Fory framework.
/// Example:
/// ```
/// @foryEnum
/// enum Color {
///   // enums
/// }
/// ```
@Target({TargetKind.enumType})
class ForyEnum extends ForyObject {
  static const String name = 'ForyEnum';
  static const List<TargetKind> targets = [TargetKind.enumType];

  /// Creates a new instance of [ForyEnum].
  const ForyEnum();
}

/// A constant instance of [ForyEnum].
const ForyEnum foryEnum = ForyEnum();

/// A class representing an enumeration id in the Fory framework.
///
/// This class extends [ForyObject] and is used to annotate enum ids
/// within the Fory framework.
///
/// Can be used in two ways:
///
/// 1. On each enum value with an explicit id:
/// ```
/// @foryEnum
/// enum Color {
///   @ForyEnumId(5)
///   blue,
///   @ForyEnumId(10)
///   white,
/// }
/// ```
///
/// 2. On an int field of an enhanced enum to use its value as the id:
/// ```
/// @foryEnum
/// enum UserRole {
///   green(0),
///   blue(1),
///   white(2);
///
///   @ForyEnumId()
///   final int code;
///   const Color(this.code);
/// }
/// ```
@Target({TargetKind.enumValue, TargetKind.field})
class ForyEnumId extends ForyObject {
  final int? id;

  const ForyEnumId([this.id]);
}