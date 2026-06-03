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

package org.apache.fory.memory;

import static org.apache.fory.util.Preconditions.checkArgument;
import static org.apache.fory.util.Preconditions.checkNotNull;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.apache.fory.annotation.CodegenInvoke;
import org.apache.fory.io.AbstractStreamReader;
import org.apache.fory.io.ForyStreamReader;

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
 *   <li>varint int/long encoding.
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
 * <p>Warning: The instance of this class should not be held at GraalVM build time; build-time heap
 * buffers do not represent the runtime heap layout.
 *
 * <p>In the Java 25 multi-release implementation, absolute random-access get/put methods are
 * internal fast paths. Callers must pass legal indices and ranges; read/write entry points perform
 * logical {@link MemoryBuffer} range validation before reaching these methods. The implementation
 * relies on {@link VarHandle}, array, and {@link ByteBuffer} access only for JVM memory safety and
 * does not repeat root Unsafe-style logical bounds checks.
 *
 * <p>Note(chaokunyang): Buffer operations are very common, and jvm inline and branch elimination is
 * not reliable even in c2 compiler, so we try to inline and avoid checks as we can manually. jvm
 * jit may stop inline for some reasons: NodeCountInliningCutoff,
 * DesiredMethodLimit,MaxRecursiveInlineLevel,FreqInlineSize,MaxInlineSize
 */
