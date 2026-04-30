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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.TestUtils;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.test.bean.Foo;
import org.testng.Assert;
import org.testng.annotations.Test;

// Most scoped meta share tests are located in CompatibleTest module.
public class ScopedMetaShareTest extends ForyTestBase {

  // Test registration doesn't skip write class meta
  @Test
  public void testRegister() throws Exception {
    Supplier<ForyBuilder> builder =
        () -> builder().withCodegen(true).withCompatible(true).withScopedMetaShare(true);
    Object foo = Foo.create();
    Class<?> fooClass = Foo.createCompatibleClass1();
    Object newFoo = fooClass.newInstance();
    TestUtils.unsafeCopy(foo, newFoo);
    Fory fory = builder.get().build();
    fory.register(Foo.class);
    Fory newFory = builder.get().withClassLoader(fooClass.getClassLoader()).build();
    newFory.register(fooClass);
    {
      byte[] foo1Bytes = newFory.serialize(newFoo);
      Object deserialized = fory.deserialize(foo1Bytes);
      Assert.assertEquals(deserialized.getClass(), Foo.class);
      Assert.assertTrue(TestUtils.objectCommonFieldsEquals(deserialized, newFoo));
      byte[] fooBytes = fory.serialize(deserialized);
      TestUtils.objectFieldsEquals(newFory.deserialize(fooBytes), newFoo, true);
    }
    {
      byte[] bytes1 = fory.serialize(foo);
      Object o1 = newFory.deserialize(bytes1);
      Assert.assertTrue(TestUtils.objectCommonFieldsEquals(o1, foo));
      Object o2 = fory.deserialize(newFory.serialize(o1));
      List<String> fields =
          Arrays.stream(fooClass.getDeclaredFields())
              .map(f -> f.getDeclaringClass().getSimpleName() + f.getName())
              .collect(Collectors.toList());
      TestUtils.objectFieldsEquals(new HashSet<>(fields), o2, foo, true);
    }
    {
      Object o3 = fory.deserialize(newFory.serialize(foo));
      TestUtils.objectFieldsEquals(o3, foo, true);
    }
  }
}
