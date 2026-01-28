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
import org.apache.fory.resolver.MapRefResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.DefaultValueUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
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
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("========== MetaSharedSerializer ClassDef for {} ==========", type.getName());
      LOG.info("ClassDef fieldsInfo count: {}", classDef.getFieldCount());
      for (int i = 0; i < classDef.getFieldsInfo().size(); i++) {
        LOG.info("  [{}] {}", i, classDef.getFieldsInfo().get(i));
      }
    }
    Collection<Descriptor> descriptors = consolidateFields(fory.getTypeResolver(), type, classDef);
    DescriptorGrouper descriptorGrouper =
        fory.getTypeResolver().createDescriptorGrouper(descriptors, false);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "========== MetaSharedSerializer sorted descriptors for {} ==========", type.getName());
      for (Descriptor d : descriptorGrouper.getSortedDescriptors()) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}, type id {}",
            StringUtils.toSnakeCase(d.getName()),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable(),
            Types.getDescriptorTypeId(fory, d));
      }
    }
    // d.getField() may be null if not exists in this class when meta share enabled.
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, descriptorGrouper);
    buildInFields = fieldGroups.buildInFields;
    containerFields = fieldGroups.containerFields;
    otherFields = fieldGroups.userTypeFields;
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
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
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
    T targetObject = newInstance();
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    SerializationBinding binding = this.binding;
    if (refResolver instanceof MapRefResolver) {
      MapRefResolver mapRefResolver = (MapRefResolver) refResolver;
      if (mapRefResolver.hasPreservedRefId()) {
        refResolver.reference(targetObject);
      }
    } else {
      refResolver.reference(targetObject);
    }
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        AbstractObjectSerializer.readBuildInFieldValue(binding, fieldInfo, buffer, targetObject);
      } else {
        if (fieldInfo.fieldConverter == null) {
          // Skip the field value from buffer since it doesn't exist in current class
          FieldSkipper.skipField(binding, fieldInfo, buffer);
        } else {
          compatibleRead(buffer, fieldInfo, targetObject);
        }
      }
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(targetObject, fieldValue);
      }
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = binding.readField(fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      if (fieldAccessor != null) {
        fieldAccessor.putObject(targetObject, fieldValue);
      }
    }
    return targetObject;
  }

  private void compatibleRead(MemoryBuffer buffer, SerializationFieldInfo fieldInfo, Object obj) {
    Object fieldValue = AbstractObjectSerializer.readBuildInFieldValue(binding, fieldInfo, buffer);
    fieldInfo.fieldConverter.set(obj, fieldValue);
  }

  private void readFields(MemoryBuffer buffer, Object[] fields) {
    int counter = 0;
    Fory fory = this.fory;
    SerializationBinding binding = this.binding;
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      if (fieldInfo.fieldAccessor != null) {
        fields[counter++] =
            AbstractObjectSerializer.readBuildInFieldValue(binding, fieldInfo, buffer);
      } else {
        // Skip the field value from buffer since it doesn't exist in current class.
        // For records, fieldConverter can't be used since records are immutable and
        // constructed all at once. We just read to advance buffer position.
        FieldSkipper.skipField(binding, fieldInfo, buffer);
        // remapping will handle those extra fields from peers.
        fields[counter++] = null;
      }
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue =
          AbstractObjectSerializer.readContainerFieldValue(binding, generics, fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = binding.readField(fieldInfo, buffer);
      fields[counter++] = fieldValue;
    }
  }

  public static Collection<Descriptor> consolidateFields(
      TypeResolver resolver, Class<?> cls, ClassDef classDef) {
    return classDef.getDescriptors(resolver, cls);
  }
}
