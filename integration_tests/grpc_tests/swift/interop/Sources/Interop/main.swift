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

// Swift peer for the Java-driven gRPC interop tests. Mirrors the Go and Rust
// peers and the Java GrpcTestBase transforms.
//
//   server --port-file <path>   start a server for all schemas, write the port
//   client --target host:port   connect and exercise all schemas, both ways

import Foundation
import ForyGrpcGenerated
import GRPC
import NIOPosix

// MARK: - Shared values

private func fail(_ message: String) -> Never {
  FileHandle.standardError.write(Data((message + "\n").utf8))
  exit(1)
}

private func expect<T: Equatable>(_ got: T, _ want: T, _ what: String) {
  if got != want { fail("\(what): got \(got), want \(want)") }
}

private func stream<T>(_ values: [T]) -> AsyncStream<T> {
  AsyncStream { continuation in
    for value in values { continuation.yield(value) }
    continuation.finish()
  }
}

// MARK: - FDL

private func fdlResponse(
  _ request: GrpcFdl.GrpcFdlRequest, _ tag: String, _ offset: Int32
) -> GrpcFdl.GrpcFdlResponse {
  GrpcFdl.GrpcFdlResponse(
    id: "\(tag):\(request.id)", count: request.count + offset, payload: "\(tag):\(request.payload)")
}

private func fdlAggregate(_ requests: [GrpcFdl.GrpcFdlRequest]) -> GrpcFdl.GrpcFdlResponse {
  GrpcFdl.GrpcFdlResponse(
    id: "client:" + requests.map(\.id).joined(separator: "+"),
    count: requests.reduce(0) { $0 + $1.count },
    payload: "client:" + requests.map(\.payload).joined(separator: "+"))
}

private func fdlRequest(_ union: GrpcFdl.GrpcFdlUnion) -> GrpcFdl.GrpcFdlRequest {
  guard case .request(let request) = union else { fail("fdl: expected request union") }
  return request
}

private func fdlUnionResponse(
  _ request: GrpcFdl.GrpcFdlRequest, _ tag: String, _ offset: Int32
) -> GrpcFdl.GrpcFdlUnion {
  .response(fdlResponse(request, tag, offset))
}

