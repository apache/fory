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

import org.apache.fory.config.ForyBuilder;
import org.testng.Assert;
import org.testng.annotations.DataProvider;

/** Fory unit test base class. */
@SuppressWarnings("unchecked")
public abstract class TestBase {

  public static ForyBuilder builder() {
    return Fory.builder().withXlang(false).requireClassRegistration(false);
  }

  @DataProvider
  public static Object[][] trackingRef() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] enableCodegen() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] compressNumber() {
    return new Object[][] {{false}, {true}};
  }

  public static Object serDeCheck(Fory fory, Object obj) {
    Object o = serDe(fory, obj);
    Assert.assertEquals(o, obj);
    return o;
  }

  public static <T> T serDe(Fory fory, T obj) {
    try {
      byte[] bytes = fory.serialize(obj);
      return (T) (fory.deserialize(bytes));
    } catch (Throwable t) {
      // Catch for add breakpoint for debugging.
      throw t;
    }
  }
}
