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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.ForyStruct.Evolution;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

public class TypeInfoTest {
  public static class EvolvingStruct {
    public int id;
  }

  @ForyStruct(evolution = Evolution.ENABLED)
  public static class ExplicitEvolvingStruct {
    public int id;
  }

  @ForyStruct(evolution = Evolution.DISABLED)
  public static class FixedStruct {
    public int id;
  }

  @ForyStruct(evolving = false)
  public static class LegacyFixedStruct {
    public int id;
  }

  @Test
  public void testEncodePackageNameAndTypeName() {
    Fory fory1 =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    TypeInfo info1 = fory1.getTypeResolver().getTypeInfo(org.apache.fory.test.bean.Foo.class);
    assertNotNull(info1.namespace);
    assertNotNull(info1.typeName);
  }

  @Test
  public void testStructEvolvingOverride() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).build();
    fory.register(EvolvingStruct.class, "test", "EvolvingStruct");
    fory.register(ExplicitEvolvingStruct.class, "test", "ExplicitEvolvingStruct");
    fory.register(FixedStruct.class, "test", "FixedStruct");
    fory.register(LegacyFixedStruct.class, "test", "LegacyFixedStruct");

    TypeInfo evolvingInfo = fory.getTypeResolver().getTypeInfo(EvolvingStruct.class, false);
    TypeInfo explicitEvolvingInfo =
        fory.getTypeResolver().getTypeInfo(ExplicitEvolvingStruct.class, false);
    TypeInfo fixedInfo = fory.getTypeResolver().getTypeInfo(FixedStruct.class, false);
    TypeInfo legacyFixedInfo = fory.getTypeResolver().getTypeInfo(LegacyFixedStruct.class, false);
    assertNotNull(evolvingInfo);
    assertNotNull(explicitEvolvingInfo);
    assertNotNull(fixedInfo);
    assertNotNull(legacyFixedInfo);
    assertEquals(evolvingInfo.getTypeId(), Types.NAMED_COMPATIBLE_STRUCT);
    assertEquals(explicitEvolvingInfo.getTypeId(), Types.NAMED_COMPATIBLE_STRUCT);
    assertEquals(fixedInfo.getTypeId(), Types.NAMED_STRUCT);
    assertEquals(legacyFixedInfo.getTypeId(), Types.NAMED_STRUCT);

    EvolvingStruct evolving = new EvolvingStruct();
    evolving.id = 123;
    FixedStruct fixed = new FixedStruct();
    fixed.id = 123;

    byte[] evolvingPayload = fory.serialize(evolving);
    byte[] fixedPayload = fory.serialize(fixed);

    assertTrue(fixedPayload.length < evolvingPayload.length);
    assertEquals(fory.deserialize(evolvingPayload, EvolvingStruct.class).id, evolving.id);
    assertEquals(fory.deserialize(fixedPayload, FixedStruct.class).id, fixed.id);
  }

  @Test
  public void testStructEvolvingOverrideForRegisteredClasses() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(true).build();
    fory.register(EvolvingStruct.class, 100);
    fory.register(ExplicitEvolvingStruct.class, 101);
    fory.register(FixedStruct.class, 102);
    fory.register(LegacyFixedStruct.class, 103);

    assertEquals(
        fory.getTypeResolver().getTypeInfo(EvolvingStruct.class, false).getTypeId(),
        Types.COMPATIBLE_STRUCT);
    assertEquals(
        fory.getTypeResolver().getTypeInfo(ExplicitEvolvingStruct.class, false).getTypeId(),
        Types.COMPATIBLE_STRUCT);
    assertEquals(
        fory.getTypeResolver().getTypeInfo(FixedStruct.class, false).getTypeId(), Types.STRUCT);
    assertEquals(
        fory.getTypeResolver().getTypeInfo(LegacyFixedStruct.class, false).getTypeId(),
        Types.STRUCT);
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = ".*schema evolution metadata.*")
  public void testStructEvolutionEnabledRequiresMetadata() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    fory.register(ExplicitEvolvingStruct.class, "test", "ExplicitEvolvingStruct");
  }
}
