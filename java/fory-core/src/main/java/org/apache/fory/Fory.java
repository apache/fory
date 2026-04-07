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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.JITContext;
import org.apache.fory.config.Config;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.MapRefReader;
import org.apache.fory.context.MapRefWriter;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaStringReader;
import org.apache.fory.context.MetaStringWriter;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.RefReader;
import org.apache.fory.context.RefWriter;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.CopyException;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.ForyException;
import org.apache.fory.exception.SerializationException;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.type.Generics;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/**
 * Cross-language header layout: 1-byte bitmap.
 *
 * <p>Bit 0: null flag, Bit 1: xlang flag, Bit 2: out-of-band flag, Bits 3-7 reserved.
 *
 * <p>serialize/deserialize are the root object APIs. Nested serialization and deserialization go
 * through {@link WriteContext} and {@link ReadContext}.
 */
@NotThreadSafe
public final class Fory implements BaseFory {
  private static final Logger LOG = LoggerFactory.getLogger(Fory.class);

  public static final byte NULL_FLAG = -3;
  // This flag indicates that object is a not-null value.
  // We don't use another byte to indicate REF, so that we can save one byte.
  public static final byte REF_FLAG = -2;
  // this flag indicates that the object is a non-null value.
  public static final byte NOT_NULL_VALUE_FLAG = -1;
  // this flag indicates that the object is a referencable and first write.
  public static final byte REF_VALUE_FLAG = 0;
  public static final byte NOT_SUPPORT_XLANG = 0;
  private static final byte isNilFlag = 1;
  private static final byte isCrossLanguageFlag = 1 << 1;
  private static final byte isOutOfBandFlag = 1 << 2;

  private final Config config;
  private final TypeResolver typeResolver;
  private final SharedRegistry sharedRegistry;
  private final ClassLoader classLoader;
  private final JITContext jitContext;
  private final WriteContext writeContext;
  private final ReadContext readContext;
  private final CopyContext copyContext;
  private MemoryBuffer buffer;

  public Fory(ForyBuilder builder, ClassLoader classLoader) {
    this(builder, classLoader, null);
  }

