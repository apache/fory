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

import 'package:fory/src/deserialization_context.dart';
import 'package:fory/src/exception/deserialization_exception.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/const/types.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serialization_context.dart';
import 'package:fory/src/serializer/serializer.dart';

abstract base class MapSerializer<T extends Map<Object?, Object?>>
    extends Serializer<Map<Object?, Object?>> {
  static const int _maxChunkSize = 255;

  static const int _trackingKeyRef = 0x01;
  static const int _keyHasNull = 0x02;
  static const int _keyDeclType = 0x04;
  static const int _trackingValueRef = 0x08;
  static const int _valueHasNull = 0x10;
  static const int _valueDeclType = 0x20;

  static const int _kvNull = _keyHasNull | _valueHasNull;
  static const int _nullKeyValueDeclType = _keyHasNull | _valueDeclType;
  static const int _nullKeyValueDeclTypeTrackingRef =
      _keyHasNull | _valueDeclType | _trackingValueRef;
  static const int _nullValueKeyDeclType = _valueHasNull | _keyDeclType;
  static const int _nullValueKeyDeclTypeTrackingRef =
      _valueHasNull | _keyDeclType | _trackingKeyRef;

  const MapSerializer(bool writeRef) : super(ObjType.MAP, writeRef);

  T newMap(int size);

  @override
  T read(ByteReader br, int refId, DeserializationContext pack) {
    int remaining = br.readVarUint32Small7();
    final int? maxCollectionSize = pack.config.maxCollectionSize;
    if (maxCollectionSize != null && remaining > maxCollectionSize) {
      throw DeserializationSizeException(
          'Collection', remaining, maxCollectionSize);
    }
    T map = newMap(remaining);
    if (writeRef) {
      pack.refResolver.setRefTheLatestId(map);
    }
    if (remaining == 0) {
      return map;
    }

    TypeSpecWrap? mapWrap = pack.typeWrapStack.peek;
    TypeSpecWrap? keyWrap = mapWrap?.param0;
    TypeSpecWrap? valueWrap = mapWrap?.param1;

    while (remaining > 0) {
      int chunkHeader = br.readUint8();
      bool keyHasNull = (chunkHeader & _keyHasNull) != 0;
      bool valueHasNull = (chunkHeader & _valueHasNull) != 0;
      if (keyHasNull || valueHasNull) {
        Object? key;
        Object? value;
        if (!keyHasNull) {
          key = _readNullChunkKey(br, chunkHeader, keyWrap, pack);
          value = null;
        } else if (!valueHasNull) {
          key = null;
          value = _readNullChunkValue(br, chunkHeader, valueWrap, pack);
        } else {
          key = null;
          value = null;
        }
        map[key] = value;
        --remaining;
        continue;
      }

      bool keyTrackRef = (chunkHeader & _trackingKeyRef) != 0;
      bool valueTrackRef = (chunkHeader & _trackingValueRef) != 0;
      bool keyDeclaredType = (chunkHeader & _keyDeclType) != 0;
      bool valueDeclaredType = (chunkHeader & _valueDeclType) != 0;
      int chunkSize = br.readUint8();

      Serializer keySerializer;
      if (keyDeclaredType) {
        if (keyWrap == null) {
          throw StateError(
              'Map key declared type flag set but key type is unavailable');
        }
        keySerializer = keyWrap.serializer ??
            pack.typeResolver.getRegisteredSerializer(keyWrap.type);
      } else {
        keySerializer = pack.typeResolver.readTypeInfo(br).serializer;
      }
      Serializer valueSerializer;
      if (valueDeclaredType) {
        if (valueWrap == null) {
          throw StateError(
              'Map value declared type flag set but value type is unavailable');
        }
        valueSerializer = valueWrap.serializer ??
            pack.typeResolver.getRegisteredSerializer(valueWrap.type);
      } else {
        valueSerializer = pack.typeResolver.readTypeInfo(br).serializer;
      }

      for (int i = 0; i < chunkSize; ++i) {
        Object? key =
            _readWithSerializer(br, keySerializer, keyTrackRef, pack, keyWrap);
        Object? value = _readWithSerializer(
            br, valueSerializer, valueTrackRef, pack, valueWrap);
        map[key] = value;
      }
      remaining -= chunkSize;
    }
    return map;
  }

  @override
  void write(ByteWriter bw, covariant T v, SerializationContext pack) {
    int mapSize = v.length;
    bw.writeVarUint32Small7(mapSize);
    if (mapSize == 0) {
      return;
    }

    TypeSpecWrap? mapWrap = pack.typeWrapStack.peek;
    TypeSpecWrap? keyWrap = mapWrap?.param0;
    TypeSpecWrap? valueWrap = mapWrap?.param1;

    Iterator<MapEntry<Object?, Object?>> iterator = v.entries.iterator;
    if (!iterator.moveNext()) {
      return;
    }
    MapEntry<Object?, Object?>? entry = iterator.current;

    while (entry != null) {
      while (entry != null) {
        Object? key = entry.key;
        Object? value = entry.value;
        if (key != null && value != null) {
          break;
        }
        _writeNullChunk(bw, key, value, keyWrap, valueWrap, pack);
        if (iterator.moveNext()) {
          entry = iterator.current;
        } else {
          entry = null;
        }
      }
      if (entry == null) {
        break;
      }
      entry = _writeNonNullChunk(bw, entry, iterator, keyWrap, valueWrap, pack);
    }
  }

  MapEntry<Object?, Object?>? _writeNonNullChunk(
    ByteWriter bw,
    MapEntry<Object?, Object?> entry,
    Iterator<MapEntry<Object?, Object?>> iterator,
    TypeSpecWrap? keyWrap,
    TypeSpecWrap? valueWrap,
    SerializationContext pack,
  ) {
    Object key0 = entry.key as Object;
    Object value0 = entry.value as Object;
    Type keyType = key0.runtimeType;
    Type valueType = value0.runtimeType;

    int chunkHeader = 0;
    ByteWriter chunkWriter = ByteWriter();
    Serializer keySerializer;
    Serializer valueSerializer;

    if (keyWrap != null &&
        keyWrap.serializationCertain &&
        keyWrap.serializer != null) {
      chunkHeader |= _keyDeclType;
      keySerializer = keyWrap.serializer!;
    } else {
      final typeInfo = pack.typeResolver.writeTypeInfo(chunkWriter, key0, pack);
      keySerializer = typeInfo.serializer;
    }
    if (valueWrap != null &&
        valueWrap.serializationCertain &&
        valueWrap.serializer != null) {
      chunkHeader |= _valueDeclType;
      valueSerializer = valueWrap.serializer!;
    } else {
      final typeInfo =
          pack.typeResolver.writeTypeInfo(chunkWriter, value0, pack);
      valueSerializer = typeInfo.serializer;
    }

    bool trackKeyRef = keySerializer.writeRef;
    bool trackValueRef = valueSerializer.writeRef;
    if (trackKeyRef) {
      chunkHeader |= _trackingKeyRef;
    }
    if (trackValueRef) {
      chunkHeader |= _trackingValueRef;
    }

    int chunkSize = 0;
    MapEntry<Object?, Object?>? current = entry;
    while (current != null) {
      Object? key = current.key;
      Object? value = current.value;
      if (key == null ||
          value == null ||
          key.runtimeType != keyType ||
          value.runtimeType != valueType) {
        break;
      }
      _writeWithSerializer(
          chunkWriter, key, keySerializer, trackKeyRef, pack, keyWrap);
      _writeWithSerializer(
          chunkWriter, value, valueSerializer, trackValueRef, pack, valueWrap);
      ++chunkSize;
      if (iterator.moveNext()) {
        current = iterator.current;
      } else {
        current = null;
        break;
      }
      if (chunkSize == _maxChunkSize) {
        break;
      }
    }

    bw.writeUint8(chunkHeader);
    bw.writeUint8(chunkSize);
    bw.writeBytes(chunkWriter.takeBytes());
    return current;
  }

  void _writeNullChunk(
    ByteWriter bw,
    Object? key,
    Object? value,
    TypeSpecWrap? keyWrap,
    TypeSpecWrap? valueWrap,
    SerializationContext pack,
  ) {
    if (key != null) {
      _writeNullValueChunk(bw, key, keyWrap, pack);
      return;
    }
    if (value != null) {
      _writeNullKeyChunk(bw, value, valueWrap, pack);
      return;
    }
    bw.writeUint8(_kvNull);
  }

  void _writeNullValueChunk(
    ByteWriter bw,
    Object key,
    TypeSpecWrap? keyWrap,
    SerializationContext pack,
  ) {
    Serializer? keySerializer = keyWrap?.serializer;
    if (keyWrap != null &&
        keyWrap.serializationCertain &&
        keySerializer != null) {
      bool trackingRef = keySerializer.writeRef;
      bw.writeUint8(trackingRef
          ? _nullValueKeyDeclTypeTrackingRef
          : _nullValueKeyDeclType);
      _writeWithSerializer(bw, key, keySerializer, trackingRef, pack, keyWrap);
      return;
    }
    bool trackingRef = keyWrap == null
        ? true
        : (keySerializer?.writeRef ?? _isRefTrackingEnabled(pack));
    bw.writeUint8(_valueHasNull | (trackingRef ? _trackingKeyRef : 0));
    _writeWithDynamic(bw, key, trackingRef, pack, keyWrap);
  }

  void _writeNullKeyChunk(
    ByteWriter bw,
    Object value,
    TypeSpecWrap? valueWrap,
    SerializationContext pack,
  ) {
    Serializer? valueSerializer = valueWrap?.serializer;
    if (valueWrap != null &&
        valueWrap.serializationCertain &&
        valueSerializer != null) {
      bool trackingRef = valueSerializer.writeRef;
      bw.writeUint8(trackingRef
          ? _nullKeyValueDeclTypeTrackingRef
          : _nullKeyValueDeclType);
      _writeWithSerializer(
          bw, value, valueSerializer, trackingRef, pack, valueWrap);
      return;
    }
    bool trackingRef = valueWrap == null
        ? true
        : (valueSerializer?.writeRef ?? _isRefTrackingEnabled(pack));
    bw.writeUint8(_keyHasNull | (trackingRef ? _trackingValueRef : 0));
    _writeWithDynamic(bw, value, trackingRef, pack, valueWrap);
  }

  Object? _readNullChunkKey(
    ByteReader br,
    int chunkHeader,
    TypeSpecWrap? keyWrap,
    DeserializationContext pack,
  ) {
    bool trackRef = (chunkHeader & _trackingKeyRef) != 0;
    bool keyDeclaredType = (chunkHeader & _keyDeclType) != 0;
    if (keyDeclaredType) {
      if (keyWrap == null) {
        throw StateError(
            'Map key declared type flag set but key type is unavailable');
      }
      Serializer keySerializer = keyWrap.serializer ??
          pack.typeResolver.getRegisteredSerializer(keyWrap.type);
      return _readWithSerializer(br, keySerializer, trackRef, pack, keyWrap);
    }
    return _readWithDynamic(br, trackRef, pack, keyWrap);
  }

  Object? _readNullChunkValue(
    ByteReader br,
    int chunkHeader,
    TypeSpecWrap? valueWrap,
    DeserializationContext pack,
  ) {
    bool trackRef = (chunkHeader & _trackingValueRef) != 0;
    bool valueDeclaredType = (chunkHeader & _valueDeclType) != 0;
    if (valueDeclaredType) {
      if (valueWrap == null) {
        throw StateError(
            'Map value declared type flag set but value type is unavailable');
      }
      Serializer valueSerializer = valueWrap.serializer ??
          pack.typeResolver.getRegisteredSerializer(valueWrap.type);
      return _readWithSerializer(
          br, valueSerializer, trackRef, pack, valueWrap);
    }
    return _readWithDynamic(br, trackRef, pack, valueWrap);
  }

  void _writeWithSerializer(
    ByteWriter bw,
    Object value,
    Serializer serializer,
    bool trackRef,
    SerializationContext pack,
    TypeSpecWrap? wrap,
  ) {
    bool pushed = _pushWrapForWrite(wrap, pack);
    if (trackRef) {
      pack.serializationDispatcher.writeWithSerializer(
          bw, serializer, value, pack,
          trackingRefOverride: true);
    } else {
      serializer.write(bw, value, pack);
    }
    if (pushed) {
      pack.typeWrapStack.pop();
    }
  }

  Object? _readWithSerializer(
    ByteReader br,
    Serializer serializer,
    bool trackRef,
    DeserializationContext pack,
    TypeSpecWrap? wrap,
  ) {
    bool pushed = _pushWrapForRead(wrap, pack);
    Object? value;
    if (trackRef) {
      value = pack.deserializationDispatcher
          .readWithSerializer(br, serializer, pack, trackingRefOverride: true);
    } else {
      value = serializer.read(br, -1, pack);
    }
    if (pushed) {
      pack.typeWrapStack.pop();
    }
    return value;
  }

  void _writeWithDynamic(
    ByteWriter bw,
    Object value,
    bool trackRef,
    SerializationContext pack,
    TypeSpecWrap? wrap,
  ) {
    bool pushed = _pushWrapForWrite(wrap, pack);
    if (trackRef) {
      pack.serializationDispatcher.writeDynamicWithRef(bw, value, pack);
    } else {
      pack.serializationDispatcher.writeDynamicWithoutRef(bw, value, pack);
    }
    if (pushed) {
      pack.typeWrapStack.pop();
    }
  }

  Object _readWithDynamic(
    ByteReader br,
    bool trackRef,
    DeserializationContext pack,
    TypeSpecWrap? wrap,
  ) {
    bool pushed = _pushWrapForRead(wrap, pack);
    Object value;
    if (trackRef) {
      value =
          pack.deserializationDispatcher.readDynamicWithRef(br, pack) as Object;
    } else {
      value = pack.deserializationDispatcher.readDynamicWithoutRef(br, pack);
    }
    if (pushed) {
      pack.typeWrapStack.pop();
    }
    return value;
  }

  bool _pushWrapForWrite(TypeSpecWrap? wrap, SerializationContext pack) {
    if (wrap != null && wrap.hasGenericsParam) {
      pack.typeWrapStack.push(wrap);
      return true;
    }
    return false;
  }

  bool _pushWrapForRead(TypeSpecWrap? wrap, DeserializationContext pack) {
    if (wrap != null && wrap.hasGenericsParam) {
      pack.typeWrapStack.push(wrap);
      return true;
    }
    return false;
  }

  bool _isRefTrackingEnabled(SerializationContext pack) {
    return !identical(pack.refResolver, pack.noRefResolver);
  }
}
