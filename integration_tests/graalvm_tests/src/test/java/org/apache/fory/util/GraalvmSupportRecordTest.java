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

package org.apache.fory.util;

import org.apache.fory.util.record.RecordUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GraalvmSupportRecordTest {

  public record PublicRecord(int value, String name) {}

  private record PrivateRecord(int value) {}

  public static class ClassWithNoArgCtor {
    public ClassWithNoArgCtor() {}
  }

  public static class ClassWithoutNoArgCtor {
    public ClassWithoutNoArgCtor(int value) {}
  }

  @Test
  public void testIsRecord() {
    Assert.assertTrue(RecordUtils.isRecord(PublicRecord.class));
    Assert.assertTrue(RecordUtils.isRecord(PrivateRecord.class));
    Assert.assertFalse(RecordUtils.isRecord(ClassWithNoArgCtor.class));
    Assert.assertFalse(RecordUtils.isRecord(String.class));
  }

  @Test
  public void testIsRecordConstructorPublicAccessible() {
    Assert.assertTrue(GraalvmSupport.isRecordConstructorPublicAccessible(PublicRecord.class));
    Assert.assertFalse(GraalvmSupport.isRecordConstructorPublicAccessible(PrivateRecord.class));
    Assert.assertFalse(
        GraalvmSupport.isRecordConstructorPublicAccessible(ClassWithNoArgCtor.class));
  }

  @Test
  public void testNeedReflectionRegisterForCreation() {
    // Public record with public constructor doesn't need reflection registration
    Assert.assertFalse(GraalvmSupport.needReflectionRegisterForCreation(PublicRecord.class));
    // Private record needs reflection registration
    Assert.assertTrue(GraalvmSupport.needReflectionRegisterForCreation(PrivateRecord.class));
    // Class with no-arg constructor doesn't need reflection registration
    Assert.assertFalse(GraalvmSupport.needReflectionRegisterForCreation(ClassWithNoArgCtor.class));
    // Class without no-arg constructor needs reflection registration
    Assert.assertTrue(
        GraalvmSupport.needReflectionRegisterForCreation(ClassWithoutNoArgCtor.class));
    // Interface doesn't need reflection registration
    Assert.assertFalse(GraalvmSupport.needReflectionRegisterForCreation(Runnable.class));
  }
}
