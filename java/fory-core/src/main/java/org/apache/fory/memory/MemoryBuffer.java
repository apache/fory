/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fory.memory;

import static org.apache.fory.util.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.io.AbstractStreamReader;
import org.apache.fory.io.ForyStreamReader;
import sun.misc.Unsafe;

/**
 * A class for operations on memory managed by Fory. The buffer may be backed by heap memory (byte
 * array) or by off-heap memory. Note that the buffer can auto grow on write operations and change
 * into a heap buffer when growing.
 *
 * <p>This is a byte buffer similar class with more features:
 *
 * <ul>
 *   <li>read/write data into a chunk of direct memory.
 *   <li>additional binary compare, swap, and copy methods.
 *   <li>little-endian access.
 *   <li>independent read/write index.
 *   <li>variant int/long encoding.
 *   <li>aligned int/long encoding.
 * </ul>
 *
 * <p>Note that this class is designed to final so that all the methods in this class can be inlined
 * by the just-in-time compiler.
 *
 * <p>TODO(chaokunyang) Let grow/readerIndex/writerIndex handled in this class and Make immutable
 * part as separate class, and use composition in this class. In this way, all fields can be final
 * and access will be much faster.
 *
 * <p>Warning: The instance of this class should not be hold on graalvm build time, the heap unsafe
 * offset are not correct in runtime since graalvm will change array base offset.
 *
 * <p>Note(chaokunyang): Buffer operations are very common, and jvm inline and branch elimination is
 * not reliable even in c2 compiler, so we try to inline and avoid checks as we can manually. jvm
 * jit may stop inline for some reasons: NodeCountInliningCutoff,
 * DesiredMethodLimit,MaxRecursiveInlineLevel,FreqInlineSize,MaxInlineSize
 */
public final class MemoryBuffer {
  public static final int BUFFER_GROW_STEP_THRESHOLD = 100 * 1024 * 1024;
  private static final Unsafe UNSAFE = Platform.UNSAFE;
  private static final boolean LITTLE_ENDIAN = (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);

  // If the data in on the heap, `heapMemory` will be non-null, and its' the object relative to
  // which we access the memory.
  // If we have this buffer, we must never void this reference, or the memory buffer will point
  // to undefined addresses outside the heap and may in out-of-order execution cases cause
  // buffer faults.
  private byte[] heapMemory;
  private int heapOffset;
  // If the data is off the heap, `offHeapBuffer` will be non-null, and it's the direct byte buffer
  // that allocated on the off-heap memory.
  // This memory buffer holds a reference to that buffer, so as long as this memory buffer lives,
  // the memory will not be released.
  private ByteBuffer offHeapBuffer;
  // The readable/writeable range is [address, addressLimit).
  // If the data in on the heap, this is the relative offset to the `heapMemory` byte array.
  // If the data is off the heap, this is the absolute memory address.
  private long address;
  // The address one byte after the last addressable byte, i.e. `address + size` while the
  // buffer is not disposed.
  private long addressLimit;
  // The size in bytes of the memory buffer.
  private int size;
  private int readerIndex;
  private int writerIndex;
  private final ForyStreamReader streamReader;

  /**
   * Creates a new memory buffer that represents the memory of the byte array.
   *
   * @param buffer The byte array whose memory is represented by this memory buffer.
   * @param offset The offset of the sub array to be used; must be non-negative and no larger than
   *     <tt>array.length</tt>.
   * @param length buffer size
   */
  private MemoryBuffer(byte[] buffer, int offset, int length) {
    this(buffer, offset, length, null);
  }

  /**
   * Creates a new memory buffer that represents the memory of the byte array.
   *
   * @param buffer The byte array whose memory is represented by this memory buffer.
   * @param offset The offset of the sub array to be used; must be non-negative and no larger than
   *     <tt>array.length</tt>.
   * @param length buffer size
   * @param streamReader a reader for reading from a stream.
   */
  private MemoryBuffer(byte[] buffer, int offset, int length, ForyStreamReader streamReader) {
    checkArgument(offset >= 0 && length >= 0);
    if (offset + length > buffer.length) {
      throw new IllegalArgumentException(
          String.format("%d exceeds buffer size %d", offset + length, buffer.length));
    }
    initHeapBuffer(buffer, offset, length);
    if (streamReader != null) {
      this.streamReader = streamReader;
    } else {
      this.streamReader = new BoundChecker();
    }
  }

  /**
   * Creates a new memory buffer that represents the native memory at the absolute address given by
   * the pointer.
   *
   * @param offHeapAddress The address of the memory represented by this memory buffer.
   * @param size The size of this memory buffer.
   * @param offHeapBuffer The byte buffer whose memory is represented by this memory buffer which
   *     may be null if the memory is not allocated by `DirectByteBuffer`. Hold this buffer to avoid
   *     the memory being released.
   */
  private MemoryBuffer(long offHeapAddress, int size, ByteBuffer offHeapBuffer) {
    this(offHeapAddress, size, offHeapBuffer, null);
  }

  /**
   * Creates a new memory buffer that represents the native memory at the absolute address given by
   * the pointer.
   *
   * @param offHeapAddress The address of the memory represented by this memory buffer.
   * @param size The size of this memory buffer.
   * @param offHeapBuffer The byte buffer whose memory is represented by this memory buffer which
   *     may be null if the memory is not allocated by `DirectByteBuffer`. Hold this buffer to avoid
   *     the memory being released.
   * @param streamReader a reader for reading from a stream.
   */
  private MemoryBuffer(
      long offHeapAddress, int size, ByteBuffer offHeapBuffer, ForyStreamReader streamReader) {
    initDirectBuffer(offHeapAddress, size, offHeapBuffer);
    if (streamReader != null) {
      this.streamReader = streamReader;
    } else {
      this.streamReader = new BoundChecker();
    }
  }

  public void initDirectBuffer(long offHeapAddress, int size, ByteBuffer offHeapBuffer) {
    this.offHeapBuffer = offHeapBuffer;
    if (offHeapAddress <= 0) {
      throw new IllegalArgumentException("negative pointer or size");
    }
    if (offHeapAddress >= Long.MAX_VALUE - Integer.MAX_VALUE) {
      // this is necessary to make sure the collapsed checks are safe against numeric overflows
      throw new IllegalArgumentException(
          "Buffer initialized with too large address: "
              + offHeapAddress
              + " ; Max allowed address is "
              + (Long.MAX_VALUE - Integer.MAX_VALUE - 1));
    }

    this.heapMemory = null;
    this.address = offHeapAddress;
    this.addressLimit = this.address + size;
    this.size = size;
  }

  private class BoundChecker extends AbstractStreamReader {
    @Override
    public int fillBuffer(int minFillSize) {
      throw new IndexOutOfBoundsException(
          String.format(
              "readerIndex(%d) + length(%d) exceeds size(%d): %s",
              readerIndex, minFillSize, size, this));
    }

    @Override
    public MemoryBuffer getBuffer() {
      return MemoryBuffer.this;
    }
  }

  public void initHeapBuffer(byte[] buffer, int offset, int length) {
    if (buffer == null) {
      throw new NullPointerException("buffer");
    }
    this.heapMemory = buffer;
    this.heapOffset = offset;
    final long startPos = Platform.BYTE_ARRAY_OFFSET + offset;
    this.address = startPos;
    this.size = length;
    this.addressLimit = startPos + length;
  }

  // ------------------------------------------------------------------------
  // Memory buffer Operations
  // ------------------------------------------------------------------------

  /**
   * Gets the size of the memory buffer, in bytes.
   *
   * @return The size of the memory buffer.
   */
  public int size() {
    return size;
  }

  public void increaseSize(int diff) {
    this.addressLimit = address + (size += diff);
  }

  /**
   * Checks whether this memory buffer is backed by off-heap memory.
   *
   * @return <tt>true</tt>, if the memory buffer is backed by off-heap memory, <tt>false</tt> if it
   *     is backed by heap memory.
   */
  public boolean isOffHeap() {
    return heapMemory == null;
  }

  /**
   * Returns <tt>true</tt>, if the memory buffer is backed by heap memory and memory buffer can
   * write to the whole memory region of underlying byte array.
   */
  public boolean isHeapFullyWriteable() {
    return heapMemory != null && heapOffset == 0;
  }

  /**
   * Get the heap byte array object.
   *
   * @return Return non-null if the memory is on the heap, and return null, if the memory if off the
   *     heap.
   */
  public byte[] getHeapMemory() {
    return heapMemory;
  }

  /**
   * Gets the buffer that owns the memory of this memory buffer.
   *
   * @return The byte buffer that owns the memory of this memory buffer.
   */
  public ByteBuffer getOffHeapBuffer() {
    if (offHeapBuffer != null) {
      return offHeapBuffer;
    } else {
      throw new IllegalStateException("Memory buffer does not represent off heap ByteBuffer");
    }
  }

  /**
   * Returns the byte array of on-heap memory buffers.
   *
   * @return underlying byte array
   * @throws IllegalStateException if the memory buffer does not represent on-heap memory
   */
  public byte[] getArray() {
    if (heapMemory != null) {
      return heapMemory;
    } else {
      throw new IllegalStateException("Memory buffer does not represent heap memory");
    }
  }

  /**
   * Returns the memory address of off-heap memory buffers.
   *
   * @return absolute memory address outside the heap
   * @throws IllegalStateException if the memory buffer does not represent off-heap memory
   */
  public long getAddress() {
    if (heapMemory == null) {
      return address;
    } else {
      throw new IllegalStateException("Memory buffer does not represent off heap memory");
    }
  }

