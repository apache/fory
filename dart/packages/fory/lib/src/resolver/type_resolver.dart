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
import 'package:fory/src/codegen/generated_registry.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/meta_string_writer.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/meta/type_ids.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/meta/type_meta.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/enum_serializer.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/serializer/primitive_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/struct_serializer.dart';
import 'package:fory/src/serializer/time_serializers.dart';
import 'package:fory/src/serializer/typed_array_serializers.dart';
import 'package:fory/src/serializer/union_serializer.dart';
import 'package:fory/src/types/bfloat16.dart';
import 'package:fory/src/types/bool_list.dart';
import 'package:fory/src/types/decimal.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/float32.dart';
import 'package:fory/src/types/int64.dart';
import 'package:fory/src/types/local_date.dart';
import 'package:fory/src/types/timestamp.dart';
import 'package:fory/src/types/uint64.dart';
import 'package:fory/src/util/hash_util.dart';

enum RegistrationKind { builtin, struct, enumType, ext, union }

bool _isStructTypeDefKind(int typeId) =>
    typeId == TypeIds.struct ||
    typeId == TypeIds.compatibleStruct ||
    typeId == TypeIds.namedStruct ||
    typeId == TypeIds.namedCompatibleStruct;

bool _isNamedTypeDefKind(int typeId) =>
    typeId == TypeIds.namedStruct ||
    typeId == TypeIds.namedCompatibleStruct ||
    typeId == TypeIds.namedEnum ||
    typeId == TypeIds.namedExt ||
    typeId == TypeIds.namedUnion;

int _nonStructTypeDefKindCode(int typeId) {
  switch (typeId) {
    case TypeIds.enumById:
      return 0;
    case TypeIds.namedEnum:
      return 1;
    case TypeIds.ext:
      return 2;
    case TypeIds.namedExt:
      return 3;
    case TypeIds.typedUnion:
      return 4;
    case TypeIds.namedUnion:
      return 5;
    default:
      throw StateError('Unsupported TypeDef kind $typeId.');
  }
}

int _nonStructTypeDefTypeId(int kindCode) {
  switch (kindCode) {
    case 0:
      return TypeIds.enumById;
    case 1:
      return TypeIds.namedEnum;
    case 2:
      return TypeIds.ext;
    case 3:
      return TypeIds.namedExt;
    case 4:
      return TypeIds.typedUnion;
    case 5:
      return TypeIds.namedUnion;
    default:
      throw StateError('Unsupported TypeDef kind code $kindCode.');
  }
}

final class TypeInfo {
  final Type type;
  final RegistrationKind kind;
  final int typeId;
  final bool supportsRef;
  final bool needsRootRef;
  final bool usesNestedTypeDefinitions;
  final bool evolving;
  final List<FieldInfo> fields;
  final Serializer<Object?> serializer;
  StructSerializer? structSerializer;
  final int? userTypeId;
  final String? namespace;
  final String? typeName;
  final EncodedMetaString? encodedNamespace;
  final EncodedMetaString? encodedTypeName;
  TypeDef? typeDef;
  final TypeDef? remoteTypeDef;

  TypeInfo({
    required this.type,
    required this.kind,
    required this.typeId,
    required this.supportsRef,
    required this.needsRootRef,
    required this.usesNestedTypeDefinitions,
    required this.evolving,
    required this.fields,
    required this.serializer,
    required this.structSerializer,
    required this.userTypeId,
    required this.namespace,
    required this.typeName,
    required this.encodedNamespace,
    required this.encodedTypeName,
    required this.typeDef,
    required this.remoteTypeDef,
  });

  bool get isNamed =>
      userTypeId == null && namespace != null && typeName != null;

  bool get isCompatibleStruct => kind == RegistrationKind.struct && evolving;

  bool get isBasicValue => TypeIds.isBasicValue(typeId);

  Int64 get cachedTypeDefHeader => remoteTypeDef?.header ?? typeDef!.header;
}

bool usesDeclaredTypeInfo(
  bool compatible,
  FieldType fieldType,
  TypeInfo resolved,
) {
  if (fieldType.isDynamic) {
    return false;
  }
  if (!compatible) {
    return true;
  }
  switch (resolved.kind) {
    case RegistrationKind.builtin:
    case RegistrationKind.enumType:
    case RegistrationKind.union:
      return true;
    case RegistrationKind.struct:
    case RegistrationKind.ext:
      return false;
  }
}

bool _fieldsNeedRootRef(List<FieldInfo> fields) {
  for (final field in fields) {
    if (_fieldTypeNeedsRootRef(field.fieldType)) {
      return true;
    }
  }
  return false;
}

bool _fieldTypeNeedsRootRef(FieldType fieldType) {
  if (fieldType.ref) {
    return true;
  }
  for (final argument in fieldType.arguments) {
    if (_fieldTypeNeedsRootRef(argument)) {
      return true;
    }
  }
  return false;
}

bool _fieldsUseNestedTypeDefinitions(List<FieldInfo> fields) {
  for (final field in fields) {
    if (_fieldTypeUsesNestedTypeDefinitions(field.fieldType)) {
      return true;
    }
  }
  return false;
}

bool _fieldTypeUsesNestedTypeDefinitions(FieldType fieldType) {
  if (fieldType.isDynamic || TypeIds.isUserType(fieldType.typeId)) {
    return true;
  }
  for (final argument in fieldType.arguments) {
    if (_fieldTypeUsesNestedTypeDefinitions(argument)) {
      return true;
    }
  }
  return false;
}

void _validateFieldInfo(FieldInfo field) {
  final id = field.id;
  if (id != null && id < 0) {
    throw ArgumentError('Field id for ${field.name} must be non-negative.');
  }
}

List<FieldInfo> _validateFieldInfos(Iterable<FieldInfo> fields) {
  final validated = <FieldInfo>[];
  final usedIds = <int, String>{};
  for (final field in fields) {
    _validateFieldInfo(field);
    final id = field.id;
    if (id != null) {
      final existing = usedIds[id];
      if (existing != null && existing != field.name) {
        throw ArgumentError(
          'Duplicate field id $id for fields $existing and ${field.name}.',
        );
      }
      usedIds[id] = field.name;
    }
    validated.add(field);
  }
  return List<FieldInfo>.unmodifiable(validated);
}

final class TypeResolver {
  static const int _minRemoteTypeMetaLimit = 8192;

  final Config config;
  final TypeMetaDecoder _typeMetaDecoder = const TypeMetaDecoder();
  final ParsedTypeMetaCache _parsedTypeMetaCache = ParsedTypeMetaCache();
  final List<TypeInfo?> _lastNamedTypeById = List<TypeInfo?>.filled(64, null);
  final Map<_BuiltinKey, TypeInfo> _builtinByKey = <_BuiltinKey, TypeInfo>{};
  final List<_NamedTypeReadCacheEntry?> _namedTypeLookupCache =
      List<_NamedTypeReadCacheEntry?>.filled(128, null);
  final Map<Type, TypeInfo> _runtimeTypeValueCache = <Type, TypeInfo>{};
  final Map<Type, TypeInfo> _registeredByType = <Type, TypeInfo>{};
  final Map<int, TypeInfo> _registeredById = <int, TypeInfo>{};
  final Map<String, TypeInfo> _registeredByName = <String, TypeInfo>{};
  final Map<EncodedMetaString, Map<EncodedMetaString, TypeInfo>>
  _registeredByEncodedName =
      LinkedHashMap<
        EncodedMetaString,
        Map<EncodedMetaString, TypeInfo>
      >.identity();
  final Map<String, EncodedMetaString> _packageMetaStrings =
      <String, EncodedMetaString>{};
  final Map<String, EncodedMetaString> _typeNameMetaStrings =
      <String, EncodedMetaString>{};
  final Map<String, EncodedMetaString> _fieldNameMetaStrings =
      <String, EncodedMetaString>{};
  final Map<_EncodedMetaStringKey, EncodedMetaString>
  _internedEncodedMetaStrings = <_EncodedMetaStringKey, EncodedMetaString>{};
  final Map<String, int> _remoteSchemaVersionsByType = <String, int>{};
  int _totalAcceptedSchemaVersions = 0;

