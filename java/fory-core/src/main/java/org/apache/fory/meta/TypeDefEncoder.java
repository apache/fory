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
import static org.apache.fory.meta.NativeTypeDefEncoder.prependHeader;
import static org.apache.fory.meta.NativeTypeDefEncoder.writePkgName;
import static org.apache.fory.meta.NativeTypeDefEncoder.writeTypeName;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;

/**
 * An encoder which encode {@link TypeDef} into binary. Global header layout follows the xlang spec
 * with an 8-bit meta size and flags at bits 8/9. See spec documentation:
 * docs/specification/fory_xlang_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_xlang_serialization_spec">...</a>
 */
class TypeDefEncoder {
  private static final Logger LOG = LoggerFactory.getLogger(TypeDefEncoder.class);

  /** Build class definition from fields of class. */
  static TypeDef buildTypeDef(Fory fory, Class<?> type) {
    DescriptorGrouper descriptorGrouper =
        fory.getXtypeResolver()
            .createDescriptorGrouper(
                fory.getXtypeResolver().getFieldDescriptors(type, true),
                false,
                Function.identity());
    TypeInfo typeInfo = fory.getTypeResolver().getTypeInfo(type);
    List<Field> fields;
    int typeId = typeInfo.getTypeId();
    if (Types.isStructType(typeId)) {
      fields =
          descriptorGrouper.getSortedDescriptors().stream()
              .map(Descriptor::getField)
              .collect(Collectors.toList());
    } else {
      fields = new ArrayList<>();
    }
    return buildTypeDefWithFieldInfos(
        fory.getXtypeResolver(), type, buildFieldsInfo(fory.getXtypeResolver(), type, fields));
  }

  static List<FieldInfo> buildFieldsInfo(TypeResolver resolver, Class<?> type, List<Field> fields) {
    Set<Integer> usedTagIds = new HashSet<>();
    return fields.stream()
        .map(
            field -> {
              ForyField foryField = field.getAnnotation(ForyField.class);
              FieldType fieldType = FieldTypes.buildFieldType(resolver, field);
              if (foryField != null) {
                int tagId = foryField.id();
                if (tagId >= 0) {
                  if (!usedTagIds.add(tagId)) {
                    throw new IllegalArgumentException(
                        "Duplicate tag id "
                            + tagId
                            + " for field "
                            + field.getName()
                            + " in class "
                            + type.getName());
                  }
                  return new FieldInfo(type.getName(), field.getName(), fieldType, (short) tagId);
                }
                // tagId == -1 means use field name, fall through to create regular FieldInfo
              }
              return new FieldInfo(type.getName(), field.getName(), fieldType);
            })
        .collect(Collectors.toList());
  }

