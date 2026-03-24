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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fory.annotation.Internal;
import org.apache.fory.builder.JITContext;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Config;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.exception.CopyException;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.exception.ForyException;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.exception.SerializationException;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.meta.MetaCompressor;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.MapRefResolver;
import org.apache.fory.resolver.MetaStringResolver;
import org.apache.fory.resolver.NoRefResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.SerializationContext;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.serializer.collection.CollectionSerializers.ArrayListSerializer;
import org.apache.fory.serializer.collection.MapSerializers.HashMapSerializer;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;

/**
 * Cross-language header layout: 1-byte bitmap.
 *
 * <p>Bit 0: null flag, Bit 1: xlang flag, Bit 2: out-of-band flag, Bits 3-7 reserved.
 *
 * <p>serialize/deserialize is user API for root object serialization, write/read api is for inner
 * serialization.
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
  private final boolean refTracking;
  private final boolean shareMeta;
  private final RefResolver refResolver;
  private final TypeResolver typeResolver;
  private final MetaStringResolver metaStringResolver;
  private final SerializationContext serializationContext;
  private final ClassLoader classLoader;
  private final JITContext jitContext;
  private MemoryBuffer buffer;
  private final StringSerializer stringSerializer;
  private final ArrayListSerializer arrayListSerializer;
  private final HashMapSerializer hashMapSerializer;
  private final boolean crossLanguage;
  private final boolean compressInt;
  private final LongEncoding longEncoding;
  private final Generics generics;
  private BufferCallback bufferCallback;
  private Iterator<MemoryBuffer> outOfBandBuffers;
  private boolean peerOutOfBandEnabled;
  private int depth;
  private final int maxDepth;
  private int copyDepth;
  private final boolean copyRefTracking;
  private final IdentityMap<Object, Object> originToCopyMap;

  public Fory(ForyBuilder builder, ClassLoader classLoader) {
    // Avoid set classLoader in `ForyBuilder`, which won't be clear when
    // `org.apache.fory.ThreadSafeFory.clearClassLoader` is called.
    config = new Config(builder);
    crossLanguage = config.isXlang();
    this.refTracking = config.trackingRef();
    this.copyRefTracking = config.copyRef();
    this.shareMeta = config.isMetaShareEnabled();
    compressInt = config.compressInt();
    longEncoding = config.longEncoding();
    maxDepth = config.maxDepth();
    if (refTracking) {
      this.refResolver = new MapRefResolver(config.mapRefLoadFactor());
    } else {
      this.refResolver = new NoRefResolver();
    }
    jitContext = new JITContext(this);
    generics = new Generics(this);
    metaStringResolver = new MetaStringResolver();
    depth = -1;
    typeResolver = crossLanguage ? new XtypeResolver(this) : new ClassResolver(this);
    typeResolver.initialize();
    serializationContext = new SerializationContext(config);
    this.classLoader = classLoader;
    stringSerializer = new StringSerializer(this);
    arrayListSerializer = new ArrayListSerializer(this);
    hashMapSerializer = new HashMapSerializer(this);
    originToCopyMap = new IdentityMap<>();
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

  @Deprecated
  @Override
  public void register(Class<?> cls, boolean createSerializer) {
    getTypeResolver().register(cls);
  }

  @Deprecated
  @Override
  public void register(Class<?> cls, int id, boolean createSerializer) {
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
  public void registerSerializer(Class<?> type, Function<Fory, Serializer<?>> serializerCreator) {
    getTypeResolver().registerSerializer(type, serializerCreator.apply(this));
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
      Class<?> type, Function<Fory, Serializer<?>> serializerCreator) {
    getTypeResolver().registerSerializerAndType(type, serializerCreator.apply(this));
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
    byte bitmap = 0;
    if (crossLanguage) {
      bitmap |= isCrossLanguageFlag;
    }
    if (obj == null) {
      bitmap |= isNilFlag;
      buffer.writeByte(bitmap);
      return buffer;
    }
    if (callback != null) {
      bitmap |= isOutOfBandFlag;
      bufferCallback = callback;
    }
    buffer.writeByte(bitmap);
    try {
      jitContext.lock();
      if (depth > 0) {
        throwDepthSerializationException();
      }
      depth = 0;
      writeRef(buffer, obj);
      return buffer;
    } catch (Throwable t) {
      throw processSerializationError(t);
    } finally {
      resetWrite();
      jitContext.unlock();
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
    if (!refTracking) {
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
    if (!copyRefTracking) {
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

  /** Serialize a nullable referencable object to <code>buffer</code>. */
  public void writeRef(MemoryBuffer buffer, Object obj) {
    if (!refResolver.writeRefOrNull(buffer, obj)) {
      TypeResolver resolver = typeResolver;
      TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass());
      if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
        depth++;
        typeInfo.getSerializer().write(buffer, obj);
        depth--;
        return;
      }
      resolver.writeTypeInfo(buffer, typeInfo);
      writeData(buffer, typeInfo, obj);
    }
  }

  public void writeRef(MemoryBuffer buffer, Object obj, TypeInfoHolder classInfoHolder) {
    if (!refResolver.writeRefOrNull(buffer, obj)) {
      TypeResolver resolver = typeResolver;
      TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass(), classInfoHolder);
      if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
        depth++;
        typeInfo.getSerializer().write(buffer, obj);
        depth--;
        return;
      }
      resolver.writeTypeInfo(buffer, typeInfo);
      writeData(buffer, typeInfo, obj);
    }
  }

  public void writeRef(MemoryBuffer buffer, Object obj, TypeInfo typeInfo) {
    if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
      if (!refResolver.writeRefOrNull(buffer, obj)) {
        depth++;
        typeInfo.getSerializer().write(buffer, obj);
        depth--;
      }
      return;
    }
    TypeResolver resolver = typeResolver;
    Serializer<Object> serializer = typeInfo.getSerializer();
    if (serializer.needToWriteRef()) {
      if (!refResolver.writeRefOrNull(buffer, obj)) {
        resolver.writeTypeInfo(buffer, typeInfo);
        depth++;
        serializer.write(buffer, obj);
        depth--;
      }
    } else {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
        resolver.writeTypeInfo(buffer, typeInfo);
        depth++;
        serializer.write(buffer, obj);
        depth--;
      }
    }
  }

  public <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer) {
    if (serializer.needToWriteRef()) {
      if (!refResolver.writeRefOrNull(buffer, obj)) {
        depth++;
        serializer.write(buffer, obj);
        depth--;
      }
    } else {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
        depth++;
        serializer.write(buffer, obj);
        depth--;
      }
    }
  }

  /**
   * Serialize a not-null and non-reference object to <code>buffer</code>.
   *
   * <p>If reference is enabled, this method should be called only the object is first seen in the
   * object graph.
   */
  public void writeNonRef(MemoryBuffer buffer, Object obj) {
    TypeResolver resolver = typeResolver;
    TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass());
    if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(buffer, obj);
      depth--;
      return;
    }
    resolver.writeTypeInfo(buffer, typeInfo);
    writeData(buffer, typeInfo, obj);
  }

  public void writeNonRef(MemoryBuffer buffer, Object obj, Serializer serializer) {
    depth++;
    serializer.write(buffer, obj);
    depth--;
  }

  public void writeNonRef(MemoryBuffer buffer, Object obj, TypeInfoHolder holder) {
    TypeResolver resolver = typeResolver;
    TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass(), holder);
    if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(buffer, obj);
      depth--;
      return;
    }
    resolver.writeTypeInfo(buffer, typeInfo);
    writeData(buffer, typeInfo, obj);
  }

  public void writeNonRef(MemoryBuffer buffer, Object obj, TypeInfo typeInfo) {
    if (crossLanguage && typeInfo.getCls() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(buffer, obj);
      depth--;
      return;
    }
    typeResolver.writeTypeInfo(buffer, typeInfo);
    writeData(buffer, typeInfo, obj);
  }

  /** Class/type info should be written already. */
  public void writeData(MemoryBuffer buffer, TypeInfo typeInfo, Object obj) {
    int typeId = typeInfo.getTypeId();
    switch (typeId) {
      case Types.BOOL:
        buffer.writeBoolean((Boolean) obj);
        break;
      case Types.INT8:
        buffer.writeByte((Byte) obj);
        break;
      case Types.INT16:
        buffer.writeInt16((Short) obj);
        break;
      case ClassResolver.CHAR_ID:
        buffer.writeChar((Character) obj);
        break;
      case Types.INT32:
      case Types.VARINT32:
        if (compressInt) {
          buffer.writeVarInt32((Integer) obj);
        } else {
          buffer.writeInt32((Integer) obj);
        }
        break;
      case Types.INT64:
        LongSerializer.writeInt64(buffer, (Long) obj, longEncoding);
        break;
      case Types.VARINT64:
        buffer.writeVarInt64((Long) obj);
        break;
      case Types.TAGGED_INT64:
        buffer.writeTaggedInt64((Long) obj);
        break;
      case Types.FLOAT32:
        buffer.writeFloat32((Float) obj);
        break;
      case Types.FLOAT64:
        buffer.writeFloat64((Double) obj);
        break;
      case Types.STRING:
        if (typeInfo.getCls() == String.class) {
          stringSerializer.writeString(buffer, (String) obj);
          break;
        }
        depth++;
        typeInfo.getSerializer().write(buffer, obj);
        depth--;
        break;
      default:
        depth++;
        typeInfo.getSerializer().write(buffer, obj);
        depth--;
    }
  }

  public void writeBufferObject(MemoryBuffer buffer, BufferObject bufferObject) {
    if (bufferCallback == null || bufferCallback.apply(bufferObject)) {
      buffer.writeBoolean(true);
      // writer length.
      int totalBytes = bufferObject.totalBytes();
      // write aligned length so that later buffer copy happen on aligned offset, which will be more
      // efficient
      // TODO(chaokunyang) Remove branch when other languages support aligned varint.
      if (!crossLanguage) {
        buffer.writeVarUint32Aligned(totalBytes);
      } else {
        buffer.writeVarUint32(totalBytes);
      }
      int writerIndex = buffer.writerIndex();
      buffer.ensure(writerIndex + bufferObject.totalBytes());
      bufferObject.writeTo(buffer);
      int size = buffer.writerIndex() - writerIndex;
      Preconditions.checkArgument(size == totalBytes);
    } else {
      buffer.writeBoolean(false);
    }
  }

  // duplicate for speed.
  public void writeBufferObject(
      MemoryBuffer buffer, ArraySerializers.PrimitiveArrayBufferObject bufferObject) {
    if (bufferCallback == null || bufferCallback.apply(bufferObject)) {
      buffer.writeBoolean(true);
      int totalBytes = bufferObject.totalBytes();
      // write aligned length so that later buffer copy happen on aligned offset, which will be very
      // efficient
      // TODO(chaokunyang) Remove branch when other languages support aligned varint.
      if (!crossLanguage) {
        buffer.writeVarUint32Aligned(totalBytes);
      } else {
        buffer.writeVarUint32(totalBytes);
      }
      bufferObject.writeTo(buffer);
    } else {
      buffer.writeBoolean(false);
    }
  }

  public MemoryBuffer readBufferObject(MemoryBuffer buffer) {
    boolean inBand = buffer.readBoolean();
    if (inBand) {
      int size;
      // TODO(chaokunyang) Remove branch when other languages support aligned varint.
      if (!crossLanguage) {
        size = buffer.readAlignedVarUint32();
      } else {
        size = buffer.readVarUint32();
      }
      if (buffer.readerIndex() + size > buffer.size() && buffer.getStreamReader() != null) {
        buffer.getStreamReader().fillBuffer(buffer.readerIndex() + size - buffer.size());
      }
      MemoryBuffer slice = buffer.slice(buffer.readerIndex(), size);
      buffer.readerIndex(buffer.readerIndex() + size);
      return slice;
    } else {
      Preconditions.checkArgument(outOfBandBuffers.hasNext());
      return outOfBandBuffers.next();
    }
  }

  public void writeString(MemoryBuffer buffer, String str) {
    stringSerializer.writeString(buffer, str);
  }

  public String readString(MemoryBuffer buffer) {
    return stringSerializer.readString(buffer);
  }

  public void writeStringRef(MemoryBuffer buffer, String str) {
    if (stringSerializer.needToWriteRef()) {
      if (!refResolver.writeRefOrNull(buffer, str)) {
        stringSerializer.writeString(buffer, str);
      }
    } else {
      if (str == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
        stringSerializer.write(buffer, str);
      }
    }
  }

  public String readStringRef(MemoryBuffer buffer) {
    RefResolver refResolver = this.refResolver;
    if (stringSerializer.needToWriteRef()) {
      String obj;
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
        obj = stringSerializer.read(buffer);
        refResolver.setReadObject(nextReadRefId, obj);
        return obj;
      } else {
        return (String) refResolver.getReadObject();
      }
    } else {
      byte headFlag = buffer.readByte();
      if (headFlag == Fory.NULL_FLAG) {
        return null;
      } else {
        return stringSerializer.read(buffer);
      }
    }
  }

  public void writeInt64(MemoryBuffer buffer, long value) {
    LongSerializer.writeInt64(buffer, value, longEncoding);
  }

  public long readInt64(MemoryBuffer buffer) {
    return LongSerializer.readInt64(buffer, longEncoding);
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
    try {
      jitContext.lock();
      if (depth > 0) {
        throwDepthDeserializationException();
      }
      depth = 0;
      byte bitmap = buffer.readByte();
      if ((bitmap & isNilFlag) == isNilFlag) {
        return null;
      }
      boolean peerOutOfBandEnabled = (bitmap & isOutOfBandFlag) == isOutOfBandFlag;
      assert !peerOutOfBandEnabled : "Out of band buffers not passed in when deserializing";
      checkXlangBitmap(bitmap);
      return deserializeByType(buffer, type);
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(this, t);
    } finally {
      resetRead();
      jitContext.unlock();
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
    try {
      jitContext.lock();
      if (depth > 0) {
        throwDepthDeserializationException();
      }
      depth = 0;
      byte bitmap = buffer.readByte();
      if ((bitmap & isNilFlag) == isNilFlag) {
        return null;
      }
      checkXlangBitmap(bitmap);
      peerOutOfBandEnabled = (bitmap & isOutOfBandFlag) == isOutOfBandFlag;
      if (peerOutOfBandEnabled) {
        Preconditions.checkNotNull(
            outOfBandBuffers,
            "outOfBandBuffers shouldn't be null when the serialized stream is "
                + "produced with bufferCallback not null.");
        this.outOfBandBuffers = outOfBandBuffers.iterator();
      } else {
        Preconditions.checkArgument(
            outOfBandBuffers == null,
            "outOfBandBuffers should be null when the serialized stream is "
                + "produced with bufferCallback null.");
      }
      return readRef(buffer);
    } catch (Throwable t) {
      throw ExceptionUtils.handleReadFailed(this, t);
    } finally {
      resetRead();
      jitContext.unlock();
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
    generics.pushGenericType(typeResolver.buildGenericType(type));
    try {
      RefResolver refResolver = this.refResolver;
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId < NOT_NULL_VALUE_FLAG) {
        return (T) refResolver.getReadObject();
      }
      TypeInfo typeInfo = typeResolver.readTypeInfo(buffer, type);
      Object value = readNonRef(buffer, typeInfo);
      refResolver.setReadObject(nextReadRefId, value);
      return (T) value;
    } finally {
      generics.popGenericType();
    }
  }

  /** Deserialize nullable referencable object from <code>buffer</code>. */
  public Object readRef(MemoryBuffer buffer) {
    RefResolver refResolver = this.refResolver;
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
      TypeInfo typeInfo = typeResolver.readTypeInfo(buffer);
      Object o = readNonRef(buffer, typeInfo);
      refResolver.setReadObject(nextReadRefId, o);
      return o;
    }
    return refResolver.getReadObject();
  }

  public Object readRef(MemoryBuffer buffer, TypeInfo typeInfo) {
    RefResolver refResolver = this.refResolver;
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
      Object o = readNonRef(buffer, typeInfo);
      refResolver.setReadObject(nextReadRefId, o);
      return o;
    }
    return refResolver.getReadObject();
  }

  public Object readRef(MemoryBuffer buffer, TypeInfoHolder classInfoHolder) {
    RefResolver refResolver = this.refResolver;
    int nextReadRefId = refResolver.tryPreserveRefId(buffer);
    if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
      TypeInfo typeInfo = typeResolver.readTypeInfo(buffer, classInfoHolder);
      Object o = readNonRef(buffer, typeInfo);
      refResolver.setReadObject(nextReadRefId, o);
      return o;
    }
    return refResolver.getReadObject();
  }

  @SuppressWarnings("unchecked")
  public <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer) {
    if (serializer.needToWriteRef()) {
      RefResolver refResolver = this.refResolver;
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
        Object o = readNonRef(buffer, serializer);
        refResolver.setReadObject(nextReadRefId, o);
        return (T) o;
      }
      return (T) refResolver.getReadObject();
    }
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return (T) readNonRef(buffer, serializer);
  }

  /** Deserialize not-null and non-reference object from <code>buffer</code>. */
  public Object readNonRef(MemoryBuffer buffer) {
    TypeInfo typeInfo = typeResolver.readTypeInfo(buffer);
    return readNonRef(buffer, typeInfo);
  }

  public Object readNonRef(MemoryBuffer buffer, TypeInfoHolder classInfoHolder) {
    TypeInfo typeInfo = typeResolver.readTypeInfo(buffer, classInfoHolder);
    return readNonRef(buffer, typeInfo);
  }

  public Object readNonRef(MemoryBuffer buffer, TypeInfo typeInfo) {
    return readDataInternal(buffer, typeInfo);
  }

  public Object readNonRef(MemoryBuffer buffer, Serializer<?> serializer) {
    incReadDepth();
    Object o = serializer.read(buffer);
    depth--;
    return o;
  }

  /** Read object class and data without tracking ref. */
  public Object readNullable(MemoryBuffer buffer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    } else {
      return readNonRef(buffer);
    }
  }

  public Object readNullable(MemoryBuffer buffer, Serializer serializer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    } else {
      return serializer.read(buffer);
    }
  }

  public Object readNullable(MemoryBuffer buffer, TypeInfoHolder classInfoHolder) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return readNonRef(buffer, classInfoHolder);
  }

  /** Class should be read already. */
  public Object readData(MemoryBuffer buffer, TypeInfo typeInfo) {
    incReadDepth();
    Serializer<?> serializer = typeInfo.getSerializer();
    Object read = serializer.read(buffer);
    depth--;
    return read;
  }

  private Object readDataInternal(MemoryBuffer buffer, TypeInfo typeInfo) {
    int typeId = typeInfo.getTypeId();
    switch (typeId) {
      case Types.BOOL:
        return buffer.readBoolean();
      case Types.INT8:
        return buffer.readByte();
      case ClassResolver.CHAR_ID:
        return buffer.readChar();
      case Types.INT16:
        return buffer.readInt16();
      case Types.INT32:
        if (compressInt) {
          return buffer.readVarInt32();
        } else {
          return buffer.readInt32();
        }
      case Types.VARINT32:
        return buffer.readVarInt32();
      case Types.FLOAT32:
        return buffer.readFloat32();
      case Types.INT64:
        return LongSerializer.readInt64(buffer, longEncoding);
      case Types.VARINT64:
        return buffer.readVarInt64();
      case Types.TAGGED_INT64:
        return buffer.readTaggedInt64();
      case Types.FLOAT64:
        return buffer.readFloat64();
      case Types.STRING:
        if (typeInfo.getCls() == String.class) {
          return stringSerializer.readString(buffer);
        }
        incReadDepth();
        Object stringLike = typeInfo.getSerializer().read(buffer);
        depth--;
        return stringLike;
      default:
        incReadDepth();
        Object read = typeInfo.getSerializer().read(buffer);
        depth--;
        return read;
    }
  }

  private void checkXlangBitmap(byte bitmap) {
    boolean payloadCrossLanguage = (bitmap & isCrossLanguageFlag) == isCrossLanguageFlag;
    Preconditions.checkArgument(
        payloadCrossLanguage == crossLanguage,
        "Serialized payload xlang flag %s does not match this Fory mode %s",
        payloadCrossLanguage,
        crossLanguage);
  }

  @Override
  public <T> T copy(T obj) {
    try {
      return copyObject(obj);
    } catch (Throwable e) {
      throw processCopyError(e);
    } finally {
      if (copyRefTracking) {
        resetCopy();
      }
    }
  }

  /**
   * Copy object. This method provides a fast copy of common types.
   *
   * @param obj object to copy
   * @return copied object
   */
  public <T> T copyObject(T obj) {
    if (obj == null) {
      return null;
    }
    Object copy;
    TypeInfo typeInfo = typeResolver.getTypeInfo(obj.getClass(), true);
    int typeId = typeInfo.getTypeId();
    switch (typeId) {
      case Types.BOOL:
      case Types.INT8:
      case ClassResolver.CHAR_ID:
      case Types.INT16:
      case Types.INT32:
      case Types.FLOAT32:
      case Types.INT64:
      case Types.FLOAT64:
        return obj;
      case Types.STRING:
        if (typeInfo.getCls() == String.class) {
          return obj;
        }
        copy = copyObject(obj, typeInfo.getSerializer());
        break;
      case ClassResolver.PRIMITIVE_BOOLEAN_ARRAY_ID:
        boolean[] boolArr = (boolean[]) obj;
        return (T) Arrays.copyOf(boolArr, boolArr.length);
      case ClassResolver.PRIMITIVE_BYTE_ARRAY_ID:
        byte[] byteArr = (byte[]) obj;
        return (T) Arrays.copyOf(byteArr, byteArr.length);
      case ClassResolver.PRIMITIVE_CHAR_ARRAY_ID:
        char[] charArr = (char[]) obj;
        return (T) Arrays.copyOf(charArr, charArr.length);
      case ClassResolver.PRIMITIVE_SHORT_ARRAY_ID:
        short[] shortArr = (short[]) obj;
        return (T) Arrays.copyOf(shortArr, shortArr.length);
      case ClassResolver.PRIMITIVE_INT_ARRAY_ID:
        int[] intArr = (int[]) obj;
        return (T) Arrays.copyOf(intArr, intArr.length);
      case ClassResolver.PRIMITIVE_FLOAT_ARRAY_ID:
        float[] floatArr = (float[]) obj;
        return (T) Arrays.copyOf(floatArr, floatArr.length);
      case ClassResolver.PRIMITIVE_LONG_ARRAY_ID:
        long[] longArr = (long[]) obj;
        return (T) Arrays.copyOf(longArr, longArr.length);
      case ClassResolver.PRIMITIVE_DOUBLE_ARRAY_ID:
        double[] doubleArr = (double[]) obj;
        return (T) Arrays.copyOf(doubleArr, doubleArr.length);
      case ClassResolver.STRING_ARRAY_ID:
        String[] stringArr = (String[]) obj;
        return (T) Arrays.copyOf(stringArr, stringArr.length);
      case ClassResolver.ARRAYLIST_ID:
        copy = arrayListSerializer.copy((ArrayList) obj);
        break;
      case ClassResolver.HASHMAP_ID:
        copy = hashMapSerializer.copy((HashMap) obj);
        break;
      // todo: add fastpath for other types.
      default:
        copy = copyObject(obj, typeInfo.getSerializer());
    }
    return (T) copy;
  }

  public <T> T copyObject(T obj, int classId) {
    if (obj == null) {
      return null;
    }
    // Fast path to avoid cost of query class map.
    switch (classId) {
      case ClassResolver.PRIMITIVE_BOOL_ID:
      case ClassResolver.PRIMITIVE_INT8_ID:
      case ClassResolver.PRIMITIVE_CHAR_ID:
      case ClassResolver.PRIMITIVE_INT16_ID:
      case ClassResolver.PRIMITIVE_INT32_ID:
      case ClassResolver.PRIMITIVE_FLOAT32_ID:
      case ClassResolver.PRIMITIVE_INT64_ID:
      case ClassResolver.PRIMITIVE_FLOAT64_ID:
      case Types.BOOL:
      case Types.INT8:
      case ClassResolver.CHAR_ID:
      case Types.INT16:
      case Types.INT32:
      case Types.FLOAT32:
      case Types.INT64:
      case Types.FLOAT64:
        return obj;
      case Types.STRING:
        if (obj.getClass() == String.class) {
          return obj;
        }
        return copyObject(obj, typeResolver.getTypeInfo(obj.getClass(), true).getSerializer());
      default:
        return copyObject(obj, typeResolver.getTypeInfo(obj.getClass(), true).getSerializer());
    }
  }

  public <T> T copyObject(T obj, Serializer<T> serializer) {
    copyDepth++;
    T copyObject;
    if (serializer.needToCopyRef()) {
      copyObject = getCopyObject(obj);
      if (copyObject == null) {
        copyObject = serializer.copy(obj);
        originToCopyMap.put(obj, copyObject);
      }
    } else {
      copyObject = serializer.copy(obj);
    }
    copyDepth--;
    return copyObject;
  }

  /**
   * Track ref for copy.
   *
   * <p>Call this method immediately after composited object such as object
   * array/map/collection/bean is created so that circular reference can be copy correctly.
   *
   * @param o1 object before copying
   * @param o2 the copied object
   */
  public <T> void reference(T o1, T o2) {
    if (o1 != null) {
      originToCopyMap.put(o1, o2);
    }
  }

  @SuppressWarnings("unchecked")
  public <T> T getCopyObject(T originObj) {
    return (T) originToCopyMap.get(originObj);
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

  public void reset() {
    resetWrite();
    resetRead();
    resetCopy();
  }

  public void resetWrite() {
    refResolver.resetWrite();
    typeResolver.resetWrite();
    metaStringResolver.resetWrite();
    serializationContext.resetWrite();
    bufferCallback = null;
    depth = 0;
  }

  public void resetRead() {
    refResolver.resetRead();
    typeResolver.resetRead();
    metaStringResolver.resetRead();
    serializationContext.resetRead();
    peerOutOfBandEnabled = false;
    depth = 0;
  }

  public void resetCopy() {
    originToCopyMap.clear();
    copyDepth = 0;
  }

  private void throwDepthSerializationException() {
    String method = "Fory#writeXXX";
    throw new SerializationException(
        String.format(
            "Nested call Fory.serializeXXX is not allowed when serializing, Please use %s instead",
            method));
  }

  private void throwDepthDeserializationException() {
    String method = "Fory#readXXX";
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

  public BufferCallback getBufferCallback() {
    return bufferCallback;
  }

  public boolean isPeerOutOfBandEnabled() {
    return peerOutOfBandEnabled;
  }

  public RefResolver getRefResolver() {
    return refResolver;
  }

  /**
   * Don't use this API for type resolving and dispatch, methods on returned resolver has
   * polymorphic invoke cost.
   */
  @Internal
  // CHECKSTYLE.OFF:MethodName
  public TypeResolver getTypeResolver() {
    // CHECKSTYLE.ON:MethodName
    return typeResolver;
  }

  public MetaStringResolver getMetaStringResolver() {
    return metaStringResolver;
  }

  public SerializationContext getSerializationContext() {
    return serializationContext;
  }

  public Generics getGenerics() {
    return generics;
  }

  public int getDepth() {
    return depth;
  }

  public void setDepth(int depth) {
    this.depth = depth;
  }

  public void incDepth(int diff) {
    this.depth += diff;
  }

  public void incDepth() {
    this.depth += 1;
  }

  public void decDepth() {
    this.depth -= 1;
  }

  public void incReadDepth() {
    if ((this.depth += 1) > maxDepth) {
      throwReadDepthExceedException();
    }
  }

  private void throwReadDepthExceedException() {
    throw new InsecureException(
        String.format(
            "Read depth exceed max depth %s, "
                + "the deserialization data may be malicious. If it's not malicious, "
                + "please increase max read depth by ForyBuilder#withMaxDepth(largerDepth)",
            maxDepth));
  }

  public void incCopyDepth(int diff) {
    this.copyDepth += diff;
  }

  // Invoked by jit
  public StringSerializer getStringSerializer() {
    return stringSerializer;
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  public boolean isCrossLanguage() {
    return crossLanguage;
  }

  public boolean isCompatible() {
    return config.getCompatibleMode() == CompatibleMode.COMPATIBLE;
  }

  public boolean isShareMeta() {
    return shareMeta;
  }

  public boolean trackingRef() {
    return refTracking;
  }

  public boolean copyTrackingRef() {
    return copyRefTracking;
  }

  public boolean isStringRefIgnored() {
    return config.isStringRefIgnored();
  }

  public boolean checkClassVersion() {
    return config.checkClassVersion();
  }

  public CompatibleMode getCompatibleMode() {
    return config.getCompatibleMode();
  }

  public Config getConfig() {
    return config;
  }

  public Class<? extends Serializer> getDefaultJDKStreamSerializerType() {
    return config.getDefaultJDKStreamSerializerType();
  }

  public boolean compressString() {
    return config.compressString();
  }

  public boolean compressInt() {
    return compressInt;
  }

  public LongEncoding longEncoding() {
    return longEncoding;
  }

  public boolean compressLong() {
    return config.compressLong();
  }

  public MetaCompressor getMetaCompressor() {
    return config.getMetaCompressor();
  }

  public static ForyBuilder builder() {
    return new ForyBuilder();
  }
}
