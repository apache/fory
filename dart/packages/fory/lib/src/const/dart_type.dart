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
import 'package:collection/collection.dart' show BoolList;
import 'package:decimal/decimal.dart';
import 'package:fory/src/datatype/float16.dart';
import 'package:fory/src/datatype/float32.dart';
import 'package:fory/src/datatype/int16.dart';
import 'package:fory/src/datatype/int32.dart';
import 'package:fory/src/datatype/int8.dart';
import 'package:fory/src/datatype/uint8.dart';
import 'package:fory/src/datatype/uint16.dart';
import 'package:fory/src/datatype/uint32.dart';

import 'package:fory/src/const/types.dart';
import 'package:fory/src/datatype/local_date.dart';
import 'package:fory/src/datatype/timestamp.dart';

/// Regarding type screening during static code analysis to prevent using unsupported types
/// If the user declares a field type that is a Dart official type, it may still be unsupported, such as Duration and Decimal...
/// At the static checking stage, once an unsupported type is detected, an exception will be thrown
/// If the user declares a custom type, the static checking stage cannot determine whether it is supported
/// because the declared type might be a parent class (e.g., Object)
/// Also, a supported subclass might be used at runtime, so the static checking stage does not interfere in such cases
enum DartTypeEnum {
  BOOL(
      bool, true, 'bool', 'dart', 'core', ObjType.BOOL, true, 'dart:core@bool'),
  INT8(Int8, true, 'Int8', 'package', 'fory/src/datatype/int8.dart',
      ObjType.INT8, true, 'dart:core@Int8'),
  INT16(Int16, true, 'Int16', 'package', 'fory/src/datatype/int16.dart',
      ObjType.INT16, true, 'dart:core@Int16'),
  INT32(Int32, true, 'Int32', 'package', 'fory/src/datatype/int32.dart',
      ObjType.INT32, true, 'dart:core@Int32'),
  UINT8(UInt8, true, 'UInt8', 'package', 'fory/src/datatype/uint8.dart',
      ObjType.UINT8, true, 'dart:core@UInt8'),
  UINT16(UInt16, true, 'UInt16', 'package', 'fory/src/datatype/uint16.dart',
      ObjType.UINT16, true, 'dart:core@UInt16'),
  UINT32(UInt32, true, 'UInt32', 'package', 'fory/src/datatype/uint32.dart',
      ObjType.UINT32, true, 'dart:core@UInt32'),
  INT(int, true, 'int', 'dart', 'core', ObjType.INT64, true, 'dart:core@int'),
  FLOAT32(Float32, true, 'Float32', 'package', 'fory/src/datatype/float32.dart',
      ObjType.FLOAT32, true, 'dart:core@Float32'),
  FLOAT16(Float16, true, 'Float16', 'package', 'fory/src/datatype/float16.dart',
        ObjType.FLOAT16, true, 'dart:core@Float16'),
  DOUBLE(double, true, 'double', 'dart', 'core', ObjType.FLOAT64, true,
      'dart:core@double'),
  STRING(String, true, 'String', 'dart', 'core', ObjType.STRING, true,
      'dart:core@String'),

  LOCALDATE(
      LocalDate,
      true,
      'LocalDate',
      'package',
      'fory/src/datatype/local_date.dart',
      ObjType.DATE,
      true,
      'dart:core@LocalDate'),
  TIMESTAMP(
      TimeStamp,
      false,
      'TimeStamp',
      'package',
      'fory/src/datatype/timestamp.dart',
      ObjType.TIMESTAMP,
      true,
      'dart:core@DateTime'),

