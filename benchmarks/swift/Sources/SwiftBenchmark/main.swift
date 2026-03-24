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

private struct CLI {
    static func printUsage() {
        print(
            """
            Usage: swift-benchmark [OPTIONS]

            Options:
              --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                                   Filter benchmark by data type
              --serializer <fory|protobuf|msgpack>
                                   Filter benchmark by serializer
              --duration <seconds>  Minimum time to run each benchmark (default: 3)
              --output <path>       JSON output file (default: results/benchmark_results.json)
              --help                Show this help message
            """
        )
    }

    static func parse(arguments: [String]) throws -> (BenchmarkConfig, String) {
        var config = BenchmarkConfig()
        var outputPath = "results/benchmark_results.json"

        var i = 1
        while i < arguments.count {
            let arg = arguments[i]
            switch arg {
            case "--data":
                guard i + 1 < arguments.count else {
                    throw CLIError.invalidArgument("Missing value for --data")
                }
                guard let dataKind = DataKind(rawValue: arguments[i + 1].lowercased()) else {
                    throw CLIError.invalidArgument("Unknown data type: \(arguments[i + 1])")
                }
                config.dataFilter = dataKind
                i += 2
            case "--serializer":
                guard i + 1 < arguments.count else {
                    throw CLIError.invalidArgument("Missing value for --serializer")
                }
                guard let serializer = SerializerKind(rawValue: arguments[i + 1].lowercased()) else {
                    throw CLIError.invalidArgument("Unknown serializer: \(arguments[i + 1])")
                }
                config.serializerFilter = serializer
                i += 2
            case "--duration":
                guard i + 1 < arguments.count else {
                    throw CLIError.invalidArgument("Missing value for --duration")
                }
                guard let duration = Double(arguments[i + 1]), duration > 0 else {
                    throw CLIError.invalidArgument("Duration must be a positive number")
                }
                config.durationSeconds = duration
                i += 2
            case "--output":
                guard i + 1 < arguments.count else {
                    throw CLIError.invalidArgument("Missing value for --output")
                }
                outputPath = arguments[i + 1]
                i += 2
            case "--help", "-h":
                printUsage()
                exit(0)
            default:
                throw CLIError.invalidArgument("Unknown option: \(arg)")
            }
        }

        return (config, outputPath)
    }
}

enum CLIError: Error, CustomStringConvertible {
    case invalidArgument(String)

    var description: String {
        switch self {
        case let .invalidArgument(message):
            return message
        }
    }
}

private func runMain() throws {
    let (config, outputPath) = try CLI.parse(arguments: CommandLine.arguments)

    print("Running Swift benchmarks")
    print("Duration per benchmark: \(config.durationSeconds)s")
    if let filter = config.dataFilter {
        print("Data filter: \(filter.rawValue)")
    }
    if let filter = config.serializerFilter {
        print("Serializer filter: \(filter.rawValue)")
    }

    let suite = BenchmarkSuite(config: config)
    let output = try suite.run()

    try writeOutput(output, to: outputPath)
    print("\nBenchmark JSON written to: \(outputPath)")

    print("\nSerialized sizes (bytes):")
    for entry in output.serializedSizes {
        print("  \(entry.dataType): fory=\(entry.fory), protobuf=\(entry.protobuf), msgpack=\(entry.msgpack)")
    }
}

private func writeOutput(_ output: BenchmarkOutput, to path: String) throws {
    let url = URL(fileURLWithPath: path)
    let dir = url.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    let data = try encoder.encode(output)
    try data.write(to: url)
}

do {
    try runMain()
} catch let error as CLIError {
    fputs("Error: \(error.description)\n\n", stderr)
    CLI.printUsage()
    exit(1)
} catch {
    fputs("Fatal error: \(error)\n", stderr)
    exit(1)
}
