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

package org.apache.fory.format.encoder;

import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;
import org.apache.fory.exception.ClassNotCompatibleException;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;

class BinaryMapEncoder<M> implements MapEncoder<M> {
  private final Encoding format;
  private final Field mapField;
  private final BinaryArrayWriter valWriter;
  private final BinaryArrayWriter keyWriter;
  private final GeneratedMapEncoder codec;
  private final boolean sizeEmbedded;
  private final long currentHash;

  /**
   * Combined (key,value) hash → source able to compile a projection codec for an older layout.
   * {@code null} disables versioning (evolution off, no hash prefix); non-null but empty under
   * evolution with no historical combinations. Shared and immutable; a combination's codec class is
   * compiled only the first time its hash is decoded.
   */
  private final LongMap<ProjectionSource> projectionSources;

  /**
   * Per-encoder cache of projection codecs compiled on first decode of their hash. Lock-free: an
   * encoder is single-threaded (see {@link MapEncoder}), and the class compile is memoized globally
   * by the shared code generator.
   */
  private final LongMap<ProjectionMapCodec> projections;

  private final Fory fory;

  /**
   * Per-version projection codec; the {@code Encoding} and historical {@code mapField} together
   * materialize an empty map shaped for the historical layout (standard vs. compact).
   */
  static final class ProjectionMapCodec {
    final Encoding format;
    final Field mapField;
    final GeneratedMapEncoder codec;

    ProjectionMapCodec(Encoding format, Field mapField, GeneratedMapEncoder codec) {
      this.format = format;
      this.mapField = mapField;
      this.codec = codec;
    }
  }

  /** Compiles one historical (key,value) combination's projection codec on first decode. */
  interface ProjectionSource {
    ProjectionMapCodec compile(Encoding format, Fory fory);
  }

  BinaryMapEncoder(
      final Encoding format,
      final Field mapField,
      final BinaryArrayWriter valWriter,
      final BinaryArrayWriter keyWriter,
      final GeneratedMapEncoder codec,
      final boolean sizeEmbedded) {
    this(format, mapField, valWriter, keyWriter, codec, sizeEmbedded, 0L, null, null);
  }

  BinaryMapEncoder(
      final Encoding format,
      final Field mapField,
      final BinaryArrayWriter valWriter,
      final BinaryArrayWriter keyWriter,
      final GeneratedMapEncoder codec,
      final boolean sizeEmbedded,
      final long currentHash,
      final LongMap<ProjectionSource> projectionSources,
      final Fory fory) {
    this.format = format;
    this.mapField = mapField;
    this.valWriter = valWriter;
    this.keyWriter = keyWriter;
    this.codec = codec;
    this.sizeEmbedded = sizeEmbedded;
    this.currentHash = currentHash;
    this.projectionSources = projectionSources;
    this.fory = fory;
    this.projections = projectionSources == null ? null : new LongMap<>(projectionSources.size);
  }

  @Override
  public Field keyField() {
    return keyWriter.getField();
  }

  @Override
  public Field valueField() {
    return valWriter.getField();
  }

  @SuppressWarnings("unchecked")
  @Override
  public M fromMap(final BinaryArray key, final BinaryArray value) {
    return (M) codec.fromMap(key, value);
  }

  @Override
  public BinaryMap toMap(final M obj) {
    return codec.toMap(obj);
  }

  @Override
  public M decode(final MemoryBuffer buffer) {
    return decode(buffer, sizeEmbedded ? buffer.readInt32() : buffer.remaining());
  }

