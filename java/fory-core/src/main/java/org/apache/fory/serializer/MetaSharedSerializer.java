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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.builder.MetaSharedCodecBuilder;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.DefaultValueUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.Utils;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

/**
 * A meta-shared compatible deserializer builder based on {@link ClassDef}. This serializer will
 * compare fields between {@link ClassDef} and class fields, then create serializer to read and
 * set/skip corresponding fields to support type forward/backward compatibility. Serializer are
 * forward to {@link ObjectSerializer} for now. We can consolidate fields between peers to create
 * better serializers to serialize common fields between peers for efficiency.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectSerializer} will take
 * all non-inner final types as non-final, so that fory can write class definition when write class
 * info for those types.
 *
 * @see CompatibleMode
 * @see ForyBuilder#withMetaShare
 * @see MetaSharedCodecBuilder
 * @see ObjectSerializer
 */
public class MetaSharedSerializer<T> extends AbstractObjectSerializer<T> {
  private static final Logger LOG = LoggerFactory.getLogger(MetaSharedSerializer.class);

  private final SerializationFieldInfo[] buildInFields;
  private final SerializationFieldInfo[] containerFields;
  private final SerializationFieldInfo[] otherFields;
  private final RecordInfo recordInfo;
  private Serializer<T> serializer;
  private final ClassInfoHolder classInfoHolder;
  private final SerializationBinding binding;
  private final boolean hasDefaultValues;
  private final DefaultValueUtils.DefaultValueField[] defaultValueFields;

