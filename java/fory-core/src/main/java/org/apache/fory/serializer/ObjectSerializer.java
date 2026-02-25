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

import static org.apache.fory.serializer.AbstractObjectSerializer.readBuildInFieldValue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.exception.ForyException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.struct.Fingerprint;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.MurmurHash3;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.Utils;
import org.apache.fory.util.record.RecordInfo;
import org.apache.fory.util.record.RecordUtils;

/**
 * A schema-consistent serializer used only for java serialization.
 *
 * <ul>
 *   <li>non-public class
 *   <li>non-static class
 *   <li>lambda
 *   <li>inner class
 *   <li>local class
 *   <li>anonymous class
 *   <li>class that can't be handled by other serializers or codegen-based serializers
 * </ul>
 */
// TODO(chaokunyang) support generics optimization for {@code SomeClass<T>}
@SuppressWarnings({"unchecked"})
public final class ObjectSerializer<T> extends AbstractObjectSerializer<T> {
  private static final Logger LOG = LoggerFactory.getLogger(ObjectSerializer.class);

  private final RecordInfo recordInfo;
  private final SerializationFieldInfo[] buildInFields;
  private final SerializationFieldInfo[] otherFields;
  private final SerializationFieldInfo[] containerFields;
  private final int classVersionHash;
  private final TypeResolver typeResolver;

  public ObjectSerializer(Fory fory, Class<T> cls) {
    this(fory, cls, true);
  }

