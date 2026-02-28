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
import 'package:fory/src/codec/encoders.dart';
import 'package:fory/src/codec/meta_string_decoder.dart';
import 'package:fory/src/codec/meta_string_encoder.dart';
import 'package:fory/src/codec/meta_string_encoding.dart';
import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/collection/long_long_key.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/fory_exception.dart'
    show
        RegistrationArgumentException,
        UnregisteredTagException,
        UnregisteredTypeException;
import 'package:fory/src/fory_context.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/meta_string_byte.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/meta/specs/type_spec.dart';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/meta/specs/field_sorter.dart';
import 'package:fory/src/meta/specs/field_type_spec.dart';
import 'package:fory/src/resolver/dart_type_resolver.dart';
import 'package:fory/src/resolver/meta_string_resolver.dart';
import 'package:fory/src/resolver/spec_lookup.dart';
import 'package:fory/src/resolver/tag_string_resolver.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/class_serializer.dart';
import 'package:fory/src/serializer/enum_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/util/murmur3hash.dart';
import 'package:fory/src/util/string_util.dart';

import '../../fory_exception.dart'
    show UnsupportedTypeException;

final class TypeResolverImpl extends TypeResolver {
  static const int _metaSizeMask = 0xff;
  static const int _hasFieldsMetaFlag = 1 << 8;
  static const int _compressMetaFlag = 1 << 9;
  static const int _smallFieldThreshold = 31;
  static const int _registerByNameFlag = 32;
  static const int _fieldNameSizeThreshold = 15;
  static const int _bigNameThreshold = 63;
  static const int _seed47 = 47;
  static const int _hashMask50Bits = 0x3ffffffffffff;
  static const int _allBits64Mask = 0xffffffffffffffff;

  static const List<MetaStringEncoding> _packageNameAllowedEncodings =
      <MetaStringEncoding>[
    MetaStringEncoding.utf8,
    MetaStringEncoding.atls,
    MetaStringEncoding.luds,
  ];
  static const List<MetaStringEncoding> _typeNameAllowedEncodings =
      <MetaStringEncoding>[
    MetaStringEncoding.utf8,
    MetaStringEncoding.atls,
    MetaStringEncoding.luds,
    MetaStringEncoding.ftls,
  ];
  static const List<MetaStringEncoding> _fieldNameAllowedEncodings =
      <MetaStringEncoding>[
    MetaStringEncoding.utf8,
    MetaStringEncoding.atls,
    MetaStringEncoding.luds,
  ];

  static const DartTypeResolver dartTypeResolver = DartTypeResolver.I;
  final ForyContext _ctx;
  final MetaStringResolver _msResolver;
  final TagStringResolver _tstrEncoder;
  final Map<LongLongKey, TypeInfo> _tagHash2Info;
  final MetaStringEncoder _packageNameEncoder;
  final MetaStringEncoder _typeNameEncoder;
  final MetaStringEncoder _fieldNameEncoder;
  final MetaStringDecoder _packageNameDecoder;
  final MetaStringDecoder _typeNameDecoder;
  final Map<Type, CustomTypeSpec> _type2Spec;
  final Map<Type, int> _writeTypeToIndex;
  final List<TypeInfo> _readTypeInfos;
  final Map<Type, Uint8List> _typeToEncodedTypeDef;

  TypeResolverImpl(
    super.conf,
  )   : _tagHash2Info = HashMap<LongLongKey, TypeInfo>(),
        _packageNameEncoder = Encoders.packageEncoder,
        _typeNameEncoder = Encoders.typeNameEncoder,
        _fieldNameEncoder = Encoders.typeNameEncoder,
        _packageNameDecoder = Encoders.packageDecoder,
        _typeNameDecoder = Encoders.typeNameDecoder,
        _type2Spec = HashMap<Type, CustomTypeSpec>(),
        _writeTypeToIndex = HashMap<Type, int>(),
        _readTypeInfos = <TypeInfo>[],
        _typeToEncodedTypeDef = HashMap<Type, Uint8List>(),
        _msResolver = MetaStringResolver.newInst,
        _tstrEncoder = TagStringResolver.newInst,
        _ctx = ForyContext(conf) {
    _ctx.initForDefaultTypes();
  }

