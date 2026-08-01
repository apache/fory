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

package org.apache.fory.codegen.pkgprivate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.testng.annotations.Test;

/**
 * Regression test for a package-private cyclic graph whose child inserts an early parent
 * back-reference into a {@link LinkedHashSet}.
 *
 * <p>The set still iterates the exact deserialized parent, but lookup fails after the parent's
 * hash-relevant fields finish materializing. A wide fan-out makes the resulting asymmetric lookup
 * easy to observe without changing the object graph or throwing an exception.
 */
public class PackagePrivateCyclicSetChildLossTest {

  private static final int CHILD_COUNT = 200;

  @Test
  public void testCyclicSetMembership() {
    ThreadSafeFory fury =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatible(false)
            .buildThreadSafeFory();

    FanOutContainer container = new FanOutContainer("v1");
    FanOutNode parent = new FanOutNode(FanOutType.TYPE_A, "parent");
    container.nodes.computeIfAbsent(FanOutType.TYPE_A, k -> new HashMap<>()).put(parent.id, parent);

    List<String> expectedChildIds = new ArrayList<>();
    for (int i = 0; i < CHILD_COUNT; i++) {
      FanOutNode child = new FanOutNode(FanOutType.TYPE_B, "child-" + i);
      parent.children.add(child);
      child.parents.computeIfAbsent(parent.type, k -> new LinkedHashSet<>()).add(parent);
      container.nodes.computeIfAbsent(FanOutType.TYPE_B, k -> new HashMap<>()).put(child.id, child);
      expectedChildIds.add(child.id);
    }

    byte[] bytes = fury.serialize(container);
    FanOutContainer result = (FanOutContainer) fury.deserialize(bytes);

    FanOutNode resultParent = result.nodes.get(FanOutType.TYPE_A).get("parent");
    assertEquals(
        resultParent.children.size(), CHILD_COUNT, "parent lost children from its Set field");

    // Inspect the child set by iteration before using contains so the test distinguishes stale
    // hash buckets from a lost reference-table edge.
    List<String> asymmetric = new ArrayList<>();
    for (String childId : expectedChildIds) {
      FanOutNode resultChild = result.nodes.get(FanOutType.TYPE_B).get(childId);
      boolean parentListsChild = resultParent.children.contains(resultChild);
      Set<FanOutNode> resultParents =
          resultChild.parents.getOrDefault(FanOutType.TYPE_A, Collections.emptySet());
      assertEquals(resultParents.size(), 1);
      assertSame(resultParents.iterator().next(), resultParent);
      boolean childListsParent = resultParents.contains(resultParent);
      if (!parentListsChild || !childListsParent) {
        asymmetric.add(
            childId
                + " (parentListsChild="
                + parentListsChild
                + ", childListsParent="
                + childListsParent
                + ")");
      }
    }
    assertTrue(
        asymmetric.isEmpty(),
        "asymmetric parent<->child edges after round trip (should be empty): " + asymmetric);
  }
}

// Package-private model retained from the original reproduction.
enum FanOutType implements Serializable {
  TYPE_A,
  TYPE_B
}

class FanOutNode implements Serializable {
  final FanOutType type;
  final String id;
  final Set<FanOutNode> children = new HashSet<>();
  final Map<FanOutType, Set<FanOutNode>> parents = new EnumMap<>(FanOutType.class);

  FanOutNode(FanOutType type, String id) {
    this.type = type;
    this.id = id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FanOutNode)) {
      return false;
    }
    FanOutNode other = (FanOutNode) o;
    return type == other.type && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, id);
  }
}

class FanOutContainer implements Serializable {
  final Map<FanOutType, Map<String, FanOutNode>> nodes = new EnumMap<>(FanOutType.class);
  final String version;

  FanOutContainer(String version) {
    this.version = version;
  }
}
