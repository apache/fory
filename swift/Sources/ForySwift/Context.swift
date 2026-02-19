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

public final class WriteContext {
    public let writer: ByteWriter
    public let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let refWriter: RefWriter

    public init(writer: ByteWriter, typeResolver: TypeResolver, trackRef: Bool, compatible: Bool = false) {
        self.writer = writer
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.refWriter = RefWriter()
    }

    public func reset() {
        refWriter.reset()
    }
}

private struct PendingRefSlot {
    var refID: UInt32
    var bound: Bool
}

public final class ReadContext {
    public let reader: ByteReader
    public let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let refReader: RefReader

    private var pendingRefStack: [PendingRefSlot] = []

    public init(reader: ByteReader, typeResolver: TypeResolver, trackRef: Bool, compatible: Bool = false) {
        self.reader = reader
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.refReader = RefReader()
    }

    public func pushPendingReference(_ refID: UInt32) {
        pendingRefStack.append(PendingRefSlot(refID: refID, bound: false))
    }

    public func bindPendingReference(_ value: Any) {
        guard var last = pendingRefStack.popLast() else {
            return
        }
        last.bound = true
        refReader.storeRef(value, at: last.refID)
        pendingRefStack.append(last)
    }

    public func finishPendingReferenceIfNeeded(_ value: Any) {
        guard var last = pendingRefStack.popLast() else {
            return
        }
        if !last.bound {
            refReader.storeRef(value, at: last.refID)
            last.bound = true
        }
    }

    public func popPendingReference() {
        _ = pendingRefStack.popLast()
    }

    public func reset() {
        refReader.reset()
        pendingRefStack.removeAll(keepingCapacity: true)
    }
}
