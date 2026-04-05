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
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Config;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.PrimitiveSerializers;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
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

  private static final class ThreadSafeMyExtSerializer extends Serializer<MyExt> {
    private ThreadSafeMyExtSerializer(Config config, Class<MyExt> cls) {
      super(config, cls);
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

    @Override
    public boolean threadSafe() {
      return true;
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
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
    fory1.register(Color.class, 101);
    fory1.register(MyStruct.class, 102);
    fory1.register(MyExt.class, 103);
    fory1.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory1.register(MyWrapper.class, 104);
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(enableCodegen)
            .build();
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
  public void testSharedRegistrySharesThreadSafeSerializersOnly() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    ForyBuilder builder1 =
        Fory.builder().withLanguage(Language.XLANG).withCompatibleMode(CompatibleMode.COMPATIBLE);
    finishBuilder(builder1);
    Fory fory1 =
        new Fory(builder1, RegisterTest.class.getClassLoader(), sharedRegistry);
    ForyBuilder builder2 =
        Fory.builder().withLanguage(Language.XLANG).withCompatibleMode(CompatibleMode.COMPATIBLE);
    finishBuilder(builder2);
    Fory fory2 =
        new Fory(builder2, RegisterTest.class.getClassLoader(), sharedRegistry);

    TypeInfo classInfo1 = fory1.getTypeResolver().getTypeInfo(Class.class);
    TypeInfo classInfo2 = fory2.getTypeResolver().getTypeInfo(Class.class);
    Assert.assertNotSame(classInfo1, classInfo2);
    Assert.assertSame(
        sharedRegistry.getSerializer(Class.class, Serializers.ClassSerializer.class),
        classInfo1.getSerializer());
    Assert.assertSame(classInfo1.getSerializer(), classInfo2.getSerializer());

    fory1.register(MyExt.class, 103);
    fory1.registerSerializer(MyExt.class, ThreadSafeMyExtSerializer.class);
    fory2.register(MyExt.class, 103);
    fory2.registerSerializer(MyExt.class, ThreadSafeMyExtSerializer.class);

    TypeInfo typeInfo1 = fory1.getTypeResolver().getTypeInfo(MyExt.class);
    TypeInfo typeInfo2 = fory2.getTypeResolver().getTypeInfo(MyExt.class);
    Assert.assertNotSame(typeInfo1, typeInfo2);
    Assert.assertSame(typeInfo1.getSerializer(), typeInfo2.getSerializer());
    Assert.assertSame(
        sharedRegistry.getSerializer(MyExt.class, ThreadSafeMyExtSerializer.class),
        typeInfo1.getSerializer());
  }

  @Test
  public void testSharedRegistrySeparatesXlangNumericVariants() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    ForyBuilder builder1 =
        Fory.builder().withLanguage(Language.XLANG).withCompatibleMode(CompatibleMode.COMPATIBLE);
    finishBuilder(builder1);
    Fory fory1 = new Fory(builder1, RegisterTest.class.getClassLoader(), sharedRegistry);
    ForyBuilder builder2 =
        Fory.builder().withLanguage(Language.XLANG).withCompatibleMode(CompatibleMode.COMPATIBLE);
    finishBuilder(builder2);
    Fory fory2 = new Fory(builder2, RegisterTest.class.getClassLoader(), sharedRegistry);

    TypeInfo intInfo1 = fory1.getTypeResolver().getTypeInfo(Integer.class);
    TypeInfo intInfo2 = fory2.getTypeResolver().getTypeInfo(Integer.class);
    Assert.assertEquals(intInfo1.getTypeId(), Types.VARINT32);
    Assert.assertNotSame(intInfo1, intInfo2);
    Assert.assertSame(
        sharedRegistry.getSerializer(Integer.class, PrimitiveSerializers.IntSerializer.class),
        intInfo1.getSerializer());
    Assert.assertSame(intInfo1.getSerializer(), intInfo2.getSerializer());
    TypeInfo uintInfo1 = fory1.getTypeResolver().getTypeInfoByTypeId(Types.VAR_UINT32);
    TypeInfo uintInfo2 = fory2.getTypeResolver().getTypeInfoByTypeId(Types.VAR_UINT32);
    Assert.assertEquals(uintInfo1.getTypeId(), Types.VAR_UINT32);
    Assert.assertNotSame(intInfo1, uintInfo1);
    Assert.assertNotSame(uintInfo1, uintInfo2);
    Assert.assertSame(
        sharedRegistry.getSerializer(Integer.class, PrimitiveSerializers.VarUint32Serializer.class),
        uintInfo1.getSerializer());
    Assert.assertSame(uintInfo1.getSerializer(), uintInfo2.getSerializer());

    TypeInfo longInfo1 = fory1.getTypeResolver().getTypeInfo(Long.class);
    TypeInfo longInfo2 = fory2.getTypeResolver().getTypeInfo(Long.class);
    Assert.assertEquals(longInfo1.getTypeId(), Types.VARINT64);
    Assert.assertNotSame(longInfo1, longInfo2);
    Assert.assertSame(
        sharedRegistry.getSerializer(Long.class, PrimitiveSerializers.LongSerializer.class),
        longInfo1.getSerializer());
    Assert.assertSame(longInfo1.getSerializer(), longInfo2.getSerializer());
    TypeInfo uint64Info1 = fory1.getTypeResolver().getTypeInfoByTypeId(Types.VAR_UINT64);
    TypeInfo uint64Info2 = fory2.getTypeResolver().getTypeInfoByTypeId(Types.VAR_UINT64);
    Assert.assertNotSame(longInfo1, uint64Info1);
    Assert.assertNotSame(uint64Info1, uint64Info2);
    Assert.assertSame(
        sharedRegistry.getSerializer(Long.class, PrimitiveSerializers.VarUint64Serializer.class),
        uint64Info1.getSerializer());
    Assert.assertSame(uint64Info1.getSerializer(), uint64Info2.getSerializer());
  }

  @Test
  public void testXlangBuiltInAliasTypeIdsKeepCanonicalTypeInfo() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(false).build();
    TypeResolver typeResolver = fory.getTypeResolver();

    typeResolver.getSerializer(StringBuffer.class);
    typeResolver.getSerializer(AtomicInteger.class);

    Assert.assertSame(typeResolver.getTypeInfoByTypeId(Types.STRING).getCls(), String.class);
    Assert.assertSame(typeResolver.getTypeInfoByTypeId(Types.INT32).getCls(), Integer.class);

    Object stringValue = fory.deserialize(fory.serialize("str"));
    Assert.assertSame(stringValue.getClass(), String.class);
    Assert.assertEquals(stringValue, "str");

    Object intValue = fory.deserialize(fory.serialize(1));
    Assert.assertSame(intValue.getClass(), Integer.class);
    Assert.assertEquals(intValue, 1);
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
