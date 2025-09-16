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

import java.util.Arrays;
import java.util.Random;
import org.apache.fory.Fory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.ArrayCompressionUtils;
import org.apache.fory.util.PrimitiveArrayCompressionType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Array compression benchmark suite using SIMD-accelerated compression from fory-simd.
 *
 * <p><strong>SETUP REQUIRED TO RUN THIS BENCHMARK:</strong>
 *
 * <p>1. This benchmark requires Java 16+ and the fory-simd module. To enable SIMD operations, you
 * must temporarily modify benchmark/pom.xml:
 *
 * <pre>{@code
 * <!-- In the maven-compiler-plugin configuration, change: -->
 * <source>1.8</source>
 * <target>1.8</target>
 * <!-- to: -->
 * <source>17</source>
 * <target>17</target>
 * <compilerArgs>
 *   <arg>--add-modules=jdk.incubator.vector</arg>
 * </compilerArgs>
 *
 * <!-- And add necessary dependencies to the main dependencies section: -->
 * <dependency>
 *   <groupId>org.openjdk.jmh</groupId>
 *   <artifactId>jmh-core</artifactId>
 *   <version>${jmh.version}</version>
 * </dependency>
 * <dependency>
 *   <groupId>org.openjdk.jmh</groupId>
 *   <artifactId>jmh-generator-annprocess</artifactId>
 *   <version>${jmh.version}</version>
 *   <scope>provided</scope>
 * </dependency>
 * <dependency>
 *   <groupId>org.apache.fory</groupId>
 *   <artifactId>fory-simd</artifactId>
 *   <version>${project.version}</version>
 * </dependency>
 * }</pre>
 *
 * <p>2. Build and run the benchmark:
 *
 * <pre>{@code
 * cd benchmark
 * mvn clean compile -Pjmh
 * mvn package -Pjmh -DskipTests
 * java --add-modules=jdk.incubator.vector -jar target/benchmarks.jar ArrayCompressionSuite
 *
 * }</pre>
 *
 * <p>Or run specific benchmarks:
 *
 * <pre>{@code
 * java --add-modules=jdk.incubator.vector -jar target/benchmarks.jar ArrayCompressionSuite.serializeCompressedIntArray -f 1 -wi 3 -i 3
 * }</pre>
 *
 * <p>The benchmark uses automatic registration of compressed serializers when compression flags are
 * enabled. Arrays with values that fit in smaller types (byte-range ints, int-range longs) will be
 * compressed automatically.
 */
@BenchmarkMode(Mode.Throughput)
@CompilerControl(value = CompilerControl.Mode.INLINE)
public class ArrayCompressionSuite {
  @State(Scope.Thread)
  public static class StateClass {

    private Fory foryNormal;
    private Fory foryWithCompression;
    private MemoryBuffer buffer;
    private MemoryBuffer byteRangeNormalIntBuffer;
    private MemoryBuffer byteRangeCompressedIntBuffer;
    private MemoryBuffer largeValueNormalIntBuffer;
    private MemoryBuffer largeValueCompressedIntBuffer;
    private MemoryBuffer intRangeNormalLongBuffer;
    private MemoryBuffer intRangeCompressedLongBuffer;
    private MemoryBuffer largeValueNormalLongBuffer;
    private MemoryBuffer largeValueCompressedLongBuffer;

    @Param({"100", "1000", "10000", "100000", "1000000", "10000000"})
    public int arraySize;

    private int[] byteRangeIntArray;
    private int[] largeValueIntArray;
    private long[] intRangeLongArray;
    private long[] largeValueLongArray;

    @Setup(Level.Trial)
    public void setup() {
      foryNormal = new ForyBuilder().build();
      foryWithCompression =
          new ForyBuilder().withIntArrayCompressed(true).withLongArrayCompressed(true).build();

      // Create different data types for comprehensive comparison
      byteRangeIntArray = createByteRangeArray(arraySize);
      largeValueIntArray = createLargeValueArray(arraySize);
      intRangeLongArray = createIntRangeLongArray(arraySize);
      largeValueLongArray = createLargeLongValueArray(arraySize);

      buffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      byteRangeNormalIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      byteRangeCompressedIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      largeValueNormalIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      largeValueCompressedIntBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      intRangeNormalLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      intRangeCompressedLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      largeValueNormalLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);
      largeValueCompressedLongBuffer = MemoryBuffer.newHeapBuffer(1024 * 1024 * 16);

