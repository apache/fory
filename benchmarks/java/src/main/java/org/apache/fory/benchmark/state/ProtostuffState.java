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

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import org.apache.fory.benchmark.IntsSerializationSuite;
import org.apache.fory.benchmark.LongStringSerializationSuite;
import org.apache.fory.benchmark.LongsSerializationSuite;
import org.apache.fory.benchmark.StringSerializationSuite;
import org.apache.fory.benchmark.data.Data;
import org.apache.fory.util.Preconditions;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class ProtostuffState {

  public static byte[] serialize(Object o, Schema schema, LinkedBuffer buffer) {
    byte[] protoStuff = ProtostuffIOUtil.toByteArray(o, schema, buffer);
    buffer.clear();
    return protoStuff;
  }

  public static Object deserialize(Schema schema, byte[] bytes) {
    Object o = schema.newMessage();
    ProtostuffIOUtil.mergeFrom(bytes, o, schema);
    return o;
  }

  public static void main(String[] args) {
    ProtostuffUserTypeState userTypeState = new ProtostuffUserTypeState();
    userTypeState.objectType = ObjectType.STRUCT;
    userTypeState.setup();

    ReadStrState readStrState = new ReadStrState();
    readStrState.setup();
    ReadLongStrState readLongStrState = new ReadLongStrState();
    readLongStrState.setup();
    // ReadIntsState readIntsState = new ReadIntsState();
    // readIntsState.setup();
    // ReadLongsState readLongsState = new ReadLongsState();
    // readLongsState.setup();
  }

  @State(Scope.Thread)
  public abstract static class ProtostuffBenchmarkState extends BenchmarkState {
    @Param({"false"})
    public boolean references;

    public Schema schema;
    public LinkedBuffer buffer = LinkedBuffer.allocate(512);
    public byte[] protoStuff;

    @Setup(Level.Trial)
    public void setup() {}
  }

  public static class ProtostuffUserTypeState extends ProtostuffBenchmarkState {
    @Param() public ObjectType objectType;
    public Object object;

    @Override
    public void setup() {
      super.setup();
      object = ObjectType.createObject(objectType, references);
      schema = RuntimeSchema.getSchema(object.getClass());
      protoStuff = serialize(object, schema, buffer);
      Preconditions.checkArgument(object.equals(deserialize(schema, protoStuff)));
    }
  }

  public static class DataState extends ProtostuffBenchmarkState {
    public Data data = new Data();
  }

  public static class ReadIntsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      schema = RuntimeSchema.getSchema(data.ints.getClass());
      protoStuff = new IntsSerializationSuite().protostuff_serializeInts(this);
      Preconditions.checkArgument(
          ProtostuffState.deserialize(schema, protoStuff).equals(data.ints));
    }
  }

  public static class ReadLongsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      schema = RuntimeSchema.getSchema(data.longs.getClass());
      protoStuff = new LongsSerializationSuite().protostuff_serializeLongs(this);
      Preconditions.checkArgument(
          ProtostuffState.deserialize(schema, protoStuff).equals(data.longs));
    }
  }

  public static class ReadStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      schema = RuntimeSchema.getSchema(data.str.getClass());
      protoStuff = new StringSerializationSuite().protostuff_serializeStr(this);
      Preconditions.checkArgument(ProtostuffState.deserialize(schema, protoStuff).equals(data.str));
    }
  }

  public static class ReadLongStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      schema = RuntimeSchema.getSchema(data.longStr.getClass());
      protoStuff = new LongStringSerializationSuite().protostuff_serializeLongStr(this);
      Preconditions.checkArgument(
          ProtostuffState.deserialize(schema, protoStuff).equals(data.longStr));
    }
  }
}
