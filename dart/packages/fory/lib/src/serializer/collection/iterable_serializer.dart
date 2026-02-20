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

import 'package:fory/src/const/ref_flag.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serialization_context.dart';

abstract base class IterableSerializer extends Serializer<Iterable> {
  static const int trackingRefFlag = 0x01;
  static const int hasNullFlag = 0x02;
  static const int isDeclElementTypeFlag = 0x04;
  static const int isSameTypeFlag = 0x08;

  static const int declSameTypeTrackingRef =
      isDeclElementTypeFlag | isSameTypeFlag | trackingRefFlag;
  static const int declSameTypeHasNull =
      isDeclElementTypeFlag | isSameTypeFlag | hasNullFlag;
  static const int declSameTypeNotHasNull =
      isDeclElementTypeFlag | isSameTypeFlag;

  const IterableSerializer(super.objType, super.writeRef);

  @override
  void write(ByteWriter bw, Iterable v, SerializationContext pack) {
    final int len = v.length;
    bw.writeVarUint32Small7(len);
    if (len == 0) {
      return;
    }
    TypeSpecWrap? elemWrap = pack.typeWrapStack.peek?.param0;
    final ({int flags, Serializer? serializer}) header =
        _writeElementsHeader(bw, v, elemWrap, pack);
    if (elemWrap != null && elemWrap.hasGenericsParam) {
      pack.typeWrapStack.push(elemWrap);
    }

    int flags = header.flags;
    Serializer? serializer = header.serializer;
    if ((flags & isSameTypeFlag) == isSameTypeFlag && serializer != null) {
      if ((flags & trackingRefFlag) == trackingRefFlag) {
        for (Object? elem in v) {
          pack.serializationDispatcher
              .writeWithSerializer(bw, serializer, elem, pack);
        }
      } else {
        if ((flags & hasNullFlag) == hasNullFlag) {
          for (Object? elem in v) {
            if (elem == null) {
              bw.writeInt8(RefFlag.NULL.id);
            } else {
              bw.writeInt8(RefFlag.UNTRACKED_NOT_NULL.id);
              serializer.write(bw, elem, pack);
            }
          }
        } else {
          for (Object? elem in v) {
            serializer.write(bw, elem as Object, pack);
          }
        }
      }
    } else {
      if ((flags & trackingRefFlag) == trackingRefFlag) {
        for (Object? elem in v) {
          pack.serializationDispatcher.writeDynamicWithRef(bw, elem, pack);
        }
      } else {
        if ((flags & hasNullFlag) == hasNullFlag) {
          for (Object? elem in v) {
            if (elem == null) {
              bw.writeInt8(RefFlag.NULL.id);
            } else {
              bw.writeInt8(RefFlag.UNTRACKED_NOT_NULL.id);
              pack.serializationDispatcher
                  .writeDynamicWithoutRef(bw, elem, pack);
            }
          }
        } else {
          for (Object? elem in v) {
            pack.serializationDispatcher
                .writeDynamicWithoutRef(bw, elem as Object, pack);
          }
        }
      }
    }

    if (elemWrap != null && elemWrap.hasGenericsParam) {
      pack.typeWrapStack.pop();
    }
  }

  ({int flags, Serializer? serializer}) _writeElementsHeader(ByteWriter bw,
      Iterable value, TypeSpecWrap? elemWrap, SerializationContext pack) {
    if (elemWrap != null) {
      if (elemWrap.serializationCertain && elemWrap.serializer != null) {
        Serializer serializer = elemWrap.serializer!;
        if (serializer.writeRef) {
          bw.writeUint8(declSameTypeTrackingRef);
          return (flags: declSameTypeTrackingRef, serializer: serializer);
        }
        int flags =
            _containsNull(value) ? declSameTypeHasNull : declSameTypeNotHasNull;
        bw.writeUint8(flags);
        return (flags: flags, serializer: serializer);
      }
      bool trackingRef =
          elemWrap.serializer?.writeRef ?? _isRefTrackingEnabled(pack);
      if (trackingRef) {
        return _writeTypeHeader(bw, value, elemWrap, pack);
      }
      return _writeTypeNullabilityHeader(bw, value, elemWrap, pack);
    }

    if (_isRefTrackingEnabled(pack)) {
      return _writeTypeHeader(bw, value, null, pack);
    }
    return _writeTypeNullabilityHeader(bw, value, null, pack);
  }

  ({int flags, Serializer? serializer}) _writeTypeHeader(ByteWriter bw,
      Iterable value, TypeSpecWrap? declareWrap, SerializationContext pack) {
    bool hasDifferentClass = false;
    Object? firstNonNull;
    Type? elemType;
    for (Object? elem in value) {
      if (elem == null) {
        continue;
      }
      if (elemType == null) {
        firstNonNull = elem;
        elemType = elem.runtimeType;
      } else if (elem.runtimeType != elemType) {
        hasDifferentClass = true;
        break;
      }
    }
    if (hasDifferentClass || firstNonNull == null) {
      bw.writeUint8(trackingRefFlag);
      return (flags: trackingRefFlag, serializer: null);
    }

    int flags = trackingRefFlag | isSameTypeFlag;
    Serializer? declaredSerializer = declareWrap?.serializer;
    if (declareWrap != null &&
        declaredSerializer != null &&
        elemType == declareWrap.type) {
      flags |= isDeclElementTypeFlag;
      bw.writeUint8(flags);
      return (flags: flags, serializer: declaredSerializer);
    }
    bw.writeUint8(flags);
    final typeInfo = pack.typeResolver.writeTypeInfo(bw, firstNonNull, pack);
    return (flags: flags, serializer: typeInfo.serializer);
  }

  ({int flags, Serializer? serializer}) _writeTypeNullabilityHeader(
      ByteWriter bw,
      Iterable value,
      TypeSpecWrap? declareWrap,
      SerializationContext pack) {
    int flags = 0;
    bool hasDifferentClass = false;
    Object? firstNonNull;
    Type? elemType;
    for (Object? elem in value) {
      if (elem == null) {
        flags |= hasNullFlag;
        continue;
      }
      if (elemType == null) {
        firstNonNull = elem;
        elemType = elem.runtimeType;
      } else if (!hasDifferentClass && elem.runtimeType != elemType) {
        hasDifferentClass = true;
      }
    }
    if (hasDifferentClass || firstNonNull == null) {
      bw.writeUint8(flags);
      return (flags: flags, serializer: null);
    }

    flags |= isSameTypeFlag;
    Serializer? declaredSerializer = declareWrap?.serializer;
    if (declareWrap != null &&
        declaredSerializer != null &&
        elemType == declareWrap.type) {
      flags |= isDeclElementTypeFlag;
      bw.writeUint8(flags);
      return (flags: flags, serializer: declaredSerializer);
    }
    bw.writeUint8(flags);
    final typeInfo = pack.typeResolver.writeTypeInfo(bw, firstNonNull, pack);
    return (flags: flags, serializer: typeInfo.serializer);
  }

  bool _isRefTrackingEnabled(SerializationContext pack) {
    return !identical(pack.refResolver, pack.noRefResolver);
  }

  bool _containsNull(Iterable value) {
    for (Object? elem in value) {
      if (elem == null) {
        return true;
      }
    }
    return false;
  }
}
