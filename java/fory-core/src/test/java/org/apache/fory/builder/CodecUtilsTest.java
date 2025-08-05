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

package org.apache.fory.builder;

import static org.testng.Assert.assertEquals;

import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.test.bean.BeanA;
import org.testng.annotations.Test;

public class CodecUtilsTest {

  @SuppressWarnings("unchecked")
  @Test
  public void loadOrGenObjectCodecClass() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    Class<?> seqCodecClass = fory.getClassResolver().getSerializerClass(BeanA.class);
    Generated.GeneratedSerializer serializer =
        seqCodecClass
            .asSubclass(Generated.GeneratedSerializer.class)
            .getConstructor(Fory.class, Class.class)
            .newInstance(fory, BeanA.class);
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    BeanA beanA = BeanA.createBeanA(2);
    serializer.write(buffer, beanA);
    byte[] bytes = buffer.getBytes(0, buffer.writerIndex());
    Object obj = serializer.read(MemoryBuffer.wrap(bytes));
    assertEquals(obj, beanA);
  }
}
