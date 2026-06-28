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

package org.apache.fory.resolver;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.serializer.ArraySerializers;
import org.testng.annotations.Test;

public class GraalvmRuntimeArrayTest {
  @Test
  public void testGraalvmRuntimeFallsBackForUnregisteredArrayClass() throws Exception {
    Process process =
        new ProcessBuilder(
                TestUtils.javaCommand(
                    System.getProperty("java.class.path"),
                    GraalvmRuntimeArrayMain.class,
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

  public static final class GraalvmRuntimeArrayMain {
    public static void main(String[] args) {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withCodegen(false)
              .requireClassRegistration(true)
              .suppressClassRegistrationWarnings(true)
              .withCompatible(false)
              .build();
      Class<?> serializerClass = fory.getTypeResolver().getSerializerClass(Throwable[].class);
      if (serializerClass != ArraySerializers.ObjectArraySerializer.class) {
        throw new AssertionError("Unexpected serializer class: " + serializerClass);
      }
      Throwable[] value = {new RuntimeException("array-element")};
      Throwable[] copy = (Throwable[]) fory.deserialize(fory.serialize(value));
      if (copy.length != 1
          || copy[0].getClass() != RuntimeException.class
          || !"array-element".equals(copy[0].getMessage())) {
        throw new AssertionError("Unexpected round-trip result");
      }
    }
  }
}
