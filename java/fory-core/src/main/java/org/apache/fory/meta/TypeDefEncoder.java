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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;

/**
 * An encoder which encode {@link TypeDef} into binary. See spec documentation:
 * docs/specification/fory_xlang_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_xlang_serialization_spec">...</a>
 */
class TypeDefEncoder {
  private static final Logger LOG = LoggerFactory.getLogger(TypeDefEncoder.class);
  private static final java.util.function.Function<Descriptor, Descriptor> IDENTITY_DESCRIPTOR =
      descriptor -> descriptor;

  /** Build class definition from fields of class. */
  static TypeDef buildTypeDef(XtypeResolver resolver, Class<?> type) {
    List<Descriptor> fieldDescriptors =
        dropShadowedXlangNameFields(type, resolver.getFieldDescriptors(type, true));
    DescriptorGrouper descriptorGrouper =
        resolver.groupDescriptors(fieldDescriptors, false, IDENTITY_DESCRIPTOR);
    TypeInfo typeInfo = resolver.getTypeInfo(type);
    List<Descriptor> descriptors;
    int typeId = typeInfo.getTypeId();
    if (Types.isStructType(typeId)) {
      descriptors = descriptorGrouper.getSortedDescriptors();
    } else {
      descriptors = new ArrayList<>();
    }
    return buildTypeDefWithFieldInfos(
        resolver, type, buildFieldsInfoFromDescriptors(resolver, type, descriptors));
  }

  static List<Descriptor> dropShadowedXlangNameFields(Class<?> type, List<Descriptor> descriptors) {
    List<Descriptor> result = new ArrayList<>(descriptors.size());
    Map<String, Integer> nameFieldIndex = new HashMap<>();
    for (Descriptor descriptor : descriptors) {
      if (descriptor.hasForyFieldId()) {
        result.add(descriptor);
        continue;
      }
      String fieldIdentifier = descriptor.getSnakeCaseName();
      Integer previousIndex = nameFieldIndex.get(fieldIdentifier);
      if (previousIndex == null) {
        nameFieldIndex.put(fieldIdentifier, result.size());
        result.add(descriptor);
        continue;
      }
      Descriptor previous = result.get(previousIndex);
      int distance = inheritanceDistance(type, descriptor);
      int previousDistance = inheritanceDistance(type, previous);
      if (!descriptor.getName().equals(previous.getName()) || distance == previousDistance) {
        throw new IllegalArgumentException(
            "Duplicate xlang field identifier "
                + fieldIdentifier
                + " for fields "
                + previous.getName()
                + " and "
                + descriptor.getName()
                + " in class "
                + type.getName()
                + ". Configure explicit non-negative field IDs to disambiguate them.");
      }
      // Untagged xlang fields are addressed by snake_case identifier. Only a true Java field hide
      // across the inheritance chain can be represented by dropping the farther inherited field.
      if (distance < previousDistance) {
        result.set(previousIndex, descriptor);
      }
    }
    return result;
  }