  public MetaSharedSerializer(Fory fory, Class<T> type, ClassDef classDef) {
    super(fory, type);
    Preconditions.checkArgument(
        !fory.getConfig().checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    Preconditions.checkArgument(
        fory.getConfig().isMetaShareEnabled(), "Meta share must be enabled.");
    if (Utils.debugOutputEnabled()) {
      LOG.info("========== MetaSharedSerializer ClassDef for {} ==========", type.getName());
      LOG.info("ClassDef fieldsInfo count: {}", classDef.getFieldsInfo().size());
      for (int i = 0; i < classDef.getFieldsInfo().size(); i++) {
        LOG.info("  [{}] {}", i, classDef.getFieldsInfo().get(i));
      }
    }
    Collection<Descriptor> descriptors = consolidateFields(fory._getTypeResolver(), type, classDef);
    DescriptorGrouper descriptorGrouper =
        fory._getTypeResolver().createDescriptorGrouper(descriptors, false);
    if (Utils.debugOutputEnabled()) {
      LOG.info(
          "========== MetaSharedSerializer sorted descriptors for {} ==========", type.getName());
      for (Descriptor d : descriptorGrouper.getSortedDescriptors()) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            d.getName(),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable());
      }
    }
    // d.getField() may be null if not exists in this class when meta share enabled.
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, descriptorGrouper);
    buildInFields = fieldGroups.buildInFields;
    containerFields = fieldGroups.containerFields;
    otherFields = fieldGroups.userTypeFields;
    classInfoHolder = this.classResolver.nilClassInfoHolder();
    if (isRecord) {
      List<String> fieldNames =
          descriptorGrouper.getSortedDescriptors().stream()
              .map(Descriptor::getName)
              .collect(Collectors.toList());
      recordInfo = new RecordInfo(type, fieldNames);
    } else {
      recordInfo = null;
    }
    binding = SerializationBinding.createBinding(fory);
    boolean hasDefaultValues = false;
    DefaultValueUtils.DefaultValueField[] defaultValueFields =
        new DefaultValueUtils.DefaultValueField[0];
    DefaultValueUtils.DefaultValueSupport defaultValueSupport;
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      defaultValueSupport = DefaultValueUtils.getScalaDefaultValueSupport();
      hasDefaultValues = defaultValueSupport.hasDefaultValues(type);
      defaultValueFields =
          defaultValueSupport.buildDefaultValueFields(
              fory, type, descriptorGrouper.getSortedDescriptors());
    }
    if (!hasDefaultValues) {
      DefaultValueUtils.DefaultValueSupport kotlinDefaultValueSupport =
          DefaultValueUtils.getKotlinDefaultValueSupport();
      if (kotlinDefaultValueSupport != null) {
        hasDefaultValues = kotlinDefaultValueSupport.hasDefaultValues(type);
        defaultValueFields =
            kotlinDefaultValueSupport.buildDefaultValueFields(
                fory, type, descriptorGrouper.getSortedDescriptors());
      }
    }
    this.hasDefaultValues = hasDefaultValues;
    this.defaultValueFields = defaultValueFields;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    if (serializer == null) {
      // xlang mode will register class and create serializer in advance, it won't go to here.
      serializer =
          this.classResolver.createSerializerSafe(type, () -> new ObjectSerializer<>(fory, type));
    }
    serializer.write(buffer, value);
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fieldValues =
          new Object[buildInFields.length + otherFields.length + containerFields.length];
      readFields(buffer, fieldValues);
      fieldValues = RecordUtils.remapping(recordInfo, fieldValues);
      T t = objectCreator.newInstanceWithArguments(fieldValues);
      Arrays.fill(recordInfo.getRecordComponents(), null);
      return t;
    }
    T obj = newInstance();
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    TypeResolver typeResolver = this.typeResolver;
    SerializationBinding binding = this.binding;
    refResolver.reference(obj);
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      if (fieldAccessor != null) {
        short classId = fieldInfo.classId;
        boolean needRead = true;
        if (fieldInfo.isPrimitive) {
          if (nullable) {
            needRead = readPrimitiveNullableFieldValue(fory, buffer, obj, fieldAccessor, classId);
          } else {
            needRead = readPrimitiveFieldValue(fory, buffer, obj, fieldAccessor, classId);
          }
        }
        if (needRead
            && (nullable
                ? AbstractObjectSerializer.readBasicNullableObjectFieldValue(
                    fory, buffer, obj, fieldAccessor, classId)
                : AbstractObjectSerializer.readBasicObjectFieldValue(
                    fory, buffer, obj, fieldAccessor, classId))) {
          assert fieldInfo.classInfo != null;
          Object fieldValue =
              AbstractObjectSerializer.readFinalObjectFieldValue(
                  binding, refResolver, typeResolver, fieldInfo, buffer);
          fieldAccessor.putObject(obj, fieldValue);
        }
      } else {
        if (fieldInfo.fieldConverter == null) {
          // Skip the field value from buffer since it doesn't exist in current class
          if (skipPrimitiveFieldValueFailed(fory, fieldInfo.classId, buffer)) {
            if (fieldInfo.classInfo == null) {
              // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
              binding.readRef(buffer, classInfoHolder);
            } else {
              AbstractObjectSerializer.readFinalObjectFieldValue(
                  binding, refResolver, typeResolver, fieldInfo, buffer);
            }
          }
        } else {
          compatibleRead(buffer, fieldInfo, obj);
        }
      }
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = AbstractObjectSerializer.readOtherFieldValue(binding, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    return obj;
  }

  private void compatibleRead(MemoryBuffer buffer, SerializationFieldInfo fieldInfo, Object obj) {
    Object fieldValue;
    short classId = fieldInfo.classId;
    if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
        && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
      fieldValue = Serializers.readPrimitiveValue(fory, buffer, classId);
    } else {
      fieldValue =
          AbstractObjectSerializer.readFinalObjectFieldValue(
              binding, refResolver, classResolver, fieldInfo, buffer);
    }
    fieldInfo.fieldConverter.set(obj, fieldValue);
  }

  private T newInstance() {
    if (!hasDefaultValues) {
      return newBean();
    }
    T obj = GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE ? newBean() : Platform.newInstance(type);
    // Set default values for missing fields in Scala case classes
    DefaultValueUtils.setDefaultValues(obj, defaultValueFields);
    return obj;
  }

  @Override
  public T xread(MemoryBuffer buffer) {
    return read(buffer);
  }

  private void readFields(MemoryBuffer buffer, Object[] fields) {
    int counter = 0;
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    ClassResolver classResolver = this.classResolver;
    SerializationBinding binding = this.binding;
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      if (fieldInfo.fieldAccessor != null) {
        assert fieldInfo.classInfo != null;
        short classId = fieldInfo.classId;
        // primitive field won't write null flag.
        if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
            && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
          fields[counter++] = Serializers.readPrimitiveValue(fory, buffer, classId);
        } else {
          Object fieldValue =
              AbstractObjectSerializer.readFinalObjectFieldValue(
                  binding, refResolver, classResolver, fieldInfo, buffer);
          fields[counter++] = fieldValue;
        }
      } else {
        // Skip the field value from buffer since it doesn't exist in current class
        if (skipPrimitiveFieldValueFailed(fory, fieldInfo.classId, buffer)) {
          if (fieldInfo.classInfo == null) {
            // TODO(chaokunyang) support registered serializer in peer with ref tracking disabled.
            fory.readRef(buffer, classInfoHolder);
          } else {
            AbstractObjectSerializer.readFinalObjectFieldValue(
                binding, refResolver, classResolver, fieldInfo, buffer);
          }
        }
        // remapping will handle those extra fields from peers.
        fields[counter++] = null;
      }
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = AbstractObjectSerializer.readOtherFieldValue(binding, fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
  }

  /** Skip primitive primitive field value since it doesn't write null flag. */
  static boolean skipPrimitiveFieldValueFailed(Fory fory, short classId, MemoryBuffer buffer) {
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_BYTE_CLASS_ID:
        buffer.increaseReaderIndex(1);
        return false;
      case ClassResolver.PRIMITIVE_CHAR_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_SHORT_CLASS_ID:
        buffer.increaseReaderIndex(2);
        return false;
      case ClassResolver.PRIMITIVE_INT_CLASS_ID:
        if (fory.compressInt()) {
          buffer.readVarInt32();
        } else {
          buffer.increaseReaderIndex(4);
        }
        return false;
      case ClassResolver.PRIMITIVE_FLOAT_CLASS_ID:
        buffer.increaseReaderIndex(4);
        return false;
      case ClassResolver.PRIMITIVE_LONG_CLASS_ID:
        fory.readInt64(buffer);
        return false;
      case ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID:
        buffer.increaseReaderIndex(8);
        return false;
      default:
        {
          return true;
        }
    }
  }

  public static Collection<Descriptor> consolidateFields(
      TypeResolver resolver, Class<?> cls, ClassDef classDef) {
    return classDef.getDescriptors(resolver, cls);
  }
}
