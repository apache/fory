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
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.LayerMarkerClassGenerator;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.FieldInfo;
import org.apache.fory.meta.FieldTypes;
import org.apache.fory.meta.NativeTypeDefEncoder;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Implement jdk custom serialization only if following conditions are met:
 *
 * <ul>
 *   <li>`writeObject/readObject` occurs only at current class in class hierarchy.
 *   <li>`writeReplace/readResolve` don't occur in class hierarchy.
 *   <li>class hierarchy doesn't have duplicated fields. If any of those conditions are not met,
 *       fallback jdk custom serialization to {@link JavaSerializer}.
 * </ul>
 *
 * <p>`ObjectInputStream#setObjectInputFilter` will be ignored by this serializer.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ObjectStreamSerializer extends AbstractObjectSerializer {
  private static final Logger LOG = LoggerFactory.getLogger(ObjectStreamSerializer.class);

  private final SlotInfo[] slotsInfos;
  // Instance-level cache: TypeDef ID -> TypeInfo (shared across all slots)
  private final LongMap<TypeInfo> typeDefIdToTypeInfo = new LongMap<>(4, 0.4f);

  /**
   * Interface for slot information used in ObjectStreamSerializer. This allows both full SlotsInfo
   * and minimal MinimalSlotsInfo implementations.
   */
  private interface SlotInfo {
    Class<?> getCls();

    StreamTypeInfo getStreamTypeInfo();

    MetaSharedLayerSerializerBase getSlotsSerializer();

    /**
     * Read the layer TypeDef from buffer (if meta share enabled) and return the appropriate
     * serializer. When meta share is enabled, reads the TypeDef from buffer, caches the serializer
     * by TypeDef ID, and returns it. When meta share is disabled, returns the default slots
     * serializer. Also stores the returned serializer for later retrieval via {@link
     * #getCurrentReadSerializer()}.
     *
     * @param fory the Fory instance
     * @param buffer the memory buffer to read TypeDef from
     * @return the serializer to use for reading
     */
    MetaSharedLayerSerializerBase getReadSerializer(Fory fory, MemoryBuffer buffer);

    /**
     * Get the current read serializer (last returned by {@link #getReadSerializer}). This is used
     * by defaultReadObject() to get the serializer without reading from buffer again.
     *
     * @return the current read serializer
     */
    MetaSharedLayerSerializerBase getCurrentReadSerializer();

    ForyObjectOutputStream getObjectOutputStream();

    ForyObjectInputStream getObjectInputStream();

    ObjectArray getFieldPool();

    ObjectIntMap<String> getFieldIndexMap();

    int getNumPutFields();

    /** Get field types for putFields/writeFields support, in index order. */
    Class<?>[] getPutFieldTypes();
  }

  /**
   * Safe wrapper for ObjectStreamClass.lookup that handles GraalVM native image limitations. In
   * GraalVM native image, ObjectStreamClass.lookup may fail for certain classes like Throwable due
   * to missing SerializationConstructorAccessor. This method catches such errors and returns null,
   * allowing the serializer to use alternative approaches like Unsafe.allocateInstance.
   */
  private static ObjectStreamClass safeObjectStreamClassLookup(Class<?> type) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      try {
        return ObjectStreamClass.lookup(type);
      } catch (Throwable e) {
        // In GraalVM native image, ObjectStreamClass.lookup may fail for certain classes
        // due to missing SerializationConstructorAccessor. We catch this and return null
        // to allow fallback to Unsafe-based object creation.
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

  public ObjectStreamSerializer(Fory fory, Class<?> type) {
    super(fory, type, createObjectCreatorForGraalVM(type));
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
    fory.getClassResolver().setSerializerIfAbsent(type, this);
    List<SlotInfo> slotsInfoList = new ArrayList<>();
    Class<?> end = type;
    // locate closest non-serializable superclass
    while (end != null && Serializable.class.isAssignableFrom(end)) {
      end = end.getSuperclass();
    }
    while (type != end) {
      slotsInfoList.add(new SlotsInfo(fory, type));
      type = type.getSuperclass();
    }
    Collections.reverse(slotsInfoList);
    slotsInfos = slotsInfoList.toArray(new SlotInfo[0]);
  }

  /**
   * Creates an appropriate ObjectCreator for GraalVM native image environment. In GraalVM, we
   * prefer UnsafeObjectCreator to avoid serialization constructor issues.
   */
  private static <T> ObjectCreator<T> createObjectCreatorForGraalVM(Class<T> type) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      // In GraalVM native image, use Unsafe to avoid serialization constructor issues
      return new ObjectCreators.UnsafeObjectCreator<>(type);
    } else {
      // In regular JVM, use the standard object creator
      return ObjectCreators.getObjectCreator(type);
    }
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {
    buffer.writeInt16((short) slotsInfos.length);
    try {
      for (SlotInfo slotsInfo : slotsInfos) {
        // create a classinfo to avoid null class bytes when class id is a
        // replacement id.
        classResolver.writeClassInternal(buffer, slotsInfo.getCls());
        // Write layer class meta first (if meta share enabled)
        MetaSharedLayerSerializerBase serializer = slotsInfo.getSlotsSerializer();
        if (fory.getConfig().isMetaShareEnabled()) {
          serializer.writeLayerClassMeta(buffer);
        }
        StreamTypeInfo streamTypeInfo = slotsInfo.getStreamTypeInfo();
        Method writeObjectMethod = streamTypeInfo.writeObjectMethod;
        if (writeObjectMethod == null) {
          // No custom writeObject - write fields directly
          serializer.writeFieldsOnly(buffer, value);
        } else {
          ForyObjectOutputStream objectOutputStream = slotsInfo.getObjectOutputStream();
          Object oldObject = objectOutputStream.targetObject;
          MemoryBuffer oldBuffer = objectOutputStream.buffer;
          ForyObjectOutputStream.PutFieldImpl oldPutField = objectOutputStream.curPut;
          boolean fieldsWritten = objectOutputStream.fieldsWritten;
          try {
            objectOutputStream.targetObject = value;
            objectOutputStream.buffer = buffer;
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
  public Object read(MemoryBuffer buffer) {
    Object obj = objectCreator.newInstance();
    fory.getRefResolver().reference(obj);
    int numClasses = buffer.readInt16();
    int slotIndex = 0;

    try {
      TreeMap<Integer, ObjectInputValidation> callbacks = new TreeMap<>(Collections.reverseOrder());
      for (int i = 0; i < numClasses; i++) {
        Class<?> currentClass = classResolver.readClassInternal(buffer);

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
          skipUnknownLayerData(buffer, currentClass);
          continue;
        }

        // Read data for the matched layer - getReadSerializer reads TypeDef from buffer
        // This must be called exactly once per layer to read the TypeDef
        matchedSlot.getReadSerializer(fory, buffer);

        StreamTypeInfo streamTypeInfo = matchedSlot.getStreamTypeInfo();
        Method readObjectMethod = streamTypeInfo.readObjectMethod;

        if (readObjectMethod == null) {
          // For standard field serialization - use getCurrentReadSerializer()
          matchedSlot.getCurrentReadSerializer().readAndSetFields(buffer, obj);
        } else {
          // For custom readObject, it handles its own format
          ForyObjectInputStream objectInputStream = matchedSlot.getObjectInputStream();
          MemoryBuffer oldBuffer = objectInputStream.buffer;
          Object oldObject = objectInputStream.targetObject;
          ForyObjectInputStream.GetFieldImpl oldGetField = objectInputStream.getField;
          ForyObjectInputStream.GetFieldImpl getField =
              (ForyObjectInputStream.GetFieldImpl) matchedSlot.getFieldPool().popOrNull();
          if (getField == null) {
            getField = new ForyObjectInputStream.GetFieldImpl(matchedSlot);
          }
          boolean fieldsRead = objectInputStream.fieldsRead;
          try {
            objectInputStream.fieldsRead = false;
            objectInputStream.buffer = buffer;
            objectInputStream.targetObject = obj;
            objectInputStream.getField = getField;
            objectInputStream.callbacks = callbacks;
            if (streamTypeInfo.readObjectFunc != null) {
              streamTypeInfo.readObjectFunc.accept(obj, objectInputStream);
            } else {
              readObjectMethod.invoke(obj, objectInputStream);
            }
          } finally {
            objectInputStream.fieldsRead = fieldsRead;
            objectInputStream.buffer = oldBuffer;
            objectInputStream.targetObject = oldObject;
            objectInputStream.getField = oldGetField;
            matchedSlot.getFieldPool().add(getField);
            objectInputStream.callbacks = null;
            Arrays.fill(getField.vals, ForyObjectInputStream.NO_VALUE_STUB);
          }
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
    } catch (InvocationTargetException | IllegalAccessException | InvalidObjectException e) {
      throwSerializationException(type, e);
    }
    return obj;
  }

  /**
   * Skip data for a layer that exists in sender but not in receiver. This is needed for schema
   * evolution when sender's class hierarchy has layers that receiver doesn't have.
   *
   * @param buffer the memory buffer to read from
   * @param senderClass the class from sender that receiver doesn't have
   */
  private void skipUnknownLayerData(MemoryBuffer buffer, Class<?> senderClass) {
    // For layers without custom writeObject, we can skip using a serializer created from the
    // TypeDef. Note: For layers with custom writeObject, the sender would have that class
    // locally, and we'd have a matching slot. This method is only called when sender has a
    // layer the receiver doesn't have.

    if (!fory.getConfig().isMetaShareEnabled()) {
      throw new UnsupportedOperationException(
          "Cannot skip unknown layer data without meta share enabled for class: "
              + senderClass.getName()
              + ". Schema evolution with removed parent classes requires meta share.");
    }

    // Read TypeInfo from buffer (maintains index alignment with readTypeInfos)
    MetaContext metaContext = fory.getSerializationContext().getMetaContext();
    if (metaContext == null) {
      throw new IllegalStateException("MetaContext is null but meta share is enabled");
    }
    int indexMarker = buffer.readVarUint32Small14();
    boolean isRef = (indexMarker & 1) == 1;
    int index = indexMarker >>> 1;
    TypeInfo typeInfo;
    if (isRef) {
      // Reference to previously read TypeInfo
      typeInfo = metaContext.readTypeInfos.get(index);
    } else {
      // New TypeDef in stream - read ID first to check cache
      long typeDefId = buffer.readInt64();
      typeInfo = typeDefIdToTypeInfo.get(typeDefId);
      if (typeInfo != null) {
        // Already cached - skip the TypeDef bytes, reuse existing TypeInfo
        TypeDef.skipTypeDef(buffer, typeDefId);
      } else {
        // Not cached - read full TypeDef and create TypeInfo
        TypeDef typeDef = TypeDef.readTypeDef(fory, buffer, typeDefId);
        typeInfo = new TypeInfo(senderClass, typeDef);
        typeDefIdToTypeInfo.put(typeDefId, typeInfo);
      }
      metaContext.readTypeInfos.add(typeInfo);
    }

    // Get or create serializer from TypeInfo to skip the fields
    MetaSharedLayerSerializerBase skipSerializer =
        (MetaSharedLayerSerializerBase) typeInfo.getSerializer();
    if (skipSerializer == null) {
      Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(fory, senderClass, 0);
      MetaSharedLayerSerializer<?> newSerializer =
          new MetaSharedLayerSerializer(fory, senderClass, typeInfo.getTypeDef(), layerMarkerClass);
      typeInfo.setSerializer(newSerializer);
      skipSerializer = newSerializer;
    }
    ((MetaSharedLayerSerializer<?>) skipSerializer).skipFields(buffer);
  }

  private static void throwUnsupportedEncodingException(Class<?> cls)
      throws UnsupportedEncodingException {
    throw new UnsupportedEncodingException(
        String.format(
            "Use %s instead by `fory.registerSerializer(%s, new JavaSerializer(fory, %s))` or "
                + "implement a custom %s.",
            JavaSerializer.class, cls, cls, Serializer.class));
  }

  private static void throwSerializationException(Class<?> type, Exception e) {
    throw new RuntimeException(
        String.format(
            "Serialize object of type %s failed, "
                + "Try to use %s instead by `fory.registerSerializer(%s, new JavaSerializer(fory, %s))` or "
                + "implement a custom %s.",
            type, JavaSerializer.class, type, type, Serializer.class),
        e);
  }

  /**
   * Ensures serializers are generated for all field types of a class during GraalVM build time.
   * This is necessary because when a new Fory instance is created at runtime in GraalVM native
   * images, it needs to reuse the serializers that were generated at build time.
   *
   * @param fory the Fory instance
   * @param layerTypeDef the TypeDef for this layer
   * @param type the target class type
   */
  private static void ensureFieldSerializersGenerated(
      Fory fory, TypeDef layerTypeDef, Class<?> type) {
    Collection<Descriptor> descriptors = layerTypeDef.getDescriptors(fory.getClassResolver(), type);
    for (Descriptor descriptor : descriptors) {
      Class<?> fieldType = descriptor.getRawType();
      if (fieldType != null && !fieldType.isPrimitive()) {
        try {
          // Trigger serializer generation for this field type
          fory.getClassResolver().getSerializerClass(fieldType);
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
   * @param fory the Fory instance
   * @param objectStreamClass the ObjectStreamClass for the type
   * @param type the class type
   * @return list of FieldInfo representing the serialization fields
   */
  private static List<FieldInfo> buildFieldInfoFromObjectStreamClass(
      Fory fory, ObjectStreamClass objectStreamClass, Class<?> type) {
    ObjectStreamField[] streamFields = objectStreamClass.getFields();
    List<FieldInfo> fieldInfos = new ArrayList<>(streamFields.length);
    String className = type.getName();

    for (ObjectStreamField streamField : streamFields) {
      Class<?> fieldType = streamField.getType();
      String fieldName = streamField.getName();

      // Build FieldType based on the field's declared type
      FieldTypes.FieldType fieldTypeInfo = buildFieldTypeFromClass(fory, fieldType);

      fieldInfos.add(new FieldInfo(className, fieldName, fieldTypeInfo));
    }
    return fieldInfos;
  }

  /**
   * Build a FieldType from a Class. This is a simplified version of FieldTypes.buildFieldType that
   * works with Class instead of Field, used for ObjectStreamField which only provides type info.
   */
  private static FieldTypes.FieldType buildFieldTypeFromClass(Fory fory, Class<?> fieldType) {
    // For primitives, use RegisteredFieldType
    if (fieldType.isPrimitive()) {
      int typeId = Types.getTypeId(fory, fieldType);
      return new FieldTypes.RegisteredFieldType(false, false, typeId, -1);
    }

    // For boxed primitives
    Class<?> unwrapped = TypeUtils.unwrap(fieldType);
    if (unwrapped.isPrimitive()) {
      int typeId = Types.getTypeId(fory, unwrapped);
      return new FieldTypes.RegisteredFieldType(true, true, typeId, -1);
    }

    // For registered types
    if (fory.getClassResolver().isRegisteredById(fieldType)) {
      int typeId = fory.getClassResolver().getTypeIdForTypeDef(fieldType);
      int userTypeId = fory.getClassResolver().getUserTypeIdForTypeDef(fieldType);
      return new FieldTypes.RegisteredFieldType(true, true, typeId, userTypeId);
    }

    // For enums
    if (fieldType.isEnum()) {
      return new FieldTypes.EnumFieldType(true, -1, -1);
    }

    // For arrays, collections, maps - use ObjectFieldType as a fallback
    // The actual type handling will be done during serialization
    return new FieldTypes.ObjectFieldType(-1, true, true);
  }

  /**
   * Information about a class's stream methods (writeObject, readObject, readObjectNoData) and
   * their optimized MethodHandle equivalents for fast invocation.
   */
  private static class StreamTypeInfo {
    private final Method writeObjectMethod;
    private final Method readObjectMethod;
    private final Method readObjectNoData;
    private final BiConsumer writeObjectFunc;
    private final BiConsumer readObjectFunc;
    private final Consumer readObjectNoDataFunc;

    private StreamTypeInfo(Class<?> type) {
      // ObjectStreamClass.lookup has cache inside, invocation cost won't be big.
      ObjectStreamClass objectStreamClass = safeObjectStreamClassLookup(type);
      // In JDK17, set private jdk method accessible will fail by default, use ObjectStreamClass
      // instead, since it set accessible.
      Method writeMethod = null;
      Method readMethod = null;
      Method noDataMethod = null;
      if (objectStreamClass != null) {
        writeMethod =
            (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "writeObjectMethod");
        readMethod =
            (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectMethod");
        noDataMethod =
            (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectNoData");
      }
      this.writeObjectMethod = writeMethod;
      this.readObjectMethod = readMethod;
      this.readObjectNoData = noDataMethod;
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
  }

  private static final ClassValue<StreamTypeInfo> STREAM_CLASS_INFO_CACHE =
      new ClassValue<StreamTypeInfo>() {
        @Override
        protected StreamTypeInfo computeValue(Class<?> type) {
          return new StreamTypeInfo(type);
        }
      };

  /**
   * Full implementation of SlotInfo for handling object stream serialization. This class manages
   * all the details of serializing and deserializing a single class in the class hierarchy using
   * Java's ObjectInputStream/ObjectOutputStream protocol.
   */
  private class SlotsInfo implements SlotInfo {
    private final Class<?> cls;
    private final StreamTypeInfo streamTypeInfo;
    // mark non-final for async-jit to update it to jit-serializer.
    private MetaSharedLayerSerializerBase slotsSerializer;
    private final ObjectIntMap<String> fieldIndexMap;
    private final int numPutFields;
    private final Class<?>[] putFieldTypes;
    private final ForyObjectOutputStream objectOutputStream;
    private final ForyObjectInputStream objectInputStream;
    private final ObjectArray getFieldPool;
    // Current read serializer (set by getReadSerializer, used by getCurrentReadSerializer)
    private MetaSharedLayerSerializerBase currentReadSerializer;

    public SlotsInfo(Fory fory, Class<?> type) {
      this.cls = type;
      ObjectStreamClass objectStreamClass = safeObjectStreamClassLookup(type);
      streamTypeInfo = STREAM_CLASS_INFO_CACHE.get(type);

      // Build TypeDef from ObjectStreamClass fields (handles serialPersistentFields).
      // This ensures the serializer uses the same field layout as defined by
      // serialPersistentFields (if present) rather than actual class fields.
      TypeDef layerTypeDef;
      if (objectStreamClass != null) {
        List<FieldInfo> fieldInfos =
            buildFieldInfoFromObjectStreamClass(fory, objectStreamClass, type);
        layerTypeDef =
            NativeTypeDefEncoder.buildTypeDefWithFieldInfos(
                fory.getClassResolver(), type, fieldInfos, true);
      } else {
        // Fallback when ObjectStreamClass is not available (e.g., GraalVM native image)
        layerTypeDef = fory.getClassResolver().getTypeDef(type, false);
      }
      // Generate marker class for this layer. Use 0 as layer index since each class
      // has its own SlotsInfo, and the (class, 0) pair is unique for each class.
      Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(fory, type, 0);

      // Create interpreter-mode serializer first
      this.slotsSerializer =
          new MetaSharedLayerSerializer(fory, type, layerTypeDef, layerMarkerClass);

      // Register JIT callback to replace with JIT serializer when ready
      if (fory.getConfig().isCodeGenEnabled()) {
        SlotsInfo thisInfo = this;
        fory.getJITContext()
            .registerSerializerJITCallback(
                () -> MetaSharedLayerSerializer.class,
                () ->
                    CodecUtils.loadOrGenMetaSharedLayerCodecClass(
                        type, fory, layerTypeDef, layerMarkerClass),
                c ->
                    thisInfo.slotsSerializer =
                        (MetaSharedLayerSerializerBase)
                            Serializers.newSerializer(fory, type, (Class<? extends Serializer>) c));
      }

      // In GraalVM, ensure serializers are generated for all field types at build time
      // so they're available when new Fory instances are created at runtime
      if (GraalvmSupport.isGraalBuildtime()) {
        ensureFieldSerializersGenerated(fory, layerTypeDef, type);
      }

      // Build fieldIndexMap and putFieldTypes from serializer's field order.
      // This ensures putFields/writeFields API uses the same order as the serializer
      // (buildIn, container, other groups), not ObjectStreamClass order.
      fieldIndexMap = new ObjectIntMap<>(4, 0.4f);
      if (streamTypeInfo != null
          && (streamTypeInfo.writeObjectMethod != null
              || streamTypeInfo.readObjectMethod != null)) {
        this.numPutFields = slotsSerializer.getNumFields();
        this.putFieldTypes = new Class<?>[numPutFields];
        slotsSerializer.populateFieldInfo(fieldIndexMap, putFieldTypes);
      } else {
        this.numPutFields = 0;
        this.putFieldTypes = null;
      }

      if (streamTypeInfo != null && streamTypeInfo.writeObjectMethod != null) {
        try {
          objectOutputStream = new ForyObjectOutputStream(this);
        } catch (IOException e) {
          Platform.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectOutputStream = null;
      }
      if (streamTypeInfo != null && streamTypeInfo.readObjectMethod != null) {
        try {
          objectInputStream = new ForyObjectInputStream(this);
        } catch (IOException e) {
          Platform.throwException(e);
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
    public MetaSharedLayerSerializerBase getSlotsSerializer() {
      return slotsSerializer;
    }

    @Override
    public ForyObjectOutputStream getObjectOutputStream() {
      return objectOutputStream;
    }

    @Override
    public ForyObjectInputStream getObjectInputStream() {
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
    public MetaSharedLayerSerializerBase getReadSerializer(Fory fory, MemoryBuffer buffer) {
      MetaSharedLayerSerializerBase result;
      if (!fory.getConfig().isMetaShareEnabled()) {
        // Meta share not enabled - use the default slots serializer
        result = slotsSerializer;
      } else {
        // Read TypeInfo from buffer (creates new or returns existing)
        TypeInfo typeInfo = readLayerTypeInfo(fory, buffer);
        if (typeInfo == null) {
          result = slotsSerializer;
        } else {
          // Get or create serializer from TypeInfo
          Serializer<?> serializer = typeInfo.getSerializer();
          if (serializer != null) {
            result = (MetaSharedLayerSerializerBase) serializer;
          } else {
            // Create a new serializer based on the TypeDef from stream
            Class<?> layerMarkerClass = LayerMarkerClassGenerator.getOrCreate(fory, cls, 0);
            MetaSharedLayerSerializer<?> newSerializer =
                new MetaSharedLayerSerializer(fory, cls, typeInfo.getTypeDef(), layerMarkerClass);
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
    public MetaSharedLayerSerializerBase getCurrentReadSerializer() {
      return currentReadSerializer;
    }

    private TypeInfo readLayerTypeInfo(Fory fory, MemoryBuffer buffer) {
      MetaContext metaContext = fory.getSerializationContext().getMetaContext();
      if (metaContext == null) {
        return null;
      }
      int indexMarker = buffer.readVarUint32Small14();
      boolean isRef = (indexMarker & 1) == 1;
      int index = indexMarker >>> 1;
      if (isRef) {
        // Reference to previously read TypeInfo
        return metaContext.readTypeInfos.get(index);
      } else {
        // New TypeDef in stream - read ID first to check cache
        long typeDefId = buffer.readInt64();
        TypeInfo typeInfo = typeDefIdToTypeInfo.get(typeDefId);
        if (typeInfo != null) {
          // Already cached - skip the TypeDef bytes, reuse existing TypeInfo
          TypeDef.skipTypeDef(buffer, typeDefId);
        } else {
          // Not cached - read full TypeDef and create TypeInfo
          TypeDef typeDef = TypeDef.readTypeDef(fory, buffer, typeDefId);
          typeInfo = new TypeInfo(cls, typeDef);
          typeDefIdToTypeInfo.put(typeDefId, typeInfo);
        }
        metaContext.readTypeInfos.add(typeInfo);
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
  private static class ForyObjectOutputStream extends ObjectOutputStream {
    private final Fory fory;
    private final boolean compressInt;
    private final SlotInfo slotsInfo;
    private MemoryBuffer buffer;
    private Object targetObject;
    private boolean fieldsWritten;

    protected ForyObjectOutputStream(SlotInfo slotsInfo) throws IOException {
      super();
      this.slotsInfo = slotsInfo;
      this.fory = slotsInfo.getSlotsSerializer().fory;
      this.compressInt = fory.compressInt();
    }

    @Override
    protected final void writeObjectOverride(Object obj) throws IOException {
      fory.writeRef(buffer, obj);
    }

    @Override
    public void writeUnshared(Object obj) throws IOException {
      fory.writeNonRef(buffer, obj);
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

      @Deprecated
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
      slotsInfo.getSlotsSerializer().writeFieldValues(buffer, curPut.vals);
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
        fory.writeInt64(buffer, value == null ? 0L : (Long) value);
      } else if (fieldType == float.class) {
        buffer.writeFloat32(value == null ? 0f : (Float) value);
      } else if (fieldType == double.class) {
        buffer.writeFloat64(value == null ? 0d : (Double) value);
      } else {
        // Object reference
        fory.writeRef(buffer, value);
      }
    }

    @Override
    public void defaultWriteObject() throws IOException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      // Write fields using MetaShare serialization (layer meta already written by write())
      slotsInfo.getSlotsSerializer().writeFieldsOnly(buffer, targetObject);
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
      fory.writeInt64(buffer, v);
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
      fory.writeJavaString(buffer, s);
    }

    @Override
    public void writeUTF(String s) throws IOException {
      Preconditions.checkNotNull(s);
      fory.writeJavaString(buffer, s);
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
  private static class ForyObjectInputStream extends ObjectInputStream {
    private final Fory fory;
    private final boolean compressInt;
    private final SlotInfo slotsInfo;
    private MemoryBuffer buffer;
    private Object targetObject;
    private GetFieldImpl getField;
    private boolean fieldsRead;
    private TreeMap<Integer, ObjectInputValidation> callbacks;

    protected ForyObjectInputStream(SlotInfo slotsInfo) throws IOException {
      this.fory = slotsInfo.getSlotsSerializer().fory;
      this.compressInt = fory.compressInt();
      this.slotsInfo = slotsInfo;
    }

    @Override
    protected Object readObjectOverride() {
      return fory.readRef(buffer);
    }

    @Override
    public Object readUnshared() {
      return fory.readNonRef(buffer);
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
      // Read field values using MetaShare serialization
      Object[] vals = slotsInfo.getCurrentReadSerializer().readFieldValues(buffer);
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
        return fory.readInt64(buffer);
      } else if (fieldType == float.class) {
        return buffer.readFloat32();
      } else if (fieldType == double.class) {
        return buffer.readFloat64();
      } else {
        // Object reference
        return fory.readRef(buffer);
      }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void defaultReadObject() throws IOException, ClassNotFoundException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      // Read fields using MetaShare serialization (layer meta already read by getReadSerializer())
      slotsInfo.getCurrentReadSerializer().readAndSetFields(buffer, targetObject);
      fieldsRead = true;
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
      return fory.readInt64(buffer);
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
      return fory.readJavaString(buffer);
    }

    @Override
    public void close() throws IOException {}
  }
}
