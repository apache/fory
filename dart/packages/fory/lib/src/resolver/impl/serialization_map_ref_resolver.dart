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

import 'package:fory/src/const/ref_flag.dart';
import 'package:fory/src/resolver/serialization_ref_resolver.dart';

final class SerializationMapRefResolver extends SerializationRefResolver {
  static final SerializationRefMeta noRef =
      (refFlag: RefFlag.NULL, refId: null);

  final Map<int, int> idenHashToRefId = HashMap();

  @override
  SerializationRefMeta getRefId(Object? obj) {
    if (obj == null) {
      return noRef;
    }
    int idenHash = identityHashCode(obj);
    int? refId = idenHashToRefId[idenHash];
    if (refId != null) {
      return (refFlag: RefFlag.TRACKED_ALREADY, refId: refId);
    } else {
      // first time
      refId = idenHashToRefId.length;
      idenHashToRefId[idenHash] = refId;
      return (refFlag: RefFlag.TRACK_FIRST, refId: null);
    }
  }
}