      // Pre-serialize the arrays for clean deserialization benchmarks
      foryNormal.serialize(byteRangeNormalIntBuffer, byteRangeIntArray);
      byteRangeNormalIntBuffer.readerIndex(0);
      foryWithCompression.serialize(byteRangeCompressedIntBuffer, byteRangeIntArray);
      byteRangeCompressedIntBuffer.readerIndex(0);

      foryNormal.serialize(largeValueNormalIntBuffer, largeValueIntArray);
      largeValueNormalIntBuffer.readerIndex(0);
      foryWithCompression.serialize(largeValueCompressedIntBuffer, largeValueIntArray);
      largeValueCompressedIntBuffer.readerIndex(0);

      foryNormal.serialize(intRangeNormalLongBuffer, intRangeLongArray);
      intRangeNormalLongBuffer.readerIndex(0);
      foryWithCompression.serialize(intRangeCompressedLongBuffer, intRangeLongArray);
      intRangeCompressedLongBuffer.readerIndex(0);

      foryNormal.serialize(largeValueNormalLongBuffer, largeValueLongArray);
      largeValueNormalLongBuffer.readerIndex(0);
      foryWithCompression.serialize(largeValueCompressedLongBuffer, largeValueLongArray);
      largeValueCompressedLongBuffer.readerIndex(0);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*ArrayCompressionSuite.* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    org.openjdk.jmh.Main.main(args);
  }

