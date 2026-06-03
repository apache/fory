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
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.builder.CompatibleCodecBuilder;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.RefReader;
import org.apache.fory.context.WriteContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.type.ScalaTypes;
import org.apache.fory.type.Types;
import org.apache.fory.util.DefaultValueUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

/**
 * A compatible deserializer based on shared {@link TypeDef} metadata. This serializer compares
 * remote fields with local class fields, then reads, sets, or skips fields to support type
 * forward/backward compatibility. Writes are delegated to {@link ObjectSerializer} for now.
 *
 * <p>With meta context share enabled and compatible mode, the {@link ObjectSerializer} will take
 * all non-inner final types as non-final, so that fory can write class definition when write class
 * info for those types.
 *
 * @see ForyBuilder#withMetaShare
 * @see CompatibleCodecBuilder
 * @see ObjectSerializer
 */
public class CompatibleSerializer<T> extends AbstractObjectSerializer<T> {
  private static final Logger LOG = LoggerFactory.getLogger(CompatibleSerializer.class);

  private final SerializationFieldInfo[] allFields;
  private final CompatibleCollectionArrayReader.ReadAction[] allCompatibleReadActions;
  private final boolean hasCompatibleCollectionArrayRead;
  private final RecordInfo recordInfo;
  private Serializer<T> serializer;
  private final boolean hasDefaultValues;
  private final DefaultValueUtils.DefaultValueField[] defaultValueFields;

