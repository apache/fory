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
using ExternalPoint = Apache.Fory.Benchmarks.CSharp.ExternalTypes.Point;
using ExternalUser = Apache.Fory.Benchmarks.CSharp.ExternalTypes.User;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Benchmarks.CSharp;

internal sealed record EquivalenceCase(
    string DataType,
    string Operation,
    int SerializedSize,
    string SerializedFrameBase64,
    Action Action);

internal sealed record EquivalenceMeasurement(
    double OperationsPerSecond,
    double AverageNanoseconds,
    long Iterations,
    double ElapsedSeconds);

internal sealed record EquivalenceSideResult(
    string DataType,
    string Operation,
    int SerializedSize,
    string SerializedFrameBase64,
    EquivalenceMeasurement Measurement,
    double AllocatedBytesPerOperation);

internal sealed record EquivalenceSideOutput(
    string GeneratedAtUtc,
    string RuntimeVersion,
    string OsDescription,
    string OsArchitecture,
    string ProcessArchitecture,
    int ProcessorCount,
    double WarmupSeconds,
    double DurationSeconds,
    int AllocationIterations,
    string Implementation,
    List<EquivalenceSideResult> Results);

internal static class ExternalEquivalenceBenchmark
{
    private const uint UserTypeId = 101;
    private const uint PointTypeId = 102;
    private const uint UserHolderTypeId = 103;
    private const uint ListHolderTypeId = 104;
    private const uint MapHolderTypeId = 105;
    private const int BatchSize = 256;

    public static int Run(
        ExternalBenchmarkOptions options,
        JsonSerializerOptions jsonOptions)
    {
        string implementation = ImplementationName(options.Implementation);
        List<EquivalenceCase> cases = BuildCases(
            options,
            options.Implementation);
        if (cases.Count == 0)
        {
            throw new ArgumentException("no external-equivalence benchmark cases selected");
        }

        Console.WriteLine("=== Fory C# External-Type Equivalence Benchmark ===");
        Console.WriteLine($"Implementation: {implementation}");
        Console.WriteLine($"Cases: {cases.Count}");
        Console.WriteLine(
            $"Warmup: {options.WarmupSeconds.ToString("F2", CultureInfo.InvariantCulture)}s");
        Console.WriteLine(
            $"Duration: {options.DurationSeconds.ToString("F2", CultureInfo.InvariantCulture)}s");
        Console.WriteLine($"Allocation iterations: {options.AllocationIterations}");
        Console.WriteLine();

        List<EquivalenceSideResult> results = new(cases.Count);
        foreach (EquivalenceCase benchmarkCase in cases)
        {
            Console.WriteLine(
                $"Running {implementation}/{benchmarkCase.DataType}/{benchmarkCase.Operation}...");
            results.Add(RunCase(benchmarkCase, options));
        }

        EquivalenceSideOutput output = new(
            GeneratedAtUtc: DateTime.UtcNow.ToString("O", CultureInfo.InvariantCulture),
            RuntimeVersion: Environment.Version.ToString(),
            OsDescription: RuntimeInformation.OSDescription,
            OsArchitecture: RuntimeInformation.OSArchitecture.ToString(),
            ProcessArchitecture: RuntimeInformation.ProcessArchitecture.ToString(),
            ProcessorCount: Environment.ProcessorCount,
            WarmupSeconds: options.WarmupSeconds,
            DurationSeconds: options.DurationSeconds,
            AllocationIterations: options.AllocationIterations,
            Implementation: implementation,
            Results: results);

        string outputPath = Path.GetFullPath(options.OutputPath);
        string? parent = Path.GetDirectoryName(outputPath);
        if (!string.IsNullOrEmpty(parent))
        {
            Directory.CreateDirectory(parent);
        }

        File.WriteAllText(outputPath, JsonSerializer.Serialize(output, jsonOptions));

        PrintSummary(implementation, results);
        Console.WriteLine();
        Console.WriteLine($"Results written to {outputPath}");
        return 0;
    }

