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

/// Fory instance configuration for the Dart xlang implementation.
///
/// The defaults favor compatible mode with conservative structural limits.
final class Config {
  /// Default maximum nesting depth for a single serialization or
  /// deserialization operation.
  static const int defaultMaxDepth = 256;

  /// Enables compatible struct encoding and decoding.
  ///
  /// In compatible mode Fory shares TypeDef metadata and disables
  /// [checkStructVersion].
  final bool compatible;

  /// Enables struct schema-version validation for same-schema payloads.
  ///
  /// This flag is forced to `false` when [compatible] is `true`.
  final bool checkStructVersion;

  /// Maximum allowed read or write nesting depth.
  final int maxDepth;

  /// Creates an immutable configuration object.
  ///
  /// Invalid numeric limits fail fast. When [compatible] is `true`,
  /// [checkStructVersion] is normalized to `false`.
  const Config({
    this.compatible = true,
    bool checkStructVersion = true,
    this.maxDepth = defaultMaxDepth,
  }) : checkStructVersion = compatible ? false : checkStructVersion,
       assert(maxDepth > 0, 'maxDepth must be positive');
}
