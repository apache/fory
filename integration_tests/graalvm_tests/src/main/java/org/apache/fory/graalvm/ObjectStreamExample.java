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

package org.apache.fory.graalvm;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.fory.Fory;

public class ObjectStreamExample extends AbstractMap<Integer, Integer> {
  private static final Fory FORY =
      Fory.builder()
          .withName(ObjectStreamExample.class.getName())
          .registerGuavaTypes(false)
          .build();

  static {
    FORY.register(ObjectStreamExample.class, true);
    FORY.ensureSerializersCompiled();
  }

  final int[] ints = new int[10];

  public static void main(String[] args) {
    FORY.reset();
    byte[] bytes = FORY.serialize(new ObjectStreamExample());
    FORY.reset();
    ObjectStreamExample o = (ObjectStreamExample) FORY.deserialize(bytes);
    System.out.println(Arrays.toString(o.ints));
  }

  @Override
  public Set<Entry<Integer, Integer>> entrySet() {
    HashSet<Entry<Integer, Integer>> set = new HashSet<>();
    for (int i = 0; i < ints.length; i++) {
      set.add(new AbstractMap.SimpleEntry<>(i, ints[i]));
    }
    return set;
  }
}
