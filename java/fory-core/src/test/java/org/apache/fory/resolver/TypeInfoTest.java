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

import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyObject;
import org.apache.fory.config.Language;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

public class TypeInfoTest {
  public static class EvolvingStruct {
    public int id;
  }

  @ForyObject(evolving = false)
  public static class FixedStruct {
    public int id;
  }

  @Test
  public void testEncodePackageNameAndTypeName() {
    Fory fory1 = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    TypeInfo info1 = fory1.getClassResolver().getTypeInfo(org.apache.fory.test.bean.Foo.class);
    assertNotNull(info1.namespaceBytes);
    assertNotNull(info1.typeNameBytes);
  }

  @Test
  public void testStructEvolvingOverride() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withCompatible(true).build();
    fory.register(EvolvingStruct.class, "test", "EvolvingStruct");
    fory.register(FixedStruct.class, "test", "FixedStruct");

    TypeInfo evolvingInfo = fory.getTypeResolver().getTypeInfo(EvolvingStruct.class, false);
    TypeInfo fixedInfo = fory.getTypeResolver().getTypeInfo(FixedStruct.class, false);
    assertNotNull(evolvingInfo);
    assertNotNull(fixedInfo);
    assertEquals(evolvingInfo.getTypeId(), Types.NAMED_COMPATIBLE_STRUCT);
    assertEquals(fixedInfo.getTypeId(), Types.NAMED_STRUCT);
  }
}
