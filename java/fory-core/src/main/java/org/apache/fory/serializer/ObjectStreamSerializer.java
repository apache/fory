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

import static org.apache.fory.resolver.ClassResolver.NO_CLASS_ID;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.collection.ObjectIntMap;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ObjectCreator;
import org.apache.fory.reflect.ObjectCreators;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.FieldResolver;
import org.apache.fory.resolver.FieldResolver.ClassField;
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

  /**
   * Interface for slot information used in ObjectStreamSerializer. This allows both full SlotsInfo
   * and minimal MinimalSlotsInfo implementations.
   */
  /**
   * Interface for slot information used in ObjectStreamSerializer. This allows both full SlotsInfo
   * and minimal MinimalSlotsInfo implementations.
   */
  private interface SlotInfo {
    Class<?> getCls();

    StreamClassInfo getStreamClassInfo();

    CompatibleSerializerBase getSlotsSerializer();

    ForyObjectOutputStream getObjectOutputStream();

    ForyObjectInputStream getObjectInputStream();

    ObjectArray getFieldPool();

    ObjectIntMap<String> getFieldIndexMap();

    FieldResolver getPutFieldsResolver();

    CompatibleSerializer getCompatibleStreamSerializer();
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
    LOG.warn(
        "{} customized jdk serialization, which is inefficient. "
            + "Please replace it with a {} or implements {}",
        type,
        Serializer.class.getName(),
        Externalizable.class.getName());
    // stream serializer may be data serializer of ReplaceResolver serializer.
    fory.getClassResolver().setSerializerIfAbsent(type, this);
    List<SlotInfo> slotsInfoList = new ArrayList<>();
    Class<?> end = type;
    // locate closest non-serializable superclass
    while (end != null && Serializable.class.isAssignableFrom(end)) {
      end = end.getSuperclass();
    }
    while (type != end) {
      try {
        slotsInfoList.add(new SlotsInfo(fory, type));
      } catch (Exception e) {
        if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
          LOG.warn(
              "Failed to create SlotsInfo for {} in GraalVM native image, "
                  + "using minimal serialization support: {}",
              type.getName(),
              e.getMessage());
          // Create a minimal SlotsInfo that can work with Unsafe
          slotsInfoList.add(new MinimalSlotsInfo(fory, type));
        } else {
          throw e;
        }
      }
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
        StreamClassInfo streamClassInfo = slotsInfo.getStreamClassInfo();
        Method writeObjectMethod = streamClassInfo.writeObjectMethod;
        if (writeObjectMethod == null) {
          slotsInfo.getSlotsSerializer().write(buffer, value);
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
            if (streamClassInfo.writeObjectFunc != null) {
              streamClassInfo.writeObjectFunc.accept(value, objectOutputStream);
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
        SlotInfo slotsInfo = slotsInfos[slotIndex++];
        StreamClassInfo streamClassInfo = slotsInfo.getStreamClassInfo();
        while (currentClass != slotsInfo.getCls()) {
          // the receiver's version extends classes that are not extended by the sender's version.
          Method readObjectNoData = streamClassInfo.readObjectNoData;
          if (readObjectNoData != null) {
            if (streamClassInfo.readObjectNoDataFunc != null) {
              streamClassInfo.readObjectNoDataFunc.accept(obj);
            } else {
              readObjectNoData.invoke(obj);
            }
          }
          slotsInfo = slotsInfos[slotIndex++];
        }
        Method readObjectMethod = streamClassInfo.readObjectMethod;
        if (readObjectMethod == null) {
          slotsInfo.getSlotsSerializer().readAndSetFields(buffer, obj);
        } else {
          ForyObjectInputStream objectInputStream = slotsInfo.getObjectInputStream();
          MemoryBuffer oldBuffer = objectInputStream.buffer;
          Object oldObject = objectInputStream.targetObject;
          ForyObjectInputStream.GetFieldImpl oldGetField = objectInputStream.getField;
          ForyObjectInputStream.GetFieldImpl getField =
              (ForyObjectInputStream.GetFieldImpl) slotsInfo.getFieldPool().popOrNull();
          if (getField == null) {
            getField = new ForyObjectInputStream.GetFieldImpl(slotsInfo);
          }
          boolean fieldsRead = objectInputStream.fieldsRead;
          try {
            objectInputStream.fieldsRead = false;
            objectInputStream.buffer = buffer;
            objectInputStream.targetObject = obj;
            objectInputStream.getField = getField;
            objectInputStream.callbacks = callbacks;
            if (streamClassInfo.readObjectFunc != null) {
              streamClassInfo.readObjectFunc.accept(obj, objectInputStream);
            } else {
              readObjectMethod.invoke(obj, objectInputStream);
            }
          } finally {
            objectInputStream.fieldsRead = fieldsRead;
            objectInputStream.buffer = oldBuffer;
            objectInputStream.targetObject = oldObject;
            objectInputStream.getField = oldGetField;
            slotsInfo.getFieldPool().add(getField);
            objectInputStream.callbacks = null;
            Arrays.fill(getField.vals, ForyObjectInputStream.NO_VALUE_STUB);
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
   * Information about a class's stream methods (writeObject, readObject, readObjectNoData) and
   * their optimized MethodHandle equivalents for fast invocation.
   */
  private static class StreamClassInfo {
    private final Method writeObjectMethod;
    private final Method readObjectMethod;
    private final Method readObjectNoData;
    private final BiConsumer writeObjectFunc;
    private final BiConsumer readObjectFunc;
    private final Consumer readObjectNoDataFunc;

    private StreamClassInfo(Class<?> type) {
      // ObjectStreamClass.lookup has cache inside, invocation cost won't be big.
      ObjectStreamClass objectStreamClass = safeObjectStreamClassLookup(type);
      // In JDK17, set private jdk method accessible will fail by default, use ObjectStreamClass
      // instead, since it set accessible.
      writeObjectMethod =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "writeObjectMethod");
      readObjectMethod =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectMethod");
      readObjectNoData =
          (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readObjectNoData");
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

  private static final ClassValue<StreamClassInfo> STREAM_CLASS_INFO_CACHE =
      new ClassValue<StreamClassInfo>() {
        @Override
        protected StreamClassInfo computeValue(Class<?> type) {
          return new StreamClassInfo(type);
        }
      };

  /**
   * Full implementation of SlotInfo for handling object stream serialization. This class manages
   * all the details of serializing and deserializing a single class in the class hierarchy using
   * Java's ObjectInputStream/ObjectOutputStream protocol.
   */
  private static class SlotsInfo implements SlotInfo {
    private final Class<?> cls;
    private final ClassInfo classInfo;
    private final StreamClassInfo streamClassInfo;
    // mark non-final for async-jit to update it to jit-serializer.
    private CompatibleSerializerBase slotsSerializer;
    private final ObjectIntMap<String> fieldIndexMap;
    private final FieldResolver putFieldsResolver;
    private final CompatibleSerializer compatibleStreamSerializer;
    private final ForyObjectOutputStream objectOutputStream;
    private final ForyObjectInputStream objectInputStream;
    private final ObjectArray getFieldPool;

    public SlotsInfo(Fory fory, Class<?> type) {
      this.cls = type;
      classInfo = fory.getClassResolver().newClassInfo(type, null, NO_CLASS_ID);
      ObjectStreamClass objectStreamClass = safeObjectStreamClassLookup(type);
      streamClassInfo = STREAM_CLASS_INFO_CACHE.get(type);
      // `putFields/writeFields` will convert to fields value to be written by
      // `CompatibleSerializer`,
      // since `put` values may not exist in current class, which means container generic type are
      // unavailable.
      // (`serialPersistentFields` field can't provide generics.)
      // So we need to mark all container fields type to `Object` to avoid reader deserialize data
      // using field generic types.
      Class<? extends Serializer> sc = CompatibleSerializer.class;
      FieldResolver fieldResolver = FieldResolver.of(fory, type, false, true);
      if (fory.getConfig().isCodeGenEnabled()
          && CodegenSerializer.supportCodegenForJavaSerialization(cls)) {
        sc =
            fory.getJITContext()
                .registerSerializerJITCallback(
                    () -> CompatibleSerializer.class,
                    () ->
                        CodecUtils.loadOrGenCompatibleCodecClass(
                            cls,
                            fory,
                            fieldResolver,
                            Generated.GeneratedCompatibleSerializer.class),
                    c ->
                        this.slotsSerializer =
                            (CompatibleSerializerBase) Serializers.newSerializer(fory, type, c));
      }
      if (GraalvmSupport.isGraalBuildtime()) {
        // trigger serializer constructor method handle generate.
        Serializers.newSerializer(fory, type, sc);
      }
      if (sc == CompatibleSerializer.class || GraalvmSupport.isGraalBuildtime()) {
        // skip init generated serializer at graalvm build time
        this.slotsSerializer = new CompatibleSerializer(fory, type, fieldResolver);
      } else {
        this.slotsSerializer = (CompatibleSerializerBase) Serializers.newSerializer(fory, type, sc);
      }
      fieldIndexMap = new ObjectIntMap<>(4, 0.4f);
      List<ClassField> allFields = new ArrayList<>();
      if (objectStreamClass != null) {
        for (ObjectStreamField serialField : objectStreamClass.getFields()) {
          allFields.add(new ClassField(serialField.getName(), serialField.getType(), cls));
        }
      } else {
        // Fallback to field resolver when ObjectStreamClass is not available in GraalVM
        for (FieldResolver.FieldInfo fieldInfo : fieldResolver.getAllFieldsList()) {
          allFields.add(new ClassField(fieldInfo.getName(), fieldInfo.getField().getType(), cls));
        }
      }
      if (streamClassInfo.writeObjectMethod != null || streamClassInfo.readObjectMethod != null) {
        putFieldsResolver = new FieldResolver(fory, cls, true, allFields, new HashSet<>());
        AtomicInteger idx = new AtomicInteger(0);
        for (FieldResolver.FieldInfo fieldInfo : putFieldsResolver.getAllFieldsList()) {
          fieldIndexMap.put(fieldInfo.getName(), idx.getAndIncrement());
        }
        compatibleStreamSerializer = new CompatibleSerializer(fory, cls, putFieldsResolver);
      } else {
        putFieldsResolver = null;
        compatibleStreamSerializer = null;
      }
      if (streamClassInfo.writeObjectMethod != null) {
        try {
          objectOutputStream = new ForyObjectOutputStream(this);
        } catch (IOException e) {
          Platform.throwException(e);
          throw new IllegalStateException("unreachable");
        }
      } else {
        objectOutputStream = null;
      }
      if (streamClassInfo.readObjectMethod != null) {
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
    public StreamClassInfo getStreamClassInfo() {
      return streamClassInfo;
    }

    @Override
    public CompatibleSerializerBase getSlotsSerializer() {
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
    public FieldResolver getPutFieldsResolver() {
      return putFieldsResolver;
    }

    @Override
    public CompatibleSerializer getCompatibleStreamSerializer() {
      return compatibleStreamSerializer;
    }

    @Override
    public String toString() {
      return "SlotsInfo{" + "cls=" + cls + '}';
    }
  }

  /**
   * Minimal SlotsInfo implementation for GraalVM native image when ObjectStreamClass.lookup fails.
   * This provides basic serialization support using Unsafe-based object creation.
   */
  private static class MinimalSlotsInfo implements SlotInfo {
    private final Class<?> cls;
    private final ClassInfo classInfo;
    private final StreamClassInfo streamClassInfo;
    private CompatibleSerializerBase slotsSerializer;
    private final ObjectIntMap<String> fieldIndexMap;
    private final FieldResolver putFieldsResolver;
    private final CompatibleSerializer compatibleStreamSerializer;
    private final ForyObjectOutputStream objectOutputStream;
    private final ForyObjectInputStream objectInputStream;
    private final ObjectArray getFieldPool;

    public MinimalSlotsInfo(Fory fory, Class<?> type) {
      // Initialize with minimal required fields
      this.cls = type;
      this.classInfo = fory.getClassResolver().newClassInfo(type, null, NO_CLASS_ID);
      this.streamClassInfo = null; // Skip problematic ObjectStreamClass lookup

      // Create a basic CompatibleSerializer for field handling
      FieldResolver fieldResolver = FieldResolver.of(fory, type, false, true);
      this.slotsSerializer = new CompatibleSerializer(fory, type, fieldResolver);

      // Initialize other fields with safe defaults
      this.fieldIndexMap = new ObjectIntMap<>(4, 0.4f);
      this.putFieldsResolver = null;
      this.compatibleStreamSerializer = null;
      this.objectOutputStream = null;
      this.objectInputStream = null;
      this.getFieldPool = new ObjectArray();
    }

    @Override
    public Class<?> getCls() {
      return cls;
    }

    @Override
    public StreamClassInfo getStreamClassInfo() {
      return streamClassInfo;
    }

    @Override
    public CompatibleSerializerBase getSlotsSerializer() {
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
    public FieldResolver getPutFieldsResolver() {
      return putFieldsResolver;
    }

    @Override
    public CompatibleSerializer getCompatibleStreamSerializer() {
      return compatibleStreamSerializer;
    }

    @Override
    public String toString() {
      return "MinimalSlotsInfo{" + "cls=" + cls + '}';
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
     * @see ConcurrentHashMap
     */
    private class PutFieldImpl extends PutField {
      private final Object[] vals;

      PutFieldImpl() {
        vals = new Object[slotsInfo.getPutFieldsResolver().getNumFields()];
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
      slotsInfo.getCompatibleStreamSerializer().writeFieldsValues(buffer, curPut.vals);
      Arrays.fill(curPut.vals, null);
      putFieldsCache.add(curPut);
      this.curPut = null;
      fieldsWritten = true;
    }

    @Override
    public void defaultWriteObject() throws IOException, NotActiveException {
      if (fieldsWritten) {
        throw new NotActiveException("not in writeObject invocation or fields already written");
      }
      slotsInfo.getSlotsSerializer().write(buffer, targetObject);
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
        vals = new Object[slotsInfo.getPutFieldsResolver().getNumFields()];
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
      slotsInfo.getCompatibleStreamSerializer().readFields(buffer, getField.vals);
      fieldsRead = true;
      return getField;
    }

    @Override
    public void defaultReadObject() throws IOException, ClassNotFoundException {
      if (fieldsRead) {
        throw new NotActiveException("not in readObject invocation or fields already read");
      }
      slotsInfo.getSlotsSerializer().readAndSetFields(buffer, targetObject);
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
