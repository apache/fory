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

import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/exception/deserialization_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/field_def.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/meta/specs/type_spec.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/meta/specs/field_sorter.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/custom_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/util/string_util.dart';

final class ClassSerializerCache extends SerializerCache {
  const ClassSerializerCache();

  @override
  ClassSerializer getSerializerWithSpec(
      ForyConfig conf, covariant TypeSpec spec, Type dartType) {
    List<TypeSpecWrap> typeWraps = TypeSpecWrap.ofList(spec.fields);
    return ClassSerializer(
      spec.fields,
      spec.construct,
      spec.noArgConstruct,
      typeWraps,
      conf.compatible,
      conf.ref,
    );
  }
}

final class ClassSerializer extends CustomSerializer<Object> {
  static const ClassSerializerCache cache = ClassSerializerCache();

  late final List<FieldSpec> _fields;
  final HasArgsCons? _construct;
  final NoArgsCons? _noArgConstruct;
  late final List<TypeSpecWrap> _fieldTypeWraps;
  final bool _compatible;

  late final int _fromForyHash;
  late final int _toForyHash;

  bool _hashComputed = false;
  bool _fieldSerializersComputed = false;

  ClassSerializer(
    List<FieldSpec> fields,
    this._construct,
    this._noArgConstruct,
    List<TypeSpecWrap> fieldTypeWraps,
    this._compatible,
    bool refWrite,
  ) : super(
          ObjType.NAMED_STRUCT,
          refWrite,
        ) {
    if (_construct == null) {
      final List<int> sortedIndices = FieldSorter.sortedIndices(fields);
      _fields = FieldSorter.reorderByIndices<FieldSpec>(fields, sortedIndices);
      _fieldTypeWraps = FieldSorter.reorderByIndices<TypeSpecWrap>(
          fieldTypeWraps, sortedIndices);
    } else {
      _fields = fields;
      _fieldTypeWraps = fieldTypeWraps;
    }
  }

  StructHashPair getHashPairForTest(StructHashResolver structHashResolver,
      String Function(Type type) getTagByDartType) {
    return structHashResolver.computeHash(_fields, getTagByDartType);
  }

  @override
  Object read(ByteReader br, int refId, DeserializationContext pack) {
    if (!_fieldSerializersComputed) {
      pack.typeResolver.bindSerializers(_fieldTypeWraps);
      _fieldSerializersComputed = true;
    }
    if (!_compatible && !_hashComputed) {
      var pair =
          pack.structHashResolver.computeHash(_fields, pack.getTagByDartType);
      _fromForyHash = pair.fromForyHash;
      _toForyHash = pair.toForyHash;
      _hashComputed = true;
    }
    if (!_compatible) {
      int readFHash = br.readInt32();
      if (readFHash != _fromForyHash) {
        throw ForyMismatchException(
          readFHash,
          _fromForyHash,
          'The field hash read from bytes does not match the expected hash.',
        );
      }
    }
    if (_noArgConstruct == null) {
      return _byParameterizedCons(br, refId, pack);
    }
    Object obj = _noArgConstruct();
    pack.refResolver.setRefTheLatestId(
        obj); // Need to ref immediately to prevent subsequent circular references and for normal reference tracking
    final List<FieldDef>? remoteDefs = pack.currentRemoteFieldDefs;
    if (_compatible && remoteDefs != null) {
      pack.currentRemoteFieldDefs = null;
      _readByRemoteFieldOrder(br, obj, remoteDefs, pack, setter: true);
      return obj;
    }
    for (int i = 0; i < _fields.length; ++i) {
      FieldSpec fieldSpec = _fields[i];
      if (!fieldSpec.includeFromFory) continue;
      TypeSpecWrap typeWrap = _fieldTypeWraps[i];
      bool hasGenericsParam = typeWrap.hasGenericsParam;
      if (hasGenericsParam) {
        pack.typeWrapStack.push(typeWrap);
      }
      late Object? fieldValue;
      Serializer? serializer = _fieldTypeWraps[i].serializer;
      if (serializer == null) {
        if (fieldSpec.trackingRef || typeWrap.nullable) {
          fieldValue =
              pack.deserializationDispatcher.readDynamicWithRef(br, pack);
        } else {
          fieldValue =
              pack.deserializationDispatcher.readDynamicWithoutRef(br, pack);
        }
      } else if (fieldSpec.trackingRef || typeWrap.nullable) {
        fieldValue = pack.deserializationDispatcher.readWithSerializer(
            br, serializer, pack,
            trackingRefOverride: fieldSpec.trackingRef);
      } else {
        fieldValue = serializer.read(br, -1, pack);
      }
      assert(fieldSpec.setter != null);
      fieldSpec.setter!(obj, fieldValue);
      if (hasGenericsParam) {
        pack.typeWrapStack.pop();
      }
    }
    return obj;
  }