  BOOLLIST(
      BoolList,
      true,
      'BoolList',
      'package',
      'collection/src/boollist.dart',
      ObjType.BOOL_ARRAY,
      true,
      'dart:typed_data@BoolList'),
  UINT8LIST(Uint8List, true, 'Uint8List', 'dart', 'typed_data', ObjType.BINARY,
      true, 'dart:typed_data@Uint8List'),
  INT8LIST(Int8List, true, 'Int8List', 'dart', 'typed_data', ObjType.INT8_ARRAY,
      true, 'dart:typed_data@Int8List'),
  INT16LIST(Int16List, true, 'Int16List', 'dart', 'typed_data',
      ObjType.INT16_ARRAY, true, 'dart:typed_data@Int16List'),
  INT32LIST(Int32List, true, 'Int32List', 'dart', 'typed_data',
      ObjType.INT32_ARRAY, true, 'dart:typed_data@Int32List'),
  INT64LIST(Int64List, true, 'Int64List', 'dart', 'typed_data',
      ObjType.INT64_ARRAY, true, 'dart:typed_data@Int64List'),
  FLOAT32LIST(Float32List, true, 'Float32List', 'dart', 'typed_data',
      ObjType.FLOAT32_ARRAY, true, 'dart:typed_data@Float32List'),
  FLOAT64LIST(Float64List, true, 'Float64List', 'dart', 'typed_data',
      ObjType.FLOAT64_ARRAY, true, 'dart:typed_data@Float64List'),

  LIST(List, false, 'List', 'dart', 'core', ObjType.LIST, true,
      'dart:core@List'),

  MAP(Map, false, 'Map', 'dart', 'core', ObjType.MAP, true, 'dart:core@Map'),
  LINKEDHASHMAP(LinkedHashMap, true, 'LinkedHashMap', 'dart', 'collection',
      ObjType.MAP, false, 'dart:collection@LinkedHashMap'),
  HASHMAP(HashMap, true, 'HashMap', 'dart', 'collection', ObjType.MAP, false,
      'dart:collection@HashMap'),
  SPLAYTREEMAP(SplayTreeMap, true, 'SplayTreeMap', 'dart', 'collection',
      ObjType.MAP, false, 'dart:collection@SplayTreeMap'),

  SET(Set, false, 'Set', 'dart', 'core', ObjType.SET, true, 'dart:core@Set'),
  LINKEDHASHSET(LinkedHashSet, true, 'LinkedHashSet', 'dart', 'collection',
      ObjType.SET, false, 'dart:collection@LinkedHashSet'),
  HASHSET(HashSet, true, 'HashSet', 'dart', 'collection', ObjType.SET, false,
      'dart:collection@HashSet'),
  SPLAYTREESET(SplayTreeSet, true, 'SplayTreeSet', 'dart', 'collection',
      ObjType.SET, false, 'dart:collection@SplayTreeSet'),

  // TODO: For classes from external libraries, the path might be updated, please note
  DECIMAL(Decimal, true, 'Decimal', 'package', 'decimal/decimal.dart', null,
      true, 'package:decimal/decimal.dart@Decimal'),
  DURATION(Duration, true, 'Duration', 'dart', 'core', null, true,
      'dart:core@Duration'),
  ;

  // NUM(num, false, 'num', 'dart', 'core', ObjType.UNKNOWN, 'dart:core@num'),

  final String scheme;
  final String path;
  final String typeName;
  final Type dartType;
  final bool serializationCertain;
  final ObjType?
      objType; // null means: confirmed as an internal Dart type, also unsupported
  final bool
      defForObjType; // Whether this Dart type is the default type for objType
  final String
      fullSign; // This field is the concatenation of scheme + path + name, but to avoid runtime concatenation, it is specified here

  const DartTypeEnum(
    this.dartType,
    this.serializationCertain,
    this.typeName,
    this.scheme,
    this.path,
    this.objType,
    this.defForObjType,
    this.fullSign,
  );

  static final Map<String, DartTypeEnum> _typeName2Enum = {
    for (var e in DartTypeEnum.values) e.typeName: e,
  };

  bool get supported => objType != null;

  @Deprecated('use find')
  // TODO: Using this method requires that all DartTypeEnum names be unique and sorted in strict string order
  /// Returning null indicates it is definitely a Dart built-in type, which is unsupported
  /// Returning UNKNOWN means uncertain
  // TODO: Attempt to record the Dart analyzer's ID to achieve a numerical comparison

  static DartTypeEnum? find(String name, String scheme, String path) {
    DartTypeEnum? e = _typeName2Enum[name];
    if (e == null) return null;
    if (e.scheme == scheme && e.path == path) {
      return e;
    } else {
      return null;
    }
  }
}
