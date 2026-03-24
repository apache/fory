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

import java.io.ByteArrayOutputStream;
import org.apache.fory.benchmark.state.ForyState;
import org.apache.fory.benchmark.state.FstState;
import org.apache.fory.benchmark.state.HessionState;
import org.apache.fory.benchmark.state.JDKState;
import org.apache.fory.benchmark.state.KryoState;
import org.apache.fory.benchmark.state.ProtostuffState;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class LongsSerializationSuite {
  @Benchmark
  public Object kryo_serializeLongs(KryoState.DataState state) {
    state.output.setPosition(0);
    state.kryo.writeClassAndObject(state.output, state.data.longs);
    return state.output;
  }

  @Benchmark
  public Object fory_serializeLongs(ForyState.DataState state) {
    state.buffer.writerIndex(0);
    state.fory.serialize(state.buffer, state.data.longs);
    return state.buffer;
  }

  @Benchmark
  public byte[] fst_serializeLongs(FstState.DataState state, Blackhole bh) {
    return FstState.FstBenchmarkState.serialize(bh, state, state.data.longs);
  }

  @Benchmark
  public ByteArrayOutputStream hession_serializeLongs(HessionState.DataState state) {
    state.bos.reset();
    state.out.reset();
    HessionState.serialize(state.out, state.data.longs);
    return state.bos;
  }

  // @Benchmark
  public byte[] protostuff_serializeLongs(ProtostuffState.DataState state) {
    return ProtostuffState.serialize(state.data.longs, state.schema, state.buffer);
  }

  @Benchmark
  public ByteArrayOutputStream jdk_serializeLongs(JDKState.DataState state) {
    state.bos.reset();
    JDKState.serialize(state.bos, state.data.longs);
    return state.bos;
  }
}
