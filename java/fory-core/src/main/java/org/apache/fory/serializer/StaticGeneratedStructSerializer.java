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

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.annotation.Internal;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldInfo;
import org.apache.fory.meta.FieldTypes;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.serializer.converter.FieldConverters;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.StringUtils;

/** Base class used by javac-generated {@code @ForyStruct} serializers. */
@Internal
public abstract class StaticGeneratedStructSerializer<T> extends AbstractObjectSerializer<T> {
  protected static final int UNKNOWN_FIELD = -1;

  protected final TypeDef typeDef;
  protected final List<RemoteFieldInfo> remoteFields;
  private final SerializationFieldInfo[] localFieldsById;

  protected StaticGeneratedStructSerializer() {
    super();
    this.typeDef = null;
    this.remoteFields = Collections.emptyList();
    this.localFieldsById = new SerializationFieldInfo[0];
  }

  @SuppressWarnings("unchecked")
  public StaticGeneratedStructSerializer(TypeResolver typeResolver, Class<?> type) {
    super(typeResolver, (Class<T>) type);
    setSerializerIfAbsent(typeResolver, (Class<T>) type);
    this.typeDef = null;
    this.remoteFields = Collections.emptyList();
    this.localFieldsById = new SerializationFieldInfo[0];
  }

  @SuppressWarnings("unchecked")
  public StaticGeneratedStructSerializer(
      TypeResolver typeResolver, Class<?> type, TypeDef typeDef, List<Descriptor> descriptors) {
    super(typeResolver, (Class<T>) type);
    setSerializerIfAbsent(typeResolver, (Class<T>) type);
    List<Descriptor> runtimeDescriptors = runtimeDescriptors(descriptors);
    SerializationFieldInfo[] localFields = buildLocalFieldsById(runtimeDescriptors);
    this.typeDef = typeDef;
    this.remoteFields =
        typeDef == null
            ? Collections.emptyList()
            : buildRemoteFields(typeDef, runtimeDescriptors, localFields);
    this.localFieldsById = localFields;
  }

  private void setSerializerIfAbsent(TypeResolver typeResolver, Class<T> type) {
    TypeInfo typeInfo = typeResolver.getTypeInfo(type, false);
    if (!typeResolver.isCrossLanguage() || typeInfo != null) {
      // Field-group construction resolves monomorphic field serializers. A generated serializer can
      // therefore encounter its own type before the subclass constructor has finished, just like
      // ObjectSerializer. Install this instance early so recursive fields reuse it instead of
      // constructing another serializer for the same type.
      if (typeInfo != null && typeInfo.getSerializer() instanceof DeferedLazySerializer) {
        typeResolver.setSerializer(type, this);
      } else {
        typeResolver.setSerializerIfAbsent(type, this);
      }
    }
  }

  @Override
  public abstract void write(WriteContext writeContext, T value);

  @Override
  public abstract T read(ReadContext readContext);

  @Override
  public abstract T copy(CopyContext copyContext, T value);

  /**
   * Creates an equivalent serializer for another local/remote TypeDef view of the same generated
   * struct.
   *
   * <p>Named Java/Kotlin generated serializers are rediscovered through their generated class.
   * Macro-generated serializers may instead override this method so compatible xlang reads can
   * reuse the same serializer-owned construction logic without a separate factory object.
   */
  @Internal
  @SuppressWarnings("unchecked")
  public StaticGeneratedStructSerializer<T> copySerializer(
      TypeResolver typeResolver, Class<?> type, TypeDef typeDef) {
    try {
      // The generated serializer class may be in a named application module that is not open to
      // Fory. Reuse the trusted constructor owner instead of reflective setAccessible.
      MethodHandle constructor =
          ReflectionUtils.getCtrHandle(
              getClass().asSubclass(StaticGeneratedStructSerializer.class),
              TypeResolver.class,
              Class.class,
              TypeDef.class);
      return (StaticGeneratedStructSerializer<T>) constructor.invoke(typeResolver, type, typeDef);
    } catch (Throwable e) {
      throw ExceptionUtils.throwException(e);
    }
  }

