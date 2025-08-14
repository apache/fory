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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class MemoryAllocatorTest {

  private MemoryAllocator originalAllocator;

  @BeforeMethod
  public void setUp() {
    // Save the original allocator before each test
    originalAllocator = MemoryBuffer.getGlobalAllocator();
  }

  @AfterMethod
  public void tearDown() {
    // Restore the original allocator after each test
    MemoryBuffer.setGlobalAllocator(originalAllocator);
  }

  @Test
  public void testDefaultMemoryAllocator() {
    MemoryAllocator defaultAllocator = MemoryBuffer.getGlobalAllocator();

    MemoryBuffer buffer = defaultAllocator.allocate(100);
    assertEquals(buffer.size(), 100);
    assertFalse(buffer.isOffHeap());

    // Test growth below BUFFER_GROW_STEP_THRESHOLD (should multiply by 2)
    defaultAllocator.grow(buffer, 200);
    assertEquals(buffer.size(), 200 << 1);

    // Test growth above BUFFER_GROW_STEP_THRESHOLD
    buffer = defaultAllocator.allocate(100);
    int largeCapacity = MemoryBuffer.BUFFER_GROW_STEP_THRESHOLD + 1000;
    defaultAllocator.grow(buffer, largeCapacity);
    int expectedSize = (int) Math.min(largeCapacity * 1.5d, Integer.MAX_VALUE - 8);
    assertEquals(buffer.size(), expectedSize);
  }

  @Test
  public void testDefaultMemoryAllocatorDataPreservation() {
    MemoryAllocator defaultAllocator = MemoryBuffer.getGlobalAllocator();
    MemoryBuffer buffer = defaultAllocator.allocate(100);

    // Write some test data
    byte[] testData = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    buffer.writeBytes(testData);
    buffer.writeInt32(42);
    buffer.writeInt64(123456789L);

    int writerIndexBeforeGrowth = buffer.writerIndex();

    // Grow the buffer
    defaultAllocator.grow(buffer, 500);

    // Verify data is preserved
    buffer.readerIndex(0);
    byte[] readData = new byte[testData.length];
    buffer.readBytes(readData);
    for (int i = 0; i < testData.length; i++) {
      assertEquals(readData[i], testData[i]);
    }

    assertEquals(buffer.readInt32(), 42);
    assertEquals(buffer.readInt64(), 123456789L);
    assertEquals(buffer.writerIndex(), writerIndexBeforeGrowth);
  }

  @Test
  public void testDefaultMemoryAllocatorGrowthSameInstance() {
    MemoryAllocator defaultAllocator = MemoryBuffer.getGlobalAllocator();
    MemoryBuffer buffer = defaultAllocator.allocate(100);

    // Growth should return the same instance
    MemoryBuffer grownBuffer = defaultAllocator.grow(buffer, 200);
    assertSame(buffer, grownBuffer);
  }

  @Test
  public void testCustomAllocator() {
    // Create a custom allocator that adds a marker
    MemoryAllocator customAllocator =
        new MemoryAllocator() {
          @Override
          public MemoryBuffer allocate(int initialCapacity) {
            // Use larger capacity as a marker
            return MemoryBuffer.fromByteArray(new byte[initialCapacity + 10]);
          }

          @Override
          public MemoryBuffer grow(MemoryBuffer buffer, int newCapacity) {
            if (newCapacity <= buffer.size()) {
              return buffer;
            }

            // Use default grow logic but with custom marker
            int newSize = newCapacity + 10; // Add 10 as marker
            byte[] data = new byte[newSize];
            buffer.copyToUnsafe(0, data, Platform.BYTE_ARRAY_OFFSET, buffer.size());
            buffer.initHeapBuffer(data, 0, data.length);
            return buffer;
          }
        };

    // Set the custom allocator
    MemoryBuffer.setGlobalAllocator(customAllocator);
    assertSame(MemoryBuffer.getGlobalAllocator(), customAllocator);

    // Test allocation
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(100);
    assertEquals(buffer.size(), 110); // 100 + 10 marker

    // Test growth
    buffer.writerIndex(50);
    buffer.readerIndex(10);
    buffer.ensure(200); // This should trigger growth
    assertEquals(buffer.writerIndex(), 50);
    assertEquals(buffer.readerIndex(), 10);
    assertTrue(buffer.size() >= 210); // Should be at least 200 + 10 marker
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void testSetNullAllocator() {
    MemoryBuffer.setGlobalAllocator(null);
  }
}
