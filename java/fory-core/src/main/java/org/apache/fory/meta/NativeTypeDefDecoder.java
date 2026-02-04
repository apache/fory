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

package org.apache.fory.meta;

import static org.apache.fory.meta.Encoders.fieldNameEncodings;
import static org.apache.fory.meta.Encoders.pkgEncodings;
import static org.apache.fory.meta.Encoders.typeNameEncodings;
import static org.apache.fory.meta.NativeTypeDefEncoder.BIG_NAME_THRESHOLD;
import static org.apache.fory.meta.NativeTypeDefEncoder.NUM_CLASS_THRESHOLD;
import static org.apache.fory.meta.TypeDef.COMPRESS_META_FLAG;
import static org.apache.fory.meta.TypeDef.HAS_FIELDS_META_FLAG;
import static org.apache.fory.meta.TypeDef.META_SIZE_MASKS;

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Unknown;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

/**
 * An decoder which decode binary into {@link TypeDef}. See spec documentation:
 * docs/specification/java_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_java_serialization_spec">...</a>
 */
class NativeTypeDefDecoder {
  static Tuple2<byte[], byte[]> decodeTypeDefBuf(
      MemoryBuffer inputBuffer, TypeResolver resolver, long id) {
    MemoryBuffer encoded = MemoryBuffer.newHeapBuffer(64);
    encoded.writeInt64(id);
    int size = (int) (id & META_SIZE_MASKS);
    if (size == META_SIZE_MASKS) {
      int moreSize = inputBuffer.readVarUint32Small14();
      encoded.writeVarUint32(moreSize);
      size += moreSize;
    }
    byte[] encodedTypeDef = inputBuffer.readBytes(size);
    encoded.writeBytes(encodedTypeDef);
    if ((id & COMPRESS_META_FLAG) != 0) {
      encodedTypeDef =
          resolver.getFory().getConfig().getMetaCompressor().decompress(encodedTypeDef, 0, size);
    }
    return Tuple2.of(encodedTypeDef, encoded.getBytes(0, encoded.writerIndex()));
  }

  public static TypeDef decodeTypeDef(ClassResolver resolver, MemoryBuffer buffer, long id) {
    Tuple2<byte[], byte[]> decoded = decodeTypeDefBuf(buffer, resolver, id);
    MemoryBuffer typeDefBuf = MemoryBuffer.fromByteArray(decoded.f0);
    int numClasses = typeDefBuf.readByte();
    if (numClasses == NUM_CLASS_THRESHOLD) {
      numClasses += typeDefBuf.readVarUint32Small7();
    }
    numClasses += 1;
    String className;
    List<FieldInfo> classFields = new ArrayList<>();
    ClassSpec classSpec = null;
    for (int i = 0; i < numClasses; i++) {
      // | num fields + register flag | header + package name | header + class name
      // | header + type id + field name | next field info | ... |
      int currentClassHeader = typeDefBuf.readVarUint32Small7();
      boolean isRegistered = (currentClassHeader & 0b1) != 0;
      int numFields = currentClassHeader >>> 1;
      if (isRegistered) {
        int typeId = typeDefBuf.readUint8();
        int userTypeId = -1;
        if (Types.isUserTypeRegisteredById(typeId)) {
          userTypeId = typeDefBuf.readVarUint32();
        }
        Class<?> cls = resolver.getRegisteredClassByTypeId(typeId, userTypeId);
        if (cls == null) {
          classSpec = new ClassSpec(Unknown.UnknownStruct.class, typeId, userTypeId);
          className = classSpec.entireClassName;
        } else {
          className = cls.getName();
          classSpec = new ClassSpec(cls, typeId, userTypeId);
        }
      } else {
        String pkg = readPkgName(typeDefBuf);
        String typeName = readTypeName(typeDefBuf);
        ClassSpec decodedSpec = Encoders.decodePkgAndClass(pkg, typeName);
        className = decodedSpec.entireClassName;
        if (resolver.isRegisteredByName(className)) {
          Class<?> cls = resolver.getRegisteredClass(className);
          className = cls.getName();
          classSpec =
              new ClassSpec(
                  cls, resolver.getTypeIdForTypeDef(cls), resolver.getUserTypeIdForTypeDef(cls));
        } else {
          Class<?> cls =
              resolver.loadClassForMeta(
                  decodedSpec.entireClassName, decodedSpec.isEnum, decodedSpec.dimension);
          if (Unknown.isUnknowClass(cls)) {
            int typeId;
            if (decodedSpec.isEnum) {
              typeId = Types.NAMED_ENUM;
            } else {
              typeId =
                  resolver.getFory().isCompatible()
                      ? Types.NAMED_COMPATIBLE_STRUCT
                      : Types.NAMED_STRUCT;
            }
            classSpec =
                new ClassSpec(
                    decodedSpec.entireClassName,
                    decodedSpec.isEnum,
                    decodedSpec.isArray,
                    decodedSpec.dimension,
                    typeId,
                    -1);
            classSpec.type = cls;
            className = classSpec.entireClassName;
          } else {
            int typeId = resolver.getTypeIdForTypeDef(cls);
            classSpec = new ClassSpec(cls, typeId, resolver.getUserTypeIdForTypeDef(cls));
            className = classSpec.entireClassName;
          }
        }
      }
      List<FieldInfo> fieldInfos = readFieldsInfo(typeDefBuf, resolver, className, numFields);
      classFields.addAll(fieldInfos);
    }
    Preconditions.checkNotNull(classSpec);
    boolean hasFieldsMeta = (id & HAS_FIELDS_META_FLAG) != 0;
    return new TypeDef(classSpec, classFields, hasFieldsMeta, id, decoded.f1);
  }