  public abstract List<Descriptor> getGeneratedDescriptors();

  public final List<Descriptor> getDescriptors() {
    return getGeneratedDescriptors();
  }

  public final List<RemoteFieldInfo> getRemoteFields() {
    return remoteFields;
  }

  public abstract T readCompatible(ReadContext readContext);

  public final FieldGroups buildFieldGroups(List<Descriptor> descriptors) {
    descriptors = runtimeDescriptors(descriptors);
    DescriptorGrouper grouper =
        FieldGroups.buildDescriptorGrouper(
            typeResolver, descriptors, false, descriptor -> descriptor);
    return FieldGroups.buildFieldInfos(typeResolver, grouper);
  }

  public final FieldGroups buildLocalFieldGroups(List<Descriptor> descriptors) {
    if (!typeResolver.isShareMeta() || hasSourceOnlyMetadata(descriptors)) {
      return buildFieldGroups(descriptors);
    }
    // In meta-share mode, Java static-generated writers must use the same local TypeDef-reified
    // descriptor view as ObjectSerializer; otherwise readers can disagree on built-in and
    // collection wire shapes during compatible skips. Scala macro descriptors are the exception:
    // Option wrappers are source-only metadata used for generated accessor adaptation and are not
    // represented in TypeDef, so those descriptors must stay on the generated path.
    DescriptorGrouper grouper =
        typeResolver.createDescriptorGrouper(typeResolver.getTypeDef(type, true), type);
    return FieldGroups.buildFieldInfos(typeResolver, grouper);
  }

  protected final List<Descriptor> runtimeDescriptors(List<Descriptor> descriptors) {
    return typeResolver.normalizeFieldDescriptors(type, true, descriptors);
  }

