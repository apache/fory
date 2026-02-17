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

import 'package:fory/fory.dart';

part '../generated/uint_annotated_struct.g.dart';

/// Test struct for uint type annotations.
/// Uses native int types with annotations to specify serialization format.
@ForyClass(promiseAcyclic: true)
class UIntAnnotatedStruct {
  @Uint8Type()
  final int age;

  @Uint16Type()
  final int port;

  @Uint32Type()
  final int count;

  @Uint32Type(encoding: UintEncoding.varint)
  final int varCount;

  @Uint64Type()
  final int id;

  @Uint64Type(encoding: UintEncoding.varint)
  final int varId;

  @Uint64Type(encoding: UintEncoding.tagged)
  final int taggedId;

  const UIntAnnotatedStruct({
    required this.age,
    required this.port,
    required this.count,
    required this.varCount,
    required this.id,
    required this.varId,
    required this.taggedId,
  });

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is UIntAnnotatedStruct &&
            runtimeType == other.runtimeType &&
            age == other.age &&
            port == other.port &&
            count == other.count &&
            varCount == other.varCount &&
            id == other.id &&
            varId == other.varId &&
            taggedId == other.taggedId);
  }

  @override
  int get hashCode =>
      age.hashCode ^
      port.hashCode ^
      count.hashCode ^
      varCount.hashCode ^
      id.hashCode ^
      varId.hashCode ^
      taggedId.hashCode;
}
