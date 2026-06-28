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

import 'package:fory/src/memory/buffer.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/util/hash_util.dart';

/// Type metadata for one serialized value.
final class TypeMeta {
  final TypeInfo resolvedType;
  final int typeId;
  final bool hasTypeDef;

  const TypeMeta({
    required this.resolvedType,
    required this.typeId,
    required this.hasTypeDef,
  });

  bool get hasUserTypeId =>
      typeId == TypeIds.enumById ||
      typeId == TypeIds.struct ||
      typeId == TypeIds.ext ||
      typeId == TypeIds.typedUnion;

  bool get hasEncodedName =>
      !hasTypeDef &&
      (typeId == TypeIds.namedEnum ||
          typeId == TypeIds.namedStruct ||
          typeId == TypeIds.namedExt ||
          typeId == TypeIds.namedUnion);
}

final class TypeHeader {
  static const int _compressMetaFlag = 1 << 8;
  static const int _reservedMetaFlags = 0x0e00;
  static const int _headerLowBitsMask = 0x0fff;
  static const int _hashLow32Mask = 0xfffff000;

  final Int64 value;

  const TypeHeader(this.value);

  @pragma('vm:prefer-inline')
  void validateGlobal() {
    if ((value.low32 & _reservedMetaFlags) != 0) {
      throw StateError('Invalid TypeDef global header.');
    }
    if ((value.low32 & _compressMetaFlag) != 0) {
      throw StateError('Compressed TypeDef metadata is not supported.');
    }
  }

  @pragma('vm:prefer-inline')
  int readMetaSize(Buffer buffer) {
    return readMetaSizeFromHeader(buffer, value);
  }

  @pragma('vm:prefer-inline')
  static int readMetaSizeFromHeader(Buffer buffer, Int64 header) {
    final lowBits = header.low32 & 0xff;
    if (lowBits == 0xff) {
      return 0xff + buffer.readVarUint32Small14();
    }
    return lowBits;
  }

  @pragma('vm:prefer-inline')
  void skipRemaining(Buffer buffer) {
    skipBody(buffer, value);
  }

  @pragma('vm:prefer-inline')
  static void skipBody(Buffer buffer, Int64 header) {
    buffer.skip(readMetaSizeFromHeader(buffer, header));
  }

  @pragma('vm:prefer-inline')
  void validateBodyHash(Uint8List body) {
    final expected = typeDefHeader(
      body,
      headerLowBits: value.low32 & _headerLowBitsMask,
    );
    if (value.high32Unsigned != expected.high32Unsigned ||
        (value.low32 & _hashLow32Mask) != (expected.low32 & _hashLow32Mask)) {
      throw StateError('Invalid TypeDef metadata hash.');
    }
  }
}

final class ParsedTypeMetaCache {
  final LinkedHashMap<Int64, TypeInfo> _entries =
      LinkedHashMap<Int64, TypeInfo>();
  TypeInfo? _cachedTypeInfo;

  @pragma('vm:prefer-inline')
  TypeInfo? lookup(TypeHeader header) {
    // The hot hint is one TypeInfo object. The validated TypeDef header lives
    // on that metadata object; do not add parallel header slots, sentinel
    // fields, accepted-header state, or body/hash/schema checks to this path.
    final cached = _cachedTypeInfo;
    if (cached != null && cached.cachedTypeDefHeader == header.value) {
      return cached;
    }
    final resolved = _entries[header.value];
    if (resolved != null) {
      _cachedTypeInfo = resolved;
    }
    return resolved;
  }

  @pragma('vm:prefer-inline')
  void remember(TypeHeader header, TypeInfo resolved) {
    assert(resolved.cachedTypeDefHeader == header.value);
    _entries[header.value] = resolved;
    _cachedTypeInfo = resolved;
  }
}

/// Decodes type metadata from the xlang wire format.
final class TypeMetaDecoder {
  const TypeMetaDecoder();

