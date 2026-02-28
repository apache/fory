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

public struct ForyDate: Serializer, Equatable, Hashable {
    public var daysSinceEpoch: Int32

    public init(daysSinceEpoch: Int32 = 0) {
        self.daysSinceEpoch = daysSinceEpoch
    }

    public static func foryDefault() -> ForyDate {
        .init()
    }

    public static var staticTypeId: TypeId {
        .date
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeInt32(daysSinceEpoch)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyDate {
        .init(daysSinceEpoch: try context.buffer.readInt32())
    }
}

public struct ForyTimestamp: Serializer, Equatable, Hashable {
    public var seconds: Int64
    public var nanos: UInt32

    public init(seconds: Int64 = 0, nanos: UInt32 = 0) {
        let normalized = Self.normalize(seconds: seconds, nanos: Int64(nanos))
        self.seconds = normalized.seconds
        self.nanos = normalized.nanos
    }

    public static func foryDefault() -> ForyTimestamp {
        .init()
    }

    public static var staticTypeId: TypeId {
        .timestamp
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeInt64(seconds)
        context.buffer.writeUInt32(nanos)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyTimestamp {
        .init(seconds: try context.buffer.readInt64(), nanos: try context.buffer.readUInt32())
    }

    public init(date: Date) {
        let time = date.timeIntervalSince1970
        let seconds = Int64(floor(time))
        let nanos = Int64((time - Double(seconds)) * 1_000_000_000.0)
        let normalized = Self.normalize(seconds: seconds, nanos: nanos)
        self.seconds = normalized.seconds
        self.nanos = normalized.nanos
    }

    public func toDate() -> Date {
        Date(timeIntervalSince1970: Double(seconds) + Double(nanos) / 1_000_000_000.0)
    }

    private static func normalize(seconds: Int64, nanos: Int64) -> (seconds: Int64, nanos: UInt32) {
        var normalizedSeconds = seconds + nanos / 1_000_000_000
        var normalizedNanos = nanos % 1_000_000_000
        if normalizedNanos < 0 {
            normalizedNanos += 1_000_000_000
            normalizedSeconds -= 1
        }
        return (normalizedSeconds, UInt32(normalizedNanos))
    }
}

extension Duration: Serializer {
    public static func foryDefault() -> Duration {
        .zero
    }

    public static var staticTypeId: TypeId {
        .duration
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        let components = self.components
        let nanos = components.attoseconds / 1_000_000_000
        let remainder = components.attoseconds % 1_000_000_000
        if remainder != 0 {
            throw ForyError.encodingError("Duration precision finer than nanoseconds is not supported")
        }
        context.buffer.writeVarInt64(components.seconds)
        context.buffer.writeInt32(Int32(nanos))
    }

    public static func foryReadData(_ context: ReadContext) throws -> Duration {
        let seconds = try context.buffer.readVarInt64()
        let nanos = try context.buffer.readInt32()
        return .seconds(seconds) + .nanoseconds(Int64(nanos))
    }
}

extension Date: Serializer {
    public static func foryDefault() -> Date {
        Date(timeIntervalSince1970: 0)
    }

    public static var staticTypeId: TypeId {
        .timestamp
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        let ts = ForyTimestamp(date: self)
        context.buffer.writeInt64(ts.seconds)
        context.buffer.writeUInt32(ts.nanos)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Date {
        let ts = ForyTimestamp(seconds: try context.buffer.readInt64(), nanos: try context.buffer.readUInt32())
        return ts.toDate()
    }
}
