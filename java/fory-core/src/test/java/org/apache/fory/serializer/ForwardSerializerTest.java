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

package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.test.bean.BeanA;
import org.testng.annotations.Test;

public class ForwardSerializerTest {

  private ForwardSerializer createSerializer(String serializerType) {
    switch (serializerType) {
      case "Fory":
        return new ForwardSerializer(
            new ForwardSerializer.DefaultForyProxy() {
              @Override
              protected Fory newForySerializer() {
                Fory fory = super.newForySerializer();
                // We can register custom serializers here.
                System.out.printf("Created serializer %s, start to do init staff.\n", fory);
                return fory;
              }
            });
      case "Kryo":
        return new ForwardSerializer(
            new ForwardSerializer.SerializerProxy<Kryo>() {
              private final ThreadLocal<Output> outputLocal =
                  ThreadLocal.withInitial(() -> new Output(32, Integer.MAX_VALUE));

              @Override
              protected Kryo newSerializer() {
                // We can register custom serializers here.
                Kryo kryo = new Kryo();
                kryo.setRegistrationRequired(false);
                return kryo;
              }

              @Override
              protected void register(Kryo serializer, Class clz) {
                serializer.register(clz);
              }

              @Override
              protected byte[] serialize(Kryo serializer, Object obj) {
                Output output = outputLocal.get();
                output.reset();
                serializer.writeClassAndObject(output, obj);
                return output.toBytes();
              }

              @Override
              protected Object deserialize(Kryo serializer, byte[] bytes) {
                Input input = new Input(bytes);
                return serializer.readClassAndObject(input);
              }

              @Override
              protected Object copy(Kryo serializer, Object obj) {
                return serializer.copy(obj);
              }
            });
      default:
        throw new UnsupportedOperationException("Unsupported serializer type " + serializerType);
    }
  }

  @Test
  public void testSerialize() {
    BeanA beanA = BeanA.createBeanA(3);
    ForwardSerializer kryo = createSerializer("Kryo");
    assertEquals(kryo.deserialize(kryo.serialize(beanA)), beanA);
    ForwardSerializer fory = createSerializer("Fory");
    assertEquals(fory.deserialize(fory.serialize(beanA)), beanA);
  }

  @Test
  public void testCopy() {
    BeanA beanA = BeanA.createBeanA(3);
    ForwardSerializer kryo = createSerializer("Kryo");
    assertEquals(kryo.copy(beanA), beanA);
    ForwardSerializer fory = createSerializer("Fory");
    assertEquals(fory.copy(beanA), beanA);
  }

  private volatile boolean hasException;

  @Test
  public void testConcurrent() throws InterruptedException {
    BeanA beanA = BeanA.createBeanA(3);
    for (String type : new String[] {"Kryo", "Fory"}) {
      ForwardSerializer serializer = createSerializer(type);
      serializer.register(BeanA.class);
      assertEquals(serializer.deserialize(serializer.serialize(beanA)), beanA);
      ExecutorService executorService = Executors.newFixedThreadPool(12);
      for (int i = 0; i < 1000; i++) {
        executorService.execute(
            () -> {
              for (int j = 0; j < 10; j++) {
                try {
                  assertEquals(serializer.deserialize(serializer.serialize(beanA)), beanA);
                } catch (Exception e) {
                  hasException = true;
                  e.printStackTrace();
                  throw e;
                }
              }
            });
      }
      executorService.shutdown();
      assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
      assertFalse(hasException);
    }
  }

  @Test
  public void testConcurrentCopy() throws InterruptedException {
    BeanA beanA = BeanA.createBeanA(3);
    for (String type : new String[] {"Kryo", "Fory"}) {
      ForwardSerializer serializer = createSerializer(type);
      serializer.register(BeanA.class);
      assertEquals(serializer.copy(beanA), beanA);
      ExecutorService executorService = Executors.newFixedThreadPool(12);
      for (int i = 0; i < 1000; i++) {
        executorService.execute(
            () -> {
              for (int j = 0; j < 10; j++) {
                try {
                  assertEquals(serializer.copy(beanA), beanA);
                } catch (Exception e) {
                  hasException = true;
                  e.printStackTrace();
                  throw e;
                }
              }
            });
      }
      executorService.shutdown();
      assertTrue(executorService.awaitTermination(30, TimeUnit.SECONDS));
      assertFalse(hasException);
    }
  }
}
