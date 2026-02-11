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

class LongLongKey {
  final int _first;
  final int _second;

  int? _hashCode;

  LongLongKey(this._first, this._second);

  @override
  int get hashCode {
    // TODO: Maybe we can use other hash function that is faster
    _hashCode ??= Object.hash(_first, _second);
    return _hashCode!;
  }

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is LongLongKey &&
            runtimeType == other.runtimeType &&
            _first == other._first &&
            _second == other._second;
  }
}