  public long getUnsafeAddress() {
    return address;
  }

  // ------------------------------------------------------------------------
  //                    Random Access get() and put() methods
  // ------------------------------------------------------------------------

  private void checkPosition(long index, long pos, long length) {
    if (BoundsChecking.BOUNDS_CHECKING_ENABLED) {
      if (index < 0 || pos > addressLimit - length) {
        throwOOBException();
      }
    }
  }

  public void get(int index, byte[] dst) {
    get(index, dst, 0, dst.length);
  }

  public void get(int index, byte[] dst, int offset, int length) {
    final byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      // System.arraycopy faster for some jdk than Unsafe.
      System.arraycopy(heapMemory, heapOffset + index, dst, offset, length);
    } else {
      final long pos = address + index;
      if ((index
              | offset
              | length
              | (offset + length)
              | (dst.length - (offset + length))
              | addressLimit - length - pos)
          < 0) {
        throwOOBException();
      }
      Platform.copyMemory(null, pos, dst, Platform.BYTE_ARRAY_OFFSET + offset, length);
    }
  }

  public void get(int offset, ByteBuffer target, int numBytes) {
    if ((offset | numBytes | (offset + numBytes)) < 0) {
      throwOOBException();
    }
    if (target.remaining() < numBytes) {
      throwOOBException();
    }
    if (target.isReadOnly()) {
      throw new IllegalArgumentException("read only buffer");
    }
    final int targetPos = target.position();
    if (target.isDirect()) {
      final long targetAddr = ByteBufferUtil.getAddress(target) + targetPos;
      final long sourceAddr = address + offset;
      if (sourceAddr <= addressLimit - numBytes) {
        Platform.copyMemory(heapMemory, sourceAddr, null, targetAddr, numBytes);
      } else {
        throwOOBException();
      }
    } else {
      assert target.hasArray();
      get(offset, target.array(), targetPos + target.arrayOffset(), numBytes);
    }
    ByteBufferUtil.position(target, targetPos + numBytes);
  }

  public void put(int offset, ByteBuffer source, int numBytes) {
    final int remaining = source.remaining();
    if ((offset | numBytes | (offset + numBytes) | (remaining - numBytes)) < 0) {
      throwOOBException();
    }
    final int sourcePos = source.position();
    if (source.isDirect()) {
      final long sourceAddr = ByteBufferUtil.getAddress(source) + sourcePos;
      final long targetAddr = address + offset;
      if (targetAddr <= addressLimit - numBytes) {
        Platform.copyMemory(null, sourceAddr, heapMemory, targetAddr, numBytes);
      } else {
        throwOOBException();
      }
    } else {
      assert source.hasArray();
      put(offset, source.array(), sourcePos + source.arrayOffset(), numBytes);
    }
    ByteBufferUtil.position(source, sourcePos + numBytes);
  }

  public void put(int index, byte[] src) {
    put(index, src, 0, src.length);
  }

  public void put(int index, byte[] src, int offset, int length) {
    final byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      // System.arraycopy faster for some jdk than Unsafe.
      System.arraycopy(src, offset, heapMemory, heapOffset + index, length);
    } else {
      final long pos = address + index;
      // check the byte array offset and length
      if ((index
              | offset
              | length
              | (offset + length)
              | (src.length - (offset + length))
              | addressLimit - length - pos)
          < 0) {
        throwOOBException();
      }
      final long arrayAddress = Platform.BYTE_ARRAY_OFFSET + offset;
      Platform.copyMemory(src, arrayAddress, null, pos, length);
    }
  }

  public byte getByte(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 1);
    return UNSAFE.getByte(heapMemory, pos);
  }

  public void putByte(int index, int b) {
    final long pos = address + index;
    checkPosition(index, pos, 1);
    UNSAFE.putByte(heapMemory, pos, (byte) b);
  }

  public void putByte(int index, byte b) {
    final long pos = address + index;
    checkPosition(index, pos, 1);
    UNSAFE.putByte(heapMemory, pos, b);
  }

  public boolean getBoolean(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 1);
    return UNSAFE.getByte(heapMemory, pos) != 0;
  }

  public void putBoolean(int index, boolean value) {
    UNSAFE.putByte(heapMemory, address + index, (value ? (byte) 1 : (byte) 0));
  }

  public char getChar(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 2);
    char c = UNSAFE.getChar(heapMemory, pos);
    return LITTLE_ENDIAN ? c : Character.reverseBytes(c);
  }

  public void putChar(int index, char value) {
    final long pos = address + index;
    checkPosition(index, pos, 2);
    if (!LITTLE_ENDIAN) {
      value = Character.reverseBytes(value);
    }
    UNSAFE.putChar(heapMemory, pos, value);
  }

  public short getInt16(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 2);
    short v = UNSAFE.getShort(heapMemory, pos);
    return LITTLE_ENDIAN ? v : Short.reverseBytes(v);
  }

  public void putInt16(int index, short value) {
    final long pos = address + index;
    checkPosition(index, pos, 2);
    if (!LITTLE_ENDIAN) {
      value = Short.reverseBytes(value);
    }
    UNSAFE.putShort(heapMemory, pos, value);
  }

  public int getInt32(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 4);
    int v = UNSAFE.getInt(heapMemory, pos);
    return LITTLE_ENDIAN ? v : Integer.reverseBytes(v);
  }

  public void putInt32(int index, int value) {
    final long pos = address + index;
    checkPosition(index, pos, 4);
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    UNSAFE.putInt(heapMemory, pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  private int _unsafeGetInt32(int index) {
    // CHECKSTYLE.ON:MethodName
    int v = UNSAFE.getInt(heapMemory, address + index);
    return LITTLE_ENDIAN ? v : Integer.reverseBytes(v);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutInt32(int index, int value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    UNSAFE.putInt(heapMemory, address + index, value);
  }

  public long getInt64(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 8);
    long v = UNSAFE.getLong(heapMemory, pos);
    return LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  public void putInt64(int index, long value) {
    final long pos = address + index;
    checkPosition(index, pos, 8);
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    UNSAFE.putLong(heapMemory, pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  long _unsafeGetInt64(int index) {
    // CHECKSTYLE.ON:MethodName
    long v = UNSAFE.getLong(heapMemory, address + index);
    return LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  // CHECKSTYLE.OFF:MethodName
  private void _unsafePutInt64(int index, long value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    UNSAFE.putLong(heapMemory, address + index, value);
  }

  public float getFloat32(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 8);
    int v = UNSAFE.getInt(heapMemory, pos);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    return Float.intBitsToFloat(v);
  }

  public void putFloat32(int index, float value) {
    final long pos = address + index;
    checkPosition(index, pos, 4);
    int v = Float.floatToRawIntBits(value);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    UNSAFE.putInt(heapMemory, pos, v);
  }

  public double getFloat64(int index) {
    final long pos = address + index;
    checkPosition(index, pos, 8);
    long v = UNSAFE.getLong(heapMemory, pos);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    return Double.longBitsToDouble(v);
  }

  public void putFloat64(int index, double value) {
    final long pos = address + index;
    checkPosition(index, pos, 8);
    long v = Double.doubleToRawLongBits(value);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    UNSAFE.putLong(heapMemory, pos, v);
  }

  // Check should be done outside to avoid this method got into the critical path.
  private void throwOOBException() {
    throw new IndexOutOfBoundsException(
        String.format("size: %d, address %s, addressLimit %d", size, address, addressLimit));
  }

  // -------------------------------------------------------------------------
  //                          Write Methods
  // -------------------------------------------------------------------------

  /** Returns the {@code writerIndex} of this buffer. */
  public int writerIndex() {
    return writerIndex;
  }

  /**
   * Sets the {@code writerIndex} of this buffer.
   *
   * @throws IndexOutOfBoundsException if the specified {@code writerIndex} is less than {@code 0}
   *     or greater than {@code this.size}
   */
  public void writerIndex(int writerIndex) {
    if (writerIndex < 0 || writerIndex > size) {
      throwOOBExceptionForWriteIndex(writerIndex);
    }
    this.writerIndex = writerIndex;
  }

  private void throwOOBExceptionForWriteIndex(int writerIndex) {
    throw new IndexOutOfBoundsException(
        String.format(
            "writerIndex: %d (expected: 0 <= writerIndex <= size(%d))", writerIndex, size));
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafeWriterIndex(int writerIndex) {
    // CHECKSTYLE.ON:MethodName
    this.writerIndex = writerIndex;
  }

  /** Returns heap index for writer index if buffer is a heap buffer. */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeHeapWriterIndex() {
    // CHECKSTYLE.ON:MethodName
    return writerIndex + heapOffset;
  }

  // CHECKSTYLE.OFF:MethodName
  public long _unsafeWriterAddress() {
    // CHECKSTYLE.ON:MethodName
    return address + writerIndex;
  }

  // CHECKSTYLE.OFF:MethodName
  public void _increaseWriterIndexUnsafe(int diff) {
    // CHECKSTYLE.ON:MethodName
    this.writerIndex = writerIndex + diff;
  }

  /** Increase writer index and grow buffer if needed. */
  public void increaseWriterIndex(int diff) {
    int writerIdx = writerIndex + diff;
    ensure(writerIdx);
    this.writerIndex = writerIdx;
  }

  public void writeBoolean(boolean value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 1;
    ensure(newIdx);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (value ? 1 : 0));
    writerIndex = newIdx;
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafeWriteByte(byte value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 1;
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, value);
    writerIndex = newIdx;
  }

  public void writeByte(byte value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 1;
    ensure(newIdx);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, value);
    writerIndex = newIdx;
  }

  public void writeByte(int value) {
    writeByte((byte) value);
  }

  public void writeChar(char value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 2;
    ensure(newIdx);
    final long pos = address + writerIdx;
    if (!LITTLE_ENDIAN) {
      value = Character.reverseBytes(value);
    }
    UNSAFE.putChar(heapMemory, pos, value);
    writerIndex = newIdx;
  }

  public void writeInt16(short value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 2;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Short.reverseBytes(value);
    }
    UNSAFE.putShort(heapMemory, address + writerIdx, value);
    writerIndex = newIdx;
  }

  public void writeInt32(int value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 4;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    UNSAFE.putInt(heapMemory, address + writerIdx, value);
    writerIndex = newIdx;
  }

  public void writeInt64(long value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 8;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    UNSAFE.putLong(heapMemory, address + writerIdx, value);
    writerIndex = newIdx;
  }

  public void writeFloat32(float value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 4;
    ensure(newIdx);
    int v = Float.floatToRawIntBits(value);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    UNSAFE.putInt(heapMemory, address + writerIdx, v);
    writerIndex = newIdx;
  }

  public void writeFloat64(double value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 8;
    ensure(newIdx);
    long v = Double.doubleToRawLongBits(value);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    UNSAFE.putLong(heapMemory, address + writerIdx, v);
    writerIndex = newIdx;
  }

  /**
   * Write int using variable length encoding. If the value is positive, use {@link #writeVarUint32}
   * to save one bit.
   */
  public int writeVarInt32(int v) {
    ensure(writerIndex + 8);
    int varintBytes = _unsafePutVarUint36Small(writerIndex, ((long) v << 1) ^ (v >> 31));
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * For implementation efficiency, this method needs at most 8 bytes for writing 5 bytes using long
   * to avoid using two memory operations.
   */
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteVarInt32(int v) {
    // CHECKSTYLE.ON:MethodName
    // Ensure negatives close to zero is encode in little bytes.
    int varintBytes = _unsafePutVarUint36Small(writerIndex, ((long) v << 1) ^ (v >> 31));
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * Writes a 1-5 byte int.
   *
   * @return The number of bytes written.
   */
  public int writeVarUint32(int v) {
    // ensure at least 8 bytes are writable at once, so jvm-jit
    // generated code is smaller. Otherwise, `MapRefResolver.writeRefOrNull`
    // may be `callee is too large`/`already compiled into a big method`
    ensure(writerIndex + 8);
    int varintBytes = _unsafePutVarUint36Small(writerIndex, v);
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * For implementation efficiency, this method needs at most 8 bytes for writing 5 bytes using long
   * to avoid using two memory operations.
   */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteVarUint32(int v) {
    // CHECKSTYLE.ON:MethodName
    int varintBytes = _unsafePutVarUint36Small(writerIndex, v);
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * Fast method for write an unsigned varint which is mostly a small value in 7 bits value in [0,
   * 127). When the value is equal or greater than 127, the write will be a little slower.
   */
  public int writeVarUint32Small7(int value) {
    ensure(writerIndex + 8);
    if (value >>> 7 == 0) {
      UNSAFE.putByte(heapMemory, address + writerIndex++, (byte) value);
      return 1;
    }
    return continueWriteVarUint32Small7(value);
  }

  private int continueWriteVarUint32Small7(int value) {
    long encoded = (value & 0x7F);
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    int writerIdx = writerIndex;
    if (value >>> 14 == 0) {
      _unsafePutInt32(writerIdx, (int) encoded);
      writerIndex += 2;
      return 2;
    }
    int diff = continuePutVarInt36(writerIdx, encoded, value);
    writerIndex += diff;
    return diff;
  }

  /**
   * Caller must ensure there must be at least 8 bytes for writing, otherwise the crash may occur.
   */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafePutVarUint36Small(int index, long value) {
    // CHECKSTYLE.ON:MethodName
    long encoded = (value & 0x7F);
    if (value >>> 7 == 0) {
      UNSAFE.putByte(heapMemory, address + index, (byte) value);
      return 1;
    }
    // bit 8 `set` indicates have next data bytes.
    // 0x3f80: 0b1111111 << 7
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    if (value >>> 14 == 0) {
      _unsafePutInt32(index, (int) encoded);
      return 2;
    }
    return continuePutVarInt36(index, encoded, value);
  }

  private int continuePutVarInt36(int index, long encoded, long value) {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      _unsafePutInt32(index, (int) encoded);
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      _unsafePutInt32(index, (int) encoded);
      return 4;
    }
    // 0xff0000000: 0b11111111 << 28. Note eight `1` here instead of seven.
    encoded |= ((value & 0xff0000000L) << 4) | 0x80000000L;
    _unsafePutInt64(index, encoded);
    return 5;
  }

  /**
   * Writes a 1-9 byte int, padding necessary bytes to align `writerIndex` to 4-byte.
   *
   * @return The number of bytes written.
   */
  public int writeVarUint32Aligned(int value) {
    // Mask first 6 bits,
    // bit 7 `unset` indicates have next padding bytes,
    // bit 8 `set` indicates have next data bytes.
    if (value >>> 6 == 0) {
      return writeVarUint32Aligned1(value);
    }
    if (value >>> 12 == 0) { // 2 byte data
      return writeVarUint32Aligned2(value);
    }
    if (value >>> 18 == 0) { // 3 byte data
      return writeVarUint32Aligned3(value);
    }
    if (value >>> 24 == 0) { // 4 byte data
      return writeVarUint32Aligned4(value);
    }
    if (value >>> 30 == 0) { // 5 byte data
      return writeVarUint32Aligned5(value);
    }
    // 6 byte data
    return writeVarUint32Aligned6(value);
  }

  private int writeVarUint32Aligned1(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 5); // 1 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    if (numPaddingBytes == 1) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x40));
      writerIndex = (writerIdx + 1);
      return 1;
    } else {
      UNSAFE.putByte(heapMemory, pos, (byte) first);
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 1, 0);
      UNSAFE.putByte(heapMemory, pos + numPaddingBytes - 1, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes;
      return numPaddingBytes;
    }
  }

  private int writeVarUint32Aligned2(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 6); // 2 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x80));
    if (numPaddingBytes == 2) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos + 1, (byte) ((value >>> 6) | 0x40));
      writerIndex = writerIdx + 2;
      return 2;
    } else {
      UNSAFE.putByte(heapMemory, pos + 1, (byte) (value >>> 6));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 2, 0);
      if (numPaddingBytes > 2) {
        UNSAFE.putByte(heapMemory, pos + numPaddingBytes - 1, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes;
        return numPaddingBytes;
      } else {
        UNSAFE.putByte(heapMemory, pos + 4, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  private int writeVarUint32Aligned3(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 7); // 3 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x80));
    UNSAFE.putByte(heapMemory, pos + 1, (byte) ((value >>> 6) | 0x80));
    if (numPaddingBytes == 3) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos + 2, (byte) ((value >>> 12) | 0x40));
      writerIndex = writerIdx + 3;
      return 3;
    } else {
      UNSAFE.putByte(heapMemory, pos + 2, (byte) (value >>> 12));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 3, 0);
      if (numPaddingBytes == 4) {
        UNSAFE.putByte(heapMemory, pos + numPaddingBytes - 1, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes;
        return numPaddingBytes;
      } else {
        UNSAFE.putByte(heapMemory, pos + numPaddingBytes + 3, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  private int writeVarUint32Aligned4(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 8); // 4 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x80));
    UNSAFE.putByte(heapMemory, pos + 1, (byte) (value >>> 6 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 2, (byte) (value >>> 12 | 0x80));
    if (numPaddingBytes == 4) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos + 3, (byte) ((value >>> 18) | 0x40));
      writerIndex = writerIdx + 4;
      return 4;
    } else {
      UNSAFE.putByte(heapMemory, pos + 3, (byte) (value >>> 18));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 4, 0);
      UNSAFE.putByte(heapMemory, pos + numPaddingBytes + 3, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes + 4;
      return numPaddingBytes + 4;
    }
  }

  private int writeVarUint32Aligned5(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 9); // 5 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x80));
    UNSAFE.putByte(heapMemory, pos + 1, (byte) (value >>> 6 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 2, (byte) (value >>> 12 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 3, (byte) (value >>> 18 | 0x80));
    if (numPaddingBytes == 1) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos + 4, (byte) ((value >>> 24) | 0x40));
      writerIndex = writerIdx + 5;
      return 5;
    } else {
      UNSAFE.putByte(heapMemory, pos + 4, (byte) (value >>> 24));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 5, 0);
      UNSAFE.putByte(heapMemory, pos + numPaddingBytes + 3, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes + 4;
      return numPaddingBytes + 4;
    }
  }

  private int writeVarUint32Aligned6(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 10); // 6 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    UNSAFE.putByte(heapMemory, pos, (byte) (first | 0x80));
    UNSAFE.putByte(heapMemory, pos + 1, (byte) (value >>> 6 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 2, (byte) (value >>> 12 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 3, (byte) (value >>> 18 | 0x80));
    UNSAFE.putByte(heapMemory, pos + 4, (byte) (value >>> 24 | 0x80));
    if (numPaddingBytes == 2) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      UNSAFE.putByte(heapMemory, pos + 5, (byte) ((value >>> 30) | 0x40));
      writerIndex = writerIdx + 6;
      return 6;
    } else {
      UNSAFE.putByte(heapMemory, pos + 5, (byte) (value >>> 30));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      UNSAFE.putInt(heapMemory, pos + 6, 0);
      if (numPaddingBytes == 1) {
        UNSAFE.putByte(heapMemory, pos + 8, (byte) (0x40));
        writerIndex = writerIdx + 9;
        return 9;
      } else {
        UNSAFE.putByte(heapMemory, pos + numPaddingBytes + 3, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  /**
   * Write long using variable length encoding. If the value is positive, use {@link
   * #writeVarUint64} to save one bit.
   */
  public int writeVarInt64(long value) {
    ensure(writerIndex + 9);
    return _unsafeWriteVarUint64((value << 1) ^ (value >> 63));
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteVarInt64(long value) {
    // CHECKSTYLE.ON:MethodName
    return _unsafeWriteVarUint64((value << 1) ^ (value >> 63));
  }

  public int writeVarUint64(long value) {
    // Var long encoding algorithm is based kryo UnsafeMemoryOutput.writeVarInt64.
    // var long are written using little endian byte order.
    ensure(writerIndex + 9);
    return _unsafeWriteVarUint64(value);
  }

  // CHECKSTYLE.OFF:MethodName
  @CodegenInvoke
  public int _unsafeWriteVarUint64(long value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIndex = this.writerIndex;
    int varInt;
    varInt = (int) (value & 0x7F);
    if (value >>> 7 == 0) {
      UNSAFE.putByte(heapMemory, address + writerIndex, (byte) varInt);
      this.writerIndex = writerIndex + 1;
      return 1;
    }
    varInt |= (int) (((value & 0x3f80) << 1) | 0x80);
    if (value >>> 14 == 0) {
      _unsafePutInt32(writerIndex, varInt);
      this.writerIndex = writerIndex + 2;
      return 2;
    }
    varInt |= (int) (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      _unsafePutInt32(writerIndex, varInt);
      this.writerIndex = writerIndex + 3;
      return 3;
    }
    varInt |= (int) (((value & 0xfe00000) << 3) | 0x800000);
    if (value >>> 28 == 0) {
      _unsafePutInt32(writerIndex, varInt);
      this.writerIndex = writerIndex + 4;
      return 4;
    }
    long varLong = (varInt & 0xFFFFFFFFL);
    varLong |= ((value & 0x7f0000000L) << 4) | 0x80000000L;
    if (value >>> 35 == 0) {
      _unsafePutInt64(writerIndex, varLong);
      this.writerIndex = writerIndex + 5;
      return 5;
    }
    varLong |= ((value & 0x3f800000000L) << 5) | 0x8000000000L;
    if (value >>> 42 == 0) {
      _unsafePutInt64(writerIndex, varLong);
      this.writerIndex = writerIndex + 6;
      return 6;
    }
    varLong |= ((value & 0x1fc0000000000L) << 6) | 0x800000000000L;
    if (value >>> 49 == 0) {
      _unsafePutInt64(writerIndex, varLong);
      this.writerIndex = writerIndex + 7;
      return 7;
    }
    varLong |= ((value & 0xfe000000000000L) << 7) | 0x80000000000000L;
    value >>>= 56;
    if (value == 0) {
      _unsafePutInt64(writerIndex, varLong);
      this.writerIndex = writerIndex + 8;
      return 8;
    }
    _unsafePutInt64(writerIndex, varLong | 0x8000000000000000L);
    UNSAFE.putByte(heapMemory, address + writerIndex + 8, (byte) (value & 0xFF));
    this.writerIndex = writerIndex + 9;
    return 9;
  }

  /**
   * Write long using fory SLI(Small long as int) encoding. If long is in [0xc0000000, 0x3fffffff],
   * encode as 4 bytes int: | little-endian: ((int) value) << 1 |; Otherwise write as 9 bytes: | 0b1
   * | little-endian 8bytes long |
   */
  public int writeSliInt64(long value) {
    ensure(writerIndex + 9);
    return _unsafeWriteSliInt64(value);
  }

  private static final long HALF_MAX_INT_VALUE = Integer.MAX_VALUE / 2;
  private static final long HALF_MIN_INT_VALUE = Integer.MIN_VALUE / 2;
  private static final byte BIG_LONG_FLAG = 0b1; // bit 0 set, means big long.

  /** Write long using fory SLI(Small Long as Int) encoding. */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteSliInt64(long value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIndex = this.writerIndex;
    final long pos = address + writerIndex;
    final byte[] heapMemory = this.heapMemory;
    if (value >= HALF_MIN_INT_VALUE && value <= HALF_MAX_INT_VALUE) {
      // write:
      // 00xxx -> 0xxx
      // 11xxx -> 1xxx
      // read:
      // 0xxx -> 00xxx
      // 1xxx -> 11xxx
      int v = ((int) value) << 1; // bit 0 unset, means int.
      if (!LITTLE_ENDIAN) {
        v = Integer.reverseBytes(v);
      }
      UNSAFE.putInt(heapMemory, pos, v);
      this.writerIndex = writerIndex + 4;
      return 4;
    } else {
      UNSAFE.putByte(heapMemory, pos, BIG_LONG_FLAG);
      if (!LITTLE_ENDIAN) {
        value = Long.reverseBytes(value);
      }
      UNSAFE.putLong(heapMemory, pos + 1, value);
      this.writerIndex = writerIndex + 9;
      return 9;
    }
  }

  public void writeBytes(byte[] bytes) {
    writeBytes(bytes, 0, bytes.length);
  }

  public void writeBytes(byte[] bytes, int offset, int length) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + length;
    ensure(newIdx);
    put(writerIdx, bytes, offset, length);
    writerIndex = newIdx;
  }

  public void write(ByteBuffer source) {
    write(source, source.remaining());
  }

  public void write(ByteBuffer source, int numBytes) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    put(writerIdx, source, numBytes);
    writerIndex = newIdx;
  }

  /** Write a primitive array into buffer with size varint encoded into the buffer. */
  public void writePrimitiveArrayWithSize(Object arr, int offset, int numBytes) {
    int idx = writerIndex;
    ensure(idx + 5 + numBytes);
    idx += _unsafeWriteVarUint32(numBytes);
    Platform.copyMemory(arr, offset, heapMemory, address + idx, numBytes);
    writerIndex = idx + numBytes;
  }

  public void writePrimitiveArrayAlignedSize(Object arr, int offset, int numBytes) {
    writeVarUint32Aligned(numBytes);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    Platform.copyMemory(arr, offset, heapMemory, address + writerIdx, numBytes);
    writerIndex = newIdx;
  }

  public void writePrimitiveArray(Object arr, int offset, int numBytes) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    Platform.copyMemory(arr, offset, heapMemory, address + writerIdx, numBytes);
    writerIndex = newIdx;
  }

  /** For off-heap buffer, this will make a heap buffer internally. */
  public void grow(int neededSize) {
    int length = writerIndex + neededSize;
    if (length > size) {
      growBuffer(length);
    }
  }

  /** For off-heap buffer, this will make a heap buffer internally. */
  public void ensure(int length) {
    if (length > size) {
      growBuffer(length);
    }
  }

  private void growBuffer(int length) {
    int newSize =
        length < BUFFER_GROW_STEP_THRESHOLD
            ? length << 2
            : (int) Math.min(length * 1.5d, Integer.MAX_VALUE - 8);
    byte[] data = new byte[newSize];
    copyToUnsafe(0, data, Platform.BYTE_ARRAY_OFFSET, size());
    initHeapBuffer(data, 0, data.length);
  }

  // -------------------------------------------------------------------------
  //                          Read Methods
  // -------------------------------------------------------------------------

  // Check should be done outside to avoid this method got into the critical path.
  private void throwIndexOOBExceptionForRead() {
    throw new IndexOutOfBoundsException(
        String.format(
            "readerIndex: %d (expected: 0 <= readerIndex <= size(%d))", readerIndex, size));
  }

  // Check should be done outside to avoid this method got into the critical path.
  private void throwIndexOOBExceptionForRead(int length) {
    throw new IndexOutOfBoundsException(
        String.format(
            "readerIndex: %d (expected: 0 <= readerIndex <= size(%d)), length %d",
            readerIndex, size, length));
  }

  /** Returns the {@code readerIndex} of this buffer. */
  public int readerIndex() {
    return readerIndex;
  }

  /**
   * Sets the {@code readerIndex} of this buffer.
   *
   * @throws IndexOutOfBoundsException if the specified {@code readerIndex} is less than {@code 0}
   *     or greater than {@code this.size}
   */
  public void readerIndex(int readerIndex) {
    if (readerIndex < 0) {
      throwIndexOOBExceptionForRead();
    } else if (readerIndex > size) {
      // in this case, diff must be greater than 0.
      streamReader.fillBuffer(readerIndex - size);
    }
    this.readerIndex = readerIndex;
  }

  /** Returns array index for reader index if buffer is a heap buffer. */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeHeapReaderIndex() {
    // CHECKSTYLE.ON:MethodName
    return readerIndex + heapOffset;
  }

  // CHECKSTYLE.OFF:MethodName
  public void _increaseReaderIndexUnsafe(int diff) {
    // CHECKSTYLE.ON:MethodName
    readerIndex += diff;
  }

  public void increaseReaderIndex(int diff) {
    int readerIdx = readerIndex;
    readerIndex = readerIdx += diff;
    if (readerIdx < 0) {
      throwIndexOOBExceptionForRead();
    } else if (readerIdx > size) {
      // in this case, diff must be greater than 0.
      streamReader.fillBuffer(readerIdx - size);
    }
  }

  public long getUnsafeReaderAddress() {
    return address + readerIndex;
  }

  public int remaining() {
    return size - readerIndex;
  }

  public boolean readBoolean() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx > size - 1) {
      streamReader.fillBuffer(1);
    }
    readerIndex = readerIdx + 1;
    return UNSAFE.getByte(heapMemory, address + readerIdx) != 0;
  }

  public int readUnsignedByte() {
    int readerIdx = readerIndex;
    if (readerIdx > size - 1) {
      streamReader.fillBuffer(1);
    }
    readerIndex = readerIdx + 1;
    int v = UNSAFE.getByte(heapMemory, address + readerIdx);
    v &= 0b11111111;
    return v;
  }

  public byte readByte() {
    int readerIdx = readerIndex;
    if (readerIdx > size - 1) {
      streamReader.fillBuffer(1);
    }
    readerIndex = readerIdx + 1;
    return UNSAFE.getByte(heapMemory, address + readerIdx);
  }

  public char readChar() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 2) {
      streamReader.fillBuffer(2 - remaining);
    }
    readerIndex = readerIdx + 2;
    char c = UNSAFE.getChar(heapMemory, address + readerIdx);
    return LITTLE_ENDIAN ? c : Character.reverseBytes(c);
  }

  public short readInt16() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 2) {
      streamReader.fillBuffer(2 - remaining);
    }
    readerIndex = readerIdx + 2;
    short v = UNSAFE.getShort(heapMemory, address + readerIdx);
    return LITTLE_ENDIAN ? v : Short.reverseBytes(v);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public short _readInt16OnLE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 2) {
      streamReader.fillBuffer(2 - remaining);
    }
    readerIndex = readerIdx + 2;
    return UNSAFE.getShort(heapMemory, address + readerIdx);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public short _readInt16OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 2) {
      streamReader.fillBuffer(2 - remaining);
    }
    readerIndex = readerIdx + 2;
    return Short.reverseBytes(UNSAFE.getShort(heapMemory, address + readerIdx));
  }

  public int readInt32() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    int v = UNSAFE.getInt(heapMemory, address + readerIdx);
    return LITTLE_ENDIAN ? v : Integer.reverseBytes(v);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _readInt32OnLE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    return UNSAFE.getInt(heapMemory, address + readerIdx);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _readInt32OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    return Integer.reverseBytes(UNSAFE.getInt(heapMemory, address + readerIdx));
  }

  public long readInt64() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    long v = UNSAFE.getLong(heapMemory, address + readerIdx);
    return LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readInt64OnLE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    return UNSAFE.getLong(heapMemory, address + readerIdx);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readInt64OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    return Long.reverseBytes(UNSAFE.getLong(heapMemory, address + readerIdx));
  }

  /** Read fory SLI(Small Long as Int) encoded long. */
  public long readSliInt64() {
    if (LITTLE_ENDIAN) {
      return _readSliInt64OnLE();
    } else {
      return _readSliInt64OnBE();
    }
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readSliInt64OnLE() {
    // CHECKSTYLE.ON:MethodName
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = UNSAFE.getInt(heapMemory, address + readIdx);
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >> 1;
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return UNSAFE.getLong(heapMemory, address + readIdx + 1);
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readSliInt64OnBE() {
    // CHECKSTYLE.ON:MethodName
    // noinspection Duplicates
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = Integer.reverseBytes(UNSAFE.getInt(heapMemory, address + readIdx));
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >> 1;
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return Long.reverseBytes(UNSAFE.getLong(heapMemory, address + readIdx + 1));
  }

  public float readFloat32() {
    // noinspection Duplicates
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    int v = UNSAFE.getInt(heapMemory, address + readerIdx);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    return Float.intBitsToFloat(v);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public float _readFloat32OnLE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    return Float.intBitsToFloat(UNSAFE.getInt(heapMemory, address + readerIdx));
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public float _readFloat32OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    return Float.intBitsToFloat(
        Integer.reverseBytes(UNSAFE.getInt(heapMemory, address + readerIdx)));
  }

  public double readFloat64() {
    // noinspection Duplicates
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    long v = UNSAFE.getLong(heapMemory, address + readerIdx);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    return Double.longBitsToDouble(v);
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public double _readFloat64OnLE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    return Double.longBitsToDouble(UNSAFE.getLong(heapMemory, address + readerIdx));
  }

  // Reduce method body for better inline in the caller.
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public double _readFloat64OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    return Double.longBitsToDouble(
        Long.reverseBytes(UNSAFE.getLong(heapMemory, address + readerIdx)));
  }

  /** Reads the 1-5 byte int part of a varint. */
  @CodegenInvoke
  public int readVarInt32() {
    if (LITTLE_ENDIAN) {
      return _readVarInt32OnLE();
    } else {
      return _readVarInt32OnBE();
    }
  }

  /** Reads the 1-5 byte as a varint on a little endian machine. */
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _readVarInt32OnLE() {
    // CHECKSTYLE.ON:MethodName
    // noinspection Duplicates
    int readIdx = readerIndex;
    int result;
    if (size - readIdx < 5) {
      result = (int) readVarUint36Slow();
    } else {
      long address = this.address;
      // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
      int fourByteValue = UNSAFE.getInt(heapMemory, address + readIdx);
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      readIdx++;
      result = fourByteValue & 0x7F;
      if ((fourByteValue & 0x80) != 0) {
        readIdx++;
        // 0x3f80: 0b1111111 << 7
        result |= (fourByteValue >>> 1) & 0x3f80;
        // 0x8000: 0b1 << 15
        if ((fourByteValue & 0x8000) != 0) {
          readIdx++;
          // 0x1fc000: 0b1111111 << 14
          result |= (fourByteValue >>> 2) & 0x1fc000;
          // 0x800000: 0b1 << 23
          if ((fourByteValue & 0x800000) != 0) {
            readIdx++;
            // 0xfe00000: 0b1111111 << 21
            result |= (fourByteValue >>> 3) & 0xfe00000;
            if ((fourByteValue & 0x80000000) != 0) {
              result |= (UNSAFE.getByte(heapMemory, address + readIdx++) & 0x7F) << 28;
            }
          }
        }
      }
      readerIndex = readIdx;
    }
    return (result >>> 1) ^ -(result & 1);
  }

  /** Reads the 1-5 byte as a varint on a big endian machine. */
  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _readVarInt32OnBE() {
    // CHECKSTYLE.ON:MethodName
    // noinspection Duplicates
    int readIdx = readerIndex;
    int result;
    if (size - readIdx < 5) {
      result = (int) readVarUint36Slow();
    } else {
      long address = this.address;
      int fourByteValue = Integer.reverseBytes(UNSAFE.getInt(heapMemory, address + readIdx));
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      readIdx++;
      result = fourByteValue & 0x7F;
      if ((fourByteValue & 0x80) != 0) {
        readIdx++;
        // 0x3f80: 0b1111111 << 7
        result |= (fourByteValue >>> 1) & 0x3f80;
        // 0x8000: 0b1 << 15
        if ((fourByteValue & 0x8000) != 0) {
          readIdx++;
          // 0x1fc000: 0b1111111 << 14
          result |= (fourByteValue >>> 2) & 0x1fc000;
          // 0x800000: 0b1 << 23
          if ((fourByteValue & 0x800000) != 0) {
            readIdx++;
            // 0xfe00000: 0b1111111 << 21
            result |= (fourByteValue >>> 3) & 0xfe00000;
            if ((fourByteValue & 0x80000000) != 0) {
              result |= (UNSAFE.getByte(heapMemory, address + readIdx++) & 0x7F) << 28;
            }
          }
        }
      }
      readerIndex = readIdx;
    }
    return (result >>> 1) ^ -(result & 1);
  }

  public long readVarUint36Small() {
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    int readIdx = readerIndex;
    if (size - readIdx >= 9) {
      long bulkValue = _unsafeGetInt64(readIdx++);
      // noinspection Duplicates
      long result = bulkValue & 0x7F;
      if ((bulkValue & 0x80) != 0) {
        readIdx++;
        // 0x3f80: 0b1111111 << 7
        result |= (bulkValue >>> 1) & 0x3f80;
        // 0x8000: 0b1 << 15
        if ((bulkValue & 0x8000) != 0) {
          return continueReadVarInt36(readIdx, bulkValue, result);
        }
      }
      readerIndex = readIdx;
      return result;
    } else {
      return readVarUint36Slow();
    }
  }

  private long continueReadVarInt36(int readIdx, long bulkValue, long result) {
    readIdx++;
    // 0x1fc000: 0b1111111 << 14
    result |= (bulkValue >>> 2) & 0x1fc000;
    // 0x800000: 0b1 << 23
    if ((bulkValue & 0x800000) != 0) {
      readIdx++;
      // 0xfe00000: 0b1111111 << 21
      result |= (bulkValue >>> 3) & 0xfe00000;
      if ((bulkValue & 0x80000000L) != 0) {
        readIdx++;
        // 0xff0000000: 0b11111111 << 28
        result |= (bulkValue >>> 4) & 0xff0000000L;
      }
    }
    readerIndex = readIdx;
    return result;
  }

  private long readVarUint36Slow() {
    long b = readByte();
    long result = b & 0x7F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    // noinspection Duplicates
    if ((b & 0x80) != 0) {
      b = readByte();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte();
            result |= (b & 0xFF) << 28;
          }
        }
      }
    }
    return result;
  }

  /** Reads the 1-5 byte int part of a non-negative varint. */
  public int readVarUint32() {
    int readIdx = readerIndex;
    if (size - readIdx < 5) {
      return (int) readVarUint36Slow();
    }
    // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
    int fourByteValue = _unsafeGetInt32(readIdx);
    readIdx++;
    int result = fourByteValue & 0x7F;
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    if ((fourByteValue & 0x80) != 0) {
      readIdx++;
      // 0x3f80: 0b1111111 << 7
      result |= (fourByteValue >>> 1) & 0x3f80;
      // 0x8000: 0b1 << 15
      if ((fourByteValue & 0x8000) != 0) {
        readIdx++;
        // 0x1fc000: 0b1111111 << 14
        result |= (fourByteValue >>> 2) & 0x1fc000;
        // 0x800000: 0b1 << 23
        if ((fourByteValue & 0x800000) != 0) {
          readIdx++;
          // 0xfe00000: 0b1111111 << 21
          result |= (fourByteValue >>> 3) & 0xfe00000;
          if ((fourByteValue & 0x80000000) != 0) {
            result |= (UNSAFE.getByte(heapMemory, address + readIdx++) & 0x7F) << 28;
          }
        }
      }
    }
    readerIndex = readIdx;
    return result;
  }

  /**
   * Fast method for read an unsigned varint which is mostly a small value in 7 bits value in [0,
   * 127). When the value is equal or greater than 127, the read will be a little slower.
   */
  public int readVarUint32Small7() {
    int readIdx = readerIndex;
    if (size - readIdx > 0) {
      byte v = UNSAFE.getByte(heapMemory, address + readIdx++);
      if ((v & 0x80) == 0) {
        readerIndex = readIdx;
        return v;
      }
    }
    return readVarUint32Small14();
  }

  /**
   * Fast path for read an unsigned varint which is mostly a small value in 14 bits value in [0,
   * 16384). When the value is equal or greater than 16384, the read will be a little slower.
   */
  public int readVarUint32Small14() {
    int readIdx = readerIndex;
    if (size - readIdx >= 5) {
      int fourByteValue = _unsafeGetInt32(readIdx++);
      int value = fourByteValue & 0x7F;
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      if ((fourByteValue & 0x80) != 0) {
        readIdx++;
        value |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) != 0) {
          // merely executed path, make it as a separate method to reduce
          // code size of current method for better jvm inline
          return continueReadVarUint32(readIdx, fourByteValue, value);
        }
      }
      readerIndex = readIdx;
      return value;
    } else {
      return (int) readVarUint36Slow();
    }
  }

  private int continueReadVarUint32(int readIdx, int bulkRead, int value) {
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    readIdx++;
    value |= (bulkRead >>> 2) & 0x1fc000;
    if ((bulkRead & 0x800000) != 0) {
      readIdx++;
      value |= (bulkRead >>> 3) & 0xfe00000;
      if ((bulkRead & 0x80000000) != 0) {
        value |= (UNSAFE.getByte(heapMemory, address + readIdx++) & 0x7F) << 28;
      }
    }
    readerIndex = readIdx;
    return value;
  }

  /** Reads the 1-9 byte int part of a var long. */
  public long readVarInt64() {
    return LITTLE_ENDIAN ? _readVarInt64OnLE() : _readVarInt64OnBE();
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readVarInt64OnLE() {
    // CHECKSTYLE.ON:MethodName
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    int readIdx = readerIndex;
    long result;
    if (size - readIdx < 9) {
      result = readVarUint64Slow();
    } else {
      long address = this.address;
      long bulkValue = UNSAFE.getLong(heapMemory, address + readIdx);
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      readIdx++;
      result = bulkValue & 0x7F;
      if ((bulkValue & 0x80) != 0) {
        readIdx++;
        // 0x3f80: 0b1111111 << 7
        result |= (bulkValue >>> 1) & 0x3f80;
        // 0x8000: 0b1 << 15
        if ((bulkValue & 0x8000) != 0) {
          result = continueReadVarInt64(readIdx, bulkValue, result);
          return ((result >>> 1) ^ -(result & 1));
        }
      }
      readerIndex = readIdx;
    }
    return ((result >>> 1) ^ -(result & 1));
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readVarInt64OnBE() {
    // CHECKSTYLE.ON:MethodName
    int readIdx = readerIndex;
    long result;
    if (size - readIdx < 9) {
      result = readVarUint64Slow();
    } else {
      long address = this.address;
      long bulkValue = Long.reverseBytes(UNSAFE.getLong(heapMemory, address + readIdx));
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      readIdx++;
      result = bulkValue & 0x7F;
      if ((bulkValue & 0x80) != 0) {
        readIdx++;
        // 0x3f80: 0b1111111 << 7
        result |= (bulkValue >>> 1) & 0x3f80;
        // 0x8000: 0b1 << 15
        if ((bulkValue & 0x8000) != 0) {
          result = continueReadVarInt64(readIdx, bulkValue, result);
          return ((result >>> 1) ^ -(result & 1));
        }
      }
      readerIndex = readIdx;
    }
    return ((result >>> 1) ^ -(result & 1));
  }

  /** Reads the 1-9 byte int part of a non-negative var long. */
  public long readVarUint64() {
    int readIdx = readerIndex;
    if (size - readIdx < 9) {
      return readVarUint64Slow();
    }
    // varint are written using little endian byte order, so read by little endian byte order.
    long bulkValue = _unsafeGetInt64(readIdx);
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    readIdx++;
    long result = bulkValue & 0x7F;
    if ((bulkValue & 0x80) != 0) {
      readIdx++;
      // 0x3f80: 0b1111111 << 7
      result |= (bulkValue >>> 1) & 0x3f80;
      // 0x8000: 0b1 << 15
      if ((bulkValue & 0x8000) != 0) {
        return continueReadVarInt64(readIdx, bulkValue, result);
      }
    }
    readerIndex = readIdx;
    return result;
  }

  private long continueReadVarInt64(int readIdx, long bulkValue, long result) {
    readIdx++;
    // 0x1fc000: 0b1111111 << 14
    result |= (bulkValue >>> 2) & 0x1fc000;
    // 0x800000: 0b1 << 23
    if ((bulkValue & 0x800000) != 0) {
      readIdx++;
      // 0xfe00000: 0b1111111 << 21
      result |= (bulkValue >>> 3) & 0xfe00000;
      if ((bulkValue & 0x80000000L) != 0) {
        readIdx++;
        result |= (bulkValue >>> 4) & 0x7f0000000L;
        if ((bulkValue & 0x8000000000L) != 0) {
          readIdx++;
          result |= (bulkValue >>> 5) & 0x3f800000000L;
          if ((bulkValue & 0x800000000000L) != 0) {
            readIdx++;
            result |= (bulkValue >>> 6) & 0x1fc0000000000L;
            if ((bulkValue & 0x80000000000000L) != 0) {
              readIdx++;
              result |= (bulkValue >>> 7) & 0xfe000000000000L;
              if ((bulkValue & 0x8000000000000000L) != 0) {
                long b = UNSAFE.getByte(heapMemory, address + readIdx++);
                result |= b << 56;
              }
            }
          }
        }
      }
    }
    readerIndex = readIdx;
    return result;
  }

  private long readVarUint64Slow() {
    long b = readByte();
    long result = b & 0x7F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    if ((b & 0x80) != 0) {
      b = readByte();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte();
            result |= (b & 0x7F) << 28;
            if ((b & 0x80) != 0) {
              b = readByte();
              result |= (b & 0x7F) << 35;
              if ((b & 0x80) != 0) {
                b = readByte();
                result |= (b & 0x7F) << 42;
                if ((b & 0x80) != 0) {
                  b = readByte();
                  result |= (b & 0x7F) << 49;
                  if ((b & 0x80) != 0) {
                    b = readByte();
                    // highest bit in last byte is symbols bit.
                    result |= b << 56;
                  }
                }
              }
            }
          }
        }
      }
    }
    return result;
  }

  /** Reads the 1-9 byte int part of an aligned varint. */
  public int readAlignedVarUint() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx < size - 10) {
      return slowReadAlignedVarUint();
    }
    long pos = address + readerIdx;
    long startPos = pos;
    int b = UNSAFE.getByte(heapMemory, pos++);
    // Mask first 6 bits,
    // bit 8 `set` indicates have next data bytes.
    int result = b & 0x3F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    if ((b & 0x80) != 0) { // has 2nd byte
      b = UNSAFE.getByte(heapMemory, pos++);
      result |= (b & 0x3F) << 6;
      if ((b & 0x80) != 0) { // has 3rd byte
        b = UNSAFE.getByte(heapMemory, pos++);
        result |= (b & 0x3F) << 12;
        if ((b & 0x80) != 0) { // has 4th byte
          b = UNSAFE.getByte(heapMemory, pos++);
          result |= (b & 0x3F) << 18;
          if ((b & 0x80) != 0) { // has 5th byte
            b = UNSAFE.getByte(heapMemory, pos++);
            result |= (b & 0x3F) << 24;
            if ((b & 0x80) != 0) { // has 6th byte
              b = UNSAFE.getByte(heapMemory, pos++);
              result |= (b & 0x3F) << 30;
            }
          }
        }
      }
    }
    pos = skipPadding(pos, b); // split method for `readVarUint` inlined
    readerIndex = (int) (pos - startPos + readerIdx);
    return result;
  }

  public int slowReadAlignedVarUint() {
    int b = readByte();
    // Mask first 6 bits,
    // bit 8 `set` indicates have next data bytes.
    int result = b & 0x3F;
    if ((b & 0x80) != 0) { // has 2nd byte
      b = readByte();
      result |= (b & 0x3F) << 6;
      if ((b & 0x80) != 0) { // has 3rd byte
        b = readByte();
        result |= (b & 0x3F) << 12;
        if ((b & 0x80) != 0) { // has 4th byte
          b = readByte();
          result |= (b & 0x3F) << 18;
          if ((b & 0x80) != 0) { // has 5th byte
            b = readByte();
            result |= (b & 0x3F) << 24;
            if ((b & 0x80) != 0) { // has 6th byte
              b = readByte();
              result |= (b & 0x3F) << 30;
            }
          }
        }
      }
    }
    // bit 7 `unset` indicates have next padding bytes,
    if ((b & 0x40) == 0) { // has first padding bytes
      b = readByte();
      if ((b & 0x40) == 0) { // has 2nd padding bytes
        b = readByte();
        if ((b & 0x40) == 0) { // has 3rd padding bytes
          b = readByte();
          checkArgument((b & 0x40) != 0, "At most 3 padding bytes.");
        }
      }
    }
    return result;
  }

  private long skipPadding(long pos, int b) {
    // bit 7 `unset` indicates have next padding bytes,
    if ((b & 0x40) == 0) { // has first padding bytes
      b = UNSAFE.getByte(heapMemory, pos++);
      if ((b & 0x40) == 0) { // has 2nd padding bytes
        b = UNSAFE.getByte(heapMemory, pos++);
        if ((b & 0x40) == 0) { // has 3rd padding bytes
          b = UNSAFE.getByte(heapMemory, pos++);
          checkArgument((b & 0x40) != 0, "At most 3 padding bytes.");
        }
      }
    }
    return pos;
  }

  public byte[] readBytes(int length) {
    int readerIdx = readerIndex;
    byte[] bytes = new byte[length];
    // use subtract to avoid overflow
    if (length > size - readerIdx) {
      streamReader.readTo(bytes, 0, length);
      return bytes;
    }
    byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      // System.arraycopy faster for some jdk than Unsafe.
      System.arraycopy(heapMemory, heapOffset + readerIdx, bytes, 0, length);
    } else {
      Platform.copyMemory(null, address + readerIdx, bytes, Platform.BYTE_ARRAY_OFFSET, length);
    }
    readerIndex = readerIdx + length;
    return bytes;
  }

  public void readBytes(byte[] dst, int dstIndex, int length) {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx > size - length) {
      streamReader.readTo(dst, dstIndex, length);
      return;
    }
    if (dstIndex < 0 || dstIndex > dst.length - length) {
      throwIndexOOBExceptionForRead();
    }
    copyToUnsafe(readerIdx, dst, Platform.BYTE_ARRAY_OFFSET + dstIndex, length);
    readerIndex = readerIdx + length;
  }

  public void readBytes(byte[] dst) {
    readBytes(dst, 0, dst.length);
  }

  /** Read {@code len} bytes into a long using little-endian order. */
  public long readBytesAsInt64(int len) {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining >= 8) {
      readerIndex = readerIdx + len;
      long v = UNSAFE.getLong(heapMemory, address + readerIdx);
      v = (LITTLE_ENDIAN ? v : Long.reverseBytes(v)) & (0xffffffffffffffffL >>> ((8 - len) * 8));
      return v;
    }
    return slowReadBytesAsInt64(remaining, len);
  }

  private long slowReadBytesAsInt64(int remaining, int len) {
    if (remaining < len) {
      streamReader.fillBuffer(len - remaining);
    }
    int readerIdx = readerIndex;
    readerIndex = readerIdx + len;
    long result = 0;
    byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      for (int i = 0, start = heapOffset + readerIdx; i < len; i++) {
        result |= (((long) heapMemory[start + i]) & 0xff) << (i * 8);
      }
    } else {
      long start = address + readerIdx;
      for (int i = 0; i < len; i++) {
        result |= ((long) UNSAFE.getByte(null, start + i) & 0xff) << (i * 8);
      }
    }
    return result;
  }

  public int read(ByteBuffer dst) {
    int readerIdx = readerIndex;
    int len = dst.remaining();
    // use subtract to avoid overflow
    if (readerIdx > size - len) {
      return streamReader.readToByteBuffer(dst);
    }
    if (heapMemory != null) {
      dst.put(heapMemory, readerIndex + heapOffset, len);
    } else {
      dst.put(sliceAsByteBuffer(readerIdx, len));
    }
    readerIndex = readerIdx + len;
    return len;
  }

  public void read(ByteBuffer dst, int len) {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx > size - len) {
      streamReader.readToByteBuffer(dst, len);
    } else {
      if (heapMemory != null) {
        dst.put(heapMemory, readerIndex + heapOffset, len);
      } else {
        dst.put(sliceAsByteBuffer(readerIdx, len));
      }
      readerIndex = readerIdx + len;
    }
  }

  /**
   * Read size for following binary, this method will check and fill readable bytes too. This method
   * is optimized for small size, it's faster than {@link #readVarUint32}.
   */
  public int readBinarySize() {
    int binarySize;
    int readIdx = readerIndex;
    if (size - readIdx >= 5) {
      int fourByteValue = _unsafeGetInt32(readIdx++);
      binarySize = fourByteValue & 0x7F;
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      if ((fourByteValue & 0x80) != 0) {
        readIdx++;
        binarySize |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) != 0) {
          // merely executed path, make it as a separate method to reduce
          // code size of current method for better jvm inline
          return continueReadBinarySize(readIdx, fourByteValue, binarySize);
        }
      }
      readerIndex = readIdx;
    } else {
      binarySize = (int) readVarUint36Slow();
      readIdx = readerIndex;
    }
    int diff = size - readIdx;
    if (diff < binarySize) {
      streamReader.fillBuffer(diff);
    }
    return binarySize;
  }

  private int continueReadBinarySize(int readIdx, int bulkRead, int binarySize) {
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    readIdx++;
    binarySize |= (bulkRead >>> 2) & 0x1fc000;
    if ((bulkRead & 0x800000) != 0) {
      readIdx++;
      binarySize |= (bulkRead >>> 3) & 0xfe00000;
      if ((bulkRead & 0x80000000) != 0) {
        binarySize |= (UNSAFE.getByte(heapMemory, address + readIdx++) & 0x7F) << 28;
      }
    }
    int diff = size - readIdx;
    if (diff < binarySize) {
      streamReader.fillBuffer(diff);
    }
    return binarySize;
  }

  public byte[] readBytesAndSize() {
    final int numBytes = readBinarySize();
    int readerIdx = readerIndex;
    final byte[] arr = new byte[numBytes];
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readTo(arr, 0, numBytes);
      return arr;
    }
    byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      System.arraycopy(heapMemory, heapOffset + readerIdx, arr, 0, numBytes);
    } else {
      Platform.UNSAFE.copyMemory(
          null, address + readerIdx, arr, Platform.BYTE_ARRAY_OFFSET, numBytes);
    }
    readerIndex = readerIdx + numBytes;
    return arr;
  }

  public byte[] readBytesWithAlignedSize() {
    final int numBytes = readAlignedVarUint();
    int readerIdx = readerIndex;
    final byte[] arr = new byte[numBytes];
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readTo(arr, 0, numBytes);
      return arr;
    }
    Platform.UNSAFE.copyMemory(
        this.heapMemory, this.address + readerIdx, arr, Platform.BYTE_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
    return arr;
  }

  /** This method should be used to read data written by {@link #writePrimitiveArrayWithSize}. */
  public char[] readChars(int numBytes) {
    int readerIdx = readerIndex;
    final char[] chars = new char[numBytes >> 1];
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readToUnsafe(chars, Platform.CHAR_ARRAY_OFFSET, numBytes);
      return chars;
    }
    Platform.copyMemory(
        heapMemory, address + readerIdx, chars, Platform.CHAR_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
    return chars;
  }

  public void readChars(char[] chars, int offset, int numBytes) {
    final int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readToUnsafe(chars, offset, numBytes);
      return;
    }
    Platform.copyMemory(heapMemory, address + readerIdx, chars, offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  @CodegenInvoke
  public char[] readCharsAndSize() {
    final int numBytes = readBinarySize();
    int readerIdx = readerIndex;
    final char[] arr = new char[numBytes >> 1];
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readToUnsafe(arr, Platform.CHAR_ARRAY_OFFSET, numBytes);
      return arr;
    }
    Platform.UNSAFE.copyMemory(
        heapMemory, address + readerIdx, arr, Platform.CHAR_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
    return arr;
  }

  public char[] readCharsWithAlignedSize() {
    final int numBytes = readAlignedVarUint();
    return readChars(numBytes);
  }

  public long[] readLongs(int numBytes) {
    int readerIdx = readerIndex;
    int numElements = numBytes >> 3;
    final long[] longs = new long[numElements];
    // use subtract to avoid overflow
    if (readerIdx > size - numBytes) {
      streamReader.readToUnsafe(longs, Platform.LONG_ARRAY_OFFSET, numElements);
      return longs;
    }
    Platform.copyMemory(
        heapMemory, address + readerIdx, longs, Platform.LONG_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
    return longs;
  }

  /**
   * Bulk copy method. Copies {@code numBytes} bytes to target unsafe object and pointer. NOTE: This
   * is a unsafe method, no check here, please be carefully.
   */
  public void readToUnsafe(Object target, long targetPointer, int numBytes) {
    int remaining = size - readerIndex;
    if (numBytes > remaining) {
      streamReader.readToUnsafe(target, targetPointer, numBytes);
    } else {
      int readerIdx = readerIndex;
      Platform.copyMemory(heapMemory, address + readerIdx, target, targetPointer, numBytes);
      readerIndex = readerIdx + numBytes;
    }
  }

  public void checkReadableBytes(int minimumReadableBytes) {
    // use subtract to avoid overflow
    int remaining = size - readerIndex;
    if (minimumReadableBytes > remaining) {
      streamReader.fillBuffer(minimumReadableBytes - remaining);
    }
  }

  /**
   * Returns internal byte array if data is on heap and remaining buffer size is equal to internal
   * byte array size, or create a new byte array which copy remaining data from off-heap.
   */
  public byte[] getRemainingBytes() {
    int length = size - readerIndex;
    if (heapMemory != null && size == length && heapOffset == 0) {
      return heapMemory;
    } else {
      return getBytes(readerIndex, length);
    }
  }

  // ------------------------- Read Methods Finished -------------------------------------

  /**
   * Bulk copy method. Copies {@code numBytes} bytes to target unsafe object and pointer. NOTE: This
   * is a unsafe method, no check here, please be carefully.
   */
  public void copyToUnsafe(long offset, Object target, long targetPointer, int numBytes) {
    final long thisPointer = this.address + offset;
    checkArgument(thisPointer + numBytes <= addressLimit);
    Platform.copyMemory(this.heapMemory, thisPointer, target, targetPointer, numBytes);
  }

  /**
   * Bulk copy method. Copies {@code numBytes} bytes from source unsafe object and pointer. NOTE:
   * This is an unsafe method, no check here, please be careful.
   */
  public void copyFromUnsafe(long offset, Object source, long sourcePointer, long numBytes) {
    final long thisPointer = this.address + offset;
    checkArgument(thisPointer + numBytes <= addressLimit);
    Platform.copyMemory(source, sourcePointer, this.heapMemory, thisPointer, numBytes);
  }

  public void copyTo(int offset, MemoryBuffer target, int targetOffset, int numBytes) {
    final byte[] thisHeapRef = this.heapMemory;
    final byte[] otherHeapRef = target.heapMemory;
    final long thisPointer = this.address + offset;
    final long otherPointer = target.address + targetOffset;
    if ((numBytes | offset | targetOffset) >= 0
        && thisPointer <= this.addressLimit - numBytes
        && otherPointer <= target.addressLimit - numBytes) {
      UNSAFE.copyMemory(thisHeapRef, thisPointer, otherHeapRef, otherPointer, numBytes);
    } else {
      throw new IndexOutOfBoundsException(
          String.format(
              "offset=%d, targetOffset=%d, numBytes=%d, address=%d, targetAddress=%d",
              offset, targetOffset, numBytes, this.address, target.address));
    }
  }

  public void copyFrom(int offset, MemoryBuffer source, int sourcePointer, int numBytes) {
    source.copyTo(sourcePointer, this, offset, numBytes);
  }

  public byte[] getBytes(int index, int length) {
    if (index == 0 && heapMemory != null && heapOffset == 0) {
      // Arrays.copyOf is an intrinsics, which is faster
      return Arrays.copyOf(heapMemory, length);
    }
    if (index + length > size) {
      throwIndexOOBExceptionForRead(length);
    }
    byte[] data = new byte[length];
    copyToUnsafe(index, data, Platform.BYTE_ARRAY_OFFSET, length);
    return data;
  }

  public void getBytes(int index, byte[] dst, int dstIndex, int length) {
    if (dstIndex > dst.length - length) {
      throwOOBException();
    }
    if (index > size - length) {
      throwOOBException();
    }
    copyToUnsafe(index, dst, Platform.BYTE_ARRAY_OFFSET + dstIndex, length);
  }

  public MemoryBuffer slice(int offset) {
    return slice(offset, size - offset);
  }

  public MemoryBuffer slice(int offset, int length) {
    if (offset + length > size) {
      throwOOBExceptionForRange(offset, length);
    }
    if (heapMemory != null) {
      return new MemoryBuffer(heapMemory, heapOffset + offset, length);
    } else {
      return new MemoryBuffer(address + offset, length, offHeapBuffer);
    }
  }

  public ByteBuffer sliceAsByteBuffer() {
    return sliceAsByteBuffer(readerIndex, size - readerIndex);
  }

  public ByteBuffer sliceAsByteBuffer(int offset, int length) {
    if (offset + length > size) {
      throwOOBExceptionForRange(offset, length);
    }
    if (heapMemory != null) {
      return ByteBuffer.wrap(heapMemory, heapOffset + offset, length).slice();
    } else {
      ByteBuffer offHeapBuffer = this.offHeapBuffer;
      if (offHeapBuffer != null) {
        ByteBuffer duplicate = offHeapBuffer.duplicate();
        int start = (int) (address - ByteBufferUtil.getAddress(duplicate));
        ByteBufferUtil.position(duplicate, start + offset);
        duplicate.limit(start + offset + length);
        return duplicate.slice();
      } else {
        return ByteBufferUtil.createDirectByteBufferFromNativeAddress(address + offset, length);
      }
    }
  }

  private void throwOOBExceptionForRange(int offset, int length) {
    throw new IndexOutOfBoundsException(
        String.format("offset(%d) + length(%d) exceeds size(%d): %s", offset, length, size, this));
  }

  public ForyStreamReader getStreamReader() {
    return streamReader;
  }

  /**
   * Equals two memory buffer regions.
   *
   * @param buf2 Buffer to equal this buffer with
   * @param offset1 Offset of this buffer to start equaling
   * @param offset2 Offset of buf2 to start equaling
   * @param len Length of the equaled memory region
   * @return true if equal, false otherwise
   */
  public boolean equalTo(MemoryBuffer buf2, int offset1, int offset2, int len) {
    final long pos1 = address + offset1;
    final long pos2 = buf2.address + offset2;
    checkArgument(pos1 < addressLimit);
    checkArgument(pos2 < buf2.addressLimit);
    return Platform.arrayEquals(heapMemory, pos1, buf2.heapMemory, pos2, len);
  }

  @Override
  public String toString() {
    return "MemoryBuffer{"
        + "size="
        + size
        + ", readerIndex="
        + readerIndex
        + ", writerIndex="
        + writerIndex
        + ", heapMemory="
        + (heapMemory == null ? null : "len(" + heapMemory.length + ")")
        + ", heapOffset="
        + heapOffset
        + ", offHeapBuffer="
        + offHeapBuffer
        + ", address="
        + address
        + ", addressLimit="
        + addressLimit
        + '}';
  }

  /** Point this buffer to a new byte array. */
  public void pointTo(byte[] buffer, int offset, int length) {
    initHeapBuffer(buffer, offset, length);
  }

  /** Creates a new memory buffer that targets to the given heap memory region. */
  public static MemoryBuffer fromByteArray(byte[] buffer, int offset, int length) {
    return new MemoryBuffer(buffer, offset, length, null);
  }

  public static MemoryBuffer fromByteArray(
      byte[] buffer, int offset, int length, ForyStreamReader streamReader) {
    return new MemoryBuffer(buffer, offset, length, streamReader);
  }

  /** Creates a new memory buffer that targets to the given heap memory region. */
  public static MemoryBuffer fromByteArray(byte[] buffer) {
    return new MemoryBuffer(buffer, 0, buffer.length);
  }

  /**
   * Creates a new memory buffer that represents the memory backing the given byte buffer section of
   * {@code [buffer.position(), buffer.limit())}. The buffer will change into a heap buffer
   * automatically if not enough.
   *
   * @param buffer a direct buffer or heap buffer
   */
  public static MemoryBuffer fromByteBuffer(ByteBuffer buffer) {
    if (buffer.isDirect()) {
      return new MemoryBuffer(
          ByteBufferUtil.getAddress(buffer) + buffer.position(), buffer.remaining(), buffer);
    } else {
      int offset = buffer.arrayOffset() + buffer.position();
      return new MemoryBuffer(buffer.array(), offset, buffer.remaining());
    }
  }

  public static MemoryBuffer fromDirectByteBuffer(
      ByteBuffer buffer, int size, ForyStreamReader streamReader) {
    long offHeapAddress = ByteBufferUtil.getAddress(buffer) + buffer.position();
    return new MemoryBuffer(offHeapAddress, size, buffer, streamReader);
  }

  /**
   * Creates a new memory buffer that represents the provided native memory. The buffer will change
   * into a heap buffer automatically if not enough.
   */
  public static MemoryBuffer fromNativeAddress(long address, int size) {
    return new MemoryBuffer(address, size, null);
  }

  /**
   * Create a heap buffer of specified initial size. The buffer will grow automatically if not
   * enough.
   */
  public static MemoryBuffer newHeapBuffer(int initialSize) {
    return fromByteArray(new byte[initialSize]);
  }
}
