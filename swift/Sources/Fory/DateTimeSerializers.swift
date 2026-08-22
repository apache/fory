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

private let nanosPerSecond: Int64 = 1_000_000_000
private let secondsPerDay = 86_400.0
private let localDateCalendar: Calendar = {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    return calendar
}()

@inline(__always)
private func normalizeTimestampComponents(for date: Date) -> (seconds: Int64, nanos: UInt32) {
    let time = date.timeIntervalSince1970
    let seconds = Int64(floor(time))
    let nanos = Int64((time - Double(seconds)) * Double(nanosPerSecond))
    return normalizeTimestampComponents(seconds: seconds, nanos: nanos)
}

@inline(__always)
private func normalizeTimestampComponents(seconds: Int64, nanos: Int64) -> (seconds: Int64, nanos: UInt32) {
    var normalizedSeconds = seconds + nanos / nanosPerSecond
    var normalizedNanos = nanos % nanosPerSecond
    if normalizedNanos < 0 {
        normalizedNanos += nanosPerSecond
        normalizedSeconds -= 1
    }
    return (normalizedSeconds, UInt32(normalizedNanos))
}

@inline(__always)
private func timestampDate(seconds: Int64, nanos: UInt32) -> Date {
    Date(timeIntervalSince1970: Double(seconds) + Double(nanos) / Double(nanosPerSecond))
}

@inline(__always)
private func localDateEpochDay(for date: Date) throws -> Int32 {
    let days = floor(date.timeIntervalSince1970 / secondsPerDay)
    guard days >= Double(Int32.min), days <= Double(Int32.max) else {
        throw dateEpochOutOfRange()
    }
    return Int32(days)
}

@inline(__always)
private func localDateFromEpochDay(_ epochDay: Int32) -> Date {
    Date(timeIntervalSince1970: Double(epochDay) * secondsPerDay)
}

@inline(__always)
private func localDateComponents(_ epochDay: Int32) -> DateComponents {
    localDateCalendar.dateComponents([.year, .month, .day], from: localDateFromEpochDay(epochDay))
}

@inline(__always)
private func writeScalarValue<T>(
    _ value: T?,
    context: WriteContext,
    refMode: RefMode,
    typeInfo: (write: Bool, id: TypeId),
    writePayload: (T) throws -> Void
) throws {
    switch refMode {
    case .none:
        guard let value else {
            throw nilScalarValue()
        }
        if typeInfo.write {
            context.writeStaticTypeInfo(typeInfo.id)
        }
        try writePayload(value)
    case .nullOnly, .tracking:
        guard let value else {
            context.buffer.writeInt8(RefFlag.null.rawValue)
            return
        }
        context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        if typeInfo.write {
            context.writeStaticTypeInfo(typeInfo.id)
        }
        try writePayload(value)
    }
}

@inline(__always)
private func readScalarNullableValue<T>(
    context: ReadContext,
    refMode: RefMode,
    readPayload: () throws -> T
) throws -> T? {
    switch refMode {
    case .none:
        return try readPayload()
    case .nullOnly:
        let rawFlag = try context.buffer.readInt8()
        switch rawFlag {
        case RefFlag.null.rawValue:
            return nil
        case RefFlag.notNullValue.rawValue:
            return try readPayload()
        case RefFlag.refValue.rawValue:
            if context.trackRef {
                let reservedRefID = context.refReader.reserveRefID()
                let value = try readPayload()
                context.refReader.storeRef(value, at: reservedRefID)
                return value
            }
            return try readPayload()
        case RefFlag.ref.rawValue:
            let refID = try context.buffer.readVarUInt32()
            return try context.refReader.readRef(refID, as: T.self)
        default:
            throw invalidScalarRefFlag(rawFlag)
        }
    case .tracking:
        let rawFlag = try context.buffer.readInt8()
        guard let flag = RefFlag(rawValue: rawFlag) else {
            throw invalidScalarRefFlag(rawFlag)
        }
        switch flag {
        case .null:
            return nil
        case .ref:
            let refID = try context.buffer.readVarUInt32()
            return try context.refReader.readRef(refID, as: T.self)
        case .refValue:
            let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
            let value = try readPayload()
            if let reservedRefID {
                context.refReader.storeRef(value, at: reservedRefID)
            }
            return value
        case .notNullValue:
            return try readPayload()
        }
    }
}

