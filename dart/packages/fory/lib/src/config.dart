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
  static const int defaultMaxGraphMemoryBytes = 128 * 1024 * 1024;

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

  /// Approximate graph-memory gate for one root deserialization.
  ///
  /// Mainly gates materialized lists, maps, arrays, structs, and objects. Leaf
  /// values are gated by unread input bytes instead, and actual process memory
  /// can be higher.
  ///
  /// Value must be a positive byte limit.
  final int maxGraphMemoryBytes;

  /// Creates an immutable configuration object.
  ///
  /// Invalid numeric limits fail fast. When [compatible] is `true`,
  /// [checkStructVersion] is normalized to `false`.
  Config({
    this.compatible = true,
    bool checkStructVersion = true,
    int maxDepth = defaultMaxDepth,
    int maxTypeFields = defaultMaxTypeFields,
    int maxTypeMetaBytes = defaultMaxTypeMetaBytes,
    int maxSchemaVersionsPerType = defaultMaxSchemaVersionsPerType,
    int maxAverageSchemaVersionsPerType =
        defaultMaxAverageSchemaVersionsPerType,
    int maxGraphMemoryBytes = defaultMaxGraphMemoryBytes,
  }) : checkStructVersion = compatible ? false : checkStructVersion,
       maxDepth = _positive(maxDepth, 'maxDepth'),
       maxTypeFields = _positive(maxTypeFields, 'maxTypeFields'),
       maxTypeMetaBytes = _positive(maxTypeMetaBytes, 'maxTypeMetaBytes'),
       maxSchemaVersionsPerType = _positive(
         maxSchemaVersionsPerType,
         'maxSchemaVersionsPerType',
       ),
       maxAverageSchemaVersionsPerType = _positive(
         maxAverageSchemaVersionsPerType,
         'maxAverageSchemaVersionsPerType',
       ),
       maxGraphMemoryBytes = _positiveSafeInteger(
         maxGraphMemoryBytes,
         'maxGraphMemoryBytes',
       );

  static int _positive(int value, String name) {
    if (value <= 0) {
      throw ArgumentError.value(value, name, 'must be positive');
    }
    return value;
  }

  static int _positiveSafeInteger(int value, String name) {
    const maxSafeInteger = 9007199254740991;
    if (value <= 0 || value > maxSafeInteger) {
      throw ArgumentError.value(
        value,
        name,
        'must be in range [1, $maxSafeInteger]',
      );
    }
    return value;
  }
}
