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

package org.apache.fory;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.test.bean.Cyclic;
import org.apache.fory.test.bean.FinalCyclic;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class CyclicTest extends ForyTestBase {

  public static Object[][] beans() {
    return new Object[][] {
      {Cyclic.create(false), Cyclic.create(true)},
      {FinalCyclic.create(false), FinalCyclic.create(true)}
    };
  }

  @DataProvider
  public static Object[][] config() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // enableCodegen
            ImmutableSet.of(true, false), // async compilation
            ImmutableSet.of(true, false) // compatible
            )
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @Test(dataProvider = "config")
  public void testBean(boolean enableCodegen, boolean asyncCompilation, boolean compatible) {
    Fory fory = Fory.builder()
        .withLanguage(Language.JAVA)
        .requireClassRegistration(false)
        .withRefTracking(true)
        .withCodegen(enableCodegen)
        .withAsyncCompilation(asyncCompilation)
        .withCompatible(compatible).build();
    for (Object[] objects : beans()) {
      Object notCyclic = objects[0];
      Object cyclic = objects[1];
      Assert.assertEquals(notCyclic, fory.deserialize(fory.serialize(notCyclic)));
      Assert.assertEquals(cyclic, fory.deserialize(fory.serialize(cyclic)));
      Object[] arr = new Object[2];
      arr[0] = arr;
      arr[1] = cyclic;
      Assert.assertEquals(arr[1], ((Object[]) fory.deserialize(fory.serialize(arr)))[1]);
      List<Object> list = new ArrayList<>();
      list.add(list);
      list.add(cyclic);
      list.add(arr);
      Assert.assertEquals(
          ((Object[]) list.get(2))[1],
          ((Object[]) ((List) fory.deserialize(fory.serialize(list))).get(2))[1]);
    }
  }

  @Test
  public void testBeanMetaShared() throws IOException {
    ByteArrayOutputStream s = new ByteArrayOutputStream();
    GZIPOutputStream gzipOutputStream = new GZIPOutputStream(s);
    gzipOutputStream.write(Fory.class.getName().getBytes(StandardCharsets.UTF_8));
    gzipOutputStream.close();
    System.out.println("gzip" + s.size());
    System.out.println(Fory.class.getName().getBytes(StandardCharsets.UTF_8).length);
  }
}