private final class FdlService: GrpcFdl_FdlGrpcServiceAsyncProvider {
  func unaryMessage(request: GrpcFdl.GrpcFdlRequest, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFdl.GrpcFdlResponse
  { fdlResponse(request, "unary", 10) }

  func serverStreamMessage(
    request: GrpcFdl.GrpcFdlRequest,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    for i in 0..<3 { try await responseStream.send(fdlResponse(request, "server-\(i)", Int32(i))) }
  }

  func clientStreamMessage(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlRequest>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFdl.GrpcFdlResponse {
    var requests: [GrpcFdl.GrpcFdlRequest] = []
    for try await request in requestStream { requests.append(request) }
    return fdlAggregate(requests)
  }

  func bidiStreamMessage(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlRequest>,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await request in requestStream {
      try await responseStream.send(fdlResponse(request, "bidi-\(index)", Int32(index)))
      index += 1
    }
  }

  func unaryUnion(request: GrpcFdl.GrpcFdlUnion, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFdl.GrpcFdlUnion
  { fdlUnionResponse(fdlRequest(request), "unary", 10) }

  func serverStreamUnion(
    request: GrpcFdl.GrpcFdlUnion,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    let value = fdlRequest(request)
    for i in 0..<3 { try await responseStream.send(fdlUnionResponse(value, "server-\(i)", Int32(i))) }
  }

  func clientStreamUnion(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFdl.GrpcFdlUnion {
    var requests: [GrpcFdl.GrpcFdlRequest] = []
    for try await union in requestStream { requests.append(fdlRequest(union)) }
    return .response(fdlAggregate(requests))
  }

  func bidiStreamUnion(
    requestStream: GrpcFdl_FdlGrpcServiceAsyncRequestStream<GrpcFdl.GrpcFdlUnion>,
    responseStream: GrpcFdl_FdlGrpcServiceAsyncResponseStream<GrpcFdl.GrpcFdlUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await union in requestStream {
      try await responseStream.send(fdlUnionResponse(fdlRequest(union), "bidi-\(index)", Int32(index)))
      index += 1
    }
  }
}

private func exerciseFdl(_ channel: GRPCChannel) async throws {
  let client = GrpcFdl_FdlGrpcServiceAsyncClient(channel: channel)
  let requests = [
    GrpcFdl.GrpcFdlRequest(id: "fdl-a", count: 1, payload: "alpha"),
    GrpcFdl.GrpcFdlRequest(id: "fdl-b", count: 2, payload: "beta"),
  ]
  let first = requests[0]
  expect(try await client.unaryMessage(first), fdlResponse(first, "unary", 10), "fdl.unary")

  var served: [GrpcFdl.GrpcFdlResponse] = []
  for try await m in client.serverStreamMessage(first) { served.append(m) }
  expect(
    served,
    [fdlResponse(first, "server-0", 0), fdlResponse(first, "server-1", 1), fdlResponse(first, "server-2", 2)],
    "fdl.serverStream")

  expect(try await client.clientStreamMessage(stream(requests)), fdlAggregate(requests), "fdl.clientStream")

  var bidi: [GrpcFdl.GrpcFdlResponse] = []
  for try await m in client.bidiStreamMessage(stream(requests)) { bidi.append(m) }
  expect(bidi, [fdlResponse(requests[0], "bidi-0", 0), fdlResponse(requests[1], "bidi-1", 1)], "fdl.bidi")

  let unions = [
    GrpcFdl.GrpcFdlUnion.request(GrpcFdl.GrpcFdlRequest(id: "fdl-u-a", count: 3, payload: "union-alpha")),
    GrpcFdl.GrpcFdlUnion.request(GrpcFdl.GrpcFdlRequest(id: "fdl-u-b", count: 4, payload: "union-beta")),
  ]
  let firstReq = fdlRequest(unions[0])
  expect(try await client.unaryUnion(unions[0]), fdlUnionResponse(firstReq, "unary", 10), "fdl.unaryUnion")

  var servedU: [GrpcFdl.GrpcFdlUnion] = []
  for try await m in client.serverStreamUnion(unions[0]) { servedU.append(m) }
  expect(
    servedU,
    [fdlUnionResponse(firstReq, "server-0", 0), fdlUnionResponse(firstReq, "server-1", 1), fdlUnionResponse(firstReq, "server-2", 2)],
    "fdl.serverStreamUnion")

  let aggU = GrpcFdl.GrpcFdlUnion.response(fdlAggregate(unions.map(fdlRequest)))
  expect(try await client.clientStreamUnion(stream(unions)), aggU, "fdl.clientStreamUnion")

  var bidiU: [GrpcFdl.GrpcFdlUnion] = []
  for try await m in client.bidiStreamUnion(stream(unions)) { bidiU.append(m) }
  expect(
    bidiU,
    [fdlUnionResponse(fdlRequest(unions[0]), "bidi-0", 0), fdlUnionResponse(fdlRequest(unions[1]), "bidi-1", 1)],
    "fdl.bidiUnion")
}

// MARK: - FBS

private func fbsResponse(
  _ request: GrpcFbs.GrpcFbsRequest, _ tag: String, _ offset: Int32
) -> GrpcFbs.GrpcFbsResponse {
  GrpcFbs.GrpcFbsResponse(
    id: "\(tag):\(request.id)", count: request.count + offset, payload: "\(tag):\(request.payload)")
}

private func fbsAggregate(_ requests: [GrpcFbs.GrpcFbsRequest]) -> GrpcFbs.GrpcFbsResponse {
  GrpcFbs.GrpcFbsResponse(
    id: "client:" + requests.map(\.id).joined(separator: "+"),
    count: requests.reduce(0) { $0 + $1.count },
    payload: "client:" + requests.map(\.payload).joined(separator: "+"))
}

private func fbsRequest(_ union: GrpcFbs.GrpcFbsUnion) -> GrpcFbs.GrpcFbsRequest {
  guard case .grpcFbsRequest(let request) = union else { fail("fbs: expected request union") }
  return request
}

private func fbsUnionResponse(
  _ request: GrpcFbs.GrpcFbsRequest, _ tag: String, _ offset: Int32
) -> GrpcFbs.GrpcFbsUnion {
  .grpcFbsResponse(fbsResponse(request, tag, offset))
}

private final class FbsService: GrpcFbs_FbsGrpcServiceAsyncProvider {
  func unaryMessage(request: GrpcFbs.GrpcFbsRequest, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFbs.GrpcFbsResponse
  { fbsResponse(request, "unary", 10) }

  func serverStreamMessage(
    request: GrpcFbs.GrpcFbsRequest,
    responseStream: GrpcFbs_FbsGrpcServiceAsyncResponseStream<GrpcFbs.GrpcFbsResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    for i in 0..<3 { try await responseStream.send(fbsResponse(request, "server-\(i)", Int32(i))) }
  }

  func clientStreamMessage(
    requestStream: GrpcFbs_FbsGrpcServiceAsyncRequestStream<GrpcFbs.GrpcFbsRequest>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFbs.GrpcFbsResponse {
    var requests: [GrpcFbs.GrpcFbsRequest] = []
    for try await request in requestStream { requests.append(request) }
    return fbsAggregate(requests)
  }

  func bidiStreamMessage(
    requestStream: GrpcFbs_FbsGrpcServiceAsyncRequestStream<GrpcFbs.GrpcFbsRequest>,
    responseStream: GrpcFbs_FbsGrpcServiceAsyncResponseStream<GrpcFbs.GrpcFbsResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await request in requestStream {
      try await responseStream.send(fbsResponse(request, "bidi-\(index)", Int32(index)))
      index += 1
    }
  }

  func unaryUnion(request: GrpcFbs.GrpcFbsUnion, context: GRPCAsyncServerCallContext)
    async throws -> GrpcFbs.GrpcFbsUnion
  { fbsUnionResponse(fbsRequest(request), "unary", 10) }

  func serverStreamUnion(
    request: GrpcFbs.GrpcFbsUnion,
    responseStream: GrpcFbs_FbsGrpcServiceAsyncResponseStream<GrpcFbs.GrpcFbsUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    let value = fbsRequest(request)
    for i in 0..<3 { try await responseStream.send(fbsUnionResponse(value, "server-\(i)", Int32(i))) }
  }

  func clientStreamUnion(
    requestStream: GrpcFbs_FbsGrpcServiceAsyncRequestStream<GrpcFbs.GrpcFbsUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcFbs.GrpcFbsUnion {
    var requests: [GrpcFbs.GrpcFbsRequest] = []
    for try await union in requestStream { requests.append(fbsRequest(union)) }
    return .grpcFbsResponse(fbsAggregate(requests))
  }

  func bidiStreamUnion(
    requestStream: GrpcFbs_FbsGrpcServiceAsyncRequestStream<GrpcFbs.GrpcFbsUnion>,
    responseStream: GrpcFbs_FbsGrpcServiceAsyncResponseStream<GrpcFbs.GrpcFbsUnion>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await union in requestStream {
      try await responseStream.send(fbsUnionResponse(fbsRequest(union), "bidi-\(index)", Int32(index)))
      index += 1
    }
  }
}

private func exerciseFbs(_ channel: GRPCChannel) async throws {
  let client = GrpcFbs_FbsGrpcServiceAsyncClient(channel: channel)
  let requests = [
    GrpcFbs.GrpcFbsRequest(id: "fbs-a", count: 5, payload: "alpha"),
    GrpcFbs.GrpcFbsRequest(id: "fbs-b", count: 6, payload: "beta"),
  ]
  let first = requests[0]
  expect(try await client.unaryMessage(first), fbsResponse(first, "unary", 10), "fbs.unary")

  var served: [GrpcFbs.GrpcFbsResponse] = []
  for try await m in client.serverStreamMessage(first) { served.append(m) }
  expect(
    served,
    [fbsResponse(first, "server-0", 0), fbsResponse(first, "server-1", 1), fbsResponse(first, "server-2", 2)],
    "fbs.serverStream")

  expect(try await client.clientStreamMessage(stream(requests)), fbsAggregate(requests), "fbs.clientStream")

  var bidi: [GrpcFbs.GrpcFbsResponse] = []
  for try await m in client.bidiStreamMessage(stream(requests)) { bidi.append(m) }
  expect(bidi, [fbsResponse(requests[0], "bidi-0", 0), fbsResponse(requests[1], "bidi-1", 1)], "fbs.bidi")

  let unions = [
    GrpcFbs.GrpcFbsUnion.grpcFbsRequest(GrpcFbs.GrpcFbsRequest(id: "fbs-u-a", count: 7, payload: "union-alpha")),
    GrpcFbs.GrpcFbsUnion.grpcFbsRequest(GrpcFbs.GrpcFbsRequest(id: "fbs-u-b", count: 8, payload: "union-beta")),
  ]
  let firstReq = fbsRequest(unions[0])
  expect(try await client.unaryUnion(unions[0]), fbsUnionResponse(firstReq, "unary", 10), "fbs.unaryUnion")

  var servedU: [GrpcFbs.GrpcFbsUnion] = []
  for try await m in client.serverStreamUnion(unions[0]) { servedU.append(m) }
  expect(
    servedU,
    [fbsUnionResponse(firstReq, "server-0", 0), fbsUnionResponse(firstReq, "server-1", 1), fbsUnionResponse(firstReq, "server-2", 2)],
    "fbs.serverStreamUnion")

  let aggU = GrpcFbs.GrpcFbsUnion.grpcFbsResponse(fbsAggregate(unions.map(fbsRequest)))
  expect(try await client.clientStreamUnion(stream(unions)), aggU, "fbs.clientStreamUnion")

  var bidiU: [GrpcFbs.GrpcFbsUnion] = []
  for try await m in client.bidiStreamUnion(stream(unions)) { bidiU.append(m) }
  expect(
    bidiU,
    [fbsUnionResponse(fbsRequest(unions[0]), "bidi-0", 0), fbsUnionResponse(fbsRequest(unions[1]), "bidi-1", 1)],
    "fbs.bidiUnion")
}

// MARK: - PB

private func pbResponsePayload(
  _ payload: GrpcPb.GrpcPbRequest.Payload?, _ tag: String, _ offset: UInt32
) -> GrpcPb.GrpcPbResponse.Payload? {
  switch payload {
  case .text(let text): return .text("\(tag):\(text)")
  case .number(let number): return .number(number + offset)
  default: return nil
  }
}

private func pbResponse(
  _ request: GrpcPb.GrpcPbRequest, _ tag: String, _ offset: UInt32
) -> GrpcPb.GrpcPbResponse {
  GrpcPb.GrpcPbResponse(
    id: "\(tag):\(request.id)",
    count: request.count + offset,
    payload: pbResponsePayload(request.payload, tag, offset))
}

private func pbAggregate(_ requests: [GrpcPb.GrpcPbRequest]) -> GrpcPb.GrpcPbResponse {
  let ids = requests.map(\.id).joined(separator: "+")
  return GrpcPb.GrpcPbResponse(
    id: "client:" + ids,
    count: requests.reduce(0) { $0 + $1.count },
    payload: .text("client:" + ids))
}

private final class PbService: GrpcPb_PbGrpcServiceAsyncProvider {
  func unaryMessage(request: GrpcPb.GrpcPbRequest, context: GRPCAsyncServerCallContext)
    async throws -> GrpcPb.GrpcPbResponse
  { pbResponse(request, "unary", 10) }

  func serverStreamMessage(
    request: GrpcPb.GrpcPbRequest,
    responseStream: GrpcPb_PbGrpcServiceAsyncResponseStream<GrpcPb.GrpcPbResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    for i in 0..<3 { try await responseStream.send(pbResponse(request, "server-\(i)", UInt32(i))) }
  }

  func clientStreamMessage(
    requestStream: GrpcPb_PbGrpcServiceAsyncRequestStream<GrpcPb.GrpcPbRequest>,
    context: GRPCAsyncServerCallContext
  ) async throws -> GrpcPb.GrpcPbResponse {
    var requests: [GrpcPb.GrpcPbRequest] = []
    for try await request in requestStream { requests.append(request) }
    return pbAggregate(requests)
  }

  func bidiStreamMessage(
    requestStream: GrpcPb_PbGrpcServiceAsyncRequestStream<GrpcPb.GrpcPbRequest>,
    responseStream: GrpcPb_PbGrpcServiceAsyncResponseStream<GrpcPb.GrpcPbResponse>,
    context: GRPCAsyncServerCallContext
  ) async throws {
    var index = 0
    for try await request in requestStream {
      try await responseStream.send(pbResponse(request, "bidi-\(index)", UInt32(index)))
      index += 1
    }
  }
}

private func exercisePb(_ channel: GRPCChannel) async throws {
  let client = GrpcPb_PbGrpcServiceAsyncClient(channel: channel)
  let requests = [
    GrpcPb.GrpcPbRequest(id: "pb-a", count: 9, payload: .text("alpha")),
    GrpcPb.GrpcPbRequest(id: "pb-b", count: 10, payload: .number(42)),
  ]
  let first = requests[0]
  expect(try await client.unaryMessage(first), pbResponse(first, "unary", 10), "pb.unary")

  var served: [GrpcPb.GrpcPbResponse] = []
  for try await m in client.serverStreamMessage(first) { served.append(m) }
  expect(
    served,
    [pbResponse(first, "server-0", 0), pbResponse(first, "server-1", 1), pbResponse(first, "server-2", 2)],
    "pb.serverStream")

  expect(try await client.clientStreamMessage(stream(requests)), pbAggregate(requests), "pb.clientStream")

  var bidi: [GrpcPb.GrpcPbResponse] = []
  for try await m in client.bidiStreamMessage(stream(requests)) { bidi.append(m) }
  expect(bidi, [pbResponse(requests[0], "bidi-0", 0), pbResponse(requests[1], "bidi-1", 1)], "pb.bidi")
}

// MARK: - Driver

private func portFileArgument() -> String? {
  let args = CommandLine.arguments
  if let i = args.firstIndex(of: "--port-file"), i + 1 < args.count { return args[i + 1] }
  return nil
}

private func targetArgument() -> String? {
  let args = CommandLine.arguments
  if let i = args.firstIndex(of: "--target"), i + 1 < args.count { return args[i + 1] }
  return nil
}

private func runServer() async throws {
  let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
  let server = try await Server.insecure(group: group)
    .withServiceProviders([FdlService(), FbsService(), PbService()])
    .bind(host: "127.0.0.1", port: 0)
    .get()
  let port = server.channel.localAddress!.port!
  if let path = portFileArgument() {
    try "\(port)\n".write(toFile: path, atomically: true, encoding: .utf8)
  }
  try await server.onClose.get()
}

private func runClient() async throws {
  guard let target = targetArgument() else { fail("client: missing --target host:port") }
  let parts = target.split(separator: ":")
  guard parts.count == 2, let port = Int(parts[1]) else { fail("client: bad --target \(target)") }
  let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
  let channel = try GRPCChannelPool.with(
    target: .host(String(parts[0]), port: port),
    transportSecurity: .plaintext,
    eventLoopGroup: group)
  do {
    try await exerciseFdl(channel)
    try await exerciseFbs(channel)
    try await exercisePb(channel)
  } catch {
    try? await channel.close().get()
    throw error
  }
  try await channel.close().get()
  print("swift interop ok")
}

let mode = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ""
switch mode {
case "server": try await runServer()
case "client": try await runClient()
default: fail("usage: interop server --port-file <path> | client --target host:port")
}
