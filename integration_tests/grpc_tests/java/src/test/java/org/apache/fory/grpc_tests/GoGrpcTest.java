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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

public class GoGrpcTest extends GrpcTestBase {

  @Test
  public void testJavaServerGoClient() throws Exception {
    Server server = startJavaFdlServer();
    try {
      runPeer("go-grpc-client", goCommand("client", "--target", "127.0.0.1:" + server.getPort()));
    } finally {
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testGoServerJavaClient() throws Exception {
    exercisePeerServer("go-grpc", "Go", "fory-grpc-go-", goCommand("server"), this::exerciseFdl);
  }

  private PeerCommand goCommand(String... args) {
    Path goRoot = grpcRoot().resolve("go");
    List<String> command = new ArrayList<>();
    command.add(goRoot.resolve("grpc-interop").toString());
    command.addAll(Arrays.asList(args));
    PeerCommand peerCommand = newPeerCommand(goRoot, command);
    setLocalhostNoProxy(peerCommand);
    return peerCommand;
  }
}
