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
import Foundation
import Fory

struct ExternalBenchmarkConfig {
    var durationSeconds = 3.0
    var caseFilter: String?
}

struct ExternalBenchmarkEntry: Codable {
    let name: String
    let serializer: String
    let dataType: String
    let operation: String
    let iterations: Int
    let totalNs: UInt64
    let nsPerOp: Double
    let opsPerSec: Double
    let bytes: Int
}

struct ExternalBenchmarkContext: Codable {
    let timestamp: String
    let os: String
    let host: String
    let cpuCoresLogical: Int
    let memoryGB: Double
    let durationSeconds: Double
}

struct ExternalBenchmarkOutput: Codable {
    let context: ExternalBenchmarkContext
    let benchmarks: [ExternalBenchmarkEntry]
}

private struct Measurement {
    let iterations: Int
    let totalNs: UInt64
    let nsPerOp: Double
    let opsPerSec: Double
}

final class ExternalBenchmarkSuite {
    private let config: ExternalBenchmarkConfig
    private let fory: Fory

    init(config: ExternalBenchmarkConfig) throws {
        self.config = config
        self.fory = Fory(config: .init(trackRef: false, compatible: false))
        try fory.register(DirectRecord.self, id: 100)
        try fory.register(ExternalRecordSerializer.self, id: 101)
        try fory.register(DirectManualRecord.self, id: 102)
        try fory.register(ManualRecordSerializer.self, id: 103)
        try fory.register(DirectRecordHolder.self, id: 104)
        try fory.register(ExternalRecordHolderSerializer.self, id: 105)
        try fory.register(DirectCompositeHolder.self, id: 106)
        try fory.register(ExternalCompositeHolderSerializer.self, id: 107)
    }

    func run() throws -> ExternalBenchmarkOutput {
        var entries: [ExternalBenchmarkEntry] = []

        let direct = DirectRecord(id: 7, name: "record")
        let external = ExternalRecord(id: 7, name: "record")
        let directManual = DirectManualRecord(id: 7, name: "record")
        let manual = ManualRecord(id: 7, name: "record")

        try appendOrdinaryCase(
            name: "DirectStructural",
            value: direct,
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalStructural",
            value: external,
            serializer: ExternalRecordSerializer.self,
            entries: &entries
        )
        try appendOrdinaryCase(
            name: "DirectManual",
            value: directManual,
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalManual",
            value: manual,
            serializer: ManualRecordSerializer.self,
            entries: &entries
        )

        try appendSelectedComparison(
            names: (direct: "DirectField", external: "ExternalField"),
            values: (
                direct: DirectRecordHolder(record: direct),
                external: ExternalRecordHolder(record: external)
            ),
            serializers: (
                direct: DirectRecordHolder.self,
                external: ExternalRecordHolderSerializer.self
            ),
            entries: &entries
        )

        let directComposite = DirectCompositeHolder(
            optional: direct,
            records: [direct, direct],
            recordSet: [direct],
            recordsByName: ["record": direct],
            nested: ["records": [direct, nil]]
        )
        let externalComposite = ExternalCompositeHolder(
            optional: external,
            records: [external, external],
            recordSet: [external],
            recordsByName: ["record": external],
            nested: ["records": [external, nil]]
        )
        try appendSelectedComparison(
            names: (
                direct: "DirectCompositeField",
                external: "ExternalCompositeField"
            ),
            values: (direct: directComposite, external: externalComposite),
            serializers: (
                direct: DirectCompositeHolder.self,
                external: ExternalCompositeHolderSerializer.self
            ),
            entries: &entries
        )

        try appendOrdinaryCase(
            name: "DirectArrayRoot",
            value: [direct, direct],
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalArrayRoot",
            value: [external, external],
            serializer: ArraySerializer<ExternalRecordSerializer>.self,
            entries: &entries
        )
        try appendOrdinaryCase(
            name: "DirectOptionalRoot",
            value: Optional(direct),
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalOptionalRoot",
            value: Optional(external),
            serializer: OptionalSerializer<ExternalRecordSerializer>.self,
            entries: &entries
        )
        try appendOrdinaryCase(
            name: "DirectSetRoot",
            value: Set([direct]),
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalSetRoot",
            value: Set([external]),
            serializer: SetSerializer<ExternalRecordSerializer>.self,
            entries: &entries
        )
        try appendOrdinaryCase(
            name: "DirectDictionaryRoot",
            value: ["record": direct],
            entries: &entries
        )
        try appendSelectedCase(
            name: "ExternalDictionaryRoot",
            value: ["record": external],
            serializer: DictionarySerializer<String, ExternalRecordSerializer>.self,
            entries: &entries
        )

        try appendSelectedCase(
            name: "DynamicDirect",
            value: direct as any BenchmarkRecord,
            serializer: DynamicSerializer<any BenchmarkRecord>.self,
            entries: &entries
        )
        try appendSelectedCase(
            name: "DynamicExternal",
            value: external as any BenchmarkRecord,
            serializer: DynamicSerializer<any BenchmarkRecord>.self,
            entries: &entries
        )
        try appendSelectedCase(
            name: "DynamicManual",
            value: manual as any BenchmarkRecord,
            serializer: DynamicSerializer<any BenchmarkRecord>.self,
            entries: &entries
        )

        entries.sort { $0.name < $1.name }
        return ExternalBenchmarkOutput(context: makeContext(), benchmarks: entries)
    }

