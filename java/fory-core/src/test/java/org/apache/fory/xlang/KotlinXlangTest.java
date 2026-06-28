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

package org.apache.fory.xlang;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.annotation.UInt16Type;
import org.apache.fory.annotation.UInt32Type;
import org.apache.fory.annotation.UInt64Type;
import org.apache.fory.annotation.UInt8Type;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.test.TestUtils;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Executes Java-driven Kotlin xlang/static generated serializer tests. */
@Test
public class KotlinXlangTest extends XlangTestBase {
  private static final String STATIC_SERIALIZER_CASE = "static_serializer_round_trip";
  private static final String DENSE_ARRAY_CASE = "dense_array_round_trip";
  private static final String UNSIGNED_COLLECTION_CASE = "unsigned_collection_round_trip";
  private static final File KOTLIN_DIR = new File("../../kotlin");
  private static final File PEER_JAR =
      new File(KOTLIN_DIR, "fory-kotlin-tests/target/fory-kotlin-tests-xlang-peer.jar");

  @BeforeMethod(alwaysRun = true)
  public void skipInheritedXlangCases(Method method) {
    if (method.getDeclaringClass() != KotlinXlangTest.class) {
      throw new SkipException(
          "Kotlin xlang phase 1 only provides the static generated serializer peer case");
    }
  }

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_KOTLIN_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping KotlinXlangTest: FORY_KOTLIN_JAVA_CI not set to 1");
    }
    List<String> installProcessorDependencies =
        Arrays.asList(
            "mvn",
            "--no-transfer-progress",
            "-pl",
            "fory-kotlin,fory-kotlin-ksp",
            "-am",
            "-DskipTests",
            "install");
    boolean installSuccess =
        TestUtils.executeCommand(
            installProcessorDependencies, 300, Collections.emptyMap(), KOTLIN_DIR);
    if (!installSuccess) {
      throw new AssertionError("Failed to install Kotlin xlang processor dependencies");
    }
    List<String> buildCommand =
        Arrays.asList("mvn", "--no-transfer-progress", "-DskipTests", "clean", "package");
    boolean buildSuccess =
        TestUtils.executeCommand(
            buildCommand, 180, Collections.emptyMap(), new File(KOTLIN_DIR, "fory-kotlin-tests"));
    if (!buildSuccess || !PEER_JAR.exists()) {
      throw new AssertionError("Failed to build Kotlin xlang peer jar");
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) {
    List<String> command = new ArrayList<>();
    command.add("java");
    command.add("-jar");
    command.add(PEER_JAR.getAbsolutePath());
    command.add(caseName);
    command.add(dataFile.toAbsolutePath().toString());
    return new CommandContext(command, envBuilder(dataFile), KOTLIN_DIR);
  }

  @Override
  protected ExecutionContext prepareExecution(String caseName, byte[] payload) throws IOException {
    if (!STATIC_SERIALIZER_CASE.equals(caseName)
        && !DENSE_ARRAY_CASE.equals(caseName)
        && !UNSIGNED_COLLECTION_CASE.equals(caseName)) {
      throw new SkipException(
          "Kotlin xlang phase 1 only provides static generated serializer peer cases");
    }
    return super.prepareExecution(caseName, payload);
  }

  @Test(groups = "xlang")
  public void testStaticGeneratedSerializer() throws IOException {
    Fory fory = newFory();
    fory.register(KotlinUserMirror.class, "kotlin", "KotlinUser");
    KotlinUserMirror request = new KotlinUserMirror();
    request.id = 4_294_967_295L;
    request.name = "java-to-kotlin";
    request.score = -123456789L;
    ExecutionContext context = executePeer(STATIC_SERIALIZER_CASE, fory, request);
    KotlinUserMirror response = (KotlinUserMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.id, 4_294_967_294L);
    Assert.assertEquals(response.name, "kotlin-to-java");
    Assert.assertEquals(response.score, 987654321L);
  }

  @Test(groups = "xlang")
  public void testDenseArrayRoundTrip() throws IOException {
    Fory fory = newFory();
    fory.register(KotlinDenseArraysMirror.class, "kotlin", "KotlinDenseArrays");
    KotlinDenseArraysMirror request = new KotlinDenseArraysMirror();
    request.ubytes = new byte[] {1, -1};
    request.ushorts = new short[] {2, -1};
    request.uints = new int[] {3, -1};
    request.ulongs = new long[] {4, -1L};
    request.ints = new int[] {-1, 1};
    request.longs = new long[] {-2L, 2L};
    request.bytes = new byte[] {5, 6};
    request.shorts = new short[] {-7, 7};
    request.floats = new float[] {1.5f, -1.5f};
    request.doubles = new double[] {2.5, -2.5};
    request.booleans = new boolean[] {true, false};
    request.nullableUInts = new int[] {8, -1};

    ExecutionContext context = executePeer(DENSE_ARRAY_CASE, fory, request);
    KotlinDenseArraysMirror response =
        (KotlinDenseArraysMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.ubytes, new byte[] {9, -1});
    Assert.assertEquals(response.ushorts, new short[] {10, -1});
    Assert.assertEquals(response.uints, new int[] {11, -1});
    Assert.assertEquals(response.ulongs, new long[] {12, -1L});
    Assert.assertEquals(response.ints, new int[] {-13, 13});
    Assert.assertEquals(response.longs, new long[] {-14L, 14L});
    Assert.assertEquals(response.bytes, new byte[] {15, 16});
    Assert.assertEquals(response.shorts, new short[] {-17, 17});
    Assert.assertEquals(response.floats, new float[] {18.5f, -18.5f});
    Assert.assertEquals(response.doubles, new double[] {19.5, -19.5});
    Assert.assertEquals(response.booleans, new boolean[] {false, true});
    Assert.assertNull(response.nullableUInts);
  }

  @Test(groups = "xlang")
  public void testUnsignedCollectionRoundTrip() throws IOException {
    Fory fory = newFory();
    fory.register(KotlinUnsignedCollectionsMirror.class, "kotlin", "KotlinUnsignedCollections");
    KotlinUnsignedCollectionsMirror request = new KotlinUnsignedCollectionsMirror();
    request.ids = Arrays.asList(1L, 4_294_967_295L);
    request.optionalIds = Arrays.asList(2L, null, 4_294_967_295L);
    request.totals = new LinkedHashSet<>(Arrays.asList(3L, -1L));
    request.byName = new LinkedHashMap<>();
    request.byName.put("a", 4L);
    request.byName.put("max", 4_294_967_295L);
    request.namesById = new LinkedHashMap<>();
    request.namesById.put(5L, "five");
    request.namesById.put(4_294_967_295L, "max");

    ExecutionContext context = executePeer(UNSIGNED_COLLECTION_CASE, fory, request);
    KotlinUnsignedCollectionsMirror response =
        (KotlinUnsignedCollectionsMirror) fory.deserialize(readBuffer(context.dataFile()));
    Assert.assertEquals(response.ids, Arrays.asList(6L, 4_294_967_295L));
    Assert.assertEquals(response.optionalIds, Arrays.asList(7L, null, 4_294_967_295L));
    Assert.assertEquals(response.totals, new LinkedHashSet<>(Arrays.asList(8L, -1L)));
    Assert.assertEquals(response.byName.get("b"), Long.valueOf(9L));
    Assert.assertEquals(response.byName.get("max"), Long.valueOf(4_294_967_295L));
    Assert.assertEquals(response.namesById.get(10L), "ten");
    Assert.assertEquals(response.namesById.get(4_294_967_295L), "max");
  }

  private ExecutionContext executePeer(String caseName, Fory fory, Object request)
      throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(128);
    fory.serialize(buffer, request);
    ExecutionContext context = prepareExecution(caseName, buffer.getBytes(0, buffer.writerIndex()));
    Assert.assertTrue(
        TestUtils.executeCommand(
            context.commandContext().command(),
            60,
            context.commandContext().environment(),
            context.commandContext().workDir()));
    return context;
  }

  private static Fory newFory() {
    return Fory.builder()
        .withLanguage(Language.XLANG)
        .requireClassRegistration(true)
        .withRefTracking(false)
        .withCompatible(true)
        .build();
  }

  @Data
  @ForyStruct
  public static class KotlinUserMirror {
    @ForyField(id = 1)
    @UInt32Type(encoding = Int32Encoding.FIXED)
    public long id;

    @ForyField(id = 2)
    public String name;

    @ForyField(id = 3)
    public long score;
  }

  @Data
  @ForyStruct
  public static class KotlinDenseArraysMirror {
    @ForyField(id = 1)
    @UInt8Type
    public byte[] ubytes;

    @ForyField(id = 2)
    @UInt16Type
    public short[] ushorts;

    @ForyField(id = 3)
    @UInt32Type
    public int[] uints;

    @ForyField(id = 4)
    @UInt64Type
    public long[] ulongs;

    @ForyField(id = 5)
    public int[] ints;

    @ForyField(id = 6)
    public long[] longs;

    @ForyField(id = 7)
    public byte[] bytes;

    @ForyField(id = 8)
    public short[] shorts;

    @ForyField(id = 9)
    public float[] floats;

    @ForyField(id = 10)
    public double[] doubles;

    @ForyField(id = 11)
    public boolean[] booleans;

    @Nullable
    @ForyField(id = 12)
    @UInt32Type
    public int[] nullableUInts;
  }

  @Data
  @ForyStruct
  public static class KotlinUnsignedCollectionsMirror {
    @ForyField(id = 1)
    public List<@UInt32Type Long> ids;

    @ForyField(id = 2)
    public List<@Nullable @UInt32Type Long> optionalIds;

    @ForyField(id = 3)
    public Set<@UInt64Type Long> totals;

    @ForyField(id = 4)
    public Map<String, @UInt32Type Long> byName;

    @ForyField(id = 5)
    public Map<@UInt32Type Long, String> namesById;
  }
}
