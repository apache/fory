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

import 'package:fory/src/const/ref_flag.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/resolver/serialization_ref_resolver.dart';

final class SerializationNoRefResolver extends SerializationRefResolver {
  static const SerializationNoRefResolver _instance =
      SerializationNoRefResolver._();
  factory SerializationNoRefResolver() => _instance;
  // private constructor
  const SerializationNoRefResolver._();

  static final SerializationRefMeta noRef =
      (refFlag: RefFlag.NULL, refId: null);
  static final SerializationRefMeta untrackedNotNull =
      (refFlag: RefFlag.UNTRACKED_NOT_NULL, refId: null);

  @inline
  @override
  SerializationRefMeta getRefId(Object? obj) {
    return obj == null ? noRef : untrackedNotNull;
  }

  @override
  @inline
  RefFlag getRefFlag(Object? obj) {
    return obj == null ? RefFlag.NULL : RefFlag.UNTRACKED_NOT_NULL;
  }
}
