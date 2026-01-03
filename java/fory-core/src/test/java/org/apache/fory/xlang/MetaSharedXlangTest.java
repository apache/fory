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
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.xlang.PyCrossLanguageTest.Bar;
import org.apache.fory.xlang.PyCrossLanguageTest.Foo;
import org.testng.annotations.Test;

public class MetaSharedXlangTest extends ForyTestBase {

  @Test
  public void testMetaSharedBasic() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(Foo.class, "example.foo");
    fory.register(Bar.class, "example.bar");
    serDeCheck(fory, Bar.create());
    serDeCheck(fory, Foo.create());
  }

  @Test
  public void testMetaSharedComplex1() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(BeanB.class, "example.b");
    serDeCheck(fory, BeanB.createBeanB(2));
  }

  @Data
  static class MDArrayFieldStruct {
    int[][] arr;
  }

  @Test
  public void testMDArrayField() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build();
    fory.register(MDArrayFieldStruct.class, "example.a");
    MDArrayFieldStruct s = new MDArrayFieldStruct();
    s.arr = new int[][] {{1, 2}, {3, 4}};
    serDeCheck(fory, s);
  }
}
