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

import com.google.protobuf.ByteString;
import org.apache.fory.Fory;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.integration_tests.state.generated.ProtoMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProtobufSerializerTest {
  @Test
  public void testSample() {
    Sample object = new Sample().populate(false);
    ProtoMessage.Sample samplePb = ProtoBuffersState.buildSample(object);
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    fory.register(ProtoMessage.Sample.class);
    byte[] bytes = fory.serialize(samplePb);
    Object newObj = fory.deserialize(bytes);
    Assert.assertEquals(newObj, samplePb);
  }

  @Test
  public void testByteString() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    Assert.assertEquals(fory.deserialize(fory.serialize(ByteString.empty())), ByteString.empty());
    ByteString bytes = ByteString.copyFrom(new byte[] {1, 2, 3});
    Assert.assertEquals(fory.deserialize(fory.serialize(bytes)), bytes);
  }
}
