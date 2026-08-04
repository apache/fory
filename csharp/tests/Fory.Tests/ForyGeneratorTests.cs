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
using Microsoft.CodeAnalysis.Emit;

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
        Assert.Contains("ReadM3FieldBridge(context, remoteField.FieldType", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("__ForyReadCompatibleField<", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("RequiresScalarRead", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("CompatibleScalarConverter.ReadBoolField(context, remoteField.FieldType", generated, StringComparison.Ordinal);
        Assert.DoesNotContain("if (remoteField.FieldType.TypeId ==", generated, StringComparison.Ordinal);
    }

    [Fact]
    public void DepthGuardGeneration()
    {
        const string source = """
            using System.Collections.Generic;
            using Apache.Fory;

            namespace GeneratedDiagnostics;

            [ForyEnum]
            public enum State
            {
                Ready,
            }

            [ForyStruct]
            public sealed class Leaf
            {
                public int Value { get; set; }
            }

            [ForyStruct]
            public sealed class Acyclic
            {
                public Leaf Leaf { get; set; } = new();
                public List<Leaf> Leaves { get; set; } = [];
                public State State { get; set; }
                public Unknown Unknown { get; set; } = new();
            }

            public sealed class Unknown
            {
                public int Value { get; set; }
            }

            [ForyStruct]
            public sealed class Recursive
            {
                public List<Recursive> Children { get; set; } = [];
            }
            """;

        string generated = GenerateSource(source);

        Assert.DoesNotContain(
            "ReadNested<global::GeneratedDiagnostics.Leaf>",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "ReadNestedData<global::System.Collections.Generic.List<global::GeneratedDiagnostics.Leaf>>",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "ReadNestedData<global::GeneratedDiagnostics.State>",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "ReadNestedData<global::System.Collections.Generic.List<global::GeneratedDiagnostics.Recursive>>",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "ReadNested<global::GeneratedDiagnostics.Unknown>",
            generated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void CompatibleBinaryListChecksBeforeCapacity()
    {
        const string source = """
            using System.Collections.Generic;
            using Apache.Fory;
            using S = Apache.Fory.Schema.Types;

            namespace GeneratedDiagnostics;

            [ForyStruct]
            public sealed class BinaryListShape
            {
                [ForyField(1, Type = typeof(S.Array<S.UInt8>))]
                public List<byte> Value { get; set; } = [];
            }
            """;

        string generated = GenerateSource(source);

        int lengthIndex = generated.IndexOf(
            "int __foryLength = checked((int)context.Reader.ReadVarUInt32());",
            StringComparison.Ordinal);
        int checkIndex = generated.IndexOf(
            "context.Reader.CheckBound(__foryLength);",
            lengthIndex,
            StringComparison.Ordinal);
        int allocationIndex = generated.IndexOf(
            "new(__foryLength);",
            lengthIndex,
            StringComparison.Ordinal);

        Assert.True(lengthIndex >= 0);
        Assert.True(checkIndex > lengthIndex);
        Assert.True(allocationIndex > checkIndex);
    }

    [Fact]
    public void GeneratedReadProgressFacts()
    {
        const string source = """
            using System.Collections.Generic;
            using Apache.Fory;
            using S = Apache.Fory.Schema.Types;

            namespace GeneratedDiagnostics;

            [ForyStruct]
            public sealed class Empty
            {
            }

            [ForyStruct]
            public sealed class Scalar
            {
                public int Value { get; set; }
            }

            [ForyStruct]
            public sealed class DynamicCollection
            {
                public List<int> Values { get; set; } = [];
            }

            [ForyStruct]
            public sealed class FixedCollection
            {
                [ForyField(Type = typeof(S.List<S.Int32>))]
                public List<int> Values { get; set; } = [];
            }
            """;

        string generated = GenerateSource(source);

        Assert.EndsWith(
            ", false);",
            GeneratedRegistration(generated, "Empty"),
            StringComparison.Ordinal);
        Assert.EndsWith(
            ", true);",
            GeneratedRegistration(generated, "Scalar"),
            StringComparison.Ordinal);
        Assert.EndsWith(
            ", false);",
            GeneratedRegistration(generated, "DynamicCollection"),
            StringComparison.Ordinal);
        Assert.EndsWith(
            ", true);",
            GeneratedRegistration(generated, "FixedCollection"),
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "UnbackedContainer",
            generated,
            StringComparison.Ordinal);

        static string GeneratedRegistration(string generated, string typeName)
        {
            return generated.Split('\n')
                .Single(line => line.Contains(
                    $"RegisterGeneratedStruct<global::GeneratedDiagnostics.{typeName},",
                    StringComparison.Ordinal))
                .Trim();
        }
    }

    [Fact]
    public void ExternalTargetsUseOneEmitter()
    {
        const string source = """
            #nullable enable
            using System.Collections.Generic;
            using Apache.Fory;
            using UserTarget = Fory.ExternalTypes.ExternalUser;
            using S = Apache.Fory.Schema.Types;

            namespace GeneratedDiagnostics;

            [ForyStruct(Target = typeof(UserTarget))]
            internal abstract class UserSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(UserTarget),
                    TargetMemberName = "<Id>k__BackingField")]
                public abstract int Id { get; }

                [ForyField(
                    2,
                    TargetDeclaringType = typeof(UserTarget),
                    TargetMemberName = "<Name>k__BackingField")]
                public abstract string Name { get; }

                [ForyField(
                    3,
                    TargetDeclaringType = typeof(UserTarget),
                    TargetMemberName = "<Friend>k__BackingField")]
                public abstract UserTarget? Friend { get; }

                [ForyField(4)]
                public abstract List<UserTarget> Links { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(UserTarget),
                    TargetMemberName = "<Links>k__BackingField")]
                public abstract List<UserTarget> LinksStorage { get; }
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalPoint))]
            internal abstract class PointSerializer
            {
                [ForyField(1)]
                public abstract int X { get; }

                [ForyField(2)]
                public abstract int Y { get; }
            }

            [ForyEnum(Target = typeof(Fory.ExternalTypes.ExternalStatus))]
            internal static class StatusSerializer
            {
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalBox<string>))]
            internal abstract class StringBoxSerializer
            {
                [ForyField(1)]
                public abstract string Value { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalBox<string>),
                    TargetMemberName = "<Value>k__BackingField")]
                public abstract string ValueStorage { get; }
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalDerived))]
            internal abstract class DerivedSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalDerived),
                    TargetMemberName = "<Id>k__BackingField")]
                public abstract int Id { get; }

                [ForyField(
                    2,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalBase),
                    TargetMemberName = "<BaseName>k__BackingField")]
                public abstract string BaseName { get; }
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalFields))]
            internal abstract class FieldsSerializer
            {
                [ForyField(1)]
                public abstract int Count { get; }

                [ForyField(
                    2,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalFields),
                    TargetMemberName = "<Name>k__BackingField")]
                public abstract string Name { get; }

                [ForyField(
                    3,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalFields),
                    TargetMemberName = "<event>k__BackingField")]
                public abstract int @event { get; }
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalSchemaModel))]
            internal abstract class SchemaSerializer
            {
                [ForyField(
                    1,
                    Type = typeof(S.Fixed<S.Int32>),
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<FixedValue>k__BackingField")]
                public abstract int FixedValue { get; }

                [ForyField(
                    2,
                    Type = typeof(S.Tagged<S.Int64>),
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<TaggedValue>k__BackingField")]
                public abstract long TaggedValue { get; }

                [ForyField(
                    3,
                    Type = typeof(S.Array<S.Int32>),
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<ArrayValue>k__BackingField")]
                public abstract int[] ArrayValue { get; }

                [ForyField(4, Type = typeof(S.List<S.Int32>))]
                public abstract List<int> ListValue { get; }

                [ForyField(5, Type = typeof(S.Set<S.Int32>))]
                public abstract HashSet<int> SetValue { get; }

                [ForyField(6, Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]
                public abstract Dictionary<uint, List<ulong?>?> NestedValue { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<ListValue>k__BackingField")]
                public abstract List<int> ListValueStorage { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<SetValue>k__BackingField")]
                public abstract HashSet<int> SetValueStorage { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalSchemaModel),
                    TargetMemberName = "<NestedValue>k__BackingField")]
                public abstract Dictionary<uint, List<ulong?>?> NestedValueStorage { get; }
            }

            [ForyStruct(
                Target = typeof(Fory.ExternalTypes.ExternalEvolutionOff),
                Evolving = false)]
            internal abstract class EvolutionOffSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalEvolutionOff),
                    TargetMemberName = "<Value>k__BackingField")]
                public abstract int Value { get; }
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalVersionOne))]
            internal abstract partial class VersionSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalVersionOne),
                    TargetMemberName = "<Id>k__BackingField")]
                public abstract int Id { get; }
            }

            internal abstract partial class VersionSerializer
            {
                [ForyField(
                    2,
                    TargetDeclaringType = typeof(Fory.ExternalTypes.ExternalVersionOne),
                    TargetMemberName = "<Name>k__BackingField")]
                public abstract string Name { get; }
            }

            [ForyStruct]
            internal sealed class NullablePointHolder
            {
                public Fory.ExternalTypes.ExternalPoint? Point { get; set; }
            }

            [ForyStruct(Evolving = false)]
            internal sealed class LocalEvolutionOff
            {
                public int Value { get; set; }
            }
            """;

        string generated = GenerateSource(source);
        const string userType = "global::Fory.ExternalTypes.ExternalUser";

        Assert.Contains($"Serializer<{userType}>", generated, StringComparison.Ordinal);
        Assert.Contains($"in {userType} value", generated, StringComparison.Ordinal);
        Assert.Contains($"public override {userType} ReadData", generated, StringComparison.Ordinal);
        Assert.Contains($"{userType} value = new {userType}();", generated, StringComparison.Ordinal);
        Assert.Contains($"GetTypeMeta<{userType}>()", generated, StringComparison.Ordinal);
        Assert.Contains(
            generated.Split('\n'),
            line =>
                line.Contains(
                    $"RegisterGeneratedStruct<{userType},",
                    StringComparison.Ordinal)
                && line.TrimEnd().EndsWith(">(true, true);", StringComparison.Ordinal));
        Assert.Contains(
            "UnsafeAccessorKind.Field, Name = \"<Name>k__BackingField\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "UnsafeAccessorKind.Field, Name = \"<event>k__BackingField\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            generated.Split('\n'),
            line =>
                line.Contains(
                    "RegisterGeneratedStruct<global::Fory.ExternalTypes.ExternalEvolutionOff,",
                    StringComparison.Ordinal)
                && line.TrimEnd().EndsWith(">(false, true);", StringComparison.Ordinal));
        Assert.Contains(
            generated.Split('\n'),
            line =>
                line.Contains(
                    "RegisterGeneratedStruct<global::GeneratedDiagnostics.LocalEvolutionOff,",
                    StringComparison.Ordinal)
                && line.TrimEnd().EndsWith(">(false, true);", StringComparison.Ordinal));
        Assert.Contains(
            "EnumSerializer<global::Fory.ExternalTypes.ExternalStatus>",
            generated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void OrdinaryHierarchyEmitsProviderContracts()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            [ForyStruct]
            public abstract class Entity
            {
                [ForyField(1)]
                private int _identifier;

                [ForyField(2)]
                protected virtual string Text { get; set; } = string.Empty;

                private readonly long _cache = 1;
            }

            [ForyStruct]
            public sealed class Event : Entity
            {
                protected override string Text { get; set; } = string.Empty;

                [ForyField(3)]
                public long Timestamp;
            }
            """;

        string generated = GenerateSource(source);

        Assert.Equal(
            1,
            generated.Split(
                "ForyGeneratedHierarchyProvider(typeof(global::GeneratedDiagnostics.",
                StringSplitOptions.None).Length - 1);
        Assert.Contains(
            "UnsafeAccessorKind.Field, Name = \"_identifier\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "static extern ref global::System.Int32 F0",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "UnsafeAccessorKind.Method, Name = \"get_Text\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "UnsafeAccessorKind.Method, Name = \"set_Text\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "\"Text\", \"Text\", FieldId = 2",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".HierarchyShallowBytes + 4 + 8",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "RegisterGeneratedStruct<global::GeneratedDiagnostics.Entity,",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "RegisterGeneratedStruct<global::GeneratedDiagnostics.Event,",
            generated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void BaseOnlyProviderOwnsPrivateFields()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            public abstract class VendorBase
            {
                protected VendorBase(int seed)
                {
                }

                private long _identifier;
                private int _cache;
                private string Secret { get; set; } = string.Empty;
                public int Count;
            }

            [ForyStruct(Target = typeof(VendorBase), BaseOnly = true)]
            public abstract class VendorProvider
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(VendorBase),
                    TargetMemberName = "_identifier")]
                public abstract long Identifier { get; }

                [ForyField(
                    2,
                    TargetDeclaringType = typeof(VendorBase),
                    TargetMemberName = "<Secret>k__BackingField")]
                public abstract string Secret { get; }

                [ForyField(3)]
                public abstract int Count { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(VendorBase),
                    TargetMemberName = "_cache")]
                public abstract int CacheStorage { get; }
            }
            """;

        string generated = GenerateSource(source);

        Assert.Contains(
            "public static class __ForyHierarchy_",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "HierarchyShallowBytes = checked(0L + 4 + 4 + 4 + 8);",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "UnsafeAccessorKind.Field, Name = \"_identifier\"",
            generated,
            StringComparison.Ordinal);
        Assert.Contains(
            "UnsafeAccessorKind.Field, Name = \"<Secret>k__BackingField\"",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "UnsafeAccessorKind.Field, Name = \"_cache\"",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "__ForyTypeMetaCacheLock",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "__ForyGraphElementBytes",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "__ForyGeneratedModuleInitializer",
            generated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "RegisterGeneratedStruct<global::GeneratedDiagnostics.VendorBase,",
            generated,
            StringComparison.Ordinal);
        Assert.Equal(
            3,
            generated.Split(
                "ForyGeneratedWireMember(",
                StringSplitOptions.None).Length - 1);
        Assert.Contains(
            "ForyGeneratedWireMember(0,",
            generated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void ReferencedParentApiSuppliesWireAndBudget()
    {
        const string parentSource = """
            using System.Runtime.CompilerServices;
            using Apache.Fory;
            [assembly: InternalsVisibleTo("Fory.ChildModels")]
            namespace ParentModels;

            [ForyStruct]
            public abstract class Parent
            {
                [ForyField(1)]
                private int _identifier;

                [ForyField(2)]
                protected abstract string ProtectedText { get; set; }
            }

            [ForyStruct]
            internal abstract class InternalParent
            {
                [ForyField(3)]
                internal int InternalValue;
            }

            public class GenericBase<T>
            {
                public T Value { get; set; } = default!;
            }

            [ForyStruct(Target = typeof(GenericBase<dynamic>), BaseOnly = true)]
            public abstract class DynamicProvider
            {
                [ForyField(4)]
                public abstract dynamic Value { get; }

                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(GenericBase<dynamic>),
                    TargetMemberName = "<Value>k__BackingField")]
                public abstract dynamic ValueStorage { get; }
            }
            """;
        MetadataReference parentReference = CreateGeneratedReference(
            "Fory.ParentModels",
            parentSource,
            out string parentGenerated);
        const string childSource = """
            using Apache.Fory;
            using ParentModels;
            namespace ChildModels;

            [ForyStruct]
            public class Child : Parent
            {
                protected override string ProtectedText { get; set; } = string.Empty;

                [ForyField(3)]
                public int Value { get; set; }
            }

            [ForyStruct]
            internal sealed class InternalChild : InternalParent
            {
                [ForyField(5)]
                public int Value { get; set; }
            }

            [ForyStruct]
            public class DynamicChild : GenericBase<object>
            {
                [ForyField(6)]
                public int Count { get; set; }
            }
            """;
        MetadataReference middleReference = CreateGeneratedReference(
            "Fory.ChildModels",
            childSource,
            out string childGenerated,
            // Roslyn permits duplicate references to the same provider assembly.
            additionalReferences: [parentReference, parentReference]);
        const string leafSource = """
            using Apache.Fory;
            using ChildModels;
            namespace LeafModels;

            [ForyStruct]
            public sealed class Leaf : Child
            {
                [ForyField(7)]
                public long LeafValue;
            }

            [ForyStruct]
            public sealed class DynamicLeaf : DynamicChild
            {
                [ForyField(8)]
                public long LeafValue;
            }
            """;
        string leafGenerated = GenerateSource(
            leafSource,
            includeExternalTypes: false,
            additionalReferences: [parentReference, middleReference],
            assemblyName: "Fory.LeafModels");
        static string ProviderName(
            string generated,
            string targetFragment)
        {
            string[] lines = generated.Split('\n');
            int markerIndex = Array.FindIndex(
                lines,
                line =>
                    line.Contains(
                        "ForyGeneratedHierarchyProvider",
                        StringComparison.Ordinal) &&
                    line.Contains(targetFragment, StringComparison.Ordinal));
            Assert.True(markerIndex >= 0);
            return lines
                .Skip(markerIndex + 1)
                .First(line => line.Contains(" class ", StringComparison.Ordinal))
                .Split(' ', StringSplitOptions.RemoveEmptyEntries)[3]
                .TrimEnd('\r');
        }

        string ordinaryParentProvider =
            ProviderName(parentGenerated, "ParentModels.Parent");
        string externalParentProvider =
            ProviderName(parentGenerated, "ParentModels.GenericBase");
        string ordinaryMiddleProvider =
            ProviderName(childGenerated, "ChildModels.Child");
        string externalMiddleProvider =
            ProviderName(childGenerated, "ChildModels.DynamicChild");

        Assert.Contains(
            "public static class __ForyHierarchy_",
            parentGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            $"{ordinaryParentProvider}.HierarchyShallowBytes + 4 + 4",
            childGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            $"{externalParentProvider}.HierarchyShallowBytes + 4",
            childGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            $"{ordinaryMiddleProvider}.HierarchyShallowBytes + 8",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            $"{externalMiddleProvider}.HierarchyShallowBytes + 8",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            "\"_identifier\"",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            "\"protected_text\"",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains("value.InternalValue", parentGenerated, StringComparison.Ordinal);
        Assert.Contains(
            ".F0(((global::ParentModels.Parent)",
            childGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".F0(((global::ParentModels.Parent)",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".G0(((global::ParentModels.GenericBase",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".S0(((global::ParentModels.GenericBase",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".G0(((global::ChildModels.DynamicChild)",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            ".S0(((global::ChildModels.DynamicChild)",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            "TypeMetaFieldInfo((short)4,",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            "TypeMetaFieldInfo((short)6,",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.Contains(
            "TypeMetaFieldInfo((short)8,",
            leafGenerated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "UnsafeAccessorKind.Field, Name = \"_identifier\"",
            childGenerated,
            StringComparison.Ordinal);
        Assert.DoesNotContain(
            "UnsafeAccessorKind.Field, Name = \"_identifier\"",
            leafGenerated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void ReferencedBaseWithoutProviderIsRejected()
    {
        MetadataReference parentReference = CreateReference(
            "Fory.UnannotatedParent",
            """
            namespace ParentModels;
            public abstract class Parent
            {
                private int _identifier;
            }
            """);
        const string childSource = """
            using Apache.Fory;
            using ParentModels;
            namespace ChildModels;
            [ForyStruct]
            public sealed class Child : Parent
            {
                public int Value { get; set; }
            }
            """;

        Assert.Contains(
            GenerateDiagnostics(
                childSource,
                includeExternalTypes: false,
                additionalReferences: [parentReference],
                assemblyName: "Fory.UnannotatedChild"),
            diagnostic => diagnostic.Id == "FORY019");
    }

    [Theory]
    [InlineData("object", "")]
    [InlineData(
        "Parent",
        """
        public static readonly int HierarchyShallowBytes;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(1, typeof(Parent), "Value", "Value")]
        public static ref int F1(Parent value) => ref value.Value;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(0, typeof(Parent), "Value", "Value")]
        private static ref int F0(Parent value) => ref value.Value;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;
        private static int Storage;

        [ForyGeneratedWireMember(0, typeof(Parent), "Value", "Value")]
        public static ref int F0(object value) => ref Storage;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(0, typeof(Parent), "Value", "Value")]
        public static ref int F0(Parent value) => ref value.Value;
        public static ref int F0<T>(T value) where T : Parent => ref value.Value;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(
            0,
            typeof(Parent),
            "Value",
            "Value",
            Slot = "Parent.Value")]
        public static int G0(Parent value) => value.Value;
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(
            0,
            typeof(Parent),
            "Value",
            "Value",
            Slot = "Parent.Value")]
        public static int G0(Parent value) => value.Value;
        public static void S0(Parent value, long fieldValue) { }
        """)]
    [InlineData(
        "Parent",
        """
        public static readonly long HierarchyShallowBytes;

        [ForyGeneratedWireMember(
            0,
            typeof(Parent),
            "Value",
            "Value",
            Slot = "Parent.Value")]
        public static string? G0(Parent value) => null;
        public static void S0(Parent value, string fieldValue) { }
        """)]
    public void MalformedProviderIsRejected(
        string markerTarget,
        string providerMembers)
    {
        MetadataReference targetReference = CreateReference(
            "MalformedProviderTarget",
            """
            namespace ParentModels;
            public abstract class Parent
            {
                public int Value;
            }
            """);
        _ = CreateGeneratedReference(
            "ProviderNameSeed",
            """
            using Apache.Fory;
            using ParentModels;
            [ForyStruct(Target = typeof(Parent), BaseOnly = true)]
            public abstract class Provider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            """,
            out string generated,
            [targetReference]);
        string providerName = generated.Split('\n')
            .Single(line => line.Contains(
                " static class __ForyHierarchy_",
                StringComparison.Ordinal))
            .Split(' ', StringSplitOptions.RemoveEmptyEntries)[3];
        string malformedSource = $$"""
            #nullable enable
            using Apache.Fory;
            using ParentModels;
            namespace Apache.Fory.Generated;
            [ForyGeneratedHierarchyProvider(typeof({{markerTarget}}), 1)]
            public static class {{providerName}}
            {
            {{providerMembers}}
            }
            """;
        CSharpCompilation malformedCompilation = CreateCompilation(
            malformedSource,
            includeExternalTypes: false,
            additionalReferences: [targetReference],
            assemblyName: "MalformedProvider");
        Assert.DoesNotContain(
            malformedCompilation.GetDiagnostics(),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
        MetadataReference malformedProvider =
            malformedCompilation.ToMetadataReference();
        const string childSource = """
            using Apache.Fory;
            using ParentModels;
            [ForyStruct]
            public sealed class Child : Parent
            {
                [ForyField(2)]
                public int ChildValue;
            }
            """;

        Diagnostic[] diagnostics = GenerateDiagnostics(
            childSource,
            includeExternalTypes: false,
            additionalReferences: [targetReference, malformedProvider])
            .ToArray();
        Assert.Contains(
            diagnostics,
            diagnostic => diagnostic.Id == "FORY020");
        Assert.DoesNotContain(
            diagnostics,
            diagnostic =>
                diagnostic.Severity >= DiagnosticSeverity.Warning &&
                diagnostic.Id != "FORY020");
    }

    [Fact]
    public void AliasOnlyProviderIsRejected()
    {
        MetadataReference parentReference = CreateGeneratedReference(
            "Fory.AliasedParent",
            """
            using Apache.Fory;
            namespace ParentModels;
            [ForyStruct]
            public abstract class Parent
            {
                [ForyField(1)]
                public int Value;
            }
            """,
            out _);
        MetadataReference aliasedParent = parentReference.WithProperties(
            new MetadataReferenceProperties(
                aliases: ImmutableArray.Create("parent")));
        const string childSource = """
            extern alias parent;
            using Apache.Fory;
            [ForyStruct]
            public sealed class Child : parent::ParentModels.Parent
            {
                [ForyField(2)]
                public int ChildValue;
            }
            """;

        Assert.Contains(
            GenerateDiagnostics(
                childSource,
                includeExternalTypes: false,
                additionalReferences: [aliasedParent]),
            diagnostic => diagnostic.Id == "FORY019");
    }

    [Fact]
    public void ProviderVisibilityScopesOwnership()
    {
        MetadataReference targetReference = CreateReference(
            "VendorModels",
            """
            namespace VendorModels;
            public class VendorBase
            {
                public int Value;
            }
            """);
        MetadataReference hiddenProvider = CreateGeneratedReference(
            "HiddenProvider",
            """
            using Apache.Fory;
            using VendorModels;
            [ForyStruct(Target = typeof(VendorBase), BaseOnly = true)]
            internal abstract class HiddenProvider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            """,
            out string hiddenGenerated,
            [targetReference],
            includePrivateMembers: true);
        MetadataReference sharedProvider = CreateGeneratedReference(
            "SharedProvider",
            """
            using Apache.Fory;
            using VendorModels;
            [ForyStruct(Target = typeof(VendorBase), BaseOnly = true)]
            public abstract class SharedProvider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            """,
            out _,
            [targetReference]);
        const string source = """
            using Apache.Fory;
            using VendorModels;
            [ForyStruct(Target = typeof(VendorBase), BaseOnly = true)]
            public abstract class LocalProvider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            [ForyStruct]
            public sealed class Child : VendorBase
            {
                [ForyField(2)]
                public int ChildValue;
            }
            """;

        Assert.Contains(
            "internal static class __ForyHierarchy_",
            hiddenGenerated,
            StringComparison.Ordinal);
        string hiddenName = hiddenGenerated.Split('\n')
            .Single(line => line.Contains(" static class __ForyHierarchy_", StringComparison.Ordinal))
            .Split(' ', StringSplitOptions.RemoveEmptyEntries)[3];
        CSharpCompilation inspection = CreateCompilation(
            string.Empty,
            includeExternalTypes: false,
            additionalReferences: [targetReference, hiddenProvider],
            assemblyName: "ProviderInspector");
        IAssemblySymbol hiddenAssembly =
            Assert.IsAssignableFrom<IAssemblySymbol>(
                inspection.GetAssemblyOrModuleSymbol(hiddenProvider));
        Assert.NotNull(hiddenAssembly.GetTypeByMetadataName(
            $"Apache.Fory.Generated.{hiddenName}"));
        Assert.DoesNotContain(
            GenerateDiagnostics(
                source,
                includeExternalTypes: false,
                additionalReferences: [targetReference, hiddenProvider],
                assemblyName: "ConsumerModels"),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
        Assert.Contains(
            GenerateDiagnostics(
                source,
                includeExternalTypes: false,
                additionalReferences: [targetReference, sharedProvider],
                assemblyName: "ConflictingConsumer"),
            diagnostic => diagnostic.Id == "FORY019");
    }

    [Fact]
    public void NonPublicSignaturesUseInternalProviders()
    {
        const string source = """
            using Apache.Fory;
            internal sealed class Hidden
            {
            }
            public class GenericBase<T>
            {
                public int Value;
            }
            [ForyStruct]
            public class PublicBase
            {
                [ForyField(2)]
                private Hidden? _hidden;
            }
            [ForyStruct]
            public class PublicMiddle : PublicBase
            {
                [ForyField(4)]
                public int MiddleValue;
            }
            public class ExternalTarget
            {
                internal Hidden? Value;
            }
            [ForyStruct(Target = typeof(GenericBase<Hidden>), BaseOnly = true)]
            public abstract class Provider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            [ForyStruct(Target = typeof(ExternalTarget), BaseOnly = true)]
            public abstract class ExternalProvider
            {
                [ForyField(3)]
                internal abstract Hidden? Value { get; }
            }
            """;

        string generated = GenerateSource(source);
        string[] lines = generated.Split('\n');
        int targetMarker = Array.FindIndex(
            lines,
            line => line.Contains(
                "typeof(global::GenericBase<global::Hidden>)",
                StringComparison.Ordinal));
        int ordinaryMarker = Array.FindIndex(
            lines,
            line => line.Contains(
                "typeof(global::PublicBase)",
                StringComparison.Ordinal));
        int externalMarker = Array.FindIndex(
            lines,
            line => line.Contains(
                "typeof(global::ExternalTarget)",
                StringComparison.Ordinal));
        int middleMarker = Array.FindIndex(
            lines,
            line => line.Contains(
                "typeof(global::PublicMiddle)",
                StringComparison.Ordinal));

        Assert.True(targetMarker >= 0);
        Assert.True(ordinaryMarker >= 0);
        Assert.True(externalMarker >= 0);
        Assert.True(middleMarker >= 0);
        Assert.Contains(
            lines.Skip(targetMarker).Take(4),
            line => line.StartsWith("internal static class", StringComparison.Ordinal));
        Assert.Contains(
            lines.Skip(ordinaryMarker).Take(4),
            line => line.StartsWith("internal sealed class", StringComparison.Ordinal));
        Assert.Contains(
            lines.Skip(externalMarker).Take(4),
            line => line.StartsWith("internal static class", StringComparison.Ordinal));
        Assert.Contains(
            lines.Skip(middleMarker).Take(4),
            line => line.StartsWith("internal sealed class", StringComparison.Ordinal));
    }

    [Fact]
    public void OrdinaryHierarchyRejectsInvalidShapes()
    {
        const string source = """
            using Apache.Fory;
            [ForyStruct]
            public static class StaticModel
            {
            }
            public static class Owner
            {
                private sealed class Hidden
                {
                }
                [ForyStruct]
                public sealed class Model
                {
                    [ForyField(1)]
                    private Hidden Value = new();
                }
            }
            [ForyStruct]
            public struct StructModel
            {
                [ForyField(1)]
                private int Value;
            }
            [ForyStruct(Evolving = false)]
            public abstract class AbstractModel
            {
            }
            [ForyStruct]
            public sealed class StorageModel
            {
                private struct HiddenValue
                {
                    public long Value;
                }
                private HiddenValue _storage;
            }
            [ForyStruct]
            public sealed class GenericAccessorModel
            {
                [ForyField(1)]
                private System.Collections.Generic.List<int> Values = [];
            }
            """;

        Diagnostic[] diagnostics = GenerateDiagnostics(source).ToArray();

        Assert.Contains(diagnostics, diagnostic => diagnostic.Id == "FORY023");
        Assert.Contains(diagnostics, diagnostic => diagnostic.Id == "FORY022");
        Assert.Contains(
            diagnostics,
            diagnostic => diagnostic.Id == "FORY022" &&
                          diagnostic.GetMessage().Contains(
                              "private generic UnsafeAccessor",
                              StringComparison.Ordinal));
        Assert.Contains(diagnostics, diagnostic => diagnostic.Id == "FORY021");
        Assert.Contains(diagnostics, diagnostic => diagnostic.Id == "FORY017");
        Assert.Contains(diagnostics, diagnostic => diagnostic.Id == "FORY018");
    }

    [Fact]
    public void ExternalMetadataNamesAreEscaped()
    {
        const string source = """
            using Apache.Fory;
            public sealed class Target
            {
            }
            [ForyStruct(Target = typeof(Target))]
            internal abstract class Provider
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(Target),
                    TargetMemberName = "line\n\0")]
                public abstract int Value { get; }
            }
            """;

        string generated = GenerateSource(source);

        Assert.Contains(
            "UnsafeAccessorKind.Field",
            generated,
            StringComparison.Ordinal);
    }

    [Fact]
    public void AssemblyVersionsChangeProviderNames()
    {
        MetadataReference versionOne = CreateReference(
            "VersionedModels",
            """
            using System.Reflection;
            [assembly: AssemblyVersion("1.0.0.0")]
            namespace VersionedModels;
            public class VersionedBase
            {
                public int Value;
            }
            """);
        MetadataReference versionTwo = CreateReference(
            "VersionedModels",
            """
            using System.Reflection;
            [assembly: AssemblyVersion("2.0.0.0")]
            namespace VersionedModels;
            public class VersionedBase
            {
                public int Value;
            }
            """);
        const string source = """
            using Apache.Fory;
            using VersionedModels;
            [ForyStruct(Target = typeof(VersionedBase), BaseOnly = true)]
            public abstract class Provider
            {
                [ForyField(1)]
                public abstract int Value { get; }
            }
            """;

        string generatedOne = GenerateSource(
            source,
            includeExternalTypes: false,
            additionalReferences: [versionOne],
            assemblyName: "ProviderOne");
        string generatedTwo = GenerateSource(
            source,
            includeExternalTypes: false,
            additionalReferences: [versionTwo],
            assemblyName: "ProviderTwo");
        string declarationOne = generatedOne.Split('\n').Single(line =>
            line.Contains(" static class __ForyHierarchy_", StringComparison.Ordinal));
        string declarationTwo = generatedTwo.Split('\n').Single(line =>
            line.Contains(" static class __ForyHierarchy_", StringComparison.Ordinal));

        Assert.NotEqual(declarationOne, declarationTwo);
    }

    [Fact]
    public void DuplicateHierarchyFieldIdsAreRejected()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct]
            public abstract class Base
            {
                [ForyField(1)]
                private int _baseValue;
            }
            [ForyStruct]
            public sealed class Derived : Base
            {
                [ForyField(1)]
                public int Value;
            }
            """;

        Assert.Contains(
            GenerateDiagnostics(source),
            diagnostic => diagnostic.Id == "FORY016");
    }

    public static TheoryData<string, string> ExternalDiagnosticCases => new()
    {
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                private System.Collections.Generic.List<int> _values = [];
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(InvalidTarget),
                    TargetMemberName = "_values")]
                public abstract System.Collections.Generic.List<int> Values { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal sealed class InvalidSerializer
            {
                public int Value { get; set; }
            }
            """,
            "FORY008"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public struct InvalidTarget
            {
                public int Value;
            }
            [ForyStruct(
                Target = typeof(InvalidTarget),
                BaseOnly = false)]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY008"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            public enum InvalidStatus { Ready }
            [ForyStruct(Target = typeof(InvalidTarget))]
            [ForyEnum(Target = typeof(InvalidStatus))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY023"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public enum InvalidTarget { Ready }
            [ForyEnum(Target = typeof(InvalidTarget))]
            internal static class InvalidSerializer
            {
                public const int Value = 1;
            }
            """,
            "FORY008"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct(Target = typeof(InvalidSerializer))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget<T> { public T Value { get; set; } = default!; }
            [ForyStruct(Target = typeof(InvalidTarget<>))]
            internal abstract class InvalidSerializer
            {
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct(Target = typeof(object))]
            internal abstract class InvalidSerializer
            {
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public enum InvalidTarget { Ready }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public static class Owner
            {
                private sealed class InvalidTarget { public int Value { get; set; } }
            }
            [ForyStruct(Target = typeof(Owner.InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct]
            public sealed class InvalidTarget { public int Value { get; set; } }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY009"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class OtherSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY010"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public InvalidTarget(int value) { Value = value; }
                public int Value { get; set; }
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY002"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public required int Value { get; set; }
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY002"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public static int Value { get; set; }
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public class InvalidTarget { public int Value; }
            [ForyStruct(
                Target = typeof(InvalidTarget),
                BaseOnly = true,
                Evolving = false)]
            public abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY008"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value; }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(InvalidTarget),
                    TargetMemberName = nameof(InvalidTarget.Value))]
                public abstract int First { get; }
                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(InvalidTarget),
                    TargetMemberName = nameof(InvalidTarget.Value))]
                public abstract int Second { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget<T>
            {
                private T _value = default!;
            }
            [ForyStruct(Target = typeof(InvalidTarget<int>))]
            internal abstract class InvalidSerializer
            {
                [ForyField(
                    1,
                    TargetDeclaringType = typeof(InvalidTarget<int>),
                    TargetMemberName = "_value")]
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public int Value { get; } = 1;
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public int Value { get; init; }
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public readonly int Value;
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract long Value { get; }
            }
            """,
            "FORY012"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public object Value { get; set; } = new(); }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract dynamic Value { get; }
            }
            """,
            "FORY012"
        },
        {
            """
            #nullable enable
            using System.Collections.Generic;
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget
            {
                public List<string?> Values { get; set; } = [];
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract List<string> Values { get; }
            }
            """,
            "FORY012"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(Type = typeof(string))]
                public abstract int Value { get; }
            }
            """,
            "FORY003"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public enum InvalidTarget : long { Invalid = -1 }
            [ForyEnum(Target = typeof(InvalidTarget))]
            internal static class InvalidSerializer
            {
            }
            """,
            "FORY014"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct]
            public sealed class InvalidTarget
            {
                [ForyField(Ignore = true)]
                public int Value { get; set; }
            }
            """,
            "FORY015"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value; }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(1, Ignore = true)]
                public abstract int Value { get; }
            }
            """,
            "FORY015"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value; }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(TargetMemberName = nameof(InvalidTarget.Value))]
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public struct InvalidTarget
            {
                private int _value;
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(
                    TargetDeclaringType = typeof(InvalidTarget),
                    TargetMemberName = "_value")]
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public unsafe class InvalidTarget
            {
                private int* _value;
            }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract unsafe class InvalidSerializer
            {
                [ForyField(
                    Ignore = true,
                    TargetDeclaringType = typeof(InvalidTarget),
                    TargetMemberName = "_value")]
                public abstract int* Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value; }
            [ForyStruct(Target = typeof(InvalidTarget))]
            internal abstract class InvalidSerializer
            {
                [ForyField(
                    TargetDeclaringType = typeof(object),
                    TargetMemberName = nameof(InvalidTarget.Value))]
                public abstract int Value { get; }
            }
            """,
            "FORY011"
        },
        {
            """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class InvalidTarget { public int Value { get; set; } }
            public sealed class Outer<T>
            {
                [ForyStruct(Target = typeof(InvalidTarget))]
                internal abstract class InvalidSerializer
                {
                    public abstract int Value { get; }
                }
            }
            """,
            "FORY001"
        },
    };

    [Theory]
    [MemberData(nameof(ExternalDiagnosticCases))]
    public void ExternalDiagnosticsAreReported(
        string source,
        string expectedDiagnostic)
    {
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out ImmutableArray<Diagnostic> diagnostics);

        IEnumerable<Diagnostic> allDiagnostics =
            driver.GetRunResult().Diagnostics.Concat(diagnostics);
        Assert.Contains(allDiagnostics, diagnostic => diagnostic.Id == expectedDiagnostic);
    }

    [Fact]
    public void AliasOnlyTargetIsRejected()
    {
        const string source = """
            extern alias thirdparty;
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyStruct(Target = typeof(thirdparty::Fory.ExternalTypes.ExternalUser))]
            internal abstract class InvalidSerializer
            {
                public abstract int Id { get; }
                public abstract string Name { get; }
                public abstract thirdparty::Fory.ExternalTypes.ExternalUser? Friend { get; }
                public abstract System.Collections.Generic.List<
                    thirdparty::Fory.ExternalTypes.ExternalUser> Links { get; }
            }
            """;
        MetadataReference aliasReference = MetadataReference.CreateFromFile(
            typeof(global::Fory.ExternalTypes.ExternalUser).Assembly.Location,
            new MetadataReferenceProperties(
                aliases: ImmutableArray.Create("thirdparty")));
        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes: false,
            additionalReferences: [aliasReference]);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out ImmutableArray<Diagnostic> diagnostics);

        Assert.Contains(
            driver.GetRunResult().Diagnostics.Concat(diagnostics),
            diagnostic => diagnostic.Id == "FORY013");
    }

    [Fact]
    public void AliasOnlyDeclaringTypeIsRejected()
    {
        const string source = """
            extern alias thirdparty;
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            public sealed class LocalTarget :
                thirdparty::Fory.ExternalTypes.ExternalBase
            {
            }
            [ForyStruct(Target = typeof(LocalTarget))]
            internal abstract class InvalidSerializer
            {
                public abstract string BaseName { get; }
            }
            """;
        MetadataReference aliasReference = MetadataReference.CreateFromFile(
            typeof(global::Fory.ExternalTypes.ExternalUser).Assembly.Location,
            new MetadataReferenceProperties(
                aliases: ImmutableArray.Create("thirdparty")));

        Assert.Contains(
            GenerateDiagnostics(
                source,
                includeExternalTypes: false,
                additionalReferences: [aliasReference]),
            diagnostic => diagnostic.Id == "FORY013");
    }

    [Fact]
    public void DynamicAndObjectTargetsAreDuplicate()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalBox<dynamic>))]
            internal abstract class DynamicBoxSerializer
            {
            }

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalBox<object>))]
            internal abstract class ObjectBoxSerializer
            {
            }
            """;
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out _);

        Assert.Equal(
            2,
            driver.GetRunResult().Diagnostics
                .Count(diagnostic => diagnostic.Id == "FORY010"));
    }

    [Fact]
    public void TupleNamesShareTargetIdentity()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            [ForyStruct(Target = typeof(
                Fory.ExternalTypes.ExternalBox<(int Left, string Name)>))]
            internal abstract class NamedTupleBoxSerializer
            {
            }

            [ForyStruct(Target = typeof(
                Fory.ExternalTypes.ExternalBox<(int X, string Y)>))]
            internal abstract class OtherTupleBoxSerializer
            {
            }
            """;
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out _);

        Assert.Equal(
            2,
            driver.GetRunResult().Diagnostics
                .Count(diagnostic => diagnostic.Id == "FORY010"));
    }

    [Fact]
    public void NativeIntsShareTargetIdentity()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            [ForyStruct(Target = typeof(Fory.ExternalTypes.ExternalBox<nint>))]
            internal abstract class NativeIntBoxSerializer
            {
            }

            [ForyStruct(Target = typeof(
                Fory.ExternalTypes.ExternalBox<System.IntPtr>))]
            internal abstract class IntPtrBoxSerializer
            {
            }
            """;
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out _);

        Assert.Equal(
            2,
            driver.GetRunResult().Diagnostics
                .Count(diagnostic => diagnostic.Id == "FORY010"));
    }

    [Fact]
    public void ArrayElementsShareTargetIdentity()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;

            [ForyStruct(Target = typeof(
                Fory.ExternalTypes.ExternalBox<dynamic[]>))]
            internal abstract class DynamicArrayBoxSerializer
            {
            }

            [ForyStruct(Target = typeof(
                Fory.ExternalTypes.ExternalBox<object[]>))]
            internal abstract class ObjectArrayBoxSerializer
            {
            }
            """;
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out _);

        Assert.Equal(
            2,
            driver.GetRunResult().Diagnostics
                .Count(diagnostic => diagnostic.Id == "FORY010"));
    }

    [Fact]
    public void InternalTargetWithIvtCompiles()
    {
        const string targetSource = """
            using System.Runtime.CompilerServices;
            [assembly: InternalsVisibleTo("ForyGeneratorDiagnostics")]
            namespace ThirdParty;
            internal sealed class InternalTarget
            {
                internal int Value { get; set; }
            }
            """;
        MetadataReference targetReference = CreateReference(
            "ThirdPartyModels",
            targetSource);
        const string source = """
            using Apache.Fory;
            using ThirdParty;
            namespace GeneratedDiagnostics;
            [ForyStruct(Target = typeof(InternalTarget))]
            internal abstract class InternalTargetSerializer
            {
                public abstract int Value { get; }
            }
            """;

        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes: false,
            additionalReferences: [targetReference]);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out Compilation output,
            out ImmutableArray<Diagnostic> diagnostics);

        Assert.DoesNotContain(
            diagnostics.Concat(output.GetDiagnostics()),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
    }

    [Fact]
    public void ObliviousTargetCompiles()
    {
        const string targetSource = """
            #nullable disable
            namespace ThirdParty;
            public sealed class ObliviousTarget
            {
                public string Value { get; set; }
            }
            """;
        MetadataReference targetReference = CreateReference(
            "ObliviousModels",
            targetSource);
        const string source = """
            #nullable enable
            using Apache.Fory;
            using ThirdParty;
            namespace GeneratedDiagnostics;
            [ForyStruct(Target = typeof(ObliviousTarget))]
            internal abstract class ObliviousSerializer
            {
                public abstract string? Value { get; }
            }
            """;

        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes: false,
            additionalReferences: [targetReference]);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out Compilation output,
            out ImmutableArray<Diagnostic> diagnostics);

        Assert.DoesNotContain(
            diagnostics.Concat(output.GetDiagnostics()),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
        Assert.Contains(
            driver.GetRunResult().Results.SelectMany(result => result.GeneratedSources)
                .Select(sourceResult => sourceResult.SourceText.ToString()),
            generated => generated.Contains("value.Value", StringComparison.Ordinal));
    }

    [Fact]
    public void OrdinaryEnumRangeIsValidated()
    {
        const string source = """
            using Apache.Fory;
            namespace GeneratedDiagnostics;
            [ForyEnum]
            public enum InvalidStatus : ulong
            {
                TooLarge = 4294967296UL,
            }
            """;
        CSharpCompilation compilation = CreateCompilation(source);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out _,
            out ImmutableArray<Diagnostic> diagnostics);

        Assert.Contains(
            driver.GetRunResult().Diagnostics.Concat(diagnostics),
            diagnostic => diagnostic.Id == "FORY014");
    }

    private static string GenerateSource(
        string source,
        bool includeExternalTypes = true,
        IEnumerable<MetadataReference>? additionalReferences = null,
        string assemblyName = "ForyGeneratorDiagnostics")
    {
        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes,
            additionalReferences,
            assemblyName);
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

    private static IEnumerable<Diagnostic> GenerateDiagnostics(
        string source,
        bool includeExternalTypes = true,
        IEnumerable<MetadataReference>? additionalReferences = null,
        string assemblyName = "ForyGeneratorDiagnostics")
    {
        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes,
            additionalReferences,
            assemblyName);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(
            new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out Compilation output,
            out ImmutableArray<Diagnostic> diagnostics);
        return driver.GetRunResult().Diagnostics
            .Concat(diagnostics)
            .Concat(output.GetDiagnostics());
    }

    private static CSharpCompilation CreateCompilation(
        string source,
        bool includeExternalTypes = true,
        IEnumerable<MetadataReference>? additionalReferences = null,
        string assemblyName = "ForyGeneratorDiagnostics")
    {
        MetadataReference foryReference =
            MetadataReference.CreateFromFile(typeof(ForyStructAttribute).Assembly.Location);
        IEnumerable<MetadataReference> references =
            PlatformReferences().Append(foryReference);
        if (includeExternalTypes)
        {
            references = references.Append(
                MetadataReference.CreateFromFile(
                    typeof(global::Fory.ExternalTypes.ExternalUser).Assembly.Location));
        }

        if (additionalReferences is not null)
        {
            references = references.Concat(additionalReferences);
        }

        return CSharpCompilation.Create(
            assemblyName,
            [CSharpSyntaxTree.ParseText(source, new CSharpParseOptions(LanguageVersion.CSharp12))],
            references,
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
    }

    private static MetadataReference CreateGeneratedReference(
        string assemblyName,
        string source,
        out string generated,
        IEnumerable<MetadataReference>? additionalReferences = null,
        bool includePrivateMembers = false)
    {
        CSharpCompilation compilation = CreateCompilation(
            source,
            includeExternalTypes: false,
            additionalReferences: additionalReferences,
            assemblyName: assemblyName);
        GeneratorDriver driver = CSharpGeneratorDriver.Create(
            new ForyModelGenerator());
        driver = driver.RunGeneratorsAndUpdateCompilation(
            compilation,
            out Compilation output,
            out ImmutableArray<Diagnostic> diagnostics);
        Assert.DoesNotContain(
            diagnostics.Concat(output.GetDiagnostics()),
            diagnostic => diagnostic.Severity == DiagnosticSeverity.Error);
        generated = string.Join(
            "\n",
            driver.GetRunResult().Results
                .SelectMany(result => result.GeneratedSources)
                .Select(result => result.SourceText.ToString()));

        using MemoryStream stream = new();
        EmitResult emit = output.Emit(
            stream,
            options: new EmitOptions(
                metadataOnly: true,
                includePrivateMembers: includePrivateMembers));
        Assert.True(
            emit.Success,
            string.Join(Environment.NewLine, emit.Diagnostics));
        return MetadataReference.CreateFromImage(stream.ToArray());
    }

    private static MetadataReference CreateReference(
        string assemblyName,
        string source,
        IEnumerable<MetadataReference>? additionalReferences = null)
    {
        IEnumerable<MetadataReference> references = PlatformReferences();
        if (additionalReferences is not null)
        {
            references = references.Concat(additionalReferences);
        }

        CSharpCompilation compilation = CSharpCompilation.Create(
            assemblyName,
            [CSharpSyntaxTree.ParseText(source, new CSharpParseOptions(LanguageVersion.CSharp12))],
            references,
            new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        using MemoryStream stream = new();
        EmitResult result = compilation.Emit(stream);
        Assert.True(
            result.Success,
            string.Join(Environment.NewLine, result.Diagnostics));
        return MetadataReference.CreateFromImage(stream.ToArray());
    }

    private static IEnumerable<MetadataReference> PlatformReferences()
    {
        return ((string)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES")!)
            .Split(Path.PathSeparator)
            .Select(path => MetadataReference.CreateFromFile(path));
    }
}
