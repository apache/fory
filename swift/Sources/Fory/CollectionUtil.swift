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

final class ReusableArray<Element> {
    private var storage: UnsafeMutablePointer<Element>
    private var capacity: Int
    private let defaultValue: Element
    private(set) var used = 0

    init(defaultValue: Element, reserve: Int = 2) {
        self.defaultValue = defaultValue
        capacity = max(reserve, 2)
        storage = UnsafeMutablePointer<Element>.allocate(capacity: capacity)
        storage.initialize(repeating: defaultValue, count: capacity)
    }

    deinit {
        storage.deinitialize(count: capacity)
        storage.deallocate()
    }

    /// Reset logical usage to zero in O(1) without clearing backing slots.
    ///
    /// This is intentional for hot-path reuse: existing slot values stay in
    /// storage until they are overwritten by later `push` calls or released by
    /// `deinit`.
    @inline(__always)
    func reset() {
        used = 0
    }

    @inline(__always)
    func push(_ value: Element) {
        if used == capacity {
            grow()
        }
        storage.advanced(by: used).pointee = value
        used += 1
    }

    @inline(__always)
    func get(_ index: Int) -> Element {
        guard index >= 0, index < used else {
            return defaultValue
        }
        return storage.advanced(by: index).pointee
    }

    @inline(__always)
    var isEmpty: Bool { used == 0 }

    private func grow() {
        let newCapacity = capacity << 1
        let newStorage = UnsafeMutablePointer<Element>.allocate(capacity: newCapacity)
        newStorage.initialize(repeating: defaultValue, count: newCapacity)
        for index in 0 ..< used {
            newStorage.advanced(by: index).pointee = storage.advanced(by: index).pointee
        }
        storage.deinitialize(count: capacity)
        storage.deallocate()
        storage = newStorage
        capacity = newCapacity
    }
}
