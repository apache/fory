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
import 'package:fory/src/collection/long_long_key.dart';
import 'package:fory/src/const/obj_type.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/exception/registration_exception.dart' show UnregisteredTagException, UnregisteredTypeException;
import 'package:fory/src/fory_context.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/meta_string_byte.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/meta/specs/class_spec.dart';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/resolver/dart_type_resolver.dart';
import 'package:fory/src/resolver/meta_string_resolver.dart';
import 'package:fory/src/resolver/tag_str_encode_resolver.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/xtype_resolver.dart';
import 'package:fory/src/serializer/class_serializer.dart';
import 'package:fory/src/serializer/enum_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer_pack.dart';
import 'package:fory/src/util/string_util.dart';

import '../../exception/deserialization_exception.dart' show UnsupportedTypeException;

final class XtypeResolverImpl extends XtypeResolver {
  static const DartTypeResolver dartTypeResolver = DartTypeResolver.I;
  final ForyContext _ctx;
  final MetaStringResolver _msResolver;
  final TagStringEncodeResolver _tstrEncoder;
  final Map<LongLongKey, TypeInfo> _tagHash2Info;

  XtypeResolverImpl(
    super.conf,
  )
  : _tagHash2Info = HashMap<LongLongKey, TypeInfo>(),
    _msResolver = MetaStringResolver.newInst,
    _tstrEncoder = TagStringEncodeResolver.newInst,
    _ctx = ForyContext(conf) {
    _ctx.initForDefaultTypes();
  }

  @override
  void reg(CustomTypeSpec spec, [String? tag]) {
    if (tag == null){
      String typeName = spec.dartType.toString();
      _regWithNamespace(spec, typeName, typeName);
      return;
    }
    int idx = tag.lastIndexOf('.');
    if (idx == -1) {
      _regWithNamespace(spec, tag, tag);
    }else{
      String ns = tag.substring(0, idx);
      String tn = tag.substring(idx + 1);
      _regWithNamespace(spec, tag, tn, ns);
    }
  }

  @override
  void registerSerializer(Type type, Serializer ser) {
    TypeInfo? typeInfo = _ctx.type2TypeInfo[type];
    if (typeInfo == null){
      throw UnregisteredTypeException(type);
    }
    typeInfo.ser = ser;
  }

  void _regWithNamespace(CustomTypeSpec spec, String tag, String tn, [String ns= '']) {
    assert(spec.objType == ObjType.NAMED_STRUCT || spec.objType == ObjType.NAMED_ENUM);
    MetaStringBytes tnMsb = _msResolver.getOrCreateMetaStringBytes(
      _tstrEncoder.encodeTypeName(tn),
    );
    MetaStringBytes nsMsb = _msResolver.getOrCreateMetaStringBytes(
      _tstrEncoder.encodeTypeName(ns),
    );
    TypeInfo typeInfo = TypeInfo(
      spec.dartType,
      spec.objType,
      tag,
      tnMsb,
      nsMsb,
    );
    typeInfo.ser = _getSerFor(spec);
    _ctx.reg(typeInfo);
  }


  /// The ClassSer generated here will not analyze the corresponding ser for each TypeArg.
  /// There are two considerations for this: 
  /// First, it intends to delay the specific analysis until the first parsing of this Class, 
  /// to prevent too many tasks from being executed at the beginning.
  /// Second, if the Ser corresponding to the arg is parsed here, 
  /// many Enums may still be registered later, and they cannot be recognized here, 
  /// resulting in an error that they are not registered even though they are.
  Serializer _getSerFor(CustomTypeSpec spec) {
    if (spec.objType == ObjType.NAMED_ENUM){
      Serializer ser = EnumSerializer.cache.getSerializerWithSpec(_ctx.conf, spec, spec.dartType);
      return ser;
    }
    // Indicates ClassSer
    return ClassSerializer.cache.getSerializerWithSpec(_ctx.conf, spec as ClassSpec, spec.dartType);
  }

