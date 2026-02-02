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

package org.apache.fory;

import java.io.OutputStream;
import java.util.function.Function;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.Serializers;

/** All Fory’s basic interface, including Fory’s basic methods. */
public interface BaseFory {

  /**
   * Register class and allocate an auto-grown ID for this class.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param cls class to register.
   */
  void register(Class<?> cls);

  /**
   * register class with given id.
   *
   * <p><b>NOTE</b>: The registration id is important. If registration id is inconsistent, and the
   * deserialization will failed !!!
   */
  void register(Class<?> cls, int id);

  /**
   * Register class and allocate an auto-grown ID for this class.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param cls class to register.
   * @param createSerializer whether to create serializer, if true and codegen enabled, this will
   *     generate the serializer code too.
   */
  void register(Class<?> cls, boolean createSerializer);

  /**
   * Register class with specified id. This method has been deprecated, please use {@link
   * #register(Class, int)} instead, and invoke {@link #ensureSerializersCompiled} after all classes
   * has been registered.
   *
   * @param cls class to register.
   * @param id id for provided class.
   * @param createSerializer whether to create serializer, if true and codegen enabled, this will
   *     generate the serializer code too. this parameter has no effect anymore on whether to
   *     generate code, please use {@link #ensureSerializersCompiled} to trigger code generation
   */
  @Deprecated
  void register(Class<?> cls, int id, boolean createSerializer);

  /** register class with given type name which will be used for cross-language serialization. */
  void register(Class<?> cls, String typeName);

  /**
   * register class with given type namespace and name. This can be used mapping different classes
   * into same type when deserializing.
   */
  void register(Class<?> cls, String namespace, String typeName);

  /**
   * Register class and allocate an auto-grown ID for this class.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param className full class name to register.
   */
  void register(String className);

  /** register class with given id. */
  void register(String className, int classId);

  /**
   * register class with given type namespace and name. This can be used mapping different classes
   * into same type when deserializing.
   */
  void register(String className, String namespace, String typeName);

  void registerUnion(Class<?> cls, int id, Serializer<?> serializer);

  void registerUnion(Class<?> cls, String namespace, String typeName, Serializer<?> serializer);