public final class MemoryBuffer {
  public static final int BUFFER_GROW_STEP_THRESHOLD = 100 * 1024 * 1024;
  private static final boolean LITTLE_ENDIAN = NativeByteOrder.IS_LITTLE_ENDIAN;
  private static final boolean UNALIGNED = true;
  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
  private static final int BOOLEAN_ARRAY_OFFSET = 0;
  private static final int BYTE_ARRAY_OFFSET = 0;
  private static final int CHAR_ARRAY_OFFSET = 0;
  private static final int SHORT_ARRAY_OFFSET = 0;
  private static final int INT_ARRAY_OFFSET = 0;
  private static final int LONG_ARRAY_OFFSET = 0;
  private static final int FLOAT_ARRAY_OFFSET = 0;
  private static final int DOUBLE_ARRAY_OFFSET = 0;
  private static final VarHandle BYTE_ARRAY_CHAR =
      MethodHandles.byteArrayViewVarHandle(char[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_ARRAY_SHORT =
      MethodHandles.byteArrayViewVarHandle(short[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_ARRAY_INT =
      MethodHandles.byteArrayViewVarHandle(int[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_ARRAY_LONG =
      MethodHandles.byteArrayViewVarHandle(long[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_BUFFER_CHAR =
      MethodHandles.byteBufferViewVarHandle(char[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_BUFFER_SHORT =
      MethodHandles.byteBufferViewVarHandle(short[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_BUFFER_INT =
      MethodHandles.byteBufferViewVarHandle(int[].class, NATIVE_ORDER);
  private static final VarHandle BYTE_BUFFER_LONG =
      MethodHandles.byteBufferViewVarHandle(long[].class, NATIVE_ORDER);
  // Global allocator instance that can be customized
  private static volatile MemoryAllocator globalAllocator = new DefaultMemoryAllocator();

  // If the data in on the heap, `heapMemory` will be non-null, and its' the object relative to
  // which we access the memory.
  // If we have this buffer, we must never void this reference, or the memory buffer will point
  // to undefined addresses outside the heap and may in out-of-order execution cases cause
  // buffer faults.
  byte[] heapMemory;
  int heapOffset;
  // If the data is off the heap, `offHeapBuffer` will be non-null, and it's the direct byte buffer
  // that allocated on the off-heap memory.
  // This memory buffer holds a reference to that buffer, so as long as this memory buffer lives,
  // the memory will not be released.
  ByteBuffer offHeapBuffer;
  ByteBuffer nativeOffHeapBuffer;
  // The readable/writeable range is [address, addressLimit).
  // If the data in on the heap, this is the relative offset to the `heapMemory` byte array.
  // If the data is off the heap, this is the logical byte index into `offHeapBuffer`.
  long address;
  // The address one byte after the last addressable byte, i.e. `address + size` while the
  // buffer is not disposed.
  long addressLimit;
  // The size in bytes of the memory buffer.
  int size;
  int readerIndex;
  int writerIndex;
  final ForyStreamReader streamReader;

  // Android branches in this class are intentional method-boundary exits.
  // Do not delete them or fold them into the JVM path: each branch must make exactly one MemoryOps
  // call, while MemoryOps owns Android heap index math and reader/writer updates.

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
    initOffHeapBuffer(offHeapAddress, size, offHeapBuffer);
    if (streamReader != null) {
      this.streamReader = streamReader;
    } else {
      this.streamReader = new BoundChecker();
    }
  }

  private void initOffHeapBuffer(long offHeapAddress, int size, ByteBuffer offHeapBuffer) {
    checkArgument(offHeapAddress >= 0 && size >= 0);
    checkNotNull(offHeapBuffer, "JDK25 MemoryBuffer requires a ByteBuffer owner for off-heap data");
    checkArgument(
        offHeapBuffer.isDirect(), "Only direct ByteBuffers can back off-heap MemoryBuffer");
    this.offHeapBuffer = offHeapBuffer;
    ByteBuffer nativeBuffer = offHeapBuffer.duplicate().order(NATIVE_ORDER);
    // Stream readers can expand the owner buffer limit after this duplicate is created. Keep the
    // absolute-access view capacity-wide so JDK25 public ByteBuffer checks match the logical buffer
    // size tracked by MemoryBuffer.
    nativeBuffer.clear();
    this.nativeOffHeapBuffer = nativeBuffer;
    this.heapMemory = null;
    this.address = offHeapAddress;
    this.addressLimit = this.address + size;
    this.size = size;
  }

  private static long getAddress(ByteBuffer buffer) {
    checkNotNull(buffer, "buffer is null");
    checkArgument(buffer.isDirect(), "Can't get address of a non-direct ByteBuffer.");
    throw new UnsupportedOperationException("Raw direct-buffer addresses are not exposed on JDK25");
  }

  public void initByteBuffer(ByteBuffer buffer, int size) {
    if (buffer.isDirect()) {
      initOffHeapBuffer(0, size, buffer);
    } else if (buffer.hasArray()) {
      initHeapBuffer(buffer.array(), buffer.arrayOffset(), size);
    } else {
      throw new IllegalArgumentException("ByteBuffer must be direct or expose an array");
    }
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
    public void readTo(byte[] dst, int dstIndex, int length) {
      throwIndexOOBExceptionForRead(length);
    }

    @Override
    public void readToByteBuffer(ByteBuffer dst, int length) {
      throwIndexOOBExceptionForRead(length);
    }

    @Override
    public int readToByteBuffer(ByteBuffer dst) {
      throwIndexOOBExceptionForRead(dst.remaining());
      return 0;
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
    this.offHeapBuffer = null;
    this.nativeOffHeapBuffer = null;
    final long startPos = BYTE_ARRAY_OFFSET + offset;
    this.address = startPos;
    this.size = length;
    this.addressLimit = startPos + length;
  }

  private byte loadByte(long pos) {
    byte[] heap = heapMemory;
    if (heap != null) {
      return heap[(int) pos];
    }
    return nativeOffHeapBuffer.get((int) pos);
  }

  private void storeByte(long pos, byte value) {
    byte[] heap = heapMemory;
    if (heap != null) {
      heap[(int) pos] = value;
    } else {
      nativeOffHeapBuffer.put((int) pos, value);
    }
  }

  private char loadChar(long pos) {
    byte[] heap = heapMemory;
    if (heap != null) {
      return (char) BYTE_ARRAY_CHAR.get(heap, (int) pos);
    }
    return (char) BYTE_BUFFER_CHAR.get(nativeOffHeapBuffer, (int) pos);
  }

  private void storeChar(long pos, char value) {
    byte[] heap = heapMemory;
    if (heap != null) {
      BYTE_ARRAY_CHAR.set(heap, (int) pos, value);
    } else {
      BYTE_BUFFER_CHAR.set(nativeOffHeapBuffer, (int) pos, value);
    }
  }

  private short loadShort(long pos) {
    byte[] heap = heapMemory;
    if (heap != null) {
      return (short) BYTE_ARRAY_SHORT.get(heap, (int) pos);
    }
    return (short) BYTE_BUFFER_SHORT.get(nativeOffHeapBuffer, (int) pos);
  }

  private void storeShort(long pos, short value) {
    byte[] heap = heapMemory;
    if (heap != null) {
      BYTE_ARRAY_SHORT.set(heap, (int) pos, value);
    } else {
      BYTE_BUFFER_SHORT.set(nativeOffHeapBuffer, (int) pos, value);
    }
  }

  private int loadInt(long pos) {
    byte[] heap = heapMemory;
    if (heap != null) {
      return (int) BYTE_ARRAY_INT.get(heap, (int) pos);
    }
    return (int) BYTE_BUFFER_INT.get(nativeOffHeapBuffer, (int) pos);
  }

  private void storeInt(long pos, int value) {
    byte[] heap = heapMemory;
    if (heap != null) {
      BYTE_ARRAY_INT.set(heap, (int) pos, value);
    } else {
      BYTE_BUFFER_INT.set(nativeOffHeapBuffer, (int) pos, value);
    }
  }

  private long loadLong(long pos) {
    byte[] heap = heapMemory;
    if (heap != null) {
      return (long) BYTE_ARRAY_LONG.get(heap, (int) pos);
    }
    return (long) BYTE_BUFFER_LONG.get(nativeOffHeapBuffer, (int) pos);
  }

  private void storeLong(long pos, long value) {
    byte[] heap = heapMemory;
    if (heap != null) {
      BYTE_ARRAY_LONG.set(heap, (int) pos, value);
    } else {
      BYTE_BUFFER_LONG.set(nativeOffHeapBuffer, (int) pos, value);
    }
  }

  private void readBytesToArray(long srcOffset, byte[] target, int targetOffset, int numBytes) {
    byte[] heap = heapMemory;
    if (heap != null) {
      System.arraycopy(heap, toIntIndex(srcOffset), target, targetOffset, numBytes);
    } else {
      nativeOffHeapBuffer.get(toIntIndex(srcOffset), target, targetOffset, numBytes);
    }
  }

  private void writeBytesFromArray(
      long targetOffset, byte[] source, int sourceOffset, int numBytes) {
    byte[] heap = heapMemory;
    if (heap != null) {
      System.arraycopy(source, sourceOffset, heap, toIntIndex(targetOffset), numBytes);
    } else {
      nativeOffHeapBuffer.put(toIntIndex(targetOffset), source, sourceOffset, numBytes);
    }
  }

  private void readBooleansToArray(
      long srcOffset, boolean[] target, int targetOffset, int numBytes) {
    for (int i = 0; i < numBytes; i++) {
      target[targetOffset + i] = loadByte(srcOffset + i) != 0;
    }
  }

  private void writeBooleansFromArray(
      long targetOffset, boolean[] source, int sourceOffset, int numBytes) {
    for (int i = 0; i < numBytes; i++) {
      storeByte(targetOffset + i, source[sourceOffset + i] ? (byte) 1 : (byte) 0);
    }
  }

  private void readCharsToArray(long srcOffset, char[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 1;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 2) {
        target[targetOffset + i] = (char) BYTE_ARRAY_CHAR.get(heap, pos);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 2) {
        target[targetOffset + i] = (char) BYTE_BUFFER_CHAR.get(direct, pos);
      }
    }
  }

  private void writeCharsFromArray(
      long targetOffset, char[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 1;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 2) {
        BYTE_ARRAY_CHAR.set(heap, pos, source[sourceOffset + i]);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 2) {
        BYTE_BUFFER_CHAR.set(direct, pos, source[sourceOffset + i]);
      }
    }
  }

  private void readShortsToArray(long srcOffset, short[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 1;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 2) {
        target[targetOffset + i] = (short) BYTE_ARRAY_SHORT.get(heap, pos);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 2) {
        target[targetOffset + i] = (short) BYTE_BUFFER_SHORT.get(direct, pos);
      }
    }
  }

  private void writeShortsFromArray(
      long targetOffset, short[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 1;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 2) {
        BYTE_ARRAY_SHORT.set(heap, pos, source[sourceOffset + i]);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 2) {
        BYTE_BUFFER_SHORT.set(direct, pos, source[sourceOffset + i]);
      }
    }
  }

  private void readIntsToArray(long srcOffset, int[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 2;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 4) {
        target[targetOffset + i] = (int) BYTE_ARRAY_INT.get(heap, pos);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 4) {
        target[targetOffset + i] = (int) BYTE_BUFFER_INT.get(direct, pos);
      }
    }
  }

  private void writeIntsFromArray(long targetOffset, int[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 2;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 4) {
        BYTE_ARRAY_INT.set(heap, pos, source[sourceOffset + i]);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 4) {
        BYTE_BUFFER_INT.set(direct, pos, source[sourceOffset + i]);
      }
    }
  }

  private void readLongsToArray(long srcOffset, long[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 3;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 8) {
        target[targetOffset + i] = (long) BYTE_ARRAY_LONG.get(heap, pos);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 8) {
        target[targetOffset + i] = (long) BYTE_BUFFER_LONG.get(direct, pos);
      }
    }
  }

  private void writeLongsFromArray(
      long targetOffset, long[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 3;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 8) {
        BYTE_ARRAY_LONG.set(heap, pos, source[sourceOffset + i]);
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 8) {
        BYTE_BUFFER_LONG.set(direct, pos, source[sourceOffset + i]);
      }
    }
  }

  private void readFloatsToArray(long srcOffset, float[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 2;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 4) {
        target[targetOffset + i] = Float.intBitsToFloat((int) BYTE_ARRAY_INT.get(heap, pos));
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 4) {
        target[targetOffset + i] = Float.intBitsToFloat((int) BYTE_BUFFER_INT.get(direct, pos));
      }
    }
  }

  private void writeFloatsFromArray(
      long targetOffset, float[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 2;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 4) {
        BYTE_ARRAY_INT.set(heap, pos, Float.floatToRawIntBits(source[sourceOffset + i]));
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 4) {
        BYTE_BUFFER_INT.set(direct, pos, Float.floatToRawIntBits(source[sourceOffset + i]));
      }
    }
  }

  private void readDoublesToArray(long srcOffset, double[] target, int targetOffset, int numBytes) {
    int elements = numBytes >>> 3;
    int pos = toIntIndex(srcOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 8) {
        target[targetOffset + i] = Double.longBitsToDouble((long) BYTE_ARRAY_LONG.get(heap, pos));
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 8) {
        target[targetOffset + i] =
            Double.longBitsToDouble((long) BYTE_BUFFER_LONG.get(direct, pos));
      }
    }
  }

  private void writeDoublesFromArray(
      long targetOffset, double[] source, int sourceOffset, int numBytes) {
    int elements = numBytes >>> 3;
    int pos = toIntIndex(targetOffset);
    byte[] heap = heapMemory;
    if (heap != null) {
      for (int i = 0; i < elements; i++, pos += 8) {
        BYTE_ARRAY_LONG.set(heap, pos, Double.doubleToRawLongBits(source[sourceOffset + i]));
      }
    } else {
      ByteBuffer direct = nativeOffHeapBuffer;
      for (int i = 0; i < elements; i++, pos += 8) {
        BYTE_BUFFER_LONG.set(direct, pos, Double.doubleToRawLongBits(source[sourceOffset + i]));
      }
    }
  }

  private static int toIntIndex(long offset) {
    if (offset < 0 || offset > Integer.MAX_VALUE) {
      throw new IndexOutOfBoundsException("offset out of int range: " + offset);
    }
    return (int) offset;
  }

  private static int putVarUInt32Heap(byte[] heap, int index, int value) {
    if (value >>> 7 == 0) {
      heap[index] = (byte) value;
      return 1;
    }
    heap[index++] = (byte) ((value & 0x7F) | 0x80);
    if (value >>> 14 == 0) {
      heap[index] = (byte) (value >>> 7);
      return 2;
    }
    heap[index++] = (byte) ((value >>> 7) | 0x80);
    if (value >>> 21 == 0) {
      heap[index] = (byte) (value >>> 14);
      return 3;
    }
    heap[index++] = (byte) ((value >>> 14) | 0x80);
    if (value >>> 28 == 0) {
      heap[index] = (byte) (value >>> 21);
      return 4;
    }
    heap[index++] = (byte) ((value >>> 21) | 0x80);
    heap[index] = (byte) (value >>> 28);
    return 5;
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

  // ------------------------------------------------------------------------
  //                    Random Access get() and put() methods
  // ------------------------------------------------------------------------

  /** Copies from a caller-validated absolute buffer index into {@code dst}. */
  public void get(int index, byte[] dst) {
    get(index, dst, 0, dst.length);
  }

  /**
   * Copies from a caller-validated absolute buffer range into {@code dst}.
   *
   * <p>This Java 25 path intentionally does not duplicate {@link MemoryBuffer} range checks; callers
   * that derive {@code index}, {@code offset}, or {@code length} from wire data must validate them
   * before calling this method.
   */
  public void get(int index, byte[] dst, int offset, int length) {
    final long pos = address + index;
    final byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      // Keep heap-to-heap bulk copies on the JDK intrinsic path.
      System.arraycopy(heapMemory, heapOffset + index, dst, offset, length);
    } else {
      readBytesToArray(pos, dst, BYTE_ARRAY_OFFSET + offset, length);
    }
  }

  /**
   * Copies from a caller-validated absolute buffer range into {@code target}.
   *
   * <p>Logical range validation belongs to the caller. This method relies on the backing Java array
   * or {@link ByteBuffer} accessors only to prevent invalid JVM memory access.
   */
  public void get(int offset, ByteBuffer target, int numBytes) {
    final int targetPos = target.position();
    if (target.isDirect()) {
      ByteBuffer duplicate = target.duplicate();
      ByteBufferUtil.position(duplicate, targetPos);
      if (heapMemory != null) {
        duplicate.put(heapMemory, heapOffset + offset, numBytes);
      } else {
        ByteBuffer source = nativeOffHeapBuffer.duplicate();
        int sourcePos = toIntIndex(address + offset);
        ByteBufferUtil.position(source, sourcePos);
        source.limit(sourcePos + numBytes);
        duplicate.put(source.slice());
      }
    } else {
      assert target.hasArray();
      get(offset, target.array(), targetPos + target.arrayOffset(), numBytes);
    }
    if (target.position() == targetPos) {
      ByteBufferUtil.position(target, targetPos + numBytes);
    }
  }

  /**
   * Copies from {@code source} into a caller-validated absolute buffer range.
   *
   * <p>Logical range validation belongs to the caller. This method relies on the backing Java array
   * or {@link ByteBuffer} accessors only to prevent invalid JVM memory access.
   */
  public void put(int offset, ByteBuffer source, int numBytes) {
    final int sourcePos = source.position();
    if (source.isDirect()) {
      ByteBuffer duplicate = source.duplicate();
      ByteBufferUtil.position(duplicate, sourcePos);
      duplicate.limit(sourcePos + numBytes);
      if (heapMemory != null) {
        duplicate.get(heapMemory, heapOffset + offset, numBytes);
      } else {
        ByteBuffer target = nativeOffHeapBuffer.duplicate();
        int targetPos = toIntIndex(address + offset);
        ByteBufferUtil.position(target, targetPos);
        target.limit(targetPos + numBytes);
        target.slice().put(duplicate.slice());
      }
    } else {
      assert source.hasArray();
      put(offset, source.array(), sourcePos + source.arrayOffset(), numBytes);
    }
    if (source.position() == sourcePos) {
      ByteBufferUtil.position(source, sourcePos + numBytes);
    }
  }

  /** Copies {@code src} into a caller-validated absolute buffer index. */
  public void put(int index, byte[] src) {
    put(index, src, 0, src.length);
  }

  /**
   * Copies {@code src} into a caller-validated absolute buffer range.
   *
   * <p>This Java 25 path intentionally does not duplicate {@link MemoryBuffer} range checks; callers
   * that derive {@code index}, {@code offset}, or {@code length} from wire data must validate them
   * before calling this method.
   */
  public void put(int index, byte[] src, int offset, int length) {
    final long pos = address + index;
    final byte[] heapMemory = this.heapMemory;
    if (heapMemory != null) {
      // Keep heap-to-heap bulk copies on the JDK intrinsic path.
      System.arraycopy(src, offset, heapMemory, heapOffset + index, length);
    } else {
      writeBytesFromArray(pos, src, BYTE_ARRAY_OFFSET + offset, length);
    }
  }

  public byte getByte(int index) {
    final long pos = address + index;
    return loadByte(pos);
  }

  // In the Java25 overlay, `_unsafe*` preserves the root MemoryBuffer unchecked-access naming.
  // These methods use indexed array, ByteBuffer, and VarHandle access, not the JDK Unsafe API.
  // CHECKSTYLE.OFF:MethodName
  public byte _unsafeGetByte(int index) {
    // CHECKSTYLE.ON:MethodName
    return loadByte(address + index);
  }

  public void putByte(int index, int b) {
    final long pos = address + index;
    storeByte(pos, (byte) b);
  }

  public void putByte(int index, byte b) {
    final long pos = address + index;
    storeByte(pos, b);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutByte(int index, byte b) {
    // CHECKSTYLE.ON:MethodName
    storeByte(address + index, b);
  }

  public boolean getBoolean(int index) {
    final long pos = address + index;
    return loadByte(pos) != 0;
  }

  // CHECKSTYLE.OFF:MethodName
  public boolean _unsafeGetBoolean(int index) {
    // CHECKSTYLE.ON:MethodName
    return loadByte(address + index) != 0;
  }

  public void putBoolean(int index, boolean value) {
    final long pos = address + index;
    storeByte(pos, (value ? (byte) 1 : (byte) 0));
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutBoolean(int index, boolean value) {
    // CHECKSTYLE.ON:MethodName
    storeByte(address + index, (value ? (byte) 1 : (byte) 0));
  }

  public char getChar(int index) {
    final long pos = address + index;
    char c = loadChar(pos);
    return LITTLE_ENDIAN ? c : Character.reverseBytes(c);
  }

  // CHECKSTYLE.OFF:MethodName
  public char _unsafeGetChar(int index) {
    // CHECKSTYLE.ON:MethodName
    char c = loadChar(address + index);
    return LITTLE_ENDIAN ? c : Character.reverseBytes(c);
  }

  public void putChar(int index, char value) {
    final long pos = address + index;
    if (!LITTLE_ENDIAN) {
      value = Character.reverseBytes(value);
    }
    storeChar(pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutChar(int index, char value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Character.reverseBytes(value);
    }
    storeChar(address + index, value);
  }

  public short getInt16(int index) {
    final long pos = address + index;
    short v = loadShort(pos);
    return LITTLE_ENDIAN ? v : Short.reverseBytes(v);
  }

  public void putInt16(int index, short value) {
    final long pos = address + index;
    if (!LITTLE_ENDIAN) {
      value = Short.reverseBytes(value);
    }
    storeShort(pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  public short _unsafeGetInt16(int index) {
    // CHECKSTYLE.ON:MethodName
    short v = loadShort(address + index);
    return LITTLE_ENDIAN ? v : Short.reverseBytes(v);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutInt16(int index, short value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Short.reverseBytes(value);
    }
    storeShort(address + index, value);
  }

  public int getInt32(int index) {
    final long pos = address + index;
    int v = loadInt(pos);
    return LITTLE_ENDIAN ? v : Integer.reverseBytes(v);
  }

  public void putInt32(int index, int value) {
    final long pos = address + index;
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    storeInt(pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  public int _unsafeGetInt32(int index) {
    // CHECKSTYLE.ON:MethodName
    int v = loadInt(address + index);
    return LITTLE_ENDIAN ? v : Integer.reverseBytes(v);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutInt32(int index, int value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    storeInt(address + index, value);
  }

  public long getInt64(int index) {
    final long pos = address + index;
    long v = loadLong(pos);
    return LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  public void putInt64(int index, long value) {
    final long pos = address + index;
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    storeLong(pos, value);
  }

  // CHECKSTYLE.OFF:MethodName
  public long _unsafeGetInt64(int index) {
    // CHECKSTYLE.ON:MethodName
    long v = loadLong(address + index);
    return LITTLE_ENDIAN ? v : Long.reverseBytes(v);
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafePutInt64(int index, long value) {
    // CHECKSTYLE.ON:MethodName
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    storeLong(address + index, value);
  }

  public float getFloat32(int index) {
    final long pos = address + index;
    int v = loadInt(pos);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    return Float.intBitsToFloat(v);
  }

  public void putFloat32(int index, float value) {
    final long pos = address + index;
    int v = Float.floatToRawIntBits(value);
    if (!LITTLE_ENDIAN) {
      v = Integer.reverseBytes(v);
    }
    storeInt(pos, v);
  }

  public double getFloat64(int index) {
    final long pos = address + index;
    long v = loadLong(pos);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    return Double.longBitsToDouble(v);
  }

  public void putFloat64(int index, double value) {
    final long pos = address + index;
    long v = Double.doubleToRawLongBits(value);
    if (!LITTLE_ENDIAN) {
      v = Long.reverseBytes(v);
    }
    storeLong(pos, v);
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
    checkHeapAddressAccess();
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
    storeByte(pos, (byte) (value ? 1 : 0));
    writerIndex = newIdx;
  }

  // CHECKSTYLE.OFF:MethodName
  public void _unsafeWriteByte(byte value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 1;
    final long pos = address + writerIdx;
    storeByte(pos, value);
    writerIndex = newIdx;
  }

  public void writeUInt8(int value) {
    writeByte((byte) value);
  }

  public void writeByte(byte value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 1;
    ensure(newIdx);
    final long pos = address + writerIdx;
    storeByte(pos, value);
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
    storeChar(pos, value);
    writerIndex = newIdx;
  }

  public void writeInt16(short value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 2;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Short.reverseBytes(value);
    }
    storeShort(address + writerIdx, value);
    writerIndex = newIdx;
  }

  public void writeInt32(int value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 4;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Integer.reverseBytes(value);
    }
    storeInt(address + writerIdx, value);
    writerIndex = newIdx;
  }

  public void writeInt64(long value) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + 8;
    ensure(newIdx);
    if (!LITTLE_ENDIAN) {
      value = Long.reverseBytes(value);
    }
    storeLong(address + writerIdx, value);
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
    storeInt(address + writerIdx, v);
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
    storeLong(address + writerIdx, v);
    writerIndex = newIdx;
  }

  /**
   * Write int using variable length encoding. If the value is positive, use {@link #writeVarUInt32}
   * to save one bit.
   */
  public int writeVarInt32(int v) {
    ensure(writerIndex + 8);
    // Zigzag encoding: maps negative values to positive values
    // This works entirely in int without conversion to long
    int varintBytes = _unsafePutVarUInt32(writerIndex, (v << 1) ^ (v >> 31));
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
    // Zigzag encoding ensures negatives close to zero are encoded in few bytes
    int varintBytes = _unsafePutVarUInt32(writerIndex, (v << 1) ^ (v >> 31));
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * Writes a 1-5 byte int.
   *
   * @return The number of bytes written.
   */
  public int writeVarUInt32(int v) {
    // ensure at least 8 bytes are writable at once, so jvm-jit
    // generated code is smaller. Otherwise, the reference writer fast path
    // may be `callee is too large`/`already compiled into a big method`
    ensure(writerIndex + 8);
    int varintBytes = _unsafePutVarUInt32(writerIndex, v);
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * For implementation efficiency, this method needs at most 8 bytes for writing 5 bytes using long
   * to avoid using two memory operations.
   */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteVarUInt32(int v) {
    // CHECKSTYLE.ON:MethodName
    int varintBytes = _unsafePutVarUInt32(writerIndex, v);
    writerIndex += varintBytes;
    return varintBytes;
  }

  /**
   * Fast method for write an unsigned varint which is mostly a small value in 7 bits value in [0,
   * 127). When the value is equal or greater than 127, the write will be a little slower.
   */
  public int writeVarUInt32Small7(int value) {
    ensure(writerIndex + 8);
    if (value >>> 7 == 0) {
      storeByte(address + writerIndex++, (byte) value);
      return 1;
    }
    return continueWriteVarUInt32Small7(value);
  }

  // Generated serializers depend on these small-varint JVM paths staying small enough for C2
  // inlining. Android exits through MemoryOps above; keep little-endian 1/2-byte stores local and
  // move only cold 3+ byte or big-endian cases into helpers.
  private int continueWriteVarUInt32Small7(int value) {
    int writerIdx = writerIndex;
    byte[] heap = heapMemory;
    if (heap != null) {
      int diff = putVarUInt32Heap(heap, (int) (address + writerIdx), value);
      writerIndex += diff;
      return diff;
    }
    ByteBuffer direct = nativeOffHeapBuffer;
    if (direct != null) {
      int diff = putVarUInt32Direct(direct, writerIdx, value);
      writerIndex += diff;
      return diff;
    }
    int encoded = (value & 0x7F);
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    if (!LITTLE_ENDIAN) {
      int diff = putVarUInt32BigEndian(writerIdx, encoded, value);
      writerIndex += diff;
      return diff;
    }
    if (value >>> 14 == 0) {
      storeInt(address + writerIdx, encoded);
      writerIndex += 2;
      return 2;
    }
    int diff = continuePutVarUInt32(writerIdx, encoded, value);
    writerIndex += diff;
    return diff;
  }

  /**
   * Writes an unsigned 32-bit varint at the given index using int operations. Caller must ensure
   * there are at least 8 bytes available for writing. This method avoids int-to-long conversion
   * overhead for the common cases (1-4 bytes).
   *
   * @param index the position to write at
   * @param value the unsigned 32-bit value (high bit may be set)
   * @return the number of bytes written (1-5)
   */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafePutVarUInt32(int index, int value) {
    // CHECKSTYLE.ON:MethodName
    byte[] heap = heapMemory;
    if (heap != null) {
      return putVarUInt32Heap(heap, (int) (address + index), value);
    }
    ByteBuffer direct = nativeOffHeapBuffer;
    if (direct != null) {
      return putVarUInt32Direct(direct, index, value);
    }
    int encoded = (value & 0x7F);
    if (value >>> 7 == 0) {
      storeByte(address + index, (byte) value);
      return 1;
    }
    // bit 8 `set` indicates have next data bytes.
    // 0x3f80: 0b1111111 << 7
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    if (!LITTLE_ENDIAN) {
      return putVarUInt32BigEndian(index, encoded, value);
    }
    if (value >>> 14 == 0) {
      storeInt(address + index, encoded);
      return 2;
    }
    return continuePutVarUInt32(index, encoded, value);
  }

  private int putVarUInt32Direct(ByteBuffer direct, int index, int value) {
    int pos = (int) (address + index);
    int encoded = (value & 0x7F);
    if (value >>> 7 == 0) {
      direct.put(pos, (byte) value);
      return 1;
    }
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    if (!LITTLE_ENDIAN) {
      return putVarUInt32BigEndian(index, encoded, value);
    }
    if (value >>> 14 == 0) {
      BYTE_BUFFER_INT.set(direct, pos, encoded);
      return 2;
    }
    return continuePutVarUInt32Direct(direct, pos, encoded, value);
  }

  private static int continuePutVarUInt32Direct(
      ByteBuffer direct, int pos, int encoded, int value) {
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      BYTE_BUFFER_INT.set(direct, pos, encoded);
      return 3;
    }
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      BYTE_BUFFER_INT.set(direct, pos, encoded);
      return 4;
    }
    long encodedLong = Integer.toUnsignedLong(encoded) | 0x80000000L;
    encodedLong |= (long) (value >>> 28) << 32;
    BYTE_BUFFER_LONG.set(direct, pos, encodedLong);
    return 5;
  }

  private int continuePutVarUInt32(int index, int encoded, int value) {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      storeInt(address + index, encoded);
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      storeInt(address + index, encoded);
      return 4;
    }
    // 5-byte case: bits 28-31 go to the 5th byte
    // Need long for the final write to include the 5th byte
    long encodedLong = Integer.toUnsignedLong(encoded) | 0x80000000L;
    encodedLong |= (long) (value >>> 28) << 32;
    storeLong(address + index, encodedLong);
    return 5;
  }

  private int putVarUInt32BigEndian(int index, int encoded, int value) {
    if (value >>> 14 == 0) {
      storeInt(address + index, Integer.reverseBytes(encoded));
      return 2;
    }
    return continuePutVarUInt32BigEndian(index, encoded, value);
  }

  private int continuePutVarUInt32BigEndian(int index, int encoded, int value) {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      storeInt(address + index, Integer.reverseBytes(encoded));
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      storeInt(address + index, Integer.reverseBytes(encoded));
      return 4;
    }
    // 5-byte case: bits 28-31 go to the 5th byte
    // Need long for the final write to include the 5th byte
    long encodedLong = Integer.toUnsignedLong(encoded) | 0x80000000L;
    encodedLong |= (long) (value >>> 28) << 32;
    storeLong(address + index, Long.reverseBytes(encodedLong));
    return 5;
  }

  /**
   * Caller must ensure there must be at least 8 bytes for writing, otherwise the crash may occur.
   * Don't pass int value to avoid sign extension.
   */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafePutVarUint36Small(int index, long value) {
    // CHECKSTYLE.ON:MethodName
    long encoded = (value & 0x7F);
    if (value >>> 7 == 0) {
      storeByte(address + index, (byte) value);
      return 1;
    }
    // bit 8 `set` indicates have next data bytes.
    // 0x3f80: 0b1111111 << 7
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    if (!LITTLE_ENDIAN) {
      return putVarUint36SmallBigEndian(index, encoded, value);
    }
    if (value >>> 14 == 0) {
      storeInt(address + index, (int) encoded);
      return 2;
    }
    return continuePutVarUint36Small(index, encoded, value);
  }

  private int continuePutVarUint36Small(int index, long encoded, long value) {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      storeInt(address + index, (int) encoded);
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      storeInt(address + index, (int) encoded);
      return 4;
    }
    // 0xff0000000: 0b11111111 << 28. Note eight `1` here instead of seven.
    encoded |= ((value & 0xff0000000L) << 4) | 0x80000000L;
    storeLong(address + index, encoded);
    return 5;
  }

  private int putVarUint36SmallBigEndian(int index, long encoded, long value) {
    if (value >>> 14 == 0) {
      storeInt(address + index, Integer.reverseBytes((int) encoded));
      return 2;
    }
    return continuePutVarUint36SmallBigEndian(index, encoded, value);
  }

  private int continuePutVarUint36SmallBigEndian(int index, long encoded, long value) {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 == 0) {
      storeInt(address + index, Integer.reverseBytes((int) encoded));
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 == 0) {
      storeInt(address + index, Integer.reverseBytes((int) encoded));
      return 4;
    }
    // 0xff0000000: 0b11111111 << 28. Note eight `1` here instead of seven.
    encoded |= ((value & 0xff0000000L) << 4) | 0x80000000L;
    storeLong(address + index, Long.reverseBytes(encoded));
    return 5;
  }

  /**
   * Writes a 1-9 byte int, padding necessary bytes to align `writerIndex` to 4-byte.
   *
   * @return The number of bytes written.
   */
  public int writeVarUInt32Aligned(int value) {
    // Mask first 6 bits,
    // bit 7 `unset` indicates have next padding bytes,
    // bit 8 `set` indicates have next data bytes.
    if (value >>> 6 == 0) {
      return writeVarUInt32Aligned1(value);
    }
    if (value >>> 12 == 0) { // 2 byte data
      return writeVarUInt32Aligned2(value);
    }
    if (value >>> 18 == 0) { // 3 byte data
      return writeVarUInt32Aligned3(value);
    }
    if (value >>> 24 == 0) { // 4 byte data
      return writeVarUInt32Aligned4(value);
    }
    if (value >>> 30 == 0) { // 5 byte data
      return writeVarUInt32Aligned5(value);
    }
    // 6 byte data
    return writeVarUInt32Aligned6(value);
  }

  private int writeVarUInt32Aligned1(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 5); // 1 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    if (numPaddingBytes == 1) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos, (byte) (first | 0x40));
      writerIndex = (writerIdx + 1);
      return 1;
    } else {
      storeByte(pos, (byte) first);
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 1, 0);
      storeByte(pos + numPaddingBytes - 1, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes;
      return numPaddingBytes;
    }
  }

  private int writeVarUInt32Aligned2(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 6); // 2 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    storeByte(pos, (byte) (first | 0x80));
    if (numPaddingBytes == 2) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos + 1, (byte) ((value >>> 6) | 0x40));
      writerIndex = writerIdx + 2;
      return 2;
    } else {
      storeByte(pos + 1, (byte) (value >>> 6));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 2, 0);
      if (numPaddingBytes > 2) {
        storeByte(pos + numPaddingBytes - 1, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes;
        return numPaddingBytes;
      } else {
        storeByte(pos + 4, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  private int writeVarUInt32Aligned3(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 7); // 3 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    storeByte(pos, (byte) (first | 0x80));
    storeByte(pos + 1, (byte) ((value >>> 6) | 0x80));
    if (numPaddingBytes == 3) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos + 2, (byte) ((value >>> 12) | 0x40));
      writerIndex = writerIdx + 3;
      return 3;
    } else {
      storeByte(pos + 2, (byte) (value >>> 12));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 3, 0);
      if (numPaddingBytes == 4) {
        storeByte(pos + numPaddingBytes - 1, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes;
        return numPaddingBytes;
      } else {
        storeByte(pos + numPaddingBytes + 3, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  private int writeVarUInt32Aligned4(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 8); // 4 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    storeByte(pos, (byte) (first | 0x80));
    storeByte(pos + 1, (byte) (value >>> 6 | 0x80));
    storeByte(pos + 2, (byte) (value >>> 12 | 0x80));
    if (numPaddingBytes == 4) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos + 3, (byte) ((value >>> 18) | 0x40));
      writerIndex = writerIdx + 4;
      return 4;
    } else {
      storeByte(pos + 3, (byte) (value >>> 18));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 4, 0);
      storeByte(pos + numPaddingBytes + 3, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes + 4;
      return numPaddingBytes + 4;
    }
  }

  private int writeVarUInt32Aligned5(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 9); // 5 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    storeByte(pos, (byte) (first | 0x80));
    storeByte(pos + 1, (byte) (value >>> 6 | 0x80));
    storeByte(pos + 2, (byte) (value >>> 12 | 0x80));
    storeByte(pos + 3, (byte) (value >>> 18 | 0x80));
    if (numPaddingBytes == 1) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos + 4, (byte) ((value >>> 24) | 0x40));
      writerIndex = writerIdx + 5;
      return 5;
    } else {
      storeByte(pos + 4, (byte) (value >>> 24));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 5, 0);
      storeByte(pos + numPaddingBytes + 3, (byte) (0x40));
      writerIndex = writerIdx + numPaddingBytes + 4;
      return numPaddingBytes + 4;
    }
  }

  private int writeVarUInt32Aligned6(int value) {
    final int writerIdx = writerIndex;
    int numPaddingBytes = 4 - writerIdx % 4;
    ensure(writerIdx + 10); // 6 byte + 4 bytes(zero out), padding range in (zero out)
    int first = (value & 0x3F);
    final long pos = address + writerIdx;
    storeByte(pos, (byte) (first | 0x80));
    storeByte(pos + 1, (byte) (value >>> 6 | 0x80));
    storeByte(pos + 2, (byte) (value >>> 12 | 0x80));
    storeByte(pos + 3, (byte) (value >>> 18 | 0x80));
    storeByte(pos + 4, (byte) (value >>> 24 | 0x80));
    if (numPaddingBytes == 2) {
      // bit 7 `set` indicates not have padding bytes.
      // bit 8 `set` indicates have next data bytes.
      storeByte(pos + 5, (byte) ((value >>> 30) | 0x40));
      writerIndex = writerIdx + 6;
      return 6;
    } else {
      storeByte(pos + 5, (byte) (value >>> 30));
      // zero out 4 bytes, so that `bit 7` value can be trusted.
      storeInt(pos + 6, 0);
      if (numPaddingBytes == 1) {
        storeByte(pos + 8, (byte) (0x40));
        writerIndex = writerIdx + 9;
        return 9;
      } else {
        storeByte(pos + numPaddingBytes + 3, (byte) (0x40));
        writerIndex = writerIdx + numPaddingBytes + 4;
        return numPaddingBytes + 4;
      }
    }
  }

  /**
   * Write long using variable length encoding. If the value is positive, use {@link
   * #writeVarUInt64} to save one bit.
   */
  public int writeVarInt64(long value) {
    ensure(writerIndex + 9);
    return _unsafeWriteVarUInt64((value << 1) ^ (value >> 63));
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteVarInt64(long value) {
    // CHECKSTYLE.ON:MethodName
    return _unsafeWriteVarUInt64((value << 1) ^ (value >> 63));
  }

  public int writeVarUInt64(long value) {
    // Var long encoding algorithm is based kryo UnsafeMemoryOutput.writeVarInt64.
    // var long are written using little endian byte order.
    ensure(writerIndex + 9);
    return _unsafeWriteVarUInt64(value);
  }

  // CHECKSTYLE.OFF:MethodName
  @CodegenInvoke
  public int _unsafeWriteVarUInt64(long value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIndex = this.writerIndex;
    int varInt;
    varInt = (int) (value & 0x7F);
    if (value >>> 7 == 0) {
      storeByte(address + writerIndex, (byte) varInt);
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
    storeByte(address + writerIndex + 8, (byte) (value & 0xFF));
    this.writerIndex = writerIndex + 9;
    return 9;
  }

  /**
   * Write signed long using fory Tagged(Small long as int) encoding. If long is in [0xc0000000,
   * 0x3fffffff], encode as 4 bytes int: {@code | little-endian: ((int) value) << 1 |}; Otherwise
   * write as 9 bytes: {@code | 0b1 | little-endian 8bytes long |}.
   */
  public int writeTaggedInt64(long value) {
    ensure(writerIndex + 9);
    return _unsafeWriteTaggedInt64(value);
  }

  /**
   * Write unsigned long using fory Tagged(Small long as int) encoding. If long is in [0,
   * 0x7fffffff], encode as 4 bytes int: {@code | little-endian: ((int) value) << 1 |}; Otherwise
   * write as 9 bytes: {@code | 0b1 | little-endian 8bytes long |}.
   */
  public int writeTaggedUInt64(long value) {
    ensure(writerIndex + 9);
    return _unsafeWriteTaggedUInt64(value);
  }

  /** Write unsigned long using fory Tagged(Small Long as Int) encoding. */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteTaggedUInt64(long value) {
    // CHECKSTYLE.ON:MethodName
    final int writerIndex = this.writerIndex;
    final long pos = address + writerIndex;
    final byte[] heapMemory = this.heapMemory;
    if (value >= 0 && value <= Integer.MAX_VALUE) {
      int v = ((int) value) << 1; // bit 0 unset, means int.
      if (!LITTLE_ENDIAN) {
        v = Integer.reverseBytes(v);
      }
      storeInt(pos, v);
      this.writerIndex = writerIndex + 4;
      return 4;
    } else {
      storeByte(pos, BIG_LONG_FLAG);
      if (!LITTLE_ENDIAN) {
        value = Long.reverseBytes(value);
      }
      storeLong(pos + 1, value);
      this.writerIndex = writerIndex + 9;
      return 9;
    }
  }

  private static final long HALF_MAX_INT_VALUE = Integer.MAX_VALUE / 2;
  private static final long HALF_MIN_INT_VALUE = Integer.MIN_VALUE / 2;
  private static final byte BIG_LONG_FLAG = 0b1; // bit 0 set, means big long.

  /** Write long using fory Tagged(Small Long as Int) encoding. */
  // CHECKSTYLE.OFF:MethodName
  public int _unsafeWriteTaggedInt64(long value) {
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
      storeInt(pos, v);
      this.writerIndex = writerIndex + 4;
      return 4;
    } else {
      storeByte(pos, BIG_LONG_FLAG);
      if (!LITTLE_ENDIAN) {
        value = Long.reverseBytes(value);
      }
      storeLong(pos + 1, value);
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

  public void writeBytesWithSize(byte[] values) {
    writeVarUInt32Small7(values.length);
    writeBytes(values, 0, values.length);
  }

  public void writeBooleansWithSize(boolean[] values) {
    writeVarUInt32Small7(values.length);
    writeBooleans(values, 0, values.length);
  }

  public void writeBooleans(boolean[] values) {
    writeBooleans(values, 0, values.length);
  }

  public void writeBooleans(boolean[] values, int offset, int numElements) {
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numElements;
    ensure(newIdx);
    writeBooleansFromArray(address + writerIdx, values, BOOLEAN_ARRAY_OFFSET + offset, numElements);
    writerIndex = newIdx;
  }

  public void writeCharsWithSize(char[] values) {
    int numBytes = Math.multiplyExact(values.length, 2);
    writeVarUInt32Small7(numBytes);
    writeChars(values, 0, values.length);
  }

  public void writeChars(char[] values) {
    writeChars(values, 0, values.length);
  }

  public void writeChars(char[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 2);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeCharsFromArray(address + writerIdx, values, CHAR_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  public void writeShortsWithSize(short[] values) {
    int numBytes = Math.multiplyExact(values.length, 2);
    writeVarUInt32Small7(numBytes);
    writeShorts(values, 0, values.length);
  }

  public void writeShorts(short[] values) {
    writeShorts(values, 0, values.length);
  }

  public void writeShorts(short[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 2);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeShortsFromArray(address + writerIdx, values, SHORT_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  public void writeIntsWithSize(int[] values) {
    int numBytes = Math.multiplyExact(values.length, 4);
    writeVarUInt32Small7(numBytes);
    writeInts(values, 0, values.length);
  }

  public void writeInts(int[] values) {
    writeInts(values, 0, values.length);
  }

  public void writeInts(int[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 4);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeIntsFromArray(address + writerIdx, values, INT_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  public void writeLongsWithSize(long[] values) {
    int numBytes = Math.multiplyExact(values.length, 8);
    writeVarUInt32Small7(numBytes);
    writeLongs(values, 0, values.length);
  }

  public void writeLongs(long[] values) {
    writeLongs(values, 0, values.length);
  }

  public void writeLongs(long[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 8);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeLongsFromArray(address + writerIdx, values, LONG_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  public void writeFloatsWithSize(float[] values) {
    int numBytes = Math.multiplyExact(values.length, 4);
    writeVarUInt32Small7(numBytes);
    writeFloats(values, 0, values.length);
  }

  public void writeFloats(float[] values) {
    writeFloats(values, 0, values.length);
  }

  public void writeFloats(float[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 4);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeFloatsFromArray(address + writerIdx, values, FLOAT_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  public void writeDoublesWithSize(double[] values) {
    int numBytes = Math.multiplyExact(values.length, 8);
    writeVarUInt32Small7(numBytes);
    writeDoubles(values, 0, values.length);
  }

  public void writeDoubles(double[] values) {
    writeDoubles(values, 0, values.length);
  }

  public void writeDoubles(double[] values, int offset, int numElements) {
    int numBytes = Math.multiplyExact(numElements, 8);
    final int writerIdx = writerIndex;
    final int newIdx = writerIdx + numBytes;
    ensure(newIdx);
    writeDoublesFromArray(address + writerIdx, values, DOUBLE_ARRAY_OFFSET + offset, numBytes);
    writerIndex = newIdx;
  }

  /** For off-heap buffer, this will make a heap buffer internally. */
  public void grow(int neededSize) {
    int length = writerIndex + neededSize;
    if (length > size) {
      globalAllocator.grow(this, length);
    }
  }

  /** For off-heap buffer, this will make a heap buffer internally. */
  public void ensure(int length) {
    if (length > size) {
      globalAllocator.grow(this, length);
    }
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
    checkHeapAddressAccess();
    return address + readerIndex;
  }

  private void checkHeapAddressAccess() {
    if (heapMemory == null) {
      throw new UnsupportedOperationException(
          "JDK25 MemoryBuffer does not expose raw native addresses for direct buffers.");
    }
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
    return loadByte(address + readerIdx) != 0;
  }

  public int readUInt8() {
    int readerIdx = readerIndex;
    if (readerIdx > size - 1) {
      streamReader.fillBuffer(1);
    }
    readerIndex = readerIdx + 1;
    int v = loadByte(address + readerIdx);
    v &= 0b11111111;
    return v;
  }

  public int readUnsignedByte() {
    return readUInt8();
  }

  public byte readByte() {
    int readerIdx = readerIndex;
    if (readerIdx > size - 1) {
      streamReader.fillBuffer(1);
    }
    readerIndex = readerIdx + 1;
    return loadByte(address + readerIdx);
  }

  public char readChar() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 2) {
      streamReader.fillBuffer(2 - remaining);
    }
    readerIndex = readerIdx + 2;
    char c = loadChar(address + readerIdx);
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
    short v = loadShort(address + readerIdx);
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
    return loadShort(address + readerIdx);
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
    return Short.reverseBytes(loadShort(address + readerIdx));
  }

  public int readInt32() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 4) {
      streamReader.fillBuffer(4 - remaining);
    }
    readerIndex = readerIdx + 4;
    int v = loadInt(address + readerIdx);
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
    return loadInt(address + readerIdx);
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
    return Integer.reverseBytes(loadInt(address + readerIdx));
  }

  public long readInt64() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    int remaining = size - readerIdx;
    if (remaining < 8) {
      streamReader.fillBuffer(8 - remaining);
    }
    readerIndex = readerIdx + 8;
    long v = loadLong(address + readerIdx);
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
    return loadLong(address + readerIdx);
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
    return Long.reverseBytes(loadLong(address + readerIdx));
  }

  /** Read signed fory Tagged(Small Long as Int) encoded long. */
  public long readTaggedInt64() {
    if (LITTLE_ENDIAN) {
      return _readTaggedInt64OnLE();
    } else {
      return _readTaggedInt64OnBE();
    }
  }

  /** Read unsigned fory Tagged(Small Long as Int) encoded long. */
  public long readTaggedUInt64() {
    if (LITTLE_ENDIAN) {
      return _readTaggedUInt64OnLE();
    } else {
      return _readTaggedUInt64OnBE();
    }
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readTaggedUInt64OnLE() {
    // CHECKSTYLE.ON:MethodName
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = loadInt(address + readIdx);
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >>> 1; // unsigned right shift
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return loadLong(address + readIdx + 1);
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readTaggedUInt64OnBE() {
    // CHECKSTYLE.ON:MethodName
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = Integer.reverseBytes(loadInt(address + readIdx));
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >>> 1; // unsigned right shift
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return Long.reverseBytes(loadLong(address + readIdx + 1));
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readTaggedInt64OnLE() {
    // CHECKSTYLE.ON:MethodName
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = loadInt(address + readIdx);
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >> 1;
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return loadLong(address + readIdx + 1);
  }

  @CodegenInvoke
  // CHECKSTYLE.OFF:MethodName
  public long _readTaggedInt64OnBE() {
    // CHECKSTYLE.ON:MethodName
    // noinspection Duplicates
    final int readIdx = readerIndex;
    int diff = size - readIdx;
    if (diff < 4) {
      streamReader.fillBuffer(4 - diff);
    }
    int i = Integer.reverseBytes(loadInt(address + readIdx));
    if ((i & 0b1) != 0b1) {
      readerIndex = readIdx + 4;
      return i >> 1;
    }
    diff = size - readIdx;
    if (diff < 9) {
      streamReader.fillBuffer(9 - diff);
    }
    readerIndex = readIdx + 9;
    return Long.reverseBytes(loadLong(address + readIdx + 1));
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
    int v = loadInt(address + readerIdx);
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
    return Float.intBitsToFloat(loadInt(address + readerIdx));
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
    return Float.intBitsToFloat(Integer.reverseBytes(loadInt(address + readerIdx)));
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
    long v = loadLong(address + readerIdx);
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
    return Double.longBitsToDouble(loadLong(address + readerIdx));
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
    return Double.longBitsToDouble(Long.reverseBytes(loadLong(address + readerIdx)));
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
      result = readVarUInt32Slow();
    } else {
      long address = this.address;
      // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
      int fourByteValue = loadInt(address + readIdx);
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
              int fifthByte = loadByte(address + readIdx++) & 0xFF;
              if ((fifthByte & 0xF0) != 0) {
                throwMalformedVarUInt32(fifthByte);
              }
              result |= fifthByte << 28;
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
      result = readVarUInt32Slow();
    } else {
      long address = this.address;
      int fourByteValue = Integer.reverseBytes(loadInt(address + readIdx));
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
              int fifthByte = loadByte(address + readIdx++) & 0xFF;
              if ((fifthByte & 0xF0) != 0) {
                throwMalformedVarUInt32(fifthByte);
              }
              result |= fifthByte << 28;
            }
          }
        }
      }
      readerIndex = readIdx;
    }
    return (result >>> 1) ^ -(result & 1);
  }

  public long readVarUint36Small() {
    // Android exits above. Keep JVM small-varint bulk reads as direct bulk loads instead of calling
    // `_unsafeGet*` helpers; those helpers carry Android/endian branches and can break inlining.
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    int readIdx = readerIndex;
    if (size - readIdx >= 9) {
      long bulkValue = loadLong(address + readIdx++);
      if (!LITTLE_ENDIAN) {
        bulkValue = Long.reverseBytes(bulkValue);
      }
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

  private int readVarUInt32Slow() {
    int b = readByte() & 0xFF;
    int result = b & 0x7F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    // noinspection Duplicates
    if ((b & 0x80) != 0) {
      b = readByte() & 0xFF;
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = readByte() & 0xFF;
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = readByte() & 0xFF;
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = readByte() & 0xFF;
            if ((b & 0xF0) != 0) {
              throwMalformedVarUInt32(b);
            }
            result |= b << 28;
          }
        }
      }
    }
    return result;
  }

  private static void throwMalformedVarUInt32(int fifthByte) {
    throw new IllegalArgumentException(
        "Malformed varuint32 fifth byte " + fifthByte + " exceeds 32 bits");
  }

  /** Reads the 1-5 byte int part of a non-negative varint. */
  public int readVarUInt32() {
    int readIdx = readerIndex;
    if (size - readIdx < 5) {
      return readVarUInt32Slow();
    }
    // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
    int fourByteValue = loadInt(address + readIdx);
    if (!LITTLE_ENDIAN) {
      fourByteValue = Integer.reverseBytes(fourByteValue);
    }
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
            int fifthByte = loadByte(address + readIdx++) & 0xFF;
            if ((fifthByte & 0xF0) != 0) {
              throwMalformedVarUInt32(fifthByte);
            }
            result |= fifthByte << 28;
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
  public int readVarUInt32Small7() {
    int readIdx = readerIndex;
    if (size - readIdx > 0) {
      byte v = loadByte(address + readIdx++);
      if ((v & 0x80) == 0) {
        readerIndex = readIdx;
        return v;
      }
    }
    return readVarUInt32Small14();
  }

  /**
   * Fast path for read an unsigned varint which is mostly a small value in 14 bits value in [0,
   * 16384). When the value is equal or greater than 16384, the read will be a little slower.
   */
  public int readVarUInt32Small14() {
    int readIdx = readerIndex;
    if (size - readIdx >= 5) {
      int fourByteValue = loadInt(address + readIdx++);
      if (!LITTLE_ENDIAN) {
        fourByteValue = Integer.reverseBytes(fourByteValue);
      }
      int value = fourByteValue & 0x7F;
      // Duplicate and manual inline for performance.
      // noinspection Duplicates
      if ((fourByteValue & 0x80) != 0) {
        readIdx++;
        value |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) != 0) {
          // merely executed path, make it as a separate method to reduce
          // code size of current method for better jvm inline
          return continueReadVarUInt32(readIdx, fourByteValue, value);
        }
      }
      readerIndex = readIdx;
      return value;
    } else {
      return readVarUInt32Slow();
    }
  }

  private int continueReadVarUInt32(int readIdx, int bulkRead, int value) {
    // Duplicate and manual inline for performance.
    // noinspection Duplicates
    readIdx++;
    value |= (bulkRead >>> 2) & 0x1fc000;
    if ((bulkRead & 0x800000) != 0) {
      readIdx++;
      value |= (bulkRead >>> 3) & 0xfe00000;
      if ((bulkRead & 0x80000000) != 0) {
        int fifthByte = loadByte(address + readIdx++) & 0xFF;
        if ((fifthByte & 0xF0) != 0) {
          throwMalformedVarUInt32(fifthByte);
        }
        value |= fifthByte << 28;
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
      result = readVarUInt64Slow();
    } else {
      long address = this.address;
      long bulkValue = loadLong(address + readIdx);
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
      result = readVarUInt64Slow();
    } else {
      long address = this.address;
      long bulkValue = Long.reverseBytes(loadLong(address + readIdx));
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
  public long readVarUInt64() {
    int readIdx = readerIndex;
    if (size - readIdx < 9) {
      return readVarUInt64Slow();
    }
    // varint are written using little endian byte order, so read by little endian byte order.
    long bulkValue = loadLong(address + readIdx);
    if (!LITTLE_ENDIAN) {
      bulkValue = Long.reverseBytes(bulkValue);
    }
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
                long b = loadByte(address + readIdx++);
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

  private long readVarUInt64Slow() {
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
  public int readAlignedVarUInt32() {
    int readerIdx = readerIndex;
    // use subtract to avoid overflow
    if (readerIdx < size - 10) {
      return slowReadAlignedVarUInt32();
    }
    long pos = address + readerIdx;
    long startPos = pos;
    int b = loadByte(pos++);
    // Mask first 6 bits,
    // bit 8 `set` indicates have next data bytes.
    int result = b & 0x3F;
    // Note:
    //  Loop are not used here to improve performance.
    //  We manually unroll the loop for better performance.
    if ((b & 0x80) != 0) { // has 2nd byte
      b = loadByte(pos++);
      result |= (b & 0x3F) << 6;
      if ((b & 0x80) != 0) { // has 3rd byte
        b = loadByte(pos++);
        result |= (b & 0x3F) << 12;
        if ((b & 0x80) != 0) { // has 4th byte
          b = loadByte(pos++);
          result |= (b & 0x3F) << 18;
          if ((b & 0x80) != 0) { // has 5th byte
            b = loadByte(pos++);
            result |= (b & 0x3F) << 24;
            if ((b & 0x80) != 0) { // has 6th byte
              b = loadByte(pos++);
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

  public int slowReadAlignedVarUInt32() {
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
      b = loadByte(pos++);
      if ((b & 0x40) == 0) { // has 2nd padding bytes
        b = loadByte(pos++);
        if ((b & 0x40) == 0) { // has 3rd padding bytes
          b = loadByte(pos++);
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
    readBytesToArray(address + readerIdx, bytes, BYTE_ARRAY_OFFSET, length);
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
    get(readerIdx, dst, dstIndex, length);
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
      long v = loadLong(address + readerIdx);
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
        result |= ((long) loadByte(start + i) & 0xff) << (i * 8);
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
   * is optimized for small size, it's faster than {@link #readVarUInt32}.
   */
  public int readBinarySize() {
    int binarySize;
    int readIdx = readerIndex;
    if (size - readIdx >= 5) {
      // Android exits above. Keep this small-size fast path as a raw JVM load; `_unsafeGetInt32`
      // carries Android/endian branches and can grow the method enough to disturb inlining.
      int fourByteValue = loadInt(address + readIdx++);
      if (!LITTLE_ENDIAN) {
        fourByteValue = Integer.reverseBytes(fourByteValue);
      }
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
      binarySize = readVarUInt32Slow();
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
        int fifthByte = loadByte(address + readIdx++) & 0xFF;
        if ((fifthByte & 0xF0) != 0) {
          throwMalformedVarUInt32(fifthByte);
        }
        binarySize |= fifthByte << 28;
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
    readBytesToArray(address + readerIdx, arr, BYTE_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
    return arr;
  }

  /**
   * Reads a size-validated primitive byte-array payload into {@code values}. The caller owns size
   * validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readByteArrayPayload(byte[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readTo(values, 0, numBytes);
      return;
    }
    readBytesToArray(address + readerIdx, values, BYTE_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive boolean-array payload into {@code values}. The caller owns
   * size validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readBooleanArrayPayload(boolean[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readBooleans(values, 0, numBytes);
      return;
    }
    readBooleansToArray(address + readerIdx, values, BOOLEAN_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive char-array payload into {@code values}. The caller owns size
   * validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readCharArrayPayload(char[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readChars(values, 0, numBytes >>> 1);
      return;
    }
    readCharsToArray(address + readerIdx, values, CHAR_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive int16-array payload into {@code values}. The caller owns size
   * validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readInt16ArrayPayload(short[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readShorts(values, 0, numBytes >>> 1);
      return;
    }
    readShortsToArray(address + readerIdx, values, SHORT_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive int32-array payload into {@code values}. The caller owns size
   * validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readInt32ArrayPayload(int[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readInts(values, 0, numBytes >>> 2);
      return;
    }
    readIntsToArray(address + readerIdx, values, INT_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive int64-array payload into {@code values}. The caller owns size
   * validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readInt64ArrayPayload(long[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readLongs(values, 0, numBytes >>> 3);
      return;
    }
    readLongsToArray(address + readerIdx, values, LONG_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive float32-array payload into {@code values}. The caller owns
   * size validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readFloat32ArrayPayload(float[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readFloats(values, 0, numBytes >>> 2);
      return;
    }
    readFloatsToArray(address + readerIdx, values, FLOAT_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  /**
   * Reads a size-validated primitive float64-array payload into {@code values}. The caller owns
   * size validation and destination allocation; this method reads payload bytes only, not the size
   * prefix.
   */
  public void readFloat64ArrayPayload(double[] values, int numBytes) {
    int readerIdx = readerIndex;
    if (readerIdx > size - numBytes) {
      streamReader.readDoubles(values, 0, numBytes >>> 3);
      return;
    }
    readDoublesToArray(address + readerIdx, values, DOUBLE_ARRAY_OFFSET, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  public void readBooleans(boolean[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    if (readerIndex > size - numElements) {
      streamReader.readBooleans(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readBooleansToArray(address + readerIdx, values, BOOLEAN_ARRAY_OFFSET + offset, numElements);
    readerIndex = readerIdx + numElements;
  }

  public void readChars(char[] chars, int numElements) {
    readChars(chars, 0, numElements);
  }

  public void readChars(char[] chars, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (chars.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 2);
    if (readerIndex > size - numBytes) {
      streamReader.readChars(chars, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readCharsToArray(address + readerIdx, chars, CHAR_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  @CodegenInvoke
  public char[] readCharsAndSize() {
    final int numBytes = readBinarySize();
    int numElements = numBytes >> 1;
    char[] values = new char[numElements];
    readChars(values, 0, numElements);
    return values;
  }

  public void readShorts(short[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 2);
    if (readerIndex > size - numBytes) {
      streamReader.readShorts(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readShortsToArray(address + readerIdx, values, SHORT_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  public void readInts(int[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 4);
    if (readerIndex > size - numBytes) {
      streamReader.readInts(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readIntsToArray(address + readerIdx, values, INT_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  public void readLongs(long[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 8);
    if (readerIndex > size - numBytes) {
      streamReader.readLongs(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readLongsToArray(address + readerIdx, values, LONG_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  public void readFloats(float[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 4);
    if (readerIndex > size - numBytes) {
      streamReader.readFloats(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readFloatsToArray(address + readerIdx, values, FLOAT_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
  }

  public void readDoubles(double[] values, int offset, int numElements) {
    if ((offset | numElements | (offset + numElements) | (values.length - offset - numElements))
        < 0) {
      throwOOBException();
    }
    int numBytes = Math.multiplyExact(numElements, 8);
    if (readerIndex > size - numBytes) {
      streamReader.readDoubles(values, offset, numElements);
      return;
    }
    int readerIdx = readerIndex;
    readDoublesToArray(address + readerIdx, values, DOUBLE_ARRAY_OFFSET + offset, numBytes);
    readerIndex = readerIdx + numBytes;
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

  public void copyTo(int offset, MemoryBuffer target, int targetOffset, int numBytes) {
    final byte[] thisHeapRef = this.heapMemory;
    final byte[] otherHeapRef = target.heapMemory;
    final long thisPointer = this.address + offset;
    final long otherPointer = target.address + targetOffset;
    if ((numBytes | offset | targetOffset) >= 0
        && thisPointer <= this.addressLimit - numBytes
        && otherPointer <= target.addressLimit - numBytes) {
      if (thisHeapRef != null && otherHeapRef != null) {
        System.arraycopy(
            thisHeapRef, toIntIndex(thisPointer), otherHeapRef, toIntIndex(otherPointer), numBytes);
      } else if (sameBufferOverlap(target, offset, targetOffset, numBytes)) {
        byte[] tmp = new byte[numBytes];
        sliceAsByteBuffer(offset, numBytes).get(tmp);
        target.sliceAsByteBuffer(targetOffset, numBytes).put(tmp);
      } else {
        target.sliceAsByteBuffer(targetOffset, numBytes).put(sliceAsByteBuffer(offset, numBytes));
      }
    } else {
      throw new IndexOutOfBoundsException(
          String.format(
              "offset=%d, targetOffset=%d, numBytes=%d, address=%d, targetAddress=%d",
              offset, targetOffset, numBytes, this.address, target.address));
    }
  }

  private boolean sameBufferOverlap(
      MemoryBuffer target, int offset, int targetOffset, int numBytes) {
    if (numBytes <= 0) {
      return false;
    }
    if (heapMemory != null && heapMemory == target.heapMemory) {
      int sourceStart = heapOffset + offset;
      int targetStart = target.heapOffset + targetOffset;
      return sourceStart < targetStart + numBytes && targetStart < sourceStart + numBytes;
    }
    if (offHeapBuffer != null && offHeapBuffer == target.offHeapBuffer) {
      long sourceStart = address + offset;
      long targetStart = target.address + targetOffset;
      return sourceStart < targetStart + numBytes && targetStart < sourceStart + numBytes;
    }
    return false;
  }

  public void copyFrom(int offset, MemoryBuffer source, int sourcePointer, int numBytes) {
    source.copyTo(sourcePointer, this, offset, numBytes);
  }

  public void copyToByteArray(int offset, byte[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 0);
    readBytesToArray(address + offset, target, BYTE_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToBooleanArray(int offset, boolean[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 0);
    readBooleansToArray(address + offset, target, BOOLEAN_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToCharArray(int offset, char[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 1);
    readCharsToArray(address + offset, target, CHAR_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToShortArray(int offset, short[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 1);
    readShortsToArray(address + offset, target, SHORT_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToIntArray(int offset, int[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 2);
    readIntsToArray(address + offset, target, INT_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToLongArray(int offset, long[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 3);
    readLongsToArray(address + offset, target, LONG_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToFloatArray(int offset, float[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 2);
    readFloatsToArray(address + offset, target, FLOAT_ARRAY_OFFSET + targetOffset, numBytes);
  }

  public void copyToDoubleArray(int offset, double[] target, int targetOffset, int numBytes) {
    checkArrayCopy(offset, targetOffset, target.length, numBytes, 3);
    readDoublesToArray(address + offset, target, DOUBLE_ARRAY_OFFSET + targetOffset, numBytes);
  }

  private void checkArrayCopy(
      int offset, int targetOffset, int targetLength, int numBytes, int elementShift) {
    int elementMask = (1 << elementShift) - 1;
    if ((numBytes & elementMask) != 0) {
      throw new IllegalArgumentException("numBytes is not aligned to array element size");
    }
    int numElements = numBytes >> elementShift;
    if ((offset | targetOffset | numBytes | numElements) < 0
        || offset > size - numBytes
        || targetOffset > targetLength - numElements) {
      throwOOBException();
    }
  }

  public void copyFromByteArray(int offset, byte[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 0);
    writeBytesFromArray(address + offset, source, BYTE_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromBooleanArray(int offset, boolean[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 0);
    writeBooleansFromArray(address + offset, source, BOOLEAN_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromCharArray(int offset, char[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 1);
    writeCharsFromArray(address + offset, source, CHAR_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromShortArray(int offset, short[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 1);
    writeShortsFromArray(address + offset, source, SHORT_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromIntArray(int offset, int[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 2);
    writeIntsFromArray(address + offset, source, INT_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromLongArray(int offset, long[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 3);
    writeLongsFromArray(address + offset, source, LONG_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromFloatArray(int offset, float[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 2);
    writeFloatsFromArray(address + offset, source, FLOAT_ARRAY_OFFSET + sourceOffset, numBytes);
  }

  public void copyFromDoubleArray(int offset, double[] source, int sourceOffset, int numBytes) {
    checkArrayCopy(offset, sourceOffset, source.length, numBytes, 3);
    writeDoublesFromArray(address + offset, source, DOUBLE_ARRAY_OFFSET + sourceOffset, numBytes);
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
    get(index, data, 0, length);
    return data;
  }

  public void getBytes(int index, byte[] dst, int dstIndex, int length) {
    if (dstIndex > dst.length - length) {
      throwOOBException();
    }
    if (index > size - length) {
      throwOOBException();
    }
    get(index, dst, dstIndex, length);
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
        int start = (int) address;
        duplicate.clear();
        ByteBufferUtil.position(duplicate, start + offset);
        duplicate.limit(start + offset + length);
        return duplicate.slice();
      } else {
        throw new IllegalStateException("Memory buffer does not own a ByteBuffer");
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
   * @return true if buffers equal or len zero, false otherwise
   */
  public boolean equalTo(MemoryBuffer buf2, int offset1, int offset2, int len) {
    if (len == 0) {
      return buf2 != null;
    }

    final long pos1 = address + offset1;
    final long pos2 = buf2.address + offset2;
    checkArgument(pos1 < addressLimit);
    checkArgument(pos2 < buf2.addressLimit);
    return unsafeEqualTo(this, heapMemory, pos1, buf2, buf2.heapMemory, pos2, len);
  }

  /**
   * Equals a memory buffer region with a byte array region.
   *
   * @param bytes Array to compare with
   * @param bytesOffset Offset of bytes to start comparing
   * @param offset Offset of this buffer to start comparing
   * @param len Length of the compared memory region
   * @return true if regions are equal or len zero, false otherwise
   */
  public boolean equalTo(byte[] bytes, int bytesOffset, int offset, int len) {
    checkArgument(bytes != null);
    checkArgument(len >= 0);
    checkArgument(bytesOffset >= 0 && bytesOffset <= bytes.length - len);
    checkArgument(offset >= 0 && offset <= size - len);
    if (len == 0) {
      return true;
    }

    final long pos = address + offset;
    return unsafeEqualTo(this, heapMemory, pos, this, bytes, BYTE_ARRAY_OFFSET + bytesOffset, len);
  }

  private static boolean unsafeEqualTo(
      MemoryBuffer left,
      Object leftBase,
      long leftOffset,
      MemoryBuffer right,
      Object rightBase,
      long rightOffset,
      int length) {
    int i = 0;
    if ((leftOffset % 8) == (rightOffset % 8)) {
      while ((leftOffset + i) % 8 != 0 && i < length) {
        if (left.rawByte(leftBase, leftOffset + i) != right.rawByte(rightBase, rightOffset + i)) {
          return false;
        }
        i += 1;
      }
    }
    if (UNALIGNED || (((leftOffset + i) % 8 == 0) && ((rightOffset + i) % 8 == 0))) {
      while (i <= length - 8) {
        if (left.rawLong(leftBase, leftOffset + i) != right.rawLong(rightBase, rightOffset + i)) {
          return false;
        }
        i += 8;
      }
    }
    while (i < length) {
      if (left.rawByte(leftBase, leftOffset + i) != right.rawByte(rightBase, rightOffset + i)) {
        return false;
      }
      i += 1;
    }
    return true;
  }

  private byte rawByte(Object base, long offset) {
    if (base == null) {
      return loadByte(offset);
    }
    return ((byte[]) base)[toIntIndex(offset)];
  }

  private long rawLong(Object base, long offset) {
    if (base == null) {
      return loadLong(offset);
    }
    return (long) BYTE_ARRAY_LONG.get((byte[]) base, toIntIndex(offset));
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

  // ------------------------------------------------------------------------
  // Memory Allocator Support
  // ------------------------------------------------------------------------

  /** Default memory allocator that uses the original heap-based allocation strategy. */
  private static final class DefaultMemoryAllocator implements MemoryAllocator {
    @Override
    public MemoryBuffer allocate(int initialSize) {
      return fromByteArray(new byte[initialSize]);
    }

    @Override
    public void grow(MemoryBuffer buffer, int newCapacity) {
      if (newCapacity <= buffer.size()) {
        return;
      }

      int newSize =
          newCapacity < BUFFER_GROW_STEP_THRESHOLD
              ? newCapacity << 1
              : (int) Math.min(newCapacity * 1.5d, Integer.MAX_VALUE - 8);

      byte[] data = new byte[newSize];
      buffer.get(0, data, 0, buffer.size());
      buffer.initHeapBuffer(data, 0, data.length);
    }
  }

  /**
   * Sets the global memory allocator. This affects all new MemoryBuffer allocations and growth
   * operations.
   *
   * @param allocator the new global allocator to use
   * @throws NullPointerException if allocator is null
   */
  public static void setGlobalAllocator(MemoryAllocator allocator) {
    if (allocator == null) {
      throw new NullPointerException("Memory allocator cannot be null");
    }
    globalAllocator = allocator;
  }

  /**
   * Gets the current global memory allocator.
   *
   * @return the current global allocator
   */
  public static MemoryAllocator getGlobalAllocator() {
    return globalAllocator;
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
      return new MemoryBuffer(buffer.position(), buffer.remaining(), buffer);
    } else if (buffer.hasArray()) {
      int offset = buffer.arrayOffset() + buffer.position();
      return new MemoryBuffer(buffer.array(), offset, buffer.remaining());
    } else {
      ByteBuffer duplicate = buffer.duplicate();
      byte[] bytes = new byte[duplicate.remaining()];
      duplicate.get(bytes);
      return fromByteArray(bytes);
    }
  }

  public static MemoryBuffer fromDirectByteBuffer(
      ByteBuffer buffer, int size, ForyStreamReader streamReader) {
    long offHeapAddress = buffer.position();
    return new MemoryBuffer(offHeapAddress, size, buffer, streamReader);
  }

  /**
   * Create a heap buffer of specified initial size. The buffer will grow automatically if not
   * enough.
   */
  public static MemoryBuffer newHeapBuffer(int initialSize) {
    return globalAllocator.allocate(initialSize);
  }
}