  @override
  void write(ByteWriter bw, Object v, SerializationContext pack) {
    if (!_fieldSerializersComputed) {
      pack.typeResolver.bindSerializers(_fieldTypeWraps);
      _fieldSerializersComputed = true;
    }
    if (!_compatible && !_hashComputed) {
      var pair =
          pack.structHashResolver.computeHash(_fields, pack.getTagByDartType);
      _fromForyHash = pair.fromForyHash;
      _toForyHash = pair.toForyHash;
      _hashComputed = true;
    }
    if (!_compatible) {
      bw.writeInt32(_toForyHash);
    }
    for (int i = 0; i < _fields.length; ++i) {
      FieldSpec fieldSpec = _fields[i];
      if (!fieldSpec.includeToFory) continue;
      TypeSpecWrap typeWrap = _fieldTypeWraps[i];
      bool hasGenericsParam = typeWrap.hasGenericsParam;
      if (hasGenericsParam) {
        pack.typeWrapStack.push(typeWrap);
      }
      Object? fieldValue = fieldSpec.getter!(v);
      Serializer? serializer = typeWrap.serializer;
      if (serializer == null) {
        if (fieldSpec.trackingRef || typeWrap.nullable) {
          pack.serializationDispatcher
              .writeDynamicWithRef(bw, fieldValue, pack);
        } else {
          pack.serializationDispatcher
              .writeDynamicWithoutRef(bw, fieldValue as Object, pack);
        }
      } else if (fieldSpec.trackingRef || typeWrap.nullable) {
        pack.serializationDispatcher.writeWithSerializer(
            bw, serializer, fieldValue, pack,
            trackingRefOverride: fieldSpec.trackingRef);
      } else {
        serializer.write(bw, fieldValue!, pack);
      }
      if (hasGenericsParam) {
        pack.typeWrapStack.pop();
      }
    }
  }

  Object _byParameterizedCons(
      ByteReader br, int refId, DeserializationContext pack) {
    final List<FieldDef>? remoteDefs = pack.currentRemoteFieldDefs;
    if (_compatible && remoteDefs != null) {
      pack.currentRemoteFieldDefs = null;
      final List<Object?> args = List.filled(_fields.length, null);
      _readByRemoteFieldOrder(br, args, remoteDefs, pack, setter: false);
      Object obj = _construct!(args);
      if (refId >= 0) {
        pack.refResolver.setRef(refId, obj);
      }
      return obj;
    }
    List<Object?> args = List.filled(_fields.length, null);
    for (int i = 0; i < _fields.length; ++i) {
      FieldSpec fieldSpec = _fields[i];
      if (!fieldSpec.includeFromFory) continue;
      TypeSpecWrap typeWrap = _fieldTypeWraps[i];
      bool hasGenericsParam = typeWrap.hasGenericsParam;
      if (hasGenericsParam) {
        pack.typeWrapStack.push(typeWrap);
      }
      Serializer? serializer = typeWrap.serializer;
      if (serializer == null) {
        if (fieldSpec.trackingRef || typeWrap.nullable) {
          args[i] = pack.deserializationDispatcher.readDynamicWithRef(br, pack);
        } else {
          args[i] =
              pack.deserializationDispatcher.readDynamicWithoutRef(br, pack);
        }
      } else if (fieldSpec.trackingRef || typeWrap.nullable) {
        args[i] = pack.deserializationDispatcher.readWithSerializer(
            br, serializer, pack,
            trackingRefOverride: fieldSpec.trackingRef);
      } else {
        args[i] = serializer.read(br, -1, pack);
      }
      if (hasGenericsParam) {
        pack.typeWrapStack.pop();
      }
    }
    // Here, ref is created after completion. In fact, it may not correctly resolve circular references,
    // but it can reach here because the user guarantees that this class will not appear in circular references through promiseAcyclic
    Object obj = _construct!(args);
    if (refId >= 0) {
      pack.refResolver.setRef(refId, obj);
    }
    return obj;
  }

