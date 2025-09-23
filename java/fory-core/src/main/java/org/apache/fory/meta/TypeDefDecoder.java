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

import static org.apache.fory.meta.ClassDef.HAS_FIELDS_META_FLAG;
import static org.apache.fory.meta.ClassDefDecoder.decodeClassDefBuf;
import static org.apache.fory.meta.ClassDefDecoder.readPkgName;
import static org.apache.fory.meta.ClassDefDecoder.readTypeName;
import static org.apache.fory.meta.Encoders.fieldNameEncodings;
import static org.apache.fory.meta.TypeDefEncoder.FIELD_NAME_SIZE_THRESHOLD;
import static org.apache.fory.meta.TypeDefEncoder.REGISTER_BY_NAME_FLAG;
import static org.apache.fory.meta.TypeDefEncoder.SMALL_NUM_FIELDS_THRESHOLD;

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef.FieldType;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.NonexistentClass;

/**
 * A decoder which decode binary into {@link ClassDef}. See spec documentation:
 * docs/specification/fory_xlang_serialization_spec.md <a
 * href="https://fory.apache.org/docs/specification/fory_xlang_serialization_spec">...</a>
 */
class TypeDefDecoder {
  public static ClassDef decodeClassDef(XtypeResolver resolver, MemoryBuffer inputBuffer, long id) {
    Tuple2<byte[], byte[]> decoded = decodeClassDefBuf(inputBuffer, resolver, id);
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(decoded.f0);
    byte header = buffer.readByte();
    int numFields = header & SMALL_NUM_FIELDS_THRESHOLD;
    if (numFields == SMALL_NUM_FIELDS_THRESHOLD) {
      numFields += buffer.readVarUint32Small7() + SMALL_NUM_FIELDS_THRESHOLD;
    }
    ClassSpec classSpec;
    if ((header & REGISTER_BY_NAME_FLAG) != 0) {
      String namespace = readPkgName(buffer);
      String typeName = readTypeName(buffer);
      ClassInfo userTypeInfo = resolver.getUserTypeInfo(namespace, typeName);
      if (userTypeInfo == null) {
        classSpec = new ClassSpec(NonexistentClass.NonexistentMetaShared.class);
      } else {
        classSpec = new ClassSpec(userTypeInfo.getCls());
      }
    } else {
      int xtypeId = buffer.readVarUint32Small7();
      ClassInfo userTypeInfo = resolver.getUserTypeInfo(xtypeId);
      if (userTypeInfo == null) {
        classSpec = new ClassSpec(NonexistentClass.NonexistentMetaShared.class, xtypeId);
      } else {
        classSpec = new ClassSpec(userTypeInfo.getCls(), xtypeId);
      }
    }
    List<ClassDef.FieldInfo> classFields =
        readFieldsInfo(buffer, resolver, classSpec.entireClassName, numFields);
    boolean hasFieldsMeta = (id & HAS_FIELDS_META_FLAG) != 0;
    return new ClassDef(classSpec, classFields, hasFieldsMeta, id, decoded.f1);
  }

  // | header + type info + field name | ... | header + type info + field name |
  private static List<ClassDef.FieldInfo> readFieldsInfo(
      MemoryBuffer buffer, XtypeResolver resolver, String className, int numFields) {
    List<ClassDef.FieldInfo> fieldInfos = new ArrayList<>(numFields);
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
      int typeId = buffer.readVarUint32Small14();
      FieldType fieldType = FieldType.xread(buffer, resolver, typeId, nullable, trackingRef);
      // read field name
      if (useTagID) {
        throw new UnsupportedOperationException(
            "Type tag not supported currently, parsed fieldInfos %s " + fieldInfos);
      }
      Encoding encoding = fieldNameEncodings[encodingFlags];
      String fieldName =
          Encoders.FIELD_NAME_DECODER.decode(buffer.readBytes(fieldNameSize), encoding);
      fieldInfos.add(new ClassDef.FieldInfo(className, fieldName, fieldType));
    }
    return fieldInfos;
  }
}
