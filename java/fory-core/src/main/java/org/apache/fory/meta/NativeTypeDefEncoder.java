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

import static org.apache.fory.meta.Encoders.fieldNameEncodingsList;
import static org.apache.fory.meta.Encoders.pkgEncodingsList;
import static org.apache.fory.meta.Encoders.typeNameEncodingsList;
import static org.apache.fory.meta.TypeDef.COMPRESS_META_FLAG;
import static org.apache.fory.meta.TypeDef.META_SIZE_MASKS;
import static org.apache.fory.meta.TypeDef.NUM_HASH_BITS;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.MurmurHash3;

/**
 * An encoder which encode {@link TypeDef} into binary. See spec documentation:
 * docs/specification/java_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_java_serialization_spec">...</a>
 */
@Internal
public class NativeTypeDefEncoder {
  static final int NUM_CLASS_THRESHOLD = 0b1111;
  private static final java.util.function.Function<Descriptor, Descriptor> IDENTITY_DESCRIPTOR =
      descriptor -> descriptor;

  private static boolean needsUserTypeId(int typeId) {
    switch (typeId) {
      case Types.ENUM:
      case Types.STRUCT:
      case Types.COMPATIBLE_STRUCT:
      case Types.EXT:
      case Types.TYPED_UNION:
        return true;
      default:
        return false;
    }
  }

  static List<Descriptor> buildDescriptors(
      TypeResolver typeResolver, Class<?> cls, boolean resolveParent) {
    DescriptorGrouper descriptorGrouper =
        typeResolver.getFieldDescriptorGrouper(cls, resolveParent, false, IDENTITY_DESCRIPTOR);
    return new ArrayList<>(descriptorGrouper.getSortedDescriptors());
  }

  public static List<FieldInfo> buildFieldsInfo(ClassResolver resolver, Class<?> cls) {
    return buildFieldsInfoFromDescriptors(resolver, buildDescriptors(resolver, cls, true));
  }

  public static List<FieldInfo> buildFieldsInfo(TypeResolver resolver, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>(fields.size());
    for (Field field : fields) {
      descriptors.add(new Descriptor(field, TypeUtils.getFieldTypeRef(field), null, null));
    }
    return buildFieldsInfoFromDescriptors(resolver, descriptors);
  }

