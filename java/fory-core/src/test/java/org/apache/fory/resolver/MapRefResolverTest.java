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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Map;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class MapRefResolverTest {

  @Test
  public void testTrackingReference() {
    MapRefResolver referenceResolver = new MapRefResolver(0.51f);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    assertTrue(referenceResolver.writeRefOrNull(buffer, null));
    assertFalse(referenceResolver.writeRefOrNull(buffer, new Object()));
    Object o = new Object();
    assertFalse(referenceResolver.writeRefOrNull(buffer, o));
    assertTrue(referenceResolver.writeRefOrNull(buffer, o));
    assertFalse(referenceResolver.writeNullFlag(buffer, o));
    assertTrue(referenceResolver.writeNullFlag(buffer, null));
  }

  @Test
  public void testRefStatistics() {
    // MapRefResolver may be loaded and run already, set flag won't take effect.
    // If java jit run and optimized `writeRefOrNull`, set `ENABLE_FORY_REF_PROFILING`
    // by reflection may not take effect too.
    // System.setProperty("fory.enable_ref_profiling", "true");
    MapRefResolver referenceResolver = new MapRefResolver(0.51f);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    Object obj1 = new Object();
    referenceResolver.writeRefOrNull(buffer, obj1);
    referenceResolver.writeRefOrNull(buffer, obj1);
    referenceResolver.writeRefOrNull(buffer, "abc");
    referenceResolver.writeRefOrNull(buffer, "abcd");
    referenceResolver.writeRefOrNull(buffer, String.class);
    MapRefResolver.RefStatistics refStatistics = referenceResolver.referenceStatistics();
    Map<Class<?>, Integer> summary = refStatistics.refTypeSummary;
    // check order, type most frequent occurs first.
    assertEquals(summary.entrySet().stream().iterator().next().getKey(), String.class);
    assertEquals(summary.get(Object.class).intValue(), 1);
    assertEquals(summary.get(String.class).intValue(), 2);
    assertEquals(summary.get(Class.class).intValue(), 1);
    // assertTrue(referenceStatistics.mapStatistics.totalProbeProfiled > 0);
    // assertTrue(referenceStatistics.mapStatistics.maxProbeProfiled > 0);
    // assertTrue(referenceStatistics.referenceCount > 0);
  }
}
