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

package org.apache.fory.benchmark.state;

import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.collection.Collections;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JsonTest {
  public static class DemoResponse {
    private JSONObject json;
    private List<JSONObject> objects;

    public DemoResponse(JSONObject json) {
      this.json = json;
      objects = Collections.ofArrayList(json);
    }
  }

  @DataProvider
  public static Object[][] config() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // referenceTracking
            ImmutableSet.of(true, false), // compatible mode
            ImmutableSet.of(true, false), // scoped meta share mode
            ImmutableSet.of(true, false) // fory enable codegen
            )
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  @Test(dataProvider = "config")
  public void testSerializeJson(
      boolean trackingRef, boolean compatible, boolean scoped, boolean codegen) {
    // For issue: https://github.com/apache/fory/issues/1604
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("k1", "v1");
    jsonObject.put("k2", "v2");
    DemoResponse resp = new DemoResponse(jsonObject);
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(trackingRef)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .withScopedMetaShare(scoped)
            .withCodegen(codegen)
            .registerGuavaTypes(false)
            .build();
    byte[] serialized = fory.serialize(resp);
    DemoResponse o = (DemoResponse) fory.deserialize(serialized);
    Assert.assertEquals(o.json, jsonObject);
    Assert.assertEquals(o.objects, Collections.ofArrayList(jsonObject));
  }
}