    private static EquivalenceSideResult RunCase(
        EquivalenceCase benchmarkCase,
        ExternalBenchmarkOptions options)
    {
        RunForDuration(benchmarkCase.Action, options.WarmupSeconds);

        CollectGarbage();
        EquivalenceMeasurement measurement = MeasureTime(
            benchmarkCase.Action,
            options.DurationSeconds);

        CollectGarbage();
        long allocatedBytes = MeasureAllocation(
            benchmarkCase.Action,
            options.AllocationIterations);
        double bytesPerOperation =
            (double)allocatedBytes / options.AllocationIterations;

        return new EquivalenceSideResult(
            benchmarkCase.DataType,
            benchmarkCase.Operation,
            benchmarkCase.SerializedSize,
            benchmarkCase.SerializedFrameBase64,
            measurement,
            bytesPerOperation);
    }

    private static EquivalenceMeasurement MeasureTime(Action action, double durationSeconds)
    {
        long iterations = 0;
        Stopwatch stopwatch = Stopwatch.StartNew();
        while (stopwatch.Elapsed.TotalSeconds < durationSeconds)
        {
            for (int i = 0; i < BatchSize; i++)
            {
                action();
            }

            iterations += BatchSize;
        }

        double elapsed = stopwatch.Elapsed.TotalSeconds;
        return new EquivalenceMeasurement(
            iterations / elapsed,
            elapsed * 1_000_000_000.0 / iterations,
            iterations,
            elapsed);
    }

    private static long MeasureAllocation(Action action, int iterations)
    {
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int i = 0; i < iterations; i++)
        {
            action();
        }

