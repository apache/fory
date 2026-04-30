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

import java.io.Serializable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.testng.annotations.Test;

/** Regression test for codegen CompileException when map key/value types are package-private. */
public class PackagePrivateMapKeyTest {

  @Test
  public void testCodegenForMapWithPackagePrivateEnumKey() {
    ThreadSafeFory fury =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .buildThreadSafeFory();

    ReproContainer container = new ReproContainer("v1");
    ReproNode parent = new ReproNode(ReproType.TYPE_A, "p1");
    ReproNode child = new ReproNode(ReproType.TYPE_B, "c1");
    parent.children.add(child);
    child.parents.computeIfAbsent(parent.type, k -> new LinkedHashSet<>()).add(parent);
    container.nodes.computeIfAbsent(ReproType.TYPE_A, k -> new HashMap<>()).put("p1", parent);
    container.nodes.computeIfAbsent(ReproType.TYPE_B, k -> new HashMap<>()).put("c1", child);

    byte[] bytes = fury.serialize(container);
    ReproContainer result = (ReproContainer) fury.deserialize(bytes);
    assertEquals(result.version, container.version);
    assertEquals(result.nodes.size(), container.nodes.size());
  }
}

// All package-private — this triggers the bug
enum ReproType implements Serializable {
  TYPE_A,
  TYPE_B
}

class ReproNode implements Serializable {
  final ReproType type;
  final String id;
  final Set<ReproNode> children = new HashSet<>();
  final Map<ReproType, Set<ReproNode>> parents = new EnumMap<>(ReproType.class);

  ReproNode(ReproType type, String id) {
    this.type = type;
    this.id = id;
  }
}

class ReproContainer implements Serializable {
  final Map<ReproType, Map<String, ReproNode>> nodes = new EnumMap<>(ReproType.class);
  final String version;

  ReproContainer(String version) {
    this.version = version;
  }
}
