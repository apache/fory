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

import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';

/// Advanced extension point for generated and manual serializers.
///
/// Most application code uses a generated library namespace for generated
/// types, or [Fory.registerSerializer] for manual serializers, instead of
/// implementing this interface directly.
abstract class Serializer<T> {
  const Serializer();

  /// Whether values handled by this serializer may participate in Ref
  /// tracking when the active field or root operation requests it.
  ///
  /// Immutable serializers such as enums should override this to `false`.
  bool get supportsRef => true;

  /// Writes [value] to [context].
  void write(WriteContext context, T value);

  /// Reads a value of type [T] from [context].
  T read(ReadContext context);
}
