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
import static org.apache.fory.meta.TypeDef.HAS_FIELDS_META_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.FIELD_NAME_SIZE_THRESHOLD;
import static org.apache.fory.meta.TypeDefEncoder.REGISTER_BY_NAME_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.SMALL_NUM_FIELDS_THRESHOLD;

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldTypes.FieldType;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.UnknownClass;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;

/**
 * A decoder which decode binary into {@link TypeDef}. Global header layout follows the xlang spec
 * with an 8-bit meta size and flags at bits 8/9. See spec documentation:
 * docs/specification/fory_xlang_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_xlang_serialization_spec">...</a>
 */
class TypeDefDecoder {
  private static final Logger LOG = LoggerFactory.getLogger(TypeDefDecoder.class);

  public static TypeDef decodeTypeDef(XtypeResolver resolver, MemoryBuffer inputBuffer, long id) {
    Tuple2<byte[], byte[]> decoded = decodeTypeDefBuf(inputBuffer, resolver, id);
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(decoded.f0);
    byte header = buffer.readByte();
    int numFields = header & SMALL_NUM_FIELDS_THRESHOLD;
    if (numFields == SMALL_NUM_FIELDS_THRESHOLD) {
      numFields += buffer.readVarUint32Small7();
    }
    ClassSpec classSpec;
    if ((header & REGISTER_BY_NAME_FLAG) != 0) {
      String namespace = readPkgName(buffer);
      String typeName = readTypeName(buffer);
      if (Utils.DEBUG_OUTPUT_ENABLED) {
        LOG.info("Decode class {} using namespace {}", typeName, namespace);
      }
      TypeInfo userTypeInfo = resolver.getUserTypeInfo(namespace, typeName);
      if (userTypeInfo == null) {
        classSpec = new ClassSpec(UnknownClass.UnknownStruct.class);
      } else {
        classSpec = new ClassSpec(userTypeInfo.getCls());
      }
    } else {
      int typeId = buffer.readUint8();
      int userTypeId = buffer.readVarUint32();
      TypeInfo userTypeInfo = resolver.getUserTypeInfo(userTypeId);
      if (userTypeInfo == null) {
        classSpec = new ClassSpec(UnknownClass.UnknownStruct.class, typeId, userTypeId);
      } else {
        classSpec = new ClassSpec(userTypeInfo.getCls(), typeId, userTypeId);
      }
    }
    List<FieldInfo> classFields =
        readFieldsInfo(buffer, resolver, classSpec.entireClassName, numFields);
    boolean hasFieldsMeta = (id & HAS_FIELDS_META_FLAG) != 0;
    TypeDef typeDef = new TypeDef(classSpec, classFields, hasFieldsMeta, id, decoded.f1);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("[Java TypeDef DECODED] " + typeDef);
      // Compute and print diff with local TypeDef
      Class<?> cls = classSpec.type;
      if (cls != null && cls != UnknownClass.UnknownStruct.class) {
        TypeDef localDef = TypeDef.buildTypeDef(resolver.getFory(), cls);
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

  // | header + type info + field name | ... | header + type info + field name |
  private static List<FieldInfo> readFieldsInfo(
      MemoryBuffer buffer, XtypeResolver resolver, String className, int numFields) {
    List<FieldInfo> fieldInfos = new ArrayList<>(numFields);
    for (int i = 0; i < numFields; i++) {
      // header: 2 bits field name encoding + 4 bits size + nullability flag + ref tracking flag
      byte header = buffer.readByte();
      int encodingFlags = (header >>> 6) & 0b11;
      boolean useTagID = encodingFlags == 3;
      int fieldNameSize = (header >>> 2) & 0b1111;
      if (fieldNameSize == FIELD_NAME_SIZE_THRESHOLD) {
        fieldNameSize += buffer.readVarUint32Small7();
      }
      fieldNameSize += 1;
      boolean nullable = (header & 0b10) != 0;
      boolean trackingRef = (header & 0b1) != 0;
      int typeId = buffer.readUint8();
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
