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

public final class UnknownCase: Equatable, Hashable, CustomDebugStringConvertible {
    public let caseId: UInt32
    public let value: Any?
    // Keep resolver TypeInfo out of the carrier. It is owned by one Fory runtime, while UnknownCase
    // may be shared across runtimes; serializers rebuild Any metadata from this stable wire type id.
    internal let typeId: UInt32

    public init(caseId: UInt32, value: Any?) {
        self.caseId = caseId
        self.value = value
        self.typeId = TypeId.unknown.rawValue
    }

    internal init(caseId: UInt32, typeId: UInt32, value: Any?) {
        self.caseId = caseId
        self.value = value
        self.typeId = typeId
    }

    public static func == (lhs: UnknownCase, rhs: UnknownCase) -> Bool {
        lhs === rhs
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(ObjectIdentifier(self))
    }

    public var debugDescription: String {
        let valueType = value.map { String(describing: Swift.type(of: $0)) } ?? "nil"
        return "UnknownCase(caseId: \(caseId), valueType: \(valueType))"
    }
}
