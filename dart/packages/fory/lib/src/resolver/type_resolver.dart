import 'dart:collection';
import 'dart:typed_data';

import 'package:fory/src/buffer.dart';
import 'package:fory/src/codegen/generated_registry.dart';
import 'package:fory/src/config.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/meta_string_writer.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/meta/meta_string.dart';
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
import 'package:fory/src/serializer/typed_array_serializers.dart';
import 'package:fory/src/serializer/union_serializer.dart';
import 'package:fory/src/types/fixed_ints.dart';
import 'package:fory/src/types/float16.dart';
import 'package:fory/src/types/float32.dart';
import 'package:fory/src/types/local_date.dart';
import 'package:fory/src/types/timestamp.dart';
import 'package:fory/src/util/hash_util.dart';

abstract final class TypeIds {
  static const int unknown = 0;
  static const int boolType = 1;
  static const int int8 = 2;
  static const int int16 = 3;
  static const int int32 = 4;
  static const int varInt32 = 5;
  static const int int64 = 6;
  static const int varInt64 = 7;
  static const int taggedInt64 = 8;
  static const int uint8 = 9;
  static const int uint16 = 10;
  static const int uint32 = 11;
  static const int varUint32 = 12;
  static const int uint64 = 13;
  static const int varUint64 = 14;
  static const int taggedUint64 = 15;
  static const int float16 = 17;
  static const int float32 = 19;
  static const int float64 = 20;
  static const int string = 21;
  static const int list = 22;
  static const int set = 23;
  static const int map = 24;
  static const int enumById = 25;
  static const int namedEnum = 26;
  static const int struct = 27;
  static const int compatibleStruct = 28;
  static const int namedStruct = 29;
  static const int namedCompatibleStruct = 30;
  static const int ext = 31;
  static const int namedExt = 32;
  static const int union = 33;
  static const int typedUnion = 34;
  static const int namedUnion = 35;
  static const int none = 36;
  static const int timestamp = 38;
  static const int date = 39;
  static const int binary = 41;
  static const int boolArray = 43;
  static const int int8Array = 44;
  static const int int16Array = 45;
  static const int int32Array = 46;
  static const int int64Array = 47;
  static const int uint8Array = 48;
  static const int uint16Array = 49;
  static const int uint32Array = 50;
  static const int uint64Array = 51;
  static const int float16Array = 53;
  static const int float32Array = 55;
  static const int float64Array = 56;

  static bool isPrimitive(int typeId) =>
      typeId == boolType ||
      typeId == int8 ||
      typeId == int16 ||
      typeId == int32 ||
      typeId == varInt32 ||
      typeId == int64 ||
      typeId == varInt64 ||
      typeId == taggedInt64 ||
      typeId == uint8 ||
      typeId == uint16 ||
      typeId == uint32 ||
      typeId == varUint32 ||
      typeId == uint64 ||
      typeId == varUint64 ||
      typeId == taggedUint64 ||
      typeId == float16 ||
      typeId == float32 ||
      typeId == float64;

  static bool isContainer(int typeId) =>
      typeId == list || typeId == set || typeId == map;

  static bool isUserType(int typeId) =>
      typeId == enumById ||
      typeId == namedEnum ||
      typeId == struct ||
      typeId == compatibleStruct ||
      typeId == namedStruct ||
      typeId == namedCompatibleStruct ||
      typeId == ext ||
      typeId == namedExt ||
      typeId == union ||
      typeId == typedUnion ||
      typeId == namedUnion;

  static bool isBasicValue(int typeId) =>
      isPrimitive(typeId) ||
      typeId == string ||
      typeId == binary ||
      typeId == timestamp ||
      typeId == date ||
      typeId == boolArray ||
      typeId == int8Array ||
      typeId == int16Array ||
      typeId == int32Array ||
      typeId == int64Array ||
      typeId == uint8Array ||
      typeId == uint16Array ||
      typeId == uint32Array ||
      typeId == uint64Array ||
      typeId == float16Array ||
      typeId == float32Array ||
      typeId == float64Array;

  static bool supportsRef(int typeId) {
    if (typeId == unknown) {
      return true;
    }
    if (isPrimitive(typeId) || typeId == binary) {
      return false;
    }
    switch (typeId) {
      case boolArray:
      case int8Array:
      case int16Array:
      case int32Array:
      case int64Array:
      case uint8Array:
      case uint16Array:
      case uint32Array:
      case uint64Array:
      case float16Array:
      case float32Array:
      case float64Array:
        return false;
      default:
        return true;
    }
  }
}

