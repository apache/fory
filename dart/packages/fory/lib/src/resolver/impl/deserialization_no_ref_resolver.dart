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

class DeserializationNoRefResolver implements DeserializationRefResolver {
  const DeserializationNoRefResolver();

  // @override
  // void appendRef(Object obj) {}

  @override
  Object getObj(int refId) {
    throw UnimplementedError("NoRefResolver does not support getObj");
  }

  @override
  int reserveId() {
    return 0; // nothing
  }

  @override
  void setRefTheLatestId(Object o) {
    // do nothing
  }

  @override
  void setRef(int refId, Object o) {
    // do nothing
  }

  // @override
  // int reserveIdSetMuchLatter() {
  //   return 0; // nothing
  // }
  //
  // @override
  // void setRefForLaterRefId(Object o) {
  //   // do nothing
  // }
}
