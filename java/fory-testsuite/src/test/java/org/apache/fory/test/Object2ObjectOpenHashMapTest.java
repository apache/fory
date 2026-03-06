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

package org.apache.fory.test;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.TestBase;
import org.apache.fory.config.CompatibleMode;
import org.testng.Assert;
import org.testng.annotations.Test;

public class Object2ObjectOpenHashMapTest extends TestBase {
  @Data
  public static class TestObject2ObjectOpenHashMap {
    Map<String, String> ext = new Object2ObjectOpenHashMap<>();
  }

  @Test(dataProvider = "enableCodegen")
  public void testObject2ObjectOpenHashMap(boolean enableCodegen) {
    Fory fory =
        builder().withCompatibleMode(CompatibleMode.COMPATIBLE).withCodegen(enableCodegen).build();

    TestObject2ObjectOpenHashMap o = new TestObject2ObjectOpenHashMap();
    byte[] bytes = fory.serialize(o);
    Assert.assertEquals(fory.deserialize(bytes, TestObject2ObjectOpenHashMap.class), o);
  }
}
