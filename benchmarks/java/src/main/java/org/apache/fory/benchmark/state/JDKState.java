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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.apache.fory.benchmark.IntsSerializationSuite;
import org.apache.fory.benchmark.LongStringSerializationSuite;
import org.apache.fory.benchmark.LongsSerializationSuite;
import org.apache.fory.benchmark.StringSerializationSuite;
import org.apache.fory.benchmark.data.Data;
import org.apache.fory.io.ClassLoaderObjectInputStream;
import org.apache.fory.util.Preconditions;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

public class JDKState {
  @State(Scope.Thread)
  public abstract static class JDKBenchmarkState extends BenchmarkState {
    public ByteArrayOutputStream bos;
    public ByteArrayInputStream bis;

    @Setup(Level.Trial)
    public void setup() {
      bos = new ByteArrayOutputStream();
    }
  }

  public static class JDKUserTypeState extends JDKBenchmarkState {
    @Param() public ObjectType objectType;

    public Object object;
    public int serializedLength;

    @Override
    public void setup() {
      super.setup();
      object = ObjectType.createObject(objectType, references);
      bos.reset();
      serialize(bos, object);
      serializedLength = bos.size();
      Object o2 = deserialize(new ByteArrayInputStream(bos.toByteArray()));
      Preconditions.checkArgument(object.equals(o2));
      bis = new ByteArrayInputStream(bos.toByteArray());
    }
  }

  public static class DataState extends JDKBenchmarkState {
    public Data data = new Data();
  }

  public static class ReadIntsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new IntsSerializationSuite().jdk_serializeInts(this).toByteArray());
    }
  }

  public static class ReadLongsState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new LongsSerializationSuite().jdk_serializeLongs(this).toByteArray());
    }
  }

  public static class ReadStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new StringSerializationSuite().jdk_serializeStr(this).toByteArray());
    }
  }

  public static class ReadLongStrState extends DataState {
    @Override
    public void setup() {
      super.setup();
      bis =
          new ByteArrayInputStream(
              new LongStringSerializationSuite().jdk_serializeLongStr(this).toByteArray());
    }
  }

  public static void serialize(ByteArrayOutputStream bas, Object data) {
    bas.reset();
    try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bas)) {
      objectOutputStream.writeObject(data);
      objectOutputStream.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Object deserialize(ByteArrayInputStream bis) {
    bis.reset();
    try (ObjectInputStream objectInputStream =
        new ClassLoaderObjectInputStream(Thread.currentThread().getContextClassLoader(), bis)) {
      return objectInputStream.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