  @override
  void registerType(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    final CustomTypeSpec spec = _resolveSpec(type);
    _registerResolvedSpec(spec,
        typeId: typeId, namespace: namespace, typename: typename);
  }

  @override
  void registerStruct(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    final CustomTypeSpec spec = _resolveSpec(type);
    if (!_isStructSpec(spec.objType)) {
      throw RegistrationArgumentException(
          'registerStruct requires a struct type, got ${spec.objType} for $type');
    }
    _registerResolvedSpec(spec,
        typeId: typeId, namespace: namespace, typename: typename);
  }

  @override
  void registerEnum(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    final CustomTypeSpec spec = _resolveSpec(type);
    if (!_isEnumSpec(spec.objType)) {
      throw RegistrationArgumentException(
          'registerEnum requires an enum type, got ${spec.objType} for $type');
    }
    _registerResolvedSpec(spec,
        typeId: typeId, namespace: namespace, typename: typename);
  }

  @override
  void registerUnion(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    final CustomTypeSpec spec = _resolveSpec(type);
    if (!_isUnionSpec(spec.objType)) {
      throw RegistrationArgumentException(
          'registerUnion requires a union type, got ${spec.objType} for $type');
    }
    _registerResolvedSpec(spec,
        typeId: typeId, namespace: namespace, typename: typename);
  }

  CustomTypeSpec _resolveSpec(Type type) {
    final CustomTypeSpec? cachedSpec = _type2Spec[type];
    if (cachedSpec != null) {
      return cachedSpec;
    }
    final CustomTypeSpec? lookedUpSpec = SpecLookup.resolve(type);
    if (lookedUpSpec == null) {
      throw RegistrationArgumentException(
          'No generated schema found for type $type. Ensure generated code is available and imported.');
    }
    if (lookedUpSpec.dartType != type) {
      throw RegistrationArgumentException(
          'Resolved schema type mismatch for $type, got ${lookedUpSpec.dartType}.');
    }
    return lookedUpSpec;
  }

  void _registerResolvedSpec(
    CustomTypeSpec spec, {
    int? typeId,
    String? namespace,
    String? typename,
  }) {
    if (typeId != null) {
      if (namespace != null || typename != null) {
        throw RegistrationArgumentException(
            'typeId cannot be used with namespace/typename');
      }
      _regWithTypeId(spec, typeId);
      return;
    }

    if (typename == null && namespace != null) {
      throw RegistrationArgumentException(
          'namespace cannot be set when typename is null');
    }

    if (typename == null) {
      final String defaultName = spec.dartType.toString();
      _regWithNamespace(spec, defaultName, defaultName);
      return;
    }

    if (namespace != null) {
      final String fullName =
          namespace.isEmpty ? typename : '$namespace.$typename';
      _regWithNamespace(spec, fullName, typename, namespace);
      return;
    }

    final int separator = typename.lastIndexOf('.');
    if (separator == -1) {
      _regWithNamespace(spec, typename, typename);
      return;
    }
    final String inferredNamespace = typename.substring(0, separator);
    final String simpleName = typename.substring(separator + 1);
    _regWithNamespace(spec, typename, simpleName, inferredNamespace);
  }

  bool _isStructSpec(ObjType objType) {
    return objType == ObjType.NAMED_STRUCT ||
        objType == ObjType.STRUCT ||
        objType == ObjType.NAMED_COMPATIBLE_STRUCT ||
        objType == ObjType.COMPATIBLE_STRUCT;
  }

  bool _isEnumSpec(ObjType objType) {
    return objType == ObjType.NAMED_ENUM || objType == ObjType.ENUM;
  }

  bool _isUnionSpec(ObjType objType) {
    return objType == ObjType.UNION ||
        objType == ObjType.TYPED_UNION ||
        objType == ObjType.NAMED_UNION;
  }

  @override
  void registerSerializer(Type type, Serializer serializer) {
    TypeInfo? typeInfo = _ctx.type2TypeInfo[type];
    if (typeInfo == null) {
      throw UnregisteredTypeException(type);
    }
    typeInfo.serializer = serializer;
  }

