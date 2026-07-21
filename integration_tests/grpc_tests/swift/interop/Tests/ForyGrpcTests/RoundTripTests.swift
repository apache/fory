// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import GRPC
import NIOPosix
import XCTest

import ForyGrpcGenerated

private func response(
  _ request: GrpcFdl.GrpcFdlRequest, _ tag: String, _ offset: Int
) -> GrpcFdl.GrpcFdlResponse {
  GrpcFdl.GrpcFdlResponse(
    id: "\(tag):\(request.id)",
    count: request.count + Int32(offset),
    payload: "\(tag):\(request.payload)")
}

private func aggregate(_ requests: [GrpcFdl.GrpcFdlRequest]) -> GrpcFdl.GrpcFdlResponse {
  GrpcFdl.GrpcFdlResponse(
    id: "client:" + requests.map(\.id).joined(separator: "+"),
    count: requests.reduce(0) { $0 + $1.count },
    payload: "client:" + requests.map(\.payload).joined(separator: "+"))
}

private func stream(_ requests: [GrpcFdl.GrpcFdlRequest]) -> AsyncStream<GrpcFdl.GrpcFdlRequest> {
  AsyncStream { continuation in
    for request in requests { continuation.yield(request) }
    continuation.finish()
  }
}

private final class FdlService: GrpcFdl_FdlGrpcServiceAsyncProvider {
  func unaryMessage(request: GrpcFdl.GrpcFdlRequest, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFdl.GrpcFdlResponse
  {
    response(request, "unary", 10)
  }
  func serverStreamMessage(
    request: GrpcFdl.GrpcFdlRequest,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    for i in 0..<3 { try await responseStream.send(response(request, "server-\(i)", i)) }
  }
  func clientStreamMessage(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlRequest>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFdl.GrpcFdlResponse {
    var requests: [GrpcFdl.GrpcFdlRequest] = []
    for try await request in requestStream { requests.append(request) }
    return aggregate(requests)
  }
  func bidiStreamMessage(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlRequest>,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await request in requestStream {
      try await responseStream.send(response(request, "bidi-\(index)", index))
      index += 1
    }
  }
  func unaryUnion(request: GrpcFdl.GrpcFdlUnion, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFdl.GrpcFdlUnion
  {
    request
  }
  func serverStreamUnion(
    request: GrpcFdl.GrpcFdlUnion,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    try await responseStream.send(request)
  }
  func clientStreamUnion(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFdl.GrpcFdlUnion {
    var last = GrpcFdl.GrpcFdlUnion.response(GrpcFdl.GrpcFdlResponse())
    for try await union in requestStream { last = union }
    return last
  }
  func bidiStreamUnion(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlUnion>,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    for try await union in requestStream { try await responseStream.send(union) }
  }
}

// Hosts the generated provider in-process and exercises the generated async client
// across all four streaming modes (the relocated SwiftPM build-and-run fixture).
final class RoundTripTests: XCTestCase {
  func testInProcessAllStreamingModes() async throws {
    let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    defer { try? group.syncShutdownGracefully() }
    let server = try await Server.insecure(group: group)
      .withServiceProviders([FdlService()])
      .bind(host: "127.0.0.1", port: 0)
      .get()
    defer { try? server.close().wait() }
    let port = server.channel.localAddress!.port!
    let channel = try GRPCChannelPool.with(
      target: .host("127.0.0.1", port: port),
      transportSecurity: .plaintext,
      eventLoopGroup: group)
    defer { try? channel.close().wait() }

    let client = GrpcFdl_FdlGrpcServiceAsyncClient(channel: channel)
    let first = GrpcFdl.GrpcFdlRequest(id: "a", count: 1, payload: "alpha")
    let requests = [first, GrpcFdl.GrpcFdlRequest(id: "b", count: 2, payload: "beta")]

    let unary = try await client.unaryMessage(first)
    XCTAssertEqual(unary, response(first, "unary", 10))

    var served: [GrpcFdl.GrpcFdlResponse] = []
    for try await message in client.serverStreamMessage(first) { served.append(message) }
    XCTAssertEqual(served, [response(first, "server-0", 0), response(first, "server-1", 1), response(first, "server-2", 2)])

    let aggregated = try await client.clientStreamMessage(stream(requests))
    XCTAssertEqual(aggregated, aggregate(requests))

    var bidi: [GrpcFdl.GrpcFdlResponse] = []
    for try await message in client.bidiStreamMessage(stream(requests)) { bidi.append(message) }
    XCTAssertEqual(bidi, [response(requests[0], "bidi-0", 0), response(requests[1], "bidi-1", 1)])
  }
}
