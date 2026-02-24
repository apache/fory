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

namespace Apache.Fory;

public sealed class DynamicAnyObjectSerializer : Serializer<object?>
{
    public override TypeId StaticTypeId => TypeId.Unknown;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override object? DefaultValue => null;
    public override bool IsNone(in object? value) => value is null;

    public override void WriteData(WriteContext context, in object? value, bool hasGenerics)
    {
        if (IsNone(value))
        {
            return;
        }

        DynamicAnyCodec.WriteAnyPayload(value!, context, hasGenerics);
    }

    public override object? ReadData(ReadContext context)
    {
        DynamicTypeInfo? dynamicTypeInfo = context.DynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo is null)
        {
            throw new InvalidDataException("dynamic Any value requires type info");
        }

        return context.TypeResolver.ReadDynamicValue(dynamicTypeInfo, context);
    }

    public override void WriteTypeInfo(WriteContext context)
    {
        throw new InvalidDataException("dynamic Any value type info is runtime-only");
    }

    public override void ReadTypeInfo(ReadContext context)
    {
        DynamicTypeInfo typeInfo = context.TypeResolver.ReadDynamicTypeInfo(context);
        context.SetDynamicTypeInfo(typeof(object), typeInfo);
    }

    public override void Write(WriteContext context, in object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            if (IsNone(value))
            {
                context.Writer.WriteInt8((sbyte)RefFlag.Null);
                return;
            }

            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking && AnyValueIsReferenceTrackable(value!, context.TypeResolver))
            {
                if (context.RefWriter.TryWriteReference(context.Writer, value!))
                {
                    return;
                }

                wroteTrackingRefFlag = true;
            }

            if (!wroteTrackingRefFlag)
            {
                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            DynamicAnyCodec.WriteAnyTypeInfo(value!, context);
        }

        WriteData(context, value, hasGenerics);
    }

    public override object? Read(ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return null;
                case RefFlag.Ref:
                {
                    uint refId = context.Reader.ReadVarUInt32();
                    return context.RefReader.ReadRefValue(refId);
                }
                case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.RefReader.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        ReadTypeInfo(context);
                    }

                    object? value = ReadData(context);
                    if (readTypeInfo)
                    {
                        context.ClearDynamicTypeInfo(typeof(object));
                    }

                    context.RefReader.FinishPendingReferenceIfNeeded(value);
                    context.RefReader.PopPendingReference();
                    return value;
                }
                case RefFlag.NotNullValue:
                    break;
                default:
                    throw new RefException($"invalid ref flag {rawFlag}");
            }
        }

        if (readTypeInfo)
        {
            ReadTypeInfo(context);
        }

        object? result = ReadData(context);
        if (readTypeInfo)
        {
            context.ClearDynamicTypeInfo(typeof(object));
        }

        return result;
    }

    private static bool AnyValueIsReferenceTrackable(object value, TypeResolver typeResolver)
    {
        TypeInfo typeInfo = typeResolver.GetTypeInfo(value.GetType());
        return typeInfo.IsReferenceTrackableType;
    }
}

public static class DynamicAnyCodec
{
    internal static void WriteAnyTypeInfo(object value, WriteContext context)
    {
        if (DynamicContainerCodec.TryGetTypeId(value, out TypeId containerTypeId))
        {
            context.Writer.WriteUInt8((byte)containerTypeId);
            return;
        }

        if (TryWriteKnownTypeInfo(value, context))
        {
            return;
        }

        TypeInfo typeInfo = context.TypeResolver.GetTypeInfo(value.GetType());
        typeInfo.WriteTypeInfo(context);
    }

    public static object? CastAnyDynamicValue(object? value, Type targetType)
    {
        if (value is null)
        {
            if (targetType == typeof(object))
            {
                return null;
            }

            if (!targetType.IsValueType || Nullable.GetUnderlyingType(targetType) is not null)
            {
                return null;
            }

            throw new InvalidDataException($"cannot cast null dynamic Any value to non-nullable {targetType}");
        }

        if (targetType.IsInstanceOfType(value))
        {
            return value;
        }

        throw new InvalidDataException($"cannot cast dynamic Any value to {targetType}");
    }