  void _regWithNamespace(CustomTypeSpec spec, String tag, String tn,
      [String ns = '']) {
    ObjType resolvedObjType = _resolveObjTypeForTagRegistration(spec.objType);
    MetaStringBytes tnMsb = _msResolver.getOrCreateMetaStringBytes(
      _tstrEncoder.encodeTypeName(tn),
    );
    MetaStringBytes nsMsb = _msResolver.getOrCreateMetaStringBytes(
      _tstrEncoder.encodeTypeName(ns),
    );
    TypeInfo typeInfo = TypeInfo(
      spec.dartType,
      resolvedObjType,
      tag,
      tnMsb,
      nsMsb,
    );
    typeInfo.serializer = _getSerializerFor(spec);
    _ctx.registerType(typeInfo);
    _type2Spec[typeInfo.dartType] = spec;
  }

  void _regWithTypeId(CustomTypeSpec spec, int userTypeId) {
    final int normalizedTypeId = userTypeId & 0xFFFFFFFF;
    ObjType resolvedObjType =
        _resolveObjTypeForTypeIdRegistration(spec.objType);
    TypeInfo typeInfo = TypeInfo(
      spec.dartType,
      resolvedObjType,
      null,
      null,
      null,
      userTypeId: normalizedTypeId,
    );
    typeInfo.serializer = _getSerializerFor(spec);
    _ctx.registerType(typeInfo);
    _type2Spec[typeInfo.dartType] = spec;
    if (resolvedObjType == ObjType.STRUCT) {
      _ctx.userTypeId2TypeInfo[
              LongLongKey(ObjType.COMPATIBLE_STRUCT.id, normalizedTypeId)] =
          typeInfo;
    } else if (resolvedObjType == ObjType.COMPATIBLE_STRUCT) {
      _ctx.userTypeId2TypeInfo[
          LongLongKey(ObjType.STRUCT.id, normalizedTypeId)] = typeInfo;
    }
  }

  ObjType _resolveObjTypeForTagRegistration(ObjType specObjType) {
    switch (specObjType) {
      case ObjType.NAMED_ENUM:
      case ObjType.ENUM:
        return ObjType.NAMED_ENUM;
      case ObjType.NAMED_STRUCT:
      case ObjType.STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
      case ObjType.COMPATIBLE_STRUCT:
        return _ctx.conf.compatible
            ? ObjType.NAMED_COMPATIBLE_STRUCT
            : ObjType.NAMED_STRUCT;
      case ObjType.NAMED_EXT:
      case ObjType.EXT:
        return ObjType.NAMED_EXT;
      default:
        throw RegistrationArgumentException(specObjType);
    }
  }

  ObjType _resolveObjTypeForTypeIdRegistration(ObjType specObjType) {
    switch (specObjType) {
      case ObjType.NAMED_ENUM:
      case ObjType.ENUM:
        return ObjType.ENUM;
      case ObjType.NAMED_STRUCT:
      case ObjType.STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
      case ObjType.COMPATIBLE_STRUCT:
        return _ctx.conf.compatible
            ? ObjType.COMPATIBLE_STRUCT
            : ObjType.STRUCT;
      case ObjType.NAMED_EXT:
      case ObjType.EXT:
        return ObjType.EXT;
      default:
        throw RegistrationArgumentException(specObjType);
    }
  }

  /// The ClassSerializer generated here will not analyze the corresponding serializer for each TypeArg.
  /// There are two considerations for this:
  /// First, it intends to delay the specific analysis until the first parsing of this Class,
  /// to prevent too many tasks from being executed at the beginning.
  /// Second, if the serializer corresponding to the arg is parsed here,
  /// many Enums may still be registered later, and they cannot be recognized here,
  /// resulting in an error that they are not registered even though they are.
  Serializer _getSerializerFor(CustomTypeSpec spec) {
    if (spec.objType == ObjType.NAMED_ENUM || spec.objType == ObjType.ENUM) {
      Serializer serializer = EnumSerializer.cache
          .getSerializerWithSpec(_ctx.conf, spec, spec.dartType);
      return serializer;
    }
    // Indicates ClassSerializer
    return ClassSerializer.cache
        .getSerializerWithSpec(_ctx.conf, spec as TypeSpec, spec.dartType);
  }

  /// This type must be a user-defined class or enum
  @override
  @inline
  String getRegisteredTag(Type type) {
    String? tag = _ctx.type2TypeInfo[type]?.tag;
    if (tag == null) {
      throw UnregisteredTypeException(type);
    }
    return tag;
  }