    private func appendOrdinaryCase<T: Serializer>(
        name: String,
        value: T,
        entries: inout [ExternalBenchmarkEntry]
    ) throws where T.Target == T {
        guard shouldRun(name) else {
            return
        }
        let bytes = try fory.serialize(value)
        let decoded: T = try fory.deserialize(bytes)
        withExtendedLifetime(decoded) {}
        try appendMeasuredPair(
            name: name,
            bytes: bytes,
            serialize: {
                try self.fory.serialize(value).count
            },
            deserialize: {
                let decoded: T = try self.fory.deserialize(bytes)
                withExtendedLifetime(decoded) {}
                return bytes.count
            },
            entries: &entries
        )
    }

    private func appendSelectedCase<S: Serializer>(
        name: String,
        value: S.Target,
        serializer: S.Type,
        entries: inout [ExternalBenchmarkEntry]
    ) throws {
        guard shouldRun(name) else {
            return
        }
        let bytes = try fory.serialize(value, with: serializer)
        let decoded = try fory.deserialize(bytes, with: serializer)
        withExtendedLifetime(decoded) {}
        try appendMeasuredPair(
            name: name,
            bytes: bytes,
            serialize: {
                try self.fory.serialize(value, with: serializer).count
            },
            deserialize: {
                let decoded = try self.fory.deserialize(bytes, with: serializer)
                withExtendedLifetime(decoded) {}
                return bytes.count
            },
            entries: &entries
        )
    }

    private func appendSelectedComparison<D: Serializer, E: Serializer>(
        names: (direct: String, external: String),
        values: (direct: D.Target, external: E.Target),
        serializers: (direct: D.Type, external: E.Type),
        entries: inout [ExternalBenchmarkEntry]
    ) throws {
        let runDirect = shouldRun(names.direct)
        let runExternal = shouldRun(names.external)
        guard runDirect || runExternal else {
            return
        }
        guard runDirect && runExternal else {
            if runDirect {
                try appendSelectedCase(
                    name: names.direct,
                    value: values.direct,
                    serializer: serializers.direct,
                    entries: &entries
                )
            } else {
                try appendSelectedCase(
                    name: names.external,
                    value: values.external,
                    serializer: serializers.external,
                    entries: &entries
                )
            }
            return
        }

        let directBytes = try fory.serialize(values.direct, with: serializers.direct)
        let externalBytes = try fory.serialize(values.external, with: serializers.external)
        let directDecoded: D.Target = try fory.deserialize(
            directBytes,
            with: serializers.direct
        )
        let externalDecoded: E.Target = try fory.deserialize(
            externalBytes,
            with: serializers.external
        )
        withExtendedLifetime(directDecoded) {}
        withExtendedLifetime(externalDecoded) {}

        let serialized = try measurePaired(
            { minimumNs, sink, iterations in
                try self.runSerializeChunk(
                    value: values.direct,
                    serializer: serializers.direct,
                    minimumNs: minimumNs,
                    sink: &sink,
                    iterations: &iterations
                )
            },
            { minimumNs, sink, iterations in
                try self.runSerializeChunk(
                    value: values.external,
                    serializer: serializers.external,
                    minimumNs: minimumNs,
                    sink: &sink,
                    iterations: &iterations
                )
            }
        )
        entries.append(
            makeEntry(
                name: names.direct,
                operation: "serialize",
                bytes: directBytes.count,
                measured: serialized.direct
            )
        )
        entries.append(
            makeEntry(
                name: names.external,
                operation: "serialize",
                bytes: externalBytes.count,
                measured: serialized.external
            )
        )

        let deserialized = try measurePaired(
            { minimumNs, sink, iterations in
                try self.runDeserializeChunk(
                    bytes: directBytes,
                    serializer: serializers.direct,
                    minimumNs: minimumNs,
                    sink: &sink,
                    iterations: &iterations
                )
            },
            { minimumNs, sink, iterations in
                try self.runDeserializeChunk(
                    bytes: externalBytes,
                    serializer: serializers.external,
                    minimumNs: minimumNs,
                    sink: &sink,
                    iterations: &iterations
                )
            }
        )
        entries.append(
            makeEntry(
                name: names.direct,
                operation: "deserialize",
                bytes: directBytes.count,
                measured: deserialized.direct
            )
        )
        entries.append(
            makeEntry(
                name: names.external,
                operation: "deserialize",
                bytes: externalBytes.count,
                measured: deserialized.external
            )
        )
    }

