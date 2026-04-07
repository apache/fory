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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.Data;
import org.testng.annotations.Test;

public class IdentityObjectIntMapTest {

  @Test
  public void testPutOrGetProfiling() {
    List<Integer> numObjs = ImmutableList.of(100, 500, 1000, 5000, 10000, 100000, 1000000);
    List<Float> loadActors = ImmutableList.of(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.90f);
    loadActors.forEach(
        loadActor -> {
          List<Tuple3<Integer, Integer, Double>> profiled =
              numObjs.stream()
                  .map(
                      n -> {
                        IdentityObjectIntMap<Object> map = new IdentityObjectIntMap<>(8, loadActor);
                        // resize map to target size to simulate the reference writer
                        for (int i = 0; i < n; i++) {
                          map.profilingPutOrGet(new Object(), -1);
                        }
                        map.clear();
                        map.getAndResetStatistics();
                        for (int i = 0; i < n; i++) {
                          map.profilingPutOrGet(new Object(), -1);
                        }
                        MapStatistics stat = map.getAndResetStatistics();
                        Tuple3<Integer, Integer, Double> profile =
                            Tuple3.of(n, stat.maxProbeProfiled, stat.totalProbeProfiled * 1.0 / n);
                        map.getAndResetStatistics();
                        return profile;
                      })
                  .collect(Collectors.toList());
          double maxCollision =
              -profiled.stream().mapToDouble(t -> -t.f2).sorted().findFirst().getAsDouble();
          System.out.printf(
              "Hash table[%f] max average collision %4f profile %s\n",
              loadActor, maxCollision, profiled);
        });
  }

  @Data
  private static class Stat {
    final float loadActor;
    final int numObjs;
    final long tps;
  }

  // @Test
  public void selectLoadFactor() {
    // If load factor is too small, memory access will be slow when the table is big,
    // so we need to select load factor dynamically.
    List<Float> loadActors = ImmutableList.of(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.90f);
    TreeSet<Stat> stats =
        new TreeSet<>(
            (o1, o2) -> {
              if (Objects.equals(o1.numObjs, o2.numObjs)) {
                if (Objects.equals(o1.tps, o2.tps)) {
                  return (int) (o1.loadActor - o2.loadActor);
                } else {
                  return (int) (o2.tps - o1.tps);
                }
              } else {
                return o1.numObjs - o2.numObjs;
              }
            });
    long executionDuration = 1_000_000_000L;
    for (int i = 1; i < 6; i++) {
      int numObjs = (int) Math.pow(10, i);
      for (Float loadActor : loadActors) {
        List<Object> objs = new ArrayList<>();
        for (int j = 0; j < numObjs; j++) {
          objs.add(new Object());
        }
        IdentityObjectIntMap<Object> map = new IdentityObjectIntMap<>(8, loadActor);
        // resize map to target size to simulate the reference writer
        for (Object obj : objs) {
          map.profilingPutOrGet(obj, -1);
        }
        map.clear();
        long tps = 0;
        long start = System.nanoTime();
        while (System.nanoTime() - start < executionDuration) {
          for (Object obj : objs) {
            map.profilingPutOrGet(obj, -1);
            tps++;
          }
          map.clear();
        }
        System.out.printf("Hash table[%f] total objects %s tps %s\n", loadActor, numObjs, tps);
        stats.add(new Stat(loadActor, numObjs, tps));
      }
      System.out.println(stats);
      stats.clear();
    }
  }
}
