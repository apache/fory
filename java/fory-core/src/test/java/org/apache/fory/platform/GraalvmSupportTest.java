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

package org.apache.fory.platform;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class GraalvmSupportTest {

  public static class PublicNoArgConstructorClass {
    public PublicNoArgConstructorClass() {}
  }

  public static class PrivateNoArgConstructorClass {
    private PrivateNoArgConstructorClass() {}
  }

  public static class NoNoArgConstructorClass {
    public NoNoArgConstructorClass(String data) {}
  }

  public abstract static class AbstractClass {}

  public interface SampleInterface {}

  public enum SampleEnum {
    VALUE
  }

  @Test
  public void testNeedReflectionRegisterForCreation() {
    assertFalse(
        GraalvmSupport.needReflectionRegisterForCreation(PublicNoArgConstructorClass.class));
    assertTrue(
        GraalvmSupport.needReflectionRegisterForCreation(PrivateNoArgConstructorClass.class));
    assertFalse(GraalvmSupport.needReflectionRegisterForCreation(NoNoArgConstructorClass.class));
    assertFalse(GraalvmSupport.needReflectionRegisterForCreation(AbstractClass.class));
    assertFalse(GraalvmSupport.needReflectionRegisterForCreation(SampleInterface.class));
    assertFalse(GraalvmSupport.needReflectionRegisterForCreation(SampleEnum.class));
    assertFalse(GraalvmSupport.needReflectionRegisterForCreation(String[].class));
  }
}
