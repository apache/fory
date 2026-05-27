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
import static org.apache.fory.meta.NativeTypeDefDecoder.decodeTypeDefBuf;
import static org.apache.fory.meta.NativeTypeDefDecoder.readPkgName;
import static org.apache.fory.meta.NativeTypeDefDecoder.readTypeName;
import static org.apache.fory.meta.NativeTypeDefDecoder.validateParsedTypeDefHash;
import static org.apache.fory.meta.TypeDef.COMPRESS_META_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.COMPATIBLE_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.FIELD_NAME_SIZE_THRESHOLD;
import static org.apache.fory.meta.TypeDefEncoder.REGISTER_BY_NAME_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.SMALL_NUM_FIELDS_THRESHOLD;
import static org.apache.fory.meta.TypeDefEncoder.STRUCT_FLAG;

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.type.Types;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;

/**
 * A decoder which decode binary into {@link TypeDef}. See spec documentation:
 * docs/specification/fory_xlang_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_xlang_serialization_spec">...</a>
 */
class TypeDefDecoder {
  private static final Logger LOG = LoggerFactory.getLogger(TypeDefDecoder.class);

  public static TypeDef decodeTypeDef(XtypeResolver resolver, MemoryBuffer inputBuffer, long id) {
    if ((id & COMPRESS_META_FLAG) != 0) {
      throw new DeserializationException("Compressed xlang TypeDef is not supported");
    }
    Tuple2<byte[], byte[]> decoded = decodeTypeDefBuf(inputBuffer, resolver, id);
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(decoded.f0);
    int header = buffer.readByte() & 0xff;
    boolean isStruct = (header & STRUCT_FLAG) != 0;
    int numFields = 0;
    ClassSpec classSpec;
    if (isStruct) {
      boolean named = (header & REGISTER_BY_NAME_FLAG) != 0;
      boolean compatible = (header & COMPATIBLE_FLAG) != 0;
      int typeId;
      if (named) {
        typeId = compatible ? Types.NAMED_COMPATIBLE_STRUCT : Types.NAMED_STRUCT;
      } else {
        typeId = compatible ? Types.COMPATIBLE_STRUCT : Types.STRUCT;
      }
      numFields = header & SMALL_NUM_FIELDS_THRESHOLD;
      if (numFields == SMALL_NUM_FIELDS_THRESHOLD) {
        int extraFields = buffer.readVarUInt32Small7();
        if (extraFields < 0 || extraFields > Integer.MAX_VALUE - SMALL_NUM_FIELDS_THRESHOLD) {
          throw new DeserializationException("Invalid TypeDef field count");
        }
        numFields += extraFields;
      }
      if (named) {
        String namespace = readPkgName(buffer);
        String typeName = readTypeName(buffer);
        if (Utils.DEBUG_OUTPUT_ENABLED) {
          LOG.info("Decode class {} using namespace {}", typeName, namespace);
        }
        TypeInfo userTypeInfo = resolver.getUserTypeInfo(namespace, typeName);
        if (userTypeInfo == null) {
          classSpec = new ClassSpec(UnknownClass.UnknownStruct.class, typeId, -1);
        } else {
          validateRegisteredTypeDefKind(userTypeInfo, typeId);
          classSpec = new ClassSpec(userTypeInfo.getType(), typeId, userTypeInfo.getUserTypeId());
        }
      } else {
        int userTypeId = buffer.readVarUInt32();
        TypeInfo userTypeInfo = resolver.getUserTypeInfo(userTypeId);
        if (userTypeInfo == null) {
          classSpec = new ClassSpec(UnknownClass.UnknownStruct.class, typeId, userTypeId);
        } else {
          validateRegisteredTypeDefKind(userTypeInfo, typeId);
          classSpec = new ClassSpec(userTypeInfo.getType(), typeId, userTypeId);
        }
      }
    } else {
      if ((header & 0b0111_0000) != 0) {
        throw new DeserializationException("Invalid TypeDef kind header");
      }
      int typeId = nonStructTypeId(header & 0b1111);
      boolean named = Types.isNamedType(typeId);
      if (named) {
        String namespace = readPkgName(buffer);
        String typeName = readTypeName(buffer);
        TypeInfo userTypeInfo = resolver.getUserTypeInfo(namespace, typeName);
        if (userTypeInfo == null) {
          classSpec = new ClassSpec(UnknownClass.UnknownStruct.class, typeId, -1);
        } else {
          validateRegisteredTypeDefKind(userTypeInfo, typeId);
          classSpec = new ClassSpec(userTypeInfo.getType(), typeId, userTypeInfo.getUserTypeId());
        }
      } else {
        int userTypeId = buffer.readVarUInt32();
        TypeInfo userTypeInfo = resolver.getUserTypeInfo(userTypeId);
        if (userTypeInfo == null) {
          classSpec = new ClassSpec(UnknownClass.UnknownStruct.class, typeId, userTypeId);
        } else {
          validateRegisteredTypeDefKind(userTypeInfo, typeId);
          classSpec = new ClassSpec(userTypeInfo.getType(), typeId, userTypeId);
        }
      }
    }
    List<FieldInfo> classFields =
        readFieldsInfo(buffer, resolver, classSpec.entireClassName, numFields);
    if (!isStruct && !classFields.isEmpty()) {
      throw new DeserializationException("Non-struct TypeDef cannot carry field metadata");
    }
    if (buffer.remaining() != 0) {
      throw new DeserializationException("Invalid TypeDef metadata size");
    }
    validateParsedTypeDefHash(id, decoded.f1);
    TypeDef typeDef = new TypeDef(classSpec, classFields, id, decoded.f1);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("[Java TypeDef DECODED] " + typeDef);
      // Compute and print diff with local TypeDef
      Class<?> cls = classSpec.type;
      if (cls != null && cls != UnknownClass.UnknownStruct.class) {
        TypeDef localDef = TypeDef.buildTypeDef(resolver, cls);
        String diff = typeDef.computeDiff(localDef);
        if (diff != null) {
          LOG.info("[Java TypeDef DIFF] " + classSpec.entireClassName + ":\n" + diff);
        } else {
          LOG.info("[Java TypeDef DIFF] " + classSpec.entireClassName + ": identical");
        }
      }
    }
    return typeDef;
  }

  private static void validateRegisteredTypeDefKind(TypeInfo userTypeInfo, int typeId) {
    int registeredTypeId = userTypeInfo.getTypeId();
    if (registeredTypeId != typeId && !isStructCompatibilityVariant(registeredTypeId, typeId)) {
      throw new DeserializationException(
          String.format(
              "TypeDef kind %s does not match registered kind %s for %s",
              typeId, registeredTypeId, userTypeInfo.getType()));
    }
  }

  private static boolean isStructCompatibilityVariant(int registeredTypeId, int typeId) {
    boolean registeredIdStruct =
        registeredTypeId == Types.STRUCT || registeredTypeId == Types.COMPATIBLE_STRUCT;
    boolean typeIdStruct = typeId == Types.STRUCT || typeId == Types.COMPATIBLE_STRUCT;
    if (registeredIdStruct || typeIdStruct) {
      return registeredIdStruct && typeIdStruct;
    }
    boolean registeredNamedStruct =
        registeredTypeId == Types.NAMED_STRUCT || registeredTypeId == Types.NAMED_COMPATIBLE_STRUCT;
    boolean typeIdNamedStruct =
        typeId == Types.NAMED_STRUCT || typeId == Types.NAMED_COMPATIBLE_STRUCT;
    return registeredNamedStruct && typeIdNamedStruct;
  }

  static int nonStructTypeId(int kindCode) {
    switch (kindCode) {
      case 0:
        return Types.ENUM;
      case 1:
        return Types.NAMED_ENUM;
      case 2:
        return Types.EXT;
      case 3:
        return Types.NAMED_EXT;
      case 4:
        return Types.TYPED_UNION;
      case 5:
        return Types.NAMED_UNION;
      default:
        throw new DeserializationException("Unsupported TypeDef kind code " + kindCode);
    }
  }

  // | header + type info + field name | ... | header + type info + field name |
  private static List<FieldInfo> readFieldsInfo(
      MemoryBuffer buffer, XtypeResolver resolver, String className, int numFields) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    for (int i = 0; i < numFields; i++) {
      // header: 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
      byte header = buffer.readByte();
      int encodingFlags = (header >>> 6) & 0b11;
      boolean useTagID = encodingFlags == 3;
      int fieldNameSize = (header >>> 2) & 0b1111;
      if (fieldNameSize == FIELD_NAME_SIZE_THRESHOLD) {
        fieldNameSize += buffer.readVarUInt32Small7();
      }
      fieldNameSize += 1;
      boolean nullable = (header & 0b10) != 0;
      boolean trackingRef = (header & 0b1) != 0;
      int typeId = buffer.readUInt8();
      FieldType fieldType =
          FieldTypes.FieldType.readCrossLanguage(buffer, resolver, typeId, nullable, trackingRef);

      // read field name or tag ID
      if (useTagID) {
        // When useTagID is true, fieldNameSize actually contains the tag ID
        short tagId = (short) (fieldNameSize - 1);
        // Use a placeholder field name since tag ID is used for identification
        String fieldName = "$tag" + tagId; // TODO we could use id as String as field name
        fieldInfos.add(new FieldInfo(className, fieldName, fieldType, tagId));
      } else {
        Encoding encoding = fieldNameEncodings[encodingFlags];
        String wireFieldName =
            Encoders.FIELD_NAME_DECODER.decode(buffer.readBytes(fieldNameSize), encoding);
        // Convert snake_case field names back to camelCase for Java field matching
        String fieldName = StringUtils.lowerUnderscoreToLowerCamelCase(wireFieldName);
        fieldInfos.add(new FieldInfo(className, fieldName, fieldType));
      }
    }
    return fieldInfos;
  }
}
