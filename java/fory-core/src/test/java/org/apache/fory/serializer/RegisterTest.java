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

import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegisterTest extends ForyTestBase {

  @Test(dataProvider = "enableCodegen")
  public void testRegisterForCompatible(boolean enableCodegen) {
    A a = new A();
    a.setB(new B());
    ForyBuilder builder =
        Fory.builder().withXlang(false).withCodegen(enableCodegen).withCompatible(true);

    Fory fory1 = builder.build();
    fory1.register(A.class, (short) 1000);

    Fory fory2 = builder.build();
    fory2.register(A.class, (short) 1000);
    fory2.register(B.class, (short) 1001);

    A a1 = fory1.deserialize(fory2.serialize(a), A.class);
    Assert.assertNotNull(a1);
    Object b = a1.b;
    Assert.assertNotNull(b);
    Assert.assertEquals(b.getClass(), B.class);

    Fory fory3 = builder.requireClassRegistration(false).build();
    fory3.register(A.class, (short) 1000);

    A a2 = fory2.deserialize(fory3.serialize(a), A.class);
    Assert.assertNotNull(a2);
    Assert.assertEquals(a2.b.getClass(), B.class);
  }

  public static class A {
    private B b;

    public void setB(B b) {
      this.b = b;
    }
  }

  public static class B {}

  @Test
  public void testRegisterThenRegisterSerializer() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();

    fory.register(MyExt.class, 103);

    fory.registerSerializer(MyExt.class, MyExtSerializer.class);

    MyExt original = new MyExt();
    original.id = "test-123";

    byte[] bytes = fory.serialize(original);
    MyExt deserialized = (MyExt) fory.deserialize(bytes);

    Assert.assertNotNull(deserialized);
    Assert.assertEquals(deserialized.id, "test-123");
  }

  @Test
  public void testRegisterSerializerThenRegister() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();
    fory.register(MyExt.class, "test.pkg", "MyExt");
    fory.registerSerializer(MyExt.class, MyExtSerializer.class);

    MyExt original = new MyExt();
    original.id = "reverse-order-test";

    byte[] bytes = fory.serialize(original);
    MyExt deserialized = (MyExt) fory.deserialize(bytes);

    Assert.assertNotNull(deserialized);
    Assert.assertEquals(deserialized.id, "reverse-order-test");
  }

  @Test
  public void testMultipleRegisterSerializer() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();

    fory.register(MyExt.class, 104);

    fory.registerSerializer(MyExt.class, MyExtSerializer.class);
    fory.registerSerializer(MyExt.class, MyExtSerializer.class);

    MyExt original = new MyExt();
    original.id = "idempotent-test";

    byte[] bytes = fory.serialize(original);
    MyExt deserialized = (MyExt) fory.deserialize(bytes);

    Assert.assertNotNull(deserialized);
    Assert.assertEquals(deserialized.id, "idempotent-test");
  }

  @Test
  public void testRegisterExtSerializerWithSharedGeneratedCodec() {
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .withName("testRegisterExtSerializerWithSharedGeneratedCodec");

    Fory fory1 = builder.build();
    fory1.register(MyExt.class, 105);
    fory1.registerSerializer(MyExt.class, MyExtSerializer.class);
    ExtHolder holder1 = new ExtHolder();
    holder1.ext = new MyExt();
    holder1.ext.id = "first";

    ExtHolder copy1 = serDe(fory1, holder1);
    Assert.assertEquals(copy1.ext.id, "first");

    Fory fory2 = builder.build();
    fory2.register(MyExt.class, 105);
    fory2.registerSerializer(MyExt.class, AlternativeMyExtSerializer.class);
    ExtHolder holder2 = new ExtHolder();
    holder2.ext = new MyExt();
    holder2.ext.id = "second";

    ExtHolder copy2 = serDe(fory2, holder2);
    Assert.assertEquals(copy2.ext.id, "second");
    Assert.assertEquals(
        fory2.getTypeResolver().getSerializer(MyExt.class).getClass(),
        AlternativeMyExtSerializer.class);
  }

  public static class ExtHolder {
    public MyExt ext;
  }

  public static class MyExt {
    public String id;
  }

  public static class MyExtSerializer extends Serializer<MyExt> {
    public MyExtSerializer(TypeResolver typeResolver) {
      super(typeResolver.getConfig(), MyExt.class);
    }

    @Override
    public void write(WriteContext writeContext, MyExt value) {
      writeContext.writeString(value.id);
    }

    @Override
    public MyExt read(ReadContext readContext) {
      MyExt result = new MyExt();
      result.id = readContext.readString();
      return result;
    }
  }

  public static class AlternativeMyExtSerializer extends Serializer<MyExt> {
    public AlternativeMyExtSerializer(TypeResolver typeResolver) {
      super(typeResolver.getConfig(), MyExt.class);
    }

    @Override
    public void write(WriteContext writeContext, MyExt value) {
      writeContext.writeString(value.id);
    }

    @Override
    public MyExt read(ReadContext readContext) {
      MyExt result = new MyExt();
      result.id = readContext.readString();
      return result;
    }
  }
}
