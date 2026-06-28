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
import SwiftProtobuf

struct BenchmarkConfig {
    var durationSeconds: Double = 3.0
    var dataFilter: DataKind?
    var serializerFilter: SerializerKind?
    var schemaMismatch: Bool = false
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
    let protobuf: Int?
    let json: Int?
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
    private let foryWriter: Fory
    private let foryReader: Fory
    private let jsonEncoder = JSONEncoder()
    private let jsonDecoder = JSONDecoder()

    init(config: BenchmarkConfig) {
        self.config = config
        self.foryWriter = Fory(ref: false, compatible: true)
        self.foryReader = Fory(ref: false, compatible: true)
        registerV1Types(foryWriter)
        if config.schemaMismatch {
            registerV2Types(foryReader)
        } else {
            registerV1Types(foryReader)
        }
    }

    func run() throws -> BenchmarkOutput {
        var entries: [BenchmarkEntry] = []
        var sizeEntries: [SizeEntry] = []

        try runBenchmarks(
            dataKind: .numericStruct,
            value: BenchmarkDataFactory.makeNumericStruct(),
            validateMismatch: { (decoded: NumericStructV2, expected: NumericStruct) in
                decoded.f1 == Int64(expected.f1)
            },
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .sample,
            value: BenchmarkDataFactory.makeSample(),
            validateMismatch: { (decoded: SampleV2, expected: Sample) in
                decoded.intValue == Int64(expected.intValue)
            },
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .mediaContent,
            value: BenchmarkDataFactory.makeMediaContent(),
            validateMismatch: { (decoded: MediaContentV2, expected: MediaContent) in
                decoded.media.width == Int64(expected.media.width)
                    && decoded.images.first?.width == Int64(expected.images[0].width)
            },
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .numericStructList,
            value: BenchmarkDataFactory.makeNumericStructList(),
            validateMismatch: { (decoded: NumericStructListV2, expected: NumericStructList) in
                decoded.structList.first?.f1 == Int64(expected.structList[0].f1)
            },
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .sampleList,
            value: BenchmarkDataFactory.makeSampleList(),
            validateMismatch: { (decoded: SampleListV2, expected: SampleList) in
                decoded.sampleList.first?.intValue == Int64(expected.sampleList[0].intValue)
            },
            entries: &entries,
            sizeEntries: &sizeEntries
        )
        try runBenchmarks(
            dataKind: .mediaContentList,
            value: BenchmarkDataFactory.makeMediaContentList(),
            validateMismatch: { (decoded: MediaContentListV2, expected: MediaContentList) in
                decoded.mediaContentList.first?.media.width
                    == Int64(expected.mediaContentList[0].media.width)
                    && decoded.mediaContentList.first?.images.first?.width
                        == Int64(expected.mediaContentList[0].images[0].width)
            },
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

    private func registerV1Types(_ fory: Fory) {
        fory.register(NumericStruct.self, id: 1)
        fory.register(Sample.self, id: 2)
        fory.register(Media.self, id: 3)
        fory.register(Image.self, id: 4)
        fory.register(MediaContent.self, id: 5)
        fory.register(NumericStructList.self, id: 6)
        fory.register(SampleList.self, id: 7)
        fory.register(MediaContentList.self, id: 8)
    }

    private func registerV2Types(_ fory: Fory) {
        fory.register(NumericStructV2.self, id: 1)
        fory.register(SampleV2.self, id: 2)
        fory.register(MediaV2.self, id: 3)
        fory.register(ImageV2.self, id: 4)
        fory.register(MediaContentV2.self, id: 5)
        fory.register(NumericStructListV2.self, id: 6)
        fory.register(SampleListV2.self, id: 7)
        fory.register(MediaContentListV2.self, id: 8)
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

    private func runBenchmarks<T: Serializer & Codable & ProtobufConvertible, TRead: Serializer>(
        dataKind: DataKind,
        value: T,
        validateMismatch: (TRead, T) -> Bool,
        entries: inout [BenchmarkEntry],
        sizeEntries: inout [SizeEntry]
    ) throws {
        if let filter = config.dataFilter, filter != dataKind {
            return
        }

        let foryBytes = try foryWriter.serialize(value)
        let protobufBytes = config.schemaMismatch ? nil : try value.toProtobuf().serializedData()
        let jsonBytes = config.schemaMismatch ? nil : try jsonEncoder.encode(value)

        sizeEntries.append(
            SizeEntry(
                dataType: dataKind.title,
                fory: foryBytes.count,
                protobuf: protobufBytes?.count,
                json: jsonBytes?.count
            )
        )
        if config.schemaMismatch {
            let decoded: TRead = try foryReader.deserialize(foryBytes, as: TRead.self)
            guard validateMismatch(decoded, value) else {
                throw BenchmarkError.schemaMismatchValidation(dataKind.rawValue)
            }
        }

        if shouldRun(dataKind, .fory) {
            entries.append(
                try runSingleCase(
                    serializer: .fory,
                    dataKind: dataKind,
                    operation: .serialize,
                    bytes: foryBytes.count
                ) {
                    try self.foryWriter.serialize(value).count
                }
            )
            entries.append(
                try runSingleCase(
                    serializer: .fory,
                    dataKind: dataKind,
                    operation: .deserialize,
                    bytes: foryBytes.count
                ) {
                    if self.config.schemaMismatch {
                        let decoded: TRead = try self.foryReader.deserialize(foryBytes, as: TRead.self)
                        withExtendedLifetime(decoded) {}
                    } else {
                        let decoded: T = try self.foryReader.deserialize(foryBytes, as: T.self)
                        withExtendedLifetime(decoded) {}
                    }
                    return foryBytes.count
                }
            )
        }

        if !config.schemaMismatch && shouldRun(dataKind, .protobuf) {
            guard let protobufBytes = protobufBytes else {
                throw BenchmarkError.schemaMismatchValidation(dataKind.rawValue)
            }
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

        if !config.schemaMismatch && shouldRun(dataKind, .json) {
            guard let jsonBytes = jsonBytes else {
                throw BenchmarkError.schemaMismatchValidation(dataKind.rawValue)
            }
            entries.append(
                try runSingleCase(
                    serializer: .json,
                    dataKind: dataKind,
                    operation: .serialize,
                    bytes: jsonBytes.count
                ) {
                    try self.jsonEncoder.encode(value).count
                }
            )
            entries.append(
                try runSingleCase(
                    serializer: .json,
                    dataKind: dataKind,
                    operation: .deserialize,
                    bytes: jsonBytes.count
                ) {
                    let decoded: T = try self.jsonDecoder.decode(T.self, from: jsonBytes)
                    withExtendedLifetime(decoded) {}
                    return jsonBytes.count
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

enum BenchmarkError: Error, CustomStringConvertible {
    case schemaMismatchValidation(String)

    var description: String {
        switch self {
        case let .schemaMismatchValidation(dataType):
            return "Fory schema-mismatch validation failed for \(dataType)"
        }
    }
}
