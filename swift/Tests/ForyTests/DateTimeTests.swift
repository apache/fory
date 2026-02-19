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

@ForyObject
private struct DateMacroHolder {
    var day: ForyDate = .init()
    var instant: Date = .foryDefault()
    var timestamp: ForyTimestamp = .init()
}

@Test
func dateAndTimestampTypeIds() {
    #expect(ForyDate.staticTypeId == .date)
    #expect(ForyTimestamp.staticTypeId == .timestamp)
    #expect(Date.staticTypeId == .timestamp)
}

@Test
func dateAndTimestampRoundTrip() throws {
    let fory = Fory()

    let day = ForyDate(daysSinceEpoch: 18_745)
    let dayData = try fory.serialize(day)
    let dayDecoded: ForyDate = try fory.deserialize(dayData)
    #expect(dayDecoded == day)

    let ts = ForyTimestamp(seconds: -123, nanos: 987_654_321)
    let tsData = try fory.serialize(ts)
    let tsDecoded: ForyTimestamp = try fory.deserialize(tsData)
    #expect(tsDecoded == ts)

    let instant = Date(timeIntervalSince1970: 1_731_234_567.123_456_7)
    let instantData = try fory.serialize(instant)
    let instantDecoded: Date = try fory.deserialize(instantData)
    let diff = abs(instantDecoded.timeIntervalSince1970 - instant.timeIntervalSince1970)
    #expect(diff < 0.000_001)
}

@Test
func dateAndTimestampMacroFieldRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(DateMacroHolder.self, id: 901)

    let value = DateMacroHolder(
        day: .init(daysSinceEpoch: 20_001),
        instant: Date(timeIntervalSince1970: 123_456.000_001),
        timestamp: .init(seconds: 44, nanos: 12_345)
    )

    let data = try fory.serialize(value)
    let decoded: DateMacroHolder = try fory.deserialize(data)

    #expect(decoded.day == value.day)
    #expect(decoded.timestamp == value.timestamp)
    let diff = abs(decoded.instant.timeIntervalSince1970 - value.instant.timeIntervalSince1970)
    #expect(diff < 0.000_001)
}
