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

namespace Apache.Fory.Generator;

public sealed partial class ForyModelGenerator
{
    private static void EmitObjectSerializer(StringBuilder sb, TypeModel model)
    {
        if (model.Kind == DeclKind.Class)
        {
            if (model.PublishesHierarchy)
            {
                sb.AppendLine(
                    $"[global::Apache.Fory.ForyGeneratedHierarchyProvider(typeof({model.TargetTypeName}), {model.DeclaredMembers.Length})]");
                sb.AppendLine("[global::System.Runtime.CompilerServices.CompilerGenerated]");
                sb.AppendLine(
                    "[global::System.ComponentModel.EditorBrowsable(global::System.ComponentModel.EditorBrowsableState.Never)]");
                if (model.ProviderOnly)
                {
                    sb.AppendLine(
                        $"{model.ProviderVisibility} static class {model.GeneratedClassName}");
                }
                else
                {
                    sb.AppendLine(
                        $"{model.ProviderVisibility} sealed class {model.GeneratedClassName} : global::Apache.Fory.Serializer<{model.TargetTypeName}>");
                }
            }
            else
            {
                sb.AppendLine(
                    $"file sealed class {model.GeneratedClassName} : global::Apache.Fory.Serializer<{model.TargetTypeName}>");
            }
        }
        else
        {
            sb.AppendLine(
                $"file sealed class {model.GeneratedClassName} : global::Apache.Fory.Serializer<{model.TargetTypeName}>");
        }

        sb.AppendLine("{");
        if (model.PublishesHierarchy)
        {
            EmitHierarchyProviderApi(sb, model);
        }

        if (model.Kind == DeclKind.Class)
        {
            foreach (MemberModel member in model.DeclaredMembers
                         .OrderBy(member => member.DeclarationOrdinal))
            {
                EmitMemberAccessors(sb, model, member);
            }

            if (!model.DeclaredMembers.IsEmpty)
            {
                sb.AppendLine();
            }
        }

        if (model.ProviderOnly)
        {
            sb.AppendLine("}");
            return;
        }

        sb.AppendLine("    private static readonly object __ForyTypeMetaCacheLock = new();");
        sb.AppendLine("    private static ulong __ForyTypeMetaResolverVersion;");
        sb.AppendLine("    private static ulong __ForyNoRefTypeMetaHash;");
        sb.AppendLine("    private static ulong __ForyRefTypeMetaHash;");
        sb.AppendLine("    private static global::Apache.Fory.TypeMeta? __ForyNoRefMeta;");
        sb.AppendLine("    private static bool __ForyNoRefMetaMatches;");
        sb.AppendLine("    private static global::Apache.Fory.TypeMeta? __ForyRefMeta;");
        sb.AppendLine("    private static bool __ForyRefMetaMatches;");
        sb.AppendLine(
            $"    private const bool __ForyAllFieldsBuiltIn = {BoolLiteral(model.SortedMembers.All(m => m.DynamicAnyKind == DynamicAnyKind.None && m.Classification.IsBuiltIn))};");
        if (model.Kind == DeclKind.Class)
        {
            string shallowMemoryExpression = model.PublishesHierarchy
                ? "HierarchyShallowBytes"
                : BuildShallowMemoryExpression(model);
            sb.AppendLine(
                $"    private static readonly long __ForyGraphMemoryBytes = checked({GraphObjectOwnerBytesExpr} + {shallowMemoryExpression});");
        }

        sb.AppendLine(
            "    private static global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo>? __ForyNoRefTypeMetaFields;");
        sb.AppendLine(
            "    private static global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo>? __ForyRefTypeMetaFields;");

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
        if (model.SortedMembers.Any(member => HasMapCodec(member.FieldCodec)))
        {
            EmitMapChunkError(sb, 1);
        }

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
            $"            global::Apache.Fory.TypeInfo typeInfo = typeResolver.GetTypeInfo<{model.TargetTypeName}>();");
        sb.AppendLine(
            "            __ForyNoRefTypeMetaHash = typeInfo.GetTypeMetaHeaderHash(false);");
        sb.AppendLine(
            "            __ForyRefTypeMetaHash = typeInfo.GetTypeMetaHeaderHash(true);");
        sb.AppendLine("            __ForyNoRefMeta = null;");
        sb.AppendLine("            __ForyNoRefMetaMatches = false;");
        sb.AppendLine("            __ForyRefMeta = null;");
        sb.AppendLine("            __ForyRefMetaMatches = false;");
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
            "            if (global::System.Object.ReferenceEquals(__ForyRefMeta, typeMeta))");
        sb.AppendLine("            {");
        sb.AppendLine("                return __ForyRefMetaMatches;");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            __ForyEnsureTypeMetaCache(typeResolver);");
        sb.AppendLine();
        sb.AppendLine("            bool matched = false;");
        sb.AppendLine("            if (typeMeta.HeaderHash == __ForyRefTypeMetaHash)");
        sb.AppendLine("            {");
        sb.AppendLine("                matched = __ForyMatchesTypeMeta(typeMeta, true);");
        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            __ForyRefMeta = typeMeta;");
        sb.AppendLine("            __ForyRefMetaMatches = matched;");
        sb.AppendLine("            return matched;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine(
            "        if (global::System.Object.ReferenceEquals(__ForyNoRefMeta, typeMeta))");
        sb.AppendLine("        {");
        sb.AppendLine("            return __ForyNoRefMetaMatches;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        __ForyEnsureTypeMetaCache(typeResolver);");
        sb.AppendLine();
        sb.AppendLine("        bool noTrackMatched = false;");
        sb.AppendLine("        if (typeMeta.HeaderHash == __ForyNoRefTypeMetaHash)");
        sb.AppendLine("        {");
        sb.AppendLine("            noTrackMatched = __ForyMatchesTypeMeta(typeMeta, false);");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        __ForyNoRefMeta = typeMeta;");
        sb.AppendLine("        __ForyNoRefMetaMatches = noTrackMatched;");
        sb.AppendLine("        return noTrackMatched;");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine("    private static uint? __ForyNoRefSchemaHash;");
        sb.AppendLine();
        sb.AppendLine("    private static uint __ForySchemaHash(bool trackRef, global::Apache.Fory.TypeResolver typeResolver)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (!trackRef && __ForyNoRefSchemaHash.HasValue)");
        sb.AppendLine("        {");
        sb.AppendLine("            return __ForyNoRefSchemaHash.Value;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.Append("        uint value = global::Apache.Fory.SchemaHash.StructHash32(");
        sb.Append(BuildSchemaFingerprintExpression(model.Members));
        sb.AppendLine(");");
        sb.AppendLine("        if (!trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine("            __ForyNoRefSchemaHash = value;");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine("        return value;");
        sb.AppendLine("    }");
        sb.AppendLine();
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine($"    public override {model.TargetTypeName} DefaultValue => null!;");
        }
        else
        {
            sb.AppendLine($"    public override {model.TargetTypeName} DefaultValue => new {model.TargetTypeName}();");
        }

        sb.AppendLine();
        sb.AppendLine("    private global::System.Collections.Generic.IReadOnlyList<global::Apache.Fory.TypeMetaFieldInfo> TypeMetaFields(bool trackRef)");
        sb.AppendLine("    {");
        sb.AppendLine("        if (trackRef)");
        sb.AppendLine("        {");
        sb.AppendLine(
            "            return __ForyRefTypeMetaFields ??= __ForyBuildTypeMetaFields(true);");
        sb.AppendLine("        }");
        sb.AppendLine();
        sb.AppendLine(
            "        return __ForyNoRefTypeMetaFields ??= __ForyBuildTypeMetaFields(false);");
        sb.AppendLine("    }");
        sb.AppendLine();
        sb.AppendLine(
            $"    public override void WriteData(global::Apache.Fory.WriteContext context, in {model.TargetTypeName} value, bool hasGenerics)");
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
        EmitReadDataWithoutTypeMeta(sb, model, "ReadDataWithoutTypeMeta");
        EmitReadDataMethod(sb, model, "ReadData", "ReadDataWithoutTypeMeta", "public");

        sb.AppendLine("}");
    }

    private static void EmitReadDataWithoutTypeMeta(
        StringBuilder sb,
        TypeModel model,
        string methodName)
    {
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine(
                $"    private {model.TargetTypeName} {methodName}(global::Apache.Fory.ReadContext context, bool publishRef, uint refId)");
        }
        else
        {
            sb.AppendLine($"    private {model.TargetTypeName} {methodName}(global::Apache.Fory.ReadContext context)");
        }

        sb.AppendLine("    {");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("        context.ReserveGraphMemory(__ForyGraphMemoryBytes);");
        }
        else
        {
            sb.AppendLine("        // Value serializers do not reserve their own graph memory because value storage is");
            sb.AppendLine("        // owned by the holder that stores or allocates the value. Containers, maps, arrays,");
            sb.AppendLine("        // pointer/box owners, class/reference owners, or dynamic boxing paths reserve");
            sb.AppendLine("        // the storage they own.");
        }

        sb.AppendLine($"        {model.TargetTypeName} valueNoTypeMeta = new {model.TargetTypeName}();");
        EmitRefPublication(sb, model, "valueNoTypeMeta", 2);

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
    }

    private static void EmitReadDataMethod(
        StringBuilder sb,
        TypeModel model,
        string methodName,
        string noTypeMetaMethodName,
        string accessibility)
    {
        sb.AppendLine("    [global::System.Runtime.CompilerServices.MethodImpl(global::System.Runtime.CompilerServices.MethodImplOptions.AggressiveInlining)]");
        sb.AppendLine($"    {accessibility} override {model.TargetTypeName} {methodName}(global::Apache.Fory.ReadContext context)");
        sb.AppendLine("    {");
        if (model.Kind == DeclKind.Class)
        {
            // Generated class serializers allocate the reference owner before reading fields. Keep
            // the ref-aware path in the existing Read API so self-references publish the final
            // owner, while structs continue to read as inline values with no generated ref
            // publication.
            sb.AppendLine($"        return {methodName}Core(context, publishRef: false, refId: 0);");
            sb.AppendLine("    }");
            sb.AppendLine();
            sb.AppendLine(
                $"    private {model.TargetTypeName} ReadReservedRefData(global::Apache.Fory.ReadContext context, uint refId)");
            sb.AppendLine("    {");
            sb.AppendLine($"        return {methodName}Core(context, publishRef: true, refId);");
            sb.AppendLine("    }");
            sb.AppendLine();
            sb.AppendLine(
                $"    public override {model.TargetTypeName} Read(global::Apache.Fory.ReadContext context, global::Apache.Fory.RefMode refMode, bool readTypeInfo)");
            sb.AppendLine("    {");
            sb.AppendLine("        if (refMode != global::Apache.Fory.RefMode.None)");
            sb.AppendLine("        {");
            sb.AppendLine("            global::Apache.Fory.RefFlag flag = context.RefReader.ReadRefFlag(context.Reader);");
            sb.AppendLine("            switch (flag)");
            sb.AppendLine("            {");
            sb.AppendLine("                case global::Apache.Fory.RefFlag.Null:");
            sb.AppendLine("                    return default!;");
            sb.AppendLine("                case global::Apache.Fory.RefFlag.Ref:");
            sb.AppendLine("                    {");
            sb.AppendLine("                        uint refId = context.RefReader.ReadRefId(context.Reader);");
            sb.AppendLine($"                        return context.RefReader.GetRef<{model.TargetTypeName}>(refId);");
            sb.AppendLine("                    }");
            sb.AppendLine("                case global::Apache.Fory.RefFlag.RefValue:");
            sb.AppendLine("                    {");
            sb.AppendLine("                        uint reservedRefId = context.RefReader.ReserveRefId();");
            sb.AppendLine("                        if (readTypeInfo)");
            sb.AppendLine("                        {");
            sb.AppendLine("                            context.TypeResolver.ReadTypeInfo(this, context);");
            sb.AppendLine("                        }");
            sb.AppendLine();
            sb.AppendLine($"                        return {methodName}Core(context, publishRef: true, reservedRefId);");
            sb.AppendLine("                    }");
            sb.AppendLine("                case global::Apache.Fory.RefFlag.NotNullValue:");
            sb.AppendLine("                    break;");
            sb.AppendLine("                default:");
            sb.AppendLine("                    throw new global::Apache.Fory.RefException($\"invalid ref flag {(sbyte)flag}\");");
            sb.AppendLine("            }");
            sb.AppendLine("        }");
            sb.AppendLine();
            sb.AppendLine("        if (readTypeInfo)");
            sb.AppendLine("        {");
            sb.AppendLine("            context.TypeResolver.ReadTypeInfo(this, context);");
            sb.AppendLine("        }");
            sb.AppendLine();
            sb.AppendLine($"        return {methodName}Core(context, publishRef: false, refId: 0);");
            sb.AppendLine("    }");
            sb.AppendLine();
            sb.AppendLine(
                $"    private {model.TargetTypeName} {methodName}Core(global::Apache.Fory.ReadContext context, bool publishRef, uint refId)");
            sb.AppendLine("    {");
        }

        sb.AppendLine("        if (context.Compatible)");
        sb.AppendLine("        {");
        sb.AppendLine(
            $"            global::Apache.Fory.TypeMeta? maybeTypeMeta = context.GetTypeMeta<{model.TargetTypeName}>();");
        sb.AppendLine("            if (maybeTypeMeta is null)");
        sb.AppendLine("            {");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine($"                return {noTypeMetaMethodName}(context, publishRef, refId);");
        }
        else
        {
            sb.AppendLine($"                return {noTypeMetaMethodName}(context);");
        }

        sb.AppendLine("            }");
        sb.AppendLine();
        sb.AppendLine("            global::Apache.Fory.TypeMeta typeMeta = maybeTypeMeta;");
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("            context.ReserveGraphMemory(__ForyGraphMemoryBytes);");
        }
        else
        {
            sb.AppendLine("            // Value serializers do not reserve their own graph memory because value storage is");
            sb.AppendLine("            // owned by the holder that stores or allocates the value. Containers, maps, arrays,");
            sb.AppendLine("            // pointer/box owners, class/reference owners, or dynamic boxing paths reserve");
            sb.AppendLine("            // the storage they own.");
        }

        sb.AppendLine($"            {model.TargetTypeName} value = new {model.TargetTypeName}();");
        EmitRefPublication(sb, model, "value", 3);

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
        sb.AppendLine("            // Keep schema-evolution field dispatch out of the exact-metadata hot body.");
        sb.AppendLine("            return ReadCompatibleFields(context, value, typeMeta);");
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
        if (model.Kind == DeclKind.Class)
        {
            sb.AppendLine("        context.ReserveGraphMemory(__ForyGraphMemoryBytes);");
        }
        else
        {
            sb.AppendLine("        // Value serializers do not reserve their own graph memory because value storage is");
            sb.AppendLine("        // owned by the holder that stores or allocates the value. Containers, maps, arrays,");
            sb.AppendLine("        // pointer/box owners, class/reference owners, or dynamic boxing paths reserve");
            sb.AppendLine("        // the storage they own.");
        }

        sb.AppendLine($"        {model.TargetTypeName} valueSchema = new {model.TargetTypeName}();");
        EmitRefPublication(sb, model, "valueSchema", 2);

        foreach (MemberModel member in model.SortedMembers)
        {
            EmitReadMemberAssignment(sb, member, BuildWriteRefModeExpression(member), "false", "valueSchema", "Schema", 2, true);
        }

        sb.AppendLine("        return valueSchema;");
        sb.AppendLine("    }");
        sb.AppendLine();
        EmitCompatibleFieldReadMethod(sb, model);
    }

    private static void EmitCompatibleFieldReadMethod(StringBuilder sb, TypeModel model)
    {
        sb.AppendLine("    [global::System.Runtime.CompilerServices.MethodImpl(global::System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]");
        sb.AppendLine(
            $"    private {model.TargetTypeName} ReadCompatibleFields(global::Apache.Fory.ReadContext context, {model.TargetTypeName} value, global::Apache.Fory.TypeMeta typeMeta)");
        sb.AppendLine("    {");
        sb.AppendLine("        for (int i = 0; i < typeMeta.Fields.Count; i++)");
        sb.AppendLine("        {");
        sb.AppendLine("            global::Apache.Fory.TypeMetaFieldInfo remoteField = typeMeta.Fields[i];");
        sb.AppendLine("            switch (remoteField.AssignedFieldId)");
        sb.AppendLine("            {");
        sb.AppendLine("                case -1:");
        sb.AppendLine("                    global::Apache.Fory.FieldSkipper.SkipFieldValue(context, remoteField.FieldType);");
        sb.AppendLine("                    break;");
        for (int idx = 0; idx < model.SortedMembers.Length; idx++)
        {
            MemberModel member = model.SortedMembers[idx];
            sb.AppendLine($"                case {idx * 2}:");
            sb.AppendLine("                    {");
            EmitReadMemberAssignment(
                sb,
                member,
                BuildWriteRefModeExpression(member),
                BuildFieldTypeInfoLiteral(member),
                "value",
                "CompatDirect",
                6,
                true);
            sb.AppendLine("                        break;");
            sb.AppendLine("                    }");
            sb.AppendLine($"                case {idx * 2 + 1}:");
            sb.AppendLine("                    {");
            string compatRefModeExpr;
            if (CompatibleCaseNeedsRemoteRefMode(member))
            {
                sb.AppendLine("                        global::Apache.Fory.RefMode remoteRefMode = __ForyRefMode(remoteField.FieldType.Nullable, remoteField.FieldType.TrackRef);");
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
                6,
                false);
            sb.AppendLine("                        break;");
            sb.AppendLine("                    }");
        }

        sb.AppendLine("                default:");
        sb.AppendLine("                    throw new global::Apache.Fory.InvalidDataException($\"invalid compatible matched id {remoteField.AssignedFieldId}\");");
        sb.AppendLine("            }");
        sb.AppendLine("        }");
        sb.AppendLine("        return value;");
        sb.AppendLine("    }");
        sb.AppendLine();
    }

    private static void EmitRefPublication(
        StringBuilder sb,
        TypeModel model,
        string valueName,
        int indentLevel)
    {
        if (model.Kind != DeclKind.Class)
        {
            return;
        }

        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}if (publishRef)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    context.RefReader.StoreRefAt(refId, {valueName});");
        sb.AppendLine($"{indent}}}");
    }

    private static void EmitUnionSerializer(StringBuilder sb, TypeModel model)
    {
        sb.AppendLine(
            $"file sealed class {model.GeneratedClassName} : global::Apache.Fory.Serializer<{model.TargetTypeName}>");
        sb.AppendLine("{");
        sb.AppendLine($"    public override {model.TargetTypeName} DefaultValue => null!;");
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
            $"    public override void WriteData(global::Apache.Fory.WriteContext context, in {model.TargetTypeName} value, bool hasGenerics)");
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
        sb.AppendLine($"    public override {model.TargetTypeName} ReadData(global::Apache.Fory.ReadContext context)");
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
            sb.AppendLine($"                {model.TargetTypeName} __foryUnion = new {unionCase.TypeName}({valueVar});");
            sb.AppendLine("                return __foryUnion;");
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
            sb.AppendLine($"                {model.TargetTypeName} __foryUnion = new {unknownCase.TypeName}(global::Apache.Fory.UnknownCaseSerializer.ReadPayload(context, caseId));");
            sb.AppendLine("                return __foryUnion;");
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
        if (HasMapCodec(member.FieldCodec))
        {
            EmitMapChunkError(sb, 2);
        }

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
            string readExpr = CanReadNested(member)
                ? $"context.TypeResolver.ReadNested<{member.TypeName}>(context, {refModeExpr}, true)"
                : $"context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, true)";
            sb.AppendLine($"{indent}{member.TypeName} {valueVar} = {readExpr};");
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
        string fallbackReadExpr = CanReadNested(member)
            ? $"context.TypeResolver.ReadNestedData<{member.TypeName}>(context)"
            : $"context.TypeResolver.GetSerializer<{member.TypeName}>().ReadData(context)";
        sb.AppendLine($"{fallbackIndent}{member.TypeName} {valueVar} = {fallbackReadExpr};");
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
        string memberId = member.CodeKey;
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
        string memberId = member.CodeKey;
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
            sb.AppendLine($"{indent}context.ReserveGraphMemory({GraphListOwnerBytesExpr} + (long)__foryLength);");
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
        string elementBytesVar = $"__foryElementBytes{id++}";
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
        sb.AppendLine($"{indent}    int {elementBytesVar} = remoteFieldType.Generics[0].TypeId switch");
        sb.AppendLine($"{indent}    {{");
        foreach (uint remoteElementTypeId in CompatibleElementReadTypeIds(PackedArrayElementTypeId(codec.TypeId)))
        {
            sb.AppendLine($"{indent}        {remoteElementTypeId} => {MinimumEncodedElementBytes(remoteElementTypeId)},");
        }
        sb.AppendLine($"{indent}        _ => throw new global::Apache.Fory.InvalidDataException($\"unsupported compatible list element type {{remoteFieldType.Generics[0].TypeId}}\"),");
        sb.AppendLine($"{indent}    }};");
        sb.AppendLine($"{indent}    context.Reader.CheckBound(checked({lengthVar} * {elementBytesVar}));");
        sb.AppendLine($"{indent}}}");
        string elementTypeName = codec.CarrierKind == CarrierKind.Array ? ElementTypeName(codec.TypeName) : PackedArrayElementTypeName(codec.TypeId);
        uint elementTypeId = PackedArrayElementTypeId(codec.TypeId);
        string elementBytesExpr = GraphElementBytesExpr(elementTypeName);
        if (codec.CarrierKind == CarrierKind.Array)
        {
            sb.AppendLine($"{indent}{codec.TypeName} {targetVar} = new {ElementTypeName(codec.TypeName)}[{lengthVar}];");
        }
        else
        {
            sb.AppendLine($"{indent}context.ReserveGraphMemory({GraphListOwnerBytesExpr} + (long){lengthVar} * {elementBytesExpr});");
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

    private static int MinimumEncodedElementBytes(uint typeId)
    {
        return typeId switch
        {
            1 or 2 or 5 or 7 or 9 or 12 or 14 => 1,
            3 or 10 or 17 or 18 => 2,
            4 or 8 or 11 or 15 or 19 => 4,
            6 or 13 or 20 => 8,
            _ => throw new InvalidOperationException($"unsupported compatible list element type id {typeId}"),
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
            string elementBytesExpr = GraphElementBytesExpr(PackedArrayElementTypeName(codec.TypeId));
            sb.AppendLine($"{indent}context.ReserveGraphMemory({GraphListOwnerBytesExpr} + (long){countVar} * {elementBytesExpr});");
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
        string ownerBytesExpr = isSet ? GraphSetOwnerBytesExpr : GraphListOwnerBytesExpr;
        sb.AppendLine($"{indent}context.ReserveGraphMemory({ownerBytesExpr} + (long){lengthVar} * {GraphElementBytesExpr(element)});");
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
        sb.AppendLine($"{indent}context.ReserveGraphMemory({GraphMapOwnerBytesExpr} + (long){totalVar} * {GraphMapElementBytesExpr(key, value)});");
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
        sb.AppendLine($"{innerIndent}if (__foryChunkSize == 0 || __foryChunkSize > {totalVar} - __foryRead)");
        sb.AppendLine($"{innerIndent}{{");
        sb.AppendLine($"{innerIndent}    __ForyThrowInvalidMapChunkSize(__foryChunkSize, {totalVar} - __foryRead);");
        sb.AppendLine($"{innerIndent}}}");
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

    private static bool HasMapCodec(FieldCodecModel? codec)
    {
        return codec is not null &&
               (codec.Kind == FieldCodecKind.Map || codec.Generics.Any(HasMapCodec));
    }

    private static bool ReadDataAlwaysAdvances(TypeModel model)
    {
        return model.SortedMembers.Any(FieldReadAlwaysAdvances);
    }

    private static bool FieldReadAlwaysAdvances(MemberModel member)
    {
        if (member.IsNullable ||
            member.NeedsFieldTypeInfo ||
            member.DynamicAnyKind != DynamicAnyKind.None)
        {
            return true;
        }

        if (member.FieldCodec is not null)
        {
            return member.FieldCodec.Kind != FieldCodecKind.Scalar;
        }

        return CanUseDirectBuiltInFieldAccess(member) &&
               TryBuildDirectPayloadRead(member.Classification.TypeId, out _);
    }

    private static void EmitMapChunkError(StringBuilder sb, int indentLevel)
    {
        string indent = new(' ', indentLevel * 4);
        sb.AppendLine($"{indent}[global::System.Runtime.CompilerServices.MethodImpl(global::System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]");
        sb.AppendLine($"{indent}private static void __ForyThrowInvalidMapChunkSize(int chunkSize, int remaining)");
        sb.AppendLine($"{indent}{{");
        sb.AppendLine($"{indent}    throw new global::Apache.Fory.InvalidDataException($\"invalid map chunk size {{chunkSize}} with {{remaining}} entries remaining\");");
        sb.AppendLine($"{indent}}}");
        sb.AppendLine();
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

    private const string GraphObjectOwnerBytesExpr =
        "(global::System.IntPtr.Size + global::System.IntPtr.Size + 4)";
    private const string GraphListOwnerBytesExpr =
        "(global::System.IntPtr.Size + global::System.IntPtr.Size + 12)";
    private const string GraphSetOwnerBytesExpr =
        "(global::System.IntPtr.Size + global::System.IntPtr.Size + 28)";
    private const string GraphMapOwnerBytesExpr =
        "(global::System.IntPtr.Size + global::System.IntPtr.Size + 32)";

    private static string GraphElementBytesExpr(FieldCodecModel codec)
    {
        return GraphElementBytesExpr(
            codec.Nullable && !codec.NullableValueType
                ? StripNullableForTypeOf(codec.TypeName)
                : codec.TypeName);
    }

    private static string GraphElementBytesExpr(string typeName)
    {
        return $"__ForyGraphElementBytes<{typeName}>.Bytes";
    }

    private static string GraphMapElementBytesExpr(FieldCodecModel key, FieldCodecModel value)
    {
        return $"((long){GraphElementBytesExpr(key)} + {GraphElementBytesExpr(value)})";
    }

    private static string ShallowFieldMemoryExpr(ITypeSymbol fieldType)
    {
        if (fieldType.TypeKind is TypeKind.Pointer or TypeKind.FunctionPointer)
        {
            return "global::System.IntPtr.Size";
        }

        if (!fieldType.IsValueType)
        {
            return "4";
        }

        if (fieldType is INamedTypeSymbol nullableType &&
            nullableType.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T)
        {
            return $"global::System.Runtime.CompilerServices.Unsafe.SizeOf<{fieldType.ToDisplayString(FullNameFormat)}>()";
        }

        TypeClassification classification = ClassifyType(fieldType);
        int fixedValueBytes = FixedGraphValueBytes(fieldType, classification);
        if (fixedValueBytes > 0)
        {
            return fixedValueBytes.ToString(CultureInfo.InvariantCulture);
        }

        return $"global::System.Runtime.CompilerServices.Unsafe.SizeOf<{fieldType.ToDisplayString(FullNameFormat)}>()";
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
        string memberAccess = member.ReadExpression("value");
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
                $"            __ForyWrite{member.CodeKey}Field(context, {memberAccess}, {refModeExpr});");
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

            if (CanBranchTrackRefData(member))
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
        string memberId = member.CodeKey;
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
        if (member.SetterAccessorName is null)
        {
            EmitReadMemberAssignmentCore(
                sb,
                member,
                refModeExpr,
                readTypeInfoExpr,
                member.AssignmentTarget(valueVar),
                variableSuffix,
                indent,
                allowDirectRead);
            return;
        }

        string fieldValueVar = $"__fory{member.CodeKey}Value{variableSuffix}";
        sb.AppendLine($"{indent}{member.TypeName} {fieldValueVar};");
        EmitReadMemberAssignmentCore(
            sb,
            member,
            refModeExpr,
            readTypeInfoExpr,
            fieldValueVar,
            variableSuffix,
            indent,
            allowDirectRead);
        sb.AppendLine(
            $"{indent}{member.AccessorProviderTypeName}.{member.SetterAccessorName}({member.AccessReceiver(valueVar)}, {fieldValueVar});");
    }

    private static void EmitReadMemberAssignmentCore(
        StringBuilder sb,
        MemberModel member,
        string refModeExpr,
        string readTypeInfoExpr,
        string assignmentTarget,
        string variableSuffix,
        string indent,
        bool allowDirectRead)
    {
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
                    $"{indent}{assignmentTarget} = __ForyCompatibleFieldReaders.Read{member.CodeKey}FieldBridge(context, remoteField.FieldType, {refModeExpr});");
            }
            else
            {
                sb.AppendLine(
                    $"{indent}{assignmentTarget} = __ForyRead{member.CodeKey}Field(context, {refModeExpr});");
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

        if (readTypeInfoExpr == "false" &&
            CanReadNested(member) &&
            !member.IsNullable &&
            (member.Classification.IsBuiltIn || !member.IsRefType))
        {
            // The field has no envelope or type metadata, so guard only the materialized body.
            // This preserves nested-depth accounting without the general ref/type dispatcher.
            sb.AppendLine(
                $"{indent}{assignmentTarget} = context.TypeResolver.ReadNestedData<{member.TypeName}>(context);");
            return;
        }

        if (CanReadInlineValueData(member))
        {
            EmitInlineValueDataRead(sb, member, assignmentTarget, readTypeInfoExpr, indent);
            return;
        }

        if (variableSuffix == "Compat")
        {
            string compatibleReadExpr = CanReadNested(member)
                ? $"context.TypeResolver.ReadNested<{member.TypeName}>(context, {refModeExpr}, {readTypeInfoExpr})"
                : $"context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, {readTypeInfoExpr})";
            sb.AppendLine(
                $"{indent}{assignmentTarget} = {compatibleReadExpr};");
            return;
        }

        string readExpr = CanReadNested(member)
            ? $"context.TypeResolver.ReadNested<{member.TypeName}>(context, {refModeExpr}, {readTypeInfoExpr})"
            : $"context.TypeResolver.GetSerializer<{member.TypeName}>().Read(context, {refModeExpr}, {readTypeInfoExpr})";
        sb.AppendLine(
            $"{indent}{assignmentTarget} = {readExpr};");
    }

    private static void EmitInlineValueDataRead(
        StringBuilder sb,
        MemberModel member,
        string assignmentTarget,
        string readTypeInfoExpr,
        string indent)
    {
        if (readTypeInfoExpr == "false")
        {
            string readExpr = DeclaredTypeMayRecurse(member)
                ? $"context.TypeResolver.ReadNestedData<{member.TypeName}>(context)"
                : $"context.TypeResolver.GetSerializer<{member.TypeName}>().ReadData(context)";
            sb.AppendLine($"{indent}{assignmentTarget} = {readExpr};");
            return;
        }

        string serializerVar = $"__fory{member.CodeKey}Serializer";
        sb.AppendLine(
            $"{indent}global::Apache.Fory.Serializer<{member.TypeName}> {serializerVar} = context.TypeResolver.GetSerializer<{member.TypeName}>();");
        if (readTypeInfoExpr == "true")
        {
            sb.AppendLine($"{indent}context.TypeResolver.ReadTypeInfo({serializerVar}, context);");
        }
        else
        {
            sb.AppendLine($"{indent}if ({readTypeInfoExpr})");
            sb.AppendLine($"{indent}{{");
            sb.AppendLine($"{indent}  context.TypeResolver.ReadTypeInfo({serializerVar}, context);");
            sb.AppendLine($"{indent}}}");
        }

        string dataReadExpr = DeclaredTypeMayRecurse(member)
            ? $"context.TypeResolver.ReadNestedData({serializerVar}, context)"
            : $"{serializerVar}.ReadData(context)";
        sb.AppendLine($"{indent}{assignmentTarget} = {dataReadExpr};");
    }

    private static bool CanReadNested(MemberModel member)
    {
        // DynamicAny resolves its envelope before TypeResolver applies the existing depth guard.
        // Known acyclic generated types have a compile-time finite owner depth. Keep the runtime
        // guard for recursive graphs and for unknown serializers whose recursion cannot be proven.
        return member.DynamicAnyKind == DynamicAnyKind.None &&
               (member.Classification.TypeId is >= 22 and <= 24 or >= 27 and <= 35) &&
               DeclaredTypeMayRecurse(member);
    }

    private static bool DeclaredTypeMayRecurse(MemberModel member)
    {
        if (member.MemberType is null)
        {
            return true;
        }

        HashSet<ITypeSymbol> active = new(RuntimeTypeComparer.Instance);
        HashSet<ITypeSymbol> complete = new(RuntimeTypeComparer.Instance);
        return TypeMayRecurse(member.MemberType, active, complete);
    }

    private static bool TypeMayRecurse(
        ITypeSymbol type,
        HashSet<ITypeSymbol> active,
        HashSet<ITypeSymbol> complete)
    {
        (_, ITypeSymbol unwrapped) = UnwrapNullable(type);
        if (unwrapped.SpecialType == SpecialType.System_Object ||
            unwrapped.TypeKind is TypeKind.Dynamic or TypeKind.TypeParameter)
        {
            return true;
        }

        if (TryGetListElementType(unwrapped, out ITypeSymbol? listElement))
        {
            return TypeMayRecurse(listElement!, active, complete);
        }

        if (TryGetSetElementType(unwrapped, out ITypeSymbol? setElement))
        {
            return TypeMayRecurse(setElement!, active, complete);
        }

        if (TryGetMapTypeArguments(
                unwrapped,
                out ITypeSymbol? keyType,
                out ITypeSymbol? valueType))
        {
            return TypeMayRecurse(keyType!, active, complete) ||
                   TypeMayRecurse(valueType!, active, complete);
        }

        if (unwrapped.TypeKind == TypeKind.Enum)
        {
            return false;
        }

        TypeClassification classification = ClassifyType(unwrapped);
        if (classification.IsBuiltIn)
        {
            return false;
        }

        if (unwrapped is not INamedTypeSymbol namedType)
        {
            return true;
        }

        ForyAttributeKind attributeKind = GetForyAttributeKind(namedType);
        if (attributeKind == ForyAttributeKind.Enum)
        {
            return false;
        }

        // Union case selection and runtime serializer registration can introduce recursive owners;
        // keep the conservative guard unless a concrete generated struct graph proves acyclic.
        if (attributeKind != ForyAttributeKind.Struct)
        {
            return true;
        }

        if (complete.Contains(namedType))
        {
            return false;
        }

        if (!active.Add(namedType))
        {
            return true;
        }

        for (INamedTypeSymbol? current = namedType;
             current is not null && current.SpecialType != SpecialType.System_Object;
             current = current.BaseType)
        {
            if (!SymbolEqualityComparer.Default.Equals(current, namedType) &&
                GetForyAttributeKind(current) != ForyAttributeKind.Struct)
            {
                active.Remove(namedType);
                return true;
            }

            foreach (ISymbol declaredMember in current.GetMembers())
            {
                if (declaredMember.IsImplicitlyDeclared ||
                    declaredMember.IsStatic ||
                    TryGetIgnoredField(declaredMember, out _))
                {
                    continue;
                }

                ITypeSymbol? memberType = declaredMember switch
                {
                    IFieldSymbol field => field.Type,
                    IPropertySymbol property when !property.IsIndexer => property.Type,
                    _ => null,
                };
                if (memberType is not null && TypeMayRecurse(memberType, active, complete))
                {
                    active.Remove(namedType);
                    return true;
                }
            }
        }

        active.Remove(namedType);
        complete.Add(namedType);
        return false;
    }

    private static bool CompatibleCaseNeedsRemoteRefMode(MemberModel member)
    {
        return !IsCompatibleScalarMember(member);
    }

    private static bool CanReadInlineValueData(MemberModel member)
    {
        if (member.IsNullable ||
            member.DynamicAnyKind != DynamicAnyKind.None ||
            member.FieldCodec is not null ||
            member.IsRefType ||
            member.IsCollection ||
            member.Classification.IsMap)
        {
            return false;
        }

        return !member.Classification.IsBuiltIn;
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

        string refFlagVar = $"__{member.CodeKey}RefFlag{variableSuffix}";
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

    private static bool CanBranchTrackRefData(MemberModel member)
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
            .ThenBy(m => m.FieldIdentifier, StringComparer.Ordinal);

        StringBuilder sb = new();
        bool first = true;
        foreach (MemberModel member in ordered)
        {
            string piece =
                $"\"{EscapeString(BuildSchemaFieldIdentifier(member))},\" + {BuildSchemaFieldTypeFingerprint(member.TypeMeta, "trackRef", includeNullable: true)} + \";\"";
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

    private static string BuildSchemaFieldTypeFingerprint(
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
            string child = BuildSchemaFieldTypeFingerprint(model.Generics[0], "false", includeNullable: false);
            return $"{prefix} + \"[\" + {child} + \"]\"";
        }

        if (model.Generics.Length == 2)
        {
            string key = BuildSchemaFieldTypeFingerprint(model.Generics[0], "false", includeNullable: false);
            string value = BuildSchemaFieldTypeFingerprint(model.Generics[1], "false", includeNullable: false);
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
}
