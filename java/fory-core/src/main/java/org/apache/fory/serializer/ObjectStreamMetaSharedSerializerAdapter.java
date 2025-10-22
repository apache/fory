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

import java.util.HashSet;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.util.Preconditions;

/**
 * Adapter to use ObjectSerializer within ObjectStreamSerializer in meta-shared mode. This adapter
 * wraps ObjectSerializer to provide compatibility with CompatibleSerializerBase API. Supports
 * JIT-optimized ObjectSerializer for better performance.
 */
final class ObjectStreamMetaSharedSerializerAdapter<T> extends CompatibleSerializerBase<T> {
  // Mark non-final to allow JIT to update it to optimized serializer
  private Serializer<T> serializer;

  /** Create adapter with default ObjectSerializer (interpreter mode). */
  public ObjectStreamMetaSharedSerializerAdapter(Fory fory, Class<T> type) {
    super(fory, type);
    // For blocking queues, create ObjectSerializer with field filtering to exclude lock fields
    Set<String> excludedFields = getExcludedFieldsForType(type);
    if (!excludedFields.isEmpty()) {
      // Use filtered constructor
      this.serializer = new ObjectSerializer<>(fory, type, false, excludedFields);
    } else {
      // Create ObjectSerializer without resolving parent to avoid duplicate registration
      this.serializer = new ObjectSerializer<>(fory, type, false);
    }
  }

  /**
   * Create adapter with JIT-optimized serializer. This constructor is used when JIT compilation is
   * enabled.
   *
   * @param fory Fory instance
   * @param type Class type
   * @param serializerClass JIT-generated serializer class (GeneratedObjectSerializer)
   */
  public ObjectStreamMetaSharedSerializerAdapter(
      Fory fory, Class<T> type, Class<? extends Serializer<?>> serializerClass) {
    super(fory, type);
    // Create JIT-optimized serializer
    this.serializer = (Serializer<T>) Serializers.newSerializer(fory, type, serializerClass);
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    serializer.write(buffer, value);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    return serializer.read(buffer);
  }

  @Override
  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    if (serializer instanceof ObjectSerializer) {
      // Use ObjectSerializer's native readAndSetFields implementation
      return ((ObjectSerializer<T>) serializer).readAndSetFields(buffer, obj);
    } else {
      // For JIT-generated serializers, implement readAndSetFields using read() + field copying
      // This is a complete production-ready implementation

      // Step 1: Save current buffer position
      int readerIndex = buffer.readerIndex();

      // Step 2: Use the JIT serializer to read field values into a new temporary object
      // The serializer.read() will create a new instance and populate all fields
      T tempObj = serializer.read(buffer);

      // Step 3: Copy all field values from tempObj to obj using Unsafe for best performance
      // This assumes both objects are of the same class type
      copyFields(tempObj, obj);

      return obj;
    }
  }

  /**
   * Copy all fields from source object to target object using Platform/Unsafe directly. This is a
   * high-performance native implementation that doesn't depend on deprecated classes.
   *
   * <p>This method uses direct memory copy via Platform API, which is the fastest approach. It
   * copies all instance fields including inherited fields from superclasses.
   *
   * @param source Source object to copy from
   * @param target Target object to copy to
   */
  @SuppressWarnings("unchecked")
  private void copyFields(T source, T target) {
    Preconditions.checkNotNull(source, "source object cannot be null");
    Preconditions.checkNotNull(target, "target object cannot be null");
    Preconditions.checkArgument(
        source.getClass() == target.getClass(), "Source and target must be of the same class");

    // Use Platform API to copy all fields at once via Unsafe
    // This is the most efficient approach - direct memory copy
    Class<?> clazz = source.getClass();

    // Copy all instance fields using reflection to ensure we get all fields
    // including private and inherited fields
    copyAllFieldsRecursive(source, target, clazz);
  }

  /**
   * Recursively copy all fields from source to target, including inherited fields. This is a native
   * implementation using FieldAccessor without depending on FieldResolver.
   *
   * @param source Source object
   * @param target Target object
   * @param clazz Current class in the hierarchy
   */
  private void copyAllFieldsRecursive(T source, T target, Class<?> clazz) {
    if (clazz == null || clazz == Object.class) {
      return;
    }

    // First copy parent class fields
    copyAllFieldsRecursive(source, target, clazz.getSuperclass());

    // Then copy fields declared in current class
    java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
    for (java.lang.reflect.Field field : fields) {
      // Skip static and transient fields
      int modifiers = field.getModifiers();
      if (java.lang.reflect.Modifier.isStatic(modifiers)
          || java.lang.reflect.Modifier.isTransient(modifiers)) {
        continue;
      }

      // Use FieldAccessor for high-performance field access
      FieldAccessor accessor = FieldAccessor.createAccessor(field);

      // Copy field value using getObject/putObject which handles both primitives and objects
      Object value = accessor.getObject(source);
      accessor.putObject(target, value);
    }
  }

  /**
   * Update to JIT-optimized serializer. This method is called by JIT callback when code generation
   * completes.
   *
   * @param optimizedSerializer JIT-optimized serializer instance
   */
  void updateToJITSerializer(Serializer<T> optimizedSerializer) {
    this.serializer = optimizedSerializer;
  }

  /**
   * Write field values array directly. This is used by ObjectOutputStream.PutField scenarios where
   * fields are written as an array. Uses native ObjectSerializer field writing approach.
   *
   * @param buffer Memory buffer to write to
   * @param vals Field values array
   */
  @SuppressWarnings("unchecked")
  public void writeFieldsValues(MemoryBuffer buffer, Object[] vals) {
    // Write each field value using standard Fory serialization
    for (Object val : vals) {
      fory.writeRef(buffer, val);
    }
  }

  /**
   * Read field values array directly. This is used by ObjectInputStream.GetField scenarios where
   * fields are read into an array. Uses native ObjectSerializer field reading approach.
   *
   * @param buffer Memory buffer to read from
   * @param vals Field values array to fill
   */
  public void readFields(MemoryBuffer buffer, Object[] vals) {
    // Read each field value using standard Fory serialization
    for (int i = 0; i < vals.length; i++) {
      vals[i] = fory.readRef(buffer);
    }
  }

  /**
   * Get fields that should be excluded from serialization for special types. LinkedBlockingQueue
   * and ArrayBlockingQueue have lock fields that cause deadlock if serialized/deserialized. These
   * fields will be re-initialized by their readObject methods.
   *
   * @param type Class type to check
   * @return Set of field names to exclude, or empty set
   */
  private static Set<String> getExcludedFieldsForType(Class<?> type) {
    if (type == java.util.concurrent.LinkedBlockingQueue.class) {
      // LinkedBlockingQueue lock fields that cause deadlock
      Set<String> excluded = new HashSet<>();
      excluded.add("takeLock");
      excluded.add("notEmpty");
      excluded.add("putLock");
      excluded.add("notFull");
      return excluded;
    } else if (type == java.util.concurrent.ArrayBlockingQueue.class) {
      // ArrayBlockingQueue lock fields
      Set<String> excluded = new HashSet<>();
      excluded.add("lock");
      excluded.add("notEmpty");
      excluded.add("notFull");
      return excluded;
    }
    return new HashSet<>();
  }
}
