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

public enum TypeId: UInt32, CaseIterable {
    case unknown = 0
    case bool = 1
    case int8 = 2
    case int16 = 3
    case int32 = 4
    case varint32 = 5
    case int64 = 6
    case varint64 = 7
    case taggedInt64 = 8
    case uint8 = 9
    case uint16 = 10
    case uint32 = 11
    case varUInt32 = 12
    case uint64 = 13
    case varUInt64 = 14
    case taggedUInt64 = 15
    case float8 = 16
    case float16 = 17
    case bfloat16 = 18
    case float32 = 19
    case float64 = 20
    case string = 21
    case list = 22
    case set = 23
    case map = 24
    case enumType = 25
    case namedEnum = 26
    case structType = 27
    case compatibleStruct = 28
    case namedStruct = 29
    case namedCompatibleStruct = 30
    case ext = 31
    case namedExt = 32
    case union = 33
    case typedUnion = 34
    case namedUnion = 35
    case none = 36
    case duration = 37
    case timestamp = 38
    case date = 39
    case decimal = 40
    case binary = 41
    case array = 42
    case boolArray = 43
    case int8Array = 44
    case int16Array = 45
    case int32Array = 46
    case int64Array = 47
    case uint8Array = 48
    case uint16Array = 49
    case uint32Array = 50
    case uint64Array = 51
    case float8Array = 52
    case float16Array = 53
    case bfloat16Array = 54
    case float32Array = 55
    case float64Array = 56

    public var isUserTypeKind: Bool {
        switch self {
        case .enumType,
             .namedEnum,
             .structType,
             .compatibleStruct,
             .namedStruct,
             .namedCompatibleStruct,
             .ext,
             .namedExt,
             .union,
             .typedUnion,
             .namedUnion:
            return true
        default:
            return false
        }
    }

    public static func needsTypeInfoForField(_ typeId: TypeId) -> Bool {
        switch typeId {
        case .structType,
             .compatibleStruct,
             .namedStruct,
             .namedCompatibleStruct,
             .ext,
             .namedExt,
             .unknown:
            return true
        default:
            return false
        }
    }
}
