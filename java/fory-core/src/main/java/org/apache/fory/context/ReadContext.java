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
import java.util.Iterator;
import org.apache.fory.Fory;
import org.apache.fory.config.Config;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Generics;
import org.apache.fory.type.Types;
import org.apache.fory.util.Preconditions;

/**
 * Per-operation state for deserialization.
 *
 * <p>{@code ReadContext} owns the current {@link MemoryBuffer}, ref/meta state, out-of-band buffer
 * iteration, and a small scratch map for serializers that need operation-local coordination. The
 * context is prepared by {@code Fory} for one read operation and {@link #reset()} before reuse.
 *
 * <p>Generated and hand-written serializers should treat this type as the root source of read-time
 * services instead of storing ambient state themselves.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ReadContext {
  private final Config config;
  private final Generics generics;
  private final TypeResolver typeResolver;
  private final RefReader refReader;
  private final MetaStringReader metaStringReader;
  private final StringSerializer stringSerializer;
  private final boolean crossLanguage;
  private final boolean compressInt;
  private final Int64Encoding longEncoding;
  private final int maxDepth;
  private final boolean scopedMetaShareEnabled;
  private final boolean forVirtualThread;
  private final IdentityHashMap<Object, Object> contextObjects = new IdentityHashMap<>();
  private MemoryBuffer buffer;
  private Iterator<MemoryBuffer> outOfBandBuffers;
  private MetaReadContext metaReadContext;
  private boolean peerOutOfBandEnabled;
  private int depth;

  /**
   * Creates read-side runtime state for one {@code Fory} instance.
   *
   * <p>The context can be reused across operations, but only one read operation may be active at a
   * time.
   */
  public ReadContext(
      Config config,
      Generics generics,
      TypeResolver typeResolver,
      RefReader refReader,
      MetaStringReader metaStringReader) {
    this.config = config;
    this.generics = generics;
    this.typeResolver = typeResolver;
    this.refReader = refReader;
    this.metaStringReader = metaStringReader;
    stringSerializer = (StringSerializer) typeResolver.getSerializer(String.class);
    crossLanguage = config.isXlang();
    compressInt = config.compressInt();
    longEncoding = config.longEncoding();
    maxDepth = config.maxDepth();
    forVirtualThread = config.forVirtualThread();
    scopedMetaShareEnabled = config.isScopedMetaShareEnabled();
    if (scopedMetaShareEnabled) {
      metaReadContext = new MetaReadContext();
    }
  }

  /**
   * Binds the current input buffer, optional out-of-band buffers, and peer out-of-band capability
   * flag for one operation.
   */
  public void prepare(
      MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers, boolean peerOutOfBandEnabled) {
    this.buffer = buffer;
    this.peerOutOfBandEnabled = peerOutOfBandEnabled;
    this.outOfBandBuffers = outOfBandBuffers == null ? null : outOfBandBuffers.iterator();
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

  /** Reads an unsigned byte directly from the current buffer. */
  /**
   * Reads a boolean value directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readBoolean()} directly for better performance.
   */
  public boolean readBoolean() {
    return buffer.readBoolean();
  }

  /**
   * Reads a signed byte directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readByte()} directly for better performance.
   */
  public byte readByte() {
    return buffer.readByte();
  }

  /**
   * Reads an unsigned byte directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readUInt8()} directly for better performance.
   */
  public int readUInt8() {
    return buffer.readUInt8();
  }

  /**
   * Reads a character value directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readChar()} directly for better performance.
   */
  public char readChar() {
    return buffer.readChar();
  }

  /**
   * Reads a 16-bit integer directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readInt16()} directly for better performance.
   */
  public short readInt16() {
    return buffer.readInt16();
  }

  /**
   * Reads a 32-bit integer directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readInt32()} directly for better performance.
   */
  public int readInt32() {
    return buffer.readInt32();
  }

  /**
   * Reads a 16-bit floating-point value encoded through its raw half-precision bits.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readInt16()} directly for better performance.
   */
  public Float16 readFloat16() {
    return Float16.fromBits(buffer.readInt16());
  }

  /**
   * Reads a 16-bit bfloat16 value encoded through its raw IEEE 754 bfloat16 bits.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readInt16()} directly for better performance.
   */
  public BFloat16 readBFloat16() {
    return BFloat16.fromBits(buffer.readInt16());
  }

  /**
   * Reads a 32-bit floating-point value directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readFloat32()} directly for better performance.
   */
  public float readFloat32() {
    return buffer.readFloat32();
  }

  /**
   * Reads a 64-bit floating-point value directly from the current buffer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readFloat64()} directly for better performance.
   */
  public double readFloat64() {
    return buffer.readFloat64();
  }

  /**
   * Reads a zig-zag encoded variable-length 32-bit integer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readVarInt32()} directly for better performance.
   */
  public int readVarInt32() {
    return buffer.readVarInt32();
  }

  /**
   * Reads an unsigned variable-length 32-bit integer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readVarUInt32()} directly for better performance.
   */
  public int readVarUInt32() {
    return buffer.readVarUInt32();
  }

  /**
   * Reads a zig-zag encoded variable-length 64-bit integer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readVarInt64()} directly for better performance.
   */
  public long readVarInt64() {
    return buffer.readVarInt64();
  }

  /**
   * Reads an unsigned variable-length 64-bit integer.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readVarUInt64()} directly for better performance.
   */
  public long readVarUInt64() {
    return buffer.readVarUInt64();
  }

  /**
   * Reads a tagged signed 64-bit integer using the compact small-long encoding.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readTaggedInt64()} directly for better
   * performance.
   */
  public long readTaggedInt64() {
    return buffer.readTaggedInt64();
  }

  /**
   * Reads a tagged unsigned 64-bit integer using the compact small-long encoding.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke {@link MemoryBuffer#readTaggedUInt64()} directly for better
   * performance.
   */
  public long readTaggedUInt64() {
    return buffer.readTaggedUInt64();
  }

  /** Clears all operation-local state so this context can be reused for another read. */
  public void reset() {
    refReader.reset();
    metaStringReader.reset();
    if (!contextObjects.isEmpty()) {
      contextObjects.clear();
    }
    if (scopedMetaShareEnabled) {
      metaReadContext.readTypeInfos.size = 0;
    } else {
      metaReadContext = null;
    }
    if (forVirtualThread) {
      stringSerializer.clearBuffer(config.bufferSizeLimitBytes());
    }
    buffer = null;
    outOfBandBuffers = null;
    peerOutOfBandEnabled = false;
    depth = 0;
  }

  /** Returns the immutable runtime configuration for this context. */
  public Config getConfig() {
    return config;
  }

  /** Returns the generics stack shared by the owning runtime. */
  public Generics getGenerics() {
    return generics;
  }

  /** Returns the resolver used for type lookup and metadata decoding. */
  public TypeResolver getTypeResolver() {
    return typeResolver;
  }

  /** Returns the read-side reference tracker. */
  public RefReader getRefReader() {
    return refReader;
  }

  /** Delegates to {@link RefReader#readRefOrNull(MemoryBuffer)} on the current buffer. */
  public byte readRefOrNull() {
    return refReader.readRefOrNull(buffer);
  }

  /** Delegates to {@link RefReader#preserveRefId()}. */
  public int preserveRefId() {
    return refReader.preserveRefId();
  }

  /** Delegates to {@link RefReader#preserveRefId(int)}. */
  public int preserveRefId(int refId) {
    return refReader.preserveRefId(refId);
  }

  /** Delegates to {@link RefReader#tryPreserveRefId(MemoryBuffer)} on the current buffer. */
  public int tryPreserveRefId() {
    return refReader.tryPreserveRefId(buffer);
  }

  /** Returns the last ref id preserved by the active {@link RefReader}. */
  public int lastPreservedRefId() {
    return refReader.lastPreservedRefId();
  }

  /** Returns whether the active {@link RefReader} has a pending preserved ref id. */
  public boolean hasPreservedRefId() {
    return refReader.hasPreservedRefId();
  }

  /** Binds the most recently preserved read ref id to {@code object}. */
  public void reference(Object object) {
    refReader.reference(object);
  }

  /** Returns a previously read object by ref id. */
  public Object getReadRef(int id) {
    return refReader.getReadRef(id);
  }

  /** Returns the object resolved by the last ref header that pointed to an existing instance. */
  public Object getReadRef() {
    return refReader.getReadRef();
  }

  /** Stores {@code object} under a previously preserved read ref id. */
  public void setReadRef(int id, Object object) {
    refReader.setReadRef(id, object);
  }

  /** Returns the read-side meta-string state for the current runtime. */
  public MetaStringReader getMetaStringReader() {
    return metaStringReader;
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

  /** Returns the current meta-share read context, or {@code null} when none is configured. */
  public MetaReadContext getMetaReadContext() {
    return metaReadContext;
  }

  /**
   * Installs an externally owned meta-share read context.
   *
   * <p>This is only valid when scoped meta share is disabled and the caller wants meta-share state
   * to survive across multiple operations.
   */
  public void setMetaReadContext(MetaReadContext metaReadContext) {
    Preconditions.checkArgument(!scopedMetaShareEnabled);
    this.metaReadContext = metaReadContext;
  }

  /** Returns whether the peer is allowed to send out-of-band buffers in this stream. */
  public boolean isPeerOutOfBandEnabled() {
    return peerOutOfBandEnabled;
  }

  /** Returns the current logical object-graph depth. */
  public int getDepth() {
    return depth;
  }

  /** Sets the current logical object-graph depth. */
  public void setDepth(int depth) {
    this.depth = depth;
  }

  /** Increases the logical object-graph depth by one and enforces the configured max depth. */
  public void increaseDepth() {
    if ((depth += 1) > maxDepth) {
      throw new InsecureException(
          String.format(
              "Read depth exceed max depth %s, the deserialization data may be malicious. If "
                  + "it's not malicious, please increase max read depth by "
                  + "ForyBuilder#withMaxDepth(largerDepth)",
              maxDepth));
    }
  }

  /** Increases the logical object-graph depth by {@code diff} without rechecking the max depth. */
  public void increaseDepth(int diff) {
    depth += diff;
  }

  /** Decreases the logical object-graph depth by one. */
  public void decreaseDepth() {
    depth -= 1;
  }

  /**
   * Reads a buffer object payload, resolving either an in-band slice or the next out-of-band
   * buffer.
   */
  public MemoryBuffer readBufferObject() {
    MemoryBuffer buffer = this.buffer;
    boolean inBand = buffer.readBoolean();
    if (inBand) {
      int size;
      if (!crossLanguage) {
        size = buffer.readAlignedVarUInt32();
      } else {
        size = buffer.readVarUInt32();
      }
      if (buffer.readerIndex() + size > buffer.size() && buffer.getStreamReader() != null) {
        buffer.getStreamReader().fillBuffer(buffer.readerIndex() + size - buffer.size());
      }
      MemoryBuffer slice = buffer.slice(buffer.readerIndex(), size);
      buffer.readerIndex(buffer.readerIndex() + size);
      return slice;
    }
    Preconditions.checkArgument(outOfBandBuffers.hasNext());
    return outOfBandBuffers.next();
  }

  /** Reads a non-null string payload without ref/null handling. */
  public String readString() {
    MemoryBuffer buffer = this.buffer;
    return stringSerializer.readString(buffer);
  }

  /** Reads a nullable string using the runtime string-ref policy. */
  public String readStringRef() {
    MemoryBuffer buffer = this.buffer;
    if (stringSerializer.needToWriteRef()) {
      int nextReadRefId = refReader.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        String obj = stringSerializer.read(this);
        refReader.setReadRef(nextReadRefId, obj);
        return obj;
      }
      return (String) refReader.getReadRef();
    }
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return stringSerializer.read(this);
  }

  /**
   * Reads a 64-bit integer using the configured long encoding policy.
   *
   * <p>If a caller needs multiple primitive reads, fetch the buffer once through {@link
   * #getBuffer()} and invoke the appropriate {@link MemoryBuffer} or long-encoding helper directly
   * for better performance.
   */
  public long readInt64() {
    MemoryBuffer buffer = this.buffer;
    return LongSerializer.readInt64(buffer, longEncoding);
  }

  /**
   * Reads a nullable reference-tracked object together with any required type metadata.
   *
   * <p>If the payload is a back-reference, the previously materialized instance is returned
   * directly.
   */
  public Object readRef() {
    MemoryBuffer buffer = this.buffer;
    int nextReadRefId = refReader.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      TypeInfo typeInfo = typeResolver.readTypeInfo(this);
      Object o = readNonRef(typeInfo);
      refReader.setReadRef(nextReadRefId, o);
      return o;
    }
    return refReader.getReadRef();
  }

  /** Variant of {@link #readRef()} that uses already resolved {@link TypeInfo}. */
  public Object readRef(TypeInfo typeInfo) {
    int nextReadRefId = refReader.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      Object o = readNonRef(typeInfo);
      refReader.setReadRef(nextReadRefId, o);
      return o;
    }
    return refReader.getReadRef();
  }

  /** Variant of {@link #readRef()} that reuses a cached type-info holder. */
  public Object readRef(TypeInfoHolder classInfoHolder) {
    int nextReadRefId = refReader.tryPreserveRefId(buffer);
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      TypeInfo typeInfo = typeResolver.readTypeInfo(this, classInfoHolder);
      Object o = readNonRef(typeInfo);
      refReader.setReadRef(nextReadRefId, o);
      return o;
    }
    return refReader.getReadRef();
  }

  /** Reads a nullable object using an already chosen serializer. */
  public <T> T readRef(Serializer<T> serializer) {
    if (serializer.needToWriteRef()) {
      int nextReadRefId = refReader.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        Object o = readNonRef(serializer);
        refReader.setReadRef(nextReadRefId, o);
        return (T) o;
      }
      return (T) refReader.getReadRef();
    }
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return (T) readNonRef(serializer);
  }

  /** Reads a non-null, first-seen object together with its type metadata. */
  public Object readNonRef() {
    TypeInfo typeInfo = typeResolver.readTypeInfo(this);
    return readNonRef(typeInfo);
  }

  /** Variant of {@link #readNonRef()} that reuses a cached type-info holder. */
  public Object readNonRef(TypeInfoHolder classInfoHolder) {
    TypeInfo typeInfo = typeResolver.readTypeInfo(this, classInfoHolder);
    return readNonRef(typeInfo);
  }

  /** Variant of {@link #readNonRef()} that uses already resolved {@link TypeInfo}. */
  public Object readNonRef(TypeInfo typeInfo) {
    return readDataInternal(typeInfo);
  }

  /** Reads a non-null payload using an already chosen serializer and no extra type metadata. */
  public Object readNonRef(Serializer<?> serializer) {
    increaseDepth();
    Object o = serializer.read(this);
    decreaseDepth();
    return o;
  }

  /** Reads a nullable object without ref tracking. */
  public Object readNullable() {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return readNonRef();
  }

  /** Reads a nullable value using an already chosen serializer and no ref tracking. */
  public Object readNullable(Serializer serializer) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return serializer.read(this);
  }

  /** Variant of {@link #readNullable()} that reuses a cached type-info holder. */
  public Object readNullable(TypeInfoHolder classInfoHolder) {
    byte headFlag = buffer.readByte();
    if (headFlag == Fory.NULL_FLAG) {
      return null;
    }
    return readNonRef(classInfoHolder);
  }

  /** Reads only the payload for a value whose {@link TypeInfo} was already decoded. */
  public Object readData(TypeInfo typeInfo) {
    increaseDepth();
    Serializer<?> serializer = typeInfo.getSerializer();
    Object read = serializer.read(this);
    decreaseDepth();
    return read;
  }

  private Object readDataInternal(TypeInfo typeInfo) {
    MemoryBuffer buffer = this.buffer;
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
        }
        return buffer.readInt32();
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
        if (typeInfo.getType() == String.class) {
          return stringSerializer.readString(buffer);
        }
        increaseDepth();
        Object stringLike = typeInfo.getSerializer().read(this);
        decreaseDepth();
        return stringLike;
      default:
        increaseDepth();
        Object read = typeInfo.getSerializer().read(this);
        decreaseDepth();
        return read;
    }
  }
}