  @override
  Serializer getRegisteredSerializer(Type type) {
    TypeInfo? typeInfo = _ctx.type2TypeInfo[type];
    if (typeInfo == null) {
      throw UnregisteredTypeException(type);
    }
    return typeInfo.serializer;
  }

  @override
  void bindSerializers(List<TypeSpecWrap> typeWraps) {
    TypeSpecWrap wrap;
    for (int i = 0; i < typeWraps.length; ++i) {
      wrap = typeWraps[i];
      if (wrap.serializationCertain) {
        wrap.serializer = _ctx.type2TypeInfo[wrap.type]!.serializer;
      } else if (wrap.objType == ObjType.LIST) {
        wrap.serializer = _ctx.abstractListSerializer;
      } else if (wrap.objType == ObjType.SET) {
        wrap.serializer = _ctx.abstractSetSerializer;
      } else if (wrap.objType == ObjType.MAP) {
        wrap.serializer = _ctx.abstractMapSerializer;
      }
      // At this point, serializer is not set, serializer is still null
      bindSerializers(wrap.genericsArgs);
    }
  }

  @override
  void resetWriteContext() {
    _writeTypeToIndex.clear();
  }

  @override
  void resetReadContext() {
    _readTypeInfos.clear();
  }

  @override
  TypeInfo readTypeInfo(ByteReader br) {
    int xtypeId = br.readUint8();
    ObjType? xtype = ObjType.fromId(xtypeId);
    if (xtype == null) {
      throw UnregisteredTypeException('xtypeId=$xtypeId');
    }
    switch (xtype) {
      case ObjType.ENUM:
      case ObjType.STRUCT:
      case ObjType.EXT:
        int userTypeId = br.readVarUint32();
        TypeInfo? idTypeInfo =
            _ctx.userTypeId2TypeInfo[LongLongKey(xtypeId, userTypeId)];
        if (idTypeInfo == null && xtype == ObjType.STRUCT) {
          idTypeInfo = _ctx.userTypeId2TypeInfo[
              LongLongKey(ObjType.COMPATIBLE_STRUCT.id, userTypeId)];
        } else if (idTypeInfo == null && xtype == ObjType.COMPATIBLE_STRUCT) {
          idTypeInfo = _ctx
              .userTypeId2TypeInfo[LongLongKey(ObjType.STRUCT.id, userTypeId)];
        }
        if (idTypeInfo != null) {
          return idTypeInfo;
        }
        throw UnregisteredTypeException(
            '${xtype.name}(userTypeId=$userTypeId)');
      case ObjType.COMPATIBLE_STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
        return _readSharedTypeMeta(br);
      case ObjType.NAMED_ENUM:
      case ObjType.NAMED_STRUCT:
      case ObjType.NAMED_EXT:
      case ObjType.NAMED_UNION:
        if (_ctx.conf.compatible) {
          return _readSharedTypeMeta(br);
        }
        MetaStringBytes pkgBytes = _msResolver.readMetaStringBytes(br);
        // assert(pkgBytes.length == 0); // fory dart does not support package
        MetaStringBytes simpleClassNameBytes =
            _msResolver.readMetaStringBytes(br);
        LongLongKey key =
            LongLongKey(pkgBytes.hashCode, simpleClassNameBytes.hashCode);
        TypeInfo? typeInfo = _tagHash2Info[key];
        if (typeInfo != null) {
          // Indicates that it has been registered
          return typeInfo;
        }
        typeInfo = _getAndCacheSpecByBytes(key, pkgBytes, simpleClassNameBytes);
        // _tagHash2Info[key] = typeInfo;
        return typeInfo;
      default:
        // Indicates built-in type
        TypeInfo? typeInfo = _ctx.objTypeId2TypeInfo[xtypeId];
        if (typeInfo != null) {
          return typeInfo;
        } else {
          throw UnsupportedTypeException(xtype);
        }
    }
  }

