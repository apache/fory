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

private enum ExternalBenchmarkCLI {
    static func parse() throws -> (ExternalBenchmarkConfig, String) {
        var config = ExternalBenchmarkConfig()
        var output = "results/external_benchmark_results.json"
        var index = 1
        while index < CommandLine.arguments.count {
            switch CommandLine.arguments[index] {
            case "--duration":
                guard
                    index + 1 < CommandLine.arguments.count,
                    let duration = Double(CommandLine.arguments[index + 1]),
                    duration > 0
                else {
                    throw ExternalBenchmarkCLIError.invalidArgument(
                        "--duration requires a positive number"
                    )
                }
                config.durationSeconds = duration
                index += 2
            case "--case":
                guard index + 1 < CommandLine.arguments.count else {
                    throw ExternalBenchmarkCLIError.invalidArgument("--case requires a value")
                }
                config.caseFilter = CommandLine.arguments[index + 1]
                index += 2
            case "--output":
                guard index + 1 < CommandLine.arguments.count else {
                    throw ExternalBenchmarkCLIError.invalidArgument("--output requires a path")
                }
                output = CommandLine.arguments[index + 1]
                index += 2
            case "--help", "-h":
                printUsage()
                exit(0)
            default:
                throw ExternalBenchmarkCLIError.invalidArgument(
                    "unknown option \(CommandLine.arguments[index])"
                )
            }
        }
        return (config, output)
    }

    static func printUsage() {
        print(
            """
            Usage: swift-external-benchmark [OPTIONS]

            Options:
              --case <substring>    Filter benchmark case names
              --duration <seconds>  Minimum time per case (default: 3)
              --output <path>       JSON output path
              --help                Show this help message
            """
        )
    }
}

private enum ExternalBenchmarkCLIError: Error, CustomStringConvertible {
    case invalidArgument(String)

    var description: String {
        switch self {
        case .invalidArgument(let message):
            return message
        }
    }
}

private func writeOutput(_ output: ExternalBenchmarkOutput, to path: String) throws {
    let url = URL(fileURLWithPath: path)
    try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    try encoder.encode(output).write(to: url)
}

private func runMain() throws {
    let (config, outputPath) = try ExternalBenchmarkCLI.parse()
    let suite = try ExternalBenchmarkSuite(config: config)
    let output = try suite.run()
    try writeOutput(output, to: outputPath)
    print("\nExternal-type benchmark JSON written to: \(outputPath)")
}

do {
    try runMain()
} catch let error as ExternalBenchmarkCLIError {
    fputs("Error: \(error.description)\n\n", stderr)
    ExternalBenchmarkCLI.printUsage()
    exit(1)
} catch {
    fputs("Fatal error: \(error)\n", stderr)
    exit(1)
}
