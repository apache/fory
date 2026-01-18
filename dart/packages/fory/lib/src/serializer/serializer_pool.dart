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
import 'package:fory/src/const/obj_type.dart';
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
import 'package:fory/src/serializer/boollist_serializer.dart';
import 'package:fory/src/serializer/collection/list/def_list_serializer.dart';
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

class SerializerPool{

  static List<TypeInfo?> setSerForDefaultType(
      Map<Type, TypeInfo> type2Ser,
      ForyConfig conf,
      ){

    Serializer linkedMapSer = LinkedHashMapSerializer.cache.getSerializer(conf);
    Serializer linkedHashSetSer = LinkedHashSetSerializer.cache.getSerializer(conf);

    type2Ser[int]!.ser = Int64Serializer.cache.getSerializer(conf);
    type2Ser[bool]!.ser = BoolSerializer.cache.getSerializer(conf);
    type2Ser[TimeStamp]!.ser = TimestampSerializer.cache.getSerializer(conf);
    type2Ser[LocalDate]!.ser = DateSerializer.cache.getSerializer(conf);
    type2Ser[double]!.ser = Float64Serializer.cache.getSerializer(conf);
    type2Ser[Int8]!.ser = Int8Serializer.cache.getSerializer(conf);
    type2Ser[Int16]!.ser = Int16Serializer.cache.getSerializer(conf);
    type2Ser[Int32]!.ser = Int32Serializer.cache.getSerializer(conf);
    type2Ser[UInt8]!.ser = UInt8Serializer.cache.getSerializer(conf);
    type2Ser[UInt16]!.ser = UInt16Serializer.cache.getSerializer(conf);
    type2Ser[UInt32]!.ser = UInt32Serializer.cache.getSerializer(conf);
    type2Ser[Float32]!.ser = Float32Serializer.cache.getSerializer(conf);
    type2Ser[String]!.ser = StringSerializer.cache.getSerializer(conf);

    type2Ser[List]!.ser = DefListSerializer.cache.getSerializer(conf);

    type2Ser[Map]!.ser = linkedMapSer;
    type2Ser[LinkedHashMap]!.ser = linkedMapSer;
    type2Ser[HashMap]!.ser = HashMapSerializer.cache.getSerializer(conf);
    type2Ser[SplayTreeMap]!.ser = SplayTreeMapSerializer.cache.getSerializer(conf);

    type2Ser[Set]!.ser = linkedHashSetSer;
    type2Ser[LinkedHashSet]!.ser = linkedHashSetSer;
    type2Ser[HashSet]!.ser = HashSetSerializer.cache.getSerializer(conf);
    type2Ser[SplayTreeSet]!.ser = SplayTreeSetSerializer.cache.getSerializer(conf);

    type2Ser[Uint8List]!.ser = Uint8ListSerializer.cache.getSerializer(conf);
    type2Ser[Int8List]!.ser = Int8ListSerializer.cache.getSerializer(conf);
    type2Ser[Int16List]!.ser = Int16ListSerializer.cache.getSerializer(conf);
    type2Ser[Int32List]!.ser = Int32ListSerializer.cache.getSerializer(conf);
    type2Ser[Int64List]!.ser = Int64ListSerializer.cache.getSerializer(conf);
    type2Ser[Float32List]!.ser = Float32ListSerializer.cache.getSerializer(conf);
    type2Ser[Float64List]!.ser = Float64ListSerializer.cache.getSerializer(conf);
    type2Ser[BoolList]!.ser = BoolListSerializer.cache.getSerializer(conf);

    List<TypeInfo?> objTypeId2TypeInfo = List<TypeInfo?>.filled(
      ObjType.values.length,
      null,
    );

    List<DartTypeEnum> values = DartTypeEnum.values;
    for (int i = 0; i< values.length; ++i){
      if (!values[i].supported || !values[i].defForObjType){
        continue;
      }
      objTypeId2TypeInfo[values[i].objType!.id] = type2Ser[values[i].dartType];
    }
    return objTypeId2TypeInfo;
  }
}