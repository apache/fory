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

import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.config.UnknownEnumValueStrategy;
import org.testng.Assert;
import org.testng.annotations.Test;

public class EnumTest {
  enum Color {
    Green,
    Red,
    Blue,
    White,
  }

  enum Color2 {
    Green,
    Red,
  }

  static class EnumWrapper {
    Color color;
  }

  static class EnumWrapper2 {
    Color2 color;
  }

  @Test
  public void testEnumEnum() {
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withCodegen(false)
            .build();
    fory1.register(Color.class, 101);
    fory1.register(Color2.class, 102);
    fory1.register(EnumWrapper.class, 103);
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withUnknownEnumValueStrategy(UnknownEnumValueStrategy.RETURN_FIRST_VARIANT)
            .withCodegen(false)
            .build();
    fory2.register(Color.class, 101);
    fory2.register(Color2.class, 102);
    fory2.register(EnumWrapper2.class, 103);

    EnumWrapper enumWrapper = new EnumWrapper();
    enumWrapper.color = Color.White;
    byte[] serialize = fory1.serialize(enumWrapper);
    EnumWrapper2 wrapper2 = (EnumWrapper2) fory2.deserialize(serialize);
    Assert.assertEquals(wrapper2.color, Color2.Green);

    Fory fory3 =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withUnknownEnumValueStrategy(UnknownEnumValueStrategy.RETURN_LAST_VARIANT)
            .withCodegen(false)
            .build();
    fory3.register(Color.class, 101);
    fory3.register(Color2.class, 102);
    fory3.register(EnumWrapper2.class, 103);
    EnumWrapper2 wrapper3 = (EnumWrapper2) fory3.deserialize(serialize);
    Assert.assertEquals(wrapper3.color, Color2.Red);
  }
}
