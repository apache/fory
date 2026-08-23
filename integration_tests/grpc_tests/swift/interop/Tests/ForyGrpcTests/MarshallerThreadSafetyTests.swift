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

import Foundation
import NIOCore
import Testing

@testable import ForyGrpcDefaultPackageOne
@testable import ForyGrpcDefaultPackageTwo
@testable import ForyGrpcGenerated

// The generated marshaller uses one Fory per thread. CI runs this suite under
// ThreadSanitizer with `--sanitize=thread --disable-xctest`. It is swift-testing
// rather than XCTest because macOS refuses to load the sanitizer runtime into
// XCTest's platform-signed `xctest` runner.
@Suite struct MarshallerThreadSafetyTests {
  @Test func concurrentRoundTrip() {
    DispatchQueue.concurrentPerform(iterations: 2000) { i in
      do {
        let allocator = ByteBufferAllocator()
        let request = GrpcFdl.GrpcFdlRequest(id: "n\(i)", count: Int32(i), payload: "p\(i)")
        var buffer = allocator.buffer(capacity: 64)
        try GrpcFdl_FdlGrpcServiceMessage(request).serialize(into: &buffer)
        let back = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
          serializedByteBuffer: &buffer)
        #expect(back.value == request)
      } catch {
        Issue.record("marshaller round-trip failed: \(error)")
      }
    }
  }

  @Test func wireCompatibleWithModuleFory() throws {
    let allocator = ByteBufferAllocator()
    let probe = GrpcFdl.GrpcFdlRequest(id: "probe", count: 7, payload: "x")

    let sharedBytes = try GrpcFdl.ForyModule.getFory().serialize(probe)
    var inbound = allocator.buffer(capacity: sharedBytes.count)
    inbound.writeBytes(sharedBytes)
    let fromShared = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
      serializedByteBuffer: &inbound)
    #expect(fromShared.value == probe)

    var outbound = allocator.buffer(capacity: 64)
    try GrpcFdl_FdlGrpcServiceMessage(probe).serialize(into: &outbound)
    let fromMarshaller: GrpcFdl.GrpcFdlRequest =
      try GrpcFdl.ForyModule.getFory().deserialize(Data(outbound.readableBytesView))
    #expect(fromMarshaller == probe)
  }

  // Both schemas are packaged, so this only covers one module.
  @Test func packagedSchemasKeepOwnRuntimeOnOneThread() throws {
    let allocator = ByteBufferAllocator()

    let fdlRequest = GrpcFdl.GrpcFdlRequest(id: "fdl", count: 1, payload: "p")
    var fdlBuffer = allocator.buffer(capacity: 64)
    try GrpcFdl_FdlGrpcServiceMessage(fdlRequest).serialize(into: &fdlBuffer)
    let fdlBack = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
      serializedByteBuffer: &fdlBuffer)
    #expect(fdlBack.value == fdlRequest)

    let fbsRequest = GrpcFbs.GrpcFbsRequest(id: "fbs", count: 2, payload: "q")
    var fbsBuffer = allocator.buffer(capacity: 64)
    try GrpcFbs_FbsGrpcServiceMessage(fbsRequest).serialize(into: &fbsBuffer)
    let fbsBack = try GrpcFbs_FbsGrpcServiceMessage<GrpcFbs.GrpcFbsRequest>(
      serializedByteBuffer: &fbsBuffer)
    #expect(fbsBack.value == fbsRequest)

    var fdlAgain = allocator.buffer(capacity: 64)
    try GrpcFdl_FdlGrpcServiceMessage(fdlRequest).serialize(into: &fdlAgain)
    let fdlSecondPass = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
      serializedByteBuffer: &fdlAgain)
    #expect(fdlSecondPass.value == fdlRequest)
  }

  // Both modules emit a bare `ForyModule`, so their generated key expressions
  // are identical and only runtime module qualification separates them.
  @Test func defaultPackageModulesKeepOwnRuntimeOnOneThread() throws {
    #expect(
      String(reflecting: ForyGrpcDefaultPackageOne.ForyModule.self)
        != String(reflecting: ForyGrpcDefaultPackageTwo.ForyModule.self))
    #expect(
      String(describing: ForyGrpcDefaultPackageOne.ForyModule.self)
        == String(describing: ForyGrpcDefaultPackageTwo.ForyModule.self))

    let allocator = ByteBufferAllocator()

    let one = DefaultPackageOneRequest(id: "one", count: 1)
    var oneBuffer = allocator.buffer(capacity: 64)
    try DefaultPackageOneServiceMessage(one).serialize(into: &oneBuffer)
    let oneBack = try DefaultPackageOneServiceMessage<DefaultPackageOneRequest>(
      serializedByteBuffer: &oneBuffer)
    #expect(oneBack.value == one)

    // Same thread, so a shared key would hand this the other module's Fory.
    let two = DefaultPackageTwoRequest(id: "two", count: 2)
    var twoBuffer = allocator.buffer(capacity: 64)
    try DefaultPackageTwoServiceMessage(two).serialize(into: &twoBuffer)
    let twoBack = try DefaultPackageTwoServiceMessage<DefaultPackageTwoRequest>(
      serializedByteBuffer: &twoBuffer)
    #expect(twoBack.value == two)

    var oneAgain = allocator.buffer(capacity: 64)
    try DefaultPackageOneServiceMessage(one).serialize(into: &oneAgain)
    let oneSecondPass = try DefaultPackageOneServiceMessage<DefaultPackageOneRequest>(
      serializedByteBuffer: &oneAgain)
    #expect(oneSecondPass.value == one)
  }
}
