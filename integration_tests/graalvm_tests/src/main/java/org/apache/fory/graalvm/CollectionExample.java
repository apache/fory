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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.Fory;
import org.apache.fory.util.Preconditions;

public class CollectionExample {
  static Fory fory;

  static {
    fory =
        Fory.builder()
            .withName(CollectionExample.class.getName())
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
  }

  static void test(Fory fory) {
    final Map<String, String> unmodifiableMap = Map.of("k1", "v1", "k2", "v2");
    Preconditions.checkArgument(
        unmodifiableMap.equals(fory.deserialize(fory.serialize(unmodifiableMap))));
    System.out.println(unmodifiableMap);
    final List<Integer> arrayasList = Arrays.asList(1, 2, 3);
    Preconditions.checkArgument(arrayasList.equals(fory.deserialize(fory.serialize(arrayasList))));
    System.out.println(arrayasList);
    final Set<String> setFromMap = Collections.newSetFromMap(new ConcurrentHashMap<>());
    setFromMap.add("a");
    setFromMap.add("b");
    setFromMap.add("c");
    Preconditions.checkArgument(setFromMap.equals(fory.deserialize(fory.serialize(setFromMap))));
    System.out.println(setFromMap);
  }

  public static void main(String[] args) {
    test(fory);
    System.out.println("Collection succeed");
  }
}
