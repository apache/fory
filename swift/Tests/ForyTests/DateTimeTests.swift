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
import Testing
@testable import Fory

@ForyStruct
private struct DateMacroHolder {
    var day: LocalDate = LocalDate()

    var instant: Date = Date(timeIntervalSince1970: 0)
    var timestamp: Date = Date(timeIntervalSince1970: 0)
}

private func localDate(_ epochDay: Int64) -> LocalDate {
    .init(epochDay: epochDay)
}

@Test
func dateAndTimestampTypeIds() {
    #expect(Duration.staticTypeId == .duration)
    #expect(LocalDate.staticTypeId == .date)
    #expect(Date.staticTypeId == .timestamp)
}

@Test
func dateAndTimestampRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: true))

    let day = localDate(18_745)
    let dayData = try fory.serialize(day)
    let dayDecoded: LocalDate = try fory.deserialize(dayData)
    #expect(dayDecoded == day)

    for boundary in [Int64.min, Int64.max] {
        let boundaryDate = LocalDate(epochDay: boundary)
        let boundaryData = try fory.serialize(boundaryDate)
        let boundaryDecoded: LocalDate = try fory.deserialize(boundaryData)
        #expect(boundaryDecoded == boundaryDate)
    }

    let duration = Duration.seconds(-7) + Duration.nanoseconds(12_000_000)
    let durationData = try fory.serialize(duration)
    let durationDecoded: Duration = try fory.deserialize(durationData)
    #expect(durationDecoded == duration)

    let durationBody = ByteBuffer()
    let durationContext = WriteContext(
        buffer: durationBody,
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try Duration.writeData(Duration.milliseconds(-500), durationContext)
    #expect(try durationBody.readVarInt64() == -1)
    #expect(try durationBody.readInt32() == 500_000_000)

    var malformedDuration = try fory.serialize(Duration.zero)
    let invalidNanos = ByteBuffer()
    invalidNanos.writeInt32(1_000_000_000)
    malformedDuration.replaceSubrange(
        (malformedDuration.count - 4)..<malformedDuration.count,
        with: invalidNanos.copyToData()
    )
    #expect(throws: (any Error).self) {
        let _: Duration = try fory.deserialize(malformedDuration)
    }
    let durationAfterFailure: Duration = try fory.deserialize(durationData)
    #expect(durationAfterFailure == duration)

    let instant = Date(timeIntervalSince1970: 1_731_234_567.123_456_7)
    let instantData = try fory.serialize(instant)
    let instantDecoded: Date = try fory.deserialize(instantData)
    let diff = abs(instantDecoded.timeIntervalSince1970 - instant.timeIntervalSince1970)
    #expect(diff < 0.000_001)
}

@Test
func localDateConvenienceMethodsExposeEpochAndCalendarViews() throws {
    let beforeEpoch = LocalDate.fromEpochDay(-1)
    let leapDay = LocalDate.fromEpochDay(19_782)
    let epoch = try LocalDate(utcDate: Date(timeIntervalSince1970: 0))

    #expect(beforeEpoch.toEpochDay() == -1)
    #expect(beforeEpoch.year == 1969)
    #expect(beforeEpoch.month == 12)
    #expect(beforeEpoch.day == 31)
    #expect(leapDay.year == 2024)
    #expect(leapDay.month == 2)
    #expect(leapDay.day == 29)
    #expect(epoch == .fromEpochDay(0))
    #expect(beforeEpoch < epoch)
    #expect(abs(epoch.toUTCDate().timeIntervalSince1970) < 0.000_001)
}

@Test
func dateAndTimestampContextHelpersUseExpectedWireProtocols() throws {
    let xlangWriteBuffer = ByteBuffer()
    let xlangConfig = Config(trackRef: false, compatible: true, checkClassVersion: true, maxDepth: 5)
    let xlangTypeResolver = TypeResolver(config: xlangConfig)
    let xlangWriteContext = WriteContext(
        buffer: xlangWriteBuffer,
        typeResolver: xlangTypeResolver,
        trackRef: false,
        compatible: true,
        checkClassVersion: true,
        maxDepth: 5
    )

    let xlangLocalDate = localDate(-1)
    try xlangWriteContext.writeLocalDate(xlangLocalDate, refMode: .nullOnly, writeTypeInfo: true)
    #expect(
        Array(xlangWriteBuffer.copyToData()) == [
            UInt8(bitPattern: RefFlag.notNullValue.rawValue),
            UInt8(LocalDate.staticTypeId.rawValue),
            0x01
        ]
    )

    let xlangReadContext = ReadContext(
        buffer: ByteBuffer(data: xlangWriteBuffer.copyToData()),
        typeResolver: xlangTypeResolver,
        config: xlangConfig
    )
    let xlangLocalDateDecoded = try xlangReadContext.readLocalDate(refMode: RefMode.nullOnly, readTypeInfo: true)
    #expect(xlangLocalDateDecoded == xlangLocalDate)

    let timestampBuffer = ByteBuffer()
    let timestampWriteContext = WriteContext(
        buffer: timestampBuffer,
        typeResolver: xlangTypeResolver,
        trackRef: false,
        compatible: true,
        checkClassVersion: true,
        maxDepth: 5
    )
    let instant = Date(timeIntervalSince1970: 123_456.000_001)
    try timestampWriteContext.writeTimestamp(instant, refMode: .nullOnly, writeTypeInfo: true)

    let timestampReadContext = ReadContext(
        buffer: ByteBuffer(data: timestampBuffer.copyToData()),
        typeResolver: xlangTypeResolver,
        config: xlangConfig
    )
    let timestampDecoded = try timestampReadContext.readTimestamp(refMode: RefMode.nullOnly, readTypeInfo: true)
    #expect(abs(timestampDecoded.timeIntervalSince1970 - instant.timeIntervalSince1970) < 0.000_001)
}

@Test
func dateAndTimestampMacroFieldRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: true))
    try fory.register(DateMacroHolder.self, id: 901)

    let value = DateMacroHolder(
        day: localDate(20_001),
        instant: Date(timeIntervalSince1970: 123_456.000_001),
        timestamp: Date(timeIntervalSince1970: 44.000_012_345)
    )

    let data = try fory.serialize(value)
    let decoded: DateMacroHolder = try fory.deserialize(data)

    #expect(decoded.day == value.day)
    let instantDiff = abs(decoded.instant.timeIntervalSince1970 - value.instant.timeIntervalSince1970)
    #expect(instantDiff < 0.000_001)
    let timestampDiff = abs(decoded.timestamp.timeIntervalSince1970 - value.timestamp.timeIntervalSince1970)
    #expect(timestampDiff < 0.000_001)
}