    public static void WriteAny(WriteContext context, object? value, RefMode refMode, bool writeTypeInfo = true, bool hasGenerics = false)
    {
        context.TypeResolver.GetSerializer<object?>().Write(context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadAny(ReadContext context, RefMode refMode, bool readTypeInfo = true)
    {
        return context.TypeResolver.GetSerializer<object?>().Read(context, refMode, readTypeInfo);
    }

    public static void WriteAnyPayload(object value, WriteContext context, bool hasGenerics)
    {
        if (DynamicContainerCodec.TryWritePayload(value, context, hasGenerics))
        {
            return;
        }

        if (TryWriteKnownPayload(value, context))
        {
            return;
        }

        TypeInfo typeInfo = context.TypeResolver.GetTypeInfo(value.GetType());
        typeInfo.WriteDataObject(context, value, hasGenerics);
    }

    private static bool TryWriteKnownTypeInfo(object value, WriteContext context)
    {
        switch (value)
        {
            case bool:
                context.Writer.WriteUInt8((byte)TypeId.Bool);
                return true;
            case sbyte:
                context.Writer.WriteUInt8((byte)TypeId.Int8);
                return true;
            case short:
                context.Writer.WriteUInt8((byte)TypeId.Int16);
                return true;
            case int:
                context.Writer.WriteUInt8((byte)TypeId.VarInt32);
                return true;
            case long:
                context.Writer.WriteUInt8((byte)TypeId.VarInt64);
                return true;
            case byte:
                context.Writer.WriteUInt8((byte)TypeId.UInt8);
                return true;
            case ushort:
                context.Writer.WriteUInt8((byte)TypeId.UInt16);
                return true;
            case uint:
                context.Writer.WriteUInt8((byte)TypeId.VarUInt32);
                return true;
            case ulong:
                context.Writer.WriteUInt8((byte)TypeId.VarUInt64);
                return true;
            case float:
                context.Writer.WriteUInt8((byte)TypeId.Float32);
                return true;
            case double:
                context.Writer.WriteUInt8((byte)TypeId.Float64);
                return true;
            case string:
                context.Writer.WriteUInt8((byte)TypeId.String);
                return true;
            case byte[]:
                context.Writer.WriteUInt8((byte)TypeId.Binary);
                return true;
            case bool[]:
                context.Writer.WriteUInt8((byte)TypeId.BoolArray);
                return true;
            case sbyte[]:
                context.Writer.WriteUInt8((byte)TypeId.Int8Array);
                return true;
            case short[]:
                context.Writer.WriteUInt8((byte)TypeId.Int16Array);
                return true;
            case int[]:
                context.Writer.WriteUInt8((byte)TypeId.Int32Array);
                return true;
            case long[]:
                context.Writer.WriteUInt8((byte)TypeId.Int64Array);
                return true;
            case ushort[]:
                context.Writer.WriteUInt8((byte)TypeId.UInt16Array);
                return true;
            case uint[]:
                context.Writer.WriteUInt8((byte)TypeId.UInt32Array);
                return true;
            case ulong[]:
                context.Writer.WriteUInt8((byte)TypeId.UInt64Array);
                return true;
            case float[]:
                context.Writer.WriteUInt8((byte)TypeId.Float32Array);
                return true;
            case double[]:
                context.Writer.WriteUInt8((byte)TypeId.Float64Array);
                return true;
            case DateOnly:
                context.Writer.WriteUInt8((byte)TypeId.Date);
                return true;
            case DateTimeOffset:
            case DateTime:
                context.Writer.WriteUInt8((byte)TypeId.Timestamp);
                return true;
            case TimeSpan:
                context.Writer.WriteUInt8((byte)TypeId.Duration);
                return true;
            default:
                return false;
        }
    }

    private static bool TryWriteKnownPayload(object value, WriteContext context)
    {
        switch (value)
        {
            case bool v:
                context.Writer.WriteUInt8(v ? (byte)1 : (byte)0);
                return true;
            case sbyte v:
                context.Writer.WriteInt8(v);
                return true;
            case short v:
                context.Writer.WriteInt16(v);
                return true;
            case int v:
                context.Writer.WriteVarInt32(v);
                return true;
            case long v:
                context.Writer.WriteVarInt64(v);
                return true;
            case byte v:
                context.Writer.WriteUInt8(v);
                return true;
            case ushort v:
                context.Writer.WriteUInt16(v);
                return true;
            case uint v:
                context.Writer.WriteVarUInt32(v);
                return true;
            case ulong v:
                context.Writer.WriteVarUInt64(v);
                return true;
            case float v:
                context.Writer.WriteFloat32(v);
                return true;
            case double v:
                context.Writer.WriteFloat64(v);
                return true;
            case string v:
                StringSerializer.WriteString(context, v);
                return true;
            case DateOnly v:
                TimeCodec.WriteDate(context, v);
                return true;
            case DateTimeOffset v:
                TimeCodec.WriteTimestamp(context, v);
                return true;
            case DateTime v:
                TimeCodec.WriteTimestamp(context, TimeCodec.ToDateTimeOffset(v));
                return true;
            case TimeSpan v:
                TimeCodec.WriteDuration(context, v);
                return true;
            case byte[] v:
                context.Writer.WriteVarUInt32((uint)v.Length);
                context.Writer.WriteBytes(v);
                return true;
            case bool[] v:
                context.Writer.WriteVarUInt32((uint)v.Length);
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteUInt8(v[i] ? (byte)1 : (byte)0);
                }
                return true;
            case sbyte[] v:
                context.Writer.WriteVarUInt32((uint)v.Length);
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteInt8(v[i]);
                }
                return true;
            case short[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 2));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteInt16(v[i]);
                }
                return true;
            case int[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 4));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteInt32(v[i]);
                }
                return true;
            case long[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 8));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteInt64(v[i]);
                }
                return true;
            case ushort[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 2));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteUInt16(v[i]);
                }
                return true;
            case uint[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 4));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteUInt32(v[i]);
                }
                return true;
            case ulong[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 8));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteUInt64(v[i]);
                }
                return true;
            case float[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 4));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteFloat32(v[i]);
                }
                return true;
            case double[] v:
                context.Writer.WriteVarUInt32((uint)(v.Length * 8));
                for (int i = 0; i < v.Length; i++)
                {
                    context.Writer.WriteFloat64(v[i]);
                }
                return true;
            default:
                return false;
        }
    }
}
