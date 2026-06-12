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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GrpcInteropTest {

  @Test
  public void testJavaServerPythonClient() throws Exception {
    Server server =
        ServerBuilder.forPort(0)
            .addService(new FdlService())
            .addService(new FbsService())
            .addService(new PbService())
            .build()
            .start();
    try {
      runPython("python-grpc-client", "client", "--target", "127.0.0.1:" + server.getPort());
    } finally {
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testJavaClientPythonServer() throws Exception {
    Path portFile = Files.createTempFile("fory-grpc-python-", ".port");
    Files.deleteIfExists(portFile);
    PeerCommand command = pythonCommand("server", "--port-file", portFile.toString());
    Process process = startPeer(command);
    PeerOutputCollector outputCollector =
        new PeerOutputCollector(process.getInputStream(), "python-grpc-server");
    outputCollector.start();
    try {
      int port = waitForPort(process, outputCollector, portFile, "Python");
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
      try {
        exerciseFdl(channel);
        exerciseFbs(channel);
        exercisePb(channel);
      } finally {
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);
      }
    } finally {
      process.destroy();
      process.waitFor(10, TimeUnit.SECONDS);
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      outputCollector.awaitOutput();
      Files.deleteIfExists(portFile);
    }
  }

  @Test
  public void testJavaServerRustClient() throws Exception {
    Server server =
        ServerBuilder.forPort(0)
            .addService(new FdlService())
            .addService(new FbsService())
            .addService(new PbService())
            .build()
            .start();
    try {
      runRust("rust-grpc-client", "client", "--target", "127.0.0.1:" + server.getPort());
    } finally {
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testJavaClientRustServer() throws Exception {
    Path portFile = Files.createTempFile("fory-grpc-rust-", ".port");
    Files.deleteIfExists(portFile);
    PeerCommand command = rustCommand("server", "--port-file", portFile.toString());
    Process process = startPeer(command);
    PeerOutputCollector outputCollector =
        new PeerOutputCollector(process.getInputStream(), "rust-grpc-server");
    outputCollector.start();
    try {
      int port = waitForPort(process, outputCollector, portFile, "Rust");
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
      try {
        exerciseFdl(channel);
        exerciseFbs(channel);
        exercisePb(channel);
      } finally {
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);
      }
    } finally {
      process.destroy();
      process.waitFor(10, TimeUnit.SECONDS);
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      outputCollector.awaitOutput();
      Files.deleteIfExists(portFile);
    }
  }

  private void exerciseFdl(ManagedChannel channel) throws InterruptedException {
    grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceBlockingStub blocking =
        grpc_fdl.FdlGrpcServiceGrpc.newBlockingStub(channel);
    grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceStub async =
        grpc_fdl.FdlGrpcServiceGrpc.newStub(channel);

    List<grpc_fdl.GrpcFdlRequest> messages =
        Arrays.asList(fdlRequest("fdl-a", 1, "alpha"), fdlRequest("fdl-b", 2, "beta"));
    assertFdlMessages(blocking, async, messages);

    List<grpc_fdl.GrpcFdlUnion> unions =
        Arrays.asList(
            grpc_fdl.GrpcFdlUnion.ofRequest(fdlRequest("fdl-u-a", 3, "union-alpha")),
            grpc_fdl.GrpcFdlUnion.ofRequest(fdlRequest("fdl-u-b", 4, "union-beta")));
    assertFdlUnions(blocking, async, unions);
  }

  // exerciseFdlMessages exercises the four FDL message streaming modes without union methods.
  private void exerciseFdlMessages(ManagedChannel channel) throws InterruptedException {
    grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceBlockingStub blocking =
        grpc_fdl.FdlGrpcServiceGrpc.newBlockingStub(channel);
    grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceStub async =
        grpc_fdl.FdlGrpcServiceGrpc.newStub(channel);

    List<grpc_fdl.GrpcFdlRequest> messages =
        Arrays.asList(fdlRequest("fdl-a", 1, "alpha"), fdlRequest("fdl-b", 2, "beta"));
    assertFdlMessages(blocking, async, messages);
  }

  private void assertFdlMessages(
      grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceBlockingStub blocking,
      grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceStub async,
      List<grpc_fdl.GrpcFdlRequest> requests)
      throws InterruptedException {
    grpc_fdl.GrpcFdlRequest first = requests.get(0);
    Assert.assertEquals(blocking.unaryMessage(first), fdlResponse(first, "unary", 10));
    Assert.assertEquals(
        toList(blocking.serverStreamMessage(first)),
        Arrays.asList(
            fdlResponse(first, "server-0", 0),
            fdlResponse(first, "server-1", 1),
            fdlResponse(first, "server-2", 2)));

    CollectingObserver<grpc_fdl.GrpcFdlResponse> clientObserver = new CollectingObserver<>();
    sendAll(async.clientStreamMessage(clientObserver), requests);
    Assert.assertEquals(clientObserver.await(), Collections.singletonList(fdlAggregate(requests)));

    CollectingObserver<grpc_fdl.GrpcFdlResponse> bidiObserver = new CollectingObserver<>();
    sendAll(async.bidiStreamMessage(bidiObserver), requests);
    Assert.assertEquals(
        bidiObserver.await(),
        Arrays.asList(
            fdlResponse(requests.get(0), "bidi-0", 0), fdlResponse(requests.get(1), "bidi-1", 1)));
  }

  private void assertFdlUnions(
      grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceBlockingStub blocking,
      grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceStub async,
      List<grpc_fdl.GrpcFdlUnion> requests)
      throws InterruptedException {
    grpc_fdl.GrpcFdlRequest first = fdlRequestFromUnion(requests.get(0));
    Assert.assertEquals(blocking.unaryUnion(requests.get(0)), fdlUnionResponse(first, "unary", 10));
    Assert.assertEquals(
        toList(blocking.serverStreamUnion(requests.get(0))),
        Arrays.asList(
            fdlUnionResponse(first, "server-0", 0),
            fdlUnionResponse(first, "server-1", 1),
            fdlUnionResponse(first, "server-2", 2)));

    CollectingObserver<grpc_fdl.GrpcFdlUnion> clientObserver = new CollectingObserver<>();
    sendAll(async.clientStreamUnion(clientObserver), requests);
    Assert.assertEquals(
        clientObserver.await(), Collections.singletonList(fdlUnionAggregate(requests)));

    CollectingObserver<grpc_fdl.GrpcFdlUnion> bidiObserver = new CollectingObserver<>();
    sendAll(async.bidiStreamUnion(bidiObserver), requests);
    Assert.assertEquals(
        bidiObserver.await(),
        Arrays.asList(
            fdlUnionResponse(fdlRequestFromUnion(requests.get(0)), "bidi-0", 0),
            fdlUnionResponse(fdlRequestFromUnion(requests.get(1)), "bidi-1", 1)));
  }

  private void exerciseFbs(ManagedChannel channel) throws InterruptedException {
    grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceBlockingStub blocking =
        grpc_fbs.FbsGrpcServiceGrpc.newBlockingStub(channel);
    grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceStub async =
        grpc_fbs.FbsGrpcServiceGrpc.newStub(channel);

    List<grpc_fbs.GrpcFbsRequest> messages =
        Arrays.asList(fbsRequest("fbs-a", 5, "alpha"), fbsRequest("fbs-b", 6, "beta"));
    assertFbsMessages(blocking, async, messages);

    List<grpc_fbs.GrpcFbsUnion> unions =
        Arrays.asList(
            grpc_fbs.GrpcFbsUnion.ofGrpcFbsRequest(fbsRequest("fbs-u-a", 7, "union-alpha")),
            grpc_fbs.GrpcFbsUnion.ofGrpcFbsRequest(fbsRequest("fbs-u-b", 8, "union-beta")));
    assertFbsUnions(blocking, async, unions);
  }

  private void assertFbsMessages(
      grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceBlockingStub blocking,
      grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceStub async,
      List<grpc_fbs.GrpcFbsRequest> requests)
      throws InterruptedException {
    grpc_fbs.GrpcFbsRequest first = requests.get(0);
    Assert.assertEquals(blocking.unaryMessage(first), fbsResponse(first, "unary", 10));
    Assert.assertEquals(
        toList(blocking.serverStreamMessage(first)),
        Arrays.asList(
            fbsResponse(first, "server-0", 0),
            fbsResponse(first, "server-1", 1),
            fbsResponse(first, "server-2", 2)));

    CollectingObserver<grpc_fbs.GrpcFbsResponse> clientObserver = new CollectingObserver<>();
    sendAll(async.clientStreamMessage(clientObserver), requests);
    Assert.assertEquals(clientObserver.await(), Collections.singletonList(fbsAggregate(requests)));

    CollectingObserver<grpc_fbs.GrpcFbsResponse> bidiObserver = new CollectingObserver<>();
    sendAll(async.bidiStreamMessage(bidiObserver), requests);
    Assert.assertEquals(
        bidiObserver.await(),
        Arrays.asList(
            fbsResponse(requests.get(0), "bidi-0", 0), fbsResponse(requests.get(1), "bidi-1", 1)));
  }

  private void assertFbsUnions(
      grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceBlockingStub blocking,
      grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceStub async,
      List<grpc_fbs.GrpcFbsUnion> requests)
      throws InterruptedException {
    grpc_fbs.GrpcFbsRequest first = fbsRequestFromUnion(requests.get(0));
    Assert.assertEquals(blocking.unaryUnion(requests.get(0)), fbsUnionResponse(first, "unary", 10));
    Assert.assertEquals(
        toList(blocking.serverStreamUnion(requests.get(0))),
        Arrays.asList(
            fbsUnionResponse(first, "server-0", 0),
            fbsUnionResponse(first, "server-1", 1),
            fbsUnionResponse(first, "server-2", 2)));

    CollectingObserver<grpc_fbs.GrpcFbsUnion> clientObserver = new CollectingObserver<>();
    sendAll(async.clientStreamUnion(clientObserver), requests);
    Assert.assertEquals(
        clientObserver.await(), Collections.singletonList(fbsUnionAggregate(requests)));

    CollectingObserver<grpc_fbs.GrpcFbsUnion> bidiObserver = new CollectingObserver<>();
    sendAll(async.bidiStreamUnion(bidiObserver), requests);
    Assert.assertEquals(
        bidiObserver.await(),
        Arrays.asList(
            fbsUnionResponse(fbsRequestFromUnion(requests.get(0)), "bidi-0", 0),
            fbsUnionResponse(fbsRequestFromUnion(requests.get(1)), "bidi-1", 1)));
  }

  private void exercisePb(ManagedChannel channel) throws InterruptedException {
    grpc_pb.PbGrpcServiceGrpc.PbGrpcServiceBlockingStub blocking =
        grpc_pb.PbGrpcServiceGrpc.newBlockingStub(channel);
    grpc_pb.PbGrpcServiceGrpc.PbGrpcServiceStub async = grpc_pb.PbGrpcServiceGrpc.newStub(channel);

    List<grpc_pb.GrpcPbRequest> requests =
        Arrays.asList(
            pbRequest("pb-a", 9, grpc_pb.GrpcPbRequest.Payload.ofText("alpha")),
            pbRequest("pb-b", 10, grpc_pb.GrpcPbRequest.Payload.ofNumber(42)));
    grpc_pb.GrpcPbRequest first = requests.get(0);
    Assert.assertEquals(blocking.unaryMessage(first), pbResponse(first, "unary", 10));
    Assert.assertEquals(
        toList(blocking.serverStreamMessage(first)),
        Arrays.asList(
            pbResponse(first, "server-0", 0),
            pbResponse(first, "server-1", 1),
            pbResponse(first, "server-2", 2)));

    CollectingObserver<grpc_pb.GrpcPbResponse> clientObserver = new CollectingObserver<>();
    sendAll(async.clientStreamMessage(clientObserver), requests);
    Assert.assertEquals(clientObserver.await(), Collections.singletonList(pbAggregate(requests)));

    CollectingObserver<grpc_pb.GrpcPbResponse> bidiObserver = new CollectingObserver<>();
    sendAll(async.bidiStreamMessage(bidiObserver), requests);
    Assert.assertEquals(
        bidiObserver.await(),
        Arrays.asList(
            pbResponse(requests.get(0), "bidi-0", 0), pbResponse(requests.get(1), "bidi-1", 1)));
  }

  @Test
  public void testJavaServerGoClient() throws Exception {
    Server server = ServerBuilder.forPort(0).addService(new FdlService()).build().start();
    try {
      runGo("go-grpc-client", "client", "--target", "127.0.0.1:" + server.getPort());
    } finally {
      server.shutdownNow();
      server.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void testGoServerJavaClient() throws Exception {
    Path portFile = Files.createTempFile("fory-grpc-go-", ".port");
    Files.deleteIfExists(portFile);
    PeerCommand command = goCommand("server", "--port-file", portFile.toString());
    Process process = startPeer(command);
    PeerOutputCollector outputCollector =
        new PeerOutputCollector(process.getInputStream(), "go-grpc-server");
    outputCollector.start();
    try {
      int port = waitForPort(process, outputCollector, portFile, "Go");
      ManagedChannel channel =
          ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
      try {
        exerciseFdlMessages(channel);
      } finally {
        channel.shutdownNow();
        channel.awaitTermination(10, TimeUnit.SECONDS);
      }
    } finally {
      process.destroy();
      process.waitFor(10, TimeUnit.SECONDS);
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      outputCollector.awaitOutput();
      Files.deleteIfExists(portFile);
    }
  }

  private PeerCommand goCommand(String... args) {
    Path grpcRoot = repoRoot().resolve("integration_tests").resolve("grpc_tests");
    Path goRoot = grpcRoot.resolve("go");
    List<String> command = new ArrayList<>();
    command.add(goRoot.resolve("grpc-interop").toString());
    command.addAll(Arrays.asList(args));
    PeerCommand peerCommand = new PeerCommand();
    peerCommand.command = command;
    peerCommand.workDir = goRoot;
    peerCommand.environment.put("NO_PROXY", "127.0.0.1,localhost");
    peerCommand.environment.put("no_proxy", "127.0.0.1,localhost");
    return peerCommand;
  }

  private void runGo(String peer, String... args) throws IOException, InterruptedException {
    Process process = startPeer(goCommand(args));
    PeerOutputCollector outputCollector = new PeerOutputCollector(process.getInputStream(), peer);
    outputCollector.start();
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      Assert.fail("Peer process timed out for " + peer + peerOutput(outputCollector));
    }
    int exitCode = process.exitValue();
    if (exitCode != 0) {
      Assert.fail(
          "Peer process failed for "
              + peer
              + " with exit code "
              + exitCode
              + peerOutput(outputCollector));
    }
    outputCollector.awaitOutput();
  }

  private PeerCommand pythonCommand(String... args) {
    Path repoRoot = repoRoot();
    Path grpcRoot = repoRoot.resolve("integration_tests").resolve("grpc_tests");
    Path pythonRoot = grpcRoot.resolve("python");
    String pythonPath =
        pythonRoot.resolve("grpc_tests").resolve("generated")
            + File.pathSeparator
            + pythonRoot
            + File.pathSeparator
            + repoRoot.resolve("python");
    String existingPythonPath = System.getenv("PYTHONPATH");
    if (existingPythonPath != null && !existingPythonPath.isEmpty()) {
      pythonPath = pythonPath + File.pathSeparator + existingPythonPath;
    }
    List<String> command = new ArrayList<>();
    command.add("python");
    command.add("-m");
    command.add("grpc_tests.grpc_interop");
    command.addAll(Arrays.asList(args));
    PeerCommand peerCommand = new PeerCommand();
    peerCommand.command = command;
    peerCommand.workDir = grpcRoot;
    peerCommand.environment.put("PYTHONPATH", pythonPath);
    peerCommand.environment.put("ENABLE_FORY_CYTHON_SERIALIZATION", "0");
    peerCommand.environment.put("ENABLE_FORY_DEBUG_OUTPUT", "1");
    peerCommand.environment.put("NO_PROXY", "127.0.0.1,localhost");
    peerCommand.environment.put("no_proxy", "127.0.0.1,localhost");
    // Some developer and CI environments set proxy variables that grpcio honors
    // even for localhost unless no_proxy is configured correctly.
    for (String proxyVar :
        Arrays.asList(
            "all_proxy", "http_proxy", "https_proxy", "ALL_PROXY", "HTTP_PROXY", "HTTPS_PROXY")) {
      peerCommand.environment.put(proxyVar, "");
    }
    return peerCommand;
  }

  private PeerCommand rustCommand(String... args) {
    Path repoRoot = repoRoot();
    Path grpcRoot = repoRoot.resolve("integration_tests").resolve("grpc_tests");
    Path rustRoot = grpcRoot.resolve("rust");
    List<String> command = new ArrayList<>();
    command.add("cargo");
    command.add("run");
    command.add("--quiet");
    command.add("--manifest-path");
    command.add(rustRoot.resolve("interop").resolve("Cargo.toml").toString());
    command.add("--");
    command.addAll(Arrays.asList(args));
    PeerCommand peerCommand = new PeerCommand();
    peerCommand.command = command;
    peerCommand.workDir = rustRoot;
    peerCommand.environment.put("CARGO_TERM_COLOR", "never");
    peerCommand.environment.put("ENABLE_FORY_DEBUG_OUTPUT", "1");
    peerCommand.environment.put("RUST_BACKTRACE", "1");
    peerCommand.environment.put("NO_PROXY", "127.0.0.1,localhost");
    peerCommand.environment.put("no_proxy", "127.0.0.1,localhost");
    for (String proxyVar :
        Arrays.asList(
            "all_proxy", "http_proxy", "https_proxy", "ALL_PROXY", "HTTP_PROXY", "HTTPS_PROXY")) {
      peerCommand.environment.put(proxyVar, "");
    }
    return peerCommand;
  }

  private void runPython(String peer, String... args) throws IOException, InterruptedException {
    Process process = startPeer(pythonCommand(args));
    PeerOutputCollector outputCollector = new PeerOutputCollector(process.getInputStream(), peer);
    outputCollector.start();
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      Assert.fail("Peer process timed out for " + peer + peerOutput(outputCollector));
    }
    int exitCode = process.exitValue();
    if (exitCode != 0) {
      Assert.fail(
          "Peer process failed for "
              + peer
              + " with exit code "
              + exitCode
              + peerOutput(outputCollector));
    }
    outputCollector.awaitOutput();
  }

  private void runRust(String peer, String... args) throws IOException, InterruptedException {
    Process process = startPeer(rustCommand(args));
    PeerOutputCollector outputCollector = new PeerOutputCollector(process.getInputStream(), peer);
    outputCollector.start();
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      Assert.fail("Peer process timed out for " + peer + peerOutput(outputCollector));
    }
    int exitCode = process.exitValue();
    if (exitCode != 0) {
      Assert.fail(
          "Peer process failed for "
              + peer
              + " with exit code "
              + exitCode
              + peerOutput(outputCollector));
    }
    outputCollector.awaitOutput();
  }

  private Process startPeer(PeerCommand command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command.command);
    builder.redirectErrorStream(true);
    builder.directory(command.workDir.toFile());
    builder.environment().putAll(command.environment);
    return builder.start();
  }

  private int waitForPort(
      Process process, PeerOutputCollector outputCollector, Path portFile, String language)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        Assert.fail(language + " gRPC server exited early" + peerOutput(outputCollector));
      }
      if (Files.exists(portFile)) {
        String value = new String(Files.readAllBytes(portFile), StandardCharsets.UTF_8).trim();
        if (!value.isEmpty()) {
          return Integer.parseInt(value);
        }
      }
      Thread.sleep(100);
    }
    process.destroyForcibly();
    process.waitFor(10, TimeUnit.SECONDS);
    Assert.fail(
        "Timed out waiting for " + language + " gRPC server port" + peerOutput(outputCollector));
    return -1;
  }

  private String peerOutput(PeerOutputCollector outputCollector)
      throws IOException, InterruptedException {
    String output = outputCollector.awaitOutput();
    return output.isEmpty() ? "" : "\noutput:\n" + output;
  }

  private Path repoRoot() {
    Path moduleDir = java.nio.file.Paths.get("").toAbsolutePath();
    return moduleDir.getParent().getParent().getParent();
  }

  private static grpc_fdl.GrpcFdlRequest fdlRequest(String id, int count, String payload) {
    grpc_fdl.GrpcFdlRequest request = new grpc_fdl.GrpcFdlRequest();
    request.setId(id);
    request.setCount(count);
    request.setPayload(payload);
    return request;
  }

  private static grpc_fdl.GrpcFdlResponse fdlResponse(
      grpc_fdl.GrpcFdlRequest request, String tag, int offset) {
    grpc_fdl.GrpcFdlResponse response = new grpc_fdl.GrpcFdlResponse();
    response.setId(tag + ":" + request.getId());
    response.setCount(request.getCount() + offset);
    response.setPayload(tag + ":" + request.getPayload());
    return response;
  }

  private static grpc_fdl.GrpcFdlResponse fdlAggregate(List<grpc_fdl.GrpcFdlRequest> requests) {
    grpc_fdl.GrpcFdlResponse response = new grpc_fdl.GrpcFdlResponse();
    response.setId("client:" + joinFdlIds(requests));
    response.setCount(requests.stream().mapToInt(grpc_fdl.GrpcFdlRequest::getCount).sum());
    response.setPayload("client:" + joinFdlPayloads(requests));
    return response;
  }

  private static grpc_fdl.GrpcFdlUnion fdlUnionResponse(
      grpc_fdl.GrpcFdlRequest request, String tag, int offset) {
    return grpc_fdl.GrpcFdlUnion.ofResponse(fdlResponse(request, tag, offset));
  }

  private static grpc_fdl.GrpcFdlUnion fdlUnionAggregate(List<grpc_fdl.GrpcFdlUnion> unions) {
    return grpc_fdl.GrpcFdlUnion.ofResponse(
        fdlAggregate(map(unions, GrpcInteropTest::fdlRequestFromUnion)));
  }

  private static grpc_fdl.GrpcFdlRequest fdlRequestFromUnion(grpc_fdl.GrpcFdlUnion union) {
    Assert.assertTrue(union.hasRequest());
    return union.getRequest();
  }

  private static grpc_fbs.GrpcFbsRequest fbsRequest(String id, int count, String payload) {
    grpc_fbs.GrpcFbsRequest request = new grpc_fbs.GrpcFbsRequest();
    request.setId(id);
    request.setCount(count);
    request.setPayload(payload);
    return request;
  }

  private static grpc_fbs.GrpcFbsResponse fbsResponse(
      grpc_fbs.GrpcFbsRequest request, String tag, int offset) {
    grpc_fbs.GrpcFbsResponse response = new grpc_fbs.GrpcFbsResponse();
    response.setId(tag + ":" + request.getId());
    response.setCount(request.getCount() + offset);
    response.setPayload(tag + ":" + request.getPayload());
    return response;
  }

  private static grpc_fbs.GrpcFbsResponse fbsAggregate(List<grpc_fbs.GrpcFbsRequest> requests) {
    grpc_fbs.GrpcFbsResponse response = new grpc_fbs.GrpcFbsResponse();
    response.setId("client:" + joinFbsIds(requests));
    response.setCount(requests.stream().mapToInt(grpc_fbs.GrpcFbsRequest::getCount).sum());
    response.setPayload("client:" + joinFbsPayloads(requests));
    return response;
  }

  private static grpc_fbs.GrpcFbsUnion fbsUnionResponse(
      grpc_fbs.GrpcFbsRequest request, String tag, int offset) {
    return grpc_fbs.GrpcFbsUnion.ofGrpcFbsResponse(fbsResponse(request, tag, offset));
  }

  private static grpc_fbs.GrpcFbsUnion fbsUnionAggregate(List<grpc_fbs.GrpcFbsUnion> unions) {
    return grpc_fbs.GrpcFbsUnion.ofGrpcFbsResponse(
        fbsAggregate(map(unions, GrpcInteropTest::fbsRequestFromUnion)));
  }

  private static grpc_fbs.GrpcFbsRequest fbsRequestFromUnion(grpc_fbs.GrpcFbsUnion union) {
    Assert.assertTrue(union.hasGrpcFbsRequest());
    return union.getGrpcFbsRequest();
  }

  private static grpc_pb.GrpcPbRequest pbRequest(
      String id, int count, grpc_pb.GrpcPbRequest.Payload payload) {
    grpc_pb.GrpcPbRequest request = new grpc_pb.GrpcPbRequest();
    request.setId(id);
    request.setCount(count);
    request.setPayload(payload);
    return request;
  }

  private static grpc_pb.GrpcPbResponse pbResponse(
      grpc_pb.GrpcPbRequest request, String tag, int offset) {
    grpc_pb.GrpcPbResponse response = new grpc_pb.GrpcPbResponse();
    response.setId(tag + ":" + request.getId());
    response.setCount(request.getCount() + offset);
    response.setPayload(pbResponsePayload(request.getPayload(), tag, offset));
    return response;
  }

  private static grpc_pb.GrpcPbResponse pbAggregate(List<grpc_pb.GrpcPbRequest> requests) {
    grpc_pb.GrpcPbResponse response = new grpc_pb.GrpcPbResponse();
    response.setId("client:" + joinPbIds(requests));
    response.setCount(requests.stream().mapToLong(grpc_pb.GrpcPbRequest::getCount).sum());
    response.setPayload(grpc_pb.GrpcPbResponse.Payload.ofText("client:" + joinPbIds(requests)));
    return response;
  }

  private static grpc_pb.GrpcPbResponse.Payload pbResponsePayload(
      grpc_pb.GrpcPbRequest.Payload payload, String tag, int offset) {
    if (payload == null) {
      return null;
    }
    if (payload.hasText()) {
      return grpc_pb.GrpcPbResponse.Payload.ofText(tag + ":" + payload.getText());
    }
    Assert.assertTrue(payload.hasNumber());
    return grpc_pb.GrpcPbResponse.Payload.ofNumber(payload.getNumber() + offset);
  }

  private static String joinFdlIds(List<grpc_fdl.GrpcFdlRequest> requests) {
    List<String> ids = new ArrayList<>();
    for (grpc_fdl.GrpcFdlRequest request : requests) {
      ids.add(request.getId());
    }
    return String.join("+", ids);
  }

  private static String joinFdlPayloads(List<grpc_fdl.GrpcFdlRequest> requests) {
    List<String> payloads = new ArrayList<>();
    for (grpc_fdl.GrpcFdlRequest request : requests) {
      payloads.add(request.getPayload());
    }
    return String.join("+", payloads);
  }

  private static String joinFbsIds(List<grpc_fbs.GrpcFbsRequest> requests) {
    List<String> ids = new ArrayList<>();
    for (grpc_fbs.GrpcFbsRequest request : requests) {
      ids.add(request.getId());
    }
    return String.join("+", ids);
  }

  private static String joinFbsPayloads(List<grpc_fbs.GrpcFbsRequest> requests) {
    List<String> payloads = new ArrayList<>();
    for (grpc_fbs.GrpcFbsRequest request : requests) {
      payloads.add(request.getPayload());
    }
    return String.join("+", payloads);
  }

  private static String joinPbIds(List<grpc_pb.GrpcPbRequest> requests) {
    List<String> ids = new ArrayList<>();
    for (grpc_pb.GrpcPbRequest request : requests) {
      ids.add(request.getId());
    }
    return String.join("+", ids);
  }

  private static <T> void sendAll(StreamObserver<T> observer, List<T> values) {
    for (T value : values) {
      observer.onNext(value);
    }
    observer.onCompleted();
  }

  private static <T> List<T> toList(Iterator<T> iterator) {
    List<T> result = new ArrayList<>();
    while (iterator.hasNext()) {
      result.add(iterator.next());
    }
    return result;
  }

  private static <T, R> List<R> map(List<T> values, Function<T, R> mapper) {
    List<R> result = new ArrayList<>();
    for (T value : values) {
      result.add(mapper.apply(value));
    }
    return result;
  }

  private static <T> void sendOne(StreamObserver<T> observer, T value) {
    observer.onNext(value);
    observer.onCompleted();
  }

  private static <T> void sendMany(StreamObserver<T> observer, List<T> values) {
    for (T value : values) {
      observer.onNext(value);
    }
    observer.onCompleted();
  }

  private static <Req, Resp> StreamObserver<Req> collectAndRespond(
      StreamObserver<Resp> responseObserver, Function<List<Req>, Resp> responseFactory) {
    return new StreamObserver<Req>() {
      private final List<Req> requests = new ArrayList<>();

      @Override
      public void onNext(Req value) {
        requests.add(value);
      }

      @Override
      public void onError(Throwable t) {
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        sendOne(responseObserver, responseFactory.apply(requests));
      }
    };
  }

  private static final class FdlService extends grpc_fdl.FdlGrpcServiceGrpc.FdlGrpcServiceImplBase {
    @Override
    public void unaryMessage(
        grpc_fdl.GrpcFdlRequest request,
        StreamObserver<grpc_fdl.GrpcFdlResponse> responseObserver) {
      sendOne(responseObserver, fdlResponse(request, "unary", 10));
    }

    @Override
    public void serverStreamMessage(
        grpc_fdl.GrpcFdlRequest request,
        StreamObserver<grpc_fdl.GrpcFdlResponse> responseObserver) {
      sendMany(
          responseObserver,
          Arrays.asList(
              fdlResponse(request, "server-0", 0),
              fdlResponse(request, "server-1", 1),
              fdlResponse(request, "server-2", 2)));
    }

    @Override
    public StreamObserver<grpc_fdl.GrpcFdlRequest> clientStreamMessage(
        StreamObserver<grpc_fdl.GrpcFdlResponse> responseObserver) {
      return collectAndRespond(responseObserver, GrpcInteropTest::fdlAggregate);
    }

    @Override
    public StreamObserver<grpc_fdl.GrpcFdlRequest> bidiStreamMessage(
        StreamObserver<grpc_fdl.GrpcFdlResponse> responseObserver) {
      return new StreamObserver<grpc_fdl.GrpcFdlRequest>() {
        private int index;

        @Override
        public void onNext(grpc_fdl.GrpcFdlRequest value) {
          responseObserver.onNext(fdlResponse(value, "bidi-" + index, index));
          index++;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void unaryUnion(
        grpc_fdl.GrpcFdlUnion request, StreamObserver<grpc_fdl.GrpcFdlUnion> responseObserver) {
      sendOne(responseObserver, fdlUnionResponse(fdlRequestFromUnion(request), "unary", 10));
    }

    @Override
    public void serverStreamUnion(
        grpc_fdl.GrpcFdlUnion request, StreamObserver<grpc_fdl.GrpcFdlUnion> responseObserver) {
      grpc_fdl.GrpcFdlRequest value = fdlRequestFromUnion(request);
      sendMany(
          responseObserver,
          Arrays.asList(
              fdlUnionResponse(value, "server-0", 0),
              fdlUnionResponse(value, "server-1", 1),
              fdlUnionResponse(value, "server-2", 2)));
    }

    @Override
    public StreamObserver<grpc_fdl.GrpcFdlUnion> clientStreamUnion(
        StreamObserver<grpc_fdl.GrpcFdlUnion> responseObserver) {
      return collectAndRespond(responseObserver, GrpcInteropTest::fdlUnionAggregate);
    }

    @Override
    public StreamObserver<grpc_fdl.GrpcFdlUnion> bidiStreamUnion(
        StreamObserver<grpc_fdl.GrpcFdlUnion> responseObserver) {
      return new StreamObserver<grpc_fdl.GrpcFdlUnion>() {
        private int index;

        @Override
        public void onNext(grpc_fdl.GrpcFdlUnion value) {
          responseObserver.onNext(
              fdlUnionResponse(fdlRequestFromUnion(value), "bidi-" + index, index));
          index++;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  private static final class FbsService extends grpc_fbs.FbsGrpcServiceGrpc.FbsGrpcServiceImplBase {
    @Override
    public void unaryMessage(
        grpc_fbs.GrpcFbsRequest request,
        StreamObserver<grpc_fbs.GrpcFbsResponse> responseObserver) {
      sendOne(responseObserver, fbsResponse(request, "unary", 10));
    }

    @Override
    public void serverStreamMessage(
        grpc_fbs.GrpcFbsRequest request,
        StreamObserver<grpc_fbs.GrpcFbsResponse> responseObserver) {
      sendMany(
          responseObserver,
          Arrays.asList(
              fbsResponse(request, "server-0", 0),
              fbsResponse(request, "server-1", 1),
              fbsResponse(request, "server-2", 2)));
    }

    @Override
    public StreamObserver<grpc_fbs.GrpcFbsRequest> clientStreamMessage(
        StreamObserver<grpc_fbs.GrpcFbsResponse> responseObserver) {
      return collectAndRespond(responseObserver, GrpcInteropTest::fbsAggregate);
    }

    @Override
    public StreamObserver<grpc_fbs.GrpcFbsRequest> bidiStreamMessage(
        StreamObserver<grpc_fbs.GrpcFbsResponse> responseObserver) {
      return new StreamObserver<grpc_fbs.GrpcFbsRequest>() {
        private int index;

        @Override
        public void onNext(grpc_fbs.GrpcFbsRequest value) {
          responseObserver.onNext(fbsResponse(value, "bidi-" + index, index));
          index++;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void unaryUnion(
        grpc_fbs.GrpcFbsUnion request, StreamObserver<grpc_fbs.GrpcFbsUnion> responseObserver) {
      sendOne(responseObserver, fbsUnionResponse(fbsRequestFromUnion(request), "unary", 10));
    }

    @Override
    public void serverStreamUnion(
        grpc_fbs.GrpcFbsUnion request, StreamObserver<grpc_fbs.GrpcFbsUnion> responseObserver) {
      grpc_fbs.GrpcFbsRequest value = fbsRequestFromUnion(request);
      sendMany(
          responseObserver,
          Arrays.asList(
              fbsUnionResponse(value, "server-0", 0),
              fbsUnionResponse(value, "server-1", 1),
              fbsUnionResponse(value, "server-2", 2)));
    }

    @Override
    public StreamObserver<grpc_fbs.GrpcFbsUnion> clientStreamUnion(
        StreamObserver<grpc_fbs.GrpcFbsUnion> responseObserver) {
      return collectAndRespond(responseObserver, GrpcInteropTest::fbsUnionAggregate);
    }

    @Override
    public StreamObserver<grpc_fbs.GrpcFbsUnion> bidiStreamUnion(
        StreamObserver<grpc_fbs.GrpcFbsUnion> responseObserver) {
      return new StreamObserver<grpc_fbs.GrpcFbsUnion>() {
        private int index;

        @Override
        public void onNext(grpc_fbs.GrpcFbsUnion value) {
          responseObserver.onNext(
              fbsUnionResponse(fbsRequestFromUnion(value), "bidi-" + index, index));
          index++;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  private static final class PbService extends grpc_pb.PbGrpcServiceGrpc.PbGrpcServiceImplBase {
    @Override
    public void unaryMessage(
        grpc_pb.GrpcPbRequest request, StreamObserver<grpc_pb.GrpcPbResponse> responseObserver) {
      sendOne(responseObserver, pbResponse(request, "unary", 10));
    }

    @Override
    public void serverStreamMessage(
        grpc_pb.GrpcPbRequest request, StreamObserver<grpc_pb.GrpcPbResponse> responseObserver) {
      sendMany(
          responseObserver,
          Arrays.asList(
              pbResponse(request, "server-0", 0),
              pbResponse(request, "server-1", 1),
              pbResponse(request, "server-2", 2)));
    }

    @Override
    public StreamObserver<grpc_pb.GrpcPbRequest> clientStreamMessage(
        StreamObserver<grpc_pb.GrpcPbResponse> responseObserver) {
      return collectAndRespond(responseObserver, GrpcInteropTest::pbAggregate);
    }

    @Override
    public StreamObserver<grpc_pb.GrpcPbRequest> bidiStreamMessage(
        StreamObserver<grpc_pb.GrpcPbResponse> responseObserver) {
      return new StreamObserver<grpc_pb.GrpcPbRequest>() {
        private int index;

        @Override
        public void onNext(grpc_pb.GrpcPbRequest value) {
          responseObserver.onNext(pbResponse(value, "bidi-" + index, index));
          index++;
        }

        @Override
        public void onError(Throwable t) {
          responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }

  private static final class CollectingObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private Throwable failure;

    @Override
    public void onNext(T value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable t) {
      failure = t;
      done.countDown();
    }

    @Override
    public void onCompleted() {
      done.countDown();
    }

    private List<T> await() throws InterruptedException {
      if (!done.await(30, TimeUnit.SECONDS)) {
        Assert.fail("Timed out waiting for gRPC responses");
      }
      if (failure != null) {
        Assert.fail("gRPC call failed", failure);
      }
      return values;
    }
  }

  private static final class PeerCommand {
    private List<String> command;
    private Path workDir;
    private final java.util.Map<String, String> environment = new java.util.HashMap<>();
  }

  private static final class PeerOutputCollector extends Thread {
    private final InputStream inputStream;
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private IOException readFailure;

    private PeerOutputCollector(InputStream inputStream, String peer) {
      super("idl-grpc-peer-output-" + peer);
      setDaemon(true);
      this.inputStream = inputStream;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[4096];
      int bytesRead;
      try {
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } catch (IOException e) {
        readFailure = e;
      } finally {
        try {
          inputStream.close();
        } catch (IOException ignored) {
        }
      }
    }

    private String awaitOutput() throws IOException, InterruptedException {
      join();
      if (readFailure != null) {
        throw readFailure;
      }
      return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
