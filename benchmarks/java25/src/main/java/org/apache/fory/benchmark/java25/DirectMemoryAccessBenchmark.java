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

package org.apache.fory.benchmark.java25;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
public class DirectMemoryAccessBenchmark {
  private static final int BUFFER_BYTES = 64 * 1024;
  private static final int INT_SLOTS = BUFFER_BYTES / Integer.BYTES;
  private static final int LONG_SLOTS = BUFFER_BYTES / Long.BYTES;
  private static final int INT_SLOT_MASK = INT_SLOTS - 1;
  private static final int LONG_SLOT_MASK = LONG_SLOTS - 1;
  private static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();
  private static final VarHandle INT_HANDLE =
      MethodHandles.byteBufferViewVarHandle(int[].class, NATIVE_ORDER);
  private static final VarHandle LONG_HANDLE =
      MethodHandles.byteBufferViewVarHandle(long[].class, NATIVE_ORDER);
  private static final ValueLayout.OfInt INT_LAYOUT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(NATIVE_ORDER);
  private static final ValueLayout.OfLong LONG_LAYOUT =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(NATIVE_ORDER);

  @State(Scope.Thread)
  public static class DirectState {
    ByteBuffer buffer;
    MemorySegment segment;
    int intValue;
    int intCursor;
    long longValue;
    int longCursor;

    @Setup
    public void setup() {
      buffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(NATIVE_ORDER);
      segment = MemorySegment.ofBuffer(buffer);
      intValue = 0x12345678;
      longValue = 0x123456789abcdef0L;
      for (int i = 0; i < INT_SLOTS; i++) {
        int intOffset = i << 2;
        int intPattern = intValue + i;
        segment.set(INT_LAYOUT, intOffset, intPattern);
      }
      for (int i = 0; i < LONG_SLOTS; i++) {
        int longOffset = i << 3;
        long longPattern = longValue + i;
        segment.set(LONG_LAYOUT, longOffset, longPattern);
      }
    }

    int nextIntOffset() {
      return (intCursor++ & INT_SLOT_MASK) << 2;
    }

    int nextIntValue() {
      intValue += 0x9e3779b9;
      return intValue;
    }

    int nextLongOffset() {
      return (longCursor++ & LONG_SLOT_MASK) << 3;
    }

    long nextLongValue() {
      longValue += 0x9e3779b97f4a7c15L;
      return longValue;
    }
  }

  @Benchmark
  public int memorySegmentPutInt(DirectState state) {
    int offset = state.nextIntOffset();
    int value = state.nextIntValue();
    state.segment.set(INT_LAYOUT, offset, value);
    return value;
  }

  @Benchmark
  public int varHandlePutInt(DirectState state) {
    int offset = state.nextIntOffset();
    int value = state.nextIntValue();
    INT_HANDLE.set(state.buffer, offset, value);
    return value;
  }

  @Benchmark
  public int memorySegmentGetInt(DirectState state) {
    return state.segment.get(INT_LAYOUT, state.nextIntOffset());
  }

  @Benchmark
  public int varHandleGetInt(DirectState state) {
    return (int) INT_HANDLE.get(state.buffer, state.nextIntOffset());
  }

  @Benchmark
  public long memorySegmentPutLong(DirectState state) {
    int offset = state.nextLongOffset();
    long value = state.nextLongValue();
    state.segment.set(LONG_LAYOUT, offset, value);
    return value;
  }

  @Benchmark
  public long varHandlePutLong(DirectState state) {
    int offset = state.nextLongOffset();
    long value = state.nextLongValue();
    LONG_HANDLE.set(state.buffer, offset, value);
    return value;
  }

  @Benchmark
  public long memorySegmentGetLong(DirectState state) {
    return state.segment.get(LONG_LAYOUT, state.nextLongOffset());
  }

  @Benchmark
  public long varHandleGetLong(DirectState state) {
    return (long) LONG_HANDLE.get(state.buffer, state.nextLongOffset());
  }
}