  static TypeDef buildTypeDefWithFieldInfos(
      XtypeResolver resolver, Class<?> type, List<FieldInfo> fieldInfos) {
    fieldInfos = new ArrayList<>(getClassFields(type, fieldInfos).values());
    TypeInfo typeInfo = resolver.getTypeInfo(type);
    MemoryBuffer encodeTypeDef = encodeTypeDef(resolver, type, fieldInfos);
    byte[] typeDefBytes = encodeTypeDef.getBytes(0, encodeTypeDef.writerIndex());
    TypeDef typeDef =
        new TypeDef(
            new ClassSpec(type, typeInfo.getTypeId(), typeInfo.getUserTypeId()),
            fieldInfos,
            true,
            encodeTypeDef.getInt64(0),
            typeDefBytes);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("[Java TypeDef BUILT] " + typeDef);
    }
    return typeDef;
  }

  static final int SMALL_NUM_FIELDS_THRESHOLD = 0b11111;
  static final int REGISTER_BY_NAME_FLAG = 0b100000;
  static final int FIELD_NAME_SIZE_THRESHOLD = 0b1111;

  // see spec documentation: docs/specification/xlang_serialization_spec.md
  // https://fory.apache.org/docs/specification/fory_xlang_serialization_spec
  static MemoryBuffer encodeTypeDef(XtypeResolver resolver, Class<?> type, List<FieldInfo> fields) {
    TypeInfo typeInfo = resolver.getTypeInfo(type);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    buffer.writeByte(-1); // placeholder for header, update later
    int currentClassHeader = fields.size();
    if (fields.size() >= SMALL_NUM_FIELDS_THRESHOLD) {
      currentClassHeader = SMALL_NUM_FIELDS_THRESHOLD;
      buffer.writeVarUint32(fields.size() - SMALL_NUM_FIELDS_THRESHOLD);
    }
    if (resolver.isRegisteredById(type)) {
      buffer.writeUint8(typeInfo.getTypeId());
      Preconditions.checkArgument(
          typeInfo.getUserTypeId() != -1,
          "User type id is required for typeId %s",
          typeInfo.getTypeId());
      buffer.writeVarUint32(typeInfo.getUserTypeId());
    } else {
      Preconditions.checkArgument(resolver.isRegisteredByName(type));
      currentClassHeader |= REGISTER_BY_NAME_FLAG;
      String ns = typeInfo.decodeNamespace();
      String typename = typeInfo.decodeTypeName();
      writePkgName(buffer, ns);
      writeTypeName(buffer, typename);
    }
    buffer.putByte(0, currentClassHeader);
    writeFieldsInfo(resolver, buffer, fields);

    byte[] compressed =
        resolver
            .getFory()
            .getMetaCompressor()
            .compress(buffer.getHeapMemory(), 0, buffer.writerIndex());
    boolean isCompressed = false;
    if (compressed.length < buffer.writerIndex()) {
      isCompressed = true;
      buffer = MemoryBuffer.fromByteArray(compressed);
      buffer.writerIndex(compressed.length);
    }
    return prependHeader(buffer, isCompressed, !fields.isEmpty());
  }

  static Map<String, FieldInfo> getClassFields(Class<?> type, List<FieldInfo> fieldsInfo) {
    Map<String, FieldInfo> sortedClassFields = new LinkedHashMap<>();
    Map<String, List<FieldInfo>> classFields = NativeTypeDefEncoder.groupClassFields(fieldsInfo);
    for (Class<?> clz : ReflectionUtils.getAllClasses(type, true)) {
      List<FieldInfo> fieldInfos = classFields.get(clz.getName());
      if (fieldInfos != null) {
        for (FieldInfo fieldInfo : fieldInfos) {
          sortedClassFields.put(fieldInfo.getFieldName(), fieldInfo);
        }
      }
    }
    return sortedClassFields;
  }

  /** Write field type and name info. Every field info format: `header + type info + field name` */
  static void writeFieldsInfo(XtypeResolver resolver, MemoryBuffer buffer, List<FieldInfo> fields) {
    for (FieldInfo fieldInfo : fields) {
      FieldType fieldType = fieldInfo.getFieldType();
      // header: 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
      int header = ((fieldType.trackingRef() ? 1 : 0));
      header |= fieldType.nullable() ? 0b10 : 0b00;
      int size, encodingFlags;
      byte[] encoded = null;
      if (fieldInfo.hasFieldId()) {
        size = fieldInfo.getFieldId();
        encodingFlags = 3;
      } else {
        // Convert camelCase field names to snake_case for xlang interoperability
        String fieldName = StringUtils.lowerCamelToLowerUnderscore(fieldInfo.getFieldName());
        MetaString metaString = Encoders.encodeFieldName(fieldName);
        // Encoding `UTF_8/ALL_TO_LOWER_SPECIAL/LOWER_UPPER_DIGIT_SPECIAL/TAG_ID`
        encodingFlags = fieldNameEncodingsList.indexOf(metaString.getEncoding());
        encoded = metaString.getBytes();
        size = (encoded.length - 1);
      }
      header |= (byte) (encodingFlags << 6);
      boolean bigSize = size >= FIELD_NAME_SIZE_THRESHOLD;
      if (bigSize) {
        header |= 0b00111100;
        buffer.writeByte(header);
        buffer.writeVarUint32Small7(size - FIELD_NAME_SIZE_THRESHOLD);
      } else {
        header |= (size << 2);
        buffer.writeByte(header);
      }
      fieldType.writeCrossLanguage(buffer, false);
      // write field name
      if (!fieldInfo.hasFieldId()) {
        buffer.writeBytes(encoded);
      }
    }
  }
}
