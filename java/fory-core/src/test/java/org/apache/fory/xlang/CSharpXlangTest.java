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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.fory.test.TestUtils;
import org.testng.SkipException;
import org.testng.annotations.Test;

/** Executes cross-language tests against the C# implementation. */
@Test
public class CSharpXlangTest extends XlangTestBase {
  private static final String CSHARP_DLL = "Fory.XlangPeer.dll";
  private static final File CSHARP_DIR = new File("../../csharp");
  private static final File CSHARP_BINARY_DIR = new File(CSHARP_DIR, "tests/Fory.XlangPeer/bin/Debug/net8.0");
  private volatile boolean peerBuilt;

  @Override
  protected void ensurePeerReady() {
    String enabled = System.getenv("FORY_CSHARP_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping CSharpXlangTest: FORY_CSHARP_JAVA_CI not set to 1");
    }

    if (!isDotnetAvailable()) {
      throw new SkipException("Skipping CSharpXlangTest: dotnet is not available");
    }

    try {
      ensurePeerBuilt();
    } catch (IOException e) {
      throw new RuntimeException("Failed to build C# peer", e);
    }
  }

  @Override
  protected CommandContext buildCommandContext(String caseName, Path dataFile) throws IOException {
    ensurePeerBuilt();

    List<String> command = new ArrayList<>();
    command.add("dotnet");
    command.add(new File(CSHARP_BINARY_DIR, CSHARP_DLL).getAbsolutePath());
    command.add("--case");
    command.add(caseName);

    Map<String, String> env = envBuilder(dataFile);
    return new CommandContext(command, env, CSHARP_BINARY_DIR);
  }

  @Override
  @Test(groups = "xlang", dataProvider = "enableCodegenParallel")
  public void testUnionXlang(boolean enableCodegen) throws java.io.IOException {
    super.testUnionXlang(enableCodegen);
  }

  private boolean isDotnetAvailable() {
    try {
      Process process = new ProcessBuilder("dotnet", "--version").start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private void ensurePeerBuilt() throws IOException {
    if (peerBuilt) {
      return;
    }

    synchronized (this) {
      if (peerBuilt) {
        return;
      }

      List<String> buildCommand =
          List.of("dotnet", "build", "tests/Fory.XlangPeer/Fory.XlangPeer.csproj", "-c", "Debug");
      boolean built = TestUtils.executeCommand(buildCommand, 180, Collections.emptyMap(), CSHARP_DIR);
      if (!built) {
        throw new IOException("dotnet build failed for csharp/tests/Fory.XlangPeer");
      }

      File dll = new File(CSHARP_BINARY_DIR, CSHARP_DLL);
      if (!dll.exists()) {
        throw new IOException("C# peer assembly not found: " + dll.getAbsolutePath());
      }

      peerBuilt = true;
    }
  }
}
