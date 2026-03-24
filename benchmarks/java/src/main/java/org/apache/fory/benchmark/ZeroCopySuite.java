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

import static org.apache.fory.benchmark.state.JsonbState.getJsonbReaderConfig;
import static org.apache.fory.benchmark.state.JsonbState.getJsonbWriterConfig;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.unsafe.UnsafeByteBufferInput;
import com.esotericsoftware.kryo.unsafe.UnsafeByteBufferOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.fory.Fory;
import org.apache.fory.benchmark.data.ComparableByteArray;
import org.apache.fory.benchmark.data.SerializableByteBuffer;
import org.apache.fory.benchmark.state.BufferType;
import org.apache.fory.config.Language;
import org.apache.fory.memory.ByteBufferUtil;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.test.bean.ArraysData;
import org.apache.fory.util.Preconditions;
import org.nustaq.serialization.FSTConfiguration;
import org.openjdk.jmh.Main;
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
public class ZeroCopySuite {

  public static void main(String[] args) throws IOException {
    Object o = new ArraysData(200);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    Object o1 = fory.deserialize(fory.serialize(o));
    Preconditions.checkArgument(o.equals(o1));

    KryoState state = new KryoState();
    state.array_size = 200;
    state.bufferType = BufferType.directBuffer;
    state.dataType = DataType.BUFFER;
    state.setup();

    JsonBState jsonBState = new JsonBState();
    jsonBState.array_size = 200;
    jsonBState.bufferType = BufferType.directBuffer;
    jsonBState.dataType = DataType.PRIMITIVE_ARRAY;
    jsonBState.setup();

    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*ZeroCopy.*_deserialize$ -f 1 -wi 0 -i 1 -t 1 -w 1s -r 1s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }

  public enum DataType {
    PRIMITIVE_ARRAY,
    BUFFER
  }

  @State(Scope.Thread)
  public static class ZeroCopyBenchmarkState {
    @Param() public BufferType bufferType;

    @Param() public DataType dataType;

    @Param({"false"})
    public boolean references;

    @Param({"200", "1000", "5000"})
    public int array_size;

    Object data;

    @Setup(Level.Trial)
    public void setup() {
      switch (dataType) {
        case PRIMITIVE_ARRAY:
          data = new ArraysData(array_size);
          break;
        case BUFFER:
          boolean isFory = getClass().getName().contains("Fory");
          boolean isKryo = getClass().getName().contains("Kryo");
          boolean isJsonb = getClass().getName().contains("Jsonb");
          data =
              IntStream.range(0, 5)
                  .mapToObj(
                      i -> {
                        if (isFory) {
                          return ByteBuffer.allocate(array_size);
                        } else if (isKryo || isJsonb) {
                          // don't know why read/write kryo unsafe output not work, read data always
                          // be zero.
                          return new ComparableByteArray(new byte[array_size]);
                        } else {
                          return new SerializableByteBuffer(ByteBuffer.allocate(array_size));
                        }
                      })
                  .collect(Collectors.toList());
      }
    }
  }

  public static class ForyState extends ZeroCopyBenchmarkState {
    Fory fory;
    MemoryBuffer buffer;
    List<BufferObject> bufferObjects;
    List<MemoryBuffer> buffers;

    @Setup(Level.Trial)
    public void setup() {
      super.setup();
      bufferObjects = new ArrayList<>();
      fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withClassVersionCheck(false)
              .withRefTracking(references)
              .requireClassRegistration(false)
              .build();
      switch (bufferType) {
        case array:
          buffer = MemoryUtils.buffer(1024 * 512);
          break;
        case directBuffer:
          buffer = MemoryUtils.wrap(ByteBuffer.allocateDirect(1024 * 512));
          break;
      }
      fory.register(ArraysData.class);
      buffer.writerIndex(0);
      buffer.readerIndex(0);
      fory.serialize(
          buffer,
          data,
          o -> {
            bufferObjects.add(o);
            return false;
          });
      buffers = bufferObjects.stream().map(BufferObject::toBuffer).collect(Collectors.toList());
      Preconditions.checkArgument(data.equals(fory.deserialize(buffer, buffers)));
    }
  }

  @Benchmark
  public Object fory_serialize(ForyState state) {
    state.bufferObjects.clear();
    state.buffer.writerIndex(0);
    state.fory.serialize(
        state.buffer,
        state.data,
        o -> {
          state.bufferObjects.add(o);
          return false;
        });
    return state.buffer;
  }

  @Benchmark
  public Object fory_deserialize(ForyState state) {
    state.buffer.readerIndex(0);
    return state.fory.deserialize(state.buffer, state.buffers);
  }

  public static class KryoState extends ZeroCopyBenchmarkState {
    Kryo kryo;
    Output output;
    Input input;

