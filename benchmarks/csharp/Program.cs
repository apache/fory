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

using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Apache.Fory.Benchmarks.CSharp;

internal sealed record BenchmarkCase(
    string Serializer,
    string DataType,
    string Operation,
    int SerializedSize,
    Action Action);

internal sealed record BenchmarkResult(
    string Serializer,
    string DataType,
    string Operation,
    int SerializedSize,
    double OperationsPerSecond,
    double AverageNanoseconds,
    long Iterations,
    double ElapsedSeconds);

internal sealed record BenchmarkOutput(
    string GeneratedAtUtc,
    string RuntimeVersion,
    string OsDescription,
    string OsArchitecture,
    string ProcessArchitecture,
    int ProcessorCount,
    double WarmupSeconds,
    double DurationSeconds,
    List<BenchmarkResult> Results);

internal sealed class BenchmarkOptions
{
    public HashSet<string> DataFilter { get; init; } = [];

    public HashSet<string> SerializerFilter { get; init; } = [];

    public double WarmupSeconds { get; init; } = 1.0;

    public double DurationSeconds { get; init; } = 3.0;

    public string OutputPath { get; init; } = "benchmark_results.json";

    public bool ShowHelp { get; init; }

    public static BenchmarkOptions Parse(string[] args)
    {
        HashSet<string> dataFilter = new(StringComparer.OrdinalIgnoreCase);
        HashSet<string> serializerFilter = new(StringComparer.OrdinalIgnoreCase);
        double warmupSeconds = 1.0;
        double durationSeconds = 3.0;
        string outputPath = "benchmark_results.json";
        bool showHelp = false;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--help":
                case "-h":
                    showHelp = true;
                    break;
                case "--data":
                    RequireValue(args, i);
                    dataFilter.Add(args[++i]);
                    break;
                case "--serializer":
                    RequireValue(args, i);
                    serializerFilter.Add(args[++i]);
                    break;
                case "--warmup":
                    RequireValue(args, i);
                    warmupSeconds = ParsePositiveDouble(args[++i], "warmup");
                    break;
                case "--duration":
                    RequireValue(args, i);
                    durationSeconds = ParsePositiveDouble(args[++i], "duration");
                    break;
                case "--output":
                    RequireValue(args, i);
                    outputPath = args[++i];
                    break;
                default:
                    throw new ArgumentException($"unknown option: {args[i]}");
            }
        }

        return new BenchmarkOptions
        {
            DataFilter = dataFilter,
            SerializerFilter = serializerFilter,
            WarmupSeconds = warmupSeconds,
            DurationSeconds = durationSeconds,
            OutputPath = outputPath,
            ShowHelp = showHelp,
        };
    }

    public bool IsDataEnabled(string dataType)
    {
        return DataFilter.Count == 0 || DataFilter.Contains(dataType);
    }

    public bool IsSerializerEnabled(string serializer)
    {
        return SerializerFilter.Count == 0 || SerializerFilter.Contains(serializer);
    }

    private static void RequireValue(string[] args, int index)
    {
        if (index + 1 >= args.Length)
        {
            throw new ArgumentException($"missing value for option {args[index]}");
        }
    }

    private static double ParsePositiveDouble(string text, string name)
    {
        if (!double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out double value) || value <= 0)
        {
            throw new ArgumentException($"{name} must be a positive number, got '{text}'");
        }

        return value;
    }
}

