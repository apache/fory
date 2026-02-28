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

public final class RefWriter {
    private var refs: [ObjectIdentifier: UInt32] = [:]
    private var nextRefID: UInt32 = 0

    public init() {}

    public func tryWriteReference(buffer: ByteBuffer, object: AnyObject) -> Bool {
        let objectID = ObjectIdentifier(object)
        if let refID = refs[objectID] {
            buffer.writeInt8(RefFlag.ref.rawValue)
            buffer.writeVarUInt32(refID)
            return true
        }
        refs[objectID] = nextRefID
        nextRefID &+= 1
        buffer.writeInt8(RefFlag.refValue.rawValue)
        return false
    }

    public func reserveRefID() -> UInt32 {
        let id = nextRefID
        nextRefID &+= 1
        return id
    }

    public func reset() {
        if !refs.isEmpty {
            refs.removeAll(keepingCapacity: true)
        }
        if nextRefID != 0 {
            nextRefID = 0
        }
    }
}

public final class RefReader {
    private var refs: [Any] = []

    public init() {}

    public func reserveRefID() -> UInt32 {
        let id = UInt32(refs.count)
        refs.append(NSNull())
        return id
    }

    public func storeRef(_ value: Any, at refID: UInt32) {
        refs[Int(refID)] = value
    }

    public func readRef<T>(_ refID: UInt32, as: T.Type) throws -> T {
        let index = Int(refID)
        guard refs.indices.contains(index) else {
            throw ForyError.refError("ref_id out of range: \(refID)")
        }
        guard let value = refs[index] as? T else {
            throw ForyError.refError("ref_id \(refID) has unexpected runtime type")
        }
        return value
    }

    public func readRefValue(_ refID: UInt32) throws -> Any {
        let index = Int(refID)
        guard refs.indices.contains(index) else {
            throw ForyError.refError("ref_id out of range: \(refID)")
        }
        return refs[index]
    }

    public func reset() {
        if !refs.isEmpty {
            refs.removeAll(keepingCapacity: true)
        }
    }
}
