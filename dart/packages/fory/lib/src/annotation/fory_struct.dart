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

/// Configures Fory generation for an ordinary class or mixin, or for [target].
final class ForyStruct {
  /// Whether the generated struct should use evolving field metadata.
  ///
  /// Set this to `false` for a fixed schema when you do not need compatible
  /// field evolution.
  final bool evolving;

  /// The external Dart class serialized by this declaration.
  ///
  /// Leave this unset when annotating the class that Fory serializes. When it
  /// is set, the annotated class is a schema-only external structural
  /// serializer declaration and generated code reads and constructs [target]
  /// directly.
  final Type? target;

  /// The named generative constructor used to rebuild [target].
  ///
  /// Leave this unset to use the unnamed constructor. This option is valid only
  /// with [target].
  final String? constructor;

  /// Whether this library exposes private hierarchy storage to child libraries.
  ///
  /// This option is valid only on a public ordinary hierarchy boundary. It
  /// generates typed static access methods for non-ignored private storage
  /// declared by this library and inherited through the boundary. It does not
  /// control field discovery or same-library private access, and it cannot be
  /// used with [target].
  final bool exposePrivateFields;

  /// Whether this struct omits private storage declared by hierarchy ancestors.
  ///
  /// This option belongs to the annotated concrete struct only. When enabled,
  /// private instance fields declared by any superclass or applied mixin are
  /// omitted from this struct's flattened schema, including fields from the
  /// same library. Private fields declared by the annotated class and all
  /// inherited public fields remain in the schema.
  ///
  /// This option defaults to `false` and is not inherited from an ancestor's
  /// annotation. It is invalid with [target] and on provider-only
  /// declarations. When a concrete boundary also enables
  /// [exposePrivateFields], its provider companion is generated independently
  /// of this schema option.
  final bool ignoreInheritedPrivateFields;

  /// Creates struct-level generation options.
  const ForyStruct({
    this.evolving = true,
    this.target,
    this.constructor,
    this.exposePrivateFields = false,
    this.ignoreInheritedPrivateFields = false,
  });
}
