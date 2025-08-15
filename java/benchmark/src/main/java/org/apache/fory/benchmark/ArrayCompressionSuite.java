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

import java.util.Arrays;
import java.util.Random;
import org.apache.fory.Fory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.ArrayCompressionUtils;
import org.apache.fory.util.PrimitiveArrayCompressionType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class ArrayCompressionSuite {
  @State(Scope.Thread)
  public static class StateClass {

    private Fory foryNormal;
    private Fory foryWithCompression;
    private MemoryBuffer buffer;
    private MemoryBuffer normalIntBuffer;
    private MemoryBuffer compressedIntBuffer;
    private MemoryBuffer normalLongBuffer;
    private MemoryBuffer compressedLongBuffer;

    @Param({"100", "1000", "10000", "100000", "1000000", "10000000"})
    public int arraySize;

    private int[] normalIntArray;
    private int[] compressibleIntArray;
    private long[] normalLongArray;
    private long[] compressibleLongArray;

    @Setup(Level.Trial)
    public void setup() {
      foryNormal = new ForyBuilder().build();
      foryWithCompression =
          new ForyBuilder().withIntArrayCompressed(true).withLongArrayCompressed(true).build();

      normalIntArray = createLargeValueArray(arraySize);
      compressibleIntArray = createByteRangeArray(arraySize);
      normalLongArray = createLargeLongValueArray(arraySize);
      compressibleLongArray = createIntRangeLongArray(arraySize);

      buffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      normalIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      compressedIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      normalLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      compressedLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);

      // Pre-serialize the arrays for clean deserialization benchmarks
      foryNormal.serialize(normalIntBuffer, normalIntArray);
      normalIntBuffer.readerIndex(0);
      foryWithCompression.serialize(compressedIntBuffer, compressibleIntArray);
      compressedIntBuffer.readerIndex(0);
      foryNormal.serialize(normalLongBuffer, normalLongArray);
      normalLongBuffer.readerIndex(0);
      foryWithCompression.serialize(compressedLongBuffer, compressibleLongArray);
      compressedLongBuffer.readerIndex(0);
    }
  }

  public static void main(String[] args) throws Exception {
    if (!ArrayCompressionUtils.isCompressionSupported()) {
      System.err.println("Array compression is not supported on this platform.");
      return;
    }
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*ArrayCompressionSuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    org.openjdk.jmh.Main.main(args);
  }

  @Benchmark
  public void serializeNormalIntArray(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.normalIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  @Benchmark
  public void serializeCompressedIntArray(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.compressibleIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  @Benchmark
  public void serializeNormalLongArray(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.normalLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  @Benchmark
  public void serializeCompressedLongArray(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.compressibleLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  @Benchmark
  public int[] deserializeNormalIntArray(StateClass state) {
    state.normalIntBuffer.readerIndex(0);
    return (int[]) state.foryNormal.deserialize(state.normalIntBuffer);
  }

  @Benchmark
  public int[] deserializeCompressedIntArray(StateClass state) {
    state.compressedIntBuffer.readerIndex(0);
    return (int[]) state.foryWithCompression.deserialize(state.compressedIntBuffer);
  }

  @Benchmark
  public long[] deserializeNormalLongArray(StateClass state) {
    state.normalLongBuffer.readerIndex(0);
    return (long[]) state.foryNormal.deserialize(state.normalLongBuffer);
  }

  @Benchmark
  public long[] deserializeCompressedLongArray(StateClass state) {
    state.compressedLongBuffer.readerIndex(0);
    return (long[]) state.foryWithCompression.deserialize(state.compressedLongBuffer);
  }

  @Benchmark
  public void determineIntArrayCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineIntCompressionType(state.compressibleIntArray);
    bh.consume(compressionType);
  }

  @Benchmark
  public void determineLongArrayCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineLongCompressionType(state.compressibleLongArray);
    bh.consume(compressionType);
  }

  @Benchmark
  public void determineIntCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineIntCompressionTypeScalar(state.compressibleIntArray);
    bh.consume(compressionType);
  }

  @Benchmark
  public void determineLongCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineLongCompressionTypeScalar(state.compressibleLongArray);
    bh.consume(compressionType);
  }

  // Helper methods to create test data
  private static final int[] createByteRangeArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(200) - 100; // -100 to 99 (byte range)
    }
    return array;
  }

  private static final int[] createSortedArray(int size) {
    int[] array = createByteRangeArray(size);
    Arrays.sort(array);
    return array;
  }

  private static final int[] createZeroArray(int size) {
    return new int[size]; // all zeros
  }

  private static final int[] createSequentialArray(int size) {
    int[] array = new int[size];
    for (int i = 0; i < size; i++) {
      array[i] = i % 256; // 0-255 repeating
    }
    return array;
  }

  private static final int[] createLargeValueArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt();
    }
    return array;
  }

  private static final long[] createIntRangeLongArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(); // int range values
    }
    return array;
  }

  private static final long[] createLargeLongValueArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextLong();
    }
    return array;
  }

  private static int determineIntCompressionTypeScalar(int[] array) {
    boolean canCompressToByte = true;
    boolean canCompressToShort = true;

    for (int i = 0; i < array.length && (canCompressToByte || canCompressToShort); i++) {
      int value = array[i];
      if (canCompressToByte && (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE)) {
        canCompressToByte = false;
      }
      if (value > Short.MAX_VALUE || value < Short.MIN_VALUE) {
        canCompressToShort = false;
      }
    }

    if (canCompressToByte) {
      return 1;
    }
    if (canCompressToShort) {
      return 2;
    }
    return 0;
  }

  private static int determineLongCompressionTypeScalar(long[] array) {
    boolean canCompressToInt = true;
    for (long value : array) {
      if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
        canCompressToInt = false;
        break;
      }
    }
    return canCompressToInt ? 3 : 0;
  }

  /** Compress int array to byte array. */
  public static byte[] compressToBytes(int[] array) {
    byte[] compressed = new byte[array.length];
    for (int i = 0; i < array.length; i++) {
      compressed[i] = (byte) array[i];
    }
    return compressed;
  }
}
