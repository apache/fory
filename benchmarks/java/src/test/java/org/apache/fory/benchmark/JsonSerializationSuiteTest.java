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

package org.apache.fory.benchmark;

import java.io.IOException;
import org.apache.fory.benchmark.JsonSerializationSuite.JsonState;
import org.apache.fory.benchmark.data.MediaContent;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JsonSerializationSuiteTest {
  @Test
  public void testJackson() throws IOException {
    JsonSerializationSuite suite = new JsonSerializationSuite();

    JsonState jackson = new JsonState();
    jackson.setup();
    assertJackson(jackson, suite);
  }

  private static void assertJackson(JsonState state, JsonSerializationSuite suite)
      throws IOException {
    MediaContent fromBytes =
        state.mapper.readValue(suite.jacksonToJsonBytes(state), MediaContent.class);
    MediaContent fromString =
        state.mapper.readValue(suite.jacksonToJsonString(state), MediaContent.class);
    Assert.assertEquals(fromBytes, state.mediaContent);
    Assert.assertEquals(fromString, state.mediaContent);
    Assert.assertEquals(suite.jacksonFromJsonBytes(state), state.mediaContent);
    Assert.assertEquals(suite.jacksonFromJsonString(state), state.mediaContent);
  }
}