  void _readByRemoteFieldOrder(ByteReader br, dynamic target,
      List<FieldDef> remoteDefs, DeserializationContext pack,
      {required bool setter}) {
    final Map<String, int> nameToIndex = {};
    for (int i = 0; i < _fields.length; ++i) {
      if (!_fields[i].includeFromFory) continue;
      final String canonical =
          StringUtil.lowerCamelToLowerUnderscore(_fields[i].name);
      nameToIndex[canonical] = i;
    }
    final TypeResolver resolver = pack.typeResolver;
    for (final FieldDef remote in remoteDefs) {
      final Object? value =
          _readFieldValueByRemoteType(br, remote, pack, resolver);
      final String name = remote.name;
      final int? localIndex = nameToIndex[name];
      if (localIndex != null) {
        if (setter) {
          assert(_fields[localIndex].setter != null);
          _fields[localIndex].setter!(target, value);
        } else {
          (target as List<Object?>)[localIndex] = value;
        }
      }
    }
  }

  Object? _readFieldValueByRemoteType(
      ByteReader br, FieldDef fieldDef, DeserializationContext pack,
      TypeResolver resolver) {
    final RemoteFieldType rft = fieldDef.fieldType;
    if (rft is SimpleRemoteFieldType) {
      final TypeInfo? typeInfo = resolver.getTypeInfoByObjTypeId(rft.typeId);
      if (typeInfo == null) {
        _throwUnsupportedTypeId(rft.typeId);
      }
      return pack.deserializationDispatcher.readByTypeInfo(
          br, typeInfo, -1, pack,
          trackingRefOverride: fieldDef.trackingRef);
    }
    if (rft is ListSetRemoteFieldType) {
      final TypeInfo? collectionTypeInfo =
          resolver.getTypeInfoByObjTypeId(rft.typeId);
      if (collectionTypeInfo == null) {
        _throwUnsupportedTypeId(rft.typeId);
      }
      final TypeInfo? elementTypeInfo =
          _typeInfoForRemoteFieldType(rft.elementType, resolver);
      if (elementTypeInfo == null) {
        _throwUnsupportedTypeId(rft.elementType.typeId);
      }
      pack.typeWrapStack
          .push(TypeSpecWrap.forRemote(elementTypeInfo.serializer,
              nullable: fieldDef.nullable));
      try {
        return pack.deserializationDispatcher.readByTypeInfo(
            br, collectionTypeInfo, -1, pack,
            trackingRefOverride: fieldDef.trackingRef);
      } finally {
        pack.typeWrapStack.pop();
      }
    }
    if (rft is MapRemoteFieldType) {
      final TypeInfo? mapTypeInfo =
          resolver.getTypeInfoByObjTypeId(ObjType.MAP.id);
      if (mapTypeInfo == null) {
        _throwUnsupportedTypeId(ObjType.MAP.id);
      }
      final TypeInfo? keyTypeInfo =
          _typeInfoForRemoteFieldType(rft.keyType, resolver);
      final TypeInfo? valueTypeInfo =
          _typeInfoForRemoteFieldType(rft.valueType, resolver);
      if (keyTypeInfo == null || valueTypeInfo == null) {
        _throwUnsupportedTypeId(rft.typeId);
      }
      pack.typeWrapStack
          .push(TypeSpecWrap.forRemote(keyTypeInfo.serializer,
              nullable: fieldDef.nullable));
      pack.typeWrapStack
          .push(TypeSpecWrap.forRemote(valueTypeInfo.serializer,
              nullable: fieldDef.nullable));
      try {
        return pack.deserializationDispatcher.readByTypeInfo(
            br, mapTypeInfo, -1, pack,
            trackingRefOverride: fieldDef.trackingRef);
      } finally {
        pack.typeWrapStack.pop();
        pack.typeWrapStack.pop();
      }
    }
    _throwUnsupportedTypeId(rft.typeId);
  }

  Never _throwUnsupportedTypeId(int typeId) {
    final ObjType? objType = ObjType.fromId(typeId);
    if (objType != null) {
      throw UnsupportedTypeException(objType);
    }
    throw ForyMismatchException(
        typeId,
        -1,
        'Unknown type id from remote TypeDef: $typeId');
  }

  TypeInfo? _typeInfoForRemoteFieldType(
      RemoteFieldType rft, TypeResolver resolver) {
    if (rft is SimpleRemoteFieldType) {
      return resolver.getTypeInfoByObjTypeId(rft.typeId);
    }
    if (rft is ListSetRemoteFieldType) {
      return resolver.getTypeInfoByObjTypeId(rft.typeId);
    }
    if (rft is MapRemoteFieldType) {
      return resolver.getTypeInfoByObjTypeId(ObjType.MAP.id);
    }
    return resolver.getTypeInfoByObjTypeId(rft.typeId);
  }
}