  public ObjectSerializer(Fory fory, Class<T> cls, boolean resolveParent) {
    super(fory, cls);
    // avoid recursive building serializers.
    // Use `setSerializerIfAbsent` to avoid overwriting existing serializer for class when used
    // as data serializer.
    if (resolveParent) {
      classResolver.setSerializerIfAbsent(cls, this);
    }
    typeResolver = fory.getTypeResolver();
    Collection<Descriptor> descriptors;
    boolean shareMeta = fory.getConfig().isMetaShareEnabled();
    if (shareMeta) {
      TypeDef typeDef = typeResolver.getTypeDef(cls, resolveParent);
      if (Utils.DEBUG_OUTPUT_ENABLED) {
        LOG.info("========== ObjectSerializer TypeDef for {} ==========", cls.getName());
        LOG.info("TypeDef fieldsInfo count: {}", typeDef.getFieldsInfo().size());
        for (int i = 0; i < typeDef.getFieldsInfo().size(); i++) {
          LOG.info("  [{}] {}", i, typeDef.getFieldsInfo().get(i));
        }
      }
      descriptors = typeDef.getDescriptors(typeResolver, cls);
    } else {
      descriptors = typeResolver.getFieldDescriptors(cls, resolveParent);
    }
    DescriptorGrouper grouper = typeResolver.createDescriptorGrouper(descriptors, false);
    descriptors = grouper.getSortedDescriptors();
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "========== ObjectSerializer {} sorted descriptors for {} ==========",
          descriptors.size(),
          cls.getName());
      for (Descriptor d : descriptors) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            StringUtils.toSnakeCase(d.getName()),
            d.getTypeName(),
            d.isTrackingRef(),
            d.isNullable());
      }
    }
    if (isRecord) {
      List<String> fieldNames =
          descriptors.stream().map(Descriptor::getName).collect(Collectors.toList());
      recordInfo = new RecordInfo(cls, fieldNames);
    } else {
      recordInfo = null;
    }
    if (fory.checkClassVersion()) {
      classVersionHash = computeStructHash(fory, grouper);
    } else {
      classVersionHash = 0;
    }
    FieldGroups fieldGroups = FieldGroups.buildFieldInfos(fory, grouper);
    buildInFields = fieldGroups.buildInFields;
    otherFields = fieldGroups.userTypeFields;
    containerFields = fieldGroups.containerFields;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    if (fory.checkClassVersion()) {
      buffer.writeInt32(classVersionHash);
    }
    // write order: primitive,boxed,final,other,collection,map
    writeBuildInFields(buffer, value, fory);
    writeContainerFields(buffer, value, fory, refResolver);
    writeOtherFields(buffer, value);
  }

  private void printWriteFieldDebugInfo(SerializationFieldInfo fieldInfo, MemoryBuffer buffer) {
    LOG.info(
        "[Java] write field {} of type {}, writer index {}",
        fieldInfo.descriptor.getName(),
        fieldInfo.typeRef,
        buffer.writerIndex());
  }

  private void printReadFieldDebugInfo(SerializationFieldInfo fieldInfo, MemoryBuffer buffer) {
    LOG.info(
        "[Java] read field {} of type {}, reader index {}",
        fieldInfo.descriptor.getName(),
        fieldInfo.typeRef,
        buffer.readerIndex());
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printWriteFieldDebugInfo(fieldInfo, buffer);
      }
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      AbstractObjectSerializer.writeField(
          fory, typeResolver, refResolver, fieldInfo, buffer, fieldValue);
    }
  }

  private void writeBuildInFields(MemoryBuffer buffer, T value, Fory fory) {
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printWriteFieldDebugInfo(fieldInfo, buffer);
      }
      AbstractObjectSerializer.writeBuildInField(
          fory, typeResolver, refResolver, fieldInfo, buffer, value);
    }
  }

  private void writeContainerFields(
      MemoryBuffer buffer, T value, Fory fory, RefResolver refResolver) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printWriteFieldDebugInfo(fieldInfo, buffer);
      }
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeContainerFieldValue(
          fory, typeResolver, refResolver, generics, fieldInfo, buffer, fieldValue);
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fields = readFields(buffer);
      fields = RecordUtils.remapping(recordInfo, fields);
      T obj = objectCreator.newInstanceWithArguments(fields);
      Arrays.fill(recordInfo.getRecordComponents(), null);
      return obj;
    }
    T obj = newBean();
    refResolver.reference(obj);
    return readAndSetFields(buffer, obj);
  }

  public Object[] readFields(MemoryBuffer buffer) {
    Fory fory = this.fory;
    if (fory.checkClassVersion()) {
      int hash = buffer.readInt32();
      checkClassVersion(type, hash, classVersionHash);
    }
    Object[] fieldValues =
        new Object[buildInFields.length + otherFields.length + containerFields.length];
    int counter = 0;
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      fieldValues[counter++] =
          readBuildInFieldValue(fory, typeResolver, refResolver, fieldInfo, buffer);
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      Object fieldValue =
          readContainerFieldValue(fory, typeResolver, refResolver, generics, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      Object fieldValue = readField(fory, typeResolver, refResolver, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    return fieldValues;
  }

  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    Fory fory = this.fory;
    if (fory.checkClassVersion()) {
      int hash = buffer.readInt32();
      checkClassVersion(type, hash, classVersionHash);
    }
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      // a numeric type can have only three kinds: primitive, not_null_boxed, nullable_boxed
      readBuildInFieldValue(fory, typeResolver, refResolver, fieldInfo, buffer, obj);
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      Object fieldValue =
          readContainerFieldValue(fory, typeResolver, refResolver, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      if (Utils.DEBUG_OUTPUT_VERBOSE) {
        printReadFieldDebugInfo(fieldInfo, buffer);
      }
      Object fieldValue = readField(fory, typeResolver, refResolver, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    return obj;
  }

  public static int computeStructHash(Fory fory, DescriptorGrouper grouper) {
    List<Descriptor> sorted = grouper.getSortedDescriptors();
    String fingerprint = Fingerprint.computeStructFingerprint(fory, sorted);
    byte[] bytes = fingerprint.getBytes(StandardCharsets.UTF_8);
    long hashLong = MurmurHash3.murmurhash3_x64_128(bytes, 0, bytes.length, 47)[0];
    return (int) (hashLong & 0xffffffffL);
  }

  public static void checkClassVersion(Class<?> cls, int readHash, int classVersionHash) {
    if (readHash != classVersionHash) {
      throw new ForyException(
          String.format(
              "Read class %s version %s is not consistent with %s",
              cls, readHash, classVersionHash));
    }
  }
}