    private func appendMeasuredPair(
        name: String,
        bytes: Data,
        serialize: () throws -> Int,
        deserialize: () throws -> Int,
        entries: inout [ExternalBenchmarkEntry]
    ) throws {
        entries.append(
            try measureCase(
                name: name,
                operation: "serialize",
                bytes: bytes.count,
                body: serialize
            )
        )
        entries.append(
            try measureCase(
                name: name,
                operation: "deserialize",
                bytes: bytes.count,
                body: deserialize
            )
        )
    }

    private func shouldRun(_ name: String) -> Bool {
        guard let filter = config.caseFilter else {
            return true
        }
        return name.lowercased().contains(filter.lowercased())
    }

    private func measureCase(
        name: String,
        operation: String,
        bytes: Int,
        body: () throws -> Int
    ) throws -> ExternalBenchmarkEntry {
        makeEntry(
            name: name,
            operation: operation,
            bytes: bytes,
            measured: try measure(body)
        )
    }

    private func makeEntry(
        name: String,
        operation: String,
        bytes: Int,
        measured: Measurement
    ) -> ExternalBenchmarkEntry {
        let operationTitle = operation == "serialize" ? "Serialize" : "Deserialize"
        let benchmarkName = "BM_Fory_\(name)_\(operationTitle)"
        print(
            "\(benchmarkName.padding(toLength: 48, withPad: " ", startingAt: 0)) "
                + "\(measured.iterations) ops  "
                + "\(String(format: "%.2f", measured.nsPerOp)) ns/op  "
                + "\(String(format: "%.2f", measured.opsPerSec)) ops/sec"
        )
        return ExternalBenchmarkEntry(
            name: benchmarkName,
            serializer: "fory",
            dataType: name,
            operation: operation,
            iterations: measured.iterations,
            totalNs: measured.totalNs,
            nsPerOp: measured.nsPerOp,
            opsPerSec: measured.opsPerSec,
            bytes: bytes
        )
    }

