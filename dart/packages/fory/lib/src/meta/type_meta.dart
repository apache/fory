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

import 'package:fory/src/buffer.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/resolver/type_resolver.dart';

/// Wire-level type metadata for one value.
final class WireTypeMeta {
  final TypeInfo resolvedType;
  final int wireTypeId;
  final bool writesTypeDef;

  const WireTypeMeta({
    required this.resolvedType,
    required this.wireTypeId,
    required this.writesTypeDef,
  });

  bool get writesUserTypeId =>
      wireTypeId == TypeIds.enumById ||
      wireTypeId == TypeIds.struct ||
      wireTypeId == TypeIds.ext ||
      wireTypeId == TypeIds.typedUnion;

  bool get writesNamedType =>
      !writesTypeDef &&
      (wireTypeId == TypeIds.namedEnum ||
          wireTypeId == TypeIds.namedStruct ||
          wireTypeId == TypeIds.namedExt ||
          wireTypeId == TypeIds.namedUnion);
}

final class TypeHeader {
  final int value;

  const TypeHeader(this.value);

  int readMetaSize(Buffer buffer) {
    final lowBits = value & 0xff;
    if (lowBits == 0xff) {
      return 0xff + buffer.readVarUint32Small14();
    }
    return lowBits;
  }

  void skipRemaining(Buffer buffer) {
    buffer.skip(readMetaSize(buffer));
  }
}

final class ParsedTypeMetaCache {
  static const int maxEntries = 8192;

  final LinkedHashMap<int, TypeInfo> _entries = LinkedHashMap<int, TypeInfo>();
  int? _lastHeader;
  TypeInfo? _lastResolved;

  TypeInfo? lookup(TypeHeader header) {
    if (_lastHeader == header.value) {
      return _lastResolved;
    }
    final resolved = _entries[header.value];
    if (resolved != null) {
      _lastHeader = header.value;
      _lastResolved = resolved;
    }
    return resolved;
  }

  void remember(TypeHeader header, TypeInfo resolved) {
    if (!_entries.containsKey(header.value) && _entries.length >= maxEntries) {
      _entries.remove(_entries.keys.first);
    }
    _entries[header.value] = resolved;
    _lastHeader = header.value;
    _lastResolved = resolved;
  }
}

/// Encodes type metadata into the xlang wire format.
final class WireTypeMetaEncoder {
  const WireTypeMetaEncoder();

  WireTypeMeta typeMetaFor(Config config, TypeInfo resolvedType) {
    final wireTypeId = _wireTypeIdFor(config, resolvedType);
    final writesTypeDef = wireTypeId == TypeIds.compatibleStruct ||
        wireTypeId == TypeIds.namedCompatibleStruct ||
        (config.compatible &&
            (wireTypeId == TypeIds.namedEnum ||
                wireTypeId == TypeIds.namedStruct ||
                wireTypeId == TypeIds.namedExt ||
                wireTypeId == TypeIds.namedUnion));
    return WireTypeMeta(
      resolvedType: resolvedType,
      wireTypeId: wireTypeId,
      writesTypeDef: writesTypeDef,
    );
  }

  void write(
    Buffer buffer,
    WireTypeMeta typeMeta, {
    required void Function(WireTypeMeta typeMeta) writeTypeDef,
    required void Function(EncodedMetaString value) writePackageMetaString,
    required void Function(EncodedMetaString value) writeTypeNameMetaString,
  }) {
    buffer.writeVarUint32Small7(typeMeta.wireTypeId);
    if (typeMeta.writesUserTypeId) {
      buffer.writeVarUint32(typeMeta.resolvedType.userTypeId!);
      return;
    }
    if (typeMeta.writesTypeDef) {
      writeTypeDef(typeMeta);
      return;
    }
    if (typeMeta.writesNamedType) {
      writePackageMetaString(typeMeta.resolvedType.encodedNamespace!);
      writeTypeNameMetaString(typeMeta.resolvedType.encodedTypeName!);
    }
  }

  int _wireTypeIdFor(Config config, TypeInfo resolvedType) {
    switch (resolvedType.kind) {
      case RegistrationKind.builtin:
        return resolvedType.typeId;
      case RegistrationKind.enumType:
        return resolvedType.isNamed ? TypeIds.namedEnum : TypeIds.enumById;
      case RegistrationKind.ext:
        return resolvedType.isNamed ? TypeIds.namedExt : TypeIds.ext;
      case RegistrationKind.union:
        if (resolvedType.userTypeId != null) {
          return TypeIds.typedUnion;
        }
        return resolvedType.isNamed ? TypeIds.namedUnion : TypeIds.union;
      case RegistrationKind.struct:
        final compatible = config.compatible && resolvedType.typeDef!.evolving;
        if (compatible) {
          return resolvedType.isNamed
              ? TypeIds.namedCompatibleStruct
              : TypeIds.compatibleStruct;
        }
        return resolvedType.isNamed ? TypeIds.namedStruct : TypeIds.struct;
    }
  }
}

