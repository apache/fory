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

package org.apache.fory.collection;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Set;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.platform.internal._JDKAccess;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class MultiKeyWeakMapTest {
  private static final Logger LOG = LoggerFactory.getLogger(MultiKeyWeakMapTest.class);

  @SuppressWarnings("unchecked")
  @Test(timeOut = 60000)
  public void testMap() throws Exception {
    if (_JDKAccess.IS_OPEN_J9) {
      throw new SkipException("OpenJ9 unsupported");
    }
    MultiKeyWeakMap<Object> map = new MultiKeyWeakMap<>();
    Field referencesField = MultiKeyWeakMap.class.getDeclaredField("REFERENCES");
    referencesField.setAccessible(true);
    Set<Reference<?>> references = (Set<Reference<?>>) referencesField.get(null);
    System.gc();
    Thread.sleep(50);
    LOG.info("Before references: {}", references);
    int size = references.size();
    Object o1 = new Object();
    Object o2 = new Object();
    map.put(new Object[] {o1, o2}, true);

    o1 = null;
    o2 = null;
    System.gc();

    // `references` is global and may contain references put by others.
    while (references.size() != size) {
      Thread.sleep(10);
      System.gc();
      LOG.info("wait object gc, references: {}", references);
    }
  }
}
