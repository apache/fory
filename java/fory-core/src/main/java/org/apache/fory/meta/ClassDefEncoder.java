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

import static org.apache.fory.meta.ClassDef.COMPRESS_META_FLAG;
import static org.apache.fory.meta.ClassDef.HAS_FIELDS_META_FLAG;
import static org.apache.fory.meta.ClassDef.META_SIZE_MASKS;
import static org.apache.fory.meta.ClassDef.NUM_HASH_BITS;
import static org.apache.fory.meta.Encoders.fieldNameEncodingsList;
import static org.apache.fory.meta.Encoders.pkgEncodingsList;
import static org.apache.fory.meta.Encoders.typeNameEncodingsList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.MurmurHash3;

/**
 * An encoder which encode {@link ClassDef} into binary. See spec documentation:
 * docs/specification/java_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_java_serialization_spec">...</a>
 */
@Internal
public class ClassDefEncoder {
  // a flag to mark a type is not struct.
  static final int NUM_CLASS_THRESHOLD = 0b1111;

  static List<Field> buildFields(Fory fory, Class<?> cls, boolean resolveParent) {
    DescriptorGrouper descriptorGrouper =
        fory.getClassResolver()
            .createDescriptorGrouper(
                fory.getClassResolver().getFieldDescriptors(cls, resolveParent),
                false,
                Function.identity());
    List<Field> fields = new ArrayList<>();
    descriptorGrouper
        .getPrimitiveDescriptors()
        .forEach(descriptor -> fields.add(descriptor.getField()));
    descriptorGrouper
        .getBoxedDescriptors()
        .forEach(descriptor -> fields.add(descriptor.getField()));
    descriptorGrouper
        .getBuildInDescriptors()
        .forEach(descriptor -> fields.add(descriptor.getField()));
    // Order must match ObjectSerializer serialization order: buildIn, container, other
    descriptorGrouper
        .getCollectionDescriptors()
        .forEach(descriptor -> fields.add(descriptor.getField()));
    descriptorGrouper.getMapDescriptors().forEach(descriptor -> fields.add(descriptor.getField()));
    descriptorGrouper
        .getOtherDescriptors()
        .forEach(descriptor -> fields.add(descriptor.getField()));
    return fields;
  }

  public static List<FieldInfo> buildFieldsInfo(ClassResolver resolver, Class<?> cls) {
    return buildFieldsInfo(resolver, buildFields(resolver.getFory(), cls, true));
  }

