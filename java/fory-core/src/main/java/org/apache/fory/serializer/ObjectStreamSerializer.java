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

import java.io.Externalizable;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.NotActiveException;
import java.io.ObjectInputStream;
import java.io.ObjectInputValidation;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.FieldInfo;
import org.apache.fory.meta.FieldTypes;
import org.apache.fory.meta.NativeTypeDefEncoder;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;

/**
 * Implement jdk custom serialization only if following conditions are met:
 *
 * <ul>
 *   <li>`writeObject/readObject` occurs only at current class in class hierarchy.
 *   <li>`writeReplace/readResolve` don't occur in class hierarchy.
 *   <li>class hierarchy doesn't have duplicated fields.
 * </ul>
 *
 * <p>Serializer selection must not change by JDK version. Types that use this serializer on JDK8
 * through JDK24 must keep this serializer on JDK25+ so existing Fory bytes remain readable.
 *
 * <p>`ObjectInputStream#setObjectInputFilter` will be ignored by this serializer.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ObjectStreamSerializer extends AbstractObjectSerializer {
  private static final Logger LOG = LoggerFactory.getLogger(ObjectStreamSerializer.class);

  private final SlotInfo[] slotsInfos;
  // Instance-level cache: TypeDef ID -> TypeInfo (shared across all slots).
  private final LongMap<TypeInfo> typeDefIdToTypeInfo = new LongMap<>(4, 0.4f);

  private static CompatibleLayerSerializerBase<?> newGeneratedSerializer(
      TypeResolver typeResolver,
      Class<?> cls,
      Class<? extends Serializer> serializerClass,
      TypeDef layerTypeDef,
      Class<?> layerMarkerClass) {
    CompatibleLayerSerializerBase<?> serializer =
        (CompatibleLayerSerializerBase<?>)
            Serializers.newSerializer(typeResolver, cls, serializerClass);
    serializer.setLayerSerializerMeta(layerTypeDef, layerMarkerClass);
    return serializer;
  }

  /**
   * Interface for slot information used in ObjectStreamSerializer. This allows both full SlotsInfo
   * and minimal MinimalSlotsInfo implementations.
   */
  private interface SlotInfo {
    Class<?> getCls();

    StreamTypeInfo getStreamTypeInfo();

    CompatibleLayerSerializerBase getSlotsSerializer();

    /**
     * Read the layer TypeDef from buffer (if meta share enabled) and return the appropriate
     * serializer. When meta share is enabled, reads the TypeDef from buffer, caches the serializer
     * by TypeDef ID, and returns it. When meta share is disabled, returns the default slots
     * serializer. Also stores the returned serializer for later retrieval via {@link
     * #getCurrentReadSerializer()}.
     *
     * @param typeResolver the type resolver
     * @param readContext the context to read TypeDef from
     * @return the serializer to use for reading
     */
    CompatibleLayerSerializerBase getReadSerializer(
        TypeResolver typeResolver, ReadContext readContext);

    /**
     * Get the current read serializer (last returned by {@link #getReadSerializer}). This is used
     * by defaultReadObject() to get the serializer without reading from buffer again.
     *
     * @return the current read serializer
     */
    CompatibleLayerSerializerBase getCurrentReadSerializer();

    ForyStructOutputStream getObjectOutputStream();

    ForyStructInputStream getObjectInputStream();

    ObjectArray getFieldPool();

    ObjectIntMap<String> getFieldIndexMap();

    int getNumPutFields();

    /** Get field types for putFields/writeFields support, in index order. */
    Class<?>[] getPutFieldTypes();
  }

  /**
   * Safe wrapper for ObjectStreamClass.lookup that handles GraalVM native image limitations. In
   * GraalVM native image, ObjectStreamClass.lookup may fail for certain classes like Throwable due
   * to missing SerializationConstructorAccessor. This method catches such errors and returns null
   * so the serializer can use its constructor-bypassing object instantiator.
   */
  private static ObjectStreamClass safeObjectStreamClassLookup(Class<?> type) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      try {
        return ObjectStreamClass.lookup(type);
      } catch (Throwable e) {
        // In GraalVM native image, ObjectStreamClass.lookup may fail for certain classes due to
        // missing SerializationConstructorAccessor. Returning null keeps stream reconstruction on
        // the serializer-owned object instantiator path.
        LOG.warn(
            "ObjectStreamClass.lookup failed for {} in GraalVM native image: {}",
            type.getName(),
            e.getMessage());
        return null;
      }
    } else {
      // In regular JVM, use normal lookup
      return ObjectStreamClass.lookup(type);
    }
  }

  public ObjectStreamSerializer(TypeResolver typeResolver, Class<?> type) {
    super(typeResolver, type, typeResolver.getSharedRegistry().getObjectStreamInstantiator(type));
    if (!Serializable.class.isAssignableFrom(type)) {
      throw new IllegalArgumentException(
          String.format("Class %s should implement %s.", type, Serializable.class));
    }
    if (!Throwable.class.isAssignableFrom(type)) {
      LOG.warn(
          "{} customized jdk serialization, which is inefficient. "
              + "Please replace it with a {} or implements {}",
          type,
          Serializer.class.getName(),
          Externalizable.class.getName());
    }
    // stream serializer may be data serializer of ReplaceResolver serializer.
    typeResolver.setSerializerIfAbsent(type, this);
    List<SlotInfo> slotsInfoList = new ArrayList<>();
    Class<?> end = type;
    // locate closest non-serializable superclass
    while (end != null && Serializable.class.isAssignableFrom(end)) {
      end = end.getSuperclass();
    }
    while (type != end) {
      slotsInfoList.add(new SlotsInfo(typeResolver, type));
      type = type.getSuperclass();
    }
    Collections.reverse(slotsInfoList);
    slotsInfos = slotsInfoList.toArray(new SlotInfo[0]);
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    buffer.writeInt16((short) slotsInfos.length);
    try {
      ClassResolver classResolver = (ClassResolver) typeResolver;
      for (SlotInfo slotsInfo : slotsInfos) {
        // create a classinfo to avoid null class bytes when class id is a
        // replacement id.
        classResolver.writeClassInternal(writeContext, slotsInfo.getCls());
        // Write layer class meta first (if meta share enabled)
        CompatibleLayerSerializerBase serializer = slotsInfo.getSlotsSerializer();
        if (config.isMetaShareEnabled()) {
          serializer.writeLayerClassMeta(writeContext);
        }
        StreamTypeInfo streamTypeInfo = slotsInfo.getStreamTypeInfo();
        Method writeObjectMethod = streamTypeInfo.writeObjectMethod;
        if (writeObjectMethod == null) {
          // No custom writeObject - write fields directly
          serializer.writeFieldsOnly(writeContext, value);
        } else {
          ForyStructOutputStream objectOutputStream = slotsInfo.getObjectOutputStream();
          Object oldObject = objectOutputStream.targetObject;
          MemoryBuffer oldBuffer = objectOutputStream.buffer;
          WriteContext oldWriteContext = objectOutputStream.writeContext;
          ForyStructOutputStream.PutFieldImpl oldPutField = objectOutputStream.curPut;
          boolean fieldsWritten = objectOutputStream.fieldsWritten;
          try {
            objectOutputStream.targetObject = value;
            objectOutputStream.buffer = buffer;
            objectOutputStream.writeContext = writeContext;
            objectOutputStream.curPut = null;
            objectOutputStream.fieldsWritten = false;
            if (streamTypeInfo.writeObjectFunc != null) {
              streamTypeInfo.writeObjectFunc.accept(value, objectOutputStream);
            } else {
              writeObjectMethod.invoke(value, objectOutputStream);
            }
          } finally {
            objectOutputStream.targetObject = oldObject;
            objectOutputStream.buffer = oldBuffer;
            objectOutputStream.writeContext = oldWriteContext;
            objectOutputStream.curPut = oldPutField;
            objectOutputStream.fieldsWritten = fieldsWritten;
          }
        }
      }
    } catch (Exception e) {
      throwSerializationException(type, e);
    }
  }

  @Override
  public Object read(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    reserveObjectGraphMemory(readContext);
    Object obj = objectInstantiator.newInstance();
    readContext.reference(obj);
    int numClasses = buffer.readInt16();
    int slotIndex = 0;

    try {
      ClassResolver classResolver = (ClassResolver) typeResolver;
      TreeMap<Integer, ObjectInputValidation> callbacks = new TreeMap<>(Collections.reverseOrder());
      for (int i = 0; i < numClasses; i++) {
        // Matching layers are accepted by the registered root object type; requiring each
        // serializable superclass to be registered would make normal ObjectStream hierarchy reads
        // unusable. Sender-only layers are checked below before their data is skipped.
        Class<?> currentClass = classResolver.readClassInternalUnchecked(readContext);

        // Find the matching local slot for sender's class
        SlotInfo matchedSlot = null;
        while (slotIndex < slotsInfos.length) {
          SlotInfo candidateSlot = slotsInfos[slotIndex];
          if (currentClass == candidateSlot.getCls()) {
            // Found matching slot
            matchedSlot = candidateSlot;
            slotIndex++;
            break;
          } else if (currentClass.isAssignableFrom(candidateSlot.getCls())) {
            // Sender's class is an ancestor of candidate's class but they don't match.
            // This means sender has a layer (currentClass) that receiver doesn't have.
            // We'll skip sender's data for this layer below.
            break;
          } else {
            // Receiver has an extra layer that sender doesn't have - call readObjectNoData
            StreamTypeInfo streamTypeInfo = candidateSlot.getStreamTypeInfo();
            Method readObjectNoData = streamTypeInfo.readObjectNoData;
            if (readObjectNoData != null) {
              if (streamTypeInfo.readObjectNoDataFunc != null) {
                streamTypeInfo.readObjectNoDataFunc.accept(obj);
              } else {
                readObjectNoData.invoke(obj);
              }
            }
            slotIndex++;
          }
        }

        if (matchedSlot == null) {
          // Sender has a layer that receiver doesn't have - read TypeDef and skip the data
          classResolver.checkClassForDeserialization(currentClass);
          skipUnknownLayerData(readContext, currentClass);
          continue;
        }

        // Read data for the matched layer - getReadSerializer reads TypeDef from buffer
        // This must be called exactly once per layer to read the TypeDef
        matchedSlot.getReadSerializer(typeResolver, readContext);

        StreamTypeInfo streamTypeInfo = matchedSlot.getStreamTypeInfo();
        Method readObjectMethod = streamTypeInfo.readObjectMethod;

        if (readObjectMethod == null) {
          if (streamTypeInfo.defaultReadObjectHandle != null) {
            readObjectStreamSlot(matchedSlot, readContext, obj, callbacks, true);
          } else {
            // For standard field serialization - use getCurrentReadSerializer()
            matchedSlot.getCurrentReadSerializer().readAndSetFields(readContext, obj);
          }
        } else {
          readObjectStreamSlot(matchedSlot, readContext, obj, callbacks, false);
        }
      }

      // Handle any remaining receiver-only layers at the end
      while (slotIndex < slotsInfos.length) {
        SlotInfo remainingSlot = slotsInfos[slotIndex++];
        StreamTypeInfo streamTypeInfo = remainingSlot.getStreamTypeInfo();
        Method readObjectNoData = streamTypeInfo.readObjectNoData;
        if (readObjectNoData != null) {
          if (streamTypeInfo.readObjectNoDataFunc != null) {
            streamTypeInfo.readObjectNoDataFunc.accept(obj);
          } else {
            readObjectNoData.invoke(obj);
          }
        }
      }

      for (ObjectInputValidation validation : callbacks.values()) {
        validation.validateObject();
      }
    } catch (InvocationTargetException
        | IllegalAccessException
        | IOException
        | ClassNotFoundException e) {
      throwSerializationException(type, e);
    }
    return obj;
  }

  private void readObjectStreamSlot(
      SlotInfo matchedSlot,
      ReadContext readContext,
      Object obj,
      TreeMap<Integer, ObjectInputValidation> callbacks,
      boolean defaultRead)
      throws IOException,
          ClassNotFoundException,
          InvocationTargetException,
          IllegalAccessException {
    StreamTypeInfo streamTypeInfo = matchedSlot.getStreamTypeInfo();
    ForyStructInputStream objectInputStream = matchedSlot.getObjectInputStream();
    MemoryBuffer oldBuffer = objectInputStream.buffer;
    Object oldObject = objectInputStream.targetObject;
    ReadContext oldReadContext = objectInputStream.readContext;
    Object[] oldFieldValuesOverride = objectInputStream.fieldValuesOverride;
    ForyStructInputStream.GetFieldImpl oldGetField = objectInputStream.getField;
    ForyStructInputStream.GetFieldImpl getField =
        (ForyStructInputStream.GetFieldImpl) matchedSlot.getFieldPool().popOrNull();
    if (getField == null) {
      getField = new ForyStructInputStream.GetFieldImpl(matchedSlot);
    }
    boolean fieldsRead = objectInputStream.fieldsRead;
    try {
      objectInputStream.fieldsRead = false;
      objectInputStream.buffer = readContext.getBuffer();
      objectInputStream.targetObject = obj;
      objectInputStream.readContext = readContext;
      objectInputStream.fieldValuesOverride = null;
      objectInputStream.getField = getField;
      objectInputStream.callbacks = callbacks;
      if (defaultRead) {
        objectInputStream.runDefaultReadObject();
      } else if (streamTypeInfo.readObjectFunc != null) {
        streamTypeInfo.readObjectFunc.accept(obj, objectInputStream);
      } else {
        streamTypeInfo.readObjectMethod.invoke(obj, objectInputStream);
      }
    } finally {
      objectInputStream.fieldsRead = fieldsRead;
      objectInputStream.buffer = oldBuffer;
      objectInputStream.targetObject = oldObject;
      objectInputStream.readContext = oldReadContext;
      objectInputStream.fieldValuesOverride = oldFieldValuesOverride;
      objectInputStream.getField = oldGetField;
      matchedSlot.getFieldPool().add(getField);
      objectInputStream.callbacks = null;
      Arrays.fill(getField.vals, ForyStructInputStream.NO_VALUE_STUB);
    }
  }

  @Override
  public Object copy(CopyContext copyContext, Object value) {
    if (!canCopyWithDefaultReadObject()) {
      return super.copy(copyContext, value);
    }
    Object copy = objectInstantiator.newInstance();
    copyContext.reference(value, copy);
    try {
      for (SlotInfo slotInfo : slotsInfos) {
        copyObjectStreamSlot(copyContext, value, copy, slotInfo);
      }
      return copy;
    } catch (IOException | ClassNotFoundException e) {
      throw new ForyException("Failed to copy Java serialization fields for " + type, e);
    }
  }

  private boolean canCopyWithDefaultReadObject() {
    for (SlotInfo slotInfo : slotsInfos) {
      if (slotInfo.getStreamTypeInfo().defaultReadObjectHandle == null) {
        return false;
      }
    }
    return true;
  }

  private void copyObjectStreamSlot(
      CopyContext copyContext, Object sourceObject, Object targetObject, SlotInfo slotInfo)
      throws IOException, ClassNotFoundException {
    ForyStructInputStream objectInputStream = slotInfo.getObjectInputStream();
    Object oldObject = objectInputStream.targetObject;
    ReadContext oldReadContext = objectInputStream.readContext;
    Object[] oldFieldValuesOverride = objectInputStream.fieldValuesOverride;
    ForyStructInputStream.GetFieldImpl oldGetField = objectInputStream.getField;
    ForyStructInputStream.GetFieldImpl getField =
        (ForyStructInputStream.GetFieldImpl) slotInfo.getFieldPool().popOrNull();
    if (getField == null) {
      getField = new ForyStructInputStream.GetFieldImpl(slotInfo);
    }
    boolean fieldsRead = objectInputStream.fieldsRead;
    try {
      objectInputStream.fieldsRead = false;
      objectInputStream.targetObject = targetObject;
      objectInputStream.readContext = null;
      objectInputStream.fieldValuesOverride =
          slotInfo
              .getSlotsSerializer()
              .copyFieldValuesForPutFields(
                  copyContext,
                  sourceObject,
                  slotInfo.getFieldIndexMap(),
                  slotInfo.getNumPutFields());
      objectInputStream.getField = getField;
      objectInputStream.runDefaultReadObject();
    } finally {
      objectInputStream.fieldsRead = fieldsRead;
      objectInputStream.targetObject = oldObject;
      objectInputStream.readContext = oldReadContext;
      objectInputStream.fieldValuesOverride = oldFieldValuesOverride;
      objectInputStream.getField = oldGetField;
      slotInfo.getFieldPool().add(getField);
      Arrays.fill(getField.vals, ForyStructInputStream.NO_VALUE_STUB);
    }
  }

  /**
   * Skip data for a layer that exists in sender but not in receiver. This is needed for schema
   * evolution when sender's class hierarchy has layers that receiver doesn't have.
   *
   * @param readContext the read context to read from
   * @param senderClass the class from sender that receiver doesn't have
   */
  private void skipUnknownLayerData(ReadContext readContext, Class<?> senderClass) {
    MemoryBuffer buffer = readContext.getBuffer();
    // For layers without custom writeObject, we can skip using a serializer created from the
    // TypeDef. Note: For layers with custom writeObject, the sender would have that class
    // locally, and we'd have a matching slot. This method is only called when sender has a
    // layer the receiver doesn't have.

    if (!typeResolver.getConfig().isMetaShareEnabled()) {
      throw new UnsupportedOperationException(
          "Cannot skip unknown layer data without meta share enabled for class: "
              + senderClass.getName()
              + ". Schema evolution with removed parent classes requires meta share.");
    }

    // Read TypeInfo from buffer (maintains index alignment with readTypeInfos)
    MetaReadContext metaReadContext = readContext.getMetaReadContext();
    if (metaReadContext == null) {
      throw new IllegalStateException("MetaReadContext is null but meta share is enabled");
    }
    int indexMarker = buffer.readVarUInt32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    TypeInfo typeInfo;
    if (isRef) {
      // Reference to previously read TypeInfo
      typeInfo = getMetaReadTypeInfo(metaReadContext, index);
    } else {
      // New TypeDef in stream, with optimized reuse by validated TypeDef header.
      long typeDefId = buffer.readInt64();
      typeInfo = readLayerTypeInfo(typeResolver, buffer, senderClass, typeDefId);
      metaReadContext.readTypeInfos.add(typeInfo);
    }

    // Get or create serializer from TypeInfo to skip the fields
    CompatibleLayerSerializerBase skipSerializer =
        (CompatibleLayerSerializerBase) typeInfo.getSerializer();
    if (skipSerializer == null) {
      Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(senderClass, 0);
      CompatibleLayerSerializer<?> newSerializer =
          new CompatibleLayerSerializer(
              typeResolver, senderClass, typeInfo.getTypeDef(), layerMarkerClass);
      typeInfo.setSerializer(newSerializer);
      skipSerializer = newSerializer;
    }
    skipSerializer.skipFields(readContext);
  }

  private static TypeInfo getMetaReadTypeInfo(MetaReadContext metaReadContext, int index) {
    if (index < 0 || index >= metaReadContext.readTypeInfos.size) {
      throw new ForyException("Invalid layer metadata reference id " + index);
    }
    TypeInfo typeInfo = metaReadContext.readTypeInfos.get(index);
    if (typeInfo == null) {
      throw new ForyException("Invalid layer metadata reference id " + index);
    }
    return typeInfo;
  }

  private TypeInfo readLayerTypeInfo(
      TypeResolver typeResolver, MemoryBuffer buffer, Class<?> cls, long typeDefId) {
    TypeInfo typeInfo = typeDefIdToTypeInfo.get(typeDefId);
    if (typeInfo != null) {
      TypeDef.skipTypeDef(buffer, typeDefId);
      return typeInfo;
    }
    return readLayerTypeDef(typeResolver, buffer, cls, typeDefId);
  }

  private TypeInfo readLayerTypeDef(
      TypeResolver typeResolver, MemoryBuffer buffer, Class<?> cls, long typeDefId) {
    byte[] encoded = TypeDef.readTypeDefBytes(typeResolver, buffer, typeDefId);
    TypeDef localTypeDef = typeResolver.getTypeDef(cls, false);
    TypeDef typeDef;
    if (Arrays.equals(encoded, localTypeDef.getEncoded())) {
      // Exact local bytes only avoid remote schema counting/parsing. They still select the
      // target class serializer, so the class must pass the active deserialization policy.
      typeResolver.checkClassForDeserialization(cls);
      typeDef = localTypeDef;
    } else {
      typeResolver.checkClassForDeserialization(cls);
      typeDef = typeResolver.cacheRemoteTypeDef(TypeDef.readTypeDef(typeResolver, encoded));
    }
    TypeInfo typeInfo = new TypeInfo(cls, typeDef);
    typeDefIdToTypeInfo.put(typeDefId, typeInfo);
    return typeInfo;
  }

  private static void throwUnsupportedEncodingException(Class<?> cls)
      throws UnsupportedEncodingException {
    throw new UnsupportedEncodingException(
        String.format(
            "Use %s instead by `fory.registerSerializer(%s, new JavaSerializer(fory.getTypeResolver(), %s))` or "
                + "implement a custom %s.",
            JavaSerializer.class, cls, cls, Serializer.class));
  }

  private static void throwSerializationException(Class<?> type, Exception e) {
    throw new RuntimeException(
        String.format(
            "Serialize object of type %s failed, "
                + "Try to use %s instead by `fory.registerSerializer(%s, new JavaSerializer(fory.getTypeResolver(), %s))` or "
                + "implement a custom %s.",
            type, JavaSerializer.class, type, type, Serializer.class),
        e);
  }

  /**
   * Ensures serializers are generated for all field types of a class during GraalVM build time.
   * This is necessary because when a new Fory instance is created at runtime in GraalVM native
   * images, it needs to reuse the serializers that were generated at build time.
   *
   * @param typeResolver the type resolver
   * @param layerTypeDef the TypeDef for this layer
   * @param type the target class type
   */
  private static void ensureFieldSerializersGenerated(
      TypeResolver typeResolver, TypeDef layerTypeDef, Class<?> type) {
    Collection<Descriptor> descriptors = layerTypeDef.getDescriptors(typeResolver, type);
    for (Descriptor descriptor : descriptors) {
      Class<?> fieldType = descriptor.getRawType();
      if (fieldType != null && !fieldType.isPrimitive()) {
        try {
          // Trigger serializer generation for this field type
          typeResolver.getSerializerClass(fieldType);
        } catch (Exception e) {
          // Ignore errors - some types may not need serializers or may be handled specially
          ExceptionUtils.ignore(e);
        }
      }
    }
  }

  /**
   * Build a list of FieldInfo from ObjectStreamClass fields (serialPersistentFields). This creates
   * FieldInfo objects that represent the serialization contract defined by the class, which may
   * differ from the actual class fields when serialPersistentFields is defined.
   *
   * @param typeResolver the type resolver
   * @param objectStreamClass the ObjectStreamClass for the type
   * @param type the class type
   * @return list of FieldInfo representing the serialization fields
   */
  private static List<FieldInfo> buildFieldInfoFromObjectStreamClass(
      TypeResolver typeResolver, ObjectStreamClass objectStreamClass, Class<?> type) {
    ObjectStreamField[] streamFields = objectStreamClass.getFields();
    List<FieldInfo> fieldInfos = new ArrayList<>(streamFields.length);
    String className = type.getName();

    for (ObjectStreamField streamField : streamFields) {
      Class<?> fieldType = streamField.getType();
      String fieldName = streamField.getName();

      // Build FieldType based on the field's declared type
      FieldTypes.FieldType fieldTypeInfo = buildFieldTypeFromClass(typeResolver, fieldType);

      fieldInfos.add(new FieldInfo(className, fieldName, fieldTypeInfo));
    }
    return fieldInfos;
  }

  /**
   * Build a FieldType from a Class. This is a simplified version of FieldTypes.buildFieldType that
   * works with Class instead of Field, used for ObjectStreamField which only provides type info.
   */
  private static FieldTypes.FieldType buildFieldTypeFromClass(
      TypeResolver typeResolver, Class<?> fieldType) {
    boolean nullable = !fieldType.isPrimitive();
    // ObjectStreamField exposes the Java serialization field contract without Fory field
    // annotations, so stream-layer metadata must use the resolver-owned native ref policy to match
    // the field serializer framing for immutable scalars such as String.
    boolean trackingRef = nullable && typeResolver.needToWriteRef(TypeRef.of(fieldType));

    // For primitives, use RegisteredFieldType
    if (fieldType.isPrimitive()) {
      int typeId = Types.getTypeId(typeResolver, fieldType);
      return new FieldTypes.RegisteredFieldType(false, false, typeId, -1);
    }

    // For boxed primitives
    Class<?> unwrapped = TypeUtils.unwrap(fieldType);
    if (unwrapped.isPrimitive()) {
      int typeId = Types.getTypeId(typeResolver, unwrapped);
      return new FieldTypes.RegisteredFieldType(nullable, trackingRef, typeId, -1);
    }

    // For registered types
    ClassResolver classResolver = (ClassResolver) typeResolver;
    if (classResolver.isRegisteredById(fieldType)) {
      int typeId = classResolver.getTypeIdForTypeDef(fieldType);
      int userTypeId = classResolver.getUserTypeIdForTypeDef(fieldType);
      return new FieldTypes.RegisteredFieldType(nullable, trackingRef, typeId, userTypeId);
    }

    // For enums
    if (fieldType.isEnum()) {
      return new FieldTypes.EnumFieldType(nullable, -1, -1);
    }

    // For arrays, collections, maps - use ObjectFieldType as a fallback
    // The actual type handling will be done during serialization
    return new FieldTypes.ObjectFieldType(-1, nullable, trackingRef);
  }

  /**
   * Information about a class's stream methods (writeObject, readObject, readObjectNoData) and
   * their optimized MethodHandle equivalents for fast invocation.
   */
  private static class StreamTypeInfo {
    private final Method writeObjectMethod;
    private final Method readObjectMethod;
    private final Method readObjectNoData;
    private final MethodHandle defaultReadObjectHandle;
    private final BiConsumer writeObjectFunc;
    private final BiConsumer readObjectFunc;
    private final Consumer readObjectNoDataFunc;

    private StreamTypeInfo(Class<?> type) {
      Method writeMethod = null;
      Method readMethod = null;
      Method noDataMethod = null;
      if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
        writeMethod = JavaSerializer.getWriteObjectMethod(type, false);
        readMethod = JavaSerializer.getReadRefMethod(type, false);
        noDataMethod = JavaSerializer.getReadRefNoData(type, false);
      } else {
        writeMethod = SerializationHookLookup.getWriteObjectMethod(type);
        readMethod = SerializationHookLookup.getReadObjectMethod(type);
        noDataMethod = SerializationHookLookup.getReadObjectNoDataMethod(type);
      }
      this.writeObjectMethod = writeMethod;
      this.readObjectMethod = readMethod;
      this.readObjectNoData = noDataMethod;
      this.defaultReadObjectHandle =
          AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE
              ? null
              : SerializationHookLookup.getDefaultReadObjectHandle(type);
      if (AndroidSupport.IS_ANDROID) {
        makeAccessible(writeObjectMethod);
        makeAccessible(readObjectMethod);
        makeAccessible(readObjectNoData);
        this.writeObjectFunc = null;
        this.readObjectFunc = null;
        this.readObjectNoDataFunc = null;
        return;
      }
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(type);
      BiConsumer writeObjectFunc = null, readObjectFunc = null;
      Consumer readObjectNoDataFunc = null;
      try {
        if (writeObjectMethod != null) {
          writeObjectFunc =
              _JDKAccess.makeJDKBiConsumer(lookup, lookup.unreflect(writeObjectMethod));
        }
        if (readObjectMethod != null) {
          readObjectFunc = _JDKAccess.makeJDKBiConsumer(lookup, lookup.unreflect(readObjectMethod));
        }
        if (readObjectNoData != null) {
          readObjectNoDataFunc =
              _JDKAccess.makeJDKConsumer(lookup, lookup.unreflect(readObjectNoData));
        }
      } catch (Exception e) {
        ExceptionUtils.ignore(e);
      }
      this.writeObjectFunc = writeObjectFunc;
      this.readObjectFunc = readObjectFunc;
      this.readObjectNoDataFunc = readObjectNoDataFunc;
    }

    private static void makeAccessible(Method method) {
      if (method == null) {
        return;
      }
      try {
        method.setAccessible(true);
      } catch (RuntimeException e) {
        throw new ForyException("Failed to make Java serialization hook accessible: " + method, e);
      }
    }
  }

  private static final ClassValueCache<StreamTypeInfo> STREAM_CLASS_INFO_CACHE =
      ClassValueCache.newClassKeyCache(32);

  /**
   * Full implementation of SlotInfo for handling object stream serialization. This class manages
   * all the details of serializing and deserializing a single class in the class hierarchy using
   * Java's ObjectInputStream/ObjectOutputStream protocol.
   */
  private class SlotsInfo implements SlotInfo {
    private final Class<?> cls;
    private final StreamTypeInfo streamTypeInfo;
    // mark non-final for async-jit to update it to jit-serializer.
    private CompatibleLayerSerializerBase slotsSerializer;
    private final ObjectIntMap<String> fieldIndexMap;
    private final int numPutFields;
    private final Class<?>[] putFieldTypes;
    private final ForyStructOutputStream objectOutputStream;
    private final ForyStructInputStream objectInputStream;
    private final ObjectArray getFieldPool;
    // Current read serializer (set by getReadSerializer, used by getCurrentReadSerializer)
    private CompatibleLayerSerializerBase currentReadSerializer;

    public SlotsInfo(TypeResolver typeResolver, Class<?> type) {
      this.cls = type;
      ObjectStreamClass objectStreamClass = safeObjectStreamClassLookup(type);
      streamTypeInfo = STREAM_CLASS_INFO_CACHE.get(type, () -> new StreamTypeInfo(type));

      // Build TypeDef from ObjectStreamClass fields (handles serialPersistentFields).
      // This ensures the serializer uses the same field layout as defined by
      // serialPersistentFields (if present) rather than actual class fields.
      TypeDef layerTypeDef;
      if (objectStreamClass != null) {
        List<FieldInfo> fieldInfos =
            buildFieldInfoFromObjectStreamClass(typeResolver, objectStreamClass, type);
        layerTypeDef =
            NativeTypeDefEncoder.buildTypeDefWithFieldInfos(
                (ClassResolver) typeResolver, type, fieldInfos);
      } else {
        // Fallback when ObjectStreamClass is not available (e.g., GraalVM native image)
        layerTypeDef = typeResolver.getTypeDef(type, false);
      }
      // Generate marker class for this layer. Use 0 as layer index since each class
      // has its own SlotsInfo, and the (class, 0) pair is unique for each class.
      Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(type, 0);

      // Create interpreter-mode serializer first
      this.slotsSerializer =
          new CompatibleLayerSerializer(typeResolver, type, layerTypeDef, layerMarkerClass);

      // Register JIT callback to replace with JIT serializer when ready
      if (config.isCodeGenEnabled()
          && !AndroidSupport.IS_ANDROID
          && !GraalvmSupport.isGraalRuntime()) {
        SlotsInfo thisInfo = this;
        typeResolver
            .getJITContext()
            .registerSerializerJITCallback(
                () -> CompatibleLayerSerializer.class,
                () ->
                    typeResolver
                        .getJITContext()
                        .asyncVisitFory(
                            fory ->
                                CodecUtils.loadOrGenCompatibleLayerCodecClass(
                                    type, fory, layerTypeDef, layerMarkerClass)),
                c ->
                    thisInfo.slotsSerializer =
                        newGeneratedSerializer(
                            typeResolver,
                            type,
                            (Class<? extends Serializer>) c,
                            layerTypeDef,
                            layerMarkerClass));
      }

      // In GraalVM, ensure serializers are generated for all field types at build time
      // so they're available when new Fory instances are created at runtime
      if (GraalvmSupport.isGraalBuildTime()) {
        ensureFieldSerializersGenerated(typeResolver, layerTypeDef, type);
      }

      // Build fieldIndexMap and putFieldTypes from serializer's field order.
      // This ensures putFields/writeFields API uses the same order as the serializer
      // (primitive, nullable primitive, non-primitive), not ObjectStreamClass order.
      fieldIndexMap = new ObjectIntMap<>(4, 0.4f);
      if (streamTypeInfo != null
          && (streamTypeInfo.writeObjectMethod != null
              || streamTypeInfo.readObjectMethod != null
              || streamTypeInfo.defaultReadObjectHandle != null)) {
        this.numPutFields = slotsSerializer.getNumFields();
        this.putFieldTypes = new Class<?>[numPutFields];
        slotsSerializer.populateFieldInfo(fieldIndexMap, putFieldTypes);
      } else {
        this.numPutFields = 0;
        this.putFieldTypes = null;
      }

      if (streamTypeInfo != null && streamTypeInfo.writeObjectMethod != null) {
        try {
          objectOutputStream = new ForyStructOutputStream(this);
        } catch (IOException e) {
          ExceptionUtils.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectOutputStream = null;
      }
      if (streamTypeInfo != null
          && (streamTypeInfo.readObjectMethod != null
              || streamTypeInfo.defaultReadObjectHandle != null)) {
        try {
          objectInputStream = new ForyStructInputStream(this);
        } catch (IOException e) {
          ExceptionUtils.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectInputStream = null;
      }
      getFieldPool = new ObjectArray();
    }

    @Override
    public Class<?> getCls() {
      return cls;
    }

    @Override
    public StreamTypeInfo getStreamTypeInfo() {
      return streamTypeInfo;
    }

    @Override
    public CompatibleLayerSerializerBase getSlotsSerializer() {
      return slotsSerializer;
    }

    @Override
    public ForyStructOutputStream getObjectOutputStream() {
      return objectOutputStream;
    }

    @Override
    public ForyStructInputStream getObjectInputStream() {
      return objectInputStream;
    }

    @Override
    public ObjectArray getFieldPool() {
      return getFieldPool;
    }

    @Override
    public ObjectIntMap<String> getFieldIndexMap() {
      return fieldIndexMap;
    }

    @Override
    public int getNumPutFields() {
      return numPutFields;
    }

    @Override
    public Class<?>[] getPutFieldTypes() {
      return putFieldTypes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompatibleLayerSerializerBase getReadSerializer(
        TypeResolver typeResolver, ReadContext readContext) {
      CompatibleLayerSerializerBase result;
      if (!typeResolver.getConfig().isMetaShareEnabled()) {
        // Meta share not enabled - use the default slots serializer
        result = slotsSerializer;
      } else {
        // Read TypeInfo from buffer (creates new or returns existing)
        TypeInfo typeInfo = readLayerTypeInfo(typeResolver, readContext);
        if (typeInfo == null) {
          result = slotsSerializer;
        } else {
          // Get or create serializer from TypeInfo
          Serializer<?> serializer = typeInfo.getSerializer();
          if (serializer != null) {
            result = (CompatibleLayerSerializerBase) serializer;
          } else {
            // Create a new serializer based on the TypeDef from stream
            Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(cls, 0);
            CompatibleLayerSerializer<?> newSerializer =
                new CompatibleLayerSerializer(
                    typeResolver, cls, typeInfo.getTypeDef(), layerMarkerClass);
            typeInfo.setSerializer(newSerializer);
            result = newSerializer;
          }
        }
      }
      // Store for getCurrentReadSerializer()
      this.currentReadSerializer = result;
      return result;
    }

    @Override
    public CompatibleLayerSerializerBase getCurrentReadSerializer() {
      return currentReadSerializer;
    }

    private TypeInfo readLayerTypeInfo(TypeResolver typeResolver, ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      MetaReadContext metaReadContext = readContext.getMetaReadContext();
      if (metaReadContext == null) {
        return null;
      }
      int indexMarker = buffer.readVarUInt32Small14();
      boolean isRef = (indexMarker & 1) == 1;
      int index = indexMarker >>> 1;
      if (isRef) {
        // Reference to previously read TypeInfo
        return getMetaReadTypeInfo(metaReadContext, index);
      } else {
        // New TypeDef in stream, with optimized reuse by validated TypeDef header.
        long typeDefId = buffer.readInt64();
        TypeInfo typeInfo =
            ObjectStreamSerializer.this.readLayerTypeInfo(typeResolver, buffer, cls, typeDefId);
        metaReadContext.readTypeInfos.add(typeInfo);
        return typeInfo;
      }
    }

    @Override
    public String toString() {
      return "SlotsInfo{" + "cls=" + cls + '}';
    }
  }

  /**
   * Implement serialization for object output with `writeObject/readObject` defined by java
   * serialization output spec.
   *
   * @see <a href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/output.html">
   *     Java Object Serialization Output Specification</a>
   */
  private static class ForyStructOutputStream extends ObjectOutputStream {
    private final boolean compressInt;
    private final Int64Encoding longEncoding;
    private final SlotInfo slotsInfo;
    private final TypeResolver typeResolver;
    private WriteContext writeContext;
    private MemoryBuffer buffer;
    private Object targetObject;
    private boolean fieldsWritten;

    protected ForyStructOutputStream(SlotInfo slotsInfo) throws IOException {
      super();
      this.slotsInfo = slotsInfo;
      this.typeResolver = slotsInfo.getSlotsSerializer().typeResolver;
      this.compressInt = slotsInfo.getSlotsSerializer().config.compressInt();
      this.longEncoding = slotsInfo.getSlotsSerializer().config.longEncoding();
    }

    @Override
    protected final void writeObjectOverride(Object obj) throws IOException {
      writeContext.writeRef(obj);
    }

    @Override
    public void writeUnshared(Object obj) throws IOException {
      writeContext.writeNonRef(obj);
    }

    /**
     * PutField is used to write fields which may not exists in current class for compatibility.
     * Note that the protocol should be compatible with `defaultReadObject`.
     *
     * @see <a
     *     href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/input.html#the-objectinputstream.getfield-class">ObjectInputStream.GetField</a>
     * @see java.util.concurrent.ConcurrentHashMap
     */
    private class PutFieldImpl extends PutField {
      private final Object[] vals;

      PutFieldImpl() {
        vals = new Object[slotsInfo.getNumPutFields()];
      }

      private void putValue(String name, Object val) {
        int index = slotsInfo.getFieldIndexMap().get(name, -1);
        if (index == -1) {
          throw new IllegalArgumentException(
              String.format(
                  "Field name %s not exist in class %s",
                  name, slotsInfo.getSlotsSerializer().type));
        }
        vals[index] = val;
      }

      @Override
      public void put(String name, boolean val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, byte val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, char val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, short val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, int val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, long val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, float val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, double val) {
        putValue(name, val);
      }

      @Override
      public void put(String name, Object val) {
        putValue(name, val);
      }

      @Override
      public void write(ObjectOutput out) throws IOException {
        Class cls = slotsInfo.getSlotsSerializer().getType();
        throwUnsupportedEncodingException(cls);
      }
    }

    private final ObjectArray putFieldsCache = new ObjectArray();
    private PutFieldImpl curPut;

    @Override
    public PutField putFields() throws IOException {
      if (curPut == null) {
        Object o = putFieldsCache.popOrNull();
        if (o == null) {
          o = new PutFieldImpl();
        }
        curPut = (PutFieldImpl) o;
      }
      return curPut;
    }

    @Override
    public void writeFields() throws IOException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      PutFieldImpl curPut = this.curPut;
      if (curPut == null) {
        throw new NotActiveException("no current PutField object");
      }
      // Write field values using MetaShare serialization
      slotsInfo.getSlotsSerializer().writeFieldValues(writeContext, curPut.vals);
      Arrays.fill(curPut.vals, null);
      putFieldsCache.add(curPut);
      this.curPut = null;
      fieldsWritten = true;
    }

    private void writePutFieldValue(MemoryBuffer buffer, Class<?> fieldType, Object value) {
      if (fieldType == boolean.class) {
        buffer.writeBoolean(value != null && (Boolean) value);
      } else if (fieldType == byte.class) {
        buffer.writeByte(value == null ? (byte) 0 : (Byte) value);
      } else if (fieldType == char.class) {
        buffer.writeChar(value == null ? (char) 0 : (Character) value);
      } else if (fieldType == short.class) {
        buffer.writeInt16(value == null ? (short) 0 : (Short) value);
      } else if (fieldType == int.class) {
        if (compressInt) {
          buffer.writeVarInt32(value == null ? 0 : (Integer) value);
        } else {
          buffer.writeInt32(value == null ? 0 : (Integer) value);
        }
      } else if (fieldType == long.class) {
        LongSerializer.writeInt64(buffer, value == null ? 0L : (Long) value, longEncoding);
      } else if (fieldType == float.class) {
        buffer.writeFloat32(value == null ? 0f : (Float) value);
      } else if (fieldType == double.class) {
        buffer.writeFloat64(value == null ? 0d : (Double) value);
      } else {
        // Object reference
        writeContext.writeRef(value);
      }
    }

    @Override
    public void defaultWriteObject() throws IOException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      // Write fields using MetaShare serialization (layer meta already written by write())
      slotsInfo.getSlotsSerializer().writeFieldsOnly(writeContext, targetObject);
      fieldsWritten = true;
    }

    @Override
    public void reset() throws IOException {
      Class cls = slotsInfo.getSlotsSerializer().getType();
      // Fory won't invoke this method, throw exception if the user invokes it.
      throwUnsupportedEncodingException(cls);
    }

    @Override
    protected void annotateClass(Class<?> cl) throws IOException {
      throw new IllegalStateException();
    }

    @Override
    protected void annotateProxyClass(Class<?> cl) throws IOException {
      throw new IllegalStateException();
    }

    @Override
    protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
      throw new UnsupportedEncodingException();
    }

    @Override
    protected Object replaceObject(Object obj) throws IOException {
      throw new UnsupportedEncodingException();
    }

    @Override
    protected boolean enableReplaceObject(boolean enable) throws SecurityException {
      throw new IllegalStateException();
    }

    @Override
    protected void writeStreamHeader() throws IOException {
      throw new IllegalStateException();
    }

    @Override
    public void write(int b) throws IOException {
      buffer.writeByte((byte) b);
    }

    @Override
    public void write(byte[] b) throws IOException {
      buffer.writeBytes(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      buffer.writeBytes(b, off, len);
    }

    @Override
    public void writeBoolean(boolean v) throws IOException {
      buffer.writeBoolean(v);
    }

    @Override
    public void writeByte(int v) throws IOException {
      buffer.writeByte((byte) v);
    }

    @Override
    public void writeShort(int v) throws IOException {
      buffer.writeInt16((short) v);
    }

    @Override
    public void writeChar(int v) throws IOException {
      buffer.writeChar((char) v);
    }

    @Override
    public void writeInt(int v) throws IOException {
      if (compressInt) {
        buffer.writeVarInt32(v);
      } else {
        buffer.writeInt32(v);
      }
    }

    @Override
    public void writeLong(long v) throws IOException {
      LongSerializer.writeInt64(buffer, v, longEncoding);
    }

    @Override
    public void writeFloat(float v) throws IOException {
      buffer.writeFloat32(v);
    }

    @Override
    public void writeDouble(double v) throws IOException {
      buffer.writeFloat64(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
      Preconditions.checkNotNull(s);
      int len = s.length();
      for (int i = 0; i < len; i++) {
        buffer.writeByte((byte) s.charAt(i));
      }
    }

    @Override
    public void writeChars(String s) throws IOException {
      Preconditions.checkNotNull(s);
      writeContext.writeString(s);
    }

    @Override
    public void writeUTF(String s) throws IOException {
      Preconditions.checkNotNull(s);
      writeContext.writeString(s);
    }

    @Override
    public void useProtocolVersion(int version) throws IOException {
      Class cls = slotsInfo.getCls();
      throwUnsupportedEncodingException(cls);
    }

    @Override
    public void flush() throws IOException {}

    @Override
    protected void drain() throws IOException {}

    @Override
    public void close() throws IOException {}
  }

  /**
   * Implement serialization for object output with `writeObject/readObject` defined by java
   * serialization input spec.
   *
   * @see <a href="https://docs.oracle.com/en/java/javase/18/docs/specs/serialization/input.html">
   *     Java Object Serialization Input Specification</a>
   */
  private static class ForyStructInputStream extends ObjectInputStream {
    private final boolean compressInt;
    private final Int64Encoding longEncoding;
    private final SlotInfo slotsInfo;
    private final TypeResolver typeResolver;
    private ReadContext readContext;
    private MemoryBuffer buffer;
    private Object targetObject;
    private GetFieldImpl getField;
    private Object[] fieldValuesOverride;
    private boolean fieldsRead;
    private TreeMap<Integer, ObjectInputValidation> callbacks;

    protected ForyStructInputStream(SlotInfo slotsInfo) throws IOException {
      this.compressInt = slotsInfo.getSlotsSerializer().config.compressInt();
      this.longEncoding = slotsInfo.getSlotsSerializer().config.longEncoding();
      this.slotsInfo = slotsInfo;
      this.typeResolver = slotsInfo.getSlotsSerializer().typeResolver;
    }

    @Override
    protected Object readObjectOverride() {
      return readContext.readRef();
    }

    @Override
    public Object readUnshared() {
      return readContext.readNonRef();
    }

    private static final Object NO_VALUE_STUB = new Object();

    /**
     * Implementation of ObjectInputStream.GetField for reading fields that may not exist in the
     * current class version.
     */
    private static class GetFieldImpl extends GetField {
      private final SlotInfo slotsInfo;
      private final Object[] vals;

      GetFieldImpl(SlotInfo slotsInfo) {
        this.slotsInfo = slotsInfo;
        vals = new Object[slotsInfo.getNumPutFields()];
        Arrays.fill(vals, NO_VALUE_STUB);
      }

      @Override
      public ObjectStreamClass getObjectStreamClass() {
        return safeObjectStreamClassLookup(slotsInfo.getCls());
      }

      @Override
      public boolean defaulted(String name) throws IOException {
        int index = slotsInfo.getFieldIndexMap().get(name, -1);
        checkFieldExists(name, index);
        return vals[index] == NO_VALUE_STUB;
      }

      @Override
      public boolean get(String name, boolean val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (boolean) fieldValue;
      }

      @Override
      public byte get(String name, byte val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (byte) fieldValue;
      }

      @Override
      public char get(String name, char val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (char) fieldValue;
      }

      @Override
      public short get(String name, short val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (short) fieldValue;
      }

      @Override
      public int get(String name, int val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (int) fieldValue;
      }

      @Override
      public long get(String name, long val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (long) fieldValue;
      }

      @Override
      public float get(String name, float val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (float) fieldValue;
      }

      @Override
      public double get(String name, double val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return (double) fieldValue;
      }

      @Override
      public Object get(String name, Object val) throws IOException {
        Object fieldValue = getFieldValue(name);
        if (fieldValue == NO_VALUE_STUB) {
          return val;
        }
        return fieldValue;
      }

      private Object getFieldValue(String name) {
        int index = slotsInfo.getFieldIndexMap().get(name, -1);
        checkFieldExists(name, index);
        return vals[index];
      }

      private void checkFieldExists(String name, int index) {
        if (index == -1) {
          throw new IllegalArgumentException(
              String.format(
                  "Field name %s not exist in class %s",
                  name, slotsInfo.getSlotsSerializer().getType()));
        }
      }
    }

    // `readFields`/`writeFields` can handle fields which doesn't exist in current class.
    // `defaultReadObject` will skip those fields.
    @Override
    public GetField readFields() throws IOException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      Object[] vals = fieldValuesOverride;
      if (vals == null) {
        // Read field values using MetaShare serialization
        vals = slotsInfo.getCurrentReadSerializer().readFieldValues(readContext);
      }
      System.arraycopy(vals, 0, getField.vals, 0, vals.length);
      fieldsRead = true;
      return getField;
    }

    private Object readPutFieldValue(MemoryBuffer buffer, Class<?> fieldType) {
      if (fieldType == boolean.class) {
        return buffer.readBoolean();
      } else if (fieldType == byte.class) {
        return buffer.readByte();
      } else if (fieldType == char.class) {
        return buffer.readChar();
      } else if (fieldType == short.class) {
        return buffer.readInt16();
      } else if (fieldType == int.class) {
        if (compressInt) {
          return buffer.readVarInt32();
        } else {
          return buffer.readInt32();
        }
      } else if (fieldType == long.class) {
        return LongSerializer.readInt64(buffer, longEncoding);
      } else if (fieldType == float.class) {
        return buffer.readFloat32();
      } else if (fieldType == double.class) {
        return buffer.readFloat64();
      } else {
        // Object reference
        return readContext.readRef();
      }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void defaultReadObject() throws IOException, ClassNotFoundException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      runDefaultReadObject();
      fieldsRead = true;
    }

    private void runDefaultReadObject() throws IOException, ClassNotFoundException {
      MethodHandle defaultReadObjectHandle = slotsInfo.getStreamTypeInfo().defaultReadObjectHandle;
      if (defaultReadObjectHandle == null) {
        // Read fields using MetaShare serialization (layer meta already read by
        // getReadSerializer())
        slotsInfo.getCurrentReadSerializer().readAndSetFields(readContext, targetObject);
        return;
      }
      try {
        // This public Java serialization compatibility hook writes final fields without requiring
        // per-package JDK opens. It calls this stream's readFields() implementation for values.
        defaultReadObjectHandle.invoke(targetObject, this);
      } catch (IOException | ClassNotFoundException e) {
        throw e;
      } catch (Throwable e) {
        throw new IOException("Failed to default-read Java serialization fields", e);
      }
    }

    // At `registerValidation` point int `readObject` root is only partially correct. To fully
    // restore it user may need access to other state which is created by the subclass and
    // at this point will be null. Users thus use `registerValidation`.
    // see `javax.swing.text.AbstractDocument.readObject` as an example.
    @Override
    public void registerValidation(ObjectInputValidation obj, int prio)
        throws NotActiveException, InvalidObjectException {
      // Since this method is only visible to fory, it won't be invoked by users outside
      // `readObject`,
      // we can skip check whether the caller is in `readObject`.
      if (obj == null) {
        throw new InvalidObjectException("null callback");
      }
      callbacks.put(prio, obj);
    }

    @Override
    public int read() throws IOException {
      return buffer.readByte() & 0xFF;
    }

    @Override
    public int read(byte[] buf, int offset, int length) throws IOException {
      if (buf == null) {
        throw new NullPointerException();
      }
      int endOffset = offset + length;
      if (offset < 0 || length < 0 || endOffset > buf.length || endOffset < 0) {
        throw new IndexOutOfBoundsException();
      }
      int remaining = buffer.remaining();
      // When remaining = 0, also force to refill buffer to avoid getting stuck
      if (remaining > 0 && remaining < length) {
        buffer.readBytes(buf, offset, remaining);
        return remaining;
      } else {
        buffer.readBytes(buf, offset, length);
        return length;
      }
    }

    @Override
    public int available() throws IOException {
      return buffer.remaining();
    }

    @Override
    public boolean readBoolean() throws IOException {
      return buffer.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
      return buffer.readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
      int b = buffer.readByte();
      return b & 0xff;
    }

    @Override
    public short readShort() throws IOException {
      return buffer.readInt16();
    }

    public int readUnsignedShort() throws IOException {
      int b = buffer.readInt16();
      return b & 0xffff;
    }

    @Override
    public char readChar() throws IOException {
      return buffer.readChar();
    }

    @Override
    public int readInt() throws IOException {
      return compressInt ? buffer.readVarInt32() : buffer.readInt32();
    }

    @Override
    public long readLong() throws IOException {
      return LongSerializer.readInt64(buffer, longEncoding);
    }

    @Override
    public float readFloat() throws IOException {
      return buffer.readFloat32();
    }

    @Override
    public double readDouble() throws IOException {
      return buffer.readFloat64();
    }

    @Override
    public void readFully(byte[] data) throws IOException {
      buffer.readBytes(data);
    }

    @Override
    public void readFully(byte[] data, int offset, int size) throws IOException {
      buffer.readBytes(data, offset, size);
    }

    @Override
    public int skipBytes(int len) throws IOException {
      buffer.increaseReaderIndex(len);
      return len;
    }

    @Override
    public String readLine() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public String readUTF() throws IOException {
      return readContext.readString();
    }

    @Override
    public void close() throws IOException {}
  }
}
