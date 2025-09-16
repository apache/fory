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
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.collection.Tuple3;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.Generics;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
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
    DescriptorGrouper descriptorGrouper = typeResolver.createDescriptorGrouper(descriptors, false);
    descriptors = descriptorGrouper.getSortedDescriptors();
    if (isRecord) {
      List<String> fieldNames =
          descriptors.stream().map(Descriptor::getName).collect(Collectors.toList());
      recordInfo = new RecordInfo(cls, fieldNames);
    } else {
      recordInfo = null;
    }
    if (fory.checkClassVersion()) {
      classVersionHash = computeStructHash(fory, descriptors);
    } else {
      classVersionHash = 0;
    }
    Tuple3<Tuple2<FinalTypeField[], boolean[]>, GenericTypeField[], GenericTypeField[]> infos =
        buildFieldInfos(fory, descriptorGrouper);
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
    writeOtherFields(buffer, value);
    writeContainerFields(buffer, value, fory, refResolver, typeResolver);
  }

  private void writeOtherFields(MemoryBuffer buffer, T value) {
    for (GenericTypeField fieldInfo : otherFields) {
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      Object fieldValue = fieldAccessor.getObject(value);
      if (fieldInfo.trackingRef) {
        binding.writeRef(buffer, fieldValue, fieldInfo.classInfoHolder);
      } else {
        binding.writeNullable(buffer, fieldValue, fieldInfo.classInfoHolder, fieldInfo.nullable);
      }
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

  @Override
  public T read(MemoryBuffer buffer) {
    if (isRecord) {
      Object[] fields = readFields(buffer);
      fields = RecordUtils.remapping(recordInfo, fields);
      try {
        T obj = (T) constructor.invokeWithArguments(fields);
        Arrays.fill(recordInfo.getRecordComponents(), null);
        return obj;
      } catch (Throwable e) {
        Platform.throwException(e);
      }
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
    for (GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(binding, fieldInfo, buffer);
      fieldValues[counter++] = fieldValue;
    }
    Generics generics = fory.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
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
    for (GenericTypeField fieldInfo : otherFields) {
      Object fieldValue = readOtherFieldValue(binding, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    Generics generics = fory.getGenerics();
    for (GenericTypeField fieldInfo : containerFields) {
      Object fieldValue = readContainerFieldValue(binding, generics, fieldInfo, buffer);
      FieldAccessor fieldAccessor = fieldInfo.fieldAccessor;
      fieldAccessor.putObject(obj, fieldValue);
    }
    return obj;
  }

  public static int computeStructHash(Fory fory, Collection<Descriptor> descriptors) {
    int hash = 17;
    for (Descriptor descriptor : descriptors) {
      hash = computeFieldHash(hash, fory, descriptor.getTypeRef());
    }
    Preconditions.checkState(hash != 0);
    return hash;
  }

  private static int computeFieldHash(int hash, Fory fory, TypeRef<?> typeRef) {
    int id = 0;
    if (typeRef.isSubtypeOf(List.class)) {
      // TODO(chaokunyang) add list element type into schema hash
      id = Types.LIST;
    } else if (typeRef.isSubtypeOf(Map.class)) {
      // TODO(chaokunyang) add map key&value type into schema hash
      id = Types.MAP;
    } else {
      try {
        TypeResolver resolver = fory._getTypeResolver();
        Class<?> cls = typeRef.getRawType();
        if (!ReflectionUtils.isAbstract(cls) && !cls.isInterface()) {
          ClassInfo classInfo = resolver.getClassInfo(cls);
          int xtypeId = id = classInfo.getXtypeId();
          if (Types.isNamedType(xtypeId & 0xff)) {
            id =
                TypeUtils.computeStringHash(
                    classInfo.decodeNamespace() + classInfo.decodeTypeName());
          }
        }
      } catch (Exception e) {
        ExceptionUtils.ignore(e);
      }
    }
    long newHash = ((long) hash) * 31 + id;
    while (newHash >= Integer.MAX_VALUE) {
      newHash /= 7;
    }
    return (int) newHash;
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