  public CompatibleSerializer(TypeResolver typeResolver, Class<T> type, TypeDef typeDef) {
    super(typeResolver, type);
    Preconditions.checkArgument(
        !config.checkClassVersion(),
        "Class version check should be disabled when compatible mode is enabled.");
    Preconditions.checkArgument(config.isMetaShareEnabled(), "Meta share must be enabled.");
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info("========== CompatibleSerializer TypeDef for {} ==========", type.getName());
      LOG.info("TypeDef fieldsInfo count: {}", typeDef.getFieldCount());
      for (int i = 0; i < typeDef.getFieldsInfo().size(); i++) {
        LOG.info("  [{}] {}", i, typeDef.getFieldsInfo().get(i));
      }
    }
    DescriptorGrouper descriptorGrouper = typeResolver.createDescriptorGrouper(typeDef, type);
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "========== CompatibleSerializer sorted descriptors for {} ==========", type.getName());
      for (Descriptor d : descriptorGrouper.getSortedDescriptors()) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}, type id {}",
            StringUtils.toSnakeCase(d.getName()),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable(),
            Types.getDescriptorTypeId(typeResolver, d));
      }
    }
    // d.getField() may be null if not exists in this class when meta share enabled.
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(typeResolver, descriptorGrouper);
    allFields = fieldGroups.allFields;
    allCompatibleReadActions = buildCompatibleCollectionArrayReadActions(typeResolver, allFields);
    hasCompatibleCollectionArrayRead = allCompatibleReadActions != null;
    if (isRecord) {
      List<String> fieldNames =
          descriptorGrouper.getSortedDescriptors().stream()
              .map(Descriptor::getName)
              .collect(Collectors.toList());
      recordInfo = new RecordInfo(type, fieldNames);
    } else {
      recordInfo = null;
    }
    boolean hasDefaultValues = false;
    DefaultValueUtils.DefaultValueField[] defaultValueFields =
        new DefaultValueUtils.DefaultValueField[0];
    DefaultValueUtils.DefaultValueSupport defaultValueSupport;
    if (ScalaTypes.SCALA_AVAILABLE) {
      defaultValueSupport = DefaultValueUtils.getScalaDefaultValueSupport();
      hasDefaultValues = defaultValueSupport.hasDefaultValues(type);
      defaultValueFields =
          defaultValueSupport.buildDefaultValueFields(
              typeResolver, type, descriptorGrouper.getSortedDescriptors());
    }
    if (!hasDefaultValues) {
      DefaultValueUtils.DefaultValueSupport kotlinDefaultValueSupport =
          DefaultValueUtils.getKotlinDefaultValueSupport();
      if (kotlinDefaultValueSupport != null) {
        hasDefaultValues = kotlinDefaultValueSupport.hasDefaultValues(type);
        defaultValueFields =
            kotlinDefaultValueSupport.buildDefaultValueFields(
                typeResolver, type, descriptorGrouper.getSortedDescriptors());
      }
    }
    this.hasDefaultValues = hasDefaultValues;
    this.defaultValueFields = defaultValueFields;
  }

  /** Used by generated compatible serializers for top-level list/array compatible field reads. */
  public static Object readCompatibleCollectionArrayField(
      ReadContext readContext,
      boolean trackingRef,
      boolean nullable,
      int readMode,
      int arrayTypeId,
      int elementTypeId,
      Class<?> targetType) {
    return CompatibleCollectionArrayReader.read(
        readContext,
        RefMode.of(trackingRef, nullable),
        readMode,
        arrayTypeId,
        elementTypeId,
        targetType);
  }

  /** Used by generated compatible serializers to cache a top-level list/array read action. */
  public static int compatibleCollectionArrayReadMode(
      TypeResolver resolver, Descriptor descriptor) {
    return requireCompatibleCollectionArrayReadAction(resolver, descriptor).mode;
  }

  /** Used by generated compatible serializers to cache the dense array carrier type. */
  public static int compatibleCollectionArrayTypeId(TypeResolver resolver, Descriptor descriptor) {
    return requireCompatibleCollectionArrayReadAction(resolver, descriptor).arrayTypeId;
  }

  /** Used by generated compatible serializers to cache the peer or local element type. */
  public static int compatibleCollectionElementTypeId(
      TypeResolver resolver, Descriptor descriptor) {
    return requireCompatibleCollectionArrayReadAction(resolver, descriptor).elementTypeId;
  }

  /** Returns whether a descriptor has a top-level list/array compatible read action. */
  public static boolean hasCompatibleCollectionArrayRead(
      TypeResolver resolver, Descriptor descriptor) {
    return CompatibleCollectionArrayReader.readAction(resolver, descriptor) != null;
  }

  private static CompatibleCollectionArrayReader.ReadAction
      requireCompatibleCollectionArrayReadAction(TypeResolver resolver, Descriptor descriptor) {
    CompatibleCollectionArrayReader.ReadAction action =
        CompatibleCollectionArrayReader.readAction(resolver, descriptor);
    if (action == null) {
      throw new IllegalArgumentException(
          "Descriptor has no top-level list/array compatible read action: " + descriptor);
    }
    return action;
  }

  private static CompatibleCollectionArrayReader.ReadAction[]
      buildCompatibleCollectionArrayReadActions(
          TypeResolver resolver, SerializationFieldInfo[] fields) {
    CompatibleCollectionArrayReader.ReadAction[] actions = null;
    for (int i = 0; i < fields.length; i++) {
      CompatibleCollectionArrayReader.ReadAction action =
          CompatibleCollectionArrayReader.readAction(resolver, fields[i].descriptor);
      if (action != null) {
        if (actions == null) {
          actions = new CompatibleCollectionArrayReader.ReadAction[fields.length];
        }
        actions[i] = action;
      }
    }
    return actions;
  }

  private static CompatibleCollectionArrayReader.ReadAction compatibleCollectionArrayReadAction(
      CompatibleCollectionArrayReader.ReadAction[] actions, int index) {
    return actions == null ? null : actions[index];
  }

  @Override
  public void write(WriteContext writeContext, T value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    if (serializer == null) {
      // xlang mode will register class and create serializer in advance, it won't go to here.
      serializer =
          ((ClassResolver) typeResolver)
              .createSerializerSafe(type, () -> new ObjectSerializer<>(typeResolver, type));
    }
    serializer.write(writeContext, value);
  }

  private T newInstance() {
    if (!hasDefaultValues) {
      return newBean();
    }
    T obj = newBean();
    // Set default values for missing fields in Scala case classes
    DefaultValueUtils.setDefaultValues(obj, defaultValueFields);
    return obj;
  }

  @Override
  public T read(ReadContext readContext) {
    if (isRecord) {
      Object[] fieldValues = new Object[allFields.length];
      if (hasCompatibleCollectionArrayRead) {
        readFieldsWithCompatibleCollectionArray(readContext, fieldValues);
      } else {
        readFields(readContext, fieldValues);
      }
      fieldValues = RecordUtils.remapping(recordInfo, fieldValues);
      T t = objectInstantiator.newInstanceWithArguments(fieldValues);
      Arrays.fill(recordInfo.getRecordComponents(), null);
      return t;
    }
    T targetObject = newInstance();
    if (readContext.hasPreservedRefId()) {
      readContext.reference(targetObject);
    }
    if (hasCompatibleCollectionArrayRead) {
      readFieldsWithCompatibleCollectionArray(readContext, targetObject);
    } else {
      readFields(readContext, targetObject);
    }
    return targetObject;
  }

  private void setFieldValue(T targetObject, SerializationFieldInfo fieldInfo, Object fieldValue) {
    if (fieldInfo.fieldAccessor != null) {
      fieldInfo.fieldAccessor.putObject(targetObject, fieldValue);
    } else if (fieldInfo.fieldConverter != null) {
      fieldInfo.fieldConverter.set(targetObject, fieldValue);
    }
  }

  private void readFields(ReadContext readContext, T targetObject) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      readField(readContext, targetObject, refReader, generics, fieldInfo, buffer, null);
    }
  }

  private void readFields(ReadContext readContext, Object[] fields) {
    MemoryBuffer buffer = readContext.getBuffer();
    int counter = 0;
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (SerializationFieldInfo fieldInfo : allFields) {
      fields[counter++] = readField(readContext, refReader, generics, fieldInfo, buffer, null);
    }
  }

  private void compatibleRead(
      ReadContext readContext, SerializationFieldInfo fieldInfo, Object obj) {
    MemoryBuffer buffer = readContext.getBuffer();
    Object fieldValue =
        AbstractObjectSerializer.readBuildInFieldValue(
            readContext, typeResolver, readContext.getRefReader(), fieldInfo, buffer);
    fieldInfo.fieldConverter.set(obj, fieldValue);
  }

  private void readFieldsWithCompatibleCollectionArray(ReadContext readContext, T targetObject) {
    MemoryBuffer buffer = readContext.getBuffer();
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (int i = 0; i < allFields.length; i++) {
      SerializationFieldInfo fieldInfo = allFields[i];
      CompatibleCollectionArrayReader.ReadAction action =
          compatibleCollectionArrayReadAction(allCompatibleReadActions, i);
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printFieldDebugInfo(fieldInfo, buffer);
      }
      readField(readContext, targetObject, refReader, generics, fieldInfo, buffer, action);
    }
  }

  private void readFieldsWithCompatibleCollectionArray(ReadContext readContext, Object[] fields) {
    MemoryBuffer buffer = readContext.getBuffer();
    int counter = 0;
    RefReader refReader = readContext.getRefReader();
    Generics generics = readContext.getGenerics();
    for (int i = 0; i < allFields.length; i++) {
      SerializationFieldInfo fieldInfo = allFields[i];
      CompatibleCollectionArrayReader.ReadAction action =
          compatibleCollectionArrayReadAction(allCompatibleReadActions, i);
      if (Utils.DEBUG_OUTPUT_ENABLED) {
        printFieldDebugInfo(fieldInfo, buffer);
      }
      fields[counter++] = readField(readContext, refReader, generics, fieldInfo, buffer, action);
    }
  }

  private void readField(
      ReadContext readContext,
      T targetObject,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      CompatibleCollectionArrayReader.ReadAction action) {
    FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
    if (fieldAccessor == null) {
      if (fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.BUILD_IN) {
        if (fieldInfo.fieldConverter == null) {
          FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
        } else {
          compatibleRead(readContext, fieldInfo, targetObject);
        }
      } else {
        readField(readContext, refReader, generics, fieldInfo, buffer, action);
      }
      return;
    }
    if (fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.BUILD_IN && action == null) {
      AbstractObjectSerializer.readBuildInFieldValue(
          readContext, typeResolver, refReader, fieldInfo, buffer, targetObject);
      return;
    }
    fieldAccessor.putObject(
        targetObject, readField(readContext, refReader, generics, fieldInfo, buffer, action));
  }

  private Object readField(
      ReadContext readContext,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      CompatibleCollectionArrayReader.ReadAction action) {
    if (action != null) {
      return CompatibleCollectionArrayReader.read(readContext, fieldInfo.refMode, action);
    }
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        if (fieldInfo.fieldAccessor == null) {
          FieldSkipper.skipField(readContext, typeResolver, refReader, fieldInfo, buffer);
          return null;
        }
        return AbstractObjectSerializer.readBuildInFieldValue(
            readContext, typeResolver, refReader, fieldInfo, buffer);
      case CONTAINER:
        return AbstractObjectSerializer.readContainerFieldValue(
            readContext, typeResolver, refReader, generics, fieldInfo, buffer);
      case OTHER:
        return AbstractObjectSerializer.readField(
            readContext, typeResolver, refReader, fieldInfo, buffer);
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  private Object readFieldValue(
      ReadContext readContext,
      RefReader refReader,
      Generics generics,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer,
      CompatibleCollectionArrayReader.ReadAction action) {
    if (fieldInfo.fieldAccessor == null
        && fieldInfo.fieldConverter != null
        && fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.BUILD_IN
        && action == null) {
      return AbstractObjectSerializer.readBuildInFieldValue(
          readContext, typeResolver, refReader, fieldInfo, buffer);
    }
    return readField(readContext, refReader, generics, fieldInfo, buffer, action);
  }

  private void printFieldDebugInfo(SerializationFieldInfo fieldInfo, MemoryBuffer buffer) {
    LOG.info(
        "[Java] read field {} of type {}, reader index {}",
        fieldInfo.descriptor.getName(),
        fieldInfo.typeRef,
        buffer.readerIndex());
  }
}
