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

using System.Globalization;
using System.Text.Json;

namespace Apache.Fory.Benchmarks.CSharp;

internal enum ExternalImplementation
{
    None,
    Ordinary,
    External,
}

internal sealed class ExternalBenchmarkOptions
{
    public const int DefaultAllocationIterations = 100_000;

    public HashSet<string> DataFilter { get; init; } = [];

    public double WarmupSeconds { get; init; } = 1.0;

    public double DurationSeconds { get; init; } = 3.0;

    public string OutputPath { get; init; } = "external_equivalence_results.json";

    public ExternalImplementation Implementation { get; init; }

    public int AllocationIterations { get; init; } = DefaultAllocationIterations;

    public bool ShowHelp { get; init; }

    public static ExternalBenchmarkOptions Parse(string[] args)
    {
        HashSet<string> dataFilter = new(StringComparer.OrdinalIgnoreCase);
        double warmupSeconds = 1.0;
        double durationSeconds = 3.0;
        string outputPath = "external_equivalence_results.json";
        ExternalImplementation implementation = ExternalImplementation.None;
        int allocationIterations = DefaultAllocationIterations;
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
                case "--external-implementation":
                    RequireValue(args, i);
                    if (implementation != ExternalImplementation.None)
                    {
                        throw new ArgumentException(
                            "--external-implementation may be specified only once");
                    }

                    implementation = ParseImplementation(args[++i]);
                    break;
                case "--allocation-iterations":
                    RequireValue(args, i);
                    allocationIterations = ParsePositiveInt(
                        args[++i],
                        "allocation-iterations");
                    break;
                default:
                    throw new ArgumentException($"unknown option: {args[i]}");
            }
        }

        if (!showHelp && implementation == ExternalImplementation.None)
        {
            throw new ArgumentException(
                "--external-implementation ordinary|external is required");
        }

        return new ExternalBenchmarkOptions
        {
            DataFilter = dataFilter,
            WarmupSeconds = warmupSeconds,
            DurationSeconds = durationSeconds,
            OutputPath = outputPath,
            Implementation = implementation,
            AllocationIterations = allocationIterations,
            ShowHelp = showHelp,
        };
    }

    public bool IsDataEnabled(string dataType)
    {
        return DataFilter.Count == 0 || DataFilter.Contains(dataType);
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
        if (!double.TryParse(
                text,
                NumberStyles.Float,
                CultureInfo.InvariantCulture,
                out double value)
            || value <= 0)
        {
            throw new ArgumentException($"{name} must be a positive number, got '{text}'");
        }

        return value;
    }

    private static int ParsePositiveInt(string text, string name)
    {
        if (!int.TryParse(
                text,
                NumberStyles.None,
                CultureInfo.InvariantCulture,
                out int value)
            || value <= 0)
        {
            throw new ArgumentException($"{name} must be a positive integer, got '{text}'");
        }

        return value;
    }

    private static ExternalImplementation ParseImplementation(string text)
    {
        if (string.Equals(text, "ordinary", StringComparison.OrdinalIgnoreCase))
        {
            return ExternalImplementation.Ordinary;
        }

        if (string.Equals(text, "external", StringComparison.OrdinalIgnoreCase))
        {
            return ExternalImplementation.External;
        }

        throw new ArgumentException(
            $"external-implementation must be ordinary or external, got '{text}'");
    }
}

internal static class ExternalEquivalenceProgram
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
    };

    private static int Main(string[] args)
    {
        ExternalBenchmarkOptions options;
        try
        {
            options = ExternalBenchmarkOptions.Parse(args);
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

        try
        {
            return ExternalEquivalenceBenchmark.Run(options, JsonOptions);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"failed to run external-equivalence benchmarks: {ex}");
            return 1;
        }
    }

    private static void PrintUsage()
    {
        Console.WriteLine("Usage: dotnet run -c Release -- [OPTIONS]");
        Console.WriteLine();
        Console.WriteLine("Options:");
        Console.WriteLine(
            "  --data <class-root|struct-root|holder-field|list-field|list-root|map-field|map-root>");
        Console.WriteLine("  --warmup <seconds>");
        Console.WriteLine("  --duration <seconds>");
        Console.WriteLine("  --output <path>");
        Console.WriteLine("  --external-implementation <ordinary|external>");
        Console.WriteLine(
            $"  --allocation-iterations <count> (default: {ExternalBenchmarkOptions.DefaultAllocationIterations})");
        Console.WriteLine("  --help");
    }
}
