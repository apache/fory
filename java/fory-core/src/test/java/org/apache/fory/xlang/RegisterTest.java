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

package org.apache.fory.xlang;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Shareable;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegisterTest extends ForyTestBase {
  enum Color {
    Green,
    Red,
    Blue,
    White,
  }

  static class MyStruct {
    int id;

    public MyStruct(int id) {
      this.id = id;
    }
  }

  @Data
  static class MyExt {
    int id;

    public MyExt(int id) {
      this.id = id;
    }

    public MyExt() {}
  }

  private static class MyExtSerializer extends Serializer<MyExt> {

    public MyExtSerializer(TypeResolver typeResolver, Class<MyExt> cls) {
      super(typeResolver.getConfig(), cls);
    }

    @Override
    public void write(WriteContext writeContext, MyExt value) {
      writeContext.getBuffer().writeVarInt32(value.id);
    }

    @Override
    public MyExt read(ReadContext readContext) {
      MyExt obj = new MyExt();
      obj.id = readContext.getBuffer().readVarInt32();
      return obj;
    }
  }

  private static class ShareableMyExtSerializer extends MyExtSerializer implements Shareable {

    public ShareableMyExtSerializer(TypeResolver typeResolver, Class<MyExt> cls) {
      super(typeResolver, cls);
    }
  }

  @Data
  static class MyWrapper {
    Color color;
    MyExt my_ext;
    MyStruct my_struct;
  }

  @Data
  static class EmptyWrapper {}

  @Test(dataProvider = "enableCodegen")
  public void testJava(boolean enableCodegen) {
    Fory fory1 =
        Fory.builder().withXlang(true).withCompatible(true).withCodegen(enableCodegen).build();
    fory1.register(Color.class, 101);
    fory1.register(MyStruct.class, 102);
    fory1.register(MyExt.class, 103);
    fory1.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory1.register(MyWrapper.class, 104);
    Fory fory2 =
        Fory.builder().withXlang(true).withCompatible(true).withCodegen(enableCodegen).build();
    fory2.register(MyExt.class, 103);
    fory2.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory2.register(EmptyWrapper.class, 104);
    MyWrapper wrapper = new MyWrapper();
    wrapper.color = Color.White;
    MyStruct myStruct = new MyStruct(42);
    wrapper.my_ext = new MyExt(43);
    wrapper.my_struct = myStruct;
    byte[] serialize = fory1.serialize(wrapper);
    MemoryBuffer buffer2 = MemoryUtils.wrap(serialize);
    EmptyWrapper newWrapper = (EmptyWrapper) fory2.deserialize(buffer2);
    Assert.assertEquals(newWrapper, new EmptyWrapper());
  }

  @Test
  public void testXlangBuiltInAliasTypeIdsKeepCanonicalTypeInfo() {
    Fory fory = Fory.builder().withXlang(true).requireClassRegistration(false).build();
    TypeResolver typeResolver = fory.getTypeResolver();

    typeResolver.getSerializer(StringBuffer.class);
    typeResolver.getSerializer(AtomicInteger.class);

    Assert.assertSame(typeResolver.getTypeInfoByTypeId(Types.STRING).getType(), String.class);
    Assert.assertSame(typeResolver.getTypeInfoByTypeId(Types.INT32).getType(), Integer.class);

    Object stringValue = fory.deserialize(fory.serialize("str"));
    Assert.assertSame(stringValue.getClass(), String.class);
    Assert.assertEquals(stringValue, "str");

    Object intValue = fory.deserialize(fory.serialize(1));
    Assert.assertSame(intValue.getClass(), Integer.class);
    Assert.assertEquals(intValue, 1);
  }

  @Test
  public void testShareableSerializerOverrideStaysLocal() {
    ForyBuilder builder = Fory.builder().withXlang(true).requireClassRegistration(true);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, RegisterTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, RegisterTest.class.getClassLoader(), sharedRegistry);

    TypeResolver resolver1 = fory1.getTypeResolver();
    TypeResolver resolver2 = fory2.getTypeResolver();

    fory1.register(MyExt.class, 103);
    fory2.register(MyExt.class, 103);
    resolver1.registerSerializer(MyExt.class, ShareableMyExtSerializer.class);
    resolver2.registerSerializer(MyExt.class, ShareableMyExtSerializer.class);

    Assert.assertNotSame(
        resolver1.getSerializer(MyExt.class), resolver2.getSerializer(MyExt.class));
  }

  @Test
  public void testShareableInternalSerializerSharedAcrossRuntimes() {
    ForyBuilder builder = Fory.builder().withXlang(true).requireClassRegistration(true);
    finishBuilder(builder);
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory fory1 = new Fory(builder, RegisterTest.class.getClassLoader(), sharedRegistry);
    Fory fory2 = new Fory(builder, RegisterTest.class.getClassLoader(), sharedRegistry);

    TypeResolver resolver1 = fory1.getTypeResolver();
    TypeResolver resolver2 = fory2.getTypeResolver();

    resolver1.registerInternalSerializer(
        MyExt.class, new ShareableMyExtSerializer(resolver1, MyExt.class));
    resolver2.registerInternalSerializer(
        MyExt.class, new ShareableMyExtSerializer(resolver2, MyExt.class));

    Assert.assertSame(resolver1.getSerializer(MyExt.class), resolver2.getSerializer(MyExt.class));
  }

  private static void finishBuilder(ForyBuilder builder) {
    try {
      Method finish = ForyBuilder.class.getDeclaredMethod("finish");
      finish.setAccessible(true);
      finish.invoke(builder);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