  /// This type must be a user-defined class or enum
  @override
  @inline
  String getTagByCustomDartType(Type type) {
    String? tag = _ctx.type2TypeInfo[type]?.tag;
    if (tag == null){
      throw UnregisteredTypeException(type);
    }
    return tag;
  }

  @override
  void setSersForTypeWrap(List<TypeSpecWrap> typeWraps) {
    TypeSpecWrap wrap;
    for (int i = 0; i < typeWraps.length; ++i){
      wrap = typeWraps[i];
      if (wrap.certainForSer){
        wrap.ser = _ctx.type2TypeInfo[wrap.type]!.ser;
      }else if (wrap.objType == ObjType.LIST){
        wrap.ser = _ctx.abstractListSer;
      }else if (wrap.objType == ObjType.MAP) {
        wrap.ser = _ctx.abstractMapSer;
      }
      // At this point, ser is not set, ser is still null
      setSersForTypeWrap(wrap.genericsArgs);
    }
  }

  @override
  TypeInfo readTypeInfo(ByteReader br) {
    int xtypeId = br.readUint8();
    ObjType xtype = ObjType.fromId(xtypeId)!;
    int userTypeId = kInvalidUserTypeId;
    if (xtype.needsUserTypeId()) {
      userTypeId = br.readVarUint32();
    }
    switch(xtype){
      case ObjType.ENUM:
      case ObjType.STRUCT:
      case ObjType.COMPATIBLE_STRUCT:
      case ObjType.EXT:
        TypeInfo? idTypeInfo = _ctx.userTypeId2TypeInfo[LongLongKey(xtypeId, userTypeId)];
        if (idTypeInfo != null) {
          return idTypeInfo;
        }
        throw UnregisteredTypeException(xtype);
      case ObjType.NAMED_ENUM:
      case ObjType.NAMED_STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
      case ObjType.NAMED_EXT:
        MetaStringBytes pkgBytes = _msResolver.readMetaStringBytes(br);
        // assert(pkgBytes.length == 0); // fory dart does not support package
        MetaStringBytes simpleClassNameBytes = _msResolver.readMetaStringBytes(br);
        LongLongKey key = LongLongKey(pkgBytes.hashCode, simpleClassNameBytes.hashCode);
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
      // TODO: Does not support non-existent class, foryJava seems to have some support
      throw UnregisteredTagException(qualifiedName);
    }
    _tagHash2Info[key] = typeInfo;
    return typeInfo;
  }

  @override
  TypeInfo writeGetTypeInfo(ByteWriter bw, Object obj, SerializerPack pack){
    Type dartType = dartTypeResolver.getForyType(obj);
    TypeInfo? typeInfo = _ctx.type2TypeInfo[dartType];
    if (typeInfo == null){
      throw UnregisteredTypeException(dartType);
    }
    bw.writeUint8(typeInfo.objType.id);
    if (typeInfo.objType.needsUserTypeId()) {
      if (typeInfo.userTypeId == kInvalidUserTypeId) {
        throw UnregisteredTypeException(dartType);
      }
      bw.writeVarUint32(typeInfo.userTypeId);
    }
    switch(typeInfo.objType){
      case ObjType.NAMED_ENUM:
      case ObjType.NAMED_STRUCT:
      case ObjType.NAMED_COMPATIBLE_STRUCT:
      case ObjType.NAMED_EXT:
        pack.msWritingResolver.writeMetaStringBytes(bw, typeInfo.nsBytes!);
        pack.msWritingResolver.writeMetaStringBytes(bw, typeInfo.typeNameBytes!);
        break;
      default:
        break;
    }
    return typeInfo;
  }
  
  // for test only
  @override
  StructHashPair getHashPairForTest(Type type) {
    TypeInfo? typeInfo = _ctx.type2TypeInfo[type];
    if (typeInfo == null){
      throw UnregisteredTypeException(type);
    }
    ClassSerializer ser = typeInfo.ser as ClassSerializer;
    StructHashPair pair = ser.getHashPairForTest(
      StructHashResolver.inst,
      getTagByCustomDartType,
    );
    return pair;
  }
}