  public Fory(ForyBuilder builder, ClassLoader classLoader, SharedRegistry sharedRegistry) {
    // Prefer the explicit constructor argument over retaining loader state on the builder used to
    // create thread-safe factories.
    if (sharedRegistry == null) {
      sharedRegistry = new SharedRegistry();
    }
    if (classLoader == null) {
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = Fory.class.getClassLoader();
      }
    }
    this.sharedRegistry = sharedRegistry;
    this.classLoader = classLoader;
    config = new Config(builder);
    RefWriter refWriter;
    RefReader refReader;
    if (config.trackingRef()) {
      refWriter = new MapRefWriter(config.mapRefLoadFactor());
      refReader = new MapRefReader();
    } else {
      refWriter = new RefWriter.NoRefWriter();
      refReader = new RefReader.NoRefReader();
    }
    jitContext = new JITContext(this);
    typeResolver =
        config.isXlang()
            ? new XtypeResolver(config, classLoader, sharedRegistry, jitContext)
            : new ClassResolver(config, classLoader, sharedRegistry, jitContext);
    typeResolver.initialize();
    MetaStringWriter metaStringWriter = new MetaStringWriter();
    MetaStringReader metaStringReader = new MetaStringReader(sharedRegistry);
    writeContext =
        new WriteContext(config, new Generics(), typeResolver, refWriter, metaStringWriter);
    readContext =
        new ReadContext(config, new Generics(), typeResolver, refReader, metaStringReader);
    copyContext = new CopyContext(typeResolver, config.copyRef());
    LOG.info("Created new fory {}", this);
  }

  @Override
  public void register(Class<?> cls) {
    getTypeResolver().register(cls);
  }

  @Override
  public void register(Class<?> cls, int id) {
    getTypeResolver().register(cls, Integer.toUnsignedLong(id));
  }

  /**
   * Register class with given type name, this method will have bigger serialization time/space cost
   * compared to register by id.
   */
  @Override
  public void register(Class<?> cls, String typeName) {
    int idx = typeName.lastIndexOf('.');
    String namespace = "";
    if (idx > 0) {
      namespace = typeName.substring(0, idx);
      typeName = typeName.substring(idx + 1);
    }
    register(cls, namespace, typeName);
  }

  public void register(Class<?> cls, String namespace, String typeName) {
    getTypeResolver().register(cls, namespace, typeName);
  }

  @Override
  public void register(String className) {
    getTypeResolver().register(className);
  }

  @Override
  public void register(String className, int classId) {
    getTypeResolver().register(className, Integer.toUnsignedLong(classId));
  }

  @Override
  public void register(String className, String namespace, String typeName) {
    getTypeResolver().register(className, namespace, typeName);
  }

  @Override
  public void registerUnion(Class<?> cls, int id, Serializer<?> serializer) {
    getTypeResolver().registerUnion(cls, Integer.toUnsignedLong(id), serializer);
  }

  @Override
  public void registerUnion(
      Class<?> cls, String namespace, String typeName, Serializer<?> serializer) {
    getTypeResolver().registerUnion(cls, namespace, typeName, serializer);
  }

  @Override
  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    getTypeResolver().registerSerializer(type, serializerClass);
  }

  @Override
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    getTypeResolver().registerSerializer(type, serializer);
  }

  @Override
  public void registerSerializer(
      Class<?> type, Function<TypeResolver, Serializer<?>> serializerCreator) {
    getTypeResolver().registerSerializer(type, serializerCreator.apply(typeResolver));
  }

  @Override
  public <T> void registerSerializerAndType(
      Class<T> type, Class<? extends Serializer> serializerClass) {
    getTypeResolver().registerSerializerAndType(type, serializerClass);
  }

  @Override
  public void registerSerializerAndType(Class<?> type, Serializer<?> serializer) {
    getTypeResolver().registerSerializerAndType(type, serializer);
  }

  @Override
  public void registerSerializerAndType(
      Class<?> type, Function<TypeResolver, Serializer<?>> serializerCreator) {
    getTypeResolver().registerSerializerAndType(type, serializerCreator.apply(typeResolver));
  }

  @Override
  public void setSerializerFactory(SerializerFactory serializerFactory) {
    typeResolver.setSerializerFactory(serializerFactory);
  }

  public SerializerFactory getSerializerFactory() {
    return typeResolver.getSerializerFactory();
  }

  public <T> Serializer<T> getSerializer(Class<T> cls) {
    Preconditions.checkNotNull(cls);
    return typeResolver.getSerializer(cls);
  }

  private void ensureRegistrationFinished() {
    if (!typeResolver.isRegistrationFinished()) {
      typeResolver.finishRegistration();
    }
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    MemoryBuffer buffer = MemoryUtils.buffer(address, size);
    serialize(buffer, obj, null);
    return buffer;
  }

  @Override
  public byte[] serialize(Object obj) {
    MemoryBuffer buf = getBuffer();
    buf.writerIndex(0);
    serialize(buf, obj, null);
    byte[] bytes = buf.getBytes(0, buf.writerIndex());
    resetBuffer();
    return bytes;
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    MemoryBuffer buf = getBuffer();
    buf.writerIndex(0);
    serialize(buf, obj, callback);
    byte[] bytes = buf.getBytes(0, buf.writerIndex());
    resetBuffer();
    return bytes;
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    return serialize(buffer, obj, null);
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    ensureRegistrationFinished();
    writeContext.prepare(buffer, callback);
    try {
      byte bitmap = 0;
      if (config.isXlang()) {
        bitmap |= isCrossLanguageFlag;
      }
      if (obj == null) {
        bitmap |= isNilFlag;
        buffer.writeByte(bitmap);
        return buffer;
      }
      if (callback != null) {
        bitmap |= isOutOfBandFlag;
      }
      buffer.writeByte(bitmap);
      try {
        jitContext.lock();
        if (writeContext.getDepth() > 0) {
          throwDepthSerializationException();
        }
        writeContext.writeRef(obj);
        return buffer;
      } catch (Throwable t) {
        throw processSerializationError(t);
      } finally {
        jitContext.unlock();
      }
    } finally {
      writeContext.reset();
    }
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    serializeToStream(outputStream, buf -> serialize(buf, obj, null));
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    serializeToStream(outputStream, buf -> serialize(buf, obj, callback));
  }

  private ForyException processSerializationError(Throwable e) {
    if (!config.trackingRef()) {
      String msg =
          "Object may contain circular references, please enable ref tracking "
              + "by `ForyBuilder#withRefTracking(true)`";
      String rawMessage = e.getMessage();
      if (StringUtils.isNotBlank(rawMessage)) {
        msg += ": " + rawMessage;
      }
      if (e instanceof StackOverflowError) {
        e = ExceptionUtils.trySetStackOverflowErrorMessage((StackOverflowError) e, msg);
      }
    }
    if (!(e instanceof ForyException)) {
      e = new SerializationException(e);
    }
    throw (ForyException) e;
  }

  private ForyException processCopyError(Throwable e) {
    if (!config.copyRef()) {
      String msg =
          "Object may contain circular references, please enable ref tracking "
              + "by `ForyBuilder#withRefCopy(true)`";
      e = ExceptionUtils.trySetStackOverflowErrorMessage((StackOverflowError) e, msg);
    }
    if (!(e instanceof ForyException)) {
      throw new CopyException(e);
    }
    throw (ForyException) e;
  }

  @Override
  public Object deserialize(byte[] bytes) {
    return deserialize(MemoryUtils.wrap(bytes), (Iterable<MemoryBuffer>) null);
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    return deserialize(MemoryUtils.wrap(bytes), type);
  }

  @Override
  public <T> T deserialize(MemoryBuffer buffer, Class<T> type) {
    ensureRegistrationFinished();
    byte bitmap = buffer.readByte();
    if ((bitmap & isNilFlag) == isNilFlag) {
      return null;
    }
    boolean peerOutOfBandEnabled = (bitmap & isOutOfBandFlag) == isOutOfBandFlag;
    assert !peerOutOfBandEnabled : "Out of band buffers not passed in when deserializing";
    checkXlangBitmap(bitmap);
    readContext.prepare(buffer, null, false);
    try {
      try {
        jitContext.lock();
        if (readContext.getDepth() > 0) {
          throwDepthDeserializationException();
        }
        return deserializeByType(buffer, type);
      } finally {
        jitContext.unlock();
      }
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(this, t);
    } finally {
      readContext.reset();
    }
  }

  @Override
  public <T> T deserialize(ForyInputStream inputStream, Class<T> type) {
    try {
      return deserialize(inputStream.getBuffer(), type);
    } finally {
      inputStream.shrinkBuffer();
    }
  }

  @Override
  public <T> T deserialize(ForyReadableChannel channel, Class<T> type) {
    return deserialize(channel.getBuffer(), type);
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    return deserialize(MemoryUtils.wrap(bytes), outOfBandBuffers);
  }

  @Override
  public Object deserialize(long address, int size) {
    return deserialize(MemoryUtils.buffer(address, size), (Iterable<MemoryBuffer>) null);
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    return deserialize(buffer, (Iterable<MemoryBuffer>) null);
  }

  /**
   * Deserialize <code>obj</code> from a <code>buffer</code> and <code>outOfBandBuffers</code>.
   *
   * @param buffer serialized data. If the provided buffer start address is aligned with 4 bytes,
   *     the bulk read will be more efficient.
   * @param outOfBandBuffers If <code>buffers</code> is not None, it should be an iterable of
   *     buffer-enabled objects that is consumed each time the pickle stream references an
   *     out-of-band {@link BufferObject}. Such buffers have been given in order to the
   *     `bufferCallback` of a Fory object. If <code>outOfBandBuffers</code> is null (the default),
   *     then the buffers are taken from the serialized stream, assuming they are serialized there.
   *     It is an error for <code>outOfBandBuffers</code> to be null if the serialized stream was
   *     produced with a non-null `bufferCallback`.
   */
  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    ensureRegistrationFinished();
    byte bitmap = buffer.readByte();
    if ((bitmap & isNilFlag) == isNilFlag) {
      return null;
    }
    checkXlangBitmap(bitmap);
    boolean peerOutOfBandEnabled = (bitmap & isOutOfBandFlag) == isOutOfBandFlag;
    if (peerOutOfBandEnabled) {
      Preconditions.checkNotNull(
          outOfBandBuffers,
          "outOfBandBuffers shouldn't be null when the serialized stream is "
              + "produced with bufferCallback not null.");
    } else {
      Preconditions.checkArgument(
          outOfBandBuffers == null,
          "outOfBandBuffers should be null when the serialized stream is "
              + "produced with bufferCallback null.");
    }
    readContext.prepare(
        buffer, peerOutOfBandEnabled ? outOfBandBuffers : null, peerOutOfBandEnabled);
    try {
      try {
        jitContext.lock();
        if (readContext.getDepth() > 0) {
          throwDepthDeserializationException();
        }
        return readContext.readRef();
      } finally {
        jitContext.unlock();
      }
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(this, t);
    } finally {
      readContext.reset();
    }
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    return deserialize(inputStream, (Iterable<MemoryBuffer>) null);
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    try {
      MemoryBuffer buf = inputStream.getBuffer();
      return deserialize(buf, outOfBandBuffers);
    } finally {
      inputStream.shrinkBuffer();
    }
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    return deserialize(channel, (Iterable<MemoryBuffer>) null);
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    MemoryBuffer buf = channel.getBuffer();
    return deserialize(buf, outOfBandBuffers);
  }

  @SuppressWarnings("unchecked")
  private <T> T deserializeByType(MemoryBuffer buffer, Class<T> type) {
    readContext
        .getGenerics()
        .pushGenericType(typeResolver.buildGenericType(type), readContext.getDepth());
    try {
      RefReader refReader = readContext.getRefReader();
      int nextReadRefId = refReader.tryPreserveRefId(buffer);
      if (nextReadRefId < NOT_NULL_VALUE_FLAG) {
        return (T) refReader.getReadRef();
      }
      TypeInfo typeInfo = typeResolver.readTypeInfo(readContext, type);
      Object value = readContext.readNonRef(typeInfo);
      refReader.setReadRef(nextReadRefId, value);
      return (T) value;
    } finally {
      readContext.getGenerics().popGenericType(readContext.getDepth());
    }
  }

  private void checkXlangBitmap(byte bitmap) {
    boolean payloadCrossLanguage = (bitmap & isCrossLanguageFlag) == isCrossLanguageFlag;
    Preconditions.checkArgument(
        payloadCrossLanguage == config.isXlang(),
        "Serialized payload xlang flag %s does not match this Fory mode %s",
        payloadCrossLanguage,
        config.isXlang());
  }

  @Override
  public <T> T copy(T obj) {
    ensureRegistrationFinished();
    try {
      return copyContext.copyObject(obj);
    } catch (Throwable e) {
      throw processCopyError(e);
    } finally {
      copyContext.reset();
    }
  }

  private void serializeToStream(OutputStream outputStream, Consumer<MemoryBuffer> function) {
    MemoryBuffer buf = getBuffer();
    if (outputStream.getClass() == ByteArrayOutputStream.class) {
      byte[] oldBytes = buf.getHeapMemory(); // Note: This should not be null.
      assert oldBytes != null;
      MemoryUtils.wrap((ByteArrayOutputStream) outputStream, buf);
      function.accept(buf);
      MemoryUtils.wrap(buf, (ByteArrayOutputStream) outputStream);
      buf.pointTo(oldBytes, 0, oldBytes.length);
      resetBuffer();
    } else {
      buf.writerIndex(0);
      function.accept(buf);
      try {
        byte[] bytes = buf.getHeapMemory();
        if (bytes != null) {
          outputStream.write(bytes, 0, buf.writerIndex());
        } else {
          outputStream.write(buf.getBytes(0, buf.writerIndex()));
        }
        outputStream.flush();
      } catch (IOException e) {
        throw new SerializationException(e);
      } finally {
        resetBuffer();
      }
    }
  }

  public MemoryBuffer getBuffer() {
    MemoryBuffer buf = buffer;
    if (buf == null) {
      buf = buffer = MemoryBuffer.newHeapBuffer(64);
    }
    return buf;
  }

  public void resetBuffer() {
    MemoryBuffer buf = buffer;
    if (buf != null && buf.size() > config.bufferSizeLimitBytes()) {
      buffer = MemoryBuffer.newHeapBuffer(config.bufferSizeLimitBytes());
    }
  }

  public void reset() {
    writeContext.reset();
    readContext.reset();
    copyContext.reset();
  }

  private void throwDepthSerializationException() {
    String method = "WriteContext#writeXXX";
    throw new SerializationException(
        String.format(
            "Nested call Fory.serializeXXX is not allowed when serializing, Please use %s instead",
            method));
  }

  private void throwDepthDeserializationException() {
    String method = "ReadContext#readXXX";
    throw new DeserializationException(
        String.format(
            "Nested call Fory.deserializeXXX is not allowed when deserializing, Please use %s instead",
            method));
  }

  @Override
  public void ensureSerializersCompiled() {
    getTypeResolver().ensureSerializersCompiled();
  }

  public JITContext getJITContext() {
    return jitContext;
  }

  /**
   * Don't use this API for type resolving and dispatch, methods on returned resolver has
   * polymorphic invoke cost.
   */
  @Internal
  public TypeResolver getTypeResolver() {
    return typeResolver;
  }

  public WriteContext getWriteContext() {
    return writeContext;
  }

  public ReadContext getReadContext() {
    return readContext;
  }

  public void setMetaWriteContext(MetaWriteContext metaWriteContext) {
    writeContext.setMetaWriteContext(metaWriteContext);
  }

  public void setMetaReadContext(MetaReadContext metaReadContext) {
    readContext.setMetaReadContext(metaReadContext);
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  @Internal
  SharedRegistry getSharedRegistry() {
    return sharedRegistry;
  }

  public Config getConfig() {
    return config;
  }

  public static ForyBuilder builder() {
    return new ForyBuilder();
  }
}
