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

import 'dart:collection';

import 'package:fory/src/buffer.dart';

final class RefWriter {
  static const int nullFlag = -3;
  static const int refFlag = -2;
  static const int notNullValueFlag = -1;
  static const int refValueFlag = 0;

  final Map<Object, int> _ids = LinkedHashMap<Object, int>.identity();
  int _nextId = 0;

  bool writeRefOrNull(
    Buffer buffer,
    Object? value, {
    bool trackRef = true,
  }) {
    if (value == null) {
      buffer.writeByte(nullFlag);
      return true;
    }
    if (!trackRef) {
      buffer.writeByte(notNullValueFlag);
      return false;
    }
    final existingId = _ids[value];
    if (existingId != null) {
      buffer.writeByte(refFlag);
      buffer.writeVarUint32(existingId);
      return true;
    }
    _ids[value] = _nextId++;
    buffer.writeByte(refValueFlag);
    return false;
  }

  bool writeRefValueFlag(Buffer buffer, Object value) {
    final existingId = _ids[value];
    if (existingId != null) {
      buffer.writeByte(refFlag);
      buffer.writeVarUint32(existingId);
      return false;
    }
    _ids[value] = _nextId++;
    buffer.writeByte(refValueFlag);
    return true;
  }

  bool writeNullFlag(Buffer buffer, Object? value) {
    if (value == null) {
      buffer.writeByte(nullFlag);
      return true;
    }
    return false;
  }

  void reference(Object value) {
    if (_ids[value] != null) {
      return;
    }
    _ids[value] = _nextId++;
  }

  void reset() {
    _ids.clear();
    _nextId = 0;
  }
}
