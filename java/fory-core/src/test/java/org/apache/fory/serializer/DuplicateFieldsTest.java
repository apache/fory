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

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.annotations.Test;

public class DuplicateFieldsTest extends ForyTestBase {

  @ToString
  @EqualsAndHashCode
  public static class B {
    int f1;
    int f2;
  }

  @ToString(callSuper = true)
  @EqualsAndHashCode(callSuper = true)
  public static class C extends B {
    int f1;
  }

  public static class PrivateBase {
    @ForyField(id = 1)
    private int value;

    @ForyField(id = 2)
    private final long finalValue;

    public PrivateBase() {
      this(0, 0);
    }

    public PrivateBase(@ForyField(id = 1) int value, @ForyField(id = 2) long finalValue) {
      this.value = value;
      this.finalValue = finalValue;
    }

    int baseValue() {
      return value;
    }

    long baseFinalValue() {
      return finalValue;
    }
  }

  public static class PrivateChild extends PrivateBase {
    @ForyField(id = 3)
    private int value;

    @ForyField(id = 4)
    private final long finalValue;

    public PrivateChild() {
      this(0, 0, 0, 0);
    }

    public PrivateChild(
        @ForyField(id = 1) int baseValue,
        @ForyField(id = 2) long baseFinalValue,
        @ForyField(id = 3) int value,
        @ForyField(id = 4) long finalValue) {
      super(baseValue, baseFinalValue);
      this.value = value;
      this.finalValue = finalValue;
    }

    int childValue() {
      return value;
    }

    long childFinalValue() {
      return finalValue;
    }
  }

  @Test()
  public void testDuplicateFieldsNoCompatible() {
    C c = new C();
    ((B) c).f1 = 100;
    c.f1 = -100;
    assertEquals(((B) c).f1, 100);
    assertEquals(c.f1, -100);
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    {
      ObjectSerializer<C> serializer = new ObjectSerializer<>(fory.getTypeResolver(), C.class);
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      writeSerializer(fory, serializer, buffer, c);
      C newC = readSerializer(fory, serializer, buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      Serializer<C> serializer =
          Serializers.newSerializer(
              fory, C.class, CodecUtils.loadOrGenObjectCodecClass(C.class, fory));
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      writeSerializer(fory, serializer, buffer, c);
      C newC = readSerializer(fory, serializer, buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      // FallbackSerializer/CodegenSerializer will set itself to ClassResolver.
      Fory fory1 =
          Fory.builder()
              .withXlang(false)
              .withRefTracking(false)
              .withCodegen(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      C newC = (C) serDeCheckSerializer(fory1, c, "Codec");
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
  }

  @Test
  public void testPrivateDuplicateFieldsNoCompatible() {
    PrivateChild value = new PrivateChild(10, 20, -10, -20);
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    Serializer<PrivateChild> serializer =
        Serializers.newSerializer(
            fory,
            PrivateChild.class,
            CodecUtils.loadOrGenObjectCodecClass(PrivateChild.class, fory));
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    writeSerializer(fory, serializer, buffer, value);
    PrivateChild newValue = readSerializer(fory, serializer, buffer);
    assertEquals(newValue.baseValue(), value.baseValue());
    assertEquals(newValue.baseFinalValue(), value.baseFinalValue());
    assertEquals(newValue.childValue(), value.childValue());
    assertEquals(newValue.childFinalValue(), value.childFinalValue());
  }

  @Test
  public void testDuplicateFieldsCompatible() {
    C c = new C();
    ((B) c).f1 = 100;
    c.f1 = -100;
    assertEquals(((B) c).f1, 100);
    assertEquals(c.f1, -100);
    ForyBuilder builder =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .withCodegen(true)
            .withCompatible(true)
            .requireClassRegistration(false);
    Fory fory = builder.build();
    {
      CompatibleSerializer<C> serializer =
          new CompatibleSerializer<>(
              fory.getTypeResolver(), C.class, fory.getTypeResolver().getTypeDef(C.class, true));
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      writeSerializer(fory, serializer, buffer, c);
      C newC = readSerializer(fory, serializer, buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      // The compatible generated serializer is schema-pair owned and installed by TypeResolver.
      Fory fory1 = builder.build();
      C newC = serDeCheckSerializer(fory1, c, ".*Codec|.*Serializer");
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
  }
}