@inline(__always)
private func readTypeID(_ context: ReadContext, expectedTypeIDs: [TypeId]) throws -> TypeId {
    let rawTypeID = UInt32(try context.buffer.readUInt8())
    guard let actualTypeID = TypeId(rawValue: rawTypeID) else {
        throw unknownScalarTypeID(rawTypeID)
    }
    if expectedTypeIDs.contains(actualTypeID) {
        return actualTypeID
    }
    if let expectedTypeID = expectedTypeIDs.first, expectedTypeIDs.count == 1 {
        throw scalarTypeMismatch(expected: expectedTypeID.rawValue, actual: rawTypeID)
    }
    throw unexpectedScalarTypeIDs(expectedTypeIDs, actual: rawTypeID)
}

@inline(never)
private func dateEpochOutOfRange() -> ForyError {
    ForyError.encodingError("date epochDay is out of Int32 range")
}

@inline(never)
private func invalidDateEpochDay() -> ForyError {
    ForyError.invalidData("date epochDay is out of Int32 range")
}

@inline(never)
private func nilScalarValue() -> ForyError {
    ForyError.encodingError("nil value requires nullable ref mode")
}

@inline(never)
private func invalidScalarRefFlag(_ rawFlag: Int8) -> ForyError {
    ForyError.refError("invalid ref flag \(rawFlag)")
}

@inline(never)
private func unknownScalarTypeID(_ rawTypeID: UInt32) -> ForyError {
    ForyError.invalidData("unknown type id \(rawTypeID)")
}

@inline(never)
private func scalarTypeMismatch(expected: UInt32, actual: UInt32) -> ForyError {
    ForyError.typeMismatch(expected: expected, actual: actual)
}

@inline(never)
private func unexpectedScalarTypeIDs(_ expected: [TypeId], actual: UInt32) -> ForyError {
    let expectedList = expected.map(\.rawValue).map(String.init).joined(separator: ", ")
    return ForyError.invalidData("expected one of type ids [\(expectedList)], got \(actual)")
}

/// A calendar date without time-of-day or timezone, encoded as Fory `date`.
public struct LocalDate: Serializer, Equatable, Hashable, Comparable {
    /// Days since 1970-01-01 in the UTC Gregorian calendar.
    public var epochDay: Int32

    public init(epochDay: Int32 = 0) {
        self.epochDay = epochDay
    }

    public static func fromEpochDay(_ epochDay: Int32) -> LocalDate {
        .init(epochDay: epochDay)
    }

    public init(year: Int, month: Int, day: Int) throws {
        var components = DateComponents()
        components.calendar = localDateCalendar
        components.timeZone = localDateCalendar.timeZone
        components.year = year
        components.month = month
        components.day = day
        guard let date = localDateCalendar.date(from: components) else {
            throw ForyError.encodingError("invalid LocalDate components")
        }
        self.epochDay = try localDateEpochDay(for: date)
    }

    public init(utcDate: Date) throws {
        self.epochDay = try localDateEpochDay(for: utcDate)
    }

    public func toEpochDay() -> Int32 {
        epochDay
    }

    public func toUTCDate() -> Date {
        localDateFromEpochDay(epochDay)
    }

    public var year: Int {
        localDateComponents(epochDay).year ?? 1970
    }

    public var month: Int {
        localDateComponents(epochDay).month ?? 1
    }

    public var day: Int {
        localDateComponents(epochDay).day ?? 1
    }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        lhs.epochDay < rhs.epochDay
    }

    public static func defaultValue(_ context: ReadContext) throws -> LocalDate {
        .init()
    }

    public static var staticTypeId: TypeId {
        .date
    }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try context.writeLocalDate(value)
    }

    public static func readData(_ context: ReadContext) throws -> LocalDate {
        try context.readLocalDate()
    }
}

