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

package org.apache.fory.memory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import org.apache.fory.util.StringUtils;
import org.testng.annotations.Test;

public class BitUtilsTest {

  @Test
  public void anyUnSet() {
    int valueCount = 10;
    MemoryBuffer buffer = MemoryBuffer.buffer(valueCount);
    int i = 0;
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    BitUtils.set(buffer, 0, i++);
    assertFalse(BitUtils.anyUnSet(buffer, 0, valueCount));
    StringUtils.encodeHexString(buffer.getRemainingBytes());
  }

  @Test
  public void getNullCount() {
    int valueCount = 14;
    MemoryBuffer buffer = MemoryBuffer.buffer(valueCount);
    buffer.putByte(0, (byte) 0b11000000);
    assertEquals(BitUtils.getNullCount(buffer, 0, 8), 6);
  }

  @Test
  public void testSetAll() {
    int valueCount = 10;
    MemoryBuffer buffer = MemoryBuffer.buffer(8);
    BitUtils.setAll(buffer, 0, valueCount);
    assertEquals(BitUtils.getNullCount(buffer, 0, valueCount), 0);
    assertEquals("ff03000000000000", StringUtils.encodeHexString(buffer.getRemainingBytes()));
  }
}
