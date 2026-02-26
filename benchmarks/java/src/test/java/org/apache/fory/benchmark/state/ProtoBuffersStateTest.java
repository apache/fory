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

import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.benchmark.state.ProtoBuffersState.ProtoBuffersUserTypeState;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProtoBuffersStateTest {
  @Test
  public void testSample() {
    Sample object = new Sample().populate(false);
    byte[] data = ProtoBuffersState.serializeSample(object);
    Sample sample = ProtoBuffersState.deserializeSample(data);
    Assert.assertEquals(sample, object);
  }

  @Test
  public void testMediaContent() {
    MediaContent object = new MediaContent().populate(false);
    byte[] data = ProtoBuffersState.serializeMediaContent(object);
    MediaContent mediaContent = ProtoBuffersState.deserializeMediaContent(data);
    Assert.assertEquals(mediaContent, object);
  }

  @Test
  public void testProtoBuffersUserTypeState() {
    ProtoBuffersUserTypeState state = new ProtoBuffersUserTypeState();
    state.objectType = ObjectType.SAMPLE;
    state.bufferType = BufferType.array;
    state.setup();
  }
}
