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

package org.apache.fory.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.fory.memory.MemoryBuffer;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

public class ArraySuite {

  @State(Scope.Thread)
  public static class ArrayState {
    @Param({"8", "16", "32", "64", "128", "256"})
    public int arraySize;

    public Object[] objects;
    public Object[] nilArray;
    public int[] ints;

    @Setup(Level.Trial)
    public void setup() {
      objects = new Object[arraySize];
      nilArray = new Object[arraySize];
      ints = new int[arraySize];
    }
  }

  // @Benchmark
  public Object clearObjectArray(ArrayState state) {
    Arrays.fill(state.objects, null);
    return state.objects;
  }

  // @Benchmark
  public Object clearObjectArrayByCopy(ArrayState state) {
    System.arraycopy(state.nilArray, 0, state.objects, 0, state.objects.length);
    return state.objects;
  }

  // @Benchmark
  public Object clearIntArray(ArrayState state) {
    Arrays.fill(state.ints, 0);
    return state.ints;
  }

  private static Integer[] array = new Integer[100];
  private static List<Integer> list = new ArrayList<>(100);

  private static MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);

  static {
    Random random = new Random(7);
    for (int i = 0; i < 100; i++) {
      int x = random.nextInt();
      array[i] = x;
      list.add(i, x);
    }
  }

  // Benchmark                  Mode  Cnt         Score          Error  Units
  // ArraySuite.iterateArray   thrpt    3  18107614.727 ± 25969433.513  ops/s
  // ArraySuite.iterateList    thrpt    3   9448162.588 ± 13139664.082  ops/s
  // ArraySuite.iterateList2   thrpt    3  14678631.109 ± 14579521.954  ops/s
  // ArraySuite.serializeList  thrpt    3   1659718.571 ±  1323226.629  ops/s
  @Benchmark
  public Object iterateArray() {
    int count = 0;
    for (Integer o : array) {
      if (o != null) {
        count += o;
      }
    }
    return count;
  }

  @Benchmark
  public Object iterateList() {
    int count = 0;
    for (Integer o : list) {
      if (o != null) {
        count += o;
      }
    }
    return count;
  }

  @Benchmark
  public Object iterateList2() {
    int count = 0;
    int size = list.size();
    for (int i = 0; i < size; i++) {
      Integer o = list.get(i);
      if (o != null) {
        count += o;
      }
    }
    return count;
  }

  @Benchmark
  public Object serializeList() {
    buffer.writerIndex(0);
    int size = list.size();
    for (int i = 0; i < size; i++) {
      Integer o = list.get(i);
      if (o != null) {
        buffer.writeVarInt32(o);
      }
    }
    return buffer;
  }

  // Mac Monterey 12.1: 2.6 GHz 6-Core Intel Core i7
  // JDK11
  // Benchmark                    (arraySize)   Mode  Cnt          Score          Error  Units
  // ArraySuite.clearIntArray               8  thrpt    9  108245693.506 ± 31943663.751  ops/s
  // ArraySuite.clearIntArray              16  thrpt    9  133361152.581 ± 25767267.552  ops/s
  // ArraySuite.clearIntArray              32  thrpt    9  114098878.909 ± 17547904.751  ops/s
  // ArraySuite.clearIntArray              64  thrpt    9   61982737.749 ± 16238111.538  ops/s
  // ArraySuite.clearIntArray             128  thrpt    9   69106119.394 ± 27190371.964  ops/s
  // ArraySuite.clearIntArray             512  thrpt    9   39273813.015 ±  7020725.000  ops/s
  // ArraySuite.clearIntArray            1024  thrpt    9   25128501.528 ±  1101091.043  ops/s
  // ArraySuite.clearObjectArray            8  thrpt    9  141504887.622 ± 13722488.675  ops/s
  // ArraySuite.clearObjectArray           16  thrpt    9   80232996.715 ± 35342157.737  ops/s
  // ArraySuite.clearObjectArray           32  thrpt    9   52120089.617 ± 22309152.826  ops/s
  // ArraySuite.clearObjectArray           64  thrpt    9   44032559.198 ±  3943121.169  ops/s
  // ArraySuite.clearObjectArray          128  thrpt    9   23546200.462 ±  3540066.627  ops/s
  // ArraySuite.clearObjectArray          512  thrpt    9    6979026.904 ±   217231.973  ops/s
  // ArraySuite.clearObjectArray         1024  thrpt    9    2347842.280 ±   287771.987  ops/s
  // ArraySuite.clearObjectArray                           8  thrpt    3  126937536.719 ±
  // 87908174.123  ops/s
  // ArraySuite.clearObjectArray                          16  thrpt    3   78874032.149 ±
  // 58772866.701  ops/s
  // ArraySuite.clearObjectArray                          32  thrpt    3   75385480.230 ±
  // 7448025.333  ops/s
  // ArraySuite.clearObjectArray                          64  thrpt    3   28410654.424 ±
  // 12096440.919  ops/s
  // ArraySuite.clearObjectArray                         128  thrpt    3   15789870.810 ±
  // 3382065.705  ops/s
  // ArraySuite.clearObjectArray                         512  thrpt    3    5679367.410 ±
  // 23524265.061  ops/s
  // ArraySuite.clearObjectArray                        1024  thrpt    3    2131032.796 ±
  // 4982755.358  ops/s
  // ArraySuite.clearObjectArrayByCopy           8  thrpt    3   56514728.690 ±  92260332.590  ops/s
  // ArraySuite.clearObjectArrayByCopy          16  thrpt    3   60330519.734 ±  48781963.636  ops/s
  // ArraySuite.clearObjectArrayByCopy          32  thrpt    3   46331928.543 ± 129029354.648  ops/s
  // ArraySuite.clearObjectArrayByCopy          64  thrpt    3   39586061.694 ± 127893598.941  ops/s
  // ArraySuite.clearObjectArrayByCopy         128  thrpt    3   26458021.777 ±   3465299.218  ops/s
  // ArraySuite.clearObjectArrayByCopy         512  thrpt    3   16208003.559 ±   4289713.125  ops/s
  // ArraySuite.clearObjectArrayByCopy        1024  thrpt    3   15714055.680 ±   9487632.001  ops/s

  // JDK8
  // Benchmark                    (arraySize)   Mode  Cnt          Score           Error  Units
  // ArraySuite.clearIntArray               8  thrpt    3  184196602.309 ± 135883212.630  ops/s
  // ArraySuite.clearIntArray              16  thrpt    3  179154932.373 ±  78623836.346  ops/s
  // ArraySuite.clearIntArray              32  thrpt    3  161215474.189 ± 392680203.745  ops/s
  // ArraySuite.clearIntArray              64  thrpt    3  151780017.048 ± 273393027.985  ops/s
  // ArraySuite.clearIntArray             128  thrpt    3  118506816.763 ± 232511327.498  ops/s
  // ArraySuite.clearIntArray             512  thrpt    3   27978161.664 ±   1171250.242  ops/s
  // ArraySuite.clearIntArray            1024  thrpt    3    9765041.671 ±  14125017.113  ops/s
  // ArraySuite.clearObjectArray            8  thrpt    3  106495482.405 ±  32207493.878  ops/s
  // ArraySuite.clearObjectArray           16  thrpt    3   91765763.414 ±   7565415.370  ops/s
  // ArraySuite.clearObjectArray           32  thrpt    3   74474726.883 ± 359533525.186  ops/s
  // ArraySuite.clearObjectArray           64  thrpt    3   47232915.214 ±  65199088.751  ops/s
  // ArraySuite.clearObjectArray          128  thrpt    3   20230618.934 ±  71573496.408  ops/s
  // ArraySuite.clearObjectArray          512  thrpt    3    6900937.984 ±  10894836.446  ops/s
  // ArraySuite.clearObjectArray         1024  thrpt    3    3752466.324 ±    991721.321  ops/s

  // JDK17
  // Benchmark                          (arraySize)   Mode  Cnt          Score           Error
  // Units
  // ArraySuite.clearIntArray                     8  thrpt    3  117381839.799 ± 160910338.855
  // ops/s
  // ArraySuite.clearIntArray                    16  thrpt    3  121886476.346 ±  58189914.429
  // ops/s
  // ArraySuite.clearIntArray                    32  thrpt    3  112453900.762 ±  49830904.623
  // ops/s
  // ArraySuite.clearIntArray                    64  thrpt    3   86973270.450 ±  79454852.864
  // ops/s
  // ArraySuite.clearIntArray                   128  thrpt    3   81387937.279 ± 119832259.155
  // ops/s
  // ArraySuite.clearIntArray                   256  thrpt    3   58873055.712 ±  36505775.521
  // ops/s
  // ArraySuite.clearObjectArray                  8  thrpt    3  136078887.308 ± 155112970.108
  // ops/s
  // ArraySuite.clearObjectArray                 16  thrpt    3   96243882.790 ±  99141935.034
  // ops/s
  // ArraySuite.clearObjectArray                 32  thrpt    3   62948878.977 ± 104425605.741
  // ops/s
  // ArraySuite.clearObjectArray                 64  thrpt    3   37872401.275 ± 107503188.763
  // ops/s
  // ArraySuite.clearObjectArray                128  thrpt    3   25658650.505 ±   7381928.566
  // ops/s
  // ArraySuite.clearObjectArray                256  thrpt    3   12788575.608 ±  12311281.411
  // ops/s
  // ArraySuite.clearObjectArrayByCopy            8  thrpt    3   73657099.785 ±  50192064.975
  // ops/s
  // ArraySuite.clearObjectArrayByCopy           16  thrpt    3   70759397.208 ±  22754349.469
  // ops/s
  // ArraySuite.clearObjectArrayByCopy           32  thrpt    3   67617869.635 ±  18863856.428
  // ops/s
  // ArraySuite.clearObjectArrayByCopy           64  thrpt    3   53018672.146 ±  47624304.702
  // ops/s
  // ArraySuite.clearObjectArrayByCopy          128  thrpt    3   49225779.076 ±  24662863.292
  // ops/s
  // ArraySuite.clearObjectArrayByCopy          256  thrpt    3   34678853.735 ±  51058860.670
  // ops/s
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine = "org.apache.fory.*ArraySuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
