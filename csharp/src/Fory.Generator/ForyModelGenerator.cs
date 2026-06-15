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
using System.Globalization;
using System.Text;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;

namespace Apache.Fory.Generator;

[Generator(LanguageNames.CSharp)]
public sealed class ForyModelGenerator : IIncrementalGenerator
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
        title: "Missing parameterless constructor",
        messageFormat: "Class '{0}' must declare an accessible parameterless constructor for [ForyStruct]",
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

    public void Initialize(IncrementalGeneratorInitializationContext context)
    {
        IncrementalValuesProvider<TypeModel?> typeModels = context.SyntaxProvider
            .CreateSyntaxProvider(
                static (node, _) => HasCandidateAttributes(node),
                static (syntaxContext, ct) => BuildTypeModel(syntaxContext, ct))
            .Where(static m => m is not null);

        context.RegisterSourceOutput(
            typeModels.Collect(),
            static (spc, models) => Emit(spc, models));
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

    private static void Emit(SourceProductionContext context, ImmutableArray<TypeModel?> maybeModels)
    {
        if (maybeModels.IsDefaultOrEmpty)
        {
            return;
        }

        Dictionary<string, TypeModel> models = new(StringComparer.Ordinal);
        foreach (TypeModel? maybeModel in maybeModels)
        {
            if (maybeModel is null)
            {
                continue;
            }

            if (!maybeModel.Diagnostics.IsDefaultOrEmpty)
            {
                foreach (Diagnostic diagnostic in maybeModel.Diagnostics)
                {
                    context.ReportDiagnostic(diagnostic);
                }

                continue;
            }

            models[maybeModel.TypeName] = maybeModel;
        }

        if (models.Count == 0)
        {
            return;
        }

        StringBuilder sb = new();
        sb.AppendLine("// <auto-generated/>");
        sb.AppendLine("#nullable enable");
        sb.AppendLine("namespace Apache.Fory.Generated;");
        sb.AppendLine();

        foreach (KeyValuePair<string, TypeModel> entry in models.OrderBy(kv => kv.Key, StringComparer.Ordinal))
        {
            TypeModel model = entry.Value;
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

        sb.AppendLine("internal static class __ForyGeneratedModuleInitializer");
        sb.AppendLine("{");
        sb.AppendLine("    [global::System.Runtime.CompilerServices.ModuleInitializer]");
        sb.AppendLine("    internal static void Register()");
        sb.AppendLine("    {");
        foreach (KeyValuePair<string, TypeModel> entry in models.OrderBy(kv => kv.Key, StringComparer.Ordinal))
        {
            TypeModel model = entry.Value;
            if (model.Kind == DeclKind.Enum)
            {
                sb.AppendLine(
                    $"        global::Apache.Fory.TypeResolver.RegisterGenerated<{model.TypeName}, global::Apache.Fory.EnumSerializer<{model.TypeName}>>();");
            }
            else if (model.Kind == DeclKind.Union)
            {
                sb.AppendLine(
                    $"        global::Apache.Fory.TypeResolver.RegisterGenerated<{model.TypeName}, {model.SerializerName}>();");
            }
            else
            {
                sb.AppendLine(
                    $"        global::Apache.Fory.TypeResolver.RegisterGenerated<{model.TypeName}, {model.SerializerName}>();");
            }
        }

        sb.AppendLine("    }");
        sb.AppendLine("}");

        context.AddSource("Fory.GeneratedSerializers.g.cs", SourceText.From(sb.ToString(), Encoding.UTF8));
    }

    private static void EmitObjectSerializer(StringBuilder sb, TypeModel model)
    {
        sb.AppendLine(
            $"file sealed class {model.SerializerName} : global::Apache.Fory.Serializer<{model.TypeName}>");
        sb.AppendLine("{");
        sb.AppendLine("    private static readonly object __ForyTypeMetaCacheLock = new();");
        sb.AppendLine("    private static ulong __ForyTypeMetaResolverVersion;");
        sb.AppendLine("    private static ulong __ForyTypeMetaHeaderHashNoTrackRef;");
        sb.AppendLine("    private static ulong __ForyTypeMetaHeaderHashTrackRef;");
        sb.AppendLine("    private static global::Apache.Fory.TypeMeta? __ForyLastTypeMetaNoTrackRef;");
        sb.AppendLine("    private static bool __ForyLastTypeMetaMatchedNoTrackRef;");
        sb.AppendLine("    private static global::Apache.Fory.TypeMeta? __ForyLastTypeMetaTrackRef;");
        sb.AppendLine("    private static bool __ForyLastTypeMetaMatchedTrackRef;");
        sb.AppendLine(
            $"    private const bool __ForyAllFieldsBuiltIn = {BoolLiteral(model.SortedMembers.All(m => m.DynamicAnyKind == DynamicAnyKind.None && m.Classification.IsBuiltIn))};");
        sb.AppendLine(
            "    private static global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo>? __ForyTypeMetaFieldsNoTrackRef;");
        sb.AppendLine(
            "    private static global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo>? __ForyTypeMetaFieldsTrackRef;");

        if (model.SortedMembers.Length > 0)
        {
            sb.AppendLine();
        }

        sb.AppendLine("    private static global::Apache.Fory.RefMode __ForyRefMode(bool nullable, bool trackRef)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine("            return global::Apache.Fory.RefMode.Tracking;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        return nullable ? global::Apache.Fory.RefMode.NullOnly : global::Apache.Fory.RefMode.None;");
        sb.AppendLine("    }");
        sb.AppendLine();
        foreach (MemberModel member in model.SortedMembers)
        {
            if (member.FieldCodec is not null)
            {
                EmitFieldCodecMethods(sb, member);
            }
        }

        EmitCompatibleFieldCodecMethods(sb, model);

        sb.AppendLine(
            "    private static global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo> __ForyBuildTypeMetaFields(bool trackRef)");
        sb.AppendLine("    {");
        if (model.SortedMembers.Length == 0)
        {
            sb.AppendLine("        return global::System.Array.Empty<global::Apache.Fory.TypeMetaFieldInfo>();");
        }
        else
        {
            sb.AppendLine("        return new global::Apache.Fory.TypeMetaFieldInfo[]");
            sb.AppendLine("        {");
            foreach (MemberModel member in model.SortedMembers)
            {
                sb.AppendLine(
                    $"            new global::Apache.Fory.TypeMetaFieldInfo({BuildTypeMetaFieldIdExpression(member.FieldId)}, \"{EscapeString(member.FieldIdentifier)}\", {BuildTypeMetaExpression(member.TypeMeta, "trackRef")}),");
            }

            sb.AppendLine("        };");
        }

        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine(
            "    private bool __ForyMatchesTypeMeta(global::Apache.Fory.TypeMeta typeMeta, bool trackRef)");
        sb.AppendLine("    {");
        sb.AppendLine(
            "        global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo> expectedFields = TypeMetaFields(trackRef);");
        sb.AppendLine("        if (typeMeta.Fields.Count != expectedFields.Count)");
        sb.AppendLine("        {");
        sb.AppendLine("            return false;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        for (int i = 0; i < expectedFields.Count; i++)");
        sb.AppendLine("        {");
        sb.AppendLine(
            "            global::Apache.Fory.TypeMetaFieldInfo remoteField = typeMeta.Fields[i];");
        sb.AppendLine(
            "            global::Apache.Fory.TypeMetaFieldInfo localField = expectedFields[i];");
        sb.AppendLine("            if (remoteField.FieldId.HasValue && localField.FieldId.HasValue)");
        sb.AppendLine("            {");
        sb.AppendLine(
            "                if (remoteField.FieldId.Value != localField.FieldId.Value || !remoteField.FieldType.Equals(localField.FieldType))");
        sb.AppendLine("                {");
        sb.AppendLine("                    return false;");
        sb.AppendLine("                }");
        sb.AppendLine();
        sb.AppendLine("                continue;");
        sb.AppendLine("            }");
        sb.AppendLine(
            "            if (remoteField.FieldName != localField.FieldName || !remoteField.FieldType.Equals(localField.FieldType))");
        sb.AppendLine("            {");
        sb.AppendLine("                return false;");
        sb.AppendLine("            }");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        return true;");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine(
            "    private static void __ForyEnsureTypeMetaCache(global::Apache.Fory.TypeResolver typeResolver)");
        sb.AppendLine("    {");
        sb.AppendLine("        ulong resolverVersion = typeResolver.VersionHash();");
        sb.AppendLine("        if (__ForyTypeMetaResolverVersion == resolverVersion)");
        sb.AppendLine("        {");
        sb.AppendLine("            return;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        lock (__ForyTypeMetaCacheLock)");
        sb.AppendLine("        {");
        sb.AppendLine("            if (__ForyTypeMetaResolverVersion == resolverVersion)");
        sb.AppendLine("            {");
        sb.AppendLine("                return;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine(
            $"            global::Apache.Fory.TypeInfo typeInfo = typeResolver.GetTypeInfo<{model.TypeName}>();");
        sb.AppendLine(
            "            __ForyTypeMetaHeaderHashNoTrackRef = typeInfo.GetTypeMetaHeaderHash(false);");
        sb.AppendLine(
            "            __ForyTypeMetaHeaderHashTrackRef = typeInfo.GetTypeMetaHeaderHash(true);");
        sb.AppendLine("            __ForyLastTypeMetaNoTrackRef = null;");
        sb.AppendLine("            __ForyLastTypeMetaMatchedNoTrackRef = false;");
        sb.AppendLine("            __ForyLastTypeMetaTrackRef = null;");
        sb.AppendLine("            __ForyLastTypeMetaMatchedTrackRef = false;");
        sb.AppendLine("            __ForyTypeMetaResolverVersion = resolverVersion;");
        sb.AppendLine("        }");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine(
            "    private bool __ForyMatchesCachedTypeMeta(global::Apache.Fory.TypeMeta typeMeta, bool trackRef, global::Apache.Fory.TypeResolver typeResolver)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine(
            "            if (global::System.Object.ReferenceEquals(__ForyLastTypeMetaTrackRef, typeMeta))");
        sb.AppendLine("            {");
        sb.AppendLine("                return __ForyLastTypeMetaMatchedTrackRef;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            __ForyEnsureTypeMetaCache(typeResolver);");
        sb.AppendLine();
        sb.AppendLine("            bool matched = false;");
        sb.AppendLine("            if (typeMeta.HeaderHash == __ForyTypeMetaHeaderHashTrackRef)");
        sb.AppendLine("            {");
        sb.AppendLine("                matched = __ForyMatchesTypeMeta(typeMeta, true);");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            __ForyLastTypeMetaTrackRef = typeMeta;");
        sb.AppendLine("            __ForyLastTypeMetaMatchedTrackRef = matched;");
        sb.AppendLine("            return matched;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine(
            "        if (global::System.Object.ReferenceEquals(__ForyLastTypeMetaNoTrackRef, typeMeta))");
        sb.AppendLine("        {");
        sb.AppendLine("            return __ForyLastTypeMetaMatchedNoTrackRef;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        __ForyEnsureTypeMetaCache(typeResolver);");
        sb.AppendLine();
        sb.AppendLine("        bool noTrackMatched = false;");
        sb.AppendLine("        if (typeMeta.HeaderHash == __ForyTypeMetaHeaderHashNoTrackRef)");
        sb.AppendLine("        {");
        sb.AppendLine("            noTrackMatched = __ForyMatchesTypeMeta(typeMeta, false);");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        __ForyLastTypeMetaNoTrackRef = typeMeta;");
        sb.AppendLine("        __ForyLastTypeMetaMatchedNoTrackRef = noTrackMatched;");
        sb.AppendLine("        return noTrackMatched;");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine("    private static uint? __ForySchemaHashNoTrackRef;");
        sb.AppendLine();
        sb.AppendLine("    private static uint __ForySchemaHash(bool trackRef, global::Apache.Fory.TypeResolver typeResolver)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (!trackRef && __ForySchemaHashNoTrackRef.HasValue)");
        sb.AppendLine("        {");
        sb.AppendLine("            return __ForySchemaHashNoTrackRef.Value;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.Append("        uint value = global::Apache.Fory.SchemaHash.StructHash32(");
        sb.Append(BuildSchemaFingerprintExpression(model.Members));
        sb.AppendLine(");");
        sb.AppendLine("        if (!trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine("            __ForySchemaHashNoTrackRef = value;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        return value;");
        sb.AppendLine("    }");
        sb.AppendLine();
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine($"    public override {model.TypeName} DefaultValue => null!;");
        }
        else
        {
            sb.AppendLine($"    public override {model.TypeName} DefaultValue => new {model.TypeName}();");
        }

        sb.AppendLine();
        sb.AppendLine("    private global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo> TypeMetaFields(bool trackRef)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine(
            "            return __ForyTypeMetaFieldsTrackRef ??= __ForyBuildTypeMetaFields(true);");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine(
            "        return __ForyTypeMetaFieldsNoTrackRef ??= __ForyBuildTypeMetaFields(false);");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine(
            $"    public override void WriteData(global::Apache.Fory.WriteContext context, in {model.TypeName} value, bool hasGenerics)");
        sb.AppendLine("    {");
        sb.AppendLine("        _ = hasGenerics;");
        sb.AppendLine("        if (context.Compatible)");
        sb.AppendLine("        {");
        if (model.SortedMembers.Length == 0)
        {
            sb.AppendLine("            return;");
        }
        else
        {
            foreach (MemberModel member in model.SortedMembers)
            {
                EmitWriteMember(sb, member, true);
            }

            sb.AppendLine("            return;");
        }

        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        uint schemaHash = __ForySchemaHash(context.TrackRef, context.TypeResolver);");
        sb.AppendLine("        context.Writer.WriteInt32(unchecked((int)schemaHash));");
        foreach (MemberModel member in model.SortedMembers)
        {
            EmitWriteMember(sb, member, false);
        }

        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine($"    private {model.TypeName} ReadDataWithoutTypeMeta(global::Apache.Fory.ReadContext context)");
        sb.AppendLine("    {");
        sb.AppendLine($"        {model.TypeName} valueNoTypeMeta = new {model.TypeName}();");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("        context.StoreRef(valueNoTypeMeta);");
        }

        foreach (MemberModel member in model.SortedMembers)
        {
            EmitReadMemberAssignment(
                sb,
                member,
                BuildWriteRefModeExpression(member),
                BuildFieldTypeInfoLiteral(member),
                "valueNoTypeMeta",
                "CompatNoTypeMeta",
                4,
                true);
        }

        sb.AppendLine("        return valueNoTypeMeta;");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine($"    public override {model.TypeName} ReadData(global::Apache.Fory.ReadContext context)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (context.Compatible)");
        sb.AppendLine("        {");
        sb.AppendLine(
            $"            global::Apache.Fory.TypeMeta? maybeTypeMeta = context.GetTypeMeta<{model.TypeName}>();");
        sb.AppendLine("            if (maybeTypeMeta is null)");
        sb.AppendLine("            {");
        sb.AppendLine("                return ReadDataWithoutTypeMeta(context);");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            global::Apache.Fory.TypeMeta typeMeta = maybeTypeMeta;");
        sb.AppendLine($"            {model.TypeName} value = new {model.TypeName}();");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("            context.StoreRef(value);");
        }

        sb.AppendLine("            bool __ForyExactTypeMeta = __ForyMatchesCachedTypeMeta(typeMeta, context.TrackRef, context.TypeResolver);");
        sb.AppendLine("            if (__ForyAllFieldsBuiltIn && __ForyExactTypeMeta)");
        sb.AppendLine("            {");
        foreach (MemberModel member in model.SortedMembers)
        {
            EmitReadMemberAssignment(
                sb,
                member,
                BuildWriteRefModeExpression(member),
                "false",
                "value",
                "CompatExact",
                6,
                true);
        }

        sb.AppendLine("                return value;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            if (__ForyExactTypeMeta)");
        sb.AppendLine("            {");
        foreach (MemberModel member in model.SortedMembers)
        {
            EmitReadMemberAssignment(
                sb,
                member,
                BuildWriteRefModeExpression(member),
                BuildFieldTypeInfoLiteral(member),
                "value",
                "CompatExactTyped",
                6,
                true);
        }

        sb.AppendLine("                return value;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            for (int i = 0; i < typeMeta.Fields.Count; i++)");
        sb.AppendLine("            {");
        sb.AppendLine("                global::Apache.Fory.TypeMetaFieldInfo remoteField = typeMeta.Fields[i];");
        sb.AppendLine("                switch (remoteField.AssignedFieldId)");
        sb.AppendLine("                {");
        sb.AppendLine("                    case -1:");
        sb.AppendLine("                        global::Apache.Fory.FieldSkipper.SkipFieldValue(context, remoteField.FieldType);");
        sb.AppendLine("                        break;");
        for (int idx = 0; idx < model.SortedMembers.Length; idx++)
        {
            MemberModel member = model.SortedMembers[idx];
            sb.AppendLine($"                    case {idx * 2}:");
            sb.AppendLine("                        {");
            EmitReadMemberAssignment(
                sb,
                member,
                BuildWriteRefModeExpression(member),
                BuildFieldTypeInfoLiteral(member),
                "value",
                "CompatDirect",
                7,
                true);
            sb.AppendLine("                            break;");
            sb.AppendLine("                        }");
            sb.AppendLine($"                    case {idx * 2 + 1}:");
            sb.AppendLine("                        {");
            string compatRefModeExpr;
            if (CompatibleCaseNeedsRemoteRefMode(member))
            {
                sb.AppendLine("                            global::Apache.Fory.RefMode remoteRefMode = __ForyRefMode(remoteField.FieldType.Nullable, remoteField.FieldType.TrackRef);");
                compatRefModeExpr = "remoteRefMode";
            }
            else
            {
                compatRefModeExpr = "default";
            }

            EmitReadMemberAssignment(
                sb,
                member,
                compatRefModeExpr,
                BuildFieldTypeInfoLiteral(member),
                "value",
                "Compat",
                7,
                false);
            sb.AppendLine("                            break;");
            sb.AppendLine("                        }");
        }

        sb.AppendLine("                    default:");
        sb.AppendLine("                        throw new global::Apache.Fory.InvalidDataException($\"invalid compatible matched id {remoteField.AssignedFieldId}\");");
        sb.AppendLine("                }");
        sb.AppendLine("            }");
        sb.AppendLine("            return value;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        uint schemaHash = unchecked((uint)context.Reader.ReadInt32());");
        sb.AppendLine("        if (context.CheckStructVersion)");
        sb.AppendLine("        {");
        sb.AppendLine("            uint expectedHash = __ForySchemaHash(context.TrackRef, context.TypeResolver);");
        sb.AppendLine("            if (schemaHash != expectedHash)");
        sb.AppendLine("            {");
        sb.AppendLine("                throw new global::Apache.Fory.InvalidDataException($\"class version hash mismatch: expected {expectedHash}, got {schemaHash}\");");
        sb.AppendLine("            }");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine($"        {model.TypeName} valueSchema = new {model.TypeName}();");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("        context.StoreRef(valueSchema);");
        }

        foreach (MemberModel member in model.SortedMembers)
        {
            EmitReadMemberAssignment(sb, member, BuildWriteRefModeExpression(member), "false", "valueSchema", "Schema", 2, true);
        }

        sb.AppendLine("        return valueSchema;");
        sb.AppendLine("    }");
        sb.AppendLine("}");
    }

    private static void EmitUnionSerializer(StringBuilder sb, TypeModel model)
    {
        sb.AppendLine(
            $"file sealed class {model.SerializerName} : global::Apache.Fory.Serializer<{model.TypeName}>");
        sb.AppendLine("{");
        sb.AppendLine($"    public override {model.TypeName} DefaultValue => null!;");
        sb.AppendLine();
        sb.AppendLine("    private static global::Apache.Fory.RefMode __ForyRefMode(bool nullable, bool trackRef)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine("            return global::Apache.Fory.RefMode.Tracking;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        return nullable ? global::Apache.Fory.RefMode.NullOnly : global::Apache.Fory.RefMode.None;");
        sb.AppendLine("    }");
        sb.AppendLine();
        foreach (UnionCaseModel unionCase in KnownUnionCases(model))
        {
            if (unionCase.ValueMember is { HasSchemaType: true } member)
            {
                EmitUnionCaseSerializer(sb, unionCase.KnownCaseId, member);
            }
        }

        sb.AppendLine(
            $"    public override void WriteData(global::Apache.Fory.WriteContext context, in {model.TypeName} value, bool hasGenerics)");
        sb.AppendLine("    {");
        sb.AppendLine("        _ = hasGenerics;");
        sb.AppendLine("        if (value is null)");
        sb.AppendLine("        {");
        sb.AppendLine("            throw new global::Apache.Fory.InvalidDataException(\"union value is null\");");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        switch (value)");
        sb.AppendLine("        {");
        UnionCaseModel? unknownCase = model.UnionCases.FirstOrDefault(c => c.IsUnknown);
        if (unknownCase is not null)
        {
            sb.AppendLine($"            case {unknownCase.TypeName} __foryCase:");
            sb.AppendLine("            {");
            sb.AppendLine("                if (__foryCase.Value.CaseId < 0)");
            sb.AppendLine("                {");
            sb.AppendLine("                    throw new global::Apache.Fory.InvalidDataException($\"unknown union case id must be non-negative: {__foryCase.Value.CaseId}\");");
            sb.AppendLine("                }");
            sb.AppendLine();
            sb.AppendLine("                context.Writer.WriteVarUInt32((uint)__foryCase.Value.CaseId);");
            sb.AppendLine("                global::Apache.Fory.UnknownCaseSerializer.WritePayload(context, __foryCase.Value);");
            sb.AppendLine("                return;");
            sb.AppendLine("            }");
        }

        foreach (UnionCaseModel unionCase in KnownUnionCases(model))
        {
            sb.AppendLine($"            case {unionCase.TypeName} __foryCase:");
            sb.AppendLine("            {");
            sb.AppendLine($"                context.Writer.WriteVarUInt32({unionCase.KnownCaseId}u);");
            EmitWriteUnionCasePayload(sb, unionCase, "__foryCase.Value", 4);
            sb.AppendLine("                return;");
            sb.AppendLine("            }");
        }

        sb.AppendLine("            default:");
        sb.AppendLine("                throw new global::Apache.Fory.InvalidDataException($\"unsupported union case {value.GetType()}\");");
        sb.AppendLine("        }");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine($"    public override {model.TypeName} ReadData(global::Apache.Fory.ReadContext context)");
        sb.AppendLine("    {");
        sb.AppendLine("        uint rawCaseId = context.Reader.ReadVarUInt32();");
        sb.AppendLine("        if (rawCaseId > int.MaxValue)");
        sb.AppendLine("        {");
        sb.AppendLine("            throw new global::Apache.Fory.InvalidDataException($\"union case id out of range: {rawCaseId}\");");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        int caseId = (int)rawCaseId;");
        sb.AppendLine("        switch (caseId)");
        sb.AppendLine("        {");
        foreach (UnionCaseModel unionCase in KnownUnionCases(model))
        {
            int caseId = unionCase.KnownCaseId;
            string valueVar = $"__foryCaseValue{caseId}";
            sb.AppendLine($"            case {caseId}:");
            sb.AppendLine("            {");
            EmitReadUnionCasePayload(sb, unionCase, valueVar, 4);
            sb.AppendLine($"                return new {unionCase.TypeName}({valueVar});");
            sb.AppendLine("            }");
        }

        sb.AppendLine("            default:");
        sb.AppendLine("            {");
        if (unknownCase is null)
        {
            sb.AppendLine("                throw new global::Apache.Fory.InvalidDataException($\"unknown union case {caseId}\");");
        }
        else
        {
            sb.AppendLine($"                return new {unknownCase.TypeName}(global::Apache.Fory.UnknownCaseSerializer.ReadPayload(context, caseId));");
        }

        sb.AppendLine("            }");
        sb.AppendLine("        }");
        sb.AppendLine("    }");
        sb.AppendLine("}");
    }

    private static void EmitUnionCaseSerializer(
        StringBuilder sb,
        int caseId,
        MemberModel member)
    {
        sb.AppendLine($"    private sealed class __ForyCaseSerializer{caseId} : global::Apache.Fory.Serializer<{member.TypeName}>");
        sb.AppendLine("    {");
        sb.AppendLine($"        internal static readonly __ForyCaseSerializer{caseId} Instance = new();");
        sb.AppendLine();
        sb.AppendLine($"        public override {member.TypeName} DefaultValue => default!;");
        sb.AppendLine();
        sb.AppendLine($"        public override void WriteData(global::Apache.Fory.WriteContext context, in {member.TypeName} value, bool hasGenerics)");
        sb.AppendLine("        {");
        sb.AppendLine("            _ = hasGenerics;");
        EmitWriteUnionTopType(sb, member.TypeMeta, 3);
        string payloadExpr = member.IsNullableValueType ? "value.GetValueOrDefault()" : "value";
        EmitWriteUnionPayload(sb, NonNullableMember(member), payloadExpr, 3);
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine($"        public override {member.TypeName} ReadData(global::Apache.Fory.ReadContext context)");
        sb.AppendLine("        {");
        EmitValidateUnionTopType(sb, member.TypeMeta, 3);
        EmitReadUnionPayload(sb, NonNullableMember(member), "__foryPayload", 3);
        sb.AppendLine("            return __foryPayload;");
        sb.AppendLine("        }");
        sb.AppendLine("    }");
        sb.AppendLine();
    }

    private static void EmitWriteUnionCasePayload(
        StringBuilder sb,
        UnionCaseModel unionCase,
        string valueExpr,
        int indentLevel)
    {
        MemberModel member = unionCase.ValueMember!;
        string indent = new(' ', indentLevel * 4);
        string refModeExpr = BuildUnionCaseRefModeExpression(member);
        string hasGenerics = member.IsCollection ? "true" : "false";

        if (member.DynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            sb.AppendLine(
                $"{indent}global::Apache.Fory.DynamicAnyCodec.WriteAny(context, {valueExpr}, {refModeExpr}, true, false);");
            return;
        }

        if (!member.HasSchemaType)
        {
            sb.AppendLine(
                $"{indent}context.TypeResolver.GetSerializer<{member.TypeName}>().Write(context, {valueExpr}, {refModeExpr}, true, {hasGenerics});");
            return;
        }

        sb.AppendLine(
            $"{indent}__ForyCaseSerializer{unionCase.KnownCaseId}.Instance.Write(context, {valueExpr}, {refModeExpr}, false, false);");
    }

    private static void EmitReadUnionCasePayload(
        StringBuilder sb,
        UnionCaseModel unionCase,
        string valueVar,
        int indentLevel)
    {
        MemberModel member = unionCase.ValueMember!;
        string indent = new(' ', indentLevel * 4);
        string refModeExpr = BuildUnionCaseRefModeExpression(member);

        if (member.DynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            string typeOfTypeName = StripNullableForTypeOf(member.TypeName);
            sb.AppendLine(
                $"{indent}{member.TypeName} {valueVar} = ({member.TypeName})global::Apache.Fory.DynamicAnyCodec.CastAnyDynamicValue(global::Apache.Fory.DynamicAnyCodec.ReadAny(context, {refModeExpr}, true), typeof({typeOfTypeName}))!;");
            return;
        }

        if (!member.HasSchemaType)
        {
            sb.AppendLine(
                $"{indent}{member.TypeName} {valueVar} = context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, true);");
            return;
        }

        sb.AppendLine(
            $"{indent}{member.TypeName} {valueVar} = __ForyCaseSerializer{unionCase.KnownCaseId}.Instance.Read(context, {refModeExpr}, false);");
    }

    private static void EmitWriteUnionPayload(
        StringBuilder sb,
        MemberModel member,
        string valueExpr,
        int indentLevel)
    {
        int id = 0;
        if (member.FieldCodec is not null)
        {
            EmitWritePayload(sb, member.FieldCodec, valueExpr, indentLevel, ref id);
            return;
        }

        if (TryBuildDirectPayloadWrite(member.Classification.TypeId, valueExpr, out string? writeCode))
        {
            string indent = new(' ', indentLevel * 4);
            sb.AppendLine($"{indent}{writeCode}");
            return;
        }

        string hasGenerics = member.IsCollection ? "true" : "false";
        string fallbackIndent = new(' ', indentLevel * 4);
        sb.AppendLine(
            $"{fallbackIndent}context.TypeResolver.GetSerializer<{member.TypeName}>().WriteData(context, {valueExpr}, {hasGenerics});");
    }

    private static void EmitReadUnionPayload(
        StringBuilder sb,
        MemberModel member,
        string valueVar,
        int indentLevel)
    {
        int id = 0;
        if (member.FieldCodec is not null)
        {
            EmitReadPayload(sb, member.FieldCodec, valueVar, indentLevel, ref id);
            return;
        }

        if (TryBuildDirectPayloadRead(member.Classification.TypeId, out string? readExpr))
        {
            string indent = new(' ', indentLevel * 4);
            sb.AppendLine($"{indent}{member.TypeName} {valueVar} = {readExpr};");
            return;
        }

        string fallbackIndent = new(' ', indentLevel * 4);
        sb.AppendLine(
            $"{fallbackIndent}{member.TypeName} {valueVar} = context.TypeResolver.GetSerializer<{member.TypeName}>().ReadData(context);");
    }

    private static void EmitWriteUnionTopType(
        StringBuilder sb,
        TypeMetaFieldTypeModel model,
        int indentLevel)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}context.Writer.WriteUInt8((byte)({model.TypeIdExpr}));");
    }

    private static void EmitValidateUnionTopType(
        StringBuilder sb,
        TypeMetaFieldTypeModel model,
        int indentLevel)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}uint __foryTypeId = context.Reader.ReadUInt8();");
        sb.AppendLine($"{indent}if (__foryTypeId != ({model.TypeIdExpr}))");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    throw new global::Apache.Fory.TypeMismatchException({model.TypeIdExpr}, __foryTypeId);");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitFieldCodecMethods(StringBuilder sb, MemberModel member)
    {
        FieldCodecModel codec = member.FieldCodec!;
        string memberId = Sanitize(member.Name);
        sb.AppendLine(
            $"    private static void __ForyWrite{memberId}Field(global::Apache.Fory.WriteContext context, {member.TypeName} value, global::Apache.Fory.RefMode refMode)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (refMode == global::Apache.Fory.RefMode.NullOnly)");
        sb.AppendLine("        {");
        if (member.IsNullableValueType)
        {
            sb.AppendLine("            if (!value.HasValue)");
        }
        else
        {
            sb.AppendLine("            if (value is null)");
        }

        sb.AppendLine("            {");
        sb.AppendLine("                context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.Null);");
        sb.AppendLine("                return;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.NotNullValue);");
        sb.AppendLine("        }");
        string writeValueExpr = member.IsNullableValueType ? "value.Value" : member.IsNullable ? "value!" : "value";
        int id = 0;
        EmitWritePayload(sb, codec, writeValueExpr, 2, ref id);
        sb.AppendLine("    }");
        sb.AppendLine();

        sb.AppendLine(
            $"    private static {member.TypeName} __ForyRead{memberId}Field(global::Apache.Fory.ReadContext context, global::Apache.Fory.RefMode refMode)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (refMode == global::Apache.Fory.RefMode.NullOnly)");
        sb.AppendLine("        {");
        sb.AppendLine("            sbyte refFlag = context.Reader.ReadInt8();");
        sb.AppendLine("            if (refFlag == (sbyte)global::Apache.Fory.RefFlag.Null)");
        sb.AppendLine("            {");
        sb.AppendLine($"                return ({member.TypeName})default!;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            if (refFlag != (sbyte)global::Apache.Fory.RefFlag.NotNullValue)");
        sb.AppendLine("            {");
        sb.AppendLine("                throw new global::Apache.Fory.InvalidDataException($\"invalid nullOnly ref flag {refFlag}\");");
        sb.AppendLine("            }");
        sb.AppendLine("        }");
        string resultVar = $"__{memberId}Value";
        id = 0;
        EmitReadPayload(sb, codec, resultVar, 2, ref id);
        sb.AppendLine($"        return {resultVar};");
        sb.AppendLine("    }");
        sb.AppendLine();
    }

    private static void EmitCompatibleFieldCodecMethods(StringBuilder sb, TypeModel model)
    {
        bool hasCompatibleField = false;
        foreach (MemberModel member in model.SortedMembers)
        {
            if (member.FieldCodec is not null &&
                CanReadCompatibleField(member.FieldCodec))
            {
                hasCompatibleField = true;
                break;
            }
        }

        if (!hasCompatibleField)
        {
            return;
        }

        sb.AppendLine("    private static class __ForyCompatibleFieldReaders");
        sb.AppendLine("    {");
        foreach (MemberModel member in model.SortedMembers)
        {
            if (member.FieldCodec is not null &&
                CanReadCompatibleField(member.FieldCodec))
            {
                EmitCompatibleFieldCodecMethod(sb, member, member.FieldCodec);
            }
        }

        sb.AppendLine("    }");
        sb.AppendLine();
    }

    private static void EmitCompatibleFieldCodecMethod(
        StringBuilder sb,
        MemberModel member,
        FieldCodecModel codec)
    {
        string memberId = Sanitize(member.Name);
        sb.AppendLine("        [global::System.Runtime.CompilerServices.MethodImpl(global::System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]");
        sb.AppendLine(
            $"        internal static {member.TypeName} Read{memberId}FieldBridge(global::Apache.Fory.ReadContext context, global::Apache.Fory.TypeMetaFieldType remoteFieldType, global::Apache.Fory.RefMode refMode)");
        sb.AppendLine("        {");
        sb.AppendLine("            if (remoteFieldType.TypeId == " + codec.TypeId + ")");
        sb.AppendLine("            {");
        sb.AppendLine($"                return __ForyRead{memberId}Field(context, refMode);");
        sb.AppendLine("            }");
        sb.AppendLine();
        if (TryBuildCompatibleListArrayReadCodec(codec, out FieldCodecModel? alternateCodec))
        {
            sb.AppendLine("            if (remoteFieldType.TypeId == " + alternateCodec.TypeId + ")");
            sb.AppendLine("            {");
            if (codec.Kind == FieldCodecKind.PackedArray)
            {
                sb.AppendLine("                if (remoteFieldType.Generics.Count != 1)");
                sb.AppendLine("                {");
                sb.AppendLine("                    throw new global::Apache.Fory.InvalidDataException(\"compatible list to array field requires one element schema\");");
                sb.AppendLine("                }");
            }

            EmitReadNullOnlyPrefix(sb, member, 4);
            int id = 0;
            string compatibleResultVar = $"__{memberId}CompatibleValue";
            if (codec.Kind == FieldCodecKind.PackedArray && alternateCodec.Kind == FieldCodecKind.List)
            {
                EmitReadCompatibleListArrayPayload(sb, codec, compatibleResultVar, 4, ref id);
            }
            else
            {
                EmitReadPayload(sb, alternateCodec, compatibleResultVar, 4, ref id);
            }

            sb.AppendLine($"                return {compatibleResultVar};");
            sb.AppendLine("            }");
        }

        if (CanReadCompatibleBinaryField(codec))
        {
            sb.AppendLine("            if (remoteFieldType.TypeId == (uint)global::Apache.Fory.TypeId.Binary)");
            sb.AppendLine("            {");
            EmitReadNullOnlyPrefix(sb, member, 4);
            EmitReadBinaryField(sb, codec, $"__{memberId}BinaryValue", 4);
            sb.AppendLine($"                return __{memberId}BinaryValue;");
            sb.AppendLine("            }");
        }

        sb.AppendLine("            throw new global::Apache.Fory.InvalidDataException($\"unsupported compatible field schema pair: local " + codec.TypeId + ", remote {remoteFieldType.TypeId}\");");
        sb.AppendLine("        }");
    }

    private static void EmitReadNullOnlyPrefix(StringBuilder sb, MemberModel member, int indentLevel)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}if (refMode == global::Apache.Fory.RefMode.NullOnly)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    sbyte refFlag = context.Reader.ReadInt8();");
        sb.AppendLine($"{indent}    if (refFlag == (sbyte)global::Apache.Fory.RefFlag.Null)");
        sb.AppendLine($"{indent}    {{");
        sb.AppendLine($"{indent}        return ({member.TypeName})default!;");
        sb.AppendLine($"{indent}    }}");
        sb.AppendLine();
        sb.AppendLine($"{indent}    if (refFlag != (sbyte)global::Apache.Fory.RefFlag.NotNullValue)");
        sb.AppendLine($"{indent}    {{");
        sb.AppendLine($"{indent}        throw new global::Apache.Fory.InvalidDataException($\"invalid nullOnly ref flag {{refFlag}}\");");
        sb.AppendLine($"{indent}    }}");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitReadBinaryField(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}int __foryLength = checked((int)context.Reader.ReadVarUInt32());");
        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = context.Reader.ReadBytes(__foryLength);");
            return;
        }

        if (codec.CarrierKind == CarrierKind.List)
        {
            sb.AppendLine($"{indent}context.Reader.CheckBound(__foryLength);");
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new(__foryLength);");
            sb.AppendLine($"{indent}for (int __foryIndex = 0; __foryIndex < __foryLength; __foryIndex++)");
            sb.AppendLine($"{indent}{{");
            sb.AppendLine($"{indent}    {targetVar}.Add(context.Reader.ReadUInt8());");
            sb.AppendLine($"{indent}}}");
            return;
        }

        throw new InvalidOperationException($"unsupported binary compatible carrier {codec.TypeName}");
    }

    private static bool CanReadCompatibleField(FieldCodecModel codec)
    {
        return TryBuildCompatibleListArrayReadCodec(codec, out _) || CanReadCompatibleBinaryField(codec);
    }

    private static bool CanReadCompatibleBinaryField(FieldCodecModel codec)
    {
        return codec.Kind == FieldCodecKind.PackedArray &&
               codec.TypeId == UInt8ArrayTypeId &&
               codec.CarrierKind is CarrierKind.Array or CarrierKind.List;
    }

    private static bool TryBuildCompatibleListArrayReadCodec(FieldCodecModel codec, out FieldCodecModel compatibleCodec)
    {
        if (codec.Kind == FieldCodecKind.PackedArray)
        {
            uint elementTypeId = PackedArrayElementTypeId(codec.TypeId);
            compatibleCodec = new FieldCodecModel(
                FieldCodecKind.List,
                22,
                codec.TypeName,
                codec.Nullable,
                codec.NullableValueType,
                codec.CarrierKind,
                ImmutableArray.Create(new FieldCodecModel(
                    FieldCodecKind.Scalar,
                    elementTypeId,
                    PackedArrayElementTypeName(codec.TypeId),
                    false,
                    false,
                    CarrierKind.Value,
                    ImmutableArray<FieldCodecModel>.Empty)));
            return true;
        }

        if (codec.Kind == FieldCodecKind.List &&
            codec.Generics.Length == 1 &&
            TryResolveArrayTypeIdForElement(codec.Generics[0].TypeId) is uint arrayTypeId)
        {
            compatibleCodec = new FieldCodecModel(
                FieldCodecKind.PackedArray,
                arrayTypeId,
                codec.TypeName,
                codec.Nullable,
                codec.NullableValueType,
                codec.CarrierKind,
                ImmutableArray<FieldCodecModel>.Empty);
            return true;
        }

        compatibleCodec = codec;
        return false;
    }

    private static void EmitReadCompatibleListArrayPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        string lengthVar = $"__foryLength{id++}";
        string headerVar = $"__foryHeader{id++}";
        string declaredVar = $"__foryDeclared{id++}";
        string sameTypeVar = $"__forySameType{id++}";
        sb.AppendLine($"{indent}int {lengthVar} = checked((int)context.Reader.ReadVarUInt32());");
        sb.AppendLine($"{indent}if ({lengthVar} != 0)");
        sb.AppendLine($"{indent}{{");
        string innerIndent = indent + "    ";
        sb.AppendLine($"{innerIndent}byte {headerVar} = context.Reader.ReadUInt8();");
        sb.AppendLine($"{innerIndent}if (({headerVar} & 0b0000_0011) != 0)");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    throw new global::Apache.Fory.InvalidDataException(\"compatible list to array field requires non-null elements\");");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}bool {declaredVar} = ({headerVar} & 0b0000_0100) != 0;");
        sb.AppendLine($"{innerIndent}bool {sameTypeVar} = ({headerVar} & 0b0000_1000) != 0;");
        sb.AppendLine($"{innerIndent}if (!{sameTypeVar})");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    throw new global::Apache.Fory.InvalidDataException(\"compatible list to array field requires same-type elements\");");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}if (!{declaredVar})");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    uint __foryWireTypeId = context.Reader.ReadUInt8();");
        sb.AppendLine($"{innerIndent}    if (__foryWireTypeId != remoteFieldType.Generics[0].TypeId)");
        sb.AppendLine($"{innerIndent}    {{");
        sb.AppendLine($"{innerIndent}        throw new global::Apache.Fory.TypeMismatchException(remoteFieldType.Generics[0].TypeId, __foryWireTypeId);");
        sb.AppendLine($"{innerIndent}    }}");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{indent}}}");
        sb.AppendLine($"{indent}if ({lengthVar} != 0)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    context.Reader.CheckBound({lengthVar});");
        sb.AppendLine($"{indent}}}");
        string elementTypeName = codec.CarrierKind == CarrierKind.Array ? ElementTypeName(codec.TypeName) : PackedArrayElementTypeName(codec.TypeId);
        uint elementTypeId = PackedArrayElementTypeId(codec.TypeId);
        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new {ElementTypeName(codec.TypeName)}[{lengthVar}];");
        }
        else
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new({lengthVar});");
        }

        string indexVar = $"__foryIndex{id++}";
        sb.AppendLine($"{indent}switch (remoteFieldType.Generics[0].TypeId)");
        sb.AppendLine($"{indent}{{");
        foreach (uint remoteElementTypeId in CompatibleElementReadTypeIds(elementTypeId))
        {
            if (!TryBuildDirectPayloadRead(remoteElementTypeId, out string? itemReadExpr))
            {
                throw new InvalidOperationException($"unsupported compatible list element type id {remoteElementTypeId}");
            }

            sb.AppendLine($"{indent}    case {remoteElementTypeId}:");
            sb.AppendLine($"{indent}        for (int {indexVar} = 0; {indexVar} < {lengthVar}; {indexVar}++)");
            sb.AppendLine($"{indent}        {{");
            sb.AppendLine($"{indent}            {elementTypeName} __foryItem = {itemReadExpr};");
            if (codec.CarrierKind == CarrierKind.Array)
            {
                sb.AppendLine($"{indent}            {targetVar}[{indexVar}] = __foryItem;");
            }
            else
            {
                sb.AppendLine($"{indent}            {targetVar}.Add(__foryItem);");
            }

            sb.AppendLine($"{indent}        }}");
            sb.AppendLine($"{indent}        break;");
        }
        sb.AppendLine($"{indent}    default:");
        sb.AppendLine($"{indent}        throw new global::Apache.Fory.InvalidDataException($\"unsupported compatible list element type {{remoteFieldType.Generics[0].TypeId}}\");");
        sb.AppendLine($"{indent}}}");
    }

    private static uint[] CompatibleElementReadTypeIds(uint elementTypeId)
    {
        return elementTypeId switch
        {
            4 or 5 => [4, 5],
            6 or 7 or 8 => [6, 7, 8],
            11 or 12 => [11, 12],
            13 or 14 or 15 => [13, 14, 15],
            _ => [elementTypeId],
        };
    }

    private static void EmitWritePayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string valueExpr,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        switch (codec.Kind)
        {
            case FieldCodecKind.Scalar:
                if (!TryBuildDirectPayloadWrite(codec.TypeId, valueExpr, out string? writeCode))
                {
                    sb.AppendLine($"{indent}context.TypeResolver.GetSerializer<{codec.TypeName}>().WriteData(context, {valueExpr}, false);");
                    return;
                }

                sb.AppendLine($"{indent}{writeCode}");
                return;
            case FieldCodecKind.PackedArray:
                EmitWritePackedArrayPayload(sb, codec, valueExpr, indentLevel, ref id);
                return;
            case FieldCodecKind.List:
                EmitWriteCollectionPayload(sb, codec, valueExpr, indentLevel, ref id, isSet: false);
                return;
            case FieldCodecKind.Set:
                EmitWriteCollectionPayload(sb, codec, valueExpr, indentLevel, ref id, isSet: true);
                return;
            case FieldCodecKind.Map:
                EmitWriteMapPayload(sb, codec, valueExpr, indentLevel, ref id);
                return;
        }
    }

    private static void EmitWritePackedArrayPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string valueExpr,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        string valuesVar = $"__foryPacked{id++}";
        sb.AppendLine($"{indent}{codec.TypeName} {valuesVar} = {valueExpr} ?? [];");
        string countExpr = codec.CarrierKind == CarrierKind.Array ? $"{valuesVar}.Length" : $"{valuesVar}.Count";
        int width = PackedArrayElementWidth(codec.TypeId);
        string lengthExpr = width == 1 ? countExpr : $"checked({countExpr} * {width})";
        sb.AppendLine($"{indent}context.Writer.WriteVarUInt32((uint){lengthExpr});");
        string packedIndexVar = $"__foryIndex{id++}";
        sb.AppendLine($"{indent}for (int {packedIndexVar} = 0; {packedIndexVar} < {countExpr}; {packedIndexVar}++)");
        sb.AppendLine($"{indent}{{");
        string itemExpr = $"{valuesVar}[{packedIndexVar}]";
        uint elementTypeId = PackedArrayElementTypeId(codec.TypeId);
        if (!TryBuildDirectPayloadWrite(elementTypeId, itemExpr, out string? writeCode))
        {
            throw new InvalidOperationException($"unsupported packed array type id {codec.TypeId}");
        }

        sb.AppendLine($"{indent}    {writeCode}");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitWriteCollectionPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string valueExpr,
        int indentLevel,
        ref int id,
        bool isSet)
    {
        string indent = new(' ', indentLevel * 4);
        FieldCodecModel element = codec.Generics[0];
        string valuesVar = $"__foryCollection{id++}";
        sb.AppendLine($"{indent}{codec.TypeName} {valuesVar} = {valueExpr} ?? [];");
        string countExpr = codec.CarrierKind == CarrierKind.Array ? $"{valuesVar}.Length" : $"{valuesVar}.Count";
        sb.AppendLine($"{indent}int __foryCount{id} = {countExpr};");
        string countVar = $"__foryCount{id++}";
        sb.AppendLine($"{indent}context.Writer.WriteVarUInt32((uint){countVar});");
        sb.AppendLine($"{indent}if ({countVar} != 0)");
        sb.AppendLine($"{indent}{{");
        string innerIndent = indent + "    ";
        string hasNullVar = $"__foryHasNull{id++}";
        if (element.Nullable)
        {
            sb.AppendLine($"{innerIndent}bool {hasNullVar} = false;");
            if (isSet)
            {
                sb.AppendLine($"{innerIndent}foreach ({element.TypeName} __foryItem in {valuesVar})");
                sb.AppendLine($"{innerIndent}{{");
                sb.AppendLine($"{innerIndent}    if (__foryItem is null)");
                sb.AppendLine($"{innerIndent}    {{");
                sb.AppendLine($"{innerIndent}        {hasNullVar} = true;");
                sb.AppendLine($"{innerIndent}        break;");
                sb.AppendLine($"{innerIndent}    }}");
                sb.AppendLine($"{innerIndent}}}");
            }
            else
            {
                string scanIndexVar = $"__foryIndex{id++}";
                sb.AppendLine($"{innerIndent}for (int {scanIndexVar} = 0; {scanIndexVar} < {countVar}; {scanIndexVar}++)");
                sb.AppendLine($"{innerIndent}{{");
                string itemExpr = $"{valuesVar}[{scanIndexVar}]";
                sb.AppendLine($"{innerIndent}    if ({itemExpr} is null)");
                sb.AppendLine($"{innerIndent}    {{");
                sb.AppendLine($"{innerIndent}        {hasNullVar} = true;");
                sb.AppendLine($"{innerIndent}        break;");
                sb.AppendLine($"{innerIndent}    }}");
                sb.AppendLine($"{innerIndent}}}");
            }
        }
        else
        {
            sb.AppendLine($"{innerIndent}bool {hasNullVar} = false;");
        }

        string collectionHeaderVar = $"__foryHeader{id++}";
        sb.AppendLine($"{innerIndent}byte {collectionHeaderVar} = 0b0000_1000 | 0b0000_0100;");
        sb.AppendLine($"{innerIndent}if ({hasNullVar})");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    {collectionHeaderVar} |= 0b0000_0010;");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}context.Writer.WriteUInt8({collectionHeaderVar});");
        if (isSet)
        {
            sb.AppendLine($"{innerIndent}foreach ({element.TypeName} __foryItem in {valuesVar})");
            sb.AppendLine($"{innerIndent}{{");
            EmitWriteNullableElementPayload(sb, element, "__foryItem", indentLevel + 2, ref id, hasNullVar);
            sb.AppendLine($"{innerIndent}}}");
        }
        else
        {
            string writeIndexVar = $"__foryIndex{id++}";
            sb.AppendLine($"{innerIndent}for (int {writeIndexVar} = 0; {writeIndexVar} < {countVar}; {writeIndexVar}++)");
            sb.AppendLine($"{innerIndent}{{");
            sb.AppendLine($"{innerIndent}    {element.TypeName} __foryItem = {valuesVar}[{writeIndexVar}];");
            EmitWriteNullableElementPayload(sb, element, "__foryItem", indentLevel + 2, ref id, hasNullVar);
            sb.AppendLine($"{innerIndent}}}");
        }

        sb.AppendLine($"{indent}}}");
    }

    private static void EmitWriteNullableElementPayload(
        StringBuilder sb,
        FieldCodecModel element,
        string itemExpr,
        int indentLevel,
        ref int id,
        string hasNullVar)
    {
        string indent = new(' ', indentLevel * 4);
        if (!element.Nullable)
        {
            EmitWritePayload(sb, element, itemExpr, indentLevel, ref id);
            return;
        }

        sb.AppendLine($"{indent}if ({hasNullVar})");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    if ({itemExpr} is null)");
        sb.AppendLine($"{indent}    {{");
        sb.AppendLine($"{indent}        context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.Null);");
        sb.AppendLine($"{indent}        continue;");
        sb.AppendLine($"{indent}    }}");
        sb.AppendLine();
        sb.AppendLine($"{indent}    context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.NotNullValue);");
        string nonNullExpr = element.NullableValueType ? $"{itemExpr}.GetValueOrDefault()" : $"{itemExpr}!";
        EmitWritePayload(sb, element, nonNullExpr, indentLevel + 1, ref id);
        sb.AppendLine($"{indent}}}");
        sb.AppendLine($"{indent}else");
        sb.AppendLine($"{indent}{{");
        EmitWritePayload(sb, element, element.NullableValueType ? $"{itemExpr}.GetValueOrDefault()" : $"{itemExpr}!", indentLevel + 1, ref id);
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitWriteMapPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string valueExpr,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        FieldCodecModel key = codec.Generics[0];
        FieldCodecModel value = codec.Generics[1];
        string mapVar = $"__foryMap{id++}";
        sb.AppendLine($"{indent}{codec.TypeName} {mapVar} = {valueExpr} ?? [];");
        sb.AppendLine($"{indent}context.Writer.WriteVarUInt32((uint){mapVar}.Count);");
        sb.AppendLine($"{indent}foreach (global::System.Collections.Generic.KeyValuePair<{key.TypeName}, {value.TypeName}> __foryEntry in {mapVar})");
        sb.AppendLine($"{indent}{{");
        string innerIndent = indent + "    ";
        string keyNullVar = $"__foryKeyNull{id++}";
        string valueNullVar = $"__foryValueNull{id++}";
        if (key.Nullable)
        {
            sb.AppendLine($"{innerIndent}bool {keyNullVar} = __foryEntry.Key is null;");
        }
        else
        {
            sb.AppendLine($"{innerIndent}bool {keyNullVar} = false;");
        }

        if (value.Nullable)
        {
            sb.AppendLine($"{innerIndent}bool {valueNullVar} = __foryEntry.Value is null;");
        }
        else
        {
            sb.AppendLine($"{innerIndent}bool {valueNullVar} = false;");
        }

        string mapHeaderVar = $"__foryHeader{id++}";
        sb.AppendLine($"{innerIndent}byte {mapHeaderVar} = 0;");
        sb.AppendLine($"{innerIndent}if ({keyNullVar}) {mapHeaderVar} |= 0b0000_0010; else {mapHeaderVar} |= 0b0000_0100;");
        sb.AppendLine($"{innerIndent}if ({valueNullVar}) {mapHeaderVar} |= 0b0001_0000; else {mapHeaderVar} |= 0b0010_0000;");
        sb.AppendLine($"{innerIndent}context.Writer.WriteUInt8({mapHeaderVar});");
        sb.AppendLine($"{innerIndent}if (!{keyNullVar} && !{valueNullVar})");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    context.Writer.WriteUInt8(1);");
        EmitWritePayload(sb, key, key.NullableValueType ? "__foryEntry.Key.GetValueOrDefault()" : "__foryEntry.Key!", indentLevel + 2, ref id);
        EmitWritePayload(sb, value, value.NullableValueType ? "__foryEntry.Value.GetValueOrDefault()" : "__foryEntry.Value!", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}    continue;");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}if (!{keyNullVar})");
        sb.AppendLine($"{innerIndent}{{");
        EmitWritePayload(sb, key, key.NullableValueType ? "__foryEntry.Key.GetValueOrDefault()" : "__foryEntry.Key!", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}if (!{valueNullVar})");
        sb.AppendLine($"{innerIndent}{{");
        EmitWritePayload(sb, value, value.NullableValueType ? "__foryEntry.Value.GetValueOrDefault()" : "__foryEntry.Value!", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitReadPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        switch (codec.Kind)
        {
            case FieldCodecKind.Scalar:
                if (TryBuildDirectPayloadRead(codec.TypeId, out string? readExpr))
                {
                    sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = {readExpr};");
                }
                else
                {
                    sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = context.TypeResolver.GetSerializer<{codec.TypeName}>().ReadData(context);");
                }

                return;
            case FieldCodecKind.PackedArray:
                EmitReadPackedArrayPayload(sb, codec, targetVar, indentLevel, ref id);
                return;
            case FieldCodecKind.List:
                EmitReadCollectionPayload(sb, codec, targetVar, indentLevel, ref id, isSet: false);
                return;
            case FieldCodecKind.Set:
                EmitReadCollectionPayload(sb, codec, targetVar, indentLevel, ref id, isSet: true);
                return;
            case FieldCodecKind.Map:
                EmitReadMapPayload(sb, codec, targetVar, indentLevel, ref id);
                return;
        }
    }

    private static void EmitReadPackedArrayPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        int width = PackedArrayElementWidth(codec.TypeId);
        uint elementTypeId = PackedArrayElementTypeId(codec.TypeId);
        string byteSizeVar = $"__foryByteSize{id++}";
        string countVar = $"__foryPackedCount{id++}";
        sb.AppendLine($"{indent}int {byteSizeVar} = checked((int)context.Reader.ReadVarUInt32());");
        if (width > 1)
        {
            int mask = width - 1;
            sb.AppendLine($"{indent}if (({byteSizeVar} & {mask}) != 0)");
            sb.AppendLine($"{indent}{{");
            sb.AppendLine($"{indent}    throw new global::Apache.Fory.InvalidDataException(\"packed array byte size mismatch\");");
            sb.AppendLine($"{indent}}}");
        }

        sb.AppendLine($"{indent}context.Reader.CheckBound({byteSizeVar});");
        sb.AppendLine($"{indent}int {countVar} = {byteSizeVar}{(width == 1 ? string.Empty : $" / {width}")};");
        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new {ElementTypeName(codec.TypeName)}[{countVar}];");
        }
        else
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new({countVar});");
        }

        string packedIndexVar = $"__foryIndex{id++}";
        sb.AppendLine($"{indent}for (int {packedIndexVar} = 0; {packedIndexVar} < {countVar}; {packedIndexVar}++)");
        sb.AppendLine($"{indent}{{");
        if (!TryBuildDirectPayloadRead(elementTypeId, out string? readExpr))
        {
            throw new InvalidOperationException($"unsupported packed array type id {codec.TypeId}");
        }

        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}    {targetVar}[{packedIndexVar}] = {readExpr};");
        }
        else
        {
            sb.AppendLine($"{indent}    {targetVar}.Add({readExpr});");
        }

        sb.AppendLine($"{indent}}}");
    }

    private static void EmitReadCollectionPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel,
        ref int id,
        bool isSet)
    {
        string indent = new(' ', indentLevel * 4);
        FieldCodecModel element = codec.Generics[0];
        string lengthVar = $"__foryLength{id++}";
        string headerVar = $"__foryHeader{id++}";
        string hasNullVar = $"__foryHasNull{id++}";
        string sameTypeVar = $"__forySameType{id++}";
        string declaredVar = $"__foryDeclared{id++}";
        sb.AppendLine($"{indent}int {lengthVar} = checked((int)context.Reader.ReadVarUInt32());");
        sb.AppendLine($"{indent}if ({lengthVar} != 0)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    context.Reader.CheckBound({lengthVar});");
        sb.AppendLine($"{indent}}}");
        if (isSet)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new();");
        }
        else if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new {ElementTypeName(codec.TypeName)}[{lengthVar}];");
        }
        else
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new({lengthVar});");
        }

        sb.AppendLine($"{indent}if ({lengthVar} != 0)");
        sb.AppendLine($"{indent}{{");
        string innerIndent = indent + "    ";
        sb.AppendLine($"{innerIndent}byte {headerVar} = context.Reader.ReadUInt8();");
        sb.AppendLine($"{innerIndent}bool {hasNullVar} = ({headerVar} & 0b0000_0010) != 0;");
        sb.AppendLine($"{innerIndent}bool {declaredVar} = ({headerVar} & 0b0000_0100) != 0;");
        sb.AppendLine($"{innerIndent}bool {sameTypeVar} = ({headerVar} & 0b0000_1000) != 0;");
        sb.AppendLine($"{innerIndent}if (!{sameTypeVar})");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    throw new global::Apache.Fory.InvalidDataException(\"generated collection fields require same-type element payloads\");");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}if (!{declaredVar})");
        sb.AppendLine($"{innerIndent}{{");
        EmitReadInlineTypeInfo(sb, NonNullableCodec(element), indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}}}");
        string collectionIndexVar = $"__foryIndex{id++}";
        sb.AppendLine($"{innerIndent}for (int {collectionIndexVar} = 0; {collectionIndexVar} < {lengthVar}; {collectionIndexVar}++)");
        sb.AppendLine($"{innerIndent}{{");
        EmitReadNullableElementPayload(sb, element, "__foryItem", indentLevel + 2, ref id, hasNullVar);
        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{innerIndent}    {targetVar}[{collectionIndexVar}] = __foryItem;");
        }
        else
        {
            sb.AppendLine($"{innerIndent}    {targetVar}.Add(__foryItem);");
        }

        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitReadNullableElementPayload(
        StringBuilder sb,
        FieldCodecModel element,
        string targetVar,
        int indentLevel,
        ref int id,
        string hasNullVar)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}{element.TypeName} {targetVar};");
        if (element.Nullable)
        {
            sb.AppendLine($"{indent}if ({hasNullVar})");
            sb.AppendLine($"{indent}{{");
            sb.AppendLine($"{indent}    sbyte __foryRefFlag = context.Reader.ReadInt8();");
            sb.AppendLine($"{indent}    if (__foryRefFlag == (sbyte)global::Apache.Fory.RefFlag.Null)");
            sb.AppendLine($"{indent}    {{");
            sb.AppendLine($"{indent}        {targetVar} = ({element.TypeName})default!;");
            sb.AppendLine($"{indent}    }}");
            sb.AppendLine($"{indent}    else if (__foryRefFlag == (sbyte)global::Apache.Fory.RefFlag.NotNullValue)");
            sb.AppendLine($"{indent}    {{");
            string nullableNonNullVar = $"__foryNonNull{id++}";
            EmitReadPayload(sb, NonNullableCodec(element), nullableNonNullVar, indentLevel + 2, ref id);
            sb.AppendLine($"{indent}        {targetVar} = {nullableNonNullVar};");
            sb.AppendLine($"{indent}    }}");
            sb.AppendLine($"{indent}    else");
            sb.AppendLine($"{indent}    {{");
            sb.AppendLine($"{indent}        throw new global::Apache.Fory.InvalidDataException($\"invalid collection null flag {{__foryRefFlag}}\");");
            sb.AppendLine($"{indent}    }}");
            sb.AppendLine($"{indent}}}");
            sb.AppendLine($"{indent}else");
            sb.AppendLine($"{indent}{{");
            string nonNullVar = $"__foryNonNull{id++}";
            EmitReadPayload(sb, NonNullableCodec(element), nonNullVar, indentLevel + 1, ref id);
            sb.AppendLine($"{indent}    {targetVar} = {nonNullVar};");
            sb.AppendLine($"{indent}}}");
            return;
        }

        string directNonNullVar = $"__foryNonNull{id++}";
        EmitReadPayload(sb, element, directNonNullVar, indentLevel, ref id);
        sb.AppendLine($"{indent}{targetVar} = {directNonNullVar};");
    }

    private static void EmitReadMapPayload(
        StringBuilder sb,
        FieldCodecModel codec,
        string targetVar,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        FieldCodecModel key = codec.Generics[0];
        FieldCodecModel value = codec.Generics[1];
        string totalVar = $"__foryTotal{id++}";
        sb.AppendLine($"{indent}int {totalVar} = checked((int)context.Reader.ReadVarUInt32());");
        sb.AppendLine($"{indent}if ({totalVar} != 0)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    context.Reader.CheckBound({totalVar});");
        sb.AppendLine($"{indent}}}");
        sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new({totalVar});");
        sb.AppendLine($"{indent}int __foryRead = 0;");
        sb.AppendLine($"{indent}while (__foryRead < {totalVar})");
        sb.AppendLine($"{indent}{{");
        string innerIndent = indent + "    ";
        sb.AppendLine($"{innerIndent}byte __foryHeader = context.Reader.ReadUInt8();");
        sb.AppendLine($"{innerIndent}bool __foryKeyNull = (__foryHeader & 0b0000_0010) != 0;");
        sb.AppendLine($"{innerIndent}bool __foryKeyDeclared = (__foryHeader & 0b0000_0100) != 0;");
        sb.AppendLine($"{innerIndent}bool __foryValueNull = (__foryHeader & 0b0001_0000) != 0;");
        sb.AppendLine($"{innerIndent}bool __foryValueDeclared = (__foryHeader & 0b0010_0000) != 0;");
        sb.AppendLine($"{innerIndent}if (__foryKeyNull || __foryValueNull)");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    {key.TypeName} __foryKey = ({key.TypeName})default!;");
        sb.AppendLine($"{innerIndent}    {value.TypeName} __foryValue = ({value.TypeName})default!;");
        sb.AppendLine($"{innerIndent}    if (!__foryKeyNull)");
        sb.AppendLine($"{innerIndent}    {{");
        sb.AppendLine($"{innerIndent}        if (!__foryKeyDeclared)");
        sb.AppendLine($"{innerIndent}        {{");
        EmitReadInlineTypeInfo(sb, NonNullableCodec(key), indentLevel + 3, ref id);
        sb.AppendLine($"{innerIndent}        }}");
        EmitReadPayload(sb, NonNullableCodec(key), "__foryReadKey", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}        __foryKey = __foryReadKey;");
        sb.AppendLine($"{innerIndent}    }}");
        sb.AppendLine($"{innerIndent}    if (!__foryValueNull)");
        sb.AppendLine($"{innerIndent}    {{");
        sb.AppendLine($"{innerIndent}        if (!__foryValueDeclared)");
        sb.AppendLine($"{innerIndent}        {{");
        EmitReadInlineTypeInfo(sb, NonNullableCodec(value), indentLevel + 3, ref id);
        sb.AppendLine($"{innerIndent}        }}");
        EmitReadPayload(sb, NonNullableCodec(value), "__foryReadValue", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}        __foryValue = __foryReadValue;");
        sb.AppendLine($"{innerIndent}    }}");
        if (codec.CarrierKind == CarrierKind.NullableKeyDictionary)
        {
            sb.AppendLine($"{innerIndent}    {targetVar}[__foryKey] = __foryValue;");
        }
        else
        {
            sb.AppendLine($"{innerIndent}    if (!__foryKeyNull)");
            sb.AppendLine($"{innerIndent}    {{");
            sb.AppendLine($"{innerIndent}        {targetVar}[__foryKey] = __foryValue;");
            sb.AppendLine($"{innerIndent}    }}");
        }

        sb.AppendLine($"{innerIndent}    __foryRead++;");
        sb.AppendLine($"{innerIndent}    continue;");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}int __foryChunkSize = context.Reader.ReadUInt8();");
        sb.AppendLine($"{innerIndent}if (!__foryKeyDeclared)");
        sb.AppendLine($"{innerIndent}{{");
        EmitReadInlineTypeInfo(sb, NonNullableCodec(key), indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}if (!__foryValueDeclared)");
        sb.AppendLine($"{innerIndent}{{");
        EmitReadInlineTypeInfo(sb, NonNullableCodec(value), indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}}}");
        string mapIndexVar = $"__foryIndex{id++}";
        sb.AppendLine($"{innerIndent}for (int {mapIndexVar} = 0; {mapIndexVar} < __foryChunkSize; {mapIndexVar}++)");
        sb.AppendLine($"{innerIndent}{{");
        EmitReadPayload(sb, NonNullableCodec(key), "__foryKey", indentLevel + 2, ref id);
        EmitReadPayload(sb, NonNullableCodec(value), "__foryValue", indentLevel + 2, ref id);
        sb.AppendLine($"{innerIndent}    {targetVar}[__foryKey] = __foryValue;");
        sb.AppendLine($"{innerIndent}}}");
        sb.AppendLine($"{innerIndent}__foryRead += __foryChunkSize;");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitReadInlineTypeInfo(
        StringBuilder sb,
        FieldCodecModel codec,
        int indentLevel,
        ref int id)
    {
        string indent = new(' ', indentLevel * 4);
        if (!CanValidateInlineTypeInfo(codec.TypeId))
        {
            sb.AppendLine(
                $"{indent}throw new global::Apache.Fory.InvalidDataException(\"generated field value requires declared nested user type metadata\");");
            return;
        }

        string typeIdVar = $"__foryWireTypeId{id++}";
        sb.AppendLine($"{indent}uint {typeIdVar} = context.Reader.ReadUInt8();");
        sb.AppendLine($"{indent}if ({typeIdVar} != {codec.TypeId}u)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    throw new global::Apache.Fory.TypeMismatchException({codec.TypeId}u, {typeIdVar});");
        sb.AppendLine($"{indent}}}");
    }

    private static bool CanValidateInlineTypeInfo(uint typeId)
    {
        return typeId is > 0 and <= 24 or >= 36 and <= 56;
    }

    private static FieldCodecModel NonNullableCodec(FieldCodecModel codec)
    {
        if (!codec.Nullable)
        {
            return codec;
        }

        return new FieldCodecModel(
            codec.Kind,
            codec.TypeId,
            codec.NullableValueType && codec.TypeName.EndsWith("?", StringComparison.Ordinal)
                ? codec.TypeName.Substring(0, codec.TypeName.Length - 1)
                : codec.TypeName,
            false,
            false,
            codec.CarrierKind,
            codec.Generics);
    }

    private static MemberModel NonNullableMember(MemberModel member)
    {
        if (!member.IsNullable)
        {
            return member;
        }

        return new MemberModel(
            member.Name,
            member.FieldIdentifier,
            member.OriginalIndex,
            member.DeclKind,
            member.IsNullableValueType && member.TypeName.EndsWith("?", StringComparison.Ordinal)
                ? member.TypeName.Substring(0, member.TypeName.Length - 1)
                : StripNullableForTypeOf(member.TypeName),
            false,
            false,
            member.FieldId,
            member.Classification,
            member.Group,
            member.IsCollection,
            member.UseDictionaryTypeInfoCache,
            member.IsRefType,
            member.NeedsFieldTypeInfo,
            member.DynamicAnyKind,
            new TypeMetaFieldTypeModel(
                member.TypeMeta.TypeIdExpr,
                false,
                member.TypeMeta.TrackRefByContext,
                member.TypeMeta.Generics),
            member.FieldCodec is null ? null : NonNullableCodec(member.FieldCodec),
            member.HasSchemaType);
    }

    private static string ElementTypeName(string arrayTypeName)
    {
        return arrayTypeName.EndsWith("[]", StringComparison.Ordinal)
            ? arrayTypeName.Substring(0, arrayTypeName.Length - 2)
            : "object";
    }

    private static string PackedArrayElementTypeName(uint typeId)
    {
        return typeId switch
        {
            41 => "byte",
            43 => "bool",
            44 => "sbyte",
            45 => "short",
            46 => "int",
            47 => "long",
            48 => "byte",
            49 => "ushort",
            50 => "uint",
            51 => "ulong",
            53 => "global::System.Half",
            54 => "global::Apache.Fory.BFloat16",
            55 => "float",
            56 => "double",
            _ => throw new InvalidOperationException($"unsupported packed array type id {typeId}"),
        };
    }

    private static int PackedArrayElementWidth(uint typeId)
    {
        return typeId switch
        {
            41 or 43 or 44 or 48 => 1,
            45 or 49 or 53 or 54 => 2,
            46 or 50 or 55 => 4,
            47 or 51 or 56 => 8,
            _ => throw new InvalidOperationException($"unsupported packed array type id {typeId}"),
        };
    }

    private static uint PackedArrayElementTypeId(uint typeId)
    {
        return typeId switch
        {
            41 => 9,
            43 => 1,
            44 => 2,
            45 => 3,
            46 => 4,
            47 => 6,
            48 => 9,
            49 => 10,
            50 => 11,
            51 => 13,
            53 => 17,
            54 => 18,
            55 => 19,
            56 => 20,
            _ => throw new InvalidOperationException($"unsupported packed array type id {typeId}"),
        };
    }

    private static void EmitWriteMember(StringBuilder sb, MemberModel member, bool compatibleMode)
    {
        string refModeExpr = BuildWriteRefModeExpression(member);
        string memberAccess = $"value.{member.Name}";
        string hasGenerics = member.IsCollection ? "true" : "false";
        string writeTypeInfo = compatibleMode
            ? BuildFieldTypeInfoLiteral(member)
            : "false";

        switch (member.DynamicAnyKind)
        {
            case DynamicAnyKind.AnyValue:
                sb.AppendLine(
                    $"            global::Apache.Fory.DynamicAnyCodec.WriteAny(context, {memberAccess}, {refModeExpr}, true, false);");
                return;
            case DynamicAnyKind.None:
                break;
            default:
                throw new InvalidOperationException($"unsupported dynamic any kind {member.DynamicAnyKind}");
        }

        if (member.FieldCodec is not null)
        {
            sb.AppendLine(
                $"            __ForyWrite{Sanitize(member.Name)}Field(context, {memberAccess}, {refModeExpr});");
            return;
        }

        if (member.UseDictionaryTypeInfoCache)
        {
            EmitWriteDictionaryWithTypeInfoCache(
                sb,
                member,
                memberAccess,
                refModeExpr,
                writeTypeInfo,
                hasGenerics,
                compatibleMode);
            return;
        }

        if (!member.IsNullable && TryBuildDirectFieldWrite(member, memberAccess, out string? directWriteCode))
        {
            sb.AppendLine($"            {directWriteCode}");
            return;
        }

        if (TryBuildNullableFixedTaggedFieldWrite(member, memberAccess, out string? nullableWriteCode))
        {
            sb.AppendLine($"            {nullableWriteCode}");
            return;
        }

        if (writeTypeInfo == "false")
        {
            if (CanUseDirectWriteDataInvocation(member))
            {
                sb.AppendLine(
                    $"            context.TypeResolver.GetSerializer<{member.TypeName}>().WriteData(context, {memberAccess}, {hasGenerics});");
                return;
            }

            if (CanUseTrackRefBranchWriteDataInvocation(member))
            {
                sb.AppendLine("            if (context.TrackRef)");
                sb.AppendLine("            {");
                sb.AppendLine(
                    $"                context.TypeResolver.GetSerializer<{member.TypeName}>().Write(context, {memberAccess}, global::Apache.Fory.RefMode.Tracking, false, {hasGenerics});");
                sb.AppendLine("            }");
                sb.AppendLine("            else");
                sb.AppendLine("            {");
                sb.AppendLine(
                    $"                context.TypeResolver.GetSerializer<{member.TypeName}>().WriteData(context, {memberAccess}, {hasGenerics});");
                sb.AppendLine("            }");
                return;
            }
        }

        sb.AppendLine(
            $"            context.TypeResolver.GetSerializer<{member.TypeName}>().Write(context, {memberAccess}, {refModeExpr}, {writeTypeInfo}, {hasGenerics});");
    }

    private static void EmitWriteDictionaryWithTypeInfoCache(
        StringBuilder sb,
        MemberModel member,
        string memberAccess,
        string refModeExpr,
        string writeTypeInfo,
        string hasGenerics,
        bool compatibleMode)
    {
        string memberId = Sanitize(member.Name);
        string modeSuffix = compatibleMode ? "Compat" : "Schema";
        string fieldValueVar = $"__{memberId}DictValue{modeSuffix}";
        string runtimeTypeVar = $"__{memberId}DictRuntimeType{modeSuffix}";
        string typeInfoVar = $"__{memberId}DictTypeInfo{modeSuffix}";
        sb.AppendLine($"            {member.TypeName} {fieldValueVar} = {memberAccess};");
        sb.AppendLine($"            if ({fieldValueVar} is null)");
        sb.AppendLine("            {");
        sb.AppendLine(
            $"                context.TypeResolver.GetSerializer<{member.TypeName}>().Write(context, ({member.TypeName})null!, {refModeExpr}, {writeTypeInfo}, {hasGenerics});");
        sb.AppendLine("            }");
        sb.AppendLine("            else");
        sb.AppendLine("            {");
        sb.AppendLine($"                global::System.Type {runtimeTypeVar} = {fieldValueVar}.GetType();");
        sb.AppendLine($"                global::Apache.Fory.TypeInfo {typeInfoVar} = context.TypeResolver.GetTypeInfo({runtimeTypeVar});");
        sb.AppendLine(
            $"                context.TypeResolver.WriteObject({typeInfoVar}, context, {fieldValueVar}, {refModeExpr}, {writeTypeInfo}, {hasGenerics});");
        sb.AppendLine("            }");
    }

    private static void EmitReadMemberAssignment(
        StringBuilder sb,
        MemberModel member,
        string refModeExpr,
        string readTypeInfoExpr,
        string valueVar,
        string variableSuffix,
        int indentLevel,
        bool allowDirectRead)
    {
        string indent = new(' ', indentLevel * 2);
        string assignmentTarget = $"{valueVar}.{member.Name}";
        string typeOfTypeName = StripNullableForTypeOf(member.TypeName);
        switch (member.DynamicAnyKind)
        {
            case DynamicAnyKind.AnyValue:
                sb.AppendLine(
                    $"{indent}{assignmentTarget} = ({member.TypeName})global::Apache.Fory.DynamicAnyCodec.CastAnyDynamicValue(global::Apache.Fory.DynamicAnyCodec.ReadAny(context, {refModeExpr}, true), typeof({typeOfTypeName}))!;");
                return;
            case DynamicAnyKind.None:
                break;
            default:
                throw new InvalidOperationException($"unsupported dynamic any kind {member.DynamicAnyKind}");
        }

        if (variableSuffix == "Compat" &&
            TryBuildCompatibleScalarReadExpression(member, out string? compatibleScalarReadExpr))
        {
            sb.AppendLine($"{indent}{assignmentTarget} = {compatibleScalarReadExpr};");
            return;
        }

        if (member.FieldCodec is not null)
        {
            if (variableSuffix == "Compat" &&
                CanReadCompatibleField(member.FieldCodec))
            {
                sb.AppendLine(
                    $"{indent}{assignmentTarget} = __ForyCompatibleFieldReaders.Read{Sanitize(member.Name)}FieldBridge(context, remoteField.FieldType, {refModeExpr});");
            }
            else
            {
                sb.AppendLine(
                    $"{indent}{assignmentTarget} = __ForyRead{Sanitize(member.Name)}Field(context, {refModeExpr});");
            }

            return;
        }

        if (allowDirectRead && !member.IsNullable && TryBuildDirectFieldRead(member, out string? directReadExpr))
        {
            sb.AppendLine($"{indent}{assignmentTarget} = {directReadExpr};");
            return;
        }

        if (allowDirectRead && TryBuildNullableFixedTaggedFieldRead(member, assignmentTarget, variableSuffix, indent, out string? nullableReadCode))
        {
            sb.AppendLine(nullableReadCode);
            return;
        }

        if (variableSuffix == "Compat")
        {
            sb.AppendLine(
                $"{indent}{assignmentTarget} = context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, {readTypeInfoExpr});");
            return;
        }

        sb.AppendLine(
            $"{indent}{assignmentTarget} = context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, {readTypeInfoExpr});");
    }

    private static bool CompatibleCaseNeedsRemoteRefMode(MemberModel member)
    {
        return !IsCompatibleScalarMember(member);
    }

    private static bool IsCompatibleScalarMember(MemberModel member)
    {
        return TryResolveCompatibleScalarTarget(member, out _);
    }

    private static bool TryBuildCompatibleScalarReadExpression(MemberModel member, out string? readExpr)
    {
        readExpr = null;
        if (!TryResolveCompatibleScalarTarget(member, out string? methodTarget))
        {
            return false;
        }

        string methodName = member.IsNullable ? $"ReadNullable{methodTarget}Field" : $"Read{methodTarget}Field";
        readExpr =
            $"global::Apache.Fory.CompatibleScalarConverter.{methodName}(context, remoteField)";
        return true;
    }

    private static bool TryResolveCompatibleScalarTarget(MemberModel member, out string? methodTarget)
    {
        methodTarget = null;
        if (member.DynamicAnyKind != DynamicAnyKind.None ||
            !IsCompatibleScalarTypeId(member.Classification.TypeId))
        {
            return false;
        }

        string targetName = StripNullableForTypeOf(member.TypeName);
        methodTarget = targetName switch
        {
            "bool" or "global::System.Boolean" => "Bool",
            "sbyte" or "global::System.SByte" => "SByte",
            "short" or "global::System.Int16" => "Int16",
            "int" or "global::System.Int32" => "Int32",
            "long" or "global::System.Int64" => "Int64",
            "byte" or "global::System.Byte" => "Byte",
            "ushort" or "global::System.UInt16" => "UInt16",
            "uint" or "global::System.UInt32" => "UInt32",
            "ulong" or "global::System.UInt64" => "UInt64",
            "global::System.Half" => "Half",
            "global::Apache.Fory.BFloat16" => "BFloat16",
            "float" or "global::System.Single" => "Float",
            "double" or "global::System.Double" => "Double",
            "string" or "global::System.String" => "String",
            "decimal" or "global::System.Decimal" => "Decimal",
            "global::Apache.Fory.ForyDecimal" => "ForyDecimal",
            _ => null,
        };

        return methodTarget is not null;
    }

    private static bool IsCompatibleScalarTypeId(uint typeId)
    {
        return typeId is >= 1 and <= 15 or >= 17 and <= 21 or 40;
    }

    private static string StripNullableForTypeOf(string typeName)
    {
        return typeName.Replace("?", string.Empty);
    }

    private static bool TryBuildDirectFieldWrite(MemberModel member, string memberAccess, out string? writeCode)
    {
        writeCode = null;
        if (!CanUseDirectBuiltInFieldAccess(member))
        {
            return false;
        }

        return TryBuildDirectPayloadWrite(member.Classification.TypeId, memberAccess, out writeCode);
    }

    private static bool TryBuildDirectFieldRead(MemberModel member, out string? readExpr)
    {
        readExpr = null;
        if (!CanUseDirectBuiltInFieldAccess(member))
        {
            return false;
        }

        return TryBuildDirectPayloadRead(member.Classification.TypeId, out readExpr);
    }

    private static bool TryBuildNullableFixedTaggedFieldWrite(MemberModel member, string memberAccess, out string? writeCode)
    {
        writeCode = null;
        if (!member.IsNullableValueType || !IsFixedTaggedTypeId(member.Classification.TypeId))
        {
            return false;
        }

        if (!TryBuildDirectPayloadWrite(member.Classification.TypeId, $"{memberAccess}.Value", out string? payloadWriteCode))
        {
            return false;
        }

        writeCode = $"if (!{memberAccess}.HasValue) {{ context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.Null); }} else {{ context.Writer.WriteInt8((sbyte)global::Apache.Fory.RefFlag.NotNullValue); {payloadWriteCode} }}";
        return true;
    }

    private static bool TryBuildNullableFixedTaggedFieldRead(
        MemberModel member,
        string assignmentTarget,
        string variableSuffix,
        string indent,
        out string code)
    {
        code = string.Empty;
        if (!member.IsNullableValueType || !IsFixedTaggedTypeId(member.Classification.TypeId))
        {
            return false;
        }

        if (!TryBuildDirectPayloadRead(member.Classification.TypeId, out string? payloadReadExpr))
        {
            return false;
        }

        string refFlagVar = $"__{member.Name}RefFlag{variableSuffix}";
        string nestedIndent = indent + "  ";
        StringBuilder sb = new();
        sb.AppendLine($"{indent}sbyte {refFlagVar} = context.Reader.ReadInt8();");
        sb.AppendLine($"{indent}if ({refFlagVar} == (sbyte)global::Apache.Fory.RefFlag.Null)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{nestedIndent}{assignmentTarget} = ({member.TypeName})null!;");
        sb.AppendLine($"{indent}}}");
        sb.AppendLine($"{indent}else");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{nestedIndent}{assignmentTarget} = {payloadReadExpr};");
        sb.Append($"{indent}}}");
        code = sb.ToString();
        return true;
    }

    private static bool IsFixedTaggedTypeId(uint typeId)
    {
        return typeId is 4 or 6 or 8 or 11 or 13 or 15;
    }

    private static bool TryBuildDirectPayloadWrite(uint typeId, string valueExpr, out string? writeCode)
    {
        writeCode = null;
        switch (typeId)
        {
            case 1:
                writeCode = $"context.Writer.WriteUInt8({valueExpr} ? (byte)1 : (byte)0);";
                return true;
            case 2:
                writeCode = $"context.Writer.WriteInt8({valueExpr});";
                return true;
            case 3:
                writeCode = $"context.Writer.WriteInt16({valueExpr});";
                return true;
            case 4:
                writeCode = $"context.Writer.WriteInt32({valueExpr});";
                return true;
            case 5:
                writeCode = $"context.Writer.WriteVarInt32({valueExpr});";
                return true;
            case 6:
                writeCode = $"context.Writer.WriteInt64({valueExpr});";
                return true;
            case 7:
                writeCode = $"context.Writer.WriteVarInt64({valueExpr});";
                return true;
            case 8:
                writeCode = $"context.Writer.WriteTaggedInt64({valueExpr});";
                return true;
            case 9:
                writeCode = $"context.Writer.WriteUInt8({valueExpr});";
                return true;
            case 10:
                writeCode = $"context.Writer.WriteUInt16({valueExpr});";
                return true;
            case 11:
                writeCode = $"context.Writer.WriteUInt32({valueExpr});";
                return true;
            case 12:
                writeCode = $"context.Writer.WriteVarUInt32({valueExpr});";
                return true;
            case 13:
                writeCode = $"context.Writer.WriteUInt64({valueExpr});";
                return true;
            case 14:
                writeCode = $"context.Writer.WriteVarUInt64({valueExpr});";
                return true;
            case 15:
                writeCode = $"context.Writer.WriteTaggedUInt64({valueExpr});";
                return true;
            case 17:
                writeCode = $"context.Writer.WriteUInt16(global::System.BitConverter.HalfToUInt16Bits({valueExpr}));";
                return true;
            case 18:
                writeCode = $"context.Writer.WriteUInt16({valueExpr}.ToBits());";
                return true;
            case 19:
                writeCode = $"context.Writer.WriteFloat32({valueExpr});";
                return true;
            case 20:
                writeCode = $"context.Writer.WriteFloat64({valueExpr});";
                return true;
            case 21:
                writeCode = $"global::Apache.Fory.StringSerializer.WriteString(context, {valueExpr});";
                return true;
            default:
                return false;
        }
    }

    private static bool TryBuildDirectPayloadRead(uint typeId, out string? readExpr)
    {
        readExpr = null;
        switch (typeId)
        {
            case 1:
                readExpr = "context.Reader.ReadUInt8() != 0";
                return true;
            case 2:
                readExpr = "context.Reader.ReadInt8()";
                return true;
            case 3:
                readExpr = "context.Reader.ReadInt16()";
                return true;
            case 4:
                readExpr = "context.Reader.ReadInt32()";
                return true;
            case 5:
                readExpr = "context.Reader.ReadVarInt32()";
                return true;
            case 6:
                readExpr = "context.Reader.ReadInt64()";
                return true;
            case 7:
                readExpr = "context.Reader.ReadVarInt64()";
                return true;
            case 8:
                readExpr = "context.Reader.ReadTaggedInt64()";
                return true;
            case 9:
                readExpr = "context.Reader.ReadUInt8()";
                return true;
            case 10:
                readExpr = "context.Reader.ReadUInt16()";
                return true;
            case 11:
                readExpr = "context.Reader.ReadUInt32()";
                return true;
            case 12:
                readExpr = "context.Reader.ReadVarUInt32()";
                return true;
            case 13:
                readExpr = "context.Reader.ReadUInt64()";
                return true;
            case 14:
                readExpr = "context.Reader.ReadVarUInt64()";
                return true;
            case 15:
                readExpr = "context.Reader.ReadTaggedUInt64()";
                return true;
            case 17:
                readExpr = "global::System.BitConverter.UInt16BitsToHalf(context.Reader.ReadUInt16())";
                return true;
            case 18:
                readExpr = "global::Apache.Fory.BFloat16.FromBits(context.Reader.ReadUInt16())";
                return true;
            case 19:
                readExpr = "context.Reader.ReadFloat32()";
                return true;
            case 20:
                readExpr = "context.Reader.ReadFloat64()";
                return true;
            case 21:
                readExpr = "global::Apache.Fory.StringSerializer.ReadString(context)";
                return true;
            default:
                return false;
        }
    }

    private static bool CanUseDirectBuiltInFieldAccess(MemberModel member)
    {
        if (member.IsNullable ||
            member.DynamicAnyKind != DynamicAnyKind.None ||
            member.IsCollection ||
            member.Classification.IsMap)
        {
            return false;
        }

        return member.Classification.IsPrimitive || member.Classification.TypeId == 21;
    }

    private static bool CanUseDirectWriteDataInvocation(MemberModel member)
    {
        if (member.IsNullable || member.DynamicAnyKind != DynamicAnyKind.None)
        {
            return false;
        }

        return member.Classification.IsBuiltIn || !member.IsRefType;
    }

    private static bool CanUseTrackRefBranchWriteDataInvocation(MemberModel member)
    {
        if (member.IsNullable || member.DynamicAnyKind != DynamicAnyKind.None)
        {
            return false;
        }

        return !member.Classification.IsBuiltIn && member.IsRefType;
    }

    private static string BuildSchemaFingerprintExpression(ImmutableArray<MemberModel> members)
    {
        if (members.IsDefaultOrEmpty)
        {
            return "\"\"";
        }

        IEnumerable<MemberModel> ordered = members
            .OrderBy(m => m.FieldId.HasValue ? 0 : 1)
            .ThenBy(m => m.FieldId.GetValueOrDefault())
            .ThenBy(m => m.FieldIdentifier, StringComparer.Ordinal)
            .ThenBy(m => m.OriginalIndex);

        StringBuilder sb = new();
        bool first = true;
        foreach (MemberModel member in ordered)
        {
            string piece =
                $"\"{EscapeString(BuildSchemaFieldIdentifier(member))},\" + {BuildSchemaFieldTypeFingerprintExpression(member.TypeMeta, "trackRef", includeNullable: true)} + \";\"";
            if (!first)
            {
                sb.Append(" + ");
            }

            first = false;
            sb.Append(piece);
        }

        return sb.ToString().Replace("b_float16", "bfloat16");
    }

    private static string BuildSchemaFieldIdentifier(MemberModel member)
    {
        return member.FieldId.HasValue
            ? member.FieldId.Value.ToString(CultureInfo.InvariantCulture)
            : member.FieldIdentifier;
    }

    private static string BuildSchemaFieldTypeFingerprintExpression(
        TypeMetaFieldTypeModel model,
        string trackRefExpr,
        bool includeNullable)
    {
        string localTrackRefExpr = model.TrackRefByContext
            ? $"({trackRefExpr} ? 1 : 0)"
            : "0";
        string prefix =
            $"\"{NormalizeSchemaFingerprintTypeId(model.TypeIdExpr).ToString(CultureInfo.InvariantCulture)},\" + {localTrackRefExpr} + \","
            + (includeNullable && model.Nullable ? "1" : "0")
            + "\"";
        if (model.Generics.Length == 0)
        {
            return prefix;
        }

        if (model.Generics.Length == 1)
        {
            string child = BuildSchemaFieldTypeFingerprintExpression(model.Generics[0], "false", includeNullable: false);
            return $"{prefix} + \"[\" + {child} + \"]\"";
        }

        if (model.Generics.Length == 2)
        {
            string key = BuildSchemaFieldTypeFingerprintExpression(model.Generics[0], "false", includeNullable: false);
            string value = BuildSchemaFieldTypeFingerprintExpression(model.Generics[1], "false", includeNullable: false);
            return $"{prefix} + \"[\" + {key} + \"|\" + {value} + \"]\"";
        }

        throw new InvalidOperationException("schema fingerprint supports only list/set/map generic arity");
    }

    private static uint NormalizeSchemaFingerprintTypeId(string typeIdExpr)
    {
        if (!TryParseSchemaFingerprintTypeId(typeIdExpr, out uint typeId))
        {
            throw new InvalidOperationException($"unsupported schema fingerprint type id expression {typeIdExpr}");
        }

        return typeId switch
        {
            0 or 25 or 26 or 27 or 28 or 29 or 30 or 31 or 32 or 33 or 34 or 35 => 0,
            _ => typeId,
        };
    }

    private static bool TryParseSchemaFingerprintTypeId(string typeIdExpr, out uint typeId)
    {
        string normalized = typeIdExpr.Replace(" ", string.Empty);
        if (normalized.StartsWith("(uint)", StringComparison.Ordinal))
        {
            normalized = normalized.Substring(6);
        }

        if (uint.TryParse(normalized, NumberStyles.None, CultureInfo.InvariantCulture, out typeId))
        {
            return true;
        }

        switch (normalized)
        {
            case "global::Apache.Fory.TypeId.Unknown":
                typeId = 0;
                return true;
            case "global::Apache.Fory.TypeId.List":
                typeId = 22;
                return true;
            case "global::Apache.Fory.TypeId.Set":
                typeId = 23;
                return true;
            case "global::Apache.Fory.TypeId.Map":
                typeId = 24;
                return true;
            case "global::Apache.Fory.TypeId.Enum":
                typeId = 25;
                return true;
            case "global::Apache.Fory.TypeId.Union":
                typeId = 33;
                return true;
            default:
                typeId = 0;
                return false;
        }
    }

    private static string BuildTypeMetaExpression(TypeMetaFieldTypeModel model, string trackRefExpr)
    {
        string localTrackRefExpr = model.TrackRefByContext ? trackRefExpr : "false";
        if (model.Generics.Length > 0)
        {
            string generics = string.Join(
                ", ",
                model.Generics.Select(g => BuildTypeMetaExpression(g, trackRefExpr)));
            return
                $"new global::Apache.Fory.TypeMetaFieldType({model.TypeIdExpr}, {BoolLiteral(model.Nullable)}, {localTrackRefExpr}, new global::Apache.Fory.TypeMetaFieldType[] {{ {generics} }})";
        }

        return $"new global::Apache.Fory.TypeMetaFieldType({model.TypeIdExpr}, {BoolLiteral(model.Nullable)}, {localTrackRefExpr})";
    }

    private static string BuildTypeMetaFieldIdExpression(short? fieldId)
    {
        return fieldId.HasValue ? $"(short){fieldId.Value}" : "null";
    }

    private static string BuildWriteRefModeExpression(MemberModel member)
    {
        return member.DynamicAnyKind switch
        {
            DynamicAnyKind.AnyValue => $"__ForyRefMode({BoolLiteral(member.IsNullable)}, context.TrackRef)",
            _ => member.Classification.IsBuiltIn || !member.IsRefType
                ? $"__ForyRefMode({BoolLiteral(member.IsNullable)}, false)"
                : $"__ForyRefMode({BoolLiteral(member.IsNullable)}, context.TrackRef)",
        };
    }

    private static string BuildUnionCaseRefModeExpression(MemberModel member)
    {
        return member.IsRefType
            ? "__ForyRefMode(true, context.TrackRef)"
            : "global::Apache.Fory.RefMode.NullOnly";
    }

    private static string BuildFieldTypeInfoLiteral(MemberModel member)
    {
        return BoolLiteral(member.NeedsFieldTypeInfo);
    }

    private static TypeModel? BuildTypeModel(GeneratorSyntaxContext context, CancellationToken cancellationToken)
    {
        _ = cancellationToken;
        if (context.SemanticModel.GetDeclaredSymbol(context.Node, cancellationToken) is not INamedTypeSymbol typeSymbol)
        {
            return null;
        }

        ForyAttributeKind attributeKind = GetForyAttributeKind(typeSymbol);
        if (attributeKind == ForyAttributeKind.None)
        {
            return null;
        }

        string typeName = typeSymbol.ToDisplayString(FullNameFormat);
        if (typeSymbol.TypeParameters.Length > 0)
        {
            return new TypeModel(
                typeName,
                string.Empty,
                DeclKind.Unknown,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    GenericTypeNotSupported,
                    typeSymbol.Locations.FirstOrDefault(),
                    typeName)));
        }

        string serializerName = "__ForySerializer_" + Sanitize(typeSymbol.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat));
        if (attributeKind == ForyAttributeKind.Enum)
        {
            if (typeSymbol.TypeKind != TypeKind.Enum)
            {
                return null;
            }

            return new TypeModel(
                typeName,
                serializerName,
                DeclKind.Enum,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<Diagnostic>.Empty);
        }

        if (attributeKind == ForyAttributeKind.Union)
        {
            if (typeSymbol.TypeKind != TypeKind.Class)
            {
                return null;
            }

            List<Diagnostic> unionDiagnostics = [];
            ImmutableArray<UnionCaseModel> unionCases = BuildUnionCases(typeSymbol, unionDiagnostics);
            if (unionCases.IsEmpty)
            {
                return new TypeModel(
                    typeName,
                    serializerName,
                    DeclKind.Union,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray.Create(Diagnostic.Create(
                        InvalidUnionType,
                        typeSymbol.Locations.FirstOrDefault(),
                        typeName)));
            }

            return new TypeModel(
                typeName,
                serializerName,
                DeclKind.Union,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                unionDiagnostics.ToImmutableArray(),
                unionCases);
        }

        DeclKind kind = typeSymbol.TypeKind switch
        {
            TypeKind.Struct => DeclKind.Struct,
            TypeKind.Class => DeclKind.Class,
            _ => DeclKind.Unknown,
        };

        if (kind == DeclKind.Unknown)
        {
            return null;
        }

        if (kind == DeclKind.Class && !HasAccessibleParameterlessCtor(typeSymbol))
        {
            return new TypeModel(
                typeName,
                serializerName,
                kind,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    MissingCtor,
                    typeSymbol.Locations.FirstOrDefault(),
                    typeName)));
        }

        List<Diagnostic> diagnostics = [];
        List<MemberModel> members = [];
        foreach (ISymbol member in typeSymbol.GetMembers())
        {
            if (member.IsStatic)
            {
                continue;
            }

            if (member is IFieldSymbol field)
            {
                if (field.IsConst || field.IsReadOnly || !IsReadableWritableAccessibility(field.DeclaredAccessibility))
                {
                    continue;
                }

                MemberModel? parsedField = BuildMemberModel(field.Name, field.Type, field, MemberDeclKind.Field, diagnostics);
                if (parsedField is not null)
                {
                    members.Add(parsedField);
                }

                continue;
            }

            if (member is IPropertySymbol property)
            {
                if (property.IsIndexer || property.GetMethod is null || property.SetMethod is null)
                {
                    continue;
                }

                if (property.SetMethod.IsInitOnly)
                {
                    continue;
                }

                if (!IsReadableWritableAccessibility(property.GetMethod.DeclaredAccessibility) ||
                    !IsReadableWritableAccessibility(property.SetMethod.DeclaredAccessibility))
                {
                    continue;
                }

                MemberModel? parsedProperty = BuildMemberModel(
                    property.Name,
                    property.Type,
                    property,
                    MemberDeclKind.Property,
                    diagnostics);
                if (parsedProperty is not null)
                {
                    members.Add(parsedProperty);
                }
            }
        }

        ImmutableArray<MemberModel> ordered = members
            .OrderBy(m => m.OriginalIndex)
            .ToImmutableArray();
        ImmutableArray<MemberModel> sorted = SortMembers(ordered);

        return new TypeModel(typeName, serializerName, kind, ordered, sorted, diagnostics.ToImmutableArray());
    }

    private static ImmutableArray<UnionCaseModel> BuildUnionCases(
        INamedTypeSymbol unionType,
        List<Diagnostic> diagnostics)
    {
        List<UnionCaseModel> cases = [];
        HashSet<int> caseIds = [];
        foreach (INamedTypeSymbol caseType in unionType.GetTypeMembers())
        {
            bool isUnknown = HasForyUnknownCase(caseType);
            if (!TryGetForyCase(caseType, diagnostics, out int caseId, out SchemaTypeModel? schemaType))
            {
                if (isUnknown)
                {
                    string unknownCaseTypeName = caseType.ToDisplayString(FullNameFormat);
                    if (!SymbolEqualityComparer.Default.Equals(caseType.BaseType, unionType))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidUnionCase,
                            caseType.Locations.FirstOrDefault(),
                            unknownCaseTypeName,
                            "unknown case type must directly derive from the annotated union root"));
                        continue;
                    }

                    if (!string.Equals(caseType.Name, "Unknown", StringComparison.Ordinal) ||
                        !HasUnknownCaseValueProperty(caseType))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidUnionCase,
                            caseType.Locations.FirstOrDefault(),
                            unknownCaseTypeName,
                            "unknown case must be named Unknown and expose Value:UnknownCase"));
                        continue;
                    }

                    cases.Add(new UnionCaseModel(null, unknownCaseTypeName, isUnknown: true, valueMember: null));
                }

                continue;
            }

            if (isUnknown)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "unknown case must use [ForyUnknownCase] without [ForyCase]"));
                continue;
            }

            if (!caseIds.Add(caseId))
            {
                diagnostics.Add(Diagnostic.Create(
                    DuplicateUnionCaseId,
                    caseType.Locations.FirstOrDefault(),
                    caseId,
                    unionType.ToDisplayString(FullNameFormat)));
                continue;
            }

            if (!SymbolEqualityComparer.Default.Equals(caseType.BaseType, unionType))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "case type must directly derive from the annotated union root"));
                continue;
            }

            string caseTypeName = caseType.ToDisplayString(FullNameFormat);
            IPropertySymbol? valueProperty = FindProperty(caseType, "Value");
            if (valueProperty is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseTypeName,
                    "known cases must expose a Value property"));
                continue;
            }

            MemberModel? valueMember = BuildMemberModel(
                valueProperty.Name,
                valueProperty.Type,
                valueProperty,
                MemberDeclKind.Property,
                diagnostics,
                schemaType,
                parseFieldAttribute: false);
            if (valueMember is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    valueProperty.Locations.FirstOrDefault(),
                    caseTypeName,
                    "case Value type is not supported"));
                continue;
            }

            cases.Add(new UnionCaseModel(caseId, caseTypeName, isUnknown: false, valueMember));
        }

        if (cases.Count(c => c.IsUnknown) > 1)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare exactly one [ForyUnknownCase] Unknown"));
        }
        else if (!cases.Any(c => c.IsUnknown))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare [ForyUnknownCase] Unknown"));
        }
        else if (!cases.Any(c => !c.IsUnknown))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare at least one non-Unknown case; Unknown is a forward-compatibility carrier and cannot be the default"));
        }

        return cases
            .OrderBy(c => c.CaseId ?? -1)
            .ToImmutableArray();
    }

    private static IEnumerable<UnionCaseModel> KnownUnionCases(TypeModel model)
    {
        return model.UnionCases
            .Where(c => !c.IsUnknown)
            .OrderBy(c => c.KnownCaseId);
    }

    private static bool TryGetForyCase(
        INamedTypeSymbol caseType,
        List<Diagnostic> diagnostics,
        out int caseId,
        out SchemaTypeModel? schemaType)
    {
        caseId = default;
        schemaType = null;
        foreach (AttributeData attribute in caseType.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            if (!string.Equals(attrName, "Apache.Fory.ForyCaseAttribute", StringComparison.Ordinal))
            {
                continue;
            }

            if (attribute.ConstructorArguments.Length != 1 ||
                !TryGetUnionCaseId(attribute.ConstructorArguments[0], out caseId))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "case id must be a non-negative int"));
                return true;
            }

            foreach (KeyValuePair<string, TypedConstant> namedArg in attribute.NamedArguments)
            {
                if (!string.Equals(namedArg.Key, "Type", StringComparison.Ordinal))
                {
                    continue;
                }

                if (namedArg.Value.Value is not ITypeSymbol schemaSymbol ||
                    TryParseSchemaType(schemaSymbol) is not SchemaTypeModel parsedSchema)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidUnionCase,
                        caseType.Locations.FirstOrDefault(),
                        caseType.ToDisplayString(FullNameFormat),
                        "ForyCase.Type must be an Apache.Fory.Schema.Types descriptor"));
                    continue;
                }

                schemaType = parsedSchema;
            }

            return true;
        }

        return false;
    }

    private static bool TryGetUnionCaseId(TypedConstant value, out int caseId)
    {
        caseId = default;
        if (value.Value is int id && id >= 0)
        {
            caseId = id;
            return true;
        }

        return false;
    }

    private static bool HasForyUnknownCase(INamedTypeSymbol caseType)
    {
        foreach (AttributeData attribute in caseType.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            if (string.Equals(attrName, "Apache.Fory.ForyUnknownCaseAttribute", StringComparison.Ordinal))
            {
                return true;
            }
        }

        return false;
    }

    private static IPropertySymbol? FindProperty(INamedTypeSymbol type, string name)
    {
        foreach (ISymbol member in type.GetMembers(name))
        {
            if (member is IPropertySymbol property && !property.IsStatic)
            {
                return property;
            }
        }

        return null;
    }

    private static bool HasUnknownCaseValueProperty(INamedTypeSymbol type)
    {
        IPropertySymbol? property = FindProperty(type, "Value");
        return property is not null &&
               string.Equals(
                   property.Type.ToDisplayString(FullNameFormat),
                   "global::Apache.Fory.UnknownCase",
                   StringComparison.Ordinal);
    }

    private static ForyAttributeKind GetForyAttributeKind(INamedTypeSymbol typeSymbol)
    {
        foreach (AttributeData attribute in typeSymbol.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            if (string.Equals(attrName, "Apache.Fory.ForyStructAttribute", StringComparison.Ordinal))
            {
                return ForyAttributeKind.Struct;
            }

            if (string.Equals(attrName, "Apache.Fory.ForyEnumAttribute", StringComparison.Ordinal))
            {
                return ForyAttributeKind.Enum;
            }

            if (string.Equals(attrName, "Apache.Fory.ForyUnionAttribute", StringComparison.Ordinal))
            {
                return ForyAttributeKind.Union;
            }
        }

        return ForyAttributeKind.None;
    }

    private static MemberModel? BuildMemberModel(
        string name,
        ITypeSymbol memberType,
        ISymbol memberSymbol,
        MemberDeclKind memberDeclKind,
        List<Diagnostic> diagnostics)
    {
        return BuildMemberModel(
            name,
            memberType,
            memberSymbol,
            memberDeclKind,
            diagnostics,
            schemaTypeOverride: null,
            parseFieldAttribute: true);
    }

    private static MemberModel? BuildMemberModel(
        string name,
        ITypeSymbol memberType,
        ISymbol memberSymbol,
        MemberDeclKind memberDeclKind,
        List<Diagnostic> diagnostics,
        SchemaTypeModel? schemaTypeOverride,
        bool parseFieldAttribute)
    {
        (bool isOptional, ITypeSymbol unwrappedType) = UnwrapNullable(memberType);
        short? fieldId = null;
        SchemaTypeModel? schemaType = schemaTypeOverride;
        if (parseFieldAttribute)
        {
            foreach (AttributeData attribute in memberSymbol.GetAttributes())
            {
                string? attrName = attribute.AttributeClass?.ToDisplayString();
                if (!string.Equals(attrName, "Apache.Fory.ForyFieldAttribute", StringComparison.Ordinal))
                {
                    continue;
                }

                if (attribute.ConstructorArguments.Length == 1 &&
                    TryGetFieldId(attribute.ConstructorArguments[0], memberSymbol, diagnostics, out short ctorFieldId))
                {
                    fieldId = ctorFieldId;
                }

                foreach (KeyValuePair<string, TypedConstant> namedArg in attribute.NamedArguments)
                {
                    if (string.Equals(namedArg.Key, "Id", StringComparison.Ordinal))
                    {
                        if (TryGetFieldId(namedArg.Value, memberSymbol, diagnostics, out short parsedFieldId))
                        {
                            fieldId = parsedFieldId;
                        }

                        continue;
                    }

                    if (!string.Equals(namedArg.Key, "Type", StringComparison.Ordinal))
                    {
                        continue;
                    }

                    if (namedArg.Value.Value is ITypeSymbol schemaSymbol)
                    {
                        schemaType = TryParseSchemaType(schemaSymbol);
                    }
                }
            }
        }

        DynamicAnyKind dynamicAnyKind = ResolveDynamicAnyKind(unwrappedType);
        TypeResolution resolution = ResolveTypeResolution(unwrappedType, schemaType);
        if (!resolution.Supported)
        {
            return null;
        }

        TypeClassification classification = resolution.Classification;
        int group = classification.IsPrimitive
            ? (isOptional ? 2 : 1)
            : 3;

        int index = int.MaxValue;
        Location? sourceLocation = memberSymbol.Locations.FirstOrDefault(loc => loc.IsInSource);
        if (sourceLocation is not null)
        {
            index = sourceLocation.SourceSpan.Start;
        }

        string typeName = memberType.ToDisplayString(FullNameFormat);
        TypeMetaFieldTypeModel typeMeta = BuildTypeMetaFieldTypeModel(
            memberType,
            isOptional,
            dynamicAnyKind,
            resolution.Classification.TypeId,
            schemaType);
        FieldCodecModel? fieldCodec = BuildFieldCodecModel(memberType, typeMeta, schemaType, classification);

        return new MemberModel(
            name,
            ToSnakeCase(name),
            index,
            memberDeclKind,
            typeName,
            isOptional,
            memberType is INamedTypeSymbol nts &&
            nts.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
            fieldId,
            classification,
            group,
            classification.IsCollection || classification.IsMap,
            classification.IsMap && !IsTypeSealed(unwrappedType),
            !unwrappedType.IsValueType && classification.TypeId != 21,
            FieldNeedsTypeInfo(classification, dynamicAnyKind, unwrappedType),
            dynamicAnyKind == DynamicAnyKind.None ? DynamicAnyKind.None : dynamicAnyKind,
            typeMeta,
            fieldCodec,
            schemaType is not null);
    }

    private static TypeMetaFieldTypeModel BuildTypeMetaFieldTypeModel(
        ITypeSymbol memberType,
        bool nullable,
        DynamicAnyKind dynamicAnyKind,
        uint explicitTypeId,
        SchemaTypeModel? schemaType = null)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(memberType);

        if (schemaType is not null)
        {
            return BuildSchemaTypeMetaFieldTypeModel(memberType, nullable, schemaType);
        }

        if (unwrapped is IArrayTypeSymbol &&
            ClassifyType(unwrapped) is { TypeId: not 22 } arrayClassification &&
            IsPackedArrayTypeId(arrayClassification.TypeId))
        {
            return new TypeMetaFieldTypeModel(
                $"(uint){arrayClassification.TypeId}",
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (TryGetListElementType(unwrapped, out ITypeSymbol? listElementType))
        {
            bool elementNullable = GenericNullable(listElementType!);
            TypeMetaFieldTypeModel element = BuildTypeMetaFieldTypeModel(
                listElementType!,
                elementNullable,
                ResolveDynamicAnyKind(UnwrapNullable(listElementType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.List",
                nullable,
                false,
                ImmutableArray.Create(element));
        }

        if (TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType))
        {
            bool elementNullable = GenericNullable(setElementType!);
            TypeMetaFieldTypeModel element = BuildTypeMetaFieldTypeModel(
                setElementType!,
                elementNullable,
                ResolveDynamicAnyKind(UnwrapNullable(setElementType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Set",
                nullable,
                false,
                ImmutableArray.Create(element));
        }

        if (TryGetMapTypeArguments(unwrapped, out ITypeSymbol? keyType, out ITypeSymbol? valueType))
        {
            bool keyNullable = GenericNullable(keyType!);
            bool valueNullable = GenericNullable(valueType!);
            TypeMetaFieldTypeModel key = BuildTypeMetaFieldTypeModel(
                keyType!,
                keyNullable,
                ResolveDynamicAnyKind(UnwrapNullable(keyType!).Item2),
                0);
            TypeMetaFieldTypeModel value = BuildTypeMetaFieldTypeModel(
                valueType!,
                valueNullable,
                ResolveDynamicAnyKind(UnwrapNullable(valueType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Map",
                nullable,
                false,
                ImmutableArray.Create(key, value));
        }

        TypeClassification classification = ClassifyType(unwrapped);
        if (explicitTypeId != 0 && classification.IsPrimitive && classification.TypeId != explicitTypeId)
        {
            return new TypeMetaFieldTypeModel(
                explicitTypeId.ToString(),
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (IsUnionType(unwrapped))
        {
            // The field owner supplies the union schema, so static union fields
            // must use UNION. TYPED_UNION/NAMED_UNION are root or dynamic Any
            // identities where no field schema is available.
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Union",
                nullable,
                true,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (dynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Unknown",
                nullable,
                true,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (unwrapped.TypeKind == TypeKind.Enum)
        {
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Enum",
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        return new TypeMetaFieldTypeModel(
            $"(uint){classification.TypeId}",
            nullable,
            !classification.IsBuiltIn && unwrapped.TypeKind != TypeKind.Enum,
            ImmutableArray<TypeMetaFieldTypeModel>.Empty);
    }

    private static TypeMetaFieldTypeModel BuildSchemaTypeMetaFieldTypeModel(
        ITypeSymbol carrierType,
        bool nullable,
        SchemaTypeModel schemaType)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        switch (schemaType.Kind)
        {
            case SchemaTypeKind.List:
                if (!TryGetListElementType(unwrapped, out ITypeSymbol? listElementType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool elementNullable = GenericNullable(listElementType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.List",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(
                            listElementType!,
                            elementNullable,
                            schemaType.Generics[0])));
            case SchemaTypeKind.Set:
                if (!TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool setElementNullable = GenericNullable(setElementType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.Set",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(
                            setElementType!,
                            setElementNullable,
                            schemaType.Generics[0])));
            case SchemaTypeKind.Map:
                if (!TryGetMapTypeArguments(unwrapped, out ITypeSymbol? keyType, out ITypeSymbol? valueType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool keyNullable = GenericNullable(keyType!);
                bool valueNullable = GenericNullable(valueType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.Map",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(keyType!, keyNullable, schemaType.Generics[0]),
                        BuildSchemaTypeMetaFieldTypeModel(valueType!, valueNullable, schemaType.Generics[1])));
            default:
                return new TypeMetaFieldTypeModel(
                    schemaType.TypeId.ToString(),
                    nullable,
                    false,
                    ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }
    }

    private static FieldCodecModel? BuildFieldCodecModel(
        ITypeSymbol carrierType,
        TypeMetaFieldTypeModel typeMeta,
        SchemaTypeModel? schemaType,
        TypeClassification classification)
    {
        (bool nullable, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        bool nullableValueType = carrierType is INamedTypeSymbol nts &&
                                 nts.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T;

        if (schemaType is not null)
        {
            FieldCodecModel codec = BuildFieldCodecFromSchema(carrierType, nullable, nullableValueType, schemaType);
            return codec.Kind == FieldCodecKind.Scalar ? null : codec;
        }

        _ = typeMeta;
        _ = classification;
        return null;
    }

    private static FieldCodecModel BuildFieldCodecFromSchema(
        ITypeSymbol carrierType,
        bool nullable,
        bool nullableValueType,
        SchemaTypeModel schemaType)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        switch (schemaType.Kind)
        {
            case SchemaTypeKind.List:
                {
                    ITypeSymbol elementType = TryGetListElementType(unwrapped, out ITypeSymbol? listElementType)
                        ? listElementType!
                        : carrierType;
                    FieldCodecModel element = BuildFieldCodecFromSchema(
                        elementType,
                        GenericNullable(elementType),
                        elementType is INamedTypeSymbol elementNamed &&
                        elementNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    return new FieldCodecModel(
                        FieldCodecKind.List,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(element));
                }
            case SchemaTypeKind.Set:
                {
                    ITypeSymbol elementType = TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType)
                        ? setElementType!
                        : carrierType;
                    FieldCodecModel element = BuildFieldCodecFromSchema(
                        elementType,
                        GenericNullable(elementType),
                        elementType is INamedTypeSymbol elementNamed &&
                        elementNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    return new FieldCodecModel(
                        FieldCodecKind.Set,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(element));
                }
            case SchemaTypeKind.Map:
                {
                    ITypeSymbol keyType = carrierType;
                    ITypeSymbol valueType = carrierType;
                    if (TryGetMapTypeArguments(unwrapped, out ITypeSymbol? parsedKeyType, out ITypeSymbol? parsedValueType))
                    {
                        keyType = parsedKeyType!;
                        valueType = parsedValueType!;
                    }

                    FieldCodecModel key = BuildFieldCodecFromSchema(
                        keyType,
                        GenericNullable(keyType),
                        keyType is INamedTypeSymbol keyNamed &&
                        keyNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    FieldCodecModel value = BuildFieldCodecFromSchema(
                        valueType,
                        GenericNullable(valueType),
                        valueType is INamedTypeSymbol valueNamed &&
                        valueNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[1]);
                    return new FieldCodecModel(
                        FieldCodecKind.Map,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(key, value));
                }
            case SchemaTypeKind.PackedArray:
                return new FieldCodecModel(
                    FieldCodecKind.PackedArray,
                    schemaType.TypeId,
                    carrierType.ToDisplayString(FullNameFormat),
                    nullable,
                    nullableValueType,
                    GetCarrierKind(unwrapped),
                    ImmutableArray<FieldCodecModel>.Empty);
            default:
                return new FieldCodecModel(
                    FieldCodecKind.Scalar,
                    schemaType.TypeId,
                    carrierType.ToDisplayString(FullNameFormat),
                    nullable,
                    nullableValueType,
                    GetCarrierKind(unwrapped),
                    ImmutableArray<FieldCodecModel>.Empty);
        }
    }

    private static CarrierKind GetCarrierKind(ITypeSymbol unwrappedType)
    {
        if (unwrappedType is IArrayTypeSymbol)
        {
            return CarrierKind.Array;
        }

        if (unwrappedType is not INamedTypeSymbol named)
        {
            return CarrierKind.Value;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        return genericName switch
        {
            "System.Collections.Generic.List<T>" => CarrierKind.List,
            "System.Collections.Generic.HashSet<T>" => CarrierKind.HashSet,
            "System.Collections.Generic.Dictionary<TKey, TValue>" => CarrierKind.Dictionary,
            "Apache.Fory.NullableKeyDictionary<TKey, TValue>" => CarrierKind.NullableKeyDictionary,
            _ => CarrierKind.Value,
        };
    }

    private static bool TryGetFieldId(
        TypedConstant value,
        ISymbol memberSymbol,
        List<Diagnostic> diagnostics,
        out short fieldId)
    {
        fieldId = default;
        object? raw = value.Value;
        if (raw is null)
        {
            return false;
        }

        long numeric;
        switch (raw)
        {
            case byte v:
                numeric = v;
                break;
            case sbyte v:
                numeric = v;
                break;
            case short v:
                numeric = v;
                break;
            case ushort v:
                numeric = v;
                break;
            case int v:
                numeric = v;
                break;
            case uint v:
                numeric = v;
                break;
            case long v:
                numeric = v;
                break;
            case ulong v:
                if (v > (ulong)short.MaxValue)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidFieldId,
                        memberSymbol.Locations.FirstOrDefault(),
                        memberSymbol.Name));
                    return false;
                }

                numeric = (long)v;
                break;
            default:
                return false;
        }

        if (numeric < 0 || numeric > short.MaxValue)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidFieldId,
                memberSymbol.Locations.FirstOrDefault(),
                memberSymbol.Name));
            return false;
        }

        fieldId = (short)numeric;
        return true;
    }

    private static ImmutableArray<MemberModel> SortMembers(ImmutableArray<MemberModel> members)
    {
        return members
            .OrderBy(m => m.Group)
            .ThenBy(m =>
            {
                if (m.Group is 1 or 2)
                {
                    return m.Classification.IsCompressedNumeric ? 1 : 0;
                }

                return 0;
            })
            .ThenByDescending(m => m.Group is 1 or 2 ? m.Classification.PrimitiveSize : 0)
            .ThenBy(m =>
            {
                if (m.Group is 1 or 2)
                {
                    return (int)m.Classification.TypeId;
                }

                return 0;
            })
            .ThenBy(m => m.FieldId.HasValue ? 0 : 1)
            .ThenBy(m => m.FieldId.GetValueOrDefault())
            .ThenBy(m => m.FieldIdentifier, StringComparer.Ordinal)
            .ThenBy(m => m.Name, StringComparer.Ordinal)
            .ThenBy(m => m.OriginalIndex)
            .ToImmutableArray();
    }

    private static bool GenericNullable(ITypeSymbol type)
    {
        (bool optional, ITypeSymbol unwrapped) = UnwrapNullable(type);
        if (optional)
        {
            return true;
        }

        if (unwrapped.IsValueType)
        {
            return false;
        }

        TypeClassification c = ClassifyType(unwrapped);
        return !c.IsPrimitive;
    }

    private static bool FieldNeedsTypeInfo(
        TypeClassification classification,
        DynamicAnyKind dynamicAnyKind,
        ITypeSymbol unwrappedType)
    {
        if (dynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            return true;
        }

        if (classification.IsBuiltIn || IsUnionType(unwrappedType) || unwrappedType.TypeKind == TypeKind.Enum)
        {
            return false;
        }

        return true;
    }

    private static bool IsReadableWritableAccessibility(Accessibility accessibility)
    {
        return accessibility is Accessibility.Public or Accessibility.Internal or Accessibility.ProtectedOrInternal;
    }

    private static bool HasAccessibleParameterlessCtor(INamedTypeSymbol type)
    {
        foreach (IMethodSymbol ctor in type.InstanceConstructors)
        {
            if (ctor.Parameters.Length != 0)
            {
                continue;
            }

            if (ctor.DeclaredAccessibility is Accessibility.Public or Accessibility.Internal or Accessibility.ProtectedOrInternal)
            {
                return true;
            }
        }

        return false;
    }

    private static SchemaTypeModel? TryParseSchemaType(ITypeSymbol symbol)
    {
        if (symbol is not INamedTypeSymbol named)
        {
            return null;
        }

        string fullName = named.ConstructedFrom.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat);
        fullName = fullName.StartsWith("global::", StringComparison.Ordinal)
            ? fullName.Substring("global::".Length)
            : fullName;

        if (fullName == "Apache.Fory.Schema.Types.List<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element)
            {
                return null;
            }

            return new SchemaTypeModel(22, SchemaTypeKind.List, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Array<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element ||
                element.HasExplicitScalarEncoding ||
                TryResolveArrayTypeIdForElement(element.TypeId) is not uint arrayTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(arrayTypeId, SchemaTypeKind.PackedArray, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Fixed<TScalar>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel scalar ||
                TryResolveFixedTypeId(scalar.TypeId) is not uint fixedTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(
                fixedTypeId,
                SchemaTypeKind.Scalar,
                ImmutableArray<SchemaTypeModel>.Empty,
                hasExplicitScalarEncoding: true);
        }

        if (fullName == "Apache.Fory.Schema.Types.Tagged<TScalar>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel scalar ||
                TryResolveTaggedTypeId(scalar.TypeId) is not uint taggedTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(
                taggedTypeId,
                SchemaTypeKind.Scalar,
                ImmutableArray<SchemaTypeModel>.Empty,
                hasExplicitScalarEncoding: true);
        }

        if (fullName == "Apache.Fory.Schema.Types.Set<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element)
            {
                return null;
            }

            return new SchemaTypeModel(23, SchemaTypeKind.Set, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Map<TKey, TValue>")
        {
            if (named.TypeArguments.Length != 2 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel key ||
                TryParseSchemaType(named.TypeArguments[1]) is not SchemaTypeModel value)
            {
                return null;
            }

            return new SchemaTypeModel(24, SchemaTypeKind.Map, ImmutableArray.Create(key, value));
        }

        return TryResolveSchemaTypeId(fullName, out uint typeId, out SchemaTypeKind kind)
            ? new SchemaTypeModel(typeId, kind, ImmutableArray<SchemaTypeModel>.Empty)
            : null;
    }

    private static bool TryResolveSchemaTypeId(string fullName, out uint typeId, out SchemaTypeKind kind)
    {
        kind = SchemaTypeKind.Scalar;
        switch (fullName)
        {
            case "Apache.Fory.Schema.Types.Bool":
                typeId = 1;
                return true;
            case "Apache.Fory.Schema.Types.Int8":
                typeId = 2;
                return true;
            case "Apache.Fory.Schema.Types.Int16":
                typeId = 3;
                return true;
            case "Apache.Fory.Schema.Types.Int32":
                typeId = 5;
                return true;
            case "Apache.Fory.Schema.Types.Int64":
                typeId = 7;
                return true;
            case "Apache.Fory.Schema.Types.UInt8":
                typeId = 9;
                return true;
            case "Apache.Fory.Schema.Types.UInt16":
                typeId = 10;
                return true;
            case "Apache.Fory.Schema.Types.UInt32":
                typeId = 12;
                return true;
            case "Apache.Fory.Schema.Types.UInt64":
                typeId = 14;
                return true;
            case "Apache.Fory.Schema.Types.Float16":
                typeId = 17;
                return true;
            case "Apache.Fory.Schema.Types.BFloat16":
                typeId = 18;
                return true;
            case "Apache.Fory.Schema.Types.Float32":
                typeId = 19;
                return true;
            case "Apache.Fory.Schema.Types.Float64":
                typeId = 20;
                return true;
            case "Apache.Fory.Schema.Types.String":
                typeId = 21;
                return true;
            case "Apache.Fory.Schema.Types.Binary":
                typeId = 41;
                return true;
            case "Apache.Fory.Schema.Types.Duration":
                typeId = 37;
                return true;
            case "Apache.Fory.Schema.Types.Timestamp":
                typeId = 38;
                return true;
            case "Apache.Fory.Schema.Types.Date":
                typeId = 39;
                return true;
            case "Apache.Fory.Schema.Types.Decimal":
                typeId = 40;
                return true;
            default:
                typeId = 0;
                return false;
        }
    }

    private static uint? TryResolveFixedTypeId(uint scalarTypeId)
    {
        return scalarTypeId switch
        {
            5 => 4,
            7 => 6,
            12 => 11,
            14 => 13,
            4 or 6 or 11 or 13 => scalarTypeId,
            _ => null,
        };
    }

    private static uint? TryResolveTaggedTypeId(uint scalarTypeId)
    {
        return scalarTypeId switch
        {
            7 or 6 => 8,
            14 or 13 => 15,
            _ => null,
        };
    }

    private static uint? TryResolveArrayTypeIdForElement(uint elementTypeId)
    {
        return elementTypeId switch
        {
            1 => 43,
            2 => 44,
            3 => 45,
            4 or 5 => 46,
            6 or 7 or 8 => 47,
            9 => 48,
            10 => 49,
            11 or 12 => 50,
            13 or 14 or 15 => 51,
            17 => 53,
            18 => 54,
            19 => 55,
            20 => 56,
            _ => null,
        };
    }

    private static bool IsPackedArrayTypeId(uint typeId)
    {
        return typeId is 41 or 43 or 44 or 45 or 46 or 47 or 48 or 49 or 50 or 51 or 53 or 54 or 55 or 56;
    }

    private static TypeResolution ResolveTypeResolution(ITypeSymbol type, SchemaTypeModel? schemaType)
    {
        TypeClassification baseType = ClassifyType(type);
        if (schemaType is null)
        {
            return new TypeResolution(true, baseType);
        }

        bool isPrimitive = schemaType.Kind == SchemaTypeKind.Scalar;
        bool isCollection = schemaType.Kind == SchemaTypeKind.List ||
                            schemaType.Kind == SchemaTypeKind.Set;
        bool isMap = schemaType.Kind == SchemaTypeKind.Map;
        bool isCompressedNumeric = schemaType.TypeId is 5 or 7 or 8 or 12 or 14 or 15;
        int primitiveSize = schemaType.TypeId switch
        {
            1 or 2 or 9 => 1,
            3 or 10 or 17 or 18 => 2,
            4 or 5 or 11 or 12 or 19 => 4,
            6 or 7 or 8 or 13 or 14 or 15 or 20 => 8,
            _ => 0,
        };
        return new TypeResolution(
            true,
            new TypeClassification(
                schemaType.TypeId,
                isPrimitive,
                true,
                isCollection,
                isMap,
                isCompressedNumeric,
                primitiveSize));
    }

    private static TypeClassification ClassifyType(ITypeSymbol type)
    {
        if (ResolveDynamicAnyKind(type) == DynamicAnyKind.AnyValue)
        {
            return new TypeClassification(0, false, true, false, false, false, 0);
        }

        if (type.SpecialType == SpecialType.System_Boolean)
        {
            return new TypeClassification(1, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_SByte)
        {
            return new TypeClassification(2, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_Int16)
        {
            return new TypeClassification(3, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_Int32)
        {
            return new TypeClassification(5, true, true, false, false, true, 4);
        }

        if (type.SpecialType == SpecialType.System_Int64)
        {
            return new TypeClassification(7, true, true, false, false, true, 8);
        }

        if (type.SpecialType == SpecialType.System_Byte)
        {
            return new TypeClassification(9, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_UInt16)
        {
            return new TypeClassification(10, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_UInt32)
        {
            return new TypeClassification(12, true, true, false, false, true, 4);
        }

        if (type.SpecialType == SpecialType.System_UInt64)
        {
            return new TypeClassification(14, true, true, false, false, true, 8);
        }

        if (type.SpecialType == SpecialType.System_Single)
        {
            return new TypeClassification(19, true, true, false, false, false, 4);
        }

        if (string.Equals(type.ToDisplayString(), "System.Half", StringComparison.Ordinal))
        {
            return new TypeClassification(17, true, true, false, false, false, 2);
        }

        if (string.Equals(type.ToDisplayString(), "Apache.Fory.BFloat16", StringComparison.Ordinal))
        {
            return new TypeClassification(18, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_Double)
        {
            return new TypeClassification(20, true, true, false, false, false, 8);
        }

        if (type.SpecialType == SpecialType.System_String)
        {
            return new TypeClassification(21, false, true, false, false, false, 0);
        }

        if (IsDateType(type))
        {
            return new TypeClassification(39, false, true, false, false, false, 0);
        }

        if (IsTimestampType(type))
        {
            return new TypeClassification(38, false, true, false, false, false, 0);
        }

        if (IsDurationType(type))
        {
            return new TypeClassification(37, false, true, false, false, false, 0);
        }

        if (type.SpecialType == SpecialType.System_Decimal ||
            string.Equals(type.ToDisplayString(), "Apache.Fory.ForyDecimal", StringComparison.Ordinal))
        {
            return new TypeClassification(40, false, true, false, false, false, 0);
        }

        if (type is IArrayTypeSymbol arrayType)
        {
            if (TryResolvePackedArrayTypeIdForElement(arrayType.ElementType) is uint packedArrayTypeId)
            {
                return new TypeClassification(packedArrayTypeId, false, true, false, false, false, 0);
            }

            return new TypeClassification(22, false, true, true, false, false, 0);
        }

        if (TryGetListElementType(type, out _))
        {
            return new TypeClassification(22, false, true, true, false, false, 0);
        }

        if (TryGetSetElementType(type, out _))
        {
            return new TypeClassification(23, false, true, true, false, false, 0);
        }

        if (TryGetMapTypeArguments(type, out _, out _))
        {
            return new TypeClassification(24, false, true, false, true, false, 0);
        }

        if (IsUnionType(type))
        {
            return new TypeClassification(33, false, false, false, false, false, 0);
        }

        return new TypeClassification(27, false, false, false, false, false, 0);
    }

    private static DynamicAnyKind ResolveDynamicAnyKind(ITypeSymbol type)
    {
        if (type.SpecialType == SpecialType.System_Object)
        {
            return DynamicAnyKind.AnyValue;
        }

        return DynamicAnyKind.None;
    }

    private static bool IsDateType(ITypeSymbol symbol)
    {
        return string.Equals(symbol.ToDisplayString(), "System.DateOnly", StringComparison.Ordinal);
    }

    private static bool IsTimestampType(ITypeSymbol symbol)
    {
        string name = symbol.ToDisplayString();
        return string.Equals(name, "System.DateTime", StringComparison.Ordinal) ||
               string.Equals(name, "System.DateTimeOffset", StringComparison.Ordinal);
    }

    private static bool IsDurationType(ITypeSymbol symbol)
    {
        return string.Equals(symbol.ToDisplayString(), "System.TimeSpan", StringComparison.Ordinal);
    }

    private static bool IsUnionType(ITypeSymbol symbol)
    {
        if (symbol is INamedTypeSymbol namedType &&
            GetForyAttributeKind(namedType) == ForyAttributeKind.Union)
        {
            return true;
        }

        INamedTypeSymbol? current = symbol as INamedTypeSymbol;
        while (current is not null)
        {
            if (string.Equals(current.ToDisplayString(), "Apache.Fory.Union", StringComparison.Ordinal))
            {
                return true;
            }

            current = current.BaseType;
        }

        return false;
    }

    private static bool IsTypeSealed(ITypeSymbol symbol)
    {
        if (symbol.TypeKind == TypeKind.TypeParameter)
        {
            return false;
        }

        return symbol.IsSealed;
    }

    private static bool TryGetListElementType(ITypeSymbol type, out ITypeSymbol? elementType)
    {
        elementType = null;
        if (type is IArrayTypeSymbol arrayType)
        {
            elementType = arrayType.ElementType;
            return true;
        }

        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.List<T>" or
            "System.Collections.Generic.LinkedList<T>" or
            "System.Collections.Generic.Queue<T>" or
            "System.Collections.Generic.Stack<T>" or
            "System.Collections.Generic.IList<T>" or
            "System.Collections.Generic.IReadOnlyList<T>")
        {
            elementType = named.TypeArguments[0];
            return true;
        }

        return false;
    }

    private static bool TryGetSetElementType(ITypeSymbol type, out ITypeSymbol? elementType)
    {
        elementType = null;
        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.HashSet<T>" or
            "System.Collections.Generic.SortedSet<T>" or
            "System.Collections.Immutable.ImmutableHashSet<T>" or
            "System.Collections.Generic.ISet<T>" or
            "System.Collections.Generic.IReadOnlySet<T>" or
            "System.Collections.Immutable.IImmutableSet<T>")
        {
            elementType = named.TypeArguments[0];
            return true;
        }

        return false;
    }

    private static bool TryGetMapTypeArguments(ITypeSymbol type, out ITypeSymbol? keyType, out ITypeSymbol? valueType)
    {
        keyType = null;
        valueType = null;
        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.Dictionary<TKey, TValue>" or
            "System.Collections.Generic.SortedDictionary<TKey, TValue>" or
            "System.Collections.Generic.SortedList<TKey, TValue>" or
            "System.Collections.Concurrent.ConcurrentDictionary<TKey, TValue>" or
            "System.Collections.Generic.IDictionary<TKey, TValue>" or
            "System.Collections.Generic.IReadOnlyDictionary<TKey, TValue>" or
            "Apache.Fory.NullableKeyDictionary<TKey, TValue>")
        {
            keyType = named.TypeArguments[0];
            valueType = named.TypeArguments[1];
            return true;
        }

        return false;
    }

    private static uint? TryResolvePackedArrayTypeIdForElement(ITypeSymbol elementType)
    {
        (bool isNullable, ITypeSymbol unwrapped) = UnwrapNullable(elementType);
        if (isNullable)
        {
            return null;
        }

        uint elementTypeId = ClassifyType(unwrapped).TypeId;
        return elementTypeId switch
        {
            9 => 41,  // byte -> binary
            1 => 43,  // bool -> bool array
            2 => 44,  // sbyte -> int8 array
            3 => 45,  // short -> int16 array
            5 => 46,  // int -> int32 array
            7 => 47,  // long -> int64 array
            10 => 49, // ushort -> uint16 array
            12 => 50, // uint -> uint32 array
            14 => 51, // ulong -> uint64 array
            17 => 53, // Half -> float16 array
            18 => 54, // BFloat16 -> bfloat16 array
            19 => 55, // float -> float32 array
            20 => 56, // double -> float64 array
            _ => null,
        };
    }

    private static (bool, ITypeSymbol) UnwrapNullable(ITypeSymbol type)
    {
        if (type is INamedTypeSymbol named &&
            named.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T)
        {
            return (true, named.TypeArguments[0]);
        }

        if (type.IsReferenceType && type.NullableAnnotation == NullableAnnotation.Annotated)
        {
            return (true, type.WithNullableAnnotation(NullableAnnotation.NotAnnotated));
        }

        return (false, type);
    }

    private static string BoolLiteral(bool value) => value ? "true" : "false";

    private static string EscapeString(string value) => value.Replace("\\", "\\\\").Replace("\"", "\\\"");

    private static string ToSnakeCase(string name)
    {
        if (string.IsNullOrEmpty(name))
        {
            return name;
        }

        StringBuilder sb = new(name.Length + 4);
        for (int i = 0; i < name.Length; i++)
        {
            char c = name[i];
            if (char.IsUpper(c))
            {
                if (i > 0)
                {
                    bool prevUpper = char.IsUpper(name[i - 1]);
                    bool nextUpperOrEnd = i + 1 >= name.Length || char.IsUpper(name[i + 1]);
                    bool leadingPascalBoundary = i == 1 && prevUpper && !nextUpperOrEnd;
                    if ((!prevUpper || !nextUpperOrEnd) && !leadingPascalBoundary)
                    {
                        sb.Append('_');
                    }
                }

                sb.Append(char.ToLowerInvariant(c));
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }

    private static string Sanitize(string name)
    {
        StringBuilder sb = new(name.Length + 8);
        foreach (char c in name)
        {
            sb.Append(char.IsLetterOrDigit(c) ? c : '_');
        }

        return sb.ToString();
    }

    private sealed class TypeResolution
    {
        public TypeResolution(bool supported, TypeClassification classification)
        {
            Supported = supported;
            Classification = classification;
        }

        public bool Supported { get; }
        public TypeClassification Classification { get; }
    }

    private sealed class TypeClassification
    {
        public TypeClassification(
            uint typeId,
            bool isPrimitive,
            bool isBuiltIn,
            bool isCollection,
            bool isMap,
            bool isCompressedNumeric,
            int primitiveSize)
        {
            TypeId = typeId;
            IsPrimitive = isPrimitive;
            IsBuiltIn = isBuiltIn;
            IsCollection = isCollection;
            IsMap = isMap;
            IsCompressedNumeric = isCompressedNumeric;
            PrimitiveSize = primitiveSize;
        }

        public uint TypeId { get; }
        public bool IsPrimitive { get; }
        public bool IsBuiltIn { get; }
        public bool IsCollection { get; }
        public bool IsMap { get; }
        public bool IsCompressedNumeric { get; }
        public int PrimitiveSize { get; }
    }

    private sealed class TypeMetaFieldTypeModel
    {
        public TypeMetaFieldTypeModel(
            string typeIdExpr,
            bool nullable,
            bool trackRefByContext,
            ImmutableArray<TypeMetaFieldTypeModel> generics)
        {
            TypeIdExpr = typeIdExpr;
            Nullable = nullable;
            TrackRefByContext = trackRefByContext;
            Generics = generics;
        }

        public string TypeIdExpr { get; }
        public bool Nullable { get; }
        public bool TrackRefByContext { get; }
        public ImmutableArray<TypeMetaFieldTypeModel> Generics { get; }
    }

    private sealed class SchemaTypeModel
    {
        public SchemaTypeModel(
            uint typeId,
            SchemaTypeKind kind,
            ImmutableArray<SchemaTypeModel> generics,
            bool hasExplicitScalarEncoding = false)
        {
            TypeId = typeId;
            Kind = kind;
            Generics = generics;
            HasExplicitScalarEncoding = hasExplicitScalarEncoding;
        }

        public uint TypeId { get; }
        public SchemaTypeKind Kind { get; }
        public ImmutableArray<SchemaTypeModel> Generics { get; }
        public bool HasExplicitScalarEncoding { get; }
    }

    private sealed class FieldCodecModel
    {
        public FieldCodecModel(
            FieldCodecKind kind,
            uint typeId,
            string typeName,
            bool nullable,
            bool nullableValueType,
            CarrierKind carrierKind,
            ImmutableArray<FieldCodecModel> generics)
        {
            Kind = kind;
            TypeId = typeId;
            TypeName = typeName;
            Nullable = nullable;
            NullableValueType = nullableValueType;
            CarrierKind = carrierKind;
            Generics = generics;
        }

        public FieldCodecKind Kind { get; }
        public uint TypeId { get; }
        public string TypeName { get; }
        public bool Nullable { get; }
        public bool NullableValueType { get; }
        public CarrierKind CarrierKind { get; }
        public ImmutableArray<FieldCodecModel> Generics { get; }
    }

    private sealed class TypeModel
    {
        public TypeModel(
            string typeName,
            string serializerName,
            DeclKind kind,
            ImmutableArray<MemberModel> members,
            ImmutableArray<MemberModel> sortedMembers,
            ImmutableArray<Diagnostic> diagnostics,
            ImmutableArray<UnionCaseModel> unionCases = default)
        {
            TypeName = typeName;
            SerializerName = serializerName;
            Kind = kind;
            Members = members;
            SortedMembers = sortedMembers;
            Diagnostics = diagnostics;
            UnionCases = unionCases.IsDefault
                ? ImmutableArray<UnionCaseModel>.Empty
                : unionCases;
        }

        public string TypeName { get; }
        public string SerializerName { get; }
        public DeclKind Kind { get; }
        public ImmutableArray<MemberModel> Members { get; }
        public ImmutableArray<MemberModel> SortedMembers { get; }
        public ImmutableArray<Diagnostic> Diagnostics { get; }
        public ImmutableArray<UnionCaseModel> UnionCases { get; }
    }

    private sealed class MemberModel
    {
        public MemberModel(
            string name,
            string fieldIdentifier,
            int originalIndex,
            MemberDeclKind declKind,
            string typeName,
            bool isNullable,
            bool isNullableValueType,
            short? fieldId,
            TypeClassification classification,
            int group,
            bool isCollection,
            bool useDictionaryTypeInfoCache,
            bool isRefType,
            bool needsFieldTypeInfo,
            DynamicAnyKind dynamicAnyKind,
            TypeMetaFieldTypeModel typeMeta,
            FieldCodecModel? fieldCodec,
            bool hasSchemaType = false)
        {
            Name = name;
            FieldIdentifier = fieldIdentifier;
            OriginalIndex = originalIndex;
            DeclKind = declKind;
            TypeName = typeName;
            IsNullable = isNullable;
            IsNullableValueType = isNullableValueType;
            FieldId = fieldId;
            Classification = classification;
            Group = group;
            IsCollection = isCollection;
            UseDictionaryTypeInfoCache = useDictionaryTypeInfoCache;
            IsRefType = isRefType;
            NeedsFieldTypeInfo = needsFieldTypeInfo;
            DynamicAnyKind = dynamicAnyKind;
            TypeMeta = typeMeta;
            FieldCodec = fieldCodec;
            HasSchemaType = hasSchemaType;
        }

        public string Name { get; }
        public string FieldIdentifier { get; }
        public int OriginalIndex { get; }
        public MemberDeclKind DeclKind { get; }
        public string TypeName { get; }
        public bool IsNullable { get; }
        public bool IsNullableValueType { get; }
        public short? FieldId { get; }
        public TypeClassification Classification { get; }
        public int Group { get; }
        public bool IsCollection { get; }
        public bool UseDictionaryTypeInfoCache { get; }
        public bool IsRefType { get; }
        public bool NeedsFieldTypeInfo { get; }
        public DynamicAnyKind DynamicAnyKind { get; }
        public TypeMetaFieldTypeModel TypeMeta { get; }
        public FieldCodecModel? FieldCodec { get; }
        public bool HasSchemaType { get; }
    }

    private sealed class UnionCaseModel
    {
        public UnionCaseModel(int? caseId, string typeName, bool isUnknown, MemberModel? valueMember)
        {
            CaseId = caseId;
            TypeName = typeName;
            IsUnknown = isUnknown;
            ValueMember = valueMember;
        }

        public int? CaseId { get; }
        public int KnownCaseId => CaseId ?? throw new InvalidOperationException("unknown union carrier has no schema case id");
        public string TypeName { get; }
        public bool IsUnknown { get; }
        public MemberModel? ValueMember { get; }
    }

    private enum MemberDeclKind
    {
        Field,
        Property,
    }

    private enum DeclKind
    {
        Unknown,
        Class,
        Struct,
        Enum,
        Union,
    }

    private enum ForyAttributeKind
    {
        None,
        Struct,
        Enum,
        Union,
    }

    private enum DynamicAnyKind
    {
        None,
        AnyValue,
    }

    private enum SchemaTypeKind
    {
        Scalar,
        PackedArray,
        List,
        Set,
        Map,
    }

    private enum FieldCodecKind
    {
        Scalar,
        PackedArray,
        List,
        Set,
        Map,
    }

    private enum CarrierKind
    {
        Value,
        Array,
        List,
        HashSet,
        Dictionary,
        NullableKeyDictionary,
    }
}
