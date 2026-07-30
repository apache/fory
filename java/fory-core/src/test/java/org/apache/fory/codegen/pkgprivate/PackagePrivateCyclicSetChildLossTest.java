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
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
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
 * Regression test: a wide fan-out (one parent, many children) cyclic graph of package-private
 * nodes silently loses entries from a {@code Set<PackagePrivateType>} field during
 * deserialization - no exception is thrown, so unlike {@link PackagePrivateMapKeyTest} (which
 * catches the codegen CompileException for this same field shape) this defect only surfaces as
 * missing data.
 *
 * <p>Reproduced from a production graph with 100+ children under one parent node; the
 * child->parent edge (an EnumMap-keyed back-reference) survives the round trip intact, but the
 * parent->child edge (a plain HashSet) loses a handful of entries. Both edges are set together,
 * atomically, at construction time, so the source object graph itself is never inconsistent -
 * the asymmetry is introduced purely by fory's serialize/deserialize round trip.
 */
public class PackagePrivateCyclicSetChildLossTest {

  private static final int CHILD_COUNT = 200;

  @Test
  public void testWideFanOutDoesNotLoseChildrenFromPackagePrivateSet() {
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
    assertEquals(resultParent.children.size(), CHILD_COUNT, "parent lost children from its Set field");

    // Every child must still be reachable BOTH ways: down (parent.children) and up
    // (child.parents), and both directions must point at the exact same object (fory's
    // refTracking should unify identical (type,id) instances, not duplicate them).
    List<String> asymmetric = new ArrayList<>();
    for (String childId : expectedChildIds) {
      FanOutNode resultChild = result.nodes.get(FanOutType.TYPE_B).get(childId);
      boolean parentListsChild = resultParent.children.contains(resultChild);
      boolean childListsParent =
          resultChild.parents.getOrDefault(FanOutType.TYPE_A, Set.of()).contains(resultParent);
      if (!parentListsChild || !childListsParent) {
        asymmetric.add(
            childId + " (parentListsChild=" + parentListsChild + ", childListsParent=" + childListsParent + ")");
      }
    }
    assertTrue(
        asymmetric.isEmpty(),
        "asymmetric parent<->child edges after round trip (should be empty): " + asymmetric);
  }
}

// All package-private — this triggers the bug
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
