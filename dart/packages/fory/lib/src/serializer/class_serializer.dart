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
import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/exception/deserialization_exception.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/meta/specs/class_spec.dart';
import 'package:fory/src/meta/specs/field_spec.dart';
import 'package:fory/src/resolver/struct_hash_resolver.dart';
import 'package:fory/src/serializer/custom_serializer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serializer_pack.dart';

final class ClassSerializerCache extends SerializerCache {
  const ClassSerializerCache();

  @override
  ClassSerializer getSerializerWithSpec(
      ForyConfig conf, covariant ClassSpec spec, Type dartType) {
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

  final List<FieldSpec> _fields;
  final HasArgsCons? _construct;
  final NoArgsCons? _noArgConstruct;
  final List<TypeSpecWrap> _fieldTypeWraps;
  final bool _compatible;

  late final int _fromForyHash;
  late final int _toForyHash;

  bool _hashComputed = false;
  bool _fieldsSersComputed = false;

  ClassSerializer(
    this._fields,
    this._construct,
    this._noArgConstruct,
    this._fieldTypeWraps,
    this._compatible,
    bool refWrite,
  ) : super(
          ObjType.NAMED_STRUCT,
          refWrite,
        );

  StructHashPair getHashPairForTest(StructHashResolver structHashResolver,
      String Function(Type type) getTagByDartType) {
    return structHashResolver.computeHash(_fields, getTagByDartType);
  }

  @override
  Object read(ByteReader br, int refId, DeserializerPack pack) {
    if (!_fieldsSersComputed) {
      pack.typeResolver.bindSerializers(_fieldTypeWraps);
      _fieldsSersComputed = true;
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
    for (int i = 0; i < _fields.length; ++i) {
      FieldSpec fieldSpec = _fields[i];
      if (!fieldSpec.includeFromFory) continue;
      TypeSpecWrap typeWrap = _fieldTypeWraps[i];
      bool hasGenericsParam = typeWrap.hasGenericsParam;
      if (hasGenericsParam) {
        pack.typeWrapStack.push(typeWrap);
      }
      late Object? fieldValue;
      Serializer? ser = _fieldTypeWraps[i].ser;
      if (ser == null) {
        fieldValue =
            pack.deserializationCoordinator.readDynamicWithRef(br, pack);
      } else if (typeWrap.nullable) {
        fieldValue =
            pack.deserializationCoordinator.readWithSerializer(br, ser, pack);
      } else {
        fieldValue = ser.read(br, -1, pack);
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
  void write(ByteWriter bw, Object v, SerializerPack pack) {
    if (!_fieldsSersComputed) {
      pack.typeResolver.bindSerializers(_fieldTypeWraps);
      _fieldsSersComputed = true;
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
      Serializer? ser = typeWrap.ser;
      if (ser == null) {
        pack.serializationCoordinator.writeDynamicWithRef(bw, fieldValue, pack);
      } else if (typeWrap.nullable) {
        pack.serializationCoordinator
            .writeWithSerializer(bw, ser, fieldValue, pack);
      } else {
        ser.write(bw, fieldValue!, pack);
      }
      if (hasGenericsParam) {
        pack.typeWrapStack.pop();
      }
    }
  }

  Object _byParameterizedCons(ByteReader br, int refId, DeserializerPack pack) {
    List<Object?> args = List.filled(_fields.length, null);
    for (int i = 0; i < _fields.length; ++i) {
      FieldSpec fieldSpec = _fields[i];
      if (!fieldSpec.includeFromFory) continue;
      TypeSpecWrap typeWrap = _fieldTypeWraps[i];
      bool hasGenericsParam = typeWrap.hasGenericsParam;
      if (hasGenericsParam) {
        pack.typeWrapStack.push(typeWrap);
      }
      Serializer? ser = typeWrap.ser;
      if (ser == null) {
        args[i] = pack.deserializationCoordinator.readDynamicWithRef(br, pack);
      } else if (typeWrap.nullable) {
        args[i] =
            pack.deserializationCoordinator.readWithSerializer(br, ser, pack);
      } else {
        args[i] = ser.read(br, -1, pack);
      }
      if (hasGenericsParam) {
        pack.typeWrapStack.pop();
      }
    }
    // Here, ref is created after completion. In fact, it may not correctly resolve circular references,
    // but it can reach here because the user guarantees that this class will not appear in circular references through promiseAcyclic
    Object obj = _construct!(args);
    pack.refResolver.setRef(refId, obj);
    return obj;
  }
}
