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
using Apache.Fory.Generator;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace Apache.Fory.Tests;

public sealed class ForyGeneratorTests
{
    [Fact]
    public void SplitAttributesCompile()
    {
        const string source = """
            using Apache.Fory;

            namespace GeneratedDiagnostics;

            [ForyEnum]
            public enum Status
            {
                Ready,
                Done,
            }

            [ForyUnion]
            public abstract partial record Choice
            {
                private Choice()
                {
                }

                [ForyUnknownCase]
                public sealed partial record Unknown(UnknownCase Value) : Choice;

                [ForyCase(0)]
                public sealed partial record Text(string Value) : Choice;
            }

            [ForyStruct]
            public sealed class Envelope
            {
                public Status Status { get; set; }
                public Choice Choice { get; set; } = new Choice.Text(string.Empty);
            }
            """;

        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver.RunGeneratorsAndUpdateCompilation(compilation, out Compilation output, out ImmutableArray<Diagnostic> diagnostics);

        Assert.DoesNotContain(
            diagnostics.Concat(output.GetDiagnostics()),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
    }

    [Fact]
    public void NegativeForyFieldIdReportsDiagnostic()
    {
        const string source = """
            using Apache.Fory;

            namespace GeneratedDiagnostics;

            [ForyStruct]
            public sealed class InvalidFieldId
            {
                [ForyField(-1)]
                public int Value { get; set; }
            }
            """;

        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(compilation, out Compilation output, out ImmutableArray<Diagnostic> diagnostics);

        ImmutableArray<Diagnostic> generatorDiagnostics = driver.GetRunResult().Diagnostics;
        Assert.Contains(generatorDiagnostics.Concat(diagnostics), diagnostic => diagnostic.Id == "FORY004");
        Assert.DoesNotContain(output.GetDiagnostics(), diagnostic => diagnostic.Severity == DiagnosticSeverity.Error && diagnostic.Id != "FORY004");
    }

    [Fact]
    public void UnionRequiresRealCaseBeyondUnknown()
    {
        const string source = """
            using Apache.Fory;

            namespace GeneratedDiagnostics;

            [ForyUnion]
            public abstract partial record OnlyUnknown
            {
                private OnlyUnknown()
                {
                }

                [ForyUnknownCase]
                public sealed partial record Unknown(UnknownCase Value) : OnlyUnknown;
            }
            """;

        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(compilation, out Compilation output, out ImmutableArray<Diagnostic> diagnostics);

        ImmutableArray<Diagnostic> generatorDiagnostics = driver.GetRunResult().Diagnostics;
        Assert.Contains(
            generatorDiagnostics.Concat(diagnostics),
            diagnostic =>
                diagnostic.Id == "FORY006" &&
                diagnostic.GetMessage().Contains("at least one non-Unknown case", StringComparison.Ordinal));
        Assert.DoesNotContain(output.GetDiagnostics(), diagnostic => diagnostic.Severity == DiagnosticSeverity.Error && diagnostic.Id != "FORY006");
    }

    [Fact]
    public void CompatibleReadSourceUsesTypedCases()
    {
        const string source = """
            using System.Collections.Generic;
            using Apache.Fory;
            using S = Apache.Fory.Schema.Types;

            namespace GeneratedDiagnostics;

            [ForyStruct]
            public sealed class Shape
            {
                [ForyField(1, Type = typeof(S.Bool))]
                public bool Flag { get; set; }

                [ForyField(2, Type = typeof(S.Int32))]
                public int? Count { get; set; }

                [ForyField(3, Type = typeof(S.String))]
                public string? Name { get; set; }

                [ForyField(4, Type = typeof(S.Array<S.Int32>))]
                public int[] Values { get; set; } = [];
            }
            """;

        string generated = GenerateSource(source);

        Assert.Contains("case 0:", generated, StringComparison.Ordinal);
        Assert.Contains("case 1:", generated, StringComparison.Ordinal);
        Assert.Contains("case 2:", generated, StringComparison.Ordinal);
        Assert.Contains("case 3:", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("__ForyLocalFields", generated, StringComparison.Ordinal);
        Assert.Contains("ReadBoolField(context, remoteField)", generated, StringComparison.Ordinal);
        Assert.Contains("ReadNullableStringField(context, remoteField)", generated, StringComparison.Ordinal);
        Assert.Contains("ReadNullableInt32Field(context, remoteField)", generated, StringComparison.Ordinal);
        Assert.Contains("ReadValuesFieldBridge(context, remoteField.FieldType", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("__ForyReadCompatibleField<", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("RequiresScalarRead", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("CompatibleScalarConverter.ReadBoolField(context, remoteField.FieldType", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("if (remoteField.FieldType.TypeId ==", generated, StringComparison.Ordinal);
    }

    private static string GenerateSource(string source)
    {
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(compilation, out Compilation output, out ImmutableArray<Diagnostic> diagnostics);

        Assert.DoesNotContain(
            diagnostics.Concat(output.GetDiagnostics()),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);

        return string.Join(
            "\n",
            driver.GetRunResult().Results.SelectMany(result => result.GeneratedSources)
                .Select(sourceResult => sourceResult.SourceText.ToString()));
    }

    private static CSharpCompilation CreateCompilation(string source)
    {
        IEnumerable<MetadataReference> platformReferences =
            ((string)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES")!)
            .Split(Path.PathSeparator)
            .Select(path => MetadataReference.CreateFromFile(path));
        MetadataReference foryReference =
            MetadataReference.CreateFromFile(typeof(ForyStructAttribute).Assembly.Location);

        return CSharpCompilation.Create(
            "ForyGeneratorDiagnostics",
            [CSharpSyntaxTree.ParseText(source, new CSharpParseOptions(LanguageVersion.CSharp12))],
            platformReferences.Append(foryReference),
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
    }
}
