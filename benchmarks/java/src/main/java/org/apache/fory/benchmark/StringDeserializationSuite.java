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
public class StringDeserializationSuite {
  @Benchmark
  public Object kryo_deserializeStr(KryoState.ReadStrState state) {
    state.input.setPosition(0);
    return state.kryo.readClassAndObject(state.input);
  }

  @Benchmark
  public Object fory_deserializeStr(ForyState.ReadStrState state) {
    state.buffer.readerIndex(0);
    return state.fory.deserialize(state.buffer);
  }

  @Benchmark
  public Object fst_deserializeStr(FstState.ReadStrState state, Blackhole bh) {
    return FstState.FstBenchmarkState.deserialize(bh, state);
  }

  @Benchmark
  public Object hession_deserializeStr(HessionState.ReadStrState state) {
    state.bis.reset();
    state.input.reset();
    return HessionState.deserialize(state.input);
  }

  @Benchmark
  public Object protostuff_deserializeStr(ProtostuffState.ReadStrState state) {
    return ProtostuffState.deserialize(state.schema, state.protoStuff);
  }

  @Benchmark
  public Object jdk_deserializeStr(JDKState.ReadStrState state) {
    state.bis.reset();
    return JDKState.deserialize(state.bis);
  }
}
