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

package org.apache.fory.serializer;

import java.net.MalformedURLException;
import java.net.URL;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.Assert;
import org.testng.annotations.Test;

public class URLSerializerTest extends ForyTestBase {

  @Test(dataProvider = "javaFory")
  public void testDefaultWrite(Fory fory) throws MalformedURLException {
    Assert.assertEquals(
        serDeCheckSerializer(fory, new URL("http://test"), "ReplaceResolve"),
        new URL("http://test"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testDefaultCopy(Fory fory) throws MalformedURLException {
    copyCheck(fory, new URL("http://test"));
  }

  @Test
  public void testURLSerializer() throws MalformedURLException {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    fory.registerSerializer(URL.class, URLSerializer.class);
    Assert.assertEquals(
        serDeCheckSerializer(fory, new URL("http://test"), "URLSerializer"),
        new URL("http://test"));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testURLSerializer(Fory fory) throws MalformedURLException {
    fory.registerSerializer(URL.class, URLSerializer.class);
    copyCheck(fory, new URL("http://test"));
  }
}
