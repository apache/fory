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

package org.apache.fory.benchmark.state;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.apache.fory.benchmark.IntsSerializationSuite;
import org.apache.fory.benchmark.LongStringSerializationSuite;
import org.apache.fory.benchmark.LongsSerializationSuite;
import org.apache.fory.benchmark.StringSerializationSuite;
import org.apache.fory.benchmark.data.Data;
import org.apache.fory.benchmark.data.Image;
import org.apache.fory.benchmark.data.Media;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.memory.ByteBufferUtil;
import org.apache.fory.util.Preconditions;
import org.nustaq.serialization.FSTConfiguration;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class FstState {

  public static void main(String[] args) {
    FstUserTypeState state = new FstUserTypeState();
    state.objectType = ObjectType.STRUCT;
    state.bufferType = BufferType.directBuffer;
    state.setup();
    FstBenchmarkState.serialize(null, state, state.object);
    FstBenchmarkState.deserialize(null, state);
  }

  @State(Scope.Thread)
  public abstract static class FstBenchmarkState extends BenchmarkState {
    FSTConfiguration fst;
    byte[] buffer;
    ByteBuffer directBuffer;
    int[] out = new int[1];

    @Setup(Level.Trial)
    public void setup() {
      setupFst();
      if (bufferType == BufferType.directBuffer) {
        directBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
      }
    }

    public void setupFst() {
      fst = FSTConfiguration.createDefaultConfiguration();
      fst.setPreferSpeed(true);
      fst.setShareReferences(references);
    }

    public static byte[] serialize(Blackhole blackhole, FstBenchmarkState state, Object value) {
      return serialize(
          state.fst, state.bufferType, value, state.out, state.directBuffer, blackhole);
    }

    public static byte[] serialize(
        FSTConfiguration fst,
        BufferType bufferType,
        Object value,
        int[] out,
        ByteBuffer directBuffer,
        Blackhole blackhole) {
      byte[] bytes = fst.asSharedByteArray(value, out);
      if (bufferType == BufferType.directBuffer) {
        ByteBufferUtil.clearBuffer(directBuffer);
        directBuffer.put(bytes, 0, out[0]);
      }
      if (blackhole != null) {
        blackhole.consume(directBuffer);
        blackhole.consume(bytes);
      }
      return bytes;
    }

    public static Object deserialize(Blackhole blackhole, FstBenchmarkState state) {
      return deserialize(
          state.fst, state.bufferType, state.buffer, state.out, state.directBuffer, blackhole);
    }

    public static Object deserialize(
        FSTConfiguration fst,
        BufferType bufferType,
        byte[] buffer,
        int[] out,
        ByteBuffer directBuffer,
        Blackhole blackhole) {
      if (bufferType == BufferType.directBuffer) {
        ByteBufferUtil.rewind(directBuffer);
        byte[] bytes = new byte[out[0]];
        directBuffer.get(bytes);
        Object newObj = fst.asObject(bytes);
        if (blackhole != null) {
          blackhole.consume(bytes);
          blackhole.consume(newObj);
        }
        return newObj;
      }
      return fst.asObject(buffer);
    }
  }

  public static class FstUserTypeState extends FstBenchmarkState {
    @Param() public ObjectType objectType;
    public Object object;

    @Override
    public void setup() {
      object = ObjectType.createObject(objectType, references);
      Thread.currentThread().setContextClassLoader(object.getClass().getClassLoader());
      super.setup();
      setupFst();
      fst.setClassLoader(object.getClass().getClassLoader());
      switch (objectType) {
        case SAMPLE:
        case STRUCT2:
        case STRUCT:
          if (registerClass) {
            fst.registerClass(object.getClass());
          }
          break;
        case MEDIA_CONTENT:
          if (registerClass) {
            fst.registerClass(Image.class);
            fst.registerClass(Image.Size.class);
            fst.registerClass(Media.class);
            fst.registerClass(Media.Player.class);
            fst.registerClass(ArrayList.class);
            fst.registerClass(MediaContent.class);
          }
          break;
        default:
          throw new IllegalStateException("Unexpected value: " + objectType);
      }
      buffer = serialize(null, this, object);
      Preconditions.checkArgument(object.equals(deserialize(null, this)));
    }
  }

  public static class DataState extends FstBenchmarkState {
    public Data data = new Data();
  }

  public static class ReadIntsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      buffer = new IntsSerializationSuite().fst_serializeInts(this, null);
    }
  }

  public static class ReadLongsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      buffer = new LongsSerializationSuite().fst_serializeLongs(this, null);
    }
  }

  public static class ReadStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      buffer = new StringSerializationSuite().fst_serializeStr(this, null);
    }
  }

  public static class ReadLongStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      buffer = new LongStringSerializationSuite().fst_serializeLongStr(this, null);
    }
  }
}
