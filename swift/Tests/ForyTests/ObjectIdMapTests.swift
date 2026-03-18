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

import Testing
@testable import Fory

private final class UInt64MapBox {}

@Test
func uint64MapInsertFindAndUpdate() {
    let map = UInt64Map<UInt32>(initialCapacity: 8)
    let first = UInt64MapBox()
    let second = UInt64MapBox()
    let firstKey = UInt64(UInt(bitPattern: ObjectIdentifier(first)))
    let secondKey = UInt64(UInt(bitPattern: ObjectIdentifier(second)))

    let firstInsert = map.putIfAbsent(3, for: firstKey)
    #expect(firstInsert.inserted)
    #expect(firstInsert.value == 3)

    let secondInsert = map.putIfAbsent(9, for: secondKey)
    #expect(secondInsert.inserted)
    #expect(secondInsert.value == 9)

    let firstExisting = map.putIfAbsent(11, for: firstKey)
    #expect(!firstExisting.inserted)
    #expect(firstExisting.value == 3)

    #expect(map.value(for: firstKey) == 3)
    #expect(map.value(for: secondKey) == 9)
    #expect(map.count == 2)
}

@Test
func uint64MapGrowRetainsEntries() {
    let map = UInt64Map<UInt32>(initialCapacity: 8)
    var boxes: [UInt64MapBox] = []

    for index in 0 ..< 64 {
        let box = UInt64MapBox()
        boxes.append(box)
        let key = UInt64(UInt(bitPattern: ObjectIdentifier(box)))
        let insert = map.putIfAbsent(UInt32(index), for: key)
        #expect(insert.inserted)
        #expect(insert.value == UInt32(index))
    }

    #expect(map.capacity >= 64)
    #expect(map.count == 64)

    for index in 0 ..< boxes.count {
        let key = UInt64(UInt(bitPattern: ObjectIdentifier(boxes[index])))
        #expect(map.value(for: key) == UInt32(index))
    }
}

@Test
func uint64MapClearKeepsCapacityAndClearsEntries() {
    let map = UInt64Map<UInt32>(initialCapacity: 8)
    let box = UInt64MapBox()
    let key = UInt64(UInt(bitPattern: ObjectIdentifier(box)))
    _ = map.putIfAbsent(7, for: key)
    let previousCapacity = map.capacity

    map.clear()

    #expect(map.isEmpty)
    #expect(map.capacity == previousCapacity)
    #expect(map.value(for: key) == nil)
}
