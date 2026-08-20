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

public protocol BenchmarkRecord {
    var id: Int32 { get }
    var name: String { get }
}

public struct ExternalRecord: BenchmarkRecord, Equatable, Hashable {
    public var id: Int32
    public var name: String

    public init(id: Int32, name: String) {
        self.id = id
        self.name = name
    }
}

public struct CustomRecord: BenchmarkRecord, Equatable, Hashable {
    public var id: Int32
    public var name: String

    public init(id: Int32, name: String) {
        self.id = id
        self.name = name
    }
}

public struct ExternalRecordHolder: Equatable {
    public var record: ExternalRecord

    public init(record: ExternalRecord) {
        self.record = record
    }
}

public struct ExternalCompositeHolder: Equatable {
    public var optional: ExternalRecord?
    public var records: [ExternalRecord]
    public var recordSet: Set<ExternalRecord>
    public var recordsByName: [String: ExternalRecord]
    public var nested: [String: [ExternalRecord?]]

    public init(
        optional: ExternalRecord?,
        records: [ExternalRecord],
        recordSet: Set<ExternalRecord>,
        recordsByName: [String: ExternalRecord],
        nested: [String: [ExternalRecord?]]
    ) {
        self.optional = optional
        self.records = records
        self.recordSet = recordSet
        self.recordsByName = recordsByName
        self.nested = nested
    }
}