        return GC.GetAllocatedBytesForCurrentThread() - before;
    }

    private static void RunForDuration(Action action, double durationSeconds)
    {
        Stopwatch stopwatch = Stopwatch.StartNew();
        while (stopwatch.Elapsed.TotalSeconds < durationSeconds)
        {
            action();
        }
    }

    private static void CollectGarbage()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
    }

    private static List<EquivalenceCase> BuildCases(
        ExternalBenchmarkOptions options,
        ExternalImplementation implementation)
    {
        return implementation switch
        {
            ExternalImplementation.Ordinary => BuildOrdinaryCases(options),
            ExternalImplementation.External => BuildExternalCases(options),
            _ => throw new ArgumentException(
                "external-equivalence requires one selected implementation"),
        };
    }

    private static List<EquivalenceCase> BuildOrdinaryCases(
        ExternalBenchmarkOptions options)
    {
        ForyRuntime writer = BuildFory();
        ForyRuntime reader = BuildFory();
        RegisterOrdinary(writer);
        RegisterOrdinary(reader);

        List<EquivalenceCase> cases = [];
        AddCase(
            "class-root",
            EquivalenceData.CreateOrdinaryUser(),
            writer,
            reader,
            OrdinaryUsersEqual,
            options,
            cases);
        AddCase(
            "struct-root",
            EquivalenceData.CreateOrdinaryPoint(),
            writer,
            reader,
            (expected, decoded) =>
                expected.X == decoded.X && expected.Y == decoded.Y,
            options,
            cases);
        AddCase(
            "holder-field",
            new OrdinaryUserHolder
            {
                Value = EquivalenceData.CreateOrdinaryUser(),
            },
            writer,
            reader,
            (expected, decoded) =>
                OrdinaryUsersEqual(expected.Value, decoded.Value),
            options,
            cases);

        List<OrdinaryUser> list = EquivalenceData.CreateOrdinaryList();
        AddCase(
            "list-field",
            new OrdinaryListHolder
            {
                Values = list,
            },
            writer,
            reader,
            (expected, decoded) =>
                OrdinaryListsEqual(expected.Values, decoded.Values),
            options,
            cases);
        AddCase(
            "list-root",
            list,
            writer,
            reader,
            OrdinaryListsEqual,
            options,
            cases);

        Dictionary<string, OrdinaryUser> map = EquivalenceData.CreateOrdinaryMap();
        AddCase(
            "map-field",
            new OrdinaryMapHolder
            {
                Values = map,
            },
            writer,
            reader,
            (expected, decoded) =>
                OrdinaryMapsEqual(expected.Values, decoded.Values),
            options,
            cases);
        AddCase(
            "map-root",
            map,
            writer,
            reader,
            OrdinaryMapsEqual,
            options,
            cases);
        return cases;
    }

    private static List<EquivalenceCase> BuildExternalCases(
        ExternalBenchmarkOptions options)
    {
        ForyRuntime writer = BuildFory();
        ForyRuntime reader = BuildFory();
        RegisterTargetTypes(writer);
        RegisterTargetTypes(reader);

        List<EquivalenceCase> cases = [];
        AddCase(
            "class-root",
            EquivalenceData.CreateExternalUser(),
            writer,
            reader,
            ExternalUsersEqual,
            options,
            cases);
        AddCase(
            "struct-root",
            EquivalenceData.CreateExternalPoint(),
            writer,
            reader,
            (expected, decoded) =>
                expected.X == decoded.X && expected.Y == decoded.Y,
            options,
            cases);
        AddCase(
            "holder-field",
            new ExternalUserHolder
            {
                Value = EquivalenceData.CreateExternalUser(),
            },
            writer,
            reader,
            (expected, decoded) =>
                ExternalUsersEqual(expected.Value, decoded.Value),
            options,
            cases);

        List<ExternalUser> list = EquivalenceData.CreateExternalList();
        AddCase(
            "list-field",
            new ExternalListHolder
            {
                Values = list,
            },
            writer,
            reader,
            (expected, decoded) =>
                ExternalListsEqual(expected.Values, decoded.Values),
            options,
            cases);
        AddCase(
            "list-root",
            list,
            writer,
            reader,
            ExternalListsEqual,
            options,
            cases);

        Dictionary<string, ExternalUser> map = EquivalenceData.CreateExternalMap();
        AddCase(
            "map-field",
            new ExternalMapHolder
            {
                Values = map,
            },
            writer,
            reader,
            (expected, decoded) =>
                ExternalMapsEqual(expected.Values, decoded.Values),
            options,
            cases);
        AddCase(
            "map-root",
            map,
            writer,
            reader,
            ExternalMapsEqual,
            options,
            cases);
        return cases;
    }

    private static ForyRuntime BuildFory()
    {
        return ForyRuntime.Builder().Compatible(true).Build();
    }

    private static void RegisterOrdinary(ForyRuntime fory)
    {
        fory.Register<OrdinaryUser>(UserTypeId);
        fory.Register<OrdinaryPoint>(PointTypeId);
        fory.Register<OrdinaryUserHolder>(UserHolderTypeId);
        fory.Register<OrdinaryListHolder>(ListHolderTypeId);
        fory.Register<OrdinaryMapHolder>(MapHolderTypeId);
    }

    private static void RegisterTargetTypes(ForyRuntime fory)
    {
        fory.Register<ExternalUser>(UserTypeId);
        fory.Register<ExternalPoint>(PointTypeId);
        fory.Register<ExternalUserHolder>(UserHolderTypeId);
        fory.Register<ExternalListHolder>(ListHolderTypeId);
        fory.Register<ExternalMapHolder>(MapHolderTypeId);
    }

    private static void AddCase<T>(
        string dataType,
        T value,
        ForyRuntime writer,
        ForyRuntime reader,
        Func<T, T, bool> valuesEqual,
        ExternalBenchmarkOptions options,
        List<EquivalenceCase> cases)
    {
        if (!options.IsDataEnabled(dataType))
        {
            return;
        }

        byte[] payload = writer.Serialize(value);
        T decoded = reader.Deserialize<T>(payload);
        if (!valuesEqual(value, decoded))
        {
            throw new InvalidOperationException(
                $"decoded value differs for {dataType}");
        }

        string frameBase64 = Convert.ToBase64String(payload);
        Action serialize = () =>
        {
            EquivalenceSink<byte[]>.Value = writer.Serialize(value);
        };
        Action deserialize = () =>
        {
            EquivalenceSink<T>.Value = reader.Deserialize<T>(payload);
        };

        cases.Add(new EquivalenceCase(
            dataType,
            "serialize",
            payload.Length,
            frameBase64,
            serialize));
        cases.Add(new EquivalenceCase(
            dataType,
            "deserialize",
            payload.Length,
            frameBase64,
            deserialize));
    }

    private static bool OrdinaryUsersEqual(
        OrdinaryUser expected,
        OrdinaryUser decoded)
    {
        return expected.Id == decoded.Id
            && expected.Score == decoded.Score
            && expected.Name == decoded.Name;
    }

    private static bool ExternalUsersEqual(
        ExternalUser expected,
        ExternalUser decoded)
    {
        return expected.Id == decoded.Id
            && expected.Score == decoded.Score
            && expected.Name == decoded.Name;
    }

    private static bool OrdinaryListsEqual(
        IReadOnlyList<OrdinaryUser> expected,
        IReadOnlyList<OrdinaryUser> decoded)
    {
        if (expected.Count != decoded.Count)
        {
            return false;
        }

        for (int i = 0; i < expected.Count; i++)
        {
            if (!OrdinaryUsersEqual(expected[i], decoded[i]))
            {
                return false;
            }
        }

        return true;
    }

    private static bool ExternalListsEqual(
        IReadOnlyList<ExternalUser> expected,
        IReadOnlyList<ExternalUser> decoded)
    {
        if (expected.Count != decoded.Count)
        {
            return false;
        }

        for (int i = 0; i < expected.Count; i++)
        {
            if (!ExternalUsersEqual(expected[i], decoded[i]))
            {
                return false;
            }
        }

        return true;
    }

    private static bool OrdinaryMapsEqual(
        IReadOnlyDictionary<string, OrdinaryUser> expected,
        IReadOnlyDictionary<string, OrdinaryUser> decoded)
    {
        if (expected.Count != decoded.Count)
        {
            return false;
        }

        foreach ((string key, OrdinaryUser expectedUser) in expected)
        {
            if (!decoded.TryGetValue(key, out OrdinaryUser? decodedUser)
                || !OrdinaryUsersEqual(expectedUser, decodedUser))
            {
                return false;
            }
        }

        return true;
    }

    private static bool ExternalMapsEqual(
        IReadOnlyDictionary<string, ExternalUser> expected,
        IReadOnlyDictionary<string, ExternalUser> decoded)
    {
        if (expected.Count != decoded.Count)
        {
            return false;
        }

        foreach ((string key, ExternalUser expectedUser) in expected)
        {
            if (!decoded.TryGetValue(key, out ExternalUser? decodedUser)
                || !ExternalUsersEqual(expectedUser, decodedUser))
            {
                return false;
            }
        }

        return true;
    }

    private static string ImplementationName(ExternalImplementation implementation)
    {
        return implementation switch
        {
            ExternalImplementation.Ordinary => "ordinary",
            ExternalImplementation.External => "external",
            _ => throw new ArgumentException(
                "external-equivalence requires one selected implementation"),
        };
    }

    private static void PrintSummary(
        string implementation,
        List<EquivalenceSideResult> results)
    {
        Console.WriteLine();
        Console.WriteLine(
            $"=== External-Type {implementation} Summary ===");
        foreach (EquivalenceSideResult result in results)
        {
            Console.WriteLine(
                $"{result.DataType}/{result.Operation}: "
                + $"{result.Measurement.AverageNanoseconds:N1} ns/op");
            Console.WriteLine(
                $"  allocated={result.AllocatedBytesPerOperation:N2} B/op");
        }
    }

    private static class EquivalenceSink<T>
    {
        public static T Value = default!;
    }
}
