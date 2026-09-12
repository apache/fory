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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyModule;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SerializerFactoryTest {

  @Data
  public static class A implements KryoSerializable {
    private String f1;

    @Override
    public void write(Kryo kryo, Output output) {
      output.writeString(f1);
    }

    @Override
    public void read(Kryo kryo, Input input) {
      f1 = input.readString();
    }
  }

  private static class KryoSerializer extends Serializer {
    private Kryo kryo;
    private Output output;
    private ByteBufferInput input;

    public KryoSerializer(TypeResolver typeResolver, Class cls) {
      super(typeResolver.getConfig(), cls);
      kryo = new Kryo();
      kryo.setRegistrationRequired(false);
      output = new Output(64, Integer.MAX_VALUE);
      input = new ByteBufferInput();
    }

    @Override
    public void write(WriteContext writeContext, Object value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      output.reset();
      kryo.writeObject(output, value);
      buffer.writeBytes(output.getBuffer(), 0, output.position());
    }

    @Override
    public Object read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      input.setBuffer(buffer.sliceAsByteBuffer());
      Object o = kryo.readObject(input, type);
      buffer.readerIndex(buffer.readerIndex() + input.position());
      return o;
    }
  }

  @Test
  public void testBuilderFactory() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withSerializerFactory(SerializerFactoryTest::createSerializer)
            .withCompatible(false)
            .build();
    assertKryoSerializer(fory);
  }

  @Test
  public void testRuntimeFactory() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    fory.registerSerializerFactory(SerializerFactoryTest::createSerializer);
    assertKryoSerializer(fory);
  }

  @Test
  public void testModuleFactory() {
    AtomicInteger installs = new AtomicInteger();
    ForyModule module =
        fory -> {
          installs.incrementAndGet();
          fory.registerSerializerFactory(SerializerFactoryTest::createSerializer);
        };
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withModule(module)
            .withModule(module)
            .withCompatible(false)
            .build();
    assertKryoSerializer(fory);
    Assert.assertEquals(installs.get(), 1);
  }

  @Test
  public void testFactoryOrder() {
    List<String> calls = new ArrayList<>();
    ForyModule module =
        fory ->
            fory.registerSerializerFactory(
                (resolver, cls) -> {
                  calls.add("module");
                  return null;
                });
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withSerializerFactory(
                (resolver, cls) -> {
                  calls.add("custom");
                  return createSerializer(resolver, cls);
                })
            .withModule(module)
            .build();
    Assert.assertEquals(fory.getTypeResolver().getSerializerClass(A.class), KryoSerializer.class);
    Assert.assertEquals(calls, Collections.singletonList("custom"));
    calls.clear();
    A a = new A();
    a.f1 = "f1";
    Object a2 = fory.deserialize(fory.serialize(a));
    Assert.assertEquals(a, a2);
    Assert.assertEquals(calls, Collections.singletonList("custom"));
  }

  private static Serializer<?> createSerializer(TypeResolver resolver, Class<?> cls) {
    if (KryoSerializable.class.isAssignableFrom(cls)) {
      return new KryoSerializer(resolver, cls);
    }
    return null;
  }

  private static void assertKryoSerializer(Fory fory) {
    Assert.assertEquals(fory.getTypeResolver().getSerializerClass(A.class), KryoSerializer.class);
    A a = new A();
    a.f1 = "f1";

    Object a2 = fory.deserialize(fory.serialize(a));
    Assert.assertEquals(a, a2);
  }

  public interface Restriction {}

  public static class RestrictionValue implements Restriction {
    public String code;
  }

  public static class RestrictionHolder {
    public Restriction restriction;
  }

  public static class FactoryBean {
    public int value;
    public FactoryBean next;
  }

  public enum FactoryEnum {
    VALUE
  }

  private static class RestrictionSerializer extends Serializer<RestrictionValue> {
    RestrictionSerializer(TypeResolver resolver) {
      super(resolver.getConfig(), RestrictionValue.class);
    }

    @Override
    public void write(WriteContext ctx, RestrictionValue value) {
      ctx.writeRef(value.code);
    }

    @Override
    public RestrictionValue read(ReadContext ctx) {
      RestrictionValue value = new RestrictionValue();
      ctx.reference(value);
      value.code = (String) ctx.readRef();
      return value;
    }
  }

  @DataProvider
  public Object[][] factoryModes() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public Object[][] metaFactoryModes() {
    List<Object[]> modes = new ArrayList<>();
    for (boolean codegen : new boolean[] {false, true}) {
      for (boolean registered : new boolean[] {false, true}) {
        for (boolean metadataFirst : new boolean[] {false, true}) {
          modes.add(new Object[] {codegen, registered, metadataFirst});
        }
      }
    }
    return modes.toArray(new Object[0][]);
  }

  private static Fory metaShareFory(boolean codegen, SerializerFactory factory) {
    return Fory.builder()
        .withXlang(false)
        .withCompatible(true)
        .withScopedMetaShare(true)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .withCodegen(codegen)
        .withAsyncCompilation(false)
        .withSerializerFactory(factory)
        .build();
  }

  private static SerializerFactory restrictionFactory(AtomicInteger creations) {
    return (resolver, cls) -> {
      if (cls == RestrictionValue.class) {
        creations.incrementAndGet();
        return new RestrictionSerializer(resolver);
      }
      return null;
    };
  }

  @Test(dataProvider = "metaFactoryModes")
  public void testMetaShareFactory(boolean codegen, boolean registered, boolean metadataFirst) {
    AtomicInteger writerCreations = new AtomicInteger();
    AtomicInteger readerCreations = new AtomicInteger();
    Fory writer = metaShareFory(codegen, restrictionFactory(writerCreations));
    Fory reader = metaShareFory(codegen, restrictionFactory(readerCreations));
    if (registered) {
      writer.register(RestrictionValue.class);
      reader.register(RestrictionValue.class);
    }
    if (metadataFirst) {
      ClassResolver resolver = (ClassResolver) reader.getTypeResolver();
      resolver.getTypeIdForTypeDef(RestrictionValue.class);
      Assert.assertNull(resolver.getSerializer(RestrictionValue.class, false));
    }
    RestrictionValue value = new RestrictionValue();
    value.code = "A";
    if (metadataFirst) {
      RestrictionValue copy = reader.deserialize(writer.serialize(value), RestrictionValue.class);
      Assert.assertEquals(copy.code, value.code);
    }
    RestrictionHolder holder = new RestrictionHolder();
    holder.restriction = value;
    for (int i = 0; i < 2; i++) {
      RestrictionHolder copy =
          reader.deserialize(writer.serialize(holder), RestrictionHolder.class);
      Assert.assertEquals(((RestrictionValue) copy.restriction).code, "A");
    }
    RestrictionHolder restored =
        writer.deserialize(reader.serialize(holder), RestrictionHolder.class);
    Assert.assertEquals(((RestrictionValue) restored.restriction).code, "A");
    Assert.assertEquals(writerCreations.get(), 1);
    Assert.assertEquals(readerCreations.get(), 1);
  }

  @Test(dataProvider = "factoryModes")
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void testMetaShareEnumFactory(boolean codegen) {
    SerializerFactory factory =
        (resolver, cls) ->
            cls == FactoryEnum.class ? new EnumSerializer(resolver.getConfig(), (Class) cls) : null;
    Fory writer = metaShareFory(codegen, factory);
    Fory reader = metaShareFory(codegen, factory);
    Assert.assertSame(reader.deserialize(writer.serialize(FactoryEnum.VALUE)), FactoryEnum.VALUE);
  }

  @Test(dataProvider = "factoryModes")
  public void testMetaShareStructFactory(boolean codegen) {
    SerializerFactory factory =
        (resolver, cls) -> cls == FactoryBean.class ? new ObjectSerializer<>(resolver, cls) : null;
    Fory writer = metaShareFory(codegen, factory);
    Fory reader = metaShareFory(codegen, factory);
    FactoryBean value = new FactoryBean();
    value.value = 42;
    value.next = value;
    FactoryBean copy = (FactoryBean) reader.deserialize(writer.serialize(value));
    Assert.assertEquals(copy.value, value.value);
    Assert.assertSame(copy.next, copy);
    // Reading metadata must not leave a partially constructed serializer for a later write.
    FactoryBean restored = (FactoryBean) writer.deserialize(reader.serialize(copy));
    Assert.assertEquals(restored.value, value.value);
    Assert.assertSame(restored.next, restored);
  }

  @Test(dataProvider = "factoryModes")
  public void testMetaShareFactoryFailure(boolean codegen) {
    Fory writer = metaShareFory(codegen, restrictionFactory(new AtomicInteger()));
    AtomicInteger attempts = new AtomicInteger();
    Fory reader =
        metaShareFory(
            codegen,
            (resolver, cls) -> {
              if (cls != RestrictionValue.class) {
                return null;
              }
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Serializer construction failed");
              }
              return new RestrictionSerializer(resolver);
            });
    RestrictionValue value = new RestrictionValue();
    value.code = "A";
    byte[] bytes = writer.serialize(value);
    Assert.expectThrows(RuntimeException.class, () -> reader.deserialize(bytes));
    RestrictionValue copy = (RestrictionValue) reader.deserialize(bytes);
    Assert.assertEquals(copy.code, value.code);
    Assert.assertEquals(attempts.get(), 2);
  }
}
