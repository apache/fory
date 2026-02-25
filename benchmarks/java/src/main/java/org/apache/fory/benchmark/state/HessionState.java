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

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.fory.benchmark.IntsSerializationSuite;
import org.apache.fory.benchmark.LongStringSerializationSuite;
import org.apache.fory.benchmark.LongsSerializationSuite;
import org.apache.fory.benchmark.StringSerializationSuite;
import org.apache.fory.benchmark.data.Data;
import org.apache.fory.benchmark.data.Struct;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
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
public class HessionState {
  private static final Logger LOG = LoggerFactory.getLogger(HessionState.class);

  public static void main(String[] args) {
    HessionUserTypeState userTypeState = new HessionUserTypeState();
    userTypeState.objectType = ObjectType.STRUCT;
    userTypeState.setup();
  }

  public static void serialize(Hessian2Output out, Object o) {
    try {
      out.startMessage();
      out.writeObject(o);
      out.completeMessage();
      out.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Object deserialize(Hessian2Input input) {
    try {
      input.startMessage();
      Object o = input.readObject();
      input.completeMessage();
      return o;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @State(Scope.Thread)
  public abstract static class HessionBenchmarkState extends BenchmarkState {
    public ByteArrayOutputStream bos;
    public Hessian2Output out;
    public ByteArrayInputStream bis;
    public Hessian2Input input;

    @Setup(Level.Trial)
    public void setup() {
      bos = new ByteArrayOutputStream();
      out = new Hessian2Output(bos);
    }
  }

  public static class HessionUserTypeState extends HessionBenchmarkState {
    @Param() public ObjectType objectType;

    public Object object;
    public int serializedLength;

    @Override
    public void setup() {
      super.setup();
      object = ObjectType.createObject(objectType, references);
      bos.reset();
      out.reset();
      serialize(out, object);
      serializedLength = bos.size();
      LOG.info(
          "======> Hession | {} | {} | {} | {} |",
          objectType,
          references,
          bufferType,
          serializedLength);
      Object o2 =
          HessionState.deserialize(new Hessian2Input(new ByteArrayInputStream(bos.toByteArray())));
      Preconditions.checkArgument(object.equals(o2));
      bis = new ByteArrayInputStream(bos.toByteArray());
      input = new Hessian2Input(bis);
    }
  }

  public static class HessianCompatibleState extends HessionUserTypeState {
    @Override
    public void setup() {
      super.setup();
      if (objectType == ObjectType.STRUCT) {
        Thread.currentThread()
            .setContextClassLoader(Struct.createStructClass(110, false).getClassLoader());
      }
    }
  }

  public static class DataState extends HessionBenchmarkState {
    public Data data = new Data();
  }

  public static class ReadIntsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new IntsSerializationSuite().hession_serializeInts(this).toByteArray());
      input = new Hessian2Input(bis);
    }
  }

  public static class ReadLongsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new LongsSerializationSuite().hession_serializeLongs(this).toByteArray());
      input = new Hessian2Input(bis);
    }
  }

  public static class ReadStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new StringSerializationSuite().hession_serializeStr(this).toByteArray());
      input = new Hessian2Input(bis);
    }
  }

  public static class ReadLongStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new LongStringSerializationSuite().hession_serializeLongStr(this).toByteArray());
      input = new Hessian2Input(bis);
    }
  }
}
