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
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
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

  @Test()
  public void testDuplicateFieldsNoCompatible() {
    C c = new C();
    ((B) c).f1 = 100;
    c.f1 = -100;
    assertEquals(((B) c).f1, 100);
    assertEquals(c.f1, -100);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .build();
    {
      ObjectSerializer<C> serializer = new ObjectSerializer<>(fory, C.class);
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      serializer.write(buffer, c);
      C newC = serializer.read(buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      Serializer<C> serializer =
          Serializers.newSerializer(
              fory, C.class, CodecUtils.loadOrGenObjectCodecClass(C.class, fory));
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      serializer.write(buffer, c);
      C newC = serializer.read(buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      // FallbackSerializer/CodegenSerializer will set itself to ClassResolver.
      Fory fory1 =
          Fory.builder()
              .withLanguage(Language.JAVA)
              .withRefTracking(false)
              .withCodegen(true)
              .requireClassRegistration(false)
              .build();
      C newC = (C) serDeCheckSerializer(fory1, c, "Codec");
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
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
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withCodegen(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false);
    Fory fory = builder.build();
    {
      MetaSharedSerializer<C> serializer =
          new MetaSharedSerializer<>(
              fory, C.class, fory.getTypeResolver().getTypeDef(C.class, true));
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      serializer.write(buffer, c);
      C newC = serializer.read(buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      // Use MetaSharedSerializer JIT version
      Serializer<C> serializer =
          Serializers.newSerializer(
              fory,
              C.class,
              CodecUtils.loadOrGenMetaSharedCodecClass(
                  fory, C.class, fory.getTypeResolver().getTypeDef(C.class, true)));
      MemoryBuffer buffer = MemoryUtils.buffer(32);
      serializer.write(buffer, c);
      C newC = serializer.read(buffer);
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
    {
      // FallbackSerializer/CodegenSerializer will set itself to ClassResolver.
      Fory fory1 = builder.build();
      C newC = serDeCheckSerializer(fory1, c, ".*Codec|.*Serializer");
      assertEquals(newC.f1, c.f1);
      assertEquals(((B) newC).f1, ((B) c).f1);
      assertEquals(newC, c);
    }
  }
}
