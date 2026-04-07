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

package org.apache.fory.context;

import java.util.IdentityHashMap;
import org.apache.fory.Fory;
import org.apache.fory.config.Config;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ArraySerializers;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

/**
 * Per-operation state for serialization.
 *
 * <p>{@code WriteContext} owns the current {@link MemoryBuffer}, runtime feature flags, ref/meta
 * state, and a small scratch map for serializers that need operation-local coordination. The
 * context is prepared by {@code Fory} for one write operation and {@link #reset()} before reuse.
 *
 * <p>Generated and hand-written serializers should treat this type as the root source of write-time
 * services instead of storing ambient state themselves.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class WriteContext {
  private final Config config;
  private final Generics generics;
  private final TypeResolver typeResolver;
  private final RefWriter refWriter;
  private final MetaStringWriter metaStringWriter;
  private final StringSerializer stringSerializer;
  private final boolean crossLanguage;
  private final boolean compressInt;
  private final LongEncoding longEncoding;
  private final boolean forVirtualThread;
  private final boolean scopedMetaShareEnabled;
  private final IdentityHashMap<Object, Object> contextObjects = new IdentityHashMap<>();
  private MemoryBuffer buffer;
  private BufferCallback bufferCallback;
  private MetaWriteContext metaWriteContext;
  private int depth;

  /**
   * Creates write-side runtime state for one {@code Fory} instance.
   *
   * <p>The context can be reused across operations, but only one write operation may be active at a
   * time.
   */
  public WriteContext(
      Config config,
      Generics generics,
      TypeResolver typeResolver,
      RefWriter refWriter,
      MetaStringWriter metaStringWriter) {
    this.config = config;
    this.generics = generics;
    this.typeResolver = typeResolver;
    this.refWriter = refWriter;
    this.metaStringWriter = metaStringWriter;
    stringSerializer = (StringSerializer) typeResolver.getSerializer(String.class);
    crossLanguage = config.isXlang();
    compressInt = config.compressInt();
    longEncoding = config.longEncoding();
    forVirtualThread = config.forVirtualThread();
    scopedMetaShareEnabled = config.isScopedMetaShareEnabled();
    if (scopedMetaShareEnabled) {
      metaWriteContext = new MetaWriteContext();
    }
  }

  /** Binds the current output buffer and optional out-of-band buffer callback for one operation. */
  public void prepare(MemoryBuffer buffer, BufferCallback callback) {
    this.buffer = buffer;
    bufferCallback = callback;
  }

  /**
   * Returns the current operation buffer.
   *
   * <p>If a caller needs multiple primitive reads or writes, fetch the buffer once and invoke
   * {@link MemoryBuffer} methods directly for better performance instead of repeatedly calling the
   * forwarding helpers on this context.
   */
  public MemoryBuffer getBuffer() {
    return buffer;
  }

  /** Writes an unsigned byte directly to the current buffer. */
  /**
   * Writes a boolean value directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeBoolean(boolean)} directly for better
   * performance.
   */
  public void writeBoolean(boolean value) {
    buffer.writeBoolean(value);
  }

  /**
   * Writes a signed byte directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeByte(byte)} directly for better performance.
   */
  public void writeByte(byte value) {
    buffer.writeByte(value);
  }

  /**
   * Writes an unsigned byte directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeUint8(int)} directly for better performance.
   */
  public void writeUint8(int value) {
    buffer.writeUint8(value);
  }

  /**
   * Writes a character value directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeChar(char)} directly for better performance.
   */
  public void writeChar(char value) {
    buffer.writeChar(value);
  }

  /**
   * Writes a 16-bit integer directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeInt16(short)} directly for better
   * performance.
   */
  public void writeInt16(short value) {
    buffer.writeInt16(value);
  }

  /**
   * Writes a 32-bit integer directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeInt32(int)} directly for better performance.
   */
  public void writeInt32(int value) {
    buffer.writeInt32(value);
  }

  /**
   * Writes a 16-bit floating-point value encoded through its raw half-precision bits.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeInt16(short)} directly for better
   * performance.
   */
  public void writeFloat16(Float16 value) {
    buffer.writeInt16(value.toBits());
  }

  /**
   * Writes a 32-bit floating-point value directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeFloat32(float)} directly for better
   * performance.
   */
  public void writeFloat32(float value) {
    buffer.writeFloat32(value);
  }

  /**
   * Writes a 64-bit floating-point value directly to the current buffer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeFloat64(double)} directly for better
   * performance.
   */
  public void writeFloat64(double value) {
    buffer.writeFloat64(value);
  }

  /**
   * Writes a zig-zag encoded variable-length 32-bit integer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeVarInt32(int)} directly for better
   * performance.
   */
  public int writeVarInt32(int value) {
    return buffer.writeVarInt32(value);
  }

  /**
   * Writes an unsigned variable-length 32-bit integer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeVarUint32(int)} directly for better
   * performance.
   */
  public int writeVarUint32(int value) {
    return buffer.writeVarUint32(value);
  }

  /**
   * Writes a zig-zag encoded variable-length 64-bit integer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeVarInt64(long)} directly for better
   * performance.
   */
  public int writeVarInt64(long value) {
    return buffer.writeVarInt64(value);
  }

  /**
   * Writes an unsigned variable-length 64-bit integer.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeVarUint64(long)} directly for better
   * performance.
   */
  public int writeVarUint64(long value) {
    return buffer.writeVarUint64(value);
  }

  /**
   * Writes a tagged signed 64-bit integer using the compact small-long encoding.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeTaggedInt64(long)} directly for better
   * performance.
   */
  public int writeTaggedInt64(long value) {
    return buffer.writeTaggedInt64(value);
  }

  /**
   * Writes a tagged unsigned 64-bit integer using the compact small-long encoding.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#writeTaggedUint64(long)} directly for better
   * performance.
   */
  public int writeTaggedUint64(long value) {
    return buffer.writeTaggedUint64(value);
  }

  /** Clears all operation-local state so this context can be reused for another write. */
  public void reset() {
    refWriter.reset();
    metaStringWriter.reset();
    if (!contextObjects.isEmpty()) {
      contextObjects.clear();
    }
    if (scopedMetaShareEnabled) {
      metaWriteContext.classMap.clear();
    } else {
      metaWriteContext = null;
    }
    buffer = null;
    bufferCallback = null;
    depth = 0;
    if (forVirtualThread) {
      stringSerializer.clearBuffer(config.bufferSizeLimitBytes());
    }
  }

  /** Returns the immutable runtime configuration for this context. */
  public Config getConfig() {
    return config;
  }

  /** Returns the generics stack shared by the owning runtime. */
  public Generics getGenerics() {
    return generics;
  }

  /** Returns the resolver used for type lookup and type metadata emission. */
  public TypeResolver getTypeResolver() {
    return typeResolver;
  }

  /** Returns the write-side reference tracker. */
  public RefWriter getRefWriter() {
    return refWriter;
  }

  /** Delegates to {@link RefWriter#writeRefOrNull(MemoryBuffer, Object)} on the current buffer. */
  public boolean writeRefOrNull(Object obj) {
    return refWriter.writeRefOrNull(buffer, obj);
  }

  /**
   * Delegates to {@link RefWriter#writeRefValueFlag(MemoryBuffer, Object)} on the current buffer.
   */
  public boolean writeRefValueFlag(Object obj) {
    return refWriter.writeRefValueFlag(buffer, obj);
  }

  /** Delegates to {@link RefWriter#writeNullFlag(MemoryBuffer, Object)} on the current buffer. */
  public boolean writeNullFlag(Object obj) {
    return refWriter.writeNullFlag(buffer, obj);
  }

  /**
   * Rebinds the recorded ref id of {@code original} to the id already assigned to {@code
   * newObject}.
   */
  public void replaceRef(Object original, Object newObject) {
    refWriter.replaceRef(original, newObject);
  }

  /** Returns the write-side meta-string state for the current runtime. */
  public MetaStringWriter getMetaStringWriter() {
    return metaStringWriter;
  }

  /** Returns the runtime string serializer used by string-specialized helpers. */
  public StringSerializer getStringSerializer() {
    return stringSerializer;
  }

  /** Stores operation-local state keyed by object identity. */
  public Object putContextObject(Object key, Object value) {
    return contextObjects.put(key, value);
  }

  /** Returns whether an operation-local object is registered for {@code key}. */
  public boolean hasContextObject(Object key) {
    return contextObjects.containsKey(key);
  }

  /** Returns operation-local state keyed by object identity. */
  public Object getContextObject(Object key) {
    return contextObjects.get(key);
  }

  /** Returns the current meta-share write context, or {@code null} when none is configured. */
  public MetaWriteContext getMetaWriteContext() {
    return metaWriteContext;
  }

  /**
   * Installs an externally owned meta-share write context.
   *
   * <p>This is only valid when scoped meta share is disabled and the caller wants meta-share state
   * to survive across multiple operations.
   */
  public void setMetaWriteContext(MetaWriteContext metaWriteContext) {
    Preconditions.checkArgument(!scopedMetaShareEnabled);
    this.metaWriteContext = metaWriteContext;
  }

  /** Returns whether the owning runtime is currently writing the cross-language protocol. */
  public boolean isCrossLanguage() {
    return crossLanguage;
  }

  /** Returns whether 32-bit integers should prefer compressed encodings when possible. */
  public boolean compressInt() {
    return compressInt;
  }

  /** Returns the configured long encoding policy for 64-bit integers. */
  public LongEncoding longEncoding() {
    return longEncoding;
  }

  /** Returns the current logical object-graph depth. */
  public int getDepth() {
    return depth;
  }

  /** Sets the current logical object-graph depth. */
  public void setDepth(int depth) {
    this.depth = depth;
  }

  /** Increases the logical object-graph depth by {@code diff}. */
  public void increaseDepth(int diff) {
    depth += diff;
  }

  /** Increases the logical object-graph depth by one. */
  public void increaseDepth() {
    depth += 1;
  }

  /** Decreases the logical object-graph depth by one. */
  public void decreaseDepth() {
    depth -= 1;
  }

  /** Returns the buffer callback used for out-of-band buffer objects, if any. */
  public BufferCallback getBufferCallback() {
    return bufferCallback;
  }

  /**
   * Writes a nullable reference-tracked object together with any required type metadata.
   *
   * <p>If the object was already seen by the current {@link RefWriter}, only a ref header is
   * emitted.
   */
  public void writeRef(Object obj) {
    MemoryBuffer buffer = this.buffer;
    if (!refWriter.writeRefOrNull(buffer, obj)) {
      TypeResolver resolver = typeResolver;
      TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass());
      if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
        depth++;
        typeInfo.getSerializer().write(this, obj);
        depth--;
        return;
      }
      resolver.writeTypeInfo(this, typeInfo);
      writeData(typeInfo, obj);
    }
  }

  /** Variant of {@link #writeRef(Object)} that reuses a cached type-info holder. */
  public void writeRef(Object obj, TypeInfoHolder classInfoHolder) {
    MemoryBuffer buffer = this.buffer;
    if (!refWriter.writeRefOrNull(buffer, obj)) {
      TypeResolver resolver = typeResolver;
      TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass(), classInfoHolder);
      if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
        depth++;
        typeInfo.getSerializer().write(this, obj);
        depth--;
        return;
      }
      resolver.writeTypeInfo(this, typeInfo);
      writeData(typeInfo, obj);
    }
  }

  /** Variant of {@link #writeRef(Object)} that uses already resolved {@link TypeInfo}. */
  public void writeRef(Object obj, TypeInfo typeInfo) {
    MemoryBuffer buffer = this.buffer;
    if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
      if (!refWriter.writeRefOrNull(buffer, obj)) {
        depth++;
        typeInfo.getSerializer().write(this, obj);
        depth--;
      }
      return;
    }
    TypeResolver resolver = typeResolver;
    Serializer<Object> serializer = typeInfo.getSerializer();
    if (serializer.needToWriteRef()) {
      if (!refWriter.writeRefOrNull(buffer, obj)) {
        resolver.writeTypeInfo(this, typeInfo);
        depth++;
        serializer.write(this, obj);
        depth--;
      }
    } else if (obj == null) {
      buffer.writeByte(Fory.NULL_FLAG);
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      resolver.writeTypeInfo(this, typeInfo);
      depth++;
      serializer.write(this, obj);
      depth--;
    }
  }

  /** Writes a nullable object using an already chosen serializer. */
  public <T> void writeRef(T obj, Serializer<T> serializer) {
    MemoryBuffer buffer = this.buffer;
    if (serializer.needToWriteRef()) {
      if (!refWriter.writeRefOrNull(buffer, obj)) {
        depth++;
        serializer.write(this, obj);
        depth--;
      }
    } else if (obj == null) {
      buffer.writeByte(Fory.NULL_FLAG);
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      depth++;
      serializer.write(this, obj);
      depth--;
    }
  }

  /**
   * Writes a non-null, first-seen object together with its type metadata.
   *
   * <p>If ref tracking is enabled, callers must use this only for the first visit to the object in
   * the current graph.
   */
  public void writeNonRef(Object obj) {
    TypeResolver resolver = typeResolver;
    TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass());
    if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(this, obj);
      depth--;
      return;
    }
    resolver.writeTypeInfo(this, typeInfo);
    writeData(typeInfo, obj);
  }

  /** Writes a non-null payload using an already chosen serializer and no extra type metadata. */
  public void writeNonRef(Object obj, Serializer serializer) {
    depth++;
    serializer.write(this, obj);
    depth--;
  }

  /** Variant of {@link #writeNonRef(Object)} that reuses a cached type-info holder. */
  public void writeNonRef(Object obj, TypeInfoHolder holder) {
    TypeResolver resolver = typeResolver;
    TypeInfo typeInfo = resolver.getTypeInfo(obj.getClass(), holder);
    if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(this, obj);
      depth--;
      return;
    }
    resolver.writeTypeInfo(this, typeInfo);
    writeData(typeInfo, obj);
  }

  /** Variant of {@link #writeNonRef(Object)} that uses already resolved {@link TypeInfo}. */
  public void writeNonRef(Object obj, TypeInfo typeInfo) {
    if (crossLanguage && typeInfo.getType() == UnknownStruct.class) {
      depth++;
      typeInfo.getSerializer().write(this, obj);
      depth--;
      return;
    }
    typeResolver.writeTypeInfo(this, typeInfo);
    writeData(typeInfo, obj);
  }

  /**
   * Writes only the payload for {@code obj} after the caller has already emitted class/type info.
   */
  public void writeData(TypeInfo typeInfo, Object obj) {
    MemoryBuffer buffer = this.buffer;
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
        if (typeInfo.getType() == String.class) {
          stringSerializer.writeString(buffer, (String) obj);
          break;
        }
        depth++;
        typeInfo.getSerializer().write(this, obj);
        depth--;
        break;
      default:
        depth++;
        typeInfo.getSerializer().write(this, obj);
        depth--;
    }
  }

  /**
   * Writes a general buffer object either in-band or out-of-band according to the active callback.
   */
  public void writeBufferObject(BufferObject bufferObject) {
    MemoryBuffer buffer = this.buffer;
    if (bufferCallback == null || bufferCallback.apply(bufferObject)) {
      buffer.writeBoolean(true);
      int totalBytes = bufferObject.totalBytes();
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

  /** Specialized variant of {@link #writeBufferObject(BufferObject)} for primitive arrays. */
  public void writeBufferObject(ArraySerializers.PrimitiveArrayBufferObject bufferObject) {
    MemoryBuffer buffer = this.buffer;
    if (bufferCallback == null || bufferCallback.apply(bufferObject)) {
      buffer.writeBoolean(true);
      int totalBytes = bufferObject.totalBytes();
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

  /** Writes a non-null string payload without ref/null headers. */
  public void writeString(String str) {
    stringSerializer.writeString(buffer, str);
  }

  /** Writes a nullable string using the runtime string-ref policy. */
  public void writeStringRef(String str) {
    MemoryBuffer buffer = this.buffer;
    if (stringSerializer.needToWriteRef()) {
      if (!refWriter.writeRefOrNull(buffer, str)) {
        stringSerializer.writeString(buffer, str);
      }
    } else if (str == null) {
      buffer.writeByte(Fory.NULL_FLAG);
    } else {
      buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
      stringSerializer.write(this, str);
    }
  }

  /**
   * Writes a 64-bit integer using the configured {@link #longEncoding() long encoding policy}.
   *
   * <p>If a caller needs multiple primitive writes, fetch the buffer once through {@link
   * #getBuffer()} and invoke the appropriate {@link MemoryBuffer} or long-encoding helper directly
   * for better performance.
   */
  public void writeInt64(long value) {
    LongSerializer.writeInt64(buffer, value, longEncoding);
  }
}