  TypeInfo _getAndCacheSpecByBytes(
    LongLongKey key,
    MetaStringBytes packageBytes,
    MetaStringBytes simpleClassNameBytes,
  ) {
    String tn = _msResolver.decodeTypename(simpleClassNameBytes);
    String ns = _msResolver.decodeNamespace(packageBytes);
    String qualifiedName = StringUtil.addingTypeNameAndNs(ns, tn);
    TypeInfo? typeInfo = _ctx.tag2TypeInfo[qualifiedName];
    if (typeInfo == null) {
      // TODO: Does not support non-existent or unknown class, foryJava seems to have some support
      throw UnregisteredTagException(qualifiedName);
    }
    _tagHash2Info[key] = typeInfo;
    return typeInfo;
  }

  @override
  TypeInfo writeTypeInfo(ByteWriter bw, Object obj, SerializationContext pack) {
    Type dartType = dartTypeResolver.getForyType(obj);
    TypeInfo? typeInfo = _ctx.type2TypeInfo[dartType];
    if (typeInfo == null) {
      throw UnregisteredTypeException(dartType);
    }
    bw.writeUint8(typeInfo.objType.id);
    switch (typeInfo.objType) {
      case ObjType.ENUM:
      case ObjType.STRUCT:
      case ObjType.EXT:
      case ObjType.TYPED_UNION:
        bw.writeVarUint32(typeInfo.userTypeId);
        break;
      case ObjType.COMPATIBLE_STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
        _writeSharedTypeMeta(bw, typeInfo);
        break;
      case ObjType.NAMED_ENUM:
      case ObjType.NAMED_STRUCT:
      case ObjType.NAMED_EXT:
      case ObjType.NAMED_UNION:
        if (_ctx.conf.compatible) {
          _writeSharedTypeMeta(bw, typeInfo);
        } else {
          pack.msWritingResolver.writeMetaStringBytes(bw, typeInfo.nsBytes!);
          pack.msWritingResolver
              .writeMetaStringBytes(bw, typeInfo.typeNameBytes!);
        }
        break;
      default:
        break;
    }
    return typeInfo;
  }

  TypeInfo _readSharedTypeMeta(ByteReader br) {
    final int marker = br.readVarUint32();
    final bool isRef = (marker & 1) == 1;
    final int index = marker >>> 1;
    if (isRef) {
      if (index < 0 || index >= _readTypeInfos.length) {
        throw UnregisteredTypeException(
            'Shared type index out of bounds: $index');
      }
      return _readTypeInfos[index];
    }
    final int id = br.readInt64();
    final int unsignedId = id & _allBits64Mask;
    int size = unsignedId & _metaSizeMask;
    if (size == _metaSizeMask) {
      size += br.readVarUint32();
    }
    final Uint8List bodyBytes = br.copyBytes(size);
    if ((unsignedId & _compressMetaFlag) != 0) {
      throw UnregisteredTypeException('Compressed TypeDef is not supported');
    }
    final TypeInfo typeInfo = _readTypeInfoFromTypeDefBody(bodyBytes);
    _readTypeInfos.add(typeInfo);
    return typeInfo;
  }

  TypeInfo _readTypeInfoFromTypeDefBody(Uint8List bodyBytes) {
    final ByteReader bodyReader = ByteReader.forBytes(bodyBytes);
    int header = bodyReader.readUint8();
    int numFields = header & _smallFieldThreshold;
    if (numFields == _smallFieldThreshold) {
      numFields += bodyReader.readVarUint32Small7();
    }
    if ((header & _registerByNameFlag) != 0) {
      final String namespace = _readPackageName(bodyReader);
      final String typeName = _readTypeName(bodyReader);
      final String qualifiedName =
          StringUtil.addingTypeNameAndNs(namespace, typeName);
      final TypeInfo? typeInfo = _ctx.tag2TypeInfo[qualifiedName];
      if (typeInfo == null) {
        throw UnregisteredTagException(qualifiedName);
      }
      return typeInfo;
    }
    final int typeId = bodyReader.readUint8();
    final int userTypeId = bodyReader.readVarUint32();
    final TypeInfo? typeInfo = _lookupTypeInfoByUserTypeId(typeId, userTypeId);
    if (typeInfo == null) {
      throw UnregisteredTypeException('typeId=$typeId,userTypeId=$userTypeId');
    }
    return typeInfo;
  }

