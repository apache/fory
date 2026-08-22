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
using System.Text;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;

namespace Apache.Fory.Generator;

[Generator(LanguageNames.CSharp)]
public sealed partial class ForyModelGenerator : IIncrementalGenerator
{
    private const uint UInt8ArrayTypeId = 48;

    private static readonly SymbolDisplayFormat FullNameFormat =
        SymbolDisplayFormat.FullyQualifiedFormat.WithMiscellaneousOptions(
            SymbolDisplayMiscellaneousOptions.IncludeNullableReferenceTypeModifier);

    private static readonly DiagnosticDescriptor GenericTypeNotSupported = new(
        id: "FORY001",
        title: "Generic types are not supported by the Fory source generator",
        messageFormat: "Type '{0}' is generic and is not supported by generated Fory attributes",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor MissingCtor = new(
        id: "FORY002",
        title: "Unsupported parameterless construction",
        messageFormat: "Class '{0}' must support legal accessible parameterless construction for [ForyStruct]",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor UnsupportedSchemaType = new(
        id: "FORY003",
        title: "Unsupported Fory field schema type",
        messageFormat: "Member '{0}' uses unsupported [ForyField] schema descriptor for type '{1}'",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidFieldId = new(
        id: "FORY004",
        title: "Invalid Fory field id",
        messageFormat: "Member '{0}' uses an invalid [ForyField] id; field ids must be non-negative and no greater than short.MaxValue",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidUnionType = new(
        id: "FORY005",
        title: "Invalid Fory union type",
        messageFormat: "Class '{0}' must declare nested [ForyUnknownCase] and [ForyCase] case types for [ForyUnion]",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidUnionCase = new(
        id: "FORY006",
        title: "Invalid Fory union case",
        messageFormat: "Union case '{0}' is invalid: {1}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor DuplicateUnionCaseId = new(
        id: "FORY007",
        title: "Duplicate Fory union case id",
        messageFormat: "Union case id {0} is declared more than once in '{1}'",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidExternalDeclaration = new(
        id: "FORY008",
        title: "Invalid external serializer declaration",
        messageFormat: "Serializer declaration '{0}' is invalid: {1}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidExternalTarget = new(
        id: "FORY009",
        title: "Invalid external serializer target",
        messageFormat: "External serializer target '{0}' is invalid: {1}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor DuplicateGeneratedTarget = new(
        id: "FORY010",
        title: "Duplicate generated serializer target",
        messageFormat: "Runtime target '{0}' has multiple generated serializer declarations",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidExternalMember = new(
        id: "FORY011",
        title: "Invalid external serializer member",
        messageFormat: "Schema member '{0}' cannot bind target '{1}': {2}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor ExternalMemberTypeMismatch = new(
        id: "FORY012",
        title: "External serializer member type mismatch",
        messageFormat: "Schema member '{0}' type '{1}' does not match target member type '{2}'",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor UnsupportedExternAlias = new(
        id: "FORY013",
        title: "Unsupported extern alias",
        messageFormat: "Type '{0}' requires an extern alias that generated code cannot preserve",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor EnumValueOutOfRange = new(
        id: "FORY014",
        title: "Enum value is outside the supported range",
        messageFormat: "Enum member '{0}' has a value outside the supported unsigned 32-bit range",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidIgnoredField = new(
        id: "FORY015",
        title: "Invalid ignored Fory field",
        messageFormat: "Member '{0}' uses invalid [ForyField(Ignore = true)]: {1}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor DuplicateStructuralField = new(
        id: "FORY016",
        title: "Duplicate structural field identity",
        messageFormat: "Target '{0}' has duplicate structural field identity '{1}' on '{2}' and '{3}'",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidAbstractStructOption = new(
        id: "FORY017",
        title: "Invalid abstract structural option",
        messageFormat: "Abstract structural base '{0}' cannot explicitly set '{1}'",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor UnsupportedShallowField = new(
        id: "FORY018",
        title: "Unsupported shallow storage field",
        messageFormat: "Class '{0}' has physical field '{1}' whose value type '{2}' cannot be named by generated code",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor MissingHierarchyProvider = new(
        id: "FORY019",
        title: "Missing generated hierarchy provider",
        messageFormat: "Class '{0}' cannot use base class '{1}': {2}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidInheritedDescriptor = new(
        id: "FORY020",
        title: "Invalid inherited wire descriptor",
        messageFormat: "Class '{0}' cannot consume generated hierarchy provider '{1}': {2}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor UnsupportedGeneratedMemberType = new(
        id: "FORY021",
        title: "Unsupported generated member type",
        messageFormat: "Member '{0}' on '{1}' uses type '{2}' that generated code cannot name",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidOrdinaryMember = new(
        id: "FORY022",
        title: "Invalid ordinary structural member",
        messageFormat: "Member '{0}' on ordinary structural type '{1}' is invalid: {2}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    private static readonly DiagnosticDescriptor InvalidGeneratedDeclaration = new(
        id: "FORY023",
        title: "Invalid generated declaration",
        messageFormat: "Generated declaration '{0}' is invalid: {1}",
        category: "Fory",
        defaultSeverity: DiagnosticSeverity.Error,
        isEnabledByDefault: true);

    public void Initialize(IncrementalGeneratorInitializationContext context)
    {
        IncrementalValuesProvider<TypeModel?> typeModels = context.SyntaxProvider
            .CreateSyntaxProvider(
                static (node, _) => HasCandidateAttributes(node),
                static (syntaxContext, ct) => BuildTypeModel(syntaxContext, ct))
            .Where(static m => m is not null);

        context.RegisterSourceOutput(
            typeModels.Collect().Combine(context.CompilationProvider),
            static (spc, input) => Emit(spc, input.Left, input.Right));
    }

    private static bool HasCandidateAttributes(SyntaxNode node)
    {
        return node switch
        {
            TypeDeclarationSyntax typeDeclaration => typeDeclaration.AttributeLists.Count > 0,
            EnumDeclarationSyntax enumDeclaration => enumDeclaration.AttributeLists.Count > 0,
            _ => false,
        };
    }

    private static void Emit(
        SourceProductionContext context,
        ImmutableArray<TypeModel?> maybeModels,
        Compilation compilation)
    {
        if (maybeModels.IsDefaultOrEmpty)
        {
            return;
        }

        Dictionary<string, TypeModel> declarations = new(StringComparer.Ordinal);
        foreach (TypeModel? maybeModel in maybeModels)
        {
            if (maybeModel is null)
            {
                continue;
            }

            if (!declarations.ContainsKey(maybeModel.DeclarationName))
            {
                declarations.Add(maybeModel.DeclarationName, maybeModel);
            }
        }

        List<TypeModel> validModels = [];
        foreach (TypeModel model in declarations.Values)
        {
            if (!model.Diagnostics.IsDefaultOrEmpty)
            {
                foreach (Diagnostic diagnostic in model.Diagnostics)
                {
                    context.ReportDiagnostic(diagnostic);
                }

                continue;
            }

            validModels.Add(model);
        }

        IEqualityComparer<ITypeSymbol> targetTypeComparer = RuntimeTypeComparer.Instance;
        Dictionary<ITypeSymbol, TypeModel> models = new(targetTypeComparer);
        foreach (IGrouping<ITypeSymbol, TypeModel> targetGroup in validModels.GroupBy(
                     model => model.TargetType,
                     targetTypeComparer))
        {
            if (targetGroup.Skip(1).Any())
            {
                foreach (TypeModel model in targetGroup)
                {
                    context.ReportDiagnostic(Diagnostic.Create(
                        DuplicateGeneratedTarget,
                        model.DeclarationLocation,
                        model.TargetTypeName));
                }

                continue;
            }

            TypeModel targetModel = targetGroup.First();
            models.Add(targetModel.TargetType, targetModel);
        }

        foreach (IGrouping<string, TypeModel> serializerGroup in models.Values
                     .GroupBy(model => model.GeneratedClassName, StringComparer.Ordinal)
                     .Where(group => group.Skip(1).Any())
                     .ToArray())
        {
            string targets = string.Join(
                ", ",
                serializerGroup
                    .Select(model => model.TargetTypeName)
                    .OrderBy(name => name, StringComparer.Ordinal));
            foreach (TypeModel model in serializerGroup)
            {
                context.ReportDiagnostic(Diagnostic.Create(
                    DuplicateGeneratedTarget,
                    model.DeclarationLocation,
                    targets));
                models.Remove(model.TargetType);
            }
        }

        if (models.Count == 0)
        {
            return;
        }

        ImmutableArray<TypeModel> emittedModels = ComposeHierarchyModels(
            context,
            compilation,
            models.Values.ToImmutableArray());
        if (emittedModels.IsEmpty)
        {
            return;
        }

        ImmutableArray<TypeModel> orderedModels = emittedModels
            .OrderBy(model => model.TargetTypeName, StringComparer.Ordinal)
            .ThenBy(model => model.DeclarationName, StringComparer.Ordinal)
            .ToImmutableArray();
        bool emitGraphElementBytes = orderedModels.Any(model =>
            !model.ProviderOnly &&
            model.Kind is DeclKind.Class or DeclKind.Struct or DeclKind.Union);
        bool emitRegistration = orderedModels.Any(model => !model.ProviderOnly);
        StringBuilder sb = new();
        sb.AppendLine("// <auto-generated/>");
        sb.AppendLine("#nullable enable");
        sb.AppendLine("namespace Apache.Fory.Generated;");
        sb.AppendLine();
        if (emitGraphElementBytes)
        {
            sb.AppendLine("file static class __ForyGraphElementBytes<T>");
            sb.AppendLine("{");
            sb.AppendLine("    internal static readonly int Bytes = typeof(T).IsValueType ? global::System.Runtime.CompilerServices.Unsafe.SizeOf<T>() : 4;");
            sb.AppendLine("}");
            sb.AppendLine();
        }

        foreach (TypeModel model in orderedModels)
        {
            if (model.Kind == DeclKind.Struct || model.Kind == DeclKind.Class)
            {
                EmitObjectSerializer(sb, model);
                sb.AppendLine();
            }
            else if (model.Kind == DeclKind.Union)
            {
                EmitUnionSerializer(sb, model);
                sb.AppendLine();
            }
        }

        if (emitRegistration)
        {
            sb.AppendLine("internal static class __ForyGeneratedModuleInitializer");
            sb.AppendLine("{");
            sb.AppendLine("    [global::System.Runtime.CompilerServices.ModuleInitializer]");
            sb.AppendLine("    internal static void Register()");
            sb.AppendLine("    {");
            foreach (TypeModel model in orderedModels)
            {
                if (model.ProviderOnly)
                {
                    continue;
                }

                if (model.Kind == DeclKind.Enum)
                {
                    sb.AppendLine(
                        $"        global::Apache.Fory.TypeResolver.RegisterGenerated<{model.TargetTypeName}, global::Apache.Fory.EnumSerializer<{model.TargetTypeName}>>();");
                }
                else if (model.Kind == DeclKind.Union)
                {
                    sb.AppendLine(
                        $"        global::Apache.Fory.TypeResolver.RegisterGenerated<{model.TargetTypeName}, {model.GeneratedClassName}>();");
                }
                else
                {
                    sb.AppendLine(
                        $"        global::Apache.Fory.TypeResolver.RegisterGeneratedStruct<{model.TargetTypeName}, {model.GeneratedClassName}>({BoolLiteral(model.Evolving)}, {BoolLiteral(ReadDataAlwaysAdvances(model))});");
                }
            }

            sb.AppendLine("    }");
            sb.AppendLine("}");
        }

        context.AddSource("Fory.GeneratedSerializers.g.cs", SourceText.From(sb.ToString(), Encoding.UTF8));
    }
}
