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
import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/util/string_util.dart';

class StructHashResolver{
  // singleton
  static final StructHashResolver _instance = StructHashResolver._internal();
  static StructHashResolver get inst => _instance;
  StructHashResolver._internal();

  // key: utf8 string objectIdentityHash, value: hash value
  final Map<int, int> _stringObjToHash = HashMap();

  StructHashPair computeHash(List<FieldSpec> fields, String Function(Type type) getTagByType) {
    if (fields.isEmpty) {
      return const StructHashPair(17, 17);
    }
    int hashF = 17;
    int hashT = 17;
    // Here, checking whether align means to see if includeFromFory and includeToFory are the same,
    // and both cannot be false at the same time, otherwise static analysis will not retain this field,
    // so actually both are true.
    bool stillAlign = fields[0].includeFromFory == fields[0].includeToFory;

    for (int i = 0; i < fields.length; ++i){
      if (stillAlign){
        // Here, stillAlign means fields[i] is aligned
        if (i < fields.length - 1){
          stillAlign = fields[i+1].includeFromFory == fields[i+1].includeToFory;
        }
        hashF = _computeFieldHash(hashF, fields[i], getTagByType);
        hashT = hashF;
        continue;
      }
      // Here, stillAlign means fields[i] is unaligned
      if (fields[i].includeFromFory) hashF = _computeFieldHash(hashF, fields[i], getTagByType);
      if (fields[i].includeToFory) hashT = _computeFieldHash(hashT, fields[i], getTagByType);
    }
    return StructHashPair(hashF, hashT);
  }

  int _computeFieldHash(int hash, FieldSpec field, String Function(Type type) getTagByType) {
    late int id;
    String tag;
    ObjType objType = field.typeSpec.objType;
    switch(objType){
      case ObjType.LIST:
        id = ObjType.LIST.id;
        break;
      case ObjType.MAP:
        id = ObjType.MAP.id;
        break;
      case ObjType.UNKNOWN:
        id = 0;
        break;
      default:
        if (objType.isStructType()){
          tag = getTagByType(field.typeSpec.type);
          int tagObjHashCode = identityHashCode(tag);
          int? hashVal = _stringObjToHash[tagObjHashCode];
          if (hashVal != null) {
            id = hashVal;
          }else{
            id = StringUtil.computeUtf8StringHash(tag);
            _stringObjToHash[tagObjHashCode] = id;
          }
        }else {
          id = objType.id.abs();
        }
    }
    int fieldHash = hash * 31 + id;
    while (fieldHash > 0x7FFFFFFF){
      fieldHash ~/= 7;
    }
    return fieldHash;
  }
}
