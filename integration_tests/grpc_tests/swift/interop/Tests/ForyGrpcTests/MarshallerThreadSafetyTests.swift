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
import XCTest

@testable import ForyGrpcGenerated

// The generated marshaller uses one Fory per thread. Run it from many threads
// (under `swift test --sanitize=thread` in CI) to prove there is no data race,
// and confirm it stays wire-compatible with the schema module's shared Fory.
final class MarshallerThreadSafetyTests: XCTestCase {
  func testConcurrentRoundTrip() {
    DispatchQueue.concurrentPerform(iterations: 2000) { i in
      do {
        let allocator = ByteBufferAllocator()
        let request = GrpcFdl.GrpcFdlRequest(id: "n\(i)", count: Int32(i), payload: "p\(i)")
        var buffer = allocator.buffer(capacity: 64)
        try GrpcFdl_FdlGrpcServiceMessage(request).serialize(into: &buffer)
        let back = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
          serializedByteBuffer: &buffer)
        XCTAssertEqual(back.value, request)
      } catch {
        XCTFail("marshaller round-trip failed: \(error)")
      }
    }
  }

  func testWireCompatibleWithModuleFory() throws {
    let allocator = ByteBufferAllocator()
    let probe = GrpcFdl.GrpcFdlRequest(id: "probe", count: 7, payload: "x")

    let sharedBytes = try GrpcFdl.ForyModule.getFory().serialize(probe)
    var inbound = allocator.buffer(capacity: sharedBytes.count)
    inbound.writeBytes(sharedBytes)
    let fromShared = try GrpcFdl_FdlGrpcServiceMessage<GrpcFdl.GrpcFdlRequest>(
      serializedByteBuffer: &inbound)
    XCTAssertEqual(fromShared.value, probe)

    var outbound = allocator.buffer(capacity: 64)
    try GrpcFdl_FdlGrpcServiceMessage(probe).serialize(into: &outbound)
    let fromMarshaller: GrpcFdl.GrpcFdlRequest =
      try GrpcFdl.ForyModule.getFory().deserialize(Data(outbound.readableBytesView))
    XCTAssertEqual(fromMarshaller, probe)
  }
}
