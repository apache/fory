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

import 'dart:convert';
import 'dart:typed_data';
import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/util/murmur3hash.dart';
import 'package:fory/src/util/string_util.dart';

class StructHashResolver {
  // singleton
  static final StructHashResolver _instance = StructHashResolver._internal();
  static StructHashResolver get inst => _instance;
  StructHashResolver._internal();

  StructHashPair computeHash(
      List<FieldSpec> fields, String Function(Type type) _) {
    final List<FieldSpec> fromFields = <FieldSpec>[];
    final List<FieldSpec> toFields = <FieldSpec>[];
    for (int i = 0; i < fields.length; ++i) {
      final FieldSpec field = fields[i];
      if (field.includeFromFory) {
        fromFields.add(field);
      }
      if (field.includeToFory) {
        toFields.add(field);
      }
    }
    if (fromFields.length == toFields.length) {
      bool same = true;
      for (int i = 0; i < fromFields.length; ++i) {
        if (!identical(fromFields[i], toFields[i])) {
          same = false;
          break;
        }
      }
      if (same) {
        final int hash = _computeFingerprintHash(fromFields);
        return StructHashPair(hash, hash);
      }
    }
    return StructHashPair(
      _computeFingerprintHash(fromFields),
      _computeFingerprintHash(toFields),
    );
  }

  int _computeFingerprintHash(List<FieldSpec> fields) {
    final List<String> entries =
        List<String>.filled(fields.length, '', growable: false);
    for (int i = 0; i < fields.length; ++i) {
      final FieldSpec field = fields[i];
      final String fieldName =
          StringUtil.lowerCamelToLowerUnderscore(field.name);
      final int typeId = _fingerprintTypeId(field.typeSpec.objType);
      final int trackingRef = field.trackingRef ? 1 : 0;
      final int nullable = field.typeSpec.nullable ? 1 : 0;
      entries[i] = '$fieldName,$typeId,$trackingRef,$nullable;';
    }
    entries.sort();
    final StringBuffer fingerprint = StringBuffer();
    for (int i = 0; i < entries.length; ++i) {
      fingerprint.write(entries[i]);
    }
    final Uint8List bytes =
        Uint8List.fromList(utf8.encode(fingerprint.toString()));
    final int hash64 = Murmur3Hash.hash128x64(bytes, bytes.length, 0, 47).$1;
    return (hash64 & 0xffffffff).toSigned(32);
  }

  int _fingerprintTypeId(ObjType objType) {
    if (objType == ObjType.INT32) {
      return ObjType.VAR_INT32.id;
    }
    if (objType == ObjType.INT64) {
      return ObjType.VAR_INT64.id;
    }
    if (objType == ObjType.UNKNOWN) {
      return ObjType.UNKNOWN.id;
    }
    if (objType == ObjType.LIST ||
        objType == ObjType.SET ||
        objType == ObjType.MAP) {
      return objType.id;
    }
    if (objType == ObjType.ENUM ||
        objType == ObjType.NAMED_ENUM ||
        objType == ObjType.STRUCT ||
        objType == ObjType.COMPATIBLE_STRUCT ||
        objType == ObjType.NAMED_STRUCT ||
        objType == ObjType.NAMED_COMPATIBLE_STRUCT ||
        objType == ObjType.EXT ||
        objType == ObjType.NAMED_EXT ||
        objType == ObjType.UNION ||
        objType == ObjType.TYPED_UNION ||
        objType == ObjType.NAMED_UNION) {
      return ObjType.UNKNOWN.id;
    }
    return objType.id;
  }
}
