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
import 'package:fory/src/serializer/serializer.dart';
import 'package:meta/meta.dart';

/// Union-specific serializer base used by manual union serializers.
///
/// The runtime owns the xlang wire contract:
/// `case_id (varuint32) | case_value (Any-style payload)`.
abstract class UnionSerializer<T> extends Serializer<T> {
  const UnionSerializer();

  /// Returns the active union case identifier for [value].
  int caseId(T value);

  /// Returns the active case payload for [value].
  Object? caseValue(T value);

  /// Rebuilds a union value from a decoded [caseId] and [value].
  T buildValue(int caseId, Object? value);

  /// Writes the active case payload. Generated union serializers override this
  /// to use the declared case type for collection and array payloads.
  void writeCasePayload(WriteContext context, int caseId, Object? value) {
    context.writeRef(value);
  }

  /// Reads the active case payload. Generated union serializers override this
  /// to use the declared case type for collection and array payloads.
  Object? readCasePayload(ReadContext context, int caseId) {
    return context.readRef();
  }

  @nonVirtual
  @override
  void write(WriteContext context, T value) {
    final activeCaseId = caseId(value);
    if (activeCaseId < 0) {
      throw StateError('Union case id must be non-negative: $activeCaseId.');
    }
    context.buffer.writeVarUint32(activeCaseId);
    writeCasePayload(context, activeCaseId, caseValue(value));
  }

  @nonVirtual
  @override
  T read(ReadContext context) {
    final activeCaseId = context.buffer.readVarUint32();
    return buildValue(activeCaseId, readCasePayload(context, activeCaseId));
  }
}
