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
import static org.testng.Assert.assertNotSame;

import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.test.bean.Cyclic;
import org.apache.fory.util.Preconditions;
import org.testng.Assert;
import org.testng.annotations.Test;

@SuppressWarnings("unchecked")
public class ObjectSerializerTest extends ForyTestBase {

  @Test
  public void testLocalClass() {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), Foo.class);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    Foo foo = new Foo();
    writeSerializer(fory, serializer, buffer, foo);
    Object obj = readSerializer(fory, serializer, buffer);
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testLocalClass(Fory fory) {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), Foo.class);
    Foo foo = new Foo();
    Object obj = withCopyContext(fory, context -> serializer.copy(context, foo));
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
    Assert.assertNotSame(foo, obj);
  }

  @Test
  public void testAnonymousClass() {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Foo foo =
        new Foo() {
          @Override
          public String foo(String s) {
            return "Anonymous " + s;
          }
        };
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), foo.getClass());
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    writeSerializer(fory, serializer, buffer, foo);
    Object obj = readSerializer(fory, serializer, buffer);
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testAnonymousClass(Fory fory) {
    String str = "str";
    class Foo {
      public String foo(String s) {
        return str + s;
      }
    }
    Foo foo =
        new Foo() {
          @Override
          public String foo(String s) {
            return "Anonymous " + s;
          }
        };
    ObjectSerializer serializer = new ObjectSerializer(fory.getTypeResolver(), foo.getClass());
    Object obj = withCopyContext(fory, context -> serializer.copy(context, foo));
    assertEquals(foo.foo("str"), ((Foo) obj).foo("str"));
    assertNotSame(foo, obj);
  }

  @Test
  public void testSerializeCircularReference() {
    Cyclic cyclic = Cyclic.create(true);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);

    ObjectSerializer<Cyclic> serializer =
        new ObjectSerializer<>(fory.getTypeResolver(), Cyclic.class);
    withWriteContext(
        fory,
        buffer,
        context -> {
          context.writeRefOrNull(cyclic);
          serializer.write(context, cyclic);
        });
    Cyclic cyclic1 =
        withReadContext(
            fory,
            buffer,
            context -> {
              byte tag = context.readRefOrNull();
              Preconditions.checkArgument(tag == Fory.REF_VALUE_FLAG);
              context.preserveRefId();
              return serializer.read(context);
            });
    fory.reset();
    assertEquals(cyclic1, cyclic);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCopyCircularReference(Fory fory) {
    Cyclic cyclic = Cyclic.create(true);
    ObjectSerializer<Cyclic> serializer =
        new ObjectSerializer<>(fory.getTypeResolver(), Cyclic.class);
    Cyclic cyclic1 = withCopyContext(fory, context -> serializer.copy(context, cyclic));
    assertEquals(cyclic1, cyclic);
    assertNotSame(cyclic1, cyclic);
  }

  @Data
  public static class A {
    Integer f1;
    Integer f2;
    Long f3;
    int f4;
    int f5;
    Integer f6;
    Long f7;
  }

  @Test
  public void testSerialization() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    ObjectSerializer<A> serializer = new ObjectSerializer<>(fory.getTypeResolver(), A.class);
    A a = new A();
    writeSerializer(fory, serializer, buffer, a);
    assertEquals(a, readSerializer(fory, serializer, buffer));
    assertEquals(a, withCopyContext(fory, context -> serializer.copy(context, a)));
  }
}