  TypeInfo? _lookupTypeInfoByUserTypeId(int typeId, int userTypeId) {
    TypeInfo? typeInfo =
        _ctx.userTypeId2TypeInfo[LongLongKey(typeId, userTypeId)];
    if (typeInfo == null && typeId == ObjType.STRUCT.id) {
      typeInfo = _ctx.userTypeId2TypeInfo[
          LongLongKey(ObjType.COMPATIBLE_STRUCT.id, userTypeId)];
    } else if (typeInfo == null && typeId == ObjType.COMPATIBLE_STRUCT.id) {
      typeInfo =
          _ctx.userTypeId2TypeInfo[LongLongKey(ObjType.STRUCT.id, userTypeId)];
    }
    return typeInfo;
  }

  String _readPackageName(ByteReader br) {
    return _readTypeNameInternal(
        br, _packageNameDecoder, _packageNameEncodingByFlag);
  }

  String _readTypeName(ByteReader br) {
    return _readTypeNameInternal(br, _typeNameDecoder, _typeNameEncodingByFlag);
  }

  String _readTypeNameInternal(
    ByteReader br,
    MetaStringDecoder decoder,
    MetaStringEncoding Function(int) getEncodingByFlag,
  ) {
    final int header = br.readUint8();
    final int encodingFlag = header & 3;
    final MetaStringEncoding encoding = getEncodingByFlag(encodingFlag);
    int size = header >>> 2;
    if (size == _bigNameThreshold) {
      size += br.readVarUint32Small7();
    }
    final Uint8List bytes = br.readBytesView(size);
    return decoder.decode(bytes, encoding);
  }

  void _writeSharedTypeMeta(ByteWriter bw, TypeInfo typeInfo) {
    final int? existingIndex = _writeTypeToIndex[typeInfo.dartType];
    if (existingIndex != null) {
      bw.writeVarUint32((existingIndex << 1) | 1);
      return;
    }
    final int index = _writeTypeToIndex.length;
    _writeTypeToIndex[typeInfo.dartType] = index;
    bw.writeVarUint32(index << 1);
    final Uint8List typeDef = _typeToEncodedTypeDef.putIfAbsent(
      typeInfo.dartType,
      () => _encodeTypeDefFor(typeInfo),
    );
    bw.writeBytes(typeDef);
  }

  Uint8List _encodeTypeDefFor(TypeInfo typeInfo) {
    final CustomTypeSpec? spec = _type2Spec[typeInfo.dartType];
    if (spec == null) {
      throw UnregisteredTypeException(typeInfo.dartType);
    }
    final List<FieldSpec> fields = _fieldsForTypeDef(spec);
    final Uint8List body = _buildTypeDefBody(typeInfo, fields);
    final int bodySize = body.length;
    final int hash50 =
        Murmur3Hash.hash128x64(body, bodySize, 0, _seed47).$1 & _hashMask50Bits;
    int id = _toSignedInt64(hash50 << 14);
    id &= ~(_metaSizeMask | _hasFieldsMetaFlag | _compressMetaFlag);
    if (fields.isNotEmpty) {
      id |= _hasFieldsMetaFlag;
    }
    if (bodySize >= _metaSizeMask) {
      id |= _metaSizeMask;
    } else {
      id |= bodySize;
    }
    id = _toSignedInt64(id);

    final ByteWriter writer = ByteWriter();
    writer.writeInt64(id);
    if (bodySize >= _metaSizeMask) {
      writer.writeVarUint32(bodySize - _metaSizeMask);
    }
    writer.writeBytes(body);
    return writer.takeBytes();
  }

  List<FieldSpec> _fieldsForTypeDef(CustomTypeSpec spec) {
    if (spec is! TypeSpec) {
      return const <FieldSpec>[];
    }
    final List<FieldSpec> fields = <FieldSpec>[];
    for (int i = 0; i < spec.fields.length; ++i) {
      final FieldSpec field = spec.fields[i];
      if (field.includeToFory) {
        fields.add(field);
      }
    }
    return FieldSorter.sort(fields);
  }

