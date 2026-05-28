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

package org.apache.fory.format.perf;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.fory.format.encoder.ArrayEncoder;
import org.apache.fory.format.encoder.BaseCodecBuilder;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.MapEncoder;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.reflect.TypeRef;

/**
 * Standalone allocation probe for nested row-format read paths. Uses
 * {@link com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes()} to measure bytes
 * allocated per decode op, isolating the per-element waste hidden inside nested struct/array/map
 * paths.
 *
 * <p>Run with: {@code java -cp <classpath> org.apache.fory.format.perf.RowFormatAllocationProbe}
 *
 * <p>Output columns: scenario, format, bytes/op (mean over {@link #ITERATIONS} iterations),
 * bytes/op (post-warmup).
 */
public final class RowFormatAllocationProbe {

  private static final int LEAF_COUNT = 32;
  private static final int MAP_ENTRIES = 16;
  private static final int MATRIX_ROWS = 8;
  private static final int WARMUP = 1_000;
  private static final int ITERATIONS = 10_000;

  // -------------------- Beans --------------------

  @Data
  public static class Leaf {
    private long a;
    private long b;
    private int c;
    private String d;
  }

  @Data
  public static class Branch {
    private Leaf leaf;
    private List<Leaf> leaves;
  }

  @Data
  public static class Root {
    private long id;
    private Branch branch;
    private List<Leaf> leaves;
    private Map<String, Leaf> table;
    private List<List<Leaf>> matrix;
  }

  // -------------------- Test data --------------------

  private static Leaf leaf(int seed) {
    Leaf l = new Leaf();
    l.setA(seed);
    l.setB(seed * 31L);
    l.setC(seed);
    l.setD("leaf-" + seed);
    return l;
  }

  private static List<Leaf> leaves(int n, int seed) {
    List<Leaf> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(leaf(seed + i));
    }
    return out;
  }

  private static Branch branch(int seed) {
    Branch b = new Branch();
    b.setLeaf(leaf(seed));
    b.setLeaves(leaves(LEAF_COUNT, seed));
    return b;
  }

  private static Root buildRoot() {
    Root r = new Root();
    r.setId(7);
    r.setBranch(branch(100));
    r.setLeaves(leaves(LEAF_COUNT, 200));
    Map<String, Leaf> table = new HashMap<>();
    for (int i = 0; i < MAP_ENTRIES; i++) {
      table.put("k" + i, leaf(300 + i));
    }
    r.setTable(table);
    List<List<Leaf>> matrix = new ArrayList<>();
    for (int i = 0; i < MATRIX_ROWS; i++) {
      matrix.add(leaves(LEAF_COUNT, 400 + i * LEAF_COUNT));
    }
    r.setMatrix(matrix);
    return r;
  }

  // -------------------- Probe --------------------

  private static final ThreadMXBean BEAN = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private static long measure(Runnable op) {
    // Warm up.
    for (int i = 0; i < WARMUP; i++) {
      op.run();
    }
    // Measure: average bytes per iteration.
    long before = BEAN.getCurrentThreadAllocatedBytes();
    for (int i = 0; i < ITERATIONS; i++) {
      op.run();
    }
    long after = BEAN.getCurrentThreadAllocatedBytes();
    return (after - before) / ITERATIONS;
  }

  // -------------------- Scenarios --------------------

  private static <B extends BaseCodecBuilder<B>> B configure(B b, boolean compact) {
    if (compact) {
      b.compactEncoding();
    }
    return b;
  }

  private static void run(String label, boolean compact) {
    RowEncoder<Root> rootCodec = configure(Encoders.buildBeanCodec(Root.class), compact).build().get();
    ArrayEncoder<List<Leaf>> arrayCodec =
        configure(Encoders.buildArrayCodec(new TypeRef<List<Leaf>>() {}), compact).build().get();
    ArrayEncoder<List<List<Leaf>>> matrixCodec =
        configure(Encoders.buildArrayCodec(new TypeRef<List<List<Leaf>>>() {}), compact)
            .build()
            .get();
    MapEncoder<Map<String, Leaf>> mapCodec =
        configure(Encoders.buildMapCodec(new TypeRef<Map<String, Leaf>>() {}), compact)
            .build()
            .get();

    Root r = buildRoot();
    byte[] rootBytes = rootCodec.encode(r);
    byte[] arrayBytes = arrayCodec.encode(r.getLeaves());
    byte[] matrixBytes = matrixCodec.encode(r.getMatrix());
    byte[] mapBytes = mapCodec.encode(r.getTable());

    // For each scenario, also fully traverse the result so lazy paths actually fire.
    long rootAlloc =
        measure(
            () -> {
              Root out = rootCodec.decode(rootBytes);
              touchRoot(out);
            });
    long arrayAlloc =
        measure(
            () -> {
              List<Leaf> out = arrayCodec.decode(arrayBytes);
              touchLeaves(out);
            });
    long matrixAlloc =
        measure(
            () -> {
              List<List<Leaf>> out = matrixCodec.decode(matrixBytes);
              for (List<Leaf> row : out) {
                touchLeaves(row);
              }
            });
    long mapAlloc =
        measure(
            () -> {
              Map<String, Leaf> out = mapCodec.decode(mapBytes);
              for (Leaf leaf : out.values()) {
                touch(leaf);
              }
            });

    System.out.printf(
        "%-9s root=%-7d array=%-7d matrix=%-7d map=%-7d  (bytes/op)%n",
        label, rootAlloc, arrayAlloc, matrixAlloc, mapAlloc);
  }

  private static long sink;

  private static void touch(Leaf l) {
    sink += l.getA() + l.getB() + l.getC() + l.getD().length();
  }

  private static void touchLeaves(List<Leaf> ls) {
    for (Leaf l : ls) {
      touch(l);
    }
  }

  private static void touchRoot(Root r) {
    sink += r.getId();
    if (r.getBranch() != null) {
      touch(r.getBranch().getLeaf());
      touchLeaves(r.getBranch().getLeaves());
    }
    touchLeaves(r.getLeaves());
    for (Leaf l : r.getTable().values()) {
      touch(l);
    }
    for (List<Leaf> row : r.getMatrix()) {
      touchLeaves(row);
    }
  }

  public static void main(String[] args) {
    run("standard", false);
    run("compact ", true);
  }
}
