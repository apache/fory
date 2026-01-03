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
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.struct.Fingerprint;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.util.MurmurHash3;
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
  private final SerializationBinding binding;
  private final TypeResolver typeResolver;

  public ObjectSerializer(Fory fory, Class<T> cls) {
    this(fory, cls, true);
  }

  public ObjectSerializer(Fory fory, Class<T> cls, boolean resolveParent) {
    super(fory, cls);
    binding = SerializationBinding.createBinding(fory);
    // avoid recursive building serializers.
    // Use `setSerializerIfAbsent` to avoid overwriting existing serializer for class when used
    // as data serializer.
    if (resolveParent) {
      classResolver.setSerializerIfAbsent(cls, this);
    }
    typeResolver = fory.isCrossLanguage() ? fory.getXtypeResolver() : classResolver;
    Collection<Descriptor> descriptors;
    boolean shareMeta = fory.getConfig().isMetaShareEnabled();
    if (shareMeta) {
      ClassDef classDef = typeResolver.getTypeDef(cls, resolveParent);
      if (Utils.debugOutputEnabled()) {
        LOG.info("========== ObjectSerializer ClassDef for {} ==========", cls.getName());
        LOG.info("ClassDef fieldsInfo count: {}", classDef.getFieldsInfo().size());
        for (int i = 0; i < classDef.getFieldsInfo().size(); i++) {
          LOG.info("  [{}] {}", i, classDef.getFieldsInfo().get(i));
        }
      }
      descriptors = classDef.getDescriptors(typeResolver, cls);
    } else {
      descriptors = typeResolver.getFieldDescriptors(cls, resolveParent);
    }
    DescriptorGrouper grouper = typeResolver.createDescriptorGrouper(descriptors, false);
    descriptors = grouper.getSortedDescriptors();
    if (Utils.debugOutputEnabled()) {
      LOG.info("========== ObjectSerializer sorted descriptors for {} ==========", cls.getName());
      for (Descriptor d : descriptors) {
        LOG.info(
            "  {} -> {}, ref {}, nullable {}",
            d.getName(),
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
      if (fory.getConfig().isForyDebugOutputEnabled()) {
        LOG.info(
            "[Java][fory-debug] Writing struct hash for {} at position {}: hash={}",
            type.getSimpleName(),
            buffer.writerIndex(),
            classVersionHash);
      }
      buffer.writeInt32(classVersionHash);
    }
    // write order: primitive,boxed,final,other,collection,map
    writeBuildInFields(buffer, value, fory, refResolver, typeResolver);
    writeContainerFields(buffer, value, fory, refResolver, typeResolver);
    writeOtherFields(buffer, value);
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (SerializationFieldInfo fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeOtherFieldValue(binding, buffer, fieldInfo, fieldValue);
    }
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
  }

  private void writeBuildInFields(
      MemoryBuffer buffer, T value, Fory fory, RefResolver refResolver, TypeResolver typeResolver) {
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      short classId = fieldInfo.classId;
      if (writePrimitiveFieldValue(fory, buffer, value, fieldAccessor, classId)) {
        Object fieldValue = fieldAccessor.getObject(value);
        boolean needWrite =
            nullable
                ? writeBasicNullableObjectFieldValue(fory, buffer, fieldValue, classId)
                : writeBasicObjectFieldValue(fory, buffer, fieldValue, classId);
        if (needWrite) {
          Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
          if (!metaShareEnabled || fieldInfo.useDeclaredTypeInfo) {
            switch (fieldInfo.refMode) {
              case NONE:
                binding.write(buffer, serializer, fieldValue);
                break;
              case NULL_ONLY:
                binding.writeNullable(buffer, fieldValue, serializer);
                break;
              case TRACKING:
                // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
                // consistent with jit serializer.
                binding.writeRef(buffer, fieldValue, serializer);
                break;
              default:
                throw new IllegalStateException("Unexpected refMode: " + fieldInfo.refMode);
            }
          } else {
            switch (fieldInfo.refMode) {
              case NONE:
                typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
                binding.write(buffer, serializer, fieldValue);
                break;
              case NULL_ONLY:
                if (fieldValue == null) {
                  buffer.writeByte(Fory.NULL_FLAG);
                } else {
                  buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
                  typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
                  binding.write(buffer, serializer, fieldValue);
                }
                break;
              case TRACKING:
                if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
                  typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
                  // No generics for field, no need to update `depth`.
                  binding.write(buffer, serializer, fieldValue);
                }
                break;
              default:
                throw new IllegalStateException("Unexpected refMode: " + fieldInfo.refMode);
            }
          }
        }
      }
    }
  }

  private void writeContainerFields(
      MemoryBuffer buffer, T value, Fory fory, RefResolver refResolver, TypeResolver typeResolver) {
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeContainerFieldValue(
          binding, refResolver, typeResolver, generics, fieldInfo, buffer, fieldValue);
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

  @Override
  public T xread(MemoryBuffer buffer) {
    return read(buffer);
  }

  public Object[] readFields(MemoryBuffer buffer) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    TypeResolver typeResolver = this.typeResolver;
    if (fory.checkClassVersion()) {
      int hash = buffer.readInt32();
      checkClassVersion(type, hash, classVersionHash);
    }
    Object[] fieldValues =
        new Object[buildInFields.length + otherFields.length + containerFields.length];
    int counter = 0;
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      short classId = fieldInfo.classId;
      if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
          && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
        fieldValues[counter++] = Serializers.readPrimitiveValue(fory, buffer, classId);
      } else {
        Object fieldValue =
            readFinalObjectFieldValue(binding, refResolver, typeResolver, fieldInfo, buffer);
        fieldValues[counter++] = fieldValue;
      }
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(binding, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    return fieldValues;
  }

  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    TypeResolver typeResolver = this.typeResolver;
    if (fory.checkClassVersion()) {
      int hash = buffer.readInt32();
      checkClassVersion(type, hash, classVersionHash);
    }
    // read order: primitive,boxed,final,other,collection,map
    for (SerializationFieldInfo fieldInfo : this.buildInFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      short classId = fieldInfo.classId;
      if (readPrimitiveFieldValue(fory, buffer, obj, fieldAccessor, classId)
          && (nullable
              ? readBasicNullableObjectFieldValue(fory, buffer, obj, fieldAccessor, classId)
              : readBasicObjectFieldValue(fory, buffer, obj, fieldAccessor, classId))) {
        Object fieldValue =
            readFinalObjectFieldValue(binding, refResolver, typeResolver, fieldInfo, buffer);
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    Generics generics = fory.getGenerics();
    for (SerializationFieldInfo fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    for (SerializationFieldInfo fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(binding, fieldInfo, buffer);
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
    int hash = (int) (hashLong & 0xffffffffL);
    if (fory.getConfig().isForyDebugOutputEnabled()) {
      String className =
          sorted.isEmpty() ? "<unknown>" : String.valueOf(sorted.get(0).getDeclaringClass());
      LOG.info(
          "[Java][fory-debug] struct "
              + className
              + " version fingerprint=\""
              + fingerprint
              + "\" version hash="
              + hash);
    }
    return hash;
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
