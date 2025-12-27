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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.exception.ForyException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.MurmurHash3;
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
  private final FinalTypeField[] finalFields;

  /**
   * Whether write class def for non-inner final types.
   *
   * @see ClassResolver#isMonomorphic(Class)
   */
  private final boolean[] isFinal;

  private final GenericTypeField[] otherFields;
  private final GenericTypeField[] containerFields;
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
      descriptors = classDef.getDescriptors(typeResolver, cls);
    } else {
      descriptors = typeResolver.getFieldDescriptors(cls, resolveParent);
    }
    DescriptorGrouper grouper = typeResolver.createDescriptorGrouper(descriptors, false);
    descriptors = grouper.getSortedDescriptors();
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
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fory, grouper);
    finalFields = infos.f0.f0;
    isFinal = infos.f0.f1;
    otherFields = infos.f1;
    containerFields = infos.f2;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    Fory fory = this.fory;
    RefResolver refResolver = this.refResolver;
    if (fory.checkClassVersion()) {
      buffer.writeInt32(classVersionHash);
    }
    // write order: primitive,boxed,final,other,collection,map
    writeFinalFields(buffer, value, fory, refResolver, typeResolver);
    writeContainerFields(buffer, value, fory, refResolver, typeResolver);
    writeOtherFields(buffer, value);
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (GenericTypeField fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeOtherFieldValue(binding, typeResolver, buffer, fieldInfo, fieldValue);
    }
  }

  @Override
  public void xwrite(MemoryBuffer buffer, T value) {
    write(buffer, value);
  }

  private void writeFinalFields(
      MemoryBuffer buffer, T value, Fory fory, RefResolver refResolver, TypeResolver typeResolver) {
    FinalTypeField[] finalFields = this.finalFields;
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    for (int i = 0; i < finalFields.length; i++) {
      FinalTypeField fieldInfo = finalFields[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      short classId = fieldInfo.classId;
      if (writePrimitiveFieldValueFailed(fory, buffer, value, fieldAccessor, classId)) {
        Object fieldValue = fieldAccessor.getObject(value);
        boolean writeBasicObjectResult =
            nullable
                ? writeBasicNullableObjectFieldValueFailed(fory, buffer, fieldValue, classId)
                : writeBasicObjectFieldValueFailed(fory, buffer, fieldValue, classId);
        if (writeBasicObjectResult) {
          Serializer<Object> serializer = fieldInfo.classInfo.getSerializer();
          if (!metaShareEnabled || isFinal[i]) {
            if (!fieldInfo.trackingRef) {
              binding.writeNullable(buffer, fieldValue, serializer, nullable);
            } else {
              // whether tracking ref is recorded in `fieldInfo.serializer`, so it's still
              // consistent with jit serializer.
              binding.writeRef(buffer, fieldValue, serializer);
            }
          } else {
            if (fieldInfo.trackingRef && serializer.needToWriteRef()) {
              if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
                typeResolver.writeClassInfo(buffer, fieldInfo.classInfo);
                // No generics for field, no need to update `depth`.
                binding.write(buffer, serializer, fieldValue);
              }
            } else {
              binding.writeNullable(buffer, fieldValue, serializer, nullable);
            }
          }
        }
      }
    }
  }

  private void writeContainerFields(
      MemoryBuffer buffer, T value, Fory fory, RefResolver refResolver, TypeResolver typeResolver) {
    Generics generics = fory.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      writeContainerFieldValue(
          binding, refResolver, typeResolver, generics, fieldInfo, buffer, fieldValue);
    }
  }

  static void writeContainerFieldValue(
      SerializationBinding binding,
      RefResolver refResolver,
      TypeResolver typeResolver,
      Generics generics,
      GenericTypeField fieldInfo,
      MemoryBuffer buffer,
      Object fieldValue) {
    if (fieldInfo.trackingRef) {
      if (!refResolver.writeRefOrNull(buffer, fieldValue)) {
        ClassInfo classInfo =
            typeResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoHolder);
        generics.pushGenericType(fieldInfo.genericType);
        binding.writeContainerFieldValue(buffer, fieldValue, classInfo);
        generics.popGenericType();
      }
    } else {
      if (fieldInfo.nullable) {
        if (fieldValue == null) {
          buffer.writeByte(Fory.NULL_FLAG);
          return;
        } else {
          buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
        }
      }
      generics.pushGenericType(fieldInfo.genericType);
      binding.writeContainerFieldValue(
          buffer,
          fieldValue,
          typeResolver.getClassInfo(fieldValue.getClass(), fieldInfo.classInfoHolder));
      generics.popGenericType();
    }
  }

  static void writeOtherFieldValue(
      SerializationBinding binding,
      TypeResolver typeResolver,
      MemoryBuffer buffer,
      GenericTypeField fieldInfo,
      Object fieldValue) {
    if (fieldValue == null) {
      buffer.writeByte(Fory.NULL_FLAG);
    } else if (fieldValue.getClass().isEnum()) {
      // Only write null flag when the field is nullable (for xlang compatibility)
      if (fieldInfo.nullable) {
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      }
      fieldInfo.genericType.getSerializer(typeResolver).write(buffer, fieldValue);
    } else if (fieldInfo.trackingRef) {
      binding.writeRef(buffer, fieldValue, fieldInfo.classInfoHolder);
    } else {
      binding.writeNullable(buffer, fieldValue, fieldInfo.classInfoHolder, fieldInfo.nullable);
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fields = readFields(buffer);
      fields = RecordUtils.remapping(recordInfo, fields);
      T obj = (T) objectCreator.newInstanceWithArguments(fields);
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
      checkClassVersion(fory, hash, classVersionHash);
    }
    Object[] fieldValues =
        new Object[finalFields.length + otherFields.length + containerFields.length];
    int counter = 0;
    // read order: primitive,boxed,final,other,collection,map
    FinalTypeField[] finalFields = this.finalFields;
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    for (int i = 0; i < finalFields.length; i++) {
      FinalTypeField fieldInfo = finalFields[i];
      boolean isFinal = !metaShareEnabled || this.isFinal[i];
      short classId = fieldInfo.classId;
      if (classId >= ClassResolver.PRIMITIVE_BOOLEAN_CLASS_ID
          && classId <= ClassResolver.PRIMITIVE_DOUBLE_CLASS_ID) {
        fieldValues[counter++] = Serializers.readPrimitiveValue(fory, buffer, classId);
      } else {
        Object fieldValue =
            readFinalObjectFieldValue(
                binding, refResolver, typeResolver, fieldInfo, isFinal, buffer);
        fieldValues[counter++] = fieldValue;
      }
    }
    Generics generics = fory.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    for (GenericTypeField fieldInfo : otherFields) {
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
      checkClassVersion(fory, hash, classVersionHash);
    }
    // read order: primitive,boxed,final,other,collection,map
    FinalTypeField[] finalFields = this.finalFields;
    boolean metaShareEnabled = fory.getConfig().isMetaShareEnabled();
    for (int i = 0; i < finalFields.length; i++) {
      FinalTypeField fieldInfo = finalFields[i];
      boolean isFinal = !metaShareEnabled || this.isFinal[i];
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      boolean nullable = fieldInfo.nullable;
      short classId = fieldInfo.classId;
      if (readPrimitiveFieldValueFailed(fory, buffer, obj, fieldAccessor, classId)
          && (nullable
              ? readBasicNullableObjectFieldValueFailed(fory, buffer, obj, fieldAccessor, classId)
              : readBasicObjectFieldValueFailed(fory, buffer, obj, fieldAccessor, classId))) {
        Object fieldValue =
            readFinalObjectFieldValue(
                binding, refResolver, typeResolver, fieldInfo, isFinal, buffer);
        fieldAccessor.putObject(obj, fieldValue);
      }
    }
    Generics generics = fory.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    for (GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(binding, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    return obj;
  }

  public static int computeStructHash(Fory fory, DescriptorGrouper grouper) {
    List<Descriptor> sorted = grouper.getSortedDescriptors();
    String fingerprint = computeStructFingerprint(fory, sorted);
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

  /**
   * Computes the fingerprint string for a struct type used in schema versioning.
   *
   * <p><b>Fingerprint Format:</b>
   *
   * <p>Each field contributes: {@code <field_id_or_name>,<type_id>,<ref>,<nullable>;}
   *
   * <p>Fields are sorted by their identifier (field ID or name) lexicographically as strings.
   *
   * <p><b>Field Components:</b>
   *
   * <ul>
   *   <li><b>field_id_or_name</b>: Tag ID as string if configured via {@link ForyField#id()} (e.g.,
   *       "0", "1"), otherwise snake_case field name
   *   <li><b>type_id</b>: Fory TypeId as decimal string (e.g., "4" for INT32)
   *   <li><b>ref</b>: "1" if reference tracking enabled, "0" otherwise
   *   <li><b>nullable</b>: "1" if null flag is written, "0" otherwise
   * </ul>
   *
   * <p><b>Example fingerprints:</b>
   *
   * <ul>
   *   <li>With tag IDs: "0,4,0,0;1,4,0,1;2,9,0,1;"
   *   <li>With field names: "age,4,0,0;name,9,0,1;"
   * </ul>
   *
   * <p>The fingerprint is used to compute a hash for struct schema versioning. Different
   * nullable/ref settings will produce different fingerprints, ensuring schema compatibility is
   * properly validated.
   *
   * @param fory the Fory instance for type resolution
   * @param descriptors the sorted list of field descriptors
   * @return the fingerprint string
   */
  public static String computeStructFingerprint(Fory fory, List<Descriptor> descriptors) {
    // Build fingerprint info for each field
    List<String[]> fieldInfos = new ArrayList<>(descriptors.size());
    for (Descriptor descriptor : descriptors) {
      Class<?> rawType = descriptor.getTypeRef().getRawType();
      int typeId = getTypeId(fory, rawType);

      // Get field identifier: tag ID if configured, otherwise snake_case name
      String fieldIdentifier;
      ForyField foryField = descriptor.getForyField();
      if (foryField != null && foryField.id() >= 0) {
        fieldIdentifier = String.valueOf(foryField.id());
      } else {
        fieldIdentifier = descriptor.getSnakeCaseName();
      }

      // Get ref flag from @ForyField annotation only (compile-time info)
      // If annotation is absent or ref not explicitly set to true, ref is 0
      // This allows fingerprint to be computed at compile time for C++/Rust
      char ref = (foryField != null && foryField.ref()) ? '1' : '0';

      // Get nullable flag:
      // - Primitives are always non-nullable
      // - For xlang: default is false (except Optional types), can be overridden by @ForyField
      // - For native: use descriptor.isNullable() which defaults to true for non-primitives
      char nullable;
      if (rawType.isPrimitive()) {
        nullable = '0';
      } else if (fory.isCrossLanguage()) {
        // For xlang: nullable defaults to false, except for Optional types
        // If @ForyField annotation is present, use its nullable value
        if (foryField != null) {
          nullable = foryField.nullable() ? '1' : '0';
        } else {
          // Default: only Optional types are nullable
          nullable = isOptionalType(rawType) ? '1' : '0';
        }
      } else {
        nullable = descriptor.isNullable() ? '1' : '0';
      }

      fieldInfos.add(
          new String[] {
            fieldIdentifier, String.valueOf(typeId), String.valueOf(ref), String.valueOf(nullable)
          });
    }

    // Sort by field identifier (lexicographically as strings)
    fieldInfos.sort((a, b) -> a[0].compareTo(b[0]));

    // Build fingerprint string
    StringBuilder builder = new StringBuilder();
    for (String[] info : fieldInfos) {
      builder
          .append(info[0])
          .append(',')
          .append(info[1])
          .append(',')
          .append(info[2])
          .append(',')
          .append(info[3])
          .append(';');
    }
    return builder.toString();
  }

  private static int getTypeId(Fory fory, Class<?> cls) {
    TypeResolver resolver = fory._getTypeResolver();
    if (resolver.isSet(cls)) {
      return Types.SET;
    } else if (resolver.isCollection(cls)) {
      return Types.LIST;
    } else if (resolver.isMap(cls)) {
      return Types.MAP;
    } else {
      if (ReflectionUtils.isAbstract(cls) || cls.isInterface() || cls.isEnum()) {
        return Types.UNKNOWN;
      }
      ClassInfo classInfo = resolver.getClassInfo(cls, false);
      if (classInfo == null) {
        return Types.UNKNOWN;
      }
      int typeId;
      if (fory.isCrossLanguage()) {
        typeId = classInfo.getXtypeId();
        if (Types.isUserDefinedType((byte) typeId)) {
          return Types.UNKNOWN;
        }
      } else {
        typeId = classInfo.getClassId();
      }
      return typeId;
    }
  }

  /** Check if a type is an Optional type (Optional, OptionalInt, OptionalLong, OptionalDouble). */
  public static boolean isOptionalType(Class<?> type) {
    return type == java.util.Optional.class
        || type == java.util.OptionalInt.class
        || type == java.util.OptionalLong.class
        || type == java.util.OptionalDouble.class;
  }

  public static void checkClassVersion(Fory fory, int readHash, int classVersionHash) {
    if (readHash != classVersionHash) {
      throw new ForyException(
          String.format(
              "Read class %s version %s is not consistent with %s",
              fory.getClassResolver().getCurrentReadClass(), readHash, classVersionHash));
    }
  }
}
