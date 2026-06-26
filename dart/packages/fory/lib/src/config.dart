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
  static const int defaultMaxTypeFields = 512;
  static const int defaultMaxTypeMetaBytes = 4096;
  static const int defaultMaxSchemaVersionsPerType = 10;
  static const int defaultMaxAverageSchemaVersionsPerType = 3;
  static const int defaultMaxContainerMemoryBytes = -1;

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

  /// Maximum accepted field count in one received struct TypeDef.
  final int maxTypeFields;

  /// Maximum accepted body size in one received TypeDef.
  final int maxTypeMetaBytes;

  /// Maximum accepted remote metadata versions for one logical type.
  final int maxSchemaVersionsPerType;

  /// Maximum accepted average remote metadata versions across logical
  /// types.
  final int maxAverageSchemaVersionsPerType;

  /// Maximum estimated container-owned memory per root deserialization.
  ///
  /// `-1` means auto. Positive values are explicit byte limits.
  final int maxContainerMemoryBytes;

  /// Creates an immutable configuration object.
  ///
  /// Invalid numeric limits fail fast. When [compatible] is `true`,
  /// [checkStructVersion] is normalized to `false`.
  const Config({
    this.compatible = true,
    bool checkStructVersion = true,
    this.maxDepth = defaultMaxDepth,
    this.maxTypeFields = defaultMaxTypeFields,
    this.maxTypeMetaBytes = defaultMaxTypeMetaBytes,
    this.maxSchemaVersionsPerType = defaultMaxSchemaVersionsPerType,
    this.maxAverageSchemaVersionsPerType =
        defaultMaxAverageSchemaVersionsPerType,
    this.maxContainerMemoryBytes = defaultMaxContainerMemoryBytes,
  }) : checkStructVersion = compatible ? false : checkStructVersion,
       assert(maxDepth > 0, 'maxDepth must be positive'),
       assert(maxTypeFields > 0, 'maxTypeFields must be positive'),
       assert(maxTypeMetaBytes > 0, 'maxTypeMetaBytes must be positive'),
       assert(
         maxSchemaVersionsPerType > 0,
         'maxSchemaVersionsPerType must be positive',
       ),
       assert(
         maxAverageSchemaVersionsPerType > 0,
         'maxAverageSchemaVersionsPerType must be positive',
       ),
       assert(
         maxContainerMemoryBytes == -1 || maxContainerMemoryBytes > 0,
         'maxContainerMemoryBytes must be -1 or positive',
       );
}