internal static class Program
{
    private static object? _sink;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
    };

    private static int Main(string[] args)
    {
        BenchmarkOptions options;
        try
        {
            options = BenchmarkOptions.Parse(args);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"error: {ex.Message}");
            PrintUsage();
            return 1;
        }

        if (options.ShowHelp)
        {
            PrintUsage();
            return 0;
        }

        List<BenchmarkCase> cases;
        try
        {
            cases = BuildBenchmarkCases(options);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"failed to build benchmarks: {ex}");
            return 1;
        }

        if (cases.Count == 0)
        {
            Console.Error.WriteLine("no benchmark cases selected");
            return 1;
        }

        Console.WriteLine("=== Fory C# Benchmark ===");
        Console.WriteLine($"Cases: {cases.Count}");
        Console.WriteLine($"Warmup: {options.WarmupSeconds.ToString("F2", CultureInfo.InvariantCulture)}s");
        Console.WriteLine($"Duration: {options.DurationSeconds.ToString("F2", CultureInfo.InvariantCulture)}s");
        Console.WriteLine();

        List<BenchmarkResult> results = new(cases.Count);
        foreach (BenchmarkCase benchmarkCase in cases)
        {
            Console.WriteLine($"Running {benchmarkCase.Serializer}/{benchmarkCase.DataType}/{benchmarkCase.Operation}...");
            BenchmarkResult result = RunBenchmarkCase(benchmarkCase, options.WarmupSeconds, options.DurationSeconds);
            results.Add(result);
        }

        BenchmarkOutput output = new(
            GeneratedAtUtc: DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture),
            RuntimeVersion: Environment.Version.ToString(),
            OsDescription: RuntimeInformation.OSDescription,
            OsArchitecture: RuntimeInformation.OSArchitecture.ToString(),
            ProcessArchitecture: RuntimeInformation.ProcessArchitecture.ToString(),
            ProcessorCount: Environment.ProcessorCount,
            WarmupSeconds: options.WarmupSeconds,
            DurationSeconds: options.DurationSeconds,
            Results: results);

        string outputPath = Path.GetFullPath(options.OutputPath);
        string? parent = Path.GetDirectoryName(outputPath);
        if (!string.IsNullOrEmpty(parent))
        {
            Directory.CreateDirectory(parent);
        }

        File.WriteAllText(outputPath, JsonSerializer.Serialize(output, JsonOptions));

        PrintSummary(results);
        Console.WriteLine();
        Console.WriteLine($"Results written to {outputPath}");
        return 0;
    }

    private static void PrintUsage()
    {
        Console.WriteLine("Usage: dotnet run -c Release -- [OPTIONS]");
        Console.WriteLine();
        Console.WriteLine("Options:");
        Console.WriteLine("  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>");
        Console.WriteLine("  --serializer <fory|protobuf|msgpack>");
        Console.WriteLine("  --warmup <seconds>");
        Console.WriteLine("  --duration <seconds>");
        Console.WriteLine("  --output <path>");
        Console.WriteLine("  --help");
    }

    private static BenchmarkResult RunBenchmarkCase(BenchmarkCase benchmarkCase, double warmupSeconds, double durationSeconds)
    {
        RunForDuration(benchmarkCase.Action, warmupSeconds);

        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();

        const int batchSize = 256;
        long iterations = 0;
        Stopwatch stopwatch = Stopwatch.StartNew();
        while (stopwatch.Elapsed.TotalSeconds < durationSeconds)
        {
            for (int i = 0; i < batchSize; i++)
            {
                benchmarkCase.Action();
            }

            iterations += batchSize;
        }

        double elapsed = stopwatch.Elapsed.TotalSeconds;
        double throughput = iterations / elapsed;
        double nsPerOp = (elapsed * 1_000_000_000.0) / iterations;

        return new BenchmarkResult(
            benchmarkCase.Serializer,
            benchmarkCase.DataType,
            benchmarkCase.Operation,
            benchmarkCase.SerializedSize,
            throughput,
            nsPerOp,
            iterations,
            elapsed);
    }

    private static void RunForDuration(Action action, double durationSeconds)
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        while (stopwatch.Elapsed.TotalSeconds < durationSeconds)
        {
            action();
        }
    }

    private static List<BenchmarkCase> BuildBenchmarkCases(BenchmarkOptions options)
    {
        List<BenchmarkCase> cases = [];

        AddCases("struct", BenchmarkDataFactory.CreateNumericStruct(), options, cases);
        AddCases("sample", BenchmarkDataFactory.CreateSample(), options, cases);
        AddCases("mediacontent", BenchmarkDataFactory.CreateMediaContent(), options, cases);
        AddCases("structlist", BenchmarkDataFactory.CreateStructList(), options, cases);
        AddCases("samplelist", BenchmarkDataFactory.CreateSampleList(), options, cases);
        AddCases("mediacontentlist", BenchmarkDataFactory.CreateMediaContentList(), options, cases);

        return cases;
    }

    private static void AddCases<T>(string dataType, T value, BenchmarkOptions options, List<BenchmarkCase> cases)
    {
        if (!options.IsDataEnabled(dataType))
        {
            return;
        }

        List<IBenchmarkSerializer<T>> serializers = [];
        if (options.IsSerializerEnabled("fory"))
        {
            serializers.Add(new ForySerializer<T>());
        }

        if (options.IsSerializerEnabled("protobuf"))
        {
            serializers.Add(new ProtobufSerializer<T>());
        }

        if (options.IsSerializerEnabled("msgpack"))
        {
            serializers.Add(new MessagePackRuntimeSerializer<T>());
        }

        foreach (IBenchmarkSerializer<T> serializer in serializers)
        {
            byte[] payload = serializer.Serialize(value);
            _sink = serializer.Deserialize(payload);

            cases.Add(new BenchmarkCase(
                serializer.Name,
                dataType,
                "serialize",
                payload.Length,
                () =>
                {
                    _sink = serializer.Serialize(value);
                }));

            cases.Add(new BenchmarkCase(
                serializer.Name,
                dataType,
                "deserialize",
                payload.Length,
                () =>
                {
                    _sink = serializer.Deserialize(payload);
                }));
        }
    }

    private static void PrintSummary(List<BenchmarkResult> results)
    {
        Console.WriteLine();
        Console.WriteLine("=== Summary (ops/s) ===");

        IEnumerable<IGrouping<string, BenchmarkResult>> groups = results
            .OrderBy(r => r.DataType, StringComparer.Ordinal)
            .ThenBy(r => r.Operation, StringComparer.Ordinal)
            .GroupBy(r => $"{r.DataType}/{r.Operation}");

        foreach (IGrouping<string, BenchmarkResult> group in groups)
        {
            Console.WriteLine(group.Key);
            foreach (BenchmarkResult result in group.OrderByDescending(r => r.OperationsPerSecond))
            {
                Console.WriteLine(
                    $"  {result.Serializer,-8} {result.OperationsPerSecond,14:N0} ops/s  {result.AverageNanoseconds,10:N1} ns/op  size={result.SerializedSize}");
            }
        }
    }
}
