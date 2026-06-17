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

package org.apache.fory.grpc_tests;

import io.grpc.Server;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

public class PythonSyncGrpcTest extends GrpcTestBase {

  @Test
  public void testJavaServerPythonClient() throws Exception {
    Server server = startJavaAllSchemasServer();
    try {
      runPythonSync(
          "python-sync-grpc-client", "client", "--target", "127.0.0.1:" + server.getPort());
    } finally {
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testJavaClientPythonServer() throws Exception {
    exercisePeerServer(
        "python-sync-grpc",
        "Python",
        "fory-grpc-python-sync-",
        pythonSyncCommand("server"),
        this::exerciseAllSchemas);
  }
}
