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
import 'package:collection/collection.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/const/dart_type.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/datatype/float16.dart';
import 'package:fory/src/datatype/float32.dart';
import 'package:fory/src/datatype/int16.dart';
import 'package:fory/src/datatype/int32.dart';
import 'package:fory/src/datatype/int8.dart';
import 'package:fory/src/datatype/uint8.dart';
import 'package:fory/src/datatype/uint16.dart';
import 'package:fory/src/datatype/uint32.dart';
import 'package:fory/src/datatype/local_date.dart';
import 'package:fory/src/datatype/timestamp.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/serializer/bool_list_serializer.dart';
import 'package:fory/src/serializer/collection/list/default_list_serializer.dart';
import 'package:fory/src/serializer/collection/map/hashmap_serializer.dart';
import 'package:fory/src/serializer/collection/map/linked_hash_map_serializer.dart';
import 'package:fory/src/serializer/collection/map/splay_tree_map_serializer.dart';
import 'package:fory/src/serializer/collection/set/hash_set_serializer.dart';
import 'package:fory/src/serializer/collection/set/linked_hash_set_serializer.dart';
import 'package:fory/src/serializer/collection/set/splay_tree_set_serializer.dart';
import 'package:fory/src/serializer/primitive_type_serializer.dart';
import 'package:fory/src/serializer/string_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/time/date_serializer.dart';
import 'package:fory/src/serializer/time/timestamp_serializer.dart';
import 'package:fory/src/serializer/typed_data_array_serializer.dart';

class SerializerPool {
  static List<TypeInfo?> setSerializerForDefaultType(
    Map<Type, TypeInfo> typeToTypeInfo,
    ForyConfig conf,
  ) {
    Serializer linkedMapSerializer =
        LinkedHashMapSerializer.cache.getSerializer(conf);
    Serializer linkedHashSetSerializer =
        LinkedHashSetSerializer.cache.getSerializer(conf);

    typeToTypeInfo[int]!.serializer = Int64Serializer.cache.getSerializer(conf);
    typeToTypeInfo[bool]!.serializer = BoolSerializer.cache.getSerializer(conf);
    typeToTypeInfo[TimeStamp]!.serializer =
        TimestampSerializer.cache.getSerializer(conf);
    typeToTypeInfo[LocalDate]!.serializer =
        DateSerializer.cache.getSerializer(conf);
    typeToTypeInfo[double]!.serializer =
        Float64Serializer.cache.getSerializer(conf);
    typeToTypeInfo[Int8]!.serializer = Int8Serializer.cache.getSerializer(conf);
    typeToTypeInfo[Int16]!.serializer =
        Int16Serializer.cache.getSerializer(conf);
    typeToTypeInfo[Int32]!.serializer =
        Int32Serializer.cache.getSerializer(conf);
    typeToTypeInfo[UInt8]!.serializer =
        UInt8Serializer.cache.getSerializer(conf);
    typeToTypeInfo[UInt16]!.serializer =
        UInt16Serializer.cache.getSerializer(conf);
    typeToTypeInfo[UInt32]!.serializer =
        UInt32Serializer.cache.getSerializer(conf);
    typeToTypeInfo[Float32]!.serializer =
        Float32Serializer.cache.getSerializer(conf);
    typeToTypeInfo[Float16]!.serializer =
        Float16Serializer.cache.getSerializer(conf);
    typeToTypeInfo[String]!.serializer =
        StringSerializer.cache.getSerializer(conf);

    typeToTypeInfo[List]!.serializer =
        DefaultListSerializer.cache.getSerializer(conf);

    typeToTypeInfo[Map]!.serializer = linkedMapSerializer;
    typeToTypeInfo[LinkedHashMap]!.serializer = linkedMapSerializer;
    typeToTypeInfo[HashMap]!.serializer =
        HashMapSerializer.cache.getSerializer(conf);
    typeToTypeInfo[SplayTreeMap]!.serializer =
        SplayTreeMapSerializer.cache.getSerializer(conf);

    typeToTypeInfo[Set]!.serializer = linkedHashSetSerializer;
    typeToTypeInfo[LinkedHashSet]!.serializer = linkedHashSetSerializer;
    typeToTypeInfo[HashSet]!.serializer =
        HashSetSerializer.cache.getSerializer(conf);
    typeToTypeInfo[SplayTreeSet]!.serializer =
        SplayTreeSetSerializer.cache.getSerializer(conf);

    typeToTypeInfo[Uint8List]!.serializer =
        Uint8ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Int8List]!.serializer =
        Int8ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Int16List]!.serializer =
        Int16ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Int32List]!.serializer =
        Int32ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Int64List]!.serializer =
        Int64ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Float32List]!.serializer =
        Float32ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[Float64List]!.serializer =
        Float64ListSerializer.cache.getSerializer(conf);
    typeToTypeInfo[BoolList]!.serializer =
        BoolListSerializer.cache.getSerializer(conf);

    List<TypeInfo?> objTypeId2TypeInfo = List<TypeInfo?>.filled(
      ObjType.values.length,
      null,
    );

    List<DartTypeEnum> values = DartTypeEnum.values;
    for (int i = 0; i < values.length; ++i) {
      if (!values[i].supported || !values[i].defForObjType) {
        continue;
      }
      objTypeId2TypeInfo[values[i].objType!.id] =
          typeToTypeInfo[values[i].dartType];
    }
    objTypeId2TypeInfo[ObjType.VAR_INT32.id] = TypeInfo.fromInnerType(
      Int32,
      ObjType.VAR_INT32,
      Int32Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.VAR_INT64.id] = TypeInfo.fromInnerType(
      int,
      ObjType.VAR_INT64,
      Int64Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.SLI_INT64.id] = TypeInfo.fromInnerType(
      int,
      ObjType.SLI_INT64,
      Int64Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.VAR_UINT32.id] = TypeInfo.fromInnerType(
      UInt32,
      ObjType.VAR_UINT32,
      VarUInt32Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.UINT64.id] = TypeInfo.fromInnerType(
      int,
      ObjType.UINT64,
      UInt64Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.VAR_UINT64.id] = TypeInfo.fromInnerType(
      int,
      ObjType.VAR_UINT64,
      VarUInt64Serializer.cache.getSerializer(conf),
    );
    objTypeId2TypeInfo[ObjType.TAGGED_UINT64.id] = TypeInfo.fromInnerType(
      int,
      ObjType.TAGGED_UINT64,
      TaggedUInt64Serializer.cache.getSerializer(conf),
    );
    return objTypeId2TypeInfo;
  }
}
