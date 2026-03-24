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

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.benchmark.data.CustomJDKSerialization;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.ByteBufferUtil;
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
import org.openjdk.jmh.infra.Blackhole;

@Warmup(iterations = 3)
@Measurement(iterations = 3)
@Fork(value = 0)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class JsonbState {
  private static final Logger LOG = LoggerFactory.getLogger(JsonbState.class);

  @State(Scope.Thread)
  public abstract static class JsonbBenchmarkState extends BenchmarkState {
    public JSONWriter.Feature[] jsonbWriteFeatures;
    public JSONReader.Feature[] jsonbReaderFeatures;
    byte[] buffer;
    ByteBuffer directBuffer;

    @Setup(Level.Trial)
    public void setup() {
      if (bufferType == BufferType.directBuffer) {
        directBuffer = ByteBuffer.allocateDirect(10 * 1024 * 1024);
      }
      jsonbWriteFeatures = getJsonbWriterConfig(references);
      jsonbReaderFeatures = getJsonbReaderConfig(references);
    }
  }

  public static class JsonbUserTypeState extends JsonbBenchmarkState {
    @Param() public ObjectType objectType;
    public Object object;

    @Override
    public void setup() {
      super.setup();
      object = ObjectType.createObject(objectType, references);
      Thread.currentThread().setContextClassLoader(object.getClass().getClassLoader());
      JSONFactory.setContextObjectReaderProvider(new ObjectReaderProvider());
      buffer = serialize(null, this, object);
      LOG.info(
          "======> Jsonb | {} | {} | {} | {} |", objectType, references, bufferType, buffer.length);
      if (bufferType == BufferType.directBuffer) {
        directBuffer.put(buffer);
        ByteBufferUtil.clearBuffer(directBuffer);
      }
      Preconditions.checkArgument(object.equals(deserialize(null, this)));
    }
  }

  public static JSONWriter.Feature[] getJsonbWriterConfig(boolean refTracking) {
    List<JSONWriter.Feature> features =
        new ArrayList<>(
            Arrays.asList(
                JSONWriter.Feature.WriteClassName,
                JSONWriter.Feature.FieldBased,
                JSONWriter.Feature.WriteNulls,
                JSONWriter.Feature.NotWriteHashMapArrayListClassName,
                JSONWriter.Feature.WriteNameAsSymbol,
                JSONWriter.Feature.BeanToArray));
    if (refTracking) {
      features.add(JSONWriter.Feature.ReferenceDetection);
    }
    return features.toArray(new JSONWriter.Feature[0]);
  }

  public static JSONReader.Feature[] getJsonbReaderConfig(boolean refTracking) {
    List<JSONReader.Feature> features =
        new ArrayList<>(
            Arrays.asList(
                JSONReader.Feature.SupportAutoType,
                JSONReader.Feature.UseDefaultConstructorAsPossible,
                JSONReader.Feature.UseNativeObject,
                JSONReader.Feature.FieldBased,
                JSONReader.Feature.SupportArrayToBean));
    return features.toArray(new JSONReader.Feature[0]);
  }

  public static byte[] serialize(Blackhole blackhole, JsonbBenchmarkState state, Object value) {
    byte[] bytes = JSONB.toBytes(value, state.jsonbWriteFeatures);
    if (state.bufferType == BufferType.directBuffer) {
      ByteBufferUtil.clearBuffer(state.directBuffer);
      state.directBuffer.put(bytes);
    }
    if (blackhole != null) {
      blackhole.consume(state.directBuffer);
      blackhole.consume(bytes);
    }
    return bytes;
  }

  public static Object deserialize(Blackhole blackhole, JsonbBenchmarkState state) {
    if (state.bufferType == BufferType.directBuffer) {
      ByteBufferUtil.rewind(state.directBuffer);
      byte[] bytes = new byte[state.buffer.length];
      state.directBuffer.get(bytes);
      Object newObj = JSONB.parseObject(bytes, Object.class, state.jsonbReaderFeatures);
      if (blackhole != null) {
        blackhole.consume(bytes);
        blackhole.consume(newObj);
      }
      return newObj;
    }
    return JSONB.parseObject(state.buffer, Object.class, state.jsonbReaderFeatures);
  }

  public static void testLambda() {
    Function<String, String> f = (Function<String, String> & Serializable) String::toLowerCase;
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    fory.deserialize(fory.serialize(f));
    byte[] data = JSONB.toBytes(f, getJsonbWriterConfig(true));
    JSONB.parseObject(data, Object.class, getJsonbReaderConfig(true));
  }

  private static class TestInvocationHandler implements InvocationHandler {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      System.out.printf("invoke %s\n", method);
      return method.invoke(args);
    }
  }

  public static void testProxy() {
    Function function =
        (Function)
            Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[] {Function.class},
                new TestInvocationHandler());
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    fory.deserialize(fory.serialize(function));
    byte[] data1 = JSONB.toBytes(function, getJsonbWriterConfig(true));
    System.out.println(JSONB.parseObject(data1, Object.class, getJsonbReaderConfig(true)));
  }

  public static void testWriteObject() {
    CustomJDKSerialization obj = new CustomJDKSerialization();
    byte[] data1 = JSONB.toBytes(obj, getJsonbWriterConfig(true));
    CustomJDKSerialization newObj =
        (CustomJDKSerialization) JSONB.parseObject(data1, Object.class, getJsonbReaderConfig(true));
    System.out.println(newObj.age);
  }

  static class A {
    // String f1;
  }

  static class B extends A {
    // String f1;
  }

  static class C {
    A f1;
    B f2;
  }

  public static void testSubclass() {
    C c = new C();
    c.f1 = new A();
    c.f2 = new B();
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    System.out.println(fory.serialize(c).length);
    System.out.println(fory.serialize(c).length);
    byte[] bytes = JSONB.toBytes(c, getJsonbWriterConfig(true));
    System.out.println(bytes.length);
    C newObj = (C) JSONB.parseObject(bytes, Object.class, getJsonbReaderConfig(true));
    System.out.println(newObj.f1);
  }

  public static void main(String[] args) {
    // testSubclass();
    // testLambda();
    // testProxy();
    // testWriteObject();
    JsonbUserTypeState state = new JsonbUserTypeState();
    state.objectType = ObjectType.MEDIA_CONTENT;
    state.bufferType = BufferType.array;
    state.setup();
    state.bufferType = BufferType.directBuffer;
    state.setup();

    ;
    JSONObject json = new JSONObject();
    json.put("k", 1);
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    byte[] bytes = fory.serialize(json);
    System.out.println(fory.deserialize(bytes));
  }
}
