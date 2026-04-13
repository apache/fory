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

package org.apache.fory;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import org.apache.fory.memory.Platform;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JpmsOptionalClassLoadingTest {
  @Test
  public void testBuildWithoutJavaSqlModule() throws Exception {
    if (Platform.JAVA_VERSION < 9) {
      throw new SkipException("Skip on jdk" + Platform.JAVA_VERSION);
    }
    String javaBin =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    Process process =
        new ProcessBuilder(
                javaBin,
                "--limit-modules",
                "java.base,java.logging,jdk.unsupported",
                "-cp",
                System.getProperty("java.class.path"),
                NoJavaSqlMain.class.getName())
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

  public static final class NoJavaSqlMain {
    public static void main(String[] args) {
      Fory fory = Fory.builder().requireClassRegistration(false).build();
      byte[] bytes = fory.serialize(new SampleValue("fory"));
      SampleValue value = (SampleValue) fory.deserialize(bytes);
      if (!"fory".equals(value.value)) {
        throw new AssertionError("Unexpected round-trip value " + value.value);
      }
    }
  }

  public static final class SampleValue implements Serializable {
    private final String value;

    public SampleValue(String value) {
      this.value = value;
    }
  }
}
