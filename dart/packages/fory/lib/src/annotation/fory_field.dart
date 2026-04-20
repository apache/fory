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

/// Field-level code generation options for [ForyStruct].
final class ForyField {
  /// Skips this field entirely in generated serializers.
  final bool skip;

  /// Stable field identifier used by compatible structs.
  ///
  /// When omitted, the generator derives an identifier from the field name.
  final int? id;

  /// Overrides the generator's inferred nullability.
  ///
  /// `null` means "use the Dart type as written".
  final bool? nullable;

  /// Enables reference tracking for this field.
  ///
  /// Basic scalar types never track references even if this flag is `true`.
  final bool ref;

  /// Controls whether generated code writes runtime type metadata for this
  /// field.
  ///
  /// `null` means "auto", `false` means "use the declared type", and `true`
  /// means "write runtime type information".
  final bool? dynamic;

  /// Creates field-level generation overrides.
  const ForyField({
    this.skip = false,
    this.id,
    this.nullable,
    this.ref = false,
    this.dynamic,
  });
}