enum RegistrationKind { builtin, struct, enumType, ext, union }

final class TypeInfo {
  final Type type;
  final RegistrationKind kind;
  final int typeId;
  final bool supportsRef;
  final Serializer<Object?> serializer;
  final StructSerializer? structSerializer;
  final int? userTypeId;
  final String? namespace;
  final String? typeName;
  final EncodedMetaString? encodedNamespace;
  final EncodedMetaString? encodedTypeName;
  final TypeDef? typeDef;
  final TypeDef? remoteTypeDef;

  const TypeInfo({
    required this.type,
    required this.kind,
    required this.typeId,
    required this.supportsRef,
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

  bool get isCompatibleStruct =>
      kind == RegistrationKind.struct && typeDef!.evolving;

  bool get isBasicValue => TypeIds.isBasicValue(typeId);
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

final class TypeResolver {
  final Config config;
  final WireTypeMetaEncoder _wireTypeMetaEncoder = const WireTypeMetaEncoder();
  final WireTypeMetaDecoder _wireTypeMetaDecoder = const WireTypeMetaDecoder();
  final ParsedTypeMetaCache _parsedTypeMetaCache = ParsedTypeMetaCache();
  final List<TypeInfo?> _lastNamedTypeByWireType =
      List<TypeInfo?>.filled(64, null);
  final List<TypeInfo?> _builtinByTypeId = List<TypeInfo?>.filled(64, null);
  final List<_NamedTypeReadCacheEntry?> _namedTypeLookupCache =
      List<_NamedTypeReadCacheEntry?>.filled(128, null);
  final Map<Type, TypeInfo> _runtimeTypeValueCache = <Type, TypeInfo>{};
  final Map<Type, TypeInfo> _registeredByType = <Type, TypeInfo>{};
  final Map<int, TypeInfo> _registeredById = <int, TypeInfo>{};
  final Map<String, TypeInfo> _registeredByName = <String, TypeInfo>{};
  final Map<EncodedMetaString, Map<EncodedMetaString, TypeInfo>>
      _registeredByEncodedName = LinkedHashMap<EncodedMetaString,
          Map<EncodedMetaString, TypeInfo>>.identity();
  final Map<String, EncodedMetaString> _packageMetaStrings =
      <String, EncodedMetaString>{};
  final Map<String, EncodedMetaString> _typeNameMetaStrings =
      <String, EncodedMetaString>{};
  final Map<String, EncodedMetaString> _fieldNameMetaStrings =
      <String, EncodedMetaString>{};
  final Map<_EncodedMetaStringKey, EncodedMetaString>
      _internedEncodedMetaStrings =
      <_EncodedMetaStringKey, EncodedMetaString>{};

  TypeResolver(this.config);

  void registerGenerated(
    Type type, {
    int? id,
    String? namespace,
    String? typeName,
  }) {
    final registration = GeneratedRegistrationCatalog.lookup(type);
    if (registration == null) {
      throw StateError(
        'Type $type has no generated registration metadata. '
        'Register it through its generated library namespace first.',
      );
    }
    _registerResolvedSerializer(
      type,
      registration.serializerFactory(),
      switch (registration.kind) {
        GeneratedRegistrationKind.enumType => RegistrationKind.enumType,
        GeneratedRegistrationKind.struct => RegistrationKind.struct,
      },
      evolving: registration.evolving,
      fields: registration.fields,
      compatibleFactory: registration.compatibleFactory,
      compatibleReadersBySlot: registration.compatibleReadersBySlot,
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
    List<FieldInfo> fields = const <FieldInfo>[],
    GeneratedStructCompatibleFactory<Object>? compatibleFactory,
    List<GeneratedStructCompatibleFieldReader<Object>>? compatibleReadersBySlot,
    int? id,
    String? namespace,
    String? typeName,
  }) {
    _validateRegistrationMode(id: id, namespace: namespace, typeName: typeName);
    final encodedNamespace =
        namespace == null ? null : packageMetaString(namespace);
    final encodedTypeName =
        typeName == null ? null : typeNameMetaString(typeName);
    final normalizedFields = registrationKind == RegistrationKind.struct
        ? List<FieldInfo>.unmodifiable(
            List<FieldInfo>.from(fields),
          )
        : const <FieldInfo>[];
    final typeDef = _buildTypeDef(
      kind: registrationKind,
      evolving: registrationKind == RegistrationKind.struct ? evolving : false,
      userTypeId: id,
      encodedNamespace: encodedNamespace,
      encodedTypeName: encodedTypeName,
      fields: normalizedFields,
    );
    final structSerializer = registrationKind != RegistrationKind.struct
        ? null
        : StructSerializer(
            payloadSerializer,
            typeDef,
            this,
            compatibleFactory: compatibleFactory,
            compatibleReadersBySlot: compatibleReadersBySlot,
          );
    final resolved = TypeInfo(
      type: type,
      kind: registrationKind,
      typeId: _defaultTypeIdForType(type),
      supportsRef: payloadSerializer.supportsRef,
      serializer: payloadSerializer,
      structSerializer: structSerializer,
      userTypeId: id,
      namespace: namespace,
      typeName: typeName,
      encodedNamespace: encodedNamespace,
      encodedTypeName: encodedTypeName,
      typeDef: typeDef,
      remoteTypeDef: null,
    );
    _parsedTypeMetaCache.remember(TypeHeader(typeDef.header), resolved);
    _rememberResolved(type, resolved,
        id: id, namespace: namespace, typeName: typeName);
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
    if (value is Int8) {
      return _builtin(Int8, TypeIds.int8);
    }
    if (value is Int16) {
      return _builtin(Int16, TypeIds.int16);
    }
    if (value is Int32) {
      return _builtin(Int32, TypeIds.varInt32);
    }
    if (value is int) {
      return _builtin(int, TypeIds.varInt64);
    }
    if (value is UInt8) {
      return _builtin(UInt8, TypeIds.uint8);
    }
    if (value is UInt16) {
      return _builtin(UInt16, TypeIds.uint16);
    }
    if (value is UInt32) {
      return _builtin(UInt32, TypeIds.uint32);
    }
    if (value is Float16) {
      return _builtin(Float16, TypeIds.float16);
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
    if (value is Uint8List) {
      return _builtin(Uint8List, TypeIds.binary);
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
    if (value is Float32List) {
      return _builtin(Float32List, TypeIds.float32Array);
    }
    if (value is Float64List) {
      return _builtin(Float64List, TypeIds.float64Array);
    }
    if (value is List<bool>) {
      return _builtin(List<bool>, TypeIds.boolArray);
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
    if (value is Timestamp) {
      return _builtin(Timestamp, TypeIds.timestamp);
    }
    throw StateError(
      'Type $runtimeType is not registered. Register generated types with '
      'their generated library namespace, or register a serializer explicitly.',
    );
  }

  TypeInfo? tryResolveFieldType(FieldType fieldType) {
    switch (fieldType.typeId) {
      case TypeIds.unknown:
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
      case TypeIds.float16:
      case TypeIds.float32:
      case TypeIds.float64:
      case TypeIds.string:
      case TypeIds.list:
      case TypeIds.set:
      case TypeIds.map:
      case TypeIds.binary:
      case TypeIds.timestamp:
      case TypeIds.date:
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
      case TypeIds.float32Array:
      case TypeIds.float64Array:
        return _builtin(fieldType.type, fieldType.typeId);
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
    int wireTypeId,
    EncodedMetaString namespace,
    EncodedMetaString typeName,
  ) {
    final slot = _namedTypeLookupCacheIndex(wireTypeId, namespace, typeName) &
        (_namedTypeLookupCache.length - 1);
    final cached = _namedTypeLookupCache[slot];
    if (cached != null &&
        cached.wireTypeId == wireTypeId &&
        identical(cached.namespace, namespace) &&
        identical(cached.typeName, typeName)) {
      return cached.resolved;
    }
    final resolved = resolveUserByEncodedName(namespace, typeName);
    _namedTypeLookupCache[slot] = _NamedTypeReadCacheEntry(
      wireTypeId,
      namespace,
      typeName,
      resolved,
    );
    if (wireTypeId < _lastNamedTypeByWireType.length) {
      _lastNamedTypeByWireType[wireTypeId] = resolved;
    }
    return resolved;
  }

  SerializationFieldInfo serializationFieldInfo(
    FieldInfo field, {
    required int slot,
  }) {
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return SerializationFieldInfo(
        field: field,
        slot: slot,
        declaredTypeInfo: null,
        usesDeclaredType: false,
      );
    }
    final declaredTypeInfo = tryResolveFieldType(fieldType);
    return SerializationFieldInfo(
      field: field,
      slot: slot,
      declaredTypeInfo: declaredTypeInfo,
      usesDeclaredType: declaredTypeInfo != null
          ? usesDeclaredTypeInfo(
              config.compatible,
              fieldType,
              declaredTypeInfo,
            )
          : false,
    );
  }

  WireTypeMeta wireTypeMetaForResolved(TypeInfo resolved) {
    return _wireTypeMetaEncoder.typeMetaFor(config, resolved);
  }

  TypeDef typeDefForResolved(
    TypeInfo resolved, {
    List<FieldInfo>? fields,
  }) {
    final resolvedFields = resolved.typeDef?.fields;
    if (fields == null || identical(fields, resolvedFields)) {
      return resolved.typeDef!;
    }
    return _buildTypeDef(
      kind: resolved.kind,
      evolving: resolved.typeDef!.evolving,
      userTypeId: resolved.userTypeId,
      encodedNamespace: resolved.encodedNamespace,
      encodedTypeName: resolved.encodedTypeName,
      fields: fields,
    );
  }

  void writeTypeMeta(
    Buffer buffer,
    TypeInfo resolved, {
    required TypeDef? typeDef,
    required LinkedHashMap<TypeDef, int> typeDefIds,
    required MetaStringWriter metaStringWriter,
  }) {
    _wireTypeMetaEncoder.write(
      buffer,
      wireTypeMetaForResolved(resolved),
      writeTypeDef: (wireTypeMeta) => _writeTypeDef(
        buffer,
        typeDef ?? wireTypeMeta.resolvedType.typeDef!,
        typeDefIds: typeDefIds,
      ),
      writePackageMetaString: (value) => metaStringWriter.writeMetaString(
        buffer,
        value,
      ),
      writeTypeNameMetaString: (value) => metaStringWriter.writeMetaString(
        buffer,
        value,
      ),
    );
  }

  TypeInfo readTypeMeta(
    Buffer buffer, {
    TypeInfo? expectedNamedType,
    required List<TypeInfo> sharedTypes,
    required MetaStringReader metaStringReader,
  }) {
    final typeMeta = _wireTypeMetaDecoder.read(
      buffer,
      config: config,
      resolveBuiltinWireType: resolveBuiltinWireType,
      resolveUserById: resolveUserById,
      resolveUserByEncodedNameCached: resolveUserByEncodedNameCached,
      expectedNamedType: (wireTypeId) {
        final expected = expectedNamedType;
        if (expected != null && _matchesNamedWireType(expected, wireTypeId)) {
          return expected;
        }
        return wireTypeId < _lastNamedTypeByWireType.length
            ? _lastNamedTypeByWireType[wireTypeId]
            : null;
      },
      readTypeDef: () => _readTypeDef(
        buffer,
        sharedTypes: sharedTypes,
      ),
      readPackageMetaString: ([expected]) =>
          metaStringReader.readMetaString(buffer, expected),
      readTypeNameMetaString: ([expected]) =>
          metaStringReader.readMetaString(buffer, expected),
    );
    if (typeMeta.writesNamedType) {
      _rememberNamedType(typeMeta.wireTypeId, typeMeta.resolvedType);
    }
    return typeMeta.resolvedType;
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
    required int? userTypeId,
    required EncodedMetaString? encodedNamespace,
    required EncodedMetaString? encodedTypeName,
    required List<FieldInfo> fields,
  }) {
    final metaBuffer = Buffer();
    var classHeader = fields.length;
    metaBuffer.writeByte(0xff);
    if (fields.length >= typeDefSmallFieldCountThreshold) {
      classHeader = typeDefSmallFieldCountThreshold;
      metaBuffer.writeVarUint32Small7(
        fields.length - typeDefSmallFieldCountThreshold,
      );
    }
    if (userTypeId == null &&
        encodedNamespace != null &&
        encodedTypeName != null) {
      classHeader |= typeDefRegisterByNameFlag;
      _writeTypeDefName(
        metaBuffer,
        encodedNamespace.bytes,
        encoding: packageNameCompactEncoding(
          encodedNamespace.encoding,
        ),
      );
      _writeTypeDefName(
        metaBuffer,
        encodedTypeName.bytes,
        encoding: typeNameCompactEncoding(
          encodedTypeName.encoding,
        ),
      );
    } else {
      metaBuffer.writeUint8(_typeDefTypeId(kind));
      metaBuffer.writeVarUint32(userTypeId!);
    }
    metaBuffer.toBytes()[0] = classHeader;
    for (final field in fields) {
      _writeTypeDefField(metaBuffer, field);
    }
    final body = metaBuffer.toBytes();
    final buffer = Buffer();
    buffer.writeInt64(
      typeDefHeader(body, hasFieldsMeta: fields.isNotEmpty),
    );
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
    header |= ((size >= typeDefBigFieldNameThreshold
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
      _writeTypeDefFieldType(target, fieldType.arguments.single,
          writeFlags: true);
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

  int _typeDefTypeId(RegistrationKind kind) {
    switch (kind) {
      case RegistrationKind.struct:
        return TypeIds.struct;
      case RegistrationKind.enumType:
        return TypeIds.enumById;
      case RegistrationKind.ext:
        return TypeIds.ext;
      case RegistrationKind.union:
        return TypeIds.typedUnion;
      case RegistrationKind.builtin:
        throw StateError('Built-in types do not write TypeDef metadata.');
    }
  }

  int _typeDefFieldTypeId(FieldType fieldType) {
    if (TypeIds.isPrimitive(fieldType.typeId) ||
        TypeIds.isContainer(fieldType.typeId) ||
        fieldType.typeId == TypeIds.string ||
        fieldType.typeId == TypeIds.binary ||
        fieldType.typeId == TypeIds.date ||
        fieldType.typeId == TypeIds.timestamp) {
      return fieldType.typeId;
    }
    return fieldType.ref ? TypeIds.unknown : fieldType.typeId;
  }

  WireTypeMeta _readTypeDef(
    Buffer buffer, {
    required List<TypeInfo> sharedTypes,
  }) {
    final marker = buffer.readVarUint32Small14();
    final isRef = (marker & 1) == 1;
    final index = marker >>> 1;
    if (isRef) {
      return wireTypeMetaForResolved(sharedTypes[index]);
    }
    final header = TypeHeader(buffer.readInt64());
    final cached = _parsedTypeMetaCache.lookup(header);
    if (cached != null) {
      header.skipRemaining(buffer);
      sharedTypes.add(cached);
      return wireTypeMetaForResolved(cached);
    }
    final resolved = _readTypeDefWithHeader(buffer, header);
    _parsedTypeMetaCache.remember(header, resolved);
    sharedTypes.add(resolved);
    return wireTypeMetaForResolved(resolved);
  }

  TypeInfo _readTypeDefWithHeader(Buffer buffer, TypeHeader header) {
    final metaSize = header.readMetaSize(buffer);
    final metaBytes = Buffer.wrap(buffer.readBytes(metaSize));
    final classHeader = metaBytes.readUint8();
    var fieldCount = classHeader & typeDefSmallFieldCountThreshold;
    if (fieldCount == typeDefSmallFieldCountThreshold) {
      fieldCount += metaBytes.readVarUint32Small7();
    }
    final byName = (classHeader & typeDefRegisterByNameFlag) != 0;
    final encodedNamespace =
        byName ? _readTypeDefName(metaBytes, packageNameEncoding) : null;
    final encodedTypeName =
        byName ? _readTypeDefName(metaBytes, typeNameEncoding) : null;
    int? userTypeId;
    if (!byName) {
      metaBytes.readUint8();
      userTypeId = metaBytes.readVarUint32();
    }
    final fields = <FieldInfo>[];
    for (var i = 0; i < fieldCount; i += 1) {
      fields.add(_readTypeDefField(metaBytes));
    }
    final resolved = userTypeId != null
        ? resolveUserById(userTypeId)
        : resolveUserByEncodedName(
            encodedNamespace!,
            encodedTypeName!,
          );
    if (resolved.kind != RegistrationKind.struct) {
      return resolved;
    }
    final remoteTypeDef = TypeDef(
      evolving: true,
      fields: List<FieldInfo>.unmodifiable(fields),
      header: header.value,
      encoded: Uint8List(0),
    );
    final localTypeDef = resolved.typeDef;
    if (localTypeDef != null && _sameTypeDef(localTypeDef, remoteTypeDef)) {
      return resolved;
    }
    return TypeInfo(
      type: resolved.type,
      kind: resolved.kind,
      typeId: resolved.typeId,
      supportsRef: resolved.supportsRef,
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
    final identifier = isTag
        ? tagId.toString()
        : decodeFieldName(source.readBytes(size), encoding);
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
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: typeId == TypeIds.unknown ? true : false,
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

  TypeInfo resolveBuiltinWireType(int wireTypeId) {
    switch (wireTypeId) {
      case TypeIds.boolType:
        return _builtin(bool, TypeIds.boolType);
      case TypeIds.int8:
        return _builtin(Int8, TypeIds.int8);
      case TypeIds.int16:
        return _builtin(Int16, TypeIds.int16);
      case TypeIds.int32:
        return _builtin(Int32, TypeIds.int32);
      case TypeIds.varInt32:
        return _builtin(Int32, TypeIds.varInt32);
      case TypeIds.int64:
        return _builtin(int, TypeIds.int64);
      case TypeIds.varInt64:
        return _builtin(int, TypeIds.varInt64);
      case TypeIds.taggedInt64:
        return _builtin(int, TypeIds.taggedInt64);
      case TypeIds.uint8:
        return _builtin(UInt8, TypeIds.uint8);
      case TypeIds.uint16:
        return _builtin(UInt16, TypeIds.uint16);
      case TypeIds.uint32:
        return _builtin(UInt32, TypeIds.uint32);
      case TypeIds.varUint32:
        return _builtin(UInt32, TypeIds.varUint32);
      case TypeIds.uint64:
        return _builtin(int, TypeIds.uint64);
      case TypeIds.varUint64:
        return _builtin(int, TypeIds.varUint64);
      case TypeIds.taggedUint64:
        return _builtin(int, TypeIds.taggedUint64);
      case TypeIds.float16:
        return _builtin(Float16, TypeIds.float16);
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
      case TypeIds.binary:
        return _builtin(Uint8List, TypeIds.binary);
      case TypeIds.date:
        return _builtin(LocalDate, TypeIds.date);
      case TypeIds.timestamp:
        return _builtin(Timestamp, TypeIds.timestamp);
      case TypeIds.boolArray:
        return _builtin(List<bool>, TypeIds.boolArray);
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
        return _builtin(Uint16List, TypeIds.float16Array);
      case TypeIds.float32Array:
        return _builtin(Float32List, TypeIds.float32Array);
      case TypeIds.float64Array:
        return _builtin(Float64List, TypeIds.float64Array);
      default:
        throw StateError('Unsupported builtin wire type id $wireTypeId.');
    }
  }

  TypeInfo _builtin(Type type, int typeId) {
    final cached = _builtinByTypeId[typeId];
    if (cached != null) {
      return cached;
    }
    final resolved = TypeInfo(
      type: type,
      kind: RegistrationKind.builtin,
      typeId: typeId,
      supportsRef: TypeIds.supportsRef(typeId),
      serializer: _builtinSerializerFor(typeId),
      structSerializer: null,
      userTypeId: null,
      namespace: null,
      typeName: null,
      encodedNamespace: null,
      encodedTypeName: null,
      typeDef: null,
      remoteTypeDef: null,
    );
    _builtinByTypeId[typeId] = resolved;
    return resolved;
  }

  Serializer<Object?> _builtinSerializerFor(int typeId) {
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
      case TypeIds.float32:
        return float32Serializer as Serializer<Object?>;
      case TypeIds.float64:
        return float64Serializer as Serializer<Object?>;
      case TypeIds.string:
        return stringSerializer as Serializer<Object?>;
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
      case TypeIds.timestamp:
        return timestampSerializer as Serializer<Object?>;
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
    if (type == Int8) {
      return TypeIds.int8;
    }
    if (type == Int16) {
      return TypeIds.int16;
    }
    if (type == Int32) {
      return TypeIds.varInt32;
    }
    if (type == int) {
      return TypeIds.varInt64;
    }
    if (type == UInt8) {
      return TypeIds.uint8;
    }
    if (type == UInt16) {
      return TypeIds.uint16;
    }
    if (type == UInt32) {
      return TypeIds.uint32;
    }
    if (type == Uint64List) {
      return TypeIds.uint64Array;
    }
    if (type == Float16) {
      return TypeIds.float16;
    }
    if (type == Float32) {
      return TypeIds.float32;
    }
    if (type == double) {
      return TypeIds.float64;
    }
    if (type == String) {
      return TypeIds.string;
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
    if (type == Timestamp) {
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
            LinkedHashMap<EncodedMetaString, TypeInfo>.identity())[
        resolved.encodedTypeName!] = resolved;
  }

  void _validateRegistrationMode({
    required int? id,
    required String? namespace,
    required String? typeName,
  }) {
    final hasNumeric = id != null;
    final hasNamed = namespace != null || typeName != null;
    if (hasNumeric == hasNamed) {
      throw ArgumentError(
        'Exactly one registration mode is required: id, or namespace + typeName.',
      );
    }
    if (hasNamed && (namespace == null || typeName == null)) {
      throw ArgumentError(
        'Both namespace and typeName are required for named registration.',
      );
    }
  }

  static String _nameKey(String namespace, String typeName) =>
      '$namespace::$typeName';

  EncodedMetaString _canonicalMetaString(
    EncodedMetaString encoded,
  ) {
    return internEncodedMetaString(
      encoded.bytes,
      encoding: encoded.encoding,
    );
  }

  void _rememberNamedType(int wireTypeId, TypeInfo resolved) {
    if (wireTypeId < _lastNamedTypeByWireType.length) {
      _lastNamedTypeByWireType[wireTypeId] = resolved;
    }
    final namespace = resolved.encodedNamespace;
    final typeName = resolved.encodedTypeName;
    if (namespace == null || typeName == null) {
      return;
    }
    final slot = _namedTypeLookupCacheIndex(wireTypeId, namespace, typeName) &
        (_namedTypeLookupCache.length - 1);
    _namedTypeLookupCache[slot] = _NamedTypeReadCacheEntry(
      wireTypeId,
      namespace,
      typeName,
      resolved,
    );
  }

  int _namedTypeLookupCacheIndex(
    int wireTypeId,
    EncodedMetaString namespace,
    EncodedMetaString typeName,
  ) {
    return Object.hash(wireTypeId, namespace.hash, typeName.hash);
  }

  bool _sameTypeDef(
    TypeDef left,
    TypeDef right,
  ) {
    if (left.evolving != right.evolving ||
        left.fields.length != right.fields.length) {
      return false;
    }
    for (var index = 0; index < left.fields.length; index += 1) {
      final leftField = left.fields[index];
      final rightField = right.fields[index];
      if (leftField.identifier != rightField.identifier ||
          leftField.id != rightField.id ||
          !_sameFieldType(leftField.fieldType, rightField.fieldType)) {
        return false;
      }
    }
    return true;
  }

  bool _sameFieldType(FieldType left, FieldType right) {
    if (left.typeId != right.typeId ||
        left.nullable != right.nullable ||
        left.ref != right.ref ||
        left.dynamic != right.dynamic ||
        left.arguments.length != right.arguments.length) {
      return false;
    }
    for (var index = 0; index < left.arguments.length; index += 1) {
      if (!_sameFieldType(left.arguments[index], right.arguments[index])) {
        return false;
      }
    }
    return true;
  }

  bool _matchesNamedWireType(TypeInfo resolved, int wireTypeId) {
    if (!resolved.isNamed) {
      return false;
    }
    switch (resolved.kind) {
      case RegistrationKind.enumType:
        return wireTypeId == TypeIds.namedEnum;
      case RegistrationKind.struct:
        return wireTypeId == TypeIds.namedStruct;
      case RegistrationKind.ext:
        return wireTypeId == TypeIds.namedExt;
      case RegistrationKind.union:
        return wireTypeId == TypeIds.namedUnion;
      case RegistrationKind.builtin:
        return false;
    }
  }
}

final class _NamedTypeReadCacheEntry {
  final int wireTypeId;
  final EncodedMetaString namespace;
  final EncodedMetaString typeName;
  final TypeInfo resolved;

  const _NamedTypeReadCacheEntry(
    this.wireTypeId,
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