  @SuppressWarnings("unchecked")
  M decode(final MemoryBuffer buffer, final int size) {
    if (projectionSources == null) {
      // Evolution off: the whole payload is body, with no hash prefix. Reading evolution-on bytes
      // here cannot be caught: the map wire form has no hash slot when evolution is off, and an
      // evolution-on payload's leading 8-byte FNV hash is indistinguishable from a valid map body,
      // so it silently mis-decodes. The row-format guide documents this direction as unsupported;
      // producer and consumer must agree on the flag.
      final BinaryMap map = format.newMap(mapField);
      final int readerIndex = buffer.readerIndex();
      map.pointTo(buffer, readerIndex, size);
      buffer.readerIndex(readerIndex + size);
      return fromMap(map);
    }
    if (size < 8) {
      throw new ClassNotCompatibleException(
          "Map payload too small for an 8-byte schema hash under schema evolution: size=" + size);
    }
    long peerHash = buffer.readInt64();
    int bodySize = size - 8;
    if (peerHash == currentHash) {
      final BinaryMap map = format.newMap(mapField);
      int readerIndex = buffer.readerIndex();
      map.pointTo(buffer, readerIndex, bodySize);
      buffer.readerIndex(readerIndex + bodySize);
      return fromMap(map);
    }
    ProjectionMapCodec projection = resolveProjection(peerHash);
    if (projection == null) {
      throw new ClassNotCompatibleException(
          String.format(
              "Map (key,value) schema is not consistent. self/peer hash are %x/%x.",
              currentHash, peerHash));
    }
    BinaryMap map = projection.format.newMap(projection.mapField);
    int readerIndex = buffer.readerIndex();
    map.pointTo(buffer, readerIndex, bodySize);
    buffer.readerIndex(readerIndex + bodySize);
    return (M) projection.codec.fromMap(map);
  }

  @Override
  public M decode(final byte[] bytes) {
    // byte[] overloads ignore sizeEmbedded: encode writes no length prefix (under schema evolution
    // an 8-byte hash leads the body, but that is data, not framing), so decode takes the size from
    // bytes.length.
    return decode(MemoryUtils.wrap(bytes), bytes.length);
  }

  /**
   * The projection codec for {@code peerHash}, or {@code null} if no historical combination has
   * that hash. Compiles the codec on first encounter and caches it. Single-threaded by the {@link
   * MapEncoder} contract, so the cache needs no locking.
   */
  private ProjectionMapCodec resolveProjection(final long peerHash) {
    ProjectionMapCodec cached = projections.get(peerHash);
    if (cached != null) {
      return cached;
    }
    ProjectionSource source = projectionSources.get(peerHash);
    if (source == null) {
      return null;
    }
    ProjectionMapCodec projection = source.compile(format, fory);
    projections.put(peerHash, projection);
    return projection;
  }

  @Override
  public byte[] encode(final M obj) {
    final BinaryMap map = toMap(obj);
    if (projectionSources == null) {
      return map.getBuf().getBytes(map.getBaseOffset(), map.getSizeInBytes());
    }
    // Build the result with a single allocation: the result byte[]. The hash header is poked
    // in via LittleEndian (no buffer wrapper) and the body is bulk-copied out of the map's
    // backing buffer into the result.
    final int n = map.getSizeInBytes();
    if (n > Integer.MAX_VALUE - 8) {
      throw new EncoderException("Map body too large to prepend schema hash header: " + n);
    }
    final byte[] result = new byte[8 + n];
    LittleEndian.putInt64(result, 0, currentHash);
    map.getBuf().get(map.getBaseOffset(), result, 8, n);
    return result;
  }

  @Override
  public int encode(final MemoryBuffer buffer, final M obj) {
    final MemoryBuffer prevBuffer = keyWriter.getBuffer();
    final int writerIndex = buffer.writerIndex();
    if (sizeEmbedded) {
      buffer.writeInt32(-1);
    }
    if (projectionSources != null) {
      buffer.writeInt64(currentHash);
    }
    try {
      keyWriter.setBuffer(buffer);
      valWriter.setBuffer(buffer);
      toMap(obj);
      final int size = buffer.writerIndex() - writerIndex;
      if (sizeEmbedded) {
        buffer.putInt32(writerIndex, size - 4);
      }
      return size;
    } finally {
      keyWriter.setBuffer(prevBuffer);
      valWriter.setBuffer(prevBuffer);
    }
  }
}
