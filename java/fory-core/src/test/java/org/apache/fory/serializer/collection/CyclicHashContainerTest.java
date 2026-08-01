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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class CyclicHashContainerTest {
  @DataProvider
  public static Object[][] configs() {
    return new Object[][] {
      {false, false}, {false, true}, {true, false}, {true, true},
    };
  }

  @Test(dataProvider = "configs")
  public void testMutableHashBackrefs(boolean codegen, boolean linked) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCodegen(codegen)
            .withAsyncCompilation(false)
            .withCompatible(false)
            .build();

    HashNode parent = newGraph(linked);
    HashNode result = (HashNode) fory.deserialize(fory.serialize(parent));
    if (codegen) {
      assertTrue(fory.getTypeResolver().getSerializer(HashNode.class) instanceof Generated);
    }
    assertGraph(result, linked);
  }

  @Test
  public void testStateResetAfterFailure() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCodegen(false)
            .withCompatible(false)
            .build();
    byte[] bytes = fory.serialize(newGraph(true));
    byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
    assertThrows(RuntimeException.class, () -> fory.deserialize(truncated));
    assertGraph((HashNode) fory.deserialize(bytes), true);
  }

  private static HashNode newGraph(boolean linked) {
    HashNode parent = new HashNode("parent");
    HashNode child = new HashNode("child");
    parent.children.add(child);
    child.parents = linked ? new LinkedHashSet<>() : new HashSet<>();
    child.parentMap = linked ? new LinkedHashMap<>() : new HashMap<>();
    addBackref(child, new HashNode("before"));
    addBackref(child, parent);
    addBackref(child, new HashNode("after"));
    return parent;
  }

  private static void assertGraph(HashNode result, boolean linked) {
    HashNode resultChild = result.children.get(0);
    assertEquals(resultChild.parents.size(), 3);
    assertEquals(resultChild.parentMap.size(), 3);
    assertSame(find(resultChild.parents, "parent"), result);
    assertSame(find(resultChild.parentMap.keySet(), "parent"), result);
    assertTrue(resultChild.parents.contains(result));
    assertEquals(resultChild.parentMap.get(result), "parent-value");
    if (linked) {
      assertEquals(ids(resultChild.parents), Arrays.asList("before", "parent", "after"));
      assertEquals(ids(resultChild.parentMap.keySet()), Arrays.asList("before", "parent", "after"));
    }
  }

  private static void addBackref(HashNode child, HashNode parent) {
    child.parents.add(parent);
    child.parentMap.put(parent, parent.id + "-value");
  }

  private static HashNode find(Collection<HashNode> nodes, String id) {
    for (HashNode node : nodes) {
      if (id.equals(node.id)) {
        return node;
      }
    }
    return null;
  }

  private static List<String> ids(Collection<HashNode> nodes) {
    List<String> ids = new ArrayList<>();
    for (HashNode node : nodes) {
      ids.add(node.id);
    }
    return ids;
  }

  public static class HashNode {
    public List<HashNode> children = new ArrayList<>();
    public String id;
    public Map<HashNode, String> parentMap;
    public Set<HashNode> parents;

    public HashNode() {}

    HashNode(String id) {
      this.id = id;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof HashNode)) {
        return false;
      }
      HashNode hashNode = (HashNode) object;
      return Objects.equals(id, hashNode.id);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(id);
    }
  }
}
