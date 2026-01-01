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

import static org.apache.fory.meta.ClassDefEncoder.buildFields;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.builder.MetaSharedCodecBuilder;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.MetaSharedSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.StringUtils;

/**
 * Serializable class definition to be sent to other process. So if sender peer and receiver peer
 * has different class definition for same class, such as add/remove fields, we can use this
 * definition to create different serializer to support back/forward compatibility.
 *
 * <p>Note that:
 * <li>If a class is already registered, this definition will contain class id only.
 * <li>Sending class definition is not cheap, should be sent with some kind of meta share mechanism.
 * <li>{@link ObjectStreamClass} doesn't contain any non-primitive field type info, which is not
 *     enough to create serializer in receiver.
 *
 * @see MetaSharedCodecBuilder
 * @see CompatibleMode#COMPATIBLE
 * @see MetaSharedSerializer
 * @see ForyBuilder#withMetaShare
 * @see ReflectionUtils#getFieldOffset
 */
public class ClassDef implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(ClassDef.class);

  static final int COMPRESS_META_FLAG = 0b1 << 13;
  static final int HAS_FIELDS_META_FLAG = 0b1 << 12;
  // low 12 bits
  static final int META_SIZE_MASKS = 0xfff;
  static final int NUM_HASH_BITS = 50;

  // TODO use field offset to sort field, which will hit l1-cache more. Since
  // `objectFieldOffset` is not part of jvm-specification, it may change between different jdk
  // vendor. But the deserialization peer use the class definition to create deserializer, it's OK
  // even field offset or fields order change between jvm process.
  public static final Comparator<Field> FIELD_COMPARATOR =
      (f1, f2) -> {
        long offset1 = Platform.objectFieldOffset(f1);
        long offset2 = Platform.objectFieldOffset(f2);
        long diff = offset1 - offset2;
        if (diff != 0) {
          return (int) diff;
        } else {
          if (!f1.equals(f2)) {
            LOG.warn(
                "Field {} has same offset with {}, please an issue with jdk info to fory", f1, f2);
          }
          int compare = f1.getDeclaringClass().getName().compareTo(f2.getName());
          if (compare != 0) {
            return compare;
          }
          return f1.getName().compareTo(f2.getName());
        }
      };

  private final ClassSpec classSpec;
  private final List<FieldInfo> fieldsInfo;
  private final boolean hasFieldsMeta;
  // Unique id for class def. If class def are same between processes, then the id will
  // be same too.
  private final long id;
  private final byte[] encoded;
  private transient List<Descriptor> descriptors;

  ClassDef(
      ClassSpec classSpec,
      List<FieldInfo> fieldsInfo,
      boolean hasFieldsMeta,
      long id,
      byte[] encoded) {
    this.classSpec = classSpec;
    this.fieldsInfo = fieldsInfo;
    this.hasFieldsMeta = hasFieldsMeta;
    this.id = id;
    this.encoded = encoded;
  }

  public static void skipClassDef(MemoryBuffer buffer, long id) {
    int size = (int) (id & META_SIZE_MASKS);
    if (size == META_SIZE_MASKS) {
      size += buffer.readVarUint32Small14();
    }
    buffer.increaseReaderIndex(size);
  }

  /**
   * Returns class name.
   *
   * @see Class#getName()
   */
  public String getClassName() {
    return classSpec.entireClassName;
  }

  public ClassSpec getClassSpec() {
    return classSpec;
  }

  /** Contain all fields info including all parent classes. */
  public List<FieldInfo> getFieldsInfo() {
    return fieldsInfo;
  }

  /** Returns ext meta for the class. */
  public boolean hasFieldsMeta() {
    return hasFieldsMeta;
  }

  /**
   * Returns an unique id for class def. If class def are same between processes, then the id will
   * be same too.
   */
  public long getId() {
    return id;
  }

  public byte[] getEncoded() {
    return encoded;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ClassDef classDef = (ClassDef) o;
    return hasFieldsMeta == classDef.hasFieldsMeta
        && id == classDef.id
        && Objects.equals(classSpec, classDef.classSpec)
        && Objects.equals(fieldsInfo, classDef.fieldsInfo);
  }

  @Override
  public int hashCode() {
    return Objects.hash(classSpec.entireClassName, fieldsInfo, id);
  }

  @Override
  public String toString() {
    return "ClassDef{"
        + "className='"
        + classSpec.entireClassName
        + '\''
        + ", fieldsInfo="
        + fieldsInfo
        + ", hasFieldsMeta="
        + hasFieldsMeta
        + ", id="
        + id
        + '}';
  }

  /**
   * Compute diff between this (decoded/remote) ClassDef and a local ClassDef. Returns a string
   * describing the differences, or null if they are identical.
   */
  public String computeDiff(ClassDef localDef) {
    if (localDef == null) {
      return "Local TypeDef is null (type not registered locally)";
    }
    StringBuilder diff = new StringBuilder();

    // Compare class names
    if (!Objects.equals(this.classSpec.entireClassName, localDef.classSpec.entireClassName)) {
      diff.append("  className: remote=")
          .append(this.classSpec.entireClassName)
          .append(", local=")
          .append(localDef.classSpec.entireClassName)
          .append("\n");
    }

    // Build field maps for comparison
    Map<String, FieldInfo> remoteFields = new HashMap<>();
    for (FieldInfo fi : this.fieldsInfo) {
      remoteFields.put(fi.getFieldName(), fi);
    }
    Map<String, FieldInfo> localFields = new HashMap<>();
    for (FieldInfo fi : localDef.fieldsInfo) {
      localFields.put(fi.getFieldName(), fi);
    }

    // Find fields only in remote
    for (String fieldName : remoteFields.keySet()) {
      if (!localFields.containsKey(fieldName)) {
        diff.append("  field '")
            .append(fieldName)
            .append("': only in remote, type=")
            .append(remoteFields.get(fieldName).getFieldType())
            .append("\n");
      }
    }

    // Find fields only in local
    for (String fieldName : localFields.keySet()) {
      if (!remoteFields.containsKey(fieldName)) {
        diff.append("  field '")
            .append(fieldName)
            .append("': only in local, type=")
            .append(localFields.get(fieldName).getFieldType())
            .append("\n");
      }
    }

    // Compare common fields
    for (String fieldName : remoteFields.keySet()) {
      if (localFields.containsKey(fieldName)) {
        FieldInfo remoteField = remoteFields.get(fieldName);
        FieldInfo localField = localFields.get(fieldName);
        if (!Objects.equals(remoteField.getFieldType(), localField.getFieldType())) {
          diff.append("  field '")
              .append(fieldName)
              .append("': type mismatch, remote=")
              .append(remoteField.getFieldType())
              .append(", local=")
              .append(localField.getFieldType())
              .append("\n");
        }
      }
    }

    // Compare field order
    if (this.fieldsInfo.size() == localDef.fieldsInfo.size()) {
      boolean orderDifferent = false;
      for (int i = 0; i < this.fieldsInfo.size(); i++) {
        if (!Objects.equals(
            this.fieldsInfo.get(i).getFieldName(), localDef.fieldsInfo.get(i).getFieldName())) {
          orderDifferent = true;
          break;
        }
      }
      if (orderDifferent) {
        diff.append("  field order differs:\n");
        diff.append("    remote: [");
        for (int i = 0; i < this.fieldsInfo.size(); i++) {
          if (i > 0) {
            diff.append(", ");
          }
          diff.append(this.fieldsInfo.get(i).getFieldName());
        }
        diff.append("]\n");
        diff.append("    local:  [");
        for (int i = 0; i < localDef.fieldsInfo.size(); i++) {
          if (i > 0) {
            diff.append(", ");
          }
          diff.append(localDef.fieldsInfo.get(i).getFieldName());
        }
        diff.append("]\n");
      }
    }

    return diff.length() > 0 ? diff.toString() : null;
  }

  /** Write class definition to buffer. */
  public void writeClassDef(MemoryBuffer buffer) {
    buffer.writeBytes(encoded, 0, encoded.length);
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(Fory fory, MemoryBuffer buffer) {
    if (fory.isCrossLanguage()) {
      return TypeDefDecoder.decodeClassDef(fory.getXtypeResolver(), buffer, buffer.readInt64());
    }
    return ClassDefDecoder.decodeClassDef(fory.getClassResolver(), buffer, buffer.readInt64());
  }

  /** Read class definition from buffer. */
  public static ClassDef readClassDef(Fory fory, MemoryBuffer buffer, long header) {
    if (fory.isCrossLanguage()) {
      return TypeDefDecoder.decodeClassDef(fory.getXtypeResolver(), buffer, header);
    }
    return ClassDefDecoder.decodeClassDef(fory.getClassResolver(), buffer, header);
  }

  /**
   * Consolidate fields of <code>classDef</code> with <code>cls</code>. If some field exists in
   * <code>cls</code> but not in <code>classDef</code>, it won't be returned in final collection. If
   * some field exists in <code>classDef</code> but not in <code> cls</code>, it will be added to
   * final collection.
   *
   * @param cls class load in current process.
   */
  public List<Descriptor> getDescriptors(TypeResolver resolver, Class<?> cls) {
    if (descriptors == null) {
      // getFieldDescriptors already handles ref tracking computation and cache update
      Collection<Descriptor> fieldDescriptors = resolver.getFieldDescriptors(cls, true);
      Map<String, Descriptor> descriptorsMap = new HashMap<>();
      Map<Short, Descriptor> fieldIdToDescriptorMap = new HashMap<>();

      for (Descriptor desc : fieldDescriptors) {
        String fullName = desc.getDeclaringClass() + "." + desc.getName();
        if (descriptorsMap.put(fullName, desc) != null) {
          throw new IllegalStateException("Duplicate key");
        }
        // If the field has @ForyField annotation with field ID, index by field ID
        if (desc.getForyField() != null) {
          int fieldId = desc.getForyField().id();
          if (fieldId >= 0) {
            if (fieldIdToDescriptorMap.containsKey((short) fieldId)) {
              throw new IllegalArgumentException(
                  "Duplicate field id "
                      + fieldId
                      + " for field "
                      + desc.getName()
                      + " in class "
                      + cls.getName());
            }
            fieldIdToDescriptorMap.put((short) fieldId, desc);
          }
        }
      }
      descriptors = new ArrayList<>(fieldsInfo.size());
      boolean isXlang = resolver.getFory().isCrossLanguage();
      for (FieldInfo fieldInfo : fieldsInfo) {
        Descriptor descriptor;
        // Try to match by field ID first if the FieldInfo has an ID
        if (fieldInfo.hasFieldId()) {
          descriptor = fieldIdToDescriptorMap.get(fieldInfo.getFieldId());
        } else {
          String fieldName = fieldInfo.getFieldName();
          String definedClass = fieldInfo.getDefinedClass();
          // First try camelCase field name (decoded name from TypeDefDecoder)
          descriptor = descriptorsMap.get(definedClass + "." + fieldName);
          // If not found and in xlang mode, also try snake_case field name
          // This supports users who use snake_case field names in Java for xlang compatibility
          if (descriptor == null && isXlang) {
            String snakeCaseName = StringUtils.lowerCamelToLowerUnderscore(fieldName);
            descriptor = descriptorsMap.get(definedClass + "." + snakeCaseName);
          }
        }
        Descriptor newDesc = fieldInfo.toDescriptor(resolver, descriptor);
        descriptors.add(newDesc);
      }
    }
    return descriptors;
  }

  public static ClassDef buildClassDef(Fory fory, Class<?> cls) {
    return buildClassDef(fory, cls, true);
  }

  public static ClassDef buildClassDef(Fory fory, Class<?> cls, boolean resolveParent) {
    if (fory.isCrossLanguage()) {
      return TypeDefEncoder.buildTypeDef(fory, cls);
    }
    return ClassDefEncoder.buildClassDef(
        fory.getClassResolver(), cls, buildFields(fory, cls, resolveParent), true);
  }

  /** Build class definition from fields of class. */
  static ClassDef buildClassDef(ClassResolver classResolver, Class<?> type, List<Field> fields) {
    return buildClassDef(classResolver, type, fields, true);
  }

  public static ClassDef buildClassDef(
      ClassResolver classResolver, Class<?> type, List<Field> fields, boolean hasFieldsMeta) {
    return ClassDefEncoder.buildClassDef(classResolver, type, fields, hasFieldsMeta);
  }

  public ClassDef replaceRootClassTo(ClassResolver classResolver, Class<?> targetCls) {
    String name = targetCls.getName();
    List<FieldInfo> fieldInfos =
        fieldsInfo.stream()
            .map(
                fieldInfo -> {
                  if (fieldInfo.definedClass.equals(classSpec.entireClassName)) {
                    return new FieldInfo(name, fieldInfo.fieldName, fieldInfo.fieldType);
                  } else {
                    return fieldInfo;
                  }
                })
            .collect(Collectors.toList());
    return ClassDefEncoder.buildClassDefWithFieldInfos(
        classResolver, targetCls, fieldInfos, hasFieldsMeta);
  }
}
