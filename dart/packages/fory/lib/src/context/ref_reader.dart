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

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/context/ref_writer.dart';

final class RefReader {
  final List<Object?> _refs = <Object?>[];
  final List<int> _preservedIds = <int>[];
  Object? _resolved;
  int? _resolvedId;

  int readRefOrNull(Buffer buffer) {
    final flag = buffer.readByte();
    if (flag == RefWriter.refFlag) {
      final id = buffer.readVarUint32();
      _resolvedId = id;
      _resolved = _refs[id];
      return flag;
    }
    _resolvedId = null;
    _resolved = null;
    return flag;
  }

  int tryPreserveRefId(Buffer buffer) {
    final flag = readRefOrNull(buffer);
    if (flag == RefWriter.refValueFlag) {
      return preserveRefId();
    }
    return flag;
  }

  int preserveRefId([int? refId]) {
    final preservedId = refId ?? _refs.length;
    if (refId == null) {
      _refs.add(null);
    } else if (refId >= 0 && refId == _refs.length) {
      _refs.add(null);
    }
    _preservedIds.add(preservedId);
    return preservedId;
  }

  bool get hasPreservedRefId => _preservedIds.isNotEmpty;

  int get preservedRefDepth => _preservedIds.length;

  int get lastPreservedRefId => _preservedIds.last;

  Object? get readRef => _resolved;

  int? get readRefId => _resolvedId;

  Object? getReadRef([int? id]) => id == null ? _resolved : _refs[id];

  Object? readRefAt(int id) => _refs[id];

  void reference(Object? value) {
    if (_preservedIds.isEmpty) {
      throw StateError(
        'reference(value) requires a preserved read ref id.',
      );
    }
    final id = _preservedIds.removeLast();
    if (id < 0) {
      return;
    }
    _refs[id] = value;
  }

  void setReadRef(int id, Object? value) {
    _refs[id] = value;
  }

  void reset() {
    _refs.clear();
    _preservedIds.clear();
    _resolved = null;
    _resolvedId = null;
  }
}
