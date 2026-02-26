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

import 'package:fory/src/resolver/deserialization_ref_resolver.dart';

class DeserializationMapRefResolver implements DeserializationRefResolver {
  final List<Object?> _refs = [];

  int _lastRefId = -1;

  @override
  Object getObj(int refId) {
    assert(_refs.length > refId);
    return _refs[refId]!;
  }

  @override
  int reserveId() {
    ++_lastRefId;
    _refs.add(null);
    return _lastRefId;
  }

  @override
  void setRefTheLatestId(Object o) {
    _refs[_lastRefId] = o;
  }

  @override
  void setRef(int refId, Object o) {
    _refs[refId] = o;
  }

  // @override
  // int reserveIdSetMuchLatter() {
  //   assert(_refIdWillSetLater == null);
  //   _refIdWillSetLater = reserveId();
  //   return _refIdWillSetLater!;
  // }
  //
  // @override
  // void setRefForLaterRefId(Object o) {
  //   assert(_refIdWillSetLater != null);
  //   _refs[_refIdWillSetLater!] = o;
  //   _refIdWillSetLater = null;
  // }
}
