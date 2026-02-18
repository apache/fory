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

public enum ForyError: Error, CustomStringConvertible, Equatable {
    case invalidData(String)
    case typeMismatch(expected: UInt32, actual: UInt32)
    case typeNotRegistered(String)
    case refError(String)
    case encodingError(String)
    case outOfBounds(cursor: Int, need: Int, length: Int)

    public var description: String {
        switch self {
        case .invalidData(let message):
            return "Invalid data: \(message)"
        case .typeMismatch(let expected, let actual):
            return "Type mismatch: expected \(expected), got \(actual)"
        case .typeNotRegistered(let message):
            return "Type not registered: \(message)"
        case .refError(let message):
            return "Reference error: \(message)"
        case .encodingError(let message):
            return "Encoding error: \(message)"
        case .outOfBounds(let cursor, let need, let length):
            return "Buffer out of bounds: cursor=\(cursor), need=\(need), length=\(length)"
        }
    }
}