  /*
  ArrayCompressionSuite.serializeByteRangeIntNormal                           100  thrpt    3   29602071.187 ±   1597560.712  ops/s
  ArrayCompressionSuite.serializeByteRangeIntNormal                          1000  thrpt    3   11742789.678 ±   1882245.907  ops/s
  ArrayCompressionSuite.serializeByteRangeIntNormal                         10000  thrpt    3    1143554.760 ±    244480.588  ops/s
  ArrayCompressionSuite.serializeByteRangeIntNormal                        100000  thrpt    3     108597.967 ±     21637.562  ops/s
  ArrayCompressionSuite.serializeByteRangeIntNormal                       1000000  thrpt    3      11880.067 ±     10340.990  ops/s
  ArrayCompressionSuite.serializeByteRangeIntNormal                      10000000  thrpt    3        758.406 ±        64.640  ops/s
   */
  @Benchmark
  public void serializeByteRangeIntNormal(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.byteRangeIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeByteRangeIntCompressed                       100  thrpt    3   32918103.048 ±   5212495.092  ops/s
  ArrayCompressionSuite.serializeByteRangeIntCompressed                      1000  thrpt    3    2050845.609 ±     49592.836  ops/s
  ArrayCompressionSuite.serializeByteRangeIntCompressed                     10000  thrpt    3     216902.275 ±     55648.714  ops/s
  ArrayCompressionSuite.serializeByteRangeIntCompressed                    100000  thrpt    3      21628.943 ±       789.935  ops/s
  ArrayCompressionSuite.serializeByteRangeIntCompressed                   1000000  thrpt    3       2046.012 ±        24.353  ops/s
  ArrayCompressionSuite.serializeByteRangeIntCompressed                  10000000  thrpt    3        198.314 ±        34.797  ops/s
   */
  @Benchmark
  public void serializeByteRangeIntCompressed(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.byteRangeIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeLargeValueIntNormal                          100  thrpt    3   32757911.585 ±   1454711.833  ops/s
  ArrayCompressionSuite.serializeLargeValueIntNormal                         1000  thrpt    3   12069202.367 ±   2323183.991  ops/s
  ArrayCompressionSuite.serializeLargeValueIntNormal                        10000  thrpt    3    1151918.630 ±    564476.182  ops/s
  ArrayCompressionSuite.serializeLargeValueIntNormal                       100000  thrpt    3     115806.440 ±     23892.650  ops/s
  ArrayCompressionSuite.serializeLargeValueIntNormal                      1000000  thrpt    3      12623.781 ±      6910.146  ops/s
  ArrayCompressionSuite.serializeLargeValueIntNormal                     10000000  thrpt    3        753.061 ±        98.523  ops/s
   */
  @Benchmark
  public void serializeLargeValueIntNormal(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.largeValueIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeLargeValueIntCompressed                      100  thrpt    3   32439920.731 ±    576543.258  ops/s
  ArrayCompressionSuite.serializeLargeValueIntCompressed                     1000  thrpt    3   11727885.248 ±   6012430.397  ops/s
  ArrayCompressionSuite.serializeLargeValueIntCompressed                    10000  thrpt    3    1349047.596 ±   1027823.065  ops/s
  ArrayCompressionSuite.serializeLargeValueIntCompressed                   100000  thrpt    3     108260.883 ±      4273.640  ops/s
  ArrayCompressionSuite.serializeLargeValueIntCompressed                  1000000  thrpt    3      11540.224 ±     22199.498  ops/s
  ArrayCompressionSuite.serializeLargeValueIntCompressed                 10000000  thrpt    3        743.762 ±       354.566  ops/s
       */
  @Benchmark
  public void serializeLargeValueIntCompressed(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.largeValueIntArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeIntRangeLongNormal                           100  thrpt    3   35856174.980 ±   2940059.749  ops/s
  ArrayCompressionSuite.serializeIntRangeLongNormal                          1000  thrpt    3    6352640.656 ±    677343.987  ops/s
  ArrayCompressionSuite.serializeIntRangeLongNormal                         10000  thrpt    3     605496.720 ±    459834.489  ops/s
  ArrayCompressionSuite.serializeIntRangeLongNormal                        100000  thrpt    3      55058.639 ±     17520.755  ops/s
  ArrayCompressionSuite.serializeIntRangeLongNormal                       1000000  thrpt    3       5507.772 ±      5608.844  ops/s
  ArrayCompressionSuite.serializeIntRangeLongNormal                      10000000  thrpt    3        399.846 ±       194.012  ops/s
       */
  @Benchmark
  public void serializeIntRangeLongNormal(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.intRangeLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeIntRangeLongCompressed                       100  thrpt    3   39417029.605 ±   3502679.210  ops/s
  ArrayCompressionSuite.serializeIntRangeLongCompressed                      1000  thrpt    3    1890390.954 ±     92899.303  ops/s
  ArrayCompressionSuite.serializeIntRangeLongCompressed                     10000  thrpt    3     192407.985 ±     10522.303  ops/s
  ArrayCompressionSuite.serializeIntRangeLongCompressed                    100000  thrpt    3      16887.773 ±      3431.113  ops/s
  ArrayCompressionSuite.serializeIntRangeLongCompressed                   1000000  thrpt    3       1664.112 ±       595.077  ops/s
  ArrayCompressionSuite.serializeIntRangeLongCompressed                  10000000  thrpt    3        163.137 ±        80.122  ops/s
       */
  @Benchmark
  public void serializeIntRangeLongCompressed(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.intRangeLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeLargeValueLongNormal                         100  thrpt    3   37021996.235 ±    803166.912  ops/s
  ArrayCompressionSuite.serializeLargeValueLongNormal                        1000  thrpt    3    6291916.449 ±    233013.314  ops/s
  ArrayCompressionSuite.serializeLargeValueLongNormal                       10000  thrpt    3     614744.987 ±    125054.809  ops/s
  ArrayCompressionSuite.serializeLargeValueLongNormal                      100000  thrpt    3      70068.254 ±     18030.859  ops/s
  ArrayCompressionSuite.serializeLargeValueLongNormal                     1000000  thrpt    3       5560.613 ±       189.321  ops/s
  ArrayCompressionSuite.serializeLargeValueLongNormal                    10000000  thrpt    3        424.310 ±        30.087  ops/s
       */
  @Benchmark
  public void serializeLargeValueLongNormal(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryNormal.serialize(state.buffer, state.largeValueLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.serializeLargeValueLongCompressed                     100  thrpt    3   39282552.480 ±   4162378.149  ops/s
  ArrayCompressionSuite.serializeLargeValueLongCompressed                    1000  thrpt    3    6108258.795 ±   1141808.601  ops/s
  ArrayCompressionSuite.serializeLargeValueLongCompressed                   10000  thrpt    3     624971.147 ±    303047.058  ops/s
  ArrayCompressionSuite.serializeLargeValueLongCompressed                  100000  thrpt    3      63606.768 ±     65709.093  ops/s
  ArrayCompressionSuite.serializeLargeValueLongCompressed                 1000000  thrpt    3       5557.365 ±       486.837  ops/s
  ArrayCompressionSuite.serializeLargeValueLongCompressed                10000000  thrpt    3
       */
  @Benchmark
  public void serializeLargeValueLongCompressed(StateClass state, Blackhole bh) {
    state.buffer.writerIndex(0);
    state.foryWithCompression.serialize(state.buffer, state.largeValueLongArray);
    bh.consume(state.buffer.writerIndex());
  }

  /*
  ArrayCompressionSuite.deserializeByteRangeIntNormal                         100  thrpt    3   38718982.173 ±  13492941.481  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntNormal                        1000  thrpt    3    5301090.857 ±   2133038.893  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntNormal                       10000  thrpt    3     638112.542 ±     54962.436  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntNormal                      100000  thrpt    3      71669.607 ±      9459.722  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntNormal                     1000000  thrpt    3       7856.892 ±      7274.736  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntNormal                    10000000  thrpt    3        611.887 ±        52.176  ops/s
     */
  @Benchmark
  public int[] deserializeByteRangeIntNormal(StateClass state) {
    state.byteRangeNormalIntBuffer.readerIndex(0);
    return (int[]) state.foryNormal.deserialize(state.byteRangeNormalIntBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                     100  thrpt    3   38620794.055 ±   8581322.975  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                    1000  thrpt    3    2999138.990 ±   1696271.989  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                   10000  thrpt    3     349900.546 ±      3805.830  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                  100000  thrpt    3      38273.286 ±      1080.966  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                 1000000  thrpt    3       3809.776 ±      1725.630  ops/s
  ArrayCompressionSuite.deserializeByteRangeIntCompressed                10000000  thrpt    3        367.880 ±       126.894  ops/s
     */
  @Benchmark
  public int[] deserializeByteRangeIntCompressed(StateClass state) {
    state.byteRangeCompressedIntBuffer.readerIndex(0);
    return (int[]) state.foryWithCompression.deserialize(state.byteRangeCompressedIntBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeLargeValueIntNormal                        100  thrpt    3   38817747.604 ±   7171399.736  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntNormal                       1000  thrpt    3    5319943.240 ±    804242.127  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntNormal                      10000  thrpt    3     648599.882 ±     54386.169  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntNormal                     100000  thrpt    3      70434.173 ±      5809.869  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntNormal                    1000000  thrpt    3       7344.590 ±      2842.617  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntNormal                   10000000  thrpt    3        600.437 ±        93.775  ops/s
   */
  @Benchmark
  public int[] deserializeLargeValueIntNormal(StateClass state) {
    state.largeValueNormalIntBuffer.readerIndex(0);
    return (int[]) state.foryNormal.deserialize(state.largeValueNormalIntBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                    100  thrpt    3   38768155.660 ±   6944891.763  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                   1000  thrpt    3    5417944.944 ±    575076.272  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                  10000  thrpt    3     642091.997 ±    332992.054  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                 100000  thrpt    3      72072.675 ±     13297.813  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                1000000  thrpt    3       7527.117 ±      1117.752  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed               10000000  thrpt    3        593.527 ±       297.571  ops/s
     */
  @Benchmark
  public int[] deserializeLargeValueIntCompressed(StateClass state) {
    state.largeValueCompressedIntBuffer.readerIndex(0);
    return (int[]) state.foryWithCompression.deserialize(state.largeValueCompressedIntBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeIntRangeLongNormal                         100  thrpt    3   24390646.099 ±  17456823.839  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongNormal                        1000  thrpt    3    1176801.246 ±   3025518.073  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongNormal                       10000  thrpt    3     348640.088 ±     25986.789  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongNormal                      100000  thrpt    3      36542.274 ±      4709.095  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongNormal                     1000000  thrpt    3       3884.386 ±      3124.934  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongNormal                    10000000  thrpt    3        323.969 ±        99.810  ops/s
  ArrayCompressionSuite.deserializeLargeValueIntCompressed                    100  thrpt    3   38768155.660 ±   6944891.763  ops/s
   */
  @Benchmark
  public long[] deserializeIntRangeLongNormal(StateClass state) {
    state.intRangeNormalLongBuffer.readerIndex(0);
    return (long[]) state.foryNormal.deserialize(state.intRangeNormalLongBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                     100  thrpt    3   25244356.365 ±   3638012.299  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                    1000  thrpt    3    1262995.909 ±    112996.170  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                   10000  thrpt    3     228776.683 ±     59892.574  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                  100000  thrpt    3      24446.994 ±      3781.033  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                 1000000  thrpt    3       2446.349 ±      1108.778  ops/s
  ArrayCompressionSuite.deserializeIntRangeLongCompressed                10000000  thrpt    3        226.659 ±        36.682  ops/s
     */
  @Benchmark
  public long[] deserializeIntRangeLongCompressed(StateClass state) {
    state.intRangeCompressedLongBuffer.readerIndex(0);
    return (long[]) state.foryWithCompression.deserialize(state.intRangeCompressedLongBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeLargeValueLongNormal                       100  thrpt    3   25203610.194 ±   3512528.220  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongNormal                      1000  thrpt    3    1616449.120 ±   2858937.525  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongNormal                     10000  thrpt    3     369299.874 ±    127905.781  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongNormal                    100000  thrpt    3      36715.513 ±     11370.163  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongNormal                   1000000  thrpt    3       3844.142 ±      2644.324  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongNormal                  10000000  thrpt    3        316.091 ±        91.883  ops/s
     */
  @Benchmark
  public long[] deserializeLargeValueLongNormal(StateClass state) {
    state.largeValueNormalLongBuffer.readerIndex(0);
    return (long[]) state.foryNormal.deserialize(state.largeValueNormalLongBuffer);
  }

  /*
  ArrayCompressionSuite.deserializeLargeValueLongCompressed                   100  thrpt    3   25195571.464 ±   4392555.267  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongCompressed                  1000  thrpt    3    1369769.388 ±   8148348.769  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongCompressed                 10000  thrpt    3     361966.547 ±    233139.929  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongCompressed                100000  thrpt    3      36292.654 ±     19122.390  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongCompressed               1000000  thrpt    3       3742.499 ±      1423.498  ops/s
  ArrayCompressionSuite.deserializeLargeValueLongCompressed              10000000  thrpt    3        321.581 ±        14.930  ops/s
     */
  @Benchmark
  public long[] deserializeLargeValueLongCompressed(StateClass state) {
    state.largeValueCompressedLongBuffer.readerIndex(0);
    return (long[]) state.foryWithCompression.deserialize(state.largeValueCompressedLongBuffer);
  }

  /*
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD              100  thrpt    3  352298791.430 ± 130932357.283  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD             1000  thrpt    3    3061069.812 ±    504920.924  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD            10000  thrpt    3     309417.284 ±     26959.332  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD           100000  thrpt    3      31240.034 ±      4255.560  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD          1000000  thrpt    3       3855.777 ±        38.621  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeSIMD         10000000  thrpt    3        375.767 ±       161.507  ops/s
     */
  @Benchmark
  public void determineByteRangeIntCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineIntCompressionType(state.byteRangeIntArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD             100  thrpt    3  357759238.753 ±   9351631.283  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD            1000  thrpt    3  300829367.086 ±  16523263.386  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD           10000  thrpt    3  298194807.033 ±  32927964.184  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD          100000  thrpt    3  300859302.157 ±  11938296.644  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD         1000000  thrpt    3  209575350.466 ±  44287039.863  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeSIMD        10000000  thrpt    3  209347057.124 ±   8935239.920  ops/s
     */
  @Benchmark
  public void determineLargeValueIntCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineIntCompressionType(state.largeValueIntArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD              100  thrpt    3  344847094.451 ± 103782454.907  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD             1000  thrpt    3    2611247.708 ±   3461604.934  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD            10000  thrpt    3     276600.137 ±     15792.393  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD           100000  thrpt    3      27365.608 ±      5194.743  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD          1000000  thrpt    3       3996.440 ±       615.246  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeSIMD         10000000  thrpt    3        378.753 ±       214.763  ops/s
     */
  @Benchmark
  public void determineIntRangeLongCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineLongCompressionType(state.intRangeLongArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD            100  thrpt    3  337030539.592 ± 142675783.525  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD           1000  thrpt    3  283212053.694 ±  34329718.391  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD          10000  thrpt    3  284672085.283 ±   8534528.439  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD         100000  thrpt    3  285633455.967 ±  22111813.855  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD        1000000  thrpt    3  216671985.713 ±  39698935.278  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeSIMD       10000000  thrpt    3  211377707.797 ± 226281881.435  ops/s
   */
  @Benchmark
  public void determineLargeValueLongCompressionTypeSIMD(StateClass state, Blackhole bh) {
    PrimitiveArrayCompressionType compressionType =
        ArrayCompressionUtils.determineLongCompressionType(state.largeValueLongArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar            100  thrpt    3   24129243.801 ±  27062240.105  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar           1000  thrpt    3    2515019.753 ±   1104092.577  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar          10000  thrpt    3     267737.347 ±     65703.684  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar         100000  thrpt    3      27050.296 ±     11725.414  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar        1000000  thrpt    3       2545.921 ±      2681.051  ops/s
  ArrayCompressionSuite.determineByteRangeIntCompressionTypeScalar       10000000  thrpt    3        258.926 ±       242.088  ops/s
       */
  @Benchmark
  public void determineByteRangeIntCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineIntCompressionTypeScalar(state.byteRangeIntArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar           100  thrpt    3  400974073.844 ±  51500926.041  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar          1000  thrpt    3  397432676.355 ± 167708237.772  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar         10000  thrpt    3  398595991.456 ± 189940151.254  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar        100000  thrpt    3  403183806.569 ±  66724093.563  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar       1000000  thrpt    3  380726495.408 ±  42496130.309  ops/s
  ArrayCompressionSuite.determineLargeValueIntCompressionTypeScalar      10000000  thrpt    3  406496045.780 ±  30380052.735  ops/s
     */
  @Benchmark
  public void determineLargeValueIntCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineIntCompressionTypeScalar(state.largeValueIntArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar            100  thrpt    3   27545961.378 ±    837734.932  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar           1000  thrpt    3    2713084.830 ±   3386310.166  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar          10000  thrpt    3     294822.359 ±     45955.350  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar         100000  thrpt    3      29725.822 ±      4929.759  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar        1000000  thrpt    3       2909.798 ±       670.229  ops/s
  ArrayCompressionSuite.determineIntRangeLongCompressionTypeScalar       10000000  thrpt    3        293.011 ±         5.516  ops/s
     */
  @Benchmark
  public void determineIntRangeLongCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineLongCompressionTypeScalar(state.intRangeLongArray);
    bh.consume(compressionType);
  }

  /*
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar          100  thrpt    3  451577331.603 ±  39679470.634  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar         1000  thrpt    3  451663223.431 ±  63816327.062  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar        10000  thrpt    3  451568350.127 ±  29393462.323  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar       100000  thrpt    3  437657352.514 ±  51110893.891  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar      1000000  thrpt    3  418498410.054 ± 111064379.501  ops/s
  ArrayCompressionSuite.determineLargeValueLongCompressionTypeScalar     10000000  thrpt    3  448778689.753 ±  14440424.992  ops/s
     */
  @Benchmark
  public void determineLargeValueLongCompressionTypeScalar(StateClass state, Blackhole bh) {
    int compressionType = determineLongCompressionTypeScalar(state.largeValueLongArray);
    bh.consume(compressionType);
  }

  // Helper methods to create test data
  private static final int[] createByteRangeArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(200) - 100; // -100 to 99 (byte range)
    }
    return array;
  }

  private static final int[] createSortedArray(int size) {
    int[] array = createByteRangeArray(size);
    Arrays.sort(array);
    return array;
  }

  private static final int[] createZeroArray(int size) {
    return new int[size]; // all zeros
  }

  private static final int[] createSequentialArray(int size) {
    int[] array = new int[size];
    for (int i = 0; i < size; i++) {
      array[i] = i % 256; // 0-255 repeating
    }
    return array;
  }

  private static final int[] createLargeValueArray(int size) {
    int[] array = new int[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt();
    }
    return array;
  }

  private static final long[] createIntRangeLongArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextInt(); // int range values
    }
    return array;
  }

  private static final long[] createLargeLongValueArray(int size) {
    long[] array = new long[size];
    Random random = new Random(42);
    for (int i = 0; i < size; i++) {
      array[i] = random.nextLong();
    }
    return array;
  }

  private static int determineIntCompressionTypeScalar(int[] array) {
    boolean canCompressToByte = true;
    boolean canCompressToShort = true;

    for (int i = 0; i < array.length && (canCompressToByte || canCompressToShort); i++) {
      int value = array[i];
      if (canCompressToByte && (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE)) {
        canCompressToByte = false;
      }
      if (value > Short.MAX_VALUE || value < Short.MIN_VALUE) {
        canCompressToShort = false;
      }
    }

    if (canCompressToByte) {
      return 1;
    }
    if (canCompressToShort) {
      return 2;
    }
    return 0;
  }

  private static int determineLongCompressionTypeScalar(long[] array) {
    boolean canCompressToInt = true;
    for (long value : array) {
      if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
        canCompressToInt = false;
        break;
      }
    }
    return canCompressToInt ? 3 : 0;
  }

  /** Compress int array to byte array. */
  public static byte[] compressToBytes(int[] array) {
    byte[] compressed = new byte[array.length];
    for (int i = 0; i < array.length; i++) {
      compressed[i] = (byte) array[i];
    }
    return compressed;
  }
}