  static List<FieldInfo> buildFieldsInfoFromDescriptors(
      TypeResolver resolver, List<Descriptor> descriptors) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    Set<Integer> usedTagIds = new HashSet<>();
    for (Descriptor descriptor : descriptors) {
      FieldType fieldType = FieldTypes.buildFieldType(resolver, descriptor);

      FieldInfo fieldInfo;
      if (descriptor.hasForyField()) {
        int tagId = descriptor.getForyFieldId();
        if (tagId >= 0) {
          if (!usedTagIds.add(tagId)) {
            throw new IllegalArgumentException(
                "Duplicate tag id: "
                    + tagId
                    + ", field: "
                    + descriptor.getName()
                    + ", class: "
                    + descriptor.getDeclaringClass());
          }
          // Create FieldInfo with tag ID for optimized serialization
          fieldInfo =
              new FieldInfo(descriptor.getDeclaringClass(), descriptor.getName(), fieldType, tagId);
        } else {
          // Negative is the annotation default sentinel for no configured tag ID; use field name.
          fieldInfo =
              new FieldInfo(descriptor.getDeclaringClass(), descriptor.getName(), fieldType);
        }
      } else {
        // No annotation, use field name
        fieldInfo = new FieldInfo(descriptor.getDeclaringClass(), descriptor.getName(), fieldType);
      }
      fieldInfos.add(fieldInfo);
    }
    return fieldInfos;
  }

  /** Build class definition from fields of class. */
  static TypeDef buildTypeDef(ClassResolver classResolver, Class<?> type, List<Field> fields) {
    return buildTypeDefWithFieldInfos(classResolver, type, buildFieldsInfo(classResolver, fields));
  }

  /** Build class definition from descriptors of class. */
  static TypeDef buildTypeDefFromDescriptors(
      ClassResolver classResolver, Class<?> type, List<Descriptor> descriptors) {
    return buildTypeDefWithFieldInfos(
        classResolver, type, buildFieldsInfoFromDescriptors(classResolver, descriptors));
  }

  public static TypeDef buildTypeDefWithFieldInfos(
      ClassResolver classResolver, Class<?> type, List<FieldInfo> fieldInfos) {
    boolean hasFieldMetadata = !fieldInfos.isEmpty();
    Map<String, List<FieldInfo>> classLayers = getClassFields(type, fieldInfos);
    fieldInfos = new ArrayList<>(fieldInfos.size());
    classLayers.values().forEach(fieldInfos::addAll);
    MemoryBuffer encodeTypeDef = encodeTypeDef(classResolver, type, classLayers, hasFieldMetadata);
    byte[] typeDefBytes = encodeTypeDef.getBytes(0, encodeTypeDef.writerIndex());
    int typeId = classResolver.getTypeDefRootTypeId(type, hasFieldMetadata);
    int userTypeId = classResolver.getUserTypeIdForTypeDef(type);
    ClassSpec classSpec = new ClassSpec(type, typeId, userTypeId);
    return new TypeDef(classSpec, fieldInfos, encodeTypeDef.getInt64(0), typeDefBytes);
  }

  // see spec documentation: docs/specification/java_serialization_spec.md
  // https://fory.apache.org/docs/specification/fory_java_serialization_spec
  public static MemoryBuffer encodeTypeDef(
      ClassResolver classResolver, Class<?> type, Map<String, List<FieldInfo>> classLayers) {
    return encodeTypeDef(classResolver, type, classLayers, hasFieldMetadata(classLayers));
  }

  private static MemoryBuffer encodeTypeDef(
      ClassResolver classResolver,
      Class<?> type,
      Map<String, List<FieldInfo>> classLayers,
      boolean hasFieldMetadata) {
    MemoryBuffer typeDefBuf = MemoryBuffer.newHeapBuffer(128);
    int numClasses = classLayers.size() - 1; // num class must be greater than 0
    int rootTypeId = classResolver.getTypeDefRootTypeId(type, hasFieldMetadata);
    int firstBodyByte = nativeKindCode(rootTypeId) << 4;
    if (numClasses >= NUM_CLASS_THRESHOLD) {
      typeDefBuf.writeByte(firstBodyByte | NUM_CLASS_THRESHOLD);
      typeDefBuf.writeVarUInt32Small7(numClasses - NUM_CLASS_THRESHOLD);
    } else {
      typeDefBuf.writeByte(firstBodyByte | numClasses);
    }
    for (Map.Entry<String, List<FieldInfo>> entry : classLayers.entrySet()) {
      String className = entry.getKey();
      Class<?> currentType = getType(type, className);
      List<FieldInfo> fields = entry.getValue();
      // | num fields + register flag | header + package name | header + class name
      // | header + type id + field name | next field info | ... |
      int currentClassHeader = (fields.size() << 1);
      if (classResolver.isRegisteredById(currentType)) {
        currentClassHeader |= 1;
        typeDefBuf.writeVarUInt32Small7(currentClassHeader);
        int typeId = classResolver.getTypeIdForTypeDef(currentType);
        typeDefBuf.writeUInt8(typeId);
        if (needsUserTypeId(typeId)) {
          int userTypeId = classResolver.getUserTypeIdForTypeDef(currentType);
          typeDefBuf.writeVarUInt32(userTypeId);
        }
      } else {
        typeDefBuf.writeVarUInt32Small7(currentClassHeader);
        String ns, typename;
        if (classResolver.isRegisteredByName(currentType)) {
          Tuple2<String, String> nameTuple = classResolver.getRegisteredNameTuple(currentType);
          ns = nameTuple.f0;
          typename = nameTuple.f1;
        } else {
          Tuple2<String, String> encoded = Encoders.encodePkgAndClass(currentType);
          ns = encoded.f0;
          typename = encoded.f1;
        }
        writePkgName(typeDefBuf, ns);
        writeTypeName(typeDefBuf, typename);
      }
      writeFieldsInfo(typeDefBuf, fields);
    }
    byte[] compressed =
        classResolver
            .getConfig()
            .getMetaCompressor()
            .compress(typeDefBuf.getHeapMemory(), 0, typeDefBuf.writerIndex());
    boolean isCompressed = false;
    if (compressed.length < typeDefBuf.writerIndex()) {
      isCompressed = true;
      typeDefBuf = MemoryBuffer.fromByteArray(compressed);
      typeDefBuf.writerIndex(compressed.length);
    }
    return prependHeader(typeDefBuf, isCompressed);
  }

  private static boolean hasFieldMetadata(Map<String, List<FieldInfo>> classLayers) {
    for (List<FieldInfo> fields : classLayers.values()) {
      if (!fields.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  static MemoryBuffer prependHeader(MemoryBuffer buffer, boolean isCompressed) {
    int metaSize = buffer.writerIndex();
    long headerLowBits = Math.min(metaSize, META_SIZE_MASKS);
    if (isCompressed) {
      headerLowBits |= COMPRESS_META_FLAG;
    }
    long header =
        computeTypeDefHashBits(buffer.getHeapMemory(), 0, metaSize, headerLowBits) | headerLowBits;
    MemoryBuffer result = MemoryUtils.buffer(metaSize + 8);
    result.writeInt64(header);
    if (metaSize >= META_SIZE_MASKS) {
      result.writeVarUInt32(metaSize - META_SIZE_MASKS);
    }
    result.writeBytes(buffer.getHeapMemory(), 0, metaSize);
    return result;
  }

  static long computeTypeDefHashBits(byte[] bytes, int offset, int size, long headerLowBits) {
    byte[] hashInput = new byte[size + Short.BYTES];
    System.arraycopy(bytes, offset, hashInput, 0, size);
    hashInput[size] = (byte) headerLowBits;
    hashInput[size + 1] = (byte) (headerLowBits >>> Byte.SIZE);
    long hash = MurmurHash3.murmurhash3_x64_128(hashInput, 0, hashInput.length, 47)[0];
    hash <<= (64 - NUM_HASH_BITS);
    long hashMask = -1L << (Long.SIZE - NUM_HASH_BITS);
    // this id will be part of generated codec, a negative number won't be allowed in class name.
    return Math.abs(hash) & hashMask;
  }

  static int nativeKindCode(int typeId) {
    switch (typeId) {
      case Types.STRUCT:
        return 0;
      case Types.COMPATIBLE_STRUCT:
        return 1;
      case Types.NAMED_STRUCT:
        return 2;
      case Types.NAMED_COMPATIBLE_STRUCT:
        return 3;
      case Types.ENUM:
        return 4;
      case Types.NAMED_ENUM:
        return 5;
      case Types.EXT:
        return 6;
      case Types.NAMED_EXT:
        return 7;
      case Types.TYPED_UNION:
        return 8;
      case Types.NAMED_UNION:
        return 9;
      default:
        throw new IllegalArgumentException("Unsupported TypeDef kind " + typeId);
    }
  }

  private static Class<?> getType(Class<?> cls, String type) {
    Class<?> c = cls;
    while (cls != null) {
      if (type.equals(cls.getName())) {
        return cls;
      }
      cls = cls.getSuperclass();
    }
    throw new IllegalStateException(
        String.format("Class %s doesn't have %s as super class", c, type));
  }

  public static Map<String, List<FieldInfo>> getClassFields(
      Class<?> type, List<FieldInfo> fieldsInfo) {
    Map<String, List<FieldInfo>> sortedClassFields = new LinkedHashMap<>();
    if (fieldsInfo.isEmpty()) {
      sortedClassFields.put(type.getName(), new ArrayList<>());
      return sortedClassFields;
    }
    Map<String, List<FieldInfo>> classFields = groupClassFields(fieldsInfo);
    for (Class<?> clz : ReflectionUtils.getAllClasses(type, true)) {
      List<FieldInfo> fieldInfos = classFields.get(clz.getName());
      if (fieldInfos != null) {
        sortedClassFields.put(clz.getName(), fieldInfos);
      } else if (type.getName().equals(clz.getName())) {
        sortedClassFields.put(clz.getName(), new ArrayList<>());
      }
    }
    classFields = sortedClassFields;
    return classFields;
  }

  static Map<String, List<FieldInfo>> groupClassFields(List<FieldInfo> fieldsInfo) {
    Map<String, List<FieldInfo>> classFields = new HashMap<>();
    for (FieldInfo fieldInfo : fieldsInfo) {
      String definedClass = fieldInfo.getDefinedClass();
      classFields.computeIfAbsent(definedClass, k -> new ArrayList<>()).add(fieldInfo);
    }
    return classFields;
  }

  /** Write field type and name info. */
  static void writeFieldsInfo(MemoryBuffer buffer, List<FieldInfo> fields) {
    for (FieldInfo fieldInfo : fields) {
      FieldType fieldType = fieldInfo.getFieldType();
      // `3 bits size + 2 bits field name encoding + nullability flag + ref tracking flag`
      int header = ((fieldType.nullable() ? 1 : 0) << 1);
      header |= ((fieldType.trackingRef() ? 1 : 0));
      // Encoding `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID`
      MetaString metaString = Encoders.encodeFieldName(fieldInfo.getFieldName());
      int encodingFlags = fieldNameEncodingsList.indexOf(metaString.getEncoding());
      byte[] encoded = metaString.getBytes();
      int size = (encoded.length - 1);
      if (fieldInfo.hasFieldId()) {
        size = fieldInfo.getFieldId();
        encodingFlags = 3;
      }
      header |= (byte) (encodingFlags << 2);
      boolean bigSize = size >= 7;
      if (bigSize) {
        header |= 0b01110000;
        buffer.writeByte(header);
        buffer.writeVarUInt32Small7(size - 7);
      } else {
        header |= (size << 4);
        buffer.writeByte(header);
      }
      if (!fieldInfo.hasFieldId()) {
        buffer.writeBytes(encoded);
      }
      fieldType.write(buffer, false);
    }
  }

  static void writePkgName(MemoryBuffer buffer, String pkg) {
    // - Package name encoding(omitted when class is registered):
    //    - encoding algorithm: `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
    //    - Header: `6 bits size | 2 bits encoding flags`.
    //      The `6 bits size: 0~63`  will be used to indicate size `0~62`,
    //      the value `63` the size need more byte to read, the encoding will encode `size - 62` as
    // a varint next.
    MetaString pkgMetaString = Encoders.encodePackage(pkg);
    byte[] encoded = pkgMetaString.getBytes();
    writeName(buffer, encoded, pkgEncodingsList.indexOf(pkgMetaString.getEncoding()));
  }

  static void writeTypeName(MemoryBuffer buffer, String typeName) {
    // - Class name encoding(omitted when class is registered):
    //     - encoding algorithm:
    // `UTF_8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
    //     - header: `6 bits size | 2 bits encoding flags`.
    //       The `6 bits size: 0~63`  will be used to indicate size `1~64`,
    //       the value `63` the size need more byte to read, the encoding will encode `size - 63` as
    // a varint next.
    MetaString metaString = Encoders.encodeTypeName(typeName);
    byte[] encoded = metaString.getBytes();
    writeName(buffer, encoded, typeNameEncodingsList.indexOf(metaString.getEncoding()));
  }

  static final int BIG_NAME_THRESHOLD = 0b111111;

  private static void writeName(MemoryBuffer buffer, byte[] encoded, int encoding) {
    boolean bigSize = encoded.length >= BIG_NAME_THRESHOLD;
    if (bigSize) {
      int header = (BIG_NAME_THRESHOLD << 2) | encoding;
      buffer.writeByte(header);
      buffer.writeVarUInt32Small7(encoded.length - BIG_NAME_THRESHOLD);
    } else {
      int header = (encoded.length << 2) | encoding;
      buffer.writeByte(header);
    }
    buffer.writeBytes(encoded);
  }
}
