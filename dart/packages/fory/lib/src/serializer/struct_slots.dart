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

import 'package:meta/meta.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';

@internal
final class StructWriteSlots {
  final List<SerializationFieldInfo> orderedFields;
  final List<SerializationFieldInfo?> _fieldsBySlot;

  StructWriteSlots(this.orderedFields, int fieldCount)
      : _fieldsBySlot = List<SerializationFieldInfo?>.filled(fieldCount, null) {
    for (final field in orderedFields) {
      _fieldsBySlot[field.slot] = field;
    }
  }

  SerializationFieldInfo? fieldForSlot(int slot) => _fieldsBySlot[slot];
}

@internal
final class StructReadSlots {
  final List<Object?> compatibleValues;
  final List<bool> presentSlots;

  const StructReadSlots(this.compatibleValues, this.presentSlots);

  bool containsSlot(int slot) => presentSlots[slot];

  Object? valueForSlot(int slot) => compatibleValues[slot];
}