  public static List<FieldInfo> buildFieldsInfo(TypeResolver resolver, List<Field> fields) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    Set<Integer> usedTagIds = new HashSet<>();
    for (Field field : fields) {
      // Check for @ForyField annotation to extract tag ID
      ForyField foryField = field.getAnnotation(ForyField.class);
      FieldType fieldType = FieldTypes.buildFieldType(resolver, field);

      FieldInfo fieldInfo;
      if (foryField != null) {
        int tagId = foryField.id();
        if (tagId >= 0) {
          if (!usedTagIds.add(tagId)) {
            throw new IllegalArgumentException(
                "Duplicate tag id: "
                    + tagId
                    + ", field: "
                    + field
                    + ", class: "
                    + field.getDeclaringClass());
          }
          // Create FieldInfo with tag ID for optimized serialization
          fieldInfo =
              new FieldInfo(
                  field.getDeclaringClass().getName(), field.getName(), fieldType, (short) tagId);
        } else {
          // tagId == -1 means opt-out, use field name
          fieldInfo =
              new FieldInfo(field.getDeclaringClass().getName(), field.getName(), fieldType);
        }
      } else {
        // No annotation, use field name
        fieldInfo = new FieldInfo(field.getDeclaringClass().getName(), field.getName(), fieldType);
      }
      fieldInfos.add(fieldInfo);
    }
    return fieldInfos;
  }

  /** Build class definition from fields of class. */
  static ClassDef buildClassDef(
      ClassResolver classResolver, Class<?> type, List<Field> fields, boolean hasFieldsMeta) {
    return buildClassDefWithFieldInfos(
        classResolver, type, buildFieldsInfo(classResolver, fields), hasFieldsMeta);
  }

  public static ClassDef buildClassDefWithFieldInfos(
      ClassResolver classResolver,
      Class<?> type,
      List<FieldInfo> fieldInfos,
      boolean hasFieldsMeta) {
    Map<String, List<FieldInfo>> classLayers = getClassFields(type, fieldInfos);
    fieldInfos = new ArrayList<>(fieldInfos.size());
    classLayers.values().forEach(fieldInfos::addAll);
    MemoryBuffer encodeClassDef = encodeClassDef(classResolver, type, classLayers, hasFieldsMeta);
    byte[] classDefBytes = encodeClassDef.getBytes(0, encodeClassDef.writerIndex());
    int typeId = classResolver.getTypeIdForClassDef(type);
    ClassSpec classSpec = new ClassSpec(type, typeId);
    return new ClassDef(
        classSpec, fieldInfos, hasFieldsMeta, encodeClassDef.getInt64(0), classDefBytes);
  }

  // see spec documentation: docs/specification/java_serialization_spec.md
  // https://fory.apache.org/docs/specification/fory_java_serialization_spec
  public static MemoryBuffer encodeClassDef(
      ClassResolver classResolver,
      Class<?> type,
      Map<String, List<FieldInfo>> classLayers,
      boolean hasFieldsMeta) {
    MemoryBuffer classDefBuf = MemoryBuffer.newHeapBuffer(128);
    int numClasses = classLayers.size() - 1; // num class must be greater than 0
    if (numClasses >= NUM_CLASS_THRESHOLD) {
      classDefBuf.writeByte(NUM_CLASS_THRESHOLD);
      classDefBuf.writeVarUint32Small7(numClasses - NUM_CLASS_THRESHOLD);
    } else {
      classDefBuf.writeByte(numClasses);
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
        classDefBuf.writeVarUint32Small7(currentClassHeader);
        classDefBuf.writeVarUint32Small7(classResolver.getTypeIdForClassDef(currentType));
      } else {
        classDefBuf.writeVarUint32Small7(currentClassHeader);
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
        writePkgName(classDefBuf, ns);
        writeTypeName(classDefBuf, typename);
      }
      writeFieldsInfo(classDefBuf, fields);
    }
    byte[] compressed =
        classResolver
            .getFory()
            .getConfig()
            .getMetaCompressor()
            .compress(classDefBuf.getHeapMemory(), 0, classDefBuf.writerIndex());
    boolean isCompressed = false;
    if (compressed.length < classDefBuf.writerIndex()) {
      isCompressed = true;
      classDefBuf = MemoryBuffer.fromByteArray(compressed);
      classDefBuf.writerIndex(compressed.length);
    }
    return prependHeader(classDefBuf, isCompressed, hasFieldsMeta);
  }

  static MemoryBuffer prependHeader(
      MemoryBuffer buffer, boolean isCompressed, boolean hasFieldsMeta) {
    int metaSize = buffer.writerIndex();
    long hash = MurmurHash3.murmurhash3_x64_128(buffer.getHeapMemory(), 0, metaSize, 47)[0];
    hash <<= (64 - NUM_HASH_BITS);
    // this id will be part of generated codec, a negative number won't be allowed in class name.
    long header = Math.abs(hash);
    if (isCompressed) {
      header |= COMPRESS_META_FLAG;
    }
    if (hasFieldsMeta) {
      header |= HAS_FIELDS_META_FLAG;
    }
    header |= Math.min(metaSize, META_SIZE_MASKS);
    MemoryBuffer result = MemoryUtils.buffer(metaSize + 8);
    result.writeInt64(header);
    if (metaSize >= META_SIZE_MASKS) {
      result.writeVarUint32(metaSize - META_SIZE_MASKS);
    }
    result.writeBytes(buffer.getHeapMemory(), 0, metaSize);
    return result;
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
      // Encoding `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID`
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
        buffer.writeVarUint32Small7(size - 7);
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
    //    - encoding algorithm: `UTF8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL`
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
    // `UTF8/LOWER_UPPER_DIGIT_SPECIAL/FIRST_TO_LOWER_SPECIAL/ALL_TO_LOWER_SPECIAL`
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
      buffer.writeVarUint32Small7(encoded.length - BIG_NAME_THRESHOLD);
    } else {
      int header = (encoded.length << 2) | encoding;
      buffer.writeByte(header);
    }
    buffer.writeBytes(encoded);
  }
}