  TypeResolver(this.config);

  void registerGenerated(
    Type type, {
    int? id,
    String? namespace,
    String? typeName,
  }) {
    final entry = GeneratedTypeCatalog.lookup(type);
    if (entry == null) {
      throw StateError(
        'Type $type has no generated type metadata. '
        'Register it through its generated Fory module first.',
      );
    }
    _registerResolvedSerializer(
      type,
      entry.serializerFactory(),
      switch (entry.kind) {
        GeneratedTypeKind.enumType => RegistrationKind.enumType,
        GeneratedTypeKind.struct => RegistrationKind.struct,
      },
      evolving: entry.evolving,
      fields: entry.fields,
      needsRootRef: entry.needsRootRef,
      usesNestedTypeDefinitions: entry.usesNestedTypeDefinitions,
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
  }

  void registerSerializer(
    Type type,
    Serializer serializer, {
    int? id,
    String? namespace,
    String? typeName,
  }) {
    final registrationKind = _inferKind(serializer);
    _registerResolvedSerializer(
      type,
      serializer as Serializer<Object?>,
      registrationKind,
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
  }

  void _registerResolvedSerializer(
    Type type,
    Serializer<Object?> payloadSerializer,
    RegistrationKind registrationKind, {
    bool evolving = true,
    bool? needsRootRef,
    bool? usesNestedTypeDefinitions,
    List<FieldInfo> fields = const <FieldInfo>[],
    int? id,
    String? namespace,
    String? typeName,
  }) {
    final name = _resolveRegistrationName(
      id: id,
      namespace: namespace,
      typeName: typeName,
    );
    final resolvedNamespace = name.namespace;
    final resolvedTypeName = name.typeName;
    final encodedNamespace =
        resolvedNamespace == null ? null : packageMetaString(resolvedNamespace);
    final encodedTypeName =
        resolvedTypeName == null ? null : typeNameMetaString(resolvedTypeName);
    final normalizedFields =
        registrationKind == RegistrationKind.struct
            ? _validateFieldInfos(fields)
            : const <FieldInfo>[];
    final resolved = TypeInfo(
      type: type,
      kind: registrationKind,
      typeId: _defaultTypeIdForType(type),
      supportsRef: payloadSerializer.supportsRef,
      needsRootRef:
          registrationKind == RegistrationKind.struct
              ? needsRootRef ?? _fieldsNeedRootRef(normalizedFields)
              : payloadSerializer.supportsRef,
      usesNestedTypeDefinitions:
          registrationKind == RegistrationKind.struct
              ? usesNestedTypeDefinitions ??
                  _fieldsUseNestedTypeDefinitions(normalizedFields)
              : true,
      evolving: registrationKind == RegistrationKind.struct ? evolving : false,
      fields: normalizedFields,
      serializer: payloadSerializer,
      structSerializer: null,
      userTypeId: id,
      namespace: resolvedNamespace,
      typeName: resolvedTypeName,
      encodedNamespace: encodedNamespace,
      encodedTypeName: encodedTypeName,
      typeDef: null,
      remoteTypeDef: null,
    );
    _rememberResolved(
      type,
      resolved,
      id: id,
      namespace: resolvedNamespace,
      typeName: resolvedTypeName,
    );
    _rebuildRegisteredTypeDefs();
  }

  void _rebuildRegisteredTypeDefs() {
    // Registration is two-stage: first record every TypeInfo, then rebuild
    // local TypeDefs from the complete current registry. A later enum/ext/union
    // registration can change a struct field's wire kind, so rebuild all
    // registered TypeDefs on the registration cold path instead of constructing
    // them lazily from read/write hot paths.
    final seen = Set<TypeInfo>.identity();
    for (final resolved in _registeredByType.values) {
      if (!seen.add(resolved)) {
        continue;
      }
      final typeDef = _buildTypeDef(
        kind: resolved.kind,
        evolving: resolved.evolving,
        userTypeId: resolved.userTypeId,
        encodedNamespace: resolved.encodedNamespace,
        encodedTypeName: resolved.encodedTypeName,
        fields: resolved.fields,
      );
      resolved.typeDef = typeDef;
      if (resolved.kind == RegistrationKind.struct) {
        resolved.structSerializer = StructSerializer(
          resolved.serializer,
          typeDef,
          this,
        );
      }
    }
  }

  EncodedMetaString packageMetaString(String value) {
    return _packageMetaStrings.putIfAbsent(
      value,
      () => _canonicalMetaString(encodePackageMetaString(value)),
    );
  }

  EncodedMetaString typeNameMetaString(String value) {
    return _typeNameMetaStrings.putIfAbsent(
      value,
      () => _canonicalMetaString(encodeTypeNameMetaString(value)),
    );
  }

  EncodedMetaString fieldNameMetaString(String value) {
    return _fieldNameMetaStrings.putIfAbsent(
      value,
      () => _canonicalMetaString(encodeFieldNameMetaString(value)),
    );
  }

  EncodedMetaString internEncodedMetaString(
    Uint8List bytes, {
    required int encoding,
  }) {
    if (bytes.isEmpty) {
      return EncodedMetaString.empty;
    }
    final key = _EncodedMetaStringKey(encoding, bytes);
    final existing = _internedEncodedMetaStrings[key];
    if (existing != null) {
      return existing;
    }
    final encoded = EncodedMetaString(bytes, encoding);
    _internedEncodedMetaStrings[key] = encoded;
    return encoded;
  }

  TypeInfo resolveValue(Object value) {
    final runtimeType = value.runtimeType;
    final cached = _runtimeTypeValueCache[runtimeType];
    if (cached != null) {
      return cached;
    }
    final resolved = _resolveValueSlow(value, runtimeType);
    _runtimeTypeValueCache[runtimeType] = resolved;
    return resolved;
  }

  TypeInfo _resolveValueSlow(Object value, Type runtimeType) {
    final registered = _registeredByType[runtimeType];
    if (registered != null) {
      return registered;
    }
    if (value is bool) {
      return _builtin(bool, TypeIds.boolType);
    }
    // Native Int64 is represented as `int`; web still needs this branch to
    // keep explicit wrapper values on the Int64 API while using varint64 wire
    // format by default, the same as plain Dart int.
    // ignore: unnecessary_type_check
    if (value is Int64 && value is! int) {
      return _builtin(Int64, TypeIds.varInt64);
    }
    if (value is int) {
      return _builtin(int, TypeIds.varInt64);
    }
    if (value is Uint64) {
      return _builtin(Uint64, TypeIds.varUint64);
    }
    if (value is Float32) {
      return _builtin(Float32, TypeIds.float32);
    }
    if (value is double) {
      return _builtin(double, TypeIds.float64);
    }
    if (value is String) {
      return _builtin(String, TypeIds.string);
    }
    if (value is Decimal) {
      return _builtin(Decimal, TypeIds.decimal);
    }
    if (value is Uint8List) {
      return _builtin(Uint8List, TypeIds.binary);
    }
    if (value is BoolList && value.preservesArraySchema) {
      return _builtin(BoolList, TypeIds.boolArray);
    }
    if (value is Int8List) {
      return _builtin(Int8List, TypeIds.int8Array);
    }
    if (value is Int16List) {
      return _builtin(Int16List, TypeIds.int16Array);
    }
    if (value is Int32List) {
      return _builtin(Int32List, TypeIds.int32Array);
    }
    if (value is Int64List) {
      return _builtin(Int64List, TypeIds.int64Array);
    }
    if (value is Uint16List) {
      return _builtin(Uint16List, TypeIds.uint16Array);
    }
    if (value is Uint32List) {
      return _builtin(Uint32List, TypeIds.uint32Array);
    }
    if (value is Uint64List) {
      return _builtin(Uint64List, TypeIds.uint64Array);
    }
    if (value is Float16List) {
      return _builtin(Float16List, TypeIds.float16Array);
    }
    if (value is Bfloat16List) {
      return _builtin(Bfloat16List, TypeIds.bfloat16Array);
    }
    if (value is Float32List) {
      return _builtin(Float32List, TypeIds.float32Array);
    }
    if (value is Float64List) {
      return _builtin(Float64List, TypeIds.float64Array);
    }
    if (value is List) {
      return _builtin(List, TypeIds.list);
    }
    if (value is Set) {
      return _builtin(Set, TypeIds.set);
    }
    if (value is Map) {
      return _builtin(Map, TypeIds.map);
    }
    if (value is LocalDate) {
      return _builtin(LocalDate, TypeIds.date);
    }
    if (value is Duration) {
      return _builtin(Duration, TypeIds.duration);
    }
    if (value is Timestamp) {
      return _builtin(Timestamp, TypeIds.timestamp);
    }
    if (value is DateTime) {
      return _builtin(DateTime, TypeIds.timestamp);
    }
    throw StateError(
      'Type $runtimeType is not registered. Register generated types with '
      'their generated Fory module, or register a serializer explicitly.',
    );
  }

  TypeInfo? tryResolveFieldType(FieldType fieldType) {
    switch (fieldType.typeId) {
      case TypeIds.boolType:
      case TypeIds.int8:
      case TypeIds.int16:
      case TypeIds.int32:
      case TypeIds.varInt32:
      case TypeIds.int64:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.uint8:
      case TypeIds.uint16:
      case TypeIds.uint32:
      case TypeIds.varUint32:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.bfloat16:
      case TypeIds.float16:
      case TypeIds.float32:
      case TypeIds.float64:
      case TypeIds.string:
      case TypeIds.list:
      case TypeIds.set:
      case TypeIds.map:
      case TypeIds.none:
      case TypeIds.binary:
      case TypeIds.duration:
      case TypeIds.timestamp:
      case TypeIds.date:
      case TypeIds.decimal:
      case TypeIds.boolArray:
      case TypeIds.int8Array:
      case TypeIds.int16Array:
      case TypeIds.int32Array:
      case TypeIds.int64Array:
      case TypeIds.uint8Array:
      case TypeIds.uint16Array:
      case TypeIds.uint32Array:
      case TypeIds.uint64Array:
      case TypeIds.float16Array:
      case TypeIds.bfloat16Array:
      case TypeIds.float32Array:
      case TypeIds.float64Array:
        return _builtin(_builtinTypeForFieldType(fieldType), fieldType.typeId);
      default:
        return _registeredByType[fieldType.type];
    }
  }

  TypeInfo resolveFieldType(FieldType fieldType) {
    final resolved = tryResolveFieldType(fieldType);
    if (resolved == null) {
      throw StateError('Type ${fieldType.type} is not registered.');
    }
    return resolved;
  }

  TypeInfo resolveUserById(int id) {
    final resolved = _registeredById[id];
    if (resolved == null) {
      throw StateError('Unknown registered type id $id.');
    }
    return resolved;
  }

  TypeInfo resolveUserByName(String namespace, String typeName) {
    final resolved = _registeredByName[_nameKey(namespace, typeName)];
    if (resolved == null) {
      throw StateError('Unknown named type $namespace.$typeName.');
    }
    return resolved;
  }

  TypeInfo resolvedRegisteredType(Type type) {
    final resolved = _registeredByType[type];
    if (resolved == null) {
      throw StateError('Type $type is not registered.');
    }
    return resolved;
  }

  TypeInfo? expectedRootType<T>() => _registeredByType[T];

  TypeInfo resolveUserByEncodedName(
    EncodedMetaString namespace,
    EncodedMetaString typeName,
  ) {
    final resolved = _registeredByEncodedName[namespace]?[typeName];
    if (resolved == null) {
      throw StateError(
        'Unknown named type ${decodePackageMetaString(namespace.bytes, namespace.encoding)}.'
        '${decodeTypeNameMetaString(typeName.bytes, typeName.encoding)}.',
      );
    }
    return resolved;
  }

  TypeInfo resolveUserByEncodedNameCached(
    int typeId,
    EncodedMetaString namespace,
    EncodedMetaString typeName,
  ) {
    final slot =
        _namedTypeLookupCacheIndex(typeId, namespace, typeName) &
        (_namedTypeLookupCache.length - 1);
    final cached = _namedTypeLookupCache[slot];
    if (cached != null &&
        cached.typeId == typeId &&
        identical(cached.namespace, namespace) &&
        identical(cached.typeName, typeName)) {
      return cached.resolved;
    }
    final resolved = resolveUserByEncodedName(namespace, typeName);
    _namedTypeLookupCache[slot] = _NamedTypeReadCacheEntry(
      typeId,
      namespace,
      typeName,
      resolved,
    );
    if (typeId < _lastNamedTypeById.length) {
      _lastNamedTypeById[typeId] = resolved;
    }
    return resolved;
  }

  SerializationFieldInfo serializationFieldInfo(
    FieldInfo field, {
    required int index,
  }) {
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return SerializationFieldInfo(
        field: field,
        index: index,
        declaredTypeInfo: null,
        usesDeclaredType: false,
      );
    }
    final declaredTypeInfo = tryResolveFieldType(fieldType);
    return SerializationFieldInfo(
      field: field,
      index: index,
      declaredTypeInfo: declaredTypeInfo,
      usesDeclaredType:
          declaredTypeInfo != null
              ? usesDeclaredTypeInfo(
                config.compatible,
                fieldType,
                declaredTypeInfo,
              )
              : false,
    );
  }

  TypeMeta typeMetaForResolved(TypeInfo resolved) {
    final typeId = _typeIdFor(resolved);
    return TypeMeta(
      resolvedType: resolved,
      typeId: typeId,
      hasTypeDef: _hasTypeDef(typeId),
    );
  }

  TypeDef typeDefForResolved(TypeInfo resolved, {List<FieldInfo>? fields}) {
    final localTypeDef = resolved.typeDef;
    if (localTypeDef == null) {
      throw StateError('Type ${resolved.type} does not have TypeDef metadata.');
    }
    if (fields == null || identical(fields, localTypeDef.fields)) {
      return localTypeDef;
    }
    return _buildTypeDef(
      kind: resolved.kind,
      evolving: resolved.evolving,
      userTypeId: resolved.userTypeId,
      encodedNamespace: resolved.encodedNamespace,
      encodedTypeName: resolved.encodedTypeName,
      fields: fields,
    );
  }

  void writeTypeMeta(
    Buffer buffer,
    TypeInfo resolved, {
    required LinkedHashMap<TypeDef, int> typeDefIds,
    required MetaStringWriter metaStringWriter,
  }) {
    final typeId = _typeIdFor(resolved);
    if (_hasTypeDef(typeId)) {
      buffer.writeVarUint32Small7(typeId);
      _writeTypeDef(buffer, resolved.typeDef!, typeDefIds: typeDefIds);
      return;
    }
    if (_hasUserTypeId(typeId)) {
      buffer.writeVarUint32Small7(typeId);
      buffer.writeVarUint32(resolved.userTypeId!);
      return;
    }
    buffer.writeVarUint32Small7(typeId);
    if (_hasEncodedName(typeId)) {
      metaStringWriter.writeMetaString(buffer, resolved.encodedNamespace!);
      metaStringWriter.writeMetaString(buffer, resolved.encodedTypeName!);
    }
  }

  @pragma('vm:prefer-inline')
  bool _hasUserTypeId(int typeId) =>
      typeId == TypeIds.enumById ||
      typeId == TypeIds.struct ||
      typeId == TypeIds.ext ||
      typeId == TypeIds.typedUnion;

  @pragma('vm:prefer-inline')
  bool _hasTypeDef(int typeId) =>
      typeId == TypeIds.compatibleStruct ||
      typeId == TypeIds.namedCompatibleStruct ||
      (config.compatible &&
          (typeId == TypeIds.namedEnum ||
              typeId == TypeIds.namedStruct ||
              typeId == TypeIds.namedExt ||
              typeId == TypeIds.namedUnion));

  @pragma('vm:prefer-inline')
  bool _hasEncodedName(int typeId) =>
      typeId == TypeIds.namedEnum ||
      typeId == TypeIds.namedStruct ||
      typeId == TypeIds.namedExt ||
      typeId == TypeIds.namedUnion;

  @pragma('vm:prefer-inline')
  int _typeIdFor(TypeInfo resolved) {
    switch (resolved.kind) {
      case RegistrationKind.builtin:
        return resolved.typeId;
      case RegistrationKind.enumType:
        return resolved.isNamed ? TypeIds.namedEnum : TypeIds.enumById;
      case RegistrationKind.ext:
        return resolved.isNamed ? TypeIds.namedExt : TypeIds.ext;
      case RegistrationKind.union:
        if (resolved.userTypeId != null) {
          return TypeIds.typedUnion;
        }
        return resolved.isNamed ? TypeIds.namedUnion : TypeIds.union;
      case RegistrationKind.struct:
        final compatibleStruct = config.compatible && resolved.evolving;
        if (compatibleStruct) {
          return resolved.isNamed
              ? TypeIds.namedCompatibleStruct
              : TypeIds.compatibleStruct;
        }
        return resolved.isNamed ? TypeIds.namedStruct : TypeIds.struct;
    }
  }

  @pragma('vm:prefer-inline')
  TypeInfo readTypeMeta(
    Buffer buffer, {
    TypeInfo? expectedNamedType,
    required List<TypeInfo> sharedTypes,
    required MetaStringReader metaStringReader,
  }) {
    final expected = expectedNamedType;
    if (expected != null && _hasTypeDef(_typeIdFor(expected))) {
      final resolved = readExpectedTypeDefMeta(
        buffer,
        expected,
        sharedTypes: sharedTypes,
      );
      if (resolved != null) {
        return resolved;
      }
    }
    final typeMeta = _typeMetaDecoder.read(
      buffer,
      config: config,
      resolveBuiltinTypeId: resolveBuiltinTypeId,
      resolveUserById: resolveUserById,
      resolveUserByEncodedNameCached: resolveUserByEncodedNameCached,
      expectedNamedType: (typeId) {
        final expected = expectedNamedType;
        if (expected != null && _matchesNamedTypeId(expected, typeId)) {
          return expected;
        }
        return typeId < _lastNamedTypeById.length
            ? _lastNamedTypeById[typeId]
            : null;
      },
      readTypeDef: () => _readTypeDef(buffer, sharedTypes: sharedTypes),
      readPackageMetaString:
          ([expected]) => metaStringReader.readMetaString(buffer, expected),
      readTypeNameMetaString:
          ([expected]) => metaStringReader.readMetaString(buffer, expected),
    );
    if (typeMeta.hasEncodedName) {
      _rememberNamedType(typeMeta.typeId, typeMeta.resolvedType);
    }
    return typeMeta.resolvedType;
  }

  TypeInfo? readExpectedTypeDefMeta(
    Buffer buffer,
    TypeInfo expected, {
    required List<TypeInfo> sharedTypes,
  }) {
    final start = bufferReaderIndex(buffer);
    final typeId = buffer.readVarUint32Small7();
    if (typeId != _typeIdFor(expected) || !_hasTypeDef(typeId)) {
      bufferSetReaderIndex(buffer, start);
      return null;
    }
    final marker = buffer.readVarUint32Small14();
    if ((marker & 1) == 1) {
      return sharedTypes[marker >>> 1];
    }
    final headerValue = buffer.readInt64();
    final expectedTypeDef = expected.typeDef;
    if (expectedTypeDef != null && expectedTypeDef.header == headerValue) {
      // The expected local TypeInfo owns this TypeDef header. This is a direct
      // local-schema hit: skip the remote body, do not rehash, and do not
      // publish to ParsedTypeMetaCache because no remote metadata miss occurred.
      TypeHeader.skipBody(buffer, headerValue);
      sharedTypes.add(expected);
      return expected;
    }
    final header = TypeHeader(headerValue);
    final cached = _parsedTypeMetaCache.lookup(header);
    if (cached != null) {
      // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
      // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
      // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
      header.skipRemaining(buffer);
      sharedTypes.add(cached);
      return cached;
    }
    final resolved = _readTypeDefWithHeader(buffer, header);
    sharedTypes.add(resolved);
    return resolved;
  }

  void _writeTypeDef(
    Buffer buffer,
    TypeDef typeDef, {
    required LinkedHashMap<TypeDef, int> typeDefIds,
  }) {
    final index = typeDefIds[typeDef];
    if (index != null) {
      buffer.writeVarUint32((index << 1) | 1);
      return;
    }
    final newIndex = typeDefIds.length;
    typeDefIds[typeDef] = newIndex;
    buffer.writeVarUint32(newIndex << 1);
    buffer.writeBytes(typeDef.encoded);
  }

  TypeDef _buildTypeDef({
    required RegistrationKind kind,
    required bool evolving,
    required int? userTypeId,
    required EncodedMetaString? encodedNamespace,
    required EncodedMetaString? encodedTypeName,
    required List<FieldInfo> fields,
  }) {
    final encoded = _encodeTypeDef(
      kind: kind,
      evolving: evolving,
      userTypeId: userTypeId,
      encodedNamespace: encodedNamespace,
      encodedTypeName: encodedTypeName,
      fields: fields,
    );
    final header = Buffer.wrap(encoded).readInt64();
    return TypeDef(
      evolving: evolving,
      fields: fields,
      header: header,
      encoded: encoded,
    );
  }

  Uint8List _encodeTypeDef({
    required RegistrationKind kind,
    required bool evolving,
    required int? userTypeId,
    required EncodedMetaString? encodedNamespace,
    required EncodedMetaString? encodedTypeName,
    required List<FieldInfo> fields,
  }) {
    final metaBuffer = Buffer();
    final byName =
        userTypeId == null &&
        encodedNamespace != null &&
        encodedTypeName != null;
    final typeId = _typeDefTypeId(kind, byName: byName, evolving: evolving);
    if (!_isStructTypeDefKind(typeId) && fields.isNotEmpty) {
      throw StateError(
        'Non-struct TypeDef $typeId cannot carry field metadata.',
      );
    }
    var classHeader = 0;
    metaBuffer.writeByte(0xff);
    if (_isStructTypeDefKind(typeId)) {
      final inlineFieldCount =
          fields.length >= typeDefSmallFieldCountThreshold
              ? typeDefSmallFieldCountThreshold
              : fields.length;
      classHeader = typeDefStructFlag | inlineFieldCount;
      if (typeId == TypeIds.compatibleStruct ||
          typeId == TypeIds.namedCompatibleStruct) {
        classHeader |= typeDefCompatibleFlag;
      }
      if (fields.length >= typeDefSmallFieldCountThreshold) {
        metaBuffer.writeVarUint32Small7(
          fields.length - typeDefSmallFieldCountThreshold,
        );
      }
      if (byName) {
        classHeader |= typeDefRegisterByNameFlag;
      }
    } else {
      classHeader = _nonStructTypeDefKindCode(typeId);
    }
    if (byName) {
      _writeTypeDefName(
        metaBuffer,
        encodedNamespace.bytes,
        encoding: packageNameCompactEncoding(encodedNamespace.encoding),
      );
      _writeTypeDefName(
        metaBuffer,
        encodedTypeName.bytes,
        encoding: typeNameCompactEncoding(encodedTypeName.encoding),
      );
    } else {
      metaBuffer.writeVarUint32(userTypeId!);
    }
    metaBuffer.toBytes()[0] = classHeader;
    if (_isStructTypeDefKind(typeId)) {
      for (final field in fields) {
        _writeTypeDefField(metaBuffer, field);
      }
    }
    final body = metaBuffer.toBytes();
    final buffer = Buffer();
    buffer.writeInt64(typeDefHeader(body));
    if (body.length >= 0xff) {
      buffer.writeVarUint32(body.length - 0xff);
    }
    buffer.writeBytes(body);
    return buffer.toBytes();
  }

  void _writeTypeDefField(Buffer target, FieldInfo field) {
    final fieldType = field.fieldType;
    final usesTag = field.id != null;
    final encodedName = usesTag ? null : fieldNameMetaString(field.identifier);
    var size = usesTag ? field.id! : encodedName!.bytes.length - 1;
    var header = fieldType.ref ? 1 : 0;
    if (fieldType.nullable) {
      header |= 1 << 1;
    }
    header |=
        ((size >= typeDefBigFieldNameThreshold
                ? typeDefBigFieldNameThreshold
                : size) <<
            2);
    header |=
        ((usesTag ? 3 : fieldNameCompactEncoding(encodedName!.encoding)) << 6);
    target.writeByte(header);
    if (size >= typeDefBigFieldNameThreshold) {
      target.writeVarUint32Small7(size - typeDefBigFieldNameThreshold);
    }
    _writeTypeDefFieldType(target, fieldType, writeFlags: false);
    if (!usesTag) {
      target.writeBytes(encodedName!.bytes);
    }
  }

  void _writeTypeDefFieldType(
    Buffer target,
    FieldType fieldType, {
    required bool writeFlags,
  }) {
    final typeId = _typeDefFieldTypeId(fieldType);
    if (writeFlags) {
      var encoded = typeId << 2;
      if (fieldType.nullable) {
        encoded |= 1 << 1;
      }
      if (fieldType.ref) {
        encoded |= 1;
      }
      target.writeVarUint32Small7(encoded);
    } else {
      target.writeUint8(typeId);
    }
    if (typeId == TypeIds.list || typeId == TypeIds.set) {
      _writeTypeDefFieldType(
        target,
        fieldType.arguments.single,
        writeFlags: true,
      );
    } else if (typeId == TypeIds.map) {
      _writeTypeDefFieldType(target, fieldType.arguments[0], writeFlags: true);
      _writeTypeDefFieldType(target, fieldType.arguments[1], writeFlags: true);
    }
  }

  void _writeTypeDefName(
    Buffer target,
    List<int> bytes, {
    required int encoding,
  }) {
    if (bytes.length >= typeDefBigNameThreshold) {
      target.writeByte((typeDefBigNameThreshold << 2) | encoding);
      target.writeVarUint32Small7(bytes.length - typeDefBigNameThreshold);
    } else {
      target.writeByte((bytes.length << 2) | encoding);
    }
    target.writeBytes(bytes);
  }

  int _typeDefTypeId(
    RegistrationKind kind, {
    required bool byName,
    required bool evolving,
  }) {
    switch (kind) {
      case RegistrationKind.struct:
        if (byName) {
          return evolving ? TypeIds.namedCompatibleStruct : TypeIds.namedStruct;
        }
        return evolving ? TypeIds.compatibleStruct : TypeIds.struct;
      case RegistrationKind.enumType:
        return byName ? TypeIds.namedEnum : TypeIds.enumById;
      case RegistrationKind.ext:
        return byName ? TypeIds.namedExt : TypeIds.ext;
      case RegistrationKind.union:
        return byName ? TypeIds.namedUnion : TypeIds.typedUnion;
      case RegistrationKind.builtin:
        throw StateError('Built-in types do not write TypeDef metadata.');
    }
  }

  int _typeDefFieldTypeId(FieldType fieldType) {
    if (fieldType.typeId == TypeIds.unknown) {
      return TypeIds.unknown;
    }
    if (TypeIds.isPrimitive(fieldType.typeId) ||
        TypeIds.isContainer(fieldType.typeId) ||
        fieldType.typeId == TypeIds.string ||
        fieldType.typeId == TypeIds.binary ||
        fieldType.typeId == TypeIds.date ||
        fieldType.typeId == TypeIds.duration ||
        fieldType.typeId == TypeIds.timestamp ||
        fieldType.typeId == TypeIds.decimal) {
      return fieldType.typeId;
    }
    // A field whose declared type is a generated union already carries the
    // union schema in the owning TypeDef field, so field metadata uses UNION.
    // TYPED_UNION/NAMED_UNION are only root or dynamic Any type identities.
    if (fieldType.typeId == TypeIds.typedUnion ||
        fieldType.typeId == TypeIds.namedUnion) {
      return TypeIds.union;
    }
    final resolved = tryResolveFieldType(fieldType);
    if (resolved != null && resolved.kind != RegistrationKind.builtin) {
      switch (resolved.kind) {
        case RegistrationKind.enumType:
          return TypeIds.enumById;
        case RegistrationKind.union:
          return TypeIds.union;
        case RegistrationKind.ext:
        case RegistrationKind.struct:
          return _typeIdFor(resolved);
        case RegistrationKind.builtin:
          break;
      }
    }
    return fieldType.ref ? TypeIds.unknown : fieldType.typeId;
  }

  @pragma('vm:prefer-inline')
  TypeMeta _readTypeDef(Buffer buffer, {required List<TypeInfo> sharedTypes}) {
    final marker = buffer.readVarUint32Small14();
    final isRef = (marker & 1) == 1;
    final index = marker >>> 1;
    if (isRef) {
      return typeMetaForResolved(sharedTypes[index]);
    }
    final header = TypeHeader(buffer.readInt64());
    final cached = _parsedTypeMetaCache.lookup(header);
    if (cached != null) {
      // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
      // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
      // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
      header.skipRemaining(buffer);
      sharedTypes.add(cached);
      return typeMetaForResolved(cached);
    }
    final resolved = _readTypeDefWithHeader(buffer, header);
    sharedTypes.add(resolved);
    return typeMetaForResolved(resolved);
  }

  @pragma('vm:never-inline')
  TypeInfo _readTypeDefWithHeader(Buffer buffer, TypeHeader header) {
    final typeDefStart = bufferReaderIndex(buffer) - 8;
    header.validateGlobal();
    final metaSize = header.readMetaSize(buffer);
    if (metaSize > config.maxTypeMetaBytes) {
      throw StateError(
        'Type metadata body size $metaSize exceeds maxTypeMetaBytes '
        '${config.maxTypeMetaBytes}. The data may be malicious. If the data '
        'is not malicious, please increase maxTypeMetaBytes.',
      );
    }
    // maxTypeMetaBytes is only a size cap. Prove the declared body bytes are
    // readable before readBytes allocates/copies from the attacker-controlled size.
    buffer.checkReadableBytes(metaSize);
    final metaBody = buffer.readBytes(metaSize);
    final metaBytes = Buffer.wrap(metaBody);
    final classHeader = metaBytes.readUint8();
    final isStruct = (classHeader & typeDefStructFlag) != 0;
    var fieldCount = 0;
    bool byName;
    int typeId;
    if (isStruct) {
      byName = (classHeader & typeDefRegisterByNameFlag) != 0;
      final compatible = (classHeader & typeDefCompatibleFlag) != 0;
      if (byName) {
        typeId =
            compatible ? TypeIds.namedCompatibleStruct : TypeIds.namedStruct;
      } else {
        typeId = compatible ? TypeIds.compatibleStruct : TypeIds.struct;
      }
      fieldCount = classHeader & typeDefSmallFieldCountThreshold;
      if (fieldCount == typeDefSmallFieldCountThreshold) {
        fieldCount += metaBytes.readVarUint32Small7();
      }
      if (fieldCount > config.maxTypeFields) {
        throw StateError(
          'Type metadata field count $fieldCount exceeds maxTypeFields '
          '${config.maxTypeFields}. The data may be malicious. If the data '
          'is not malicious, please increase maxTypeFields.',
        );
      }
    } else {
      if ((classHeader & 0x70) != 0) {
        throw StateError('Invalid TypeDef kind header.');
      }
      typeId = _nonStructTypeDefTypeId(classHeader & 0x0f);
      byName = _isNamedTypeDefKind(typeId);
    }
    final encodedNamespace =
        byName ? _readTypeDefName(metaBytes, packageNameEncoding) : null;
    final encodedTypeName =
        byName ? _readTypeDefName(metaBytes, typeNameEncoding) : null;
    int? userTypeId;
    if (!byName) {
      userTypeId = metaBytes.readVarUint32();
    }
    if (fieldCount > metaBytes.readableBytes) {
      throw StateError(
        'Type metadata field count exceeds available body bytes.',
      );
    }
    final fields = <FieldInfo>[];
    for (var i = 0; i < fieldCount; i += 1) {
      fields.add(_readTypeDefField(metaBytes));
    }
    if (!isStruct && fields.isNotEmpty) {
      throw StateError('Non-struct TypeDef cannot carry field metadata.');
    }
    if (metaBytes.readableBytes != 0) {
      throw StateError('Invalid TypeDef metadata size.');
    }
    header.validateBodyHash(metaBody);
    final resolved =
        userTypeId != null
            ? resolveUserById(userTypeId)
            : resolveUserByEncodedName(encodedNamespace!, encodedTypeName!);
    if (_typeDefTypeId(
          resolved.kind,
          byName: byName,
          evolving: resolved.evolving,
        ) !=
        typeId) {
      throw StateError('TypeDef kind does not match registered type metadata.');
    }
    final localTypeDef = typeDefForResolved(resolved);
    if (_matchesEncodedTypeDef(buffer, typeDefStart, localTypeDef.encoded)) {
      _parsedTypeMetaCache.remember(header, resolved);
      return resolved;
    }
    if (resolved.kind != RegistrationKind.struct) {
      final remoteSchemaKey = _checkRemoteTypeDefLimit(
        typeId: typeId,
        userTypeId: userTypeId,
        resolved: resolved,
      );
      _parsedTypeMetaCache.remember(header, resolved);
      _recordRemoteTypeDef(remoteSchemaKey);
      return resolved;
    }
    final remoteSchemaKey = _checkRemoteTypeDefLimit(
      typeId: typeId,
      userTypeId: userTypeId,
      resolved: resolved,
    );
    final remoteTypeDef = TypeDef(
      evolving: true,
      fields: List<FieldInfo>.unmodifiable(fields),
      header: header.value,
      encoded: Uint8List(0),
    );
    final remoteResolved = TypeInfo(
      type: resolved.type,
      kind: resolved.kind,
      typeId: resolved.typeId,
      supportsRef: resolved.supportsRef,
      needsRootRef: resolved.needsRootRef,
      usesNestedTypeDefinitions: resolved.usesNestedTypeDefinitions,
      evolving: resolved.evolving,
      fields: resolved.fields,
      serializer: resolved.serializer,
      structSerializer: resolved.structSerializer,
      userTypeId: resolved.userTypeId,
      namespace: resolved.namespace,
      typeName: resolved.typeName,
      encodedNamespace: resolved.encodedNamespace,
      encodedTypeName: resolved.encodedTypeName,
      typeDef: resolved.typeDef,
      remoteTypeDef: remoteTypeDef,
    );
    remoteResolved.structSerializer?.validateCompatibleTypeInfo(remoteResolved);
    _parsedTypeMetaCache.remember(header, remoteResolved);
    _recordRemoteTypeDef(remoteSchemaKey);
    return remoteResolved;
  }

  bool _matchesEncodedTypeDef(
    Buffer buffer,
    int typeDefStart,
    Uint8List encoded,
  ) {
    if (bufferReaderIndex(buffer) - typeDefStart != encoded.length) {
      return false;
    }
    return bufferMatchesBytes(buffer, typeDefStart, encoded);
  }

  @pragma('vm:never-inline')
  String _checkRemoteTypeDefLimit({
    required int typeId,
    required int? userTypeId,
    required TypeInfo resolved,
  }) {
    final key =
        userTypeId != null
            ? 'i$userTypeId'
            : 'n${resolved.namespace ?? ''}\u0000${resolved.typeName ?? ''}';
    final versionsForType = _remoteSchemaVersionsByType[key] ?? 0;
    if (versionsForType >= config.maxSchemaVersionsPerType) {
      throw StateError(
        'Remote schema version limit exceeded for one type. The data may be '
        'malicious. If the data is not malicious, please increase '
        'maxSchemaVersionsPerType=${config.maxSchemaVersionsPerType}.',
      );
    }
    final acceptedTypeCount =
        versionsForType == 0
            ? _remoteSchemaVersionsByType.length + 1
            : _remoteSchemaVersionsByType.length;
    final averageLimit =
        acceptedTypeCount * config.maxAverageSchemaVersionsPerType;
    final globalLimit =
        averageLimit > _minRemoteTypeMetaLimit
            ? averageLimit
            : _minRemoteTypeMetaLimit;
    if (_totalAcceptedSchemaVersions >= globalLimit) {
      throw StateError(
        'Remote schema version limit exceeded globally. The data may be '
        'malicious. If the data is not malicious, please increase '
        'maxAverageSchemaVersionsPerType=${config.maxAverageSchemaVersionsPerType}.',
      );
    }
    return key;
  }

  void _recordRemoteTypeDef(String key) {
    final versionsForType = _remoteSchemaVersionsByType[key] ?? 0;
    _remoteSchemaVersionsByType[key] = versionsForType + 1;
    _totalAcceptedSchemaVersions += 1;
  }

  EncodedMetaString _readTypeDefName(
    Buffer source,
    int Function(int compactEncoding) decodeEncoding,
  ) {
    final header = source.readUint8();
    final compactEncoding = header & 0x03;
    var size = header >>> 2;
    if (size == typeDefBigNameThreshold) {
      size += source.readVarUint32Small7();
    }
    source.checkReadableBytes(size);
    return internEncodedMetaString(
      Uint8List.fromList(source.readBytes(size)),
      encoding: decodeEncoding(compactEncoding),
    );
  }

  FieldInfo _readTypeDefField(Buffer source) {
    final fieldHeader = source.readByte();
    final encoding = (fieldHeader >>> 6) & 0x03;
    final fieldRef = (fieldHeader & 1) == 1;
    final fieldNullable = (fieldHeader & (1 << 1)) != 0;
    var size = (fieldHeader >> 2) & 0x0f;
    if (size == typeDefBigFieldNameThreshold) {
      size += source.readVarUint32Small7();
    }
    size += 1;
    final isTag = encoding == 3;
    final tagId = isTag ? size - 1 : null;
    final fieldType = _readTypeDefFieldType(
      source,
      typeId: source.readUint8(),
      nullable: fieldNullable,
      ref: fieldRef,
    );
    final String identifier;
    if (isTag) {
      identifier = tagId.toString();
    } else {
      source.checkReadableBytes(size);
      identifier = decodeFieldName(source.readBytes(size), encoding);
    }
    return FieldInfo(
      name: identifier,
      identifier: identifier,
      id: tagId,
      fieldType: fieldType,
    );
  }

  FieldType _readTypeDefFieldType(
    Buffer source, {
    required int typeId,
    required bool nullable,
    required bool ref,
  }) {
    final arguments = <FieldType>[];
    if (typeId == TypeIds.list || typeId == TypeIds.set) {
      arguments.add(_readNestedFieldType(source));
    } else if (typeId == TypeIds.map) {
      arguments.add(_readNestedFieldType(source));
      arguments.add(_readNestedFieldType(source));
    }
    return FieldType(
      type: Object,
      declaredTypeName: null,
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: typeId == TypeIds.unknown && !ref ? true : null,
      arguments: arguments,
    );
  }

  FieldType _readNestedFieldType(Buffer source) {
    final encoded = source.readVarUint32Small7();
    return _readTypeDefFieldType(
      source,
      typeId: encoded >>> 2,
      nullable: ((encoded >> 1) & 1) == 1,
      ref: (encoded & 1) == 1,
    );
  }

  TypeInfo resolveBuiltinTypeId(int typeId) {
    switch (typeId) {
      case TypeIds.boolType:
        return _builtin(bool, TypeIds.boolType);
      case TypeIds.int8:
        return _builtin(int, TypeIds.int8);
      case TypeIds.int16:
        return _builtin(int, TypeIds.int16);
      case TypeIds.int32:
        return _builtin(int, TypeIds.int32);
      case TypeIds.varInt32:
        return _builtin(int, TypeIds.varInt32);
      case TypeIds.int64:
        return _builtin(Int64, TypeIds.int64);
      case TypeIds.varInt64:
        return _builtin(int, TypeIds.varInt64);
      case TypeIds.taggedInt64:
        return _builtin(Int64, TypeIds.taggedInt64);
      case TypeIds.uint8:
        return _builtin(int, TypeIds.uint8);
      case TypeIds.uint16:
        return _builtin(int, TypeIds.uint16);
      case TypeIds.uint32:
        return _builtin(int, TypeIds.uint32);
      case TypeIds.varUint32:
        return _builtin(int, TypeIds.varUint32);
      case TypeIds.uint64:
        return _builtin(Uint64, TypeIds.uint64);
      case TypeIds.varUint64:
        return _builtin(Uint64, TypeIds.varUint64);
      case TypeIds.taggedUint64:
        return _builtin(Uint64, TypeIds.taggedUint64);
      case TypeIds.float16:
        return _builtin(double, TypeIds.float16);
      case TypeIds.bfloat16:
        return _builtin(double, TypeIds.bfloat16);
      case TypeIds.float32:
        return _builtin(Float32, TypeIds.float32);
      case TypeIds.float64:
        return _builtin(double, TypeIds.float64);
      case TypeIds.string:
        return _builtin(String, TypeIds.string);
      case TypeIds.list:
        return _builtin(List, TypeIds.list);
      case TypeIds.set:
        return _builtin(Set, TypeIds.set);
      case TypeIds.map:
        return _builtin(Map, TypeIds.map);
      case TypeIds.none:
        return _builtin(Null, TypeIds.none);
      case TypeIds.decimal:
        return _builtin(Decimal, TypeIds.decimal);
      case TypeIds.binary:
        return _builtin(Uint8List, TypeIds.binary);
      case TypeIds.date:
        return _builtin(LocalDate, TypeIds.date);
      case TypeIds.duration:
        return _builtin(Duration, TypeIds.duration);
      case TypeIds.timestamp:
        return _builtin(DateTime, TypeIds.timestamp);
      case TypeIds.boolArray:
        return _builtin(BoolList, TypeIds.boolArray);
      case TypeIds.int8Array:
        return _builtin(Int8List, TypeIds.int8Array);
      case TypeIds.int16Array:
        return _builtin(Int16List, TypeIds.int16Array);
      case TypeIds.int32Array:
        return _builtin(Int32List, TypeIds.int32Array);
      case TypeIds.int64Array:
        return _builtin(Int64List, TypeIds.int64Array);
      case TypeIds.uint8Array:
        return _builtin(Uint8List, TypeIds.uint8Array);
      case TypeIds.uint16Array:
        return _builtin(Uint16List, TypeIds.uint16Array);
      case TypeIds.uint32Array:
        return _builtin(Uint32List, TypeIds.uint32Array);
      case TypeIds.uint64Array:
        return _builtin(Uint64List, TypeIds.uint64Array);
      case TypeIds.float16Array:
        return _builtin(Float16List, TypeIds.float16Array);
      case TypeIds.bfloat16Array:
        return _builtin(Bfloat16List, TypeIds.bfloat16Array);
      case TypeIds.float32Array:
        return _builtin(Float32List, TypeIds.float32Array);
      case TypeIds.float64Array:
        return _builtin(Float64List, TypeIds.float64Array);
      default:
        throw StateError('Unsupported builtin type id $typeId.');
    }
  }

  TypeInfo resolveExpectedRootType<T>(TypeInfo resolved) {
    if ((_isType<T, Int64>() || _isType<T, Int64?>()) &&
        resolved.typeId == TypeIds.varInt64) {
      return _builtin(Int64, TypeIds.varInt64);
    }
    return resolved;
  }

  TypeInfo _builtin(Type type, int typeId) {
    final key = _BuiltinKey(type, typeId);
    final cached = _builtinByKey[key];
    if (cached != null) {
      return cached;
    }
    final resolved = TypeInfo(
      type: type,
      kind: RegistrationKind.builtin,
      typeId: typeId,
      supportsRef: TypeIds.supportsRef(typeId),
      needsRootRef: false,
      usesNestedTypeDefinitions: false,
      evolving: false,
      fields: const <FieldInfo>[],
      serializer: _builtinSerializerFor(typeId, type),
      structSerializer: null,
      userTypeId: null,
      namespace: null,
      typeName: null,
      encodedNamespace: null,
      encodedTypeName: null,
      typeDef: null,
      remoteTypeDef: null,
    );
    _builtinByKey[key] = resolved;
    return resolved;
  }

  Serializer<Object?> _builtinSerializerFor(int typeId, Type type) {
    switch (typeId) {
      case TypeIds.boolType:
        return boolSerializer as Serializer<Object?>;
      case TypeIds.int8:
        return int8Serializer as Serializer<Object?>;
      case TypeIds.int16:
        return int16Serializer as Serializer<Object?>;
      case TypeIds.int32:
        return int32Serializer as Serializer<Object?>;
      case TypeIds.varInt32:
        return varInt32Serializer as Serializer<Object?>;
      case TypeIds.int64:
        return int64Serializer as Serializer<Object?>;
      case TypeIds.varInt64:
        return varInt64Serializer as Serializer<Object?>;
      case TypeIds.taggedInt64:
        return taggedInt64Serializer as Serializer<Object?>;
      case TypeIds.uint8:
        return uint8Serializer as Serializer<Object?>;
      case TypeIds.uint16:
        return uint16Serializer as Serializer<Object?>;
      case TypeIds.uint32:
        return uint32Serializer as Serializer<Object?>;
      case TypeIds.varUint32:
        return varUint32Serializer as Serializer<Object?>;
      case TypeIds.uint64:
        return uint64Serializer as Serializer<Object?>;
      case TypeIds.varUint64:
        return varUint64Serializer as Serializer<Object?>;
      case TypeIds.taggedUint64:
        return taggedUint64Serializer as Serializer<Object?>;
      case TypeIds.float16:
        return float16Serializer as Serializer<Object?>;
      case TypeIds.bfloat16:
        return bfloat16Serializer as Serializer<Object?>;
      case TypeIds.float32:
        return float32Serializer as Serializer<Object?>;
      case TypeIds.float64:
        return float64Serializer as Serializer<Object?>;
      case TypeIds.string:
        return stringSerializer as Serializer<Object?>;
      case TypeIds.none:
        return noneSerializer as Serializer<Object?>;
      case TypeIds.decimal:
        return decimalSerializer as Serializer<Object?>;
      case TypeIds.binary:
      case TypeIds.uint8Array:
        return binarySerializer as Serializer<Object?>;
      case TypeIds.boolArray:
        return boolArraySerializer as Serializer<Object?>;
      case TypeIds.int8Array:
        return int8ArraySerializer as Serializer<Object?>;
      case TypeIds.int16Array:
        return int16ArraySerializer as Serializer<Object?>;
      case TypeIds.int32Array:
        return int32ArraySerializer as Serializer<Object?>;
      case TypeIds.int64Array:
        return int64ArraySerializer as Serializer<Object?>;
      case TypeIds.uint16Array:
        return uint16ArraySerializer as Serializer<Object?>;
      case TypeIds.uint32Array:
        return uint32ArraySerializer as Serializer<Object?>;
      case TypeIds.uint64Array:
        return uint64ArraySerializer as Serializer<Object?>;
      case TypeIds.float16Array:
        return float16ArraySerializer as Serializer<Object?>;
      case TypeIds.bfloat16Array:
        return bfloat16ArraySerializer as Serializer<Object?>;
      case TypeIds.float32Array:
        return float32ArraySerializer as Serializer<Object?>;
      case TypeIds.float64Array:
        return float64ArraySerializer as Serializer<Object?>;
      case TypeIds.list:
        return listSerializer as Serializer<Object?>;
      case TypeIds.set:
        return setSerializer as Serializer<Object?>;
      case TypeIds.map:
        return mapSerializer as Serializer<Object?>;
      case TypeIds.date:
        return localDateSerializer as Serializer<Object?>;
      case TypeIds.duration:
        return durationSerializer as Serializer<Object?>;
      case TypeIds.timestamp:
        if (type == Timestamp) {
          return timestampSerializer as Serializer<Object?>;
        }
        return dateTimeSerializer as Serializer<Object?>;
      default:
        throw StateError('Unsupported builtin type id $typeId.');
    }
  }

  RegistrationKind _inferKind(Serializer serializer) {
    if (serializer is EnumSerializer) {
      return RegistrationKind.enumType;
    }
    if (serializer is UnionSerializer) {
      return RegistrationKind.union;
    }
    return RegistrationKind.ext;
  }

  int _defaultTypeIdForType(Type type) {
    if (type == bool) {
      return TypeIds.boolType;
    }
    if (type == int) {
      return TypeIds.varInt64;
    }
    if (type == Int64) {
      return TypeIds.varInt64;
    }
    if (type == Int64List) {
      return TypeIds.int64Array;
    }
    if (type == Uint64) {
      return TypeIds.varUint64;
    }
    if (type == Uint64List) {
      return TypeIds.uint64Array;
    }
    if (type == BoolList) {
      return TypeIds.list;
    }
    if (type == Float32) {
      return TypeIds.float32;
    }
    if (type == Decimal) {
      return TypeIds.decimal;
    }
    if (type == Float16List) {
      return TypeIds.float16Array;
    }
    if (type == Bfloat16List) {
      return TypeIds.bfloat16Array;
    }
    if (type == double) {
      return TypeIds.float64;
    }
    if (type == String) {
      return TypeIds.string;
    }
    if (type == Null) {
      return TypeIds.none;
    }
    if (type == List) {
      return TypeIds.list;
    }
    if (type == Set) {
      return TypeIds.set;
    }
    if (type == Map) {
      return TypeIds.map;
    }
    if (type == Uint8List) {
      return TypeIds.binary;
    }
    if (type == Duration) {
      return TypeIds.duration;
    }
    if (type == Timestamp) {
      return TypeIds.timestamp;
    }
    if (type == DateTime) {
      return TypeIds.timestamp;
    }
    if (type == LocalDate) {
      return TypeIds.date;
    }
    return TypeIds.unknown;
  }

  void _rememberResolved(
    Type type,
    TypeInfo resolved, {
    required int? id,
    required String? namespace,
    required String? typeName,
  }) {
    _registeredByType[type] = resolved;
    if (id != null) {
      _registeredById[id] = resolved;
      return;
    }
    _registeredByName[_nameKey(namespace!, typeName!)] = resolved;
    (_registeredByEncodedName[resolved.encodedNamespace!] ??=
            LinkedHashMap<EncodedMetaString, TypeInfo>.identity())[resolved
            .encodedTypeName!] =
        resolved;
  }

  ({String? namespace, String? typeName}) _resolveRegistrationName({
    required int? id,
    required String? namespace,
    required String? typeName,
  }) {
    final hasNumeric = id != null;
    final hasNamed = namespace != null || typeName != null;
    if (hasNumeric == hasNamed) {
      throw ArgumentError(
        'Exactly one registration mode is required: id, or name.',
      );
    }
    if (namespace != null && typeName == null) {
      throw ArgumentError('typeName is required when namespace is provided.');
    }
    if (typeName == null) {
      return (namespace: null, typeName: null);
    }
    var resolvedNamespace = namespace;
    var resolvedTypeName = typeName;
    if (resolvedNamespace == null) {
      final lastDot = typeName.lastIndexOf('.');
      if (lastDot >= 0) {
        resolvedNamespace = typeName.substring(0, lastDot);
        resolvedTypeName = typeName.substring(lastDot + 1);
      } else {
        resolvedNamespace = '';
      }
    }
    if (resolvedTypeName.isEmpty) {
      throw ArgumentError('name must include a non-empty type name.');
    }
    return (namespace: resolvedNamespace, typeName: resolvedTypeName);
  }

  static String _nameKey(String namespace, String typeName) =>
      '$namespace::$typeName';

  EncodedMetaString _canonicalMetaString(EncodedMetaString encoded) {
    return internEncodedMetaString(encoded.bytes, encoding: encoded.encoding);
  }

  void _rememberNamedType(int typeId, TypeInfo resolved) {
    if (typeId < _lastNamedTypeById.length) {
      _lastNamedTypeById[typeId] = resolved;
    }
    final namespace = resolved.encodedNamespace;
    final typeName = resolved.encodedTypeName;
    if (namespace == null || typeName == null) {
      return;
    }
    final slot =
        _namedTypeLookupCacheIndex(typeId, namespace, typeName) &
        (_namedTypeLookupCache.length - 1);
    _namedTypeLookupCache[slot] = _NamedTypeReadCacheEntry(
      typeId,
      namespace,
      typeName,
      resolved,
    );
  }

  int _namedTypeLookupCacheIndex(
    int typeId,
    EncodedMetaString namespace,
    EncodedMetaString typeName,
  ) {
    return Object.hash(typeId, namespace.hash, typeName.hash);
  }

  Type _builtinTypeForFieldType(FieldType fieldType) {
    switch (fieldType.typeId) {
      case TypeIds.int64:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.int64Array:
      case TypeIds.uint64Array:
        break;
      default:
        return fieldType.type;
    }
    final declaredTypeName = fieldType.declaredTypeName;
    if (declaredTypeName != null) {
      if (_matchesDeclaredTypeName(declaredTypeName, 'Int64')) {
        return Int64;
      }
      if (_matchesDeclaredTypeName(declaredTypeName, 'Uint64')) {
        return Uint64;
      }
      if (_matchesDeclaredTypeName(declaredTypeName, 'Int64List')) {
        return Int64List;
      }
      if (_matchesDeclaredTypeName(declaredTypeName, 'Uint64List')) {
        return Uint64List;
      }
    }
    return fieldType.type;
  }

  bool _matchesDeclaredTypeName(String declaredTypeName, String typeName) {
    return declaredTypeName == typeName ||
        declaredTypeName.endsWith('.$typeName');
  }

  bool _matchesNamedTypeId(TypeInfo resolved, int typeId) {
    if (!resolved.isNamed) {
      return false;
    }
    switch (resolved.kind) {
      case RegistrationKind.enumType:
        return typeId == TypeIds.namedEnum;
      case RegistrationKind.struct:
        return typeId == TypeIds.namedStruct;
      case RegistrationKind.ext:
        return typeId == TypeIds.namedExt;
      case RegistrationKind.union:
        return typeId == TypeIds.namedUnion;
      case RegistrationKind.builtin:
        return false;
    }
  }
}

bool _isType<T, U>() => T == U;

final class _BuiltinKey {
  final Type type;
  final int typeId;

  const _BuiltinKey(this.type, this.typeId);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is _BuiltinKey && other.type == type && other.typeId == typeId;

  @override
  int get hashCode => Object.hash(type, typeId);
}

final class _NamedTypeReadCacheEntry {
  final int typeId;
  final EncodedMetaString namespace;
  final EncodedMetaString typeName;
  final TypeInfo resolved;

  const _NamedTypeReadCacheEntry(
    this.typeId,
    this.namespace,
    this.typeName,
    this.resolved,
  );
}

final class _EncodedMetaStringKey {
  final int encoding;
  final Uint8List bytes;
  final int _hashCode;

  _EncodedMetaStringKey(this.encoding, this.bytes)
    : _hashCode = Object.hash(encoding, Object.hashAll(bytes));

  @override
  int get hashCode => _hashCode;

  @override
  bool operator ==(Object other) {
    if (other is! _EncodedMetaStringKey || other.encoding != encoding) {
      return false;
    }
    if (identical(other.bytes, bytes)) {
      return true;
    }
    if (other.bytes.length != bytes.length) {
      return false;
    }
    for (var index = 0; index < bytes.length; index += 1) {
      if (other.bytes[index] != bytes[index]) {
        return false;
      }
    }
    return true;
  }
}
