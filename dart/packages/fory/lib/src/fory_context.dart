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
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/fory_exception.dart'
    show
        DuplicatedTagRegistrationException,
        DuplicatedTypeRegistrationException,
        DuplicatedUserTypeIdRegistrationException;
import 'package:fory/src/collection/long_long_key.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_pool.dart';
import 'package:fory/src/const/dart_type.dart';
import 'package:fory/src/const/types.dart';

class ForyContext {
  // Cannot be static because TypeInfo contains the serializer field
  final Iterable<MapEntry<Type, TypeInfo>> _defaultTypeInfos =
      DartTypeEnum.values.where((e) => e.objType != null).map((e) => MapEntry(
            e.dartType,
            TypeInfo(e.dartType, e.objType!, null, null, null),
          ));

  final ForyConfig conf;
  final Map<String, TypeInfo> tag2TypeInfo;
  final Map<Type, TypeInfo> type2TypeInfo;
  final Map<LongLongKey, TypeInfo> userTypeId2TypeInfo;
  late final List<TypeInfo?> objTypeId2TypeInfo;

  late final Serializer abstractListSerializer;
  late final Serializer abstractSetSerializer;
  late final Serializer abstractMapSerializer;

  ForyContext(this.conf)
      : tag2TypeInfo = HashMap(),
        type2TypeInfo = HashMap(),
        userTypeId2TypeInfo = HashMap();

  void initForDefaultTypes() {
    type2TypeInfo.addEntries(_defaultTypeInfos);
    objTypeId2TypeInfo =
        SerializerPool.setSerializerForDefaultType(type2TypeInfo, conf);
    abstractListSerializer = objTypeId2TypeInfo[ObjType.LIST.id]!.serializer;
    abstractSetSerializer = objTypeId2TypeInfo[ObjType.SET.id]!.serializer;
    abstractMapSerializer = objTypeId2TypeInfo[ObjType.MAP.id]!.serializer;
  }

  void registerType(TypeInfo typeInfo) {
    TypeInfo? info = type2TypeInfo[typeInfo.dartType];
    // Check if the type is already registered
    if (info != null) {
      throw DuplicatedTypeRegistrationException(
        info.dartType,
        typeInfo.tag ?? typeInfo.userTypeId,
      );
    }
    if (typeInfo.tag != null) {
      // Check if the tag is already registered
      info = tag2TypeInfo[typeInfo.tag];
      if (info != null) {
        throw DuplicatedTagRegistrationException(
          typeInfo.tag!,
          info.dartType,
          typeInfo.dartType,
        );
      }
      tag2TypeInfo[typeInfo.tag!] = typeInfo;
    }
    if (typeInfo.objType.needsUserTypeId() &&
        typeInfo.userTypeId != kInvalidUserTypeId) {
      LongLongKey key = LongLongKey(typeInfo.objType.id, typeInfo.userTypeId);
      info = userTypeId2TypeInfo[key];
      if (info != null) {
        throw DuplicatedUserTypeIdRegistrationException(
          typeInfo.userTypeId,
          info.dartType,
          typeInfo.dartType,
        );
      }
      userTypeId2TypeInfo[key] = typeInfo;
    }
    type2TypeInfo[typeInfo.dartType] = typeInfo;
  }
}
