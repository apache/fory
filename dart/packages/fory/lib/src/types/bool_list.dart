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
import 'dart:typed_data';

/// Fixed-length contiguous one-byte storage for bool values.
///
/// [BoolList] is a list-like carrier backed by [Int8List]. It maps to
/// `list<bool>` unless field metadata explicitly requests `array<bool>`.
final class BoolList extends ListBase<bool> {
  /// The number of bytes used by one bool element.
  static const int bytesPerElement = Int8List.bytesPerElement;

  final Int8List _storage;
  final bool _arraySchema;

  /// Creates a zero-initialized list with [length] bool elements.
  BoolList(int length)
      : _storage = Int8List(length),
        _arraySchema = false;

  BoolList._(this._storage, this._arraySchema);

  /// Copies [values] into a new contiguous bool list.
  factory BoolList.fromList(Iterable<bool> values) {
    final copied = values.toList(growable: false);
    final result = BoolList(copied.length);
    for (var index = 0; index < copied.length; index += 1) {
      result[index] = copied[index];
    }
    return result;
  }

  /// Creates a zero-copy view over [buffer].
  factory BoolList.view(ByteBuffer buffer,
      [int offsetInBytes = 0, int? length]) {
    return BoolList._(Int8List.view(buffer, offsetInBytes, length), false);
  }

  /// Creates a zero-copy element-range view of [data].
  factory BoolList.sublistView(TypedData data, [int start = 0, int? end]) {
    final actualEnd =
        RangeError.checkValidRange(start, end, data.lengthInBytes);
    return BoolList.view(
      data.buffer,
      data.offsetInBytes + start,
      actualEnd - start,
    );
  }

  /// Creates a carrier for a dynamically read `array<bool>` value.
  factory BoolList.arrayView(
    ByteBuffer buffer, [
    int offsetInBytes = 0,
    int? length,
  ]) {
    return BoolList._(Int8List.view(buffer, offsetInBytes, length), true);
  }

  /// Creates an array-schema carrier over existing contiguous storage.
  factory BoolList.arrayStorage(Int8List storage) {
    return BoolList._(storage, true);
  }

  /// True when this value came from a dynamic `array<bool>` payload.
  bool get preservesArraySchema => _arraySchema;

  /// Returns the shared backing buffer for this list.
  ByteBuffer get buffer => _storage.buffer;

  /// Returns the byte length of this list view.
  int get lengthInBytes => _storage.lengthInBytes;

  /// Returns the byte offset of this view in [buffer].
  int get offsetInBytes => _storage.offsetInBytes;

  /// Returns a byte view over the storage.
  Uint8List asUint8List() {
    return _storage.buffer.asUint8List(_storage.offsetInBytes, _storage.length);
  }

  @override
  int get length => _storage.length;

  @override
  set length(int newLength) {
    throw UnsupportedError('BoolList has a fixed length.');
  }

  @override
  bool operator [](int index) => _storage[index] != 0;

  @override
  void operator []=(int index, bool value) {
    _storage[index] = value ? 1 : 0;
  }
}
