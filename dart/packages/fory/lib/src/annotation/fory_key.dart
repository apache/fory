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

/// This class is used as a target for fields and includes options to
/// specify whether to include from and to Fory.
///
/// Example:
/// ```
/// @foryClass
/// class MyClass {
///   @foryKey(includeFromFory: false)
///   final String name;
/// }
/// ```
///
/// The `ForyKey` class has two optional parameters:
/// - `includeFromFory`: A boolean value indicating whether to include this field during deserialization.
///   Defaults to `true`.
/// - `includeToFory`: A boolean value indicating whether to include this field during serialization.
///   Defaults to `true`.
@Target({TargetKind.field})
class ForyKey {
  static const String name = 'ForyKey';
  static const List<TargetKind> targets = [TargetKind.field];

  /// A boolean value indicating whether to include this field during deserialization.
  final bool includeFromFory;

  /// A boolean value indicating whether to include this field during serialization.
  final bool includeToFory;

  /// A boolean value indicating whether to track references for this field.
  final bool ref;

  /// Both [includeFromFory] and [includeToFory] default to `true`.
  const ForyKey({
    this.includeFromFory = true,
    this.includeToFory = true,
    this.ref = false,
  });
}
