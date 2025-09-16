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

package org.apache.fory.serializer.collection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

public class XlangCollectionSerializerTest extends ForyTestBase {
  static class SomeClass {
    Set<String> set = new HashSet<>();
    List<String> list = new ArrayList<>();
    Map<String, String> map = new HashMap<>();

    LinkedHashSet<String> set1 = new LinkedHashSet<>();
    LinkedList<String> list1 = new LinkedList<>();
    LinkedHashMap<String, String> map1 = new LinkedHashMap<>();
  }

  @Test(dataProvider = "enableCodegen")
  public void testContainerType(boolean enableCodegen) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).withCodegen(enableCodegen).build();
    fory.register(SomeClass.class, "SomeClass");

    SomeClass someClass = new SomeClass();
    byte[] bytes = fory.serialize(someClass);
    SomeClass obj = (SomeClass) fory.deserialize(bytes);
    Assert.assertEquals(obj.set.getClass(), HashSet.class);
    Assert.assertEquals(obj.list.getClass(), ArrayList.class);
    Assert.assertEquals(obj.map.getClass(), HashMap.class);
    Assert.assertEquals(obj.set1.getClass(), LinkedHashSet.class);
    Assert.assertEquals(obj.list1.getClass(), LinkedList.class);
    Assert.assertEquals(obj.map1.getClass(), LinkedHashMap.class);
  }
}
