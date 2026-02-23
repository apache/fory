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

import Fory
import Foundation
import MessagePack
import SwiftProtobuf

struct BenchmarkConfig {
    var durationSeconds: Double = 3.0
    var dataFilter: DataKind?
    var serializerFilter: SerializerKind?
}

struct BenchmarkEntry: Codable {
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

struct SizeEntry: Codable {
    let dataType: String
    let fory: Int
    let protobuf: Int
    let msgpack: Int
}

struct BenchmarkContext: Codable {
    let timestamp: String
    let os: String
    let host: String
    let cpuCoresLogical: Int
    let memoryGB: Double
    let durationSeconds: Double
}

struct BenchmarkOutput: Codable {
    let context: BenchmarkContext
    let benchmarks: [BenchmarkEntry]
    let serializedSizes: [SizeEntry]
}

final class BenchmarkSuite {
    private let config: BenchmarkConfig
    private let fory: Fory
    private let msgpackEncoder = MessagePackEncoder()
    private let msgpackDecoder = MessagePackDecoder()

    init(config: BenchmarkConfig) {
        self.config = config
        self.fory = Fory(xlang: true, trackRef: false, compatible: true)
        registerTypes()
    }

    func run() throws -> BenchmarkOutput {
        var entries: [BenchmarkEntry] = []
        var sizeEntries: [SizeEntry] = []

        try runBenchmarks(
            dataKind: .numericStruct,
            value: BenchmarkDataFactory.makeNumericStruct(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .sample,
            value: BenchmarkDataFactory.makeSample(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .mediaContent,
            value: BenchmarkDataFactory.makeMediaContent(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .structList,
            value: BenchmarkDataFactory.makeStructList(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .sampleList,
            value: BenchmarkDataFactory.makeSampleList(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .mediaContentList,
            value: BenchmarkDataFactory.makeMediaContentList(),
            entries: &entries,
            sizeEntries: &sizeEntries
        )

        entries.sort { $0.name < $1.name }
        sizeEntries.sort { $0.dataType < $1.dataType }

        return BenchmarkOutput(
            context: createContext(),
            benchmarks: entries,
            serializedSizes: sizeEntries
        )
    }

    private func registerTypes() {
        fory.register(NumericStruct.self, id: 1)
        fory.register(Sample.self, id: 2)
        fory.register(Media.self, id: 3)
        fory.register(Image.self, id: 4)
        fory.register(MediaContent.self, id: 5)
        fory.register(Player.self, id: 6)
        fory.register(Size.self, id: 7)
        fory.register(StructList.self, id: 8)
        fory.register(SampleList.self, id: 9)
        fory.register(MediaContentList.self, id: 10)
    }

    private func shouldRun(_ dataKind: DataKind, _ serializer: SerializerKind) -> Bool {
        if let filter = config.dataFilter, filter != dataKind {
            return false
        }
        if let filter = config.serializerFilter, filter != serializer {
            return false
        }
        return true
    }

    private func runBenchmarks<T: Serializer & Codable & ProtobufConvertible>(
        dataKind: DataKind,
        value: T,
        entries: inout [BenchmarkEntry],
        sizeEntries: inout [SizeEntry]
    ) throws {
        if let filter = config.dataFilter, filter != dataKind {
            return
        }

        let foryBytes = try fory.serialize(value)
        let protobufBytes = try value.toProtobuf().serializedData()
        let msgpackBytes = try msgpackEncoder.encode(value)

        sizeEntries.append(
            SizeEntry(
                dataType: dataKind.title,
                fory: foryBytes.count,
                protobuf: protobufBytes.count,
                msgpack: msgpackBytes.count
            )
        )

        if shouldRun(dataKind, .fory) {
            entries.append(
                try runSingleCase(
                    serializer: .fory,
                    dataKind: dataKind,
                    operation: .serialize,
                    bytes: foryBytes.count
                ) {
                    try self.fory.serialize(value).count
                }
            )
            entries.append(
                try runSingleCase(
                    serializer: .fory,
                    dataKind: dataKind,
                    operation: .deserialize,
                    bytes: foryBytes.count
                ) {
                    let decoded: T = try self.fory.deserialize(foryBytes)
                    withExtendedLifetime(decoded) {}
                    return foryBytes.count
                }
            )
        }

        if shouldRun(dataKind, .protobuf) {
            entries.append(
                try runSingleCase(
                    serializer: .protobuf,
                    dataKind: dataKind,
                    operation: .serialize,
                    bytes: protobufBytes.count
                ) {
                    try value.toProtobuf().serializedData().count
                }
            )
            entries.append(
                try runSingleCase(
                    serializer: .protobuf,
                    dataKind: dataKind,
                    operation: .deserialize,
                    bytes: protobufBytes.count
                ) {
                    let decodedPB = try T.PBType(serializedBytes: protobufBytes)
                    let decoded = T.fromProtobuf(decodedPB)
                    withExtendedLifetime(decoded) {}
                    return protobufBytes.count
                }
            )
        }

        if shouldRun(dataKind, .msgpack) {
            entries.append(
                try runSingleCase(
                    serializer: .msgpack,
                    dataKind: dataKind,
                    operation: .serialize,
                    bytes: msgpackBytes.count
                ) {
                    try self.msgpackEncoder.encode(value).count
                }
            )
            entries.append(
                try runSingleCase(
                    serializer: .msgpack,
                    dataKind: dataKind,
                    operation: .deserialize,
                    bytes: msgpackBytes.count
                ) {
                    let decoded: T = try self.msgpackDecoder.decode(T.self, from: msgpackBytes)
                    withExtendedLifetime(decoded) {}
                    return msgpackBytes.count
                }
            )
        }
    }

    private func runSingleCase(
        serializer: SerializerKind,
        dataKind: DataKind,
        operation: OperationKind,
        bytes: Int,
        body: () throws -> Int
    ) throws -> BenchmarkEntry {
        let measured = try measure(
            durationSeconds: config.durationSeconds,
            body: body
        )

        let name = "BM_\(serializer.title)_\(dataKind.title)_\(operation.title)"
        let paddedName = name.padding(toLength: 42, withPad: " ", startingAt: 0)
        let iterations = String(measured.iterations)
        let line = "\(paddedName) \(iterations) ops  \(String(format: "%.2f", measured.nsPerOp)) ns/op  \(String(format: "%.2f", measured.opsPerSec)) ops/sec"
        print(line)

        return BenchmarkEntry(
            name: name,
            serializer: serializer.rawValue,
            dataType: dataKind.rawValue,
            operation: operation.rawValue,
            iterations: measured.iterations,
            totalNs: measured.totalNs,
            nsPerOp: measured.nsPerOp,
            opsPerSec: measured.opsPerSec,
            bytes: bytes
        )
    }

    private func measure(
        durationSeconds: Double,
        body: () throws -> Int
    ) throws -> (iterations: Int, totalNs: UInt64, nsPerOp: Double, opsPerSec: Double) {
        let warmupNs = UInt64(0.2 * 1_000_000_000)
        let durationNs = UInt64(max(durationSeconds, 0.1) * 1_000_000_000)

        // Warmup
        let warmupStart = DispatchTime.now().uptimeNanoseconds
        var warmupSink = 0
        while DispatchTime.now().uptimeNanoseconds - warmupStart < warmupNs {
            warmupSink &+= try body()
        }
        _ = warmupSink

        let start = DispatchTime.now().uptimeNanoseconds
        var iterations = 0
        var localSink = 0

        benchmarkLoop: while true {
            for _ in 0 ..< 256 {
                localSink &+= try body()
                iterations += 1
            }
            let elapsed = DispatchTime.now().uptimeNanoseconds - start
            if elapsed >= durationNs {
                break benchmarkLoop
            }
        }

        _ = localSink

        let totalNs = DispatchTime.now().uptimeNanoseconds - start
        let nsPerOp = Double(totalNs) / Double(max(iterations, 1))
        let opsPerSec = 1_000_000_000.0 / nsPerOp

        return (iterations, totalNs, nsPerOp, opsPerSec)
    }

    private func createContext() -> BenchmarkContext {
        let process = ProcessInfo.processInfo
        let timestamp = ISO8601DateFormatter().string(from: Date())

        return BenchmarkContext(
            timestamp: timestamp,
            os: process.operatingSystemVersionString,
            host: process.hostName,
            cpuCoresLogical: process.processorCount,
            memoryGB: Double(process.physicalMemory) / (1024.0 * 1024.0 * 1024.0),
            durationSeconds: config.durationSeconds
        )
    }
}
