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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.annotations.Test;

@SuppressWarnings("unchecked")
public class LambdaSerializerTest extends ForyTestBase {

  @Test(dataProvider = "javaFory")
  public void testLambda(Fory fory) {
    {
      BiFunction<Fory, Object, byte[]> function =
          (Serializable & BiFunction<Fory, Object, byte[]>) Fory::serialize;
      fory.deserialize(fory.serialize(function));
    }
    {
      Function<Integer, Integer> function =
          (Serializable & Function<Integer, Integer>) (x) -> x + x;
      Function<Integer, Integer> newFunc =
          (Function<Integer, Integer>) fory.deserialize(fory.serialize(function));
      assertEquals(newFunc.apply(10), Integer.valueOf(20));
      List<Function<Integer, Integer>> list = serDe(fory, Arrays.asList(function, function));
      assertSame(list.get(0), list.get(1));
      assertEquals(list.get(0).apply(20), Integer.valueOf(40));
    }
    assertSame(
        fory.getClassResolver().getSerializerClass(Class.class), Serializers.ClassSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testLambdaCopy(Fory fory) {
    {
      BiFunction<Fory, Object, byte[]> function =
          (Serializable & BiFunction<Fory, Object, byte[]>) Fory::serialize;
      fory.copy(function);
    }
    {
      Function<Integer, Integer> function =
          (Serializable & Function<Integer, Integer>) (x) -> x + x;
      Function<Integer, Integer> newFunc = fory.copy(function);
      assertEquals(newFunc.apply(10), Integer.valueOf(20));
      List<Function<Integer, Integer>> list = fory.copy(Arrays.asList(function, function));
      assertSame(list.get(0), list.get(1));
      assertEquals(list.get(0).apply(20), Integer.valueOf(40));
    }
    assertSame(
        fory.getClassResolver().getSerializerClass(Class.class), Serializers.ClassSerializer.class);
  }

  @Test
  public void testLambdaUnserializableMsg() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    Function<Object, String> function = String::valueOf;
    assertThrowsCause(UnsupportedOperationException.class, () -> fory.serialize(function));
    try {
      fory.serialize(function);
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("LambdaSerializerTest"));
    }
  }
}
