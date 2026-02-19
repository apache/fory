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

public enum RefFlag: Int8 {
    case null = -3
    case ref = -2
    case notNullValue = -1
    case refValue = 0
}

public enum RefMode: UInt8, Equatable {
    case none = 0
    case nullOnly = 1
    case tracking = 2

    public static func from(nullable: Bool, trackRef: Bool) -> Self {
        if trackRef {
            return .tracking
        }
        return nullable ? .nullOnly : .none
    }
}

public enum ForyHeaderFlag {
    public static let isNull: UInt8 = 0x01
    public static let isXlang: UInt8 = 0x02
    public static let isOutOfBand: UInt8 = 0x04
}