  /**
   * Register a Serializer for a class, and allocate an auto-grown ID for this class if it's not
   * registered yet.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param type class needed to be serialized/deserialized.
   * @param serializerClass serializer class can be created with {@link Serializers#newSerializer}.
   * @param <T> type of class.
   */
  <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass);

  /**
   * Register a Serializer for a class, and allocate an auto-grown ID for this class if it's not
   * registered yet.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   */
  void registerSerializer(Class<?> type, Serializer<?> serializer);

  /**
   * Register a Serializer created by serializerCreator when fory created. And allocate an
   * auto-grown ID for this class if it's not registered yet.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param type class needed to be serialized/deserialized.
   * @param serializerCreator serializer creator with param {@link Fory}
   */
  void registerSerializer(Class<?> type, Function<Fory, Serializer<?>> serializerCreator);

  /**
   * Register a class (if not already registered) and then register its serializer class.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param type class needed to be serialized/deserialized.
   * @param serializerClass serializer class can be created with {@link Serializers#newSerializer}.
   * @param <T> type of class.
   */
  <T> void registerSerializerAndType(Class<T> type, Class<? extends Serializer> serializerClass);

  /**
   * Register a class (if not already registered) and then register its serializer instance.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   */
  void registerSerializerAndType(Class<?> type, Serializer<?> serializer);

  /**
   * Register a class (if not already registered) and then register a serializer created by
   * serializerCreator when fory created.
   *
   * <p><b>NOTE</b>: The registration order is important. If registration order is inconsistent, the
   * allocated ID will be different, and the deserialization will failed !!!
   *
   * @param type class needed to be serialized/deserialized.
   * @param serializerCreator serializer creator with param {@link Fory}
   */
  void registerSerializerAndType(Class<?> type, Function<Fory, Serializer<?>> serializerCreator);

  void setSerializerFactory(SerializerFactory serializerFactory);

  /**
   * Ensure all compilation for serializers and accessors even for lazy initialized serializers.
   * This method will block until all compilation is done.
   *
   * <p>This method is mainly used for graalvm native image build time or trigger compilation ahead
   * for online service ahead to avoid cold start.
   *
   * <p>Note that this method should be invoked after all registrations and invoked only once.
   * Repeated invocations will have no effect.
   */
  void ensureSerializersCompiled();

  /** Return serialized <code>obj</code> as a byte array. */
  byte[] serialize(Object obj);

  /** Return serialized <code>obj</code> as a byte array. */
  byte[] serialize(Object obj, BufferCallback callback);

  /**
   * Serialize <code>obj</code> to a off-heap buffer specified by <code>address</code> and <code>
   * size</code>.
   */
  MemoryBuffer serialize(Object obj, long address, int size);

  /** Serialize data into buffer. */
  MemoryBuffer serialize(MemoryBuffer buffer, Object obj);

  /** Serialize <code>obj</code> to a <code>buffer</code>. */
  MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback);

  void serialize(OutputStream outputStream, Object obj);

  void serialize(OutputStream outputStream, Object obj, BufferCallback callback);

  /** Deserialize <code>obj</code> from a byte array. */
  Object deserialize(byte[] bytes);

  <T> T deserialize(byte[] bytes, Class<T> type);

  Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers);

  /**
   * Deserialize <code>obj</code> from a off-heap buffer specified by <code>address</code> and
   * <code>size</code>.
   */
  Object deserialize(long address, int size);

  /** Deserialize <code>obj</code> from a <code>buffer</code>. */
  Object deserialize(MemoryBuffer buffer);

  Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers);

  Object deserialize(ForyInputStream inputStream);

  Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers);

  Object deserialize(ForyReadableChannel channel);

  Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers);

  /**
   * Serialize java object without class info, deserialization should use {@link
   * #deserializeJavaObject}.
   */
  byte[] serializeJavaObject(Object obj);

  /**
   * Serialize java object without class info, deserialization should use {@link
   * #deserializeJavaObject}.
   */
  void serializeJavaObject(MemoryBuffer buffer, Object obj);

  void serializeJavaObject(OutputStream outputStream, Object obj);

  /**
   * Deserialize java object from binary without class info, serialization should use {@link
   * #serializeJavaObject}.
   */
  <T> T deserializeJavaObject(byte[] data, Class<T> cls);

  /**
   * Deserialize java object from binary by passing class info, serialization should use {@link
   * #serializeJavaObject}.
   */
  <T> T deserializeJavaObject(MemoryBuffer buffer, Class<T> cls);

  <T> T deserializeJavaObject(ForyInputStream inputStream, Class<T> cls);

  <T> T deserializeJavaObject(ForyReadableChannel channel, Class<T> cls);

  /** This method is deprecated, please use {@link #serialize} instead. */
  @Deprecated
  byte[] serializeJavaObjectAndClass(Object obj);

  /** This method is deprecated, please use {@link #serialize} instead. */
  @Deprecated
  void serializeJavaObjectAndClass(MemoryBuffer buffer, Object obj);

  /** This method is deprecated, please use {@link #serialize} instead. */
  @Deprecated
  void serializeJavaObjectAndClass(OutputStream outputStream, Object obj);

  /** This method is deprecated, please use {@link #deserialize} instead. */
  @Deprecated
  Object deserializeJavaObjectAndClass(byte[] data);

  /** This method is deprecated, please use {@link #deserialize} instead. */
  @Deprecated
  Object deserializeJavaObjectAndClass(MemoryBuffer buffer);

  /** This method is deprecated, please use {@link #deserialize} instead. */
  @Deprecated
  Object deserializeJavaObjectAndClass(ForyInputStream inputStream);

  /** This method is deprecated, please use {@link #deserialize} instead. */
  @Deprecated
  Object deserializeJavaObjectAndClass(ForyReadableChannel channel);

  /** Deep copy the <code>obj</code>. */
  <T> T copy(T obj);
}
