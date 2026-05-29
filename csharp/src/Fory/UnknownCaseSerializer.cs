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

using System.Runtime.CompilerServices;

namespace Apache.Fory;

public static class UnknownCaseSerializer
{
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static void WritePayload(WriteContext context, UnknownCase unknownCase)
    {
        // Wire order is ref metadata first, then Any type metadata, then value bytes. Numeric
        // Any payloads are scalar values, so replay writes NotNullValue plus the original type id.
        if (WriteTypedPayload(context, (TypeId)unknownCase.TypeId, unknownCase.Value))
        {
            return;
        }

        DynamicAnyCodec.WriteAny(context, unknownCase.Value, RefMode.Tracking, true, false);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    public static UnknownCase ReadPayload(ReadContext context, int caseId)
    {
        RefFlag flag = context.RefReader.ReadRefFlag(context.Reader);
        switch (flag)
        {
            case RefFlag.Null:
                return UnknownCase.FromRuntime(caseId, (uint)TypeId.Unknown, null);
            case RefFlag.Ref:
                {
                    uint refId = context.RefReader.ReadRefId(context.Reader);
                    return UnknownCase.FromRuntime(caseId, (uint)TypeId.Unknown, context.RefReader.GetRefValue(refId));
                }
            case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.SetReservedRefId(reservedRefId);
                    try
                    {
                        (uint typeId, object? value) = ReadNonNullPayload(context);
                        context.StoreRef(value);
                        return UnknownCase.FromRuntime(caseId, typeId, value);
                    }
                    finally
                    {
                        context.ClearReservedRefId();
                    }
                }
            case RefFlag.NotNullValue:
                {
                    (uint typeId, object? value) = ReadNonNullPayload(context);
                    return UnknownCase.FromRuntime(caseId, typeId, value);
                }
            default:
                throw new RefException($"invalid ref flag {(sbyte)flag}");
        }
    }

    private static bool WriteTypedPayload(WriteContext context, TypeId typeId, object? value)
    {
        if (value is null)
        {
            return false;
        }

        switch (typeId)
        {
            case TypeId.Bool when value is bool typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteUInt8(typed ? (byte)1 : (byte)0);
                return true;
            case TypeId.Int8 when value is sbyte typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteInt8(typed);
                return true;
            case TypeId.UInt8 when value is byte typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteUInt8(typed);
                return true;
            case TypeId.Int16 when value is short typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteInt16(typed);
                return true;
            case TypeId.UInt16 when value is ushort typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteUInt16(typed);
                return true;
            case TypeId.Int32 when value is int typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteInt32(typed);
                return true;
            case TypeId.VarInt32 when value is int typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteVarInt32(typed);
                return true;
            case TypeId.UInt32 when value is uint typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteUInt32(typed);
                return true;
            case TypeId.VarUInt32 when value is uint typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteVarUInt32(typed);
                return true;
            case TypeId.Int64 when value is long typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteInt64(typed);
                return true;
            case TypeId.VarInt64 when value is long typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteVarInt64(typed);
                return true;
            case TypeId.TaggedInt64 when value is long typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteTaggedInt64(typed);
                return true;
            case TypeId.UInt64 when value is ulong typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteUInt64(typed);
                return true;
            case TypeId.VarUInt64 when value is ulong typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteVarUInt64(typed);
                return true;
            case TypeId.TaggedUInt64 when value is ulong typed:
                WriteRefAndType(context, typeId);
                context.Writer.WriteTaggedUInt64(typed);
                return true;
            default:
                return false;
        }
    }

    private static void WriteRefAndType(WriteContext context, TypeId typeId)
    {
        context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
        context.Writer.WriteUInt8((byte)typeId);
    }

    private static (uint TypeId, object? Value) ReadNonNullPayload(ReadContext context)
    {
        // UnknownCase owns the union payload envelope only. The envelope is not
        // a nested dynamic value, so depth checks belong to the decoded payload
        // serializer or the final root-context reset, not this carrier reader.
        TypeInfo typeInfo = context.TypeResolver.ReadAnyTypeInfo(context);
        object? value = context.TypeResolver.ReadAnyValue(typeInfo, context);
        return ((uint)(typeInfo.WireTypeId ?? typeInfo.BuiltInTypeId ?? TypeId.Unknown), value);
    }
}