    @Setup(Level.Trial)
    public void setup() {
      super.setup();
      kryo = new Kryo();
      switch (bufferType) {
        case array:
          output = new Output(1024 * 512);
          input = new Input(output.getBuffer());
          break;
        case directBuffer:
          output = new UnsafeByteBufferOutput(1024 * 512);
          input = new UnsafeByteBufferInput(((UnsafeByteBufferOutput) output).getByteBuffer());
          break;
      }
      kryo.setReferences(references);
      kryo.register(ArraysData.class);
      kryo.writeClassAndObject(output, data);
      Object newObj = kryo.readClassAndObject(input);
      Preconditions.checkArgument(data.equals(newObj));
    }
  }

  @Benchmark
  public Object kryo_serialize(KryoState state) {
    state.output.setPosition(0);
    state.kryo.writeClassAndObject(state.output, state.data);
    return state.output;
  }

  @Benchmark
  public Object kryo_deserialize(KryoState state) {
    state.input.setPosition(0);
    return state.kryo.readClassAndObject(state.input);
  }

  public static class FstState extends ZeroCopyBenchmarkState {
    FSTConfiguration fst;
    byte[] buffer;
    ByteBuffer directBuffer;
    int[] out = new int[1];

    @Setup(Level.Trial)
    public void setup() {
      super.setup();
      fst = FSTConfiguration.createDefaultConfiguration();
      fst.setPreferSpeed(true);
      fst.setShareReferences(references);
      fst.registerClass(ArraysData.class);
      if (bufferType == BufferType.directBuffer) {
        directBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
      }
      buffer = fstSerialize(null, this, data);
      Preconditions.checkArgument(data.equals(fst.asObject(fst.asByteArray(data))));
    }
  }

  public static byte[] fstSerialize(Blackhole blackhole, FstState state, Object value) {
    return org.apache.fory.benchmark.state.FstState.FstBenchmarkState.serialize(
        state.fst, state.bufferType, value, state.out, state.directBuffer, blackhole);
  }

  public static Object fstDeserialize(Blackhole blackhole, FstState state) {
    return org.apache.fory.benchmark.state.FstState.FstBenchmarkState.deserialize(
        state.fst, state.bufferType, state.buffer, state.out, state.directBuffer, blackhole);
  }

  @Benchmark
  public Object fst_serialize(FstState state, Blackhole bh) {
    return fstSerialize(bh, state, state.data);
  }

  @Benchmark
  public Object fst_deserialize(FstState state, Blackhole bh) {
    return fstDeserialize(bh, state);
  }

  public static class JsonBState extends ZeroCopyBenchmarkState {
    byte[] buffer;
    ByteBuffer directBuffer;
    public JSONWriter.Feature[] jsonbWriteFeatures;
    public JSONReader.Feature[] jsonbReaderFeatures;

    @Setup(Level.Trial)
    public void setup() {
      super.setup();
      jsonbWriteFeatures = getJsonbWriterConfig(references);
      jsonbReaderFeatures = getJsonbReaderConfig(references);
      buffer = JSONB.toBytes(data, jsonbWriteFeatures);
      if (bufferType == BufferType.directBuffer) {
        directBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
        directBuffer.put(buffer);
      }
      jsonbSerialize(this, null);
      Preconditions.checkArgument(data.equals(jsonbDeserialize(this, null)));
    }
  }

  // @Benchmark
  public Object jsonb_serialize(JsonBState state, Blackhole bh) {
    return jsonbSerialize(state, bh);
  }

  public static Object jsonbSerialize(JsonBState state, Blackhole bh) {
    byte[] bytes = JSONB.toBytes(state.data, state.jsonbWriteFeatures);
    if (state.bufferType == BufferType.directBuffer) {
      ByteBufferUtil.clearBuffer(state.directBuffer);
      state.directBuffer.put(bytes);
    }
    if (bh != null) {
      bh.consume(state.directBuffer);
      bh.consume(bytes);
    }
    return bytes;
  }

  // @Benchmark
  public Object jsonb_deserialize(JsonBState state, Blackhole bh) {
    return jsonbDeserialize(state, bh);
  }

  public static Object jsonbDeserialize(JsonBState state, Blackhole bh) {
    if (state.bufferType == BufferType.directBuffer) {
      ByteBufferUtil.rewind(state.directBuffer);
      byte[] bytes = new byte[state.buffer.length];
      state.directBuffer.get(bytes);
      Object newObj = JSONB.parseObject(bytes, Object.class, state.jsonbReaderFeatures);
      if (bh != null) {
        bh.consume(bytes);
        bh.consume(newObj);
      }
      return newObj;
    }
    return JSONB.parseObject(state.buffer, Object.class, state.jsonbReaderFeatures);
  }
}
