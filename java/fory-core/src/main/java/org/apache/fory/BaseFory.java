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
import java.nio.ByteBuffer;
import java.util.function.Function;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;
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
   * Register class with a name used for cross-language serialization. Names with `.` are split by
   * the last `.` into namespace and type name.
   */
  void register(Class<?> cls, String name);

  /**
   * register class with given type namespace and name. This can be used mapping different classes
   * into same type when deserializing. The type name must not contain `.`.
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
   * Register class with a name used for cross-language serialization. Names with `.` are split by
   * the last `.` into namespace and type name.
   */
  void register(String className, String name);

  /**
   * register class with given type namespace and name. This can be used mapping different classes
   * into same type when deserializing. The type name must not contain `.`.
   */
  void register(String className, String namespace, String typeName);

  /**
   * Register a runtime module. Direct {@link Fory} instances install the module immediately;
   * thread-safe runtimes install it into every underlying runtime instance.
   *
   * <p>For thread-safe runtimes, call this during setup before concurrent serialization,
   * deserialization, or copy operations start.
   */
  void register(ForyModule module);

  void registerUnion(Class<?> cls, int id, Serializer<?> serializer);

  /**
   * Register a union with a name used for cross-language serialization. Names with `.` are split by
   * the last `.` into namespace and type name.
   */
  void registerUnion(Class<?> cls, String name, Serializer<?> serializer);

  /** Register a union with explicit namespace and type name. The type name must not contain `.`. */
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
   * @param serializerCreator serializer creator with param {@link TypeResolver}
   */
  void registerSerializer(Class<?> type, Function<TypeResolver, Serializer<?>> serializerCreator);

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
   * @param serializerCreator serializer creator with param {@link TypeResolver}
   */
  void registerSerializerAndType(
      Class<?> type, Function<TypeResolver, Serializer<?>> serializerCreator);

  /**
   * Registers an additional serializer factory.
   *
   * <p>Factories are queried in registration order. This method does not replace previously
   * registered factories and does not affect serializers that have already been resolved and
   * cached.
   *
   * <p>For thread-safe runtimes, call this during setup before concurrent serialization,
   * deserialization, or copy operations start.
   */
  void registerSerializerFactory(SerializerFactory serializerFactory);

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

  /** Serialize data into buffer. */
  MemoryBuffer serialize(MemoryBuffer buffer, Object obj);

  /** Serialize <code>obj</code> to a <code>buffer</code>. */
  MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback);

  void serialize(OutputStream outputStream, Object obj);

  void serialize(OutputStream outputStream, Object obj, BufferCallback callback);

  /** Deserialize <code>obj</code> from a byte array. */
  Object deserialize(byte[] bytes);

  /**
   * Deserialize <code>obj</code> from {@code byteBuffer.position()} to {@code byteBuffer.limit()}
   * without changing the caller buffer position or limit. On Android, heap, direct, and readonly
   * buffers are copied into Fory-owned heap memory before deserialization.
   */
  Object deserialize(ByteBuffer byteBuffer);

  <T> T deserialize(byte[] bytes, Class<T> type);

  <T> T deserialize(MemoryBuffer buffer, Class<T> type);

  <T> T deserialize(ForyInputStream inputStream, Class<T> type);

  <T> T deserialize(ForyReadableChannel channel, Class<T> type);

  Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers);

  /** Deserialize <code>obj</code> from a <code>buffer</code>. */
  Object deserialize(MemoryBuffer buffer);

  Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers);

  Object deserialize(ForyInputStream inputStream);

  Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers);

  Object deserialize(ForyReadableChannel channel);

  Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers);

  /** Deep copy the <code>obj</code>. */
  <T> T copy(T obj);
}