public extension WriteContext {
    @inline(__always)
    func writeTimestamp(_ value: Date) throws {
        let normalized = normalizeTimestampComponents(for: value)
        buffer.writeInt64(normalized.seconds)
        buffer.writeUInt32(normalized.nanos)
    }

    @inline(__always)
    func writeLocalDate(_ value: LocalDate) throws {
        buffer.writeVarInt64(Int64(value.epochDay))
    }

    @inline(__always)
    func writeTimestamp(
        _ value: Date?,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try writeScalarValue(
            value,
            context: self,
            refMode: refMode,
            typeInfo: (write: writeTypeInfo, id: .timestamp),
            writePayload: { try self.writeTimestamp($0) }
        )
    }

    @inline(__always)
    func writeLocalDate(
        _ value: LocalDate?,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try writeScalarValue(
            value,
            context: self,
            refMode: refMode,
            typeInfo: (write: writeTypeInfo, id: .date),
            writePayload: { try self.writeLocalDate($0) }
        )
    }
}

public extension ReadContext {
    @inline(__always)
    func readTimestamp() throws -> Date {
        timestampDate(seconds: try buffer.readInt64(), nanos: try buffer.readUInt32())
    }

    @inline(__always)
    func readLocalDate() throws -> LocalDate {
        guard let epochDay = Int32(exactly: try buffer.readVarInt64()) else {
            throw invalidDateEpochDay()
        }
        return .init(epochDay: epochDay)
    }

    @inline(__always)
    func readNullableTimestamp(
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Date? {
        try readScalarNullableValue(context: self, refMode: refMode) {
            if readTypeInfo {
                _ = try readTypeID(self, expectedTypeIDs: [.timestamp])
            }
            return try self.readTimestamp()
        }
    }

    @inline(__always)
    func readTimestamp(
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Date {
        if let value = try readNullableTimestamp(refMode: refMode, readTypeInfo: readTypeInfo) {
            return value
        }
        return try Date.defaultValue(self)
    }

    @inline(__always)
    func readNullableLocalDate(
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> LocalDate? {
        try readScalarNullableValue(context: self, refMode: refMode) {
            if readTypeInfo {
                _ = try readTypeID(self, expectedTypeIDs: [.date])
            }
            return try self.readLocalDate()
        }
    }

    @inline(__always)
    func readLocalDate(
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> LocalDate {
        if let value = try readNullableLocalDate(refMode: refMode, readTypeInfo: readTypeInfo) {
            return value
        }
        return try LocalDate.defaultValue(self)
    }
}

extension Duration: Serializer {
    public static func defaultValue(_ context: ReadContext) throws -> Duration {
        .zero
    }

    public static var staticTypeId: TypeId {
        .duration
    }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        let components = value.components
        let nanos = components.attoseconds / 1_000_000_000
        let remainder = components.attoseconds % 1_000_000_000
        if remainder != 0 {
            throw ForyError.encodingError("Duration precision finer than nanoseconds is not supported")
        }
        context.buffer.writeVarInt64(components.seconds)
        context.buffer.writeInt32(Int32(nanos))
    }

    public static func readData(_ context: ReadContext) throws -> Duration {
        let seconds = try context.buffer.readVarInt64()
        let nanos = try context.buffer.readInt32()
        return .seconds(seconds) + .nanoseconds(Int64(nanos))
    }
}

extension Date: Serializer {
    public static func defaultValue(_ context: ReadContext) throws -> Date {
        Date(timeIntervalSince1970: 0)
    }

    public static var staticTypeId: TypeId {
        .timestamp
    }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try context.writeTimestamp(value)
    }

    public static func readData(_ context: ReadContext) throws -> Date {
        try context.readTimestamp()
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Date {
        try context.readTimestamp(refMode: refMode, readTypeInfo: readTypeInfo)
    }
}
