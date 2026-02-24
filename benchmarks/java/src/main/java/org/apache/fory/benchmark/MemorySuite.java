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

package org.apache.fory.benchmark;

import java.nio.ByteBuffer;
import java.util.Random;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.Platform;
import org.apache.fory.util.StringUtils;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class MemorySuite {
  static int arrLen = 32;

  static {
    String strLengthStr = System.getenv("ARRAY_LENGTH");
    if (StringUtils.isNotBlank(strLengthStr)) {
      arrLen = Integer.parseInt(strLengthStr);
    }
  }

  @State(Scope.Thread)
  public static class MemoryState {

    public byte[] bytes;
    public short[] shorts;
    public char[] chars;
    public int[] ints;
    public long[] longs;
    public float[] floats;
    public double[] doubles;
    public MemoryBuffer heapBuffer;
    public MemoryBuffer directBuffer;

    @Setup(Level.Trial)
    public void setup() {
      bytes = new byte[arrLen];
      shorts = new short[arrLen];
      chars = new char[arrLen];
      ints = new int[arrLen];
      longs = new long[arrLen];
      floats = new float[arrLen];
      doubles = new double[arrLen];
      Random random = new Random(0);
      for (int i = 0; i < arrLen; i++) {
        bytes[i] = (byte) random.nextInt();
        shorts[i] = (short) random.nextInt();
        chars[i] = (char) random.nextInt();
        ints[i] = random.nextInt();
        longs[i] = random.nextLong();
        floats[i] = random.nextFloat();
        doubles[i] = random.nextDouble();
      }
      heapBuffer = MemoryBuffer.fromByteArray(new byte[arrLen * 20]);
      directBuffer = MemoryBuffer.fromByteBuffer(ByteBuffer.allocateDirect(arrLen * 20));
    }
  }

  // @Benchmark
  public Object bytesCopyAligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writeBytes(state.bytes);
    return state.heapBuffer;
  }

  // @Benchmark
  public Object bytesCopyUnaligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writeBoolean(false);
    state.heapBuffer.writeBytes(state.bytes);
    return state.heapBuffer;
  }

  // @Benchmark
  public Object charsCopyAligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writePrimitiveArrayWithSize(
        state.chars, Platform.CHAR_ARRAY_OFFSET, state.chars.length * 2);
    return state.heapBuffer;
  }

  // @Benchmark
  public Object charsCopyUnaligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writeBoolean(false);
    state.heapBuffer.writePrimitiveArrayWithSize(
        state.chars, Platform.CHAR_ARRAY_OFFSET, state.chars.length * 2);
    return state.heapBuffer;
  }

  // @Benchmark
  public Object longsCopyAligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writePrimitiveArrayWithSize(
        state.longs, Platform.LONG_ARRAY_OFFSET, state.longs.length * 8);
    return state.heapBuffer;
  }

  // @Benchmark
  public Object longsCopyUnaligned(MemoryState state) {
    state.heapBuffer.writerIndex(0);
    state.heapBuffer.writeBoolean(false);
    state.heapBuffer.writePrimitiveArrayWithSize(
        state.longs, Platform.LONG_ARRAY_OFFSET, state.longs.length * 8);
    return state.heapBuffer;
  }

  private static final byte[] target = new byte[arrLen * 8];

  @org.openjdk.jmh.annotations.Benchmark
  public Object systemArrayCopy(MemoryState state) {
    System.arraycopy(state.bytes, 0, target, 0, state.bytes.length);
    return target;
  }

  @org.openjdk.jmh.annotations.Benchmark
  public Object unsafeCopy(MemoryState state) {
    Platform.UNSAFE.copyMemory(
        state.bytes,
        Platform.BYTE_ARRAY_OFFSET,
        target,
        Platform.BYTE_ARRAY_OFFSET,
        state.bytes.length);
    return target;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object arrayAssignCopy(MemoryState state) {
    byte[] bytes = state.bytes;
    for (int i = 0; i < bytes.length; i++) {
      target[i] = bytes[i];
    }
    return target;
  }

  private static byte[] getByteArray(int arrLen) {
    Random random = new Random(0);
    byte[] bytes = new byte[arrLen];
    for (int i = 0; i < arrLen; i++) {
      bytes[i] = (byte) random.nextInt();
    }
    return bytes;
  }

  private static final byte[] array1 = getByteArray(256);
  private static final MemoryBuffer buffer1 = MemoryUtils.wrap(getByteArray(256));

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferUnsafeReadByte(MemoryState state) {
    int x = 0;
    MemoryBuffer buffer1 = MemorySuite.buffer1;
    buffer1.readerIndex(0);
    int size = buffer1.size();
    for (int i = 0; i < size; i++) {
      x += buffer1.readByte();
    }
    return x;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferArrayReadZByte(MemoryState state) {
    int x = 0;
    byte[] array1 = MemorySuite.array1;
    for (int i = 0; i < array1.length; i++) {
      x += array1[i];
    }
    return x;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferArrayReadShort(MemoryState state) {
    int x = 0;
    byte[] array1 = MemorySuite.array1;
    for (int i = 0; i < array1.length; i += 2) {
      x += (array1[i] << 8) | (array1[i + 1]);
    }
    return x;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferUnsafeReadShort(MemoryState state) {
    int x = 0;
    MemoryBuffer buffer1 = MemorySuite.buffer1;
    buffer1.readerIndex(0);
    int size = buffer1.size();
    for (int i = 0; i < size / 2; i++) {
      x += buffer1.readInt16();
    }
    return x;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferArrayReadInt(MemoryState state) {
    int x = 0;
    byte[] array1 = MemorySuite.array1;
    for (int i = 0; i < array1.length; i += 4) {
      x += (array1[i]) | (array1[i + 1] << 8) | (array1[i] << 16) | (array1[i + 1] << 24);
    }
    return x;
  }

  // @org.openjdk.jmh.annotations.Benchmark
  public Object bufferUnsafeReadInt(MemoryState state) {
    int x = 0;
    MemoryBuffer buffer1 = MemorySuite.buffer1;
    buffer1.readerIndex(0);
    int size = buffer1.size();
    for (int i = 0; i < size / 4; i++) {
      x += buffer1.readInt32();
    }
    return x;
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*MemorySuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