    private func measurePaired(
        _ direct: (UInt64, inout Int, inout Int) throws -> UInt64,
        _ external: (UInt64, inout Int, inout Int) throws -> UInt64
    ) throws -> (direct: Measurement, external: Measurement) {
        let warmupNs: UInt64 = 200_000_000
        let durationNs = UInt64(max(config.durationSeconds, 0.1) * 1_000_000_000)
        let chunkNs: UInt64 = 20_000_000
        var directSink = 0
        var externalSink = 0
        var directIterations = 0
        var externalIterations = 0
        var directTotal: UInt64 = 0
        var externalTotal: UInt64 = 0
        var directFirst = true

        while directTotal < warmupNs || externalTotal < warmupNs {
            if directFirst {
                directTotal += try direct(chunkNs, &directSink, &directIterations)
                externalTotal += try external(chunkNs, &externalSink, &externalIterations)
            } else {
                externalTotal += try external(chunkNs, &externalSink, &externalIterations)
                directTotal += try direct(chunkNs, &directSink, &directIterations)
            }
            directFirst.toggle()
        }

        directIterations = 0
        externalIterations = 0
        directTotal = 0
        externalTotal = 0
        directFirst = true
        while directTotal < durationNs || externalTotal < durationNs {
            if directFirst {
                directTotal += try direct(chunkNs, &directSink, &directIterations)
                externalTotal += try external(chunkNs, &externalSink, &externalIterations)
            } else {
                externalTotal += try external(chunkNs, &externalSink, &externalIterations)
                directTotal += try direct(chunkNs, &directSink, &directIterations)
            }
            directFirst.toggle()
        }
        withExtendedLifetime(directSink) {}
        withExtendedLifetime(externalSink) {}

        return (
            makeMeasurement(iterations: directIterations, totalNs: directTotal),
            makeMeasurement(iterations: externalIterations, totalNs: externalTotal)
        )
    }

    private func runSerializeChunk<S: Serializer>(
        value: S.Target,
        serializer: S.Type,
        minimumNs: UInt64,
        sink: inout Int,
        iterations: inout Int
    ) throws -> UInt64 {
        let start = DispatchTime.now().uptimeNanoseconds
        var elapsed: UInt64 = 0
        repeat {
            for _ in 0..<256 {
                sink &+= try fory.serialize(value, with: serializer).count
                iterations += 1
            }
            elapsed = DispatchTime.now().uptimeNanoseconds - start
        } while elapsed < minimumNs
        return elapsed
    }

    private func runDeserializeChunk<S: Serializer>(
        bytes: Data,
        serializer: S.Type,
        minimumNs: UInt64,
        sink: inout Int,
        iterations: inout Int
    ) throws -> UInt64 {
        let start = DispatchTime.now().uptimeNanoseconds
        var elapsed: UInt64 = 0
        repeat {
            for _ in 0..<256 {
                let decoded: S.Target = try fory.deserialize(bytes, with: serializer)
                withExtendedLifetime(decoded) {}
                sink &+= bytes.count
                iterations += 1
            }
            elapsed = DispatchTime.now().uptimeNanoseconds - start
        } while elapsed < minimumNs
        return elapsed
    }

    private func measure(
        _ body: () throws -> Int
    ) throws -> Measurement {
        let warmupNs = UInt64(0.2 * 1_000_000_000)
        let durationNs = UInt64(max(config.durationSeconds, 0.1) * 1_000_000_000)
        let warmupStart = DispatchTime.now().uptimeNanoseconds
        var sink = 0
        while DispatchTime.now().uptimeNanoseconds - warmupStart < warmupNs {
            sink &+= try body()
        }

        let start = DispatchTime.now().uptimeNanoseconds
        var iterations = 0
        benchmarkLoop: while true {
            for _ in 0..<256 {
                sink &+= try body()
                iterations += 1
            }
            if DispatchTime.now().uptimeNanoseconds - start >= durationNs {
                break benchmarkLoop
            }
        }
        withExtendedLifetime(sink) {}

        let totalNs = DispatchTime.now().uptimeNanoseconds - start
        return makeMeasurement(iterations: iterations, totalNs: totalNs)
    }

    private func makeMeasurement(iterations: Int, totalNs: UInt64) -> Measurement {
        let nsPerOp = Double(totalNs) / Double(max(iterations, 1))
        return Measurement(
            iterations: iterations,
            totalNs: totalNs,
            nsPerOp: nsPerOp,
            opsPerSec: 1_000_000_000.0 / nsPerOp
        )
    }

    private func makeContext() -> ExternalBenchmarkContext {
        let process = ProcessInfo.processInfo
        return ExternalBenchmarkContext(
            timestamp: ISO8601DateFormatter().string(from: Date()),
            os: process.operatingSystemVersionString,
            host: process.hostName,
            cpuCoresLogical: process.processorCount,
            memoryGB: Double(process.physicalMemory) / (1024.0 * 1024.0 * 1024.0),
            durationSeconds: config.durationSeconds
        )
    }
}
