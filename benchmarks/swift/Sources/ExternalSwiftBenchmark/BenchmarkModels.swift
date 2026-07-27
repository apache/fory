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

import ExternalBenchmarkModels
import Fory

@ForyStruct
struct DirectRecord: BenchmarkRecord, Equatable, Hashable {
    var id: Int32 = 0
    var name: String = ""
}

@ForyStruct(target: ExternalRecord.self)
struct ExternalRecordSerializer {
    var id: Int32
    var name: String
}

struct DirectManualRecord: BenchmarkRecord, Equatable, Hashable, Serializer {
    typealias Target = Self

    var id: Int32 = 0
    var name: String = ""

    static var staticTypeId: TypeId { .ext }

    static func defaultValue(_: ReadContext) throws -> Self {
        Self()
    }

    static func writeData(_ value: Self, _ context: WriteContext) throws {
        try Int32.writeData(value.id, context)
        try String.writeData(value.name, context)
    }

    static func readData(_ context: ReadContext) throws -> Self {
        Self(
            id: try Int32.readData(context),
            name: try String.readData(context)
        )
    }
}

enum ManualRecordSerializer: Serializer {
    typealias Target = ManualRecord

    static var staticTypeId: TypeId { .ext }

    static func defaultValue(_: ReadContext) throws -> Target {
        Target(id: 0, name: "")
    }

    static func writeData(_ value: Target, _ context: WriteContext) throws {
        try Int32.writeData(value.id, context)
        try String.writeData(value.name, context)
    }

    static func readData(_ context: ReadContext) throws -> Target {
        Target(
            id: try Int32.readData(context),
            name: try String.readData(context)
        )
    }
}

@ForyStruct
struct DirectRecordHolder: Equatable {
    var record: DirectRecord = DirectRecord()
}

@ForyStruct(target: ExternalRecordHolder.self)
struct ExternalRecordHolderSerializer {
    @ForyField(with: ExternalRecordSerializer.self)
    var record: ExternalRecord
}

@ForyStruct
struct DirectCompositeHolder: Equatable {
    var optional: DirectRecord?
    var records: [DirectRecord] = []
    var recordSet: Set<DirectRecord> = []
    var recordsByName: [String: DirectRecord] = [:]
    var nested: [String: [DirectRecord?]] = [:]
}

@ForyStruct(target: ExternalCompositeHolder.self)
struct ExternalCompositeHolderSerializer {
    @ForyField(with: OptionalSerializer<ExternalRecordSerializer>.self)
    var optional: ExternalRecord?

    @ListField(element: .with(ExternalRecordSerializer.self))
    var records: [ExternalRecord]

    @SetField(element: .with(ExternalRecordSerializer.self))
    var recordSet: Set<ExternalRecord>

    @MapField(value: .with(ExternalRecordSerializer.self))
    var recordsByName: [String: ExternalRecord]

    @MapField(value: .list(element: .with(OptionalSerializer<ExternalRecordSerializer>.self)))
    var nested: [String: [ExternalRecord?]]
}
