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

import org.apache.fory.CrossLanguageTest;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CodegenXlangTest extends ForyTestBase {

  @Test
  public void testCodeGenSchemaConsistent() {
    Fory fory =
        Fory.builder()
            .withCodegen(true)
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .requireClassRegistration(true)
            .requireClassRegistration(false)
            .build();
    Fory fory1 =
        Fory.builder()
            .withCodegen(false)
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
            .requireClassRegistration(true)
            .requireClassRegistration(false)
            .build();
    //    fory.register(CrossLanguageTest.Foo.class, "example.foo");
    //    fory.register(CrossLanguageTest.Bar.class, "example.bar");
    //    fory1.register(CrossLanguageTest.Foo.class, "example.foo");
    //    fory1.register(CrossLanguageTest.Bar.class, "example.bar");

    serDeCheck(fory, CrossLanguageTest.Bar.create());
    serDeCheck(fory, CrossLanguageTest.Foo.create());
    serDeCheck(fory1, CrossLanguageTest.Bar.create());
    serDeCheck(fory1, CrossLanguageTest.Foo.create());
    CrossLanguageTest.Bar bar = CrossLanguageTest.Bar.create();
    byte[] serialize = fory.serialize(bar);
    Object deserialize = fory1.deserialize(serialize);
    Assert.assertEquals(bar, deserialize);
    byte[] serialize1 = fory1.serialize(bar);
    Object deserialize1 = fory1.deserialize(serialize1);
    Assert.assertEquals(bar, deserialize1);
  }

  @Test
  public void testCodeGenCompatible() {
    Fory fory =
        Fory.builder()
            .withCodegen(true)
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(true)
            .build();
    Fory fory1 =
        Fory.builder()
            .withCodegen(false)
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(true)
            .build();
    fory.register(CrossLanguageTest.Foo.class, "example.foo");
    fory.register(CrossLanguageTest.Bar.class, "example.bar");
    fory1.register(CrossLanguageTest.Foo.class, "example.foo");
    fory1.register(CrossLanguageTest.Bar.class, "example.bar");
    serDeCheck(fory, CrossLanguageTest.Bar.create());
    serDeCheck(fory, CrossLanguageTest.Foo.create());
    serDeCheck(fory1, CrossLanguageTest.Bar.create());
    serDeCheck(fory1, CrossLanguageTest.Foo.create());
    CrossLanguageTest.Bar bar = CrossLanguageTest.Bar.create();
    byte[] serialize = fory.serialize(bar);
    Object deserialize = fory1.deserialize(serialize);
    Assert.assertEquals(bar, deserialize);
    byte[] serialize1 = fory1.serialize(bar);
    Object deserialize1 = fory1.deserialize(serialize1);
    Assert.assertEquals(bar, deserialize1);
  }
}
