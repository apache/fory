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

using System.Collections.Immutable;
using System.Reflection;
using Apache.Fory.Generator;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace Apache.Fory.Tests;

public sealed class MetadataPublicationTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task FirstUse(bool trackRef)
    {
        await RunScenario("Concurrent", trackRef, false,
            "global::System.Threading.Volatile.Write(ref __ForyTypeMetaCache, cache);");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task IndependentCodecs(bool trackRef)
    {
        await RunScenario("Concurrent", trackRef, true,
            "global::System.Threading.Volatile.Write(ref __ForyTypeMetaCache, cache);");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SchemaReplacement(bool trackRef)
    {
        await RunScenario("Replacement", trackRef, false);
    }

    private static async Task RunScenario(
        string method, bool trackRef, bool independent, string? pauseAfter = null)
    {
        CSharpParseOptions parseOptions = new(LanguageVersion.CSharp12);
        IEnumerable<MetadataReference> references =
            ((string)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES")!)
            .Split(Path.PathSeparator)
            .Select(path => MetadataReference.CreateFromFile(path))
            .Append(MetadataReference.CreateFromFile(typeof(Fory).Assembly.Location));
        CSharpCompilation compilation = CSharpCompilation.Create(
            "MetadataPublication_" + Guid.NewGuid().ToString("N"),
            [CSharpSyntaxTree.ParseText(Scenario, parseOptions)], references,
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary,
                optimizationLevel: OptimizationLevel.Release));
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation, out Compilation output, out ImmutableArray<Diagnostic> diagnostics);
        Assert.DoesNotContain(diagnostics, d => d.Severity == DiagnosticSeverity.Error);
        if (pauseAfter is not null)
        {
            // Instrument only generated publication; the payload and schema stay unchanged.
            SyntaxTree generated = Assert.Single(output.SyntaxTrees.Skip(1));
            string original = generated.ToString();
            Assert.Contains(pauseAfter, original, StringComparison.Ordinal);
            string instrumented = original.Replace(pauseAfter,
                pauseAfter + "\n        global::Scenario.AfterPublication();", StringComparison.Ordinal);
            output = output.ReplaceSyntaxTree(generated,
                CSharpSyntaxTree.ParseText(instrumented, parseOptions, path: generated.FilePath));
        }

        using MemoryStream stream = new();
        var emitted = output.Emit(stream);
        Assert.True(emitted.Success, string.Join(Environment.NewLine, emitted.Diagnostics));
        Assembly assembly = Assembly.Load(stream.ToArray());
        MethodInfo entry = assembly.GetType("Scenario")!.GetMethod(method)!;
        await Task.Run(async () =>
        {
            Task task = (Task)entry.Invoke(null, [trackRef, independent])!;
            await task;
        }).WaitAsync(TimeSpan.FromSeconds(30));
    }

    private const string Scenario = """
        using System;
        using System.Threading;
        using System.Threading.Tasks;
        using Apache.Fory;

        [ForyStruct]
        public sealed class Payload
        {
            public int Count { get; set; }
            public string Message { get; set; } = "";
        }

        [ForyStruct]
        public sealed class ExtendedPayload
        {
            public int Count { get; set; }
            public string Message { get; set; } = "";
            public long Extra { get; set; }
        }

        public static class Scenario
        {
            private static readonly ManualResetEventSlim Published = new(false);
            private static readonly ManualResetEventSlim Resume = new(false);
            private static int armed;

            public static void AfterPublication()
            {
                if (Interlocked.Exchange(ref armed, 0) == 1)
                {
                    Published.Set();
                    if (!Resume.Wait(TimeSpan.FromSeconds(10)))
                        throw new TimeoutException("publication hook timed out");
                }
            }

            private static void Check(Payload value)
            {
                if (value.Count != 37 || value.Message != "correct")
                    throw new Exception($"Count={value.Count}, Message={value.Message}");
            }

            public static async Task Concurrent(bool trackRef, bool independent)
            {
                using var firstCodec = global::Apache.Fory.Fory.Builder().Compatible(true).TrackRef(trackRef).BuildThreadSafe();
                using var otherCodec = global::Apache.Fory.Fory.Builder().Compatible(true).TrackRef(trackRef).BuildThreadSafe();
                firstCodec.Register<Payload>("test", "First");
                otherCodec.Register<Payload>("test", "Other");
                var secondCodec = independent ? otherCodec : firstCodec;
                var value = new Payload { Count = 37, Message = "correct" };
                byte[] firstBytes = firstCodec.Serialize(value);
                byte[] secondBytes = secondCodec.Serialize(value);
                armed = 1;
                Task first = Task.Factory.StartNew(() => Check(firstCodec.Deserialize<Payload>(firstBytes)),
                    CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
                try
                {
                    if (!Published.Wait(TimeSpan.FromSeconds(10)))
                        throw new TimeoutException("publication hook was not reached");
                    Check(secondCodec.Deserialize<Payload>(secondBytes));
                }
                finally
                {
                    Resume.Set();
                    await first.WaitAsync(TimeSpan.FromSeconds(10));
                }
            }

            public static async Task Replacement(bool trackRef, bool independent)
            {
                using var reader = global::Apache.Fory.Fory.Builder().Compatible(true).TrackRef(trackRef).BuildThreadSafe();
                using var writer = global::Apache.Fory.Fory.Builder().Compatible(true).TrackRef(trackRef).BuildThreadSafe();
                reader.Register<Payload>("test", "Payload");
                writer.Register<ExtendedPayload>("test", "Payload");
                byte[] exact = reader.Serialize(new Payload { Count = 37, Message = "correct" });
                byte[] evolved = writer.Serialize(new ExtendedPayload { Count = 37, Message = "correct", Extra = 99 });
                Check(reader.Deserialize<Payload>(exact));
                // Registration between completed roots is supported; no concurrent mutation.
                reader.Register<ExtendedPayload>("test", "Extra");
                Task[] workers = new Task[4];
                for (int worker = 0; worker < workers.Length; worker++)
                {
                    workers[worker] = Task.Run(() =>
                    {
                        for (int i = 0; i < 128; i++)
                        {
                            Check(reader.Deserialize<Payload>(evolved));
                            Check(reader.Deserialize<Payload>(exact));
                        }
                    });
                }
                await Task.WhenAll(workers).WaitAsync(TimeSpan.FromSeconds(10));
            }
        }
        """;
}
