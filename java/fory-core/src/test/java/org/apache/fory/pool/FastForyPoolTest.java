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

package org.apache.fory.pool;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import org.apache.fory.Fory;
import org.apache.fory.test.bean.BeanB;
import org.testng.annotations.Test;

public class FastForyPoolTest {

  @Test
  public void testRegisterBeforeFirstUseAppliesToCreatedInstance() {
    FastForyPool pool =
        (FastForyPool) Fory.builder().requireClassRegistration(true).buildFastForyPool(1);

    pool.register(BeanB.class);

    BeanB bean = BeanB.createBeanB(2);
    assertEquals(pool.deserialize(pool.serialize(bean)), bean);
  }

  @Test
  public void testRegisterCallbackAfterSerializationStartsThrows() {
    FastForyPool pool =
        (FastForyPool) Fory.builder().requireClassRegistration(true).buildFastForyPool(1);

    assertEquals(pool.deserialize(pool.serialize("abc")), "abc");
    assertThrows(IllegalStateException.class, () -> pool.registerCallback(fory -> {}));
    assertThrows(IllegalStateException.class, () -> pool.register(BeanB.class));
  }
}
