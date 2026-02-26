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
import org.apache.fory.Fory;
import org.apache.fory.benchmark.IntsSerializationSuite;
import org.apache.fory.benchmark.LongStringSerializationSuite;
import org.apache.fory.benchmark.LongsSerializationSuite;
import org.apache.fory.benchmark.StringSerializationSuite;
import org.apache.fory.benchmark.UserTypeDeserializeSuite;
import org.apache.fory.benchmark.UserTypeSerializeSuite;
import org.apache.fory.benchmark.data.Data;
import org.apache.fory.benchmark.data.Image;
import org.apache.fory.benchmark.data.Media;
import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Struct;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.util.Preconditions;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class ForyState {
  private static final Logger LOG = LoggerFactory.getLogger(ForyState.class);

  @State(Scope.Thread)
  public abstract static class ForyBenchmarkState extends BenchmarkState {
    public Fory fory;
    public MemoryBuffer buffer;

    @Setup(Level.Trial)
    public void setup() {
      fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withClassVersionCheck(false)
              .withRefTracking(references)
              .requireClassRegistration(false)
              .build();
      setupBuffer();
    }

    public void setupBuffer() {
      switch (bufferType) {
        case array:
          buffer = MemoryUtils.buffer(1024 * 512);
          break;
        case directBuffer:
          buffer = MemoryUtils.wrap(ByteBuffer.allocateDirect(1024 * 512));
          break;
      }
    }
  }

  public static void main(String[] args) {
    Object o = Struct.create(true);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    Object o1 = fory.deserialize(fory.serialize(o));
    Preconditions.checkArgument(o.equals(o1));
    ForyUserTypeState state = new ForyUserTypeState();
    state.objectType = ObjectType.MEDIA_CONTENT;
    state.bufferType = BufferType.directBuffer;
    state.setup();
    fory.serialize(new MediaContent().populate(false));
    fory.serialize(Struct.create(true));
    fory.serialize(Struct.create(false));
  }

  public static class ForyUserTypeState extends ForyBenchmarkState {
    @Param() public ObjectType objectType;

    public Object object;
    public int serializedLength;

    @Override
    public void setup() {
      setupBuffer();
      object = ObjectType.createObject(objectType, references);
      Thread.currentThread().setContextClassLoader(object.getClass().getClassLoader());
      ForyBuilder foryBuilder =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withClassVersionCheck(false)
              .withRefTracking(references)
              .requireClassRegistration(false);
      if (compatible()) {
        foryBuilder.withCompatibleMode(CompatibleMode.COMPATIBLE);
      }
      fory = foryBuilder.build();
      switch (objectType) {
        case SAMPLE:
        case STRUCT:
        case STRUCT2:
          if (registerClass) {
            fory.register(object.getClass());
          }
          break;
        case MEDIA_CONTENT:
          if (registerClass) {
            fory.register(Image.class);
            fory.register(Image.Size.class);
            fory.register(Media.class);
            fory.register(Media.Player.class);
            fory.register(ArrayList.class);
            fory.register(MediaContent.class);
            fory.register(Struct.class);
          }
          break;
      }

      fory.serialize(buffer, object);
      serializedLength = buffer.writerIndex();
      LOG.info(
          "======> Fory | {} | {} | {} | {} |",
          objectType,
          references,
          bufferType,
          serializedLength);
      buffer.writerIndex(0);
      Preconditions.checkArgument(object.equals(fory.deserialize(buffer)));
      buffer.readerIndex(0);
    }

    public boolean compatible() {
      return false;
    }
  }

  public static class ForyCompatibleState extends ForyUserTypeState {
    @Override
    public boolean compatible() {
      return true;
    }
  }

  public static class ForyMetaSharedState extends ForyUserTypeState {
    public MetaContext writerMetaContext = new MetaContext();
    public MetaContext readerMetaContext = new MetaContext();

    @Override
    public void setup() {
      setupBuffer();
      object = ObjectType.createObject(objectType, references);
      Thread.currentThread().setContextClassLoader(object.getClass().getClassLoader());
      fory =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withClassVersionCheck(false)
              .withRefTracking(references)
              .requireClassRegistration(false)
              .withMetaShare(true)
              .withCompatibleMode(CompatibleMode.COMPATIBLE)
              .build();
      // share meta first time.
      new UserTypeSerializeSuite().forymetashared_serialize_compatible(this);
      new UserTypeDeserializeSuite().forymetashared_deserialize_compatible(this);
      // meta shared.
      new UserTypeSerializeSuite().forymetashared_serialize_compatible(this);
      serializedLength = buffer.writerIndex();
      LOG.info(
          "======> Fory metashared | {} | {} | {} | {} |",
          objectType,
          references,
          bufferType,
          serializedLength);
      buffer.writerIndex(0);
      Object o = new UserTypeDeserializeSuite().forymetashared_deserialize_compatible(this);
      Preconditions.checkArgument(object.equals(o));
      buffer.readerIndex(0);
    }

    @Override
    public boolean compatible() {
      return true;
    }
  }

  public static class DataState extends ForyBenchmarkState {
    public Data data = new Data();
  }

  public static class ReadIntsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      new IntsSerializationSuite().fory_serializeInts(this);
    }
  }

  public static class ReadLongsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      new LongsSerializationSuite().fory_serializeLongs(this);
    }
  }

  public static class ReadStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      new StringSerializationSuite().fory_serializeStr(this);
    }
  }

  public static class ReadLongStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      new LongStringSerializationSuite().fory_serializeLongStr(this);
    }
  }
}