/// Decodes type metadata from the xlang wire format.
final class WireTypeMetaDecoder {
  const WireTypeMetaDecoder();

  WireTypeMeta read(
    Buffer buffer, {
    required Config config,
    required TypeInfo Function(int wireTypeId) resolveBuiltinWireType,
    required TypeInfo Function(int id) resolveUserById,
    required TypeInfo Function(
      int wireTypeId,
      EncodedMetaString namespace,
      EncodedMetaString typeName,
    ) resolveUserByEncodedNameCached,
    required TypeInfo? Function(int wireTypeId) expectedNamedType,
    required WireTypeMeta Function() readTypeDef,
    required EncodedMetaString Function([
      EncodedMetaString? expected,
    ]) readPackageMetaString,
    required EncodedMetaString Function([
      EncodedMetaString? expected,
    ]) readTypeNameMetaString,
  }) {
    final wireTypeId = buffer.readVarUint32Small7();
    if (_isBuiltinWireType(wireTypeId)) {
      return WireTypeMeta(
        resolvedType: resolveBuiltinWireType(wireTypeId),
        wireTypeId: wireTypeId,
        writesTypeDef: false,
      );
    }
    switch (wireTypeId) {
      case TypeIds.enumById:
      case TypeIds.struct:
      case TypeIds.ext:
      case TypeIds.typedUnion:
        return WireTypeMeta(
          resolvedType: resolveUserById(buffer.readVarUint32()),
          wireTypeId: wireTypeId,
          writesTypeDef: false,
        );
      case TypeIds.namedEnum:
      case TypeIds.namedStruct:
      case TypeIds.namedExt:
      case TypeIds.namedUnion:
        if (config.compatible) {
          return readTypeDef();
        }
        final expected = expectedNamedType(wireTypeId);
        final namespace = readPackageMetaString(expected?.encodedNamespace);
        final typeName = readTypeNameMetaString(expected?.encodedTypeName);
        if (expected != null &&
            identical(namespace, expected.encodedNamespace) &&
            identical(typeName, expected.encodedTypeName)) {
          return WireTypeMeta(
            resolvedType: expected,
            wireTypeId: wireTypeId,
            writesTypeDef: false,
          );
        }
        return WireTypeMeta(
          resolvedType: resolveUserByEncodedNameCached(
            wireTypeId,
            namespace,
            typeName,
          ),
          wireTypeId: wireTypeId,
          writesTypeDef: false,
        );
      case TypeIds.compatibleStruct:
      case TypeIds.namedCompatibleStruct:
        return readTypeDef();
      default:
        throw StateError('Unsupported wire type id $wireTypeId.');
    }
  }

  bool _isBuiltinWireType(int wireTypeId) =>
      wireTypeId == TypeIds.boolType ||
      wireTypeId == TypeIds.int8 ||
      wireTypeId == TypeIds.int16 ||
      wireTypeId == TypeIds.int32 ||
      wireTypeId == TypeIds.varInt32 ||
      wireTypeId == TypeIds.int64 ||
      wireTypeId == TypeIds.varInt64 ||
      wireTypeId == TypeIds.taggedInt64 ||
      wireTypeId == TypeIds.uint8 ||
      wireTypeId == TypeIds.uint16 ||
      wireTypeId == TypeIds.uint32 ||
      wireTypeId == TypeIds.varUint32 ||
      wireTypeId == TypeIds.uint64 ||
      wireTypeId == TypeIds.varUint64 ||
      wireTypeId == TypeIds.taggedUint64 ||
      wireTypeId == TypeIds.float16 ||
      wireTypeId == TypeIds.bfloat16 ||
      wireTypeId == TypeIds.float32 ||
      wireTypeId == TypeIds.float64 ||
      wireTypeId == TypeIds.string ||
      wireTypeId == TypeIds.list ||
      wireTypeId == TypeIds.set ||
      wireTypeId == TypeIds.map ||
      wireTypeId == TypeIds.binary ||
      wireTypeId == TypeIds.date ||
      wireTypeId == TypeIds.timestamp ||
      wireTypeId == TypeIds.boolArray ||
      wireTypeId == TypeIds.int8Array ||
      wireTypeId == TypeIds.int16Array ||
      wireTypeId == TypeIds.int32Array ||
      wireTypeId == TypeIds.int64Array ||
      wireTypeId == TypeIds.uint8Array ||
      wireTypeId == TypeIds.uint16Array ||
      wireTypeId == TypeIds.uint32Array ||
      wireTypeId == TypeIds.uint64Array ||
      wireTypeId == TypeIds.float16Array ||
      wireTypeId == TypeIds.bfloat16Array ||
      wireTypeId == TypeIds.float32Array ||
      wireTypeId == TypeIds.float64Array;
}