  private static int inheritanceDistance(Class<?> type, Descriptor descriptor) {
    String declaringClass = descriptor.getDeclaringClass();
    int distance = 0;
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (current.getName().equals(declaringClass)) {
        return distance;
      }
      distance++;
    }
    return Integer.MAX_VALUE;
  }

  static List<FieldInfo> buildFieldsInfo(TypeResolver resolver, Class<?> type, List<Field> fields) {
    List<Descriptor> descriptors = new ArrayList<>(fields.size());
    for (Field field : fields) {
      descriptors.add(new Descriptor(field, TypeUtils.getFieldTypeRef(field), null, null));
    }
    return buildFieldsInfoFromDescriptors(resolver, type, descriptors);
  }

  static List<FieldInfo> buildFieldsInfoFromDescriptors(
      TypeResolver resolver, Class<?> type, List<Descriptor> descriptors) {
    Set<Integer> usedTagIds = new HashSet<>();
    return descriptors.stream()
        .map(
            descriptor -> {
              FieldType fieldType = FieldTypes.buildFieldType(resolver, descriptor);
              if (descriptor.hasForyField()) {
                int tagId = descriptor.getForyFieldId();
                if (tagId >= 0) {
                  if (!usedTagIds.add(tagId)) {
                    throw new IllegalArgumentException(
                        "Duplicate tag id "
                            + tagId
                            + " for field "
                            + descriptor.getName()
                            + " in class "
                            + type.getName());
                  }
                  return new FieldInfo(
                      type.getName(), descriptor.getName(), fieldType, (short) tagId);
                }
                // Negative is the annotation default sentinel for no configured tag ID; fall
                // through to create regular FieldInfo. User-facing tag IDs must be non-negative.
              }
              return new FieldInfo(type.getName(), descriptor.getName(), fieldType);
            })
        .collect(Collectors.toList());
  }

  static TypeDef buildTypeDefWithFieldInfos(
      XtypeResolver resolver, Class<?> type, List<FieldInfo> fieldInfos) {
    fieldInfos = new ArrayList<>(fieldInfos);
    TypeInfo typeInfo = resolver.getTypeInfo(type);
    MemoryBuffer encodeTypeDef = encodeTypeDef(resolver, type, fieldInfos);
    byte[] typeDefBytes = encodeTypeDef.getBytes(0, encodeTypeDef.writerIndex());
    TypeDef typeDef =
        new TypeDef(
            new ClassSpec(type, typeInfo.getTypeId(), typeInfo.getUserTypeId()),
            fieldInfos,
            encodeTypeDef.getInt64(0),
            typeDefBytes);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("[Java TypeDef BUILT] " + typeDef);
    }
    return typeDef;
  }

  static final int SMALL_NUM_FIELDS_THRESHOLD = 0b11111;
  static final int REGISTER_BY_NAME_FLAG = 0b0010_0000;
  static final int COMPATIBLE_FLAG = 0b0100_0000;
  static final int STRUCT_FLAG = 0b1000_0000;
  static final int FIELD_NAME_SIZE_THRESHOLD = 0b1111;

  // see spec documentation: docs/specification/xlang_serialization_spec.md
  // https://fory.apache.org/docs/specification/fory_xlang_serialization_spec
  static MemoryBuffer encodeTypeDef(XtypeResolver resolver, Class<?> type, List<FieldInfo> fields) {
    TypeInfo typeInfo = resolver.getTypeInfo(type);
    int typeId = typeInfo.getTypeId();
    boolean isStruct = Types.isStructType(typeId);
    Preconditions.checkArgument(
        isStruct || fields.isEmpty(), "Non-struct TypeDef %s cannot carry field metadata", typeId);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    buffer.writeByte(-1); // placeholder for header, update later
    if (isStruct) {
      int fieldCount = fields.size();
      int currentClassHeader = STRUCT_FLAG | Math.min(fieldCount, SMALL_NUM_FIELDS_THRESHOLD);
      if (typeId == Types.COMPATIBLE_STRUCT || typeId == Types.NAMED_COMPATIBLE_STRUCT) {
        currentClassHeader |= COMPATIBLE_FLAG;
      }
      if (fieldCount >= SMALL_NUM_FIELDS_THRESHOLD) {
        buffer.writeVarUInt32(fieldCount - SMALL_NUM_FIELDS_THRESHOLD);
      }
      if (resolver.isRegisteredById(type)) {
        Preconditions.checkArgument(
            typeInfo.getUserTypeId() != -1,
            "User type id is required for typeId %s",
            typeInfo.getTypeId());
        buffer.writeVarUInt32(typeInfo.getUserTypeId());
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
    } else {
      buffer.putByte(0, nonStructKindCode(typeId));
      if (resolver.isRegisteredById(type)) {
        Preconditions.checkArgument(
            typeInfo.getUserTypeId() != -1,
            "User type id is required for typeId %s",
            typeInfo.getTypeId());
        buffer.writeVarUInt32(typeInfo.getUserTypeId());
      } else {
        Preconditions.checkArgument(resolver.isRegisteredByName(type));
        String ns = typeInfo.decodeNamespace();
        String typename = typeInfo.decodeTypeName();
        writePkgName(buffer, ns);
        writeTypeName(buffer, typename);
      }
    }
    return prependHeader(buffer, false);
  }

  static int nonStructKindCode(int typeId) {
    switch (typeId) {
      case Types.ENUM:
        return 0;
      case Types.NAMED_ENUM:
        return 1;
      case Types.EXT:
        return 2;
      case Types.NAMED_EXT:
        return 3;
      case Types.TYPED_UNION:
        return 4;
      case Types.NAMED_UNION:
        return 5;
      default:
        throw new IllegalArgumentException("Unsupported TypeDef kind " + typeId);
    }
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
        buffer.writeVarUInt32Small7(size - FIELD_NAME_SIZE_THRESHOLD);
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