  private static List<FieldInfo> readFieldsInfo(
      MemoryBuffer buffer, ClassResolver resolver, String className, int numFields) {
    List<FieldInfo> fieldInfos = new ArrayList<>(numFields);
    for (int i = 0; i < numFields; i++) {
      int header = buffer.readByte() & 0xff;
      //  `3 bits size + 2 bits field name encoding + nullability flag + ref tracking flag`
      int encodingFlags = (header >>> 2) & 0b11;
      boolean useTagID = encodingFlags == 3;
      int size = header >>> 4;
      if (size == 7) {
        size += buffer.readVarUint32Small7();
      }
      size += 1;

      // Read field name or tag ID
      String fieldName;
      short tagId = -1;
      if (useTagID) {
        // When useTagID is true, size contains the tag ID
        tagId = (short) (size - 1);
        // Use placeholder field name since tag ID is used for identification
        fieldName = "$tag" + tagId;
      } else {
        Encoding encoding = fieldNameEncodings[encodingFlags];
        fieldName = Encoders.FIELD_NAME_DECODER.decode(buffer.readBytes(size), encoding);
      }

      boolean nullable = (header & 0b010) != 0;
      boolean trackingRef = (header & 0b001) != 0;
      int kindHeader = buffer.readUint8();
      int kind = kindHeader >>> 2;
      FieldType fieldType =
          FieldTypes.FieldType.read(buffer, resolver, nullable, trackingRef, kind);

      if (useTagID) {
        fieldInfos.add(new FieldInfo(className, fieldName, fieldType, tagId));
      } else {
        fieldInfos.add(new FieldInfo(className, fieldName, fieldType));
      }
    }
    return fieldInfos;
  }

  static String readPkgName(MemoryBuffer buffer) {
    // - Package name encoding(omitted when class is registered):
    //    - encoding algorithm: `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
    //    - Header: `6 bits size | 2 bits encoding flags`.
    //      The `6 bits size: 0~63`  will be used to indicate size `0~63`,
    //      the value `63` the size need more byte to read, the encoding will encode `size - 63` as
    // a varint next.
    return readName(Encoders.PACKAGE_DECODER, buffer, pkgEncodings);
  }

  static String readTypeName(MemoryBuffer buffer) {
    // - Class name encoding(omitted when class is registered):
    //     - encoding algorithm:
    // `UTF_8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
    //     - header: `6 bits size | 2 bits encoding flags`.
    //      The `6 bits size: 0~63`  will be used to indicate size `0~63`,
    //       the value `63` the size need more byte to read, the encoding will encode `size - 63` as
    // a varint next.
    return readName(Encoders.TYPE_NAME_DECODER, buffer, typeNameEncodings);
  }

  private static String readName(
      MetaStringDecoder decoder, MemoryBuffer buffer, Encoding[] encodings) {
    int header = buffer.readByte() & 0xff;
    int encodingFlags = header & 0b11;
    Encoding encoding = encodings[encodingFlags];
    int size = header >> 2;
    if (size == BIG_NAME_THRESHOLD) {
      size = buffer.readVarUint32Small7() + BIG_NAME_THRESHOLD;
    }
    return decoder.decode(buffer.readBytes(size), encoding);
  }
}
