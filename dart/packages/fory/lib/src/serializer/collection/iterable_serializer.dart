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
import 'package:fory/src/serializer_pack.dart';

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
  void write(ByteWriter bw, Iterable v, SerializerPack pack) {
    final int len = v.length;
    bw.writeVarUint32Small7(len);
    if (len == 0) {
      return;
    }
    TypeSpecWrap? elemWrap = pack.typeWrapStack.peek?.param0;
    final ({int flags, Serializer? ser}) header =
        _writeElementsHeader(bw, v, elemWrap, pack);
    if (elemWrap != null && elemWrap.hasGenericsParam) {
      pack.typeWrapStack.push(elemWrap);
    }

    int flags = header.flags;
    Serializer? ser = header.ser;
    if ((flags & isSameTypeFlag) == isSameTypeFlag && ser != null) {
      if ((flags & trackingRefFlag) == trackingRefFlag) {
        for (Object? elem in v) {
          pack.forySer.xWriteRefWithSer(bw, ser, elem, pack);
        }
      } else {
        if ((flags & hasNullFlag) == hasNullFlag) {
          for (Object? elem in v) {
            if (elem == null) {
              bw.writeInt8(RefFlag.NULL.id);
            } else {
              bw.writeInt8(RefFlag.UNTRACKED_NOT_NULL.id);
              ser.write(bw, elem, pack);
            }
          }
        } else {
          for (Object? elem in v) {
            ser.write(bw, elem as Object, pack);
          }
        }
      }
    } else {
      if ((flags & trackingRefFlag) == trackingRefFlag) {
        for (Object? elem in v) {
          pack.forySer.xWriteRefNoSer(bw, elem, pack);
        }
      } else {
        if ((flags & hasNullFlag) == hasNullFlag) {
          for (Object? elem in v) {
            if (elem == null) {
              bw.writeInt8(RefFlag.NULL.id);
            } else {
              bw.writeInt8(RefFlag.UNTRACKED_NOT_NULL.id);
              pack.forySer.xWriteNonRefNoSer(bw, elem, pack);
            }
          }
        } else {
          for (Object? elem in v) {
            pack.forySer.xWriteNonRefNoSer(bw, elem as Object, pack);
          }
        }
      }
    }

    if (elemWrap != null && elemWrap.hasGenericsParam) {
      pack.typeWrapStack.pop();
    }
  }

  ({int flags, Serializer? ser}) _writeElementsHeader(ByteWriter bw,
      Iterable value, TypeSpecWrap? elemWrap, SerializerPack pack) {
    if (elemWrap != null) {
      if (elemWrap.certainForSer && elemWrap.ser != null) {
        Serializer ser = elemWrap.ser!;
        if (ser.writeRef) {
          bw.writeUint8(declSameTypeTrackingRef);
          return (flags: declSameTypeTrackingRef, ser: ser);
        }
        int flags =
            _containsNull(value) ? declSameTypeHasNull : declSameTypeNotHasNull;
        bw.writeUint8(flags);
        return (flags: flags, ser: ser);
      }
      bool trackingRef = elemWrap.ser?.writeRef ?? _isRefTrackingEnabled(pack);
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

  ({int flags, Serializer? ser}) _writeTypeHeader(ByteWriter bw, Iterable value,
      TypeSpecWrap? declareWrap, SerializerPack pack) {
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
      return (flags: trackingRefFlag, ser: null);
    }

    int flags = trackingRefFlag | isSameTypeFlag;
    Serializer? declaredSer = declareWrap?.ser;
    if (declareWrap != null &&
        declaredSer != null &&
        elemType == declareWrap.type) {
      flags |= isDeclElementTypeFlag;
      bw.writeUint8(flags);
      return (flags: flags, ser: declaredSer);
    }
    bw.writeUint8(flags);
    final typeInfo =
        pack.xtypeResolver.writeGetTypeInfo(bw, firstNonNull, pack);
    return (flags: flags, ser: typeInfo.ser);
  }

  ({int flags, Serializer? ser}) _writeTypeNullabilityHeader(ByteWriter bw,
      Iterable value, TypeSpecWrap? declareWrap, SerializerPack pack) {
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
      return (flags: flags, ser: null);
    }

    flags |= isSameTypeFlag;
    Serializer? declaredSer = declareWrap?.ser;
    if (declareWrap != null &&
        declaredSer != null &&
        elemType == declareWrap.type) {
      flags |= isDeclElementTypeFlag;
      bw.writeUint8(flags);
      return (flags: flags, ser: declaredSer);
    }
    bw.writeUint8(flags);
    final typeInfo =
        pack.xtypeResolver.writeGetTypeInfo(bw, firstNonNull, pack);
    return (flags: flags, ser: typeInfo.ser);
  }

  bool _isRefTrackingEnabled(SerializerPack pack) {
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
