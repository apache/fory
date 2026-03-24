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

import java.io.IOException;
import org.apache.fory.benchmark.state.FlatBuffersState;
import org.apache.fory.benchmark.state.ForyState;
import org.apache.fory.benchmark.state.FstState;
import org.apache.fory.benchmark.state.HessionState;
import org.apache.fory.benchmark.state.JDKState;
import org.apache.fory.benchmark.state.JsonbState;
import org.apache.fory.benchmark.state.KryoState;
import org.apache.fory.benchmark.state.MsgpackState;
import org.apache.fory.benchmark.state.ObjectType;
import org.apache.fory.benchmark.state.ProtoBuffersState;
import org.apache.fory.benchmark.state.ProtostuffState;
import org.apache.fory.memory.ByteBufferUtil;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class UserTypeDeserializeSuite {

  @Benchmark
  public Object kryo_deserialize(KryoState.KryoUserTypeState state) {
    state.input.setPosition(0);
    state.input.setLimit(state.serializedLength);
    return state.kryo.readClassAndObject(state.input);
  }

  @Benchmark
  public Object kryo_deserialize_compatible(KryoState.KryoCompatibleState state) {
    state.input.setPosition(0);
    state.input.setLimit(state.serializedLength);
    return state.kryo.readClassAndObject(state.input);
  }

  @Benchmark
  public Object fory_deserialize(ForyState.ForyUserTypeState state) {
    state.buffer.readerIndex(0);
    return state.fory.deserialize(state.buffer);
  }

  @Benchmark
  public Object fory_deserialize_compatible(ForyState.ForyCompatibleState state) {
    state.buffer.readerIndex(0);
    return state.fory.deserialize(state.buffer);
  }

  @Benchmark
  public Object forymetashared_deserialize_compatible(ForyState.ForyMetaSharedState state) {
    state.buffer.readerIndex(0);
    state.fory.getSerializationContext().setMetaContext(state.readerMetaContext);
    return state.fory.deserialize(state.buffer);
  }

  @Benchmark
  public Object fst_deserialize(FstState.FstUserTypeState state, Blackhole bh) {
    return FstState.FstBenchmarkState.deserialize(bh, state);
  }

  @Benchmark
  public Object hession_deserialize(HessionState.HessionUserTypeState state) {
    state.bis.reset();
    state.input.reset();
    return HessionState.deserialize(state.input);
  }

  @Benchmark
  public Object hession_deserialize_compatible(HessionState.HessianCompatibleState state) {
    state.bis.reset();
    state.input.reset();
    return HessionState.deserialize(state.input);
  }

  @Benchmark
  public Object protostuff_deserialize(ProtostuffState.ProtostuffUserTypeState state) {
    return ProtostuffState.deserialize(state.schema, state.protoStuff);
  }

  @Benchmark
  public Object jdk_deserialize(JDKState.JDKUserTypeState state) {
    state.bis.reset();
    return JDKState.deserialize(state.bis);
  }

  @Benchmark
  public Object jsonb_deserialize(JsonbState.JsonbUserTypeState state, Blackhole bh) {
    return JsonbState.deserialize(bh, state);
  }

  @Benchmark
  public Object msgpack_deserialize(MsgpackState.MsgpackUserTypeState state, Blackhole bh)
      throws IOException {
    state.bis.reset();
    return MsgpackState.deserialize(bh, state);
  }

  @Benchmark
  public Object msgpack_jackson_deserialize(
      MsgpackState.MsgpackJacksonUserTypeState state, Blackhole bh) throws IOException {
    state.bis.reset();
    return MsgpackState.deserialize(bh, state);
  }

  @Benchmark
  public Object protobuffers_deserialize(ProtoBuffersState.ProtoBuffersUserTypeState state) {
    if (state.objectType == ObjectType.SAMPLE) {
      return ProtoBuffersState.deserializeSample(state.data);
    } else {
      return ProtoBuffersState.deserializeMediaContent(state.data);
    }
  }

  @Benchmark
  public Object flatbuffers_deserialize(FlatBuffersState.FlatBuffersUserTypeState state) {
    ByteBufferUtil.clearBuffer(state.deserializedData);
    if (state.objectType == ObjectType.SAMPLE) {
      return FlatBuffersState.deserializeSample(state.deserializedData);
    } else {
      return FlatBuffersState.deserializeMediaContent(state.deserializedData);
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*UserTypeDeserializeSuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv "
              + "-p objectType=MEDIA_CONTENT -p bufferType=array -p references=false";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
