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

package org.apache.fory.serializer;

import static org.apache.fory.serializer.SerializationUtils.getTypeResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.collection.IdentityObjectIntMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.MapEntry;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.resolver.MetaStringResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.Serializers.CrossLanguageCompatibleSerializer;
import org.apache.fory.serializer.UnknownClass.UnknownEnum;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.Utils;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class UnknownClassSerializers {
  private static final Logger LOG = LoggerFactory.getLogger(UnknownClassSerializers.class);

  private static final class ClassFieldsInfo {
    private final SerializationFieldInfo[] buildInFields;
    private final SerializationFieldInfo[] otherFields;
    private final SerializationFieldInfo[] containerFields;
    private final int classVersionHash;

    private ClassFieldsInfo(FieldGroups fieldGroups, int classVersionHash) {
      this.buildInFields = fieldGroups.buildInFields;
      this.otherFields = fieldGroups.userTypeFields;
      this.containerFields = fieldGroups.containerFields;
      this.classVersionHash = classVersionHash;
    }
  }

  public static final class UnknownStructSerializer extends Serializer {
    private static final int NONEXISTENT_META_SHARED_ID_SIZE =
        computeVarUint32Size(ClassResolver.NONEXISTENT_META_SHARED_ID);
    private final TypeDef typeDef;
    private final LongMap<ClassFieldsInfo> fieldsInfoMap;

    public UnknownStructSerializer(Fory fory, TypeDef typeDef) {
      super(fory, UnknownClass.UnknownStruct.class);
      this.typeDef = typeDef;
      fieldsInfoMap = new LongMap<>();
      Preconditions.checkArgument(fory.getConfig().isMetaShareEnabled());
      if (Utils.DEBUG_OUTPUT_ENABLED && typeDef != null) {
        LOG.info("========== UnknownClassSerializer TypeDef for {} ==========", type.getName());
        LOG.info("TypeDef fieldsInfo count: {}", typeDef.getFieldCount());
        for (int i = 0; i < typeDef.getFieldsInfo().size(); i++) {
          LOG.info("  [{}] {}", i, typeDef.getFieldsInfo().get(i));
        }
      }
    }

    /**
     * Multiple un existed class will correspond to this `UnknownStruct`. When querying classinfo by
     * `class`, it may dispatch to same `UnknownClassSerializer`, so we can't use `typeDef` in this
     * serializer, but use `typeDef` in `UnknownStruct` instead.
     *
     * <p>UnknownStruct is registered with a fixed internal typeId for dispatch. This serializer
     * rewinds that placeholder typeId and writes the original class's typeId, then writes the
     * shared TypeDef inline using the stream meta protocol.
     */
    private void writeTypeDef(MemoryBuffer buffer, UnknownClass.UnknownStruct value) {
      MetaContext metaContext = fory.getSerializationContext().getMetaContext();
      IdentityObjectIntMap classMap = metaContext.classMap;
      int newId = classMap.size;
      // class not exist, use class def id for identity.
      int id = classMap.putOrGet(value.typeDef.getId(), newId);
      if (id >= 0) {
        // Reference to previously written type: (index << 1) | 1, LSB=1
        buffer.writeVarUint32((id << 1) | 1);
      } else {
        // New type: index << 1, LSB=0, followed by TypeDef bytes inline
        buffer.writeVarUint32(newId << 1);
        buffer.writeBytes(value.typeDef.getEncoded());
      }
    }

    private static int computeVarUint32Size(int value) {
      if ((value & ~0x7f) == 0) {
        return 1;
      }
      if ((value & ~0x3fff) == 0) {
        return 2;
      }
      if ((value & ~0x1fffff) == 0) {
        return 3;
      }
      if ((value & ~0xfffffff) == 0) {
        return 4;
      }
      return 5;
    }

    private int resolveTypeId(TypeDef typeDef) {
      if (typeDef.getClassSpec().isEnum) {
        if (typeDef.isNamed()) {
          return Types.NAMED_ENUM;
        }
        return Types.ENUM;
      }
      if (typeDef.isNamed()) {
        return typeDef.isCompatible() ? Types.NAMED_COMPATIBLE_STRUCT : Types.NAMED_STRUCT;
      }
      return typeDef.isCompatible() ? Types.COMPATIBLE_STRUCT : Types.STRUCT;
    }

    @Override
    public void write(MemoryBuffer buffer, Object v) {
      UnknownClass.UnknownStruct value = (UnknownClass.UnknownStruct) v;
      int typeId = resolveTypeId(value.typeDef);
      int userTypeId = value.typeDef.isNamed() ? -1 : value.typeDef.getUserTypeId();
      int typeIdSize = 1;
      int userTypeIdSize = userTypeId != -1 ? computeVarUint32Size(userTypeId) : 0;
      if (fory.isCrossLanguage()) {
        buffer.writeUint8(typeId);
        if (userTypeIdSize > 0) {
          buffer.writeVarUint32(userTypeId);
        }
      } else {
        int totalSize = typeIdSize + userTypeIdSize;
        if (totalSize == NONEXISTENT_META_SHARED_ID_SIZE) {
          buffer.increaseWriterIndex(-NONEXISTENT_META_SHARED_ID_SIZE);
          buffer.writeUint8(typeId);
          if (userTypeIdSize > 0) {
            buffer.writeVarUint32(userTypeId);
          }
        } else {
          int originalWriterIndex = buffer.writerIndex();
          int placeholderStart = originalWriterIndex - NONEXISTENT_META_SHARED_ID_SIZE;
          int payloadStart = placeholderStart + NONEXISTENT_META_SHARED_ID_SIZE;
          int payloadLength = originalWriterIndex - payloadStart;
          byte[] payload = buffer.getBytes(payloadStart, payloadLength);
          buffer.writerIndex(placeholderStart);
          buffer.writeUint8(typeId);
          if (userTypeIdSize > 0) {
            buffer.writeVarUint32(userTypeId);
          }
          buffer.writeBytes(payload);
        }
      }
      writeTypeDef(buffer, value);
      TypeDef typeDef = value.typeDef;
      ClassFieldsInfo fieldsInfo = getClassFieldsInfo(typeDef);
      Fory fory = this.fory;
      RefResolver refResolver = fory.getRefResolver();
      TypeResolver typeResolver = fory.getTypeResolver();
      if (fory.checkClassVersion()) {
        buffer.writeInt32(fieldsInfo.classVersionHash);
      }
      // write order: primitive,boxed,final,other,collection,map
      for (SerializationFieldInfo fieldInfo : fieldsInfo.buildInFields) {
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        AbstractObjectSerializer.writeBuildInFieldValue(
            fory, typeResolver, refResolver, fieldInfo, buffer, fieldValue);
      }
      Generics generics = fory.getGenerics();
      for (SerializationFieldInfo fieldInfo : fieldsInfo.containerFields) {
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        AbstractObjectSerializer.writeContainerFieldValue(
            fory, typeResolver, refResolver, generics, fieldInfo, buffer, fieldValue);
      }
      for (SerializationFieldInfo fieldInfo : fieldsInfo.otherFields) {
        Object fieldValue = value.get(fieldInfo.qualifiedFieldName);
        AbstractObjectSerializer.writeField(
            fory, typeResolver, refResolver, fieldInfo, buffer, fieldValue);
      }
    }

    private ClassFieldsInfo getClassFieldsInfo(TypeDef typeDef) {
      ClassFieldsInfo fieldsInfo = fieldsInfoMap.get(typeDef.getId());
      TypeResolver resolver = getTypeResolver(fory);
      if (fieldsInfo == null) {
        // Use `UnknownEmptyStruct` since it doesn't have any field.
        Collection<Descriptor> descriptors =
            MetaSharedSerializer.consolidateFields(
                resolver, UnknownClass.UnknownEmptyStruct.class, typeDef);
        DescriptorGrouper grouper =
            fory.getClassResolver().createDescriptorGrouper(descriptors, false);
        FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, grouper);
        int classVersionHash = 0;
        if (fory.checkClassVersion()) {
          classVersionHash = ObjectSerializer.computeStructHash(fory, grouper);
        }
        fieldsInfo = new ClassFieldsInfo(fieldGroups, classVersionHash);
        fieldsInfoMap.put(typeDef.getId(), fieldsInfo);
      }
      return fieldsInfo;
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      UnknownClass.UnknownStruct obj = new UnknownClass.UnknownStruct(typeDef);
      Fory fory = this.fory;
      RefResolver refResolver = fory.getRefResolver();
      TypeResolver typeResolver = fory.getTypeResolver();
      refResolver.reference(obj);
      List<MapEntry> entries = new ArrayList<>();
      // read order: primitive,boxed,final,other,collection,map
      ClassFieldsInfo allFieldsInfo = getClassFieldsInfo(typeDef);
      for (SerializationFieldInfo fieldInfo : allFieldsInfo.buildInFields) {
        Object fieldValue =
            AbstractObjectSerializer.readBuildInFieldValue(
                fory, typeResolver, refResolver, fieldInfo, buffer);
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      Generics generics = fory.getGenerics();
      for (SerializationFieldInfo fieldInfo : allFieldsInfo.containerFields) {
        Object fieldValue =
            AbstractObjectSerializer.readContainerFieldValue(
                fory, typeResolver, refResolver, generics, fieldInfo, buffer);
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      for (SerializationFieldInfo fieldInfo : allFieldsInfo.otherFields) {
        Object fieldValue =
            AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
        entries.add(new MapEntry(fieldInfo.qualifiedFieldName, fieldValue));
      }
      obj.setEntries(entries);
      return obj;
    }
  }

  public static final class UnknownEnumSerializer extends CrossLanguageCompatibleSerializer {
    private final UnknownEnum[] enumConstants;
    private final MetaStringResolver metaStringResolver;

    public UnknownEnumSerializer(Fory fory) {
      super(fory, UnknownEnum.class);
      metaStringResolver = fory.getMetaStringResolver();
      enumConstants = UnknownEnum.class.getEnumConstants();
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {
      UnknownEnum enumValue = (UnknownEnum) value;
      buffer.writeVarUint32Small7(enumValue.ordinal());
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      if (fory.getConfig().serializeEnumByName()) {
        metaStringResolver.readMetaStringBytes(buffer);
        return UnknownEnum.UNKNOWN;
      }

      int ordinal = buffer.readVarUint32Small7();
      if (ordinal >= enumConstants.length) {
        return UnknownEnum.UNKNOWN;
      }
      return enumConstants[ordinal];
    }
  }

  public static Serializer getSerializer(Fory fory, String className, Class<?> cls) {
    if (cls.isArray()) {
      return new ArraySerializers.UnknownArraySerializer(fory, className, cls);
    } else {
      if (cls.isEnum()) {
        return new UnknownEnumSerializer(fory);
      } else {
        if (fory.getConfig().isMetaShareEnabled()) {
          throw new IllegalStateException(
              String.format(
                  "Serializer of class %s should be set in ClassResolver#getMetaSharedTypeInfo",
                  className));
        } else {
          return new ObjectSerializer(fory, cls);
        }
      }
    }
  }
}