  Uint8List _buildTypeDefBody(TypeInfo typeInfo, List<FieldSpec> fields) {
    final ByteWriter writer = ByteWriter();
    int header = fields.length >= _smallFieldThreshold
        ? _smallFieldThreshold
        : fields.length;
    final bool registerByName = typeInfo.tag != null;
    if (registerByName) {
      header |= _registerByNameFlag;
    }
    writer.writeUint8(header);
    if (fields.length >= _smallFieldThreshold) {
      writer.writeVarUint32Small7(fields.length - _smallFieldThreshold);
    }
    if (registerByName) {
      final String ns = _msResolver.decodeNamespace(typeInfo.nsBytes!);
      final String typeName =
          _msResolver.decodeTypename(typeInfo.typeNameBytes!);
      _writePackageName(writer, ns);
      _writeTypeName(writer, typeName);
    } else {
      writer.writeUint8(typeInfo.objType.id);
      writer.writeVarUint32(typeInfo.userTypeId);
    }
    for (int i = 0; i < fields.length; ++i) {
      _writeTypeDefField(writer, fields[i]);
    }
    return writer.takeBytes();
  }

  void _writePackageName(ByteWriter writer, String value) {
    final meta = _packageNameEncoder.encodeByAllowedEncodings(
        value, _packageNameAllowedEncodings);
    _writeName(writer, meta.bytes, _packageNameEncodingFlag(meta.encoding));
  }

  void _writeTypeName(ByteWriter writer, String value) {
    final meta = _typeNameEncoder.encodeByAllowedEncodings(
        value, _typeNameAllowedEncodings);
    _writeName(writer, meta.bytes, _typeNameEncodingFlag(meta.encoding));
  }

  void _writeName(ByteWriter writer, Uint8List encodedBytes, int encodingFlag) {
    final int size = encodedBytes.length;
    if (size >= _bigNameThreshold) {
      writer.writeUint8((_bigNameThreshold << 2) | encodingFlag);
      writer.writeVarUint32Small7(size - _bigNameThreshold);
    } else {
      writer.writeUint8((size << 2) | encodingFlag);
    }
    writer.writeBytes(encodedBytes);
  }

  void _writeTypeDefField(ByteWriter writer, FieldSpec field) {
    final String fieldName = StringUtil.lowerCamelToLowerUnderscore(field.name);
    final meta = _fieldNameEncoder.encodeByAllowedEncodings(
        fieldName, _fieldNameAllowedEncodings);
    final Uint8List encodedName = meta.bytes;
    final int encodingFlag = _fieldNameEncodingFlag(meta.encoding);
    int size = encodedName.length - 1;
    int header = encodingFlag << 6;
    if (field.trackingRef) {
      header |= 1;
    }
    if (field.typeSpec.nullable) {
      header |= 2;
    }
    if (size >= _fieldNameSizeThreshold) {
      header |= (_fieldNameSizeThreshold << 2);
      writer.writeUint8(header);
      writer.writeVarUint32Small7(size - _fieldNameSizeThreshold);
    } else {
      header |= (size << 2);
      writer.writeUint8(header);
    }
    final int typeId = _fieldTypeId(field.typeSpec);
    writer.writeUint8(typeId);
    _writeNestedTypeInfo(writer, field.typeSpec);
    writer.writeBytes(encodedName);
  }

  void _writeNestedTypeInfo(ByteWriter writer, FieldTypeSpec typeSpec) {
    final int typeId = _fieldTypeId(typeSpec);
    switch (ObjType.fromId(typeId)) {
      case ObjType.LIST:
      case ObjType.SET:
        final FieldTypeSpec elem = typeSpec.genericsArgs.isNotEmpty
            ? typeSpec.genericsArgs[0]
            : const FieldTypeSpec(
                Object, ObjType.UNKNOWN, true, false, null, <FieldTypeSpec>[]);
        _writeNestedFieldTypeHeader(writer, elem);
        _writeNestedTypeInfo(writer, elem);
        break;
      case ObjType.MAP:
        final FieldTypeSpec key = typeSpec.genericsArgs.isNotEmpty
            ? typeSpec.genericsArgs[0]
            : const FieldTypeSpec(
                Object, ObjType.UNKNOWN, true, false, null, <FieldTypeSpec>[]);
        final FieldTypeSpec value = typeSpec.genericsArgs.length > 1
            ? typeSpec.genericsArgs[1]
            : const FieldTypeSpec(
                Object, ObjType.UNKNOWN, true, false, null, <FieldTypeSpec>[]);
        _writeNestedFieldTypeHeader(writer, key);
        _writeNestedTypeInfo(writer, key);
        _writeNestedFieldTypeHeader(writer, value);
        _writeNestedTypeInfo(writer, value);
        break;
      default:
        break;
    }
  }

