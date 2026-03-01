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

import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.Serializer;
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

    public MyExtSerializer(Fory fory, Class<MyExt> cls) {
      super(fory, cls);
    }

    @Override
    public void write(MemoryBuffer buffer, MyExt value) {
      buffer.writeVarInt32(value.id);
    }

    @Override
    public MyExt read(MemoryBuffer buffer) {
      MyExt obj = new MyExt();
      obj.id = buffer.readVarInt32();
      return obj;
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
  public void testCodegenCacheIsolation() {
    Fory foryA =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(true)
            .build();
    foryA.register(Color.class, 101);
    foryA.register(MyStruct.class, 102);
    foryA.register(MyExt.class, 103);
    foryA.registerSerializer(MyExt.class, MyExtSerializer.class);
    foryA.register(MyWrapper.class, 104);

    MyWrapper wrapperA = new MyWrapper();
    wrapperA.color = Color.Red;
    wrapperA.my_struct = new MyStruct(10);
    wrapperA.my_ext = new MyExt(20);

    Fory foryB =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(true)
            .build();
    foryB.register(Color.class, 101);
    foryB.register(MyStruct.class, 102);
    foryB.register(MyExt.class, 103);
    // NO MyExtSerializer registered
    foryB.register(MyWrapper.class, 104);

    MyWrapper wrapperB = new MyWrapper();
    wrapperB.color = Color.Blue;
    wrapperB.my_struct = new MyStruct(30);
    wrapperB.my_ext = new MyExt(40);

    try {
      byte[] serializedByB = foryB.serialize(wrapperB);
      MyWrapper deserializedB = (MyWrapper) foryB.deserialize(serializedByB);

      Assert.assertNotNull(deserializedB);
      Assert.assertEquals(deserializedB.my_ext.id, 40);

    } catch (Exception e) {
      Assert.fail("foryB tried to use foryA's codegen. Exception: " + e.getMessage());
    }
  }
}
