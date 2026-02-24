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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.benchmark.util.MsgpackUtil;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.util.Preconditions;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@Slf4j
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class MsgpackState {
  private static final Logger LOG = LoggerFactory.getLogger(MsgpackState.class);

  @State(Scope.Thread)
  public abstract static class MsgpackBenchmarkState extends BenchmarkState {
    byte[] buffer;
    public Class<?> clz;
    public ByteArrayOutputStream bos;
    public ByteArrayInputStream bis;

    @Setup(Level.Trial)
    public void setup() {}
  }

  public static class MsgpackUserTypeState extends MsgpackBenchmarkState {
    @Param() public ObjectType objectType;
    public Object object;

    @Override
    public void setup() {
      super.setup();
      object = ObjectType.createObject(objectType, references);
      bos = new ByteArrayOutputStream();
      if (objectType != ObjectType.MEDIA_CONTENT && objectType != ObjectType.SAMPLE) {
        throw new IllegalArgumentException("objectType must be MEDIA_CONTENT or SAMPLE");
      }
      try {
        buffer = serialize(null, this, object);
        bis = new ByteArrayInputStream(buffer);
        Preconditions.checkArgument(object.equals(deserialize(null, this)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static class MsgpackJacksonUserTypeState extends MsgpackBenchmarkState {
    @Param() public ObjectType objectType;
    public MessagePackFactory factory;
    public ObjectMapper mapper;
    public Object object;

    @Override
    public void setup() {
      super.setup();
      factory = new MessagePackFactory();
      mapper = new ObjectMapper(factory);
      bos = new ByteArrayOutputStream();
      object = ObjectType.createObject(objectType, references);
      clz = object.getClass();
      try {
        buffer = serialize(null, this, object);
        bis = new ByteArrayInputStream(buffer);
        Preconditions.checkArgument(object.equals(deserialize(null, this)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static byte[] serialize(Blackhole blackhole, MsgpackUserTypeState state, Object value)
      throws IOException {
    if (state.objectType == ObjectType.MEDIA_CONTENT) {
      byte[] bytes = MsgpackUtil.serialize((MediaContent) value, state.bos);
      if (blackhole != null) {
        blackhole.consume(bytes);
      }
      return bytes;
    } else if (state.objectType == ObjectType.SAMPLE) {
      byte[] bytes = MsgpackUtil.serializeSample((Sample) value, state.bos);
      if (blackhole != null) {
        blackhole.consume(bytes);
      }
      return bytes;
    }
    return null;
  }

  public static Object deserialize(Blackhole blackhole, MsgpackUserTypeState state)
      throws IOException {
    if (state.objectType == ObjectType.MEDIA_CONTENT) {
      MediaContent mc = MsgpackUtil.deserialize(state.bis);
      if (blackhole != null) {
        blackhole.consume(mc);
      }
      return mc;
    } else if (state.objectType == ObjectType.SAMPLE) {
      Sample sample = MsgpackUtil.deserializeSample(state.bis);
      if (blackhole != null) {
        blackhole.consume(sample);
      }
      return sample;
    }
    return null;
  }

  public static byte[] serialize(
      Blackhole blackhole, MsgpackJacksonUserTypeState state, Object value) throws IOException {
    state.mapper.writeValue(state.bos, value);
    byte[] bytes = state.bos.toByteArray();
    if (blackhole != null) {
      blackhole.consume(bytes);
    }
    return bytes;
  }

  public static Object deserialize(Blackhole blackhole, MsgpackJacksonUserTypeState state)
      throws IOException {
    Object newObj = state.mapper.readValue(state.bis, state.clz);
    if (blackhole != null) {
      blackhole.consume(newObj);
    }
    return newObj;
  }

  public static void main(String[] args) {
    MsgpackJacksonUserTypeState s1 = new MsgpackJacksonUserTypeState();
    s1.objectType = ObjectType.SAMPLE;
    s1.references = false;
    s1.setup();

    MsgpackUserTypeState s2 = new MsgpackUserTypeState();
    s2.objectType = ObjectType.SAMPLE;
    s2.references = false;
    s2.setup();
  }
}