  void _writeNestedFieldTypeHeader(ByteWriter writer, FieldTypeSpec typeSpec) {
    int header = _fieldTypeId(typeSpec) << 2;
    if (typeSpec.nullable) {
      header |= 2;
    }
    writer.writeVarUint32Small7(header);
  }

  int _fieldTypeId(FieldTypeSpec typeSpec) {
    switch (typeSpec.objType) {
      case ObjType.NAMED_ENUM:
        return ObjType.ENUM.id;
      case ObjType.NAMED_EXT:
        return ObjType.EXT.id;
      case ObjType.TYPED_UNION:
      case ObjType.NAMED_UNION:
        return ObjType.UNION.id;
      case ObjType.INT32:
        return ObjType.VAR_INT32.id;
      case ObjType.INT64:
        return ObjType.VAR_INT64.id;
      case ObjType.STRUCT:
      case ObjType.COMPATIBLE_STRUCT:
      case ObjType.NAMED_STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
        final TypeInfo? typeInfo = _ctx.type2TypeInfo[typeSpec.type];
        if (typeInfo != null && typeInfo.objType.isStructType()) {
          return typeInfo.objType.id;
        }
        if (_ctx.conf.compatible) {
          return typeSpec.objType == ObjType.NAMED_STRUCT ||
                  typeSpec.objType == ObjType.NAMED_COMPATIBLE_STRUCT
              ? ObjType.NAMED_COMPATIBLE_STRUCT.id
              : ObjType.COMPATIBLE_STRUCT.id;
        }
        return typeSpec.objType == ObjType.NAMED_STRUCT ||
                typeSpec.objType == ObjType.NAMED_COMPATIBLE_STRUCT
            ? ObjType.NAMED_STRUCT.id
            : ObjType.STRUCT.id;
      default:
        return typeSpec.objType.id;
    }
  }

  int _packageNameEncodingFlag(MetaStringEncoding encoding) {
    switch (encoding) {
      case MetaStringEncoding.utf8:
        return 0;
      case MetaStringEncoding.atls:
        return 1;
      case MetaStringEncoding.luds:
        return 2;
      default:
        throw RegistrationArgumentException(encoding);
    }
  }

  int _typeNameEncodingFlag(MetaStringEncoding encoding) {
    switch (encoding) {
      case MetaStringEncoding.utf8:
        return 0;
      case MetaStringEncoding.atls:
        return 1;
      case MetaStringEncoding.luds:
        return 2;
      case MetaStringEncoding.ftls:
        return 3;
      default:
        throw RegistrationArgumentException(encoding);
    }
  }

  int _fieldNameEncodingFlag(MetaStringEncoding encoding) {
    switch (encoding) {
      case MetaStringEncoding.utf8:
        return 0;
      case MetaStringEncoding.atls:
        return 1;
      case MetaStringEncoding.luds:
        return 2;
      default:
        throw RegistrationArgumentException(encoding);
    }
  }

  MetaStringEncoding _packageNameEncodingByFlag(int flag) {
    switch (flag) {
      case 0:
        return MetaStringEncoding.utf8;
      case 1:
        return MetaStringEncoding.atls;
      case 2:
        return MetaStringEncoding.luds;
      default:
        throw RegistrationArgumentException(flag);
    }
  }

  MetaStringEncoding _typeNameEncodingByFlag(int flag) {
    switch (flag) {
      case 0:
        return MetaStringEncoding.utf8;
      case 1:
        return MetaStringEncoding.atls;
      case 2:
        return MetaStringEncoding.luds;
      case 3:
        return MetaStringEncoding.ftls;
      default:
        throw RegistrationArgumentException(flag);
    }
  }

  int _toSignedInt64(int value) {
    return value.toSigned(64);
  }

  // for test only
  @override
  StructHashPair getHashPairForTest(Type type) {
    TypeInfo? typeInfo = _ctx.type2TypeInfo[type];
    if (typeInfo == null) {
      throw UnregisteredTypeException(type);
    }
    ClassSerializer serializer = typeInfo.serializer as ClassSerializer;
    StructHashPair pair = serializer.getHashPairForTest(
      StructHashResolver.inst,
      getRegisteredTag,
    );
    return pair;
  }
}
