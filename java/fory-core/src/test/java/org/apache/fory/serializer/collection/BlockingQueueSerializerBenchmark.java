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

package org.apache.fory.serializer.collection;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Performance and correctness test for blocking queue serializers.
 */
public class BlockingQueueSerializerBenchmark extends ForyTestBase {

  @Test
  public void testLinkedBlockingQueuePerformance() {
    Fory fory = Fory.builder()
        .withLanguage(Language.JAVA)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .build();

    // Test with different queue sizes
    int[] sizes = {10, 100, 1000};
    
    for (int size : sizes) {
      LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>(size * 2);
      for (int i = 0; i < size; i++) {
        queue.add(i);
      }

      long startTime = System.nanoTime();
      byte[] bytes = fory.serialize(queue);
      long serializeTime = System.nanoTime() - startTime;

      startTime = System.nanoTime();
      LinkedBlockingQueue<Integer> deserialized = 
          (LinkedBlockingQueue<Integer>) fory.deserialize(bytes);
      long deserializeTime = System.nanoTime() - startTime;

      System.out.printf("LinkedBlockingQueue size=%d: serialize=%d μs, deserialize=%d μs, bytes=%d%n",
          size, serializeTime / 1000, deserializeTime / 1000, bytes.length);

      // Verify correctness
      Assert.assertEquals(deserialized.size(), queue.size());
      Assert.assertEquals(deserialized.remainingCapacity(), queue.remainingCapacity());
      
      Object[] originalElements = queue.toArray();
      Object[] deserializedElements = deserialized.toArray();
      Assert.assertEquals(deserializedElements, originalElements);
    }
  }

  @Test
  public void testArrayBlockingQueuePerformance() {
    Fory fory = Fory.builder()
        .withLanguage(Language.JAVA)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .build();

    // Test with different queue sizes
    int[] sizes = {10, 100, 1000};
    
    for (int size : sizes) {
      ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(size * 2);
      for (int i = 0; i < size; i++) {
        queue.add("Element-" + i);
      }

      long startTime = System.nanoTime();
      byte[] bytes = fory.serialize(queue);
      long serializeTime = System.nanoTime() - startTime;

      startTime = System.nanoTime();
      ArrayBlockingQueue<String> deserialized = 
          (ArrayBlockingQueue<String>) fory.deserialize(bytes);
      long deserializeTime = System.nanoTime() - startTime;

      System.out.printf("ArrayBlockingQueue size=%d: serialize=%d μs, deserialize=%d μs, bytes=%d%n",
          size, serializeTime / 1000, deserializeTime / 1000, bytes.length);

      // Verify correctness
      Assert.assertEquals(deserialized.size(), queue.size());
      Assert.assertEquals(deserialized.remainingCapacity(), queue.remainingCapacity());
      
      Object[] originalElements = queue.toArray();
      Object[] deserializedElements = deserialized.toArray();
      Assert.assertEquals(deserializedElements, originalElements);
    }
  }

  @Test
  public void testLinkedBlockingQueueConcurrentOperations() throws InterruptedException {
    Fory fory = Fory.builder()
        .withLanguage(Language.JAVA)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .build();

    LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>(100);
    for (int i = 0; i < 50; i++) {
      queue.add(i);
    }

    byte[] bytes = fory.serialize(queue);
    LinkedBlockingQueue<Integer> deserialized = 
        (LinkedBlockingQueue<Integer>) fory.deserialize(bytes);

    // Verify the deserialized queue works correctly with concurrent operations
    Thread producer = new Thread(() -> {
      try {
        for (int i = 50; i < 100; i++) {
          deserialized.put(i);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    Thread consumer = new Thread(() -> {
      try {
        for (int i = 0; i < 100; i++) {
          deserialized.take();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    producer.start();
    consumer.start();

    producer.join(5000);
    consumer.join(5000);

    Assert.assertTrue(deserialized.isEmpty(), "Queue should be empty after concurrent operations");
  }
}