  private static boolean hasSourceOnlyMetadata(List<Descriptor> descriptors) {
    Set<TypeRef<?>> visitedTypes = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Descriptor descriptor : descriptors) {
      if (hasSourceOnlyMetadata(descriptor.getTypeRef(), visitedTypes)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSourceOnlyMetadata(TypeRef<?> typeRef, Set<TypeRef<?>> visitedTypes) {
    if (!visitedTypes.add(typeRef)) {
      return false;
    }
    TypeExtMeta meta = typeRef.getTypeExtMeta();
    if (meta != null && meta.nullableWrapper()) {
      return true;
    }
    for (TypeRef<?> argument : typeRef.getTypeArguments()) {
      if (hasSourceOnlyMetadata(argument, visitedTypes)) {
        return true;
      }
    }
    // TypeRef.getComponentType() is only meaningful for arrays here; walking it for arbitrary
    // TypeRefs can loop through self-like component views while checking a cold descriptor path.
    if (!typeRef.isArray()) {
      return false;
    }
    TypeRef<?> componentType = typeRef.getComponentType();
    return componentType != null && hasSourceOnlyMetadata(componentType, visitedTypes);
  }

  public final int[] localFieldIds(
      SerializationFieldInfo[] fieldInfos, List<Descriptor> descriptors) {
    Map<String, Integer> localIds = new HashMap<>();
    for (int i = 0; i < descriptors.size(); i++) {
      Descriptor descriptor = descriptors.get(i);
      localIds.put(fieldKey(descriptor), i);
    }
    int[] ids = new int[fieldInfos.length];
    for (int i = 0; i < fieldInfos.length; i++) {
      Integer id = localIds.get(fieldKey(fieldInfos[i].descriptor));
      if (id == null) {
        throw new IllegalStateException(
            "Generated descriptor is not part of local descriptor list: "
                + fieldInfos[i].descriptor);
      }
      ids[i] = id;
    }
    return ids;
  }

  protected final long[] buildConstructorFieldBits(int size, int[] indexes) {
    if (indexes == null) {
      return null;
    }
    long[] bits = newFieldBits(size);
    for (int index : indexes) {
      if (index >= 0) {
        markField(bits, index);
      }
    }
    return bits;
  }

  protected static long[] newFieldBits(int size) {
    return new long[(size + Long.SIZE - 1) / Long.SIZE];
  }

  protected static boolean hasField(long[] bits, int fieldId) {
    return (bits[fieldId / Long.SIZE] & (1L << (fieldId % Long.SIZE))) != 0;
  }

  protected static void markField(long[] bits, int fieldId) {
    bits[fieldId / Long.SIZE] |= 1L << (fieldId % Long.SIZE);
  }

  protected static int countConstructorFields(long[] constructorFieldBits) {
    int count = 0;
    for (long constructorFields : constructorFieldBits) {
      count += Long.bitCount(constructorFields);
    }
    return count;
  }

  protected final void setGeneratedFieldValue(
      Object targetObject, SerializationFieldInfo fieldInfo, Object fieldValue) {
    if (fieldInfo.fieldAccessor != null) {
      fieldInfo.fieldAccessor.putObject(targetObject, fieldValue);
      return;
    }
    if (fieldInfo.fieldConverter != null) {
      fieldInfo.fieldConverter.set(targetObject, fieldValue);
      return;
    }
    throw new ForyException("Generated field " + fieldInfo.getName() + " is not writable");
  }

  protected final void writeBuildInFieldValue(
      WriteContext writeContext, SerializationFieldInfo fieldInfo, Object fieldValue) {
    // Some schema-built-in fields still use container-shaped Java accessors, such as
    // @ArrayType List<T>. The override owns the accessor-to-payload conversion.
    if (fieldInfo.containerSerializerOverride != null) {
      writeContainerFieldValue(writeContext, fieldInfo, fieldValue);
      return;
    }
    AbstractObjectSerializer.writeBuildInFieldValue(
        writeContext,
        typeResolver,
        writeContext.getRefWriter(),
        fieldInfo,
        writeContext.getBuffer(),
        fieldValue);
  }

  protected final void writeContainerFieldValue(
      WriteContext writeContext, SerializationFieldInfo fieldInfo, Object fieldValue) {
    AbstractObjectSerializer.writeContainerFieldValue(
        writeContext,
        typeResolver,
        writeContext.getRefWriter(),
        writeContext.getGenerics(),
        fieldInfo,
        writeContext.getBuffer(),
        fieldValue);
  }

  private static void writeContainerFieldValue(
      TypeResolver typeResolver,
      WriteContext writeContext,
      SerializationFieldInfo fieldInfo,
      Object fieldValue) {
    AbstractObjectSerializer.writeContainerFieldValue(
        writeContext,
        typeResolver,
        writeContext.getRefWriter(),
        writeContext.getGenerics(),
        fieldInfo,
        writeContext.getBuffer(),
        fieldValue);
  }

  protected final void writeOtherFieldValue(
      WriteContext writeContext, SerializationFieldInfo fieldInfo, Object fieldValue) {
    AbstractObjectSerializer.writeField(
        writeContext,
        typeResolver,
        writeContext.getRefWriter(),
        fieldInfo,
        writeContext.getBuffer(),
        fieldValue);
  }

  public final void writeFieldValue(
      WriteContext writeContext, SerializationFieldInfo fieldInfo, Object fieldValue) {
    writeFieldValue(typeResolver, writeContext, fieldInfo, fieldValue);
  }

  public static void writeFieldValue(
      TypeResolver typeResolver,
      WriteContext writeContext,
      SerializationFieldInfo fieldInfo,
      Object fieldValue) {
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        // Some schema-built-in fields still use container-shaped Java accessors, such as
        // @ArrayType List<T>. The override owns the accessor-to-payload conversion.
        if (fieldInfo.containerSerializerOverride != null) {
          writeContainerFieldValue(typeResolver, writeContext, fieldInfo, fieldValue);
          return;
        }
        AbstractObjectSerializer.writeBuildInFieldValue(
            writeContext,
            typeResolver,
            writeContext.getRefWriter(),
            fieldInfo,
            writeContext.getBuffer(),
            fieldValue);
        return;
      case CONTAINER:
        writeContainerFieldValue(typeResolver, writeContext, fieldInfo, fieldValue);
        return;
      case OTHER:
        AbstractObjectSerializer.writeField(
            writeContext,
            typeResolver,
            writeContext.getRefWriter(),
            fieldInfo,
            writeContext.getBuffer(),
            fieldValue);
        return;
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  protected final Object readBuildInFieldValue(
      ReadContext readContext, SerializationFieldInfo fieldInfo) {
    // See writeBuildInFieldValue: built-in schema groups can still need container conversion.
    if (fieldInfo.containerSerializerOverride != null) {
      return readContainerFieldValue(readContext, fieldInfo);
    }
    return AbstractObjectSerializer.readBuildInFieldValue(
        readContext, typeResolver, readContext.getRefReader(), fieldInfo, readContext.getBuffer());
  }

  protected final Object readContainerFieldValue(
      ReadContext readContext, SerializationFieldInfo fieldInfo) {
    return AbstractObjectSerializer.readContainerFieldValue(
        readContext,
        typeResolver,
        readContext.getRefReader(),
        readContext.getGenerics(),
        fieldInfo,
        readContext.getBuffer());
  }

  private static Object readContainerFieldValue(
      TypeResolver typeResolver, ReadContext readContext, SerializationFieldInfo fieldInfo) {
    return AbstractObjectSerializer.readContainerFieldValue(
        readContext,
        typeResolver,
        readContext.getRefReader(),
        readContext.getGenerics(),
        fieldInfo,
        readContext.getBuffer());
  }

  protected final Object readOtherFieldValue(
      ReadContext readContext, SerializationFieldInfo fieldInfo) {
    return AbstractObjectSerializer.readField(
        readContext, typeResolver, readContext.getRefReader(), fieldInfo, readContext.getBuffer());
  }

  protected final Object readFieldValue(ReadContext readContext, SerializationFieldInfo fieldInfo) {
    return readFieldValue(typeResolver, readContext, fieldInfo);
  }

  public static Object readFieldValue(
      TypeResolver typeResolver, ReadContext readContext, SerializationFieldInfo fieldInfo) {
    switch (fieldInfo.codecCategory) {
      case BUILD_IN:
        // See writeFieldValue: built-in schema groups can still need container conversion.
        if (fieldInfo.containerSerializerOverride != null) {
          return readContainerFieldValue(typeResolver, readContext, fieldInfo);
        }
        return AbstractObjectSerializer.readBuildInFieldValue(
            readContext,
            typeResolver,
            readContext.getRefReader(),
            fieldInfo,
            readContext.getBuffer());
      case CONTAINER:
        return readContainerFieldValue(typeResolver, readContext, fieldInfo);
      case OTHER:
        return AbstractObjectSerializer.readField(
            readContext,
            typeResolver,
            readContext.getRefReader(),
            fieldInfo,
            readContext.getBuffer());
      default:
        throw new IllegalStateException("Unknown field codec category " + fieldInfo.codecCategory);
    }
  }

  protected final Object readRemoteField(ReadContext readContext, RemoteFieldInfo remoteField) {
    if (remoteField.compatibleCollectionArrayReadAction != null) {
      return CompatibleCollectionArrayReader.read(
          readContext,
          remoteField.serializationFieldInfo.refMode,
          remoteField.compatibleCollectionArrayReadAction);
    }
    return readField(readContext, remoteField.serializationFieldInfo);
  }

  protected final Object readFieldConverterSource(
      ReadContext readContext, RemoteFieldInfo remoteField) {
    return FieldConverters.readSourceScalar(
        readContext,
        remoteField.serializationFieldInfo,
        remoteField.serializationFieldInfo.fieldConverter);
  }

  public final void skipField(ReadContext readContext, RemoteFieldInfo remoteField) {
    try {
      FieldSkipper.skipField(
          readContext,
          typeResolver,
          readContext.getRefReader(),
          remoteField.serializationFieldInfo,
          readContext.getBuffer());
    } catch (RuntimeException e) {
      throw new DeserializationException(
          "Failed to skip remote field " + remoteField.serializationFieldInfo.descriptor.getName(),
          e);
    }
  }

  protected final SerializationFieldInfo localFieldInfo(int localFieldId) {
    return localFieldsById[localFieldId];
  }

  public final Object readCompatibleFieldValue(
      ReadContext readContext, RemoteFieldInfo remoteField, SerializationFieldInfo localFieldInfo) {
    if (remoteField.compatibleScalarRead) {
      Object fieldValue =
          FieldConverters.readSourceScalar(
              readContext, remoteField.serializationFieldInfo, localFieldInfo);
      return FieldConverters.convertValue(
          remoteField.serializationFieldInfo, localFieldInfo, fieldValue);
    }
    Object fieldValue = readRemoteField(readContext, remoteField);
    if (remoteField.compatibleCollectionArrayReadAction != null) {
      return fieldValue;
    }
    return fieldValue;
  }

  protected final void debugWriteField(
      String stage, SerializationFieldInfo fieldInfo, WriteContext writeContext) {
    if (!org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) {
      return;
    }
    MemoryBuffer buffer = writeContext.getBuffer();
    System.out.println(
        "[ForyStruct] "
            + stage
            + " write "
            + type.getName()
            + "."
            + fieldInfo.descriptor.getName()
            + " localId="
            + localFieldId(fieldInfo.descriptor)
            + " type="
            + fieldInfo.typeRef
            + " writerIndex="
            + buffer.writerIndex());
  }

  protected final void debugReadField(
      String stage, SerializationFieldInfo fieldInfo, ReadContext readContext) {
    if (!org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) {
      return;
    }
    MemoryBuffer buffer = readContext.getBuffer();
    System.out.println(
        "[ForyStruct] "
            + stage
            + " read "
            + type.getName()
            + "."
            + fieldInfo.descriptor.getName()
            + " localId="
            + localFieldId(fieldInfo.descriptor)
            + " type="
            + fieldInfo.typeRef
            + " readerIndex="
            + buffer.readerIndex());
  }

  protected final void debugRemoteReadField(
      String stage, RemoteFieldInfo remoteField, ReadContext readContext) {
    if (!org.apache.fory.util.Utils.DEBUG_OUTPUT_ENABLED) {
      return;
    }
    MemoryBuffer buffer = readContext.getBuffer();
    System.out.println(
        "[ForyStruct] "
            + stage
            + " compatible read "
            + type.getName()
            + " remote="
            + remoteField.fieldInfo.getDefinedClass()
            + "."
            + remoteField.fieldInfo.getFieldName()
            + " remoteFieldId="
            + remoteField.fieldInfo.getFieldId()
            + " matchedId="
            + remoteField.matchedId
            + " descriptor="
            + remoteField.descriptor.getName()
            + " type="
            + remoteField.serializationFieldInfo.typeRef
            + " readerIndex="
            + buffer.readerIndex());
  }

  public static Object copyFieldValue(
      CopyContext copyContext, Object fieldValue, SerializationFieldInfo fieldInfo) {
    if (fieldValue == null) {
      return null;
    }
    if (fieldInfo.containerSerializerOverride != null) {
      @SuppressWarnings("unchecked")
      Serializer<Object> serializer = (Serializer<Object>) fieldInfo.containerSerializerOverride;
      return copyContext.copyObject(fieldValue, serializer);
    }
    if (fieldInfo.codecCategory == FieldGroups.FieldCodecCategory.CONTAINER
        && fieldInfo.containerTypeInfo != null) {
      @SuppressWarnings("unchecked")
      Serializer<Object> serializer =
          (Serializer<Object>) fieldInfo.containerTypeInfo.getSerializer();
      return copyContext.copyObject(fieldValue, serializer);
    }
    return copyContext.copyObject(fieldValue, fieldInfo.dispatchId);
  }

  public final int computeClassVersionHash(List<Descriptor> descriptors) {
    DescriptorGrouper grouper =
        typeResolver.isShareMeta()
            ? typeResolver.createDescriptorGrouper(typeResolver.getTypeDef(type, true), type)
            : FieldGroups.buildDescriptorGrouper(
                typeResolver, runtimeDescriptors(descriptors), false, descriptor -> descriptor);
    return ObjectSerializer.computeStructHash(typeResolver, grouper);
  }

  public final void checkClassVersion(int readHash, int classVersionHash) {
    ObjectSerializer.checkClassVersion(type, readHash, classVersionHash);
  }

  private Object readField(ReadContext readContext, SerializationFieldInfo fieldInfo) {
    return readFieldValue(readContext, fieldInfo);
  }

  private List<RemoteFieldInfo> buildRemoteFields(
      TypeDef remoteTypeDef,
      List<Descriptor> localDescriptors,
      SerializationFieldInfo[] localFieldsById) {
    Class<?> remoteDescriptorClass = remoteDescriptorClass(remoteTypeDef);
    List<FieldInfo> remoteFieldInfos = remoteTypeDef.getFieldsInfo();
    List<Descriptor> remoteDescriptors =
        remoteTypeDef.getDescriptors(typeResolver, remoteDescriptorClass);
    Map<String, FieldInfo> remoteFieldInfosByKey = new HashMap<>();
    for (int i = 0; i < remoteFieldInfos.size(); i++) {
      FieldInfo fieldInfo = remoteFieldInfos.get(i);
      Descriptor descriptor = remoteDescriptors.get(i);
      putRemoteFieldInfo(remoteFieldInfosByKey, fieldInfo, descriptor);
    }
    Map<Short, Integer> fieldIds = new HashMap<>();
    Map<String, Integer> fields = new HashMap<>();
    for (int i = 0; i < localDescriptors.size(); i++) {
      Descriptor descriptor = localDescriptors.get(i);
      if (descriptor.hasForyFieldId()) {
        fieldIds.put((short) descriptor.getForyFieldId(), i);
      }
      fields.put(fieldKey(descriptor), i);
    }
    // Keep compatible-read descriptor ordering owned by TypeResolver, matching the sorted
    // DescriptorGrouper order used by ObjectCodecBuilder and CompatibleCodecBuilder.
    DescriptorGrouper remoteGrouper =
        typeResolver.createDescriptorGrouper(remoteTypeDef, remoteDescriptorClass);
    // Remote compatible reads must dispatch through the remote field's codec category. FieldGroups
    // uses the grouper's sorted allFields order while retaining specialized
    // build-in/container/other read paths.
    SerializationFieldInfo[] remoteFieldInfosInWireOrder =
        FieldGroups.buildFieldInfos(typeResolver, remoteGrouper).allFields;
    List<RemoteFieldInfo> remoteFields = new ArrayList<>(remoteFieldInfos.size());
    appendRemoteFields(
        remoteFields,
        remoteFieldInfosInWireOrder,
        remoteFieldInfosByKey,
        fieldIds,
        fields,
        localDescriptors,
        localFieldsById);
    return Collections.unmodifiableList(remoteFields);
  }

  private Class<?> remoteDescriptorClass(TypeDef remoteTypeDef) {
    String className = remoteTypeDef.getClassName();
    if (className.equals(type.getName())) {
      return type;
    }
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      return Class.forName(className, false, contextClassLoader);
    } catch (ClassNotFoundException | LinkageError e) {
      try {
        return Class.forName(className, false, type.getClassLoader());
      } catch (ClassNotFoundException | LinkageError ignored) {
        return type;
      }
    }
  }

  private void appendRemoteFields(
      List<RemoteFieldInfo> remoteFields,
      SerializationFieldInfo[] remoteFieldInfosInWireOrder,
      Map<String, FieldInfo> remoteFieldInfosByKey,
      Map<Short, Integer> fieldIds,
      Map<String, Integer> fields,
      List<Descriptor> localDescriptors,
      SerializationFieldInfo[] localFieldsById) {
    for (SerializationFieldInfo serializationFieldInfo : remoteFieldInfosInWireOrder) {
      Descriptor descriptor = serializationFieldInfo.descriptor;
      FieldInfo fieldInfo = remoteFieldInfosByKey.get(remoteFieldKey(descriptor));
      if (fieldInfo == null) {
        throw new IllegalStateException("Missing remote field metadata for " + descriptor);
      }
      int matchedId = matchField(fieldInfo, fieldIds, fields);
      Descriptor localDescriptor =
          matchedId == UNKNOWN_FIELD ? null : localDescriptors.get(matchedId);
      SerializationFieldInfo localFieldInfo =
          matchedId == UNKNOWN_FIELD ? null : localFieldsById[matchedId];
      boolean exactFieldSchema = false;
      if (localDescriptor != null) {
        Descriptor readDescriptor = fieldInfo.toDescriptor(typeResolver, localDescriptor);
        exactFieldSchema =
            readDescriptor == localDescriptor
                || registeredFieldSchemaEquals(fieldInfo, localDescriptor);
        serializationFieldInfo =
            new SerializationFieldInfo(
                typeResolver, readDescriptor, serializationFieldInfo.codecCategory);
      }
      remoteFields.add(
          new RemoteFieldInfo(
              typeResolver,
              matchedId,
              fieldInfo,
              descriptor,
              serializationFieldInfo,
              localDescriptor,
              localFieldInfo,
              exactFieldSchema));
    }
  }

  private boolean registeredFieldSchemaEquals(FieldInfo fieldInfo, Descriptor localDescriptor) {
    FieldTypes.FieldType remoteFieldType = fieldInfo.getFieldType();
    FieldTypes.FieldType localFieldType = FieldTypes.buildFieldType(typeResolver, localDescriptor);
    return remoteFieldType instanceof FieldTypes.RegisteredFieldType
        && localFieldType instanceof FieldTypes.RegisteredFieldType
        && remoteFieldType.equals(localFieldType);
  }

  private SerializationFieldInfo[] buildLocalFieldsById(List<Descriptor> descriptors) {
    FieldGroups fieldGroups = buildFieldGroups(descriptors);
    SerializationFieldInfo[] allFields = fieldGroups.allFields;
    int[] ids = localFieldIds(allFields, descriptors);
    SerializationFieldInfo[] fieldsById = new SerializationFieldInfo[descriptors.size()];
    for (int i = 0; i < allFields.length; i++) {
      fieldsById[ids[i]] = allFields[i];
    }
    return fieldsById;
  }

  private int matchField(
      FieldInfo fieldInfo, Map<Short, Integer> fieldIds, Map<String, Integer> fields) {
    Integer localId;
    if (fieldInfo.hasFieldId()) {
      localId = fieldIds.get(fieldInfo.getFieldId());
    } else {
      String key = fieldInfo.getDefinedClass() + "." + fieldInfo.getFieldName();
      localId = fields.get(key);
      if (localId == null && typeResolver.isCrossLanguage()) {
        localId =
            fields.get(
                fieldInfo.getDefinedClass()
                    + "."
                    + StringUtils.lowerCamelToLowerUnderscore(fieldInfo.getFieldName()));
      }
    }
    return localId == null ? UNKNOWN_FIELD : localId;
  }

  private static String fieldKey(Descriptor descriptor) {
    return descriptor.getDeclaringClass() + "." + descriptor.getName();
  }

  private static int localFieldId(Descriptor descriptor) {
    return descriptor.hasForyFieldId() ? descriptor.getForyFieldId() : -1;
  }

  private static void putRemoteFieldInfo(
      Map<String, FieldInfo> remoteFieldInfosByKey, FieldInfo fieldInfo, Descriptor descriptor) {
    remoteFieldInfosByKey.put(remoteFieldKey(descriptor), fieldInfo);
    if (fieldInfo.hasFieldId()) {
      remoteFieldInfosByKey.put(remoteFieldKey(fieldInfo), fieldInfo);
    }
  }

  private static String remoteFieldKey(FieldInfo fieldInfo) {
    return fieldInfo.hasFieldId()
        ? "id:" + fieldInfo.getFieldId()
        : fieldInfo.getDefinedClass() + "." + fieldInfo.getFieldName();
  }

  private static String remoteFieldKey(Descriptor descriptor) {
    return descriptor.hasForyFieldId() ? "id:" + descriptor.getForyFieldId() : fieldKey(descriptor);
  }

  /** Remote field metadata consumed by generated compatible read methods. */
  @Internal
  public static final class RemoteFieldInfo {
    /**
     * Doubled compatible-read dispatch id: local id * 2 for exact, local id * 2 + 1 for conversion.
     */
    public final int matchedId;

    public final FieldInfo fieldInfo;
    public final Descriptor descriptor;
    public final SerializationFieldInfo serializationFieldInfo;
    public final CompatibleCollectionArrayReader.ReadAction compatibleCollectionArrayReadAction;
    public final boolean incompatibleCollectionArrayMatch;
    public final boolean nestedCollectionArrayMatch;
    public final boolean canRead;
    public final boolean compatibleScalarRead;

    private RemoteFieldInfo(
        TypeResolver typeResolver,
        int matchedId,
        FieldInfo fieldInfo,
        Descriptor descriptor,
        SerializationFieldInfo serializationFieldInfo,
        Descriptor localDescriptor,
        SerializationFieldInfo localFieldInfo,
        boolean exactFieldSchema) {
      this.fieldInfo = fieldInfo;
      this.descriptor = descriptor;
      this.serializationFieldInfo = serializationFieldInfo;
      this.compatibleCollectionArrayReadAction =
          CompatibleCollectionArrayReader.readAction(typeResolver, fieldInfo, localDescriptor);
      this.incompatibleCollectionArrayMatch =
          CompatibleCollectionArrayReader.incompatibleCollectionArrayMatch(
              typeResolver, fieldInfo, localDescriptor);
      this.nestedCollectionArrayMatch =
          CompatibleCollectionArrayReader.nestedCollectionArrayMatch(
              typeResolver, fieldInfo, localDescriptor);
      if (localFieldInfo == null) {
        this.matchedId = UNKNOWN_FIELD;
        this.canRead = false;
        this.compatibleScalarRead = false;
      } else if (compatibleCollectionArrayReadAction != null) {
        this.matchedId = matchedId * 2 + 1;
        this.canRead = true;
        this.compatibleScalarRead = false;
      } else {
        boolean canGeneratedRead =
            !incompatibleCollectionArrayMatch
                && !nestedCollectionArrayMatch
                && FieldConverters.canReadCompatibleField(
                    typeResolver, serializationFieldInfo, localFieldInfo);
        if (exactFieldSchema) {
          this.matchedId = matchedId * 2;
          this.canRead = true;
          this.compatibleScalarRead = false;
        } else if (canGeneratedRead) {
          this.matchedId = matchedId * 2 + 1;
          this.canRead = true;
          this.compatibleScalarRead =
              FieldConverters.requiresSourceScalarRead(serializationFieldInfo, localFieldInfo);
        } else {
          throw incompatibleFieldError(
              fieldInfo,
              localFieldInfo,
              incompatibleCollectionArrayMatch,
              nestedCollectionArrayMatch);
        }
      }
    }

    private static DeserializationException incompatibleFieldError(
        FieldInfo fieldInfo,
        SerializationFieldInfo localFieldInfo,
        boolean incompatibleCollectionArrayMatch,
        boolean nestedCollectionArrayMatch) {
      String reason;
      if (incompatibleCollectionArrayMatch || nestedCollectionArrayMatch) {
        reason =
            "compatible list/array adaptation requires a matching non-null primitive element"
                + " schema and does not apply recursively";
      } else {
        reason = "remote and local field schemas are not compatible";
      }
      return new DeserializationException(
          "Cannot read remote field "
              + fieldInfo.getDefinedClass()
              + "."
              + fieldInfo.getFieldName()
              + " as local field "
              + localFieldInfo.descriptor.getDeclaringClass()
              + "."
              + localFieldInfo.descriptor.getName()
              + ": "
              + reason);
    }
  }
}
