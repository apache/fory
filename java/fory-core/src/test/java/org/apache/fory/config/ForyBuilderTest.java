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

package org.apache.fory.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.meta.MetaCompressor;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ForyBuilderTest {

  @Test
  public void testWithMetaCompressor() {
    MetaCompressor metaCompressor =
        new ForyBuilder()
            .withMetaCompressor(
                new MetaCompressor() {
                  @Override
                  public byte[] compress(byte[] data, int offset, int size) {
                    return new byte[0];
                  }

                  @Override
                  public byte[] decompress(byte[] compressedData, int offset, int size) {
                    return new byte[0];
                  }
                })
            .metaCompressor;
    Assert.assertEquals(metaCompressor.getClass().getSimpleName(), "TypeEqualMetaCompressor");
    new ForyBuilder()
        .withMetaCompressor(
            new MetaCompressor() {
              @Override
              public byte[] compress(byte[] data, int offset, int size) {
                return new byte[0];
              }

              @Override
              public byte[] decompress(byte[] compressedData, int offset, int size) {
                return new byte[0];
              }

              @Override
              public boolean equals(Object o) {
                if (this == o) {
                  return true;
                }
                return o != null && getClass() == o.getClass();
              }

              @Override
              public int hashCode() {
                return getClass().hashCode();
              }
            });
  }

  @Test
  public void testCompatibleStateIsBooleanBacked() {
    Fory compatible = new ForyBuilder().withXlang(false).withCompatible(true).build();
    Fory compatibleFromBoolean = new ForyBuilder().withXlang(false).withCompatible(true).build();
    Fory schemaConsistent = new ForyBuilder().withXlang(false).withCompatible(false).build();

    assertTrue(compatible.getConfig().isCompatible());
    assertEquals(compatible.getConfig(), compatibleFromBoolean.getConfig());

    assertFalse(schemaConsistent.getConfig().isCompatible());
  }

  @Test
  public void testXlangDefaultsToCompatibleUnlessExplicitlySet() {
    assertTrue(new ForyBuilder().xlang);
    Fory defaultXlang = new ForyBuilder().withXlang(true).build();
    Fory explicitSchemaConsistent =
        new ForyBuilder().withCompatible(false).withXlang(true).withClassVersionCheck(true).build();
    Fory explicitSchemaConsistentReverseOrder =
        new ForyBuilder().withXlang(true).withCompatible(false).withClassVersionCheck(true).build();

    assertTrue(defaultXlang.getConfig().isCompatible());
    assertFalse(defaultXlang.getConfig().checkClassVersion());

    assertFalse(explicitSchemaConsistent.getConfig().isCompatible());
    assertTrue(explicitSchemaConsistent.getConfig().checkClassVersion());
    assertFalse(explicitSchemaConsistentReverseOrder.getConfig().isCompatible());
    assertTrue(explicitSchemaConsistentReverseOrder.getConfig().checkClassVersion());
  }

  @Test
  public void testXlangForcesProtocolIntegerEncodings() {
    Fory xlangAfterNativeCompressionOptions =
        new ForyBuilder()
            .withXlang(true)
            .withIntCompressed(false)
            .withLongCompressed(Int64Encoding.FIXED)
            .build();
    Fory xlangBeforeNativeCompressionOptions =
        new ForyBuilder()
            .withIntCompressed(false)
            .withLongCompressed(Int64Encoding.TAGGED)
            .withXlang(true)
            .build();
    Fory xlangLanguage =
        new ForyBuilder().withNumberCompressed(false).withLanguage(Language.XLANG).build();

    assertTrue(xlangAfterNativeCompressionOptions.getConfig().compressInt());
    assertEquals(
        xlangAfterNativeCompressionOptions.getConfig().longEncoding(), Int64Encoding.VARINT);
    assertTrue(xlangBeforeNativeCompressionOptions.getConfig().compressInt());
    assertEquals(
        xlangBeforeNativeCompressionOptions.getConfig().longEncoding(), Int64Encoding.VARINT);
    assertTrue(xlangLanguage.getConfig().compressInt());
    assertEquals(xlangLanguage.getConfig().longEncoding(), Int64Encoding.VARINT);
  }

  @Test
  public void testCodegenDefaultsOnOrdinaryJvm() {
    Fory defaultFory = new ForyBuilder().withXlang(false).build();
    Fory explicitCodegen = new ForyBuilder().withXlang(false).withCodegen(true).build();
    Fory interpreter = new ForyBuilder().withXlang(false).withCodegen(false).build();
    ThreadSafeFory threadSafeInterpreter =
        new ForyBuilder().withXlang(false).withCodegen(false).buildThreadSafeForyPool(1);

    assertTrue(defaultFory.getConfig().isCodeGenEnabled());
    assertTrue(explicitCodegen.getConfig().isCodeGenEnabled());
    assertFalse(interpreter.getConfig().isCodeGenEnabled());
    assertFalse(threadSafeInterpreter.execute(fory -> fory.getConfig().isCodeGenEnabled()));
  }

  @Test
  public void testGraalvmRuntimeForcesCodegenOff() throws Exception {
    Process process =
        new ProcessBuilder(
                TestUtils.javaCommand(
                    System.getProperty("java.class.path"),
                    GraalvmCodegenConfigMain.class,
                    "-Dorg.graalvm.nativeimage.imagecode=runtime"))
            .redirectErrorStream(true)
            .start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class GraalvmCodegenConfigMain {
    public static void main(String[] args) {
      Fory defaultFory = new ForyBuilder().withXlang(false).build();
      Fory explicitCodegen = new ForyBuilder().withXlang(false).withCodegen(true).build();
      Fory interpreter = new ForyBuilder().withXlang(false).withCodegen(false).build();
      Fory asyncRequested =
          new ForyBuilder().withXlang(false).withCodegen(true).withAsyncCompilation(true).build();

      assertDisabled(defaultFory, "default");
      assertDisabled(explicitCodegen, "explicit codegen");
      assertDisabled(interpreter, "explicit interpreter");
      assertDisabled(asyncRequested, "async requested");
      if (asyncRequested.getConfig().isAsyncCompilationEnabled()) {
        throw new AssertionError("GraalVM runtime must force async compilation off");
      }
    }

    private static void assertDisabled(Fory fory, String label) {
      if (fory.getConfig().isCodeGenEnabled()) {
        throw new AssertionError(label + " codegen should be disabled");
      }
    }
  }
}