  TypeMeta read(
    Buffer buffer, {
    required Config config,
    required TypeInfo Function(int typeId) resolveBuiltinTypeId,
    required TypeInfo Function(int id) resolveUserById,
    required TypeInfo Function(
      int typeId,
      EncodedMetaString namespace,
      EncodedMetaString typeName,
    )
    resolveUserByEncodedNameCached,
    required TypeInfo? Function(int typeId) expectedNamedType,
    required TypeMeta Function() readTypeDef,
    required EncodedMetaString Function([EncodedMetaString? expected])
    readPackageMetaString,
    required EncodedMetaString Function([EncodedMetaString? expected])
    readTypeNameMetaString,
  }) {
    final typeId = buffer.readVarUint32Small7();
    if (_isBuiltinTypeId(typeId)) {
      return TypeMeta(
        resolvedType: resolveBuiltinTypeId(typeId),
        typeId: typeId,
        hasTypeDef: false,
      );
    }
    switch (typeId) {
      case TypeIds.enumById:
      case TypeIds.struct:
      case TypeIds.ext:
      case TypeIds.typedUnion:
        return TypeMeta(
          resolvedType: resolveUserById(buffer.readVarUint32()),
          typeId: typeId,
          hasTypeDef: false,
        );
      case TypeIds.namedEnum:
      case TypeIds.namedStruct:
      case TypeIds.namedExt:
      case TypeIds.namedUnion:
        if (config.compatible) {
          return readTypeDef();
        }
        final expected = expectedNamedType(typeId);
        final namespace = readPackageMetaString(expected?.encodedNamespace);
        final typeName = readTypeNameMetaString(expected?.encodedTypeName);
        if (expected != null &&
            identical(namespace, expected.encodedNamespace) &&
            identical(typeName, expected.encodedTypeName)) {
          return TypeMeta(
            resolvedType: expected,
            typeId: typeId,
            hasTypeDef: false,
          );
        }
        return TypeMeta(
          resolvedType: resolveUserByEncodedNameCached(
            typeId,
            namespace,
            typeName,
          ),
          typeId: typeId,
          hasTypeDef: false,
        );
      case TypeIds.compatibleStruct:
      case TypeIds.namedCompatibleStruct:
        return readTypeDef();
      default:
        throw StateError('Unsupported type id $typeId.');
    }
  }

  bool _isBuiltinTypeId(int typeId) =>
      typeId == TypeIds.boolType ||
      typeId == TypeIds.int8 ||
      typeId == TypeIds.int16 ||
      typeId == TypeIds.int32 ||
      typeId == TypeIds.varInt32 ||
      typeId == TypeIds.int64 ||
      typeId == TypeIds.varInt64 ||
      typeId == TypeIds.taggedInt64 ||
      typeId == TypeIds.uint8 ||
      typeId == TypeIds.uint16 ||
      typeId == TypeIds.uint32 ||
      typeId == TypeIds.varUint32 ||
      typeId == TypeIds.uint64 ||
      typeId == TypeIds.varUint64 ||
      typeId == TypeIds.taggedUint64 ||
      typeId == TypeIds.float16 ||
      typeId == TypeIds.bfloat16 ||
      typeId == TypeIds.float32 ||
      typeId == TypeIds.float64 ||
      typeId == TypeIds.string ||
      typeId == TypeIds.list ||
      typeId == TypeIds.set ||
      typeId == TypeIds.map ||
      typeId == TypeIds.none ||
      typeId == TypeIds.binary ||
      typeId == TypeIds.duration ||
      typeId == TypeIds.decimal ||
      typeId == TypeIds.date ||
      typeId == TypeIds.timestamp ||
      typeId == TypeIds.boolArray ||
      typeId == TypeIds.int8Array ||
      typeId == TypeIds.int16Array ||
      typeId == TypeIds.int32Array ||
      typeId == TypeIds.int64Array ||
      typeId == TypeIds.uint8Array ||
      typeId == TypeIds.uint16Array ||
      typeId == TypeIds.uint32Array ||
      typeId == TypeIds.uint64Array ||
      typeId == TypeIds.float16Array ||
      typeId == TypeIds.bfloat16Array ||
      typeId == TypeIds.float32Array ||
      typeId == TypeIds.float64Array;
}
