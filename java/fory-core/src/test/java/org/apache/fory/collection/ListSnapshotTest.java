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

import static org.testng.Assert.*;

import java.util.*;
import org.testng.annotations.Test;

public class ListSnapshotTest {

  @Test
  public void testSetList() {
    ListSnapshot<String> snapshot = new ListSnapshot<>();
    List<String> source = Arrays.asList("a", "b", "c");

    snapshot.setList(source);

    assertEquals(snapshot.size(), 3);
    List<String> result = new ArrayList<>();
    for (String item : snapshot) {
      result.add(item);
    }
    assertEquals(result, source);
  }

  @Test
  public void testGet() {
    ListSnapshot<String> snapshot = new ListSnapshot<>();
    List<String> source = Arrays.asList("a", "b", "c");
    snapshot.setList(source);

    assertEquals(snapshot.get(0), "a");
    assertEquals(snapshot.get(1), "b");
    assertEquals(snapshot.get(2), "c");
  }

  @Test
  public void testIterator() {
    ListSnapshot<Integer> snapshot = new ListSnapshot<>();
    List<Integer> source = Arrays.asList(1, 2, 3);
    snapshot.setList(source);

    Iterator<Integer> iterator = snapshot.iterator();
    assertTrue(iterator.hasNext());
    assertEquals(iterator.next(), Integer.valueOf(1));
    assertTrue(iterator.hasNext());
    assertEquals(iterator.next(), Integer.valueOf(2));
    assertTrue(iterator.hasNext());
    assertEquals(iterator.next(), Integer.valueOf(3));
    assertFalse(iterator.hasNext());
  }

  @Test
  public void testClearSmallCollection() {
    ListSnapshot<String> snapshot = new ListSnapshot<>();
    snapshot.setList(Arrays.asList("a", "b"));

    snapshot.clear();

    assertEquals(snapshot.size(), 0);
    assertFalse(snapshot.iterator().hasNext());
  }

  @Test
  public void testClearLargeCollection() {
    ListSnapshot<Integer> snapshot = new ListSnapshot<>();
    List<Integer> largeList = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      largeList.add(i);
    }
    snapshot.setList(largeList);

    snapshot.clear();

    assertEquals(snapshot.size(), 0);
    assertFalse(snapshot.iterator().hasNext());
  }

  @Test
  public void testClearAndReuse() {
    ListSnapshot<Integer> snapshot = new ListSnapshot<>();

    // First use
    snapshot.setList(Arrays.asList(1, 2, 3));
    assertEquals(snapshot.size(), 3);

    // Clear and reuse multiple times
    for (int i = 0; i < 3; i++) {
      snapshot.clear();
      assertEquals(snapshot.size(), 0);

      List<Integer> newData = Arrays.asList(i * 10, i * 10 + 1);
      snapshot.setList(newData);
      assertEquals(snapshot.size(), 2);

      List<Integer> result = new ArrayList<>();
      for (Integer item : snapshot) {
        result.add(item);
      }
      assertEquals(result, newData);
    }
  }

  @Test
  public void testIndexedAccessAfterReuse() {
    ListSnapshot<String> snapshot = new ListSnapshot<>();

    // First use
    snapshot.setList(Arrays.asList("a", "b", "c"));
    assertEquals(snapshot.get(0), "a");
    assertEquals(snapshot.get(1), "b");
    assertEquals(snapshot.get(2), "c");

    snapshot.clear();

    // Second use
    snapshot.setList(Arrays.asList("x", "y"));
    assertEquals(snapshot.get(0), "x");
    assertEquals(snapshot.get(1), "y");
    assertEquals(snapshot.size(), 2);
  }

  @Test
  public void testEmptyCollectionSet() {
    ListSnapshot<String> snapshot = new ListSnapshot<>();
    List<String> emptyList = new ArrayList<>();

    snapshot.setList(emptyList);

    assertEquals(snapshot.size(), 0);
    assertTrue(snapshot.isEmpty());
    assertFalse(snapshot.iterator().hasNext());
  }
}
